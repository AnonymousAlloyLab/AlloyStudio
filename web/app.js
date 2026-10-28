import { createTutorChat } from './tutor-chat.js';
import { renderInstanceGraph } from './instance-graph.js';
import { renderAlloyCode, enterIndent, closingIndent, indentAlloy } from './alloy-language.js';

// BEGIN LEAN POLICY KERNEL
const LEAN_POLICIES = {"feedbackSuccess":{"acceptedMasks":[1023],"arity":10},"guidanceSuccess":{"acceptedMasks":[8191],"arity":13},"poolChoose":{"acceptedMasks":[0,2,3],"arity":2},"poolFinish":{"acceptedMasks":[3],"arity":2}};
function verifiedPolicy(name, atoms) {
  if (!Object.hasOwn(LEAN_POLICIES, name)) return false;
  const policy = LEAN_POLICIES[name];
  if (!Array.isArray(atoms) || atoms.length !== policy.arity) return false;
  let mask = 0;
  for (let index = 0; index < atoms.length; index += 1) {
    if (typeof atoms[index] !== 'boolean') return false;
    if (atoms[index]) mask |= (1 << index);
  }
  return policy.acceptedMasks.includes(mask);
}
// END LEAN POLICY KERNEL

const $ = (selector) => document.querySelector(selector);
const elements = {
  search: $('#exercise-search'), group: $('#group-filter'), list: $('#exercise-list'),
  previousExercise: $('#previous-exercise'), nextExercise: $('#next-exercise'), exercisePosition: $('#exercise-position'),
  editor: $('#predicate-editor'), check: $('#check-button'), reset: $('#reset-button'),
  download: $('#download-button'), live: $('#live-feedback'), result: $('#feedback-result'),
  status: $('#feedback-state'), lines: $('#line-numbers'), draft: $('#draft-status'),
  highlight: $('#source-highlight'), locationBar: $('#source-location-bar'), locationStatus: $('#source-location-status'),
  canonical: $('#canonical-content'), canonicalStatus: $('#canonical-location-status'),
  behavior: $('#behavior-result'), behaviorStatus: $('#behavior-state'),
  metric: $('#distance-metric'),
};
const state = {
  exercises: [], exercise: null, loadingExercise: false, revision: 0, selection: 0, metric: 'canonical',
  feedbackAbort: null, explainAbort: null, behaviorAbort: null, detailAbort: null, timer: null,
  history: [], lastHistoryBody: null, feedbackStatus: 'waiting', storageAvailable: true,
  sourceHighlight: null, canonical: null, education: null, behaviorEvidence: null, progressiveHints: null,
  solved: new Map(), structuralCompletionEvidence: null,
  completedBodies: new Map(), savedRecords: new Map(), validCompletionTokens: new Set(),
  memoryDrafts: new Map(), resetRecovery: null,
  progressRestore: null, progressEpoch: 0,
  progressSavedAt: 0, chatEvidence: null,
  channel: null, channelPromise: null, checkFlight: null,
};
const STORAGE_PREFIX = 'alloy-studio:v1:';
const MAX_SOLVED_EXERCISES = 1000;
const MAX_PROGRESS_RECORDS = 200;
const MAX_PROGRESS_BYTES = 4 * 1024 * 1024;
const BODY_BYTES = 8192;
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const VERSION_PATTERN = /^[a-f0-9]{64}$/;
const APP_BASE = new URL('.', import.meta.url);
const METRICS = {
  canonical: { id: 'acgn-fast-rewrite-canonical-distance', basis: 'nearest-known-correct-v1', label: 'Canonical' },
  ast: { id: 'acgn-raw-ast-zhang-shasha-distance', basis: 'nearest-known-correct-raw-ast-v1', label: 'AST' },
};

const tutorChat = createTutorChat({
  baseURL: APP_BASE,
  ensureChannel,
  clearChannel(channel) {
    if (state.channel === channel) { state.channel = null; state.chatEvidence = null; }
  },
  getContext() {
    if (!state.exercise || state.loadingExercise || elements.editor.disabled) return null;
    const tokens = prerequisiteTokens(state.exercise);
    if (tokens === null) return null;
    const context = { exerciseId: state.exercise.id, revision: state.revision,
      body: elements.editor.value, metric: state.metric, prerequisiteTokens: tokens };
    const evidence = state.chatEvidence;
    const matched = evidence && sourceContextCurrent(evidence.context);
    if (matched && evidence.evidenceToken) context.evidenceToken = evidence.evidenceToken;
    if (matched && evidence.behaviorToken) context.behaviorToken = evidence.behaviorToken;
    return { payload: context, title: state.exercise.title,
      contentVersion: exerciseVersion(state.exercise), selection: state.selection,
      hasHints: !!context.evidenceToken, hasExamples: !!context.behaviorToken };
  },
});

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = String(text);
  return element;
}

function readStorage(key, fallback) {
  try {
    const raw = localStorage.getItem(STORAGE_PREFIX + key);
    return raw === null ? fallback : JSON.parse(raw);
  } catch { state.storageAvailable = false; return fallback; }
}

function writeStorage(key, value) {
  try { localStorage.setItem(STORAGE_PREFIX + key, JSON.stringify(value)); return true; }
  catch { state.storageAvailable = false; return false; }
}

function progressStatus(message) { $('#progress-status').textContent = message; }

function validToken(value) { return typeof value === 'string' && TOKEN_PATTERN.test(value); }
function validVersion(value) { return typeof value === 'string' && VERSION_PATTERN.test(value); }

function strictObject(value, keys, required = keys) {
  return value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).every(key => keys.includes(key) && !['__proto__', 'constructor', 'prototype'].includes(key))
    && required.every(key => Object.hasOwn(value, key));
}

function progressBody(value) {
  if (typeof value !== 'string' || value.length > BODY_BYTES || value.includes('\0')) return false;
  for (let index = 0; index < value.length; index += 1) {
    const unit = value.charCodeAt(index);
    if (unit >= 0xD800 && unit <= 0xDBFF) {
      const next = value.charCodeAt(++index);
      if (!(next >= 0xDC00 && next <= 0xDFFF)) return false;
    } else if (unit >= 0xDC00 && unit <= 0xDFFF) return false;
  }
  return new TextEncoder().encode(value).length <= BODY_BYTES;
}

async function bodySha256(body) {
  if (!globalThis.crypto?.subtle) throw new Error('Saved checks require HTTPS or localhost. Saved code remains available.');
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(body));
  return [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('');
}

function tokenList(value, cap = 99) {
  return Array.isArray(value) && value.length <= cap && new Set(value).size === value.length && value.every(token => validToken(token));
}

function parseProgress(text) {
  if (typeof text !== 'string' || new TextEncoder().encode(text).length > MAX_PROGRESS_BYTES) throw new Error('Progress files must be at most 4 MiB.');
  let value;
  try { value = JSON.parse(text); } catch { throw new Error('This is not a readable progress file.'); }
  if (Array.isArray(value?.records) && value.records.length > MAX_PROGRESS_RECORDS) {
    throw new Error('Progress supports at most 200 exercise-code records. No learner code was omitted.');
  }
  const topKeys = ['format', 'version', 'savedAt', 'selectedExerciseId', 'metric', 'live', 'records', 'solved'];
  const timestamp = at => Number.isSafeInteger(at) && at > 0;
  const identity = id => typeof id === 'string' && id.length > 0 && id.length <= 256 && !['__proto__', 'constructor', 'prototype'].includes(id);
  if (!strictObject(value, topKeys) || value.format !== 'alloy-studio-progress' || value.version !== 1
    || !timestamp(value.savedAt) || (value.selectedExerciseId !== null && !identity(value.selectedExerciseId))
    || !Object.hasOwn(METRICS, value.metric) || typeof value.live !== 'boolean'
    || !Array.isArray(value.records) || value.records.length > MAX_PROGRESS_RECORDS
    || !Array.isArray(value.solved) || value.solved.length > MAX_SOLVED_EXERCISES) throw new Error('This progress file has an unsupported or malformed format.');
  const seen = new Set();
  for (const record of value.records) {
    if (!strictObject(record, ['id', 'contentVersion', 'sourceSha256', 'draft', 'completed', 'history'], ['id', 'contentVersion', 'sourceSha256', 'history'])
      || !identity(record.id) || seen.has(record.id) || !validVersion(record.contentVersion)
      || !validVersion(record.sourceSha256)
      || (!Object.hasOwn(record, 'draft') && !Object.hasOwn(record, 'completed'))
      || !strictObject(record.history, ['canonical', 'ast'])) throw new Error('This progress file contains an invalid exercise record.');
    seen.add(record.id);
    if (Object.hasOwn(record, 'draft') && (!strictObject(record.draft, ['body', 'updatedAt'])
      || !progressBody(record.draft.body) || !timestamp(record.draft.updatedAt))) throw new Error('A saved draft is malformed or exceeds 8 KiB.');
    if (Object.hasOwn(record, 'completed')) {
      const completed = record.completed;
      if (!strictObject(completed, ['body', 'metric', 'at', 'completionToken', 'dependencyContextVersion', 'prerequisiteTokens'], ['body', 'metric', 'at'])
        || !progressBody(completed.body) || !timestamp(completed.at) || !Object.hasOwn(METRICS, completed.metric)
        || (Object.hasOwn(completed, 'completionToken') && !validToken(completed.completionToken))
        || (Object.hasOwn(completed, 'dependencyContextVersion') && !validVersion(completed.dependencyContextVersion))
        || (Object.hasOwn(completed, 'prerequisiteTokens') && !tokenList(completed.prerequisiteTokens))) throw new Error('A saved implementation is malformed.');
    }
    for (const metric of ['canonical', 'ast']) {
      const points = record.history[metric];
      if (!Array.isArray(points) || points.length > 12 || !points.every(point => strictObject(point, ['distance', 'at', 'basis'])
        && Number.isFinite(point.distance) && point.distance >= 0 && timestamp(point.at)
        && point.basis === METRICS[metric].basis)) throw new Error('A saved history is malformed.');
    }
  }
  const solvedSeen = new Set();
  for (const record of value.solved) {
    if (!strictObject(record, ['id', 'version', 'at', 'metric']) || !identity(record.id) || solvedSeen.has(record.id)
      || !validVersion(record.version) || !timestamp(record.at) || !Object.hasOwn(METRICS, record.metric)) throw new Error('Saved completion history is malformed.');
    solvedSeen.add(record.id);
  }
  return value;
}

function dependencyList(exercise) {
  if (exercise?.dependencies === undefined) return [];
  if (!Array.isArray(exercise.dependencies) || exercise.dependencies.length > 99) return null;
  const seen = new Set();
  if (!exercise.dependencies.every(dependency => strictObject(dependency, ['id', 'predicate', 'contentVersion'])
    && typeof dependency.id === 'string' && dependency.id !== exercise.id && !seen.has(dependency.id) && seen.add(dependency.id)
    && typeof dependency.predicate === 'string' && validVersion(dependency.contentVersion))) return null;
  return exercise.dependencies;
}

function prerequisiteTokens(exercise) {
  const dependencies = dependencyList(exercise);
  if (dependencies === null) return null;
  const tokens = [];
  for (const dependency of dependencies) {
    const saved = state.completedBodies.get(dependency.id);
    if (!saved || saved.contentVersion !== dependency.contentVersion || !validToken(saved.completionToken)
      || !state.validCompletionTokens.has(saved.completionToken)) return null;
    tokens.push(saved.completionToken);
  }
  return tokens;
}

function lockedExercise(exercise) { return prerequisiteTokens(exercise) === null; }

function rejectPrerequisites(result, tokens) {
  tokens.forEach(token => state.validCompletionTokens.delete(token));
  if (Array.isArray(result.required) && dependencyList({ ...state.exercise, dependencies: result.required }) !== null) {
    state.exercise.dependencies = result.required;
    const summary = state.exercises.find(item => item.id === state.exercise.id);
    if (summary) summary.dependencies = result.required;
  }
  applyPracticeLock(); renderExercises();
}

function dependencyClosure(exercise, found = new Set(), visiting = new Set()) {
  const dependencies = dependencyList(exercise);
  if (dependencies === null || visiting.has(exercise.id)) throw new Error('This exercise has invalid prerequisite metadata.');
  visiting.add(exercise.id);
  for (const dependency of dependencies) {
    const record = state.exercises.find(item => item.id === dependency.id);
    if (!record || exerciseVersion(record) !== dependency.contentVersion) throw new Error('A prerequisite has changed or is unavailable.');
    dependencyClosure(record, found, visiting); found.add(dependency.id);
  }
  visiting.delete(exercise.id);
  return found;
}

function resolvedEnvironment(exercise) {
  const environment = { environmentBefore: exercise.environmentBefore || '', environmentAfter: exercise.environmentAfter || '' };
  if (!exercise.dependencySlots?.length) return environment;
  if (!Array.isArray(exercise.dependencySlots) || exercise.dependencySlots.length > 100) throw new Error('This exercise has invalid prerequisite slots.');
  const closure = dependencyClosure(exercise);
  const intervals = { environmentBefore: [], environmentAfter: [] };
  for (const slot of exercise.dependencySlots) {
    if (!strictObject(slot, ['id', 'part', 'start', 'end']) || !Object.hasOwn(intervals, slot.part)
      || !Number.isSafeInteger(slot.start) || !Number.isSafeInteger(slot.end) || slot.start < 0 || slot.end < slot.start
      || slot.end > environment[slot.part].length) throw new Error('This exercise has invalid prerequisite slots.');
    const text = environment[slot.part].slice(slot.start, slot.end);
    if (text !== '// Write your predicate here.') throw new Error('A prerequisite slot has changed. Reload the exercises.');
    intervals[slot.part].push(slot);
  }
  for (const part of Object.keys(intervals)) {
    const ordered = intervals[part].sort((left, right) => left.start - right.start);
    if (ordered.some((slot, index) => index > 0 && slot.start < ordered[index - 1].end)) throw new Error('Prerequisite slots overlap.');
    for (const slot of ordered.reverse()) {
      const saved = state.completedBodies.get(slot.id);
      if (closure.has(slot.id) && saved && state.validCompletionTokens.has(saved.completionToken)) {
        environment[part] = environment[part].slice(0, slot.start) + '\n' + saved.body + '\n' + environment[part].slice(slot.end);
      }
    }
  }
  return environment;
}

function cancelProgressRestore(message = 'Restore cancelled. Saved code is unchanged.') {
  state.progressEpoch += 1;
  if (!state.progressRestore) return;
  state.progressRestore.abort(); state.progressRestore = null;
  cancelChannel(++state.revision);
  $('#cancel-restore-progress').hidden = true;
  $('#restore-progress').disabled = false;
  progressStatus(message);
}

function applyPracticeLock() {
  if (!state.exercise || state.loadingExercise) return;
  let locked = lockedExercise(state.exercise), slotError = null;
  try { resolvedEnvironment(state.exercise); } catch (error) { locked = true; slotError = error.message; }
  elements.editor.disabled = locked;
  elements.check.disabled = locked; elements.reset.disabled = locked; elements.download.disabled = locked;
  $('#indent-button').disabled = locked;
  const panel = $('#practice-lock'); panel.hidden = !locked;
  if (locked) {
    const missing = (dependencyList(state.exercise) || []).filter(dependency => {
      const saved = state.completedBodies.get(dependency.id);
      return !saved || saved.contentVersion !== dependency.contentVersion || !state.validCompletionTokens.has(saved.completionToken);
    });
    panel.replaceChildren(node('span', '', slotError || `Complete and check ${missing.map(item => item.predicate).join(', ') || 'the prerequisites'} before working on this predicate.`));
    missing.forEach(dependency => {
      const button = node('button', 'button text-button', `Open ${dependency.predicate}`); button.type = 'button';
      button.addEventListener('click', () => selectExercise(dependency.id)); panel.append(button);
    });
    showWaiting('This practice is locked until its prerequisites are checked.');
  }
  $('#restore-progress').hidden = ![...state.completedBodies.values()].some(saved => !state.validCompletionTokens.has(saved.completionToken));
}

function buildProgress() {
  if (state.exercise && !state.loadingExercise && !progressBody(elements.editor.value)) {
    throw new Error('Progress was not saved: the current draft must be valid text of at most 8 KiB, without NUL characters. Your editor is unchanged.');
  }
  if (state.exercise && !state.loadingExercise && !saveDraft()) {
    throw new Error('Progress was not saved because the current draft cannot be retained. Your code stays in this editor; download the model before leaving.');
  }
  const records = [];
  for (const exercise of state.exercises) {
    const version = exerciseVersion(exercise);
    if (!version) continue;
    const saved = state.savedRecords.get(exercise.id);
    const stored = state.memoryDrafts.get(exercise.id) || readStorage(`draft:${exercise.id}`, null);
    const currentStored = stored && (!saved?.draft || stored.progressSavedAt === state.progressSavedAt);
    if (currentStored && (!stored.version || stored.version === version) && typeof stored.body === 'string' && !progressBody(stored.body)) {
      throw new Error(`Progress was not saved: the draft for ${exercise.predicate || exercise.id} must be valid text of at most 8 KiB, without NUL characters. No learner code was omitted.`);
    }
    const useStored = currentStored && progressBody(stored.body) && validVersion(stored.source)
      && (!stored.version || stored.version === version) && Number.isSafeInteger(stored.updatedAt) && stored.updatedAt > 0
      && (!saved?.draft || stored.progressSavedAt === state.progressSavedAt);
    const draft = useStored ? { body: stored.body, updatedAt: stored.updatedAt } : saved?.draft;
    const completed = state.completedBodies.get(exercise.id);
    const source = (useStored ? stored.source : saved?.sourceSha256) || completed?.sourceSha256;
    if ((!draft && !completed) || !validVersion(source)) continue;
    const history = Object.fromEntries(['canonical', 'ast'].map(metric => {
      const legacy = readStorage(`history:${exercise.id}${metric === 'canonical' ? '' : ':ast'}`, []);
      const stamp = readStorage(`history:${exercise.id}${metric === 'canonical' ? '' : ':ast'}:progress`, null);
      const raw = saved?.history?.[metric] && stamp !== state.progressSavedAt
        ? saved.history[metric] : legacy;
      const points = (Array.isArray(raw) ? raw : []).filter(point => strictObject(point, ['distance', 'at', 'basis'])
        && Number.isFinite(point.distance) && point.distance >= 0 && Number.isSafeInteger(point.at) && point.at > 0
        && point.basis === METRICS[metric].basis).slice(-12);
      return [metric, points];
    }));
    const record = { id: exercise.id, contentVersion: version, sourceSha256: source, ...(draft ? { draft } : {}), history };
    if (completed && completed.contentVersion === version) {
      record.completed = { body: completed.body, metric: completed.metric, at: completed.at,
        ...(completed.completionToken ? { completionToken: completed.completionToken } : {}),
        ...(completed.dependencyContextVersion ? { dependencyContextVersion: completed.dependencyContextVersion } : {}),
        ...(completed.prerequisiteTokens ? { prerequisiteTokens: completed.prerequisiteTokens } : {}) };
    }
    records.push(record);
  }
  const snapshot = { format: 'alloy-studio-progress', version: 1, savedAt: Math.max(Date.now(), state.progressSavedAt + 1),
    selectedExerciseId: state.exercise?.id || null, metric: state.metric, live: elements.live.checked, records,
    solved: [...state.solved].filter(([, record]) => validVersion(record.version))
      .slice(0, MAX_SOLVED_EXERCISES).map(([id, record]) => ({ id, ...record })) };
  parseProgress(JSON.stringify(snapshot));
  return snapshot;
}

function persistProgress() {
  try {
    const snapshot = buildProgress();
    if (!writeStorage('progress', snapshot)) {
      progressStatus('Browser storage is full or unavailable. Your progress is still in memory; use Export progress to keep a file.'); return false;
    }
    state.savedRecords = new Map(snapshot.records.map(record => [record.id, record]));
    state.progressSavedAt = snapshot.savedAt;
    state.memoryDrafts.clear();
    return true;
  } catch (error) { progressStatus(error.message); return false; }
}

function applyProgress(snapshot) {
  const versions = new Map(state.exercises.map(exercise => [exercise.id, exerciseVersion(exercise)]));
  const records = snapshot.records.filter(record => versions.get(record.id) === record.contentVersion);
  state.savedRecords = new Map(records.map(record => [record.id, record]));
  state.progressSavedAt = snapshot.savedAt;
  state.memoryDrafts.clear();
  state.completedBodies = new Map(records.filter(record => record.completed).map(record => [record.id,
    { ...record.completed, contentVersion: record.contentVersion, sourceSha256: record.sourceSha256 }]));
  state.validCompletionTokens.clear();
  state.solved = new Map(snapshot.solved.filter(record => versions.get(record.id) === record.version)
    .map(({ id, ...record }) => [id, record]));
  return snapshot.records.length - records.length;
}

async function validateCompletionTokens(signal = null) {
  const tokens = [...new Set([...state.completedBodies.values()].map(saved => saved.completionToken).filter(token => validToken(token)))];
  if (!tokens.length) return;
  const epoch = state.progressEpoch;
  const reply = await fetchJSON('api/progress', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ completionTokens: tokens }), ...(signal ? { signal } : {}) });
  if (epoch !== state.progressEpoch || signal?.aborted) return;
  if (!strictObject(reply, ['status', 'completions', 'expiredTokens']) || reply.status !== 'ok'
    || !Array.isArray(reply.completions) || reply.completions.length > tokens.length
    || !Array.isArray(reply.expiredTokens) || reply.expiredTokens.length > tokens.length) throw new Error('Saved checks could not be verified. Use Restore checks.');
  const requested = new Set(tokens), seen = new Set(), verified = new Map();
  for (const completion of reply.completions) {
    const saved = state.completedBodies.get(completion?.exerciseId);
    if (!strictObject(completion, ['exerciseId', 'contentVersion', 'completionToken', 'dependencyContextVersion', 'bodySha256', 'prerequisiteTokens'])
      || !saved || !requested.has(completion.completionToken) || seen.has(completion.completionToken)
      || saved.completionToken !== completion.completionToken || saved.contentVersion !== completion.contentVersion
      || !validVersion(completion.dependencyContextVersion)
      || saved.dependencyContextVersion !== completion.dependencyContextVersion
      || !validVersion(completion.bodySha256) || !tokenList(completion.prerequisiteTokens)) throw new Error('Saved checks could not be verified. Use Restore checks.');
    seen.add(completion.completionToken);
    if (await bodySha256(saved.body) === completion.bodySha256) verified.set(completion.exerciseId, completion);
    if (epoch !== state.progressEpoch || signal?.aborted) return;
  }
  for (const token of reply.expiredTokens) {
    if (!requested.has(token) || seen.has(token)) throw new Error('Saved checks could not be verified. Use Restore checks.');
    seen.add(token);
  }
  if (seen.size !== requested.size) throw new Error('Saved checks could not be verified. Use Restore checks.');
  const valid = new Set(), visited = new Set(), visiting = new Set();
  const visit = id => {
    if (visited.has(id)) return valid.has(state.completedBodies.get(id)?.completionToken);
    if (visiting.has(id)) return false;
    visiting.add(id);
    const completion = verified.get(id), exercise = state.exercises.find(item => item.id === id);
    const dependencies = dependencyList(exercise);
    const direct = dependencies?.map(dependency => state.completedBodies.get(dependency.id)?.completionToken);
    const okay = completion && direct && direct.length === completion.prerequisiteTokens.length
      && direct.every(token => completion.prerequisiteTokens.includes(token))
      && dependencies.every(dependency => visit(dependency.id));
    visiting.delete(id); visited.add(id);
    if (okay) {
      state.completedBodies.get(id).prerequisiteTokens = completion.prerequisiteTokens;
      valid.add(completion.completionToken);
    }
    return okay;
  };
  [...verified.keys()].forEach(visit);
  state.validCompletionTokens = valid;
}

function saveProgressFile() {
  try {
    const snapshot = buildProgress();
    const browserSaved = writeStorage('progress', snapshot);
    if (browserSaved) { state.savedRecords = new Map(snapshot.records.map(record => [record.id, record])); state.progressSavedAt = snapshot.savedAt; state.memoryDrafts.clear(); }
    const blob = new Blob([JSON.stringify(snapshot)], { type: 'application/json' });
    const url = URL.createObjectURL(blob), link = node('a');
    link.href = url; link.download = `alloy-studio-progress-${new Date().toISOString().replace(/[:.]/g, '-')}.json`;
    document.body.append(link); link.click(); link.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    progressStatus(browserSaved ? 'Progress saved in this browser and downloaded. Keep the file to continue elsewhere.'
      : 'Progress downloaded. Browser storage is full or unavailable; keep the downloaded file.');
  } catch (error) { progressStatus(error.message); }
}

async function loadProgressFile(file) {
  cancelProgressRestore('Loading saved progress…');
  const epoch = state.progressEpoch;
  try {
    if (!file || file.size > MAX_PROGRESS_BYTES) throw new Error('Progress files must be at most 4 MiB.');
    const snapshot = parseProgress(await file.text());
    if (epoch !== state.progressEpoch) return;
    const versions = new Map(state.exercises.map(exercise => [exercise.id, exerciseVersion(exercise)]));
    const filtered = { ...snapshot, savedAt: Math.max(Date.now(), state.progressSavedAt + 1), records: snapshot.records.filter(record => versions.get(record.id) === record.contentVersion),
      solved: snapshot.solved.filter(record => versions.get(record.id) === record.version) };
    const omitted = snapshot.records.length - filtered.records.length;
    if (!writeStorage('progress', filtered)) throw new Error('Progress was not loaded because browser storage is full or unavailable. Existing drafts are unchanged.');
    invalidateFeedback(); applyProgress(filtered);
    state.metric = snapshot.metric; elements.live.checked = snapshot.live; renderMetric();
    writeStorage('metric', snapshot.metric); writeStorage('live', snapshot.live);
    try { await validateCompletionTokens(); } catch (error) { progressStatus(error.message); }
    if (epoch !== state.progressEpoch) return;
    const id = state.exercises.some(exercise => exercise.id === snapshot.selectedExerciseId) ? snapshot.selectedExerciseId : state.exercise?.id;
    state.exercise = null;
    if (id) await selectExercise(id, { restoring: true });
    renderExercises(); applyPracticeLock();
    progressStatus(`Loaded ${filtered.records.length} saved exercise${filtered.records.length === 1 ? '' : 's'}${omitted ? `; omitted ${omitted} changed or removed exercises` : ''}. ${[...state.completedBodies.values()].some(saved => !state.validCompletionTokens.has(saved.completionToken)) ? 'Use Restore checks to verify saved implementations.' : 'Saved code is ready.'}${state.storageAvailable ? '' : ' Browser storage could not retain every preference.'}`);
  } catch (error) { if (epoch === state.progressEpoch) progressStatus(error.message); }
  finally { $('#progress-file').value = ''; }
}

function recordVerifiedImplementation(result, payload, metric) {
  if (!validToken(result.completionToken) || !validVersion(result.dependencyContextVersion)) return;
  const old = state.completedBodies.get(payload.exerciseId);
  if (!old && state.completedBodies.size >= MAX_PROGRESS_RECORDS) {
    progressStatus('Only 200 exercise implementations can be saved in one progress file. This checkmark is kept; the new implementation was not added to saved progress.'); return;
  }
  if (old && (old.body !== payload.body || old.completionToken !== result.completionToken
    || old.dependencyContextVersion !== result.dependencyContextVersion)) {
    for (const exercise of state.exercises) {
      try {
        if (dependencyClosure(exercise).has(payload.exerciseId)) {
          const saved = state.completedBodies.get(exercise.id);
          if (saved) state.validCompletionTokens.delete(saved.completionToken);
        }
      } catch { /* Invalid metadata remains locked. */ }
    }
  }
  if (old?.completionToken) state.validCompletionTokens.delete(old.completionToken);
  state.completedBodies.set(payload.exerciseId, { body: payload.body, metric, at: Date.now(),
    contentVersion: exerciseVersion(state.exercise), sourceSha256: state.exercise.source?.sha256,
    completionToken: result.completionToken, dependencyContextVersion: result.dependencyContextVersion,
    prerequisiteTokens: [...(payload.prerequisiteTokens || [])] });
  state.validCompletionTokens.add(result.completionToken);
  persistProgress(); renderExercises(); applyPracticeLock(); renderContext();
}

async function restoreProgressChecks() {
  cancelProgressRestore(); invalidateFeedback();
  const controller = new AbortController(); state.progressRestore = controller;
  const epoch = state.progressEpoch, current = () => !controller.signal.aborted && epoch === state.progressEpoch;
  $('#cancel-restore-progress').hidden = false; $('#restore-progress').disabled = true;
  const savedSelection = state.exercise?.id;
  try {
    const ordered = [], visited = new Set(), visiting = new Set();
    const visit = exercise => {
      if (visited.has(exercise.id)) return;
      if (visiting.has(exercise.id)) throw new Error('Saved prerequisites contain a cycle.');
      visiting.add(exercise.id);
      for (const dependency of dependencyList(exercise) || []) {
        const predecessor = state.exercises.find(item => item.id === dependency.id);
        if (!predecessor || !state.completedBodies.has(predecessor.id)) throw new Error(`Save and check ${dependency.predicate} before restoring this chain.`);
        visit(predecessor);
      }
      visiting.delete(exercise.id); visited.add(exercise.id); ordered.push(exercise);
    };
    state.exercises.filter(exercise => state.completedBodies.has(exercise.id)).forEach(visit);
    const { channel } = await ensureChannel(controller.signal);
    for (const [index, exercise] of ordered.entries()) {
      if (!current()) return;
      const saved = state.completedBodies.get(exercise.id);
      if (state.validCompletionTokens.has(saved.completionToken)) continue;
      const tokens = prerequisiteTokens(exercise);
      if (tokens === null) throw new Error(`Restore stopped: ${exercise.predicate} still has an unchecked prerequisite.`);
      progressStatus(`Restoring ${index + 1} of ${ordered.length}: ${exercise.predicate}…`);
      const revision = ++state.revision;
      const payload = { exerciseId: exercise.id, body: saved.body, metric: saved.metric, revision,
        ...(channel ? { channel } : {}), ...(tokens.length ? { prerequisiteTokens: tokens } : {}) };
      const feedback = await learnerJSON('api/feedback', payload, controller.signal, current);
      if (!current()) return;
      if (feedback.status !== 'ok' || feedback.distance !== 0 || feedback.exerciseId !== exercise.id
        || feedback.revision !== revision || feedback.requestedMetric !== saved.metric || feedback.metric !== METRICS[saved.metric].id
        || feedback.contentVersion !== exerciseVersion(exercise) || !validVersion(feedback.evidenceToken)
        || !validVersion(feedback.dependencyContextVersion)) throw new Error(`Restore stopped: ${exercise.predicate} needs another check.`);
      const behavior = await learnerJSON('api/behavior', { ...payload, evidenceToken: feedback.evidenceToken }, controller.signal, current);
      if (!current()) return;
      if (!validBehaviorResult(behavior) || !completedBehavior(behavior) || behavior.exerciseId !== exercise.id || behavior.revision !== revision
        || behavior.contentVersion !== exerciseVersion(exercise) || behavior.dependencyContextVersion !== feedback.dependencyContextVersion
        || !validToken(behavior.completionToken)) throw new Error(`Restore stopped: ${exercise.predicate} is not a completed solution within the current bounds.`);
      state.completedBodies.set(exercise.id, { ...saved, completionToken: behavior.completionToken,
        dependencyContextVersion: behavior.dependencyContextVersion, prerequisiteTokens: [...tokens] });
      state.validCompletionTokens.add(behavior.completionToken); persistProgress(); renderExercises();
    }
    if (current()) {
      progressStatus('Saved implementations checked. Their dependent practices are available.');
      if (savedSelection && state.exercise?.id === savedSelection) { applyPracticeLock(); renderContext(); }
    }
  } catch (error) { if (current()) progressStatus(error.message); }
  finally {
    if (state.progressRestore === controller) {
      state.progressRestore = null;
      $('#cancel-restore-progress').hidden = true; $('#restore-progress').disabled = false;
      applyPracticeLock();
    }
  }
}

function exerciseVersion(exercise) {
  return typeof exercise?.contentVersion === 'string' && /^[a-f0-9]{64}$/.test(exercise.contentVersion)
    ? exercise.contentVersion : null;
}

function saveSolvedExercises() {
  const records = [...state.solved].filter(([, record]) => record.version !== null)
    .sort(([, left], [, right]) => right.at - left.at).slice(0, MAX_SOLVED_EXERCISES);
  writeStorage('solved', records.map(([id, record]) => ({ id, ...record })));
}

function loadSolvedExercises() {
  state.solved.clear();
  const records = readStorage('solved', []);
  if (!Array.isArray(records)) return;
  const versions = new Map(state.exercises.map(exercise => [exercise.id, exerciseVersion(exercise)]));
  for (const record of records.slice(0, MAX_SOLVED_EXERCISES)) {
    if (!record || typeof record.id !== 'string' || typeof record.version !== 'string'
      || !/^[a-f0-9]{64}$/.test(record.version) || record.version !== versions.get(record.id)
      || !Number.isFinite(record.at) || record.at <= 0 || !Object.hasOwn(METRICS, record.metric)) continue;
    state.solved.set(record.id, { version: record.version, at: record.at, metric: record.metric });
  }
  // Removed exercises and changed questions or models must not retain a check.
  saveSolvedExercises();
}

function solvedExercise(exercise) {
  const saved = state.solved.get(exercise.id);
  return Boolean(saved && (saved.version === exerciseVersion(exercise)));
}

function completedBehavior(result) {
  return result.scoreStatus === 'ok' && result.scoreReason === 'OK' && result.score === 1
    && result.sampling.semanticCounterexamples === 0
    && result.sampling.positiveTested > 0 && result.sampling.negativeTested > 0
    && result.sampling.positiveAccepted === result.sampling.positiveTested
    && result.sampling.negativeRejected === result.sampling.negativeTested
    && ['both', 'neither'].every(id => result.categories.some(category => category.id === id
      && category.status === 'sat' && category.instances.length > 0))
    && ['undercoverage', 'overcoverage'].every(id => result.categories.some(category => category.id === id
      && category.status === 'unsat' && category.enumerationComplete && category.instances.length === 0));
}

function recordSolvedExercise(result, payload, selection, metric) {
  const structural = state.structuralCompletionEvidence;
  if (!structural || structural.distance !== 0 || structural.exerciseId !== payload.exerciseId
    || structural.revision !== payload.revision || structural.selection !== selection
    || structural.body !== payload.body || structural.metric !== metric
    || payload.revision !== state.revision || selection !== state.selection || metric !== state.metric
    || payload.exerciseId !== state.exercise?.id || payload.body !== elements.editor.value
    || structural.version !== exerciseVersion(state.exercise)
    || structural.responseVersion !== structural.version || (result.contentVersion ?? null) !== structural.version
    || !completedBehavior(result)) return;
  state.solved.set(payload.exerciseId, { version: structural.version, at: Date.now(), metric });
  saveSolvedExercises();
  renderExercises();
  return true;
}

function renderWorkspaceBadge() {
  // Use the browser's actual URL, never the backend's forwarded Host metadata.
  const hostname = window.location.hostname.toLowerCase();
  const badge = $('#local-workspace-badge');
  badge.hidden = badge.hasAttribute('data-public-deployment')
    || !['localhost', '127.0.0.1', '[::1]', '::1'].includes(hostname);
}

function historyStorageKey() {
  return `history:${state.exercise.id}${state.metric === 'canonical' ? '' : ':ast'}`;
}

function loadHistory() {
  const legacy = state.exercise ? readStorage(historyStorageKey(), []) : [];
  const restored = state.savedRecords.get(state.exercise?.id)?.history?.[state.metric];
  const stamp = state.exercise ? readStorage(`${historyStorageKey()}:progress`, null) : null;
  const saved = restored && stamp !== state.progressSavedAt ? restored : legacy;
  state.history = (Array.isArray(saved) ? saved : []).filter(item => item
    && item.basis === METRICS[state.metric].basis && typeof item.distance === 'number'
    && Number.isFinite(item.distance) && item.distance >= 0 && typeof item.at === 'number').slice(-12);
  state.lastHistoryBody = null;
}

function renderMetric() {
  const ast = state.metric === 'ast';
  elements.metric.value = state.metric;
  $('#canonical-panel').hidden = ast;
  $('#metric-description').textContent = ast
    ? 'Zhang–Shasha compares the original syntax trees. Each inserted, deleted, or changed node costs one edit.'
    : 'Compares simplified forms of your predicate and the saved correct answers.';
  $('#metric-method-note').textContent = ast
    ? 'The AST distance uses the closest original syntax tree among the saved correct answers, including the oracle. Zero means the compared tree labels and child order match. This score does not measure behavior.'
    : 'The distance uses the closest match among the saved correct answers, including the oracle. Zero means their canonical forms match. It does not prove that they behave the same in every case.';
  $('#history-heading').textContent = `${METRICS[state.metric].label} progress`;
}

function selectMetric() {
  const metric = elements.metric.value;
  if (!Object.hasOwn(METRICS, metric) || metric === state.metric) return;
  cancelProgressRestore(); invalidateFeedback();
  state.metric = metric;
  tutorChat.refresh();
  writeStorage('metric', metric);
  renderMetric();
  loadHistory();
  renderHistory();
  showWaiting('Comparison method changed.');
  if (state.exercise && !elements.editor.disabled) scheduleFeedback(0);
}

function responseMetricMatches(result, metric) {
  return result.requestedMetric === metric
    || (result.status !== 'ok' && metric === 'canonical' && result.requestedMetric === undefined);
}

function showToast(message) {
  const toast = $('#toast');
  toast.textContent = message;
  toast.hidden = false;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => { toast.hidden = true; }, 3300);
}

function setStatus(status, text) {
  state.feedbackStatus = status;
  elements.status.dataset.state = status;
  elements.status.textContent = text;
}

function invalidateFeedback() {
  state.chatEvidence = null;
  state.progressiveHints = null;
  state.structuralCompletionEvidence = null;
  clearOperationHighlight();
  resetBehavior();
  const pending = state.checkFlight || state.feedbackAbort || state.behaviorAbort || state.explainAbort;
  state.revision += 1;
  tutorChat.refresh();
  if (pending) cancelChannel(state.revision);
  state.checkFlight = null;
  clearTimeout(state.timer);
  state.feedbackAbort?.abort();
  state.feedbackAbort = null;
  state.explainAbort?.abort();
  state.explainAbort = null;
}

function showWaiting(message = 'Your next edit is ready to explore.') {
  clearCanonicalForm('Check this draft to see its canonical form.');
  setStatus('waiting', 'Not checked');
  const wrapper = node('div', 'feedback-empty');
  const illustration = node('div', 'empty-illustration');
  illustration.setAttribute('aria-hidden', 'true');
  illustration.append(node('span', '', '{'), node('span', 'empty-orbit', '↗'), node('span', '', '}'));
  wrapper.append(illustration, node('h3', '', message), node('p', '', elements.live.checked
    ? 'Live feedback compares your predicate with the closest answer in the private set of correct answers, including the oracle.'
    : 'Check your predicate to see its distance from a correct answer and suggestions for what to review.'));
  elements.result.replaceChildren(wrapper);
}

async function fetchJSONResponse(url, options = {}) {
  const endpoint = new URL(url, APP_BASE);
  const response = await fetch(endpoint, { ...options, headers: { Accept: 'application/json', ...options.headers } });
  // An IIS error page or sign-in redirect is not an application result. Report
  // only the requested path and status; response bodies may contain private
  // server diagnostics, so never include them in the error or console.
  const invalidResponse = () => {
    const status = response.status;
    let advice = 'Ask the server administrator to check the API connection.';
    if (response.redirected || status === 401 || status === 403) {
      advice = 'Access to the API requires attention. Sign in again or contact the server administrator.';
    } else if ([502, 503, 504].includes(status)) {
      advice = 'The analysis service is unavailable or timed out. Ask the server administrator to check the backend and proxy.';
    } else if (status === 404 || status === 405 || status === 200) {
      advice = 'The API route is not returning application data. Ask the server administrator to check the site routing.';
    }
    return new Error(`API response error: HTTP ${status} at ${endpoint.pathname}${response.redirected ? ' (redirected)' : ''}. ${advice}`);
  };
  if (response.redirected) throw invalidResponse();
  let data;
  try { data = await response.json(); }
  catch (error) {
    if (error.name === 'AbortError') throw error;
    throw invalidResponse();
  }
  if (!data || typeof data !== 'object' || Array.isArray(data)) throw invalidResponse();
  if (!response.ok && !(data && typeof data.status === 'string')) {
    throw new Error(data?.error?.message || data?.error || data?.message || `Request failed (${response.status}).`);
  }
  return { data, response };
}

async function fetchJSON(url, options = {}) {
  return (await fetchJSONResponse(url, options)).data;
}

// BEGIN TRAFFIC RETRY POLICY
function retryDelay(response, data, attempt, now, deadline, random = Math.random) {
  // Only an explicit rejection before dispatch permits an automatic retry.
  // A lost response, worker timeout or provider error never enters this branch.
  if (attempt !== 0 || ![429, 503].includes(response.status)
    || data.status !== 'busy' || data.retryable !== true || data.dispatched !== false
    || data.code !== 'capacity') return null;
  const seconds = response.headers.get('Retry-After');
  if (typeof seconds !== 'string' || !/^\d+(?:\.\d+)?$/.test(seconds)) return null;
  const wait = Number(seconds) * 1000;
  if (!Number.isFinite(wait) || wait < 0 || wait > 5000) return null;
  const delay = wait + Math.floor(random() * 151);
  return now + delay < deadline ? delay : null;
}

function abortableDelay(milliseconds, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException('Request superseded.', 'AbortError')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, milliseconds);
    if (signal.aborted) abort();
    else signal.addEventListener('abort', abort, { once: true });
  });
}
// END TRAFFIC RETRY POLICY

async function learnerJSON(url, payload, signal, current) {
  const serialized = JSON.stringify(payload);
  // Leave room for the supported cold-worker + legacy explanation path through IIS.
  // Retries still require an explicit refusal before analysis dispatch.
  const requestBudget = 150000;
  const deadline = performance.now() + requestBudget;
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal.addEventListener('abort', abort, { once: true });
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, requestBudget);
  // A channel-less fallback request is sent at most once (AP01-C06); only a
  // channel-bound request may take the single explicit-capacity retry.
  const attempts = typeof payload.channel === 'string' ? 2 : 1;
  try {
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      if (signal.aborted || !current() || performance.now() >= deadline) {
        throw new DOMException('Request superseded.', 'AbortError');
      }
      const { data, response } = await fetchJSONResponse(url, {
        method: 'POST', signal: controller.signal,
        headers: { 'Content-Type': 'application/json' }, body: serialized,
      });
      if (performance.now() >= deadline) {
        throw new Error('The analysis request timed out. Check your predicate again when ready.');
      }
      if (data.status === 'expired' && data.code === 'channel_expired' && state.channel === payload.channel) {
        state.channel = null;
      }
      const delay = attempt + 1 < attempts ? retryDelay(response, data, attempt, performance.now(), deadline) : null;
      if (delay === null || !current()) return data;
      await abortableDelay(delay, controller.signal);
    }
    throw new Error('The analysis service is busy. Check your predicate again shortly.');
  } catch (error) {
    if (timedOut) throw new Error('The analysis request timed out. Check your predicate again when ready.');
    throw error;
  } finally {
    clearTimeout(timer);
    signal.removeEventListener('abort', abort);
  }
}

// BEGIN CHANNEL FALLBACK POLICY
// AP01-C06 (Identity.requestPlan): one channel attempt per check. Only an explicit
// capacity refusal or an unreachable channel endpoint permits channel-less
// requests; abort, authentication, malformed and other failures never fall back.
function channelOutcome(status, redirected, data) {
  if (redirected || status === 401 || status === 403) return 'authenticationFailure';
  if (status === 200 && !redirected && data && data.status === 'ok' && typeof data.channel === 'string'
    && /^[a-zA-Z0-9_-]{43}$/.test(data.channel)) return 'issued';
  if ([429, 503].includes(status) && data && data.status === 'busy' && data.code === 'capacity') return 'explicitlyUnavailable';
  return status === 200 ? 'malformed' : 'otherFailure';
}

function fallbackPermitted(outcome) {
  return outcome === 'explicitlyUnavailable' || outcome === 'availabilityFailure';
}
// END CHANNEL FALLBACK POLICY

async function ensureChannel(signal) {
  if (state.channel) return { channel: state.channel };
  if (!state.channelPromise) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    state.channelPromise = (async () => {
      let response;
      try {
        response = await fetch(new URL('api/channel', APP_BASE), {
          method: 'POST', signal: controller.signal, redirect: 'manual',
          headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, body: '{}',
        });
      } catch (error) {
        return { outcome: 'availabilityFailure' };  // unreachable or past the channel deadline
      }
      let data = null;
      try { data = await response.json(); } catch (error) { data = null; }
      const outcome = channelOutcome(response.status, response.redirected || response.type === 'opaqueredirect',
        data && typeof data === 'object' && !Array.isArray(data) ? data : null);
      if (outcome === 'issued') state.channel = data.channel;
      return { outcome, channel: outcome === 'issued' ? data.channel : null };
    })().finally(() => { clearTimeout(timer); state.channelPromise = null; });
  }
  const result = await state.channelPromise;
  if (signal?.aborted) throw new DOMException('Request superseded.', 'AbortError');
  if (result.outcome === 'issued') return { channel: result.channel };
  if (fallbackPermitted(result.outcome)) return { channel: null };
  throw new Error(result.outcome === 'authenticationFailure'
    ? 'Access to the API requires attention. Sign in again or contact the server administrator.'
    : 'The editing session could not be started. Check your predicate again shortly.');
}

function cancelChannel(revision) {
  if (!state.channel) return;
  // Cancellation reaches only this tab's subscribers. Local guards take effect
  // immediately; server suppression starts when this notification arrives.
  fetchJSON('api/cancel', { method: 'POST', keepalive: true,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ channel: state.channel, revision }),
  }).catch(() => {});
}

function visibleExercises() {
  const query = elements.search.value.trim().toLowerCase();
  const group = elements.group.value;
  return state.exercises.filter((exercise) => (!group || exercise.group === group)
    && [exercise.title, exercise.predicate, exercise.group, exercise.description].join(' ').toLowerCase().includes(query));
}

function renderExerciseNavigation(visible = visibleExercises()) {
  const index = visible.findIndex(exercise => exercise.id === state.exercise?.id);
  const ready = index >= 0 && !state.loadingExercise && (!elements.editor.disabled || lockedExercise(state.exercise));
  elements.previousExercise.disabled = !ready || index === 0;
  elements.nextExercise.disabled = !ready || index === visible.length - 1;
  const filtered = Boolean(elements.search.value.trim() || elements.group.value);
  elements.exercisePosition.textContent = !visible.length ? 'No matching exercises'
    : state.loadingExercise ? 'Loading exercise…'
      : index < 0 ? (state.exercise ? 'Select a matching exercise' : 'Choose an exercise')
        : elements.editor.disabled ? (lockedExercise(state.exercise) ? `${index + 1} of ${visible.length} · Prerequisites required` : 'Choose an exercise to continue')
        : `${index + 1} of ${visible.length}${filtered ? ' matching exercises' : ' exercises'}`;
  elements.previousExercise.title = ready && index > 0 ? `Previous: ${visible[index - 1].title}` : 'Previous exercise';
  elements.nextExercise.title = ready && index < visible.length - 1 ? `Next: ${visible[index + 1].title}` : 'Next exercise';
}

function navigateExercise(offset) {
  if (!state.exercise || state.loadingExercise) return;
  const visible = visibleExercises();
  const index = visible.findIndex(exercise => exercise.id === state.exercise?.id);
  if (index < 0) return;
  const exercise = visible[index + offset];
  if (exercise) selectExercise(exercise.id);
}

function renderExercises() {
  const visible = visibleExercises();
  renderExerciseNavigation(visible);
  elements.list.replaceChildren();
  let currentGroup;
  visible.forEach((exercise) => {
    if (exercise.group !== currentGroup) {
      currentGroup = exercise.group;
      elements.list.append(node('p', 'exercise-group-label', currentGroup || 'Exercises'));
    }
    const button = node('button', `exercise-item${state.exercise?.id === exercise.id ? ' active' : ''}`);
    button.type = 'button';
    button.dataset.exerciseId = exercise.id;
    const solved = solvedExercise(exercise);
    const locked = lockedExercise(exercise);
    button.dataset.solved = String(solved);
    button.dataset.locked = String(locked);
    button.setAttribute('aria-label', `${exercise.title}, ${exercise.group || 'Exercise'}${solved ? ', Solved' : ''}`);
    if (state.exercise?.id === exercise.id) button.setAttribute('aria-current', 'page');
    const text = node('span', 'exercise-item-text');
    text.append(node('span', 'exercise-item-title', exercise.title), node('span', 'exercise-item-predicate', exercise.predicate));
    button.append(node('span', 'exercise-item-number', String(state.exercises.indexOf(exercise) + 1).padStart(2, '0')), text);
    if (solved) {
      const check = node('span', 'exercise-item-solved', '✓');
      check.setAttribute('aria-hidden', 'true');
      check.title = 'Solved: zero edit distance and 1.000 behavioral score, with no bounded counterexamples.';
      button.append(check);
    }
    if (locked) {
      const lock = node('span', 'exercise-item-lock', '🔒'); lock.setAttribute('aria-hidden', 'true');
      lock.title = `Complete ${(dependencyList(exercise) || []).map(item => item.predicate).join(', ') || 'the prerequisites'} first.`;
      button.append(lock);
    }
    if (state.exercise?.id === exercise.id) button.append(node('span', 'exercise-item-arrow', '›'));
    button.addEventListener('click', () => selectExercise(exercise.id));
    elements.list.append(button);
  });
  if (!visible.length) elements.list.append(node('p', 'sidebar-message', 'No exercises match your search.'));
  const active = elements.list.querySelector('.exercise-item.active');
  if (active) {
    const bounds = active.getBoundingClientRect();
    const listBounds = elements.list.getBoundingClientRect();
    if (elements.list.scrollWidth > elements.list.clientWidth) {
      elements.list.scrollLeft += bounds.left - listBounds.left;
    } else if (bounds.top < listBounds.top || bounds.bottom > listBounds.bottom) {
      elements.list.scrollTop += bounds.top - listBounds.top - 28;
    }
  }
}

function updateEditor() {
  const count = elements.editor.value.split('\n').length;
  elements.lines.textContent = Array.from({ length: count }, (_, index) => index + 1).join('\n');
  renderAlloyCode(elements.highlight, elements.editor.value + '\n', state.sourceHighlight?.range);
  $('#indent-button').disabled = elements.editor.disabled;
  syncEditorOverlay();
  updateCursor();
}

function replaceEditorText(text, start, end, cursor = start + text.length) {
  elements.editor.focus({ preventScroll: true });
  elements.editor.setSelectionRange(start, end);
  // Native insertion preserves browser undo/redo. Its input event follows the
  // usual revision, stale-result invalidation, and draft-saving path.
  const previous = elements.editor.value;
  const revision = state.revision;
  let inserted = false;
  try { inserted = document.execCommand('insertText', false, text); } catch { /* Native API unavailable. */ }
  if (!inserted && elements.editor.value === previous) {
    elements.editor.setRangeText(text, start, end, 'end');
  }
  if (state.revision === revision && elements.editor.value !== previous) onEdit();
  elements.editor.setSelectionRange(cursor, cursor);
  updateCursor();
}

function syncEditorOverlay() {
  elements.highlight.style.width = `${elements.editor.clientWidth}px`;
  elements.highlight.style.height = `${elements.editor.clientHeight}px`;
  elements.highlight.scrollTop = elements.editor.scrollTop;
  elements.highlight.scrollLeft = elements.editor.scrollLeft;
  elements.lines.style.height = `${elements.editor.clientHeight}px`;
  elements.lines.scrollTop = elements.editor.scrollTop;
}

function clearSourceHighlight() {
  state.sourceHighlight = null;
  elements.editor.classList.remove('has-source-highlight');
  renderAlloyCode(elements.highlight, elements.editor.value + '\n');
  elements.locationStatus.textContent = '';
  elements.locationBar.hidden = true;
  document.querySelectorAll('.operation-locate[aria-pressed="true"]').forEach((button) => button.setAttribute('aria-pressed', 'false'));
}

function clearOperationHighlight() {
  clearSourceHighlight();
  if (state.canonical) renderCanonicalForms();
  elements.canonicalStatus.textContent = '';
  elements.canonicalStatus.hidden = true;
  document.querySelectorAll('.operation-item.active-operation').forEach((item) => item.classList.remove('active-operation'));
  document.querySelectorAll('.operation-select[aria-pressed="true"]').forEach((button) => button.setAttribute('aria-pressed', 'false'));
}

function clearCanonicalForm(message) {
  state.canonical = null;
  elements.canonical.replaceChildren(node('p', 'canonical-empty', message));
  elements.canonicalStatus.textContent = '';
  elements.canonicalStatus.hidden = true;
}

function renderCanonicalForms(ranges = []) {
  elements.canonical.replaceChildren();
  state.canonical.forms.forEach((form, formIndex) => {
    const pre = node('pre', 'canonical-form');
    pre.dataset.formIndex = formIndex;
    const candidates = ranges.filter(range => range.formIndex === formIndex).sort((a, b) => a.start - b.start || a.end - b.end);
    // Ambiguous candidates can overlap. Their union is a visual highlight,
    // while the status preserves the number of possible correspondences.
    const merged = [];
    candidates.forEach(range => {
      const last = merged.at(-1);
      if (last && range.start < last.end) last.end = Math.max(last.end, range.end);
      else merged.push({ start: range.start, end: range.end });
    });
    let cursor = 0;
    merged.forEach(range => {
      pre.append(document.createTextNode(form.slice(cursor, range.start)), node('mark', 'canonical-range', form.slice(range.start, range.end)));
      cursor = range.end;
    });
    pre.append(document.createTextNode(form.slice(cursor)));
    elements.canonical.append(pre);
  });
}

function validatedCanonicalLocation(location, context) {
  if (!sourceContextCurrent(context) || context.metric !== 'canonical' || state.canonical?.context !== context
    || !location || !['located', 'ambiguous'].includes(location.status)
    || !['node', 'related', 'form'].includes(location.precision) || location.coordinateSystem !== 'canonical'
    || (location.precision === 'node' && location.status !== 'located')
    || location.offsetEncoding !== 'utf-16' || !Array.isArray(location.ranges) || location.ranges.length > 32
    || (location.status === 'located' ? location.ranges.length !== 1 : location.ranges.length < 2)) return null;
  const seen = new Set();
  for (const range of location.ranges) {
    const form = Number.isInteger(range?.formIndex) && state.canonical.forms[range.formIndex];
    if (typeof form !== 'string' || !Number.isInteger(range.start) || !Number.isInteger(range.end)
      || range.start < 0 || range.end <= range.start || range.end > form.length
      || typeof range.text !== 'string' || form.slice(range.start, range.end) !== range.text) return null;
    const key = `${range.formIndex}:${range.start}:${range.end}`;
    if (seen.has(key)) return null;
    seen.add(key);
    for (const offset of [range.start, range.end]) {
      if (offset > 0 && offset < form.length && /[\uD800-\uDBFF]/.test(form[offset - 1]) && /[\uDC00-\uDFFF]/.test(form[offset])) return null;
    }
  }
  return location;
}

function selectOperation(operation, context, item, sourceIndex = null, sourceButton = null) {
  if (!sourceContextCurrent(context)) { clearOperationHighlight(); return; }
  clearOperationHighlight();
  item.classList.add('active-operation');
  item.querySelector('.operation-select').setAttribute('aria-pressed', 'true');
  const canonical = validatedCanonicalLocation(operation.canonicalLocation, context);
  if (canonical) {
    renderCanonicalForms(canonical.ranges);
    const label = canonical.precision === 'node' ? 'Expression selected by this edit'
      : canonical.precision === 'form' ? 'Canonical form context' : 'Related part of your canonical form';
    const ambiguity = canonical.status === 'ambiguous' ? ` · ${canonical.ranges.length} possible parts highlighted. These highlights are not paired with the locations in your code.` : '.';
    elements.canonicalStatus.textContent = `${label}${ambiguity}${typeof canonical.reason === 'string' && canonical.reason ? ` ${canonical.reason}` : ''}`;
    $('#canonical-panel').open = true;
  } else {
    elements.canonicalStatus.textContent = 'Canonical form location unavailable for this edit step.';
  }
  elements.canonicalStatus.hidden = false;
  const source = validatedSourceLocation(operation.sourceLocation, context);
  if (source && (source.status === 'located' || sourceIndex !== null)) {
    locateSource(source, sourceIndex ?? 0, context, sourceButton || item.querySelector('.operation-locate'));
  } else {
    elements.locationStatus.textContent = source
      ? `Related source context · ${source.ranges.length} possible locations. Choose a source candidate in the edit step.`
      : 'Source location unavailable for this edit step.';
    elements.locationBar.hidden = false;
    elements.canonical.querySelector('.canonical-range')?.scrollIntoView({ block: 'nearest', behavior: 'auto' });
  }
}

function sourceContextCurrent(context) {
  return context && context.revision === state.revision && context.selection === state.selection
    && context.metric === state.metric
    && context.exerciseId === state.exercise?.id && context.body === elements.editor.value && !elements.editor.disabled;
}

function bodyPosition(body, offset) {
  const prefix = body.slice(0, offset).split('\n');
  return { line: prefix.length, column: prefix.at(-1).length + 1 };
}

function validatedSourceLocation(location, context) {
  if (!sourceContextCurrent(context) || !location || !['located', 'ambiguous'].includes(location.status)
    || !['node', 'exact', 'related', 'predicate'].includes(location.precision)
    || (location.precision === 'node' && location.status !== 'located')
    || location.coordinateSystem !== 'body' || location.offsetEncoding !== 'utf-16'
    || !Array.isArray(location.ranges) || location.ranges.length > 32
    || (location.status === 'located' ? location.ranges.length !== 1 : location.ranges.length < 2)) return null;
  const { body } = context;
  const splitsSurrogate = (offset) => offset > 0 && offset < body.length
    && /[\uD800-\uDBFF]/.test(body[offset - 1]) && /[\uDC00-\uDFFF]/.test(body[offset]);
  const seen = new Set();
  for (const range of location.ranges) {
    if (!range || !Number.isInteger(range.start) || !Number.isInteger(range.end)
      || range.start < 0 || range.end <= range.start || range.end > body.length
      || typeof range.text !== 'string' || body.slice(range.start, range.end) !== range.text
      || splitsSurrogate(range.start) || splitsSurrogate(range.end)) return null;
    const start = bodyPosition(body, range.start);
    const end = bodyPosition(body, range.end);
    if (range.startLine !== start.line || range.startColumn !== start.column
      || range.endLine !== end.line || range.endColumn !== end.column
      || !Number.isInteger(range.moduleLine) || range.moduleLine < 1
      || !Number.isInteger(range.moduleColumn) || range.moduleColumn < 1) return null;
    const key = `${range.start}:${range.end}`;
    if (seen.has(key)) return null;
    seen.add(key);
  }
  return location;
}

function sourceLocationLabel(location) {
  return location.precision === 'node' ? 'Expression selected by this edit'
    : location.precision === 'exact' ? 'Source expression'
    : location.precision === 'predicate' ? 'Predicate context' : 'Related source context';
}

function locateSource(location, index, context, button) {
  if (!validatedSourceLocation(location, context)) { clearSourceHighlight(); return; }
  clearSourceHighlight();
  const range = location.ranges[index];
  renderAlloyCode(elements.highlight, context.body + '\n', range);
  const mark = elements.highlight.querySelector('.source-range');
  state.sourceHighlight = { context, range };
  elements.editor.classList.add('has-source-highlight');
  button.setAttribute('aria-pressed', 'true');
  const ambiguity = location.status === 'ambiguous' ? ` · possible location ${index + 1} of ${location.ranges.length}` : '';
  elements.locationStatus.textContent = `${sourceLocationLabel(location)}${ambiguity} · Body Ln ${range.startLine}, Col ${range.startColumn} · Model Ln ${range.moduleLine}, Col ${range.moduleColumn}`;
  elements.locationBar.hidden = false;
  elements.editor.focus({ preventScroll: true });
  elements.editor.setSelectionRange(range.start, range.end);
  syncEditorOverlay();
  // The mirror measures tabs and proportional Unicode fallback glyphs exactly
  // as the textarea does; character-count estimates drift on long lines.
  const rect = mark.getClientRects()[0];
  const viewport = elements.highlight.getBoundingClientRect();
  const left = rect ? rect.left - viewport.left + elements.highlight.scrollLeft : 0;
  const lineHeight = parseFloat(getComputedStyle(elements.editor).lineHeight);
  elements.editor.scrollTop = Math.max(0, (range.startLine - 1) * lineHeight - elements.editor.clientHeight / 3);
  elements.editor.scrollLeft = Math.max(0, left - Math.min(60, elements.editor.clientWidth / 3));
  syncEditorOverlay();
  elements.editor.scrollIntoView({ block: 'center', behavior: 'auto' });
  updateCursor();
}

function renderSourceLocator(operation, context, index, item) {
  const container = node('div', 'operation-source-location');
  const location = validatedSourceLocation(operation.sourceLocation, context);
  if (!location) {
    const reason = operation.sourceLocation?.status === 'unavailable' && typeof operation.sourceLocation.reason === 'string'
      ? ` ${operation.sourceLocation.reason}` : '';
    container.append(node('p', 'source-location-unavailable', `Source location unavailable.${reason}`));
    return container;
  }
  const note = node('p', 'operation-location-note');
  note.id = `source-location-note-${index}`;
  const ambiguity = location.status === 'ambiguous' ? ` · ${location.ranges.length} possible locations; choose one to inspect.` : '.';
  note.textContent = `${sourceLocationLabel(location)}${ambiguity}${typeof location.reason === 'string' && location.reason ? ` ${location.reason}` : ''}`;
  container.append(note);
  const choices = node('div', 'source-location-choices');
  location.ranges.forEach((range, rangeIndex) => {
    const button = node('button', 'button operation-locate', location.status === 'ambiguous' ? `Locate candidate ${rangeIndex + 1}` : 'Locate in model');
    button.type = 'button';
    button.setAttribute('aria-controls', 'predicate-editor');
    button.setAttribute('aria-describedby', note.id);
    button.setAttribute('aria-pressed', 'false');
    button.title = `Body line ${range.startLine}, column ${range.startColumn}; model line ${range.moduleLine}, column ${range.moduleColumn}`;
    button.addEventListener('click', () => selectOperation(operation, context, item, rangeIndex, button));
    choices.append(button);
  });
  container.append(choices);
  return container;
}

function updateCursor() {
  const prefix = elements.editor.value.slice(0, elements.editor.selectionStart);
  const lines = prefix.split('\n');
  $('#cursor-position').textContent = `Ln ${lines.length}, Col ${lines.at(-1).length + 1}`;
}

function saveDraft() {
  if (!state.exercise) return;
  const draft = { body: elements.editor.value, source: state.exercise.source?.sha256,
    version: exerciseVersion(state.exercise), updatedAt: Date.now(), progressSavedAt: state.progressSavedAt };
  const written = writeStorage(`draft:${state.exercise.id}`, draft);
  if (written) state.memoryDrafts.delete(state.exercise.id);
  else if ((state.memoryDrafts.has(state.exercise.id) || state.memoryDrafts.size < MAX_PROGRESS_RECORDS)
    && draft.body.length <= BODY_BYTES) state.memoryDrafts.set(state.exercise.id, draft);
  else {
    progressStatus('Browser storage is unavailable and the in-memory draft limit is full. Your code stays in this editor; download the model before leaving.'); return false;
  }
  elements.draft.textContent = written ? 'Saved in this browser' : 'In this tab only · export a backup';
  return true;
}

function clearResetRecovery() {
  state.resetRecovery = null;
  $('#reset-recovery').hidden = true;
}

function keepSelectedExerciseVisible() {
  // Scroll only the compact horizontal strip, never the page or user's editor.
  const selected = elements.list.querySelector('.exercise-item.active');
  if (!selected || getComputedStyle(elements.list).display !== 'flex') return;
  const listBox = elements.list.getBoundingClientRect(), itemBox = selected.getBoundingClientRect();
  if (itemBox.left < listBox.left) elements.list.scrollLeft += itemBox.left - listBox.left;
  else if (itemBox.right > listBox.right) elements.list.scrollLeft += itemBox.right - listBox.right;
}

function renderContext() {
  if (!state.exercise) return;
  const exercise = state.exercise;
  let environment;
  try { environment = resolvedEnvironment(exercise); }
  catch { environment = exercise; }
  renderAlloyCode($('#environment-code'), environment.environmentBefore + exercise.predicateHeader
    + '{\n  // TODO: write the predicate body in the editor above\n}' + environment.environmentAfter);
  const used = $('#dependency-implementations');
  try {
    const names = [...dependencyClosure(exercise)].filter(id => {
      const saved = state.completedBodies.get(id); return saved && state.validCompletionTokens.has(saved.completionToken);
    }).map(id => state.exercises.find(item => item.id === id)?.predicate || id);
    used.hidden = !names.length; used.textContent = names.length
      ? `Using your last checked implementations of ${names.join(', ')}. New drafts do not replace these until checked successfully.` : '';
  } catch { used.hidden = true; }
}

async function selectExercise(id, { restoring = false } = {}) {
  if (!restoring) cancelProgressRestore();
  if (state.exercise?.id === id && !elements.editor.disabled) return;
  if (state.exercise && !elements.editor.disabled && !saveDraft()) return;
  clearResetRecovery();
  invalidateFeedback();
  const selection = ++state.selection;
  state.detailAbort?.abort();
  const controller = new AbortController();
  state.detailAbort = controller;
  state.loadingExercise = true;
  tutorChat.refresh();
  elements.editor.disabled = true;
  elements.check.disabled = true;
  elements.reset.disabled = true;
  elements.download.disabled = true;
  renderExerciseNavigation();
  elements.draft.textContent = 'Loading model…';
  showWaiting('Loading your model…');
  $('#startup-error').hidden = true;
  try {
    const exercise = await fetchJSON(`api/exercises/${encodeURIComponent(id)}`, { signal: controller.signal, cache: 'no-cache' });
    if (selection !== state.selection) return;
    if (!exercise || typeof exercise.id !== 'string' || typeof exercise.starter !== 'string') throw new Error('This exercise could not be loaded.');
    state.exercise = exercise;
    const summary = state.exercises.find(item => item.id === exercise.id);
    if (summary) {
      for (const field of ['title', 'group', 'predicate', 'description', 'contentVersion', 'dependencies']) {
        if (exercise[field] !== undefined) summary[field] = exercise[field];
      }
    }
    const completion = state.solved.get(exercise.id);
    if (completion && completion.version !== exerciseVersion(exercise)) {
      state.solved.delete(exercise.id);
      saveSolvedExercises();
    }
    state.loadingExercise = false;
    state.context = 'before';
    loadHistory();
    const legacy = state.memoryDrafts.get(id) || readStorage(`draft:${id}`, null), saved = state.savedRecords.get(id);
    const fileDraft = saved?.contentVersion === exerciseVersion(exercise) && saved?.sourceSha256 === exercise.source?.sha256
      && saved.draft ? { ...saved.draft, source: saved.sourceSha256, version: saved.contentVersion } : null;
    const draft = fileDraft && (!legacy || legacy.progressSavedAt !== state.progressSavedAt) ? fileDraft : legacy;
    elements.editor.value = draft && typeof draft.body === 'string' && draft.source === exercise.source?.sha256
      && (!draft.version || draft.version === exerciseVersion(exercise)) ? draft.body : exercise.starter;
    elements.editor.disabled = false;
    elements.check.disabled = false;
    elements.reset.disabled = false;
    elements.download.disabled = false;
    elements.draft.textContent = draft && elements.editor.value === draft.body ? 'Your draft restored' : 'Starter predicate';
    $('#exercise-title').textContent = exercise.title;
    $('#exercise-group').textContent = exercise.group || 'Predicate exercises';
    $('#exercise-description').textContent = exercise.description || `Complete the ${exercise.predicate} predicate within its original Alloy model.`;
    $('#predicate-filename').textContent = `${exercise.predicate || exercise.id}.als`;
    $('#predicate-signature').textContent = `${(exercise.predicateHeader || `pred ${exercise.predicate} `).trimStart()}{`;
    $('#source-info').textContent = `${exercise.source?.path || 'Original model'}${exercise.source?.sha256 ? ` · SHA-256 ${exercise.source.sha256.slice(0, 12)}` : ''}`;
    $('#source-info').title = exercise.source?.sha256 || '';
    document.title = `${exercise.title} · Alloy Studio`;
    writeStorage('lastExercise', id);
    const url = new URL(window.location.href);
    url.searchParams.set('exercise', id);
    window.history.replaceState(null, '', url);
    updateEditor();
    renderExercises();
    renderContext();
    renderHistory();
    showWaiting();
    applyPracticeLock(); renderExercises();
    keepSelectedExerciseVisible();
    tutorChat.refresh();
    if (elements.live.checked && !elements.editor.disabled && !restoring) scheduleFeedback(200);
  } catch (error) {
    if (error.name === 'AbortError' || selection !== state.selection) return;
    state.loadingExercise = false;
    renderExerciseNavigation();
    elements.draft.textContent = 'Could not load';
    showError(error.message, () => selectExercise(id));
  }
}

function scheduleFeedback(delay = 650) {
  clearTimeout(state.timer);
  state.timer = setTimeout(checkPredicate, delay);
}

function onEdit() {
  clearResetRecovery();
  cancelProgressRestore();
  invalidateFeedback();
  updateEditor();
  saveDraft();
  showWaiting('This draft has changed.');
  if (elements.live.checked) scheduleFeedback();
}

function checkPredicate() {
  if (state.progressRestore) cancelProgressRestore();
  clearTimeout(state.timer);
  if (!state.exercise || elements.editor.disabled) return;
  const tokens = prerequisiteTokens(state.exercise);
  if (tokens === null) { applyPracticeLock(); return; }
  const identity = JSON.stringify([state.exercise.id, state.selection, state.metric, elements.editor.value, tokens]);
  if (state.checkFlight?.identity === identity) return state.checkFlight.promise;
  const flight = { identity, promise: null };
  state.checkFlight = flight;
  flight.promise = runPredicateCheck().finally(() => {
    if (state.checkFlight === flight) state.checkFlight = null;
  });
  return flight.promise;
}

async function runPredicateCheck() {
  state.chatEvidence = null;
  state.progressiveHints = null;
  state.structuralCompletionEvidence = null;
  clearOperationHighlight();
  clearCanonicalForm('Checking this draft…');
  resetBehavior('Waiting for this draft to compile…', 'Waiting');
  state.feedbackAbort?.abort();
  state.explainAbort?.abort();
  const controller = new AbortController();
  state.feedbackAbort = controller;
  const exerciseId = state.exercise.id;
  const revision = ++state.revision;
  tutorChat.refresh();
  const selection = state.selection;
  const body = elements.editor.value;
  const metric = state.metric;
  const tokens = prerequisiteTokens(state.exercise);
  if (tokens === null) { applyPracticeLock(); return; }
  const current = () => revision === state.revision && selection === state.selection && metric === state.metric
    && exerciseId === state.exercise?.id && body === elements.editor.value && !controller.signal.aborted;
  saveDraft();
  setStatus('pending', 'Checking…');
  const pending = node('div', 'pending-message');
  const spinner = node('span', 'spinner');
  spinner.setAttribute('aria-hidden', 'true');
  pending.append(spinner, node('span', '', 'Comparing your predicate with the correct answers…'));
  elements.result.replaceChildren(pending);
  try {
    const { channel } = await ensureChannel(controller.signal);
    if (!current()) return;
    // Fallback omits the channel field entirely; a present JSON null is rejected.
    const channelField = { ...(channel ? { channel } : {}), ...(tokens.length ? { prerequisiteTokens: tokens } : {}) };
    const result = await learnerJSON('api/feedback', { exerciseId, body, revision, metric, ...channelField }, controller.signal, current);
    if (revision !== state.revision || selection !== state.selection || metric !== state.metric || exerciseId !== state.exercise?.id || body !== elements.editor.value || controller.signal.aborted) return;
    if (result.status === 'locked') {
      rejectPrerequisites(result, tokens); return;
    }
    if (tokens.length && !validVersion(result.dependencyContextVersion)) throw new Error('Feedback could not be matched to your prerequisite implementations.');
    // Atom order is registered by SessionBridge.feedbackAtomNames.
    if (result.status === 'ok' && !verifiedPolicy('feedbackSuccess', [
      revision === state.revision, selection === state.selection, metric === state.metric,
      exerciseId === state.exercise?.id, body === elements.editor.value, !controller.signal.aborted,
      result.exerciseId === exerciseId, result.revision === revision,
      result.requestedMetric === metric, result.metric === METRICS[metric].id,
    ])) {
      if (result.exerciseId !== exerciseId || result.revision !== revision) {
        throw new Error('The server returned feedback for a different draft. Check your predicate again.');
      }
      throw new Error('The server returned feedback for a different comparison method. Check your predicate again.');
    }
    if (result.status !== 'ok' && ((result.exerciseId !== undefined && result.exerciseId !== exerciseId)
      || (result.revision !== undefined && result.revision !== revision))) {
      throw new Error('The server returned feedback for a different draft. Check your predicate again.');
    }
    if (result.status !== 'ok' && !responseMetricMatches(result, metric)) {
      throw new Error('The server returned feedback for a different comparison method. Check your predicate again.');
    }
    if (result.status === 'ok' && typeof result.distance === 'number' && Number.isFinite(result.distance) && result.distance >= 0) {
      state.structuralCompletionEvidence = { exerciseId, revision, selection, body, metric,
        distance: result.distance, version: exerciseVersion(state.exercise), responseVersion: result.contentVersion ?? null,
        dependencyContextVersion: result.dependencyContextVersion ?? null };
      state.education = { context: { exerciseId, revision, selection, body, metric }, phase: 'waiting',
        operationIds: (Array.isArray(result.operations) ? result.operations : []).map((_, index) => `operation-${index + 1}`),
        operations: new Map(), instances: new Map() };
    }
    // Successful feedback passed the exact echo checks above. Compatible error
    // responses may omit identity fields and do not create locator context.
    renderFeedback(result, result.exerciseId === exerciseId && result.revision === revision
      ? { exerciseId, revision, selection, body, metric } : null);
    if (result.status === 'ok' && typeof result.distance === 'number' && Number.isFinite(result.distance) && result.distance >= 0 && state.lastHistoryBody !== body) {
      state.history.push({ distance: result.distance, at: Math.max(Date.now(), state.progressSavedAt + 1), basis: METRICS[metric].basis });
      state.history = state.history.slice(-12);
      state.lastHistoryBody = body;
      writeStorage(historyStorageKey(), state.history);
      writeStorage(`${historyStorageKey()}:progress`, state.progressSavedAt);
      renderHistory();
    }
    if (result.status === 'ok' && typeof result.distance === 'number' && Number.isFinite(result.distance) && result.distance >= 0) {
      const evidenceToken = typeof result.evidenceToken === 'string' && /^[a-f0-9]{64}$/.test(result.evidenceToken)
        ? result.evidenceToken : null;
      state.chatEvidence = { context: { exerciseId, revision, selection, body, metric }, evidenceToken, behaviorToken: null };
      tutorChat.refresh();
      await requestBehavior({ exerciseId, body, revision, metric, ...channelField, ...(evidenceToken ? { evidenceToken } : {}) }, selection, true, metric);
    } else if (result.status === 'unsupported' && !(Array.isArray(result.diagnostics)
      && result.diagnostics.some((diagnostic) => diagnostic?.code === 'WORK_LIMIT'))) {
      // A draft over the analysis work budget is not sent on to the solver.
      await requestBehavior({ exerciseId, body, revision, metric, ...channelField }, selection, false, metric);
    }
  } catch (error) {
    if (error.name === 'AbortError' || revision !== state.revision || selection !== state.selection || metric !== state.metric) return;
    renderFeedback({ status: 'error', diagnostics: [{ message: error.message }] });
  } finally {
    if (state.feedbackAbort === controller) state.feedbackAbort = null;
  }
}

function formatNumber(value) {
  return typeof value === 'number' && Number.isFinite(value) ? new Intl.NumberFormat(undefined, { maximumFractionDigits: 4 }).format(value) : '—';
}

function renderOperation(operation, index, sourceContext) {
  const kind = String(operation.kind || 'edit');
  const item = node('li', 'operation-item');
  item.dataset.kind = kind;
  item.dataset.operationId = `operation-${index + 1}`;
  if (operation.aggregate) item.dataset.aggregate = 'true';
  const symbols = { insert: '+', delete: '−', replace: '↔', modify: '↔', update: '↔', reorder: '↕', move: '↕' };
  const icon = node('span', 'operation-icon', symbols[kind] || '~');
  icon.setAttribute('aria-hidden', 'true');
  const detail = node('div', 'operation-detail');
  const title = operation.action || operation.description || operation.summary
    || `${kind.charAt(0).toUpperCase() + kind.slice(1).replaceAll('_', ' ')} ${operation.component || 'structure'}`;
  const heading = node('div', 'operation-heading');
  const select = node('button', 'operation-title operation-select', title);
  select.type = 'button';
  select.setAttribute('aria-controls', 'canonical-panel predicate-editor');
  select.setAttribute('aria-pressed', 'false');
  select.disabled = !sourceContextCurrent(sourceContext);
  select.addEventListener('click', () => selectOperation(operation, sourceContext, item));
  heading.append(node('span', 'operation-step', String(index + 1).padStart(2, '0')), select, node('span', 'operation-cost', `${formatNumber(operation.cost)} edit cost`));
  detail.append(heading);

  if (typeof operation.sourceTerm === 'string' && operation.sourceTerm.length) {
    const fragment = node('div', 'operation-fragment');
    const view = state.metric === 'ast' ? 'your code' : 'canonical form';
    const label = operation.sourceRole === 'insertion-anchor' ? `Where to look · ${view}` : `Part to review · ${view}`;
    fragment.append(node('span', 'operation-fragment-label', label), node('code', '', operation.sourceTerm));
    detail.append(fragment);
  }
  if (typeof operation.sourceOperator === 'string' || typeof operation.replacementOperator === 'string') {
    const operators = node('div', 'operation-operators');
    if (typeof operation.sourceOperator === 'string') {
      const current = node('span', 'operator-chip current-operator');
      current.append(node('span', '', operation.sourceRole === 'insertion-anchor' ? 'Operator here' : 'Your operator'), node('code', '', operation.sourceOperator));
      operators.append(current);
    }
    if (typeof operation.replacementOperator === 'string') {
      const arrow = node('span', 'operator-arrow', '→');
      arrow.setAttribute('aria-hidden', 'true');
      const replacement = node('span', 'operator-chip replacement-operator');
      replacement.append(node('span', '', kind === 'insert' ? 'Operator to add' : 'Suggested operator'), node('code', '', operation.replacementOperator));
      if (operators.children.length) operators.append(arrow);
      operators.append(replacement);
    }
    detail.append(operators);
  }
  if (typeof operation.reason === 'string' && operation.reason) detail.append(node('p', 'operation-reason', operation.reason));
  if (typeof operation.nextStep === 'string' && operation.nextStep) {
    const instruction = node('p', 'operation-next-step');
    instruction.append(node('strong', '', 'Try this: '), node('span', '', operation.nextStep));
    detail.append(instruction);
  }
  if (operation.aggregate) detail.append(node('p', 'operation-aggregate', 'Several edits are grouped here. This hint does not identify one specific change to make in your code.'));

  const path = typeof operation.path === 'string' ? operation.path : JSON.stringify(operation.path || '');
  const structure = node('details', 'operation-structure');
  const structureSummary = node('summary', '', 'Technical location');
  structureSummary.append(node('span', 'chevron', '⌄'));
  structure.append(structureSummary, node('div', 'operation-path', [operation.component, operation.sourceNodeKind, path].filter(Boolean).join(' · ')));
  detail.append(structure);

  detail.append(renderSourceLocator(operation, sourceContext, index, item));
  detail.append(educationSlot('operation', `operation-${index + 1}`));
  item.append(icon, detail);
  return item;
}

function renderFeedback(result, sourceContext = null) {
  state.progressiveHints = null;
  if (result.status !== 'ok') {
    clearCanonicalForm('Canonical form unavailable for this draft. Check the feedback and try again.');
    resetBehavior('Behavioral feedback needs a successful check of this draft.', 'Not checked');
    const statuses = {
      invalid: ['invalid', 'Check syntax', 'Your model needs a small repair.'],
      unsupported: ['invalid', 'Unsupported', 'The checker cannot yet compare this kind of expression.'],
      timeout: ['timeout', 'Timed out', 'This check took too long.'],
      busy: ['pending', 'Server busy', 'The comparison engine is busy.'],
      error: ['error', 'Unavailable', 'The check could not be completed.'],
      engine_error: ['error', 'Engine error', 'The comparison engine could not complete this check.'],
      expired: ['error', 'Session expired', 'Check your predicate again to start a new editing session.'],
      superseded: ['waiting', 'Draft changed', 'Check your current predicate to continue.'],
    };
    const [status, label, title] = statuses[result.status] || statuses.error;
    setStatus(status, label);
    const wrapper = node('div', 'feedback-problem');
    wrapper.append(node('h3', '', title));
    const diagnostics = Array.isArray(result.diagnostics) ? result.diagnostics : [];
    diagnostics.forEach((diagnostic) => {
      const item = node('div', 'diagnostic');
      if (diagnostic.line) item.append(node('span', 'diagnostic-location', `Line ${diagnostic.line}${diagnostic.column ? ` · Column ${diagnostic.column}` : ''}`));
      item.append(node('span', '', diagnostic.message || 'The checker returned a diagnostic.'));
      wrapper.append(item);
    });
    if (!diagnostics.length) wrapper.append(node('p', '', result.status === 'busy' ? 'Wait a moment, then check the predicate again.' : 'Your draft is preserved. Edit the predicate or check again.'));
    elements.result.replaceChildren(wrapper);
    return;
  }
  if (typeof result.distance !== 'number' || !Number.isFinite(result.distance) || result.distance < 0) {
    renderFeedback({ status: 'error', diagnostics: [{ message: 'The engine returned no valid distance for this draft.' }] });
    return;
  }
  setStatus('ok', 'Checked');
  const forms = Array.isArray(result.canonicalForm) ? result.canonicalForm
    : typeof result.canonicalForm === 'string' ? [result.canonicalForm] : [];
  if (state.metric === 'canonical' && forms.length && forms.every(form => typeof form === 'string')) {
    state.canonical = { context: sourceContext, forms };
    renderCanonicalForms();
  } else clearCanonicalForm('The checker returned no canonical form for this draft.');
  const distance = node('div', 'distance-result');
  const ast = state.metric === 'ast';
  distance.dataset.metric = state.metric;
  const caption = node('div', 'distance-caption');
  caption.append(node('span', '', `${ast ? 'AST distance' : 'Distance'} to closest correct predicate`));
  if (result.distance === 0) caption.append(node('span', 'match-badge', ast ? 'Syntax-tree match' : 'Canonical match'));
  const value = node('div', 'distance-value-row');
  value.append(node('span', `distance-value${result.distance === 0 ? ' zero' : ''}`, formatNumber(result.distance)),
    node('span', 'distance-unit', ast ? `node edit${result.distance === 1 ? '' : 's'}` : 'edit cost'));
  distance.append(caption, value, node('p', 'distance-description', result.distance === 0
    ? ast ? 'Your original syntax tree matches one of the known-correct answers.'
      : 'After rewriting both predicates into canonical form, yours matches one of the known-correct answers.'
    : ast ? 'The fewest syntax-tree node edits to a saved correct answer, including the oracle. Each insertion, deletion, or label change costs one edit.'
      : 'The lowest total edit cost among the saved correct answers, including the oracle. Different edits can have different costs.'));
  if (result.comparison?.complete === true && Number.isInteger(result.comparison.poolSize) && result.comparison.poolSize > 0) {
    const count = result.comparison.poolSize;
    distance.append(node('p', 'distance-description', count === 1
      ? 'Compared with the oracle. No other saved correct answers are available for this exercise.'
      : `Compared with all ${formatNumber(count)} saved correct answers, including the oracle. These hints use one of the closest matches.`));
  }
  const components = node('div', `component-grid${ast ? ' ast-components' : ''}`);
  const labels = ast ? { ast: 'Syntax tree edits' } : { temporal: 'Time rules', quantifier: 'Variable rules', matrix: 'Expressions' };
  Object.entries(labels).forEach(([key, label]) => {
    const component = node('div', 'component');
    component.append(node('span', 'component-value', formatNumber(result.breakdown?.[key])), node('span', 'component-name', label));
    components.append(component);
  });
  distance.append(components);
  const operations = node('div', 'operations-section');
  const list = Array.isArray(result.operations) ? result.operations : [];
  const heading = node('div', 'section-label');
  heading.append(node('span', '', 'Edit operations'), node('span', 'section-count', `${list.length} operation${list.length === 1 ? '' : 's'}`));
  operations.append(heading);
  if (list.length) {
    operations.append(node('p', 'operations-intro', ast
      ? 'Each step changes one syntax-tree node. Select a step to find its expression or insertion context in your code.'
      : 'Select an edit to highlight the related expression. Use the operator hint to decide what to try next.'));
    const operationList = node('ol', 'operation-list');
    operationList.id = 'repair-hint-list';
    operationList.append(renderOperation(list[0], 0, sourceContext));
    operations.append(operationList);
    const hints = { context: sourceContext, list, revealed: 1, operationList, count: null, button: null };
    state.progressiveHints = hints;
    if (list.length > 1) {
      const controls = node('div', 'hint-reveal-controls');
      const count = node('p', 'hint-reveal-count');
      count.setAttribute('role', 'status');
      count.setAttribute('aria-live', 'polite');
      const button = node('button', 'button secondary show-next-hint', 'Show next hint');
      button.type = 'button';
      button.setAttribute('aria-controls', operationList.id);
      button.addEventListener('click', () => revealNextHint(hints));
      hints.count = count; hints.button = button;
      updateHintRevealControls(hints);
      controls.append(count, button);
      operations.append(controls);
    }
  } else operations.append(node('p', 'no-operations', result.distance === 0 ? 'No structural edits are needed.' : 'No detailed operations are available for this comparison.'));
  if (result.trace) {
    const reconciliation = result.trace.matchesDistance
      ? `These hints have a total edit cost of ${formatNumber(result.trace.cost)}.`
      : 'Hint costs are shown separately from the distance.';
    const aggregate = result.trace.hasAggregates ? ' Some hints group several edits.' : '';
    const limitation = ast
      ? 'Use these tree edits to decide what to try next. Editing valid Alloy code may require several steps together. Correct expressions stay hidden.'
      : 'Use these hints to decide what to try next. They may not form a complete or shortest repair. Correct expressions stay hidden.';
    operations.append(node('p', 'trace-note', `${reconciliation}${aggregate} ${limitation}`));
  }
  const explanation = node('section', 'explanation-section');
  explanation.id = 'luna-explanation';
  explanation.setAttribute('aria-label', 'AI learning guidance');
  const explanationHeading = node('div', 'section-label');
  explanationHeading.append(node('span', '', 'AI · summary'), node('span', 'ai-badge', 'AI'));
  explanation.append(explanationHeading, node('p', 'explanation-caption', 'Short learning hints appear beside each edit and example.'));
  const explanationBody = node('div', 'explanation-body');
  explanationBody.id = 'luna-explanation-body';
  explanationBody.append(node('p', 'explanation-pending', 'Waiting for the behavioral check before preparing explanations…'));
  explanation.append(explanationBody);
  elements.result.replaceChildren(distance, operations, explanation);
}

function updateHintRevealControls(hints) {
  if (!hints.count || !hints.button) return;
  const remaining = hints.list.length - hints.revealed;
  hints.count.textContent = `${hints.revealed} of ${hints.list.length} hints shown${remaining ? ` · ${remaining} more available` : ''}.`;
  hints.button.textContent = remaining ? 'Show next hint' : 'All hints shown';
  hints.button.disabled = !remaining || !sourceContextCurrent(hints.context);
  hints.button.setAttribute('aria-label', remaining
    ? `Show next hint (${remaining} remaining)` : 'All hints shown');
}

function revealNextHint(hints) {
  if (state.progressiveHints !== hints || !sourceContextCurrent(hints.context)
    || hints.revealed >= hints.list.length) return;
  const index = hints.revealed;
  hints.operationList.append(renderOperation(hints.list[index], index, hints.context));
  hints.revealed += 1;
  updateHintRevealControls(hints);
  renderEducationSummary();
}

function renderEducationSummary() {
  const education = state.education;
  const container = $('#luna-explanation-body');
  if (!container || !education || education.phase !== 'ready'
    || !sourceContextCurrent(education.context)) return;
  const hints = state.progressiveHints;
  const withheld = hints && hints.revealed < hints.list.length;
  container.replaceChildren(node('p', withheld ? 'explanation-withheld' : 'explanation-text', withheld
    ? 'Try the hint you have opened. The AI summary will appear after you choose to show all hints.'
    : education.summary));
}

const BEHAVIOR_CATEGORIES = [
  { id: 'both', title: 'Both accept', oracle: true, student: true, description: 'The oracle and your predicate both accept this instance.' },
  { id: 'undercoverage', title: 'Undercoverage', oracle: true, student: false, description: 'The oracle accepts this instance, but your predicate excludes it.' },
  { id: 'overcoverage', title: 'Overcoverage', oracle: false, student: true, description: 'Your predicate accepts this instance, but the oracle excludes it.' },
  { id: 'neither', title: 'Neither accepts', oracle: false, student: false, description: 'The oracle and your predicate both reject this instance.' },
];

function behaviorMessage(status, label, message) {
  elements.behaviorStatus.dataset.state = status;
  elements.behaviorStatus.textContent = label;
  const paragraph = node('p', 'behavior-message', message);
  if (status === 'pending') {
    const spinner = node('span', 'spinner');
    spinner.setAttribute('aria-hidden', 'true');
    paragraph.prepend(spinner);
  }
  elements.behavior.replaceChildren(paragraph);
}

function resetBehavior(message = 'Check this draft to compare its behavior with the oracle.', label = 'Not checked') {
  state.behaviorAbort?.abort();
  state.behaviorAbort = null;
  state.behaviorEvidence = null;
  resetEducation();
  behaviorMessage('waiting', label, message);
}

function validBehaviorResult(result) {
  const integer = (value, minimum = 0, maximum = 10000) => Number.isInteger(value) && value >= minimum && value <= maximum;
  const label = value => typeof value === 'string' && value.length > 0 && value.length <= 256 && !value.includes('\0');
  const list = (value, maximum, check) => Array.isArray(value) && value.length <= maximum && value.every(check);
  const scope = result.scope, sampling = result.sampling;
  if (result.metric !== 'acgn-reward' || !scope || scope.moduleFacts !== true
    || !integer(scope.overall, 1) || !integer(scope.bitwidth, 1, 32) || !integer(scope.maxSequence)
    || !integer(scope.poolSize, 1, 100) || !integer(scope.minTrace, 1, 10) || !integer(scope.maxTrace, scope.minTrace, 10)
    || !sampling || !['positiveTested', 'positiveAccepted', 'negativeTested', 'negativeRejected'].every(key => integer(sampling[key], 0, 100))
    || !integer(sampling.semanticCounterexamples, 0, 2)
    || sampling.positiveAccepted > sampling.positiveTested || sampling.negativeRejected > sampling.negativeTested) return false;
  if (result.scoreStatus === 'ok') {
    if (typeof result.score !== 'number' || !Number.isFinite(result.score) || result.score < 0 || result.score > 1 || result.scoreReason !== 'OK') return false;
    if (Math.abs(result.score * 1000 - Math.round(result.score * 1000)) > 1e-9) return false;
  } else if (result.scoreStatus !== 'unavailable' || result.score !== null
    || !['ORACLE_POSITIVE_UNSAT', 'ORACLE_NEGATIVE_UNSAT'].includes(result.scoreReason)) return false;
  const validState = (stateData, index) => stateData && stateData.index === index
    && list(stateData.signatures, 128, signature => signature && label(signature.label) && list(signature.atoms, 128, label))
    && list(stateData.relations, 128, relation => relation && label(relation.label) && integer(relation.arity, 1, 8)
      && list(relation.tuples, 512, tuple => Array.isArray(tuple) && tuple.length === relation.arity && tuple.every(label)));
  const validBindings = bindings => Array.isArray(bindings) && bindings.length >= 1 && bindings.length <= 16
    && new Set(bindings.map(binding => binding?.name)).size === bindings.length
    && bindings.reduce((count, binding) => count + (binding?.tuples?.length || 0), 0) <= 2048
    && bindings.every(binding => binding && typeof binding.name === 'string'
      && /^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(binding.name) && integer(binding.arity, 1, 8)
      && list(binding.tuples, 512, tuple => Array.isArray(tuple) && tuple.length === binding.arity && tuple.every(label)));
  const validInstance = instance => instance && integer(instance.traceLength, 1, scope.maxTrace)
    && integer(instance.loopState, -1, instance.traceLength - 1)
    && (instance.truncated === undefined || typeof instance.truncated === 'boolean')
    && (instance.stringsAnonymized === undefined || typeof instance.stringsAnonymized === 'boolean')
    && Array.isArray(instance.states) && instance.states.length >= 1 && instance.states.length <= instance.traceLength
    && (instance.states.length === instance.traceLength || instance.truncated === true)
    && instance.states.every(validState)
    && (instance.parameterBindings === undefined || validBindings(instance.parameterBindings));
  if (!Array.isArray(result.categories) || result.categories.length !== BEHAVIOR_CATEGORIES.length) return false;
  const validCategories = BEHAVIOR_CATEGORIES.every(expected => {
    const matching = result.categories.filter(category => category?.id === expected.id);
    if (matching.length !== 1) return false;
    const category = matching[0];
    return category.oracle === expected.oracle && category.student === expected.student
      && typeof category.enumerationComplete === 'boolean' && list(category.instances, 3, validInstance)
      && (category.enumerationComplete || category.instances.length === 3)
      && (category.status === 'unsat' ? category.instances.length === 0 && category.enumerationComplete
        : category.status === 'sat' && category.instances.length > 0);
  });
  if (!validCategories) return false;
  if (result.score === 1) {
    return sampling.positiveTested > 0 && sampling.negativeTested > 0
      && sampling.positiveAccepted === sampling.positiveTested
      && sampling.negativeRejected === sampling.negativeTested
      && sampling.semanticCounterexamples === 0
      && result.categories.filter(category => ['both', 'neither'].includes(category.id))
        .every(category => category.status === 'sat' && category.instances.length > 0)
      && result.categories.filter(category => ['undercoverage', 'overcoverage'].includes(category.id))
        .every(category => category.status === 'unsat' && category.enumerationComplete && category.instances.length === 0);
  }
  return true;
}

function behaviorTable(caption, headings, rows) {
  const wrapper = node('div', 'behavior-table-scroll');
  wrapper.tabIndex = 0;
  wrapper.setAttribute('role', 'region');
  wrapper.setAttribute('aria-label', caption);
  const table = node('table', 'behavior-table');
  table.append(node('caption', '', caption));
  const header = node('tr');
  headings.forEach(heading => {
    const th = node('th', '', heading);
    th.scope = 'col';
    header.append(th);
  });
  const thead = node('thead');
  thead.append(header);
  const tbody = node('tbody');
  rows.forEach(values => {
    const row = node('tr');
    values.forEach(value => row.append(node('td', '', value)));
    tbody.append(row);
  });
  table.append(thead, tbody);
  wrapper.append(table);
  return wrapper;
}

function renderBehaviorState(container, stateData) {
  container.replaceChildren(renderInstanceGraph(stateData));
  const exactData = node('details', 'behavior-exact-data');
  exactData.append(node('summary', '', 'Exact atoms and relation tables'));
  exactData.append(node('p', 'behavior-data-intro', 'These are the values behind the picture. Each row in a relation table is one connection; read its columns from left to right.'));
  const signatures = node('div', 'behavior-signatures');
  if (stateData.signatures.length) {
    signatures.append(behaviorTable('Atoms in this state', ['Signature', 'Atoms'],
      stateData.signatures.map(signature => [signature.label, signature.atoms.length ? signature.atoms.join(', ') : 'No atoms'])));
  } else signatures.append(node('p', 'behavior-empty', 'No named signatures in this state.'));
  exactData.append(signatures);
  const relations = node('div', 'behavior-relations');
  relations.append(node('h4', '', 'Relations'));
  if (!stateData.relations.length) relations.append(node('p', 'behavior-empty', 'No relations in this state.'));
  stateData.relations.forEach((relation, index) => {
    const detail = node('details', 'behavior-relation');
    detail.open = index < 3;
    detail.append(node('summary', '', `${relation.label} · ${relation.tuples.length} tuple${relation.tuples.length === 1 ? '' : 's'}`));
    if (relation.tuples.length) {
      const headings = relation.arity === 2 ? ['From', 'To']
        : Array.from({ length: relation.arity }, (_, position) => `Atom ${position + 1}`);
      detail.append(behaviorTable(relation.label, headings, relation.tuples));
    } else detail.append(node('p', 'behavior-empty', 'No tuples in this state.'));
    relations.append(detail);
  });
  exactData.append(relations);
  container.append(exactData);
}

function renderBehaviorInstance(container, instance, categoryId, exampleIndex) {
  container.replaceChildren();
  container.append(educationSlot('instance', `${categoryId}-${exampleIndex + 1}`));
  if (instance.parameterBindings) {
    const argumentsView = node('div', 'behavior-arguments');
    argumentsView.append(node('h4', '', 'Parameter values for this example'));
    for (const binding of instance.parameterBindings) {
      const value = binding.tuples.length
        ? binding.tuples.map(tuple => tuple.join(' → ')).join(', ') : 'none';
      argumentsView.append(node('p', '', `${binding.name} = ${value}`));
    }
    container.append(argumentsView);
  }
  if (instance.truncated) container.append(node('p', 'behavior-truncated', 'This instance is only partially displayed; some atoms, relations, tuples, or states were omitted.'));
  if (instance.stringsAnonymized) container.append(node('p', 'behavior-string-note', 'String contents are hidden; atom identities are preserved.'));
  const stateContent = node('div', 'behavior-state-content');
  if (instance.states.length > 1) {
    const controls = node('div', 'behavior-state-controls');
    const id = `behavior-trace-${categoryId}-${exampleIndex}`;
    const label = node('label', '', 'Trace state');
    label.htmlFor = id;
    const select = node('select', 'behavior-state-select');
    select.id = id;
    instance.states.forEach((stateData, index) => select.append(new Option(`State ${stateData.index + 1}`, index)));
    select.addEventListener('change', () => renderBehaviorState(stateContent, instance.states[Number(select.value)]));
    controls.append(label, select, node('span', '', `${instance.traceLength} states${instance.loopState >= 0 ? ` · after the last state, repeat from state ${instance.loopState + 1}` : ''}`));
    container.append(controls);
  } else if (instance.traceLength > 1) {
    container.append(node('p', 'behavior-enumeration', `Only state 1 of ${instance.traceLength} is displayed.${instance.loopState >= 0 ? ` After its last state, the trace repeats from state ${instance.loopState + 1}.` : ''}`));
  } else if (instance.loopState >= 0) container.append(node('p', 'behavior-enumeration', 'One-state trace; this state repeats.'));
  renderBehaviorState(stateContent, instance.states[0]);
  container.append(stateContent);
}

function renderBehaviorCategory(container, category, description) {
  container.replaceChildren(node('h3', '', description.title), node('p', 'behavior-category-description', description.description));
  if (category.status === 'unsat') {
    container.append(node('p', 'behavior-empty', 'No instance within these bounds.'));
    return;
  }
  const count = category.instances.length;
  container.append(node('p', 'behavior-enumeration', category.enumerationComplete
    ? `All ${count} enumerated example${count === 1 ? '' : 's'} shown within these bounds.`
    : `${count} example${count === 1 ? '' : 's'} shown, up to three per category. More may exist within these bounds.`));
  const choices = node('div', 'behavior-example-choices');
  choices.setAttribute('role', 'group');
  choices.setAttribute('aria-label', `${description.title} examples`);
  const content = node('div', 'behavior-instance');
  content.id = `behavior-instance-${category.id}`;
  const buttons = category.instances.map((instance, index) => {
    const button = node('button', 'button behavior-example-choice', `Example ${index + 1}`);
    button.type = 'button';
    button.setAttribute('aria-pressed', String(index === 0));
    button.setAttribute('aria-controls', content.id);
    button.addEventListener('click', () => {
      buttons.forEach((choice, choiceIndex) => choice.setAttribute('aria-pressed', String(choiceIndex === index)));
      renderBehaviorInstance(content, instance, category.id, index);
    });
    return button;
  });
  choices.append(...buttons);
  renderBehaviorInstance(content, category.instances[0], category.id, 0);
  container.append(choices, content);
}

function renderBehavior(result) {
  elements.behaviorStatus.dataset.state = 'ok';
  elements.behaviorStatus.textContent = 'Checked';
  const summary = node('div', 'behavior-summary');
  const score = node('div', 'behavior-score-block');
  const available = result.scoreStatus === 'ok';
  const rounded = available ? (Math.round((result.score + Number.EPSILON) * 1000) / 1000).toFixed(3) : 'Unavailable';
  score.append(node('span', `behavior-score${available ? '' : ' unavailable'}`, rounded), node('span', 'behavior-score-range', available ? 'out of 1.000' : 'within these bounds'));
  const explanation = node('div', 'behavior-score-description');
  explanation.append(node('p', '', 'Behavioral similarity against the exercise oracle, using continuously updated instance samples. Structural distance above uses the closest correct predicate.'), node('span', 'behavior-facts', 'Model facts enforced'));
  if (!available) explanation.append(node('p', 'behavior-score-reason', result.scoreReason === 'ORACLE_POSITIVE_UNSAT'
    ? 'The oracle accepts no instance within these bounds, so the score is unavailable.'
    : 'The oracle rejects no instance within these bounds, so the score is unavailable.'));
  explanation.append(node('p', 'behavior-rounding-note', '1.000 requires completed checks with no undercoverage or overcoverage within these bounds.'));
  summary.append(score, explanation);
  const scope = result.scope;
  const bounds = node('p', 'behavior-scope', `Bounds: default atom scope ${scope.overall} · ${scope.bitwidth}-bit integers · sequence bound ${scope.maxSequence} · traces ${scope.minTrace}–${scope.maxTrace} states · sample pool ${scope.poolSize}.`);
  const sampling = result.sampling;
  const samples = node('p', 'behavior-sampling', `Oracle-accepted samples your predicate accepts: ${sampling.positiveAccepted}/${sampling.positiveTested}. Oracle-rejected samples your predicate rejects: ${sampling.negativeRejected}/${sampling.negativeTested}. Extra counterexample correction: ${sampling.semanticCounterexamples}.`);
  const note = node('p', 'behavior-bound-note', 'This bounded score and these examples do not prove equivalence. Each example satisfies the model facts.');
  const navigation = node('div', 'behavior-category-choices');
  navigation.setAttribute('role', 'group');
  navigation.setAttribute('aria-label', 'Behavior categories');
  const content = node('div', 'behavior-category-content');
  content.id = 'behavior-category-content';
  const buttons = BEHAVIOR_CATEGORIES.map((description, index) => {
    const category = result.categories.find(item => item.id === description.id);
    const button = node('button', 'behavior-category-choice');
    button.type = 'button';
    button.dataset.category = category.id;
    button.setAttribute('aria-pressed', String(index === 0));
    button.setAttribute('aria-controls', content.id);
    button.append(node('strong', '', description.title), node('span', 'behavior-truth', `Oracle: ${category.oracle} · Yours: ${category.student}`),
      node('span', 'behavior-category-count', category.status === 'unsat' ? 'No instance within bounds' : `${category.instances.length} example${category.instances.length === 1 ? '' : 's'}`));
    button.addEventListener('click', () => {
      buttons.forEach((choice, choiceIndex) => choice.setAttribute('aria-pressed', String(choiceIndex === index)));
      renderBehaviorCategory(content, category, description);
    });
    return button;
  });
  navigation.append(...buttons);
  renderBehaviorCategory(content, result.categories.find(category => category.id === 'both'), BEHAVIOR_CATEGORIES[0]);
  elements.behavior.replaceChildren(summary, bounds, samples, note, navigation, content);
}

async function requestBehavior(payload, selection, explain = false, metric = state.metric) {
  state.behaviorAbort?.abort();
  state.behaviorEvidence = null;
  const controller = new AbortController();
  state.behaviorAbort = controller;
  const current = () => payload.revision === state.revision && selection === state.selection
    && metric === state.metric
    && payload.exerciseId === state.exercise?.id && payload.body === elements.editor.value && !controller.signal.aborted;
  behaviorMessage('pending', 'Analyzing…', 'Comparing behavior with the oracle and finding examples within the model bounds…');
  let allowExplanation = true;
  try {
    if (!current()) return;
    const result = await learnerJSON('api/behavior', payload, controller.signal, current);
    if (!current()) return;
    if (result.status === 'locked') {
      rejectPrerequisites(result, payload.prerequisiteTokens || []); allowExplanation = false; return;
    }
    if (['superseded', 'expired'].includes(result.status)) allowExplanation = false;
    if (result.exerciseId !== payload.exerciseId || result.revision !== payload.revision) throw new Error('Behavioral feedback for this draft is unavailable. Check your predicate again.');
    if (result.status !== 'ok') {
      const errors = {
        timeout: ['timeout', 'Timed out', 'The behavioral check timed out. No score or examples are available for this draft.'],
        busy: ['waiting', 'Server busy', 'The behavioral checker is busy. Check your predicate again shortly.'],
        unsupported: ['error', 'Unavailable', typeof result.message === 'string' && result.message
          ? result.message : 'Behavioral analysis is unavailable for this model structure.'],
        invalid: ['error', 'Unavailable', 'The behavioral checker could not compile this draft.'],
      };
      behaviorMessage(...(errors[result.status] || ['error', 'Unavailable', 'Behavioral analysis is unavailable for this draft. Check your predicate again.']));
      return;
    }
    if (!validBehaviorResult(result)) throw new Error('The behavioral checker returned an invalid result. Check your predicate again.');
    const structuralContext = state.structuralCompletionEvidence?.dependencyContextVersion;
    if ((structuralContext && result.dependencyContextVersion !== structuralContext)
      || (payload.prerequisiteTokens?.length && !validVersion(result.dependencyContextVersion))) {
      throw new Error('Behavioral feedback could not be matched to your prerequisite implementations.');
    }
    if (recordSolvedExercise(result, payload, selection, metric)) recordVerifiedImplementation(result, payload, metric);
    state.behaviorEvidence = { token: typeof result.behaviorToken === 'string' && /^[a-f0-9]{64}$/.test(result.behaviorToken) ? result.behaviorToken : null,
      instanceIds: result.categories.flatMap(category => category.instances.map((_, index) => `${category.id}-${index + 1}`)) };
    state.chatEvidence = { context: { exerciseId: payload.exerciseId, revision: payload.revision, selection, body: payload.body, metric },
      evidenceToken: payload.evidenceToken || null, behaviorToken: state.behaviorEvidence.token };
    tutorChat.refresh();
    renderBehavior(result);
  } catch (error) {
    if (error.name === 'AbortError' || !current()) return;
    state.behaviorEvidence = null;
    behaviorMessage('error', 'Unavailable', error.message);
  } finally {
    if (state.behaviorAbort === controller) state.behaviorAbort = null;
    if (current() && explain && allowExplanation) {
      const token = state.behaviorEvidence?.token;
      await requestExplanation({ ...payload, metric, ...(token ? { behaviorToken: token } : {}) }, selection);
    } else if (current() && explain && !allowExplanation) {
      const container = $('#luna-explanation-body');
      if (container) renderExplanationUnavailable(container,
        'This check has expired or changed. Check your predicate again to refresh guidance.', payload, selection, false);
    }
  }
}

function resetEducation() {
  state.explainAbort?.abort();
  state.explainAbort = null;
  state.education = null;
  document.querySelectorAll('.education-slot').forEach(slot => slot.remove());
}

function educationSlot(kind, id) {
  const slot = node('aside', `education-slot ${kind}-explanation`);
  slot.dataset.educationKind = kind;
  slot.dataset.educationId = id;
  slot.append(node('span', 'education-label', kind === 'operation' ? 'AI · edit hint' : 'AI · example hint'), node('div', 'education-copy'));
  updateEducationSlot(slot);
  return slot;
}

function updateEducationSlot(slot) {
  const education = state.education;
  const kind = slot.dataset.educationKind;
  const current = education && sourceContextCurrent(education.context);
  const description = current && (kind === 'operation' ? education.operations : education.instances).get(slot.dataset.educationId);
  const instanceBound = kind !== 'instance' || (education?.behaviorToken && education.behaviorToken === state.behaviorEvidence?.token);
  if (description && instanceBound) {
    slot.querySelector('.education-copy').replaceChildren(node('p', 'education-description', description));
  } else {
    const unavailable = !current || ['unavailable', 'ready'].includes(education.phase);
    slot.querySelector('.education-copy').replaceChildren(node('p', unavailable ? 'education-unavailable' : 'education-pending',
      unavailable ? 'AI explanation unavailable. The checked result is still shown.' : 'Preparing a short explanation…'));
  }
}

function refreshEducationSlots() {
  document.querySelectorAll('.education-slot').forEach(updateEducationSlot);
}

function validEducation(explanation, operationIds, instanceIds) {
  const validText = (value, limit) => typeof value === 'string' && value.trim().length > 0
    && Array.from(value).length <= limit && !value.includes('\0');
  const covers = (items, ids) => {
    if (!Array.isArray(items) || items.length !== ids.length) return false;
    const expected = new Set(ids);
    return items.every(item => item && expected.delete(item.id) && validText(item.description, 360));
  };
  return validText(explanation.summary, 700) && covers(explanation.operations, operationIds) && covers(explanation.instances, instanceIds);
}

async function requestExplanation(payload, selection) {
  const education = state.education;
  if (!education || !sourceContextCurrent(education.context) || payload.body !== education.context.body
    || payload.revision !== education.context.revision || selection !== education.context.selection
    || payload.exerciseId !== education.context.exerciseId || payload.metric !== education.context.metric) return;
  if (typeof payload.evidenceToken !== 'string' || !/^[a-f0-9]{64}$/.test(payload.evidenceToken)) {
    const container = $('#luna-explanation-body');
    if (container) renderExplanationUnavailable(container,
      'Guidance is unavailable for this check. Your structural feedback and examples remain available.', payload, selection, false);
    return;
  }
  const behaviorToken = payload.behaviorToken || null;
  if (behaviorToken !== (state.behaviorEvidence?.token || null)) return;
  state.explainAbort?.abort();
  const controller = new AbortController();
  state.explainAbort = controller;
  const current = () => state.education === education && sourceContextCurrent(education.context)
    && behaviorToken === (state.behaviorEvidence?.token || null) && !controller.signal.aborted;
  const instanceIds = behaviorToken ? [...state.behaviorEvidence.instanceIds] : [];
  education.phase = 'pending';
  education.behaviorToken = behaviorToken;
  education.operations.clear(); education.instances.clear();
  refreshEducationSlots();
  $('#luna-explanation-body')?.replaceChildren(node('p', 'explanation-pending', 'Preparing short explanations…'));
  try {
    if (!current()) return;
    const explanation = await learnerJSON('api/explain', payload, controller.signal, current);
    if (!current()) return;
    const structuralContext = state.structuralCompletionEvidence?.dependencyContextVersion;
    if (explanation.status === 'ok' && structuralContext && explanation.dependencyContextVersion !== structuralContext) {
      throw new Error('Guidance could not be matched to your prerequisite implementations. Check again.');
    }
    if (explanation.status !== 'ok' && (explanation.exerciseId !== payload.exerciseId || explanation.revision !== payload.revision)) throw new Error('Guidance for this draft is unavailable. Please check again.');
    if (explanation.status !== 'ok' && !responseMetricMatches(explanation, payload.metric)) throw new Error('Guidance for this comparison method is unavailable. Check again or retry guidance.');
    const container = $('#luna-explanation-body');
    if (!container) return;
    if (explanation.status === 'ok') {
      // Atom order is registered by SessionBridge.guidanceAtomNames.
      const context = education.context;
      if (!verifiedPolicy('guidanceSuccess', [
        context.revision === state.revision, context.selection === state.selection, context.metric === state.metric,
        context.exerciseId === state.exercise?.id, context.body === elements.editor.value, !elements.editor.disabled,
        state.education === education, behaviorToken === (state.behaviorEvidence?.token || null), !controller.signal.aborted,
        explanation.exerciseId === context.exerciseId, explanation.revision === context.revision,
        explanation.requestedMetric === context.metric, (explanation.behaviorToken ?? null) === behaviorToken,
      ])
        || !validEducation(explanation, education.operationIds, instanceIds)) throw new Error('AI explanations could not be matched to the displayed edits and examples. Check again or retry guidance.');
      education.phase = 'ready';
      education.summary = explanation.summary;
      education.operations = new Map(explanation.operations.map(item => [item.id, item.description]));
      education.instances = new Map(explanation.instances.map(item => [item.id, item.description]));
      refreshEducationSlots();
      renderEducationSummary();
    } else renderExplanationUnavailable(container, explanation.message || 'AI guidance is currently unavailable. Structural feedback remains available.', payload, selection);
  } catch (error) {
    if (error.name === 'AbortError' || !current()) return;
    const container = $('#luna-explanation-body');
    if (container) renderExplanationUnavailable(container, error.message, payload, selection);
  } finally {
    if (state.explainAbort === controller) state.explainAbort = null;
  }
}

function renderExplanationUnavailable(container, message, payload, selection, retryable = true) {
  if (state.education) {
    state.education.phase = 'unavailable';
    state.education.operations.clear(); state.education.instances.clear();
    refreshEducationSlots();
  }
  const retry = node('button', 'button text-button explanation-retry', 'Retry guidance');
  retry.type = 'button';
  retry.addEventListener('click', () => {
    requestExplanation(payload, selection);
  });
  container.replaceChildren(node('p', 'explanation-unavailable', message), ...(retryable ? [retry] : []));
}

function renderHistory() {
  const container = $('#history-list');
  $('#history-count').textContent = `${state.history.length} check${state.history.length === 1 ? '' : 's'}`;
  if (!state.history.length) {
    container.replaceChildren(node('p', 'history-empty', 'Each successful check is a new point on your path.'));
    return;
  }
  const chart = node('div', 'history-chart');
  chart.setAttribute('role', 'img');
  chart.setAttribute('aria-label', `Recent ${METRICS[state.metric].label.toLowerCase()} distances: ${state.history.map((item) => formatNumber(item.distance)).join(', ')}`);
  const max = Math.max(1, ...state.history.map((item) => item.distance));
  state.history.forEach((item, index) => {
    const bar = node('span', 'history-bar');
    bar.style.height = `${Math.max(7, (item.distance / max) * 100)}%`;
    bar.dataset.zero = String(item.distance === 0);
    bar.title = `Check ${index + 1}: distance ${formatNumber(item.distance)} · ${new Date(item.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    chart.append(bar);
  });
  const summary = node('div', 'history-summary');
  const latest = state.history.at(-1);
  summary.append(node('span', '', `Latest · ${new Date(latest.at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`), node('strong', '', `Best distance ${formatNumber(Math.min(...state.history.map((item) => item.distance)))}`));
  container.replaceChildren(chart, summary);
}

function showError(message, retry) {
  const error = $('#startup-error');
  error.replaceChildren(node('span', '', String(message)));
  if (retry) {
    const button = node('button', 'button secondary', 'Try again');
    button.type = 'button';
    button.addEventListener('click', retry);
    error.append(button);
  }
  error.hidden = false;
}

function downloadModel() {
  const exercise = state.exercise;
  if (!exercise) return;
  let environment;
  try { environment = resolvedEnvironment(exercise); } catch (error) { progressStatus(error.message); return; }
  const fullModel = environment.environmentBefore + (exercise.predicateHeader || '') + '{\n' + elements.editor.value + '\n}' + environment.environmentAfter;
  const blob = new Blob([fullModel], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${String(exercise.id).replace(/[^a-zA-Z0-9._-]/g, '_')}.als`;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  showToast('Downloaded your predicate with its full model environment.');
}

async function initialize() {
  renderWorkspaceBadge();
  const preference = readStorage('live', true);
  elements.live.checked = typeof preference === 'boolean' ? preference : true;
  const metric = readStorage('metric', 'canonical');
  state.metric = Object.hasOwn(METRICS, metric) ? metric : 'canonical';
  renderMetric();
  if (!/Mac|iPhone|iPad/.test(navigator.platform)) $('.keyboard-hint').textContent = 'Ctrl ↵';
  elements.search.addEventListener('input', renderExercises);
  elements.group.addEventListener('change', renderExercises);
  elements.previousExercise.addEventListener('click', () => navigateExercise(-1));
  elements.nextExercise.addEventListener('click', () => navigateExercise(1));
  elements.metric.addEventListener('change', selectMetric);
  $('#save-progress').addEventListener('click', saveProgressFile);
  $('#load-progress').addEventListener('click', () => $('#progress-file').click());
  $('#progress-file').addEventListener('change', event => loadProgressFile(event.target.files[0]));
  $('#restore-progress').addEventListener('click', restoreProgressChecks);
  $('#cancel-restore-progress').addEventListener('click', () => cancelProgressRestore());
  elements.editor.addEventListener('input', onEdit);
  elements.editor.addEventListener('scroll', syncEditorOverlay);
  new ResizeObserver(syncEditorOverlay).observe(elements.editor);
  new ResizeObserver(keepSelectedExerciseVisible).observe(elements.list);
  $('#clear-source-highlight').addEventListener('click', clearOperationHighlight);
  ['click', 'keyup', 'select'].forEach((event) => elements.editor.addEventListener(event, updateCursor));
  elements.editor.addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); checkPredicate(); }
    if (event.isComposing || event.defaultPrevented) return;
    if (event.key === 'Enter' && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault();
      const edit = enterIndent(elements.editor.value, elements.editor.selectionStart, elements.editor.selectionEnd);
      replaceEditorText(edit.text, edit.start, edit.end, edit.cursor);
    }
    const closing = !event.ctrlKey && !event.metaKey && !event.altKey
      ? closingIndent(elements.editor.value, elements.editor.selectionStart, elements.editor.selectionEnd, event.key) : null;
    if (closing) {
      event.preventDefault();
      replaceEditorText(closing.text, closing.start, closing.end);
    }
    if (event.key === 'Escape') {
      elements.editor.dataset.releaseTab = 'true';
      showToast('Press Tab to leave the editor.');
    }
    if (event.key === 'Tab' && !event.shiftKey && elements.editor.dataset.releaseTab !== 'true') {
      event.preventDefault();
      const start = elements.editor.selectionStart;
      const end = elements.editor.selectionEnd;
      replaceEditorText('  ', start, end);
    }
    if (event.key !== 'Escape') delete elements.editor.dataset.releaseTab;
  });
  elements.check.addEventListener('click', checkPredicate);
  elements.reset.addEventListener('click', () => {
    if (!state.exercise || elements.editor.disabled) return;
    const previous = elements.editor.value;
    // Reset also clears feedback when the body already equals the starter.
    // A repeated reset must retain the earlier recoverable learner draft.
    const recovery = previous === state.exercise.starter ? state.resetRecovery
      : { body: previous, exerciseId: state.exercise.id, version: exerciseVersion(state.exercise) };
    elements.editor.value = state.exercise.starter;
    onEdit();
    state.resetRecovery = recovery;
    $('#reset-recovery').hidden = !recovery;
    elements.editor.focus();
    if (recovery) showToast('Starter restored. Use Undo reset to recover your draft.');
  });
  $('#undo-reset').addEventListener('click', () => {
    const saved = state.resetRecovery;
    if (!saved || !state.exercise || elements.editor.disabled || saved.exerciseId !== state.exercise.id
      || saved.version !== exerciseVersion(state.exercise) || elements.editor.value !== state.exercise.starter) return;
    elements.editor.value = saved.body;
    onEdit();
    elements.editor.focus();
    showToast('Your previous draft is restored.');
  });
  elements.live.addEventListener('change', () => {
    cancelProgressRestore();
    writeStorage('live', elements.live.checked);
    if (elements.live.checked) scheduleFeedback(0);
    else { clearTimeout(state.timer); }
  });
  elements.download.addEventListener('click', downloadModel);
  $('#indent-button').addEventListener('click', () => {
    if (elements.editor.disabled) return;
    const body = indentAlloy(elements.editor.value);
    if (body !== elements.editor.value) {
      replaceEditorText(body, 0, elements.editor.value.length);
    }
    elements.editor.focus();
  });
  window.addEventListener('beforeunload', () => {
    cancelProgressRestore();
    if (!elements.editor.disabled) saveDraft();
    if (state.checkFlight || state.explainAbort) cancelChannel(state.revision + 1);
  });
  await loadExercises();
}

async function loadExercises() {
  cancelProgressRestore();
  $('#startup-error').hidden = true;
  try {
    const data = await fetchJSON('api/exercises', { cache: 'no-cache' });
    if (!Array.isArray(data.exercises)) throw new Error('The exercise catalog could not be read.');
    state.exercises = data.exercises;
    loadSolvedExercises();
    const progress = readStorage('progress', null);
    if (progress !== null) {
      try {
        applyProgress(parseProgress(JSON.stringify(progress)));
        await validateCompletionTokens();
      } catch (error) { state.validCompletionTokens.clear(); progressStatus(error.message); }
    }
    $('#exercise-count').textContent = String(state.exercises.length);
    elements.group.replaceChildren(new Option('All models', ''));
    [...new Set(state.exercises.map((exercise) => exercise.group).filter(Boolean))].forEach((group) => elements.group.append(new Option(group, group)));
    renderExercises();
    if (!state.exercises.length) {
      showError('No exercises are installed. Add a server-side exercise to begin.');
      return;
    }
    const requested = new URL(window.location.href).searchParams.get('exercise') || readStorage('lastExercise', null);
    const selected = state.exercises.find((exercise) => exercise.id === requested)
      || state.exercises.find((exercise) => exercise.id === 'graphs-inv1') || state.exercises[0];
    await selectExercise(selected.id);
  } catch (error) {
    elements.list.replaceChildren(node('p', 'sidebar-message', 'The exercise catalog is unavailable.'));
    showError(error.message, loadExercises);
  }
}

initialize();

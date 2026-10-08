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
  channel: null, channelPromise: null, checkFlight: null,
};
const STORAGE_PREFIX = 'alloy-studio:v1:';
const MAX_SOLVED_EXERCISES = 1000;
const APP_BASE = new URL('.', import.meta.url);
const METRICS = {
  canonical: { id: 'acgn-fast-rewrite-canonical-distance', basis: 'nearest-known-correct-v1', label: 'Canonical' },
  ast: { id: 'acgn-raw-ast-zhang-shasha-distance', basis: 'nearest-known-correct-raw-ast-v1', label: 'AST' },
};

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
  try { localStorage.setItem(STORAGE_PREFIX + key, JSON.stringify(value)); }
  catch { state.storageAvailable = false; }
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

function recordSolvedExercise(result, payload, selection, metric) {
  const structural = state.structuralCompletionEvidence;
  if (!structural || structural.distance !== 0 || structural.exerciseId !== payload.exerciseId
    || structural.revision !== payload.revision || structural.selection !== selection
    || structural.body !== payload.body || structural.metric !== metric
    || payload.revision !== state.revision || selection !== state.selection || metric !== state.metric
    || payload.exerciseId !== state.exercise?.id || payload.body !== elements.editor.value
    || structural.version !== exerciseVersion(state.exercise)
    || structural.responseVersion !== structural.version || (result.contentVersion ?? null) !== structural.version
    || result.scoreStatus !== 'ok' || result.scoreReason !== 'OK' || result.score !== 1
    || result.sampling.semanticCounterexamples !== 0
    || result.sampling.positiveTested <= 0 || result.sampling.negativeTested <= 0
    || result.sampling.positiveAccepted !== result.sampling.positiveTested
    || result.sampling.negativeRejected !== result.sampling.negativeTested
    || !['both', 'neither'].every(id => result.categories.some(category => category.id === id
      && category.status === 'sat' && category.instances.length > 0))
    || !['undercoverage', 'overcoverage'].every(id => result.categories.some(category => category.id === id
      && category.status === 'unsat' && category.enumerationComplete && category.instances.length === 0))) return;
  state.solved.set(payload.exerciseId, { version: structural.version, at: Date.now(), metric });
  saveSolvedExercises();
  renderExercises();
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
  const saved = state.exercise ? readStorage(historyStorageKey(), []) : [];
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
  invalidateFeedback();
  state.metric = metric;
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
  state.progressiveHints = null;
  state.structuralCompletionEvidence = null;
  clearOperationHighlight();
  resetBehavior();
  const pending = state.checkFlight || state.feedbackAbort || state.behaviorAbort || state.explainAbort;
  state.revision += 1;
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
  const ready = index >= 0 && !elements.editor.disabled;
  elements.previousExercise.disabled = !ready || index === 0;
  elements.nextExercise.disabled = !ready || index === visible.length - 1;
  const filtered = Boolean(elements.search.value.trim() || elements.group.value);
  elements.exercisePosition.textContent = !visible.length ? 'No matching exercises'
    : state.loadingExercise ? 'Loading exercise…'
      : index < 0 ? (state.exercise ? 'Select a matching exercise' : 'Choose an exercise')
        : elements.editor.disabled ? 'Choose an exercise to continue'
        : `${index + 1} of ${visible.length}${filtered ? ' matching exercises' : ' exercises'}`;
  elements.previousExercise.title = ready && index > 0 ? `Previous: ${visible[index - 1].title}` : 'Previous exercise';
  elements.nextExercise.title = ready && index < visible.length - 1 ? `Next: ${visible[index + 1].title}` : 'Next exercise';
}

function navigateExercise(offset) {
  if (elements.editor.disabled) return;
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
    button.dataset.solved = String(solved);
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
  writeStorage(`draft:${state.exercise.id}`, { body: elements.editor.value, source: state.exercise.source?.sha256, updatedAt: Date.now() });
  elements.draft.textContent = state.storageAvailable ? 'Draft saved' : 'Draft in memory';
}

function renderContext() {
  if (!state.exercise) return;
  const exercise = state.exercise;
  renderAlloyCode($('#environment-code'), exercise.environmentBefore + exercise.predicateHeader
    + '{\n  // TODO: write the predicate body in the editor above\n}' + exercise.environmentAfter);
}

async function selectExercise(id) {
  if (state.exercise?.id === id && !elements.editor.disabled) return;
  if (state.exercise && !elements.editor.disabled) saveDraft();
  invalidateFeedback();
  const selection = ++state.selection;
  state.detailAbort?.abort();
  const controller = new AbortController();
  state.detailAbort = controller;
  state.loadingExercise = true;
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
      for (const field of ['title', 'group', 'predicate', 'description', 'contentVersion']) summary[field] = exercise[field];
    }
    const completion = state.solved.get(exercise.id);
    if (completion && completion.version !== exerciseVersion(exercise)) {
      state.solved.delete(exercise.id);
      saveSolvedExercises();
    }
    state.loadingExercise = false;
    state.context = 'before';
    loadHistory();
    const draft = readStorage(`draft:${id}`, null);
    elements.editor.value = draft && typeof draft.body === 'string' && draft.source === exercise.source?.sha256 ? draft.body : exercise.starter;
    elements.editor.disabled = false;
    elements.check.disabled = false;
    elements.reset.disabled = false;
    elements.download.disabled = false;
    elements.draft.textContent = draft && elements.editor.value === draft.body ? 'Draft restored' : 'Starter predicate';
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
    if (elements.live.checked) scheduleFeedback(200);
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
  invalidateFeedback();
  updateEditor();
  saveDraft();
  showWaiting('This draft has changed.');
  if (elements.live.checked) scheduleFeedback();
}

function checkPredicate() {
  clearTimeout(state.timer);
  if (!state.exercise || elements.editor.disabled) return;
  const identity = JSON.stringify([state.exercise.id, state.selection, state.metric, elements.editor.value]);
  if (state.checkFlight?.identity === identity) return state.checkFlight.promise;
  const flight = { identity, promise: null };
  state.checkFlight = flight;
  flight.promise = runPredicateCheck().finally(() => {
    if (state.checkFlight === flight) state.checkFlight = null;
  });
  return flight.promise;
}

async function runPredicateCheck() {
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
  const selection = state.selection;
  const body = elements.editor.value;
  const metric = state.metric;
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
    const channelField = channel ? { channel } : {};
    const result = await learnerJSON('api/feedback', { exerciseId, body, revision, metric, ...channelField }, controller.signal, current);
    if (revision !== state.revision || selection !== state.selection || metric !== state.metric || exerciseId !== state.exercise?.id || body !== elements.editor.value || controller.signal.aborted) return;
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
        distance: result.distance, version: exerciseVersion(state.exercise), responseVersion: result.contentVersion ?? null };
      state.education = { context: { exerciseId, revision, selection, body, metric }, phase: 'waiting',
        operationIds: (Array.isArray(result.operations) ? result.operations : []).map((_, index) => `operation-${index + 1}`),
        operations: new Map(), instances: new Map() };
    }
    // Successful feedback passed the exact echo checks above. Compatible error
    // responses may omit identity fields and do not create locator context.
    renderFeedback(result, result.exerciseId === exerciseId && result.revision === revision
      ? { exerciseId, revision, selection, body, metric } : null);
    if (result.status === 'ok' && typeof result.distance === 'number' && Number.isFinite(result.distance) && result.distance >= 0 && state.lastHistoryBody !== body) {
      state.history.push({ distance: result.distance, at: Date.now(), basis: METRICS[metric].basis });
      state.history = state.history.slice(-12);
      state.lastHistoryBody = body;
      writeStorage(historyStorageKey(), state.history);
      renderHistory();
    }
    if (result.status === 'ok' && typeof result.distance === 'number' && Number.isFinite(result.distance) && result.distance >= 0) {
      const evidenceToken = typeof result.evidenceToken === 'string' && /^[a-f0-9]{64}$/.test(result.evidenceToken)
        ? result.evidenceToken : null;
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
  explanation.setAttribute('aria-label', 'Luna learning guidance');
  const explanationHeading = node('div', 'section-label');
  explanationHeading.append(node('span', '', 'Luna · summary'), node('span', 'ai-badge', 'AI'));
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
    ? 'Try the hint you have opened. Luna’s summary will appear after you choose to show all hints.'
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
  const validInstance = instance => instance && integer(instance.traceLength, 1, scope.maxTrace)
    && integer(instance.loopState, -1, instance.traceLength - 1)
    && (instance.truncated === undefined || typeof instance.truncated === 'boolean')
    && (instance.stringsAnonymized === undefined || typeof instance.stringsAnonymized === 'boolean')
    && Array.isArray(instance.states) && instance.states.length >= 1 && instance.states.length <= instance.traceLength
    && (instance.states.length === instance.traceLength || instance.truncated === true)
    && instance.states.every(validState);
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
    const { evidenceToken: structuralEvidence, ...behaviorPayload } = payload;
    const result = await learnerJSON('api/behavior', behaviorPayload, controller.signal, current);
    if (!current()) return;
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
    recordSolvedExercise(result, payload, selection, metric);
    state.behaviorEvidence = { token: typeof result.behaviorToken === 'string' && /^[a-f0-9]{64}$/.test(result.behaviorToken) ? result.behaviorToken : null,
      instanceIds: result.categories.flatMap(category => category.instances.map((_, index) => `${category.id}-${index + 1}`)) };
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
  slot.append(node('span', 'education-label', kind === 'operation' ? 'Luna · edit hint' : 'Luna · example hint'), node('div', 'education-copy'));
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
  const fullModel = (exercise.environmentBefore || '') + (exercise.predicateHeader || '') + '{\n' + elements.editor.value + '\n}' + (exercise.environmentAfter || '');
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
  elements.editor.addEventListener('input', onEdit);
  elements.editor.addEventListener('scroll', syncEditorOverlay);
  new ResizeObserver(syncEditorOverlay).observe(elements.editor);
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
    if (!state.exercise) return;
    const previous = elements.editor.value;
    elements.editor.value = state.exercise.starter;
    onEdit();
    elements.editor.focus();
    if (previous !== elements.editor.value) showToast('Restored the starter predicate.');
  });
  elements.live.addEventListener('change', () => {
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
    if (!elements.editor.disabled) saveDraft();
    if (state.checkFlight || state.explainAbort) cancelChannel(state.revision + 1);
  });
  await loadExercises();
}

async function loadExercises() {
  $('#startup-error').hidden = true;
  try {
    const data = await fetchJSON('api/exercises', { cache: 'no-cache' });
    if (!Array.isArray(data.exercises)) throw new Error('The exercise catalog could not be read.');
    state.exercises = data.exercises;
    loadSolvedExercises();
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

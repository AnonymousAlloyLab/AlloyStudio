const $ = (selector) => document.querySelector(selector);
const elements = {
  search: $('#exercise-search'), group: $('#group-filter'), list: $('#exercise-list'),
  editor: $('#predicate-editor'), check: $('#check-button'), reset: $('#reset-button'),
  download: $('#download-button'), live: $('#live-feedback'), result: $('#feedback-result'),
  status: $('#feedback-state'), lines: $('#line-numbers'), draft: $('#draft-status'),
  highlight: $('#source-highlight'), locationBar: $('#source-location-bar'), locationStatus: $('#source-location-status'),
  canonical: $('#canonical-content'), canonicalStatus: $('#canonical-location-status'),
};
const state = {
  exercises: [], exercise: null, revision: 0, selection: 0,
  feedbackAbort: null, explainAbort: null, detailAbort: null, timer: null, context: 'before',
  history: [], lastHistoryBody: null, feedbackStatus: 'waiting', storageAvailable: true,
  sourceHighlight: null, canonical: null,
};
const STORAGE_PREFIX = 'alloy-studio:v1:';
const APP_BASE = new URL('.', import.meta.url);
const COMPARISON_VERSION = 'nearest-known-correct-v1';

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
  clearOperationHighlight();
  state.revision += 1;
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
    ? 'Live feedback will find the closest predicate in the private correct pool, including the oracle.'
    : 'Check your predicate to see its distance and structural edit operations.'));
  elements.result.replaceChildren(wrapper);
}

async function fetchJSON(url, options = {}) {
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
  return data;
}

function renderExercises() {
  const query = elements.search.value.trim().toLowerCase();
  const group = elements.group.value;
  const visible = state.exercises.filter((exercise) => (!group || exercise.group === group)
    && [exercise.title, exercise.predicate, exercise.group, exercise.description].join(' ').toLowerCase().includes(query));
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
    button.setAttribute('aria-label', `${exercise.title}, ${exercise.group || 'Exercise'}`);
    if (state.exercise?.id === exercise.id) button.setAttribute('aria-current', 'page');
    const text = node('span', 'exercise-item-text');
    text.append(node('span', 'exercise-item-title', exercise.title), node('span', 'exercise-item-predicate', exercise.predicate));
    button.append(node('span', 'exercise-item-number', String(state.exercises.indexOf(exercise) + 1).padStart(2, '0')), text);
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
  syncEditorOverlay();
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
  elements.highlight.replaceChildren();
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
  if (!sourceContextCurrent(context) || state.canonical?.context !== context
    || !location || !['located', 'ambiguous'].includes(location.status)
    || !['related', 'form'].includes(location.precision) || location.coordinateSystem !== 'canonical'
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
    const label = canonical.precision === 'form' ? 'Canonical form context' : 'Related canonical fragment';
    const ambiguity = canonical.status === 'ambiguous' ? ` · ${canonical.ranges.length} possible fragments highlighted; source candidates are independent.` : '.';
    elements.canonicalStatus.textContent = `${label}${ambiguity}${typeof canonical.reason === 'string' && canonical.reason ? ` ${canonical.reason}` : ''}`;
    $('#canonical-panel').open = true;
  } else {
    elements.canonicalStatus.textContent = 'Canonical fragment location unavailable for this edit step.';
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
    && context.exerciseId === state.exercise?.id && context.body === elements.editor.value && !elements.editor.disabled;
}

function bodyPosition(body, offset) {
  const prefix = body.slice(0, offset).split('\n');
  return { line: prefix.length, column: prefix.at(-1).length + 1 };
}

function validatedSourceLocation(location, context) {
  if (!sourceContextCurrent(context) || !location || !['located', 'ambiguous'].includes(location.status)
    || !['exact', 'related', 'predicate'].includes(location.precision)
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
  return location.precision === 'exact' ? 'Source expression'
    : location.precision === 'predicate' ? 'Predicate context' : 'Related source context';
}

function locateSource(location, index, context, button) {
  if (!validatedSourceLocation(location, context)) { clearSourceHighlight(); return; }
  clearSourceHighlight();
  const range = location.ranges[index];
  const mark = node('mark', 'source-range', range.text);
  mark.dataset.start = range.start;
  mark.dataset.end = range.end;
  elements.highlight.append(document.createTextNode(context.body.slice(0, range.start)), mark,
    document.createTextNode(context.body.slice(range.end) + '\n'));
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
  const ambiguity = location.status === 'ambiguous' ? ` · ${location.ranges.length} possible locations; choose a candidate.` : '.';
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
  const isBefore = state.context === 'before';
  $('#environment-code').textContent = isBefore ? state.exercise.environmentBefore : state.exercise.environmentAfter;
  $('#environment-code').setAttribute('aria-label', isBefore ? 'Environment before predicate' : 'Environment after predicate');
  document.querySelectorAll('.environment-tab').forEach((button) => {
    const active = button.dataset.context === state.context;
    button.classList.toggle('active', active);
    button.setAttribute('aria-pressed', String(active));
  });
}

async function selectExercise(id) {
  if (state.exercise?.id === id && !elements.editor.disabled) return;
  if (state.exercise && !elements.editor.disabled) saveDraft();
  invalidateFeedback();
  const selection = ++state.selection;
  state.detailAbort?.abort();
  const controller = new AbortController();
  state.detailAbort = controller;
  elements.editor.disabled = true;
  elements.check.disabled = true;
  elements.reset.disabled = true;
  elements.download.disabled = true;
  elements.draft.textContent = 'Loading model…';
  showWaiting('Loading your model…');
  $('#startup-error').hidden = true;
  try {
    const exercise = await fetchJSON(`api/exercises/${encodeURIComponent(id)}`, { signal: controller.signal });
    if (selection !== state.selection) return;
    if (!exercise || typeof exercise.id !== 'string' || typeof exercise.starter !== 'string') throw new Error('This exercise could not be loaded.');
    state.exercise = exercise;
    state.context = 'before';
    state.history = readStorage(`history:${id}`, []);
    if (!Array.isArray(state.history)) state.history = [];
    state.history = state.history.filter((item) => item && item.basis === COMPARISON_VERSION && typeof item.distance === 'number' && Number.isFinite(item.distance) && item.distance >= 0 && typeof item.at === 'number').slice(-12);
    state.lastHistoryBody = null;
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

async function checkPredicate() {
  clearTimeout(state.timer);
  if (!state.exercise || elements.editor.disabled) return;
  clearOperationHighlight();
  clearCanonicalForm('Checking this draft…');
  state.feedbackAbort?.abort();
  state.explainAbort?.abort();
  const controller = new AbortController();
  state.feedbackAbort = controller;
  const exerciseId = state.exercise.id;
  const revision = ++state.revision;
  const selection = state.selection;
  const body = elements.editor.value;
  saveDraft();
  setStatus('pending', 'Checking…');
  const pending = node('div', 'pending-message');
  const spinner = node('span', 'spinner');
  spinner.setAttribute('aria-hidden', 'true');
  pending.append(spinner, node('span', '', 'Comparing canonical structure…'));
  elements.result.replaceChildren(pending);
  try {
    const result = await fetchJSON('api/feedback', {
      method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ exerciseId, body, revision }),
    });
    if (revision !== state.revision || selection !== state.selection || exerciseId !== state.exercise?.id || body !== elements.editor.value || controller.signal.aborted) return;
    if ((result.exerciseId !== undefined && result.exerciseId !== exerciseId) || (result.revision !== undefined && result.revision !== revision)) {
      throw new Error('The server returned feedback for a different draft. Check your predicate again.');
    }
    // A locator needs the echoed request identity, even when an older server
    // can still provide useful feedback without those fields.
    renderFeedback(result, result.exerciseId === exerciseId && result.revision === revision
      ? { exerciseId, revision, selection, body } : null);
    if (result.status === 'ok' && typeof result.distance === 'number' && Number.isFinite(result.distance) && result.distance >= 0 && state.lastHistoryBody !== body) {
      state.history.push({ distance: result.distance, at: Date.now(), basis: COMPARISON_VERSION });
      state.history = state.history.slice(-12);
      state.lastHistoryBody = body;
      writeStorage(`history:${exerciseId}`, state.history);
      renderHistory();
    }
    if (result.status === 'ok' && typeof result.distance === 'number' && Number.isFinite(result.distance) && result.distance >= 0) {
      requestExplanation({ exerciseId, body, revision }, selection);
    }
  } catch (error) {
    if (error.name === 'AbortError' || revision !== state.revision || selection !== state.selection) return;
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
  heading.append(node('span', 'operation-step', String(index + 1).padStart(2, '0')), select, node('span', 'operation-cost', `${formatNumber(operation.cost)} cost`));
  detail.append(heading);

  if (typeof operation.sourceTerm === 'string' && operation.sourceTerm.length) {
    const fragment = node('div', 'operation-fragment');
    const label = operation.sourceRole === 'insertion-anchor' ? 'Your insertion context · canonical' : 'Your affected fragment · canonical';
    fragment.append(node('span', 'operation-fragment-label', label), node('code', '', operation.sourceTerm));
    detail.append(fragment);
  }
  if (typeof operation.sourceOperator === 'string' || typeof operation.replacementOperator === 'string') {
    const operators = node('div', 'operation-operators');
    if (typeof operation.sourceOperator === 'string') {
      const current = node('span', 'operator-chip current-operator');
      current.append(node('span', '', operation.sourceRole === 'insertion-anchor' ? 'Context' : 'Current'), node('code', '', operation.sourceOperator));
      operators.append(current);
    }
    if (typeof operation.replacementOperator === 'string') {
      const arrow = node('span', 'operator-arrow', '→');
      arrow.setAttribute('aria-hidden', 'true');
      const replacement = node('span', 'operator-chip replacement-operator');
      replacement.append(node('span', '', kind === 'insert' ? 'Insert operator' : 'Replacement'), node('code', '', operation.replacementOperator));
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
  if (operation.aggregate) detail.append(node('p', 'operation-aggregate', 'Component summary · several edit units; no precise source repair is available.'));

  const path = typeof operation.path === 'string' ? operation.path : JSON.stringify(operation.path || '');
  const structure = node('details', 'operation-structure');
  const structureSummary = node('summary', '', 'Canonical location');
  structureSummary.append(node('span', 'chevron', '⌄'));
  structure.append(structureSummary, node('div', 'operation-path', [operation.component, operation.sourceNodeKind, path].filter(Boolean).join(' · ')));
  detail.append(structure);

  detail.append(renderSourceLocator(operation, sourceContext, index, item));
  item.append(icon, detail);
  return item;
}

function renderFeedback(result, sourceContext = null) {
  if (result.status !== 'ok') {
    clearCanonicalForm('Canonical form unavailable for this draft. Check the feedback and try again.');
    const statuses = {
      invalid: ['invalid', 'Check syntax', 'Your model needs a small repair.'],
      unsupported: ['invalid', 'Unsupported', 'This structure is outside the supported rewrite rules.'],
      timeout: ['timeout', 'Timed out', 'This check took too long.'],
      busy: ['pending', 'Server busy', 'The comparison engine is busy.'],
      error: ['error', 'Unavailable', 'The check could not be completed.'],
      engine_error: ['error', 'Engine error', 'The comparison engine could not complete this check.'],
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
  if (forms.length && forms.every(form => typeof form === 'string')) {
    state.canonical = { context: sourceContext, forms };
    renderCanonicalForms();
  } else clearCanonicalForm('The checker returned no canonical form for this draft.');
  const distance = node('div', 'distance-result');
  const caption = node('div', 'distance-caption');
  caption.append(node('span', '', 'Distance to closest correct predicate'));
  if (result.distance === 0) caption.append(node('span', 'match-badge', 'Canonical match'));
  const value = node('div', 'distance-value-row');
  value.append(node('span', `distance-value${result.distance === 0 ? ' zero' : ''}`, formatNumber(result.distance)), node('span', 'distance-unit', 'edit cost'));
  distance.append(caption, value, node('p', 'distance-description', result.distance === 0
    ? 'Your predicate shares a canonical form with a member of the correct pool under the supported rewrite rules.'
    : 'The minimum weighted edit cost across compatible known-correct predicates, including the oracle.'));
  if (result.comparison?.complete === true && Number.isInteger(result.comparison.poolSize) && result.comparison.poolSize > 0) {
    const count = result.comparison.poolSize;
    distance.append(node('p', 'distance-description', count === 1
      ? 'Compared the oracle; this exercise has no compatible correct corpus alternatives.'
      : `Compared all ${formatNumber(count)} private candidates, including the oracle. The edits follow one closest candidate.`));
  }
  const components = node('div', 'component-grid');
  const labels = { temporal: 'Temporal', quantifier: 'Quantifier', matrix: 'Matrix' };
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
    operations.append(node('p', 'operations-intro', 'Use your canonical fragments and the operator hints to guide the next edit.'));
    const operationList = node('ol', 'operation-list');
    list.forEach((operation, index) => operationList.append(renderOperation(operation, index, sourceContext)));
    operations.append(operationList);
  } else operations.append(node('p', 'no-operations', result.distance === 0 ? 'No structural edits are needed.' : 'No detailed operations are available for this comparison.'));
  if (result.trace) {
    const reconciliation = result.trace.matchesDistance
      ? `Hint costs sum to ${formatNumber(result.trace.cost)}.`
      : 'The engine reports these hints separately from the distance.';
    const aggregate = result.trace.hasAggregates ? ' Some entries aggregate several edits.' : '';
    operations.append(node('p', 'trace-note', `${reconciliation}${aggregate} Hints are not a certified replayable minimum edit script. Reference expressions stay hidden.`));
  }
  const explanation = node('section', 'explanation-section');
  explanation.id = 'luna-explanation';
  explanation.setAttribute('aria-label', 'Luna repair guidance');
  const explanationHeading = node('div', 'section-label');
  explanationHeading.append(node('span', '', 'Luna · repair guidance'), node('span', 'ai-badge', 'AI'));
  explanation.append(explanationHeading, node('p', 'explanation-caption', 'AI explanation of the redacted trace'));
  const explanationBody = node('div', 'explanation-body');
  explanationBody.id = 'luna-explanation-body';
  explanationBody.append(node('p', 'explanation-pending', 'Preparing guidance from the redacted edit trace…'));
  explanation.append(explanationBody);
  elements.result.replaceChildren(distance, operations, explanation);
}

async function requestExplanation(payload, selection) {
  state.explainAbort?.abort();
  const controller = new AbortController();
  state.explainAbort = controller;
  const current = () => payload.revision === state.revision && selection === state.selection && payload.exerciseId === state.exercise?.id && !controller.signal.aborted;
  try {
    const explanation = await fetchJSON('api/explain', {
      method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    });
    if (!current()) return;
    if ((explanation.exerciseId !== undefined && explanation.exerciseId !== payload.exerciseId) || (explanation.revision !== undefined && explanation.revision !== payload.revision)) throw new Error('Guidance for this draft is unavailable. Please check again.');
    const container = $('#luna-explanation-body');
    if (!container) return;
    if (explanation.status === 'ok' && typeof explanation.text === 'string') {
      container.replaceChildren(node('p', 'explanation-text', explanation.text));
    } else renderExplanationUnavailable(container, explanation.message || 'AI guidance is currently unavailable. Canonical feedback remains available.', payload, selection);
  } catch (error) {
    if (error.name === 'AbortError' || !current()) return;
    const container = $('#luna-explanation-body');
    if (container) renderExplanationUnavailable(container, error.message, payload, selection);
  } finally {
    if (state.explainAbort === controller) state.explainAbort = null;
  }
}

function renderExplanationUnavailable(container, message, payload, selection) {
  const retry = node('button', 'button text-button explanation-retry', 'Retry guidance');
  retry.type = 'button';
  retry.addEventListener('click', () => {
    container.replaceChildren(node('p', 'explanation-pending', 'Preparing guidance…'));
    requestExplanation(payload, selection);
  });
  container.replaceChildren(node('p', 'explanation-unavailable', message), retry);
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
  chart.setAttribute('aria-label', `Recent canonical distances: ${state.history.map((item) => formatNumber(item.distance)).join(', ')}`);
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
  const preference = readStorage('live', true);
  elements.live.checked = typeof preference === 'boolean' ? preference : true;
  if (!/Mac|iPhone|iPad/.test(navigator.platform)) $('.keyboard-hint').textContent = 'Ctrl ↵';
  elements.search.addEventListener('input', renderExercises);
  elements.group.addEventListener('change', renderExercises);
  elements.editor.addEventListener('input', onEdit);
  elements.editor.addEventListener('scroll', syncEditorOverlay);
  new ResizeObserver(syncEditorOverlay).observe(elements.editor);
  $('#clear-source-highlight').addEventListener('click', clearOperationHighlight);
  ['click', 'keyup', 'select'].forEach((event) => elements.editor.addEventListener(event, updateCursor));
  elements.editor.addEventListener('keydown', (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') { event.preventDefault(); checkPredicate(); }
    if (event.key === 'Escape') {
      elements.editor.dataset.releaseTab = 'true';
      showToast('Press Tab to leave the editor.');
    }
    if (event.key === 'Tab' && !event.shiftKey && elements.editor.dataset.releaseTab !== 'true') {
      event.preventDefault();
      const start = elements.editor.selectionStart;
      const end = elements.editor.selectionEnd;
      elements.editor.setRangeText('  ', start, end, 'end');
      onEdit();
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
  document.querySelectorAll('.environment-tab').forEach((button) => button.addEventListener('click', () => { state.context = button.dataset.context; renderContext(); }));
  window.addEventListener('beforeunload', () => { if (!elements.editor.disabled) saveDraft(); });
  await loadExercises();
}

async function loadExercises() {
  $('#startup-error').hidden = true;
  try {
    const data = await fetchJSON('api/exercises');
    if (!Array.isArray(data.exercises)) throw new Error('The exercise catalog could not be read.');
    state.exercises = data.exercises;
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

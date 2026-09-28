const $ = (selector) => document.querySelector(selector);
const elements = {
  search: $('#exercise-search'), group: $('#group-filter'), list: $('#exercise-list'),
  editor: $('#predicate-editor'), check: $('#check-button'), reset: $('#reset-button'),
  download: $('#download-button'), live: $('#live-feedback'), result: $('#feedback-result'),
  status: $('#feedback-state'), lines: $('#line-numbers'), draft: $('#draft-status'),
  highlight: $('#source-highlight'), locationBar: $('#source-location-bar'), locationStatus: $('#source-location-status'),
  canonical: $('#canonical-content'), canonicalStatus: $('#canonical-location-status'),
  behavior: $('#behavior-result'), behaviorStatus: $('#behavior-state'),
  metric: $('#distance-metric'),
};
const state = {
  exercises: [], exercise: null, revision: 0, selection: 0, metric: 'canonical',
  feedbackAbort: null, explainAbort: null, behaviorAbort: null, detailAbort: null, timer: null, context: 'before',
  history: [], lastHistoryBody: null, feedbackStatus: 'waiting', storageAvailable: true,
  sourceHighlight: null, canonical: null, education: null, behaviorEvidence: null,
};
const STORAGE_PREFIX = 'alloy-studio:v1:';
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
  return result.requestedMetric === metric || (metric === 'canonical' && result.requestedMetric === undefined);
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
  resetBehavior();
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
    ? 'Live feedback compares your predicate with the closest answer in the private set of correct answers, including the oracle.'
    : 'Check your predicate to see its distance from a correct answer and suggestions for what to review.'));
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
  saveDraft();
  setStatus('pending', 'Checking…');
  const pending = node('div', 'pending-message');
  const spinner = node('span', 'spinner');
  spinner.setAttribute('aria-hidden', 'true');
  pending.append(spinner, node('span', '', 'Comparing your predicate with the correct answers…'));
  elements.result.replaceChildren(pending);
  try {
    const result = await fetchJSON('api/feedback', {
      method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ exerciseId, body, revision, metric }),
    });
    if (revision !== state.revision || selection !== state.selection || metric !== state.metric || exerciseId !== state.exercise?.id || body !== elements.editor.value || controller.signal.aborted) return;
    if ((result.exerciseId !== undefined && result.exerciseId !== exerciseId) || (result.revision !== undefined && result.revision !== revision)) {
      throw new Error('The server returned feedback for a different draft. Check your predicate again.');
    }
    if (!responseMetricMatches(result, metric) || (result.status === 'ok' && result.metric !== METRICS[metric].id)) {
      throw new Error('The server returned feedback for a different comparison method. Check your predicate again.');
    }
    if (result.status === 'ok' && typeof result.distance === 'number' && Number.isFinite(result.distance) && result.distance >= 0) {
      state.education = { context: { exerciseId, revision, selection, body, metric }, phase: 'waiting',
        operationIds: (Array.isArray(result.operations) ? result.operations : []).map((_, index) => `operation-${index + 1}`),
        operations: new Map(), instances: new Map() };
    }
    // A locator needs the echoed request identity, even when an older server
    // can still provide useful feedback without those fields.
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
      requestBehavior({ exerciseId, body, revision }, selection, true, metric);
    } else if (result.status === 'unsupported') {
      requestBehavior({ exerciseId, body, revision }, selection, false, metric);
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
    list.forEach((operation, index) => operationList.append(renderOperation(operation, index, sourceContext)));
    operations.append(operationList);
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
  return BEHAVIOR_CATEGORIES.every(expected => {
    const matching = result.categories.filter(category => category?.id === expected.id);
    if (matching.length !== 1) return false;
    const category = matching[0];
    return category.oracle === expected.oracle && category.student === expected.student
      && typeof category.enumerationComplete === 'boolean' && list(category.instances, 3, validInstance)
      && (category.enumerationComplete || category.instances.length === 3)
      && (category.status === 'unsat' ? category.instances.length === 0 && category.enumerationComplete
        : category.status === 'sat' && category.instances.length > 0);
  });
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
  container.replaceChildren();
  const signatures = node('div', 'behavior-signatures');
  if (stateData.signatures.length) {
    signatures.append(behaviorTable('Atoms in this state', ['Signature', 'Atoms'],
      stateData.signatures.map(signature => [signature.label, signature.atoms.length ? signature.atoms.join(', ') : 'No atoms'])));
  } else signatures.append(node('p', 'behavior-empty', 'No named signatures in this state.'));
  container.append(signatures);
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
  container.append(relations);
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
  explanation.append(node('p', '', 'ACGN reward against the exercise oracle. Structural distance above uses the closest correct predicate.'), node('span', 'behavior-facts', 'Model facts enforced'));
  if (!available) explanation.append(node('p', 'behavior-score-reason', result.scoreReason === 'ORACLE_POSITIVE_UNSAT'
    ? 'The oracle accepts no instance within these bounds, so the score is unavailable.'
    : 'The oracle rejects no instance within these bounds, so the score is unavailable.'));
  if (rounded === '1.000' && result.categories.some(category => ['undercoverage', 'overcoverage'].includes(category.id) && category.status === 'sat')) {
    explanation.append(node('p', 'behavior-rounding-note', 'Rounding shows 1.000; counterexamples still exist within these bounds.'));
  }
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
  try {
    const result = await fetchJSON('api/behavior', { method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
    if (!current()) return;
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
    state.behaviorEvidence = { token: typeof result.behaviorToken === 'string' && /^[a-f0-9]{64}$/.test(result.behaviorToken) ? result.behaviorToken : null,
      instanceIds: result.categories.flatMap(category => category.instances.map((_, index) => `${category.id}-${index + 1}`)) };
    renderBehavior(result);
  } catch (error) {
    if (error.name === 'AbortError' || !current()) return;
    state.behaviorEvidence = null;
    behaviorMessage('error', 'Unavailable', error.message);
  } finally {
    if (state.behaviorAbort === controller) state.behaviorAbort = null;
    if (current() && explain) {
      const token = state.behaviorEvidence?.token;
      requestExplanation({ ...payload, metric, ...(token ? { behaviorToken: token } : {}) }, selection);
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
    const explanation = await fetchJSON('api/explain', {
      method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
    });
    if (!current()) return;
    if (explanation.exerciseId !== payload.exerciseId || explanation.revision !== payload.revision) throw new Error('Guidance for this draft is unavailable. Please check again.');
    if (!responseMetricMatches(explanation, payload.metric)) throw new Error('Guidance for this comparison method is unavailable. Check again or retry guidance.');
    const container = $('#luna-explanation-body');
    if (!container) return;
    if (explanation.status === 'ok') {
      if ((explanation.behaviorToken ?? null) !== behaviorToken
        || !validEducation(explanation, education.operationIds, instanceIds)) throw new Error('AI explanations could not be matched to the displayed edits and examples. Check again or retry guidance.');
      education.phase = 'ready';
      education.operations = new Map(explanation.operations.map(item => [item.id, item.description]));
      education.instances = new Map(explanation.instances.map(item => [item.id, item.description]));
      refreshEducationSlots();
      container.replaceChildren(node('p', 'explanation-text', explanation.summary));
    } else renderExplanationUnavailable(container, explanation.message || 'AI guidance is currently unavailable. Structural feedback remains available.', payload, selection);
  } catch (error) {
    if (error.name === 'AbortError' || !current()) return;
    const container = $('#luna-explanation-body');
    if (container) renderExplanationUnavailable(container, error.message, payload, selection);
  } finally {
    if (state.explainAbort === controller) state.explainAbort = null;
  }
}

function renderExplanationUnavailable(container, message, payload, selection) {
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
  const preference = readStorage('live', true);
  elements.live.checked = typeof preference === 'boolean' ? preference : true;
  const metric = readStorage('metric', 'canonical');
  state.metric = Object.hasOwn(METRICS, metric) ? metric : 'canonical';
  renderMetric();
  if (!/Mac|iPhone|iPad/.test(navigator.platform)) $('.keyboard-hint').textContent = 'Ctrl ↵';
  elements.search.addEventListener('input', renderExercises);
  elements.group.addEventListener('change', renderExercises);
  elements.metric.addEventListener('change', selectMetric);
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

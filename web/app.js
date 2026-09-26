const $ = (selector) => document.querySelector(selector);
const elements = {
  search: $('#exercise-search'), group: $('#group-filter'), list: $('#exercise-list'),
  editor: $('#predicate-editor'), check: $('#check-button'), reset: $('#reset-button'),
  download: $('#download-button'), live: $('#live-feedback'), result: $('#feedback-result'),
  status: $('#feedback-state'), lines: $('#line-numbers'), draft: $('#draft-status'),
};
const state = {
  exercises: [], exercise: null, revision: 0, selection: 0,
  feedbackAbort: null, explainAbort: null, detailAbort: null, timer: null, context: 'before',
  history: [], lastHistoryBody: null, feedbackStatus: 'waiting', storageAvailable: true,
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
  state.revision += 1;
  clearTimeout(state.timer);
  state.feedbackAbort?.abort();
  state.feedbackAbort = null;
  state.explainAbort?.abort();
  state.explainAbort = null;
}

function showWaiting(message = 'Your next edit is ready to explore.') {
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
  elements.lines.scrollTop = elements.editor.scrollTop;
  updateCursor();
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
    if (revision !== state.revision || selection !== state.selection || exerciseId !== state.exercise?.id || controller.signal.aborted) return;
    if ((result.exerciseId !== undefined && result.exerciseId !== exerciseId) || (result.revision !== undefined && result.revision !== revision)) {
      throw new Error('The server returned feedback for a different draft. Check your predicate again.');
    }
    renderFeedback(result);
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

function renderOperation(operation, index) {
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
  heading.append(node('span', 'operation-step', String(index + 1).padStart(2, '0')), node('div', 'operation-title', title), node('span', 'operation-cost', `${formatNumber(operation.cost)} cost`));
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

  const span = operation.sourceSpan;
  const body = elements.editor.value;
  if (span && span.coordinateSystem === 'body' && span.exact === true
    && Number.isInteger(span.start) && Number.isInteger(span.end)
    && span.start >= 0 && span.end > span.start && span.end <= body.length
    && typeof span.text === 'string' && body.slice(span.start, span.end) === span.text) {
    const revision = state.revision;
    const highlight = node('button', 'button text-button operation-highlight', 'Select in editor');
    highlight.type = 'button';
    highlight.addEventListener('click', () => {
      if (revision !== state.revision || elements.editor.value !== body) return;
      elements.editor.focus();
      elements.editor.setSelectionRange(span.start, span.end);
      const precedingLines = body.slice(0, span.start).split('\n').length - 1;
      const lineHeight = parseFloat(getComputedStyle(elements.editor).lineHeight) || 23;
      elements.editor.scrollTop = Math.max(0, precedingLines * lineHeight - elements.editor.clientHeight / 3);
      elements.editor.scrollIntoView({ block: 'center', behavior: 'auto' });
      updateCursor();
    });
    detail.append(highlight);
  }
  item.append(icon, detail);
  return item;
}

function renderFeedback(result) {
  if (result.status !== 'ok') {
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
    list.forEach((operation, index) => operationList.append(renderOperation(operation, index)));
    operations.append(operationList);
  } else operations.append(node('p', 'no-operations', result.distance === 0 ? 'No structural edits are needed.' : 'No detailed operations are available for this comparison.'));
  if (result.trace) {
    const reconciliation = result.trace.matchesDistance
      ? `Hint costs sum to ${formatNumber(result.trace.cost)}.`
      : 'The engine reports these hints separately from the distance.';
    const aggregate = result.trace.hasAggregates ? ' Some entries aggregate several edits.' : '';
    operations.append(node('p', 'trace-note', `${reconciliation}${aggregate} Hints are not a certified replayable minimum edit script. Reference expressions stay hidden.`));
  }
  const canonical = node('details', 'canonical-details');
  const summary = node('summary', '', 'Your canonical form');
  summary.append(node('span', 'chevron', '⌄'));
  const canonicalText = typeof result.canonicalForm === 'string' ? result.canonicalForm
    : Array.isArray(result.canonicalForm) ? result.canonicalForm.join('\n\n') : JSON.stringify(result.canonicalForm ?? {}, null, 2);
  canonical.append(summary, node('pre', '', canonicalText));
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
  elements.result.replaceChildren(distance, operations, canonical, explanation);
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
  elements.editor.addEventListener('scroll', () => { elements.lines.scrollTop = elements.editor.scrollTop; });
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

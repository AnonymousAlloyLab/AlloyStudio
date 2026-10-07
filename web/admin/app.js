'use strict';
const $ = id => document.getElementById(id);
const bytes = value => new TextEncoder().encode(value).length;
let csrf = '', draft = null, source = '', epoch = 0, busy = false;
const cards = [];
const library = { offset: 0, total: 0, limit: 50, items: [], loading: false, sequence: 0 };
const candidates = { offset: 0, total: 0, limit: 25, items: [], loading: false, sequence: 0 };
let selectedQuestion = null, selectedCandidate = null, activePanel = 'upload';
const digest = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const identifier = value => typeof value === 'string' && value.length > 0 && value.length <= 512;
function notice(message, error = false) { $('notice').textContent = message; $('notice').classList.toggle('error', error); }
function lock(value) {
  busy = value;
  for (const id of ['prepare','suggest','publish','discard','tab-upload','tab-library','tab-candidates',
    'library-refresh','candidates-refresh']) $(id).disabled = value;
  document.querySelectorAll('.library-choice').forEach(button => { button.disabled = value; });
  for (const item of cards) { item.title.disabled = value; item.question.disabled = value; }
  $('reviewed').disabled = value;
  updateLibraryControls();
}
function signedOut() {
  epoch += 1; csrf = ''; draft = null; source = ''; cards.length = 0;
  $('workspace').hidden = true; $('logout').hidden = true; $('preview').hidden = true;
  $('success').hidden = true; $('exercise-cards').replaceChildren(); $('source-preview').textContent = '';
  $('model-file').value = ''; $('question-seed').value = ''; $('password').value = '';
  clearLibraryState();
  lock(false);
}
async function api(path, data) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(`../api/admin/${path}`, { method: data === undefined ? 'GET' : 'POST',
      credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal,
      headers: data === undefined ? {} : { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      body: data === undefined ? undefined : JSON.stringify(data) });
    const type = response.headers.get('Content-Type') || '';
    if (!type.includes('application/json')) throw new Error('The admin service returned an unreadable response. Check the IIS API connection.');
    const value = await response.json();
    if (!response.ok) {
      if (path === 'session') signedOut();
      if ([401, 403].includes(response.status) && path !== 'login') {
        signedOut(); $('login-form').hidden = false;
        await bootstrap(false);
        notice('Your administrator session expired. Sign in again.', true);
      }
      throw new Error(typeof value.error === 'string' ? value.error : 'This operation could not complete.');
    }
    return value;
  } catch (error) {
    if (path === 'session') signedOut();
    if (error.name === 'AbortError') throw new Error('The request timed out. Refresh the page to reconnect.');
    throw error;
  } finally { clearTimeout(timer); }
}
async function bootstrap(showNotice = true) {
  const state = await api('session');
  if (!state.authenticated) signedOut();
  csrf = state.csrfToken || '';
  $('disabled').hidden = state.enabled;
  $('login-form').hidden = !state.enabled || state.authenticated;
  $('workspace').hidden = !state.authenticated; $('logout').hidden = !state.authenticated;
  if (showNotice) notice(!state.enabled ? 'Configure an administrator password on the backend host.'
    : state.authenticated ? 'Signed in. Choose a model to prepare.' : 'Sign in to upload and publish exercises.');
}
function renderPreview(value) {
  draft = value; cards.length = 0;
  $('preview').hidden = false; $('success').hidden = true; $('reviewed').checked = false;
  $('source-preview').textContent = source;
  $('source-hash').textContent = `Source SHA-256: ${value.sourceSha256}`;
  $('validation').textContent = `${value.groups.length} exercise(s) · Alloy equivalence scope ${value.equivalenceScope}, bitwidth 5 · Original names and bodies preserved.`;
  const container = $('exercise-cards'); container.replaceChildren();
  for (const group of value.groups) {
    const card = document.createElement('article'); card.className = 'exercise-card';
    const heading = document.createElement('h3'); heading.textContent = group.predicate;
    const detail = document.createElement('p'); detail.className = 'muted';
    detail.textContent = `${group.id} · ${group.oracleCount} distinct solution(s) · ${group.variants.join(', ')}`;
    const titleLabel = document.createElement('label'); titleLabel.textContent = 'Public title';
    const title = document.createElement('input'); title.type = 'text'; title.required = true;
    title.maxLength = 256; title.value = group.title; titleLabel.append(title);
    const questionLabel = document.createElement('label'); questionLabel.textContent = 'Public question';
    const question = document.createElement('textarea'); question.rows = 4; question.required = true;
    question.maxLength = 8192; question.value = group.question; questionLabel.append(question);
    for (const input of [title, question]) input.addEventListener('input', () => { $('reviewed').checked = false; });
    card.append(heading, detail, titleLabel, questionLabel); container.append(card);
    cards.push({ predicate: group.predicate, title, question });
  }
}
async function waitForDraft(value, currentEpoch) {
  const deadline = Date.now() + 125000;
  while (['preparing','suggesting'].includes(value.state)) {
    if (currentEpoch !== epoch) return null;
    notice(value.message);
    await new Promise(resolve => setTimeout(resolve, 700));
    if (currentEpoch !== epoch) return null;
    value = await api(`drafts/${encodeURIComponent(value.id)}`);
    if (Date.now() > deadline) throw new Error('This job has not finished. Refresh to reconnect or start again later.');
  }
  if (currentEpoch !== epoch) return null;
  draft = value;
  if (value.state === 'rejected') throw new Error(value.message);
  return value;
}
async function suggest(currentEpoch) {
  const seed = $('question-seed').value;
  if (bytes(seed) > 8192) throw new Error('The teaching goal must fit within 8 KiB.');
  const value = await waitForDraft(await api('suggest', { id: draft.id, revision: draft.revision, questionSeed: seed }), currentEpoch);
  if (value) { renderPreview(value); notice(value.message); }
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.currentTarget.querySelector('button'); button.disabled = true;
  try {
    const result = await api('login', { password: $('password').value });
    $('password').value = ''; csrf = result.csrfToken;
    $('login-form').hidden = true; $('workspace').hidden = false; $('logout').hidden = false;
    notice('Signed in. Choose a model to prepare.');
  } catch (error) { $('password').value = ''; notice(error.message, true); }
  finally { button.disabled = false; }
});
$('logout').addEventListener('click', async () => {
  try { await api('logout', {}); signedOut(); await bootstrap(); }
  catch (error) { signedOut(); notice(error.message, true); $('login-form').hidden = false; }
});
$('retry').addEventListener('click', () => bootstrap().catch(error => notice(error.message, true)));
$('model-file').addEventListener('change', () => {
  const file = $('model-file').files[0];
  if (file) $('model-id').value = file.name.replace(/\.als$/i, '').replace(/[^A-Za-z0-9_.-]/g, '-').replace(/^[^A-Za-z0-9]+/, '').slice(0, 100) || 'model';
});
$('upload-form').addEventListener('submit', async event => {
  event.preventDefault(); if (busy) return;
  const currentEpoch = ++epoch; lock(true);
  try {
    const file = $('model-file').files[0];
    if (!file || !/\.als$/i.test(file.name) || file.size > 262144) throw new Error('Choose a UTF-8 .als file up to 256 KiB.');
    let input;
    try { input = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(await file.arrayBuffer()); }
    catch { throw new Error('The file is not valid UTF-8. Save it as UTF-8 and try again.'); }
    if (!input.trim() || input.includes('\0')) throw new Error('The model must contain Alloy source without NUL characters.');
    if (bytes($('question-seed').value) > 8192) throw new Error('The teaching goal must fit within 8 KiB.');
    if (currentEpoch !== epoch) return;
    if (draft) await api('discard', { id: draft.id });
    source = input; draft = null; $('preview').hidden = true; $('success').hidden = true;
    let value = await api('prepare', { source, filename: file.name, modelId: $('model-id').value, equivalenceScope: Number($('scope').value) });
    draft = value;
    value = await waitForDraft(value, currentEpoch);
    if (value) { renderPreview(value); await suggest(currentEpoch); }
  } catch (error) { notice(error.message, true); }
  finally { if (currentEpoch === epoch) lock(false); }
});
$('suggest').addEventListener('click', async () => {
  if (busy || !draft) return;
  const currentEpoch = epoch; lock(true); $('reviewed').checked = false;
  try { await suggest(currentEpoch); } catch (error) { notice(error.message, true); }
  finally { if (currentEpoch === epoch) lock(false); }
});
$('discard').addEventListener('click', async () => {
  if (busy || !draft) return;
  try {
    await api('discard', { id: draft.id }); epoch += 1; draft = null; source = '';
    $('preview').hidden = true; $('source-preview').textContent = ''; cards.length = 0;
    notice('Draft discarded. Choose another model when ready.');
  } catch (error) { notice(error.message, true); }
});
$('publish-form').addEventListener('submit', async event => {
  event.preventDefault(); if (busy || !draft || !$('reviewed').checked) return;
  lock(true); const currentEpoch = epoch;
  try {
    const exercises = cards.map(item => ({ predicate: item.predicate, title: item.title.value, question: item.question.value }));
    if (exercises.some(item => !item.title.trim() || !item.question.trim() || bytes(item.title) > 256 || bytes(item.question) > 8192)) throw new Error('Each exercise needs a title up to 256 bytes and a question up to 8 KiB.');
    const result = await api('commit', { id: draft.id, revision: draft.revision, exercises });
    if (currentEpoch !== epoch) return;
    draft = null; source = ''; $('preview').hidden = true; $('success').hidden = false;
    $('source-preview').textContent = ''; $('model-file').value = '';
    $('published-count').textContent = `${result.exerciseIds.length} exercise(s) added. The library now contains ${result.exerciseCount} exercises.`;
    notice('Published. The exercise library is ready to use.');
  } catch (error) { notice(error.message, true); }
  finally { if (currentEpoch === epoch) lock(false); }
});

function plain(tag, text, className = '') {
  const node = document.createElement(tag);
  node.textContent = String(text);
  if (className) node.className = className;
  return node;
}
function clearQuestion() {
  selectedQuestion = null;
  $('question-form').hidden = true; $('question-removal').hidden = true;
  for (const id of ['question-title','question-text']) $(id).value = '';
  for (const id of ['question-heading','question-identity']) $(id).textContent = '';
  $('question-reviewed').checked = false; $('question-remove-confirm').checked = false;
}
function clearCandidate() {
  selectedCandidate = null;
  $('candidate-detail').hidden = true; $('candidate-approval').hidden = true;
  for (const id of ['candidate-heading','candidate-identity','candidate-state','candidate-body','candidate-evidence']) $(id).textContent = '';
  $('candidate-advice').replaceChildren(); $('candidate-approve-confirm').checked = false;
}
function clearLibraryState() {
  for (const [state, prefix] of [[library, 'library'], [candidates, 'candidates']]) {
    state.sequence += 1; state.offset = 0; state.total = 0; state.items = []; state.loading = false;
    $(`${prefix}-list`).replaceChildren(); $(`${prefix}-page`).textContent = '';
  }
  clearQuestion(); clearCandidate(); activePanel = 'upload';
  for (const panel of ['upload','library','candidates']) {
    $(`${panel}-panel`).hidden = panel !== activePanel;
    $(`tab-${panel}`).setAttribute('aria-pressed', String(panel === activePanel));
  }
}
function updateLibraryControls() {
  for (const [state, prefix] of [[library, 'library'], [candidates, 'candidates']]) {
    $(`${prefix}-previous`).disabled = busy || state.loading || state.offset === 0;
    $(`${prefix}-next`).disabled = busy || state.loading || state.offset + state.limit >= state.total;
  }
  const questionReady = !busy && !library.loading && selectedQuestion !== null;
  $('question-title').disabled = !questionReady; $('question-text').disabled = !questionReady;
  $('question-reviewed').disabled = !questionReady;
  $('question-save').disabled = !questionReady || !$('question-reviewed').checked;
  $('question-remove-open').disabled = !questionReady;
  $('question-remove-confirm').disabled = !questionReady;
  $('question-remove').disabled = !questionReady || !$('question-remove-confirm').checked;
  $('question-remove-cancel').disabled = !questionReady;
  const candidateReady = !busy && !candidates.loading && selectedCandidate?.state === 'pending' && selectedCandidate.stale === false;
  const advice = selectedCandidate?.review?.status === 'ok' ? selectedCandidate.review.advice : null;
  $('candidate-review').disabled = !candidateReady || Boolean(advice);
  $('candidate-approve-open').disabled = !candidateReady;
  $('candidate-dismiss').disabled = !candidateReady;
  $('candidate-approve-confirm').disabled = !candidateReady;
  $('candidate-approve').disabled = !candidateReady || !$('candidate-approve-confirm').checked;
  $('candidate-approve-cancel').disabled = !candidateReady;
}
function validatePage(value, state, requestedOffset) {
  if (!value || !Array.isArray(value.items) || value.items.length > state.limit
    || value.limit !== state.limit || value.offset !== requestedOffset
    || !Number.isSafeInteger(value.total) || value.total < 0 || value.total < value.items.length
    || !value.items.every(item => item && identifier(item.id))) throw new Error('The library returned an invalid page. Refresh to try again.');
  if (state === candidates && !value.items.every(item => /^[A-Za-z0-9_-]{43}$/.test(item.id)
    && identifier(item.exerciseId) && digest(item.exerciseVersion) && digest(item.candidateHash)
    && digest(item.candidateVersion) && ['pending','approved','dismissed'].includes(item.state)
    && typeof item.stale === 'boolean')) throw new Error('The cache returned invalid candidate identities. Refresh to try again.');
  return value;
}
function pageLabel(state, kind) {
  return !state.total ? `No ${kind}` : `${state.offset + 1}–${Math.min(state.offset + state.items.length, state.total)} of ${state.total} ${kind}`;
}
function listChoices(state, prefix, selectedId, choose) {
  const container = $(`${prefix}-list`); container.replaceChildren();
  if (!state.items.length) container.append(plain('p', prefix === 'library' ? 'No questions on this page.' : 'No candidates on this page.', 'muted'));
  for (const item of state.items) {
    const button = plain('button', '', 'library-choice'); button.type = 'button'; button.dataset.itemId = item.id;
    button.setAttribute('aria-pressed', String(item.id === selectedId));
    const content = plain('span', '');
    content.append(plain('strong', item.title || item.exerciseId || item.id),
      plain('small', prefix === 'library' ? `${item.group || 'Exercise'} · ${item.predicate || item.id}` : `${item.exerciseId} · ${item.id.slice(0, 12)}`));
    button.append(content);
    if (prefix === 'candidates') button.append(plain('span', item.stale ? 'Stale' : item.state, 'row-state'));
    button.disabled = busy; button.addEventListener('click', () => choose(item)); container.append(button);
  }
  $(`${prefix}-page`).textContent = pageLabel(state, prefix === 'library' ? 'questions' : 'candidates');
  updateLibraryControls();
}
async function loadLibrary(offset = library.offset, keepId = null) {
  const currentEpoch = epoch, sequence = ++library.sequence;
  library.loading = true; clearQuestion(); updateLibraryControls();
  try {
    const value = validatePage(await api('library', { offset }), library, offset);
    if (currentEpoch !== epoch || sequence !== library.sequence) return;
    if (!value.items.length && value.total && offset >= value.total) {
      library.loading = false;
      return await loadLibrary(Math.floor((value.total - 1) / library.limit) * library.limit, keepId);
    }
    Object.assign(library, { offset, total: value.total, items: value.items, loading: false });
    listChoices(library, 'library', null, selectQuestion);
    if (keepId && library.items.some(item => item.id === keepId)) await selectQuestion(library.items.find(item => item.id === keepId));
  } catch (error) { if (currentEpoch === epoch && sequence === library.sequence) notice(error.message, true); }
  finally { if (currentEpoch === epoch && sequence === library.sequence) { library.loading = false; updateLibraryControls(); } }
}
async function selectQuestion(item) {
  const currentEpoch = epoch, sequence = ++library.sequence;
  clearQuestion(); library.loading = true; updateLibraryControls();
  try {
    const value = await api('questions/detail', { exerciseId: item.id });
    if (currentEpoch !== epoch || sequence !== library.sequence) return;
    const id = value.id || value.exerciseId, question = value.question ?? value.description;
    if (id !== item.id || !digest(value.exerciseVersion) || typeof value.title !== 'string'
      || typeof question !== 'string' || bytes(value.title) > 256 || bytes(question) > 8192) throw new Error('This question could not be read. Refresh the library.');
    selectedQuestion = { id, version: value.exerciseVersion };
    $('question-heading').textContent = value.predicate || item.predicate || id;
    $('question-identity').textContent = id;
    $('question-title').value = value.title; $('question-text').value = question;
    $('question-form').hidden = false; library.loading = false;
    listChoices(library, 'library', id, selectQuestion);
  } catch (error) { if (currentEpoch === epoch && sequence === library.sequence) notice(error.message, true); }
  finally { if (currentEpoch === epoch && sequence === library.sequence) { library.loading = false; updateLibraryControls(); } }
}
async function loadCandidates(offset = candidates.offset, keepId = null) {
  const currentEpoch = epoch, sequence = ++candidates.sequence;
  candidates.loading = true; clearCandidate(); updateLibraryControls();
  try {
    const value = validatePage(await api('candidates', { offset }), candidates, offset);
    if (currentEpoch !== epoch || sequence !== candidates.sequence) return;
    if (!value.items.length && value.total && offset >= value.total) {
      candidates.loading = false;
      return await loadCandidates(Math.floor((value.total - 1) / candidates.limit) * candidates.limit, keepId);
    }
    Object.assign(candidates, { offset, total: value.total, items: value.items, loading: false });
    listChoices(candidates, 'candidates', null, selectCandidate);
    if (keepId && candidates.items.some(item => item.id === keepId)) await selectCandidate(candidates.items.find(item => item.id === keepId));
  } catch (error) { if (currentEpoch === epoch && sequence === candidates.sequence) notice(error.message, true); }
  finally { if (currentEpoch === epoch && sequence === candidates.sequence) { candidates.loading = false; updateLibraryControls(); } }
}
function renderAdvice(review) {
  const container = $('candidate-advice'); container.replaceChildren();
  container.append(plain('h4', 'Sol advice · administrator decides'));
  if (review?.status === 'ok' && review.advice) {
    const advice = review.advice;
    container.append(plain('p', `Recommendation: ${advice.verdict}`), plain('p', advice.reason));
    const ideas = advice.counterexampleIdeas || [];
    if (Array.isArray(ideas) && ideas.length) {
      container.append(plain('p', 'Suggested counterexamples to investigate (not verified):'));
      const list = plain('ul', '');
      for (const idea of ideas.slice(0, 3)) list.append(plain('li', idea));
      container.append(list);
    }
  } else container.append(plain('p', review?.message || 'Not reviewed with AI. You can request advice or make a manual decision.'));
}
async function selectCandidate(item) {
  const currentEpoch = epoch, sequence = ++candidates.sequence;
  clearCandidate(); candidates.loading = true; updateLibraryControls();
  try {
    const value = await api('candidates/detail', { id: item.id, version: item.candidateVersion });
    if (currentEpoch !== epoch || sequence !== candidates.sequence) return;
    if (value.id !== item.id || value.candidateVersion !== item.candidateVersion
      || value.exerciseId !== item.exerciseId || value.exerciseVersion !== item.exerciseVersion
      || value.candidateHash !== item.candidateHash || !digest(value.candidateVersion) || !digest(value.exerciseVersion)
      || !identifier(value.exerciseId) || typeof value.body !== 'string' || bytes(value.body) > 8192
      || !['pending','approved','dismissed'].includes(value.state) || typeof value.stale !== 'boolean') throw new Error('This candidate changed or could not be read. Refresh the cache.');
    selectedCandidate = value;
    $('candidate-heading').textContent = `${value.title || value.exerciseId} · ${value.predicate || 'Candidate'}`;
    $('candidate-identity').textContent = `${value.exerciseId} · candidate ${value.id} · body SHA-256 ${value.candidateHash || ''}`;
    $('candidate-state').textContent = value.stale ? 'Stale: the exercise changed or was removed. This candidate cannot be approved.'
      : `Status: ${value.state}. ${value.state === 'pending' ? 'Review the exact body before deciding.' : 'This decision is final.'}`;
    $('candidate-body').textContent = value.body;
    $('candidate-evidence').textContent = JSON.stringify(value.behavioralEvidence || {}, null, 2);
    renderAdvice(value.review); $('candidate-detail').hidden = false; candidates.loading = false;
    listChoices(candidates, 'candidates', value.id, selectCandidate);
  } catch (error) { if (currentEpoch === epoch && sequence === candidates.sequence) notice(error.message, true); }
  finally { if (currentEpoch === epoch && sequence === candidates.sequence) { candidates.loading = false; updateLibraryControls(); } }
}
async function showPanel(panel) {
  if (busy) return;
  activePanel = panel;
  for (const name of ['upload','library','candidates']) {
    $(`${name}-panel`).hidden = name !== panel;
    $(`tab-${name}`).setAttribute('aria-pressed', String(name === panel));
  }
  if (panel === 'library') await loadLibrary();
  if (panel === 'candidates') await loadCandidates();
}
for (const panel of ['upload','library','candidates']) $(`tab-${panel}`).addEventListener('click', () => showPanel(panel));
for (const [state, prefix, load] of [[library, 'library', loadLibrary], [candidates, 'candidates', loadCandidates]]) {
  $(`${prefix}-refresh`).addEventListener('click', () => { if (!busy) load(state.offset); });
  $(`${prefix}-previous`).addEventListener('click', () => { if (!busy) load(Math.max(0, state.offset - state.limit)); });
  $(`${prefix}-next`).addEventListener('click', () => { if (!busy) load(state.offset + state.limit); });
}
for (const id of ['question-title','question-text']) $(id).addEventListener('input', () => { $('question-reviewed').checked = false; updateLibraryControls(); });
$('question-reviewed').addEventListener('change', updateLibraryControls);
$('question-form').addEventListener('submit', async event => {
  event.preventDefault(); if (busy || !selectedQuestion || !$('question-reviewed').checked) return;
  const currentEpoch = epoch, selected = selectedQuestion; lock(true);
  try {
    const title = $('question-title').value, question = $('question-text').value;
    if (!title.trim() || !question.trim() || bytes(title) > 256 || bytes(question) > 8192) throw new Error('A question needs a title up to 256 UTF-8 bytes and text up to 8 KiB.');
    await api('questions/edit', { exerciseId: selected.id, version: selected.version, title, question });
    if (currentEpoch !== epoch) return;
    await loadLibrary(library.offset, selected.id); notice('Question saved. The Alloy model and solutions are unchanged.');
  } catch (error) { if (currentEpoch === epoch) notice(error.message, true); }
  finally { if (currentEpoch === epoch) lock(false); }
});
$('question-remove-open').addEventListener('click', () => {
  if (busy || !selectedQuestion) return;
  $('question-removal').hidden = false; $('question-remove-confirm').checked = false; updateLibraryControls();
});
$('question-remove-cancel').addEventListener('click', () => { $('question-removal').hidden = true; $('question-remove-confirm').checked = false; updateLibraryControls(); });
$('question-remove-confirm').addEventListener('change', updateLibraryControls);
$('question-remove').addEventListener('click', async () => {
  if (busy || !selectedQuestion || !$('question-remove-confirm').checked) return;
  const currentEpoch = epoch, selected = selectedQuestion; lock(true);
  try {
    await api('questions/remove', { exerciseId: selected.id, version: selected.version, confirmation: selected.id });
    if (currentEpoch !== epoch) return;
    await loadLibrary(); notice('Question removed from the learner library. Its source evidence is retained.');
  } catch (error) { if (currentEpoch === epoch) notice(error.message, true); }
  finally { if (currentEpoch === epoch) lock(false); }
});
async function waitForCandidateJob(value, currentEpoch, candidateId, kind) {
  const deadline = Date.now() + 125000;
  const jobId = value.id;
  if (!/^[A-Za-z0-9_-]{43}$/.test(jobId)) throw new Error('The candidate job returned an invalid identity. Refresh the cache.');
  while (['reviewing','approving'].includes(value.state)) {
    if (currentEpoch !== epoch) return null;
    if (value.id !== jobId || value.candidateId !== candidateId || value.kind !== kind) throw new Error('The candidate job returned a different identity. Refresh the cache.');
    notice(value.message || 'Checking the candidate…');
    await new Promise(resolve => setTimeout(resolve, 700));
    if (currentEpoch !== epoch) return null;
    value = await api(`drafts/${encodeURIComponent(value.id)}`);
    if (Date.now() > deadline) throw new Error('This job has not finished. Refresh the cache to reconnect.');
  }
  if (currentEpoch !== epoch) return null;
  // Terminal results are already persisted in the private cache/pool. Release
  // their owner-bound job records so repeated decisions cannot fill draft slots.
  if (value.id === jobId && ['completed','rejected'].includes(value.state)) {
    try { await api('discard', { id: jobId }); }
    catch { /* Best effort: a cleanup failure does not undo a completed decision. */ }
    if (currentEpoch !== epoch) return null;
  }
  if (value.id !== jobId || value.candidateId !== candidateId || value.kind !== kind || !['completed','rejected'].includes(value.state)) throw new Error('The candidate job returned an invalid completion. Refresh the cache.');
  return value;
}
async function candidateAction(action) {
  if (busy || selectedCandidate?.state !== 'pending' || selectedCandidate.stale) return;
  if (action === 'approve' && !$('candidate-approve-confirm').checked) return;
  const currentEpoch = epoch, selected = selectedCandidate; lock(true);
  try {
    let value = await api(`candidates/${action}`, { id: selected.id, version: selected.candidateVersion });
    if (currentEpoch !== epoch) return;
    if (action !== 'dismiss') value = await waitForCandidateJob(value, currentEpoch, selected.id, action);
    if (currentEpoch !== epoch || !value) return;
    await loadCandidates(candidates.offset, selected.id);
    if (currentEpoch !== epoch) return;
    notice(value.message || (action === 'dismiss' ? 'Candidate dismissed.' : action === 'review' ? 'Advice is ready. You decide whether to approve or dismiss.' : 'Candidate validation finished.'), value.state === 'rejected');
  } catch (error) { if (currentEpoch === epoch) notice(error.message, true); }
  finally { if (currentEpoch === epoch) lock(false); }
}
$('candidate-review').addEventListener('click', () => candidateAction('review'));
$('candidate-dismiss').addEventListener('click', () => candidateAction('dismiss'));
$('candidate-approve-open').addEventListener('click', () => {
  if (busy || selectedCandidate?.state !== 'pending' || selectedCandidate.stale) return;
  $('candidate-approval').hidden = false; $('candidate-approve-confirm').checked = false; updateLibraryControls();
});
$('candidate-approve-cancel').addEventListener('click', () => { $('candidate-approval').hidden = true; $('candidate-approve-confirm').checked = false; updateLibraryControls(); });
$('candidate-approve-confirm').addEventListener('change', updateLibraryControls);
$('candidate-approve').addEventListener('click', () => candidateAction('approve'));
bootstrap().catch(error => notice(error.message, true));

'use strict';
const $ = id => document.getElementById(id);
const bytes = value => new TextEncoder().encode(value).length;
let csrf = '', draft = null, source = '', epoch = 0, busy = false;
const cards = [];
function notice(message, error = false) { $('notice').textContent = message; $('notice').classList.toggle('error', error); }
function lock(value) {
  busy = value;
  for (const id of ['prepare','suggest','publish','discard']) $(id).disabled = value;
  for (const item of cards) { item.title.disabled = value; item.question.disabled = value; }
  $('reviewed').disabled = value;
}
function signedOut() {
  epoch += 1; csrf = ''; draft = null; source = ''; cards.length = 0;
  $('workspace').hidden = true; $('logout').hidden = true; $('preview').hidden = true;
  $('success').hidden = true; $('exercise-cards').replaceChildren(); $('source-preview').textContent = '';
  $('model-file').value = ''; $('question-seed').value = ''; $('password').value = '';
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
      if (response.status === 401 && path !== 'login') {
        signedOut(); $('login-form').hidden = false;
        await bootstrap(false);
      }
      throw new Error(typeof value.error === 'string' ? value.error : 'This operation could not complete.');
    }
    return value;
  } catch (error) {
    if (error.name === 'AbortError') throw new Error('The request timed out. Refresh the page to reconnect.');
    throw error;
  } finally { clearTimeout(timer); }
}
async function bootstrap(showNotice = true) {
  const state = await api('session');
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
bootstrap().catch(error => notice(error.message, true));

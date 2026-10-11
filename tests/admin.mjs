// Exercise the real admin assets against a finite, offline API fixture.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const requests = [], passed = [], errors = [], external = [];
const attack = '<img src=x onerror="window.adminScriptExecuted=true">';
let enabled = false, authenticated = false, mode = 'group', failLogin = false;
let expireNext = false, revision = 1, sequence = 0, pollCount = 0, prepared = null, preparedGroups = null;
const preauth = 'fixture-preauth-csrf', session = 'fixture-session-csrf';
const apiErrors = [];
const retainedDrafts = new Set();
let failNextValidation = false, rejectedValidation = false;
const latch = () => {
  let resolve;
  const promise = new Promise(value => { resolve = value; });
  return { promise, resolve };
};
let pausedPrepare = null;
let pausedDiscard = null;
const groups = () => ['hundred', 'overflow'].includes(mode)
  ? Array.from({ length: mode === 'hundred' ? 100 : 101 }, (_, index) => ({ predicate: `Inv${index + 1}`,
    id: `fixture-Inv${index + 1}`, variants: [`Inv${index + 1}C0`, `Inv${index + 1}C1`], oracleCount: 2,
    title: `Question ${index + 1}`, question: `Describe the requirement for question ${index + 1}.` }))
  : mode === 'single' ? [{ predicate: 'Reachable', id: 'fixture-Reachable',
  variants: ['Reachable'], oracleCount: 1, title: 'Reachability', question: 'Describe reachability.' }]
  : [{ predicate: 'Inv1', id: 'fixture-Inv1', variants: ['Inv1C0', 'Inv1C7'],
    oracleCount: 2, title: 'Node constraint', question: 'Constrain the nodes.' },
  { predicate: 'acyclic', id: 'fixture-acyclic', variants: ['acyclic'],
    oracleCount: 1, title: 'Acyclicity', question: 'Constrain cycles.' }];
function draft(state = prepared?.practicePredicates.length === 0 ? 'selecting' : 'ready', message = 'Check these public questions before publishing.') {
  return { id: `draft-${sequence}`, state, revision, groups: preparedGroups || groups(),
    sourceSha256: createHash('sha256').update(prepared?.source || '').digest('hex'),
    equivalenceScope: prepared?.equivalenceScope || 5, message };
}
const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const match = url.pathname.match(/^\/(?:alloy\/)?api\/admin\/(.*)$/);
  if (!match) {
    const asset = url.pathname.match(/^\/(?:alloy\/)?admin\/(index\.html|app\.js|styles\.css)?$/)?.[1] ||
      (/^\/(?:alloy\/)?admin\/$/.test(url.pathname) ? 'index.html' : null);
    if (!asset) { res.writeHead(404).end(); return; }
    res.setHeader('Content-Type', { 'index.html': 'text/html', 'app.js': 'text/javascript', 'styles.css': 'text/css' }[asset]);
    res.end(await readFile(path.join(root, 'web/admin', asset)));
    return;
  }
  const send = (value, status = 200) => {
    res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store, private' });
    res.end(JSON.stringify(value));
  };
  try {
    let raw = '';
    for await (const chunk of req) raw += chunk;
    const data = raw ? JSON.parse(raw) : undefined;
    const operation = match[1];
    requests.push({ path: url.pathname, operation, data, headers: req.headers });
    if (operation === 'session') {
      send({ enabled, authenticated, csrfToken: authenticated ? session : preauth }); return;
    }
    if (req.method === 'POST') {
      assert.equal(req.headers['content-type'], 'application/json');
      assert.equal(req.headers['x-csrf-token'], authenticated ? session : preauth);
    }
    if (operation === 'login') {
      assert.equal(data.password, 'fixture-only-password');
      if (failLogin) { send({ error: 'Sign-in failed.' }, 403); return; }
      authenticated = true; send({ csrfToken: session }); return;
    }
    if (expireNext) {
      expireNext = false; authenticated = false; retainedDrafts.clear();
      send({ error: 'Your session expired. Sign in again.' }, 401); return;
    }
    assert.equal(authenticated, true);
    if (operation === 'logout') { authenticated = false; retainedDrafts.clear(); send({ ok: true }); return; }
    if (operation === 'prepare') {
      if (retainedDrafts.size >= 2) { send({ error: 'Discard an earlier draft before uploading another model.' }, 429); return; }
      assert(Array.isArray(data.practicePredicates));
      assert.equal(new Set(data.practicePredicates).size, data.practicePredicates.length);
      const discovered = groups();
      assert(data.practicePredicates.every(name => discovered.some(group => group.predicate === name)));
      prepared = data; preparedGroups = data.practicePredicates.length
        ? discovered.filter(group => data.practicePredicates.includes(group.predicate)) : discovered;
      sequence += 1; revision = 1; pollCount = 0;
      retainedDrafts.add(`draft-${sequence}`);
      rejectedValidation = failNextValidation && data.practicePredicates.length > 0;
      if (rejectedValidation) failNextValidation = false;
      if (pausedPrepare) {
        const pending = pausedPrepare; pausedPrepare = null;
        pending.started.resolve(); await pending.release.promise;
      }
      send(draft('preparing', 'Checking the model.'), 202); return;
    }
    if (operation.startsWith('drafts/')) {
      pollCount += 1;
      if (mode === 'rejected') { send(draft('rejected', 'The model could not be validated.')); return; }
      if (mode === 'overflow') { send(draft('rejected', 'An upload must contain between one and 100 exercise groups.')); return; }
      if (rejectedValidation) { send(draft('rejected', 'A selected solution failed equivalence validation.')); return; }
      send(draft()); return;
    }
    if (operation === 'suggest') {
      assert(prepared.practicePredicates.length > 0, 'Discovery cannot request suggestions.');
      assert.equal(data.id, `draft-${sequence}`);
      assert.equal(data.revision, revision);
      revision += 1;
      if (mode === 'manual') {
        send(draft('ready', 'Luna is unavailable. Enter the public questions yourself.')); return;
      }
      const value = draft();
      value.groups = value.groups.map(group => ({ ...group,
        title: mode === 'hundred' ? `Suggested ${group.predicate}` : attack,
        question: mode === 'hundred' ? `Explain the requirement for ${group.predicate}.` : `Public prose ${attack}` }));
      value.message = `Review the suggested wording. ${attack}`;
      send(value); return;
    }
    if (operation === 'commit') {
      assert(prepared.practicePredicates.length > 0, 'Discovery cannot publish.');
      assert.equal(data.id, `draft-${sequence}`);
      assert.equal(data.revision, revision);
      assert.deepEqual(data.exercises.map(item => item.predicate), preparedGroups.map(item => item.predicate));
      retainedDrafts.delete(data.id);
      send({ exerciseIds: data.exercises.map(item => `fixture-${item.predicate}`), exerciseCount: 181 + data.exercises.length }); return;
    }
    if (operation === 'discard') {
      let held = null;
      if (pausedDiscard?.id === data.id) {
        held = pausedDiscard; pausedDiscard = null;
        held.started.resolve(); await held.release.promise;
      }
      retainedDrafts.delete(data.id); send({ ok: true }); held?.finished.resolve(); return;
    }
    throw new Error('Unexpected mock API operation.');
  } catch (error) { apiErrors.push(error.message); send({ error: 'Offline fixture rejected the request.' }, 500); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
let browser;
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const context = await browser.newContext();
  await context.route('**/*', route => {
    if (new URL(route.request().url()).origin === base) return route.continue();
    external.push(route.request().url()); return route.abort();
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  const operationCount = operation => requests.filter(row => row.operation === operation).length;
  const latest = operation => requests.filter(row => row.operation === operation).at(-1);
  const loaded = async (prefix = '') => {
    await page.goto(`${base}/${prefix}admin/`);
    await page.waitForFunction(() => !document.querySelector('#notice').textContent.includes('Checking administration'));
  };
  const login = async () => {
    await page.locator('#password').fill('fixture-only-password');
    await page.locator('#login-form button').click();
    await page.waitForFunction(() => !document.querySelector('#workspace').hidden);
  };
  const validateSelection = async () => {
    await page.locator('#validate-selection').click();
    await page.waitForFunction(() => !document.querySelector('#validate-selection').disabled);
  };
  const upload = async (content, name = 'fixture.als', validate = true) => {
    await page.locator('#model-file').setInputFiles({ name, mimeType: 'text/plain', buffer: Buffer.isBuffer(content) ? content : Buffer.from(content) });
    await page.locator('#prepare').click();
    await page.waitForFunction(() => !document.querySelector('#prepare').disabled);
    if (validate && await page.locator('#selection').isVisible()) await validateSelection();
  };
  const original = '\ufeffmodule original\r\n// naïve λ source\r\nsig Node {edge: set Node}\r\npred Inv1C0 {\r\n some Node\r\n}\r\npred Inv1C7 {not no Node}\r\npred acyclic {no iden & ^edge}\r\n';

  await loaded();
  assert.equal(await page.locator('#disabled').isVisible(), true);
  assert.equal(await page.locator('#login-form').isVisible(), false);
  assert.equal(await page.locator('#workspace').isVisible(), false);
  assert.deepEqual(requests.map(row => row.operation), ['session']);
  passed.push('disabled-admin-has-no-upload-authority');

  enabled = true;
  await page.locator('#retry').click();
  await page.waitForFunction(() => !document.querySelector('#login-form').hidden);
  failLogin = true;
  await page.locator('#password').fill('fixture-only-password');
  await page.locator('#login-form button').click();
  await page.waitForFunction(() => document.querySelector('#notice').textContent === 'Sign-in failed.');
  assert.equal(await page.locator('#password').inputValue(), '');
  assert.equal(await page.locator('#workspace').isVisible(), false);
  failLogin = false;
  await login();
  assert.equal(await page.locator('#password').inputValue(), '');
  passed.push('login-failure-clears-password-and-success-rotates-csrf');

  await page.locator('#question-seed').fill('Keep the protected names and explain the goal.');
  await upload(original, 'fixture.als', false);
  assert.deepEqual(latest('prepare').data.practicePredicates, []);
  assert.equal(await page.locator('#selection').isVisible(), true);
  assert.equal(await page.locator('#preview').isVisible(), false);
  assert.deepEqual(await page.locator('.predicate-choice strong').allTextContents(), ['Inv1', 'acyclic']);
  assert.equal(await page.locator('.predicate-choice input').count(), 2);
  assert.match(await page.locator('.predicate-choice').first().textContent(), /Inv1C0, Inv1C7/);
  assert.equal(operationCount('suggest'), 0);
  assert.equal(operationCount('commit'), 0);
  await page.locator('#clear-all').click();
  const beforeEmpty = operationCount('prepare');
  await validateSelection();
  assert.equal(operationCount('prepare'), beforeEmpty);
  assert.match(await page.locator('#notice').textContent(), /Select at least one predicate/);
  await page.locator('#select-all').click();
  assert.equal(await page.locator('.predicate-choice input:checked').count(), 2);
  await validateSelection();
  assert.equal(operationCount('discard'), 1);
  assert.deepEqual(latest('prepare').data.practicePredicates, ['Inv1', 'acyclic']);
  passed.push('discovery-requires-explicit-nonempty-selection-and-keeps-variants-together');
  assert.equal(latest('prepare').data.source, original);
  assert.deepEqual(Buffer.from(latest('prepare').data.source), Buffer.from(original));
  assert.deepEqual(Object.keys(latest('prepare').data).sort(), ['equivalenceScope', 'filename', 'modelId', 'practicePredicates', 'source']);
  assert.equal(latest('prepare').data.equivalenceScope, 5);
  assert.equal(await page.locator('#source-preview').textContent(), original);
  assert.equal(pollCount, 1);
  assert.equal(operationCount('suggest'), 1);
  assert.equal(latest('suggest').data.questionSeed, 'Keep the protected names and explain the goal.');
  assert.deepEqual(await page.locator('.exercise-card h3').allTextContents(), ['Inv1', 'acyclic']);
  assert.match(await page.locator('.exercise-card').first().textContent(), /Inv1C0, Inv1C7/);
  assert.equal(await page.locator('.exercise-card h3 input').count(), 0);
  passed.push('upload-preserves-bom-unicode-crlf-and-protected-group-names');

  assert.equal(await page.locator('.exercise-card input').first().inputValue(), attack);
  assert.equal(await page.locator('.exercise-card textarea').first().inputValue(), `Public prose ${attack}`);
  assert.equal(await page.locator('#notice img, .exercise-card img').count(), 0);
  assert.equal(await page.evaluate(() => window.adminScriptExecuted), undefined);
  assert.match(await page.locator('#notice').textContent(), /<img/);
  passed.push('provider-prose-renders-only-as-text-or-form-values');

  const beforeCommit = operationCount('commit');
  await page.locator('#publish').click();
  assert.equal(operationCount('commit'), beforeCommit);
  await page.locator('#reviewed').check();
  await page.locator('.exercise-card input').first().fill('Reviewed node question');
  assert.equal(await page.locator('#reviewed').isChecked(), false);
  await page.locator('.exercise-card textarea').first().fill('Describe a constraint on nodes without giving its solution.');
  await page.locator('#reviewed').check();
  await page.locator('#publish').click();
  await page.waitForFunction(() => !document.querySelector('#success').hidden);
  const commit = latest('commit').data;
  assert.deepEqual(Object.keys(commit).sort(), ['exercises', 'id', 'revision']);
  assert.deepEqual(commit.exercises.map(item => item.predicate), ['Inv1', 'acyclic']);
  for (const item of commit.exercises) assert.deepEqual(Object.keys(item).sort(), ['predicate', 'question', 'title']);
  assert.equal(JSON.stringify(commit).includes(original), false);
  assert.equal(await page.locator('#source-preview').textContent(), '');
  assert.match(await page.locator('#published-count').textContent(), /2 exercise\(s\).*183/);
  passed.push('publication-requires-review-and-submits-metadata-only');

  mode = 'group';
  await upload(original, 'selected.als', false);
  await page.locator('.predicate-choice input').first().uncheck();
  assert.match(await page.locator('#selection-summary').textContent(), /1 of 2/);
  await validateSelection();
  assert.deepEqual(latest('prepare').data.practicePredicates, ['acyclic']);
  assert.equal(latest('prepare').data.source, original);
  assert.deepEqual(await page.locator('.exercise-card h3').allTextContents(), ['acyclic']);
  assert.equal(await page.locator('#source-preview').textContent(), original);
  assert.match(await page.locator('.guide').textContent(), /Unselected predicates and their bodies remain unchanged/);
  await page.locator('#reviewed').check();
  await page.locator('#publish').click();
  await page.waitForFunction(() => !document.querySelector('#success').hidden);
  assert.deepEqual(latest('commit').data.exercises.map(item => item.predicate), ['acyclic']);
  assert.equal(JSON.stringify(latest('commit').data).includes(original), false);
  passed.push('selected-only-publication-preserves-original-upload-and-retains-helper-guidance');

  mode = 'hundred';
  const hundred = 'module hundred\nsig Node {}\n' + Array.from({ length: 100 }, (_, index) =>
    `pred Inv${index + 1}C0 {some Node}\npred Inv${index + 1}C1 {not no Node}\n`).join('');
  const names = Array.from({ length: 100 }, (_, index) => `Inv${index + 1}`);
  await upload(hundred, 'hundred.als', false);
  assert.equal(await page.locator('.predicate-choice input').count(), 100);
  assert.deepEqual(await page.locator('.predicate-choice strong').allTextContents(), names);
  await mkdir(path.join(root, 'build/admin-upload-limit'), { recursive: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.locator('#selection').scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(root, 'build/admin-upload-limit/selection-desktop.png') });
  await page.setViewportSize({ width: 375, height: 812 });
  await page.locator('#selection').scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(root, 'build/admin-upload-limit/selection-mobile.png') });
  await page.locator('.predicate-choice').last().scrollIntoViewIfNeeded();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await validateSelection();
  assert.deepEqual(latest('prepare').data.practicePredicates, names);
  assert.deepEqual(await page.locator('.exercise-card h3').allTextContents(), names);
  assert.equal(await page.locator('.exercise-card input').count(), 100);
  assert.equal(await page.locator('.exercise-card textarea').count(), 100);
  assert.match(await page.locator('#validation').textContent(), /100 exercise/);
  await page.locator('#suggest').click();
  await page.waitForFunction(() => !document.querySelector('#suggest').disabled);
  assert.equal(await page.locator('.exercise-card').count(), 100);
  const beforeOversizedCommit = operationCount('commit');
  await page.evaluate(() => {
    for (const field of document.querySelectorAll('.exercise-card textarea')) {
      field.value = '\u0001'.repeat(8192); field.dispatchEvent(new Event('input', { bubbles: true }));
    }
  });
  await page.locator('#reviewed').check();
  await page.locator('#publish').click();
  await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('2 MiB'));
  assert.equal(operationCount('commit'), beforeOversizedCommit);
  assert.equal(await page.locator('#publish').isEnabled(), true);
  await page.locator('#suggest').click();
  await page.waitForFunction(() => !document.querySelector('#suggest').disabled);
  for (const index of [0, 49, 99]) {
    await page.locator('#reviewed').check();
    await page.locator('.exercise-card input').nth(index).fill(`Reviewed ${names[index]}`);
    assert.equal(await page.locator('#reviewed').isChecked(), false);
    await page.locator('.exercise-card textarea').nth(index).fill(`Explain the public requirement for ${names[index]}.`);
  }
  await page.locator('#publish').scrollIntoViewIfNeeded();
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  const publishBox = await page.locator('#publish').boundingBox();
  assert(publishBox && publishBox.x >= 0 && publishBox.x + publishBox.width <= 375 && publishBox.height >= 44);
  await page.locator('#reviewed').check();
  await page.locator('#publish').click();
  await page.waitForFunction(() => !document.querySelector('#success').hidden);
  const hundredCommit = latest('commit').data;
  assert.deepEqual(hundredCommit.exercises.map(item => item.predicate), names);
  for (const item of hundredCommit.exercises) assert.deepEqual(Object.keys(item).sort(), ['predicate', 'question', 'title']);
  for (const index of [0, 49, 99]) assert.equal(hundredCommit.exercises[index].title, `Reviewed ${names[index]}`);
  assert(Buffer.byteLength(JSON.stringify(hundredCommit)) <= 2097152);
  assert.equal(JSON.stringify(hundredCommit).includes(hundred), false);
  assert.match(await page.locator('#published-count').textContent(), /100 exercise\(s\).*281/);
  await page.setViewportSize({ width: 1280, height: 900 });
  passed.push('hundred-group-selection-review-and-publication-stays-ordered-and-usable-on-mobile');

  mode = 'overflow';
  const beforeOverflowSuggest = operationCount('suggest'), beforeOverflowCommit = operationCount('commit');
  await upload(hundred + 'pred Inv101C0 {some Node}\npred Inv101C1 {not no Node}\n', 'overflow.als');
  assert.match(await page.locator('#notice').textContent(), /between one and 100 exercise groups/);
  assert.equal(await page.locator('#selection').isVisible(), false);
  assert.equal(await page.locator('#preview').isVisible(), false);
  assert.equal(await page.locator('#prepare').isEnabled(), true);
  assert.equal(operationCount('suggest'), beforeOverflowSuggest);
  assert.equal(operationCount('commit'), beforeOverflowCommit);
  passed.push('hundred-and-one-groups-fail-without-luna-publication-or-locked-controls');

  mode = 'group';
  for (let attempt = 0; attempt < 3; attempt += 1) {
    await upload(original, `rejected-${attempt}.als`, false);
    const beforeValidationSuggest = operationCount('suggest');
    failNextValidation = true;
    await validateSelection();
    assert.match(await page.locator('#notice').textContent(), /selected solution failed equivalence validation/);
    assert.equal(await page.locator('#selection').isVisible(), false);
    assert.equal(await page.locator('#preview').isVisible(), false);
    assert.equal(await page.locator('#prepare').isEnabled(), true);
    assert.equal(retainedDrafts.size, 0);
    assert.equal(latest('discard').data.id, `draft-${sequence}`);
    assert.equal(operationCount('suggest'), beforeValidationSuggest);
  }
  await upload(original, 'after-rejections.als');
  assert.equal(await page.locator('#preview').isVisible(), true);
  await page.locator('#discard').click();
  await page.waitForFunction(() => document.querySelector('#preview').hidden);
  assert.equal(retainedDrafts.size, 0);
  passed.push('rejected-selection-drafts-are-discarded-so-repeated-validation-and-retry-remain-usable');

  await upload(original, 'old-rejection.als', false);
  const cleanup = { id: `draft-${sequence + 1}`, started: latch(), release: latch(), finished: latch() };
  pausedDiscard = cleanup; failNextValidation = true;
  await page.locator('#validate-selection').click(); await cleanup.started.promise;
  await page.locator('#model-file').setInputFiles({ name: 'newer.als', mimeType: 'text/plain', buffer: Buffer.from(original) });
  await page.locator('#prepare').click();
  await page.waitForFunction(() => !document.querySelector('#prepare').disabled);
  const newerDraft = `draft-${sequence}`;
  assert.notEqual(newerDraft, cleanup.id);
  assert.equal(await page.locator('#selection').isVisible(), true);
  cleanup.release.resolve(); await cleanup.finished.promise; await page.waitForTimeout(50);
  assert.equal(await page.locator('#selection').isVisible(), true);
  assert.deepEqual(await page.locator('.predicate-choice strong').allTextContents(), ['Inv1', 'acyclic']);
  assert.equal(await page.locator('#model-id').inputValue(), 'newer');
  assert.equal(retainedDrafts.has(newerDraft), true);
  assert.equal(retainedDrafts.has(cleanup.id), false);
  assert.match(await page.locator('#notice').textContent(), /Choose the predicates/);
  await validateSelection();
  assert.equal(latest('prepare').data.modelId, 'newer');
  assert.equal(await page.locator('#source-preview').textContent(), original);
  await page.locator('#discard').click();
  await page.waitForFunction(() => document.querySelector('#preview').hidden);
  passed.push('delayed-rejection-cleanup-preserves-the-newer-upload-and-its-owned-draft');

  mode = 'group';
  const pending = { started: latch(), release: latch() }; pausedPrepare = pending;
  await page.locator('#model-file').setInputFiles({ name: 'late.als', mimeType: 'text/plain', buffer: Buffer.from(original) });
  await page.locator('#prepare').click();
  await pending.started.promise;
  const lateResponse = page.waitForResponse(response => response.url().endsWith('/api/admin/prepare'));
  const beforeLateSuggest = operationCount('suggest');
  await page.locator('#model-file').setInputFiles({ name: 'replacement.als', mimeType: 'text/plain', buffer: Buffer.from(original) });
  pending.release.resolve(); await (await lateResponse).finished(); await page.waitForTimeout(50);
  assert.equal(await page.locator('#selection').isVisible(), false);
  assert.equal(await page.locator('#preview').isVisible(), false);
  assert.equal(await page.locator('#predicate-choices').textContent(), '');
  assert.equal(await page.locator('#source-preview').textContent(), '');
  assert.equal(await page.locator('#model-id').inputValue(), 'replacement');
  assert.equal(await page.locator('#prepare').isEnabled(), true);
  assert.equal(operationCount('suggest'), beforeLateSuggest);
  assert.equal(retainedDrafts.size, 0);
  await upload(original, 'fresh.als', false);
  await page.locator('#selection-discard').click();
  await page.waitForFunction(() => document.querySelector('#selection').hidden);
  assert.equal(await page.locator('#predicate-choices').textContent(), '');
  assert.equal(await page.locator('#source-preview').textContent(), '');
  passed.push('late-discovery-cannot-restore-cleared-source-selection-or-provider-authority');

  const beforeInvalid = operationCount('prepare');
  for (const [content, name, expected] of [[Buffer.from([0xc3, 0x28]), 'bad.als', /not valid UTF-8/],
    ['sig A {}\0', 'nul.als', /without NUL/], ['sig A {}', 'wrong.txt', /UTF-8 \.als/],
    [Buffer.alloc(262145, 65), 'large.als', /256 KiB/]]) {
    await upload(content, name);
    assert.match(await page.locator('#notice').textContent(), expected);
    assert.equal(operationCount('prepare'), beforeInvalid);
  }
  passed.push('invalid-utf8-nul-extension-and-size-fail-before-upload');

  await page.locator('#question-seed').fill('λ'.repeat(4097));
  await upload('sig A {}\npred p {some A}');
  assert.match(await page.locator('#notice').textContent(), /8 KiB/);
  assert.equal(operationCount('prepare'), beforeInvalid);
  await page.locator('#question-seed').fill('');
  passed.push('teaching-goal-enforces-utf8-byte-limit');

  mode = 'single';
  const beforeSingle = operationCount('suggest');
  await upload('sig Node {}\npred Reachable {some Node}');
  assert.deepEqual(await page.locator('.exercise-card h3').allTextContents(), ['Reachable']);
  assert.equal(operationCount('suggest'), beforeSingle + 1);
  assert.equal(await page.locator('#reviewed').isChecked(), false);
  passed.push('single-predicate-also-gets-automatic-luna-metadata');

  mode = 'manual';
  await page.locator('#suggest').click();
  await page.waitForFunction(() => !document.querySelector('#suggest').disabled);
  assert.match(await page.locator('#notice').textContent(), /Luna is unavailable/);
  assert.equal(await page.locator('.exercise-card textarea').first().isEditable(), true);
  await page.locator('.exercise-card textarea').first().fill('A manually reviewed public question.');
  assert.equal(await page.locator('#publish').isEnabled(), true);
  passed.push('provider-unavailable-keeps-manual-review-usable');

  await page.locator('#discard').click();
  await page.waitForFunction(() => document.querySelector('#preview').hidden);
  assert.equal(await page.locator('#source-preview').textContent(), '');
  mode = 'rejected';
  const beforeRejected = operationCount('suggest');
  await upload('not a model');
  assert.match(await page.locator('#notice').textContent(), /could not be validated/);
  assert.equal(await page.locator('#preview').isVisible(), false);
  assert.equal(operationCount('suggest'), beforeRejected);
  passed.push('discard-and-rejected-preparation-never-publish-or-call-luna');

  mode = 'single';
  await upload('sig Node {}\npred Reachable {some Node}');
  expireNext = true;
  await page.locator('#suggest').click();
  await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('session expired'));
  assert.equal(await page.locator('#workspace').isVisible(), false);
  assert.equal(await page.locator('#login-form').isVisible(), true);
  assert.equal(await page.locator('#source-preview').textContent(), '');
  assert.equal(await page.locator('#model-file').inputValue(), '');
  assert.equal(await page.locator('#exercise-cards').textContent(), '');
  assert.equal(await page.locator('#predicate-choices').textContent(), '');
  assert.equal(await page.locator('#selection').isVisible(), false);
  passed.push('expired-session-clears-draft-source-and-returns-to-login');

  await login();
  await page.locator('#logout').click();
  await page.waitForFunction(() => !document.querySelector('#login-form').hidden);
  assert.equal(await page.locator('#workspace').isVisible(), false);
  assert.equal(await page.locator('#logout').isVisible(), false);
  assert.equal(await page.locator('#password').inputValue(), '');
  passed.push('logout-clears-authority-and-private-page-state');

  const prefixStart = requests.length;
  await page.setViewportSize({ width: 375, height: 812 });
  await loaded('alloy/');
  await login();
  await upload('sig Node {}\npred Reachable {some Node}');
  assert.equal(await page.locator('a.brand').getAttribute('href'), '../');
  assert.equal(requests.slice(prefixStart).every(row => row.path.startsWith('/alloy/api/admin/')), true);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  assert.equal(await page.locator('#preview').isVisible(), true);
  passed.push('narrow-iis-subapplication-keeps-assets-api-and-layout-in-scope');

  assert.deepEqual(external, []);
  assert.deepEqual(apiErrors, []);
  assert.deepEqual(errors, []);
  passed.push('offline-browser-has-no-external-requests-or-script-errors');
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}

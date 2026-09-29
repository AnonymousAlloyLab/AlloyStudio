// Exercise the real admin assets against a finite, offline API fixture.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
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
const groups = () => mode === 'single' ? [{ predicate: 'Reachable', id: 'fixture-Reachable',
  variants: ['Reachable'], oracleCount: 1, title: 'Reachability', question: 'Describe reachability.' }]
  : [{ predicate: 'Inv1', id: 'fixture-Inv1', variants: ['Inv1C0', 'Inv1C7'],
    oracleCount: 2, title: 'Node constraint', question: 'Constrain the nodes.' },
  { predicate: 'acyclic', id: 'fixture-acyclic', variants: ['acyclic'],
    oracleCount: 1, title: 'Acyclicity', question: 'Constrain cycles.' }];
function draft(state = 'ready', message = 'Check these public questions before publishing.') {
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
      expireNext = false; authenticated = false;
      send({ error: 'Your session expired. Sign in again.' }, 401); return;
    }
    assert.equal(authenticated, true);
    if (operation === 'logout') { authenticated = false; send({ ok: true }); return; }
    if (operation === 'prepare') {
      prepared = data; preparedGroups = groups(); sequence += 1; revision = 1; pollCount = 0;
      send(draft('preparing', 'Checking the model.'), 202); return;
    }
    if (operation.startsWith('drafts/')) {
      pollCount += 1;
      if (mode === 'rejected') { send(draft('rejected', 'The model could not be validated.')); return; }
      send(draft()); return;
    }
    if (operation === 'suggest') {
      assert.equal(data.id, `draft-${sequence}`);
      assert.equal(data.revision, revision);
      revision += 1;
      if (mode === 'manual') {
        send(draft('ready', 'Luna is unavailable. Enter the public questions yourself.')); return;
      }
      const value = draft();
      value.groups = value.groups.map(group => ({ ...group, title: attack, question: `Public prose ${attack}` }));
      value.message = `Review the suggested wording. ${attack}`;
      send(value); return;
    }
    if (operation === 'commit') {
      assert.equal(data.id, `draft-${sequence}`);
      assert.equal(data.revision, revision);
      send({ exerciseIds: data.exercises.map(item => `fixture-${item.predicate}`), exerciseCount: 183 }); return;
    }
    if (operation === 'discard') { send({ ok: true }); return; }
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
  const upload = async (content, name = 'fixture.als') => {
    await page.locator('#model-file').setInputFiles({ name, mimeType: 'text/plain', buffer: Buffer.isBuffer(content) ? content : Buffer.from(content) });
    await page.locator('#prepare').click();
    await page.waitForFunction(() => !document.querySelector('#prepare').disabled);
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
  await upload(original);
  assert.equal(latest('prepare').data.source, original);
  assert.deepEqual(Buffer.from(latest('prepare').data.source), Buffer.from(original));
  assert.deepEqual(Object.keys(latest('prepare').data).sort(), ['equivalenceScope', 'filename', 'modelId', 'source']);
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

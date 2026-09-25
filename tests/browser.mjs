import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, readFile } from 'node:fs/promises';
import { createServer, request as httpRequest } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const artifacts = path.join(root, 'build/browser');
await mkdir(artifacts, { recursive: true });
const server = spawn('python3', ['server.py', '--port', '0'], {
  cwd: root, env: { ...process.env, OPENAI_DISABLED: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let browser;
const passed = [];
const errors = [];
const externalRequests = [];
async function check(name, fn) { await fn(); passed.push(name); }
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const result = (payload, distance) => ({ ...payload, body: undefined, status: 'ok', metric: 'acgn-fast-rewrite-canonical-distance',
  distance, breakdown: { temporal: 0, quantifier: 0, matrix: distance }, canonicalForm: ['some Node'],
  operations: distance ? [{ kind: 'component-edit', component: 'matrix', path: 'matrix', cost: distance, aggregate: true,
    description: 'Matrix edit units with no matching detailed trace.' }] : [],
  trace: { cost: distance, matchesDistance: true, hasAggregates: distance > 0, certifiedOptimalScript: false } });
try {
  const url = await new Promise((resolve, reject) => {
    let output = '';
    const timer = setTimeout(() => reject(new Error('Server startup timeout')), 10000);
    server.stdout.on('data', chunk => { output += chunk; const match = output.match(/http:\/\/127\.0\.0\.1:\d+/); if (match) { clearTimeout(timer); resolve(match[0]); } });
    server.on('exit', code => { clearTimeout(timer); reject(new Error(`Server exited ${code}`)); });
  });
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  await context.route('**/*', route => {
    if (!route.request().url().startsWith(url) && !route.request().url().startsWith('blob:')) {
      externalRequests.push(route.request().url()); return route.abort();
    }
    return route.continue();
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  const editor = page.locator('#predicate-editor');
  const feedback = page.locator('#feedback-state');
  const waitChecked = async () => { await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked'); };
  const submit = async body => { await editor.fill(body); await page.locator('#check-button').click(); await waitChecked(); };
  let record;

  await check('real-engine-initial-load-and-private-projection', async () => {
    await page.goto(url + '/?exercise=graphs-inv1');
    await waitChecked();
    assert.equal(await page.locator('#exercise-count').textContent(), '181');
    assert.equal(await page.locator('.distance-value').count(), 1);
    await page.locator('.explanation-unavailable').waitFor();
    assert.match(await page.locator('#luna-explanation-body').textContent(), /not configured/);
    record = await (await context.request.get(url + '/api/exercises/graphs-inv1')).json();
    assert(!('oracleBody' in record)); assert(!('originalSource' in record));
    assert.equal(await page.locator('#environment-code').textContent(), record.environmentBefore);
    await page.screenshot({ path: path.join(artifacts, 'desktop.png'), fullPage: true });
  });
  await check('real-invalid-diagnostic-and-canonical-zero-witness', async () => {
    await page.locator('#live-feedback').uncheck();
    await editor.fill('some UNDECLARED_NODE');
    await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Check syntax');
    assert.match(await page.locator('.diagnostic-location').textContent(), /Line 1/);
    await submit('adj = ~adj');
    assert.equal(await page.locator('.distance-value').textContent(), '0');
    assert.equal(await page.locator('.match-badge').textContent(), 'Canonical match');
    await page.locator('.canonical-details summary').click();
    assert((await page.locator('.canonical-details pre').textContent()).length > 0);
  });
  await check('expanded-operator-hint-drives-a-real-source-repair', async () => {
    await page.locator('[data-exercise-id="graphs-inv5"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv5'));
    await submit('some (iden & adj)');
    assert.equal(await page.locator('.distance-value').textContent(), '1');
    const replacement = await page.locator('.replacement-operator code').first().textContent();
    assert.equal(replacement, 'no');
    assert.match(await page.locator('.operation-fragment code').first().textContent(), /some/);
    assert((await page.locator('.operation-next-step').first().textContent()).length > 15);
    assert.equal(await page.locator('.operation-structure[open]').count(), 0);
    // A learner can use the displayed primitive operator hint in their actual source.
    await submit(replacement + ' (iden & adj)');
    assert.equal(await page.locator('.distance-value').textContent(), '0');
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
  });
  await check('alternative-correct-formulation-matches-the-inclusive-pool', async () => {
    await page.locator('[data-exercise-id="graphs-inv5"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv5'));
    await submit('all n: Node | n not in n.adj');
    assert.equal(await page.locator('.distance-value').textContent(), '0');
    assert.match(await page.locator('.distance-description').allTextContents().then(values => values.join(' ')),
      /Compared all \d+ private candidates, including the oracle/);
    const response = await context.request.post(url + '/api/feedback', {
      data: { exerciseId: 'graphs-inv5', body: 'all n: Node | n not in n.adj', revision: 401 },
    });
    const feedback = await response.json();
    assert.equal(feedback.comparison.strategy, 'nearest-known-correct');
    assert.equal(feedback.comparison.evaluatedCandidates, feedback.comparison.poolSize);
    assert.equal(feedback.comparison.complete, true);
    assert(feedback.comparison.poolSize > 1);
    assert(!JSON.stringify(feedback).includes('referenceBodies'));
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
  });
  await check('draft-survives-reload-and-exercise-switch', async () => {
    await editor.fill('some Node // browser draft');
    await page.reload();
    await editor.waitFor({ state: 'visible' });
    await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
    assert.equal(await editor.inputValue(), 'some Node // browser draft');
    await page.locator('[data-exercise-id="graphs-inv2"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv2'));
    await editor.fill('no Node // second draft');
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
    assert.equal(await editor.inputValue(), 'some Node // browser draft');
  });
  await check('preserved-environment-and-download', async () => {
    await page.locator('[data-context="after"]').click();
    assert.equal(await page.locator('#environment-code').textContent(), record.environmentAfter);
    await page.locator('[data-context="before"]').click();
    const downloadPromise = page.waitForEvent('download');
    await page.locator('#download-button').click();
    const download = await downloadPromise;
    const contents = await readFile(await download.path(), 'utf8');
    assert.equal(contents, record.environmentBefore + record.predicateHeader + '{\n' + await editor.inputValue() + '\n}' + record.environmentAfter);
    assert(!contents.includes('inv1c'));
  });
  await check('search-filter-reset-and-keyboard', async () => {
    await page.locator('#group-filter').selectOption('graphs');
    assert.equal(await page.locator('.exercise-item').count(), 8);
    await page.locator('#exercise-search').fill('no such exercise');
    assert.equal(await page.locator('.exercise-item').count(), 0);
    await page.locator('#exercise-search').fill('');
    await page.locator('#reset-button').click();
    assert.equal(await editor.inputValue(), record.starter);
    await editor.fill('some Node');
    await editor.press('End'); await editor.press('Tab');
    assert.equal(await editor.inputValue(), 'some Node  ');
    await editor.press('Control+Enter'); await waitChecked();
  });
  let oldStarted;
  await check('stale-feedback-cannot-overwrite-newer-draft', async () => {
    const started = new Promise(resolve => { oldStarted = resolve; });
    await page.route('**/api/feedback', async route => {
      const payload = route.request().postDataJSON();
      const old = payload.body.includes('older');
      if (old) { oldStarted(); await delay(350); }
      try { await route.fulfill({ json: result(payload, old ? 99 : 2) }); } catch {}
    });
    await editor.fill('some Node // older'); await page.locator('#check-button').click();
    await started;
    await submit('some Node // newer');
    await delay(500);
    assert.equal(await page.locator('.distance-value').textContent(), '2');
    await page.unroute('**/api/feedback');
  });
  await check('stale-luna-output-and-plain-text-rendering', async () => {
    let resolveOld;
    const started = new Promise(resolve => { resolveOld = resolve; });
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 1) }));
    await page.route('**/api/explain', async route => {
      const payload = route.request().postDataJSON();
      const old = payload.body.includes('old guidance');
      if (old) { resolveOld(); await delay(350); }
      try { await route.fulfill({ json: { exerciseId: payload.exerciseId, revision: payload.revision, status: 'ok', model: 'gpt-6-luna',
        text: old ? 'OBSOLETE_GUIDANCE' : '<img src=x onerror="window.INJECTED=true"> Current guidance.' } }); } catch {}
    });
    await submit('some Node // old guidance'); await started;
    await submit('some Node // current guidance');
    await page.locator('.explanation-text').waitFor(); await delay(500);
    assert.match(await page.locator('.explanation-text').textContent(), /Current guidance/);
    assert(!((await page.locator('#luna-explanation-body').textContent()).includes('OBSOLETE_GUIDANCE')));
    assert.equal(await page.locator('#luna-explanation-body img').count(), 0);
    assert.equal(await page.evaluate(() => window.INJECTED), undefined);
    await page.unroute('**/api/feedback'); await page.unroute('**/api/explain');
  });
  await check('timeout-busy-and-quota-errors-remain-usable', async () => {
    for (const [status, label] of [['timeout', 'Timed out'], ['busy', 'Server busy']]) {
      await page.route('**/api/feedback', route => route.fulfill({ json: { ...route.request().postDataJSON(), status, diagnostics: [{ message: 'Fixture failure' }] } }));
      await editor.fill('some Node // ' + status); await page.locator('#check-button').click();
      await page.waitForFunction(expected => document.querySelector('#feedback-state').textContent === expected, label);
      assert.equal(await editor.isEnabled(), true);
      await page.unroute('**/api/feedback');
    }
    await page.route('**/api/explain', route => route.fulfill({ json: { ...route.request().postDataJSON(), status: 'unavailable', model: 'gpt-6-luna', message: 'The OpenAI project has no available API credits.' } }));
    await submit('some Node'); await page.locator('.explanation-unavailable').waitFor();
    assert.match(await page.locator('.explanation-unavailable').textContent(), /credits/);
    assert.equal(await feedback.textContent(), 'Checked');
    await page.unroute('**/api/explain');
  });
  await check('live-debounce-and-mobile-layout', async () => {
    await page.locator('#live-feedback').check();
    await editor.fill('no Node'); await editor.fill('some Node');
    await waitChecked();
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.screenshot({ path: path.join(artifacts, 'mobile.png'), fullPage: true });
    assert.equal(await editor.isEnabled(), true);
  });
  await check('iis-prefix-proxy-assets-navigation-feedback-and-download', async () => {
    // Model an IIS virtual application: strip /alloy and rewrite Host to the
    // loopback backend. The browser's Origin remains the public proxy origin.
    let backendURL;
    let backend;
    let proxyContext;
    const proxyRequests = [];
    const proxy = createServer((request, response) => {
      proxyRequests.push(request.url);
      if (!request.url.startsWith('/alloy/')) {
        response.writeHead(404); response.end('Outside the Alloy application'); return;
      }
      if (!backendURL) { response.writeHead(503); response.end(); return; }
      const target = new URL(request.url.slice('/alloy'.length), backendURL);
      const upstream = httpRequest(target, {
        method: request.method,
        headers: { ...request.headers, host: target.host,
          'x-forwarded-host': request.headers.host, 'x-forwarded-proto': 'http' },
      }, reply => {
        response.writeHead(reply.statusCode, reply.headers); reply.pipe(response);
      });
      upstream.on('error', () => { response.writeHead(502); response.end(); });
      request.pipe(upstream);
    });
    await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
    const proxyOrigin = `http://127.0.0.1:${proxy.address().port}`;
    try {
      backend = spawn('python3', ['server.py', '--port', '0', '--public-origin', proxyOrigin], {
        cwd: root, env: { ...process.env, OPENAI_DISABLED: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
      });
      backendURL = await new Promise((resolve, reject) => {
        let output = '';
        const timer = setTimeout(() => reject(new Error('Proxy backend startup timeout')), 10000);
        backend.stdout.on('data', chunk => {
          output += chunk;
          const match = output.match(/http:\/\/127\.0\.0\.1:\d+/);
          if (match) { clearTimeout(timer); resolve(match[0]); }
        });
        backend.on('exit', code => { clearTimeout(timer); reject(new Error(`Proxy backend exited ${code}`)); });
      });
      proxyContext = await browser.newContext({ acceptDownloads: true });
      await proxyContext.route('**/*', route => {
        if (!route.request().url().startsWith(proxyOrigin + '/') && !route.request().url().startsWith('blob:')) {
          externalRequests.push(route.request().url()); return route.abort();
        }
        return route.continue();
      });
      const proxyPage = await proxyContext.newPage();
      proxyPage.on('pageerror', error => errors.push(error.message));
      await proxyPage.goto(proxyOrigin + '/alloy/?exercise=graphs-inv1');
      const checked = async () => proxyPage.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked');
      await checked();
      assert.equal(await proxyPage.locator('#exercise-count').textContent(), '181');
      assert(proxyRequests.includes('/alloy/app.js'));
      assert(proxyRequests.includes('/alloy/styles.css'));
      assert(proxyRequests.includes('/alloy/api/exercises'));
      assert(proxyRequests.includes('/alloy/api/feedback'));
      const proxyEditor = proxyPage.locator('#predicate-editor');
      await proxyPage.locator('#live-feedback').uncheck();
      await proxyEditor.fill('adj = ~adj');
      await proxyPage.locator('#check-button').click();
      await checked();
      assert.equal(await proxyPage.locator('.distance-value').textContent(), '0');
      await proxyPage.locator('[data-exercise-id="graphs-inv5"]').click();
      await proxyPage.waitForFunction(() => location.search.includes('graphs-inv5'));
      assert.equal(new URL(proxyPage.url()).pathname, '/alloy/');
      await proxyEditor.fill('some (iden & adj) // prefix draft');
      await proxyPage.locator('#check-button').click();
      await checked();
      assert.equal(await proxyPage.locator('.replacement-operator code').first().textContent(), 'no');
      const publicRecord = await (await proxyContext.request.get(proxyOrigin + '/alloy/api/exercises/graphs-inv5')).json();
      const downloadPromise = proxyPage.waitForEvent('download');
      await proxyPage.locator('#download-button').click();
      const download = await downloadPromise;
      const contents = await readFile(await download.path(), 'utf8');
      assert.equal(contents, publicRecord.environmentBefore + publicRecord.predicateHeader + '{\n' + await proxyEditor.inputValue() + '\n}' + publicRecord.environmentAfter);
      await proxyPage.reload();
      await proxyPage.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
      assert.equal(await proxyEditor.inputValue(), 'some (iden & adj) // prefix draft');
      assert(proxyRequests.every(request => request.startsWith('/alloy/')));
    } finally {
      await proxyContext?.close();
      backend?.kill('SIGTERM');
      await new Promise(resolve => proxy.close(resolve));
    }
  });
  await check('no-browser-errors-or-external-asset-requests', async () => {
    assert.deepEqual(errors, []);
    assert.deepEqual(externalRequests, []);
  });
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally {
  await browser?.close();
  server.kill('SIGTERM');
}

import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Serve the real application against a bounded deterministic API on an
// ephemeral port. No JVM, actual catalogue, credential, or provider is used.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const artifacts = path.join(root, 'build/browser-progressive-hints');
await mkdir(artifacts, { recursive: true });
const records = ['hints-a', 'hints-b'].map(id => ({ id, title: id, group: 'Hints', predicate: 'inv1',
  starter: 'some Node and some Node', predicateHeader: 'pred inv1 ', environmentBefore: 'sig Node {}\n',
  environmentAfter: '\n', description: 'Keep the intended collection of nodes.',
  contentVersion: 'f'.repeat(64), source: { path: `${id}.als`, sha256: id } }));
const calls = [], passed = [], errors = [], external = [];
const token = 'a'.repeat(64);
let pendingExplanation = null;
let delayExplanation = false;
const send = (response, data) => {
  if (!response.destroyed && !response.headersSent) {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(data));
  }
};
function sourceRange(body, start, end) {
  const position = offset => {
    const lines = body.slice(0, offset).split('\n');
    return { line: lines.length, column: lines.at(-1).length + 1 };
  };
  const first = position(start), last = position(end);
  return { start, end, text: body.slice(start, end), startLine: first.line,
    startColumn: first.column, endLine: last.line, endColumn: last.column,
    moduleLine: 2 + first.line, moduleColumn: first.column };
}
function feedback(payload) {
  const count = payload.body.includes('// zero') ? 0 : payload.body.includes('// one') ? 1 : 3;
  const second = Math.max(0, payload.body.lastIndexOf('some Node'));
  return { exerciseId: payload.exerciseId, revision: payload.revision, status: 'ok', requestedMetric: payload.metric,
    metric: payload.metric === 'ast' ? 'acgn-raw-ast-zhang-shasha-distance' : 'acgn-fast-rewrite-canonical-distance',
    distance: count, breakdown: { temporal: 0, quantifier: 0, matrix: count, ast: count },
    canonicalForm: ['some Node and some Node'], evidenceToken: token,
    operations: Array.from({ length: count }, (_, index) => ({ kind: 'replace', component: payload.metric === 'ast' ? 'ast' : 'matrix',
      path: `root.child[${index}]`, cost: 1, action: `Deterministic hint ${index + 1}`, sourceTerm: 'some Node',
      sourceOperator: 'some', replacementOperator: 'no', reason: `Reason ${index + 1}`,
      sourceLocation: { status: 'located', precision: 'node', coordinateSystem: 'body', offsetEncoding: 'utf-16',
        ranges: [sourceRange(payload.body, index === 1 ? second : 0, (index === 1 ? second : 0) + 9)] },
      canonicalLocation: payload.metric === 'ast' ? { status: 'unavailable', ranges: [] }
        : { status: 'located', precision: 'node', coordinateSystem: 'canonical', offsetEncoding: 'utf-16',
          ranges: [{ formIndex: 0, start: index === 1 ? 14 : 0, end: (index === 1 ? 14 : 0) + 9, text: 'some Node' }] },
    })), trace: { cost: count, matchesDistance: true, hasAggregates: false } };
}
function explanation(payload) {
  const ids = feedback(payload).operations.map((_, index) => `operation-${index + 1}`);
  return { exerciseId: payload.exerciseId, revision: payload.revision, requestedMetric: payload.metric,
    status: 'ok', operations: ids.map(id => ({ id, description: `Luna guidance for ${id}: ${payload.metric}.` })),
    instances: [], summary: 'FULL_ROUTE_SUMMARY describes all later repairs.' };
}
const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  if (url.pathname === '/' || /^\/[a-zA-Z0-9-]+\.(?:js|css)$/.test(url.pathname)) {
    const file = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
    try {
      const content = await readFile(path.join(root, 'web', file));
      response.writeHead(200, { 'Content-Type': file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html' });
      response.end(content);
    } catch { response.writeHead(404); response.end(); }
    return;
  }
  if (url.pathname === '/api/exercises') return send(response, { exercises: records });
  if (url.pathname.startsWith('/api/exercises/')) return send(response, records.find(record => record.id === url.pathname.split('/').at(-1)));
  let raw = ''; for await (const chunk of request) raw += chunk;
  const payload = raw ? JSON.parse(raw) : {};
  calls.push({ path: url.pathname, payload });
  if (url.pathname === '/api/channel') return send(response, { status: 'ok', channel: 'c'.repeat(43) });
  if (url.pathname === '/api/cancel') return send(response, { status: 'ok' });
  if (url.pathname === '/api/feedback') return send(response, feedback(payload));
  if (url.pathname === '/api/behavior') return send(response, { exerciseId: payload.exerciseId, revision: payload.revision, status: 'unavailable' });
  if (url.pathname === '/api/explain') {
    const finish = () => send(response, explanation(payload));
    if (delayExplanation) pendingExplanation = finish; else finish();
    return;
  }
  response.writeHead(404); response.end();
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
let browser;
async function check(name, operation) {
  try { await operation(); passed.push(name); }
  catch (error) { error.message = `${name}: ${error.message}`; throw error; }
}
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(() => localStorage.setItem('alloy-studio:v1:live', 'false'));
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await context.route('**/*', route => {
    if (new URL(route.request().url()).origin !== origin) { external.push(route.request().url()); return route.abort(); }
    return route.continue();
  });
  const editor = page.locator('#predicate-editor');
  const visibleHints = page.locator('.operation-item');
  const next = page.locator('.show-next-hint');
  const submit = async body => {
    if (body !== undefined) await editor.fill(body);
    await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked');
    await page.locator('.operation-explanation .education-description, .explanation-text').first().waitFor();
  };
  await page.goto(`${origin}/?exercise=hints-a`);
  await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);

  await check('canonical-first-only-default-and-luna-summary-withheld', async () => {
    assert.equal(await page.locator('#distance-metric').inputValue(), 'canonical');
    await submit();
    assert.equal(await visibleHints.count(), 1);
    assert.equal(await page.locator('.operation-explanation .education-description').count(), 1);
    assert.match(await page.locator('.hint-reveal-count').textContent(), /1 of 3.*2 more/);
    assert.equal(await next.getAttribute('aria-controls'), 'repair-hint-list');
    assert.equal(await next.getAttribute('aria-label'), 'Show next hint (2 remaining)');
    assert.equal(await page.locator('.explanation-text').count(), 0);
    assert(!(await page.locator('#feedback-result').textContent()).includes('FULL_ROUTE_SUMMARY'));
    assert(!(await page.locator('#feedback-result').textContent()).includes('operation-2'));
    assert(!(await page.locator('#feedback-result').textContent()).includes('Deterministic hint 2'));
  });
  await check('one-click-one-hint-preserves-location-and-does-not-request-api-work', async () => {
    const before = calls.length;
    await next.focus(); await page.keyboard.press('Enter');
    assert.equal(await visibleHints.count(), 2);
    assert.match(await page.locator('[data-operation-id="operation-2"] .education-description').textContent(), /operation-2/);
    assert.equal(await page.locator('.explanation-text').count(), 0);
    await page.locator('[data-operation-id="operation-2"] .operation-select').click();
    assert.deepEqual(await editor.evaluate(element => [element.selectionStart, element.selectionEnd]), [14, 23]);
    assert.equal(await page.locator('.source-range').count(), 1);
    assert.equal(await page.locator('.canonical-range').count(), 1);
    await next.click();
    assert.equal(await visibleHints.count(), 3);
    assert.equal(await next.isDisabled(), true);
    assert.equal(await next.textContent(), 'All hints shown');
    assert.match(await page.locator('.explanation-text').textContent(), /FULL_ROUTE_SUMMARY/);
    assert.equal(await page.locator('[data-operation-id="operation-2"]').getAttribute('class').then(value => value.includes('active-operation')), true);
    assert.deepEqual(await editor.evaluate(element => [element.selectionStart, element.selectionEnd]), [14, 23]);
    assert.equal(calls.length, before);
    await page.screenshot({ path: path.join(artifacts, 'revealed.png'), fullPage: true });
  });
  await check('draft-and-new-check-reset-reveal-count-and-stale-button', async () => {
    await page.evaluate(() => { window.oldHintButton = document.querySelector('.show-next-hint'); });
    await editor.fill('some Node and some Node // changed');
    assert.equal(await visibleHints.count(), 0);
    await submit();
    assert.equal(await visibleHints.count(), 1);
    await page.evaluate(() => window.oldHintButton.click());
    assert.equal(await visibleHints.count(), 1);
    await next.click(); assert.equal(await visibleHints.count(), 2);
    await submit(); assert.equal(await visibleHints.count(), 1);
  });
  await check('ast-metric-resets-and-revealed-locator-remains-exact', async () => {
    await next.click(); assert.equal(await visibleHints.count(), 2);
    await page.locator('#distance-metric').selectOption('ast');
    await page.locator('.distance-result[data-metric="ast"] .distance-value').waitFor();
    await page.locator('.operation-explanation .education-description').waitFor();
    assert.equal(await visibleHints.count(), 1);
    assert.equal(await page.locator('.explanation-text').count(), 0);
    await next.click();
    await page.locator('[data-operation-id="operation-2"] .operation-select').click();
    assert.deepEqual(await editor.evaluate(element => [element.selectionStart, element.selectionEnd]), [14, 23]);
    assert.equal(await page.locator('.canonical-range').count(), 0);
    assert.match(await page.locator('[data-operation-id="operation-2"] .education-description').textContent(), /ast/);
  });
  await check('exercise-selection-and-reset-discard-revealed-hints', async () => {
    await page.locator('[data-exercise-id="hints-b"]').click();
    await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled && location.search.includes('hints-b'));
    assert.equal(await visibleHints.count(), 0);
    await submit(); assert.equal(await visibleHints.count(), 1);
    await next.click(); await page.locator('#reset-button').click();
    assert.equal(await visibleHints.count(), 0);
    await submit(); assert.equal(await visibleHints.count(), 1);
  });
  await check('empty-and-single-operation-traces-keep-summary-without-reveal-control', async () => {
    await submit('some Node // zero');
    assert.equal(await visibleHints.count(), 0);
    assert.equal(await next.count(), 0);
    assert.match(await page.locator('.no-operations').textContent(), /No structural edits/);
    assert.match(await page.locator('.explanation-text').textContent(), /FULL_ROUTE_SUMMARY/);
    await submit('some Node // one');
    assert.equal(await visibleHints.count(), 1);
    assert.equal(await next.count(), 0);
    assert.match(await page.locator('.explanation-text').textContent(), /FULL_ROUTE_SUMMARY/);
  });
  await check('late-guidance-never-creates-unrevealed-operation-descriptions', async () => {
    delayExplanation = true;
    await editor.fill('some Node and some Node // delayed');
    await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked');
    for (let tries = 0; !pendingExplanation && tries < 100; tries += 1) await new Promise(resolve => setTimeout(resolve, 10));
    assert(pendingExplanation);
    await next.click(); assert.equal(await visibleHints.count(), 2);
    pendingExplanation(); pendingExplanation = null; delayExplanation = false;
    await page.locator('[data-operation-id="operation-2"] .education-description').waitFor();
    assert.equal(await page.locator('.operation-explanation .education-description').count(), 2);
    assert.equal(await page.locator('[data-operation-id="operation-3"]').count(), 0);
    assert.equal(await page.locator('.explanation-text').count(), 0);
    await next.click(); await page.locator('.explanation-text').waitFor();
  });
  await check('mobile-controls-retain-label-and-no-overflow', async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    await submit('some Node and some Node // mobile');
    assert.equal(await visibleHints.count(), 1);
    assert.equal(await next.isVisible(), true);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.screenshot({ path: path.join(artifacts, 'first-hint-mobile.png'), fullPage: true });
  });
  assert.deepEqual(errors, []);
  assert.deepEqual(external, []);
  const report = { status: 'PASS', checks: passed.length, passed, externalRequests: external.length };
  await writeFile(path.join(artifacts, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally {
  pendingExplanation?.();
  await browser?.close();
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
}

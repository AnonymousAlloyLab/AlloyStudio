#!/usr/bin/env node
// Isolated rendering tests: an ephemeral static server, synthetic public state,
// production assets and independently measured geometry; no API or credentials.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { instanceDiagramGeometry } from './instance-graph-geometry.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.resolve(process.argv.find(value => value.startsWith('--output='))?.slice(9)
  || path.join(root, 'build/instance-labels/tests'));
const scratch = path.join(output, 'tmp');
await mkdir(scratch, { recursive: true });
for (const name of ['TMPDIR', 'TMP', 'TEMP']) process.env[name] = scratch;
const sources = ['web/instance-graph.js', 'web/styles.css', 'tests/instance-labels.mjs',
  'tests/instance-graph-geometry.mjs', 'docs/instance-labels-spec.md'];
const frozen = new Map(await Promise.all(sources.map(async name => [name, await readFile(path.join(root, name))])));
const report = { status: 'RUNNING', sourceHashes: Object.fromEntries([...frozen].map(([name, bytes]) =>
  [name, createHash('sha256').update(bytes).digest('hex')])), checks: 0, passed: [], cases: [], externalRequests: [], errors: [],
  protocol: { solverCalls: 0, providerCalls: 0, ephemeralStaticServer: true, viewports: [1440, 390],
    independentGeometry: true, syntheticStates: true } };
const document = '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">'
  + '<link rel="stylesheet" href="/styles.css"></head><body><main id="probe"></main></body></html>';
const assets = new Map([
  ['/', [Buffer.from(document), 'text/html; charset=utf-8']],
  ['/styles.css', [frozen.get('web/styles.css'), 'text/css; charset=utf-8']],
  ['/instance-graph.js', [frozen.get('web/instance-graph.js'), 'text/javascript; charset=utf-8']],
]);
const server = createServer((request, response) => {
  const asset = assets.get(new URL(request.url, 'http://localhost').pathname);
  response.writeHead(asset ? 200 : 404, { 'Content-Type': asset?.[1] || 'text/plain', 'Cache-Control': 'no-store' });
  response.end(asset?.[0] || 'Not found');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const state = { index: 0, signatures: [
  { label: 'Node', atoms: ['Node$0', 'Node$1', '7'] }, { label: 'NamedNumeric', atoms: ['7'] },
  { label: 'Int', atoms: ['3'] },
], relations: [
  { label: 'size', arity: 2, tuples: [['Node$0', '-2'], ['Node$1', '3']] },
  { label: 'size', arity: 2, tuples: [['Node$0', '3']] },
  { label: 'placement', arity: 3, tuples: [['Node$0', '-2', 'Node$1']] },
  { label: 'empty', arity: 2, tuples: [] },
  { label: '<img src=x onerror=alert(1)>', arity: 2, tuples: [['Node$1', '7']] },
] };
let browser;
async function probe(name, action) {
  try { await action(); report.cases.push({ name, status: 'PASS' }); }
  catch (error) { report.cases.push({ name, status: 'FAIL', message: error.message }); throw error; }
}
async function check(name, action) {
  await action(); report.passed.push(name); report.checks = report.passed.length;
}
try {
  browser = await chromium.launch({ headless: true,
    executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined,
    ignoreDefaultArgs: ['--disable-dev-shm-usage'], env: { ...process.env, TMPDIR: scratch, TMP: scratch, TEMP: scratch } });
  const context = await browser.newContext();
  context.on('request', request => { if (!request.url().startsWith(`${base}/`)) report.externalRequests.push(request.url()); });
  const page = await context.newPage();
  page.on('pageerror', error => report.errors.push(error.message));
  await page.goto(base); await page.evaluate(() => document.fonts.ready);
  await check('numeric labels and indexed relation focus preserve supplied data', async () => {
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.evaluate(async state => {
      const { renderInstanceGraph } = await import('/instance-graph.js');
      document.querySelector('#probe').replaceChildren(renderInstanceGraph(state));
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    }, state);
    const graph = page.locator('.instance-graph');
    const selectedTuples = () => graph.locator('.instance-graph-tuple.instance-graph-context-related')
      .evaluateAll(elements => elements.map(element => element.dataset.tupleId).sort());
    const selectedNodes = () => graph.locator('.instance-graph-node.instance-graph-context-related')
      .evaluateAll(elements => elements.map(element => element.dataset.atom).sort());
    await probe(`numeric labels preserve actual membership ${width}`, async () => {
      assert.equal(await graph.locator('[data-atom="-2"] .instance-graph-node-label').textContent(), 'Value · -2');
      assert.equal(await graph.locator('[data-atom="3"] .instance-graph-node-label').textContent(), 'Int · 3');
      assert.equal(await graph.locator('[data-atom="7"] .instance-graph-node-label').textContent(), 'Node · 7');
      assert(!/Member of Int/.test(await graph.locator('[data-atom="-2"]').getAttribute('aria-label')));
      const labels = await graph.locator('.instance-graph-node-label').allTextContents();
      assert(labels.every(label => !/^[+-]?\d+$/.test(label)));
    });
    await probe(`numeric click exposes exact connected relations ${width}`, async () => {
      await graph.locator('[data-atom="-2"]').click();
      assert.deepEqual(await selectedTuples(), ['tuple-0-0', 'tuple-2-0']);
      assert.deepEqual(await selectedNodes(), ['-2', 'Node$0', 'Node$1']);
      assert.deepEqual(await graph.locator('.instance-graph-object-relation').evaluateAll(buttons =>
        buttons.map(button => [button.dataset.relationIndex, button.textContent])), [['0', 'size'], ['2', 'placement']]);
      assert((await graph.locator('.instance-graph-details').textContent()).includes('Connected by size, placement.'));
    });
    await probe(`value relation button highlights exact named relation ${width}`, async () => {
      await graph.locator('.instance-graph-object-relation[data-relation-index="0"]').click();
      assert.deepEqual(await selectedTuples(), ['tuple-0-0', 'tuple-0-1']);
      assert.deepEqual(await selectedNodes(), ['-2', '3', 'Node$0', 'Node$1']);
      assert((await graph.locator('.instance-graph-details').textContent()).includes('Showing 2 of 2 supplied tuples'));
      assert.equal(await graph.locator('.instance-graph-relation-button[data-relation-index="0"]').getAttribute('aria-pressed'), 'true');
    });
    await probe(`equal relation names use exact indexes and keyboard selection ${width}`, async () => {
      const button = graph.locator('.instance-graph-relation-button[data-relation-index="1"]');
      await button.focus(); await button.press('Enter');
      assert.deepEqual(await selectedTuples(), ['tuple-1-0']);
      assert.deepEqual(await selectedNodes(), ['3', 'Node$0']);
      assert.equal(await button.getAttribute('aria-pressed'), 'true');
      assert.equal(await graph.locator('.instance-graph-relation-button[data-relation-index="0"]').getAttribute('aria-pressed'), 'false');
    });
    await probe(`empty and filtered-out relation selection invents no tuples ${width}`, async () => {
      await graph.locator('.instance-graph-relation-button[data-relation-index="3"]').click();
      assert.deepEqual(await selectedTuples(), []); assert.deepEqual(await selectedNodes(), []);
      assert((await graph.locator('.instance-graph-details').textContent()).includes('no tuples in this state'));
      await graph.locator('.instance-graph-relation-filter').selectOption('2');
      assert.equal(await graph.locator('[aria-pressed="true"]').count(), 0);
      await graph.locator('.instance-graph-relation-button[data-relation-index="0"]').click();
      assert.deepEqual(await selectedTuples(), []);
      assert.equal(await graph.locator('[data-tuple-id]').count(), 1);
      assert.equal(await graph.locator('.instance-graph-relation-filter').inputValue(), '2');
      assert((await graph.locator('.instance-graph-details').textContent()).includes('Showing 0 of 2 supplied tuples'));
      await graph.locator('.instance-graph-relation-filter').selectOption('all');
    });
    await probe(`relation names stay text and reset clears all focus state ${width}`, async () => {
      await graph.locator('.instance-graph-relation-button[data-relation-index="4"]').click();
      assert.equal(await graph.locator('img, script, foreignObject, [onerror], [onclick]').count(), 0);
      assert.deepEqual(await selectedTuples(), ['tuple-4-0']);
      assert((await graph.locator('.instance-graph-details').textContent()).includes('<img src=x onerror=alert(1)>'));
      await graph.locator('.instance-graph-reset-context').click();
      assert.equal(await graph.locator('.instance-graph-context-related, .instance-graph-context-muted, [aria-pressed="true"], .instance-graph-relation-focused').count(), 0);
      assert.equal(await graph.locator('.instance-graph-object-relation').count(), 0);
    });
    await probe(`numeric and relation selection preserve independent geometry ${width}`, async () => {
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const geometry = await graph.locator('svg.instance-graph-canvas').evaluate(instanceDiagramGeometry);
      assert.deepEqual(geometry.failures, []);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
      await graph.screenshot({ path: path.join(output, `labels-${width}.png`) });
    });
  }
  });
  await check('selection is local to one concrete instance', async () => {
    await page.evaluate(async state => {
      const { renderInstanceGraph } = await import('/instance-graph.js');
      document.querySelector('#probe').replaceChildren(renderInstanceGraph(state), renderInstanceGraph(state));
    }, state);
    const diagrams = page.locator('.instance-graph');
    await diagrams.nth(0).locator('.instance-graph-relation-button[data-relation-index="1"]').click();
    assert.equal(await diagrams.nth(1).locator('.instance-graph-context-muted, [aria-pressed="true"]').count(), 0);
    assert.equal(await diagrams.nth(0).locator('.instance-graph-tuple.instance-graph-context-related').count(), 1);
  });
  assert.deepEqual(report.errors, []); assert.deepEqual(report.externalRequests, []);
  report.status = 'PASS';
} catch (error) {
  report.status = 'FAIL'; report.fatalError = error.message; throw error;
} finally {
  report.finished = new Date().toISOString();
  await writeFile(path.join(output, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
  await browser?.close(); await new Promise(resolve => server.close(resolve));
}
process.stdout.write(JSON.stringify({ status: report.status, checks: report.checks, passed: report.passed }) + '\n');

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Serve the production frontend with synthetic public exercise records. No
// private corpus, Java process, network service, or OpenAI request is needed.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const records = [
  { id: 'first', title: 'First graph', group: 'Graphs', description: 'Explore shared relations.' },
  { id: 'middle', title: 'Middle family', group: 'Families', description: 'Explore family relations.' },
  { id: 'last', title: 'Last graph', group: 'Graphs', description: 'Explore shared connections.' },
].map((record, index) => ({ ...record, predicate: `inv${index + 1}`, starter: `some Node // ${record.id}`,
  predicateHeader: `pred inv${index + 1} `, environmentBefore: 'sig Node {}\n', environmentAfter: '\n',
  source: { path: `${record.group.toLowerCase()}.als`, sha256: `fixture-${record.id}` } }));
const assets = new Map(await Promise.all([
  ['/', 'index.html', 'text/html'], ['/app.js', 'app.js', 'text/javascript'], ['/styles.css', 'styles.css', 'text/css'],
].map(async ([url, file, type]) => [url, { type, bytes: await readFile(path.join(root, 'web', file)) }])));
const server = createServer((request, response) => {
  const asset = assets.get(new URL(request.url, 'http://localhost').pathname);
  if (!asset) { response.writeHead(404); response.end(); return; }
  response.writeHead(200, { 'Content-Type': asset.type, 'Cache-Control': 'no-store' });
  response.end(asset.bytes);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const passed = [];
const errors = [];
const externalRequests = [];
let browser;
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
async function check(name, operation) { await operation(); passed.push(name); }
async function session({ catalogue = records, viewport = { width: 1440, height: 1000 }, detail } = {}) {
  const context = await browser.newContext({ viewport });
  await context.addInitScript(() => localStorage.setItem('alloy-studio:v1:live', 'false'));
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) { externalRequests.push(url.href); return route.abort(); }
    if (url.pathname === '/api/exercises') return route.fulfill({ json: { exercises: catalogue } });
    if (url.pathname.startsWith('/api/exercises/')) {
      const id = decodeURIComponent(url.pathname.slice('/api/exercises/'.length));
      const record = records.find(value => value.id === id);
      if (detail && await detail(route, id, record)) return;
      return route.fulfill({ json: record });
    }
    if (url.pathname.startsWith('/api/')) {
      errors.push(`Unexpected analysis request: ${url.pathname}`);
      return route.abort();
    }
    return route.continue();
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin);
  return { context, page, editor: page.locator('#predicate-editor'), previous: page.getByRole('button', { name: 'Previous exercise', exact: true }),
    next: page.getByRole('button', { name: 'Next exercise', exact: true }), position: page.locator('#exercise-position') };
}
async function selected(page, id) {
  await page.waitForFunction(expected => new URL(location.href).searchParams.get('exercise') === expected
    && !document.querySelector('#predicate-editor').disabled, id);
}

try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  await check('boundaries-use-catalogue-order-without-wrapping', async () => {
    const { context, page, previous, next, position } = await session();
    try {
      await selected(page, 'first');
      assert.equal(await page.locator('#distance-metric').inputValue(), 'canonical');
      assert.equal(await previous.isDisabled(), true);
      assert.equal(await next.isEnabled(), true);
      assert.equal(await position.textContent(), '1 of 3 exercises');
      await next.click(); await selected(page, 'middle');
      assert.equal(await previous.isEnabled(), true);
      assert.equal(await next.isEnabled(), true);
      assert.equal(await position.textContent(), '2 of 3 exercises');
      await next.click(); await selected(page, 'last');
      assert.equal(await next.isDisabled(), true);
      await previous.click(); await selected(page, 'middle');
      await previous.click(); await selected(page, 'first');
      assert.equal(await previous.isDisabled(), true);
    } finally { await context.close(); }
  });
  await check('search-and-model-filter-use-visible-order-and-disable-hidden-selection', async () => {
    const { context, page, previous, next, position } = await session();
    try {
      await selected(page, 'first');
      await page.locator('#group-filter').selectOption('Graphs');
      assert.equal(await position.textContent(), '1 of 2 matching exercises');
      await next.click(); await selected(page, 'last');
      assert.equal(await next.isDisabled(), true);
      await previous.click(); await selected(page, 'first');
      await page.locator('#group-filter').selectOption('');
      await page.locator('#exercise-search').fill('shared');
      assert.equal(await page.locator('.exercise-item').count(), 2);
      await next.click(); await selected(page, 'last');
      await page.locator('#exercise-search').fill('family');
      assert.equal(await previous.isDisabled(), true);
      assert.equal(await next.isDisabled(), true);
      assert.equal(await position.textContent(), 'Select a matching exercise');
      await page.locator('[data-exercise-id="middle"]').click(); await selected(page, 'middle');
      assert.equal(await position.textContent(), '1 of 1 matching exercises');
      assert.equal(await previous.isDisabled(), true);
      assert.equal(await next.isDisabled(), true);
      await page.locator('#exercise-search').fill('no matching record');
      assert.equal(await position.textContent(), 'No matching exercises');
      assert.equal(await previous.isDisabled(), true);
      assert.equal(await next.isDisabled(), true);
    } finally { await context.close(); }
  });
  await check('navigation-saves-and-restores-each-draft', async () => {
    const { context, page, editor, previous, next } = await session();
    try {
      await selected(page, 'first');
      await editor.fill('no Node // first draft');
      await next.click(); await selected(page, 'middle');
      await editor.fill('one Node // middle draft');
      await previous.click(); await selected(page, 'first');
      assert.equal(await editor.inputValue(), 'no Node // first draft');
      await next.click(); await selected(page, 'middle');
      assert.equal(await editor.inputValue(), 'one Node // middle draft');
      await page.reload(); await selected(page, 'middle');
      assert.equal(await editor.inputValue(), 'one Node // middle draft');
    } finally { await context.close(); }
  });
  await check('pending-navigation-disables-buttons-and-newer-sidebar-selection-wins', async () => {
    const waiting = deferred(), requested = deferred(), finished = deferred();
    const { context, page, editor, previous, next, position } = await session({ detail: async (route, id, record) => {
      if (id !== 'middle') return false;
      requested.resolve();
      await waiting.promise;
      try { await route.fulfill({ json: record }); }
      catch (error) { if (!/closed|canceled|cancelled|aborted/i.test(error.message)) throw error; }
      finally { finished.resolve(); }
      return true;
    } });
    try {
      await selected(page, 'first');
      await editor.fill('no Node // saved before pending request');
      await next.click(); await requested.promise;
      assert.equal(await previous.isDisabled(), true);
      assert.equal(await next.isDisabled(), true);
      assert.equal(await editor.isDisabled(), true);
      assert.equal(await position.textContent(), 'Loading exercise…');
      await page.locator('[data-exercise-id="last"]').click(); await selected(page, 'last');
      waiting.resolve(); await finished.promise;
      await page.waitForTimeout(50);
      assert.equal(await editor.inputValue(), 'some Node // last');
      assert.equal(await position.textContent(), '3 of 3 exercises');
      assert.equal(await next.isDisabled(), true);
      await page.locator('[data-exercise-id="first"]').click(); await selected(page, 'first');
      assert.equal(await editor.inputValue(), 'no Node // saved before pending request');
    } finally { waiting.resolve(); await context.close(); }
  });
  await check('failed-navigation-stays-disabled-until-retry-loads', async () => {
    let attempts = 0;
    const { context, page, previous, next, position } = await session({ detail: async (route, id) => {
      if (id !== 'middle' || attempts++ > 0) return false;
      await route.fulfill({ status: 503, json: { error: 'Exercise temporarily unavailable.' } });
      return true;
    } });
    try {
      await selected(page, 'first');
      await next.click();
      await page.waitForFunction(() => !document.querySelector('#startup-error').hidden);
      assert.equal(await previous.isDisabled(), true);
      assert.equal(await next.isDisabled(), true);
      assert.equal(await position.textContent(), 'Choose an exercise to continue');
      await page.locator('#startup-error button').click(); await selected(page, 'middle');
      assert.equal(await previous.isEnabled(), true);
      assert.equal(await next.isEnabled(), true);
    } finally { await context.close(); }
  });
  await check('empty-catalogue-leaves-navigation-disabled', async () => {
    const { context, page, previous, next, position } = await session({ catalogue: [] });
    try {
      await page.waitForFunction(() => !document.querySelector('#startup-error').hidden);
      assert.equal(await previous.isDisabled(), true);
      assert.equal(await next.isDisabled(), true);
      assert.equal(await position.textContent(), 'No matching exercises');
    } finally { await context.close(); }
  });
  await check('mobile-buttons-fit-and-support-keyboard-activation', async () => {
    const { context, page, previous, next, position } = await session({ viewport: { width: 360, height: 780 } });
    try {
      await selected(page, 'first');
      for (const control of [previous, position, next]) {
        const bounds = await control.boundingBox();
        assert(bounds && bounds.x >= 0 && bounds.x + bounds.width <= 360);
      }
      for (const control of [previous, next]) assert((await control.boundingBox()).height >= 44);
      await next.focus(); await page.keyboard.press('Enter'); await selected(page, 'middle');
      await previous.focus(); await page.keyboard.press('Space'); await selected(page, 'first');
      assert.equal(await position.textContent(), '1 of 3 exercises');
    } finally { await context.close(); }
  });
  await check('no-browser-errors-or-external-requests', async () => {
    assert.deepEqual(errors, []);
    assert.deepEqual(externalRequests, []);
  });
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}

import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repository = 'AnonymousAlloyLab/AlloyStudio';
const sha = 'a'.repeat(40);
let snapshot = { schemaVersion: 1, repository, version: '0.0.1-alpha', revision: { sha, dirty: false }, checks: [], closure: { status: 'NOT_AVAILABLE' } };
const server = createServer(async (req, res) => {
  const name = req.url.split('?')[0].replace(/^\/(?:alloy\/)?dashboard\//, '') || 'index.html';
  if (!['index.html', 'app.js', 'styles.css', 'data.json'].includes(name)) { res.writeHead(404).end(); return; }
  res.setHeader('Content-Type', { 'index.html': 'text/html', 'app.js': 'text/javascript', 'styles.css': 'text/css', 'data.json': 'application/json' }[name]);
  res.end(name === 'data.json' ? JSON.stringify(snapshot) : await readFile(path.join(root, 'web/dashboard', name)));
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
let browser;
const passed = [];
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  let remote = [];
  let response = { workflow_runs: [] };
  let releases = [];
  let failure = false;
  await context.route('https://api.github.com/**', route => {
    remote.push(route.request().url());
    assert.equal(route.request().headers().authorization, undefined);
    if (failure) return route.fulfill({ status: 403, json: { message: 'PRIVATE_SENTINEL' } });
    return route.fulfill({ json: route.request().url().includes('/releases?') ? releases : response });
  });
  const loaded = async (prefix = '') => {
    await page.goto(`${base}/${prefix}dashboard/`);
    await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('loaded'));
  };
  const refresh = async () => { await page.locator('#refresh').click(); await page.waitForFunction(() => !document.querySelector('#refresh').disabled); };

  await loaded();
  assert.equal(remote.length, 0);
  assert.equal(await page.locator('#checks .check').count(), 5);
  assert.match(await page.locator('#check-state').textContent(), /Incomplete/);
  passed.push('baseline-does-not-claim-success-or-fetch-external-data');

  snapshot.checks = ['build', 'runtime', 'python', 'browser', 'dashboard'].map(name => ({ name, status: 'PASS', count: 12, revision: sha, current: true }));
  snapshot.closure = { status: 'VERIFIED', id: 'portal-20260928T120000Z-aabbccdd', inputRootHash: 'c'.repeat(64), claimsPassed: 10, claimsTotal: 10, buildsPassed: 2, buildsRequired: 2 };
  snapshot.lean = { total: 8, open: 8 };
  await loaded();
  assert.equal(await page.locator('#check-state').textContent(), 'Recorded checks passed');
  assert.match(await page.locator('#closure-state').textContent(), /historical/);
  assert.match(await page.locator('#lean-state').textContent(), /8 open/);
  passed.push('clean-revision-checks-and-historical-closure-are-distinct');

  snapshot.revision.dirty = true;
  await loaded();
  assert.match(await page.locator('#check-state').textContent(), /Incomplete/);
  assert.equal(await page.locator('#checks .good').count(), 0);
  passed.push('uncommitted-build-never-inherits-green-results');

  await refresh();
  assert.match(await page.locator('#remote-state').textContent(), /No workflow runs/);
  assert.equal(await page.locator('#release').textContent(), 'No published release');
  passed.push('empty-github-history-is-reported-honestly');

  response = { workflow_runs: [
    { name: '<img src=x onerror=alert(1)>', status: 'completed', conclusion: 'failure', head_sha: sha, created_at: '2026-09-28T12:00:00Z', html_url: 'javascript:alert(1)' },
    { name: 'CI', status: 'in_progress', conclusion: null, head_sha: sha, created_at: '2026-09-28T12:00:00Z', html_url: `https://github.com/${repository}/actions/runs/7` },
  ] };
  releases = [{ name: 'alpha v0.0.1', tag_name: 'v0.0.1-alpha', prerelease: true, html_url: `https://github.com/${repository}/releases/tag/v0.0.1-alpha` }];
  await refresh();
  assert.equal(await page.locator('#runs tr').count(), 2);
  assert.equal(await page.locator('#runs img').count(), 0);
  assert.equal(await page.locator('#runs a[href^="javascript:"]').count(), 0);
  assert.match(await page.locator('#runs').textContent(), /in progress/);
  assert.match(await page.locator('#runs').textContent(), /failure/);
  assert.match(await page.locator('#release-detail').textContent(), /Prerelease/);
  passed.push('remote-statuses-safe-links-and-prerelease-rendering');

  failure = true;
  await refresh();
  assert.match(await page.locator('#remote-state').textContent(), /unavailable/);
  assert.equal(await page.locator('#runs tr').count(), 0);
  assert.equal(await page.locator('#release').textContent(), 'Unavailable');
  assert.doesNotMatch(await page.locator('body').textContent(), /PRIVATE_SENTINEL/);
  passed.push('github-errors-clear-old-success-and-hide-response-content');

  await loaded('alloy/');
  assert.equal(await page.locator('a.brand').getAttribute('href'), '../');
  assert.equal(await page.locator('#checks .check').count(), 5);
  assert.deepEqual(errors, []);
  passed.push('iis-application-prefix-keeps-relative-assets-and-no-browser-errors');
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
}

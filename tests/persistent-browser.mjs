// Production HTTP/UI/JVM pipeline. The only server instrumentation records safe
// numeric counters during shutdown; no routes, evaluation methods or tokens are mocked.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'build/browser-traffic/persistent');
const scratch = path.join(output, 'tmp');
await mkdir(scratch, { recursive: true });
const statsPath = path.join(output, `stats-${Date.now()}-${process.pid}.json`);
const program = `import json,sys
from pathlib import Path
import server
destination=Path(sys.argv[1])
sys.argv=['server.py','--port','0']
close=server.Portal.server_close
def captured_close(self):
    before=self.engine_pool.stats()
    work=self.scheduler.stats()
    mode=self.engine_mode
    close(self)
    destination.write_text(json.dumps({'mode':mode,'before':before,'after':self.engine_pool.stats(),'scheduler':work}))
server.Portal.server_close=captured_close
server.main()
`;
const environment = { ...process.env, OPENAI_DISABLED: '1', TMPDIR: scratch, TMP: scratch, TEMP: scratch };
delete environment.ALLOY_ENGINE_MODE;
const backend = spawn('python3', ['-E', '-s', '-c', program, statsPath], {
  cwd: root, env: environment, stdio: ['ignore', 'pipe', 'pipe'],
});
let browser, context;
const passed = [], errors = [];
const ended = new Promise(resolve => backend.once('exit', (code, signal) => resolve({ code, signal })));
async function check(name, operation) { await operation(); passed.push(name); }
async function stopBackend() {
  if (backend.exitCode === null) backend.kill('SIGTERM');
  const timer = setTimeout(() => backend.kill('SIGKILL'), 10000);
  try { return await ended; } finally { clearTimeout(timer); }
}
try {
  const url = await new Promise((resolve, reject) => {
    let text = '';
    const timer = setTimeout(() => reject(new Error('Persistent browser backend startup deadline exceeded')), 20000);
    backend.stdout.on('data', chunk => {
      text += chunk;
      const match = text.match(/Alloy practice: (http:\/\/127\.0\.0\.1:\d+)/);
      if (match) { clearTimeout(timer); resolve(match[1]); }
    });
    backend.once('exit', () => { clearTimeout(timer); reject(new Error('Persistent browser backend stopped before startup')); });
  });
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined,
    env: environment });
  context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(() => localStorage.setItem('alloy-studio:v1:live', 'false'));
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => assert.equal(new URL(request.url()).origin, url, 'No provider or external request is permitted'));
  await page.goto(`${url}/?exercise=graphs-inv1`);
  await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
  await check('persistent-real-browser-revisit-refreshes-behavior-without-new-jvm-launches', async () => {
    const evidence = [];
    for (const body of ['adj = ~adj', 'no (iden & adj)', 'adj = ~adj']) {
      await page.locator('#predicate-editor').fill(body);
      const structuralReply = page.waitForResponse(response => response.url().endsWith('/api/feedback'));
      const behavioralReply = page.waitForResponse(response => response.url().endsWith('/api/behavior'));
      const guidanceReply = page.waitForResponse(response => response.url().endsWith('/api/explain'));
      await page.locator('#check-button').click();
      const responses = await Promise.all([structuralReply, behavioralReply, guidanceReply]);
      const [structural, behavioral, guidance] = await Promise.all(responses.map(response => response.json()));
      assert(responses.every(response => response.status() === 200));
      assert.equal(structural.status, 'ok'); assert.equal(structural.requestedMetric, 'canonical');
      assert.equal(behavioral.status, 'ok'); assert.equal(behavioral.categories.length, 4);
      assert.equal(guidance.status, 'disabled');
      const requests = responses.map(response => response.request().postDataJSON());
      assert(requests.every(request => request.body === body && request.channel === requests[0].channel
        && request.revision === requests[0].revision && request.metric === 'canonical'));
      assert.equal(requests[2].evidenceToken, structural.evidenceToken);
      assert.equal(requests[2].behaviorToken, behavioral.behaviorToken);
      assert.equal(Object.hasOwn(requests[1], 'evidenceToken'), false);
      await page.locator('.explanation-unavailable').waitFor();
      assert.equal(await page.locator('#feedback-state').textContent(), 'Checked');
      assert.equal(await page.locator('#behavior-state').textContent(), 'Checked');
      assert.equal(await page.locator('.behavior-category-choice').count(), 4);
      evidence.push({ distance: structural.distance, token: behavioral.behaviorToken, revision: structural.revision });
    }
    assert.equal(evidence[0].distance, evidence[2].distance);
    assert.equal(evidence[0].token, evidence[2].token);
    assert(evidence[0].revision < evidence[1].revision && evidence[1].revision < evidence[2].revision);
    assert.deepEqual(errors, []);
    await context.close(); context = null;
    const exit = await stopBackend(); assert.equal(exit.code, 0);
    const stats = JSON.parse(await readFile(statsPath, 'utf8'));
    assert.equal(stats.mode, 'persistent');
    // Two feedback computations (the revisited body is cached), but all three
    // behavioral observations must refresh the retained LFU instance pools.
    assert.equal(stats.before.launches, 2); assert.equal(stats.before.completed, 5);
    assert.equal(stats.before.failures, 0); assert.equal(stats.before.recycled, 0);
    assert.equal(stats.scheduler.computations, 5); assert.equal(stats.scheduler.cacheHits, 1);
    assert.equal(stats.scheduler.jobs, 0); assert.equal(stats.scheduler.subscribers, 0);
    assert.equal(stats.after.processBudget.reserved, 0); assert.equal(stats.after.unreaped, 0);
    assert.equal(stats.after.workers.feedback, 0); assert.equal(stats.after.workers.behavior, 0);
  });
  const report = { status: 'PASS', checks: passed.length, passed };
  await writeFile(path.join(output, 'report.json'), JSON.stringify(report) + '\n');
  console.log(JSON.stringify(report));
} finally {
  await context?.close(); await browser?.close(); await stopBackend();
}

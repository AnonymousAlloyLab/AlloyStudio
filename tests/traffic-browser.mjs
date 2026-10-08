import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { chromium } from 'playwright';

// Exercise production browser code against a deterministic HTTP origin, including
// real browser HTTP revalidation. No JVM, private model, or paid API is involved.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const scratch = path.join(root, 'build/browser-traffic/tmp');
await mkdir(scratch, { recursive: true });
process.env.TMPDIR = scratch;
process.env.TMP = scratch;
process.env.TEMP = scratch;
const assets = new Map(await Promise.all([
  ['/', 'index.html', 'text/html'], ['/app.js', 'app.js', 'text/javascript'],
  ['/styles.css', 'styles.css', 'text/css'], ['/instance-graph.js', 'instance-graph.js', 'text/javascript'],
  ['/alloy-language.js', 'alloy-language.js', 'text/javascript'],
].map(async ([url, file, type]) => [url, { type, bytes: await readFile(path.join(root, 'web', file)) }])));
const record = { id: 'traffic', title: 'Traffic fixture', group: 'Graphs', predicate: 'inv1',
  starter: 'some Node', predicateHeader: 'pred inv1 ', environmentBefore: 'sig Node {}\n',
  environmentAfter: '\n', description: 'Explore nodes.', source: { path: 'graph.als', sha256: 'fixture-v1' } };
const token = 'a'.repeat(64);
const feedback = payload => ({ exerciseId: payload.exerciseId, revision: payload.revision, status: 'ok',
  requestedMetric: payload.metric, metric: payload.metric === 'ast'
    ? 'acgn-raw-ast-zhang-shasha-distance' : 'acgn-fast-rewrite-canonical-distance',
  distance: 0, breakdown: { temporal: 0, quantifier: 0, matrix: 0, ast: 0 }, operations: [],
  canonicalForm: ['some Node'], trace: { cost: 0, matchesDistance: true }, evidenceToken: token });
const responseJSON = (response, data, status = 200, headers = {}) => {
  if (response.headersSent) return;
  response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...headers });
  response.end(JSON.stringify(data));
};
const calls = [];
let behavior;
let generation = 1;
let channelSerial = 0;
let revalidations = 0;
const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://localhost');
  const asset = assets.get(url.pathname);
  if (asset) { response.writeHead(200, { 'Content-Type': asset.type }); response.end(asset.bytes); return; }
  if (url.pathname === '/api/exercises' || url.pathname === '/api/exercises/traffic') {
    const etag = `"${url.pathname}-${generation}"`;
    const headers = { ETag: etag, 'Cache-Control': 'private, no-cache', 'X-Alloy-Generation': String(generation) };
    if (request.headers['if-none-match'] === etag) {
      revalidations += 1; response.writeHead(304, headers); response.end(); return;
    }
    responseJSON(response, url.pathname === '/api/exercises' ? { exercises: [record] }
      : { ...record, starter: generation === 1 ? record.starter : 'no Node', source: { ...record.source, sha256: `fixture-v${generation}` } }, 200, headers);
    return;
  }
  let raw = '';
  for await (const chunk of request) raw += chunk;
  const payload = raw ? JSON.parse(raw) : {};
  calls.push({ path: url.pathname, payload, raw });
  if (behavior && await behavior(url.pathname, payload, response)) return;
  if (url.pathname === '/api/channel') return responseJSON(response, { status: 'ok', channel: String(++channelSerial).padStart(43, 'c') });
  if (url.pathname === '/api/cancel') return responseJSON(response, { status: 'ok' });
  if (url.pathname === '/api/feedback') return responseJSON(response, feedback(payload));
  if (url.pathname === '/api/behavior') return responseJSON(response, { exerciseId: payload.exerciseId, revision: payload.revision, status: 'unavailable' });
  if (url.pathname === '/api/explain') return responseJSON(response, { exerciseId: payload.exerciseId, revision: payload.revision,
    requestedMetric: payload.metric, status: 'ok', operations: [], instances: [], summary: 'Inspect the displayed examples before your next edit.' });
  response.writeHead(404); response.end();
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const passed = [], errors = [];
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const count = route => calls.filter(call => call.path === `/api/${route}`);
async function until(predicate) {
  const deadline = Date.now() + 5000;
  while (!predicate()) { if (Date.now() >= deadline) throw new Error('Traffic fixture deadline exceeded'); await delay(10); }
}
async function check(name, operation) {
  calls.length = 0; behavior = null;
  try { await operation(); } catch (error) { error.message = `${name}: ${error.message}`; throw error; }
  passed.push(name);
}
let browser;
async function session(live = false) {
  const context = await browser.newContext();
  await context.addInitScript(enabled => localStorage.setItem('alloy-studio:v1:live', String(enabled)), live);
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => assert.equal(new URL(request.url()).origin, origin, 'No external requests permitted'));
  await page.goto(origin);
  await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
  return { context, page, editor: page.locator('#predicate-editor'), check: page.locator('#check-button') };
}
async function ready(page) { await page.locator('.explanation-text').waitFor(); }
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  await check('live-off-visits-do-not-create-channels-or-analysis-and-canonical-remains-default', async () => {
    const s = await session();
    try { await delay(800); assert.equal(calls.length, 0); assert.equal(await s.page.locator('#distance-metric').inputValue(), 'canonical'); }
    finally { await s.context.close(); }
  });
  await check('identical-manual-checks-coalesce-through-downstream-and-forward-evidence', async () => {
    let pendingFeedback, pendingBehavior;
    behavior = async (route, payload, response) => {
      if (route === '/api/feedback') { pendingFeedback = () => responseJSON(response, feedback(payload)); return true; }
      if (route === '/api/behavior') { pendingBehavior = () => responseJSON(response, { ...payload, status: 'unavailable' }); return true; }
      return false;
    };
    const s = await session();
    try {
      await s.check.click(); await until(() => pendingFeedback); await s.check.click(); await s.check.click();
      assert.equal(count('feedback').length, 1); pendingFeedback(); await until(() => pendingBehavior);
      await s.check.click(); assert.equal(count('feedback').length, 1); pendingBehavior(); await ready(s.page);
      assert.equal(count('channel').length, 1); assert.equal(count('behavior').length, 1); assert.equal(count('explain').length, 1);
      const structural = count('feedback')[0].payload, explanation = count('explain')[0].payload;
      assert.equal(explanation.channel, structural.channel); assert.equal(explanation.revision, structural.revision);
      assert.equal(explanation.body, structural.body); assert.equal(explanation.metric, structural.metric);
      assert.equal(explanation.evidenceToken, token);
      assert.equal(Object.hasOwn(count('behavior')[0].payload, 'evidenceToken'), false);
    } finally { pendingFeedback?.(); pendingBehavior?.(); await s.context.close(); }
  });
  await check('editing-cancels-own-channel-and-stale-feedback-cannot-start-downstream', async () => {
    let release;
    behavior = async (route, payload, response) => {
      if (route !== '/api/feedback') return false;
      release = () => responseJSON(response, feedback(payload)); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await until(() => release); await s.editor.fill('no Node');
      await until(() => count('cancel').length === 1); release(); await delay(150);
      const request = count('feedback')[0].payload, cancellation = count('cancel')[0].payload;
      assert.equal(cancellation.channel, request.channel); assert(cancellation.revision > request.revision);
      assert.equal(count('behavior').length, 0); assert.equal(count('explain').length, 0);
      assert.equal(await s.page.locator('#feedback-state').textContent(), 'Not checked');
    } finally { await s.context.close(); }
  });
  await check('cancellation-while-behavior-waits-prevents-obsolete-explanation', async () => {
    let release;
    behavior = async (route, payload, response) => {
      if (route !== '/api/behavior') return false;
      release = () => responseJSON(response, { ...payload, status: 'unavailable' }); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await until(() => release); await s.editor.fill('one Node'); release(); await delay(150);
      assert.equal(count('explain').length, 0); assert.equal(count('cancel').length, 1);
    } finally { await s.context.close(); }
  });
  await check('each-tab-uses-a-distinct-server-channel', async () => {
    const a = await session(), b = await session();
    try {
      await a.check.click(); await ready(a.page); await b.check.click(); await ready(b.page);
      assert.equal(count('channel').length, 2);
      assert.notEqual(count('feedback')[0].payload.channel, count('feedback')[1].payload.channel);
    } finally { await a.context.close(); await b.context.close(); }
  });
  await check('capacity-retry-preserves-exact-payload-and-retries-only-once', async () => {
    let attempts = 0;
    behavior = async (route, payload, response) => {
      if (route !== '/api/feedback' || attempts++ > 0) return false;
      responseJSON(response, { status: 'busy', code: 'capacity', retryable: true, dispatched: false }, 503, { 'Retry-After': '0' }); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await ready(s.page);
      assert.equal(count('feedback').length, 2); assert.equal(count('feedback')[0].raw, count('feedback')[1].raw);
      assert.equal(count('channel').length, 1);
    } finally { await s.context.close(); }
  });
  await check('two-capacity-rejections-terminate-without-a-third-attempt', async () => {
    behavior = async (route, payload, response) => {
      if (route !== '/api/feedback') return false;
      responseJSON(response, { status: 'busy', code: 'capacity', retryable: true, dispatched: false }, 429, { 'Retry-After': '0' }); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await s.page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Server busy');
      await delay(200); assert.equal(count('feedback').length, 2); assert.equal(count('behavior').length, 0);
    } finally { await s.context.close(); }
  });
  await check('new-edit-aborts-pending-capacity-retry', async () => {
    behavior = async (route, payload, response) => {
      if (route !== '/api/feedback') return false;
      responseJSON(response, { status: 'busy', code: 'capacity', retryable: true, dispatched: false }, 503, { 'Retry-After': '1' }); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await until(() => count('feedback').length === 1); await s.editor.fill('lone Node');
      await delay(1250); assert.equal(count('feedback').length, 1);
    } finally { await s.context.close(); }
  });
  await check('analysis-timeout-and-unknown-network-outcome-are-never-retried', async () => {
    for (const mode of ['timeout', 'network']) {
      calls.length = 0;
      behavior = async (route, payload, response) => {
        if (route !== '/api/feedback') return false;
        if (mode === 'network') {
          response.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': '100' });
          response.write('{'); setTimeout(() => response.destroy(), 20);
        }
        else responseJSON(response, { ...payload, requestedMetric: payload.metric, status: 'timeout' }, 503);
        return true;
      };
      const s = await session();
      try {
        await s.check.click(); await s.page.waitForFunction(() => ['timeout', 'error'].includes(document.querySelector('#feedback-state').dataset.state));
        await delay(200); assert.equal(count('feedback').length, 1); assert.equal(count('behavior').length, 0);
      } finally { await s.context.close(); }
    }
  });
  await check('channel-expiry-is-not-retried-and-next-manual-check-creates-new-channel', async () => {
    let first = true;
    behavior = async (route, payload, response) => {
      if (route !== '/api/feedback' || !first) return false;
      first = false; responseJSON(response, { status: 'expired', code: 'channel_expired' }, 410); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await s.page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Session expired');
      assert.equal(count('channel').length, 1); assert.equal(count('feedback').length, 1);
      await s.check.click(); await ready(s.page); assert.equal(count('channel').length, 2);
      assert.notEqual(count('feedback')[0].payload.channel, count('feedback')[1].payload.channel);
    } finally { await s.context.close(); }
  });
  await check('superseded-behavior-does-not-start-explanations', async () => {
    behavior = async (route, payload, response) => {
      if (route !== '/api/behavior') return false;
      responseJSON(response, { ...payload, status: 'superseded' }, 409); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await s.page.waitForFunction(() => document.querySelector('#behavior-state').textContent === 'Unavailable');
      await delay(100); assert.equal(count('explain').length, 0);
      await s.page.locator('.explanation-unavailable').waitFor();
      assert.equal(await s.page.locator('.explanation-retry').count(), 0);
    } finally { await s.context.close(); }
  });
  await check('missing-structural-evidence-never-falls-back-to-legacy-explanation-analysis', async () => {
    behavior = async (route, payload, response) => {
      if (route !== '/api/feedback') return false;
      const result = feedback(payload); delete result.evidenceToken;
      responseJSON(response, result); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await s.page.locator('.explanation-unavailable').waitFor();
      assert.equal(count('behavior').length, 1); assert.equal(count('explain').length, 0);
      assert.equal(await s.page.locator('.explanation-retry').count(), 0);
      assert.equal(await s.page.locator('#feedback-state').textContent(), 'Checked');
    } finally { await s.context.close(); }
  });
  await check('draft-edited-during-channel-creation-is-never-submitted', async () => {
    let release;
    behavior = async (route, payload, response) => {
      if (route !== '/api/channel') return false;
      release = () => responseJSON(response, { status: 'ok', channel: 'd'.repeat(43) }); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await until(() => release); await s.editor.fill('some Node // changed before channel');
      release(); await delay(100); assert.equal(count('feedback').length, 0);
      await s.check.click(); await ready(s.page);
      assert.equal(count('channel').length, 1);
      assert.equal(count('feedback')[0].payload.body, 'some Node // changed before channel');
    } finally { await s.context.close(); }
  });
  await check('metric-switch-keeps-channel-but-creates-current-delivery-and-separate-structural-request', async () => {
    const s = await session();
    try {
      await s.check.click(); await ready(s.page);
      await s.page.locator('#distance-metric').selectOption('ast');
      await s.page.waitForFunction(() => document.querySelector('.distance-result')?.dataset.metric === 'ast');
      await ready(s.page);
      assert.equal(count('feedback').length, 2); assert.equal(count('channel').length, 1);
      assert.equal(count('feedback')[0].payload.metric, 'canonical'); assert.equal(count('feedback')[1].payload.metric, 'ast');
      assert(count('feedback')[1].payload.revision > count('feedback')[0].payload.revision);
      assert.equal(count('behavior')[0].payload.metric, 'canonical'); assert.equal(count('behavior')[1].payload.metric, 'ast');
      assert.equal(count('behavior')[0].payload.body, count('behavior')[1].payload.body);
      assert.equal(count('explain')[1].payload.metric, 'ast');
    } finally { await s.context.close(); }
  });
  await check('turning-live-off-after-dispatch-keeps-the-current-check', async () => {
    let release;
    behavior = async (route, payload, response) => {
      if (route !== '/api/feedback') return false;
      release = () => responseJSON(response, feedback(payload)); return true;
    };
    const s = await session(true);
    try {
      await until(() => release); await s.page.locator('#live-feedback').uncheck(); release(); await ready(s.page);
      assert.equal(count('feedback').length, 1); assert.equal(count('behavior').length, 1); assert.equal(count('cancel').length, 0);
    } finally { await s.context.close(); }
  });
  await check('turning-live-off-cancels-debounce-but-manual-check-still-works', async () => {
    const s = await session();
    try {
      await s.page.locator('#live-feedback').check(); await ready(s.page);
      const before = count('feedback').length;
      await s.editor.fill('some Node // edited'); await s.page.locator('#live-feedback').uncheck();
      await delay(800); assert.equal(count('feedback').length, before);
      await s.check.click(); await ready(s.page); assert.equal(count('feedback').length, before + 1);
    } finally { await s.context.close(); }
  });
  await check('catalogue-and-detail-revalidate-and-updated-snapshot-discards-old-draft', async () => {
    const s = await session();
    try {
      await s.editor.fill('one Node // saved');
      const before = revalidations; await s.page.reload();
      await s.page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
      assert(revalidations >= before + 2); assert.equal(await s.editor.inputValue(), 'one Node // saved');
      generation = 2; await s.page.reload();
      await s.page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
      assert.equal(await s.editor.inputValue(), 'no Node');
    } finally { generation = 1; await s.context.close(); }
  });
  await check('learner-request-aborts-on-the-shared-150-second-deadline-without-retry', async () => {
    const source = assets.get('/app.js').bytes.toString('utf8').split('async function learnerJSON')[1].split('// BEGIN CHANNEL FALLBACK POLICY')[0];
    let expire, timerDelay, cleared = false, requests = 0;
    const evaluate = vm.runInNewContext(`async function learnerJSON${source}\nlearnerJSON`, {
      AbortController, DOMException, performance: { now: () => 0 }, state: {},
      setTimeout: (callback, delay) => { expire = callback; timerDelay = delay; return 42; },
      clearTimeout: timer => { assert.equal(timer, 42); cleared = true; },
      fetchJSONResponse: async (url, options) => {
        requests += 1;
        return new Promise((resolve, reject) => options.signal.addEventListener('abort',
          () => reject(new DOMException('Aborted.', 'AbortError')), { once: true }));
      },
      retryDelay: () => { throw new Error('A timed-out dispatched request must not retry.'); },
    });
    const controller = new AbortController();
    const pending = evaluate('api/feedback', { channel: 'c'.repeat(43) }, controller.signal, () => true);
    assert.equal(timerDelay, 150000);
    assert.equal(requests, 1);
    expire();
    await assert.rejects(pending, /analysis request timed out/);
    assert.equal(requests, 1);
    assert(cleared);
  });
  await check('retry-policy-rejects-ambiguous-admission-and-preserves-absolute-deadline', async () => {
    const source = assets.get('/app.js').bytes.toString('utf8').split('// BEGIN TRAFFIC RETRY POLICY')[1].split('// END TRAFFIC RETRY POLICY')[0];
    const evaluate = vm.runInNewContext(`${source}\nretryDelay`, { Math, Number });
    const approved = { status: 'busy', code: 'capacity', retryable: true, dispatched: false };
    const response = (status = 503, value = '1') => ({ status, headers: { get: () => value } });
    assert.equal(evaluate(response(), approved, 0, 100, 2000, () => 0), 1000);
    for (const field of Object.keys(approved)) {
      const missing = { ...approved }; delete missing[field];
      assert.equal(evaluate(response(), missing, 0, 0, 5000), null);
    }
    for (const status of [200, 400, 401, 403, 408, 500, 502, 504]) assert.equal(evaluate(response(status), approved, 0, 0, 5000), null);
    for (const value of [null, '-1', 'Infinity', '6', 'later', 'Wed, 21 Oct 2026 07:28:00 GMT']) assert.equal(evaluate(response(503, value), approved, 0, 0, 5000), null);
    assert.equal(evaluate(response(), approved, 1, 0, 5000), null);
    assert.equal(evaluate(response(), approved, 0, 1000, 2000, () => 0), null);
    assert.equal(evaluate(response(), { ...approved, dispatched: true }, 0, 0, 5000), null);
  });
  const busy = { status: 'busy', code: 'capacity', retryable: true, dispatched: false };
  const omitsChannel = call => !Object.hasOwn(call.payload, 'channel') && !call.raw.includes('"channel"');
  await check('channel-capacity-refusal-sends-one-channel-less-request-and-never-cancels', async () => {
    behavior = async (route, payload, response) => {
      if (route !== '/api/channel') return false;
      responseJSON(response, busy, 429, { 'Retry-After': '1' }); return true;
    };
    const s = await session();
    try {
      await s.check.click(); await ready(s.page);
      assert.equal(count('channel').length, 1);
      assert.equal(count('feedback').length, 1); assert(omitsChannel(count('feedback')[0]));
      assert(count('behavior').every(omitsChannel)); assert(count('explain').every(omitsChannel));
      await s.editor.fill('some Node // edited after fallback'); await delay(100);
      assert.equal(count('cancel').length, 0);
    } finally { await s.context.close(); }
  });
  await check('channel-less-request-is-sent-at-most-once-even-when-refused', async () => {
    behavior = async (route, payload, response) => {
      if (route === '/api/channel') { responseJSON(response, busy, 429, { 'Retry-After': '1' }); return true; }
      if (route === '/api/feedback') { responseJSON(response, { ...busy, exerciseId: payload.exerciseId, revision: payload.revision }, 503, { 'Retry-After': '1' }); return true; }
      return false;
    };
    const s = await session();
    try {
      await s.check.click();
      await s.page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Server busy');
      await delay(1500);
      assert.equal(count('channel').length, 1); assert.equal(count('feedback').length, 1);
      assert.equal(count('behavior').length, 0);
    } finally { await s.context.close(); }
  });
  await check('channel-authentication-malformed-and-other-failures-never-fall-back', async () => {
    for (const [status, body] of [[403, { status: 'forbidden' }], [401, { status: 'unauthorized' }],
      [200, { status: 'ok', channel: 'short' }], [200, { status: 'ok' }],
      [200, { status: 'ok', channel: 'c'.repeat(42) }], [200, { status: 'ok', channel: 'c'.repeat(44) }],
      [400, { status: 'error', code: 'identity_rejected' }], [500, { status: 'error' }]]) {
      calls.length = 0;
      behavior = async (route, payload, response) => {
        if (route !== '/api/channel') return false;
        responseJSON(response, body, status); return true;
      };
      const s = await session();
      try {
        await s.check.click();
        await s.page.waitForFunction(() => ['Unavailable'].includes(document.querySelector('#feedback-state').textContent));
        await delay(100);
        assert.equal(count('channel').length, 1, `one channel attempt for ${status}`);
        assert.equal(count('feedback').length, 0, `no fallback for ${status} ${JSON.stringify(body)}`);
      } finally { await s.context.close(); }
    }
  });
  await check('unreachable-channel-endpoint-falls-back-once', async () => {
    const s = await session();
    let attempts = 0;
    try {
      // Refuse at the browser boundary: one failed fetch, no transport-level resend.
      await s.page.route('**/api/channel', route => { attempts += 1; return route.abort('connectionrefused'); });
      await s.check.click(); await ready(s.page);
      assert.equal(attempts, 1); assert.equal(count('channel').length, 0); assert.equal(count('feedback').length, 1);
      assert(omitsChannel(count('feedback')[0]));
    } finally { await s.context.close(); }
  });
  await check('redirected-capacity-response-never-permits-channel-less-work', async () => {
    behavior = async (route, payload, response) => {
      if (route === '/api/channel') {
        response.writeHead(302, { Location: '/channel-access' }); response.end(); return true;
      }
      if (route === '/channel-access') { responseJSON(response, busy, 503); return true; }
      return false;
    };
    const s = await session();
    try {
      await s.check.click();
      await s.page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Unavailable');
      assert.equal(count('channel').length, 1);
      assert.equal(calls.filter(call => call.path === '/channel-access').length, 0);
      assert.equal(count('feedback').length, 0);
      assert.equal(count('behavior').length, 0);
    } finally { await s.context.close(); }
  });
  await check('work-limit-feedback-never-starts-behavior-or-explanation', async () => {
    behavior = async (route, payload, response) => {
      if (route !== '/api/feedback') return false;
      responseJSON(response, { exerciseId: payload.exerciseId, revision: payload.revision,
        requestedMetric: payload.metric, status: 'unsupported',
        diagnostics: [{ code: 'WORK_LIMIT', message: 'This predicate needs more analysis work than live feedback allows.' }] });
      return true;
    };
    const s = await session();
    try {
      for (const metric of ['canonical', 'ast']) {
        await s.page.locator('#distance-metric').selectOption(metric);
        if (metric === 'canonical') await s.check.click();  // Changing the metric schedules its own check.
        await s.page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Unsupported');
        await delay(100);
        assert.equal(count('behavior').length, 0);
        assert.equal(count('explain').length, 0);
      }
      assert.equal(count('feedback').length, 2);
      assert.equal(await s.page.locator('.distance-result').count(), 0);
    } finally { await s.context.close(); }
  });
  await check('stale-channel-less-response-cannot-change-the-visible-result', async () => {
    let release;
    behavior = async (route, payload, response) => {
      if (route === '/api/channel') { responseJSON(response, busy, 429); return true; }
      if (route === '/api/feedback' && !release) {
        release = () => responseJSON(response, { ...feedback(payload), distance: 7 }); return true;
      }
      return false;
    };
    const s = await session();
    try {
      await s.check.click(); await until(() => release);
      await s.editor.fill('no Node // newer draft'); release(); await delay(150);
      assert.notEqual(await s.page.locator('#feedback-state').textContent(), 'Checked');
      assert.equal(await s.page.locator('.distance-result').count(), 0);
      await s.check.click(); await ready(s.page);
      assert.equal(count('feedback').at(-1).payload.body, 'no Node // newer draft');
      assert(count('feedback').every(omitsChannel));
    } finally { await s.context.close(); }
  });
  await check('channel-fallback-policy-matches-the-modeled-request-plan', async () => {
    const source = assets.get('/app.js').bytes.toString('utf8').split('// BEGIN CHANNEL FALLBACK POLICY')[1].split('// END CHANNEL FALLBACK POLICY')[0];
    const [outcome, permitted] = vm.runInNewContext(`${source}\n[channelOutcome, fallbackPermitted]`, {});
    const valid = { status: 'ok', channel: 'c'.repeat(43) };
    assert.equal(outcome(200, false, valid), 'issued');
    assert.equal(outcome(200, true, valid), 'authenticationFailure');
    assert.equal(outcome(429, false, busy), 'explicitlyUnavailable');
    assert.equal(outcome(503, false, busy), 'explicitlyUnavailable');
    for (const status of [429, 503]) assert.equal(outcome(status, true, busy), 'authenticationFailure');
    assert.equal(outcome(503, false, { ...busy, code: 'other' }), 'otherFailure');
    assert.equal(outcome(503, false, null), 'otherFailure');
    for (const status of [401, 403]) assert.equal(outcome(status, false, busy), 'authenticationFailure');
    for (const data of [null, { status: 'ok' }, { status: 'ok', channel: 'x' }]) assert.equal(outcome(200, false, data), 'malformed');
    for (const status of [400, 404, 500, 502]) assert.equal(outcome(status, false, { status: 'error' }), 'otherFailure');
    for (const value of ['explicitlyUnavailable', 'availabilityFailure']) assert.equal(permitted(value), true);
    for (const value of ['issued', 'aborted', 'authenticationFailure', 'malformed', 'otherFailure']) assert.equal(permitted(value), false);
  });
  assert.deepEqual(errors, []);
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
}

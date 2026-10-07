import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Real production HTML/JS/CSS with deterministic public response fixtures.
// Every request is intercepted; no JVM, external service or provider is used.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const assets = new Map(await Promise.all([
  ['/', 'index.html', 'text/html'], ['/app.js', 'app.js', 'text/javascript'],
  ['/styles.css', 'styles.css', 'text/css'], ['/instance-graph.js', 'instance-graph.js', 'text/javascript'],
].map(async ([url, file, type]) => [url, { type, bytes: await readFile(path.join(root, 'web', file)) }])));
const records = () => ['first', 'second'].map((id, index) => ({ id, title: `Question ${index + 1}`,
  group: 'Graphs', predicate: `inv${index + 1}`, description: 'There must be at least one node.',
  starter: 'some Node', predicateHeader: `pred inv${index + 1} `,
  environmentBefore: 'sig Node {}\n', environmentAfter: '\n',
  source: { path: 'fixture.als', sha256: String(index + 1).repeat(64) },
  contentVersion: String(index + 3).repeat(64) }));
const instance = { traceLength: 1, loopState: -1, states: [{ index: 0,
  signatures: [{ label: 'Node', atoms: ['Node$0'] }], relations: [] }] };
const behavior = payload => ({ exerciseId: payload.exerciseId, revision: payload.revision,
  status: 'ok', metric: 'acgn-reward', behaviorToken: 'b'.repeat(64), score: 1,
  scoreStatus: 'ok', scoreReason: 'OK',
  scope: { overall: 3, bitwidth: 3, maxSequence: 3, poolSize: 4, minTrace: 1, maxTrace: 1, moduleFacts: true },
  sampling: { positiveTested: 4, positiveAccepted: 4, negativeTested: 4, negativeRejected: 4, semanticCounterexamples: 0 },
  categories: [['both', true, true], ['undercoverage', true, false], ['overcoverage', false, true], ['neither', false, false]]
    .map(([id, oracle, student]) => ({ id, oracle, student, status: ['both', 'neither'].includes(id) ? 'sat' : 'unsat',
      enumerationComplete: true, instances: ['both', 'neither'].includes(id) ? [instance] : [] })) });
const structural = (payload, distance = 0) => ({ exerciseId: payload.exerciseId, revision: payload.revision,
  status: 'ok', requestedMetric: payload.metric,
  metric: payload.metric === 'ast' ? 'acgn-raw-ast-zhang-shasha-distance' : 'acgn-fast-rewrite-canonical-distance',
  evidenceToken: 'a'.repeat(64), distance, canonicalForm: ['some Node'],
  breakdown: payload.metric === 'ast' ? { ast: distance } : { temporal: 0, quantifier: 0, matrix: distance },
  comparison: { strategy: 'nearest-known-correct', poolSize: 1, evaluatedCandidates: 1, complete: true },
  operations: [], trace: { cost: distance, matchesDistance: true, hasAggregates: false, certifiedOptimalScript: false } });
const passed = [], errors = [], externalRequests = [];
let browser;
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};
async function check(name, operation) { await operation(); passed.push(name); }
async function session({ origin = 'https://alloy.example', basePath = '/', catalogue = records(),
  feedback = payload => structural(payload), analyze = payload => behavior(payload),
  viewport = { width: 1440, height: 1000 }, storage = null, publicDeployment = false } = {}) {
  const context = await browser.newContext({ viewport });
  await context.addInitScript(saved => {
    localStorage.setItem('alloy-studio:v1:live', 'false');
    if (saved !== null && localStorage.getItem('alloy-studio:v1:solved') === null) {
      localStorage.setItem('alloy-studio:v1:solved', JSON.stringify(saved));
    }
  }, storage);
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) { externalRequests.push(url.href); return route.abort(); }
    const resource = '/' + url.pathname.slice(basePath.length);
    const send = json => route.fulfill({ json });
    if (resource === '/api/exercises') return send({ exercises: catalogue });
    if (resource.startsWith('/api/exercises/')) return send(catalogue.find(record => record.id === resource.slice('/api/exercises/'.length)));
    if (resource === '/api/channel') return send({ status: 'ok', channel: 'C'.repeat(43) });
    if (resource === '/api/cancel') return send({ status: 'ok' });
    if (resource === '/api/feedback') {
      const payload = route.request().postDataJSON();
      const version = catalogue.find(record => record.id === payload.exerciseId)?.contentVersion;
      const reply = await feedback(payload);
      return send({ contentVersion: version, ...reply });
    }
    if (resource === '/api/behavior') {
      const payload = route.request().postDataJSON();
      const version = catalogue.find(record => record.id === payload.exerciseId)?.contentVersion;
      const reply = await analyze(payload);
      try { return await send({ contentVersion: version, ...reply }); }
      catch (error) { if (!/closed|canceled|cancelled|aborted/i.test(error.message)) throw error; }
      return;
    }
    if (resource === '/api/explain') return send({ status: 'unavailable', message: 'AI disabled in this test.' });
    const asset = assets.get(resource);
    if (asset) return route.fulfill({ contentType: asset.type,
      body: resource === '/' && publicDeployment ? asset.bytes.toString().replace('id="local-workspace-badge"',
        'id="local-workspace-badge" data-public-deployment') : asset.bytes });
    errors.push(`Unregistered fixture resource: ${resource}`);
    return route.abort();
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + basePath);
  await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
  return { context, page, catalogue, editor: page.locator('#predicate-editor'),
    item: id => page.locator(`.exercise-item[data-exercise-id="${id}"]`) };
}
async function checked(page) {
  await page.locator('#check-button').click();
  await page.waitForFunction(() => ['ok', 'error', 'timeout'].includes(document.querySelector('#behavior-state').dataset.state));
}
async function unsolved(item) { assert.equal(await item.getAttribute('data-solved'), 'false'); }

try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  await check('workspace-badge-is-hidden-before-javascript-and-only-enabled-for-real-loopback-hosts', async () => {
    assert.match(assets.get('/').bytes.toString(), /id="local-workspace-badge"[^>]*hidden/);
    for (const [origin, visible] of [
      ['http://localhost', true], ['http://127.0.0.1', true], ['http://[::1]', true],
      ['https://alloy.example', false], ['http://localhost.example', false], ['http://127.0.0.1.example', false],
    ]) {
      const { context, page } = await session({ origin });
      try { assert.equal(await page.locator('#local-workspace-badge').isVisible(), visible, origin); }
      finally { await context.close(); }
    }
  });
  await check('public-deployment-marker-hides-workspace-badge-even-in-loopback-previews', async () => {
    const { context, page } = await session({ origin: 'http://127.0.0.1', publicDeployment: true });
    try {
      assert.equal(await page.locator('#local-workspace-badge').getAttribute('data-public-deployment'), '');
      assert.equal(await page.locator('#local-workspace-badge').isVisible(), false);
    } finally { await context.close(); }
  });
  await check('exact-zero-distance-and-perfect-completed-bounded-behavior-award-a-green-accessible-check', async () => {
    const requested = deferred(), release = deferred();
    const { context, page, item } = await session({ basePath: '/practice/', analyze: async payload => {
      requested.resolve(); await release.promise; return behavior(payload);
    } });
    try {
      await page.locator('#check-button').click(); await requested.promise;
      await unsolved(item('first')); assert.equal(await item('first').locator('.exercise-item-solved').count(), 0);
      release.resolve(); await page.waitForFunction(() => document.querySelector('[data-exercise-id="first"]').dataset.solved === 'true');
      assert.match(await item('first').getAttribute('aria-label'), /, Solved$/);
      assert.equal(await item('first').locator('.exercise-item-solved').textContent(), '✓');
      assert.equal(await item('first').locator('.exercise-item-solved').evaluate(element => getComputedStyle(element).color), 'rgb(33, 135, 120)');
      await unsolved(item('second'));
      const saved = await page.evaluate(() => JSON.parse(localStorage.getItem('alloy-studio:v1:solved')));
      assert.equal(saved.length, 1); assert.equal(saved[0].version, '3'.repeat(64));
      assert.equal(saved[0].metric, 'canonical'); assert.equal(Object.hasOwn(saved[0], 'body'), false);
      await mkdir(path.join(root, 'build/learner-progress'), { recursive: true });
      await page.screenshot({ path: path.join(root, 'build/learner-progress/solved-desktop.png'), fullPage: true });
    } finally { release.resolve(); await context.close(); }
  });
  await check('earned-progress-survives-edits-navigation-and-reloads-and-is-browser-local', async () => {
    const { context, page, editor, item } = await session();
    try {
      await checked(page); assert.equal(await item('first').getAttribute('data-solved'), 'true');
      await editor.fill('no Node'); await page.reload();
      await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
      assert.equal(await item('first').getAttribute('data-solved'), 'true');
      await item('second').click(); await page.waitForFunction(() => new URL(location).searchParams.get('exercise') === 'second');
      assert.equal(await item('first').getAttribute('data-solved'), 'true');
      await unsolved(item('second'));
      const other = await session();
      try { await unsolved(other.item('first')); } finally { await other.context.close(); }
    } finally { await context.close(); }
  });
  await check('changed-public-question-or-model-content-and-removed-exercises-invalidate-restored-checks', async () => {
    const { context, page, catalogue, item } = await session();
    try {
      await checked(page); assert.equal(await item('first').getAttribute('data-solved'), 'true');
      catalogue[0].description = 'An updated question.'; catalogue[0].contentVersion = 'c'.repeat(64);
      await page.reload(); await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
      await unsolved(item('first'));
      assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('alloy-studio:v1:solved'))), []);
      await checked(page); catalogue.shift(); await page.reload();
      await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
      assert.equal(await item('first').count(), 0);
      assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('alloy-studio:v1:solved'))), []);
    } finally { await context.close(); }
  });
  await check('rounded-1-point-000-and-zero-distance-without-perfect-behavior-do-not-award-progress', async () => {
    for (const mode of ['rounded', 'distance', 'unavailable', 'positiveMismatch', 'negativeMismatch', 'semanticCounterexample', 'zeroSamples', 'positiveWitnessMissing', 'negativeWitnessMissing']) {
      const { context, page, item } = await session({ feedback: payload => structural(payload, mode === 'distance' ? 1 : 0),
        analyze: payload => {
          const answer = behavior(payload);
          if (mode === 'rounded') answer.score = 0.9999;
          if (mode === 'unavailable') return { exerciseId: payload.exerciseId, revision: payload.revision, status: 'timeout' };
          if (mode === 'positiveMismatch') answer.sampling.positiveAccepted = 3;
          if (mode === 'negativeMismatch') answer.sampling.negativeRejected = 3;
          if (mode === 'semanticCounterexample') answer.sampling.semanticCounterexamples = 1;
          if (mode === 'zeroSamples') Object.assign(answer.sampling, { positiveTested: 0, positiveAccepted: 0, negativeTested: 0, negativeRejected: 0 });
          if (mode.endsWith('WitnessMissing')) Object.assign(answer.categories.find(category => category.id === (mode === 'positiveWitnessMissing' ? 'both' : 'neither')),
            { status: 'unsat', enumerationComplete: true, instances: [] });
          return answer;
        } });
      try {
        await checked(page); await unsolved(item('first'));
        if (mode === 'rounded') assert.equal(await page.locator('.behavior-score').textContent(), '1.000');
      } finally { await context.close(); }
    }
  });
  await check('counterexamples-in-either-direction-or-malformed-completion-evidence-do-not-award-progress', async () => {
    for (const mode of ['undercoverage', 'overcoverage', 'incomplete', 'wrongExercise', 'wrongRevision', 'factsMissing', 'duplicateCategory']) {
      const { context, page, item } = await session({ analyze: payload => {
        const answer = behavior(payload);
        if (['undercoverage', 'overcoverage'].includes(mode)) Object.assign(answer.categories.find(category => category.id === mode), { status: 'sat', instances: [instance] });
        if (mode === 'incomplete') answer.categories[1].enumerationComplete = false;
        if (mode === 'wrongExercise') answer.exerciseId = 'second';
        if (mode === 'wrongRevision') answer.revision += 1;
        if (mode === 'factsMissing') answer.scope.moduleFacts = false;
        if (mode === 'duplicateCategory') answer.categories[2] = answer.categories[1];
        return answer;
      } });
      try { await checked(page); await unsolved(item('first')); } finally { await context.close(); }
    }
  });
  await check('failed-or-wrongly-bound-structural-feedback-cannot-be-combined-with-a-perfect-behavior', async () => {
    for (const mode of ['unsupported', 'wrongExercise', 'wrongRevision', 'wrongMetric', 'nonfinite']) {
      let behaviorRequests = 0;
      const { context, page, item } = await session({ feedback: payload => {
        const answer = structural(payload);
        if (mode === 'unsupported') return { exerciseId: payload.exerciseId, revision: payload.revision,
          requestedMetric: payload.metric, status: 'unsupported' };
        if (mode === 'wrongExercise') answer.exerciseId = 'second';
        if (mode === 'wrongRevision') answer.revision += 1;
        if (mode === 'wrongMetric') answer.requestedMetric = 'ast';
        if (mode === 'nonfinite') answer.distance = null;
        return answer;
      }, analyze: payload => { behaviorRequests += 1; return behavior(payload); } });
      try {
        await page.locator('#check-button').click();
        if (mode === 'unsupported') {
          await page.waitForFunction(() => document.querySelector('#behavior-state').dataset.state === 'ok');
          assert.equal(behaviorRequests, 1);
        } else {
          await page.waitForFunction(() => document.querySelector('#feedback-state').dataset.state === 'error');
          assert.equal(behaviorRequests, 0);
        }
        await unsolved(item('first')); await unsolved(item('second'));
      } finally { await context.close(); }
    }
  });
  await check('stale-behavior-after-body-metric-or-exercise-change-never-awards-progress', async () => {
    for (const mode of ['body', 'metric', 'exercise']) {
      const requested = deferred(), release = deferred(), finished = deferred(); let requests = 0;
      const { context, page, editor, item } = await session({ analyze: async payload => {
        if (requests++ === 0) {
          requested.resolve(); await release.promise; finished.resolve(); return behavior(payload);
        }
        return { exerciseId: payload.exerciseId, revision: payload.revision, status: 'timeout' };
      } });
      try {
        await page.locator('#check-button').click(); await requested.promise;
        if (mode === 'body') await editor.fill('no Node');
        if (mode === 'metric') await page.locator('#distance-metric').selectOption('ast');
        if (mode === 'exercise') await item('second').click();
        release.resolve(); await finished.promise; await page.waitForTimeout(120);
        await unsolved(item('first')); await unsolved(item('second'));
        assert.equal(await page.evaluate(() => localStorage.getItem('alloy-studio:v1:solved')), '[]');
      } finally { release.resolve(); await context.close(); }
    }
  });
  await check('missing-or-changed-server-content-version-on-either-analysis-never-awards-progress', async () => {
    for (const mode of ['feedbackChanged', 'feedbackMissing', 'behaviorChanged', 'behaviorMissing']) {
      const { context, page, item } = await session({ feedback: payload => ({ ...structural(payload),
        ...(mode.startsWith('feedback') ? { contentVersion: mode.endsWith('Changed') ? 'e'.repeat(64) : null } : {}) }),
      analyze: payload => ({ ...behavior(payload),
        ...(mode.startsWith('behavior') ? { contentVersion: mode.endsWith('Changed') ? 'e'.repeat(64) : null } : {}) }) });
      try { await checked(page); await unsolved(item('first')); } finally { await context.close(); }
    }
  });
  await check('ast-zero-distance-can-award-progress-with-the-same-perfect-behavior', async () => {
    const { context, page, item } = await session();
    try {
      await page.locator('#distance-metric').selectOption('ast');
      await page.waitForFunction(() => document.querySelector('[data-exercise-id="first"]').dataset.solved === 'true');
      assert.equal(await item('first').getAttribute('data-solved'), 'true');
      assert.equal((await page.evaluate(() => JSON.parse(localStorage.getItem('alloy-studio:v1:solved'))))[0].metric, 'ast');
    } finally { await context.close(); }
  });
  await check('malformed-or-obsolete-local-storage-is-ignored-and-mobile-checks-fit', async () => {
    const storage = [null, { id: 'first', version: '3'.repeat(64), at: 1234, metric: 'unknown' },
      { id: 'first', version: 'obsolete', at: 1234, metric: 'canonical' }];
    const { context, page, item } = await session({ storage, viewport: { width: 390, height: 844 } });
    try {
      await unsolved(item('first')); await checked(page);
      const checkBounds = await item('first').locator('.exercise-item-solved').boundingBox();
      assert(checkBounds && checkBounds.x >= 0 && checkBounds.x + checkBounds.width <= 390);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.screenshot({ path: path.join(root, 'build/learner-progress/solved-mobile.png'), fullPage: true });
    } finally { await context.close(); }
  });
  await check('no-browser-errors-or-external-requests', async () => {
    assert.deepEqual(errors, []); assert.deepEqual(externalRequests, []);
  });
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally { await browser?.close(); }

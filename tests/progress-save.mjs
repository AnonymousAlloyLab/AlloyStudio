import assert from 'node:assert/strict';
import { readFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Production frontend with bounded deterministic backend DTOs. No JVM or paid AI.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const origin = 'https://progress.alloy.example';
const assets = new Map(await Promise.all([
  ['/', 'index.html', 'text/html'], ['/app.js', 'app.js', 'text/javascript'],
  ['/styles.css', 'styles.css', 'text/css'], ['/instance-graph.js', 'instance-graph.js', 'text/javascript'],
  ['/alloy-language.js', 'alloy-language.js', 'text/javascript'],
  ['/tutor-chat.js', 'tutor-chat.js', 'text/javascript'],
].map(async ([url, file, type]) => [url, { type, bytes: await readFile(path.join(root, 'web', file)) }])));
const sha = value => createHash('sha256').update(value).digest('hex');
const marker = '// Write your predicate here.';
const instance = { traceLength: 1, loopState: -1, states: [{ index: 0, signatures: [{ label: 'Node', atoms: ['Node$0'] }], relations: [] }] };
const makeRecords = () => ['A', 'B', 'C'].map((predicate, index) => {
  const heads = ['A', 'B', 'C'].map(name => `pred ${name} {${marker}}\n`);
  const before = 'sig Node {}\n' + heads.slice(0, index).join(''), after = '\n' + heads.slice(index + 1).join('');
  const slots = [];
  for (const [part, text] of [['environmentBefore', before], ['environmentAfter', after]]) {
    for (const name of ['A', 'B', 'C']) {
      const start = text.indexOf(`pred ${name} {`);
      if (start >= 0) slots.push({ id: name, part, start: start + `pred ${name} {`.length, end: start + `pred ${name} {`.length + marker.length });
    }
  }
  return { id: predicate, title: `Practice ${predicate}`, predicate, group: 'Dependent model', description: 'There must be a node.',
    contentVersion: String(index + 1).repeat(64), source: { path: 'dependencies.als', sha256: '9'.repeat(64) },
    starter: 'some Node', predicateHeader: `pred ${predicate} `, environmentBefore: before, environmentAfter: after,
    dependencies: index ? [{ id: ['A', 'B'][index - 1], predicate: ['A', 'B'][index - 1], contentVersion: String(index).repeat(64) }] : [],
    dependencySlots: slots };
});
const completedBehavior = payload => ({ exerciseId: payload.exerciseId, revision: payload.revision,
  status: 'ok', metric: 'acgn-reward', behaviorToken: sha(JSON.stringify(payload)), score: 1, scoreStatus: 'ok', scoreReason: 'OK',
  scope: { overall: 3, bitwidth: 3, maxSequence: 3, poolSize: 4, minTrace: 1, maxTrace: 1, moduleFacts: true },
  sampling: { positiveTested: 4, positiveAccepted: 4, negativeTested: 4, negativeRejected: 4, semanticCounterexamples: 0 },
  categories: [['both', true, true], ['undercoverage', true, false], ['overcoverage', false, true], ['neither', false, false]].map(([id, oracle, student]) => ({
    id, oracle, student, status: ['both', 'neither'].includes(id) ? 'sat' : 'unsat', enumerationComplete: true,
    instances: ['both', 'neither'].includes(id) ? [instance] : [] })) });
const passed = [], errors = [];
let browser;
async function check(name, operation) { await operation(); passed.push(name); }
async function session({ saved = null, records = makeRecords(), malformedSlots = false, viewport = { width: 1440, height: 1000 } } = {}) {
  const context = await browser.newContext({ viewport, acceptDownloads: true });
  await context.addInitScript(snapshot => {
    localStorage.setItem('alloy-studio:v1:live', 'false');
    if (snapshot) localStorage.setItem('alloy-studio:v1:progress', JSON.stringify(snapshot));
  }, saved);
  const completions = new Map(), evidence = new Map(), calls = [], expired = new Set();
  let sequence = 0, delayed = null, forceFreshTokens = false;
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) { errors.push(`External request: ${url}`); return route.abort(); }
    const send = (json, status = 200) => route.fulfill({ json, status });
    if (url.pathname === '/api/exercises') return send({ exercises: records.map(({ dependencySlots, ...record }) => record) });
    if (url.pathname.startsWith('/api/exercises/')) {
      const record = records.find(value => value.id === decodeURIComponent(url.pathname.slice('/api/exercises/'.length)));
      return send(malformedSlots && record.id === 'B' ? { ...record, dependencySlots: record.dependencySlots.map(slot => ({ ...slot, start: slot.start + 1 })) } : record);
    }
    if (url.pathname === '/api/channel') return send({ status: 'ok', channel: 'q'.repeat(43) });
    if (url.pathname === '/api/cancel') return send({ status: 'ok' });
    if (url.pathname === '/api/progress') {
      const payload = route.request().postDataJSON(); calls.push({ kind: 'progress', payload });
      const valid = payload.completionTokens.filter(token => completions.has(token) && !expired.has(token));
      return send({ status: 'ok', completions: valid.map(token => {
        const saved = completions.get(token);
        return { exerciseId: saved.exerciseId, contentVersion: saved.contentVersion, completionToken: token,
          dependencyContextVersion: saved.dependencyContextVersion, bodySha256: sha(saved.body), prerequisiteTokens: saved.prerequisiteTokens || [] };
      }), expiredTokens: payload.completionTokens.filter(token => !valid.includes(token)) });
    }
    if (url.pathname.startsWith('/api/')) {
      const payload = route.request().postDataJSON(), record = records.find(value => value.id === payload.exerciseId);
      const kind = url.pathname.slice('/api/'.length), tokens = payload.prerequisiteTokens || [];
      const chain = tokens.map(token => completions.get(token));
      const valid = tokens.length === record.dependencies.length && chain.every((saved, index) => saved && !expired.has(tokens[index])
        && saved.exerciseId === record.dependencies[index].id && saved.contentVersion === record.dependencies[index].contentVersion);
      if (!valid) return send({ status: 'locked', code: 'prerequisites_required', exerciseId: record.id, revision: payload.revision, required: record.dependencies }, 423);
      calls.push({ kind, payload });
      const dependencyContextVersion = sha(JSON.stringify(chain.map(saved => [saved.body, saved.dependencyContextVersion])));
      const common = { exerciseId: record.id, revision: payload.revision, contentVersion: record.contentVersion, dependencyContextVersion };
      if (kind === 'feedback') {
        if (delayed) await delayed.promise;
        const token = sha(JSON.stringify(payload)); evidence.set(token, { ...payload, dependencyContextVersion });
        const distance = payload.body.includes('wrong') ? 1 : 0;
        return send({ ...common, status: 'ok', requestedMetric: payload.metric, metric: payload.metric === 'ast'
          ? 'acgn-raw-ast-zhang-shasha-distance' : 'acgn-fast-rewrite-canonical-distance', evidenceToken: token,
          distance, canonicalForm: ['some Node'], breakdown: payload.metric === 'ast' ? { ast: distance } : { temporal: 0, quantifier: 0, matrix: distance },
          comparison: { strategy: 'nearest-known-correct', poolSize: 1, evaluatedCandidates: 1, complete: true }, operations: [],
          trace: { cost: distance, matchesDistance: true, hasAggregates: false, certifiedOptimalScript: false } });
      }
      if (kind === 'behavior') {
        const structural = evidence.get(payload.evidenceToken);
        const mint = structural?.body === payload.body && structural.dependencyContextVersion === dependencyContextVersion && !payload.body.includes('wrong');
        const prior = !forceFreshTokens && [...completions].find(([token, saved]) => !expired.has(token)
          && saved.exerciseId === record.id && saved.body === payload.body && saved.dependencyContextVersion === dependencyContextVersion
          && JSON.stringify(saved.prerequisiteTokens) === JSON.stringify(tokens));
        const token = prior ? prior[0] : Buffer.alloc(32, ++sequence).toString('base64url');
        if (mint) completions.set(token, { ...common, body: payload.body, prerequisiteTokens: [...tokens] });
        return send({ ...completedBehavior(payload), ...common, ...(mint ? { completionToken: token } : {}) });
      }
      if (kind === 'explain') return send({ ...common, requestedMetric: payload.metric, status: 'unavailable', message: 'No AI calls in this fixture.' });
      errors.push(`Unexpected API: ${kind}`); return route.abort();
    }
    const asset = assets.get(url.pathname);
    if (asset) return route.fulfill({ contentType: asset.type, body: asset.bytes });
    errors.push(`Unexpected resource: ${url.pathname}`); return route.abort();
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin); await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
  return { context, page, records, calls, completions, expired, editor: page.locator('#predicate-editor'),
    freshTokens: () => { forceFreshTokens = true; },
    delay: () => { let resolve; const promise = new Promise(done => { resolve = done; }); delayed = { promise, resolve }; return delayed; },
    item: id => page.locator(`[data-exercise-id="${id}"]`) };
}
async function choose(session, id) {
  await session.item(id).click();
  await session.page.waitForFunction(expected => new URL(location).searchParams.get('exercise') === expected, id);
}
async function complete(session, body = 'some Node') {
  await session.editor.fill(body); await session.page.locator('#check-button').click();
  await session.page.waitForFunction(() => document.querySelector('#behavior-state').dataset.state === 'ok');
  await session.page.waitForTimeout(30);
}
async function snapshot(session) {
  const downloaded = session.page.waitForEvent('download'); await session.page.locator('#save-progress').click();
  const download = await downloaded;
  return JSON.parse(await readFile(await download.path(), 'utf8'));
}
async function importSnapshot(session, value) {
  await session.page.locator('#progress-file').setInputFiles({ name: 'progress.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(value)) });
  await session.page.waitForFunction(() => document.querySelector('#progress-file').value === '');
}

try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  await check('dependent-practices-lock-until-a-to-b-to-c-verified-chain-is-complete', async () => {
    const s = await session();
    try {
      assert.equal(await s.item('B').getAttribute('data-locked'), 'true'); assert.equal(await s.item('C').getAttribute('data-locked'), 'true');
      await choose(s, 'B'); assert(await s.editor.isDisabled()); assert.match(await s.page.locator('#practice-lock').textContent(), /A/);
      assert.equal(s.calls.filter(call => ['feedback', 'behavior'].includes(call.kind)).length, 0);
      await choose(s, 'A'); await complete(s, 'some Node // A verified');
      assert.equal(await s.item('B').getAttribute('data-locked'), 'false'); assert.equal(await s.item('C').getAttribute('data-locked'), 'true');
      await choose(s, 'B'); assert(await s.editor.isEnabled());
      assert.match(await s.page.locator('#environment-code').textContent(), /some Node \/\/ A verified/);
      await complete(s, 'A[] // B verified'); await choose(s, 'C'); assert(await s.editor.isEnabled());
      assert.match(await s.page.locator('#environment-code').textContent(), /A\[\] \/\/ B verified/);
      const last = s.calls.filter(call => call.kind === 'feedback').at(-1);
      assert.equal(last.payload.prerequisiteTokens.length, 1); assert.equal(typeof last.payload.prerequisiteTokens[0], 'string');
    } finally { await s.context.close(); }
  });
  await check('last-verified-body-is-used-and-a-new-completion-invalidates-descendant-contexts', async () => {
    const s = await session();
    try {
      await complete(s, 'some Node // accepted old'); await choose(s, 'B'); await complete(s, 'A[]');
      await choose(s, 'A'); await s.editor.fill('no Node // wrong draft'); await choose(s, 'B');
      assert(await s.editor.isEnabled()); assert.match(await s.page.locator('#environment-code').textContent(), /accepted old/);
      assert.doesNotMatch(await s.page.locator('#environment-code').textContent(), /wrong draft/);
      await choose(s, 'A'); await complete(s, 'some Node // accepted new');
      assert.equal(await s.item('B').getAttribute('data-locked'), 'false'); assert.equal(await s.item('C').getAttribute('data-locked'), 'true');
      assert(await s.page.locator('#restore-progress').isVisible());
    } finally { await s.context.close(); }
  });
  await check('identical-recheck-retains-a-chain-but-a-replaced-nonce-invalidates-descendants', async () => {
    const s = await session();
    try {
      await complete(s, 'some Node'); await choose(s, 'B'); await complete(s, 'A[]');
      assert.equal(await s.item('C').getAttribute('data-locked'), 'false');
      await choose(s, 'A'); await complete(s, 'some Node');
      assert.equal(await s.item('C').getAttribute('data-locked'), 'false');
      assert.equal(await s.page.locator('#restore-progress').isVisible(), false);
      s.freshTokens(); await complete(s, 'some Node');
      assert.equal(await s.item('B').getAttribute('data-locked'), 'false');
      assert.equal(await s.item('C').getAttribute('data-locked'), 'true');
      assert(await s.page.locator('#restore-progress').isVisible());
    } finally { await s.context.close(); }
  });
  await check('save-file-round-trips-drafts-histories-preferences-and-last-verified-code', async () => {
    const s = await session();
    try {
      await complete(s, 'some Node // accepted'); await s.editor.fill('no Node // ongoing draft');
      const value = await snapshot(s); assert.equal(value.format, 'alloy-studio-progress'); assert.equal(value.version, 1);
      assert.equal(value.records[0].draft.body, 'no Node // ongoing draft'); assert.equal(value.records[0].completed.body, 'some Node // accepted');
      assert.equal(value.records[0].history.canonical.length, 1); assert.equal(value.metric, 'canonical');
      assert.equal(Object.hasOwn(value.solved[0], 'body'), false);
      await s.editor.fill('some Node // replaced locally'); await importSnapshot(s, value);
      assert.equal(await s.editor.inputValue(), 'no Node // ongoing draft'); assert.equal(await s.item('B').getAttribute('data-locked'), 'false');
      assert.match(await s.page.locator('#progress-status').textContent(), /Loaded 1 saved exercise/);
    } finally { await s.context.close(); }
  });
  await check('loading-progress-with-live-enabled-does-not-dispatch-analysis-or-ai', async () => {
    const s = await session();
    try {
      const value = await snapshot(s); value.live = true;
      const start = s.calls.length; await importSnapshot(s, value); await s.page.waitForTimeout(300);
      assert(await s.page.locator('#live-feedback').isChecked());
      assert.equal(s.calls.slice(start).filter(call => ['feedback', 'behavior', 'explain'].includes(call.kind)).length, 0);
    } finally { await s.context.close(); }
  });
  await check('forged-progress-checks-and-completion-tokens-never-unlock-a-dependent-practice', async () => {
    const s = await session();
    try {
      const value = await snapshot(s);
      value.records[0].completed = { body: 'some Node', metric: 'canonical', at: Date.now(), completionToken: 'x'.repeat(43), dependencyContextVersion: 'a'.repeat(64) };
      value.solved = [{ id: 'A', version: '1'.repeat(64), at: Date.now(), metric: 'canonical' }];
      await importSnapshot(s, value); assert.equal(await s.item('B').getAttribute('data-locked'), 'true');
      await choose(s, 'B'); assert(await s.editor.isDisabled()); assert.equal(s.calls.filter(call => call.kind === 'feedback').length, 0);
      assert(await s.page.locator('#restore-progress').isVisible());
    } finally { await s.context.close(); }
  });
  await check('altered-saved-body-cannot-redeem-an-otherwise-valid-completion-token', async () => {
    const s = await session();
    try {
      await complete(s, 'some Node // genuine'); const value = await snapshot(s);
      value.records[0].completed.body = 'no Node // wrong forged body';
      await importSnapshot(s, value);
      assert.equal(await s.item('B').getAttribute('data-locked'), 'true');
      assert(await s.page.locator('#restore-progress').isVisible());
    } finally { await s.context.close(); }
  });
  await check('mixed-historical-valid-tokens-do-not-activate-a-different-prerequisite-context', async () => {
    const s = await session();
    try {
      await complete(s, 'some Node // old A'); await choose(s, 'B'); await complete(s, 'A[] // old B');
      await choose(s, 'A'); await complete(s, 'some Node // new A'); const value = await snapshot(s);
      assert.equal(value.records.find(record => record.id === 'B').completed.prerequisiteTokens.length, 1);
      await importSnapshot(s, value);
      assert.equal(await s.item('B').getAttribute('data-locked'), 'false');
      assert.equal(await s.item('C').getAttribute('data-locked'), 'true');
      assert(await s.page.locator('#restore-progress').isVisible());
    } finally { await s.context.close(); }
  });
  await check('expired-checks-restore-sequentially-in-graph-order-without-ai-explanations', async () => {
    const s = await session();
    try {
      await complete(s); await choose(s, 'B'); await complete(s, 'A[]'); const value = await snapshot(s);
      for (const token of s.completions.keys()) s.expired.add(token);
      await importSnapshot(s, value); assert(await s.editor.isDisabled());
      const start = s.calls.length; await s.page.locator('#restore-progress').click();
      await s.page.waitForFunction(() => document.querySelector('#progress-status').textContent.startsWith('Saved implementations checked'));
      assert.deepEqual(s.calls.slice(start).filter(call => ['feedback', 'behavior'].includes(call.kind)).map(call => [call.kind, call.payload.exerciseId]),
        [['feedback', 'A'], ['behavior', 'A'], ['feedback', 'B'], ['behavior', 'B']]);
      assert.equal(s.calls.slice(start).filter(call => call.kind === 'explain').length, 0); assert(await s.editor.isEnabled());
    } finally { await s.context.close(); }
  });
  await check('malformed-and-oversized-files-are-rejected-before-any-editor-or-storage-mutation', async () => {
    const s = await session();
    try {
      await s.editor.fill('some Node // must survive'); const original = await snapshot(s);
      for (const mutate of [value => { value.rogue = true; }, value => { value.records[0].draft.body = 'x'.repeat(8193); },
        value => { value.records[0].contentVersion = ['1'.repeat(64)]; },
        value => { value.records[0].completed = { body: 'some Node', metric: 'canonical', at: Date.now(), completionToken: ['x'.repeat(43)] }; },
        value => { value.records[0].history.ast = [{ distance: -1, at: Date.now(), basis: 'nearest-known-correct-raw-ast-v1' }]; },
        value => { value.records.push(...Array.from({ length: 200 }, (_, index) => ({ ...value.records[0], id: `fake-${index}` }))); },
        value => { Object.defineProperty(value.records[0], '__proto__', { value: {}, enumerable: true }); }]) {
        const before = await s.page.evaluate(() => localStorage.getItem('alloy-studio:v1:progress'));
        const malformed = structuredClone(original); mutate(malformed); await importSnapshot(s, malformed);
        assert.equal(await s.editor.inputValue(), 'some Node // must survive');
        assert.equal(await s.page.evaluate(() => localStorage.getItem('alloy-studio:v1:progress')), before);
      }
      await s.page.locator('#progress-file').setInputFiles({ name: 'large.json', mimeType: 'application/json', buffer: Buffer.alloc(4 * 1024 * 1024 + 1, 32) });
      await s.page.waitForFunction(() => document.querySelector('#progress-file').value === '');
      assert.match(await s.page.locator('#progress-status').textContent(), /at most 4 MiB/);
    } finally { await s.context.close(); }
  });
  await check('save-refuses-invalid-or-oversized-latest-drafts-without-silently-dropping-code', async () => {
    const s = await session();
    try {
      await snapshot(s);
      const before = await s.page.evaluate(() => localStorage.getItem('alloy-studio:v1:progress'));
      let downloads = 0; s.page.on('download', () => { downloads += 1; });
      for (const body of ['x'.repeat(8193), 'é'.repeat(4097), 'some Node\0']) {
        await s.editor.fill(body); await s.page.locator('#save-progress').click();
        assert.match(await s.page.locator('#progress-status').textContent(), /not saved.*at most 8 KiB/);
        assert.equal(await s.editor.inputValue(), body);
        assert.equal(await s.page.evaluate(() => localStorage.getItem('alloy-studio:v1:progress')), before);
      }
      assert.equal(downloads, 0);
      await choose(s, 'B'); await s.page.locator('#save-progress').click();
      assert.match(await s.page.locator('#progress-status').textContent(), /not saved.*draft for A/);
      assert.equal(await s.page.evaluate(() => JSON.parse(localStorage.getItem('alloy-studio:v1:draft:A')).body), 'some Node\0');
      await s.editor.evaluate(element => { element.value = 'x'.repeat(8193); });
      await s.page.locator('#save-progress').click(); assert.match(await s.page.locator('#progress-status').textContent(), /not saved/);
    } finally { await s.context.close(); }
  });
  await check('removed-or-changed-exercises-are-omitted-while-current-records-restore', async () => {
    const s = await session();
    try {
      const value = await snapshot(s); value.records.push({ ...structuredClone(value.records[0]), id: 'removed' });
      value.records.push({ ...structuredClone(value.records[0]), id: 'B', contentVersion: 'e'.repeat(64) });
      await importSnapshot(s, value); assert.match(await s.page.locator('#progress-status').textContent(), /omitted 2 changed or removed exercises/);
      assert.equal(JSON.parse(await s.page.evaluate(() => localStorage.getItem('alloy-studio:v1:progress'))).records.length, 1);
    } finally { await s.context.close(); }
  });
  await check('browser-quota-failure-leaves-existing-progress-and-editor-untouched', async () => {
    const s = await session();
    try {
      await s.editor.fill('some Node // old draft'); const value = await snapshot(s); value.records[0].draft.body = 'some Node // incoming';
      const before = await s.page.evaluate(() => localStorage.getItem('alloy-studio:v1:progress'));
      await s.page.evaluate(() => { Storage.prototype.setItem = function () { throw new DOMException('Quota', 'QuotaExceededError'); }; });
      await importSnapshot(s, value); assert.equal(await s.editor.inputValue(), 'some Node // old draft');
      assert.equal(await s.page.evaluate(() => localStorage.getItem('alloy-studio:v1:progress')), before);
      assert.match(await s.page.locator('#progress-status').textContent(), /not loaded.*storage is full/);
    } finally { await s.context.close(); }
  });
  await check('failed-browser-writes-still-export-the-current-and-navigated-away-drafts', async () => {
    const s = await session();
    try {
      await s.editor.fill('some Node // OLD'); await snapshot(s);
      await s.page.evaluate(() => { Storage.prototype.setItem = function () { throw new DOMException('Quota', 'QuotaExceededError'); }; });
      await s.editor.fill('some Node // NEW storage failed');
      const current = await snapshot(s); assert.equal(current.records.find(record => record.id === 'A').draft.body, 'some Node // NEW storage failed');
      assert.match(await s.page.locator('#progress-status').textContent(), /Browser storage is full/);
      await choose(s, 'B'); const away = await snapshot(s);
      assert.equal(away.records.find(record => record.id === 'A').draft.body, 'some Node // NEW storage failed');
      await choose(s, 'A'); assert.equal(await s.editor.inputValue(), 'some Node // NEW storage failed');
    } finally { await s.context.close(); }
  });
  await check('future-imported-timestamps-cannot-suppress-subsequent-local-edits', async () => {
    const s = await session();
    try {
      const value = await snapshot(s); value.records[0].draft.body = 'some Node // OLD future clock';
      value.records[0].draft.updatedAt = Number.MAX_SAFE_INTEGER; await importSnapshot(s, value);
      await s.editor.fill('some Node // NEW after future import'); await choose(s, 'B'); await choose(s, 'A');
      assert.equal(await s.editor.inputValue(), 'some Node // NEW after future import');
      const exported = await snapshot(s); assert.equal(exported.records.find(record => record.id === 'A').draft.body, 'some Node // NEW after future import');
      await s.page.reload(); await s.page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
      assert.equal(await s.editor.inputValue(), 'some Node // NEW after future import');
    } finally { await s.context.close(); }
  });
  await check('near-limit-progress-download-is-bounded-and-can-be-loaded-again', async () => {
    const base = makeRecords()[0];
    const catalogue = Array.from({ length: 200 }, (_, index) => ({ ...base, id: `practice-${index}`, predicate: `inv${index}`,
      predicateHeader: `pred inv${index} `, title: `Practice ${index}`, dependencies: [], dependencySlots: [] }));
    const s = await session({ records: catalogue });
    try {
      const point = metric => ({ distance: 1, at: Date.now(), basis: metric === 'canonical'
        ? 'nearest-known-correct-v1' : 'nearest-known-correct-raw-ast-v1' });
      const value = { format: 'alloy-studio-progress', version: 1, savedAt: Date.now(), selectedExerciseId: catalogue[0].id,
        metric: 'canonical', live: false, solved: [], records: catalogue.map(record => ({ id: record.id, contentVersion: record.contentVersion,
          sourceSha256: record.source.sha256, draft: { body: 'a'.repeat(8192), updatedAt: Date.now() },
          completed: { body: 'b'.repeat(8192), at: Date.now(), metric: 'canonical', completionToken: 'z'.repeat(43),
            dependencyContextVersion: 'a'.repeat(64), prerequisiteTokens: Array.from({ length: 20 }, (_, index) => String.fromCharCode(65 + index).repeat(43)) },
          history: { canonical: Array.from({ length: 12 }, () => point('canonical')), ast: Array.from({ length: 12 }, () => point('ast')) } })) };
      assert(Buffer.byteLength(JSON.stringify(value)) < 4 * 1024 * 1024);
      await importSnapshot(s, value);
      const downloaded = s.page.waitForEvent('download'); await s.page.locator('#save-progress').click();
      const bytes = await readFile(await (await downloaded).path());
      assert(bytes.length <= 4 * 1024 * 1024, `Export exceeded its own ${4 * 1024 * 1024}-byte import bound`);
      await importSnapshot(s, JSON.parse(bytes.toString('utf8')));
      assert.match(await s.page.locator('#progress-status').textContent(), /Loaded 200 saved exercises/);
    } finally { await s.context.close(); }
  });
  await check('full-in-memory-draft-capacity-refuses-save-and-navigation-without-dropping-current-code', async () => {
    const base = makeRecords()[0];
    const catalogue = Array.from({ length: 201 }, (_, index) => ({ ...base, id: `practice-${index}`, predicate: `inv${index}`,
      predicateHeader: `pred inv${index} `, title: `Practice ${index}`, dependencies: [], dependencySlots: [] }));
    const s = await session({ records: catalogue });
    try {
      await s.page.evaluate(() => { Storage.prototype.setItem = function () { throw new DOMException('Quota', 'QuotaExceededError'); }; });
      for (let index = 0; index < 200; index += 1) {
        await s.editor.fill(`some Node // saved in memory ${index}`); await choose(s, catalogue[index + 1].id);
      }
      const body = 'some Node // CURRENT 201 cannot enter memory'; await s.editor.fill(body);
      let downloads = 0; s.page.on('download', () => { downloads += 1; }); await s.page.locator('#save-progress').click();
      assert.match(await s.page.locator('#progress-status').textContent(), /Progress was not saved/); assert.equal(downloads, 0);
      await s.item(catalogue[0].id).click();
      assert.equal(await s.editor.inputValue(), body);
      assert.equal(new URL(s.page.url()).searchParams.get('exercise'), catalogue[200].id);
    } finally { await s.context.close(); }
  });
  await check('malformed-public-slots-lock-the-editor-instead-of-substituting-code', async () => {
    const s = await session({ malformedSlots: true });
    try { await complete(s); await choose(s, 'B'); assert(await s.editor.isDisabled()); assert.match(await s.page.locator('#practice-lock').textContent(), /slot has changed/); }
    finally { await s.context.close(); }
  });
  await check('restore-cancellation-after-navigation-cannot-unlock-a-chain-or-start-more-analysis', async () => {
    const s = await session(); let waiting;
    try {
      await complete(s); const value = await snapshot(s); for (const token of s.completions.keys()) s.expired.add(token);
      await importSnapshot(s, value); waiting = s.delay(); const start = s.calls.length;
      await s.page.locator('#restore-progress').click();
      await s.page.waitForFunction(() => document.querySelector('#progress-status').textContent.startsWith('Restoring'));
      await choose(s, 'B'); waiting.resolve(); await s.page.waitForTimeout(100);
      assert(await s.editor.isDisabled()); assert.equal(s.calls.slice(start).filter(call => call.kind === 'behavior').length, 0);
      assert.equal(await s.item('B').getAttribute('data-locked'), 'true');
    } finally { waiting?.resolve(); await s.context.close(); }
  });
  await check('progress-controls-and-locked-prerequisite-explanations-fit-on-mobile', async () => {
    const s = await session({ viewport: { width: 390, height: 844 } });
    try {
      await choose(s, 'B'); assert(await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      assert(await s.page.locator('#save-progress').isVisible()); assert(await s.page.locator('#load-progress').isVisible());
      await mkdir(path.join(root, 'build/learner-progress'), { recursive: true });
      await s.page.screenshot({ path: path.join(root, 'build/learner-progress/dependency-locked-mobile.png'), fullPage: true });
    } finally { await s.context.close(); }
  });
  await check('no-browser-errors-or-unregistered-network-requests', async () => { assert.deepEqual(errors, []); });
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally { await browser?.close(); }

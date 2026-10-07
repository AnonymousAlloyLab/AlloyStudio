import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Production administration assets against finite, offline public/private DTOs.
// All requests are intercepted; no backend, solver, or OpenAI call is made.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const assets = new Map(await Promise.all([
  ['/', 'index.html', 'text/html'], ['/app.js', 'app.js', 'text/javascript'], ['/styles.css', 'styles.css', 'text/css'],
].map(async ([url, file, type]) => [url, { type, bytes: await readFile(path.join(root, 'web/admin', file)) }])));
const hash = value => createHash('sha256').update(value).digest('hex');
const attack = '<img src=x onerror="window.adminInjected=true">';
const passed = [], errors = [], external = [];
let browser;
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function check(name, operation) { await operation(); passed.push(name); }
async function session(options = {}) {
  const origin = 'https://alloy.example', base = '/alloy/', csrf = 'PRIVATE_FIXTURE_CSRF';
  const state = { authenticated: options.authenticated ?? true, enabled: true, expireNext: false,
    requests: [], jobs: new Map(), jobSequence: 0, providerCalls: 0, approvalChecks: 0,
    questions: Array.from({ length: options.questionCount ?? 52 }, (_, index) => ({
      id: `exercise-${index + 1}`, predicate: `Inv${index + 1}`, group: 'Graphs', title: `Question ${index + 1}`,
      question: `Public question ${index + 1}.`, exerciseVersion: hash(`exercise-${index + 1}-v1`), contentVersion: hash(`public-${index + 1}-v1`) })),
    candidates: Array.from({ length: options.candidateCount ?? 27 }, (_, index) => ({
      id: String(index + 1).padStart(43, 'C'), exerciseId: `exercise-${index + 1}`,
      exerciseVersion: hash(`exercise-${index + 1}-v1`), candidateVersion: hash(`candidate-${index + 1}-v1`),
      candidateHash: hash('some Node'), state: 'pending', stale: false, revision: 1,
      createdAt: 123000 + index, body: `some Node // PRIVATE_LEARNER_BODY_${index + 1}`,
      behavioralEvidence: { score: 1, moduleFacts: true, semanticCounterexamples: 0,
        undercoverage: 'unsat', overcoverage: 'unsat' }, review: null })) };
  const context = await browser.newContext({ viewport: options.viewport || { width: 1440, height: 1000 } });
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) { external.push(url.href); return route.abort(); }
    const send = (json, status = 200) => route.fulfill({ status, json, headers: { 'Cache-Control': 'no-store, private' } });
    if (!url.pathname.startsWith(base)) { errors.push('A relative API escaped its deployment base path.'); return route.abort(); }
    const pathname = url.pathname.slice(base.length);
    if (pathname.startsWith('admin/')) {
      const asset = assets.get('/' + pathname.slice('admin/'.length));
      if (!asset) { errors.push('Unregistered admin asset.'); return route.abort(); }
      return route.fulfill({ contentType: asset.type, body: asset.bytes });
    }
    const operation = pathname.slice('api/admin/'.length), request = route.request();
    const data = request.method() === 'POST' ? request.postDataJSON() : undefined;
    state.requests.push({ operation, data, headers: request.headers(), method: request.method() });
    if (operation === 'session') return send({ enabled: state.enabled, authenticated: state.authenticated,
      csrfToken: state.authenticated ? csrf : 'preauth-fixture' });
    if (!state.authenticated || state.expireNext) {
      state.expireNext = false; state.authenticated = false; return send({ error: 'Your session expired. Sign in again.' }, 401);
    }
    if (request.method() === 'POST') assert.equal(request.headers()['x-csrf-token'], csrf);
    assert.equal(request.method(), operation.startsWith('drafts/') ? 'GET' : 'POST');
    if (operation === 'logout') { state.authenticated = false; return send({ status: 'ok' }); }
    if (operation === 'discard') {
      assert.deepEqual(Object.keys(data), ['id']);
      assert(state.jobs.has(data.id)); state.jobs.delete(data.id); return send({ status: 'discarded' });
    }
    if (options.intercept && await options.intercept({ route, operation, data, state, send })) return;
    if (operation === 'library') {
      assert.deepEqual(Object.keys(data), ['offset']);
      return send({ items: state.questions.slice(data.offset, data.offset + 50).map(({ question, ...item }) => item),
        total: state.questions.length, offset: data.offset, limit: 50 });
    }
    if (operation === 'questions/detail') return send(state.questions.find(item => item.id === data.exerciseId));
    if (operation === 'questions/edit') {
      const item = state.questions.find(value => value.id === data.exerciseId);
      if (item.exerciseVersion !== data.version) return send({ error: 'This question changed. Refresh the library.' }, 409);
      item.title = data.title; item.question = data.question; item.exerciseVersion = hash(item.exerciseVersion + 'next');
      item.contentVersion = hash(item.contentVersion + 'next');
      return send({ status: 'updated' });
    }
    if (operation === 'questions/remove') {
      const item = state.questions.find(value => value.id === data.exerciseId);
      assert.equal(data.version, item.exerciseVersion); assert.equal(data.confirmation, item.id);
      state.questions = state.questions.filter(value => value !== item);
      return send({ status: 'removed' });
    }
    if (operation === 'candidates') return send({ items: state.candidates.slice(data.offset, data.offset + 25)
      .map(({ body, behavioralEvidence, review, ...item }) => item), total: state.candidates.length, offset: data.offset, limit: 25 });
    if (operation === 'candidates/detail') {
      const item = state.candidates.find(value => value.id === data.id);
      if (item.candidateVersion !== data.version) return send({ error: 'This candidate changed. Refresh the cache.' }, 409);
      return send(item);
    }
    if (operation.startsWith('candidates/')) {
      const action = operation.slice('candidates/'.length), item = state.candidates.find(value => value.id === data.id);
      if (item.candidateVersion !== data.version || item.state !== 'pending' || item.stale) return send({ error: 'This candidate is no longer pending.' }, 409);
      assert.deepEqual(Object.keys(data).sort(), ['id', 'version']);
      if (action === 'dismiss') {
        item.state = 'dismissed'; item.candidateVersion = hash(item.candidateVersion + 'dismissed'); return send({ status: 'dismissed' });
      }
      if (state.jobs.size >= 2) return send({ error: 'Discard an earlier draft before starting another operation.' }, 429);
      if (action === 'review') state.providerCalls += 1;
      if (action === 'approve') state.approvalChecks += 1;
      const id = String(++state.jobSequence).padStart(43, 'J');
      const job = { id, candidateId: item.id, kind: action, state: action === 'review' ? 'reviewing' : 'approving', message: 'Checking this candidate.' };
      state.jobs.set(id, { job, item }); return send(job, 202);
    }
    if (operation.startsWith('drafts/')) {
      const { job, item } = state.jobs.get(operation.slice('drafts/'.length));
      if (options.jobMismatch) return send({ ...job, id: 'W'.repeat(43), state: 'completed' });
      if (job.kind === 'review') {
        item.review = options.providerUnavailable ? { status: 'disabled', message: 'No provider key is configured. Manual decisions are available.' }
          : { status: 'ok', advice: { verdict: options.verdict || 'uncertain', reason: `Review this candidate carefully. ${attack}`,
            counterexampleIdeas: [`Try a two-node structure. ${attack}`] } };
        item.candidateVersion = hash(item.candidateVersion + 'reviewed');
      } else if (!options.approvalRejected) {
        item.state = 'approved'; item.candidateVersion = hash(item.candidateVersion + 'approved');
      }
      return send({ ...job, state: job.kind === 'approve' && options.approvalRejected ? 'rejected' : 'completed',
        message: options.approvalRejected ? 'A bounded counterexample prevents approval.' : 'Candidate operation completed.',
        result: job.kind === 'review' ? item : { status: 'approved', exerciseId: item.exerciseId, exerciseCount: state.questions.length } });
    }
    errors.push(`Unregistered operation: ${operation}`); return send({ error: 'Unregistered operation.' }, 500);
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(`${origin}${base}admin/`);
  await page.waitForFunction(() => !document.querySelector('#notice').textContent.includes('Checking administration'));
  return { context, page, state, count: operation => state.requests.filter(request => request.operation === operation).length,
    latest: operation => state.requests.filter(request => request.operation === operation).at(-1) };
}
async function library(page) { await page.locator('#tab-library').click(); await page.waitForFunction(() => document.querySelector('#library-page').textContent.includes('questions')); }
async function cache(page) { await page.locator('#tab-candidates').click(); await page.waitForFunction(() => document.querySelector('#candidates-page').textContent.includes('candidates')); }
async function question(page, id = 'exercise-1') {
  await page.locator(`#library-list [data-item-id="${id}"]`).click();
  await page.waitForFunction(expected => !document.querySelector('#question-form').hidden && document.querySelector('#question-identity').textContent === expected, id);
}
async function candidate(page, id = String(1).padStart(43, 'C')) {
  await page.locator(`#candidates-list [data-item-id="${id}"]`).click();
  await page.waitForFunction(expected => !document.querySelector('#candidate-detail').hidden && document.querySelector('#candidate-identity').textContent.includes(expected), id);
}
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  await check('default-upload-and-signed-out-workspace-do-not-fetch-private-library-or-candidates', async () => {
    for (const authenticated of [true, false]) {
      const { context, page, state } = await session({ authenticated });
      try {
        assert.deepEqual(state.requests.map(request => request.operation), ['session']);
        assert.equal(await page.locator('#workspace').isVisible(), authenticated);
        assert.equal(await page.locator('#library-panel').isVisible(), false);
        assert.equal(await page.locator('#candidates-panel').isVisible(), false);
      } finally { await context.close(); }
    }
  });
  await check('question-pages-are-bounded-and-edit-submits-only-reviewed-public-metadata-with-current-version', async () => {
    const { context, page, count, latest, state } = await session();
    try {
      await library(page); assert.equal(await page.locator('#library-list .library-choice').count(), 50);
      assert.equal(await page.locator('#library-previous').isDisabled(), true);
      await page.locator('#library-next').click(); await page.waitForFunction(() => document.querySelector('#library-page').textContent === '51–52 of 52 questions');
      assert.equal(await page.locator('#library-list .library-choice').count(), 2); assert.equal(await page.locator('#library-next').isDisabled(), true);
      await question(page, 'exercise-51');
      assert.equal(await page.locator('#question-save').isDisabled(), true);
      await page.locator('#question-reviewed').check(); await page.locator('#question-text').fill('A revised question.');
      assert.equal(await page.locator('#question-reviewed').isChecked(), false);
      await page.locator('#question-title').fill(`Revised title ${attack}`); await page.locator('#question-reviewed').check();
      const oldVersion = state.questions[50].exerciseVersion;
      await page.locator('#question-save').click(); await page.waitForFunction(() => document.querySelector('#notice').textContent.startsWith('Question saved'));
      assert.equal(count('questions/edit'), 1);
      assert.deepEqual(Object.keys(latest('questions/edit').data).sort(), ['exerciseId','question','title','version']);
      assert.equal(latest('questions/edit').data.version, oldVersion);
      assert.equal(await page.locator('#question-title').inputValue(), `Revised title ${attack}`);
      assert.notEqual(state.questions[50].exerciseVersion, oldVersion);
      assert.equal(await page.locator('#library-list img').count(), 0);
    } finally { await context.close(); }
  });
  await check('question-removal-requires-confirmation-for-selected-identity-and-empty-library-is-valid', async () => {
    const { context, page, count, latest } = await session({ questionCount: 1 });
    try {
      await library(page); await question(page); await page.locator('#question-remove-open').click();
      assert.equal(count('questions/remove'), 0); assert.equal(await page.locator('#question-remove').isDisabled(), true);
      await page.locator('#question-remove-confirm').check(); await page.locator('#question-remove-cancel').click();
      assert.equal(count('questions/remove'), 0); await page.locator('#question-remove-open').click();
      assert.equal(await page.locator('#question-remove-confirm').isChecked(), false);
      await page.locator('#question-remove-confirm').check(); await page.locator('#question-remove').click();
      await page.waitForFunction(() => document.querySelector('#library-page').textContent === 'No questions');
      assert.equal(latest('questions/remove').data.confirmation, 'exercise-1');
      assert.deepEqual(Object.keys(latest('questions/remove').data).sort(), ['confirmation','exerciseId','version']);
      assert.equal(await page.locator('#question-form').isVisible(), false);
    } finally { await context.close(); }
  });
  await check('utf8-byte-limit-rejects-oversized-question-title-before-mutation', async () => {
    const { context, page, count } = await session();
    try {
      await library(page); await question(page); await page.locator('#question-title').fill('é'.repeat(129));
      await page.locator('#question-reviewed').check(); await page.locator('#question-save').click();
      await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('256 UTF-8 bytes'));
      assert.equal(count('questions/edit'), 0);
    } finally { await context.close(); }
  });
  await check('candidate-list-is-paged-and-details-never-call-ai-or-auto-approve', async () => {
    const { context, page, state } = await session();
    try {
      await cache(page); assert.equal(await page.locator('#candidates-list .library-choice').count(), 25);
      await page.locator('#candidates-next').click(); await page.waitForFunction(() => document.querySelector('#candidates-page').textContent === '26–27 of 27 candidates');
      await candidate(page, String(26).padStart(43, 'C'));
      assert.match(await page.locator('#candidate-body').textContent(), /PRIVATE_LEARNER_BODY_26/);
      assert.equal(state.providerCalls, 0); assert.equal(state.approvalChecks, 0);
      assert.match(await page.locator('#candidates-panel').textContent(), /not a proof of equivalence/);
      await page.locator('#candidates-refresh').click(); await page.waitForFunction(() => document.querySelector('#candidate-detail').hidden);
      assert.equal(state.providerCalls, 0);
    } finally { await context.close(); }
  });
  await check('explicit-sol-review-refreshes-version-and-renders-advice-as-text-without-taking-admin-authority', async () => {
    for (const verdict of ['recommend','reject','uncertain']) {
      const { context, page, state, latest } = await session({ candidateCount: 1, verdict });
      try {
        await cache(page); await candidate(page); const oldVersion = state.candidates[0].candidateVersion;
        await page.locator('#candidate-review').click();
        await page.waitForFunction(() => !document.querySelector('#candidate-approve-open').disabled);
        assert.equal(state.providerCalls, 1); assert.equal(state.approvalChecks, 0);
        assert.equal(latest('candidates/review').data.version, oldVersion);
        assert.deepEqual(Object.keys(latest('candidates/review').data).sort(), ['id','version']);
        assert.notEqual(state.candidates[0].candidateVersion, oldVersion);
        assert.match(await page.locator('#candidate-advice').textContent(), new RegExp(`Recommendation: ${verdict}`));
        assert.match(await page.locator('#candidate-advice').textContent(), /<img/);
        assert.equal(await page.locator('#candidate-advice img').count(), 0);
        assert.equal(await page.evaluate(() => window.adminInjected), undefined);
        assert.equal(await page.locator('#candidate-review').isDisabled(), true);
        assert.equal(await page.locator('#candidate-approve-open').isEnabled(), true);
      } finally { await context.close(); }
    }
  });
  await check('approval-is-explicit-versioned-and-fresh-validation-result-controls-terminal-state', async () => {
    for (const approvalRejected of [false, true]) {
      const { context, page, state, latest } = await session({ candidateCount: 1, approvalRejected });
      try {
        await cache(page); await candidate(page); await page.locator('#candidate-approve-open').click();
        assert.equal(state.approvalChecks, 0); assert.equal(await page.locator('#candidate-approve').isDisabled(), true);
        await page.locator('#candidate-approve-confirm').check(); await page.locator('#candidate-approve').click();
        await page.waitForFunction(() => !document.querySelector('#tab-candidates').disabled);
        assert.equal(state.approvalChecks, 1); assert.equal(state.providerCalls, 0);
        assert.deepEqual(Object.keys(latest('candidates/approve').data).sort(), ['id','version']);
        assert.equal(state.candidates[0].state, approvalRejected ? 'pending' : 'approved');
        assert.equal(await page.locator('#candidate-approve-open').isDisabled(), !approvalRejected);
        if (approvalRejected) assert.match(await page.locator('#notice').textContent(), /counterexample/);
      } finally { await context.close(); }
    }
  });
  await check('provider-unavailable-keeps-manual-approval-and-dismissal-usable', async () => {
    const { context, page, state, latest } = await session({ candidateCount: 1, providerUnavailable: true });
    try {
      await cache(page); await candidate(page); await page.locator('#candidate-review').click();
      await page.waitForFunction(() => !document.querySelector('#candidate-dismiss').disabled);
      assert.match(await page.locator('#candidate-advice').textContent(), /No provider key/);
      assert.equal(await page.locator('#candidate-approve-open').isEnabled(), true);
      await page.locator('#candidate-dismiss').click(); await page.waitForFunction(() => document.querySelector('#candidate-state').textContent.includes('dismissed'));
      assert.deepEqual(Object.keys(latest('candidates/dismiss').data).sort(), ['id','version']);
      assert.equal(state.candidates[0].state, 'dismissed'); assert.equal(await page.locator('#candidate-review').isDisabled(), true);
    } finally { await context.close(); }
  });
  await check('terminal-review-and-approval-jobs-are-discarded-so-more-than-two-operations-remain-usable', async () => {
    const { context, page, state, count } = await session({ candidateCount: 2, approvalRejected: true });
    try {
      await cache(page); await candidate(page); await page.locator('#candidate-review').click();
      await page.waitForFunction(() => !document.querySelector('#tab-candidates').disabled);
      assert.equal(state.jobs.size, 0); assert.equal(count('discard'), 1);
      await page.locator('#candidate-approve-open').click(); await page.locator('#candidate-approve-confirm').check();
      await page.locator('#candidate-approve').click(); await page.waitForFunction(() => !document.querySelector('#tab-candidates').disabled);
      assert.equal(state.jobs.size, 0); assert.equal(count('discard'), 2);
      await candidate(page, String(2).padStart(43, 'C')); await page.locator('#candidate-review').click();
      await page.waitForFunction(() => !document.querySelector('#tab-candidates').disabled);
      assert.equal(state.providerCalls, 2); assert.equal(state.approvalChecks, 1);
      assert.equal(state.jobs.size, 0); assert.equal(count('discard'), 3);
      assert.match(await page.locator('#candidate-advice').textContent(), /Recommendation: uncertain/);
    } finally { await context.close(); }
  });
  await check('stale-candidates-have-no-review-approval-or-dismissal-authority', async () => {
    const { context, page, state } = await session({ candidateCount: 1 }); state.candidates[0].stale = true;
    try {
      await cache(page); await candidate(page);
      for (const id of ['candidate-review','candidate-approve-open','candidate-dismiss']) assert.equal(await page.locator(`#${id}`).isDisabled(), true);
      assert.match(await page.locator('#candidate-state').textContent(), /Stale/);
    } finally { await context.close(); }
  });
  await check('newer-question-selection-wins-over-delayed-details', async () => {
    const requested = deferred(), release = deferred(), finished = deferred();
    const { context, page } = await session({ intercept: async ({ operation, data, state, send }) => {
      if (operation !== 'questions/detail' || data.exerciseId !== 'exercise-1') return false;
      requested.resolve(); await release.promise; await send(state.questions[0]); finished.resolve(); return true;
    } });
    try {
      await library(page); await page.locator('#library-list [data-item-id="exercise-1"]').click(); await requested.promise;
      await question(page, 'exercise-2'); release.resolve(); await finished.promise; await page.waitForTimeout(50);
      assert.equal(await page.locator('#question-identity').textContent(), 'exercise-2');
      assert.equal(await page.locator('#question-text').inputValue(), 'Public question 2.');
    } finally { release.resolve(); await context.close(); }
  });
  await check('newer-candidate-selection-wins-and-mismatched-detail-version-grants-no-actions', async () => {
    const requested = deferred(), release = deferred(), finished = deferred();
    const first = String(1).padStart(43, 'C'), second = String(2).padStart(43, 'C');
    const { context, page } = await session({ candidateCount: 2, intercept: async ({ operation, data, state, send }) => {
      if (operation !== 'candidates/detail' || data.id !== first) return false;
      requested.resolve(); await release.promise; await send(state.candidates[0]); finished.resolve(); return true;
    } });
    try {
      await cache(page); await page.locator(`#candidates-list [data-item-id="${first}"]`).click(); await requested.promise;
      await candidate(page, second); release.resolve(); await finished.promise; await page.waitForTimeout(50);
      assert.match(await page.locator('#candidate-body').textContent(), /PRIVATE_LEARNER_BODY_2/);
      assert.match(await page.locator('#candidate-identity').textContent(), new RegExp(second));
    } finally { release.resolve(); await context.close(); }
    const mismatch = await session({ candidateCount: 1, intercept: async ({ operation, state, send }) => {
      if (operation !== 'candidates/detail') return false;
      await send({ ...state.candidates[0], candidateVersion: 'e'.repeat(64) }); return true;
    } });
    try {
      await cache(mismatch.page); await mismatch.page.locator('#candidates-list .library-choice').click();
      await mismatch.page.waitForFunction(() => document.querySelector('#notice').textContent.includes('could not be read'));
      assert.equal(await mismatch.page.locator('#candidate-detail').isVisible(), false);
      assert.equal(await mismatch.page.locator('#candidate-body').textContent(), '');
      assert.equal(mismatch.state.approvalChecks, 0);
    } finally { await mismatch.context.close(); }
  });
  await check('logout-and-expired-session-clear-all-private-details-and-delayed-responses-cannot-restore-them', async () => {
    for (const mode of ['logout', 'expired']) {
      const requested = deferred(), release = deferred(), finished = deferred();
      const { context, page, state } = await session({ candidateCount: 1, intercept: async ({ operation, state, send }) => {
        if (mode !== 'logout' || operation !== 'candidates/detail') return false;
        requested.resolve(); await release.promise;
        try { await send(state.candidates[0]); } catch (error) { if (!/closed|cancel/i.test(error.message)) throw error; }
        finished.resolve(); return true;
      } });
      try {
        await cache(page);
        if (mode === 'logout') {
          await page.locator('#candidates-list .library-choice').click(); await requested.promise;
          await page.locator('#logout').click(); release.resolve(); await finished.promise;
        } else {
          await candidate(page); state.expireNext = true; await page.locator('#candidates-refresh').click();
        }
        await page.waitForFunction(() => !document.querySelector('#login-form').hidden);
        assert.equal(await page.locator('#workspace').isVisible(), false);
        for (const id of ['candidate-body','candidate-evidence','candidate-advice','candidates-list','library-list']) assert.equal(await page.locator(`#${id}`).textContent(), '');
        assert.equal(await page.locator('#question-text').inputValue(), '');
      } finally { release.resolve(); await context.close(); }
    }
  });
  await check('wrong-job-identity-is-rejected-without-updating-advice-or-auto-approval', async () => {
    const { context, page, state } = await session({ candidateCount: 1, jobMismatch: true });
    try {
      await cache(page); await candidate(page); await page.locator('#candidate-review').click();
      await page.waitForFunction(() => document.querySelector('#notice').textContent.includes('invalid completion'));
      assert.equal(state.providerCalls, 1); assert.equal(state.approvalChecks, 0);
      assert.match(await page.locator('#candidate-advice').textContent(), /Not reviewed/);
    } finally { await context.close(); }
  });
  await check('logout-during-sol-job-discards-delayed-completion-and-stops-private-followup-reads', async () => {
    const requested = deferred(), release = deferred(), finished = deferred();
    const { context, page, state, count } = await session({ candidateCount: 1, intercept: async ({ operation, state, send }) => {
      if (!operation.startsWith('drafts/')) return false;
      const { job } = state.jobs.get(operation.slice('drafts/'.length));
      requested.resolve(); await release.promise;
      await send({ ...job, state: 'completed', message: 'Late private review completion.' }); finished.resolve(); return true;
    } });
    try {
      await cache(page); await candidate(page); await page.locator('#candidate-review').click(); await requested.promise;
      const before = count('candidates/detail');
      await page.locator('#logout').click(); release.resolve(); await finished.promise;
      await page.waitForFunction(() => !document.querySelector('#login-form').hidden); await page.waitForTimeout(80);
      assert.equal(count('candidates/detail'), before);
      assert.equal(await page.locator('#candidate-body').textContent(), '');
      assert.equal(await page.locator('#candidate-advice').textContent(), '');
      assert.equal(state.approvalChecks, 0); assert.equal(await page.locator('#workspace').isVisible(), false);
    } finally { release.resolve(); await context.close(); }
  });
  await check('mobile-private-candidate-view-fits-and-retains-readable-actions', async () => {
    const { context, page, state } = await session({ candidateCount: 1, viewport: { width: 390, height: 844 } });
    try {
      state.candidates[0].title = 'A'.repeat(256);
      state.candidates[0].predicate = 'LongPredicate'.repeat(10);
      await cache(page); await candidate(page);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      for (const id of ['candidate-review','candidate-approve-open','candidate-dismiss']) {
        const bounds = await page.locator(`#${id}`).boundingBox(); assert(bounds && bounds.x >= 0 && bounds.x + bounds.width <= 390 && bounds.height >= 44);
      }
      await mkdir(path.join(root, 'build/admin-library'), { recursive: true });
      await page.screenshot({ path: path.join(root, 'build/admin-library/candidate-mobile.png'), fullPage: true });
      await page.setViewportSize({ width: 1440, height: 1000 });
      await page.screenshot({ path: path.join(root, 'build/admin-library/candidate-desktop.png'), fullPage: true });
    } finally { await context.close(); }
  });
  await check('no-browser-script-errors-or-external-requests', async () => { assert.deepEqual(errors, []); assert.deepEqual(external, []); });
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally { await browser?.close(); }

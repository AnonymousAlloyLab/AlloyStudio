import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

// Real UI, deterministic server-owned context and provider responses. No paid calls.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const artifacts = path.join(root, 'build/browser-tutor-chat');
await mkdir(artifacts, { recursive: true });
const assets = new Map(await Promise.all(['index.html', 'app.js', 'styles.css', 'instance-graph.js', 'alloy-language.js', 'tutor-chat.js']
  .map(async file => [file, await readFile(path.join(root, 'web', file))])));
const records = ['first', 'second'].map((id, index) => ({ id, title: `Graphs · inv${index + 1}`, group: 'Graphs',
  predicate: `inv${index + 1}`, description: 'Require a node with an outgoing edge.', starter: 'some Node',
  predicateHeader: `pred inv${index + 1} `, environmentBefore: 'sig Node { edge: set Node }\n', environmentAfter: '\n',
  source: { path: 'graphs.als', sha256: 'e'.repeat(64) }, contentVersion: String(index + 1).repeat(64) }));
const catalogue = [
  { id: 'openai:luna', provider: 'OpenAI', label: 'GPT-6 Luna', efforts: ['low', 'medium', 'high', 'xhigh', 'max'], defaultEffort: 'low' },
  { id: 'openai:terra', provider: 'OpenAI', label: 'GPT-6.1 Terra', efforts: ['low', 'high'], defaultEffort: 'low' },
  { id: 'openai:sol', provider: 'OpenAI', label: 'GPT-6.1 Sol', efforts: ['low', 'high'], defaultEffort: 'high' },
  { id: 'anthropic:haiku', provider: 'Claude', label: 'Haiku 5.5', efforts: ['none'], defaultEffort: 'none' },
  { id: 'anthropic:sonnet', provider: 'Claude', label: 'Sonnet 5.5', efforts: ['low', 'high'], defaultEffort: 'low' },
  { id: 'anthropic:opus', provider: 'Claude', label: 'Opus 5.5', efforts: ['low', 'high'], defaultEffort: 'high' },
  { id: 'gemini:flash', provider: 'Gemini', label: 'Gemini 3.8 Flash', efforts: ['low', 'high'], defaultEffort: 'low' },
  { id: 'gemini:argon', provider: 'Gemini', label: 'Gemini 4 Argon', efforts: ['low', 'high'], defaultEffort: 'high' },
];
const instance = { traceLength: 1, loopState: -1, states: [{ index: 0,
  signatures: [{ label: 'Node', atoms: ['Node$0'] }], relations: [{ label: 'edge', arity: 2, tuples: [] }] }] };
const identity = payload => ({ exerciseId: payload.exerciseId, revision: payload.revision, metric: payload.metric,
  contentVersion: records.find(record => record.id === payload.exerciseId).contentVersion });
const feedback = payload => ({ ...identity(payload), status: 'ok', requestedMetric: payload.metric,
  metric: payload.metric === 'ast' ? 'acgn-raw-ast-zhang-shasha-distance' : 'acgn-fast-rewrite-canonical-distance',
  distance: 1, evidenceToken: 'a'.repeat(64), canonicalForm: ['some Node'],
  breakdown: { temporal: 0, quantifier: 0, matrix: 1, ast: 1 }, operations: [
    { kind: 'replace', component: 'matrix', path: 'phase[0].matrix', cost: 1, action: 'Review the operator', sourceOperator: 'some', replacementOperator: 'no' },
  ], trace: { cost: 1, matchesDistance: true, hasAggregates: false } });
const behavior = payload => ({ ...identity(payload), status: 'ok', metric: 'acgn-reward', behaviorToken: 'b'.repeat(64),
  score: 0.5, scoreStatus: 'ok', scoreReason: 'OK',
  scope: { overall: 3, bitwidth: 3, maxSequence: 3, poolSize: 4, minTrace: 1, maxTrace: 1, moduleFacts: true },
  sampling: { positiveTested: 4, positiveAccepted: 2, negativeTested: 4, negativeRejected: 2, semanticCounterexamples: 2 },
  categories: [['both', true, true], ['undercoverage', true, false], ['overcoverage', false, true], ['neither', false, false]]
    .map(([id, oracle, student]) => ({ id, oracle, student, status: 'sat', enumerationComplete: true, instances: [instance] })) });
const passed = [], errors = [], external = [];
let browser;
async function check(name, fn) { try { await fn(); passed.push(name); } catch (error) { error.message = `${name}: ${error.message}`; throw error; } }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function session({ basePath = '/', disabled = false, mismatch = false, preference, storageUnavailable = false, suggestionError = false } = {}) {
  const origin = 'https://alloy.example';
  const calls = [], state = { replyGate: null, suggestionGate: null, mismatch, disabled, suggestionError };
  let channelNumber = 0;
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(({ preference, storageUnavailable }) => {
    localStorage.setItem('alloy-studio:v1:live', 'false');
    if (preference !== undefined) localStorage.setItem('alloy-studio:v1:tutor-profile', preference);
    if (storageUnavailable) {
      Storage.prototype.getItem = () => { throw new DOMException('Unavailable', 'SecurityError'); };
      Storage.prototype.setItem = () => { throw new DOMException('Unavailable', 'SecurityError'); };
    }
  }, { preference, storageUnavailable });
  await context.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.origin !== origin) { external.push(url.href); return route.abort(); }
    const resource = '/' + url.pathname.slice(basePath.length);
    const payload = route.request().method() === 'POST' ? route.request().postDataJSON() : null;
    if (resource.startsWith('/api/')) calls.push({ resource, payload });
    const send = async data => { try { await route.fulfill({ json: data }); } catch (error) { if (!/closed|aborted|canceled|cancelled/i.test(error.message)) throw error; } };
    if (resource === '/api/exercises') return send({ exercises: records });
    if (resource.startsWith('/api/exercises/')) return send(records.find(record => record.id === resource.split('/').at(-1)));
    if (resource === '/api/channel') return send({ status: 'ok', channel: String.fromCharCode(99 + channelNumber++).repeat(43) });
    if (resource === '/api/cancel') return send({ status: 'ok' });
    if (resource === '/api/feedback') {
      if (state.expireFeedbackOnce) { state.expireFeedbackOnce = false; return route.fulfill({ status: 410, json: { status: 'expired', code: 'channel_expired', ...identity(payload), requestedMetric: payload.metric } }); }
      return send(feedback(payload));
    }
    if (resource === '/api/behavior') return send(behavior(payload));
    if (resource === '/api/explain') return send({ ...identity(payload), requestedMetric: payload.metric, status: 'unavailable' });
    if (resource === '/api/chat/models') return send(state.disabled
      ? { status: 'disabled', message: 'The administrator has not enabled AI chat.' }
      : { status: 'ok', models: catalogue, defaultModel: 'openai:luna' });
    if (resource === '/api/chat/suggestions') {
      state.suggestionStarted?.resolve();
      if (state.suggestionGate) await state.suggestionGate.promise;
      if (state.suggestionError) return route.fulfill({ status: 502, json: { status: 'error', message: 'Tailored questions unavailable.' } });
      return send({ status: 'ok', ...identity(payload), model: 'openai:luna', suggestions: state.suggestions || [
        'What does the first hint mean?', 'What does this counterexample show?', 'How can I test my reasoning?', 'Fourth question is omitted',
      ] });
    }
    if (resource === '/api/chat') {
      state.replyStarted?.resolve();
      if (state.replyGate) await state.replyGate.promise;
      if (state.expireOnce) { state.expireOnce = false; return route.fulfill({ status: 410, json: { status: 'expired', code: 'channel_expired', ...identity(payload) } }); }
      if (state.providerError) return route.fulfill({ status: 502, json: { status: 'error', message: 'Injected provider failure.' } });
      return send({ status: 'ok', ...identity(payload), revision: payload.revision + (state.mismatch ? 1 : 0),
        model: payload.model, effort: payload.effort, conversationToken: 't'.repeat(43), historyTruncated: !!state.truncated,
        historyCompressed: payload.resumeConversation === true,
        reply: state.replyText || 'Consider a node with no outgoing edges. <img src=x onerror="window.injected=true"> Which part of your predicate rules it out?' });
    }
    const file = resource === '/' ? 'index.html' : resource.slice(1), bytes = assets.get(file);
    if (bytes) return route.fulfill({ body: bytes, contentType: file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html' });
    errors.push(`Unregistered resource ${resource}`); return route.abort();
  });
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
  await page.goto(origin + basePath);
  await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
  return { page, context, calls, state };
}
const waitSuggestions = page => page.waitForFunction(() => document.querySelectorAll('.tutor-suggestion:not([data-starter])').length === 3 && !document.querySelector('#tutor-suggest').disabled);
const chatCalls = calls => calls.filter(call => call.resource.startsWith('/api/chat'));
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const { page, context, calls, state } = await session();
  const editor = page.locator('#predicate-editor');
  await check('tutor-folded-by-default-and-no-provider-request-until-open', async () => {
    assert.equal(await page.locator('#tutor-panel').isVisible(), false);
    assert.equal(await page.locator('#tutor-toggle').getAttribute('aria-expanded'), 'false');
    assert.equal(chatCalls(calls).length, 0);
    await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
    assert.equal(await page.locator('#tutor-panel').isVisible(), true);
    assert.equal(await page.locator('#tutor-toggle').getAttribute('aria-expanded'), 'true');
    assert.equal(await page.locator('#tutor-model').inputValue(), 'openai:luna');
    assert.equal(await page.locator('#tutor-effort').inputValue(), 'low');
    assert.equal(await page.locator('#tutor-model option').count(), 8);
    assert.equal(await page.locator('.tutor-suggestion:not([data-starter])').count(), 3);
    assert.equal(chatCalls(calls).filter(call => call.resource === '/api/chat/suggestions').length, 1);
  });
  await check('tutor-suggestion-click-sends-only-current-draft-and-pins-not-history', async () => {
    await page.locator('.tutor-suggestion:not([data-starter])').first().click();
    await page.locator('.tutor-assistant').last().waitFor(); await waitSuggestions(page);
    const first = calls.find(call => call.resource === '/api/chat').payload;
    assert.deepEqual(Object.keys(first).sort(), ['body', 'channel', 'effort', 'exerciseId', 'message', 'metric', 'model', 'prerequisiteTokens', 'revision'].sort());
    assert.equal(first.message, 'What does the first hint mean?');
    assert.equal(first.body, 'some Node'); assert.equal(first.metric, 'canonical');
    assert.equal(first.channel, 'c'.repeat(43)); assert.equal(first.model, 'openai:luna');
    assert.equal(first.evidenceToken, undefined); assert.equal(first.behaviorToken, undefined);
    assert.equal(await page.locator('#tutor-messages img').count(), 0);
    assert.match(await page.locator('.tutor-assistant').textContent(), /<img src=x/);
    assert.equal(await page.evaluate(() => window.injected), undefined);
    await page.locator('#tutor-message').fill('Explain the idea.');
    await page.locator('#tutor-message').press('Enter');
    await page.waitForFunction(() => document.querySelectorAll('.tutor-assistant').length === 2);
    await waitSuggestions(page);
    assert.equal(calls.filter(call => call.resource === '/api/chat').at(-1).payload.conversationToken, 't'.repeat(43));
    assert.equal(calls.filter(call => call.resource === '/api/chat').at(-1).payload.history, undefined);
  });
  await check('tutor-check-attaches-exact-evidence-and-behavior-tokens', async () => {
    await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelectorAll('#tutor-context-badges li.available').length === 4);
    assert.equal(await page.locator('.tutor-assistant').count(), 2);
    assert.equal(await page.locator('.tutor-assistant.tutor-earlier').count(), 2);
    await page.locator('#tutor-message').fill('Help me read the counterexample.');
    await page.locator('#tutor-send').click(); await page.locator('.tutor-assistant').last().waitFor(); await waitSuggestions(page);
    const latest = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
    assert.equal(latest.evidenceToken, 'a'.repeat(64)); assert.equal(latest.behaviorToken, 'b'.repeat(64));
    assert.equal(latest.conversationToken, 't'.repeat(43));
    await page.screenshot({ path: path.join(artifacts, 'desktop.png') });
  });
  await check('tutor-composer-integrates-accessible-model-and-reasoning-menus', async () => {
    for (const id of ['tutor-message', 'tutor-model', 'tutor-effort', 'tutor-send']) {
      assert.equal(await page.locator(`.tutor-input-frame #${id}`).count(), 1);
    }
    assert.equal(await page.getByLabel('Model', { exact: true }).getAttribute('id'), 'tutor-model');
    assert.equal(await page.getByLabel('Reasoning', { exact: true }).getAttribute('id'), 'tutor-effort');
    await page.locator('#tutor-message').focus(); await page.keyboard.press('Tab');
    assert.equal(await page.locator('#tutor-model').evaluate(element => element === document.activeElement), true);
    await page.keyboard.press('Tab');
    assert.equal(await page.locator('#tutor-effort').evaluate(element => element === document.activeElement), true);
  });
  await check('tutor-docked-panel-keeps-repair-trace-reachable-without-overlap', async () => {
    const before = chatCalls(calls).length;
    for (const width of [1920, 1440, 1350, 1349, 1280, 1200, 1199, 1024, 800, 731]) {
      await page.setViewportSize({ width, height: 1000 });
      const drawer = await page.locator('#tutor-panel').boundingBox();
      const editorBox = await page.locator('.editor-card').boundingBox();
      const feedbackBox = await page.locator('.feedback-card').boundingBox();
      assert(editorBox && feedbackBox && drawer, `missing panel at ${width}`);
      assert(feedbackBox.x + feedbackBox.width <= drawer.x + 1, `trace under chat at ${width}`);
      assert(editorBox.x + editorBox.width <= drawer.x + 1, `editor under chat at ${width}`);
      if (width >= 1200) {
        assert(editorBox.x + editorBox.width <= feedbackBox.x, `columns overlap at ${width}`);
        assert(Math.abs(editorBox.y - feedbackBox.y) <= 1, `trace moved below editor at ${width}`);
      }
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `page overflow at ${width}`);
      assert.equal(await page.locator('.app-shell').evaluate(element => element.inert), false);
      await page.locator('.operation-item').first().scrollIntoViewIfNeeded();
      assert.equal(await page.locator('.operation-item').first().evaluate(element => {
        const box = element.getBoundingClientRect();
        return element.contains(document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2));
      }), true, `trace cannot be reached at ${width}`);
      const frame = await page.locator('.tutor-input-frame').boundingBox();
      for (const id of ['tutor-message', 'tutor-model', 'tutor-effort', 'tutor-send']) {
        const box = await page.locator(`#${id}`).boundingBox();
        assert(box.x >= frame.x && box.x + box.width <= frame.x + frame.width + 1, `${id} exceeds frame at ${width}`);
        assert(box.y >= 0 && box.y + box.height <= 1001, `${id} outside viewport at ${width}`);
      }
    }
    assert.equal(chatCalls(calls).length, before);
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({ path: path.join(artifacts, 'desktop.png') });
  });
  await check('tutor-edit-preserves-conversation-and-retrieves-current-draft-without-keystroke-calls', async () => {
    const before = chatCalls(calls).length;
    const previousAnswers = await page.locator('.tutor-assistant').count();
    await editor.fill('no Node');
    assert.equal(await page.locator('.tutor-assistant').count(), previousAnswers);
    assert.equal(await page.locator('.tutor-assistant.tutor-earlier').count(), previousAnswers);
    assert.equal(await page.locator('.tutor-suggestion:not([data-starter])').count(), 0);
    assert.equal(await page.locator('#tutor-context-badges li.available').count(), 2);
    assert.equal(chatCalls(calls).length, before);
    assert.match(await page.locator('#tutor-status').textContent(), /conversation is kept/);
    await page.locator('#tutor-message').fill('What should I examine?');
    await page.locator('#tutor-send').click(); await page.locator('.tutor-assistant').last().waitFor(); await waitSuggestions(page);
    const latest = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
    assert.equal(latest.body, 'no Node'); assert.equal(latest.evidenceToken, undefined);
    assert.equal(latest.behaviorToken, undefined); assert.equal(latest.conversationToken, 't'.repeat(43));
  });
  await check('tutor-model-provider-and-effort-switches-preserve-conversation-and-unsent-message', async () => {
    const previousAnswers = await page.locator('.tutor-assistant').count();
    const before = chatCalls(calls).length;
    await page.locator('#tutor-message').fill('Review my approach.');
    await page.locator('#tutor-model').selectOption('anthropic:haiku');
    assert.equal(await page.locator('#tutor-effort').isDisabled(), true);
    assert.equal(await page.locator('#tutor-effort').inputValue(), 'none');
    assert.equal(await page.locator('.tutor-assistant').count(), previousAnswers);
    assert.equal(await page.locator('#tutor-message').inputValue(), 'Review my approach.');
    await page.locator('#tutor-model').selectOption('openai:sol');
    await page.locator('#tutor-effort').selectOption('high');
    assert.equal(chatCalls(calls).length, before);
    assert.equal(await page.locator('.tutor-assistant').count(), previousAnswers);
    assert.match(await page.locator('#tutor-status').textContent(), /Conversation kept.*GPT-6.1 Sol/);
    await page.locator('#tutor-send').click(); await waitSuggestions(page);
    const latest = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
    assert.equal(latest.model, 'openai:sol'); assert.equal(latest.effort, 'high');
    assert.equal(latest.conversationToken, 't'.repeat(43));
    assert.match(await page.locator('.tutor-assistant .tutor-speaker').last().textContent(), /GPT-6.1 Sol.*high/);
    const beforeEffort = chatCalls(calls).length;
    await page.locator('#tutor-message').fill('Why does that matter?');
    await page.locator('#tutor-effort').selectOption('low');
    assert.equal(chatCalls(calls).length, beforeEffort);
    assert.equal(await page.locator('#tutor-message').inputValue(), 'Why does that matter?');
    await page.locator('#tutor-send').click(); await waitSuggestions(page);
    const changed = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
    assert.equal(changed.effort, 'low'); assert.equal(changed.conversationToken, 't'.repeat(43));
    assert.equal(await page.locator('.tutor-assistant').count(), previousAnswers + 2);
    await page.locator('#tutor-model').selectOption('gemini:argon');
    assert.equal(await page.locator('#tutor-effort').inputValue(), 'low');
  });
  await check('tutor-model-switch-drains-old-suggestions-without-reset-or-extra-dispatch', async () => {
    state.suggestionGate = deferred();
    const dispatched = page.waitForRequest(request => request.url().endsWith('/api/chat/suggestions'));
    await page.locator('#tutor-suggest').click();
    await dispatched;
    await page.waitForFunction(() => document.querySelector('#tutor-suggestion-note').textContent.includes('Preparing'));
    const before = chatCalls(calls).length;
    const previousAnswers = await page.locator('.tutor-assistant').count();
    await page.locator('#tutor-message').fill('Continue with this tutor.');
    await page.locator('#tutor-model').selectOption('anthropic:haiku');
    assert.equal(await page.locator('#tutor-send').isDisabled(), true);
    assert.equal(await page.locator('#tutor-message').isDisabled(), false);
    assert.equal(await page.locator('.tutor-assistant').count(), previousAnswers);
    state.suggestionGate.resolve(); state.suggestionGate = null;
    await page.waitForFunction(() => !document.querySelector('#tutor-send').disabled);
    assert.equal(await page.locator('.tutor-suggestion:not([data-starter])').count(), 0);
    assert.equal(chatCalls(calls).length, before);
    assert.match(await page.locator('#tutor-suggestion-note').textContent(), /selected model/);
    await page.locator('#tutor-send').click(); await waitSuggestions(page);
    const latest = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
    assert.equal(latest.model, 'anthropic:haiku'); assert.equal(latest.conversationToken, 't'.repeat(43));
    assert.match(await page.locator('.tutor-assistant .tutor-speaker').last().textContent(), /Haiku 5.5/);
  });
  await check('tutor-late-response-after-edit-is-discarded', async () => {
    const previousAnswers = await page.locator('.tutor-assistant').count();
    const previousQuestions = await page.locator('.tutor-user').count();
    state.replyGate = deferred();
    await page.locator('#tutor-message').fill('Delayed response.'); await page.locator('#tutor-send').click();
    await page.waitForFunction(() => document.querySelector('#tutor-messages').getAttribute('aria-busy') === 'true');
    await editor.fill('some Node // new draft');
    state.replyGate.resolve(); state.replyGate = null;
    await page.waitForFunction(() => !document.querySelector('#tutor-messages').hasAttribute('aria-busy'));
    assert.equal(await page.locator('.tutor-assistant').count(), previousAnswers);
    assert.equal(await page.locator('.tutor-user').count(), previousQuestions + 1);
    assert.equal(await page.locator('.tutor-user.tutor-earlier').count(), previousQuestions + 1);
    assert.equal(await page.locator('#tutor-message').inputValue(), '');
  });
  await check('tutor-close-cancels-request-escape-returns-focus-and-reopens', async () => {
    const previousAnswers = await page.locator('.tutor-assistant').count();
    state.replyGate = deferred();
    await page.locator('#tutor-message').fill('Another delayed response.'); await page.locator('#tutor-send').click();
    await page.locator('#tutor-message').press('Escape');
    assert.equal(await page.locator('#tutor-panel').isVisible(), false);
    assert.equal(await page.locator('#tutor-toggle').evaluate(element => element === document.activeElement), true);
    state.replyGate.resolve(); state.replyGate = null;
    await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
    assert.equal(await page.locator('.tutor-assistant').count(), previousAnswers);
    await page.locator('#tutor-message').fill('First line'); await page.locator('#tutor-message').press('Shift+Enter');
    assert.equal(await page.locator('#tutor-message').inputValue(), 'First line\n');
  });
  await check('tutor-reopened-suggestions-remain-clickable', async () => {
    const suggestionsBefore = calls.filter(call => call.resource === '/api/chat/suggestions').length;
    await page.locator('#tutor-close').click();
    await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
    assert.equal(calls.filter(call => call.resource === '/api/chat/suggestions').length, suggestionsBefore);
    const before = calls.filter(call => call.resource === '/api/chat').length;
    await page.locator('.tutor-suggestion:not([data-starter])').first().click();
    await page.locator('.tutor-assistant').last().waitFor(); await waitSuggestions(page);
    assert.equal(calls.filter(call => call.resource === '/api/chat').length, before + 1);
  });
  await check('tutor-folding-does-not-auto-retry-an-interrupted-suggestion-call', async () => {
    state.suggestionGate = deferred(); state.suggestionStarted = deferred();
    await page.locator('#tutor-suggest').click(); await state.suggestionStarted.promise;
    const count = calls.filter(call => call.resource === '/api/chat/suggestions').length;
    await page.locator('#tutor-close').click(); state.suggestionGate.resolve();
    state.suggestionGate = null; state.suggestionStarted = null;
    await page.locator('#tutor-toggle').click();
    assert.equal(calls.filter(call => call.resource === '/api/chat/suggestions').length, count);
    assert.match(await page.locator('#tutor-suggestion-note').textContent(), /interrupted/);
    await page.locator('#tutor-suggest').click(); await waitSuggestions(page);
    assert.equal(calls.filter(call => call.resource === '/api/chat/suggestions').length, count + 1);
  });
  await check('tutor-send-waits-for-suggestions-without-aborting-or-queuing-a-chat', async () => {
    state.suggestionGate = deferred(); state.suggestionStarted = deferred();
    await page.locator('#tutor-suggest').click(); await state.suggestionStarted.promise;
    const chatsBefore = calls.filter(call => call.resource === '/api/chat').length;
    await page.locator('#tutor-message').fill('My question while suggestions are loading.');
    assert.equal(await page.locator('#tutor-message').isDisabled(), false);
    assert.equal(await page.locator('#tutor-send').isDisabled(), true);
    await page.locator('#tutor-message').press('Enter');
    assert.equal(calls.filter(call => call.resource === '/api/chat').length, chatsBefore);
    assert.equal(await page.locator('#tutor-message').inputValue(), 'My question while suggestions are loading.');
    assert.match(await page.locator('#tutor-suggestion-note').textContent(), /Preparing/);
    state.suggestionGate.resolve(); state.suggestionGate = null; state.suggestionStarted = null;
    await waitSuggestions(page);
    assert.equal(await page.locator('#tutor-send').isDisabled(), false);
    assert.equal(calls.filter(call => call.resource === '/api/chat').length, chatsBefore);
    assert.doesNotMatch(await page.locator('#tutor-suggestion-note').textContent(), /Preparing/);
    await page.locator('#tutor-send').click(); await waitSuggestions(page);
    assert.equal(calls.filter(call => call.resource === '/api/chat').length, chatsBefore + 1);
    assert.equal(calls.filter(call => call.resource === '/api/chat').at(-1).payload.message, 'My question while suggestions are loading.');
  });
  await check('tutor-message-limit-is-utf8-bytes-and-preserves-overlong-input', async () => {
    const before = chatCalls(calls).length;
    const message = '讨论'.repeat(500);
    await page.locator('#tutor-message').fill(message);
    assert.equal(await page.locator('#tutor-send').isDisabled(), true);
    assert.match(await page.locator('#tutor-message-limit').textContent(), /2,048 UTF-8 bytes/);
    await page.locator('#tutor-message').press('Enter');
    assert.equal(chatCalls(calls).length, before);
    assert.equal(await page.locator('#tutor-message').inputValue(), message);
    await page.locator('#tutor-message').fill('A short question.');
    assert.equal(await page.locator('#tutor-message-limit').isVisible(), false);
    assert.equal(await page.locator('#tutor-send').isDisabled(), false);
  });
  await check('tutor-history-truncation-is-disclosed-and-new-chat-clears-it', async () => {
    state.truncated = true;
    await page.locator('#tutor-message').fill('Help me reason about the next case.'); await page.locator('#tutor-send').click();
    await page.waitForFunction(() => !document.querySelector('#tutor-history-note').hidden); await waitSuggestions(page);
    assert.match(await page.locator('#tutor-history-note').textContent(), /latest six exchanges/);
    await page.locator('#tutor-new').click();
    assert.equal(await page.locator('#tutor-history-note').isVisible(), false); state.truncated = false;
  });
  await check('tutor-expired-channel-can-recover-on-next-explicit-send', async () => {
    await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelectorAll('#tutor-context-badges li.available').length === 4);
    state.expireOnce = true;
    const channelCount = calls.filter(call => call.resource === '/api/channel').length;
    await page.locator('#tutor-message').fill('Use my current check.'); await page.locator('#tutor-send').click();
    await page.waitForFunction(() => document.querySelector('#tutor-status').textContent.includes('session expired'));
    assert.equal(await page.locator('#tutor-message').inputValue(), 'Use my current check.');
    assert.equal(await page.locator('#tutor-context-badges li.available').count(), 2);
    await page.locator('#tutor-send').click(); await page.locator('.tutor-assistant').last().waitFor(); await waitSuggestions(page);
    assert.equal(calls.filter(call => call.resource === '/api/channel').length, channelCount + 1);
    const latest = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
    assert.equal(latest.evidenceToken, undefined); assert.equal(latest.behaviorToken, undefined);
    assert.equal(latest.conversationToken, undefined);
  });
  await check('tutor-new-editor-channel-starts-a-fresh-session-with-current-question', async () => {
    const before = calls.filter(call => call.resource === '/api/channel').length;
    state.expireFeedbackOnce = true;
    await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelector('#feedback-state').textContent !== 'Checking…');
    await page.locator('#tutor-message').fill('Continue with this current draft.'); await page.locator('#tutor-send').click();
    await waitSuggestions(page);
    const latest = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
    assert.equal(calls.filter(call => call.resource === '/api/channel').length, before + 1);
    assert.equal(latest.conversationToken, undefined);
    assert.equal(latest.message, 'Continue with this current draft.');
    assert.equal(await page.locator('.tutor-user').count(), 1);
    assert.equal(await page.locator('.tutor-assistant').count(), 1);
  });
  await check('tutor-metric-refresh-preserves-history-but-exercise-switch-resets-it', async () => {
    const previousQuestions = await page.locator('.tutor-user').count();
    await page.locator('#distance-metric').selectOption('ast');
    await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked');
    assert.equal(await page.locator('.tutor-user').count(), previousQuestions);
    assert.equal(await page.locator('.tutor-user.tutor-earlier').count(), previousQuestions);
    await page.locator('#tutor-message').fill('Now explain the AST comparison.'); await page.locator('#tutor-send').click();
    await waitSuggestions(page);
    assert.equal(calls.filter(call => call.resource === '/api/chat').at(-1).payload.metric, 'ast');
    assert.equal(calls.filter(call => call.resource === '/api/chat').at(-1).payload.conversationToken, 't'.repeat(43));
    await page.locator('[data-exercise-id="second"]').click();
    await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled && location.search.includes('second'));
    assert.match(await page.locator('#tutor-exercise').textContent(), /inv2/);
    assert.equal(await page.locator('.tutor-assistant').count(), 0);
    await page.locator('#tutor-message').fill('Explain this new question.'); await page.locator('#tutor-send').click();
    await page.locator('.tutor-assistant').last().waitFor(); await waitSuggestions(page);
    const latest = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
    assert.equal(latest.exerciseId, 'second'); assert.equal(latest.metric, 'ast');
    assert.equal(latest.evidenceToken, undefined); assert.equal(latest.conversationToken, undefined);
  });
  await check('tutor-new-chat-clears-server-history-without-an-analysis-call', async () => {
    const before = chatCalls(calls).length;
    await page.locator('#tutor-new').click();
    assert.equal(await page.locator('.tutor-assistant').count(), 0);
    assert.equal(await page.locator('.tutor-user').count(), 0);
    assert.equal(chatCalls(calls).length, before);
    await page.locator('#tutor-message').fill('Start again from the question.'); await page.locator('#tutor-send').click();
    await page.locator('.tutor-assistant').last().waitFor(); await waitSuggestions(page);
    assert.equal(calls.filter(call => call.resource === '/api/chat').at(-1).payload.conversationToken, undefined);
  });
  await check('tutor-mobile-panel-is-contained-and-controls-remain-visible', async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.waitForFunction(() => document.querySelector('#tutor-panel').getAttribute('aria-modal') === 'true');
    assert.equal(await page.locator('#tutor-panel').getAttribute('aria-modal'), 'true');
    assert.equal(await page.locator('.app-shell').evaluate(element => element.inert), true);
    await page.locator('#tutor-history').focus(); await page.keyboard.press('Shift+Tab');
    assert.equal(await page.locator('#tutor-send').evaluate(element => element === document.activeElement), false);
    assert.equal(await page.locator('#tutor-effort').isDisabled(), true);
    assert.equal(await page.locator('#tutor-model').evaluate(element => element === document.activeElement), true);
    await page.locator('#tutor-model').selectOption('openai:luna');
    await page.locator('#tutor-history').focus(); await page.keyboard.press('Shift+Tab');
    assert.equal(await page.locator('#tutor-effort').evaluate(element => element === document.activeElement), true);
    for (const id of ['tutor-panel', 'tutor-close', 'tutor-message', 'tutor-model', 'tutor-effort', 'tutor-send']) {
      const box = await page.locator(`#${id}`).boundingBox();
      assert(box && box.x >= 0 && box.y >= 0 && box.x + box.width <= 391 && box.y + box.height <= 845, id);
    }
    await page.screenshot({ path: path.join(artifacts, 'mobile.png') });
  });
  await context.close();
  await check('tutor-subpath-deployment-keeps-all-chat-requests-under-base', async () => {
    const scoped = await session({ basePath: '/alloy/' });
    await scoped.page.locator('#tutor-toggle').click(); await waitSuggestions(scoped.page);
    assert(scoped.calls.some(call => call.resource === '/api/chat/models'));
    await scoped.page.locator('.tutor-suggestion:not([data-starter])').first().click(); await scoped.page.locator('.tutor-assistant').last().waitFor();
    await scoped.context.close();
  });
  await check('tutor-unconfigured-provider-is-explained-without-key-input', async () => {
    const disabled = await session({ disabled: true });
    await disabled.page.locator('#tutor-toggle').click();
    await disabled.page.waitForFunction(() => document.querySelector('#tutor-availability').textContent.includes('not enabled'));
    assert.equal(await disabled.page.locator('#tutor-message').isDisabled(), true);
    assert.equal(await disabled.page.locator('#tutor-panel input').count(), 0);
    assert.equal(disabled.calls.filter(call => call.resource === '/api/chat/suggestions').length, 0);
    await disabled.context.close();
  });
  await check('tutor-mismatched-response-never-enters-conversation', async () => {
    const bad = await session({ mismatch: true });
    await bad.page.locator('#tutor-toggle').click(); await waitSuggestions(bad.page);
    await bad.page.locator('#tutor-message').fill('Check my reasoning.'); await bad.page.locator('#tutor-send').click();
    await bad.page.waitForFunction(() => document.querySelector('#tutor-status').textContent.includes('could not be matched'));
    assert.equal(await bad.page.locator('.tutor-assistant').count(), 0);
    assert.equal(await bad.page.locator('#tutor-message').inputValue(), 'Check my reasoning.');
    await bad.context.close();
  });
  await check('tutor-delayed-replies-preserve-composer-and-recover-without-auto-retry', async () => {
    for (const outcome of ['provider-error', 'mismatch', 'expired', 'success']) {
      for (const draft of ['', '  Which part of my follow-up matters?\n', ' \n']) {
        const trial = await session();
        const { page, calls, state } = trial;
        const composer = page.locator('#tutor-message');
        const replies = () => calls.filter(call => call.resource === '/api/chat');
        try {
          await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
          // Establish both server history and exact-draft evidence before failure.
          await composer.fill('Earlier successful question.');
          await page.locator('#tutor-send').click(); await waitSuggestions(page);
          await page.locator('#check-button').click();
          await page.waitForFunction(() => document.querySelectorAll('#tutor-context-badges li.available').length === 4);
          const before = replies().length;
          const channelsBefore = calls.filter(call => call.resource === '/api/channel').length;
          const suggestionsBefore = calls.filter(call => call.resource === '/api/chat/suggestions').length;
          state.replyGate = deferred(); state.replyStarted = deferred();
          state.providerError = outcome === 'provider-error';
          state.mismatch = outcome === 'mismatch'; state.expireOnce = outcome === 'expired';
          const question = 'Explain the first hint.';
          await composer.fill(question); await page.locator('#tutor-send').click();
          await state.replyStarted.promise;
          assert.equal(await composer.isDisabled(), false);
          assert.equal(await composer.inputValue(), '');
          assert.equal(await page.locator('#tutor-send').isDisabled(), true);
          await composer.fill(draft); await composer.press('Enter');
          assert.equal(replies().length, before + 1);
          assert.equal(await composer.inputValue(), draft);
          state.replyGate.resolve(); state.replyGate = null;
          await page.waitForFunction(() => !document.querySelector('#tutor-messages').hasAttribute('aria-busy'));
          if (outcome === 'success') await waitSuggestions(page);
          else {
            const expected = outcome === 'provider-error' ? /Injected provider failure/ :
              outcome === 'mismatch' ? /could not be matched/ : /session expired/;
            assert.match(await page.locator('#tutor-status').textContent(), expected);
            if (draft) assert.match(await page.locator('#tutor-status').textContent(), /unanswered question is kept above/);
          }
          assert.equal(await composer.inputValue(), outcome === 'success' ? draft : draft || question);
          assert.equal(await page.locator('.tutor-user p').last().textContent(), question);
          assert.equal(await page.locator('.tutor-assistant').count(), outcome === 'expired' ? 0 : outcome === 'success' ? 2 : 1);
          assert.equal(replies().length, before + 1); // No automatic retry or queued Enter.
          assert.equal(calls.filter(call => call.resource === '/api/channel').length, channelsBefore);
          assert.equal(calls.filter(call => call.resource === '/api/chat/suggestions').length,
            suggestionsBefore + (outcome === 'success' ? 1 : 0));
          if (outcome === 'expired') assert.equal(await page.locator('#tutor-context-badges li.available').count(), 2);
          // The original is recoverable even when expiration discarded old history.
          // Copy it back explicitly; it must not import transcript text as history.
          state.providerError = false; state.mismatch = false;
          await composer.fill(await page.locator('.tutor-user p').last().textContent());
          await page.locator('#tutor-send').click(); await waitSuggestions(page);
          assert.equal(replies().length, before + 2);
          const latest = replies().at(-1).payload;
          assert.equal(latest.message, question);
          assert.equal(latest.history, undefined);
          assert.equal(latest.conversationToken, outcome === 'expired' ? undefined : 't'.repeat(43));
          assert.equal(latest.evidenceToken, outcome === 'expired' ? undefined : 'a'.repeat(64));
          assert.equal(latest.behaviorToken, outcome === 'expired' ? undefined : 'b'.repeat(64));
          assert.equal(calls.filter(call => call.resource === '/api/channel').length,
            channelsBefore + (outcome === 'expired' ? 1 : 0));
        } catch (error) {
          error.message = `${outcome}/${draft === '' ? 'unchanged' : draft.trim() ? 'new-draft' : 'whitespace'}: ${error.message}`;
          throw error;
        } finally { state.replyGate?.resolve(); await trial.context.close(); }
      }
    }
  });
  await check('tutor-model-preference-is-explicit-validated-and-optional', async () => {
    const trial = await session();
    try {
      const { page } = trial;
      await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
      assert.equal(await page.evaluate(() => localStorage.getItem('alloy-studio:v1:tutor-profile')), null);
      const before = chatCalls(trial.calls).length;
      await page.locator('#tutor-model').selectOption('openai:sol');
      await page.locator('#tutor-effort').selectOption('high');
      assert.equal(chatCalls(trial.calls).length, before);
      assert.deepEqual(await page.evaluate(() => JSON.parse(localStorage.getItem('alloy-studio:v1:tutor-profile'))),
        { model: 'openai:sol', effort: 'high' });
      assert.match(await page.locator('#tutor-availability').textContent(), /may take longer.*conversation and unsent message/);
      await page.reload(); await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
      assert.equal(await page.locator('#tutor-model').inputValue(), 'openai:sol');
      assert.equal(await page.locator('#tutor-effort').inputValue(), 'high');
    } finally { await trial.context.close(); }
    for (const preference of ['invalid JSON', 'null', '[]', JSON.stringify({ model: 'removed', effort: 'high' }),
      JSON.stringify({ model: 'openai:sol', effort: 'unknown' }),
      JSON.stringify({ model: 'openai:sol', effort: 'high', conversationToken: 'PRIVATE_UNUSED_TOKEN' })]) {
      const trial = await session({ preference });
      try {
        await trial.page.locator('#tutor-toggle').click(); await waitSuggestions(trial.page);
        assert.equal(await trial.page.locator('#tutor-model').inputValue(), 'openai:luna');
        assert.equal(await trial.page.locator('#tutor-effort').inputValue(), 'low');
        assert.equal(JSON.stringify(trial.calls).includes('PRIVATE_UNUSED_TOKEN'), false);
      } finally { await trial.context.close(); }
    }
    const unavailable = await session({ storageUnavailable: true });
    try {
      await unavailable.page.locator('#tutor-toggle').click(); await waitSuggestions(unavailable.page);
      await unavailable.page.locator('#tutor-model').selectOption('openai:sol');
      await unavailable.page.locator('#tutor-message').fill('Help with my approach.');
      await unavailable.page.locator('#tutor-send').click(); await waitSuggestions(unavailable.page);
      assert.equal(unavailable.calls.filter(call => call.resource === '/api/chat').at(-1).payload.model, 'openai:sol');
    } finally { await unavailable.context.close(); }
  });
  await check('tutor-starting-points-work-without-ai-questions-and-never-overwrite-or-send', async () => {
    const trial = await session({ suggestionError: true });
    const { page, calls, state } = trial;
    try {
      state.suggestionGate = deferred(); state.suggestionStarted = deferred();
      await page.locator('#tutor-toggle').click(); await state.suggestionStarted.promise;
      const before = chatCalls(calls).length;
      assert.equal(await page.locator('[data-starter]').count(), 3);
      assert.match(await page.locator('#tutor-suggestion-note').textContent(), /Starting points.*edit a question, then send it/);
      const first = page.locator('[data-starter]').first(), question = await first.textContent();
      await first.click(); assert.equal(await page.locator('#tutor-message').inputValue(), question);
      assert.equal(await first.isDisabled(), true);
      assert.equal(chatCalls(calls).length, before);
      await page.locator('#tutor-message').fill(' \n');
      assert.equal(await first.isDisabled(), true);
      state.suggestionGate.resolve(); state.suggestionGate = null;
      await page.waitForFunction(() => document.querySelector('#tutor-suggestion-note').textContent.includes('Tailored questions unavailable'));
      assert.equal(await page.locator('#tutor-message').inputValue(), ' \n');
      assert.equal(await page.locator('#tutor-suggestions button').count(), 3);
      await page.locator('#tutor-message').fill('');
      assert.equal(await first.isDisabled(), false);
      await page.locator('#check-button').click();
      await page.waitForFunction(() => document.querySelectorAll('#tutor-context-badges li.available').length === 4);
      assert.match(await page.locator('#tutor-suggestions').textContent(), /first repair hint.*counterexample/);
      assert.equal(calls.filter(call => call.resource === '/api/chat').length, 0);
      assert.equal(calls.filter(call => call.resource === '/api/chat/suggestions').length, 1);
    } finally { state.suggestionGate?.resolve(); await trial.context.close(); }
  });
  await check('tutor-ai-suggestion-keeps-unsent-message-through-reply-failure-and-expiration', async () => {
    for (const outcome of ['success', 'provider-error', 'expired']) {
      for (const draft of ['  Keep my unfinished question.\n', ' \n']) {
        const trial = await session();
        const { page, calls, state } = trial;
        try {
          await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
          await page.locator('#tutor-message').fill(draft);
          state.providerError = outcome === 'provider-error'; state.expireOnce = outcome === 'expired';
          const question = await page.locator('.tutor-suggestion:not([data-starter])').first().textContent();
          await page.locator('.tutor-suggestion:not([data-starter])').first().click();
          if (outcome === 'success') await waitSuggestions(page);
          else await page.waitForFunction(() => !document.querySelector('#tutor-messages').hasAttribute('aria-busy'));
          assert.equal(await page.locator('#tutor-message').inputValue(), draft);
          const sent = calls.filter(call => call.resource === '/api/chat');
          assert.equal(sent.length, 1); assert.equal(sent[0].payload.message, question);
          assert.equal(await page.locator('.tutor-user p').last().textContent(), question);
        } finally { await trial.context.close(); }
      }
    }
  });
  await check('tutor-history-is-local-bounded-selectable-and-resumes-only-on-explicit-send', async () => {
    const trial = await session(); const { page, calls, state } = trial;
    try {
      await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
      await page.locator('#tutor-message').fill('My archived question.'); await page.locator('#tutor-send').click(); await waitSuggestions(page);
      const draft = '  My unsent follow-up.\n'; await page.locator('#tutor-message').fill(draft);
      const before = chatCalls(calls).length;
      await page.locator('#tutor-new').click();
      assert.equal(await page.locator('#tutor-message').inputValue(), draft);
      assert.equal(await page.locator('#tutor-messages .tutor-bubble').count(), 0);
      await page.locator('#tutor-history').click();
      assert.equal(await page.locator('#tutor-history-panel').isVisible(), true);
      assert.equal(await page.locator('#tutor-form').isVisible(), false);
      assert.equal(await page.locator('#tutor-messages').isVisible(), false);
      assert.match(await page.locator('#tutor-history-content').textContent(), /My archived question/);
      assert.equal(await page.locator('#tutor-history-content img').count(), 0);
      assert.equal(await page.locator('.tutor-history-use').isDisabled(), true);
      assert.equal(chatCalls(calls).length, before);
      await page.locator('#tutor-history-close').click();
      assert.equal(await page.locator('#tutor-message').inputValue(), draft);
      await page.locator('#tutor-message').fill(''); await page.locator('#tutor-history').click();
      await page.locator('.tutor-history-use').click();
      assert.equal(await page.locator('#tutor-message').inputValue(), 'My archived question.');
      assert.equal(chatCalls(calls).length, before);
      await page.locator('#tutor-message').fill('Continue my earlier reasoning.');
      await page.locator('#tutor-history').click(); await page.locator('#tutor-history-resume').click();
      assert.equal(chatCalls(calls).length, before);
      assert.match(await page.locator('#tutor-status').textContent(), /next message.*abridged history.*current draft/);
      assert.equal(await page.locator('.tutor-assistant.tutor-earlier').count(), 1);
      assert.equal(await page.locator('#tutor-message').inputValue(), 'Continue my earlier reasoning.');
      // Merely requesting fresh recommendations cannot send the selected old history.
      await page.locator('#tutor-suggest').click(); await waitSuggestions(page);
      const suggestion = calls.filter(call => call.resource === '/api/chat/suggestions').at(-1).payload;
      assert.equal(suggestion.conversationToken, undefined); assert.equal(suggestion.resumeConversation, undefined);
      state.providerError = true;
      await page.locator('#tutor-send').click();
      await page.waitForFunction(() => document.querySelector('#tutor-status').textContent.includes('Injected provider failure'));
      let sent = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
      assert.equal(sent.conversationToken, 't'.repeat(43)); assert.equal(sent.resumeConversation, true); assert.equal(sent.history, undefined);
      state.providerError = false; await page.locator('#tutor-send').click(); await waitSuggestions(page);
      sent = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
      assert.equal(sent.resumeConversation, true);
      assert.match(await page.locator('#tutor-history-note').textContent(), /abridged/);
      await page.locator('#tutor-message').fill('One more thought.'); await page.locator('#tutor-send').click(); await waitSuggestions(page);
      assert.equal(calls.filter(call => call.resource === '/api/chat').at(-1).payload.resumeConversation, undefined);
      assert.equal(await page.evaluate(() => Object.values(localStorage).some(value => /My archived question|My unsent follow-up/.test(value))), false);
      await page.locator('[data-exercise-id="second"]').click();
      await page.waitForFunction(() => location.search.includes('second') && !document.querySelector('#predicate-editor').disabled);
      assert.equal(await page.locator('#tutor-history').isVisible(), false);
      assert.equal(await page.locator('#tutor-history-content').textContent(), '');
    } finally { await trial.context.close(); }
  });
  await check('tutor-history-evicts-old-chats-and-never-restores-client-transcript-as-history', async () => {
    const trial = await session(); const { page, calls, state } = trial;
    try {
      await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
      for (let index = 0; index < 6; index += 1) {
        await page.locator('#tutor-message').fill(`Archived ${index}.`);
        await page.locator('#tutor-send').click(); await waitSuggestions(page);
        await page.locator('#tutor-new').click();
      }
      assert.equal(await page.locator('#tutor-history-select option').count(), 5);
      await page.locator('#tutor-history').click();
      assert.doesNotMatch(await page.locator('#tutor-history-select').textContent(), /Archived 0/);
      const before = chatCalls(calls).length;
      await page.locator('#tutor-history-select').selectOption('2');
      assert.equal(chatCalls(calls).length, before);
      assert.match(await page.locator('#tutor-history-content').textContent(), /Archived 1/);
      await page.locator('#tutor-history-resume').click();
      await page.locator('#tutor-history').click(); await page.locator('#tutor-history-resume').click();
      assert.equal(await page.locator('#tutor-history-select option').count(), 5); // No duplicate snapshot.
      state.expireOnce = true;
      await page.locator('#tutor-message').fill('Resume this chat.'); await page.locator('#tutor-send').click();
      await page.waitForFunction(() => document.querySelector('#tutor-status').textContent.includes('session expired'));
      await page.locator('#tutor-history').click();
      assert.match(await page.locator('#tutor-history-content').textContent(), /Archived/);
      assert.equal(calls.filter(call => call.resource === '/api/chat').at(-1).payload.history, undefined);
      await page.reload(); await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
      assert.equal(await page.locator('#tutor-history').isVisible(), false);
      for (let index = 0; index < 7; index += 1) {
        state.replyText = `Large answer ${index}: ` + '界'.repeat(3900);
        await page.locator('#tutor-message').fill(`Large exchange ${index}.`);
        await page.locator('#tutor-send').click(); await waitSuggestions(page);
      }
      await page.locator('#tutor-new').click(); await page.locator('#tutor-history').click();
      assert.match(await page.locator('#tutor-archive-note').textContent(), /Older messages.*omitted/);
      assert(await page.locator('#tutor-history-content .tutor-bubble').count() < 14);
      assert.doesNotMatch(await page.locator('#tutor-history-content').textContent(), /Large exchange 0/);
      assert.match(await page.locator('#tutor-history-content').textContent(), /Large answer 6/);
      const retainedBytes = await page.locator('#tutor-history-content').evaluate(content =>
        [...content.querySelectorAll('.tutor-speaker, p')].reduce((size, item) => size + new TextEncoder().encode(item.textContent).length, 0));
      assert(retainedBytes <= 64 * 1024);
    } finally { await trial.context.close(); }
  });
  await check('tutor-failed-only-archive-resumes-as-new-without-fabricated-history', async () => {
    const trial = await session(); const { page, calls, state } = trial;
    try {
      await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
      state.providerError = true;
      await page.locator('#tutor-message').fill('A question without an answer.'); await page.locator('#tutor-send').click();
      await page.waitForFunction(() => document.querySelector('#tutor-status').textContent.includes('Injected provider failure'));
      await page.locator('#tutor-new').click(); await page.locator('#tutor-history').click();
      const before = chatCalls(calls).length;
      await page.locator('#tutor-history-resume').click();
      assert.equal(chatCalls(calls).length, before);
      assert.match(await page.locator('#tutor-status').textContent(), /no saved server history/);
      assert.equal(await page.locator('#tutor-message').inputValue(), 'A question without an answer.');
      state.providerError = false;
      await page.locator('#tutor-send').click(); await waitSuggestions(page);
      const sent = calls.filter(call => call.resource === '/api/chat').at(-1).payload;
      assert.equal(sent.conversationToken, undefined); assert.equal(sent.resumeConversation, undefined); assert.equal(sent.history, undefined);
    } finally { await trial.context.close(); }
  });
  await check('tutor-reply-tail-follows-layout-growth-but-preserves-reading-older-messages', async () => {
    const trial = await session(); const { page, calls, state } = trial;
    const log = page.locator('#tutor-messages');
    const atBottom = () => page.waitForFunction(() => {
      const log = document.querySelector('#tutor-messages');
      return log.scrollHeight - log.clientHeight - log.scrollTop <= 2;
    });
    try {
      await page.locator('#tutor-toggle').click(); await waitSuggestions(page);
      state.replyText = 'Compare the intended behavior with a small graph. Decide which node would make the statement false, then inspect how your operator treats that node. '.repeat(9);
      await page.locator('#tutor-message').fill('Help me reason through the example.');
      await page.locator('#tutor-send').click(); await waitSuggestions(page); await atBottom();
      await page.locator('#tutor-new').click(); await page.locator('#tutor-history').click();
      await page.locator('#tutor-history-resume').click();
      state.suggestions = Array.from({ length: 3 }, (_, index) => `${index + 1}. How should I compare the meaning of my chosen operator with the question's requirements, use the available examples to test that interpretation, and decide what single small change to examine next?`);
      state.suggestionGate = deferred(); state.suggestionStarted = deferred();
      await page.locator('#tutor-message').fill('Continue with this reasoning.'); await page.locator('#tutor-send').click();
      await state.suggestionStarted.promise; await atBottom();
      assert.match(await page.locator('#tutor-history-note').textContent(), /abridged/);
      const beforeHeight = await log.evaluate(element => element.clientHeight);
      const beforeCalls = chatCalls(calls).length;
      state.suggestionGate.resolve(); state.suggestionGate = null; await waitSuggestions(page); await atBottom();
      assert(await log.evaluate(element => element.clientHeight) < beforeHeight, 'suggestions must actually shrink the viewport');
      assert.equal(chatCalls(calls).length, beforeCalls);
      state.suggestionGate = deferred(); state.suggestionStarted = deferred();
      await page.locator('#tutor-message').fill('What else should I test?'); await page.locator('#tutor-send').click();
      await state.suggestionStarted.promise; await atBottom();
      await log.evaluate(element => { element.scrollTop = 0; element.dispatchEvent(new Event('scroll')); });
      assert.equal(await log.evaluate(element => element.scrollTop), 0);
      state.suggestionGate.resolve(); state.suggestionGate = null; await waitSuggestions(page);
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      assert.equal(await log.evaluate(element => element.scrollTop), 0, 'reading position must not jump to the latest answer');
      assert(await log.evaluate(element => element.scrollHeight > element.clientHeight + 50));
    } finally { state.suggestionGate?.resolve(); await trial.context.close(); }
  });
  assert.deepEqual(errors, []); assert.deepEqual(external, []);
  const report = { status: 'PASS', checks: passed.length, passed, externalRequests: external.length };
  await writeFile(path.join(artifacts, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally { await browser?.close(); }

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
async function session({ basePath = '/', disabled = false, mismatch = false } = {}) {
  const origin = 'https://alloy.example';
  const calls = [], state = { replyGate: null, suggestionGate: null, mismatch, disabled };
  let channelNumber = 0;
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(() => localStorage.setItem('alloy-studio:v1:live', 'false'));
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
      return send({ status: 'ok', ...identity(payload), model: 'openai:luna', suggestions: [
        'What does the first hint mean?', 'What does this counterexample show?', 'How can I test my reasoning?', 'Fourth question is omitted',
      ] });
    }
    if (resource === '/api/chat') {
      if (state.expireOnce) { state.expireOnce = false; return route.fulfill({ status: 410, json: { status: 'expired', code: 'channel_expired', ...identity(payload) } }); }
      if (state.replyGate) await state.replyGate.promise;
      return send({ status: 'ok', ...identity(payload), revision: payload.revision + (state.mismatch ? 1 : 0),
        model: payload.model, effort: payload.effort, conversationToken: 't'.repeat(43), historyTruncated: !!state.truncated,
        reply: 'Consider a node with no outgoing edges. <img src=x onerror="window.injected=true"> Which part of your predicate rules it out?' });
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
const waitSuggestions = page => page.waitForFunction(() => document.querySelectorAll('.tutor-suggestion').length === 3 && !document.querySelector('#tutor-suggest').disabled);
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
    assert.equal(await page.locator('.tutor-suggestion').count(), 3);
    assert.equal(chatCalls(calls).filter(call => call.resource === '/api/chat/suggestions').length, 1);
  });
  await check('tutor-suggestion-click-sends-only-current-draft-and-pins-not-history', async () => {
    await page.locator('.tutor-suggestion').first().click();
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
  await check('tutor-edit-preserves-conversation-and-retrieves-current-draft-without-keystroke-calls', async () => {
    const before = chatCalls(calls).length;
    const previousAnswers = await page.locator('.tutor-assistant').count();
    await editor.fill('no Node');
    assert.equal(await page.locator('.tutor-assistant').count(), previousAnswers);
    assert.equal(await page.locator('.tutor-assistant.tutor-earlier').count(), previousAnswers);
    assert.equal(await page.locator('.tutor-suggestion').count(), 0);
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
    assert.equal(await page.locator('.tutor-suggestion').count(), 0);
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
    await page.locator('.tutor-suggestion').first().click();
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
    await page.locator('#tutor-new').focus(); await page.keyboard.press('Shift+Tab');
    assert.equal(await page.locator('#tutor-send').evaluate(element => element === document.activeElement), false);
    assert.equal(await page.locator('#tutor-message').evaluate(element => element === document.activeElement), true);
    for (const id of ['tutor-panel', 'tutor-close', 'tutor-message', 'tutor-send']) {
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
    await scoped.page.locator('.tutor-suggestion').first().click(); await scoped.page.locator('.tutor-assistant').last().waitFor();
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
  assert.deepEqual(errors, []); assert.deepEqual(external, []);
  const report = { status: 'PASS', checks: passed.length, passed, externalRequests: external.length };
  await writeFile(path.join(artifacts, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally { await browser?.close(); }

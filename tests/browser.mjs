import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, readFile } from 'node:fs/promises';
import { createServer, request as httpRequest } from 'node:http';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const artifacts = path.join(root, 'build/browser');
await mkdir(artifacts, { recursive: true });
const server = spawn('python3', ['server.py', '--port', '0'], {
  cwd: root, env: { ...process.env, OPENAI_DISABLED: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let browser;
const passed = [];
const errors = [];
const externalRequests = [];
async function check(name, fn) { await fn(); passed.push(name); }
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const result = (payload, distance) => ({ ...payload, body: undefined, status: 'ok', metric: 'acgn-fast-rewrite-canonical-distance',
  distance, breakdown: { temporal: 0, quantifier: 0, matrix: distance }, canonicalForm: ['some Node'],
  operations: distance ? [{ kind: 'component-edit', component: 'matrix', path: 'matrix', cost: distance, aggregate: true,
    description: 'Matrix edit units with no matching detailed trace.' }] : [],
  trace: { cost: distance, matchesDistance: true, hasAggregates: distance > 0, certifiedOptimalScript: false } });
const sourceRange = (body, start, end) => {
  const position = offset => {
    const lines = body.slice(0, offset).split('\n');
    return { line: lines.length, column: lines.at(-1).length + 1 };
  };
  const first = position(start), last = position(end);
  return { start, end, text: body.slice(start, end), startLine: first.line, startColumn: first.column,
    endLine: last.line, endColumn: last.column, moduleLine: 40 + first.line, moduleColumn: first.column };
};
const sourceResult = (payload, ranges, location = {}) => ({ ...result(payload, 1),
  operations: [{ kind: 'replace', component: 'matrix', path: 'matrix', cost: 1,
    action: 'Change the related expression', sourceOperator: 'some', replacementOperator: 'no',
    sourceLocation: { status: 'located', precision: 'related', coordinateSystem: 'body', offsetEncoding: 'utf-16',
      reason: 'The expression corresponds to this canonical edit; its exact repair may differ.',
      ranges: ranges.map(([start, end]) => sourceRange(payload.body, start, end)), ...location },
    canonicalLocation: { status: 'located', precision: 'related', coordinateSystem: 'canonical', offsetEncoding: 'utf-16',
      reason: 'Related canonical expression.', ranges: [{ formIndex: 0, start: 0, end: 9, text: 'some Node' }] } }],
  trace: { cost: 1, matchesDistance: true, hasAggregates: false, certifiedOptimalScript: false } });
const someSourceResult = payload => {
  const start = payload.body.indexOf('some Node');
  return sourceResult(payload, [[start, start + 'some Node'.length]]);
};
const behaviorInstance = (identity, states = 1) => ({ traceLength: states, loopState: states > 1 ? 0 : -1,
  truncated: false, stringsAnonymized: false,
  states: Array.from({ length: states }, (_, index) => ({ index,
    signatures: [{ label: 'Node', atoms: [`Node$${identity}`, `State$${index}`] }],
    relations: [{ label: 'adj', arity: 2, tuples: [[`Node$${identity}`, `State$${index}`]] }] })) });
const behaviorResult = (payload, score = 0.6665) => ({ exerciseId: payload.exerciseId, revision: payload.revision,
  behaviorToken: createHash('sha256').update(JSON.stringify([payload.exerciseId, payload.body, payload.revision])).digest('hex'),
  status: 'ok', metric: 'acgn-reward', score, scoreStatus: 'ok', scoreReason: 'OK',
  scope: { overall: 3, bitwidth: 3, maxSequence: 3, poolSize: 100, minTrace: 1, maxTrace: 10, moduleFacts: true },
  sampling: { positiveTested: 4, positiveAccepted: 3, negativeTested: 4, negativeRejected: 2, semanticCounterexamples: 1 },
  categories: [['both', true, true], ['undercoverage', true, false], ['overcoverage', false, true], ['neither', false, false]]
    .map(([id, oracle, student]) => ({ id, oracle, student, status: 'sat', enumerationComplete: false,
      instances: Array.from({ length: 3 }, (_, index) => behaviorInstance(`${id}-${index + 1}`)) })) });
const mockBehaviorUnavailable = route => {
  const { exerciseId, revision } = route.request().postDataJSON();
  return route.fulfill({ json: { exerciseId, revision, status: 'unavailable' } });
};
const educationResult = (payload, { operationIds = ['operation-1'], instanceIds = payload.behaviorToken
  ? ['both', 'undercoverage', 'overcoverage', 'neither'].flatMap(id => [1, 2, 3].map(index => `${id}-${index}`)) : [],
  prefix = 'Learner explanation', summary = 'Review one edit and compare its example before changing the predicate.' } = {}) => ({
  exerciseId: payload.exerciseId, revision: payload.revision, status: 'ok', model: 'gpt-6-luna',
  ...(payload.behaviorToken ? { behaviorToken: payload.behaviorToken } : {}),
  operations: operationIds.map(id => ({ id, description: `${prefix}: ${id}. This edit changes which structures your predicate accepts.` })),
  instances: instanceIds.map(id => ({ id, description: `${prefix}: ${id}. Read the listed atoms and relation tuples to see the example.` })), summary,
});
try {
  const url = await new Promise((resolve, reject) => {
    let output = '';
    const timer = setTimeout(() => reject(new Error('Server startup timeout')), 10000);
    server.stdout.on('data', chunk => { output += chunk; const match = output.match(/http:\/\/127\.0\.0\.1:\d+/); if (match) { clearTimeout(timer); resolve(match[0]); } });
    server.on('exit', code => { clearTimeout(timer); reject(new Error(`Server exited ${code}`)); });
  });
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  await context.route('**/*', route => {
    if (!route.request().url().startsWith(url) && !route.request().url().startsWith('blob:')) {
      externalRequests.push(route.request().url()); return route.abort();
    }
    if (route.request().url().endsWith('/api/behavior')) return mockBehaviorUnavailable(route);
    return route.continue();
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  const editor = page.locator('#predicate-editor');
  const feedback = page.locator('#feedback-state');
  const waitChecked = async () => { await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked'); };
  const waitBehavior = async (status = 'ok') => page.waitForFunction(expected => document.querySelector('#behavior-state').dataset.state === expected, status);
  const submit = async body => { await editor.fill(body); await page.locator('#check-button').click(); await waitChecked(); };
  let record;

  await check('real-engine-initial-load-and-private-projection', async () => {
    await page.goto(url + '/?exercise=graphs-inv1');
    await waitChecked();
    assert.equal(await page.locator('#exercise-count').textContent(), '181');
    assert.equal(await page.locator('.distance-value').count(), 1);
    await page.locator('.explanation-unavailable').waitFor();
    assert.match(await page.locator('#luna-explanation-body').textContent(), /not configured/);
    record = await (await context.request.get(url + '/api/exercises/graphs-inv1')).json();
    assert(!('oracleBody' in record)); assert(!('originalSource' in record));
    assert.equal(await page.locator('#exercise-description').textContent(), record.description);
    assert.equal(await page.locator('#environment-code').textContent(), record.environmentBefore);
    await page.screenshot({ path: path.join(artifacts, 'desktop.png'), fullPage: true });
  });
  await check('real-invalid-diagnostic-and-canonical-zero-witness', async () => {
    await page.locator('#live-feedback').uncheck();
    await editor.fill('some UNDECLARED_NODE');
    await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Check syntax');
    assert.match(await page.locator('.diagnostic-location').textContent(), /Line 1/);
    await submit('adj = ~adj');
    assert.equal(await page.locator('.distance-value').textContent(), '0');
    assert.equal(await page.locator('.match-badge').textContent(), 'Canonical match');
    await page.locator('.canonical-details summary').click();
    assert((await page.locator('.canonical-details pre').allTextContents()).join('').length > 0);
  });
  await check('expanded-operator-hint-drives-a-real-source-repair', async () => {
    await page.locator('[data-exercise-id="graphs-inv5"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv5'));
    await submit('some (iden & adj)');
    assert.equal(await page.locator('.distance-value').textContent(), '1');
    const replacement = await page.locator('.replacement-operator code').first().textContent();
    assert.equal(replacement, 'no');
    assert.match(await page.locator('.operation-fragment code').first().textContent(), /some/);
    assert((await page.locator('.operation-next-step').first().textContent()).length > 15);
    assert.equal(await page.locator('.operation-structure[open]').count(), 0);
    // A learner can use the displayed primitive operator hint in their actual source.
    await submit(replacement + ' (iden & adj)');
    assert.equal(await page.locator('.distance-value').textContent(), '0');
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
  });
  await check('real-engine-source-locator-selects-related-expression', async () => {
    await page.locator('[data-exercise-id="graphs-inv5"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv5'));
    const body = 'some (iden & adj)';
    await submit(body);
    await page.locator('.operation-locate').first().click();
    const selection = await editor.evaluate(element => ({ start: element.selectionStart, end: element.selectionEnd,
      selected: element.value.slice(element.selectionStart, element.selectionEnd), focused: document.activeElement === element }));
    assert(selection.focused);
    assert(selection.start >= 0 && selection.end <= body.length && selection.end > selection.start);
    assert(selection.selected.includes('some'));
    assert.equal(await page.locator('.source-range').textContent(), selection.selected);
    assert.match(await page.locator('#source-location-status').textContent(), /Related source context.*Body Ln 1, Col 1.*Model Ln \d+, Col \d+/);
    assert((await page.locator('.canonical-range').count()) > 0);
    assert((await page.locator('#canonical-content pre').allTextContents()).every(form => !/\s{2,}/.test(form)));
    await page.screenshot({ path: path.join(artifacts, 'defect-locator.png'), fullPage: true });
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
    assert.equal(await page.locator('.source-range').count(), 0);
  });
  await check('alternative-correct-formulation-matches-the-inclusive-pool', async () => {
    await page.locator('[data-exercise-id="graphs-inv5"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv5'));
    await submit('all n: Node | n not in n.adj');
    assert.equal(await page.locator('.distance-value').textContent(), '0');
    assert.match(await page.locator('.distance-description').allTextContents().then(values => values.join(' ')),
      /Compared with all \d+ saved correct answers, including the oracle/);
    const response = await context.request.post(url + '/api/feedback', {
      data: { exerciseId: 'graphs-inv5', body: 'all n: Node | n not in n.adj', revision: 401 },
    });
    const feedback = await response.json();
    assert.equal(feedback.comparison.strategy, 'nearest-known-correct');
    assert.equal(feedback.comparison.evaluatedCandidates, feedback.comparison.poolSize);
    assert.equal(feedback.comparison.complete, true);
    assert(feedback.comparison.poolSize > 1);
    assert(!JSON.stringify(feedback).includes('referenceBodies'));
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
  });
  await check('source-locator-selects-utf16-range-and-persists-on-blur', async () => {
    await page.route('**/api/feedback', route => route.fulfill({ json: someSourceResult(route.request().postDataJSON()) }));
    const body = '// 🧭 learner note\n\tsome Node\n';
    await submit(body);
    const locate = page.locator('.operation-locate');
    await locate.focus(); await locate.press('Enter');
    assert.deepEqual(await editor.evaluate(element => [element.selectionStart, element.selectionEnd, document.activeElement === element]),
      [body.indexOf('some Node'), body.indexOf('some Node') + 9, true]);
    assert.equal(await page.locator('.source-range').textContent(), 'some Node');
    assert.match(await page.locator('#source-location-status').textContent(), /Body Ln 2, Col 2 · Model Ln 42, Col 2/);
    assert.equal(await page.locator('#source-highlight').getAttribute('aria-hidden'), 'true');
    assert.equal(await page.locator('#source-highlight').evaluate(element => getComputedStyle(element).pointerEvents), 'none');
    await page.locator('#download-button').focus();
    assert.equal(await page.locator('.source-range').textContent(), 'some Node');
    assert.equal(await editor.inputValue(), body);
    await page.locator('#clear-source-highlight').click();
    assert.equal(await page.locator('.source-range').count(), 0);
    assert.equal(await page.locator('#source-location-bar').isHidden(), true);
    assert.equal(await locate.getAttribute('aria-pressed'), 'false');
    await page.unroute('**/api/feedback');
  });
  await check('source-locator-aligns-tabs-multiline-scroll-resize-and-mobile', async () => {
    const body = '// context\n'.repeat(22) + '\t' + 'Node + '.repeat(45) + 'some Node\n\tno Node\n' + '// tail\n'.repeat(18);
    const start = body.indexOf('some Node'), end = body.indexOf('\tno Node') + '\tno Node'.length;
    await page.route('**/api/feedback', route => route.fulfill({ json: sourceResult(route.request().postDataJSON(), [[start, end]]) }));
    await submit(body);
    const assertAlignment = async () => {
      const geometry = await page.evaluate(() => {
        const editor = document.querySelector('#predicate-editor'), overlay = document.querySelector('#source-highlight');
        const mark = document.querySelector('.source-range');
        const editorStyle = getComputedStyle(editor), mirrorStyle = getComputedStyle(overlay);
        const rect = mark.getClientRects()[0], bounds = overlay.getBoundingClientRect();
        const canvas = document.createElement('canvas').getContext('2d');
        canvas.font = `${editorStyle.fontSize} ${editorStyle.fontFamily}`;
        const glyphWidth = canvas.measureText('N').width;
        return { start: editor.selectionStart, end: editor.selectionEnd, top: editor.scrollTop, left: editor.scrollLeft,
          overlayTop: overlay.scrollTop, overlayLeft: overlay.scrollLeft, width: overlay.clientWidth, height: overlay.clientHeight,
          editorWidth: editor.clientWidth, editorHeight: editor.clientHeight, glyphWidth,
          x: rect.left - bounds.left + overlay.scrollLeft, y: rect.top - bounds.top + overlay.scrollTop,
          padding: parseFloat(editorStyle.paddingLeft), lineHeight: parseFloat(editorStyle.lineHeight),
          mirrorMatches: ['fontFamily', 'fontSize', 'lineHeight', 'tabSize', 'whiteSpace', 'paddingLeft', 'paddingRight', 'letterSpacing']
            .every(property => editorStyle[property] === mirrorStyle[property]),
          visible: rect.left >= bounds.left && rect.left < bounds.right && rect.top >= bounds.top && rect.top < bounds.bottom,
          underline: getComputedStyle(mark).boxShadow };
      });
      assert.equal(geometry.start, start); assert.equal(geometry.end, end);
      assert(geometry.top > 0 && geometry.left > 0);
      assert.equal(geometry.overlayTop, geometry.top); assert.equal(geometry.overlayLeft, geometry.left);
      assert.equal(geometry.width, geometry.editorWidth); assert.equal(geometry.height, geometry.editorHeight);
      assert(geometry.mirrorMatches); assert(geometry.visible); assert.notEqual(geometry.underline, 'none');
      assert(Math.abs(geometry.x - geometry.padding - (2 + 7 * 45) * geometry.glyphWidth) < 2, JSON.stringify(geometry));
      // The inline mark uses the glyph box within the 23px line box.
      assert(Math.abs(geometry.y - 22 * geometry.lineHeight) < 6);
      assert.equal(await page.locator('.source-range').textContent(), body.slice(start, end));
    };
    await page.locator('.operation-locate').click(); await assertAlignment();
    await editor.evaluate(element => { element.style.height = '360px'; element.scrollLeft = 140; element.scrollTop = 120; element.dispatchEvent(new Event('scroll')); });
    await page.waitForFunction(() => document.querySelector('#source-highlight').clientHeight === document.querySelector('#predicate-editor').clientHeight);
    assert.deepEqual(await page.evaluate(() => ['#predicate-editor', '#source-highlight'].map(selector => {
      const element = document.querySelector(selector); return [element.scrollLeft, element.scrollTop];
    })), [[140, 120], [140, 120]]);
    await page.locator('.operation-locate').click(); await assertAlignment();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('.operation-locate').click(); await assertAlignment();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.screenshot({ path: path.join(artifacts, 'source-locator-mobile.png'), fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await editor.evaluate(element => { element.style.height = ''; });
    await page.unroute('**/api/feedback');
  });
  await check('source-locator-clears-on-edit-reset-switch-and-check', async () => {
    let releaseCheck;
    await page.route('**/api/feedback', async route => {
      if (releaseCheck) await releaseCheck;
      await route.fulfill({ json: someSourceResult(route.request().postDataJSON()) });
    });
    await submit('some Node'); await page.locator('.operation-locate').click();
    const oldButton = await page.locator('.operation-locate').elementHandle();
    await editor.fill('some Node // edited');
    await oldButton.evaluate(button => button.click());
    assert.equal(await page.locator('.source-range').count(), 0);
    await submit('some Node'); await page.locator('.operation-locate').click();
    await editor.press('Tab');
    assert.equal(await editor.inputValue(), '  ');
    assert.equal(await page.locator('.source-range').count(), 0);
    await submit('some Node'); await page.locator('.operation-locate').click();
    await page.locator('#reset-button').click();
    assert.equal(await page.locator('.source-range').count(), 0);
    await submit('some Node'); await page.locator('.operation-locate').click();
    let resolveCheck;
    releaseCheck = new Promise(resolve => { resolveCheck = resolve; });
    await page.locator('#check-button').click();
    assert.equal(await page.locator('.source-range').count(), 0);
    assert.equal(await page.locator('#source-location-bar').isHidden(), true);
    resolveCheck(); await waitChecked(); releaseCheck = null;
    await page.locator('.operation-locate').click();
    await page.locator('[data-exercise-id="graphs-inv2"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv2'));
    assert.equal(await page.locator('.source-range').count(), 0);
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
    await page.unroute('**/api/feedback');
  });
  await check('source-locator-offers-explicit-ambiguous-candidates', async () => {
    const body = 'some Node\n\tsome Node';
    await page.route('**/api/feedback', route => route.fulfill({ json: sourceResult(route.request().postDataJSON(),
      [[0, 9], [body.lastIndexOf('some Node'), body.length]], { status: 'ambiguous' }) }));
    await submit(body);
    assert.match(await page.locator('.operation-location-note').textContent(), /Related source context · 2 possible locations/);
    assert.equal(await page.locator('.operation-locate').count(), 2);
    await page.getByRole('button', { name: 'Locate candidate 2' }).click();
    assert.equal(await editor.evaluate(element => element.selectionStart), body.lastIndexOf('some Node'));
    assert.match(await page.locator('#source-location-status').textContent(), /possible location 2 of 2 · Body Ln 2, Col 2/);
    await page.getByRole('button', { name: 'Locate candidate 1' }).click();
    assert.equal(await editor.evaluate(element => element.selectionStart), 0);
    assert.match(await page.locator('#source-location-status').textContent(), /possible location 1 of 2 · Body Ln 1, Col 1/);
    assert.equal(await page.locator('.operation-locate[aria-pressed="true"]').count(), 1);
    await page.unroute('**/api/feedback');
  });
  await check('source-locator-rejects-invalid-ranges-and-response-identities', async () => {
    const mutations = [
      data => { data.operations[0].sourceLocation.ranges[0].start = -1; },
      data => { data.operations[0].sourceLocation.ranges[0].end = 1000; },
      data => { data.operations[0].sourceLocation.ranges[0].start = 0.5; },
      data => { data.operations[0].sourceLocation.ranges[0].text = 'no Node'; },
      data => { data.operations[0].sourceLocation.ranges[0].startLine = 2; },
      data => { delete data.operations[0].sourceLocation.ranges[0].moduleLine; },
      data => { data.operations[0].sourceLocation.coordinateSystem = 'module'; },
      data => { data.operations[0].sourceLocation.offsetEncoding = 'utf-8'; },
      data => { data.operations[0].sourceLocation.status = 'ambiguous'; },
      data => { data.operations[0].sourceLocation.status = 'unavailable'; data.operations[0].sourceLocation.reason = 'No reliable original expression.'; },
      data => { delete data.revision; },
      data => { delete data.exerciseId; },
    ];
    for (const mutate of mutations) {
      await page.route('**/api/feedback', route => {
        const data = someSourceResult(route.request().postDataJSON()); mutate(data); return route.fulfill({ json: data });
      });
      await submit('some Node');
      assert.equal(await page.locator('.operation-locate').count(), 0);
      assert.match(await page.locator('.source-location-unavailable').textContent(), /Source location unavailable/);
      assert.equal(await page.locator('.source-range').count(), 0);
      await page.unroute('**/api/feedback');
    }
    for (const mismatch of ['revision', 'exerciseId']) {
      await page.route('**/api/feedback', route => {
        const data = someSourceResult(route.request().postDataJSON());
        data[mismatch] = mismatch === 'revision' ? data.revision - 1 : 'graphs-inv8';
        return route.fulfill({ json: data });
      });
      await editor.fill('some Node'); await page.locator('#check-button').click();
      await page.waitForFunction(() => document.querySelector('#feedback-state').dataset.state === 'error');
      assert.match(await page.locator('#feedback-result').textContent(), /different draft/);
      assert.equal(await page.locator('.operation-locate').count(), 0);
      await page.unroute('**/api/feedback');
    }
  });
  await check('canonical-panel-and-edit-step-share-source-highlight-color', async () => {
    const canonical = String.raw`(some Node) and label = "two  spaces \"inside\""`;
    await page.route('**/api/feedback', route => {
      const data = someSourceResult(route.request().postDataJSON());
      data.canonicalForm = [canonical];
      data.operations[0].canonicalLocation.ranges = [{ formIndex: 0, start: 1, end: 10, text: 'some Node' }];
      return route.fulfill({ json: data });
    });
    await submit('some Node');
    assert.equal(await page.locator('#canonical-content pre').textContent(), canonical);
    assert.equal(await page.locator('#feedback-result .canonical-details').count(), 0);
    assert(await page.locator('#canonical-panel').evaluate(element => element.previousElementSibling.classList.contains('editor-card')));
    await page.locator('.operation-select').focus(); await page.locator('.operation-select').press('Enter');
    assert.equal(await page.locator('.canonical-range').textContent(), 'some Node');
    assert.equal(await page.locator('.source-range').textContent(), 'some Node');
    assert.equal(await page.locator('.operation-item.active-operation').count(), 1);
    assert.equal(await page.locator('#canonical-panel').getAttribute('open'), '');
    const colors = await page.evaluate(() => ['.canonical-range', '.source-range'].map(selector => {
      const style = getComputedStyle(document.querySelector(selector)); return [style.backgroundColor, style.boxShadow];
    }));
    assert.deepEqual(colors[0], colors[1]);
    assert.notEqual(colors[0][0], 'rgba(0, 0, 0, 0)'); assert.notEqual(colors[0][1], 'none');
    await page.locator('#clear-source-highlight').click();
    assert.equal(await page.locator('.canonical-range, .source-range').count(), 0);
    assert.equal(await page.locator('#canonical-content pre').textContent(), canonical);
    await page.unroute('**/api/feedback');
  });
  await check('canonical-locator-supports-unavailable-source-and-independent-ambiguity', async () => {
    const canonical = 'some Node and some Node';
    let sourceUnavailable = true;
    await page.route('**/api/feedback', route => {
      const data = sourceResult(route.request().postDataJSON(), [[0, 9], [10, 19]], { status: 'ambiguous' });
      if (sourceUnavailable) data.operations[0].sourceLocation = { status: 'unavailable', ranges: [], reason: 'No reliable source mapping.' };
      data.canonicalForm = [canonical];
      data.operations[0].canonicalLocation = { status: 'ambiguous', precision: 'related', coordinateSystem: 'canonical', offsetEncoding: 'utf-16',
        ranges: [{ formIndex: 0, start: 0, end: 9, text: 'some Node' }, { formIndex: 0, start: 14, end: 23, text: 'some Node' }] };
      return route.fulfill({ json: data });
    });
    await submit('some Node\nsome Node'); await page.locator('.operation-select').click();
    assert.equal(await page.locator('.canonical-range').count(), 2);
    assert.equal(await page.locator('.source-range').count(), 0);
    assert.match(await page.locator('#canonical-location-status').textContent(), /2 possible parts highlighted.*not paired with the locations in your code/);
    assert.match(await page.locator('.source-location-unavailable').textContent(), /No reliable source mapping/);
    sourceUnavailable = false;
    await submit('some Node\nsome Node'); await page.locator('.operation-select').click();
    assert.equal(await page.locator('.source-range').count(), 0);
    assert.match(await page.locator('#source-location-status').textContent(), /Choose a source candidate/);
    await page.getByRole('button', { name: 'Locate candidate 2' }).click();
    assert.equal(await page.locator('.canonical-range').count(), 2);
    assert.equal(await editor.evaluate(element => element.selectionStart), 10);
    assert.match(await page.locator('#canonical-location-status').textContent(), /not paired with the locations in your code/);
    await page.unroute('**/api/feedback');
  });
  await check('canonical-form-clears-on-new-draft-and-rejects-invalid-locations', async () => {
    let mode = 'valid';
    await page.route('**/api/feedback', route => {
      const data = someSourceResult(route.request().postDataJSON());
      if (mode === 'text') data.operations[0].canonicalLocation.ranges[0].text = 'no Node';
      if (mode === 'bounds') data.operations[0].canonicalLocation.ranges[0].end = 1000;
      if (mode === 'form') data.operations[0].canonicalLocation.ranges[0].formIndex = 4;
      if (mode === 'context') data.operations[0].canonicalLocation.precision = 'form';
      if (mode === 'invalid') return route.fulfill({ json: { ...route.request().postDataJSON(), status: 'invalid', diagnostics: [{ message: 'Invalid draft.' }] } });
      return route.fulfill({ json: data });
    });
    await submit('some Node'); await page.locator('.operation-select').click();
    assert.equal(await page.locator('.canonical-range').count(), 1);
    await editor.fill('some Node // changed');
    assert.equal(await page.locator('#canonical-content pre').count(), 0);
    assert.equal(await page.locator('.canonical-range, .source-range').count(), 0);
    for (mode of ['text', 'bounds', 'form']) {
      await submit('some Node'); await page.locator('.operation-select').click();
      assert.equal(await page.locator('.canonical-range').count(), 0);
      assert.match(await page.locator('#canonical-location-status').textContent(), /location unavailable/);
      assert.equal(await page.locator('.source-range').count(), 1);
    }
    mode = 'context'; await submit('some Node'); await page.locator('.operation-select').click();
    assert.match(await page.locator('#canonical-location-status').textContent(), /Canonical form context/);
    mode = 'invalid'; await editor.fill('some Node'); await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Check syntax');
    assert.equal(await page.locator('#canonical-content pre').count(), 0);
    assert.equal(await page.locator('.canonical-range, .source-range').count(), 0);
    assert.match(await page.locator('.canonical-empty').textContent(), /unavailable for this draft/);
    await page.unroute('**/api/feedback');
  });
  await check('real-behavior-score-and-four-bounded-categories', async () => {
    await page.route('**/api/behavior', route => route.continue());
    await submit('adj = ~adj'); await waitBehavior();
    assert.equal(await page.locator('.behavior-score').textContent(), '1.000');
    assert.equal(await page.locator('.behavior-category-choice').count(), 4);
    assert.match(await page.locator('.behavior-facts').textContent(), /Model facts enforced/);
    assert.match(await page.locator('.behavior-scope').textContent(), /atom scope 3.*3-bit integers.*traces 1–10/);
    assert.match(await page.locator('.behavior-bound-note').textContent(), /do not prove equivalence/);
    for (const id of ['undercoverage', 'overcoverage']) {
      await page.locator(`.behavior-category-choice[data-category="${id}"]`).click();
      assert.match(await page.locator('.behavior-category-content').textContent(), /No instance within these bounds/);
      assert.equal(await page.locator('.behavior-example-choice').count(), 0);
    }
    await page.locator('.behavior-category-choice[data-category="both"]').click();
    const count = await page.locator('.behavior-example-choice').count();
    assert(count >= 1 && count <= 3);
    assert((await page.locator('.behavior-signatures td').count()) > 0);
    assert.equal(await page.locator('.distance-value').textContent(), '0');
    await submit('no (iden & adj)'); await waitBehavior();
    assert.match(await page.locator('.behavior-score').textContent(), /^\d\.\d{3}$/);
    for (const id of ['both', 'undercoverage', 'overcoverage', 'neither']) {
      await page.locator(`.behavior-category-choice[data-category="${id}"]`).click();
      const examples = await page.locator('.behavior-example-choice').count();
      assert(examples >= 1 && examples <= 3);
    }
    await page.locator('.behavior-category-choice[data-category="undercoverage"]').click();
    await page.locator('#behavior-card').screenshot({ path: path.join(artifacts, 'behavioral-examples.png') });
    await page.unroute('**/api/behavior');
  });
  await check('behavior-rounding-categories-and-three-example-choices', async () => {
    let score = 0.6665;
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', route => route.fulfill({ json: behaviorResult(route.request().postDataJSON(), score) }));
    await submit('some Node'); await waitBehavior();
    assert.equal(await page.locator('.behavior-score').textContent(), '0.667');
    for (const [id, oracle, student] of [['both', true, true], ['undercoverage', true, false], ['overcoverage', false, true], ['neither', false, false]]) {
      const choice = page.locator(`.behavior-category-choice[data-category="${id}"]`);
      assert((await choice.textContent()).includes(`Oracle: ${oracle} · Yours: ${student}`));
      await choice.click();
      assert.equal(await choice.getAttribute('aria-pressed'), 'true');
      assert.equal(await page.locator('.behavior-example-choice').count(), 3);
      await page.getByRole('button', { name: 'Example 3', exact: true }).click();
      assert((await page.locator('.behavior-signatures').textContent()).includes(`Node$${id}-3`));
      assert.deepEqual(await page.locator('.behavior-relation th').allTextContents(), ['From', 'To']);
      assert.match(await page.locator('.behavior-enumeration').textContent(), /More may exist/);
    }
    await page.locator('.behavior-category-choice[data-category="undercoverage"]').click();
    await page.locator('#behavior-card').screenshot({ path: path.join(artifacts, 'behavioral-example-controls.png') });
    for (const [value, text] of [[0, '0.000'], [1, '1.000'], [0.0005, '0.001'], [0.9995, '1.000'], [0.1234, '0.123']]) {
      score = value; await submit(`some Node // score ${value}`); await waitBehavior();
      assert.equal(await page.locator('.behavior-score').textContent(), text);
      if (text === '1.000') assert.match(await page.locator('.behavior-rounding-note').textContent(), /counterexamples still exist/);
    }
    await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('behavior-temporal-tables-truncation-and-text-safety', async () => {
    const markup = '<img src=x onerror="window.BEHAVIOR_INJECTED=true">';
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', route => {
      const data = behaviorResult(route.request().postDataJSON());
      const instance = behaviorInstance('temporal', 3);
      instance.truncated = true; instance.stringsAnonymized = true;
      instance.states.forEach(state => {
        state.signatures.push({ label: markup, atoms: ['String$0'] });
        state.relations.push({ label: '<script>window.BEHAVIOR_INJECTED=true</script>', arity: 3, tuples: [['String$0', markup, `State$${state.index}`]] });
      });
      data.categories[0].instances = [instance, { ...instance, loopState: 1, states: instance.states.slice(0, 1) }];
      data.categories[0].enumerationComplete = true;
      return route.fulfill({ json: data });
    });
    await submit('some Node'); await waitBehavior();
    assert.match(await page.locator('.behavior-truncated').textContent(), /only partially displayed/);
    assert.match(await page.locator('.behavior-string-note').textContent(), /String contents are hidden; atom identities are preserved/);
    assert.match(await page.locator('.behavior-state-controls').textContent(), /repeat from state 1/);
    assert.equal(await page.locator('.behavior-state-select option').count(), 3);
    await page.locator('.behavior-state-select').selectOption('2');
    assert.match(await page.locator('.behavior-state-content').textContent(), /State\$2/);
    assert(!(await page.locator('.behavior-state-content').textContent()).includes('State$0'));
    assert((await page.locator('.behavior-state-content').textContent()).includes(markup));
    assert.equal(await page.locator('#behavior-result img, #behavior-result script').count(), 0);
    assert.equal(await page.evaluate(() => window.BEHAVIOR_INJECTED), undefined);
    await page.getByRole('button', { name: 'Example 2', exact: true }).click();
    assert.equal(await page.locator('.behavior-state-select').count(), 0);
    assert.match(await page.locator('.behavior-instance .behavior-enumeration').textContent(), /Only state 1 of 3 is displayed.*repeats from state 2/);
    await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('behavior-unavailable-polarity-and-timeout-are-distinct', async () => {
    let mode = 'ORACLE_POSITIVE_UNSAT';
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', route => {
      const payload = route.request().postDataJSON();
      if (['timeout', 'unsupported', 'unavailable'].includes(mode)) return route.fulfill({ json: { exerciseId: payload.exerciseId, revision: payload.revision, status: mode,
        message: 'The predicate introduces String literals outside the oracle sampling universe.' } });
      const data = behaviorResult(payload, null); data.scoreStatus = 'unavailable'; data.scoreReason = mode;
      data.categories.forEach(category => {
        if (category.oracle === (mode === 'ORACLE_POSITIVE_UNSAT')) {
          category.status = 'unsat'; category.instances = []; category.enumerationComplete = true;
        }
      });
      return route.fulfill({ json: data });
    });
    for (mode of ['ORACLE_POSITIVE_UNSAT', 'ORACLE_NEGATIVE_UNSAT']) {
      await submit('some Node'); await waitBehavior();
      assert.equal(await page.locator('.behavior-score').textContent(), 'Unavailable');
      assert.match(await page.locator('.behavior-score-reason').textContent(), /oracle (accepts|rejects) no instance within these bounds/);
      assert.equal(await page.locator('.behavior-category-choice').count(), 4);
      const emptyId = mode === 'ORACLE_POSITIVE_UNSAT' ? 'both' : 'neither';
      await page.locator(`.behavior-category-choice[data-category="${emptyId}"]`).click();
      assert.match(await page.locator('.behavior-empty').textContent(), /No instance within these bounds/);
    }
    for (mode of ['timeout', 'unsupported', 'unavailable']) {
      await submit('some Node'); await waitBehavior(mode === 'timeout' ? 'timeout' : 'error');
      assert.equal(await page.locator('.behavior-category-choice, .behavior-score').count(), 0);
      assert(!(await page.locator('#behavior-result').textContent()).includes('No instance within these bounds'));
      assert.equal(await page.locator('.distance-value').textContent(), '2');
      if (mode === 'unsupported') assert.match(await page.locator('.behavior-message').textContent(), /outside the oracle sampling universe/);
    }
    await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('behavior-pending-does-not-block-structure-and-clears-on-draft-change', async () => {
    let release, started;
    let pending = new Promise(resolve => { release = resolve; });
    const entered = new Promise(resolve => { started = resolve; });
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', async route => {
      started(); await pending;
      try { await route.fulfill({ json: behaviorResult(route.request().postDataJSON()) }); } catch {}
    });
    await submit('some Node'); await entered;
    assert.equal(await feedback.textContent(), 'Checked');
    assert.equal(await page.locator('.distance-value').textContent(), '2');
    assert.equal(await page.locator('#behavior-state').textContent(), 'Analyzing…');
    assert.equal(await page.locator('.behavior-score').count(), 0);
    release(); await waitBehavior(); pending = Promise.resolve();
    pending = new Promise(resolve => { release = resolve; });
    await page.locator('#check-button').click(); await waitChecked();
    assert.equal(await page.locator('.behavior-score, .behavior-category-choice').count(), 0);
    assert.equal(await page.locator('#behavior-state').textContent(), 'Analyzing…');
    release(); await waitBehavior(); pending = Promise.resolve();
    await editor.fill('some Node // edit');
    assert.equal(await page.locator('.behavior-score, .behavior-category-choice').count(), 0);
    await submit('some Node'); await waitBehavior();
    await page.locator('#reset-button').click();
    assert.equal(await page.locator('.behavior-score').count(), 0);
    await submit('some Node'); await waitBehavior();
    await page.locator('[data-exercise-id="graphs-inv2"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv2'));
    assert.equal(await page.locator('.behavior-score').count(), 0);
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
    await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('behavior-stale-results-and-invalid-payloads-cannot-replace-current-draft', async () => {
    let oldStarted;
    const entered = new Promise(resolve => { oldStarted = resolve; });
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', async route => {
      const payload = route.request().postDataJSON(), old = payload.body.includes('older');
      if (old) { oldStarted(); await delay(350); }
      try { await route.fulfill({ json: behaviorResult(payload, old ? 0.111 : 0.988) }); } catch {}
    });
    await submit('some Node // older'); await entered;
    await submit('some Node // current'); await waitBehavior(); await delay(500);
    assert.equal(await page.locator('.behavior-score').textContent(), '0.988');
    await page.unroute('**/api/behavior');
    for (const mutate of [
      data => { data.score = NaN; }, data => { data.score = 2; }, data => { data.score = -1; },
      data => { data.score = '0.8'; }, data => { data.scope.moduleFacts = false; },
      data => { data.categories[0].oracle = false; }, data => { data.categories[0].instances.push(behaviorInstance('extra')); },
      data => { data.categories[0].instances[0].states[0].relations[0].tuples = [['arity mismatch']]; },
      data => { data.categories[0].instances[0].states[0].index = 3; },
      data => { data.revision -= 1; }, data => { delete data.exerciseId; },
    ]) {
      await page.route('**/api/behavior', route => {
        const data = behaviorResult(route.request().postDataJSON()); mutate(data); return route.fulfill({ json: data });
      });
      await submit('some Node'); await waitBehavior('error');
      assert.equal(await page.locator('.behavior-score, .behavior-category-choice').count(), 0);
      assert.equal(await page.locator('.distance-value').textContent(), '2');
      await page.unroute('**/api/behavior');
    }
    await page.unroute('**/api/feedback');
  });
  await check('behavior-mobile-layout-and-table-navigation', async () => {
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', route => {
      const data = behaviorResult(route.request().postDataJSON());
      data.categories[0].instances[0].states[0].relations.push({ label: 'long relation', arity: 3,
        tuples: [['Atom$' + 'a'.repeat(140), 'Node$1', 'Node$2']] });
      return route.fulfill({ json: data });
    });
    await page.setViewportSize({ width: 390, height: 844 });
    await submit('some Node'); await waitBehavior();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    const choice = page.locator('.behavior-category-choice[data-category="overcoverage"]');
    await choice.focus(); await choice.press('Enter');
    assert.equal(await choice.getAttribute('aria-pressed'), 'true');
    await page.getByRole('button', { name: 'Example 2', exact: true }).focus();
    await page.getByRole('button', { name: 'Example 2', exact: true }).press('Enter');
    assert.match(await page.locator('.behavior-signatures').textContent(), /Node\$overcoverage-2/);
    await page.locator('#behavior-card').screenshot({ path: path.join(artifacts, 'behavioral-mobile.png'),
      // A tall element capture can include normally offscreen fixed overlays.
      style: '.skip-link, .toast { visibility: hidden !important; }' });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('luna-learning-hints-cover-every-operation-and-twelve-instances', async () => {
    let releaseBehavior, enterBehavior, checkedBehavior;
    const behaviorGate = new Promise(resolve => { releaseBehavior = resolve; });
    const behaviorEntered = new Promise(resolve => { enterBehavior = resolve; });
    const explanationRequests = [];
    const markup = '<img src=x onerror="window.ITEM_HINT_INJECTED=true">';
    await page.route('**/api/feedback', route => {
      const data = someSourceResult(route.request().postDataJSON());
      data.operations.push({ ...data.operations[0], action: 'Review a second related edit' });
      return route.fulfill({ json: data });
    });
    await page.route('**/api/behavior', async route => {
      checkedBehavior = behaviorResult(route.request().postDataJSON());
      checkedBehavior.categories[0].instances[0] = behaviorInstance('both-1', 2);
      enterBehavior(); await behaviorGate; await route.fulfill({ json: checkedBehavior });
    });
    await page.route('**/api/explain', route => {
      const payload = route.request().postDataJSON(); explanationRequests.push(payload);
      const data = educationResult(payload, { operationIds: ['operation-2', 'operation-1'], prefix: markup,
        summary: 'Compare one highlighted edit with an example before revising your predicate.' });
      data.instances.reverse(); data.text = 'OBSOLETE_FREEFORM_ROUTE_GUIDANCE';
      return route.fulfill({ json: data });
    });
    await submit('some Node'); await behaviorEntered;
    assert.equal(explanationRequests.length, 0);
    assert.equal(await page.locator('.distance-value').textContent(), '1');
    assert.equal(await page.locator('.operation-explanation').count(), 2);
    assert.equal(await page.locator('.operation-explanation .education-description').count(), 0);
    releaseBehavior(); await page.locator('.explanation-text').waitFor();
    assert.equal(explanationRequests.length, 1);
    assert.equal(explanationRequests[0].body, 'some Node');
    assert.equal(explanationRequests[0].behaviorToken, checkedBehavior.behaviorToken);
    for (let index = 1; index <= 2; index += 1) {
      assert((await page.locator(`[data-operation-id="operation-${index}"] .education-description`).textContent()).includes(`operation-${index}`));
    }
    const seen = [];
    for (const category of ['both', 'undercoverage', 'overcoverage', 'neither']) {
      await page.locator(`.behavior-category-choice[data-category="${category}"]`).click();
      for (let index = 1; index <= 3; index += 1) {
        await page.getByRole('button', { name: `Example ${index}`, exact: true }).click();
        const id = `${category}-${index}`;
        const description = await page.locator('.instance-explanation .education-description').textContent();
        assert(description.includes(id)); assert(description.includes(markup)); seen.push(id);
        if (category === 'both' && index === 1) {
          await page.locator('.behavior-state-select').selectOption('1');
          assert.equal(await page.locator('.instance-explanation .education-description').textContent(), description);
        }
      }
    }
    assert.equal(new Set(seen).size, 12);
    await page.locator('.behavior-category-choice[data-category="both"]').click();
    assert((await page.locator('.instance-explanation .education-description').textContent()).includes('both-1'));
    assert.equal(await page.locator('.education-slot img, .education-slot script').count(), 0);
    assert.equal(await page.evaluate(() => window.ITEM_HINT_INJECTED), undefined);
    assert.equal(await page.locator('.explanation-text').textContent(), 'Compare one highlighted edit with an example before revising your predicate.');
    assert(!(await page.locator('body').textContent()).includes('OBSOLETE_FREEFORM_ROUTE_GUIDANCE'));
    await page.locator('.operation-locate').first().click();
    assert.equal(await page.locator('.source-range').textContent(), 'some Node');
    assert.equal(await page.locator('.operation-explanation .education-description').count(), 2);
    await page.unroute('**/api/explain'); await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('luna-rejects-mismatched-token-and-incomplete-item-identities', async () => {
    await page.route('**/api/feedback', route => route.fulfill({ json: someSourceResult(route.request().postDataJSON()) }));
    await page.route('**/api/behavior', route => route.fulfill({ json: behaviorResult(route.request().postDataJSON()) }));
    for (const mutate of [
      data => { data.behaviorToken = '0'.repeat(64); }, data => { delete data.behaviorToken; },
      data => { data.operations.pop(); }, data => { data.operations[0].id = 'operation-99'; },
      data => { data.operations.push(data.operations[0]); }, data => { data.instances.pop(); },
      data => { data.instances[0].id = 'unknown-1'; }, data => { data.instances[11] = data.instances[0]; },
      data => { data.instances[0].description = 'x'.repeat(361); }, data => { data.summary = 'x'.repeat(701); },
      data => { delete data.operations; delete data.instances; delete data.summary; data.text = 'OLD_FREEFORM_ONLY'; },
      data => { data.revision -= 1; }, data => { data.exerciseId = 'graphs-inv8'; },
    ]) {
      await page.route('**/api/explain', route => {
        const data = educationResult(route.request().postDataJSON()); mutate(data); return route.fulfill({ json: data });
      });
      await submit('some Node'); await page.locator('.explanation-unavailable').waitFor();
      assert.equal(await page.locator('.education-description, .explanation-text').count(), 0);
      assert.equal(await page.locator('.distance-value').textContent(), '1');
      assert.equal(await page.locator('.behavior-score').textContent(), '0.667');
      assert.equal(await page.locator('.behavior-category-choice').count(), 4);
      assert.equal(await page.locator('.operation-locate').count(), 1);
      assert(!(await page.locator('body').textContent()).includes('OLD_FREEFORM_ONLY'));
      await page.unroute('**/api/explain');
    }
    await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('luna-explains-operations-after-behavior-failure-without-token', async () => {
    let explanationRequest;
    await page.route('**/api/feedback', route => route.fulfill({ json: someSourceResult(route.request().postDataJSON()) }));
    await page.route('**/api/behavior', route => {
      const payload = route.request().postDataJSON();
      return route.fulfill({ json: { exerciseId: payload.exerciseId, revision: payload.revision, status: 'timeout' } });
    });
    await page.route('**/api/explain', route => {
      explanationRequest = route.request().postDataJSON();
      return route.fulfill({ json: educationResult(explanationRequest, { prefix: 'Operation-only hint' }) });
    });
    await submit('some Node'); await page.locator('.explanation-text').waitFor();
    assert(!('behaviorToken' in explanationRequest));
    assert.match(await page.locator('.operation-explanation .education-description').textContent(), /Operation-only hint/);
    assert.equal(await page.locator('.instance-explanation').count(), 0);
    assert.equal(await page.locator('#behavior-state').textContent(), 'Timed out');
    assert.equal(await page.locator('.distance-value').textContent(), '1');
    await page.unroute('**/api/explain'); await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('luna-stale-instance-hints-clear-on-edit-and-exercise-switch', async () => {
    let oldStarted;
    const started = new Promise(resolve => { oldStarted = resolve; });
    await page.route('**/api/feedback', route => route.fulfill({ json: someSourceResult(route.request().postDataJSON()) }));
    await page.route('**/api/behavior', route => route.fulfill({ json: behaviorResult(route.request().postDataJSON()) }));
    await page.route('**/api/explain', async route => {
      const payload = route.request().postDataJSON(), old = payload.body.includes('older');
      if (old) { oldStarted(); await delay(350); }
      try { await route.fulfill({ json: educationResult(payload, { prefix: old ? 'OBSOLETE_INSTANCE' : 'CURRENT_INSTANCE' }) }); } catch {}
    });
    await submit('some Node // older'); await started;
    await editor.fill('some Node // current');
    assert.equal(await page.locator('.education-description, .explanation-text').count(), 0);
    await page.locator('#check-button').click(); await waitChecked();
    await page.locator('.explanation-text').waitFor(); await delay(500);
    assert.match(await page.locator('.instance-explanation .education-description').textContent(), /CURRENT_INSTANCE/);
    assert(!(await page.locator('body').textContent()).includes('OBSOLETE_INSTANCE'));
    await editor.fill('some Node // unsubmitted');
    assert.equal(await page.locator('.education-slot, .explanation-text').count(), 0);
    await page.locator('[data-exercise-id="graphs-inv2"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv2'));
    assert.equal(await page.locator('.education-slot').count(), 0);
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
    await page.unroute('**/api/explain'); await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('luna-unavailable-and-retry-preserve-checked-items', async () => {
    let available = false;
    let behaviorCalls = 0;
    await page.route('**/api/feedback', route => route.fulfill({ json: someSourceResult(route.request().postDataJSON()) }));
    await page.route('**/api/behavior', route => { behaviorCalls += 1; return route.fulfill({ json: behaviorResult(route.request().postDataJSON()) }); });
    await page.route('**/api/explain', route => {
      const payload = route.request().postDataJSON();
      if (!available) return route.fulfill({ json: {
        exerciseId: payload.exerciseId, revision: payload.revision, status: 'unavailable', message: 'Luna is not configured for this workspace.' } });
      const data = educationResult(payload, { summary: 'Compare an undercoverage example with an overcoverage example, then revisit one highlighted expression.' });
      data.operations[0].description = 'Inspect the highlighted expression. How does its operator affect which cases are accepted?';
      data.instances.forEach(item => {
        const id = item.id.slice(0, item.id.lastIndexOf('-'));
        item.description = {
          both: 'Both checks accept this example. Follow the relation tuples and find what makes it fit your current rule.',
          undercoverage: 'The oracle accepts this example, but your rule rejects it. Which part of your rule might exclude a case that should be allowed?',
          overcoverage: 'Your rule accepts this example, but the oracle rejects it. Look for a condition your rule may be missing.',
          neither: 'Both checks reject this example. Which relation tuple shows why your current rule excludes it?',
        }[id];
      });
      return route.fulfill({ json: data });
    });
    await submit('some Node'); await page.locator('.explanation-unavailable').waitFor();
    assert.equal(await page.locator('.education-description').count(), 0);
    assert.equal(await page.locator('.education-unavailable').count(), 2);
    const before = await page.locator('.behavior-signatures').textContent();
    assert.equal(await page.locator('.distance-value').textContent(), '1');
    assert.equal(await page.locator('.behavior-score').textContent(), '0.667');
    available = true; await page.locator('.explanation-retry').click(); await page.locator('.explanation-text').waitFor();
    assert.equal(behaviorCalls, 1);
    assert.equal(await page.locator('.education-description').count(), 2);
    assert.equal(await page.locator('.behavior-signatures').textContent(), before);
    await page.locator('[data-category="undercoverage"]').click();
    await page.locator('#behavior-card').screenshot({ path: path.join(artifacts, 'luna-instance-hint.png'),
      style: '.skip-link, .toast { visibility: hidden !important; }' });
    await page.locator('.operation-item').screenshot({ path: path.join(artifacts, 'luna-operation-hint.png'),
      style: '.skip-link, .toast { visibility: hidden !important; }' });
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    // Keep the fixed skip link out of Chromium's stitched tall-element capture.
    await page.locator('.skip-link').evaluate(element => { element.hidden = true; });
    await page.locator('#behavior-card').screenshot({ path: path.join(artifacts, 'luna-instance-hint-mobile.png'),
      style: '.skip-link, .toast { visibility: hidden !important; }' });
    await page.locator('.skip-link').evaluate(element => { element.hidden = false; });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.unroute('**/api/explain'); await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('draft-survives-reload-and-exercise-switch', async () => {
    await editor.fill('some Node // browser draft');
    await page.reload();
    await editor.waitFor({ state: 'visible' });
    await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
    assert.equal(await editor.inputValue(), 'some Node // browser draft');
    await page.locator('[data-exercise-id="graphs-inv2"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv2'));
    await editor.fill('no Node // second draft');
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
    assert.equal(await editor.inputValue(), 'some Node // browser draft');
  });
  await check('preserved-environment-and-download', async () => {
    await page.locator('[data-context="after"]').click();
    assert.equal(await page.locator('#environment-code').textContent(), record.environmentAfter);
    await page.locator('[data-context="before"]').click();
    const downloadPromise = page.waitForEvent('download');
    await page.locator('#download-button').click();
    const download = await downloadPromise;
    const contents = await readFile(await download.path(), 'utf8');
    assert.equal(contents, record.environmentBefore + record.predicateHeader + '{\n' + await editor.inputValue() + '\n}' + record.environmentAfter);
    assert(!contents.includes('inv1c'));
  });
  await check('search-filter-reset-and-keyboard', async () => {
    await page.locator('#group-filter').selectOption('graphs');
    assert.equal(await page.locator('.exercise-item').count(), 8);
    await page.locator('#exercise-search').fill('no such exercise');
    assert.equal(await page.locator('.exercise-item').count(), 0);
    await page.locator('#exercise-search').fill('');
    await page.locator('#reset-button').click();
    assert.equal(await editor.inputValue(), record.starter);
    await editor.fill('some Node');
    await editor.press('End'); await editor.press('Tab');
    assert.equal(await editor.inputValue(), 'some Node  ');
    await editor.press('Control+Enter'); await waitChecked();
  });
  let oldStarted;
  await check('stale-feedback-cannot-overwrite-newer-draft', async () => {
    const started = new Promise(resolve => { oldStarted = resolve; });
    await page.route('**/api/feedback', async route => {
      const payload = route.request().postDataJSON();
      const old = payload.body.includes('older');
      if (old) { oldStarted(); await delay(350); }
      try { await route.fulfill({ json: old ? { ...someSourceResult(payload), distance: 99 } : result(payload, 2) }); } catch {}
    });
    await editor.fill('some Node // older'); await page.locator('#check-button').click();
    await started;
    await submit('some Node // newer');
    await delay(500);
    assert.equal(await page.locator('.distance-value').textContent(), '2');
    assert.equal(await page.locator('.operation-locate').count(), 0);
    assert.equal(await page.locator('.canonical-range, .source-range').count(), 0);
    await page.unroute('**/api/feedback');
  });
  await check('stale-luna-output-and-plain-text-rendering', async () => {
    let resolveOld;
    const started = new Promise(resolve => { resolveOld = resolve; });
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 1) }));
    await page.route('**/api/explain', async route => {
      const payload = route.request().postDataJSON();
      const old = payload.body.includes('old guidance');
      if (old) { resolveOld(); await delay(350); }
      try { await route.fulfill({ json: educationResult(payload, {
        summary: old ? 'OBSOLETE_GUIDANCE' : '<img src=x onerror="window.INJECTED=true"> Current guidance.' }) }); } catch {}
    });
    await submit('some Node // old guidance'); await started;
    await submit('some Node // current guidance');
    await page.locator('.explanation-text').waitFor(); await delay(500);
    assert.match(await page.locator('.explanation-text').textContent(), /Current guidance/);
    assert(!((await page.locator('#luna-explanation-body').textContent()).includes('OBSOLETE_GUIDANCE')));
    assert.equal(await page.locator('#luna-explanation-body img').count(), 0);
    assert.equal(await page.evaluate(() => window.INJECTED), undefined);
    await page.unroute('**/api/feedback'); await page.unroute('**/api/explain');
  });
  await check('timeout-busy-and-quota-errors-remain-usable', async () => {
    for (const [status, label] of [['timeout', 'Timed out'], ['busy', 'Server busy']]) {
      await page.route('**/api/feedback', route => route.fulfill({ json: { ...route.request().postDataJSON(), status, diagnostics: [{ message: 'Fixture failure' }] } }));
      await editor.fill('some Node // ' + status); await page.locator('#check-button').click();
      await page.waitForFunction(expected => document.querySelector('#feedback-state').textContent === expected, label);
      assert.equal(await editor.isEnabled(), true);
      await page.unroute('**/api/feedback');
    }
    await page.route('**/api/explain', route => route.fulfill({ json: { ...route.request().postDataJSON(), status: 'unavailable', model: 'gpt-6-luna', message: 'The OpenAI project has no available API credits.' } }));
    await submit('some Node'); await page.locator('.explanation-unavailable').waitFor();
    assert.match(await page.locator('.explanation-unavailable').textContent(), /credits/);
    assert.equal(await feedback.textContent(), 'Checked');
    await page.unroute('**/api/explain');
  });
  await check('non-json-api-errors-preserve-draft-and-identify-http-failure', async () => {
    const privateBody = '<html><h1>PRIVATE_SERVER_DIAGNOSTIC</h1><script>window.LEAKED=true</script></html>';
    for (const [status, contentType, body] of [
      [502, 'text/html', privateBody], [503, 'text/html', privateBody],
      [504, 'text/html', privateBody], [404, 'text/html', privateBody],
      [500, 'text/html', privateBody], [401, 'text/html', privateBody],
      [200, 'text/html', privateBody], [200, 'application/json', '{malformed'],
      [200, 'application/json', 'null'], [204, 'application/json', ''],
    ]) {
      await page.route('**/api/feedback', route => route.fulfill({ status, contentType, body }));
      const draft = `some Node // HTTP ${status}`;
      await editor.fill(draft); await page.locator('#check-button').click();
      await page.waitForFunction(() => document.querySelector('#feedback-state').dataset.state === 'error');
      const message = await page.locator('#feedback-result').textContent();
      assert(message.includes(`HTTP ${status} at /api/feedback`));
      assert(!message.includes('PRIVATE_SERVER_DIAGNOSTIC'));
      assert.equal(await editor.inputValue(), draft);
      assert.equal(await editor.isEnabled(), true);
      assert.equal(await page.evaluate(() => window.LEAKED), undefined);
      await page.unroute('**/api/feedback');
    }
    await submit('adj = ~adj');
    assert.equal(await page.locator('.distance-value').textContent(), '0');
  });
  await check('redirected-guidance-is-reported-and-retry-retains-canonical-feedback', async () => {
    await page.route('**/api/explain', route => route.fulfill({ status: 302, headers: { location: '/index.html' } }));
    await submit('adj = ~adj');
    await page.locator('.explanation-unavailable').waitFor();
    const message = await page.locator('.explanation-unavailable').textContent();
    assert.match(message, /HTTP 200 at \/api\/explain \(redirected\)/);
    assert(!message.includes('<html'));
    assert.equal(await feedback.textContent(), 'Checked');
    assert.equal(await page.locator('.distance-value').textContent(), '0');
    await page.unroute('**/api/explain');
    await page.locator('.explanation-retry').click();
    await page.waitForFunction(() => document.querySelector('.explanation-unavailable')?.textContent.includes('not configured'));
  });
  await check('catalogue-proxy-error-can-be-retried-after-server-recovery', async () => {
    await page.route('**/api/exercises', route => route.fulfill({ status: 502, contentType: 'text/html', body: 'PRIVATE_GATEWAY_PAGE' }));
    await page.reload();
    await page.locator('#startup-error').waitFor({ state: 'visible' });
    assert.match(await page.locator('#startup-error').textContent(), /HTTP 502 at \/api\/exercises/);
    assert(!(await page.locator('body').textContent()).includes('PRIVATE_GATEWAY_PAGE'));
    await page.unroute('**/api/exercises');
    await page.locator('#startup-error button').click();
    await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
    assert.equal(await page.locator('#exercise-count').textContent(), '181');
    assert.equal(await editor.inputValue(), 'adj = ~adj');
    assert.equal(await page.locator('#startup-error').isHidden(), true);
  });
  await check('live-debounce-and-mobile-layout', async () => {
    await page.locator('#live-feedback').check();
    await editor.fill('no Node'); await editor.fill('some Node');
    await waitChecked();
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.screenshot({ path: path.join(artifacts, 'mobile.png'), fullPage: true });
    assert.equal(await editor.isEnabled(), true);
  });
  await check('all-exercise-descriptions-and-temporal-requirement-display', async () => {
    const authored = JSON.parse(await readFile(path.join(root, 'scripts/exercise_descriptions.json'), 'utf8')).descriptions;
    const listing = await (await context.request.get(url + '/api/exercises')).json();
    assert.equal(listing.exercises.length, 181);
    for (const exercise of listing.exercises) {
      assert.equal(exercise.description, authored[exercise.id].description);
      assert(!('oracleBody' in exercise));
    }
    // Select a temporal task with a longer requirement at mobile width. Avoid
    // recomputation: this assertion is about selecting and reading the task.
    await page.locator('#live-feedback').uncheck();
    await page.goto(url + '/?exercise=trash_ltl-inv18');
    await page.waitForFunction(() => document.querySelector('#exercise-title').textContent.includes('inv18'));
    assert.equal(await page.locator('#exercise-description').textContent(), authored['trash_ltl-inv18'].description);
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  });
  await check('iis-prefix-proxy-assets-navigation-feedback-and-download', async () => {
    // Model an IIS virtual application: strip /alloy and rewrite Host to the
    // loopback backend. The browser's Origin remains the public proxy origin.
    let backendURL;
    let backend;
    let proxyContext;
    const proxyRequests = [];
    const proxy = createServer((request, response) => {
      proxyRequests.push(request.url);
      if (!request.url.startsWith('/alloy/')) {
        response.writeHead(404); response.end('Outside the Alloy application'); return;
      }
      if (!backendURL) { response.writeHead(503); response.end(); return; }
      const target = new URL(request.url.slice('/alloy'.length), backendURL);
      const upstream = httpRequest(target, {
        method: request.method,
        headers: { ...request.headers, host: target.host,
          'x-forwarded-host': request.headers.host, 'x-forwarded-proto': 'http' },
      }, reply => {
        response.writeHead(reply.statusCode, reply.headers); reply.pipe(response);
      });
      upstream.on('error', () => { response.writeHead(502); response.end(); });
      request.pipe(upstream);
    });
    await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
    const proxyOrigin = `http://127.0.0.1:${proxy.address().port}`;
    try {
      backend = spawn('python3', ['server.py', '--port', '0', '--public-origin', proxyOrigin], {
        cwd: root, env: { ...process.env, OPENAI_DISABLED: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
      });
      backendURL = await new Promise((resolve, reject) => {
        let output = '';
        const timer = setTimeout(() => reject(new Error('Proxy backend startup timeout')), 10000);
        backend.stdout.on('data', chunk => {
          output += chunk;
          const match = output.match(/http:\/\/127\.0\.0\.1:\d+/);
          if (match) { clearTimeout(timer); resolve(match[0]); }
        });
        backend.on('exit', code => { clearTimeout(timer); reject(new Error(`Proxy backend exited ${code}`)); });
      });
      proxyContext = await browser.newContext({ acceptDownloads: true });
      await proxyContext.route('**/*', route => {
        if (!route.request().url().startsWith(proxyOrigin + '/') && !route.request().url().startsWith('blob:')) {
          externalRequests.push(route.request().url()); return route.abort();
        }
        if (route.request().url().endsWith('/api/behavior')) return mockBehaviorUnavailable(route);
        return route.continue();
      });
      const proxyPage = await proxyContext.newPage();
      proxyPage.on('pageerror', error => errors.push(error.message));
      await proxyPage.goto(proxyOrigin + '/alloy/?exercise=graphs-inv1');
      const checked = async () => proxyPage.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked');
      await checked();
      assert.equal(await proxyPage.locator('#exercise-count').textContent(), '181');
      assert(proxyRequests.includes('/alloy/app.js'));
      assert(proxyRequests.includes('/alloy/styles.css'));
      assert(proxyRequests.includes('/alloy/api/exercises'));
      assert(proxyRequests.includes('/alloy/api/feedback'));
      const proxyEditor = proxyPage.locator('#predicate-editor');
      await proxyPage.locator('#live-feedback').uncheck();
      await proxyEditor.fill('adj = ~adj');
      await proxyPage.locator('#check-button').click();
      await checked();
      assert.equal(await proxyPage.locator('.distance-value').textContent(), '0');
      await proxyPage.locator('[data-exercise-id="graphs-inv5"]').click();
      await proxyPage.waitForFunction(() => location.search.includes('graphs-inv5'));
      assert.equal(new URL(proxyPage.url()).pathname, '/alloy/');
      await proxyEditor.fill('some (iden & adj) // prefix draft');
      await proxyPage.locator('#check-button').click();
      await checked();
      assert.equal(await proxyPage.locator('.replacement-operator code').first().textContent(), 'no');
      const publicRecord = await (await proxyContext.request.get(proxyOrigin + '/alloy/api/exercises/graphs-inv5')).json();
      const downloadPromise = proxyPage.waitForEvent('download');
      await proxyPage.locator('#download-button').click();
      const download = await downloadPromise;
      const contents = await readFile(await download.path(), 'utf8');
      assert.equal(contents, publicRecord.environmentBefore + publicRecord.predicateHeader + '{\n' + await proxyEditor.inputValue() + '\n}' + publicRecord.environmentAfter);
      await proxyPage.reload();
      await proxyPage.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
      assert.equal(await proxyEditor.inputValue(), 'some (iden & adj) // prefix draft');
      assert(proxyRequests.every(request => request.startsWith('/alloy/')));
    } finally {
      await proxyContext?.close();
      backend?.kill('SIGTERM');
      await new Promise(resolve => proxy.close(resolve));
    }
  });
  await check('no-browser-errors-or-external-asset-requests', async () => {
    assert.deepEqual(errors, []);
    assert.deepEqual(externalRequests, []);
  });
  console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));
} finally {
  await browser?.close();
  server.kill('SIGTERM');
}

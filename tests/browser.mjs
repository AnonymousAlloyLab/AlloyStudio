import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile } from 'node:fs/promises';
import { createServer, request as httpRequest } from 'node:http';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { instanceDiagramGeometry } from './instance-graph-geometry.mjs';

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
  requestedMetric: payload.metric || 'canonical',
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
const astResult = payload => {
  const first = payload.body.indexOf('some Node'), last = payload.body.lastIndexOf('Node');
  const ranges = [[first, first + 9], [last, last + 4], [first, first + 9]];
  return { ...payload, body: undefined, requestedMetric: 'ast', metric: 'acgn-raw-ast-zhang-shasha-distance',
    status: 'ok', distance: 3, breakdown: { ast: 3 }, astSize: 5, canonicalForm: [],
    comparison: { strategy: 'nearest-known-correct', poolSize: 2, evaluatedCandidates: 2, complete: true },
    operations: ['replace', 'delete', 'insert'].map((kind, index) => ({
      kind, component: 'ast', path: `ast.child[${index}]`, cost: 1, aggregate: false,
      action: ['Review this operator', 'Review this syntax-tree node', 'Review this insertion context'][index],
      sourceTerm: payload.body.slice(...ranges[index]), sourceRole: kind === 'insert' ? 'insertion-anchor' : 'source',
      sourceLocation: { status: 'located', precision: kind === 'insert' ? 'related' : 'node', coordinateSystem: 'body', offsetEncoding: 'utf-16',
        ranges: [sourceRange(payload.body, ...ranges[index])] },
      canonicalLocation: { status: 'unavailable', ranges: [], reason: 'AST edits refer to the original syntax tree.' },
    })), trace: { cost: 3, matchesDistance: true, hasAggregates: false, certifiedOptimalScript: false } };
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
  requestedMetric: payload.metric || 'canonical',
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

  await check('lean-policy-kernels-control-feedback-and-guidance-publication', async () => {
    const original = await readFile(path.join(root, 'web/app.js'), 'utf8');
    for (const policy of ['feedbackSuccess', 'guidanceSuccess']) {
      const isolated = await browser.newContext();
      try {
        await isolated.route('**/*', route => {
          if (!route.request().url().startsWith(url)) return route.abort();
          return route.continue();
        });
        await isolated.route('**/app.js*', route => route.fulfill({ contentType: 'text/javascript',
          body: original.replace('function verifiedPolicy(name, atoms) {',
            `function verifiedPolicy(name, atoms) { if (name === '${policy}') return false;`) }));
        await isolated.route('**/api/feedback', route => route.fulfill({ json: someSourceResult(route.request().postDataJSON()) }));
        await isolated.route('**/api/behavior', mockBehaviorUnavailable);
        await isolated.route('**/api/explain', route => route.fulfill({ json: educationResult(route.request().postDataJSON()) }));
        const probe = await isolated.newPage();
        await probe.goto(url + '/?exercise=graphs-inv1');
        if (policy === 'feedbackSuccess') {
          await probe.waitForFunction(() => document.querySelector('#feedback-state').dataset.state === 'error');
          assert.equal(await probe.locator('.distance-value, .operation-item, .education-description').count(), 0);
        } else {
          await probe.locator('.explanation-unavailable').waitFor();
          assert.equal(await probe.locator('.distance-value').count(), 1);
          assert.equal(await probe.locator('.education-description, .explanation-text').count(), 0);
        }
      } finally { await isolated.close(); }
    }
  });

  await check('real-engine-initial-load-and-private-projection', async () => {
    await page.goto(url + '/?exercise=graphs-inv1');
    await waitChecked();
    assert.equal(await page.locator('#exercise-count').textContent(), '181');
    assert.equal(await page.getByRole('link', { name: 'Project dashboard' }).getAttribute('href'), './dashboard/');
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
    assert.match(await page.locator('#source-location-status').textContent(), /(?:Related source context|Expression selected by this edit).*Body Ln 1, Col 1.*Model Ln \d+, Col \d+/);
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
  await check('source-locator-rejects-invalid-ranges', async () => {
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
  });
  await check('structural-node-locator-selects-repeated-occurrence-and-rejects-ambiguity', async () => {
    const body = 'some Node and some Node';
    const canonical = '(some Node) and (some Node)';
    const start = body.lastIndexOf('some Node'), canonicalStart = canonical.lastIndexOf('some Node');
    let invalid = '';
    await page.route('**/api/feedback', route => {
      const data = sourceResult(route.request().postDataJSON(), [[start, start + 9]], { precision: 'node' });
      data.canonicalForm = [canonical];
      data.operations[0].canonicalLocation = { status: 'located', precision: 'node', coordinateSystem: 'canonical', offsetEncoding: 'utf-16',
        ranges: [{ formIndex: 0, start: canonicalStart, end: canonicalStart + 9, text: 'some Node' }] };
      if (invalid === 'source') {
        data.operations[0].sourceLocation.status = 'ambiguous';
        data.operations[0].sourceLocation.ranges.push(sourceRange(body, 0, 9));
      } else if (invalid === 'canonical') {
        data.operations[0].canonicalLocation.status = 'ambiguous';
        data.operations[0].canonicalLocation.ranges.push({ formIndex: 0, start: 1, end: 10, text: 'some Node' });
      }
      return route.fulfill({ json: data });
    });
    await submit(body); await page.locator('.operation-select').click();
    assert.equal(await page.locator('.operation-locate').count(), 1);
    assert.equal(await page.locator('.source-range').count(), 1);
    assert.equal(await page.locator('.canonical-range').count(), 1);
    assert.deepEqual(await editor.evaluate(element => [element.selectionStart, element.selectionEnd]), [start, start + 9]);
    assert.equal(await page.locator('.canonical-range').evaluate(mark => mark.previousSibling.textContent.length), canonicalStart);
    assert.match(await page.locator('#source-location-status').textContent(), /Expression selected by this edit/);
    assert.match(await page.locator('#canonical-location-status').textContent(), /Expression selected by this edit/);
    for (invalid of ['source', 'canonical']) {
      await submit(body); await page.locator('.operation-select').click();
      if (invalid === 'source') {
        assert.equal(await page.locator('.operation-locate, .source-range').count(), 0);
        assert.equal(await page.locator('.canonical-range').count(), 1);
        assert.match(await page.locator('#source-location-status').textContent(), /Source location unavailable/);
      } else {
        assert.equal(await page.locator('.canonical-range').count(), 0);
        assert.equal(await page.locator('.source-range').count(), 1);
        assert.match(await page.locator('#canonical-location-status').textContent(), /Canonical form location unavailable/);
      }
    }
    await page.unroute('**/api/feedback');
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
  await check('metric-switch-keeps-ast-steps-guidance-and-history-separate', async () => {
    const metric = page.locator('#distance-metric');
    const requests = [], behaviorRequests = [];
    await page.route('**/api/feedback', route => {
      const payload = route.request().postDataJSON(); requests.push(payload);
      return route.fulfill({ json: payload.metric === 'ast' ? astResult(payload) : someSourceResult(payload) });
    });
    await page.route('**/api/behavior', route => {
      behaviorRequests.push(route.request().postDataJSON()); return mockBehaviorUnavailable(route);
    });
    await page.route('**/api/explain', route => {
      const payload = route.request().postDataJSON();
      return route.fulfill({ json: educationResult(payload, { prefix: `${payload.metric} learning hint`,
        operationIds: payload.metric === 'ast' ? ['operation-1', 'operation-2', 'operation-3'] : ['operation-1'] }) });
    });
    const body = 'some Node and some Node';
    await submit(body); await page.locator('.explanation-text').waitFor();
    const canonicalHistory = await page.evaluate(() => localStorage.getItem('alloy-studio:v1:history:graphs-inv1'));
    await metric.focus(); await metric.selectOption('ast'); await waitChecked();
    await page.locator('.operation-explanation .education-description').first().waitFor();
    assert.equal(await metric.inputValue(), 'ast');
    assert.equal(await editor.inputValue(), body);
    assert.equal(await page.locator('.distance-value').textContent(), '3');
    assert.equal(await page.locator('.distance-result').getAttribute('data-metric'), 'ast');
    assert.equal(await page.locator('.component-name').textContent(), 'Syntax tree edits');
    assert.equal(await page.locator('#canonical-panel').isHidden(), true);
    assert.match(await page.locator('#metric-description').textContent(), /Zhang–Shasha.*one edit/);
    assert.match(await page.locator('#history-heading').textContent(), /AST progress/);
    assert.equal(await page.locator('.operation-explanation .education-description').count(), 3);
    for (let index = 0; index < 3; index += 1) {
      const operation = page.locator('.operation-item').nth(index);
      assert.match(await operation.locator('.education-description').textContent(), new RegExp(`ast learning hint.*operation-${index + 1}`));
      assert.match(await operation.locator('.operation-fragment-label').textContent(), /your code/);
      await operation.locator('.operation-select').click();
      const expected = astResult({ body }).operations[index].sourceLocation.ranges[0];
      assert.deepEqual(await editor.evaluate(element => [element.selectionStart, element.selectionEnd]), [expected.start, expected.end]);
      assert.equal(await page.locator('.source-range').textContent(), expected.text);
      assert.equal(await page.locator('.canonical-range').count(), 0);
    }
    assert.equal(await page.evaluate(() => localStorage.getItem('alloy-studio:v1:history:graphs-inv1')), canonicalHistory);
    const astHistory = await page.evaluate(() => localStorage.getItem('alloy-studio:v1:history:graphs-inv1:ast'));
    assert(JSON.parse(astHistory).every(item => item.distance === 3 && item.basis === 'nearest-known-correct-raw-ast-v1'));
    assert(behaviorRequests.every(payload => !('metric' in payload)));
    await page.locator('.operation-select').first().click();
    // Hide fixed overlays during Chromium's stitched element captures.
    const hidden = await page.locator('.skip-link, #toast').evaluateAll(elements => elements.map(element => {
      const previous = element.hidden; element.hidden = true; return previous;
    }));
    try {
      await page.screenshot({ path: path.join(artifacts, 'ast-distance.png'), fullPage: true });
      await page.setViewportSize({ width: 390, height: 844 });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
      await page.locator('.feedback-card').screenshot({ path: path.join(artifacts, 'ast-distance-mobile.png') });
    } finally {
      await page.locator('.skip-link, #toast').evaluateAll((elements, previous) => elements.forEach((element, index) => { element.hidden = previous[index]; }), hidden);
      await page.setViewportSize({ width: 1440, height: 1000 });
    }
    await page.reload(); await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
    assert.equal(await metric.inputValue(), 'ast');
    assert.equal(await editor.inputValue(), body);
    assert.match(await page.locator('#history-list [role="img"]').getAttribute('aria-label'), /Recent ast distances: 3/);
    await metric.selectOption('canonical'); await waitChecked(); await page.locator('.explanation-text').waitFor();
    assert.equal(await page.locator('.distance-value').textContent(), '1');
    assert.equal(await page.locator('#canonical-panel').isVisible(), true);
    assert.equal(await page.evaluate(() => localStorage.getItem('alloy-studio:v1:history:graphs-inv1:ast')), astHistory);
    assert.equal(requests.at(-1).metric, 'canonical');
    assert.equal(await page.locator('.source-range, .canonical-range').count(), 0);
    await page.unroute('**/api/explain'); await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('metric-switch-discards-stale-feedback-and-learning-hints', async () => {
    const metric = page.locator('#distance-metric');
    let enterFeedback, releaseFeedback, enterExplanation, releaseExplanation;
    const feedbackEntered = new Promise(resolve => { enterFeedback = resolve; });
    const feedbackGate = new Promise(resolve => { releaseFeedback = resolve; });
    const explanationEntered = new Promise(resolve => { enterExplanation = resolve; });
    const explanationGate = new Promise(resolve => { releaseExplanation = resolve; });
    let holdFeedback = true, holdExplanation = false;
    await page.route('**/api/feedback', async route => {
      const payload = route.request().postDataJSON();
      const data = payload.metric === 'ast' ? astResult(payload) : someSourceResult(payload);
      if (payload.metric === 'canonical' && holdFeedback) {
        holdFeedback = false; enterFeedback(); await feedbackGate; data.distance = 91;
      }
      try { await route.fulfill({ json: data }); } catch {}
    });
    await page.route('**/api/explain', async route => {
      const payload = route.request().postDataJSON();
      if (payload.metric === 'canonical' && holdExplanation) { enterExplanation(); await explanationGate; }
      const data = educationResult(payload, { prefix: payload.metric === 'ast' ? 'CURRENT_AST_HINT' : 'OLD_CANONICAL_HINT',
        operationIds: payload.metric === 'ast' ? ['operation-1', 'operation-2', 'operation-3'] : ['operation-1'] });
      try { await route.fulfill({ json: data }); } catch {}
    });
    await editor.fill('some Node // metric race'); await page.locator('#check-button').click(); await feedbackEntered;
    await metric.selectOption('ast'); await waitChecked(); await page.locator('.explanation-text').waitFor();
    releaseFeedback(); await delay(100);
    assert.equal(await page.locator('.distance-value').textContent(), '3');
    assert.match(await page.locator('.education-description').first().textContent(), /CURRENT_AST_HINT/);
    holdExplanation = true;
    await metric.selectOption('canonical'); await waitChecked(); await explanationEntered;
    await metric.selectOption('ast'); await waitChecked(); await page.locator('.explanation-text').waitFor();
    releaseExplanation(); await delay(100);
    assert.equal(await page.locator('.distance-value').textContent(), '3');
    assert.equal(await page.locator('.operation-explanation .education-description').count(), 3);
    assert(!(await page.locator('body').textContent()).includes('OLD_CANONICAL_HINT'));
    holdExplanation = false;
    await metric.selectOption('canonical'); await waitChecked(); await page.locator('.explanation-text').waitFor();
    await page.unroute('**/api/explain'); await page.unroute('**/api/feedback');
  });
  await check('successful-feedback-requires-every-identity-echo-in-both-metrics', async () => {
    const metric = page.locator('#distance-metric');
    const ids = { canonical: 'acgn-fast-rewrite-canonical-distance', ast: 'acgn-raw-ast-zhang-shasha-distance' };
    let mutate = null, behaviorCalls = 0, explanationCalls = 0;
    await page.route('**/api/feedback', route => {
      const payload = route.request().postDataJSON();
      const data = payload.metric === 'ast' ? astResult(payload) : someSourceResult(payload);
      mutate?.(data);
      return route.fulfill({ json: data });
    });
    await page.route('**/api/behavior', route => { behaviorCalls += 1; return mockBehaviorUnavailable(route); });
    await page.route('**/api/explain', route => {
      explanationCalls += 1;
      const payload = route.request().postDataJSON();
      return route.fulfill({ json: educationResult(payload, {
        operationIds: payload.metric === 'ast' ? ['operation-1', 'operation-2', 'operation-3'] : ['operation-1'],
      }) });
    });
    for (const selected of ['canonical', 'ast']) {
      if (await metric.inputValue() !== selected) {
        await metric.selectOption(selected); await waitChecked(); await page.locator('.explanation-text').waitFor();
      }
      await submit(`some Node // accepted ${selected} baseline`); await page.locator('.explanation-text').waitFor();
      const historyKey = `alloy-studio:v1:history:graphs-inv1${selected === 'ast' ? ':ast' : ''}`;
      const history = await page.evaluate(key => localStorage.getItem(key), historyKey);
      const calls = [behaviorCalls, explanationCalls];
      const other = selected === 'canonical' ? 'ast' : 'canonical';
      for (const field of ['exerciseId', 'revision', 'requestedMetric', 'metric']) {
        for (const fault of ['missing', 'mismatched']) {
          mutate = data => {
            if (fault === 'missing') delete data[field];
            else data[field] = field === 'exerciseId' ? 'graphs-inv8'
              : field === 'revision' ? data.revision - 1 : field === 'requestedMetric' ? other : ids[other];
          };
          await editor.fill(`some Node // rejected ${selected} ${field} ${fault}`);
          await page.locator('#check-button').click();
          await page.waitForFunction(() => document.querySelector('#feedback-state').dataset.state === 'error');
          assert.match(await page.locator('#feedback-result').textContent(),
            field === 'exerciseId' || field === 'revision' ? /different draft/ : /different comparison method/,
            `${selected} ${field} ${fault}`);
          assert.equal(await page.locator('.distance-value, .operation-item, .education-description, .source-range, .canonical-range').count(), 0);
          assert.equal(await page.locator('#canonical-content pre').count(), 0);
          assert.deepEqual([behaviorCalls, explanationCalls], calls, 'Rejected success must not start downstream analysis');
          assert.equal(await page.evaluate(key => localStorage.getItem(key), historyKey), history,
            'Rejected success must not enter progress history');
        }
      }
      mutate = null;
      await submit(`some Node // accepted ${selected} exact echoes`); await page.locator('.explanation-text').waitFor();
      assert.equal(await page.locator('.distance-value').textContent(), selected === 'ast' ? '3' : '1');
      await page.locator('.operation-select').first().click();
      assert.equal(await page.locator('.source-range').textContent(), 'some Node');
    }
    await metric.selectOption('canonical'); await waitChecked(); await page.locator('.explanation-text').waitFor();
    await page.unroute('**/api/explain'); await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('successful-guidance-requires-every-identity-echo-in-both-metrics', async () => {
    const metric = page.locator('#distance-metric');
    let mutate = null;
    await page.route('**/api/feedback', route => {
      const payload = route.request().postDataJSON();
      return route.fulfill({ json: payload.metric === 'ast' ? astResult(payload) : someSourceResult(payload) });
    });
    await page.route('**/api/behavior', route => route.fulfill({ json: behaviorResult(route.request().postDataJSON()) }));
    await page.route('**/api/explain', route => {
      const payload = route.request().postDataJSON();
      const data = educationResult(payload, {
        operationIds: payload.metric === 'ast' ? ['operation-1', 'operation-2', 'operation-3'] : ['operation-1'],
        prefix: mutate ? 'REJECTED_RESPONSE_GUIDANCE' : 'Current checked guidance',
        summary: mutate ? 'REJECTED_RESPONSE_GUIDANCE must never be displayed.' : 'Inspect one selected expression and compare its example.',
      });
      mutate?.(data);
      return route.fulfill({ json: data });
    });
    for (const selected of ['canonical', 'ast']) {
      if (await metric.inputValue() !== selected) {
        await metric.selectOption(selected); await waitChecked(); await page.locator('.explanation-text').waitFor();
      }
      const other = selected === 'canonical' ? 'ast' : 'canonical';
      for (const field of ['exerciseId', 'revision', 'requestedMetric', 'behaviorToken']) {
        for (const fault of ['missing', 'mismatched']) {
          mutate = data => {
            if (fault === 'missing') delete data[field];
            else data[field] = field === 'exerciseId' ? 'graphs-inv8'
              : field === 'revision' ? data.revision - 1 : field === 'requestedMetric' ? other : '0'.repeat(64);
          };
          await submit(`some Node // guidance ${selected} ${field} ${fault}`);
          await page.locator('.explanation-unavailable').waitFor();
          assert.equal(await page.locator('.education-description, .explanation-text').count(), 0, `${selected} ${field} ${fault}`);
          assert(!(await page.locator('body').textContent()).includes('REJECTED_RESPONSE_GUIDANCE'));
          assert.equal(await page.locator('.distance-value').textContent(), selected === 'ast' ? '3' : '1');
          assert.equal(await page.locator('.operation-item').count(), selected === 'ast' ? 3 : 1);
          assert.equal(await page.locator('.behavior-score').textContent(), '0.667');
          assert.equal(await page.locator('.behavior-category-choice').count(), 4);
          await page.locator('.operation-select').first().click();
          assert.equal(await page.locator('.source-range').textContent(), 'some Node');
        }
      }
      mutate = null;
      await submit(`some Node // accepted ${selected} guidance`); await page.locator('.explanation-text').waitFor();
      assert.equal(await page.locator('.operation-explanation .education-description').count(), selected === 'ast' ? 3 : 1);
      assert.equal(await page.locator('.instance-explanation .education-description').count(), 1);
    }
    await metric.selectOption('canonical'); await waitChecked(); await page.locator('.explanation-text').waitFor();
    await page.unroute('**/api/explain'); await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('strict-success-echo-checks-preserve-compatible-error-responses', async () => {
    const metric = page.locator('#distance-metric');
    let failedFeedback = false;
    await page.route('**/api/feedback', route => {
      const payload = route.request().postDataJSON();
      if (failedFeedback) return route.fulfill({ json: { status: 'timeout',
        ...(payload.metric === 'ast' ? { requestedMetric: 'ast' } : {}),
        diagnostics: [{ message: 'Legacy timeout remains visible.' }] } });
      return route.fulfill({ json: payload.metric === 'ast' ? astResult(payload) : someSourceResult(payload) });
    });
    await page.route('**/api/explain', route => {
      const payload = route.request().postDataJSON();
      return route.fulfill({ json: { status: 'unavailable', exerciseId: payload.exerciseId, revision: payload.revision,
        ...(payload.metric === 'ast' ? { requestedMetric: 'ast' } : {}),
        message: 'Legacy unavailable guidance remains visible.' } });
    });
    for (const selected of ['canonical', 'ast']) {
      if (await metric.inputValue() !== selected) {
        await metric.selectOption(selected); await waitChecked(); await page.locator('.explanation-unavailable').waitFor();
      }
      failedFeedback = true;
      await editor.fill(`some Node // ${selected} legacy timeout`); await page.locator('#check-button').click();
      await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Timed out');
      assert.match(await page.locator('#feedback-result').textContent(), /Legacy timeout remains visible/);
      assert.equal(await page.locator('.operation-item, .distance-value, .education-description').count(), 0);
      assert.equal(await editor.isEnabled(), true);
      failedFeedback = false;
      await submit(`some Node // ${selected} legacy guidance`); await page.locator('.explanation-unavailable').waitFor();
      assert.match(await page.locator('.explanation-unavailable').textContent(), /Legacy unavailable guidance remains visible/);
      assert.equal(await page.locator('.distance-value').textContent(), selected === 'ast' ? '3' : '1');
    }
    await metric.selectOption('canonical'); await waitChecked(); await page.locator('.explanation-unavailable').waitFor();
    await page.unroute('**/api/explain'); await page.unroute('**/api/feedback');
  });
  await check('real-ast-distance-shows-atomic-steps-and-source-location', async () => {
    await page.locator('[data-exercise-id="graphs-inv5"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv5'));
    await editor.fill('some Node');
    await page.locator('#distance-metric').selectOption('ast'); await waitChecked();
    const distance = Number(await page.locator('.distance-value').textContent());
    assert(Number.isInteger(distance) && distance > 0);
    assert.equal(await page.locator('.operation-item').count(), distance);
    assert((await page.locator('.operation-cost').allTextContents()).every(text => text === '1 edit cost'));
    assert.equal(await page.locator('#canonical-panel').isHidden(), true);
    await page.locator('.operation-locate').first().click();
    assert((await page.locator('.source-range').textContent()).length > 0);
    assert.equal(await page.locator('.canonical-range').count(), 0);
    await page.locator('.explanation-unavailable').waitFor();
    assert.equal(await page.locator('.distance-value').textContent(), String(distance));
    await page.locator('#distance-metric').selectOption('canonical'); await waitChecked();
    await page.locator('[data-exercise-id="graphs-inv1"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv1'));
  });
  await check('real-behavior-score-and-four-bounded-categories', async () => {
    let realBehavior;
    await page.route('**/api/behavior', async route => {
      const response = await route.fetch();
      realBehavior = await response.json();
      return route.fulfill({ response });
    });
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
      for (let index = 0; index < examples; index += 1) {
        await page.getByRole('button', { name: `Example ${index + 1}`, exact: true }).click();
        const stateData = realBehavior.categories.find(category => category.id === id).instances[index].states[0];
        const expectedAtoms = [...new Set([...stateData.signatures.flatMap(signature => signature.atoms),
          ...stateData.relations.flatMap(relation => relation.tuples.flat())])].sort();
        assert.equal(await page.locator('.instance-graph').count(), 1);
        assert.equal(await page.locator('.instance-graph').isVisible(), true);
        assert.deepEqual(await page.locator('.instance-graph [data-atom]').evaluateAll(nodes =>
          nodes.map(node => node.dataset.atom).sort()), expectedAtoms);
        assert.equal(await page.locator('.instance-graph [data-tuple-id]').count(),
          stateData.relations.reduce((count, relation) => count + relation.tuples.length, 0));
        assert.equal(await page.locator('.instance-graph-limit').count(), 0);
      }
    }
    await page.locator('.behavior-category-choice[data-category="undercoverage"]').click();
    await page.locator('#behavior-card').screenshot({ path: path.join(artifacts, 'behavioral-examples.png') });
    await page.locator('#behavior-card').screenshot({ path: path.join(artifacts, 'instance-graph-real-desktop.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    await page.waitForFunction(() => {
      const viewport = document.querySelector('.instance-graph-scroll');
      return viewport && (viewport.scrollWidth <= viewport.clientWidth + 1
        || Math.abs(viewport.scrollLeft - (viewport.scrollWidth - viewport.clientWidth) / 2) <= 2);
    });
    assert(await page.locator('.instance-graph-scroll').evaluate(viewport => {
      const bounds = viewport.getBoundingClientRect();
      return [...viewport.querySelectorAll('[data-atom]')].some(atom => {
        const rect = atom.getBoundingClientRect();
        return rect.left >= bounds.left && rect.right <= bounds.right;
      });
    }), 'The real example must show an object immediately on a narrow screen, before scrolling');
    const hiddenOverlays = await page.locator('.skip-link, #toast').evaluateAll(elements => elements.map(element => {
      const previous = element.hidden; element.hidden = true; return previous;
    }));
    try {
      await page.locator('#behavior-card').screenshot({ path: path.join(artifacts, 'instance-graph-real-mobile.png'),
        style: '.skip-link, .toast { visibility: hidden !important; }' });
    } finally {
      await page.locator('.skip-link, #toast').evaluateAll((elements, previous) => elements.forEach((element, index) => {
        element.hidden = previous[index];
      }), hiddenOverlays);
    }
    await page.setViewportSize({ width: 1440, height: 1000 });
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
      for (let example = 1; example <= 3; example += 1) {
        await page.getByRole('button', { name: `Example ${example}`, exact: true }).click();
        assert.equal(await page.locator('.instance-graph').count(), 1);
        assert.equal(await page.locator('.instance-graph').isVisible(), true);
        assert.deepEqual(await page.locator('.instance-graph [data-atom]').evaluateAll(nodes =>
          nodes.map(node => node.dataset.atom).sort()), [`Node$${id}-${example}`, 'State$0']);
        const edge = page.locator('.instance-graph-edge');
        assert.equal(await edge.count(), 1);
        assert.equal(await edge.getAttribute('data-source'), `Node$${id}-${example}`);
        assert.equal(await edge.getAttribute('data-target'), 'State$0');
      }
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
    assert((await page.locator('.instance-graph [data-atom]').evaluateAll(nodes => nodes.map(node => node.dataset.atom))).includes('State$2'));
    assert(!(await page.locator('.instance-graph [data-atom]').evaluateAll(nodes => nodes.map(node => node.dataset.atom))).includes('State$0'));
    assert.equal(await page.locator('.instance-graph script, .instance-graph img, .instance-graph foreignObject').count(), 0);
    assert.equal(await page.locator('.instance-graph [onerror], .instance-graph [onclick]').count(), 0);
    assert.equal(await page.locator('#behavior-result img, #behavior-result script').count(), 0);
    assert.equal(await page.evaluate(() => window.BEHAVIOR_INJECTED), undefined);
    await page.getByRole('button', { name: 'Example 2', exact: true }).click();
    assert.equal(await page.locator('.behavior-state-select').count(), 0);
    assert.match(await page.locator('.behavior-instance .behavior-enumeration').textContent(), /Only state 1 of 3 is displayed.*repeats from state 2/);
    await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('instance-graph-preserves-membership-tuple-order-loops-and-integer-values', async () => {
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', route => {
      const data = behaviorResult(route.request().postDataJSON());
      data.categories[0].instances[0].states[0] = { index: 0,
        signatures: [{ label: 'Thing', atoms: ['Thing$0', 'Thing$1', 'Thing$2'] }, { label: 'Chosen', atoms: ['Thing$0'] }],
        relations: [{ label: 'self', arity: 2, tuples: [['Thing$0', 'Thing$0']] },
          { label: 'marked', arity: 1, tuples: [['Thing$1']] },
          { label: 'position', arity: 3, tuples: [['Thing$0', '-2', 'Thing$1'], ['Thing$1', '1', 'Thing$0']] },
          { label: 'empty', arity: 2, tuples: [] }] };
      return route.fulfill({ json: data });
    });
    await submit('some Node'); await waitBehavior();
    assert.deepEqual(await page.locator('.instance-graph [data-atom]').evaluateAll(nodes => nodes.map(node => node.dataset.atom).sort()),
      ['-2', '1', 'Thing$0', 'Thing$1', 'Thing$2']);
    assert.equal(await page.locator('.instance-graph [data-atom="Thing$0"]').count(), 1, 'Overlapping signature membership must not duplicate an atom');
    assert.equal(await page.locator('.instance-graph [data-atom="Thing$2"]').count(), 1, 'Disconnected atoms remain visible');
    const loop = page.locator('.instance-graph-edge[data-source="Thing$0"][data-target="Thing$0"]');
    assert.equal(await loop.count(), 1);
    assert(await loop.evaluate(edge => {
      const length = edge.getTotalLength(), first = edge.getPointAtLength(0), last = edge.getPointAtLength(length);
      return length > Math.hypot(last.x - first.x, last.y - first.y) + 10;
    }), 'A self relation must visibly leave and return to its object, rather than collapse into a line');
    const unary = page.locator('.instance-graph [data-tuple-id="tuple-1-0"]');
    assert.equal(await unary.getAttribute('data-arity'), '1');
    const ternary = page.locator('.instance-graph [data-tuple-id="tuple-2-0"]');
    assert.equal(await ternary.getAttribute('data-arity'), '3');
    assert.deepEqual(await ternary.locator('.instance-graph-column-label').evaluateAll(nodes => nodes.map(node => node.dataset.column)), ['1', '2', '3']);
    await ternary.focus(); await ternary.press('Enter');
    const details = await page.locator('.instance-graph-details').textContent();
    assert.match(details, /position/);
    assert(details.indexOf('Thing$0') < details.indexOf('-2') && details.indexOf('-2') < details.indexOf('Thing$1'),
      'The selected tuple description must preserve all three column positions');
    const atom = page.locator('.instance-graph [data-atom="Thing$0"]');
    await atom.focus(); await atom.press('Space');
    assert.match(await page.locator('.instance-graph-details').textContent(), /Thing\$0/);
    assert.match(await page.locator('.instance-graph-details').textContent(), /Chosen/);
    assert.match(await page.locator('.instance-graph-details').textContent(), /Thing/);
    await page.locator('.instance-graph-relation-filter').selectOption('1');
    assert.equal(await page.locator('.instance-graph [data-tuple-id]').count(), 1);
    assert.equal(await page.locator('.instance-graph [data-tuple-id="tuple-1-0"]').count(), 1);
    await page.locator('.instance-graph-relation-filter').selectOption('3');
    assert.equal(await page.locator('.instance-graph [data-tuple-id]').count(), 0);
    assert.equal(await page.locator('.instance-graph [data-atom="Thing$2"]').count(), 1);
    await page.locator('.instance-graph-relation-filter').selectOption('all');
    assert.equal(await page.locator('.instance-graph [data-tuple-id]').count(), 4);
    await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
  });
  await check('instance-graph-routing-does-not-cross-unrelated-atoms', async () => {
    // Synthetic size-three signatures are compatible with the portal's bounds.
    // These expose loops hidden behind another row and nonadjacent grid edges
    // passing through an atom that is not an endpoint of the selected tuple.
    const signatures = ['A', 'B', 'C'].map(label => ({ label,
      atoms: [0, 1, 2].map(index => `${label}$${index}`) }));
    const cases = [
      [{ label: 'A.r', arity: 2, tuples: [['A$0', 'A$0']] },
        { label: 'A.s', arity: 2, tuples: [['A$0', 'A$0']] }],
      [{ label: 'A.r', arity: 2, tuples: [['A$0', 'A$1'], ['A$1', 'A$2'], ['A$0', 'A$2']] }],
      [{ label: 'A.r', arity: 3, tuples: [['A$0', 'A$0', 'A$2'], ['B$0', 'C$2', 'B$0']] }],
    ];
    let relations;
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', route => {
      const data = behaviorResult(route.request().postDataJSON());
      data.categories[0].instances[0].states[0] = { index: 0, signatures, relations };
      return route.fulfill({ json: data });
    });
    try {
      for (const [caseIndex, fixture] of cases.entries()) {
        relations = fixture;
        await submit(`some Node // routing fixture ${caseIndex}`); await waitBehavior();
        const expectedEdges = relations.reduce((sum, relation) => sum
          + relation.tuples.length * (relation.arity === 2 ? 1 : relation.arity), 0);
        assert.equal(await page.locator('.instance-graph-edge').count(), expectedEdges,
          'Every tuple column connection must remain present after routing');
        const collisions = await page.locator('.instance-graph-canvas').evaluate(canvas => {
          const obstacles = [...canvas.querySelectorAll('.instance-graph-node > rect, .instance-graph-junction > rect')];
          const failures = [];
          for (const edge of canvas.querySelectorAll('.instance-graph-edge')) {
            const tuple = edge.closest('.instance-graph-tuple');
            const length = edge.getTotalLength();
            const samples = Math.max(1, Math.ceil(length / 2));
            for (const rectangle of obstacles) {
              const atom = rectangle.closest('.instance-graph-node')?.dataset.atom;
              const junction = rectangle.closest('.instance-graph-junction');
              if (atom !== undefined && (atom === edge.dataset.source || atom === edge.dataset.target)) continue;
              if (junction && junction.closest('.instance-graph-tuple') === tuple) continue;
              const bounds = rectangle.getBoundingClientRect();
              for (let index = 1; index < samples; index += 1) {
                const point = edge.getPointAtLength(length * index / samples).matrixTransform(edge.getScreenCTM());
                if (point.x > bounds.left + 1 && point.x < bounds.right - 1
                  && point.y > bounds.top + 1 && point.y < bounds.bottom - 1) {
                  failures.push({ tuple: tuple.dataset.tupleId, column: edge.dataset.column || null,
                    obstacle: atom ?? junction.closest('.instance-graph-tuple').dataset.tupleId });
                  break;
                }
              }
            }
          }
          return failures;
        });
        assert.deepEqual(collisions, [], `Routing fixture ${caseIndex} must not imply connections through unrelated objects`);
      }
    } finally {
      await page.unroute('**/api/behavior'); await page.unroute('**/api/feedback');
    }
  });
  await check('complex-instance-layout-keeps-objects-labels-and-routes-separated', async () => {
    const fixture = JSON.parse(await readFile(path.join(root, 'tests/fixtures/production-line-inv3-instances.json'), 'utf8'));
    assert.equal(fixture.provenance.exerciseId, 'productionLineNew-inv3');
    assert.equal(fixture.examples.length, 6);
    // Long names, parallel and reverse relations, loops, and repeated tuple
    // columns stress label measurement and route separation independently of
    // the six real solver examples above.
    const atoms = ['Machine_With_A_Very_Long_Name$0', 'Machine_With_A_Very_Long_Name$1', 'Machine_With_A_Very_Long_Name$2'];
    const stress = { index: 0,
      signatures: [{ label: 'Machine_With_A_Very_Long_Name', atoms },
        { label: 'Production_Equipment_With_A_Long_Name', atoms: [atoms[0], atoms[2]] }],
      relations: [
        ...Array.from({ length: 8 }, (_, index) => ({ label: `ProductionLine.long_parallel_relation_${index}`,
          arity: 2, tuples: [[atoms[0], atoms[1]], [atoms[1], atoms[0]], [atoms[2], atoms[2]]] })),
        { label: 'Repeated_column_positions_remain_distinguishable', arity: 4,
          tuples: [[atoms[0], atoms[0], atoms[2], atoms[0]], [atoms[1], atoms[2], atoms[1], atoms[2]]] },
      ] };
    const maximalAtoms = Array.from({ length: 40 }, (_, index) => `Part$${index}`);
    const maximal = { index: 0, signatures: [{ label: 'Part', atoms: maximalAtoms }],
      relations: Array.from({ length: 6 }, (_, relation) => ({ label: `Eight_column_relation_${relation}`, arity: 8,
        tuples: Array.from({ length: 8 }, (_, tuple) => Array.from({ length: 8 }, (_, column) =>
          maximalAtoms[(relation * 7 + tuple * 3 + column * 5) % maximalAtoms.length])) })) };
    const cases = [...fixture.examples.map(example => ({
      name: `${example.category}-${example.example}`, state: example.state })),
    { name: 'bounded-max-arity', state: maximal }, { name: 'long-parallel-repeated', state: stress }];
    const visual = await context.newPage();
    visual.on('pageerror', error => errors.push(error.message));
    try {
      await visual.route(url + '/instance-layout-test', route => route.fulfill({ contentType: 'text/html', body:
        '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">'
        + '<link rel="stylesheet" href="/styles.css"></head><body><main id="layout-probe"></main></body></html>' }));
      await visual.goto(url + '/instance-layout-test');
      await visual.evaluate(() => document.fonts.ready);
      for (const width of [1440, 390]) {
        await visual.setViewportSize({ width, height: 1000 });
        for (const fixtureCase of cases) {
          await visual.evaluate(async state => {
            const { renderInstanceGraph } = await import('/instance-graph.js');
            document.querySelector('#layout-probe').replaceChildren(renderInstanceGraph(state));
            await new Promise(resolve => requestAnimationFrame(resolve));
          }, fixtureCase.state);
          const canvas = visual.locator('.instance-graph-canvas');
          const report = await canvas.evaluate(instanceDiagramGeometry);
          assert(report.objects > 0 && report.texts >= report.objects);
          assert.deepEqual(report.failures, [], `${fixtureCase.name} at ${width}px: objects and labels must be readable without overlaps`);
          const expected = fixtureCase.state.relations.reduce((sum, relation) => sum
            + relation.tuples.length * (relation.arity === 2 ? 1 : relation.arity), 0);
          assert.equal(report.edges, expected, `${fixtureCase.name}: routing must retain every relation connection`);
          const routes = await canvas.locator('.instance-graph-edge').evaluateAll(edges => edges.map(edge => edge.getAttribute('d')));
          assert.equal(new Set(routes).size, routes.length, `${fixtureCase.name}: parallel/repeated connections must not collapse onto the same path`);
          assert(await visual.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
            `${fixtureCase.name}: diagram overflow must stay inside its scroll region`);
          if (width === 390) {
            assert(await visual.locator('.instance-graph-scroll').evaluate(viewport => {
              const bounds = viewport.getBoundingClientRect();
              return [...viewport.querySelectorAll('.instance-graph-node > rect')].some(node => {
                const card = node.getBoundingClientRect();
                return card.left >= bounds.left && card.right <= bounds.right
                  && card.top >= bounds.top && card.bottom <= bounds.bottom;
              });
            }), `${fixtureCase.name}: initial mobile view must show at least one complete object`);
          }
          if (fixtureCase.name === 'both-3') {
            await visual.locator('.instance-graph').screenshot({ path: path.join(artifacts,
              `instance-graph-production-line-${width === 390 ? 'mobile' : 'desktop'}.png`) });
          }
        }
      }
      await visual.locator('.instance-graph-relation-filter').selectOption('8');
      assert.deepEqual((await visual.locator('.instance-graph-canvas').evaluate(instanceDiagramGeometry)).failures, [],
        'Filtering must recompute a collision-free layout');
      assert.equal(await visual.locator('[data-tuple-id]').count(), 2);
    } finally { await visual.close(); }
  });
  await check('instance-graph-empty-and-bounded-dense-states-are-explicit', async () => {
    let dense = false;
    await page.route('**/api/feedback', route => route.fulfill({ json: result(route.request().postDataJSON(), 2) }));
    await page.route('**/api/behavior', route => {
      const data = behaviorResult(route.request().postDataJSON());
      data.categories[0].instances[0].states[0] = dense ? { index: 0,
        signatures: [{ label: 'Many', atoms: Array.from({ length: 55 }, (_, index) => `Many$${index}`) }],
        relations: [{ label: 'links', arity: 2, tuples: Array.from({ length: 70 }, (_, index) => [`Many$${index % 55}`, `Many$${(index + 1) % 55}`]) }] }
        : { index: 0, signatures: [{ label: 'Empty', atoms: [] }], relations: [{ label: 'links', arity: 2, tuples: [] }] };
      return route.fulfill({ json: data });
    });
    await submit('some Node'); await waitBehavior();
    assert.equal(await page.locator('.instance-graph').count(), 1);
    assert.equal(await page.locator('.instance-graph [data-atom], .instance-graph [data-tuple-id]').count(), 0);
    assert.match(await page.locator('.instance-graph').textContent(), /no atoms|no objects|empty/i);
    assert.equal(await page.locator('.instance-graph-limit').count(), 0);
    dense = true; await submit('some Node // dense'); await waitBehavior();
    const atoms = await page.locator('.instance-graph [data-atom]').evaluateAll(nodes => nodes.map(node => node.dataset.atom));
    assert(atoms.length > 0 && atoms.length <= 40);
    assert((await page.locator('.instance-graph [data-tuple-id]').count()) <= 48);
    assert.match(await page.locator('.instance-graph-limit').textContent(), /show|omitt|limit/i);
    assert.match(await page.locator('.behavior-signatures').textContent(), /Many\$54/);
    const edges = await page.locator('.instance-graph-edge').evaluateAll(nodes => nodes.map(node => [node.dataset.source, node.dataset.target]));
    assert(edges.every(edge => edge.every(atom => atoms.includes(atom))), 'Truncated views may not draw edges to missing nodes');
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    const viewport = page.locator('.instance-graph-scroll');
    await viewport.focus();
    assert(await viewport.evaluate(node => node === document.activeElement));
    await viewport.press('ArrowRight');
    await page.setViewportSize({ width: 1440, height: 1000 });
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
    // Serve the actual packaged HTML. An old CDN entry poisons each unversioned
    // asset URL, so this workflow can only pass if new content URLs are used.
    const packagedAsset = name => execFileSync('python3', ['-c',
      'import sys,zipfile; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read("wwwroot/" + sys.argv[2]))',
      process.env.ALLOY_IIS_TEST_ARCHIVE || path.join(root, 'build/iis/alloy-studio-iis.zip'), name]);
    const packagedIndex = packagedAsset('index.html');
    const packagedAssets = new Map(['app.js', 'styles.css', 'instance-graph.js'].map(name => [name, packagedAsset(name)]));
    const assetVersions = new Map([...packagedAssets].map(([name, content]) =>
      [name, createHash('sha256').update(content).digest('hex')]));
    for (const name of ['app.js', 'styles.css']) {
      const version = assetVersions.get(name);
      assert(packagedIndex.includes(Buffer.from(`./${name}?v=${version}`)));
    }
    assert(packagedAssets.get('app.js').includes(Buffer.from(`./instance-graph.js?v=${assetVersions.get('instance-graph.js')}`)));
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
      if (target.pathname === '/' || target.pathname === '/index.html') {
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
        response.end(packagedIndex); return;
      }
      const asset = target.pathname.slice(1);
      if (assetVersions.has(asset) && target.searchParams.get('v') !== assetVersions.get(asset)) {
        response.writeHead(200, { 'Content-Type': asset.endsWith('.js') ? 'application/javascript' : 'text/css' });
        response.end(asset.endsWith('.js') ? 'throw new Error("Stale cached JavaScript loaded")' : 'body { display: none }');
        return;
      }
      if (packagedAssets.has(asset)) {
        response.writeHead(200, { 'Content-Type': asset.endsWith('.js') ? 'application/javascript' : 'text/css' });
        response.end(packagedAssets.get(asset)); return;
      }
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
      assert(proxyRequests.includes(`/alloy/app.js?v=${assetVersions.get('app.js')}`));
      assert(proxyRequests.includes(`/alloy/styles.css?v=${assetVersions.get('styles.css')}`));
      assert(proxyRequests.includes(`/alloy/instance-graph.js?v=${assetVersions.get('instance-graph.js')}`));
      assert(!proxyRequests.includes('/alloy/app.js'));
      assert(!proxyRequests.includes('/alloy/styles.css'));
      assert(!proxyRequests.includes('/alloy/instance-graph.js'));
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

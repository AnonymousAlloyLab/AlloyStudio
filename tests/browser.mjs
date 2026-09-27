import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, readFile } from 'node:fs/promises';
import { createServer, request as httpRequest } from 'node:http';
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
    return route.continue();
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message));
  const editor = page.locator('#predicate-editor');
  const feedback = page.locator('#feedback-state');
  const waitChecked = async () => { await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked'); };
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
      /Compared all \d+ private candidates, including the oracle/);
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
    assert.match(await page.locator('#canonical-location-status').textContent(), /2 possible fragments highlighted; source candidates are independent/);
    assert.match(await page.locator('.source-location-unavailable').textContent(), /No reliable source mapping/);
    sourceUnavailable = false;
    await submit('some Node\nsome Node'); await page.locator('.operation-select').click();
    assert.equal(await page.locator('.source-range').count(), 0);
    assert.match(await page.locator('#source-location-status').textContent(), /Choose a source candidate/);
    await page.getByRole('button', { name: 'Locate candidate 2' }).click();
    assert.equal(await page.locator('.canonical-range').count(), 2);
    assert.equal(await editor.evaluate(element => element.selectionStart), 10);
    assert.match(await page.locator('#canonical-location-status').textContent(), /source candidates are independent/);
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
      try { await route.fulfill({ json: { exerciseId: payload.exerciseId, revision: payload.revision, status: 'ok', model: 'gpt-6-luna',
        text: old ? 'OBSOLETE_GUIDANCE' : '<img src=x onerror="window.INJECTED=true"> Current guidance.' } }); } catch {}
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

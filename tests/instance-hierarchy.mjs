#!/usr/bin/env node
// Production browser rendering over public concrete instances. This harness
// starts a static asset server only: it cannot launch Alloy or call a provider.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { instanceDiagramGeometry } from './instance-graph-geometry.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const option = name => process.argv.find(value => value.startsWith(`--${name}=`))?.slice(name.length + 3);
const output = path.resolve(option('output') || process.env.ALLOY_HIERARCHY_OUTPUT
  || path.join(root, 'build/instance-hierarchy/tests'));
const cachedResponses = option('cached-responses');
const scratch = path.join(output, 'tmp');
await mkdir(scratch, { recursive: true });
await mkdir(path.join(output, 'images'), { recursive: true });
for (const name of ['TMPDIR', 'TMP', 'TEMP']) process.env[name] = scratch;
const hash = value => createHash('sha256').update(value).digest('hex');
const sources = ['web/instance-graph.js', 'web/styles.css', 'tests/instance-graph-geometry.mjs',
  'tests/instance-hierarchy.mjs', 'tests/fixtures/production-line-inv3-instances.json'];
const frozen = new Map(await Promise.all(sources.map(async name => [name, await readFile(path.join(root, name))])));
for (const [name, bytes] of frozen) {
  const destination = path.join(output, 'inputs', name);
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, bytes);
}
const fixture = JSON.parse(frozen.get('tests/fixtures/production-line-inv3-instances.json'));
assert.equal(fixture.provenance.exerciseId, 'productionLineNew-inv3');
assert.equal(fixture.examples.length, 6);
const report = { schemaVersion: 1, status: 'RUNNING', started: new Date().toISOString(),
  sourceHashes: Object.fromEntries([...frozen].map(([name, bytes]) => [name, hash(bytes)])),
  protocol: { productionRendererAndStyles: true, staticAssetsFrozenAtStart: true,
    solverCalls: 0, providerCalls: 0, browserScratch: path.relative(root, scratch),
    viewports: [1440, 390], geometry: 'independent browser-measured boxes, texts and sampled SVG routes',
    semanticChecks: 'raw public tuples and memberships compared with DOM data and accessible descriptions',
    hierarchyChecks: 'known chain/tree/DAG ancestry compared with browser-measured node bounds',
    notClaimed: ['all possible instances', 'unbounded rendering', 'formal proof of visual preference'] },
  focused: [], cached: [], errors: [], externalRequests: [] };

// Read only a fixed asset allowlist. No filesystem paths supplied by HTTP clients
// are ever resolved; no application/API server or credentials are loaded.
const document = '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">'
  + '<link rel="stylesheet" href="/styles.css"></head><body><main id="hierarchy-probe"></main></body></html>';
const assets = new Map([
  ['/', [Buffer.from(document), 'text/html; charset=utf-8']],
  ['/styles.css', [frozen.get('web/styles.css'), 'text/css; charset=utf-8']],
  ['/instance-graph.js', [frozen.get('web/instance-graph.js'), 'text/javascript; charset=utf-8']],
]);
const server = createServer((request, response) => {
  const asset = assets.get(new URL(request.url, 'http://localhost').pathname);
  response.writeHead(asset ? 200 : 404, { 'Content-Type': asset?.[1] || 'text/plain', 'Cache-Control': 'no-store' });
  response.end(asset?.[0] || 'Not found');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
let browser;

// This function deliberately does not import the renderer's graph, rank, layout
// or route helpers. The supplied state is the independent source of truth.
function semantics({ state, selected = null }) {
  const failures = [];
  const atomSet = new Set(state.signatures.flatMap(signature => signature.atoms));
  for (const relation of state.relations) for (const tuple of relation.tuples) for (const atom of tuple) atomSet.add(atom);
  const expectedTuples = state.relations.reduce((count, relation, index) =>
    count + (selected === null || selected === index ? relation.tuples.length : 0), 0);
  const nodeElements = [...document.querySelectorAll('.instance-graph-node')];
  const groups = [...document.querySelectorAll('.instance-graph-tuple')];
  const shownAtoms = new Set(nodeElements.map(element => element.dataset.atom));
  const fail = (kind, data = {}) => failures.push({ kind, ...data });
  if (shownAtoms.size !== nodeElements.length) fail('duplicate-atom-node');
  for (const node of nodeElements) {
    if (!atomSet.has(node.dataset.atom)) fail('invented-atom', { atom: node.dataset.atom });
    if (!node.getAttribute('aria-label')?.includes(node.dataset.atom)) fail('missing-full-atom-name');
    if (node.getAttribute('role') !== 'button' || node.getAttribute('tabindex') !== '0') fail('inaccessible-atom-selection');
    for (const signature of state.signatures.filter(signature => signature.atoms.includes(node.dataset.atom))) {
      if (!node.getAttribute('aria-label')?.includes(signature.label)) fail('missing-signature-membership', { atom: node.dataset.atom, signature: signature.label });
    }
  }
  for (const group of groups) {
    const relationIndex = Number(group.dataset.relationIndex), tupleIndex = Number(group.dataset.tupleIndex);
    const relation = state.relations[relationIndex], tuple = relation?.tuples[tupleIndex];
    if (!tuple || (selected !== null && selected !== relationIndex)) { fail('invented-or-unselected-tuple'); continue; }
    if (Number(group.dataset.arity) !== tuple.length) fail('wrong-tuple-arity');
    const description = group.getAttribute('aria-label') || '';
    if (!description.includes(relation.label)) fail('missing-full-relation-name');
    if (group.getAttribute('role') !== 'button' || group.getAttribute('tabindex') !== '0') fail('inaccessible-tuple-selection');
    const edges = [...group.querySelectorAll('.instance-graph-edge')];
    const visibleLabels = tuple.length === 2 ? group.querySelectorAll('.instance-graph-edge-label')
      : group.querySelectorAll('.instance-graph-junction-label');
    if (visibleLabels.length !== 1) fail('missing-visible-relation-name');
    else {
      const drawn = visibleLabels[0].textContent.replace(/(?:\s*·)?\s+#\d+$/u, '');
      const parts = drawn.split('…');
      // Long names may be shortened in the middle only: a real-name prefix
      // and distinguishing suffix remain visible, and exact data remains in
      // the independently checked accessible tuple description above.
      if (drawn !== relation.label && !(parts.length === 2 && parts[0].length >= 3 && parts[1].length >= 3
        && relation.label.startsWith(parts[0]) && relation.label.endsWith(parts[1]))) fail('connection-has-code-or-ambiguous-label', { drawn, relation: relation.label });
    }
    for (const atom of tuple) if (!shownAtoms.has(atom)) fail('dangling-tuple-endpoint', { atom });
    if (tuple.length === 2) {
      if (edges.length !== 1 || edges[0]?.dataset.source !== tuple[0] || edges[0]?.dataset.target !== tuple[1]) fail('wrong-binary-direction');
      if (tuple[0] === tuple[1] && edges.length === 1) {
        const length = edges[0].getTotalLength(), first = edges[0].getPointAtLength(0), last = edges[0].getPointAtLength(length);
        if (length <= Math.hypot(last.x - first.x, last.y - first.y) + 10) fail('self-loop-collapsed');
      }
    } else {
      if (edges.length !== tuple.length) fail('missing-tuple-column');
      for (let column = 0; column < tuple.length; column += 1) {
        if (edges.filter(edge => Number(edge.dataset.column) === column + 1 && edge.dataset.target === tuple[column]).length !== 1) fail('wrong-ordered-tuple-column', { column: column + 1 });
        if (!description.includes(`column ${column + 1}: ${tuple[column]}`)) fail('missing-full-ordered-tuple-description', { column: column + 1 });
        if (group.querySelectorAll(`.instance-graph-column-label[data-column="${column + 1}"]`).length !== 1) fail('missing-visible-tuple-column-number', { column: column + 1 });
      }
    }
  }
  if (nodeElements.length > 40 || groups.length > 48) fail('diagram-cap-exceeded');
  if (atomSet.size <= 40 && expectedTuples <= 48
    && (nodeElements.length !== atomSet.size || groups.length !== expectedTuples)) fail('missing-input-data-under-caps');
  const limited = nodeElements.length < atomSet.size || groups.length < expectedTuples;
  if (limited !== Boolean(document.querySelector('.instance-graph-limit'))) fail('incorrect-limit-notice');
  if (!atomSet.size && !document.querySelector('.instance-graph-empty')) fail('missing-empty-state-description');
  const options = [...document.querySelector('.instance-graph-relation-filter').options];
  for (const [index, relation] of state.relations.entries()) {
    if (!options.some(option => option.value === String(index) && option.textContent.includes(relation.label)
      && option.textContent.includes(String(relation.tuples.length)))) fail('missing-relation-name-or-count', { relation: index });
  }
  const routes = [...document.querySelectorAll('.instance-graph-edge')].map(edge => edge.getAttribute('d'));
  if (routes.some(route => !route)) fail('missing-route-geometry');
  if (new Set(routes).size !== routes.length) fail('collapsed-distinct-connections');
  if (document.documentElement.scrollWidth > innerWidth + 1) fail('page-overflow');
  if (nodeElements.length && innerWidth === 390) {
    const viewport = document.querySelector('.instance-graph-scroll').getBoundingClientRect();
    const verticallyVisible = nodeElements.map(node => node.querySelector('rect').getBoundingClientRect())
      .filter(bounds => bounds.top >= viewport.top && bounds.bottom <= viewport.bottom);
    const firstRow = Math.min(...verticallyVisible.map(bounds => bounds.top));
    if (!verticallyVisible.some(bounds => bounds.top <= firstRow + 1
      && bounds.left >= viewport.left && bounds.right <= viewport.right)) fail('no-complete-object-in-topmost-initial-mobile-row');
  }
  return { failures, expectedAtoms: atomSet.size, expectedTuples,
    shownAtoms: nodeElements.length, shownTuples: groups.length, limited };
}

function hierarchy({ orderedPairs = [], sameCycles = [] }) {
  const cards = new Map([...document.querySelectorAll('.instance-graph-node')].map(node =>
    [node.dataset.atom, { bounds: node.querySelector('rect').getBoundingClientRect(),
      rank: Number(node.dataset.layoutRank), component: node.dataset.layoutComponent }]));
  const failures = [];
  for (const [source, target] of orderedPairs) {
    const first = cards.get(source), last = cards.get(target);
    if (!first || !last) { failures.push({ kind: 'hierarchy-atom-missing', source, target }); continue; }
    if (!Number.isFinite(first.rank) || !Number.isFinite(last.rank) || last.rank <= first.rank) failures.push({ kind: 'downstream-rank-not-later', source, target });
    // This coordinate assertion is independent of the exported rank metadata.
    if (last.bounds.top <= first.bounds.bottom + 10) failures.push({ kind: 'downstream-object-not-below-source', source, target });
  }
  for (const atoms of sameCycles) {
    const members = atoms.map(atom => cards.get(atom));
    if (members.some(member => !member) || new Set(members.map(member => member?.rank)).size !== 1
      || new Set(members.map(member => member?.component)).size !== 1
      || members.some(member => member?.component === undefined)) failures.push({ kind: 'cycle-not-kept-as-one-component', atoms });
  }
  if (innerWidth === 390 && orderedPairs.length && !sameCycles.length) {
    const rows = [];
    for (const { bounds } of cards.values()) {
      const centerY = (bounds.top + bounds.bottom) / 2;
      const row = rows.find(row => Math.abs(row.centerY - centerY) < 1);
      if (row) row.count += 1;
      else rows.push({ centerY, count: 1 });
    }
    if (rows.some(row => row.count > 2)) failures.push({ kind: 'mobile-row-has-more-than-two-object-columns' });
  }
  return { failures };
}

const binary = (label, tuples) => ({ label, arity: 2, tuples });
const state = (atoms, relations, label = 'Node') => ({ index: 0, signatures: [{ label, atoms }], relations });
const longAtoms = Array.from({ length: 3 }, (_, index) => `Machine_With_A_Very_Long_Name$${index}`);
const maximalAtoms = Array.from({ length: 40 }, (_, index) => `Part$${index}`);
const cases = [
  ...fixture.examples.map(example => ({ name: `production-line-${example.category}-${example.example}`, state: example.state })),
  { name: 'five-level-chain', state: state(['A', 'B', 'C', 'D', 'E'], [binary('next', [['A', 'B'], ['B', 'C'], ['C', 'D'], ['D', 'E']])]),
    orderedPairs: [['A', 'B'], ['B', 'C'], ['C', 'D'], ['D', 'E']] },
  { name: 'branching-tree', state: state(['Root', 'Left', 'Right', 'L1', 'L2', 'R1'],
    [binary('child', [['Root', 'Left'], ['Root', 'Right'], ['Left', 'L1'], ['Left', 'L2'], ['Right', 'R1']])]),
    orderedPairs: [['Root', 'Left'], ['Root', 'Right'], ['Left', 'L1'], ['Left', 'L2'], ['Right', 'R1']] },
  { name: 'shared-child-dag', state: state(['Root', 'Left', 'Right', 'Shared', 'End'],
    [binary('depends', [['Root', 'Left'], ['Root', 'Right'], ['Left', 'Shared'], ['Right', 'Shared'], ['Shared', 'End']])]),
    orderedPairs: [['Root', 'Left'], ['Root', 'Right'], ['Left', 'Shared'], ['Right', 'Shared'], ['Shared', 'End']] },
  { name: 'cycle-self-loop-and-parallel', state: state(['A', 'B', 'C', 'Disconnected'],
    [binary('next', [['A', 'B'], ['B', 'A'], ['B', 'C'], ['C', 'C']]), binary('parallel', [['A', 'B'], ['C', 'C']])]),
    orderedPairs: [['B', 'C']], sameCycles: [['A', 'B']] },
  { name: 'six-node-cycle-wraps-without-losing-connections', state: state(['A', 'B', 'C', 'D', 'E', 'F'],
    [binary('next', [['A', 'B'], ['B', 'C'], ['C', 'D'], ['D', 'E'], ['E', 'F'], ['F', 'A']])]),
    sameCycles: [['A', 'B', 'C', 'D', 'E', 'F']] },
  { name: 'disconnected-components', state: state(['Root1', 'Leaf1', 'Root2', 'Leaf2', 'Isolated'],
    [binary('child', [['Root1', 'Leaf1'], ['Root2', 'Leaf2']])]), orderedPairs: [['Root1', 'Leaf1'], ['Root2', 'Leaf2']] },
  { name: 'unary-repeated-column-integer-and-empty', state: { index: 0,
    signatures: [{ label: 'Thing', atoms: ['Thing$0', 'Thing$1', 'Thing$2'] }, { label: 'Chosen', atoms: ['Thing$0'] }, { label: 'Empty', atoms: [] }],
    relations: [{ label: 'marked', arity: 1, tuples: [['Thing$1']] },
      { label: 'position', arity: 4, tuples: [['Thing$0', '-2', 'Thing$0', 'Thing$1'], ['Thing$1', '1', 'Thing$1', 'Thing$0']] },
      binary('empty relation', [])] } },
  { name: 'long-parallel-repeated', state: { index: 0,
    signatures: [{ label: 'Machine_With_A_Very_Long_Name', atoms: longAtoms },
      { label: 'Production_Equipment_With_A_Long_Name', atoms: [longAtoms[0], longAtoms[2]] }],
    relations: [...Array.from({ length: 8 }, (_, index) => binary(`ProductionLine.long_parallel_relation_${index}`,
      [[longAtoms[0], longAtoms[1]], [longAtoms[1], longAtoms[0]], [longAtoms[2], longAtoms[2]]])),
      { label: 'Repeated_column_positions_remain_distinguishable', arity: 4,
        tuples: [[longAtoms[0], longAtoms[0], longAtoms[2], longAtoms[0]], [longAtoms[1], longAtoms[2], longAtoms[1], longAtoms[2]]] }] } },
  { name: 'bounded-max-arity', state: state(maximalAtoms, Array.from({ length: 6 }, (_, relation) =>
    ({ label: `Eight_column_relation_${relation}`, arity: 8,
      tuples: Array.from({ length: 8 }, (_, tuple) => Array.from({ length: 8 }, (_, column) =>
        maximalAtoms[(relation * 7 + tuple * 3 + column * 5) % maximalAtoms.length])) })), 'Part') },
  { name: 'empty-state', state: { index: 0, signatures: [{ label: 'Empty', atoms: [] }], relations: [binary('links', [])] } },
  { name: 'explicitly-limited', state: state(Array.from({ length: 55 }, (_, index) => `Many$${index}`),
    [binary('links', Array.from({ length: 70 }, (_, index) => [`Many$${index % 55}`, `Many$${(index + 1) % 55}`]))], 'Many') },
  { name: 'untrusted-names-remain-text', state: state(['<img src=x onerror=alert(1)>', '<script>alert(1)</script>'],
    [binary('<svg onload=alert(1)>', [['<img src=x onerror=alert(1)>', '<script>alert(1)</script>']])], '<b>Unsafe label</b>') },
];

async function save() {
  report.updated = new Date().toISOString();
  report.counts = { focusedCases: cases.length, focusedChecks: report.focused.length,
    focusedPassed: report.focused.filter(check => !check.failures.length).length,
    cachedExercises: new Set(report.cached.map(check => check.exerciseId)).size,
    cachedChecks: report.cached.length, cachedPassed: report.cached.filter(check => !check.failures.length).length,
    failed: [...report.focused, ...report.cached].filter(check => check.failures.length).length };
  await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n');
}
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined,
    ignoreDefaultArgs: ['--disable-dev-shm-usage'], env: { ...process.env, TMPDIR: scratch, TMP: scratch, TEMP: scratch } });
  report.chromiumVersion = browser.version();
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  context.on('request', request => { if (!request.url().startsWith(base + '/')) report.externalRequests.push(request.url()); });
  const page = await context.newPage();
  page.on('pageerror', error => report.errors.push(error.message));
  await page.goto(base);
  await page.evaluate(() => document.fonts.ready);
  async function render(value, width) {
    await page.setViewportSize({ width, height: 1000 });
    return page.evaluate(async value => {
      const { renderInstanceGraph } = await import('/instance-graph.js');
      const started = performance.now();
      document.querySelector('#hierarchy-probe').replaceChildren(renderInstanceGraph(value));
      const renderMilliseconds = performance.now() - started;
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      return { renderMilliseconds, attachedAndFramesMilliseconds: performance.now() - started };
    }, value);
  }
  async function inspect(value, selected = null) {
    const semantic = await page.evaluate(semantics, { state: value, selected });
    const canvas = page.locator('.instance-graph-canvas');
    const geometry = await canvas.count() ? await canvas.evaluate(instanceDiagramGeometry) : { failures: [], objects: 0, texts: 0, edges: 0 };
    return { semantic, geometry, failures: [...semantic.failures, ...geometry.failures] };
  }
  for (const fixtureCase of cases) {
    for (const width of [1440, 390]) {
      let timing;
      try { timing = await render(fixtureCase.state, width); }
      catch (error) {
        report.focused.push({ name: fixtureCase.name, width,
          failures: [{ kind: 'render-exception', message: error.message.slice(0, 1000) }] });
        continue;
      }
      const measured = await inspect(fixtureCase.state);
      const arranged = await page.evaluate(hierarchy, fixtureCase);
      const check = { name: fixtureCase.name, width, timing, ...measured, hierarchy: arranged,
        failures: [...measured.failures, ...arranged.failures] };
      if (await page.locator('.instance-graph script, .instance-graph img, .instance-graph foreignObject, .instance-graph [onerror], .instance-graph [onclick], .instance-graph [onload]').count()) check.failures.push({ kind: 'names-became-active-markup' });
      if (fixtureCase.name === 'unary-repeated-column-integer-and-empty') {
        const membership = await page.locator('.instance-graph-node[data-atom="Thing$0"] .instance-graph-node-membership').textContent();
        if (!membership.includes('Thing') || !membership.includes('Chosen')) check.failures.push({ kind: 'short-type-memberships-not-visible' });
      }
      // Capture the untouched overview before keyboard focus emphasizes one
      // relationship and mutes unrelated objects.
      if (['production-line-both-3', 'five-level-chain', 'branching-tree'].includes(fixtureCase.name) || check.failures.length) {
        const image = `${fixtureCase.name}-${width}.png`;
        await page.locator('.instance-graph').screenshot({ path: path.join(output, 'images', image) });
        check.image = `images/${image}`;
      }
      // Keyboard-selected descriptions must retain the exact full value, even
      // where the drawn label is intentionally shortened for a compact view.
      const firstAtom = page.locator('.instance-graph-node').first();
      if (await firstAtom.count()) {
        const atom = await firstAtom.getAttribute('data-atom');
        await firstAtom.focus(); await firstAtom.press('Space');
        if (!(await page.locator('.instance-graph-details').textContent()).includes(atom)) check.failures.push({ kind: 'keyboard-selection-lost-full-name' });
      }
      const firstTuple = page.locator('.instance-graph-tuple').first();
      if (await firstTuple.count()) {
        const expected = await firstTuple.getAttribute('aria-label');
        await firstTuple.focus(); await firstTuple.press('Enter');
        if (!(await page.locator('.instance-graph-details').textContent()).includes(expected)) check.failures.push({ kind: 'keyboard-selection-lost-tuple-description' });
      }
      if (fixtureCase.name === 'production-line-both-3') {
        // Changing the view must never change concrete atoms, tuples, names or
        // ordered endpoints. This is one realistic exploration workflow rather
        // than isolated tests repeating each button's implementation.
        if (!await page.locator('.instance-graph-context-related').count()
          || !await page.locator('.instance-graph-context-muted').count()) check.failures.push({ kind: 'selection-does-not-distinguish-related-context' });
        const nativeWidth = await page.locator('.instance-graph-canvas').evaluate(canvas => canvas.getBoundingClientRect().width);
        await page.locator('.instance-graph-reset-context').click();
        if (await page.locator('.instance-graph-context-muted, .instance-graph [aria-pressed="true"]').count()) check.failures.push({ kind: 'show-all-does-not-clear-selection' });
        await page.locator('.instance-graph-fit').click();
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        if (!await page.locator('.instance-graph-scroll').evaluate(viewport =>
          viewport.querySelector('svg').getBoundingClientRect().width <= viewport.clientWidth + 1)) check.failures.push({ kind: 'fit-width-does-not-fit-real-instance' });
        const fitted = await inspect(fixtureCase.state);
        check.failures.push(...fitted.failures.map(failure => ({ ...failure, view: 'fit-width' })));
        await page.locator('.instance-graph-size-normal').click();
        if (Math.abs(await page.locator('.instance-graph-canvas').evaluate(canvas => canvas.getBoundingClientRect().width) - nativeWidth) > 1) check.failures.push({ kind: 'normal-size-not-restored' });
        for (const control of ['.instance-graph-zoom-in', '.instance-graph-zoom-out']) {
          for (let attempt = 0; attempt < 8 && !await page.locator(control).isDisabled(); attempt += 1) {
            await page.locator(control).click();
            const percent = Number.parseInt(await page.locator('.instance-graph-zoom-value').textContent(), 10);
            if (percent < 25 || percent > 150 || !Number.isFinite(percent)) check.failures.push({ kind: 'zoom-outside-supported-range' });
          }
        }
        await page.locator('.instance-graph-size-normal').click();
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const restored = await inspect(fixtureCase.state);
        check.failures.push(...restored.failures.map(failure => ({ ...failure, view: 'restored-native' })));
        check.viewControlsPreserveFacts = true;
      }
      // Filtering must preserve all atoms/values and exactly the selected raw
      // tuple inventory, including a deliberately empty relation.
      for (const relationIndex of fixtureCase.name === 'unary-repeated-column-integer-and-empty' ? [0, 1, 2]
        : fixtureCase.name === 'long-parallel-repeated' ? [8] : []) {
        await page.locator('.instance-graph-relation-filter').selectOption(String(relationIndex));
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const filtered = await inspect(fixtureCase.state, relationIndex);
        check.failures.push(...filtered.failures.map(failure => ({ ...failure, selectedRelation: relationIndex })));
      }
      report.focused.push(check);
    }
    await save();
  }
  if (cachedResponses) {
    report.cachedSource = path.resolve(cachedResponses);
    const files = (await readdir(report.cachedSource)).filter(name => name.endsWith('.json')).sort();
    const records = [];
    for (const file of files) {
      const bytes = await readFile(path.join(report.cachedSource, file));
      const record = JSON.parse(bytes);
      if (record.behavior?.status !== 'ok') continue;
      records.push({ file, inputHash: hash(bytes), behavior: record.behavior });
    }
    // The retained audit contains one usable behavior response for every public
    // invariant; four empty starters have separately labeled supplemental probes.
    assert.equal(records.length, 181, 'Cached audit must contain all 181 usable public responses');
    report.cachedInputs = records.map(({ file, inputHash }) => ({ file, inputHash }));
    for (const [recordIndex, { file, behavior }] of records.entries()) {
      const exerciseId = file.replace(/--supplemental-true\.json$|\.json$/u, '');
      for (const category of behavior.categories || []) {
        for (const [instanceIndex, instance] of category.instances.entries()) {
          for (const [stateIndex, value] of instance.states.entries()) {
            for (const width of [1440, 390]) {
              let timing;
              try { timing = await render(value, width); }
              catch (error) {
                report.cached.push({ exerciseId, category: category.id, instance: instanceIndex + 1,
                  state: stateIndex + 1, width,
                  failures: [{ kind: 'render-exception', message: error.message.slice(0, 1000) }] });
                continue;
              }
              const measured = await inspect(value);
              const check = { exerciseId, category: category.id, instance: instanceIndex + 1, state: stateIndex + 1, width,
                timing, ...measured };
              if (check.failures.length) {
                const image = `${exerciseId}-${category.id}-${instanceIndex + 1}-${stateIndex + 1}-${width}.png`;
                await page.locator('.instance-graph').screenshot({ path: path.join(output, 'images', image) });
                check.image = `images/${image}`;
              }
              report.cached.push(check);
            }
          }
        }
      }
      await save();
      process.stdout.write(`Cached hierarchy audit ${recordIndex + 1}/181: ${exerciseId}\n`);
    }
  }
  report.finished = new Date().toISOString();
  await save();
  report.status = report.counts.failed || report.errors.length || report.externalRequests.length ? 'FAIL' : 'PASS';
  await save();
  assert.deepEqual(report.errors, [], 'No browser exceptions');
  assert.deepEqual(report.externalRequests, [], 'Offline audit must not request external resources');
  assert.equal(report.counts.failed, 0, `${report.counts.failed} hierarchy checks failed; inspect ${path.join(output, 'report.json')}`);
  process.stdout.write(JSON.stringify({ status: report.status, ...report.counts }) + '\n');
} catch (error) {
  report.status = 'FAIL';
  report.fatalError = error.message.slice(0, 1000);
  throw error;
} finally {
  await browser?.close();
  await new Promise(resolve => server.close(resolve));
  await save();
}

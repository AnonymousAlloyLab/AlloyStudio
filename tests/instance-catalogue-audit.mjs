#!/usr/bin/env node
// Exhaustive catalogue audit, using real public solver responses and production
// rendering. No mock instances, oracle access, or model-provider requests.
import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile, rename, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { instanceDiagramGeometry } from './instance-graph-geometry.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.resolve(process.env.ALLOY_INSTANCE_AUDIT_OUTPUT || path.join(root, 'build/instance-audit-v003'));
const responseDir = path.join(output, 'responses');
const imageDir = path.join(output, 'images');
const scratch = path.join(output, 'tmp');
for (const directory of [responseDir, imageDir, scratch]) await mkdir(directory, { recursive: true });
// Playwright's profile directory is chosen in this parent process; setting only
// the backend's environment would still send Chromium scratch to system /tmp.
for (const name of ['TMPDIR', 'TMP', 'TEMP']) process.env[name] = scratch;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const hashes = {};
for (const file of ['server.py', 'runtime_dependencies.py', 'exercise_store.py', 'web/instance-graph.js', 'web/styles.css', 'tests/instance-graph-geometry.mjs',
  'tests/instance-catalogue-audit.mjs', 'engine/src/live/BehaviorFeedback.java', 'exercises/catalogue.json']) {
  hashes[file] = hash(await readFile(path.join(root, file)));
}
async function runtimeInventory() {
  const inventory = {};
  async function visit(directory) {
    for (const entry of (await readdir(path.join(root, directory), { withFileTypes: true })).sort((a, b) => a.name.localeCompare(b.name))) {
      const relative = `${directory}/${entry.name}`;
      if (entry.isDirectory()) await visit(relative);
      else if (entry.isFile()) inventory[relative] = hash(await readFile(path.join(root, relative)));
    }
  }
  await visit('build/engine/classes');
  await visit('vendor/acgn/lib');
  inventory['exercises/exercises.sqlite3'] = hash(await readFile(path.join(root, 'exercises/exercises.sqlite3')));
  for (const suffix of ['-wal', '-shm']) {
    try { inventory[`exercises/exercises.sqlite3${suffix}`] = hash(await readFile(path.join(root, `exercises/exercises.sqlite3${suffix}`))); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  inventory['browser/chromium'] = hash(await readFile(chromium.executablePath()));
  return inventory;
}
const runtimeHashes = await runtimeInventory();
const runtimeHash = hash(JSON.stringify(runtimeHashes));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const manifest = { schemaVersion: 1, started: new Date().toISOString(), sourceHashes: hashes, runtimeHashes, runtimeHash,
  pythonVersion: execFileSync('python3', ['--version'], { encoding: 'utf8' }).trim(),
  javaVersion: execFileSync('java', ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim(),
  protocol: { draft: 'public catalogue starter; separately recorded no none probe only for empty starters', actualPublicBehaviorResponse: true, mockInstances: false,
    serverWorkers: 1, serverTimeoutSeconds: 12, behaviorTimeoutSeconds: 30, sequentialRequests: true,
    openaiDisabled: true, viewports: [1440, 390], maxDiagramAtoms: 40, maxDiagramTuples: 48, pageLifetime: 'one fresh browser page/context per invariant',
    browserScratch: 'audit-owned tmp directory; native shared memory; no --disable-dev-shm-usage fallback to system /tmp',
    geometry: 'independent browser-measured object/text/route checks', screenshots: 'one actual state per exercise with instances, plus failures',
    notClaimed: ['unbounded Alloy correctness', 'visual preference', 'all possible instances', 'exhaustive category enumeration'] },
  exercises: [] };
try { manifest.priorInfrastructureEvents = JSON.parse(await readFile(path.join(output, 'infrastructure-events.json'), 'utf8')); }
catch (error) { if (error.code !== 'ENOENT') throw error; }
async function save() {
  manifest.updated = new Date().toISOString();
  manifest.counts = { exercises: manifest.exercises.length, ...Object.fromEntries(
    [...new Set(manifest.exercises.map(row => row.status))].map(status => [status, manifest.exercises.filter(row => row.status === status).length])),
    actualInstances: manifest.exercises.reduce((sum, row) => sum + (row.instances || 0), 0),
    actualStates: manifest.exercises.reduce((sum, row) => sum + (row.states || 0), 0),
    drawableStates: manifest.exercises.reduce((sum, row) => sum + (row.drawableStates || 0), 0),
    emptyStates: manifest.exercises.reduce((sum, row) => sum + (row.emptyStates || 0), 0),
    startersUnavailable: manifest.exercises.filter(row => row.starterStatus !== 'ok').length,
    supplementalDrafts: manifest.exercises.filter(row => row.draftKind !== 'starter').length,
    stateViewportChecks: manifest.exercises.reduce((sum, row) => sum + (row.checks?.length || 0), 0) };
  await writeFile(path.join(output, 'manifest.json.new'), JSON.stringify(manifest, null, 2) + '\n');
  await rename(path.join(output, 'manifest.json.new'), path.join(output, 'manifest.json'));
}
const server = spawn('python3', ['server.py', '--host', '127.0.0.1', '--port', '0', '--workers', '1', '--timeout', '12'], {
  cwd: root, env: { ...process.env, OPENAI_DISABLED: '1', ALLOY_ENGINE_TMP_ROOT: scratch, TMPDIR: scratch },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let serverLog = '', browser;
server.stdout.on('data', chunk => { serverLog += chunk.toString(); });
server.stderr.on('data', chunk => { serverLog += chunk.toString(); });
async function getJson(url, options) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(40000) });
  assert.equal(response.status, 200, `${url}: HTTP ${response.status}`);
  return response.json();
}
// Independent semantic checks use the public tuples themselves, never the
// renderer's build/layout helpers. Under the caps, all input data must appear.
function diagramSemantics(state) {
  const failures = [];
  const expectedAtoms = new Set(state.signatures.flatMap(signature => signature.atoms));
  for (const relation of state.relations) for (const tuple of relation.tuples) for (const atom of tuple) expectedAtoms.add(atom);
  const expectedTuples = state.relations.reduce((sum, relation) => sum + relation.tuples.length, 0);
  const nodes = [...document.querySelectorAll('.instance-graph-node')];
  const tuples = [...document.querySelectorAll('.instance-graph-tuple')];
  const actualAtoms = new Set(nodes.map(node => node.dataset.atom));
  if (actualAtoms.size !== nodes.length) failures.push({ kind: 'duplicate-atom-node' });
  for (const node of nodes) {
    if (!expectedAtoms.has(node.dataset.atom)) failures.push({ kind: 'invented-atom', atom: node.dataset.atom });
    for (const signature of state.signatures.filter(signature => signature.atoms.includes(node.dataset.atom))) {
      if (!node.getAttribute('aria-label').includes(signature.label)) failures.push({ kind: 'missing-membership', atom: node.dataset.atom });
    }
  }
  for (const group of tuples) {
    const relation = state.relations[Number(group.dataset.relationIndex)];
    const tuple = relation?.tuples[Number(group.dataset.tupleIndex)];
    if (!tuple) { failures.push({ kind: 'invented-tuple' }); continue; }
    if (Number(group.dataset.arity) !== tuple.length) failures.push({ kind: 'wrong-tuple-arity' });
    for (const atom of tuple) if (!actualAtoms.has(atom)) failures.push({ kind: 'dangling-endpoint', atom });
    const edges = [...group.querySelectorAll('.instance-graph-edge')];
    if (tuple.length === 2) {
      if (edges.length !== 1 || edges[0]?.dataset.source !== tuple[0] || edges[0]?.dataset.target !== tuple[1]) failures.push({ kind: 'wrong-binary-direction' });
    } else {
      if (edges.length !== tuple.length) failures.push({ kind: 'missing-tuple-column' });
      for (let column = 0; column < tuple.length; column++) {
        if (edges.filter(edge => Number(edge.dataset.column) === column + 1 && edge.dataset.target === tuple[column]).length !== 1) failures.push({ kind: 'wrong-ordered-tuple-column', column });
      }
    }
  }
  if (nodes.length > 40 || tuples.length > 48) failures.push({ kind: 'diagram-cap-exceeded' });
  if (expectedAtoms.size <= 40 && expectedTuples <= 48 && (nodes.length !== expectedAtoms.size || tuples.length !== expectedTuples)) failures.push({ kind: 'missing-input-data-under-caps' });
  const limited = actualAtoms.size < expectedAtoms.size || tuples.length < expectedTuples;
  if (limited !== Boolean(document.querySelector('.instance-graph-limit'))) failures.push({ kind: 'missing-or-inaccurate-limit-notice' });
  if (!expectedAtoms.size && !document.querySelector('.instance-graph-empty')) failures.push({ kind: 'missing-empty-state-description' });
  const routes = [...document.querySelectorAll('.instance-graph-edge')].map(edge => edge.getAttribute('d'));
  if (new Set(routes).size !== routes.length) failures.push({ kind: 'collapsed-distinct-connections' });
  if (document.documentElement.scrollWidth > innerWidth + 1) failures.push({ kind: 'page-overflow' });
  if (nodes.length && innerWidth === 390) {
    const viewport = document.querySelector('.instance-graph-scroll').getBoundingClientRect();
    if (!nodes.some(node => { const card = node.querySelector('rect').getBoundingClientRect();
      return card.left >= viewport.left && card.right <= viewport.right && card.top >= viewport.top && card.bottom <= viewport.bottom;
    })) failures.push({ kind: 'no-complete-atom-in-initial-mobile-view' });
  }
  return { failures, expectedAtoms: expectedAtoms.size, expectedTuples, shownAtoms: nodes.length, shownTuples: tuples.length, limited };
}
try {
  for (let attempt = 0; attempt < 300 && !serverLog.includes('Alloy practice:'); attempt++) {
    if (server.exitCode !== null) throw new Error(`Audit server failed: ${serverLog}`);
    await sleep(100);
  }
  const base = serverLog.match(/Alloy practice: (http:\/\/[^\s]+)/)?.[1];
  if (!base) throw new Error('Audit server failed to become ready');
  const exercises = (await getJson(base + '/api/exercises')).exercises;
  assert.equal(exercises.length, 181, 'Freeze the intended catalogue population');
  manifest.catalogueIdsHash = hash(JSON.stringify(exercises.map(exercise => exercise.id)));
  browser = await chromium.launch({ headless: true, ignoreDefaultArgs: ['--disable-dev-shm-usage'],
    env: { ...process.env, TMPDIR: scratch, TMP: scratch, TEMP: scratch } });
  manifest.chromiumVersion = browser.version();
  const pageErrors = [];
  async function newAuditPage() {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    page.on('pageerror', error => pageErrors.push(error.message));
    await page.route(base + '/instance-audit', route => route.fulfill({ contentType: 'text/html', body:
      '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">'
      + '<link rel="stylesheet" href="/styles.css"></head><body><main id="audit-root"></main></body></html>' }));
    await page.goto(base + '/instance-audit');
    await page.evaluate(() => document.fonts.ready);
    return page;
  }
  async function collectResponse(exerciseId, body, revision, suffix = '') {
    const request = { exerciseId, body, revision };
    const requestHash = hash(JSON.stringify(request));
    const responseFile = path.join(responseDir, exerciseId + suffix + '.json');
    let responseRecord;
    try {
      const prior = JSON.parse(await readFile(responseFile, 'utf8'));
      if (prior.requestHash === requestHash && prior.catalogueHash === hashes['exercises/catalogue.json']
        && prior.runtimeHash === runtimeHash && prior.engineHash === hashes['engine/src/live/BehaviorFeedback.java'] && prior.serverHash === hashes['server.py']) responseRecord = prior;
    } catch { /* no reusable real response */ }
    if (!responseRecord) {
      const started = performance.now();
      const behavior = await getJson(base + '/api/behavior', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request) });
      responseRecord = { requestHash, catalogueHash: hashes['exercises/catalogue.json'], engineHash: hashes['engine/src/live/BehaviorFeedback.java'],
        runtimeHash, serverHash: hashes['server.py'], collected: new Date().toISOString(), seconds: (performance.now() - started) / 1000, behavior };
      await writeFile(responseFile, JSON.stringify(responseRecord, null, 2) + '\n');
    }
    return responseRecord;
  }
  for (const [index, exercise] of exercises.entries()) {
    const page = await newAuditPage();
    const detail = await getJson(base + '/api/exercises/' + encodeURIComponent(exercise.id));
    const primary = await collectResponse(exercise.id, detail.starter, index + 1);
    let responseRecord = primary, draftKind = 'starter';
    if (!detail.starter.trim() && primary.behavior.status !== 'ok') {
      responseRecord = await collectResponse(exercise.id, 'no none', index + 1001, '--supplemental-true');
      draftKind = 'supplemental-constant-true';
    }
    const behavior = responseRecord.behavior;
    const record = { id: exercise.id, title: exercise.title, starterHash: hash(detail.starter), requestHash: responseRecord.requestHash,
      starterStatus: primary.behavior.status, starterResponseHash: hash(JSON.stringify(primary.behavior)), draftKind,
      responseHash: hash(JSON.stringify(behavior)), solverSeconds: responseRecord.seconds, behaviorStatus: behavior.status,
      status: behavior.status === 'ok' ? 'pending-render' : `unavailable-${behavior.status}`, categories: [], checks: [], instances: 0, states: 0, drawableStates: 0, emptyStates: 0 };
    const errorsBefore = pageErrors.length;
    let screenshotTaken = false;
    for (const category of behavior.categories || []) {
      record.categories.push({ id: category.id, status: category.status, instances: category.instances.length,
        enumerationComplete: category.enumerationComplete });
      for (const [instanceIndex, instance] of category.instances.entries()) {
        record.instances++;
        for (const [stateIndex, state] of instance.states.entries()) {
          record.states++;
          for (const width of [1440, 390]) {
            await writeFile(path.join(output, 'current-check.json'), JSON.stringify({ id: exercise.id, category: category.id,
              instance: instanceIndex + 1, state: stateIndex + 1, width }) + '\n');
            await page.setViewportSize({ width, height: 1000 });
            await page.evaluate(async ({ state, title }) => {
              const { renderInstanceGraph } = await import('/instance-graph.js');
              const heading = document.createElement('h2'); heading.textContent = title;
              document.querySelector('#audit-root').replaceChildren(heading, renderInstanceGraph(state));
              await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
            }, { state, title: `${exercise.title} · ${category.id} · Example ${instanceIndex + 1} · State ${stateIndex + 1}` });
            const canvas = page.locator('.instance-graph-canvas');
            const geometry = await canvas.count() ? await canvas.evaluate(instanceDiagramGeometry) : { failures: [], objects: 0, texts: 0, edges: 0 };
            const semantics = await page.evaluate(diagramSemantics, state);
            if (width === 1440) record[semantics.expectedAtoms ? 'drawableStates' : 'emptyStates']++;
            const failures = [...geometry.failures, ...semantics.failures];
            const check = { category: category.id, instance: instanceIndex + 1, state: stateIndex + 1, width,
              geometry: { objects: geometry.objects, texts: geometry.texts, edges: geometry.edges },
              semantics: { ...semantics, failures: undefined }, failures };
            if ((!screenshotTaken && width === 1440) || failures.length) {
              const filename = `${exercise.id}--${category.id}-${instanceIndex + 1}-${stateIndex + 1}-${width}.png`;
              await page.screenshot({ path: path.join(imageDir, filename), fullPage: true });
              check.screenshot = `images/${filename}`;
              screenshotTaken = true;
            }
            record.checks.push(check);
          }
        }
      }
    }
    if (behavior.status === 'ok') record.status = record.checks.some(check => check.failures.length) || pageErrors.length > errorsBefore
      ? 'render-failure' : record.instances ? 'passed' : 'no-instances';
    if (pageErrors.length > errorsBefore) record.browserErrors = pageErrors.slice(errorsBefore);
    await page.close();
    manifest.exercises.push(record);
    await save();
    console.log(`${index + 1}/${exercises.length} ${exercise.id}: ${record.status}; ${record.instances} instances; ${record.checks.length} state/viewport checks; solver ${record.solverSeconds.toFixed(2)}s`);
  }
  assert.equal(manifest.exercises.length, 181);
  assert.deepEqual(manifest.exercises.map(row => row.id), exercises.map(row => row.id));
  for (const [file, expected] of Object.entries(hashes)) assert.equal(hash(await readFile(path.join(root, file))), expected, `Source changed during audit: ${file}`);
  assert.deepEqual(await runtimeInventory(), runtimeHashes, 'Compiled runtime, database, or browser changed during audit');
  manifest.inputsUnchanged = true;
  manifest.status = manifest.exercises.every(record => record.status === 'passed')
    ? manifest.exercises.some(record => record.draftKind !== 'starter') ? 'COMPLETE_WITH_EMPTY_STARTER_SUBSTITUTIONS' : 'PASS'
    : 'INCOMPLETE';
  manifest.finished = new Date().toISOString();
  await save();
  console.log(JSON.stringify(manifest.counts));
  if (manifest.status === 'INCOMPLETE') process.exitCode = 1;
} catch (error) {
  manifest.status = 'INFRASTRUCTURE_FAILURE';
  manifest.failure = { message: error.message, at: new Date().toISOString() };
  await save();
  throw error;
} finally {
  if (browser) await browser.close();
  server.kill('SIGTERM');
  await writeFile(path.join(output, 'server.log'), serverLog);
}

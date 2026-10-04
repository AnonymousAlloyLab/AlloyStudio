'use strict';
const repository = 'AnonymousAlloyLab/AlloyStudio';
const github = `https://github.com/${repository}`;
const api = `https://api.github.com/repos/${repository}`;
const $ = id => document.getElementById(id);
const labels = { build: 'Portable build', runtime: 'Engine checks', python: 'Python regressions', browser: 'Portal browser', dashboard: 'Dashboard browser' };
const checkStates = new Set(['PASS', 'FAIL', 'NOT_RUN', 'UNAVAILABLE']);
const runStates = new Set(['queued', 'in_progress', 'waiting', 'pending', 'requested', 'completed']);
const conclusions = new Set(['success', 'failure', 'cancelled', 'skipped', 'timed_out', 'action_required', 'neutral', 'stale']);
function text(value, limit = 120) { return typeof value === 'string' ? value.slice(0, limit) : ''; }
function number(value) { return Number.isSafeInteger(value) && value >= 0 ? String(value) : '—'; }
function hash(value) { return typeof value === 'string' && /^[0-9a-f]{40,64}$/.test(value) ? value : ''; }
function node(tag, value, className) { const element = document.createElement(tag); if (value !== undefined) element.textContent = value; if (className) element.className = className; return element; }
function badge(value, style = '') { return node('span', value, `badge ${style}`); }
function publicLink(value, label) {
  let url;
  try { url = new URL(value); } catch { return node('span', label); }
  if (url.origin !== 'https://github.com' || !url.pathname.startsWith(`/${repository}/`) || url.username || url.password) return node('span', label);
  const anchor = node('a', label); anchor.href = url.href; anchor.target = '_blank'; anchor.rel = 'noopener noreferrer'; return anchor;
}
function showSnapshot(data) {
  if (data?.schemaVersion !== 1 || data.repository !== repository) throw new Error('invalid-summary');
  $('version').textContent = text(data.version) || 'Unknown';
  const revision = hash(data.revision?.sha);
  $('revision').replaceChildren(revision ? publicLink(`${github}/commit/${revision}`, revision.slice(0, 12)) : node('span', 'Revision unavailable'));
  if (data.revision?.dirty === true) $('revision').append(node('span', ' · local changes'));
  const checks = Array.isArray(data.checks) ? data.checks.filter(item => item && Object.hasOwn(labels, item.name)).slice(0, 5) : [];
  $('checks').replaceChildren();
  let failure = false, complete = checks.length === 5;
  for (const name of Object.keys(labels)) {
    const item = checks.find(value => value.name === name) || { status: 'NOT_RUN' };
    const state = checkStates.has(item.status) ? item.status : 'NOT_RUN';
    const current = item.current === true && data.revision?.dirty === false && revision && hash(item.revision) === revision;
    const status = state === 'NOT_RUN' ? 'Not recorded' : current ? state : `${state} · earlier / unbound`;
    if (state !== 'PASS' || !current) complete = false;
    if (current && ['FAIL', 'UNAVAILABLE'].includes(state)) failure = true;
    const cell = node('article', undefined, 'check');
    cell.append(node('h3', labels[name]), badge(status, current && state === 'PASS' ? 'good' : current && state === 'FAIL' ? 'bad' : 'warn'));
    cell.append(node('p', Number.isSafeInteger(item.count) ? `${number(item.count)} checks` : 'Count unavailable'));
    $('checks').append(cell);
  }
  $('check-state').textContent = failure ? 'Needs attention' : complete ? 'Recorded checks passed' : 'Incomplete / unbound';
  const closure = data.closure;
  if (closure && ['VERIFIED', 'BLOCKED', 'INFRASTRUCTURE_FAILURE'].includes(closure.status)
      && /^portal-\d{8}T\d{6}Z-[a-f0-9]{8}$/.test(closure.id || '') && /^[0-9a-f]{64}$/.test(closure.inputRootHash || '')) {
    $('closure-state').textContent = `${closure.status} · ${closure.id} (historical)`;
    $('closure-detail').replaceChildren(node('span', `${number(closure.claimsPassed)} / ${number(closure.claimsTotal)} claims; ${number(closure.buildsPassed)} / ${number(closure.buildsRequired)} clean builds. Frozen input: `), node('code', closure.inputRootHash));
  }
  const lean = data.lean;
  if (lean && Number.isSafeInteger(lean.total) && Number.isSafeInteger(lean.open)) {
    $('lean-state').textContent = `${number(lean.open)} open obligations / ${number(lean.total)} total`;
  }
  $('notice').textContent = 'Local build summary loaded. GitHub status is fetched only when you refresh.';
}
async function read(url) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(url, { credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal });
    if (!response.ok) throw new Error(response.status === 403 || response.status === 429 ? 'rate-limit-or-access' : 'unavailable');
    return await response.json();
  } finally { clearTimeout(timeout); }
}
async function refresh() {
  $('refresh').disabled = true;
  $('remote-state').textContent = 'Reading public GitHub workflow status…';
  const results = await Promise.allSettled([read(`${api}/actions/runs?per_page=12`), read(`${api}/releases?per_page=10`)]);
  const workflow = results[0];
  if (workflow.status === 'fulfilled' && Array.isArray(workflow.value?.workflow_runs)) {
    const runs = workflow.value.workflow_runs.slice(0, 12);
    $('runs').replaceChildren();
    for (const run of runs) {
      if (!run || typeof run !== 'object') continue;
      const row = node('tr');
      const title = node('td'); title.append(publicLink(run.html_url, text(run.name) || 'Workflow')); row.append(title);
      const state = run.status === 'completed' ? (conclusions.has(run.conclusion) ? run.conclusion : 'unknown') : (runStates.has(run.status) ? run.status : 'unknown');
      const status = node('td'); status.append(badge(state.replaceAll('_', ' '), state === 'success' ? 'good' : ['failure', 'timed_out'].includes(state) ? 'bad' : 'warn')); row.append(status);
      const sha = hash(run.head_sha); row.append(node('td', sha ? sha.slice(0, 12) : 'Unknown'));
      const date = new Date(run.created_at); row.append(node('td', Number.isFinite(date.getTime()) ? date.toLocaleString() : 'Unknown'));
      const artifacts = node('td'); artifacts.append(publicLink(run.html_url, 'Run / artifacts ↗')); row.append(artifacts);
      $('runs').append(row);
    }
    $('remote-state').textContent = runs.length ? 'Latest public workflow runs. A successful run is separate from production deployment.' : 'No workflow runs have been published yet.';
    $('remote-time').textContent = `Fetched ${new Date().toLocaleTimeString()}`;
  } else {
    $('runs').replaceChildren();
    $('remote-time').textContent = 'Unavailable';
    $('remote-state').textContent = 'GitHub status unavailable (network, access, or rate limit). No success is assumed; use the GitHub Actions link to inspect runs.';
  }
  const release = results[1];
  if (release.status === 'fulfilled' && Array.isArray(release.value)) {
    const latest = release.value.find(item => item && item.draft !== true);
    if (latest) {
      $('release').replaceChildren(publicLink(latest.html_url, text(latest.name) || text(latest.tag_name) || 'Published release'));
      $('release-detail').textContent = `${latest.prerelease ? 'Prerelease' : 'Release'} · ${text(latest.tag_name) || 'tag unavailable'}. Source and public release notes on GitHub.`;
    } else {
      $('release').textContent = 'No published release'; $('release-detail').textContent = 'Release metadata is available, but the repository has no public releases yet.';
    }
  } else { $('release').textContent = 'Unavailable'; $('release-detail').textContent = 'Published release status could not be fetched.'; }
  $('refresh').disabled = false;
}
$('refresh').addEventListener('click', refresh);
read('./data.json').then(showSnapshot).catch(() => {
  $('notice').textContent = 'Local build summary unavailable. GitHub status can still be refreshed; no local verification is assumed.';
});

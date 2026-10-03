// CI reports one combined result for both public editor workflows.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
const passed = [];
for (const file of ['tests/browser.mjs', 'tests/navigation.mjs', 'tests/traffic-browser.mjs', 'tests/persistent-browser.mjs']) {
  const expected = [...readFileSync(file, 'utf8').matchAll(/await check\('([^']+)'/g)].map(match => match[1]);
  assert.ok(expected.length > 0, 'No browser scenarios registered');
  assert.equal(new Set(expected).size, expected.length, 'Duplicate browser scenario');
  const result = spawnSync(process.execPath, [file], { encoding: 'utf8', timeout: 240000 });
  if (result.error || result.status !== 0) {
    process.stderr.write(result.stderr || 'Browser verification failed.\n');
    process.exit(1);
  }
  const report = JSON.parse(result.stdout.trim().split('\n').at(-1));
  assert.equal(report.status, 'PASS');
  assert.deepEqual(report.passed, expected, 'Missing, repeated or undeclared browser scenario');
  assert.equal(report.checks, report.passed.length);
  passed.push(...report.passed.map(name => `${file}:${name}`));
}
console.log(JSON.stringify({ status: 'PASS', checks: passed.length, passed }));

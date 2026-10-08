import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { chromium } from 'playwright';
import { alloyTokens, enterIndent, closingIndent, indentAlloy } from '../web/alloy-language.js';

const passed = [], errors = [], external = [];
let diagnosticPage;
const artifacts = new URL('../build/browser-alloy-editor/', import.meta.url);
await mkdir(artifacts, { recursive: true });
async function check(name, fn) {
  try { await fn(); passed.push(name); }
  catch (error) {
    if (diagnosticPage) {
      await diagnosticPage.screenshot({ path: new URL('failure.png', artifacts).pathname, timeout: 5000 }).catch(() => {});
      await writeFile(new URL('failure-state.json', artifacts), JSON.stringify(await diagnosticPage.evaluate(() => {
        const e = document.querySelector('#predicate-editor');
        return { disabled: e?.disabled, length: e?.value.length, rect: e?.getBoundingClientRect().toJSON(),
          hidden: e?.hidden, feedback: document.querySelector('#feedback-state')?.textContent };
      }).catch(() => ({}))));
    }
    throw new Error(`${name}: ${error.name}`);
  }
}
const apply = (source, edit) => source.slice(0, edit.start) + edit.text + source.slice(edit.end);

await check('alloy-lexer-preserves-unicode-offsets-comments-strings-and-temporal-tokens', async () => {
  const source = 'all x: Node | always x.next = x\'\n// 🧭 <img onerror="boom">\n/* some { */ "no \\"Node\\""\n';
  const tokens = alloyTokens(source);
  assert.equal(tokens.map(token => token.text).join(''), source);
  assert(tokens.every((token, index) => token.text === source.slice(token.start, token.end)
    && token.start === (index ? tokens[index - 1].end : 0)));
  assert.deepEqual(tokens.filter(token => token.kind === 'keyword').map(token => token.text), ['all', 'always']);
  assert.equal(tokens.filter(token => token.kind === 'comment').length, 2);
  for (const incomplete of ['"', '/*', '\ud83d\ude00', '\t', 'some Node\r\n']) {
    assert.equal(alloyTokens(incomplete).map(token => token.text).join(''), incomplete);
  }
});
await check('automatic-indentation-ignores-literal-delimiters-and-keeps-token-identity', async () => {
  assert.equal(apply('{}', enterIndent('{}', 1)), '{\n  \n}');
  assert.equal(enterIndent('  all n: Node |', 15).text, '\n    ');
  assert.equal(enterIndent('  // {', 6).text, '\n  ');
  assert.equal(enterIndent('pred p { // explanation', 23).text, '\n  ');
  assert.equal(enterIndent('  "{"', 5).text, '\n  ');
  assert.equal(enterIndent('{\n  ', 4).text, '\n  ');
  assert.equal(apply('  ', closingIndent('  ', 2, 2, '}')), '}');
  assert.equal(closingIndent('  // ', 5, 5, '}'), null);
  const original = '{\n some Node\n {\nno Node\n}\n/* multiline\n  retained indentation\n*/\n"line one\n  line two"\n}';
  const formatted = indentAlloy(original);
  const semanticTokens = value => alloyTokens(value).filter(token => token.text.trim()).map(({ kind, text }) => [kind, text]);
  assert.deepEqual(semanticTokens(formatted), semanticTokens(original));
  assert.equal(indentAlloy(formatted), formatted);
  assert(formatted.includes('\n  some Node\n  {\n    no Node\n  }'));
  assert(formatted.includes('/* multiline\n  retained indentation\n*/'));
  assert(formatted.includes('"line one\n  line two"'));
  assert.equal(indentAlloy('{\n{\n{\nsome Node\n}}}'), '{\n  {\n    {\n      some Node\n}}}');
  const deep = '{\n'.repeat(4000) + 'x';
  assert.equal(indentAlloy(deep), deep);
  assert.equal(alloyTokens('iden').at(0).kind, 'keyword');
});

const server = spawn('python3', ['server.py', '--host', '127.0.0.1', '--port', '0', '--workers', '1'], {
  env: { ...process.env, OPENAI_DISABLED: '1' }, stdio: ['ignore', 'pipe', 'pipe'],
});
let browser;
try {
  const origin = await new Promise((resolve, reject) => {
    let stdout = '';
    const timer = setTimeout(() => reject(new Error('Startup timeout')), 15000);
    server.stdout.on('data', chunk => {
      stdout += chunk;
      const match = stdout.match(/http:\/\/127\.0\.0\.1:\d+/);
      if (match) { clearTimeout(timer); resolve(match[0]); }
    });
    server.on('exit', code => { clearTimeout(timer); reject(new Error(`Server exit ${code}`)); });
  });
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(() => localStorage.setItem('alloy-studio:v1:live', 'false'));
  const page = await context.newPage();
  diagnosticPage = page;
  page.on('pageerror', error => errors.push(error.message));
  await context.route('**/*', route => {
    if (!route.request().url().startsWith(origin)) { external.push(route.request().url()); return route.abort(); }
    return route.continue();
  });
  await page.goto(origin + '/?exercise=graphs-inv1');
  const editor = page.locator('#predicate-editor');
  await page.waitForFunction(() => !document.querySelector('#predicate-editor').disabled);
  await check('combined-environment-shows-only-todo-body-with-colored-original-context', async () => {
    const record = await (await context.request.get(origin + '/api/exercises/graphs-inv1')).json();
    assert.equal(await page.locator('.environment-tab').count(), 0);
    assert.equal(await page.locator('#environment-code').textContent(), record.environmentBefore + record.predicateHeader
      + '{\n  // TODO: write the predicate body in the editor above\n}' + record.environmentAfter);
    assert(await page.locator('#environment-code .alloy-keyword').count() > 0);
    assert.match(await page.locator('#environment-code .alloy-comment').allTextContents().then(rows => rows.join(' ')), /TODO/);
  });
  await check('syntax-mirror-renders-bold-keywords-and-html-as-text', async () => {
    const text = 'all n: Node | some n.adj\n// <img src=x onerror="window.BAD=true"> 🧭\n';
    await editor.fill(text);
    assert.equal(await page.locator('#source-highlight').textContent(), text + '\n');
    assert.deepEqual(await page.locator('#source-highlight .alloy-keyword').allTextContents(), ['all', 'some']);
    assert.equal(await page.locator('#source-highlight img').count(), 0);
    assert.equal(await page.evaluate(() => window.BAD), undefined);
    assert.equal(await page.locator('#source-highlight .alloy-keyword').first().evaluate(element => getComputedStyle(element).fontWeight), '700');
    assert.equal(await page.locator('#source-highlight').evaluate(element => getComputedStyle(element).pointerEvents), 'none');
    await page.screenshot({ path: new URL('desktop.png', artifacts).pathname, fullPage: true });
  });
  await check('complete-environment-grows-and-wraps-without-internal-scroll', async () => {
    const environment = page.locator('#environment-code');
    const original = await environment.textContent();
    const large = original + '\n// ' + 'long_environment_identifier_'.repeat(90)
      + '\n' + Array.from({ length: 80 }, (_, i) => `// supporting environment line ${i}`).join('\n');
    await environment.evaluate((element, text) => { element.textContent = text; }, large);
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: 1000 });
      const layout = await environment.evaluate(element => ({
        scrollHeight: element.scrollHeight, clientHeight: element.clientHeight,
        scrollWidth: element.scrollWidth, clientWidth: element.clientWidth,
        height: element.getBoundingClientRect().height, overflow: getComputedStyle(element).overflow,
        pageWidth: document.documentElement.scrollWidth, width: innerWidth,
      }));
      assert.equal(layout.overflow, 'visible');
      assert(layout.height > 1000, 'Long environments must grow beyond the former fixed height');
      assert(layout.scrollHeight <= layout.clientHeight + 1);
      assert(layout.scrollWidth <= layout.clientWidth + 1);
      assert(layout.pageWidth <= layout.width + 1);
      assert.equal(await environment.textContent(), large);
    }
    await environment.evaluate((element, text) => { element.textContent = text; }, original);
    await page.setViewportSize({ width: 1440, height: 1000 });
  });
  await check('enter-closing-delimiter-and-indent-button-follow-normal-edit-invalidation', async () => {
    await editor.fill('{}'); await editor.evaluate(element => element.setSelectionRange(1, 1));
    await editor.press('Enter');
    assert.equal(await editor.inputValue(), '{\n  \n}');
    assert.deepEqual(await editor.evaluate(element => [element.selectionStart, element.selectionEnd]), [4, 4]);
    await editor.fill('  '); await editor.press('End'); await editor.press('}');
    assert.equal(await editor.inputValue(), '}');
    await editor.fill('{\nsome Node\n}'); await page.locator('#indent-button').click();
    assert.equal(await editor.inputValue(), '{\n  some Node\n}');
    assert.equal(await page.locator('#source-highlight').textContent(), '{\n  some Node\n}\n');
    assert.equal(await page.locator('#draft-status').textContent(), 'Draft saved');
    await editor.fill('some Node'); await editor.press('End'); await editor.press('Tab');
    assert.equal(await editor.inputValue(), 'some Node  ');
    await editor.press('Escape'); await editor.press('Tab');
    assert.equal(await editor.evaluate(element => document.activeElement === element), false);
  });
  await check('syntax-highlighting-composes-with-exact-structural-defect-selection', async () => {
    await page.locator('[data-exercise-id="graphs-inv5"]').click();
    await page.waitForFunction(() => location.search.includes('graphs-inv5') && !document.querySelector('#predicate-editor').disabled);
    const source = '// 🧭\nsome (iden & adj)';
    await editor.fill(source); await page.locator('#check-button').click();
    await page.waitForFunction(() => document.querySelector('#feedback-state').textContent === 'Checked');
    await page.locator('.operation-locate').first().click();
    const selection = await editor.evaluate(element => element.value.slice(element.selectionStart, element.selectionEnd));
    assert.equal(await page.locator('.source-range').textContent(), selection);
    assert(await page.locator('.source-range .alloy-keyword').count() > 0);
    assert.equal(await page.locator('#source-highlight').textContent(), source + '\n');
    await page.locator('#clear-source-highlight').click();
    assert.equal(await page.locator('.source-range').count(), 0);
    assert(await page.locator('#source-highlight .alloy-keyword').count() > 0);
  });
  await check('automatic-indentation-and-explicit-format-preserve-native-undo-redo', async () => {
    await editor.fill('{}'); await editor.evaluate(element => element.setSelectionRange(1, 1));
    await editor.press('Enter');
    assert.equal(await editor.inputValue(), '{\n  \n}');
    await editor.press('Control+z');
    assert.equal(await editor.inputValue(), '{}');
    await editor.press('Control+Shift+z');
    assert.equal(await editor.inputValue(), '{\n  \n}');
    await editor.fill('{\nsome Node\n}'); await page.locator('#indent-button').click();
    assert.equal(await editor.inputValue(), '{\n  some Node\n}');
    await editor.press('Control+z');
    assert.equal(await editor.inputValue(), '{\nsome Node\n}');
    assert.equal(await page.locator('#source-highlight').textContent(), '{\nsome Node\n}\n');
    const deep = '{\n'.repeat(4000) + 'x';
    // Native Chromium insertText itself stalls for thousands of paragraphs in
    // a bare textarea. Seed this stress fixture then exercise the real input
    // handler and Format button; normal keyboard undo is tested above.
    await editor.evaluate((element, text) => { element.value = text; element.dispatchEvent(new Event('input', { bubbles: true })); }, deep);
    await page.locator('#indent-button').click();
    assert.equal(await editor.inputValue(), deep);
    assert.equal(await page.locator('#source-highlight .alloy-operator').count(), 0);
    await editor.evaluate(element => { element.value = 'some Node'; element.dispatchEvent(new Event('input', { bubbles: true })); });
  });
  await check('editor-mobile-has-no-page-overflow-and-keeps-visible-syntax-mirror', async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
    assert.equal(await page.locator('#source-highlight').evaluate(element => getComputedStyle(element).color), 'rgb(228, 237, 247)');
    await page.screenshot({ path: new URL('mobile.png', artifacts).pathname, fullPage: true });
  });
  assert.deepEqual(errors, []); assert.deepEqual(external, []);
  const report = { status: 'PASS', checks: passed.length, passed, errors, external };
  await writeFile(new URL('report.json', artifacts), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report));
} finally {
  await browser?.close();
  server.kill('SIGTERM');
  await new Promise(resolve => server.exitCode !== null ? resolve() : server.once('exit', resolve));
}

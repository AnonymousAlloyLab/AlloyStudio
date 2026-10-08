// Display-only Alloy lexer. Preserve text and UTF-16 offsets; never parse or
// rewrite the model. Keyword sources are linked in docs/alloy-editor-spec.md.
const KEYWORDS = new Set(('abstract all and as assert but check disj else exactly extends fact for fun '
  + 'iff implies in Int iden let lone module no none not one open or pred run set sig some sum univ '
  + 'after always before enabled event eventually historically invariant modifies once releases '
  + 'since steps triggered until var seq private enum').split(' '));
const OPERATORS = /^(?:<=>|=>|->|<:|:>|\+\+|&&|\|\||!=|<=|=<|>=|[!#&+\-*~^=<>.|:;,{}()[\]'])/;

export function alloyTokens(source) {
  const tokens = [];
  let index = 0;
  while (index < source.length) {
    const start = index;
    let kind = 'plain';
    let closed = true;
    const pair = source.slice(index, index + 2);
    if (pair === '//' || pair === '--') {
      kind = 'comment';
      while (index < source.length && !/[\r\n]/.test(source[index])) index += 1;
    } else if (pair === '/*') {
      kind = 'comment';
      const end = source.indexOf('*/', index + 2);
      closed = end >= 0;
      index = end < 0 ? source.length : end + 2;
    } else if (source[index] === '"') {
      kind = 'string';
      closed = false;
      index += 1;
      while (index < source.length) {
        if (source[index] === '\\') { index = Math.min(source.length, index + 2); continue; }
        if (source[index++] === '"') { closed = true; break; }
      }
    } else {
      const identifier = /^[A-Za-z_$][A-Za-z0-9_$"]*/.exec(source.slice(index));
      const number = /^\d+/.exec(source.slice(index));
      const operator = OPERATORS.exec(source.slice(index));
      if (identifier) {
        index += identifier[0].length;
        if (KEYWORDS.has(identifier[0])) kind = 'keyword';
      } else if (number) { kind = 'number'; index += number[0].length; }
      else if (operator) { kind = 'operator'; index += operator[0].length; }
      else {
        index += source.codePointAt(index) > 0xffff ? 2 : 1;
        while (index < source.length && /\s/.test(source[index]) && /\s/.test(source[start])) index += 1;
      }
    }
    tokens.push({ start, end: index, kind, text: source.slice(start, index), closed });
  }
  return tokens;
}

function appendTokens(target, source, tokens, start, end) {
  for (const token of tokens) {
    const left = Math.max(start, token.start), right = Math.min(end, token.end);
    if (left >= right) continue;
    const text = source.slice(left, right);
    if (token.kind === 'plain') target.append(document.createTextNode(text));
    else {
      const span = document.createElement('span');
      span.className = `alloy-${token.kind}`;
      span.textContent = text;
      target.append(span);
    }
  }
}

export function renderAlloyCode(target, source, range = null) {
  // Keep malformed drafts with thousands of tiny tokens inexpensive to paint.
  // Plain text is an exact fallback; ranges/copying and the complete code remain.
  let tokens = alloyTokens(source);
  if (tokens.length > 1536) tokens = [{ start: 0, end: source.length, kind: 'plain', text: source }];
  const fragment = document.createDocumentFragment();
  if (range && Number.isInteger(range.start) && Number.isInteger(range.end)
    && range.start >= 0 && range.end > range.start && range.end <= source.length) {
    appendTokens(fragment, source, tokens, 0, range.start);
    const mark = document.createElement('mark');
    mark.className = 'source-range';
    mark.dataset.start = range.start;
    mark.dataset.end = range.end;
    appendTokens(mark, source, tokens, range.start, range.end);
    fragment.append(mark);
    appendTokens(fragment, source, tokens, range.end, source.length);
  } else appendTokens(fragment, source, tokens, 0, source.length);
  target.replaceChildren(fragment);
}

function codeTokens(source) {
  return alloyTokens(source).filter(token => token.kind !== 'comment' && token.kind !== 'string');
}

function insideLiteral(source, offset) {
  return alloyTokens(source).some(token => ['comment', 'string'].includes(token.kind)
    && token.start < offset && (token.end > offset || (token.end === offset
      && (token.kind === 'comment' && !token.text.startsWith('/*') || !token.closed))));
}

export function enterIndent(source, start, end = start) {
  const before = source.slice(0, start);
  const line = before.slice(before.lastIndexOf('\n') + 1);
  const indent = /^[\t ]*/.exec(line)[0];
  let extra = '';
  const last = codeTokens(before).filter(token => token.text.trim()).at(-1);
  const literal = alloyTokens(source).find(token => ['comment', 'string'].includes(token.kind)
    && token.start < start && token.end >= start);
  const literalContinues = literal && (literal.kind === 'string' || literal.text.startsWith('/*'))
    && insideLiteral(source, start);
  if (!literalContinues && last?.start >= start - line.length
    && last.end <= start && /^(?:[({\[]|\|)$/.test(last.text)) extra = '  ';
  const close = source.slice(end).match(/^[\t ]*([)}\]])/);
  const pairs = { '(': ')', '{': '}', '[': ']' };
  const suffix = extra && close && pairs[last?.text] === close[1] ? `\n${indent}` : '';
  const text = `\n${indent}${extra}${suffix}`;
  return { start, end: suffix ? end + close[0].length - 1 : end, text,
    cursor: start + 1 + indent.length + extra.length };
}

export function closingIndent(source, start, end, character) {
  if (!/^[)}\]]$/.test(character) || start !== end || insideLiteral(source, start)) return null;
  const lineStart = source.lastIndexOf('\n', start - 1) + 1;
  const line = source.slice(lineStart, start);
  if (!/^(?: {2}|\t)+$/.test(line)) return null;
  return { start: start - (line.endsWith('\t') ? 1 : 2), end, text: character };
}

export function indentAlloy(source) {
  const tokens = codeTokens(source);
  const literals = alloyTokens(source).filter(token => token.kind === 'string'
    || (token.kind === 'comment' && token.text.startsWith('/*')));
  let depth = 0, offset = 0, cursor = 0, literalCursor = 0, bytes = 0;
  const lines = [];
  const encoder = new TextEncoder();
  for (const line of source.split('\n')) {
    const first = offset, end = offset + line.length;
    offset = end + 1;
    while (literalCursor < literals.length && literals[literalCursor].end <= first) literalCursor += 1;
    const inLiteral = literals[literalCursor]?.start < first && literals[literalCursor]?.end > first;
    const leadingClose = /^[\t ]*([)}\]]+)/.exec(line)?.[1].length || 0;
    const formatted = line.trim() ? inLiteral ? line
      : '  '.repeat(Math.min(64, Math.max(0, depth - leadingClose))) + line.replace(/^[\t ]*/, '')
      : inLiteral ? line : '';
    bytes += encoder.encode(formatted).length + (lines.length ? 1 : 0);
    // Match the backend's body budget; formatting an invalid draft must not
    // amplify it into megabytes or change its tokens to fit a budget.
    if (bytes > 8192) return source;
    lines.push(formatted);
    while (cursor < tokens.length && tokens[cursor].start < end) {
      const token = tokens[cursor++];
      if (/^[({\[]$/.test(token.text)) depth += 1;
      if (/^[)}\]]$/.test(token.text)) depth = Math.max(0, depth - 1);
    }
  }
  return lines.join('\n');
}

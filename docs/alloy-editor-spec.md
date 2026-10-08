# Alloy editor and combined environment contract

The read-only model environment combines `environmentBefore`, the unchanged
`predicateHeader`, a `{\n  // TODO: write the predicate body in the editor above\n}`
placeholder, and `environmentAfter`. It never inserts a correct answer or the
learner draft into this view. The original environment bytes remain unchanged
in downloads, backend records, and solver requests. The two context tabs disappear.

Syntax coloring is a display-only lexical pass, based on the
[Alloy language reference](https://alloytools.org/spec.html) and
[Alloy 6 keywords](https://alloytools.org/alloy6.html). It is not an Alloy parser.
Keywords are bold/colored; comments, strings, operators, and numbers have
distinct colors. Every displayed text character and UTF-16 source offset is
preserved, including tabs, Unicode, incomplete input, and HTML-like text. DOM
text nodes are used throughout. Defect highlighting composes with token colors
and continues to select the exact backend-supplied structural range.

The textarea retains native selection, copying, screen-reader input, scrolling,
and resizing. Its synchronized mirror supplies colors without an external
editor dependency. Indentation uses two spaces. Enter carries the current
indent and adds a level after a code opening delimiter or quantifier `|`.
Enter between matching delimiters places the closing delimiter on its own
line. A closing delimiter on an otherwise blank indented line removes one
level. Delimiters inside comments/strings do not affect indentation. Explicit
“Indent code” changes only leading whitespace outside strings/block comments;
it never rewrites tokens, comments, predicate names, or environment code.
Formatting scans tokens/lines with advancing cursors, caps displayed indentation
at 64 levels, and retains the original draft if the result would exceed the
backend's 8 KiB UTF-8 body limit. Native insertion preserves browser undo/redo
where supported, with a normal edit fallback when the native command is unavailable.
Above 1,536 lexical tokens, the mirror renders the exact source as plain text
with defect underlines, avoiding thousands of colored DOM elements for malformed
or unusually complex drafts. The complete model environment remains visible.

The environment panel grows to the height of its complete text, with a 300 px
minimum on desktop. It has no internal vertical or horizontal scroll area:
long lines wrap at the panel edge, while all source characters remain available
for selection and copying. Small screens use a 220 px minimum. The page itself
can scroll normally. This is a read-only display change; solver input is unchanged.

Existing Escape then Tab / Shift+Tab focus escape and Ctrl/Command+Enter
checking remain available. Each indentation action follows the normal draft
revision/invalidation path. Source locations are invalidated after any edit.
The language module is served through the fixed static allowlist and packaged
with a content-versioned import, including IIS subpath deployments.

Validation covers token/text preservation and malicious-looking input,
comments/strings and temporal operators, indentation token invariance, UTF-16
defect ranges, synchronized geometry, keyboard behavior, combined environment,
and package/static-route completeness. These are regression claims, not new
formal closure claims.

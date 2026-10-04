package live;

/** Bounded RFC JSON syntax check before org.json's more permissive decoder. */
final class WorkerJson {
    private final String source;
    private int cursor;
    private WorkerJson(String source) { this.source = source; }
    static void check(String source) {
        WorkerJson parser = new WorkerJson(source);
        parser.value(0);
        parser.space();
        if (parser.cursor != source.length()) throw new IllegalArgumentException();
    }
    private void space() {
        while (cursor < source.length() && " \r\n\t".indexOf(source.charAt(cursor)) >= 0) cursor++;
    }
    private char take() {
        if (cursor == source.length()) throw new IllegalArgumentException();
        return source.charAt(cursor++);
    }
    private void expected(char value) {
        if (take() != value) throw new IllegalArgumentException();
    }
    private void string() {
        expected('"');
        while (true) {
            char value = take();
            if (value == '"') return;
            if (value < 32) throw new IllegalArgumentException();
            if (value == '\\') {
                char escape = take();
                if (escape == 'u') {
                    for (int i = 0; i < 4; i++)
                        if ("0123456789abcdefABCDEF".indexOf(take()) < 0) throw new IllegalArgumentException();
                } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw new IllegalArgumentException();
            }
        }
    }
    private boolean digit() {
        return cursor < source.length() && source.charAt(cursor) >= '0' && source.charAt(cursor) <= '9';
    }
    private void number() {
        if (cursor < source.length() && source.charAt(cursor) == '-') cursor++;
        char leading = take();
        if (leading >= '1' && leading <= '9') { while (digit()) cursor++; }
        else if (leading != '0') throw new IllegalArgumentException();
        if (cursor < source.length() && source.charAt(cursor) == '.') {
            cursor++;
            if (!digit()) throw new IllegalArgumentException();
            while (digit()) cursor++;
        }
        if (cursor < source.length() && "eE".indexOf(source.charAt(cursor)) >= 0) {
            cursor++;
            if (cursor < source.length() && "+-".indexOf(source.charAt(cursor)) >= 0) cursor++;
            if (!digit()) throw new IllegalArgumentException();
            while (digit()) cursor++;
        }
    }
    private void value(int depth) {
        if (depth > 128) throw new IllegalArgumentException();
        space();
        if (cursor >= source.length()) throw new IllegalArgumentException();
        char first = source.charAt(cursor);
        if (first == '"') { string(); return; }
        if (first == '{' || first == '[') {
            cursor++;
            char closing = first == '{' ? '}' : ']';
            space();
            if (cursor < source.length() && source.charAt(cursor) == closing) { cursor++; return; }
            while (true) {
                if (first == '{') { space(); string(); space(); expected(':'); }
                value(depth + 1); space();
                char separator = take();
                if (separator == closing) return;
                if (separator != ',') throw new IllegalArgumentException();
            }
        }
        for (String keyword : new String[]{"true", "false", "null"}) {
            if (source.startsWith(keyword, cursor)) { cursor += keyword.length(); return; }
        }
        number();
    }
}

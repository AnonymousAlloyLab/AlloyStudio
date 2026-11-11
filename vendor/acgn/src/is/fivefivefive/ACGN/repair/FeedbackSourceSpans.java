package is.fivefivefive.ACGN.repair;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Completes parser spans using delimiters in the owned predicate, ignoring comments/strings. */
final class FeedbackSourceSpans {
    private final int[] mate;
    FeedbackSourceSpans(String text) {
        mate = new int[text.length()]; Arrays.fill(mate, -1);
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        boolean quote = false, escape = false, line = false, block = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (line) { if (c == '\n' || c == '\r') line = false; continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (quote) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') quote = false;
                continue;
            }
            if (c == '"') { quote = true; continue; }
            if (c == '/' && next == '*' ) { block = true; i++; continue; }
            if (c == '/' && next == '/' || c == '-' && next == '-') { line = true; i++; continue; }
            if (c == '(' || c == '[' || c == '{') stack.push(i);
            else if (c == ')' || c == ']' || c == '}') {
                if (stack.isEmpty()) throw new IllegalArgumentException("UNBALANCED_SOURCE");
                int open = stack.pop(); char start = text.charAt(open);
                if (!(start == '(' && c == ')' || start == '[' && c == ']' || start == '{' && c == '}')) {
                    throw new IllegalArgumentException("UNBALANCED_SOURCE");
                }
                mate[open] = i; mate[i] = open;
            }
        }
        if (quote || block || !stack.isEmpty()) throw new IllegalArgumentException("UNBALANCED_SOURCE");
    }

    int[] complete(int start, int end) {
        if (start < 0 || end > mate.length || start >= end) throw new IllegalArgumentException("SPAN_OUTSIDE_BODY");
        boolean changed;
        do {
            int oldStart = start, oldEnd = end;
            for (int i = start; i < end; i++) if (mate[i] >= 0) {
                start = Math.min(start, mate[i]); end = Math.max(end, mate[i] + 1);
            }
            changed = start != oldStart || end != oldEnd;
        } while (changed);
        return new int[] {start, end};
    }
}

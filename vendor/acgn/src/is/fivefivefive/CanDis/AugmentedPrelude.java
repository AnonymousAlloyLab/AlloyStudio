package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.ExprVar;
import edu.mit.csail.sdg.parser.CompLexer;
import edu.mit.csail.sdg.parser.CompSym;
import java_cup.runtime.Symbol;

/** Renames only a lexer-identified module name, preserving declarations and parameters. */
final class AugmentedPrelude {
    private AugmentedPrelude() { }

    static String withModuleName(String source, String name) throws IOException {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid generated module name");
        }
        CompLexer lexer = new CompLexer(new StringReader(source));
        lexer.alloy_filename = "augmented-prelude";
        lexer.alloy_seenDollar = new ArrayList<>();
        if (lexer.next_token().sym != CompSym.MODULE) {
            return "module " + name + "\n" + source;
        }
        Pos nameSpan = requireName(lexer.next_token());
        Symbol following = lexer.next_token();
        while (following.sym == CompSym.SLASH) {
            nameSpan = nameSpan.merge(requireName(lexer.next_token()));
            following = lexer.next_token();
        }
        int[] range = nameSpan.toStartEnd(source);
        if (range == null || range.length != 2 || range[0] < 0
                || range[1] < range[0] || range[1] > source.length()) {
            throw new IOException("Module name has invalid source coordinates");
        }
        return source.substring(0, range[0]) + name + source.substring(range[1]);
    }

    private static Pos requireName(Symbol token) throws IOException {
        if (token.sym != CompSym.ID || !(token.value instanceof ExprVar variable)) {
            throw new IOException("Module header lacks a valid name component");
        }
        return variable.pos;
    }
}

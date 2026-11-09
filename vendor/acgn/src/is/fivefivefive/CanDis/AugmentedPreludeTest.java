package is.fivefivefive.CanDis;

import java.io.IOException;
import java.util.List;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;

public final class AugmentedPreludeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        for (String source : List.of("sig A {}\n", "sig A {}", "",
                "open util/ordering[A]\nsig A {}\n", "// module fake\nsig A {}\n",
                "/* module fake */\r\nsig A {}\r\n")) {
            String result = AugmentedPrelude.withModuleName(source, "generated");
            check(result.equals("module generated\n" + source), "module-less prelude preserved byte-for-byte");
            parse(result);
        }
        for (String[] pair : List.of(
                new String[] {"module old\nsig A {}", "module generated\nsig A {}"},
                new String[] {"module old sig A {}", "module generated sig A {}"},
                new String[] {"// lead\nmodule old/pkg\nsig A {}", "// lead\nmodule generated\nsig A {}"},
                new String[] {"/* lead */\nmodule /* kept */ old[A]\nsig B {}",
                        "/* lead */\nmodule /* kept */ generated[A]\nsig B {}"},
                new String[] {"\tmodule old\r\nsig A {}\r\n", "\tmodule generated\r\nsig A {}\r\n"},
                new String[] {"module old[\n exactly A, B\n]\nsig C {}",
                        "module generated[\n exactly A, B\n]\nsig C {}"})) {
            String result = AugmentedPrelude.withModuleName(pair[0], "generated");
            check(result.equals(pair[1]), "only module name changes");
            var original = CompUtil.parseEverything_fromString(A4Reporter.NOP, pair[0]);
            var rewritten = CompUtil.parseEverything_fromString(A4Reporter.NOP, result);
            check(original.getAllReachableUserDefinedSigs().size()
                    == rewritten.getAllReachableUserDefinedSigs().size(), "parameter and signature counts preserved");
            check(rewritten.getModuleName().equals("generated"), "renamed module parses");
        }
        try {
            AugmentedPrelude.withModuleName("module /broken", "generated");
            throw new AssertionError("invalid header accepted");
        } catch (IOException expected) { check(true, "malformed header rejected"); }
        System.out.println("AugmentedPreludeTest: " + checks + " checks passed");
    }

    private static void parse(String text) throws Exception {
        var parsed = CompUtil.parseEverything_fromString(A4Reporter.NOP, text);
        check(parsed.getModuleName().equals("generated"), "generated module parses");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}

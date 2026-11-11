package live;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.RawAstTrace;
import is.fivefivefive.CanDis.WorkBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Differential parser controls for the file-free non-worker path. */
public final class ParserSourceMapSelfTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        for (String source : List.of(
                "sig A { r: set A } pred p { all x:A | some x.r }",
                "open util/ordering[A]\nsig A {} pred p { some first }",
                "sig A {} pred p { always (some A implies after no A) }",
                "sig A {} pred p { let x = A | some x }",
                "// unicode comment: \u03b1\n sig A {} pred p { some A }")) {
            var disk = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
            var memory = WorkerSafety.parse(source);
            var a = RawAstTrace.prepare(disk, "p");
            var b = RawAstTrace.prepare(memory, "p");
            check(RawAstTrace.distance(a, b) == 0 && RawAstTrace.distance(b, a) == 0, "typed AST correspondence");
            check(RawAstTrace.trace(a, b, 0).edits().isEmpty(), "replay correspondence");
            var original = predicate(disk);
            var current = predicate(memory);
            var p = original.getBody().span();
            var q = current.getBody().span();
            check(p.x == q.x && p.y == q.y && p.x2 == q.x2 && p.y2 == q.y2, "source coordinates");
            check(!Files.exists(Path.of(q.filename)), "no source file materialized");
            check(!q.filename.equals(predicate(WorkerSafety.parse(source)).getBody().span().filename),
                    "separate source identities");
        }
        for (String source : List.of("sig A {} pred p { some ( }", "sig A {} pred p { some UNKNOWN }",
                "sig A {} pred p { A and A }", "open missing_unique_module_4072\nsig A {} pred p { some A }")) {
            Err disk = error(source, false), memory = error(source, true);
            check(disk.getClass().equals(memory.getClass()), "same invalid-input rejection kind");
            check(disk.pos.x == memory.pos.x && disk.pos.y == memory.pos.y, "same error coordinates");
        }
        String importedName = "parser_import_" + java.util.UUID.randomUUID().toString().replace("-", "");
        Path imported = Path.of(System.getProperty("java.io.tmpdir"), importedName + ".als");
        Files.writeString(imported, "module " + importedName + "\nsig A {}\npred ok { some A }");
        try {
            String source = "open " + importedName + "\npred p { ok }";
            var a = RawAstTrace.prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP, source), "p");
            var b = RawAstTrace.prepare(WorkerSafety.parse(source), "p");
            check(RawAstTrace.distance(a, b) == 0 && RawAstTrace.distance(b, a) == 0, "relative import directory preserved");
        } finally { Files.delete(imported); }
        WorkBudget.begin(2);
        try {
            boolean rejected = false;
            try { WorkerSafety.parse("sig A {} pred p { some A }"); }
            catch (WorkBudget.Exhausted expected) { rejected = true; }
            check(rejected && WorkBudget.exhausted(), "input still charged before parsing");
        } finally { WorkBudget.end(); }
        Path owned = Files.createTempDirectory("parser-owned-control-");
        WorkerSafety.begin(owned);
        try {
            WorkerSafety.parse("sig A {} pred p { some A }");
            check(Files.isRegularFile(owned.resolve("source-1.als")), "owned worker path unchanged");
        } finally {
            check(WorkerSafety.end() == 1, "owned parse accounting unchanged");
            Files.deleteIfExists(owned.resolve("source-1.als"));
            Files.delete(owned);
        }
        System.out.println("ParserSourceMapSelfTest passed (" + checks + " checks)");
    }
    private static Func predicate(CompModule module) {
        for (Func function : module.getAllFunc()) if (function.label.equals("this/p")) return function;
        throw new AssertionError("Missing predicate");
    }
    private static Err error(String source, boolean memory) {
        try {
            if (memory) WorkerSafety.parse(source);
            else CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        } catch (Err error) { return error; }
        throw new AssertionError("Invalid source accepted");
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

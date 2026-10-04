package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.alloy.AlloyLibraryCallableLedger;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

/** Bounded regressions for non-temporal failures found by the corpus preflight. */
public final class FullCorpusNonTemporalP0RegressionTest {
    private static int checks;

    private FullCorpusNonTemporalP0RegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        requireImportedOverload("max", 0);
        requireImportedOverload("max", 1);
        requireImportedOverload("min", 0);
        requireImportedOverload("min", 1);
        rejectImportedOverload("max", 2);

        prepare("classified-data/graphs/under/oisjGf4FHY7ybsDNY_inv6.als", "inv6");
        prepare("classified-data/graphs/under/mwErw7ZWCMusvp6BG_inv7.als", "inv7");
        prepare("classified-data/graphs/both/QcRD5957JiXbxbX69_inv3.als", "inv3");
        prepare("classified-data/trainStationNew/under/DMXEQCwkZDKHBrj68_inv4.als", "inv4");
        prepare("classified-data/trainStationNew/both/8gHAegWhaGCAkwRvP_inv5.als", "inv5");
        prepare("classified-data/productionLine_v2/under/MKZQjPQz4XArtL6sA_inv5.als", "inv5");
        prepare("classified-data/coursesOld/over/BELuLQRupeCQWiBYW_inv13.als", "inv13");
        prepare("classified-data/coursesOld/over/wtyqiAToMKNyvWmww_inv15.als", "inv15");
        prepare("classified-data/cv_v1/both/BATG5mJQrmFFWErWW_inv4.als", "inv4");
        prepare("classified-data/trash_rl/both/M5ddBc6EYZS233e4n_inv9.als", "inv9");

        System.out.println("FullCorpusNonTemporalP0RegressionTest: "
                + checks + " checks passed");
    }

    private static void requireImportedOverload(String member, int arity) {
        AlloyLibraryCallableLedger.Signature signature =
                AlloyLibraryCallableLedger.require(
                        "util/integer",
                        member,
                        CallSymbol.Kind.EXPRESSION,
                        arity);
        check(signature.arity() == arity
                        && signature.kind() == CallSymbol.Kind.EXPRESSION,
                "wrong imported signature for " + member + "/" + arity);
    }

    private static void rejectImportedOverload(String member, int arity) {
        try {
            AlloyLibraryCallableLedger.require(
                    "util/integer",
                    member,
                    CallSymbol.Kind.EXPRESSION,
                    arity);
            throw new AssertionError("unpinned overload was accepted: "
                    + member + "/" + arity);
        } catch (IllegalStateException expected) {
            checks++;
        }
    }

    private static void prepare(String source, String predicate) throws Exception {
        Path path = Path.of(source);
        check(Files.isRegularFile(path), "missing corpus witness " + source);
        CompModule module = AlloyUtil.compileAlloyModule(path.toString());
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        Integer graphId = visitor.getForestId(predicate);
        check(graphId != null, "missing predicate " + predicate + " in " + source);
        Multigraph graph = visitor.getForest().get(graphId);
        check(graph != null, "missing graph " + predicate + " in " + source);
        check(CanonicalAlloyPipeline.prepare(graph) != null,
                "faithful preparation failed for " + source);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

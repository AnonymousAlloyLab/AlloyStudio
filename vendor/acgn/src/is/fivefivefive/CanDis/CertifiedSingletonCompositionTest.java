package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.theory.FlatConstructionCertificate;
import is.fivefivefive.CanDis.theory.CertificateBundleWriter;
import parser.ast.nodes.ModelUnit;

/** Source-linked singleton composition, with bounded semantic negative controls. */
public final class CertifiedSingletonCompositionTest {
    private static int checks;
    private static final String SOURCE = """
            sig Workstation { workers: set Worker }
            sig Worker {}
            sig Human, Robot in Worker {}
            pred paired {
              all h,r:Worker | all w:Workstation |
                h in Human and r in Robot and h in w.workers implies r not in w.workers
              all h,r:Worker | all w:Workstation |
                h in Human and r in Robot and r in w.workers implies h not in w.workers
            }
            pred single {
              all h,r:Worker | all w:Workstation |
                h in Human and r in Robot and h in w.workers implies r not in w.workers
            }
            pred bad {
              all h,r:Worker | all w:Workstation |
                h in Human and r in Robot and h in w.workers implies r in w.workers
            }
            """;

    public static void main(String[] args) throws Exception {
        CompModule module = parse(SOURCE);
        Canonical.Prepared left = prepare(module, "paired");
        Canonical.Prepared right = prepare(module, "single");
        var certified = CanonicalAlloyPipeline.prepare(left);
        var other = CanonicalAlloyPipeline.prepare(right);
        check(Canonical.distance(left, right) == 0, "reduced Fast equality");
        check(CanonicalAlloyPipeline.distance(certified, other) == 0,
                "source-linked composition has zero distance");
        check(certified.canonicalObservation().equivalentTo(other.canonicalObservation()),
                "source-linked composition has the same canonical observation");
        check(certified.semanticArtifact().flatConstructions().stream()
                .anyMatch(FlatConstructionCertificate::hasExposures), "insertion-linked exposure retained");
        check(certified.semanticArtifact().flatConstructions().stream()
                .anyMatch(FlatConstructionCertificate::collapsedToSingleton), "existing singleton evidence retained");
        var missing = new ArrayList<>(certified.semanticArtifact().flatConstructions());
        missing.removeIf(FlatConstructionCertificate::collapsedToSingleton);
        try {
            certified.semanticArtifact().withFlatConstructions(missing);
            throw new AssertionError("Missing source certificate must not be accepted");
        } catch (IllegalArgumentException expected) {
            check(true, "missing source certificate rejected");
        }
        for (int scope : new int[] {1, 2, 4, 6}) {
            check(!solve(module, "not (paired iff single)", scope), "bounded source equality scope " + scope);
            check(solve(module, "not (paired iff bad)", scope), "negative control scope " + scope);
        }
        check(Canonical.distance(left, prepare(module, "bad")) > 0, "changed membership retained");
        check(CanonicalAlloyPipeline.distance(certified,
                CanonicalAlloyPipeline.prepare(prepare(module, "bad"))) > 0,
                "changed membership retained by certified path");
        String priorOverride = System.getProperty("acgn.provenance.testOverride");
        Path exportDir = Files.createTempDirectory("flat-exposure-export-");
        Path bundle = exportDir.resolve("unsupported.acgncert");
        try {
            System.setProperty("acgn.provenance.testOverride", "true");
            var visitor = new MASGVisitor(new GlobalVariables(), Set.of("paired"), module);
            visitor.visit(new ModelUnit(null, module), null);
            var retained = CanonicalAlloyPipeline.prepareCompatibilityForVerification(
                    visitor.getForest().get(visitor.getForestId("paired")));
            try {
                CertificateBundleWriter.write(retained.certificateExportSession(), bundle);
                throw new AssertionError("Unsupported exposure must not be exported as verified");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("source-linked flat exposure"),
                        "missing independent wire replay fails closed");
                check(!Files.exists(bundle), "no unsupported bundle written");
            }
        } finally {
            if (priorOverride == null) System.clearProperty("acgn.provenance.testOverride");
            else System.setProperty("acgn.provenance.testOverride", priorOverride);
            Files.deleteIfExists(bundle);
            Files.delete(exportDir);
        }

        String root = "classified-data/productionLineNew/correct/";
        var common = prepare(parse(Files.readString(Path.of(root + "vxzQHEki2BEDvK5Fc_inv5.als"))), "inv5");
        for (String name : new String[] {"n2WnyJE2dPAAwbi7G", "xmAjG8FuhbWTR4QL2",
                "Aa78bZ38iggPmC6np", "CiW5iLsW9zifA8zqT"}) {
            var peer = prepare(parse(Files.readString(Path.of(root + name + "_inv5.als"))), "inv5");
            check(Canonical.distance(common, peer) == 0, "original Fast zero " + name);
            var a = CanonicalAlloyPipeline.prepare(common);
            var b = CanonicalAlloyPipeline.prepare(peer);
            check(CanonicalAlloyPipeline.distance(a, b) == 0
                    && CanonicalAlloyPipeline.distance(b, a) == 0, "original composed equality " + name);
            check(a.canonicalObservation().equivalentTo(b.canonicalObservation()),
                    "original canonical equality " + name);
        }
        CompModule outlier = parse(Files.readString(Path.of(
                "classified-data/coursesOld/both/xJnitBpFv9K74WNvf_inv14.als")));
        var x = prepare(outlier, "inv14");
        var y = prepare(outlier, "inv14c");
        check(Canonical.distance(x, y) == 49, "outlier Fast remains 49");
        var cx = CanonicalAlloyPipeline.prepare(x);
        var cy = CanonicalAlloyPipeline.prepare(y);
        check(CanonicalAlloyPipeline.distance(cx, cy) == 49
                && CanonicalAlloyPipeline.distance(cy, cx) == 49, "outlier uses source-certified singleton collapse");
        check(cx.repairView().phases().get(0).matrix().children().get(0).children().stream()
                .noneMatch(node -> node.operator().equals("AND") && node.children().size() == 1),
                "certified original operands remove the residual wrapper");
        System.out.println("CertifiedSingletonCompositionTest: " + checks + " checks passed");
    }

    private static CompModule parse(String source) throws Exception {
        return CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
    }

    private static Canonical.Prepared prepare(CompModule module, String predicate) {
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of(predicate), module);
        visitor.visit(new ModelUnit(null, module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(predicate)));
    }

    private static boolean solve(CompModule module, String expression, int scope) throws Exception {
        var command = new Command(false, scope, 4, 4, null,
                CompUtil.parseOneExpression_fromString(module, expression));
        var options = new A4Options();
        options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,
                module.getAllReachableSigs(), command, options).satisfiable();
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

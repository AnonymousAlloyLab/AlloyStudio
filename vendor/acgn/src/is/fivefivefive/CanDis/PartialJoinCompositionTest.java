package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** P03 source-occurrence, insertion-linkage and finite Alloy controls. */
public final class PartialJoinCompositionTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void reject(Runnable action, String label) {
        checks++; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(label);
    }
    private static Canonical.Prepared prepare(CompModule module, String predicate) {
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of(predicate), module);
        visitor.visit(new ModelUnit(null, module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(predicate)));
    }
    private static CompModule module(String path) throws Exception {
        return CompUtil.parseEverything_fromString(A4Reporter.NOP, Files.readString(Path.of(path)));
    }
    private static EGraphNode source(Canonical.Prepared input, long lineage) {
        var pending = new ArrayDeque<EGraphNode>();
        for (var form : input.normalizedForms()) pending.add(form.getMatrixEGraph());
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            if (node.getSourceOccurrenceLineage() == lineage) return node;
            pending.addAll(node.getChildren());
        }
        throw new AssertionError("Missing original source occurrence");
    }
    private static boolean solve(CompModule module, String expression, int scope) throws Exception {
        var options = new A4Options(); options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.getAllReachableSigs(),
                new Command(false, scope, 4, 4, null,
                        CompUtil.parseOneExpression_fromString(module, expression)), options).satisfiable();
    }
    private static void pair(Canonical.Prepared left, Canonical.Prepared right, String label) {
        check(Canonical.distance(left, right) == 0 && Canonical.distance(right, left) == 0, label+" Fast0");
        var l = CanonicalAlloyPipeline.prepare(left); var r = CanonicalAlloyPipeline.prepare(right);
        check(CanonicalAlloyPipeline.distance(l, r) == 0 && CanonicalAlloyPipeline.distance(r, l) == 0,
                label+" certified0 both directions");
        check(l.canonicalObservation().equivalentTo(r.canonicalObservation()), label+" producer equality");
        check(!l.semanticArtifact().guardedJoinConstructions().isEmpty(), label+" actual proof retained");
        for (var c : l.semanticArtifact().guardedJoinConstructions()) CertificateVerifier.verify(c);
    }
    public static void main(String[] args) throws Exception {
        CompModule reduced = module("docs/distance-parity-20261107/partial-join.als");
        var left = prepare(reduced, "lhs"); var right = prepare(reduced, "rhs");
        pair(left, right, "reduced");
        var evidence = TheoryAlloyAdapter.adapt(left.normalizedForms(), left.semanticProfile());
        var artifact = evidence.semanticArtifact();
        var proof = artifact.guardedJoinConstructions().get(0);
        check(proof.steps().equals(List.of(List.of(), List.of(0))), "two exact local rotation paths");
        check(proof.target().presentationOperands().size() == 2, "unary interior keeps outer binary JOIN");
        check(proof.target().left().presentationOperands().size() == 3, "only guarded prefix is flattened");
        reject(() -> DependentChainTheory.requireSoundFlattening(DependentChainKind.JOIN,
                proof.source().leaves().stream().map(t -> t.type().relationType()).toList()), "whole chain still rejected");
        reject(() -> artifact.withGuardedJoinConstructions(List.of()), "source ledger rejects missing proof");
        var other = CanonicalAlloyPipeline.prepare(right).semanticArtifact().guardedJoinConstructions().get(0);
        reject(() -> artifact.withGuardedJoinConstructions(List.of(other)), "source ledger rejects substituted proof");
        EGraphNode original = source(left, proof.sourceOccurrenceLineage()); proof.requireMatches(original);
        reject(() -> proof.requireMatches(source(right, other.sourceOccurrenceLineage())), "another occurrence rejected");
        var child = proof.realization().children().get(0);
        reject(() -> new GuardedJoinNodeCertificate.Child(child.construction(),
                proof.source().leaves().get(0).leaf().port(), child.insertion()), "wrong inserted leaf rejected");
        reject(() -> new GuardedJoinNodeCertificate.Child(child.construction(), child.port(),
                EqualityCertificates.reflexive(TypedCertificateEndpoint.node(child.construction().node()))),
                "unlinked node proof rejected");
        var saved = original.getChildren();
        reject(() -> original.setChildren(List.of(saved.get(1), saved.get(0))),
                "certified source children cannot be mutated");
        for (int scope : new int[]{1,2,4,6})
            check(!solve(reduced, "not (lhs iff rhs)", scope), "reduced Alloy equality scope "+scope);

        for (var names : List.of(List.of("qnxyu7R5W48bktkkJ", "rFKSTy9XyDT6hQKKA"),
                List.of("DTvPvFQrJCN5rrmEe", "f6HNybZwPFRfocGYW"))) {
            String prefix = "classified-data/classroom_rl/correct/";
            pair(prepare(module(prefix+names.get(0)+"_inv12.als"), "inv12"),
                    prepare(module(prefix+names.get(1)+"_inv12.als"), "inv12"), names.toString());
        }
        CompModule negative = CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                sig A { r,t:set A }
                sig U in A {}
                pred left { some (r.U).t }
                pred right { some r.(U.t) }
                """);
        var nleft = CanonicalAlloyPipeline.prepare(prepare(negative, "left"));
        var nright = CanonicalAlloyPipeline.prepare(prepare(negative, "right"));
        check(CanonicalAlloyPipeline.distance(nleft, nright) > 0, "unary-middle mismatch retained");
        check(solve(negative, "not (left iff right)", 3), "independent unary-middle counterexample");

        String prior = System.getProperty("acgn.provenance.testOverride");
        Path dir = Files.createTempDirectory("guarded-join-export-"); Path output = dir.resolve("blocked.acgncert");
        try {
            System.setProperty("acgn.provenance.testOverride", "true");
            var visitor = new MASGVisitor(new GlobalVariables(), Set.of("lhs"), reduced);
            visitor.visit(new ModelUnit(null, reduced), null);
            var retained = CanonicalAlloyPipeline.prepareCompatibilityForVerification(
                    visitor.getForest().get(visitor.getForestId("lhs")));
            try {
                retained.certificateExportSession().write(output);
                throw new AssertionError("Unimplemented wire replay must be blocked");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("source-linked guarded JOIN rotation wire replay"),
                        "explicit independent exporter blocker");
                check(!Files.exists(output), "no unsupported bundle produced");
            }
        } finally {
            if (prior == null) System.clearProperty("acgn.provenance.testOverride");
            else System.setProperty("acgn.provenance.testOverride", prior);
            Files.deleteIfExists(output); Files.delete(dir);
        }
        System.out.println("PartialJoinCompositionTest: "+checks+" checks passed");
    }
}

package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

/** Source-linked local-domain congruence and bounded independent Alloy checks. */
public final class LocalDomainSingletonTest {
    private static int checks;
    private static final String SOURCE = """
            sig Person { projects: set Project }
            sig Student in Person {}
            sig Project {}
            pred paired {
              all s1,s2:Student | one p:Project |
                p in s1.projects and p in s2.projects implies
                  all ps:Project-p | ps in s1.projects implies ps not in s2.projects
              all s1,s2:Student | one p:Project |
                p in s1.projects and p in s2.projects implies
                  all ps:Project-p | ps in s2.projects implies ps not in s1.projects
            }
            pred single {
              all s1,s2:Student | one p:Project |
                p in s1.projects and p in s2.projects implies
                  all ps:Project-p | ps in s1.projects implies ps not in s2.projects
            }
            pred renamed {
              all a,b:Student | one z:Project |
                z in a.projects and z in b.projects implies
                  all y:Project-z | y in b.projects implies y not in a.projects
            }
            """;
    private static void check(boolean value,String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void reject(Runnable action,String label) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(label);
    }
    private static Canonical.Prepared prepare(CompModule module,String name) {
        var visitor = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static boolean solve(CompModule m,String expression,int scope) throws Exception {
        var options = new A4Options(); options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,m.getAllReachableSigs(),
                new Command(false,scope,4,4,null,CompUtil.parseOneExpression_fromString(m,expression)),options).satisfiable();
    }
    private static EGraphNode andOccurrence(Canonical.Prepared input) {
        var pending = new java.util.ArrayDeque<EGraphNode>();
        for (var form : input.normalizedForms()) pending.add(form.getMatrixEGraph());
        while (!pending.isEmpty()) {
            EGraphNode n = pending.removeFirst();
            if (n.getOpcode() == EGraphNode.Opcode.AND && n.getChildren().size() == 2
                    && n.getChildren().get(0).getOpcode() == EGraphNode.Opcode.ONE) return n;
            pending.addAll(n.getChildren());
        }
        throw new AssertionError("Expected AND of ONE occurrences");
    }
    private static void partition(Canonical.Prepared input) throws Exception {
        EGraphNode substituted = andOccurrence(prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP,SOURCE),"paired"));
        var adapted = TheoryAlloyAdapter.adapt(input.normalizedForms(),input.semanticProfile());
        boolean found = false;
        for (var entry : adapted.certifiedSetOperandPartitions().entrySet()) {
            EGraphNode source = entry.getKey();
            if (source.getOpcode() != EGraphNode.Opcode.AND || source.getChildren().size() != 2
                    || source.getChildren().get(0).getOpcode() != EGraphNode.Opcode.ONE) continue;
            found = true;
            check(entry.getValue().inputFibers(source).equals(List.of(List.of(0,1))),"actual ONE source fibers collapse");
            reject(()->entry.getValue().inputFibers(substituted),"identical shape at another occurrence is not authority");
        }
        check(found,"source-linked partition is present");
        var artifact = adapted.semanticArtifact();
        var collapsed = artifact.flatConstructions().stream().filter(c -> c.source().operator().operator().equals("ALLOY/AND")
                && c.containerTrace().inputOccurrences().size() == 2 && c.collapsedToSingleton()).toList();
        check(!collapsed.isEmpty(),"actual flat singleton certificate exists");
        for (var c : collapsed) {
            CertificateVerifier.verify(c);
            check(c.containerTrace().outputFibers().equals(List.of(List.of(0,1))),"verifier replays the two-input singleton fiber");
            check(c.containerTrace().inputOccurrences().get(0).equals(c.containerTrace().inputOccurrences().get(1)),
                    "original operands are the same typed invocation, not projected aliases");
            check(!c.hasExposures(),"no extra-source/exposure prototype involved");
        }
        var missing = new ArrayList<>(artifact.flatConstructions()); missing.removeAll(collapsed);
        reject(()->artifact.withFlatConstructions(missing),"missing singleton evidence must fail source ledger");
        boolean domain = artifact.classes().values().stream().flatMap(c->c.shapeWitnesses().keySet().stream())
                .map(CanonicalShape::node).anyMatch(n->n.operator().operator().equals("ALLOY/LOCAL-BIND-DOMAINS/FORALL")
                        && n.ports().size() == 2 && n.ports().get(0) instanceof OnePort
                        && !n.ports().get(0).support().isEmpty() && n.ports().get(1) instanceof BindBlockPort);
        check(domain,"domain is an actual support-bearing port outside the local binder");
    }
    public static void main(String[] args) throws Exception {
        CompModule m = CompUtil.parseEverything_fromString(A4Reporter.NOP,SOURCE);
        var p = prepare(m,"paired"); partition(p);
        for (String peer : new String[]{"single","renamed"}) {
            var q = prepare(m,peer);
            check(Canonical.distance(p,q) == 0,"Fast reduced equality");
            var a = CanonicalAlloyPipeline.prepare(p); var b = CanonicalAlloyPipeline.prepare(q);
            check(CanonicalAlloyPipeline.distance(a,b) == 0 && CanonicalAlloyPipeline.distance(b,a) == 0,"certified reduced equality");
            for (int scope : new int[]{1,2,4,6}) check(!solve(m,"not (paired iff "+peer+")",scope),"independent bounded equality "+peer+" scope "+scope);
        }
        CompModule original = CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(Path.of(
                "classified-data/coursesOld/both/xJnitBpFv9K74WNvf_inv14.als")));
        var x = prepare(original,"inv14"); var y = prepare(original,"inv14c"); partition(x);
        check(Canonical.distance(x,y) == 49,"original Fast49");
        var cx = CanonicalAlloyPipeline.prepare(x); var cy = CanonicalAlloyPipeline.prepare(y);
        check(CanonicalAlloyPipeline.distance(cx,cy) == 49 && CanonicalAlloyPipeline.distance(cy,cx) == 49,"original certified49 both directions");

        CompModule negative = CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r: set A }
                pred left[p,q:set A] { one z:p | z=z }
                pred right[p,q:set A] { one z:q | z=z }
                pred alpha[p,q:set A] { one renamed:p | renamed=renamed }
                pred currentBlock { one a:A, b:a.r | a=b }
                """);
        var left = CanonicalAlloyPipeline.prepare(prepare(negative,"left"));
        var right = CanonicalAlloyPipeline.prepare(prepare(negative,"right"));
        var alpha = CanonicalAlloyPipeline.prepare(prepare(negative,"alpha"));
        check(CanonicalAlloyPipeline.distance(left,alpha) == 0,"real alpha renaming of domain binder");
        check(CanonicalAlloyPipeline.distance(left,right) > 0,"different ambient domain operands must not collapse");
        for (int scope : new int[]{1,2,4}) check(solve(negative,"not (left[A,none] iff right[A,none])",scope),
                "independent domain substitution counterexample scope "+scope);
        var current = CanonicalAlloyPipeline.prepare(prepare(negative,"currentBlock"));
        check(current.semanticArtifact().classes().values().stream().flatMap(c->c.shapeWitnesses().keySet().stream())
                .map(CanonicalShape::node).noneMatch(n->n.operator().operator().startsWith("ALLOY/LOCAL-BIND-DOMAINS/")),
                "same-block dependency is not moved to an ambient domain port");
        System.out.println("LocalDomainSingletonTest: "+checks+" checks passed");
    }
}

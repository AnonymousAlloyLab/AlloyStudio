package is.fivefivefive.CanDis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import is.fivefivefive.CanDis.core.JoinSegmentation;
import is.fivefivefive.CanDis.theory.CertificateVerifier;
import is.fivefivefive.CanDis.theory.GuardedJoinNodeCertificate;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;

/** Structural/relational tests, including the JS1 full-realization counterexample. */
public final class JoinSegmentationTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }

    private record Tree(int arity, Tree left, Tree right, Set<List<Integer>> rows) {
        List<Tree> children() { return left == null ? List.of() : List.of(left, right); }
    }

    private static Set<List<Integer>> join(Set<List<Integer>> left, Set<List<Integer>> right) {
        var result = new LinkedHashSet<List<Integer>>();
        for (var a : left) for (var b : right) {
            if (a.isEmpty() || b.isEmpty() || !a.get(a.size() - 1).equals(b.get(0))) continue;
            var row = new ArrayList<>(a.subList(0, a.size() - 1));
            row.addAll(b.subList(1, b.size()));
            result.add(List.copyOf(row));
        }
        return result;
    }

    private static Tree app(Tree l, Tree r) {
        return new Tree(l.arity() + r.arity() - 2, l, r, join(l.rows(), r.rows()));
    }

    private static List<Tree> shapes(List<Tree> leaves, int from, int to) {
        if (to - from == 1) return List.of(leaves.get(from));
        var result = new ArrayList<Tree>();
        for (int split = from + 1; split < to; split++) {
            for (var l : shapes(leaves, from, split)) for (var r : shapes(leaves, split, to)) {
                if (l.arity() + r.arity() > 2) result.add(app(l, r));
            }
        }
        return result;
    }

    private static void leaves(Tree tree, List<Tree> out) {
        if (tree.left() == null) out.add(tree);
        else { leaves(tree.left(), out); leaves(tree.right(), out); }
    }

    private static void semanticFamilies() {
        int cases = 0;
        for (int count = 2; count <= 5; count++) {
            int patterns = (int) Math.pow(3, count);
            for (int pattern = 0; pattern < patterns; pattern++) {
                int code = pattern;
                var original = new ArrayList<Tree>();
                for (int i = 0; i < count; i++) {
                    int arity = 1 + code % 3; code /= 3;
                    var row = new ArrayList<Integer>();
                    var other = new ArrayList<Integer>();
                    for (int j = 0; j < arity; j++) {
                        row.add((i + j) % 2); other.add(1 - (i + j) % 2);
                    }
                    original.add(new Tree(arity, null, null,
                            (pattern + i) % 7 == 0 ? Set.of() : Set.of(List.copyOf(row), List.copyOf(other))));
                }
                for (var source : shapes(original, 0, count)) {
                    var cut = JoinSegmentation.of(source, Tree::children, Tree::arity, 4096);
                    var ports = cut.operands();
                    var occurrences = new ArrayList<Tree>();
                    var value = ports.get(0).rows();
                    leaves(ports.get(0), occurrences);
                    for (int i = 1; i < ports.size(); i++) {
                        value = join(value, ports.get(i).rows());
                        leaves(ports.get(i), occurrences);
                    }
                    check(value.equals(source.rows()), "actual relational semantics retained");
                    check(occurrences.size() == original.size(), "no orphan or duplicate occurrence");
                    for (int i = 0; i < original.size(); i++)
                        check(occurrences.get(i) == original.get(i), "complete ordered occurrence identity");
                    for (int i = 1; i + 1 < ports.size(); i++)
                        check(ports.get(i).arity() >= 2, "every exposed interior is wide");
                    check(cut.inputVisits() == 2 * count - 1, "one input visit per occurrence");
                    check(cut.outputVisits() <= cut.inputVisits(), "bounded output traversal");
                    cases++;
                }
            }
        }
        check(cases > 2000, "nontrivial arity/association/typed-empty family");
        System.out.println("JOIN semantic family: " + cases + " typed trees");
    }

    private static void linearTraversal() {
        for (int count : new int[]{128, 256, 512}) {
            var a = new Tree(2, null, null, Set.of());
            var b = new Tree(1, null, null, Set.of());
            var prefix = app(a, b);
            Tree root = prefix;
            for (int i = 2; i < count; i++) root = app(root, a);
            int[] reads = new int[2];
            var result = JoinSegmentation.of(root, t -> { reads[0]++; return t.children(); },
                    t -> { reads[1]++; return t.arity(); }, 4096);
            int nodes = 2 * count - 1;
            check(reads[0] == nodes && result.inputVisits() == nodes, "each occurrence expanded once");
            check(reads[1] == nodes + 2 * (count - 1), "no hidden endpoint rescanning");
            check(result.outputVisits() <= nodes, "linear output visit bound");
            check(result.operands().size() == count - 1 && result.operands().get(0) == prefix,
                    "long spine keeps the actual unary prefix");
            System.out.println("JOIN traversal leaves=" + count + " childReads=" + reads[0]
                    + " arityReads=" + reads[1] + " outputVisits=" + result.outputVisits());
        }
    }

    private static void fullRealizationCounterexample() throws Exception {
        var pair = NativeOperationComparisonTest.pair(
                "sig A { r:set A, t:set A } sig B in A {} "
                + "pred inv { some r.(B.t) and some (r.B).t } pred invc { some A }", "inv", "invc");
        var adapted = TheoryAlloyAdapter.adapt(pair.left().normalizedForms());
        adapted.requireRepairProjectionSources(pair.left().normalizedForms());
        var proofs = adapted.semanticArtifact().guardedJoinConstructions();
        var right = proofs.stream().filter(c -> !c.source().right().isLeaf()).findFirst().orElseThrow();
        var left = proofs.stream().filter(c -> !c.source().left().isLeaf()).findFirst().orElseThrow();
        CertificateVerifier.verify(right);
        CertificateVerifier.verify(left);
        check(right.steps().isEmpty(), "unary right barrier cannot be rotated");
        var ports = right.target().presentationOperands();
        check(ports.size() == 2 && ports.get(0) == right.target().left()
                && ports.get(1) == right.target().right(), "right barrier retains the actual subtree");
        check(!right.realization().node().structuralKey().equals(left.realization().node().structuralKey()),
                "full certified construction distinguishes the two meanings");
        var field = GuardedJoinNodeCertificate.class.getDeclaredField("node");
        field.setAccessible(true);
        var original = right.realization().node();
        field.set(right.realization(), left.realization().node());
        boolean rejected = false;
        try { CertificateVerifier.verify(right); }
        catch (IllegalArgumentException expected) { rejected = true; }
        finally { field.set(right.realization(), original); }
        check(rejected, "full source-linked certificate rejects a wrong-association realization");
        CertificateVerifier.verify(right);
    }

    public static void main(String[] args) throws Exception {
        semanticFamilies();
        linearTraversal();
        fullRealizationCounterexample();
        System.out.println("JoinSegmentationTest: " + checks + " checks passed");
    }
}

package is.fivefivefive.CanDis.theory;

import java.util.List;

/** Kernel-level negative controls for local rotations and finite tree budgets. */
public final class GuardedJoinRotationTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void reject(Runnable action, String label) {
        checks++; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(label);
    }
    public static void main(String[] args) {
        GraphType a = GraphType.constructor("AlloySig:A");
        TypedSlot r = TypedSlot.source(GraphType.relation(a,a), 1);
        TypedSlot s = TypedSlot.source(GraphType.relation(a,a,a), 2);
        TypedSlot c = TypedSlot.source(GraphType.relation(a), 3);
        TypedSlot d = TypedSlot.source(GraphType.relation(a), 4);
        var context = TypedSlotContext.of(r,s,c,d);
        GuardedJoinTree tr = leaf(context,r), ts = leaf(context,s), tc = leaf(context,c), td = leaf(context,d);
        var source = GuardedJoinTree.join(tr, GuardedJoinTree.join(GuardedJoinTree.join(ts,td),tc));
        var once = source.rotate();
        var target = once.rotateAt(List.of(0));
        check(target.structuralKey().equals(GuardedJoinCertificate.normalized(source).structuralKey()),
                "two locally admitted rotations");
        check(source.type().equals(target.type()), "exact intermediate result preserved");
        check(source.leaves().equals(target.leaves()), "ordered occurrence identity retained");
        reject(() -> DependentChainTheory.requireSoundFlattening(DependentChainKind.JOIN,
                source.leaves().stream().map(t -> t.type().relationType()).toList()), "whole-list unary guard intact");
        reject(() -> GuardedJoinTree.join(tr, GuardedJoinTree.join(tc,tr)).rotate(), "unary middle not rotatable");
        reject(() -> GuardedJoinTree.join(tc,td), "ill-typed zero-arity JOIN rejected");
        reject(() -> source.rotateAt(List.of(2)), "invalid path rejected");
        reject(() -> source.rotateAt(List.of(0)), "leaf path rejected");
        reject(() -> GuardedJoinTree.join(tr, leaf(TypedSlotContext.of(r),r)), "caller context substitution rejected");
        var repeated = GuardedJoinTree.join(tr, GuardedJoinTree.join(tr,tr));
        check(repeated.rotate().leaves().size() == 3, "duplicate multiplicity retained");
        GuardedJoinTree dag = tr;
        for (int i=0; i<6; i++) dag = GuardedJoinTree.join(dag,dag);
        check(dag.leaves().size() == 64, "expanded DAG budget counts occurrences");
        GuardedJoinTree maximal = dag;
        reject(() -> GuardedJoinTree.join(maximal,maximal), "repeated DAG expansion fails before construction");
        GuardedJoinNodeCertificate realization = new GuardedJoinNodeCertificate(target.left(), List.of(),
                SemanticProfile.alloyOverflowForbidding());
        check(((SeqPort)realization.node().ports().get(0)).elements().size() == 3, "only admitted prefix realized flat");
        reject(() -> new GuardedJoinNodeCertificate(target, List.of(),
                SemanticProfile.alloyOverflowForbidding()), "missing child insertion premise rejected");
        System.out.println("GuardedJoinRotationTest: "+checks+" checks passed");
    }
    private static GuardedJoinTree leaf(TypedSlotContext context, TypedSlot slot) {
        return GuardedJoinTree.leaf(new DependentChainLeaf(OnePort.slot(context,slot)));
    }
}

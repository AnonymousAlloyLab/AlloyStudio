package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Realizes a derived JOIN term using exact child-node insertion equalities.
 * Its left endpoint is not an additional trusted source occurrence. */
public final class GuardedJoinNodeCertificate extends TypedEqualityCertificate {
    public record Child(GuardedJoinNodeCertificate construction, OnePort port,
            TypedEqualityCertificate insertion) {
        public Child {
            CertificateVerifier.verify(construction);
            CertificateVerifier.verify(insertion);
            if (!(port.leaf() instanceof InvocationPortLeaf invocation)
                    || !insertion.leftEndpoint().equals(TypedCertificateEndpoint.node(construction.node()))
                    || !insertion.rightEndpoint().equals(TypedCertificateEndpoint.invocation(invocation.invocation()))
                    || !port.context().equals(construction.node().context()))
                throw new IllegalArgumentException("Derived JOIN child lacks exact insertion linkage");
        }
    }
    private static final StructuralKey DERIVED = StructuralKey.leaf("derived-join", "not-a-source-root");
    private final GuardedJoinTree tree;
    private final TypedENode node;
    private final List<Child> children;
    private final SemanticProfile profile;

    GuardedJoinNodeCertificate(GuardedJoinTree tree, List<Child> children, SemanticProfile profile) {
        this(tree, List.copyOf(children), profile, node(tree, children));
    }
    private GuardedJoinNodeCertificate(GuardedJoinTree tree, List<Child> children,
            SemanticProfile profile, TypedENode node) {
        super(CertificateCategory.GUARDED_JOIN_ROTATION,
                TypedCertificateEndpoint.guardedJoin(tree, profile, DERIVED),
                TypedCertificateEndpoint.node(node), premises(children), List.of());
        this.tree = tree; this.children = children; this.node = node; this.profile = profile;
        CertificateVerifier.verify(this);
    }
    private static List<TypedEqualityCertificate> premises(List<Child> children) {
        var result = new ArrayList<TypedEqualityCertificate>();
        for (Child child : children) { result.add(child.construction()); result.add(child.insertion()); }
        return result;
    }
    private static TypedENode node(GuardedJoinTree tree, List<Child> children) {
        List<OnePort> ports = new ArrayList<>(); int index = 0;
        for (var operand : tree.presentationOperands()) {
            if (operand.isLeaf()) ports.add(operand.leaf().port());
            else {
                if (index >= children.size()) throw new IllegalArgumentException("Missing derived JOIN child");
                Child child = children.get(index++);
                if (!operand.structuralKey().equals(child.construction().tree.structuralKey()))
                    throw new IllegalArgumentException("Derived JOIN child belongs to another term");
                ports.add(child.port());
            }
        }
        if (index != children.size()) throw new IllegalArgumentException("Extra derived JOIN child");
        SeqPortSchema schema = SeqPortSchema.dependent(ports.stream().map(OnePort::schema).toList());
        var operator = OperatorDeclaration.monomorphic(DependentChainKind.JOIN.operatorIdentity(),
                List.of(schema), tree.type().relationType(), Collections.singletonMap(PortPath.at(0),
                        ContainerLawDeclaration.of(ContainerLawDeclaration.Kind.SEQ, false, false, false, false)),
                null).instantiateMonomorphic();
        return TypedENode.construct(operator, tree.context(), List.of(new SeqPort(schema, tree.context(), ports)));
    }
    @Override void verifyLocal() {
        if (!profile.isAdmissibleAlloyProfile()) throw new IllegalArgumentException("Unadmitted JOIN profile");
        for (Child child : children) {
            new Child(child.construction(), child.port(), child.insertion());
            if (!profile.equals(child.construction().profile)) throw new IllegalArgumentException("JOIN child profile differs");
        }
        if (!node.structuralKey().equals(node(tree, children).structuralKey()))
            throw new IllegalArgumentException("Derived JOIN node differs from ordered term");
    }
    public GuardedJoinTree tree() { return tree; }
    public TypedENode node() { return node; }
    public SemanticProfile semanticProfile() { return profile; }
    public List<Child> children() { return children; }
    public List<TypedENode> nodes() {
        var result = new ArrayList<TypedENode>(); result.add(node);
        for (Child child : children) result.addAll(child.construction().nodes());
        return List.copyOf(result);
    }
}

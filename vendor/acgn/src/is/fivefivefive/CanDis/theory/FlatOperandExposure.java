package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source-linked opening of one sealed, same-operator Boolean operand. */
final class FlatOperandExposure {
    private final OnePort leaf;
    private final TypedENode node;
    private final TypedEqualityCertificate insertion;
    private final SemanticProfile profile;

    FlatOperandExposure(OnePort leaf, TypedENode node,
            TypedEqualityCertificate insertion, SemanticProfile profile) {
        this.leaf = Objects.requireNonNull(leaf, "leaf");
        this.node = Objects.requireNonNull(node, "node");
        this.insertion = Objects.requireNonNull(insertion, "insertion");
        this.profile = Objects.requireNonNull(profile, "profile");
        verify();
    }

    void verify() {
        CertificateVerifier.verify(insertion);
        if (!(leaf.leaf() instanceof InvocationPortLeaf invocation)
                || !node.context().equals(leaf.context())
                || !node.outputType().equals(leaf.schema().type())
                || !insertion.leftEndpoint().equals(TypedCertificateEndpoint.node(node))
                || !insertion.rightEndpoint().equals(
                        TypedCertificateEndpoint.invocation(invocation.invocation()))
                || !node.operator().usesFlatConstruction()
                || node.ports().size() != 1 || !(node.ports().get(0) instanceof SetPort)
                || !profile.isAdmissibleAlloyProfile()) {
            throw new IllegalArgumentException("Flat exposure lacks its exact node-to-leaf proof");
        }
        ContainerLawDeclaration laws = node.operator().lawForPath(PortPath.at(0));
        laws.requireCertified();
        laws.validateEvidenceFor(node.operator().operator(), node.outputType(),
                PortPath.at(0), node.operator().schemaAt(PortPath.at(0)), true);
        for (ContainerLawCertificate law : laws.certificates().values()) {
            if (!profile.equals(law.semanticProfile())) {
                throw new IllegalArgumentException("Flat exposure changes semantic profile");
            }
        }
    }

    OnePort leaf() { return leaf; }
    TypedEqualityCertificate insertion() { return insertion; }

    private FlatApplication application() {
        List<FlatInput> inputs = new ArrayList<>();
        for (PortValue port : ((SetPort) node.ports().get(0)).elements()) {
            if (!(port instanceof OnePort one)) {
                throw new IllegalArgumentException("Flat exposure requires homogeneous One operands");
            }
            inputs.add(new FlatLeaf(one));
        }
        return new FlatApplication(node.operator(), node.context(), inputs);
    }

    static FlatApplication expand(FlatApplication source,
            Map<OnePort, FlatOperandExposure> candidates, SemanticProfile profile,
            List<FlatOperandExposure> used) {
        List<FlatInput> inputs = new ArrayList<>();
        boolean changed = false;
        for (FlatInput input : source.operands()) {
            FlatInput replacement = input;
            if (input instanceof FlatApplication nested) {
                replacement = expand(nested, candidates, profile, used);
            } else if (input instanceof FlatLeaf flat) {
                FlatOperandExposure exposure = candidates.get(flat.port());
                if (exposure != null && source.operator().equals(exposure.node.operator())) {
                    exposure.verify();
                    if (!exposure.leaf.equals(flat.port())
                            || !source.context().equals(exposure.node.context())
                            || !profile.equals(exposure.profile)) {
                        throw new IllegalArgumentException("Flat exposure changes its occurrence endpoint");
                    }
                    replacement = exposure.application();
                    used.add(exposure);
                }
            }
            inputs.add(replacement);
            changed |= replacement != input;
        }
        return changed ? new FlatApplication(source.operator(), source.context(), inputs) : source;
    }
}

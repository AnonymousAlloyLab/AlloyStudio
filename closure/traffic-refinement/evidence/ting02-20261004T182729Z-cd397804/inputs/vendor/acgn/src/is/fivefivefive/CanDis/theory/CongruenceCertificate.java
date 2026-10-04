package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One forward congruence step over a node or one explicit port constructor. */
public final class CongruenceCertificate extends TypedEqualityCertificate {
    private final List<TypedEqualityCertificate> childCertificates;

    private CongruenceCertificate(Build build) {
        super(
                CertificateCategory.FORWARD_CONGRUENCE,
                build.left,
                build.right,
                build.children,
                Collections.singletonList(build.constructorKey));
        this.childCertificates = build.children;
        verifyLocal();
    }

    public static CongruenceCertificate nodes(
            TypedENode left,
            TypedENode right,
            List<? extends TypedEqualityCertificate> childCertificates) {
        return new CongruenceCertificate(buildNodes(left, right, childCertificates));
    }

    public static CongruenceCertificate ports(
            PortValue left,
            PortValue right,
            List<? extends TypedEqualityCertificate> childCertificates) {
        return new CongruenceCertificate(buildPorts(left, right, childCertificates));
    }

    public List<TypedEqualityCertificate> childCertificates() {
        return childCertificates;
    }

    private static Build buildNodes(
            TypedENode left,
            TypedENode right,
            List<? extends TypedEqualityCertificate> supplied) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.operator().equals(right.operator())
                || !left.context().equals(right.context())) {
            throw new IllegalArgumentException(
                    "Node congruence requires one instantiated operator and context");
        }
        List<EndpointPair> expected = new ArrayList<>();
        for (int index = 0; index < left.ports().size(); index++) {
            addIfDifferent(
                    expected,
                    TypedCertificateEndpoint.port(left.ports().get(index)),
                    TypedCertificateEndpoint.port(right.ports().get(index)));
        }
        return build(
                TypedCertificateEndpoint.node(left),
                TypedCertificateEndpoint.node(right),
                expected,
                supplied,
                left.operator().structuralKey());
    }

    private static Build buildPorts(
            PortValue left,
            PortValue right,
            List<? extends TypedEqualityCertificate> supplied) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.getClass().equals(right.getClass())
                || !left.schema().equals(right.schema())
                || !left.context().equals(right.context())) {
            throw new IllegalArgumentException(
                    "Port congruence requires one constructor, schema, and context");
        }
        List<EndpointPair> expected = directPortChildren(left, right);
        return build(
                TypedCertificateEndpoint.port(left),
                TypedCertificateEndpoint.port(right),
                expected,
                supplied,
                left.schema().structuralKey());
    }

    private static List<EndpointPair> directPortChildren(
            PortValue left,
            PortValue right) {
        List<EndpointPair> expected = new ArrayList<>();
        if (left instanceof OnePort) {
            PortLeaf leftLeaf = ((OnePort) left).leaf();
            PortLeaf rightLeaf = ((OnePort) right).leaf();
            if (leftLeaf.equals(rightLeaf)) {
                return expected;
            }
            if (!(leftLeaf instanceof InvocationPortLeaf)
                    || !(rightLeaf instanceof InvocationPortLeaf)) {
                throw new IllegalArgumentException(
                        "Forward congruence cannot derive equality of distinct slot leaves");
            }
            addIfDifferent(
                    expected,
                    TypedCertificateEndpoint.invocation(
                            ((InvocationPortLeaf) leftLeaf).invocation()),
                    TypedCertificateEndpoint.invocation(
                            ((InvocationPortLeaf) rightLeaf).invocation()));
            return expected;
        }
        if (left instanceof SeqPort) {
            addPortPairs(expected,
                    ((SeqPort) left).elements(), ((SeqPort) right).elements());
            return expected;
        }
        if (left instanceof BagPort) {
            addPortPairs(expected,
                    ((BagPort) left).occurrences(), ((BagPort) right).occurrences());
            return expected;
        }
        if (left instanceof SetPort) {
            addPortPairs(expected,
                    ((SetPort) left).elements(), ((SetPort) right).elements());
            return expected;
        }
        if (left instanceof BindPort) {
            BindPort leftBind = (BindPort) left;
            BindPort rightBind = (BindPort) right;
            if (!leftBind.boundSlot().equals(rightBind.boundSlot())) {
                throw new IllegalArgumentException(
                        "Forward congruence does not invent binder alpha-renamings");
            }
            addIfDifferent(
                    expected,
                    TypedCertificateEndpoint.port(leftBind.body()),
                    TypedCertificateEndpoint.port(rightBind.body()));
            return expected;
        }
        if (left instanceof BindBlockPort) {
            BindBlockPort leftBlock = (BindBlockPort) left;
            BindBlockPort rightBlock = (BindBlockPort) right;
            if (!leftBlock.descriptorToOccurrence().equals(
                    rightBlock.descriptorToOccurrence())) {
                throw new IllegalArgumentException(
                        "Forward congruence does not invent block automorphisms");
            }
            addIfDifferent(
                    expected,
                    TypedCertificateEndpoint.port(leftBlock.body()),
                    TypedCertificateEndpoint.port(rightBlock.body()));
            return expected;
        }
        throw new IllegalStateException("Unhandled port value " + left.getClass().getName());
    }

    private static void addPortPairs(
            List<EndpointPair> output,
            List<PortValue> left,
            List<PortValue> right) {
        if (left.size() != right.size()) {
            throw new IllegalArgumentException(
                    "Forward congruence preserves container arity");
        }
        for (int index = 0; index < left.size(); index++) {
            addIfDifferent(
                    output,
                    TypedCertificateEndpoint.port(left.get(index)),
                    TypedCertificateEndpoint.port(right.get(index)));
        }
    }

    private static void addIfDifferent(
            List<EndpointPair> output,
            TypedCertificateEndpoint left,
            TypedCertificateEndpoint right) {
        if (!left.equals(right)) {
            output.add(new EndpointPair(left, right));
        }
    }

    private static Build build(
            TypedCertificateEndpoint left,
            TypedCertificateEndpoint right,
            List<EndpointPair> expected,
            List<? extends TypedEqualityCertificate> supplied,
            StructuralKey constructorKey) {
        if (left.equals(right)) {
            throw new IllegalArgumentException(
                    "Use reflexivity for identical congruence endpoints");
        }
        Objects.requireNonNull(supplied, "childCertificates");
        if (expected.size() != supplied.size()) {
            throw new IllegalArgumentException(
                    "Congruence requires exactly one certificate per changed direct child");
        }
        List<TypedEqualityCertificate> oriented = new ArrayList<>(expected.size());
        for (int index = 0; index < expected.size(); index++) {
            EndpointPair pair = expected.get(index);
            TypedEqualityCertificate certificate = Objects.requireNonNull(
                    supplied.get(index), "child certificate");
            CertificateVerifier.verify(certificate);
            oriented.add(EqualityCertificates.orient(
                    certificate, pair.left, pair.right));
        }
        return new Build(left, right, oriented, constructorKey);
    }

    @Override
    void verifyLocal() {
        if (leftEndpoint().equals(rightEndpoint()) || childCertificates.isEmpty()) {
            throw new IllegalStateException("Malformed forward-congruence certificate");
        }
    }

    private static final class EndpointPair {
        private final TypedCertificateEndpoint left;
        private final TypedCertificateEndpoint right;

        private EndpointPair(
                TypedCertificateEndpoint left,
                TypedCertificateEndpoint right) {
            this.left = left;
            this.right = right;
        }
    }

    private static final class Build {
        private final TypedCertificateEndpoint left;
        private final TypedCertificateEndpoint right;
        private final List<TypedEqualityCertificate> children;
        private final StructuralKey constructorKey;

        private Build(
                TypedCertificateEndpoint left,
                TypedCertificateEndpoint right,
                List<TypedEqualityCertificate> children,
                StructuralKey constructorKey) {
            this.left = left;
            this.right = right;
            this.children = Collections.unmodifiableList(new ArrayList<>(children));
            this.constructorKey = constructorKey;
        }
    }
}

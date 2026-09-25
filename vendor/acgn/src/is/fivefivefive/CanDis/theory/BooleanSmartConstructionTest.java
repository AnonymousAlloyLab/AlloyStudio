package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import is.fivefivefive.CanDis.core.AlloyOperatorPolicy;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** P2-16: the certified Boolean K+ boundary after source smart normalization. */
public final class BooleanSmartConstructionTest {
    private static int checks;

    private BooleanSmartConstructionTest() {
    }

    public static void main(String[] args) {
        for (SemanticProfile profile : Arrays.asList(
                SemanticProfile.alloyOverflowForbidding(),
                SemanticProfile.alloyModular())) {
            for (Opcode opcode : Arrays.asList(Opcode.AND, Opcode.OR)) {
                testBooleanConstruction(profile, opcode);
            }
        }
        System.out.println("BooleanSmartConstructionTest passed: " + checks + " checks");
    }

    private static void testBooleanConstruction(SemanticProfile profile, Opcode opcode) {
        String head = "ALLOY/" + opcode.name();
        PortPath path = PortPath.at(0);
        SetPortSchema schema = new SetPortSchema(
                ArityPolicy.nonemptyVariadic(), new OnePortSchema(GraphType.BOOL));
        List<ContainerLawCertificate> certificates = new ArrayList<>();
        for (ContainerLawCertificate.Law law : Arrays.asList(
                ContainerLawCertificate.Law.ASSOCIATIVITY,
                ContainerLawCertificate.Law.COMMUTATIVITY,
                ContainerLawCertificate.Law.IDEMPOTENCY)) {
            certificates.add(AlloyLawRegistry.issue(
                    profile, opcode, head, GraphType.BOOL, path, schema, law));
        }
        ContainerLawDeclaration laws = ContainerLawDeclaration.certified(schema, certificates);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                head, Collections.singletonList(schema), GraphType.BOOL,
                Collections.singletonMap(path, laws), 0).instantiateMonomorphic();
        AlloyOperatorPolicy policy = AlloyOperatorPolicy.forShape(opcode, -1, true, profile);
        check(policy.unitLicense() == UnitLicense.ABSENT && !laws.hasUnit(),
                head + " has no unit license or certified U law");
        check(!policy.arityPolicy().admits(0) && policy.arityPolicy().admits(1),
                head + " admits singleton input but not empty storage");
        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                profile, opcode, head, GraphType.BOOL, path, schema,
                ContainerLawCertificate.Law.UNIT));

        TypedSlot first = TypedSlot.source(GraphType.BOOL, 1600);
        TypedSlot second = TypedSlot.source(GraphType.BOOL, 1601);
        TypedSlotContext context = TypedSlotContext.of(first, second);
        OnePort firstPort = OnePort.slot(context, first);
        OnePort secondPort = OnePort.slot(context, second);
        FlatLeaf firstLeaf = new FlatLeaf(firstPort);
        FlatLeaf secondLeaf = new FlatLeaf(secondPort);
        expectThrows(IllegalArgumentException.class, () -> construct(
                operator, context, Collections.emptyList(), profile));
        expectThrows(IllegalArgumentException.class, () -> new SetPort(
                schema, context, Collections.emptyList()));

        CertifiedFlatConstruction singleton = construct(
                operator, context, Collections.singletonList(firstLeaf), profile);
        verifyNoUnit(singleton);
        check(singleton.collapsedToSingleton() && singleton.singleton().equals(firstPort),
                head + " returns the exact singleton operand including its context");
        check(singleton.certificate().premises().stream().noneMatch(
                premise -> premise instanceof ContainerLawCertificate
                        && ((ContainerLawCertificate) premise).law()
                                == ContainerLawCertificate.Law.IDEMPOTENCY),
                head + " unary collapse needs no idempotency premise");
        expectThrows(IllegalStateException.class, singleton::node);

        CertifiedFlatConstruction duplicate = construct(
                operator, context, Arrays.asList(firstLeaf, firstLeaf), profile);
        verifyNoUnit(duplicate);
        check(duplicate.collapsedToSingleton() && duplicate.singleton().equals(firstPort),
                head + " returns the singleton after certified Set deduplication");
        expectThrows(IllegalStateException.class, duplicate::node);

        CertifiedFlatConstruction stored = construct(
                operator, context, Arrays.asList(secondLeaf, firstLeaf, secondLeaf), profile);
        verifyNoUnit(stored);
        check(!stored.collapsedToSingleton(), head + " retains two distinct operands");
        SetPort carrier = (SetPort) stored.node().ports().get(0);
        check(carrier.elements().size() == 2
                        && carrier.elements().contains(firstPort)
                        && carrier.elements().contains(secondPort),
                head + " stores exactly the nonempty deduplicated carrier");
        check(stored.node().operator().equals(operator)
                        && !stored.node().operator().containerLaws().get(path).hasUnit(),
                head + " storage preserves the exact head and absence of U");
        expectThrows(IllegalStateException.class, stored::singleton);
    }

    private static CertifiedFlatConstruction construct(
            InstantiatedOperator operator,
            TypedSlotContext context,
            List<FlatLeaf> leaves,
            SemanticProfile profile) {
        return TypedENode.flatConstructCertified(
                new FlatApplication(operator, context, leaves),
                ignored -> {
                    throw new AssertionError("A leaf-only construction must not invoke the sealer");
                },
                profile);
    }

    private static void verifyNoUnit(CertifiedFlatConstruction construction) {
        CertificateVerifier.verify(construction.certificate());
        check(construction.certificate().premises().stream().noneMatch(
                premise -> premise instanceof ContainerLawCertificate
                        && ((ContainerLawCertificate) premise).law()
                                == ContainerLawCertificate.Law.UNIT),
                "Replayed construction certificate contains no unit premise");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError("Expected " + type.getSimpleName(), failure);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}

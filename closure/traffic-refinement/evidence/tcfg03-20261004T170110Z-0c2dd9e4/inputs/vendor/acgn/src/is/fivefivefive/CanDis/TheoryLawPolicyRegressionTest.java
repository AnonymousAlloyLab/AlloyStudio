package is.fivefivefive.CanDis;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import is.fivefivefive.CanDis.core.AlloyOperatorPolicy;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.theory.ArityPolicy;
import is.fivefivefive.CanDis.theory.AlloyLawRegistry;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.BagPortSchema;
import is.fivefivefive.CanDis.theory.ContainerLawDeclaration;
import is.fivefivefive.CanDis.theory.ContainerLawCertificate;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.InstantiatedOperator;
import is.fivefivefive.CanDis.theory.OnePortSchema;
import is.fivefivefive.CanDis.theory.OperatorDeclaration;
import is.fivefivefive.CanDis.theory.PortPath;
import is.fivefivefive.CanDis.theory.SeqPort;
import is.fivefivefive.CanDis.theory.SeqPortSchema;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.SetPortSchema;
import is.fivefivefive.CanDis.theory.SiblingQuotient;
import is.fivefivefive.CanDis.theory.TypedSlotContext;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;

/** Adversarial regression matrix for the repaired arity/law separation. */
public final class TheoryLawPolicyRegressionTest {
    private static int checks;

    private TheoryLawPolicyRegressionTest() {
    }

    public static void main(String[] args) {
        testContainerLawSeparation();
        testArityClosureAndEmptyStorage();
        testExactAlloyPolicy();
        testFixedCommutativeAndRoleSensitiveForms();
        testCompletedArityAndTypedSplicing();
        testSlotMapCompositionAndUnionArity();
        testOverflowProfile();
        testTrustedLawAuthority();
        testMissingLocalDomainFailsClosed();
        System.out.println("TheoryLawPolicyRegressionTest passed: " + checks + " checks");
    }

    private static void testContainerLawSeparation() {
        OnePortSchema integer = new OnePortSchema(GraphType.INT);

        SeqPortSchema ordinarySeq = new SeqPortSchema(ArityPolicy.nonemptyVariadic(), integer);
        ContainerLawDeclaration noSeqLaws = laws(
                ContainerLawDeclaration.Kind.SEQ, false, false, false, false);
        InstantiatedOperator ordinary = operator(
                "ordinary-seq", ordinarySeq, noSeqLaws, false);
        check(!ordinary.usesFlatConstruction(), "Ordinary Seq+ is not flat");
        check(!ordinary.containerLaws().get(PortPath.at(0)).associative(),
                "Ordinary Seq+ has no A law");

        expectThrows(IllegalArgumentException.class, () -> operator(
                "illegal-flat-seq", ordinarySeq, noSeqLaws, true));

        BagPortSchema exactBag = new BagPortSchema(ArityPolicy.exact(2), integer);
        ContainerLawDeclaration commutativeOnly = laws(
                ContainerLawDeclaration.Kind.BAG, false, true, false, false);
        InstantiatedOperator fixedBag = operator(
                "fixed-bag", exactBag, commutativeOnly, false);
        check(!fixedBag.usesFlatConstruction(), "Bag=2 C-only operator is nonflat");
        check(fixedBag.containerLaws().get(PortPath.at(0)).commutative(),
                "Bag=2 carries C");
        check(!fixedBag.containerLaws().get(PortPath.at(0)).associative(),
                "Bag=2 does not acquire A from C");

        BagPortSchema variadicBag = new BagPortSchema(
                ArityPolicy.nonemptyVariadic(), integer);
        ContainerLawDeclaration associativeCommutative = laws(
                ContainerLawDeclaration.Kind.BAG, true, true, false, false);
        InstantiatedOperator flatBag = operator(
                "flat-bag", variadicBag, associativeCommutative, true);
        check(flatBag.usesFlatConstruction(), "Flat Bag+ accepts A+C");
        check(!flatBag.containerLaws().get(PortPath.at(0)).idempotent(),
                "Flat Bag+ retains multiplicity");
        expectThrows(IllegalArgumentException.class, () -> operator(
                "flat-bag-without-a", variadicBag, commutativeOnly, true));
        expectThrows(IllegalArgumentException.class, () -> laws(
                ContainerLawDeclaration.Kind.BAG, true, true, true, false));

        SeqPortSchema zeroSeq = new SeqPortSchema(ArityPolicy.zeroOrMore(), integer);
        InstantiatedOperator ordinaryZero = operator(
                "ordinary-zero", zeroSeq, noSeqLaws, false);
        check(!ordinaryZero.containerLaws().get(PortPath.at(0)).hasUnit(),
                "Ordinary K0 is legal without U");
        expectThrows(IllegalArgumentException.class, () -> operator(
                "flat-zero-no-a", zeroSeq, noSeqLaws, true));
        expectThrows(IllegalArgumentException.class, () -> operator(
                "flat-zero-no-u",
                zeroSeq,
                laws(ContainerLawDeclaration.Kind.SEQ, true, false, false, false),
                true));
        InstantiatedOperator flatZero = operator(
                "flat-zero",
                zeroSeq,
                laws(ContainerLawDeclaration.Kind.SEQ, true, false, false, true),
                true);
        check(flatZero.usesFlatConstruction(), "Flat K0 accepts exact A+U declarations");
    }

    private static void testArityClosureAndEmptyStorage() {
        OnePortSchema integer = new OnePortSchema(GraphType.INT);
        expectThrows(IllegalArgumentException.class, () -> new SetPortSchema(
                ArityPolicy.finite(1, 3), integer));

        SeqPortSchema spliceGap = new SeqPortSchema(ArityPolicy.finite(1, 2), integer);
        expectThrows(IllegalArgumentException.class, () -> operator(
                "splice-gap",
                spliceGap,
                laws(ContainerLawDeclaration.Kind.SEQ, true, false, false, false),
                true));

        SeqPortSchema nonempty = new SeqPortSchema(
                ArityPolicy.nonemptyVariadic(), integer);
        expectThrows(IllegalArgumentException.class, () -> new SeqPort(
                nonempty, TypedSlotContext.empty(), Collections.emptyList()));
    }

    private static void testExactAlloyPolicy() {
        Set<Opcode> expectedFlat = EnumSet.of(
                Opcode.AND, Opcode.OR, Opcode.PLUS, Opcode.INTERSECT);
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        for (Opcode opcode : Opcode.values()) {
            AlloyOperatorPolicy policy = AlloyOperatorPolicy.forShape(
                    opcode,
                    expectedFlat.contains(opcode) ? -1 : defaultFixedArity(opcode),
                    expectedFlat.contains(opcode),
                    profile);
            check(policy.flatLicense().enabled() == expectedFlat.contains(opcode),
                    "Exact flat whitelist for " + opcode);
        }

        for (Opcode excluded : Arrays.asList(
                Opcode.CALL,
                Opcode.JOIN,
                Opcode.ARROW,
                Opcode.LIST,
                Opcode.DISJOINT,
                Opcode.DISJOINT_LIST,
                Opcode.TOTALORDER_LIST,
                Opcode.FORALL,
                Opcode.EXISTS,
                Opcode.GENERICRELDECL,
                Opcode.DISJ,
                Opcode.VAR,
                Opcode.DISJVAR)) {
            AlloyOperatorPolicy policy = AlloyOperatorPolicy.forShape(
                    excluded, 2, isStructurallyVariadic(excluded), profile);
            check(!policy.flatLicense().enabled(), excluded + " is excluded from flattening");
        }

        AlloyOperatorPolicy malformedJoinHint = AlloyOperatorPolicy.forShape(
                Opcode.JOIN, -1, true, profile);
        check(malformedJoinHint.arityPolicy().equals(ArityPolicy.exact(2)),
                "JOIN stays fixed binary even when a stale variadic hint is supplied");
        AlloyOperatorPolicy malformedArrowHint = AlloyOperatorPolicy.forShape(
                Opcode.ARROW, -1, true, profile);
        check(malformedArrowHint.arityPolicy().equals(ArityPolicy.exact(2)),
                "ARROW stays fixed binary even when a stale variadic hint is supplied");
    }

    private static void testFixedCommutativeAndRoleSensitiveForms() {
        EGraphNode nestedEquality = node(
                Opcode.EQUALS, false, variable("a"), variable("b"));
        EGraphNode equality = node(
                Opcode.EQUALS, false, variable("c"), nestedEquality);
        equality.saturate();
        check(equality.isOrderInsensitive(), "Equality has C");
        check(!equality.hasFlatLicense(), "Equality has no A/flat license");
        check(equality.getChildren().size() == 2, "Equality never flattens a nested equality");

        EGraphNode duplicate = variable("x");
        EGraphNode disjoint = node(
                Opcode.DISJOINT, true, variable("y"), duplicate, duplicate);
        disjoint.saturate();
        check(disjoint.isBagFlexibleArity(), "Disjoint arguments use a bag quotient");
        check(disjoint.getChildren().size() == 3,
                "Disjoint arguments retain duplicate occurrences");
        check(!disjoint.hasFlatLicense(), "Disjoint arguments do not flatten");

        EGraphNode disjointRoles = node(
                Opcode.DISJOINT_LIST,
                true,
                variable("declared"),
                variable("domain"));
        disjointRoles.saturate();
        check(!disjointRoles.isOrderInsensitive(),
                "DISJOINT_LIST structural roles remain ordered");
        check(names(disjointRoles).equals(Arrays.asList("declared", "domain")),
                "DISJOINT_LIST structural role order survives saturation");

        EGraphNode list = node(Opcode.LIST, true, variable("left"), variable("right"));
        list.saturate();
        check(!list.isOrderInsensitive(), "Argument lists retain role order");
        check(names(list).equals(Arrays.asList("left", "right")),
                "Argument list order survives saturation");

        EGraphNode totalOrder = node(
                Opcode.TOTALORDER_LIST,
                true,
                variable("ordered"), variable("first"), variable("next"));
        totalOrder.saturate();
        check(!totalOrder.isOrderInsensitive(), "totalOrder roles remain positional");
        check(names(totalOrder).equals(Arrays.asList("ordered", "first", "next")),
                "totalOrder role order survives saturation");
    }

    private static void testOverflowProfile() {
        SemanticProfile forbidding = new SemanticProfile(
                4,
                SemanticProfile.OverflowMode.FORBID,
                "alloy-temporal",
                "policy-regression",
                "alloy-signature-v2");
        SemanticProfile modular = SemanticProfile.alloyModular();
        AlloyOperatorPolicy forbidAdd = AlloyOperatorPolicy.forShape(
                Opcode.IPLUS, 2, false, forbidding);
        AlloyOperatorPolicy modularAdd = AlloyOperatorPolicy.forShape(
                Opcode.IPLUS, -1, true, modular);
        check(!forbidAdd.flatLicense().enabled(),
                "Overflow-forbidding integer addition has no A/flat license");
        check(forbidAdd.arityPolicy().equals(ArityPolicy.exact(2)),
                "Overflow-forbidding integer addition remains binary");
        check(modularAdd.flatLicense().enabled(), "Modular integer addition has A");
        check(modularAdd.siblingQuotient() == SiblingQuotient.COMMUTATIVE_BAG,
                "Modular integer addition has A+C while retaining multiplicity");

        EGraphNode modularNested = node(
                modular,
                Opcode.IPLUS,
                true,
                integerVariable(modular, "m1"), integerVariable(modular, "m2"));
        EGraphNode modularRoot = node(
                modular,
                Opcode.IPLUS,
                true,
                integerVariable(modular, "m0"), modularNested);
        modularNested.setSourceType("Int");
        modularRoot.setSourceType("Int");
        modularRoot.saturate();
        check(modularRoot.getChildren().size() == 3,
                "An explicitly modular production e-node receives the Bag+ flat license");

        EGraphNode left = node(
                Opcode.IPLUS,
                false,
                node(Opcode.IPLUS, false, constant("7"), constant("1")),
                constant("-1"));
        left.saturate();
        check(left.getChildren().size() == 2,
                "4-bit (7+1)+(-1) remains a binary source association");
        check(left.getChildren().stream().anyMatch(child -> child.getOpcode() == Opcode.IPLUS),
                "Overflow-forbidding saturation preserves the nested addition");
    }

    private static void testSlotMapCompositionAndUnionArity() {
        EGraphNode.beginGraph();
        try {
            EGraphNode inner = variable("inner");
            EGraphNode nested = node(Opcode.PLUS, true);
            nested.setSourceType("Rel(A)");
            nested.addChildInvocation(inner.getEClass().invoke(
                    Collections.singletonMap("inner", "middle")));
            nested.addChild(constant("k1"));

            EGraphNode root = node(Opcode.PLUS, true);
            root.setSourceType("Rel(A)");
            root.addChildInvocation(nested.getEClass().invoke(
                    Collections.singletonMap("middle", "outer")));
            root.addChild(constant("k2"));
            root.saturate();

            EGraphNode.EClassRef flattened = root.getChildClasses().stream()
                    .filter(ref -> ref.getEClass().getId() == inner.getEClass().getId())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "flat splice dropped the renamed leaf occurrence"));
            check(flattened.getSlotMap().equals(
                            Collections.singletonMap("inner", "outer")),
                    "flat splice composes inner->middle->outer slot maps");

            EGraphNode underfilled = new EGraphNode(
                    9001,
                    Opcode.JOIN,
                    Collections.singletonList(variable("only")),
                    false,
                    2,
                    false,
                    Metatype.SET,
                    SemanticProfile.alloyOverflowForbidding());
            EGraphNode complete = new EGraphNode(
                    9002,
                    Opcode.JOIN,
                    Arrays.asList(variable("left"), variable("right")),
                    false,
                    2,
                    false,
                    Metatype.SET,
                    SemanticProfile.alloyOverflowForbidding());
            expectThrows(IllegalStateException.class, () -> EGraphNode.union(
                    underfilled.getEClassRef(), complete.getEClassRef()));
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testCompletedArityAndTypedSplicing() {
        expectThrows(IllegalArgumentException.class, () -> node(
                Opcode.JOIN, true, variable("a"), variable("b"), variable("c")));
        expectThrows(IllegalArgumentException.class, () -> node(
                Opcode.ARROW, true, variable("a"), variable("b"), variable("c")));
        expectThrows(IllegalArgumentException.class, () -> node(
                Opcode.EQUALS, false, variable("a"), variable("b"), variable("c")));

        EGraphNode emptyAnd = node(Opcode.AND, true);
        expectThrows(IllegalStateException.class, emptyAnd::saturate);

        EGraphNode allNeutralAnd = node(
                Opcode.AND, true, booleanConstant(true), booleanConstant(true));
        allNeutralAnd.saturate();
        check(allNeutralAnd.getOpcode() == Opcode.CONSTANT
                        && "true".equalsIgnoreCase(allNeutralAnd.getSourceName()),
                "AND(true,true) smart-constructs true instead of a nullary K+ node");
        EGraphNode allNeutralOr = node(
                Opcode.OR, true, booleanConstant(false), booleanConstant(false));
        allNeutralOr.saturate();
        check(allNeutralOr.getOpcode() == Opcode.CONSTANT
                        && "false".equalsIgnoreCase(allNeutralOr.getSourceName()),
                "OR(false,false) smart-constructs false instead of a nullary K+ node");

        EGraphNode typedA = node(Opcode.PLUS, true, variable("a"), variable("b"));
        typedA.setSourceType("Rel(A)");
        EGraphNode typedB = node(Opcode.PLUS, true, variable("c"), typedA);
        typedB.setSourceType("Rel(B)");
        typedB.saturate();
        check(typedB.getChildren().size() == 2
                        && typedB.getChildren().stream()
                                .anyMatch(child -> child.getOpcode() == Opcode.PLUS),
                "Flat splicing rejects a same-opcode child with a different carrier type");

        EGraphNode typedAParent = node(Opcode.PLUS, true, variable("c"), typedA);
        typedAParent.setSourceType("Rel(A)");
        typedAParent.saturate();
        check(typedAParent.getChildren().size() == 3,
                "Flat splicing accepts the same typed operator instance");
    }

    private static void testTrustedLawAuthority() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        SetPortSchema booleanSet = new SetPortSchema(
                ArityPolicy.nonemptyVariadic(), new OnePortSchema(GraphType.BOOL));
        ContainerLawCertificate certificate = AlloyLawRegistry.issue(
                profile,
                Opcode.AND,
                "ALLOY/AND",
                GraphType.BOOL,
                PortPath.at(0),
                booleanSet,
                ContainerLawCertificate.Law.ASSOCIATIVITY);
        check(certificate.authority()
                        == ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY,
                "Production laws carry fixed source-theory authority");
        check(AlloyLawRegistry.SOURCE_THEORY_DIGEST.equals(
                        certificate.sourceTheoryDigest()),
                "Production laws retain the fixed source-theory digest");
        check(!certificate.leftSourceEndpoint().equals(
                        certificate.rightSourceEndpoint()),
                "Production law evidence retains both exact source endpoints");
        check(ContainerLawCertificate.class.getConstructors().length == 0,
                "A producer label cannot invoke a public law-certificate constructor");

        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                profile,
                Opcode.CALL,
                "ALLOY/CALL",
                GraphType.BOOL,
                PortPath.at(0),
                booleanSet,
                ContainerLawCertificate.Law.ASSOCIATIVITY));
        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                profile,
                Opcode.AND,
                "ALLOY/OR",
                GraphType.BOOL,
                PortPath.at(0),
                booleanSet,
                ContainerLawCertificate.Law.ASSOCIATIVITY));
        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                profile,
                Opcode.AND,
                "ALLOY/AND",
                GraphType.INT,
                PortPath.at(0),
                booleanSet,
                ContainerLawCertificate.Law.ASSOCIATIVITY));
        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                profile,
                Opcode.AND,
                "ALLOY/AND",
                GraphType.BOOL,
                PortPath.at(1),
                booleanSet,
                ContainerLawCertificate.Law.ASSOCIATIVITY));

        SetPortSchema integerSet = new SetPortSchema(
                ArityPolicy.nonemptyVariadic(), new OnePortSchema(GraphType.INT));
        for (Opcode booleanOpcode : Arrays.asList(Opcode.AND, Opcode.OR)) {
            expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                    profile,
                    booleanOpcode,
                    "ALLOY/" + booleanOpcode,
                    GraphType.INT,
                    PortPath.at(0),
                    integerSet,
                    ContainerLawCertificate.Law.ASSOCIATIVITY));
        }
        BagPortSchema integerPair = new BagPortSchema(
                ArityPolicy.exact(2), new OnePortSchema(GraphType.INT));
        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                profile,
                Opcode.IFF,
                "ALLOY/IFF",
                GraphType.BOOL,
                PortPath.at(0),
                integerPair,
                ContainerLawCertificate.Law.COMMUTATIVITY));
        for (Opcode relationalOpcode : Arrays.asList(Opcode.PLUS, Opcode.INTERSECT)) {
            expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                    profile,
                    relationalOpcode,
                    "ALLOY/" + relationalOpcode,
                    GraphType.BOOL,
                    PortPath.at(0),
                    booleanSet,
                    ContainerLawCertificate.Law.ASSOCIATIVITY));
        }
        BagPortSchema integerBagForDisjoint = new BagPortSchema(
                ArityPolicy.nonemptyVariadic(), new OnePortSchema(GraphType.INT));
        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                profile,
                Opcode.DISJOINT,
                "ALLOY/DISJOINT",
                GraphType.BOOL,
                PortPath.at(0),
                integerBagForDisjoint,
                ContainerLawCertificate.Law.COMMUTATIVITY));
        GraphType relationS = GraphType.relation(
                GraphType.constructor("AlloySig:S"));
        GraphType relationT = GraphType.relation(
                GraphType.constructor("AlloySig:T"));
        GraphType heterogeneousRelationCarrier = AlloyTypeBridge.commutativeCarrier(
                Arrays.asList(relationS, relationT));
        BagPortSchema heterogeneousDisjoint = new BagPortSchema(
                ArityPolicy.nonemptyVariadic(),
                new OnePortSchema(heterogeneousRelationCarrier));
        check(AlloyLawRegistry.issue(
                        profile,
                        Opcode.DISJOINT,
                        "ALLOY/DISJOINT",
                        GraphType.BOOL,
                        PortPath.at(0),
                        heterogeneousDisjoint,
                        ContainerLawCertificate.Law.COMMUTATIVITY)
                        .authority()
                        == ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY,
                "A same-arity heterogeneous relation family admits disjoint commutativity");
        GraphType malformedCarrier = GraphType.constructor(
                "AlloyComparableCarrier", Arrays.asList(relationS, GraphType.INT));
        BagPortSchema malformedDisjoint = new BagPortSchema(
                ArityPolicy.nonemptyVariadic(), new OnePortSchema(malformedCarrier));
        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                profile,
                Opcode.DISJOINT,
                "ALLOY/DISJOINT",
                GraphType.BOOL,
                PortPath.at(0),
                malformedDisjoint,
                ContainerLawCertificate.Law.COMMUTATIVITY));

        SemanticProfile modular = SemanticProfile.alloyModular();
        BagPortSchema integerBag = new BagPortSchema(
                ArityPolicy.nonemptyVariadic(), new OnePortSchema(GraphType.INT));
        ContainerLawCertificate modularA = AlloyLawRegistry.issue(
                modular,
                Opcode.IPLUS,
                "ALLOY/IPLUS",
                GraphType.INT,
                PortPath.at(0),
                integerBag,
                ContainerLawCertificate.Law.ASSOCIATIVITY);
        check(!modularA.appliesTo(
                        profile,
                        "ALLOY/IPLUS",
                        GraphType.INT,
                        PortPath.at(0),
                        integerBag),
                "A modular law certificate cannot be replayed in an overflow-forbidding profile");
        SemanticProfile inventedModular = new SemanticProfile(
                5,
                SemanticProfile.OverflowMode.MODULAR,
                "alloy-temporal",
                "repaired-normal-form-v2",
                "alloy-signature-v2");
        expectThrows(IllegalStateException.class, () -> AlloyLawRegistry.issue(
                inventedModular,
                Opcode.IPLUS,
                "ALLOY/IPLUS",
                GraphType.INT,
                PortPath.at(0),
                integerBag,
                ContainerLawCertificate.Law.ASSOCIATIVITY));

        EGraphNode.beginGraph();
        try {
            EGraphNode forbiddingNode = integerVariable(profile, "forbid");
            EGraphNode modularNode = integerVariable(modular, "modular");
            expectThrows(IllegalArgumentException.class, () -> EGraphNode.union(
                    forbiddingNode.getEClassRef(), modularNode.getEClassRef()));
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static InstantiatedOperator operator(
            String name,
            is.fivefivefive.CanDis.theory.PortSchema schema,
            ContainerLawDeclaration declaration,
            boolean flat) {
        Map<PortPath, ContainerLawDeclaration> laws = new LinkedHashMap<>();
        laws.put(PortPath.at(0), declaration);
        return OperatorDeclaration.monomorphic(
                name,
                Collections.singletonList(schema),
                GraphType.INT,
                laws,
                flat ? 0 : null).instantiateMonomorphic();
    }

    private static ContainerLawDeclaration laws(
            ContainerLawDeclaration.Kind kind,
            boolean associative,
            boolean commutative,
            boolean idempotent,
            boolean unit) {
        return ContainerLawDeclaration.of(
                kind, associative, commutative, idempotent, unit);
    }

    private static EGraphNode node(Opcode opcode, boolean variadic, EGraphNode... children) {
        return node(
                SemanticProfile.alloyOverflowForbidding(),
                opcode,
                variadic,
                children);
    }

    private static EGraphNode node(
            SemanticProfile profile,
            Opcode opcode,
            boolean variadic,
            EGraphNode... children) {
        EGraphNode node = new EGraphNode(
                opcode.ordinal(),
                opcode,
                new ArrayList<>(Arrays.asList(children)),
                false,
                variadic ? -1 : children.length,
                variadic,
                Metatype.BOOLEAN,
                profile);
        if (opcode == Opcode.NOT || opcode == Opcode.AND || opcode == Opcode.OR
                || opcode == Opcode.IMPLIES || opcode == Opcode.IFF) {
            String sourceName;
            switch (opcode) {
                case NOT:
                    sourceName = "UNOPF_NOT";
                    break;
                case AND:
                    sourceName = "BOP_AND";
                    break;
                case OR:
                    sourceName = "BOP_OR";
                    break;
                case IMPLIES:
                    sourceName = "BOP_IMPLIES";
                    break;
                case IFF:
                    sourceName = "BOP_IFF";
                    break;
                default:
                    throw new AssertionError(opcode);
            }
            node.setSourceName(sourceName);
            node.setSourceType("MIDDLENODE_" + sourceName);
            node.setExactAlloyType(ExactAlloyType.boolType());
        }
        return node;
    }

    private static EGraphNode variable(String name) {
        EGraphNode node = node(Opcode.VARIABLE, false);
        node.setAlphaName(name);
        node.setSourceName(name);
        return node;
    }

    private static EGraphNode constant(String name) {
        EGraphNode node = node(Opcode.CONSTANT, false);
        node.setSourceName(name);
        node.setSemanticIdentity("int/" + name);
        return node;
    }

    private static EGraphNode integerVariable(SemanticProfile profile, String name) {
        EGraphNode node = node(profile, Opcode.VARIABLE, false);
        node.setAlphaName(name);
        node.setSourceName(name);
        node.setSourceType("Int");
        return node;
    }

    private static EGraphNode booleanConstant(boolean value) {
        EGraphNode node = constant(Boolean.toString(value));
        node.setSourceType("Bool");
        node.setExactAlloyType(ExactAlloyType.boolType());
        return node;
    }

    private static List<String> names(EGraphNode node) {
        List<String> result = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            result.add(child.getAlphaName());
        }
        return result;
    }

    private static int defaultFixedArity(Opcode opcode) {
        switch (opcode) {
            case VARIABLE:
            case GLOBALBINDING:
            case CONSTANT:
            case END:
                return 0;
            case NOT:
            case SOME:
            case NO:
            case LONE:
            case ONE:
                return 1;
            case ITE:
                return 3;
            default:
                return 2;
        }
    }

    private static boolean isStructurallyVariadic(Opcode opcode) {
        return opcode == Opcode.LIST
                || opcode == Opcode.DISJOINT || opcode == Opcode.DISJOINT_LIST
                || opcode == Opcode.TOTALORDER_LIST
                || opcode == Opcode.FORALL
                || opcode == Opcode.EXISTS
                || opcode == Opcode.GENERICRELDECL
                || opcode == Opcode.DISJ
                || opcode == Opcode.VAR
                || opcode == Opcode.DISJVAR;
    }

    private static void testMissingLocalDomainFailsClosed() {
        checks++;
        try {
            Method method = TheoryAlloyAdapter.class.getDeclaredMethod(
                    "domainKey", EGraphNode.class, Map.class, List.class);
            method.setAccessible(true);
            method.invoke(null, null, Collections.emptyMap(), Collections.emptyList());
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof IllegalArgumentException) {
                return;
            }
            throw new AssertionError(
                    "Missing local domain produced an unexpected failure",
                    exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to exercise local-domain rejection", exception);
        }
        throw new AssertionError("A missing local domain was interpreted as source evidence");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> type,
            Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + type.getSimpleName() + " but got " + throwable,
                    throwable);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
}

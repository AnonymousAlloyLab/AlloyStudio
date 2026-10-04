package is.fivefivefive.CanDis.theory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** Deterministic Phase F certificate and certified-transition gate. */
public final class TheoryCertificatesTest {
    private static final long SEED = 555_202_608_21L;
    private static final GraphType USER = GraphType.constructor("User");
    private static final GraphType USER_REL = GraphType.relation(USER);
    private static int checks;

    private TheoryCertificatesTest() {
    }

    public static void main(String[] args) throws Exception {
        testCertificateAlgebra();
        testCertifiedUnionAndFindReplay();
        testCertifiedSymmetryAdmission();
        testForwardCongruenceOnly();
        testContainerLawProvenance();
        testConcreteContainerConstructionReplay();
        testBinderAutomorphismProvenance();
        testInterfaceRestrictionCertificate();
        testGeneratedCertifiedChains();
        testRejectedFixedBatchIsPure();
        testMutationBoundary();
        checks += TheoryDependentChainTest.runAssertions();
        System.out.println("TheoryCertificatesTest passed: " + checks
                + " checks; deterministic seed=" + SEED);
    }

    /** Definition 7 and Theorem 1: typed endpoints and valid proof composition. */
    private static void testCertificateAlgebra() {
        TypedEClassInterface left = emptyClass(1, GraphType.BOOL);
        TypedEClassInterface right = emptyClass(2, GraphType.BOOL);
        InputEquationCertificate input = inputEquation(
                left, TypedInvocation.identity(right), "eq", 0);
        CertificateVerifier.verify(input);
        check(input.category() == CertificateCategory.INPUT_EQUATION,
                "Input equation retains its proof category");
        check(input.endpointTypeCheck() != null,
                "Every certificate retains its successful endpoint type check");

        TypedEqualityCertificate symmetric = EqualityCertificates.symmetric(input);
        TypedEqualityCertificate roundTrip = EqualityCertificates.transitive(
                input, symmetric);
        CertificateVerifier.verify(roundTrip);
        check(roundTrip.leftEndpoint().equals(roundTrip.rightEndpoint()),
                "Typed symmetry and transitivity compose at exact endpoints");

        TypedSlot source = TypedSlot.source(USER, 0);
        TypedSlot target = TypedSlot.source(USER, 1);
        TypedSlotContext sourceContext = TypedSlotContext.singleton(source);
        TypedSlotContext targetContext = TypedSlotContext.singleton(target);
        TypedEClassInterface sourceClass = new TypedEClassInterface(
                EClassId.of(3), USER_REL, sourceContext);
        TypedEClassInterface targetClass = new TypedEClassInterface(
                EClassId.of(4), USER_REL, sourceContext);
        InputEquationCertificate contextual = inputEquation(
                sourceClass, TypedInvocation.identity(targetClass), "contextual", 1);
        TypedRenaming renaming = TypedRenaming.of(
                sourceContext, targetContext, mapOf(source, target));
        TypedEqualityCertificate renamed = EqualityCertificates.rename(
                contextual, renaming);
        CertificateVerifier.verify(renamed);
        check(renamed.context().equals(targetContext),
                "Typed renaming transports both certificate endpoints");

        expectThrows(IllegalArgumentException.class, () -> EqualityCertificates.transitive(
                input, contextual));
        expectThrows(IllegalArgumentException.class, () -> new InputEquationCertificate(
                CertificateOrigin.containerLaw("sig", "bad", 0),
                input.leftEndpoint(),
                input.rightEndpoint()));
        expectThrows(IllegalArgumentException.class, () -> new InputEquationCertificate(
                CertificateOrigin.inputEquation("model", "ill-typed", 0),
                TypedCertificateEndpoint.eclassWitness(left),
                TypedCertificateEndpoint.eclassWitness(sourceClass)));
    }

    /** Definition 5, Lemma 5, and Theorem 1 obligations 4, 7, and 9. */
    private static void testCertifiedUnionAndFindReplay() {
        TypedSlot ax = TypedSlot.source(USER, 10);
        TypedSlot ay = TypedSlot.source(USER, 11);
        TypedSlot bx = TypedSlot.source(USER, 20);
        TypedSlot cx = TypedSlot.source(USER, 30);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(10), USER_REL, TypedSlotContext.of(ax, ay));
        TypedEClassInterface middle = new TypedEClassInterface(
                EClassId.of(11), USER_REL, TypedSlotContext.singleton(bx));
        TypedEClassInterface leader = new TypedEClassInterface(
                EClassId.of(12), USER_REL, TypedSlotContext.singleton(cx));

        TypedInvocation middleInChild = new TypedInvocation(
                middle,
                TypedEmbedding.of(
                        middle.exposedSlots(), child.exposedSlots(), mapOf(bx, ay)));
        TypedInvocation leaderInMiddle = new TypedInvocation(
                leader,
                TypedEmbedding.of(
                        leader.exposedSlots(), middle.exposedSlots(), mapOf(cx, bx)));
        ParentEdgeCertificate first = new ParentEdgeCertificate(
                child,
                middleInChild,
                inputEquation(child, middleInChild, "proper-parent", 0));
        ParentEdgeCertificate second = new ParentEdgeCertificate(
                middle,
                leaderInMiddle,
                inputEquation(middle, leaderInMiddle, "parent", 1));

        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(child);
        graph.registerEmptyClassForPhaseF(middle);
        graph.registerEmptyClassForPhaseF(leader);
        graph.unionCertified(first);
        graph.unionCertified(second);
        check(graph.status() == GraphStatus.DIRTY,
                "A certified union dirties the graph for Phase G rebuilding");
        check(graph.eclass(child.id()).symmetryGroup().elements().size() == 1,
                "A union never infers a symmetry from endpoint images");

        TypedSlot gx = TypedSlot.source(USER, 40);
        TypedSlot gy = TypedSlot.source(USER, 41);
        TypedSlotContext caller = TypedSlotContext.of(gx, gy);
        TypedInvocation source = new TypedInvocation(
                child,
                TypedEmbedding.of(
                        child.exposedSlots(), caller, mapOf(ax, gx, ay, gy)));
        TypedFindResult result = graph.findWithProvenance(source);
        check(result.hasParentCertificate(),
                "Certified find retains a replayable primitive proof path");
        TypedEqualityCertificate replay = result.parentCertificate();
        CertificateVerifier.verify(replay);
        check(replay.leftEndpoint().equals(
                    TypedCertificateEndpoint.invocation(source))
                && replay.rightEndpoint().equals(
                        TypedCertificateEndpoint.invocation(result.leaderInvocation())),
                "Compressed find proof has exactly the returned invocation endpoints");
        check(graph.parentAssignments().get(child.id())
                        .provenancePath().steps().size() == 2,
                "Path compression retains both primitive parent certificates");

        TypedEqualityCertificate reflexivity = EqualityCertificates.reflexive(
                TypedCertificateEndpoint.eclassWitness(leader));
        expectThrows(IllegalArgumentException.class, () -> new ParentEdgeCertificate(
                child, middleInChild, reflexivity));
        expectThrows(IllegalArgumentException.class, () -> new ParentEdgeCertificate(
                leader, TypedInvocation.identity(leader), reflexivity));
    }

    /** Definition 7 (SC) and Theorem 1 obligation 5: separately proved symmetry. */
    private static void testCertifiedSymmetryAdmission() {
        TypedSlot x = TypedSlot.source(USER, 50);
        TypedSlot y = TypedSlot.source(USER, 51);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        TypedEClassInterface eclass = new TypedEClassInterface(
                EClassId.of(20), USER_REL, context);
        TypedPermutation swap = TypedPermutation.of(context, mapOf(x, y, y, x));
        TypedInvocation left = TypedInvocation.identity(eclass);
        TypedInvocation right = new TypedInvocation(eclass, swap);
        InputEquationCertificate origin = InputEquationCertificate.betweenInvocations(
                CertificateOrigin.rewriteAxiom("rules", "swap-proof", 0),
                left,
                right);
        SymmetryCertificate certificate = new SymmetryCertificate(left, right, origin);
        check(certificate.inducedPermutation().equals(swap),
                "Symmetry certificate records the induced typed permutation");
        check(certificate.provenanceKind()
                        == SymmetryCertificate.ProvenanceKind.REWRITE_AXIOM,
                "Symmetry certificate records its explicit provenance category");

        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(eclass);
        check(graph.addSymmetryCertified(eclass.id(), certificate),
                "A verified symmetry generator is admitted");
        check(graph.eclass(eclass.id()).symmetryGroup().contains(swap),
                "The certified group closes over the admitted generator");
        check(!graph.addSymmetryCertified(eclass.id(), certificate),
                "An already generated symmetry is not inserted twice");

        TypedSlot z = TypedSlot.source(USER, 52);
        TypedSlotContext three = TypedSlotContext.of(x, y, z);
        TypedEClassInterface s3Class = new TypedEClassInterface(
                EClassId.of(21), USER_REL, three);
        TypedPermutation swapXy = TypedPermutation.of(
                three, mapOf(x, y, y, x, z, z));
        TypedPermutation swapYz = TypedPermutation.of(
                three, mapOf(x, x, y, z, z, y));
        SymmetryCertificate xyCertificate = symmetryAxiom(
                s3Class, swapXy, "s3-xy", 1);
        SymmetryCertificate yzCertificate = symmetryAxiom(
                s3Class, swapYz, "s3-yz", 2);
        TypedSymmetryGroup s3 = TypedSymmetryGroup.certified(
                s3Class, Arrays.asList(xyCertificate, yzCertificate));
        check(s3.elements().size() == 6,
                "Two certified transpositions generate the complete S3 closure");
        for (TypedPermutation element : s3.elements()) {
            CertificateVerifier.verify(s3.derivationFor(s3Class, element));
            check(true,
                    "Every derived e-class symmetry has reconstructible provenance");
        }

        TypedSlotContext larger = TypedSlotContext.of(x, y, z);
        TypedEmbedding proper = TypedEmbedding.of(
                context, larger, mapOf(x, x, y, y));
        TypedInvocation properEndpoint = new TypedInvocation(eclass, proper);
        InputEquationCertificate properOrigin = InputEquationCertificate.betweenInvocations(
                CertificateOrigin.inputEquation("model", "proper", 0),
                properEndpoint,
                properEndpoint);
        expectThrows(IllegalArgumentException.class,
                () -> new SymmetryCertificate(
                        properEndpoint, properEndpoint, properOrigin));
    }

    /** Lemma 4 and Theorem 1 obligation 8: congruence is forward only. */
    private static void testForwardCongruenceOnly() {
        TypedEClassInterface leftClass = emptyClass(30, GraphType.BOOL);
        TypedEClassInterface rightClass = emptyClass(31, GraphType.BOOL);
        OnePortSchema oneBool = new OnePortSchema(GraphType.BOOL);
        OnePort leftPort = OnePort.invocation(
                TypedSlotContext.empty(), TypedInvocation.identity(leftClass));
        OnePort rightPort = OnePort.invocation(
                TypedSlotContext.empty(), TypedInvocation.identity(rightClass));
        InputEquationCertificate childEquation = inputEquation(
                leftClass, TypedInvocation.identity(rightClass), "child", 0);
        CongruenceCertificate portCongruence = CongruenceCertificate.ports(
                leftPort, rightPort, Collections.singletonList(childEquation));

        OperatorDeclaration operator = OperatorDeclaration.monomorphic(
                "opaque-f",
                Collections.singletonList(oneBool),
                GraphType.BOOL,
                Collections.emptyMap(),
                null);
        TypedENode leftNode = TypedENode.construct(
                operator.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Collections.singletonList(leftPort));
        TypedENode rightNode = TypedENode.construct(
                operator.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Collections.singletonList(rightPort));
        CongruenceCertificate nodeCongruence = CongruenceCertificate.nodes(
                leftNode, rightNode, Collections.singletonList(portCongruence));
        CertificateVerifier.verify(nodeCongruence);
        check(nodeCongruence.category() == CertificateCategory.FORWARD_CONGRUENCE,
                "Child equality lifts through the parent operator");

        InputEquationCertificate leftCoherence = new InputEquationCertificate(
                CertificateOrigin.inputEquation("model", "left-coherence", 2),
                TypedCertificateEndpoint.eclassWitness(leftClass),
                TypedCertificateEndpoint.node(leftNode));
        InputEquationCertificate rightCoherence = new InputEquationCertificate(
                CertificateOrigin.inputEquation("model", "right-coherence", 3),
                TypedCertificateEndpoint.node(rightNode),
                TypedCertificateEndpoint.eclassWitness(rightClass));
        TypedEqualityCertificate congruenceDerived = EqualityCertificates.transitive(
                EqualityCertificates.transitive(leftCoherence, nodeCongruence),
                rightCoherence);
        ParentEdgeCertificate congruenceParent = new ParentEdgeCertificate(
                leftClass,
                TypedInvocation.identity(rightClass),
                congruenceDerived);
        CertificateVerifier.verifyParentEdge(congruenceParent);
        check(CertificateVerifier.containsCategory(
                        congruenceParent,
                        CertificateCategory.FORWARD_CONGRUENCE),
                "A parent edge can retain a forward-congruence derivation");

        InputEquationCertificate parentOnly = new InputEquationCertificate(
                CertificateOrigin.inputEquation("model", "noninjective-parent", 1),
                TypedCertificateEndpoint.node(leftNode),
                TypedCertificateEndpoint.node(rightNode));
        expectThrows(IllegalArgumentException.class, () -> new ParentEdgeCertificate(
                leftClass, TypedInvocation.identity(rightClass), parentOnly));
        check(parentOnly.premises().isEmpty(),
                "A parent equation exposes no inverse child-equality operation");
    }

    /** Section 4: Seq=A, Bag=AC, Set=ACI, with explicit unit provenance. */
    private static void testContainerLawProvenance() {
        OnePortSchema element = new OnePortSchema(USER);
        SetPortSchema setSchema = new SetPortSchema(
                ContainerEmptiness.K_PLUS, element);
        CertificateOrigin origin = CertificateOrigin.containerLaw(
                "test-signature", "aci-set", 0);
        List<ContainerLawCertificate> certificates = Arrays.asList(
                ContainerLawCertificate.testFixture(
                        setSchema, ContainerLawCertificate.Law.ASSOCIATIVITY, origin),
                ContainerLawCertificate.testFixture(
                        setSchema, ContainerLawCertificate.Law.COMMUTATIVITY, origin),
                ContainerLawCertificate.testFixture(
                        setSchema, ContainerLawCertificate.Law.IDEMPOTENCY, origin));
        ContainerLawDeclaration certified = ContainerLawDeclaration.certified(
                setSchema, certificates);
        check(certified.hasCertifiedLaws(),
                "Set declaration retains ACI provenance separately from its schema");

        TypedSlot x = TypedSlot.source(USER, 60);
        TypedSlot y = TypedSlot.source(USER, 61);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        SetPort set = new SetPort(
                setSchema,
                context,
                Arrays.asList(OnePort.slot(context, y), OnePort.slot(context, x)));
        OperatorDeclaration certifiedOperator = OperatorDeclaration.monomorphic(
                "certified-set",
                Collections.singletonList(setSchema),
                GraphType.BOOL,
                lawMap(certified),
                null);
        TypedENode certifiedNode = TypedENode.construct(
                certifiedOperator.instantiateMonomorphic(),
                context,
                Collections.singletonList(set));
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        expectThrows(IllegalStateException.class,
                () -> graph.canonicalize(certifiedNode));
        expectThrows(IllegalStateException.class,
                () -> graph.insertNode(
                        certifiedNode, graph.coherentWitnessFamily()));
        TypedSlottedPortEGraph fixtureGraph =
                TypedSlottedPortEGraph.structuralFixture();
        check(fixtureGraph.canonicalize(certifiedNode).shape() != null,
                "TEST_ONLY Set laws are confined to an explicit structural fixture graph");

        ContainerLawDeclaration raw = ContainerLawDeclaration.of(
                ContainerLawDeclaration.Kind.SET, true, true, true, false);
        OperatorDeclaration rawOperator = OperatorDeclaration.monomorphic(
                "raw-set",
                Collections.singletonList(setSchema),
                GraphType.BOOL,
                lawMap(raw),
                null);
        TypedENode rawNode = TypedENode.construct(
                rawOperator.instantiateMonomorphic(),
                context,
                Collections.singletonList(set));
        expectThrows(IllegalStateException.class, () -> graph.canonicalize(rawNode));

        BagPortSchema bagSchema = new BagPortSchema(
                ContainerEmptiness.K_PLUS, element);
        expectThrows(IllegalStateException.class, () -> ContainerLawCertificate.testFixture(
                bagSchema,
                ContainerLawCertificate.Law.IDEMPOTENCY,
                origin));
        SeqPortSchema emptySequence = new SeqPortSchema(
                ContainerEmptiness.K_ZERO, element);
        ContainerLawCertificate sequenceAssociativity = ContainerLawCertificate.testFixture(
                emptySequence,
                ContainerLawCertificate.Law.ASSOCIATIVITY,
                origin);
        ContainerLawDeclaration ordinaryEmptySequence = ContainerLawDeclaration.certified(
                emptySequence, Collections.singletonList(sequenceAssociativity));
        check(ordinaryEmptySequence.associative() && !ordinaryEmptySequence.hasUnit(),
                "An ordinary K0 sequence may carry certified A without fabricating U");
    }

    private static void testConcreteContainerConstructionReplay() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        OnePortSchema booleanElement = new OnePortSchema(GraphType.BOOL);
        SetPortSchema booleanSet = new SetPortSchema(
                ArityPolicy.nonemptyVariadic(), booleanElement);
        List<ContainerLawCertificate> booleanAci = new ArrayList<>();
        for (ContainerLawCertificate.Law law : Arrays.asList(
                ContainerLawCertificate.Law.ASSOCIATIVITY,
                ContainerLawCertificate.Law.COMMUTATIVITY,
                ContainerLawCertificate.Law.IDEMPOTENCY)) {
            booleanAci.add(AlloyLawRegistry.issue(
                    profile,
                    Opcode.AND,
                    "ALLOY/AND",
                    GraphType.BOOL,
                    PortPath.at(0),
                    booleanSet,
                    law));
        }
        InstantiatedOperator productionAnd = OperatorDeclaration.monomorphic(
                "ALLOY/AND",
                Collections.singletonList(booleanSet),
                GraphType.BOOL,
                lawMap(ContainerLawDeclaration.certified(booleanSet, booleanAci)),
                0).instantiateMonomorphic();
        TypedSlot booleanSlot = TypedSlot.source(GraphType.BOOL, 599);
        TypedSlotContext booleanContext = TypedSlotContext.singleton(booleanSlot);
        OnePort booleanPort = OnePort.slot(booleanContext, booleanSlot);
        FlatApplication duplicateAnd = new FlatApplication(
                productionAnd,
                booleanContext,
                Arrays.asList(new FlatLeaf(booleanPort), new FlatLeaf(booleanPort)));
        expectThrows(IllegalArgumentException.class, () -> TypedENode.flatConstruct(
                duplicateAnd,
                ignored -> {
                    throw new AssertionError("A leaf-only application has no sealer");
                }));
        CertifiedFlatConstruction certifiedDuplicateAnd =
                TypedENode.flatConstructCertified(
                        duplicateAnd,
                        ignored -> {
                            throw new AssertionError("A leaf-only application has no sealer");
                        },
                        profile);
        check(certifiedDuplicateAnd.collapsedToSingleton()
                        && certifiedDuplicateAnd.singleton().equals(booleanPort),
                "Production ACI construction is certificate-returning and smart-collapses");
        CertifiedFlatConstruction unaryAnd = TypedENode.flatConstructCertified(
                new FlatApplication(
                        productionAnd,
                        booleanContext,
                        Collections.singletonList(new FlatLeaf(booleanPort))),
                ignored -> {
                    throw new AssertionError("A leaf-only application has no sealer");
                },
                profile);
        check(unaryAnd.collapsedToSingleton()
                        && unaryAnd.singleton().equals(booleanPort)
                        && unaryAnd.certificate().premises().stream().noneMatch(
                                premise -> premise instanceof ContainerLawCertificate
                                        && ((ContainerLawCertificate) premise).law()
                                                == ContainerLawCertificate.Law.IDEMPOTENCY),
                "Unary ACI smart collapse needs no fabricated idempotency step");

        OnePortSchema integer = new OnePortSchema(GraphType.INT);
        BagPortSchema equalityBag = new BagPortSchema(ArityPolicy.exact(2), integer);
        ContainerLawCertificate equalityC = AlloyLawRegistry.issue(
                profile,
                Opcode.EQUALS,
                "ALLOY/EQUALS",
                GraphType.BOOL,
                PortPath.at(0),
                equalityBag,
                ContainerLawCertificate.Law.COMMUTATIVITY);
        ContainerLawDeclaration equalityLaws = ContainerLawDeclaration.certified(
                equalityBag, Collections.singletonList(equalityC));
        InstantiatedOperator equality = OperatorDeclaration.monomorphic(
                "ALLOY/EQUALS",
                Collections.singletonList(equalityBag),
                GraphType.BOOL,
                lawMap(equalityLaws),
                null).instantiateMonomorphic();

        TypedSlot x = TypedSlot.source(GraphType.INT, 600);
        TypedSlot y = TypedSlot.source(GraphType.INT, 601);
        TypedSlot flag = TypedSlot.source(GraphType.BOOL, 602);
        TypedSlotContext context = TypedSlotContext.of(x, y, flag);
        OnePort xPort = OnePort.slot(context, x);
        OnePort yPort = OnePort.slot(context, y);
        OnePort flagPort = OnePort.slot(context, flag);
        CertifiedContainerConstruction xy = TypedENode.constructContainerCertified(
                equality,
                PortPath.at(0),
                context,
                Arrays.asList(xPort, yPort),
                profile);
        CertifiedContainerConstruction yx = TypedENode.constructContainerCertified(
                equality,
                PortPath.at(0),
                context,
                Arrays.asList(yPort, xPort),
                profile);
        check(xy.node().equals(yx.node()),
                "C-only construction has one normalized bag target");
        check(!xy.certificate().leftEndpoint().equals(
                        yx.certificate().leftEndpoint()),
                "Concrete C certificates retain distinct ordered source endpoints");
        check(xy.certificate().premises().stream()
                        .anyMatch(equalityC::equals)
                        && yx.certificate().premises().stream()
                                .anyMatch(equalityC::equals),
                "Every ordered-to-bag quotient cites the exact C theory index");
        check(xy.certificate().containerTrace().outputFibers().size() == 2
                        && yx.certificate().containerTrace().outputFibers().size() == 2,
                "Concrete C certificates retain an exact permutation fiber for each output");
        TypedSlottedPortEGraph strictConstructionGraph =
                new TypedSlottedPortEGraph();
        expectThrows(IllegalStateException.class, () ->
                strictConstructionGraph.canonicalize(xy.node()));
        check(strictConstructionGraph.canonicalizeConstructed(xy).shape() != null,
                "Strict canonicalization requires the ordered-source construction proof");
        expectThrows(IllegalStateException.class, () ->
                strictConstructionGraph.insertNode(
                        xy.node(), strictConstructionGraph.coherentWitnessFamily()));
        check(strictConstructionGraph.insertNodeConstructed(
                        xy, strictConstructionGraph.coherentWitnessFamily())
                        .sourceToReturnedInvocation() != null,
                "Strict insertion retains concrete nonflat source evidence");

        TypedSlottedPortEGraph fixedBatchGraph = new TypedSlottedPortEGraph();
        CanonicalizationResult fixedCanonical =
                fixedBatchGraph.canonicalizeConstructed(xy);
        TypedEClassInterface fixedOwner = new TypedEClassInterface(
                EClassId.of(6_050),
                GraphType.BOOL,
                fixedCanonical.effectiveSupport());
        ShapeWitness fixedWitness = new ShapeWitness(
                fixedCanonical.shape().exactSlots(),
                fixedCanonical.effectiveSupport(),
                fixedOwner.exposedSlots(),
                fixedCanonical.witness());
        TypedEClassRecord fixedRecord = TypedEClassRecord.of(
                fixedOwner,
                Collections.singletonMap(fixedCanonical.shape(), fixedWitness),
                TypedSymmetryGroup.identity(fixedOwner.exposedSlots()));
        TypedEmbedding fixedInSource = TypedEmbedding.inclusion(
                fixedOwner.exposedSlots(), fixedCanonical.source().context());
        InputEquationCertificate fixedEquation = new InputEquationCertificate(
                CertificateOrigin.inputEquation(
                        "certificate-test", "fixed-batch-equality", 0),
                TypedCertificateEndpoint.node(fixedCanonical.shape().node().act(
                        fixedCanonical.witness())),
                TypedCertificateEndpoint.invocation(
                        new TypedInvocation(fixedOwner, fixedInSource)));
        Map<CanonicalShape, TypedEqualityCertificate> fixedEquations =
                Collections.singletonMap(fixedCanonical.shape(), fixedEquation);
        expectThrows(IllegalArgumentException.class, () ->
                fixedBatchGraph.admitFixedBatchRecordCertified(
                        fixedRecord, fixedEquations));
        fixedBatchGraph.admitFixedBatchRecordCertified(
                fixedRecord,
                fixedEquations,
                Collections.singletonMap(
                        fixedCanonical.shape(), xy.certificate()));
        check(fixedBatchGraph.classes().containsKey(fixedOwner.id()),
                "Fixed-batch law-bearing admission retains exact construction evidence");

        expectThrows(IllegalArgumentException.class, () ->
                ContainerConstructionCertificate.createProduction(
                        equality,
                        PortPath.at(0),
                        context,
                        Arrays.asList(xPort, yPort),
                        xy.node(),
                        SemanticProfile.alloyModular()));
        expectThrows(IllegalArgumentException.class, () ->
                ContainerConstructionCertificate.createProduction(
                        equality,
                        PortPath.at(1),
                        context,
                        Arrays.asList(xPort, yPort),
                        xy.node(),
                        profile));
        expectThrows(IllegalStateException.class, () ->
                ContainerConstructionCertificate.createProduction(
                        equality,
                        PortPath.at(0),
                        context,
                        Collections.singletonList(xPort),
                        xy.node(),
                        profile));
        expectThrows(IllegalArgumentException.class, () ->
                ContainerConstructionCertificate.createProduction(
                        equality,
                        PortPath.at(0),
                        context,
                        Arrays.asList(xPort, flagPort),
                        xy.node(),
                        profile));

        ContainerLawCertificate inequalityC = AlloyLawRegistry.issue(
                profile,
                Opcode.NOT_EQUALS,
                "ALLOY/NOT_EQUALS",
                GraphType.BOOL,
                PortPath.at(0),
                equalityBag,
                ContainerLawCertificate.Law.COMMUTATIVITY);
        ContainerLawDeclaration inequalityLaws = ContainerLawDeclaration.certified(
                equalityBag, Collections.singletonList(inequalityC));
        InstantiatedOperator inequality = OperatorDeclaration.monomorphic(
                "ALLOY/NOT_EQUALS",
                Collections.singletonList(equalityBag),
                GraphType.BOOL,
                lawMap(inequalityLaws),
                null).instantiateMonomorphic();
        CertifiedContainerConstruction unequal = TypedENode.constructContainerCertified(
                inequality,
                PortPath.at(0),
                context,
                Arrays.asList(xPort, yPort),
                profile);
        expectThrows(IllegalArgumentException.class, () ->
                ContainerConstructionCertificate.createProduction(
                        equality,
                        PortPath.at(0),
                        context,
                        Arrays.asList(xPort, yPort),
                        unequal.node(),
                        profile));

        OnePortSchema relation = new OnePortSchema(USER_REL);
        BagPortSchema disjointBag = new BagPortSchema(
                ArityPolicy.nonemptyVariadic(), relation);
        ContainerLawCertificate disjointC = AlloyLawRegistry.issue(
                profile,
                Opcode.DISJOINT,
                "ALLOY/DISJOINT",
                GraphType.BOOL,
                PortPath.at(0),
                disjointBag,
                ContainerLawCertificate.Law.COMMUTATIVITY);
        InstantiatedOperator disjoint = OperatorDeclaration.monomorphic(
                "ALLOY/DISJOINT",
                Collections.singletonList(disjointBag),
                GraphType.BOOL,
                lawMap(ContainerLawDeclaration.certified(
                        disjointBag, Collections.singletonList(disjointC))),
                null).instantiateMonomorphic();
        TypedSlot relationX = TypedSlot.source(USER_REL, 603);
        TypedSlot relationY = TypedSlot.source(USER_REL, 604);
        TypedSlotContext relationContext = TypedSlotContext.of(relationX, relationY);
        OnePort relationXPort = OnePort.slot(relationContext, relationX);
        OnePort relationYPort = OnePort.slot(relationContext, relationY);
        CertifiedContainerConstruction duplicateDisjoint =
                TypedENode.constructContainerCertified(
                        disjoint,
                        PortPath.at(0),
                        relationContext,
                        Arrays.asList(relationXPort, relationXPort, relationYPort),
                        profile);
        check(duplicateDisjoint.certificate().containerTrace()
                        .inputOccurrences().size() == 3
                        && duplicateDisjoint.certificate().containerTrace()
                                .outputOccurrences().size() == 3,
                "C-only disjoint construction retains duplicate source occurrences");
        check(duplicateDisjoint.certificate().premises().stream()
                        .noneMatch(premise -> premise instanceof ContainerLawCertificate
                                && ((ContainerLawCertificate) premise).law()
                                        == ContainerLawCertificate.Law.IDEMPOTENCY),
                "Disjoint construction never cites I evidence");
    }

    /** Section 3.6 and Lemma 3: Aut(beta) preserves the complete descriptor. */
    private static void testBinderAutomorphismProvenance() {
        TypedSlot x = TypedSlot.canonicalBound(USER, 0);
        TypedSlot y = TypedSlot.canonicalBound(USER, 1);
        StructuralKey domain = StructuralKey.leaf("domain", "User");
        List<BinderCoordinateDescriptor> compatible = Arrays.asList(
                coordinate(x, domain, "all", "one", -1),
                coordinate(y, domain, "all", "one", -1));
        TypedSlotContext context = TypedSlotContext.of(x, y);
        TypedPermutation swap = TypedPermutation.of(context, mapOf(x, y, y, x));
        BinderAutomorphismCertificate certificate = new BinderAutomorphismCertificate(
                compatible,
                swap,
                CertificateOrigin.binderAutomorphism(
                        "test-signature", "all-user-pair", 0));
        BinderBlockDescriptor certified = BinderBlockDescriptor.certified(
                compatible, Collections.singletonList(certificate));
        check(certified.hasCertifiedAutomorphisms()
                        && certified.automorphisms().contains(swap),
                "Complete descriptor admits its certified permutation group");

        TypedSlot z = TypedSlot.canonicalBound(USER, 2);
        List<BinderCoordinateDescriptor> compatibleThree = Arrays.asList(
                coordinate(x, domain, "all", "one", -1),
                coordinate(y, domain, "all", "one", -1),
                coordinate(z, domain, "all", "one", -1));
        TypedSlotContext contextThree = TypedSlotContext.of(x, y, z);
        TypedPermutation swapXy = TypedPermutation.of(
                contextThree, mapOf(x, y, y, x, z, z));
        TypedPermutation swapYz = TypedPermutation.of(
                contextThree, mapOf(x, x, y, z, z, y));
        BinderAutomorphismCertificate binderXy = new BinderAutomorphismCertificate(
                compatibleThree,
                swapXy,
                CertificateOrigin.binderAutomorphism(
                        "test-signature", "all-user-triple-xy", 1));
        BinderAutomorphismCertificate binderYz = new BinderAutomorphismCertificate(
                compatibleThree,
                swapYz,
                CertificateOrigin.binderAutomorphism(
                        "test-signature", "all-user-triple-yz", 2));
        BinderBlockDescriptor certifiedThree = BinderBlockDescriptor.certified(
                compatibleThree, Arrays.asList(binderXy, binderYz));
        check(certifiedThree.automorphisms().elements().size() == 6,
                "Certified binder generators close to the full descriptor S3");
        for (TypedPermutation element : certifiedThree.automorphisms().elements()) {
            CertificateVerifier.verify(
                    certifiedThree.automorphisms().derivationFor(
                            certifiedThree, element));
            check(true,
                    "Every derived binder automorphism has reconstructible provenance");
        }

        List<BinderCoordinateDescriptor> incompatible = Arrays.asList(
                coordinate(x, domain, "all", "one", -1),
                coordinate(y, domain, "some", "one", -1));
        expectThrows(IllegalArgumentException.class, () -> new BinderAutomorphismCertificate(
                incompatible,
                swap,
                CertificateOrigin.binderAutomorphism(
                        "test-signature", "incompatible", 1)));

        List<BinderCoordinateDescriptor> separateScopes = Arrays.asList(
                new BinderCoordinateDescriptor(
                        x, domain, "all", "one", -1, 0,
                        TypedSlotContext.empty()),
                new BinderCoordinateDescriptor(
                        y, domain, "all", "one", -1, 1,
                        TypedSlotContext.empty()));
        expectThrows(IllegalArgumentException.class, () -> new BinderAutomorphismCertificate(
                separateScopes,
                swap,
                CertificateOrigin.binderAutomorphism(
                        "test-signature", "cross-exchange-scope", 2)));

        BindBlockPortSchema certifiedSchema = new BindBlockPortSchema(
                certified, new OnePortSchema(USER));
        TypedRenaming occurrence = certified.freshOccurrenceRenaming(
                TypedSlotContext.empty());
        BindBlockPort certifiedPort = new BindBlockPort(
                certifiedSchema,
                TypedSlotContext.empty(),
                occurrence,
                OnePort.slot(occurrence.codomain(), occurrence.apply(x)));
        OperatorDeclaration certifiedOperator = OperatorDeclaration.monomorphic(
                "certified-block",
                Collections.singletonList(certifiedSchema),
                GraphType.BOOL,
                Collections.emptyMap(),
                null);
        TypedENode certifiedNode = TypedENode.construct(
                certifiedOperator.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Collections.singletonList(certifiedPort));
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        check(graph.canonicalize(certifiedNode).shape() != null,
                "Strict canonicalization uses a certified Aut(beta)");

        BinderOccurrenceAutomorphismCertificate occurrenceCertificate =
                BinderOccurrenceAutomorphismCertificate.create(
                        certifiedNode,
                        certifiedPort,
                        Collections.singletonList(0),
                        swap);
        CertificateVerifier.verify(occurrenceCertificate);
        check(occurrenceCertificate.source().equals(certifiedPort)
                        && occurrenceCertificate.sourcePath().equals(
                                Collections.singletonList(0)),
                "Binder evidence is bound to the complete concrete source occurrence and path");
        check(occurrenceCertificate.occurrencePermutation().apply(
                        occurrence.apply(x)).equals(occurrence.apply(y))
                        && occurrenceCertificate.occurrencePermutation().apply(
                                occurrence.apply(y)).equals(occurrence.apply(x)),
                "Descriptor automorphism is conjugated into the fresh occurrence coordinates");
        check(((SlotPortLeaf) ((OnePort) occurrenceCertificate.target().body())
                        .leaf()).slot().equals(occurrence.apply(y)),
                "Concrete binder evidence records the exact acted target endpoint");
        check(!occurrenceCertificate.appliesTo(
                        certifiedNode,
                        certifiedPort,
                        Collections.singletonList(1),
                        swap),
                "Binder evidence cannot replay at another source path");
        expectThrows(IllegalArgumentException.class,
                () -> BinderOccurrenceAutomorphismCertificate.create(
                        certifiedNode,
                        certifiedPort,
                        Collections.singletonList(0),
                        TypedPermutation.identity(certified.boundContext())));

        TypedRenaming secondOccurrence = certified.freshOccurrenceRenaming(
                occurrence.codomain());
        BindBlockPort secondPort = new BindBlockPort(
                certifiedSchema,
                TypedSlotContext.empty(),
                secondOccurrence,
                OnePort.slot(secondOccurrence.codomain(), secondOccurrence.apply(x)));
        OperatorDeclaration twoOccurrenceOperator = OperatorDeclaration.monomorphic(
                "two-certified-block-occurrences",
                Arrays.asList(certifiedSchema, certifiedSchema),
                GraphType.BOOL,
                Collections.emptyMap(),
                null);
        TypedENode twoOccurrences = TypedENode.construct(
                twoOccurrenceOperator.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Arrays.asList(certifiedPort, secondPort));
        List<BinderOccurrenceAutomorphismCertificate> occurrenceProofs =
                BinderOccurrenceProofs.collect(twoOccurrences);
        check(occurrenceProofs.size() == 2,
                "Every concrete occurrence of a symmetric descriptor receives evidence");
        check(occurrenceProofs.get(0).sourcePath().equals(
                        Collections.singletonList(0))
                        && occurrenceProofs.get(1).sourcePath().equals(
                                Collections.singletonList(1)),
                "Same-descriptor occurrences retain distinct deterministic source paths");
        check(!occurrenceProofs.get(0).source().descriptorToOccurrence().equals(
                        occurrenceProofs.get(1).source().descriptorToOccurrence())
                        && !occurrenceProofs.get(0).leftEndpoint().equals(
                                occurrenceProofs.get(1).leftEndpoint()),
                "Same-descriptor occurrences retain distinct fresh contexts and endpoints");
        check(!occurrenceProofs.get(0).appliesTo(
                        twoOccurrences,
                        occurrenceProofs.get(1).source(),
                        occurrenceProofs.get(1).sourcePath(),
                        swap),
                "Binder occurrence evidence cannot replay across fresh occurrence contexts");

        OperatorDeclaration alternateRootOperator = OperatorDeclaration.monomorphic(
                "certified-block-alternate-root",
                Collections.singletonList(certifiedSchema),
                GraphType.BOOL,
                Collections.emptyMap(),
                null);
        TypedENode alternateRoot = TypedENode.construct(
                alternateRootOperator.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Collections.singletonList(certifiedPort));
        BinderOccurrenceAutomorphismCertificate alternateRootCertificate =
                BinderOccurrenceProofs.collect(alternateRoot).get(0);
        check(!occurrenceCertificate.structuralKey().equals(
                        alternateRootCertificate.structuralKey()),
                "Equal local binder paths under distinct roots have distinct evidence keys");
        check(!occurrenceCertificate.appliesTo(
                        alternateRoot,
                        certifiedPort,
                        Collections.singletonList(0),
                        swap),
                "Binder evidence cannot replay under another enclosing root");

        BinderBlockDescriptor raw = new BinderBlockDescriptor(
                compatible, Collections.singletonList(swap));
        BindBlockPortSchema rawSchema = new BindBlockPortSchema(
                raw, new OnePortSchema(USER));
        TypedRenaming rawOccurrence = raw.freshOccurrenceRenaming(
                TypedSlotContext.empty());
        BindBlockPort rawPort = new BindBlockPort(
                rawSchema,
                TypedSlotContext.empty(),
                rawOccurrence,
                OnePort.slot(rawOccurrence.codomain(), rawOccurrence.apply(x)));
        OperatorDeclaration rawOperator = OperatorDeclaration.monomorphic(
                "raw-block",
                Collections.singletonList(rawSchema),
                GraphType.BOOL,
                Collections.emptyMap(),
                null);
        TypedENode rawNode = TypedENode.construct(
                rawOperator.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Collections.singletonList(rawPort));
        expectThrows(IllegalStateException.class, () -> graph.canonicalize(rawNode));
    }

    /** Theorem 1 obligation 6: restriction requires typed factorization evidence. */
    private static void testInterfaceRestrictionCertificate() {
        TypedSlot x = TypedSlot.source(USER, 70);
        TypedSlot y = TypedSlot.source(USER, 71);
        TypedEClassInterface original = new TypedEClassInterface(
                EClassId.of(40), USER_REL, TypedSlotContext.of(x, y));
        TypedSlot canonicalX = TypedSlot.canonicalFree(USER, 0);
        TypedSlot canonicalY = TypedSlot.canonicalFree(USER, 1);
        TypedSlotContext exactContext = TypedSlotContext.of(canonicalX, canonicalY);
        OnePortSchema oneUser = new OnePortSchema(USER);
        OperatorDeclaration operator = OperatorDeclaration.monomorphic(
                "restriction-shape",
                Arrays.asList(oneUser, oneUser),
                USER_REL,
                Collections.emptyMap(),
                null);
        CanonicalShape shape = CanonicalShape.of(TypedENode.construct(
                operator.instantiateMonomorphic(),
                exactContext,
                Arrays.asList(
                        OnePort.slot(exactContext, canonicalX),
                        OnePort.slot(exactContext, canonicalY))));
        TypedRenaming instantiation = TypedRenaming.of(
                exactContext,
                original.exposedSlots(),
                mapOf(canonicalX, x, canonicalY, y));
        ShapeWitness witness = new ShapeWitness(
                exactContext,
                original.exposedSlots(),
                original.exposedSlots(),
                instantiation);
        TypedEClassRecord record = TypedEClassRecord.of(
                original,
                Collections.singletonMap(shape, witness),
                TypedSymmetryGroup.identity(original.exposedSlots()));
        TypedSlotContext restrictedContext = TypedSlotContext.singleton(x);
        TypedCertificateEndpoint restricted = TypedCertificateEndpoint.restrictedWitness(
                original, restrictedContext);
        TypedEmbedding inclusion = TypedEmbedding.inclusion(
                restrictedContext, original.exposedSlots());
        InputEquationCertificate factorization = new InputEquationCertificate(
                CertificateOrigin.inputEquation("model", "independence", 0),
                TypedCertificateEndpoint.eclassWitness(original),
                restricted.act(inclusion));
        InterfaceRestrictionCertificate certificate =
                new InterfaceRestrictionCertificate(
                        record, restrictedContext, factorization);
        CertificateVerifier.verifyInterfaceRestriction(certificate);
        check(certificate.inclusion().equals(inclusion)
                        && certificate.restrictedInterface().exposedSlots()
                                .equals(restrictedContext),
                "Restriction certificate records the exact typed factorization");
        ShapeWitness transported = certificate.transportedShapeWitnesses().get(shape);
        check(transported != null
                        && transported.exactSlots().equals(witness.exactSlots())
                        && transported.ambientSupport().equals(witness.ambientSupport())
                        && transported.instantiatingRenaming().equals(
                                witness.instantiatingRenaming())
                        && transported.exposedInterface().equals(restrictedContext),
                "Restriction transport preserves each witness field except its interface");

        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(record);
        graph.verifyInterfaceRestriction(certificate);
        check(graph.eclass(original.id()).interfaceView().equals(original),
                "Phase F verification does not perform the Phase G restriction transaction");
        expectThrows(IllegalArgumentException.class,
                () -> new InterfaceRestrictionCertificate(
                        record, original.exposedSlots(), factorization));
        expectThrows(IllegalArgumentException.class,
                () -> new InterfaceRestrictionCertificate(
                        record,
                        restrictedContext,
                        EqualityCertificates.reflexive(
                                TypedCertificateEndpoint.eclassWitness(original))));
    }

    /** Lemma 5: generated parent paths compose embeddings and PC derivations. */
    private static void testGeneratedCertifiedChains() {
        Random random = new Random(SEED);
        for (int round = 0; round < 64; round++) {
            int length = 2 + random.nextInt(6);
            List<TypedEClassInterface> classes = new ArrayList<>(length);
            List<TypedSlot> slots = new ArrayList<>(length);
            TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
            for (int index = 0; index < length; index++) {
                TypedSlot slot = TypedSlot.source(
                        USER, 10_000L + round * 20L + index);
                slots.add(slot);
                TypedEClassInterface eclass = new TypedEClassInterface(
                        EClassId.of(10_000L + round * 20L + index),
                        USER_REL,
                        TypedSlotContext.singleton(slot));
                classes.add(eclass);
                graph.registerEmptyClassForPhaseF(eclass);
            }
            for (int index = 0; index + 1 < length; index++) {
                TypedEClassInterface child = classes.get(index);
                TypedEClassInterface parent = classes.get(index + 1);
                TypedInvocation parentInvocation = new TypedInvocation(
                        parent,
                        TypedRenaming.of(
                                parent.exposedSlots(),
                                child.exposedSlots(),
                                mapOf(slots.get(index + 1), slots.get(index))));
                ParentEdgeCertificate certificate = new ParentEdgeCertificate(
                        child,
                        parentInvocation,
                        inputEquation(child, parentInvocation, "generated", index));
                graph.unionCertified(certificate);
            }
            TypedFindResult result = graph.findWithProvenance(
                    TypedInvocation.identity(classes.get(0)));
            TypedEqualityCertificate replay = result.parentCertificate();
            CertificateVerifier.verify(replay);
            check(result.leaderInvocation().eclass().equals(classes.get(length - 1)),
                    "Generated certified chain reaches its final leader");
            check(result.parentPath().steps().size() == length - 1,
                    "Generated compressed path retains every primitive proof");
            check(replay.leftEndpoint().equals(
                        TypedCertificateEndpoint.eclassWitness(classes.get(0)))
                    && replay.rightEndpoint().equals(
                            TypedCertificateEndpoint.invocation(
                                    result.leaderInvocation())),
                    "Generated path replay has exact endpoints");
        }
    }

    /** A rejected fixed batch must not path-compress or otherwise mutate the graph. */
    private static void testRejectedFixedBatchIsPure() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        OnePortSchema integer = new OnePortSchema(GraphType.INT);
        BagPortSchema equalityBag = new BagPortSchema(ArityPolicy.exact(2), integer);
        ContainerLawCertificate equalityC = AlloyLawRegistry.issue(
                profile,
                Opcode.EQUALS,
                "ALLOY/EQUALS",
                GraphType.BOOL,
                PortPath.at(0),
                equalityBag,
                ContainerLawCertificate.Law.COMMUTATIVITY);
        InstantiatedOperator equality = OperatorDeclaration.monomorphic(
                "ALLOY/EQUALS",
                Collections.singletonList(equalityBag),
                GraphType.BOOL,
                lawMap(ContainerLawDeclaration.certified(
                        equalityBag, Collections.singletonList(equalityC))),
                null).instantiateMonomorphic();

        TypedEClassInterface bottom = emptyClass(6_100, GraphType.INT);
        TypedEClassInterface middle = emptyClass(6_101, GraphType.INT);
        TypedEClassInterface leader = emptyClass(6_102, GraphType.INT);
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(bottom);
        graph.registerEmptyClassForPhaseF(middle);
        graph.registerEmptyClassForPhaseF(leader);
        TypedInvocation middleInBottom = TypedInvocation.identity(middle);
        TypedInvocation leaderInMiddle = TypedInvocation.identity(leader);
        graph.unionCertified(new ParentEdgeCertificate(
                bottom,
                middleInBottom,
                inputEquation(bottom, middleInBottom, "fixed-preflight", 0)));
        graph.unionCertified(new ParentEdgeCertificate(
                middle,
                leaderInMiddle,
                inputEquation(middle, leaderInMiddle, "fixed-preflight", 1)));
        graph.rebuild();
        check(graph.findWithoutCompressionForTesting(TypedInvocation.identity(bottom))
                        .parentPath().steps().size() == 2,
                "Fixed-batch purity fixture retains a two-edge parent chain");

        TypedSlotContext empty = TypedSlotContext.empty();
        CertifiedContainerConstruction construction =
                TypedENode.constructContainerCertified(
                        equality,
                        PortPath.at(0),
                        empty,
                        Arrays.asList(
                                OnePort.invocation(
                                        empty, TypedInvocation.identity(bottom)),
                                OnePort.invocation(
                                        empty, TypedInvocation.identity(leader))),
                        profile);
        CanonicalizationResult canonical = ProductionGraphCanonicalizer.instance()
                .canonicalizeWithoutCompression(
                        graph, construction.node().inExactSupportContext());
        TypedEClassInterface owner = new TypedEClassInterface(
                EClassId.of(6_103),
                GraphType.BOOL,
                canonical.effectiveSupport());
        ShapeWitness witness = new ShapeWitness(
                canonical.shape().exactSlots(),
                canonical.effectiveSupport(),
                owner.exposedSlots(),
                canonical.witness());
        TypedEClassRecord record = TypedEClassRecord.of(
                owner,
                Collections.singletonMap(canonical.shape(), witness),
                TypedSymmetryGroup.identity(owner.exposedSlots()));
        InputEquationCertificate malformedEquation = new InputEquationCertificate(
                CertificateOrigin.inputEquation(
                        "certificate-test", "malformed-fixed-batch", 0),
                TypedCertificateEndpoint.node(construction.node()),
                TypedCertificateEndpoint.invocation(TypedInvocation.identity(owner)));

        StructuralKey before = graph.stateStructuralKey();
        expectThrows(IllegalArgumentException.class, () ->
                graph.admitFixedBatchRecordCertified(
                        record,
                        Collections.singletonMap(
                                canonical.shape(), malformedEquation),
                        Collections.singletonMap(
                                canonical.shape(), construction.certificate())));
        check(before.equals(graph.stateStructuralKey()),
                "Rejected fixed-batch preflight leaves the complete graph state unchanged");
        check(graph.findWithoutCompressionForTesting(TypedInvocation.identity(bottom))
                        .parentPath().steps().size() == 2,
                "Rejected fixed-batch preflight does not path-compress union-find state");
        check(!graph.classes().containsKey(owner.id()),
                "Rejected fixed-batch preflight does not install its proposed owner");
    }

    /** Mission sections 16 and 25: proof algebra and raw mutation paths stay closed. */
    private static void testMutationBoundary() {
        check(TypedEqualityCertificate.class.isSealed(),
                "Certificate implementations form a closed derivation algebra");
        for (Constructor<?> constructor : ParentStep.class.getDeclaredConstructors()) {
            check(!Modifier.isPublic(constructor.getModifiers()),
                    "No public ParentStep constructor bypasses parent-edge certification");
        }
        for (Method method : TypedSlottedPortEGraph.class.getDeclaredMethods()) {
            if (method.getName().equals("linkLeadersForPhaseD")
                    || method.getName().equals("registerRecordForPhaseD")
                    || method.getName().equals("extractLeaderKernel")
                    || method.getName().equals("admitFixedBatchRecordCertified")) {
                check(!Modifier.isPublic(method.getModifiers()),
                        "Already-authorized replay is not a public source-admission path");
            }
        }
        check(!Modifier.isPublic(LeaderKernelExtractor.class.getModifiers())
                        && !Modifier.isPublic(
                                ExhaustiveGraphCanonicalizer.class.getModifiers()),
                "Internal kernel and reference canonicalizers are package-confined");
        try {
            check(!Modifier.isPublic(ProductionGraphCanonicalizer.class
                            .getDeclaredMethod("instance").getModifiers()),
                    "The production canonicalizer singleton is available only to the graph facade");
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
        check(new TypedSlottedPortEGraph().certificateMode()
                        == GraphCertificateMode.REQUIRED,
                "The public graph defaults to certificate-enforcing mode");
        check(CertifiedSemanticArtifact.class.getConstructors().length == 0,
                "Public callers cannot mint complete semantic artifacts");
        try {
            check(!Modifier.isPublic(ConstructionSourceLedger.class
                            .getDeclaredMethod(
                                    "builder", SemanticProfile.class)
                            .getModifiers())
                            && !Modifier.isPublic(
                                    ConstructionSourceLedger.Builder.class
                                            .getModifiers()),
                    "Only the package-confined certified adapter can mint source ledgers");
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private static BinderCoordinateDescriptor coordinate(
            TypedSlot slot,
            StructuralKey domain,
            String quantifier,
            String multiplicity,
            int disjointnessClass) {
        return new BinderCoordinateDescriptor(
                slot,
                domain,
                quantifier,
                multiplicity,
                disjointnessClass,
                TypedSlotContext.empty());
    }

    private static TypedEClassInterface emptyClass(long id, GraphType outputType) {
        return new TypedEClassInterface(
                EClassId.of(id), outputType, TypedSlotContext.empty());
    }

    private static InputEquationCertificate inputEquation(
            TypedEClassInterface child,
            TypedInvocation parent,
            String equationId,
            int ordinal) {
        return new InputEquationCertificate(
                CertificateOrigin.inputEquation("test-model", equationId, ordinal),
                TypedCertificateEndpoint.eclassWitness(child),
                TypedCertificateEndpoint.invocation(parent));
    }

    private static SymmetryCertificate symmetryAxiom(
            TypedEClassInterface eclass,
            TypedPermutation permutation,
            String equationId,
            int ordinal) {
        TypedInvocation left = TypedInvocation.identity(eclass);
        TypedInvocation right = new TypedInvocation(eclass, permutation);
        return new SymmetryCertificate(
                left,
                right,
                InputEquationCertificate.betweenInvocations(
                        CertificateOrigin.rewriteAxiom(
                                "test-rules", equationId, ordinal),
                        left,
                        right));
    }

    private static Map<PortPath, ContainerLawDeclaration> lawMap(
            ContainerLawDeclaration declaration) {
        Map<PortPath, ContainerLawDeclaration> result = new LinkedHashMap<>();
        result.put(PortPath.at(0), declaration);
        return result;
    }

    private static Map<TypedSlot, TypedSlot> mapOf(TypedSlot... entries) {
        if ((entries.length & 1) != 0) {
            throw new IllegalArgumentException("mapOf requires key/value pairs");
        }
        Map<TypedSlot, TypedSlot> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> expected,
            Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable throwable) {
            if (expected.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName()
                            + " but got " + throwable,
                    throwable);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}

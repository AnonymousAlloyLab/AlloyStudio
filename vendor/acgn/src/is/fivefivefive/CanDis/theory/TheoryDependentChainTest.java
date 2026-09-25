package is.fivefivefive.CanDis.theory;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.ast.Sig.PrimSig;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.Multigraph;

/** Axiomatic regressions for the ordered dependent JOIN/ARROW Seq carrier. */
public final class TheoryDependentChainTest {
    private static int checks;

    private TheoryDependentChainTest() {
    }

    public static void main(String[] args) throws Exception {
        runAssertions();
        System.out.println("TheoryDependentChainTest passed: " + checks + " checks");
    }

    static int runAssertions() throws Exception {
        checks = 0;
        testJoinReassociation();
        testJoinMultiplicity();
        testSubtypeJoinBoundary();
        testCorrelatedDependentTypeDag();
        testAllDisjointJoinProducesTypedEmptySequence();
        testSubsetSignatureCarrierCorrespondence();
        testConsumedBoundaryCannotHideConflictingHierarchy();
        testExplicitUnivChainsAndForeignCarrierRejection();
        testConflictingSubtypeParentsRejected();
        testIdenticalForeignModulesRejected();
        testOccurrenceStoreRejectsForeignAuthorityCollision();
        testSubtypeStackIsSingleValued();
        testConsumedAuthorityCannotHideForeignModule();
        testSequenceBuiltinAncestryAccepted();
        testUnaryInteriorJoinHasNoReassociationLicense();
        testArrowReassociationAndMultiplicity();
        testExactTypingRejectsUnsoundChains();
        return checks;
    }

    private static void testJoinReassociation() {
        GraphType a = GraphType.constructor("AlloySig:A");
        GraphType b = GraphType.constructor("AlloySig:B");
        GraphType c = GraphType.constructor("AlloySig:C");
        GraphType d = GraphType.constructor("AlloySig:D");
        ChainFixture fixture = fixture(
                GraphType.relation(a, b),
                GraphType.relation(b, c),
                GraphType.relation(c, d));

        DependentChainApplication leftAssociated = new DependentChainApplication(
                DependentChainKind.JOIN,
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        fixture.first(),
                        fixture.second()),
                fixture.third());
        DependentChainApplication rightAssociated = new DependentChainApplication(
                DependentChainKind.JOIN,
                fixture.first(),
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        fixture.second(),
                        fixture.third()));

        check(leftAssociated.outputType().equals(GraphType.relation(a, d)),
                "JOIN derives the exact outer relation columns");
        check(leftAssociated.outputType().equals(rightAssociated.outputType()),
                "Both JOIN associations derive one exact result type");
        CertifiedDependentChainConstruction left = construct(leftAssociated);
        CertifiedDependentChainConstruction right = construct(rightAssociated);
        check(left.node().equals(right.node()),
                "JOIN reassociation reaches one ordered dependent Seq node");
        check(!left.certificate().leftEndpoint().equals(
                        right.certificate().leftEndpoint())
                        && left.certificate().rightEndpoint().equals(
                                right.certificate().rightEndpoint()),
                "Distinct source associations retain distinct proofs to one target");
        assertDependentSequence(left, fixture.ports());
        check(left.certificate().theoryIndex().equals(
                        DependentChainTheory.proofIndex(
                                DependentChainKind.JOIN,
                                fixture.types(),
                                GraphType.relation(a, d))),
                "JOIN certificate is indexed by every operand and result type");
    }

    private static void testArrowReassociationAndMultiplicity() {
        GraphType a = GraphType.constructor("AlloySig:A");
        GraphType b = GraphType.constructor("AlloySig:B");
        ChainFixture fixture = fixture(
                GraphType.relation(a),
                GraphType.relation(b),
                GraphType.relation(b));
        DependentChainApplication leftAssociated = new DependentChainApplication(
                DependentChainKind.ARROW,
                new DependentChainApplication(
                        DependentChainKind.ARROW,
                        fixture.first(),
                        fixture.second()),
                fixture.second());
        DependentChainApplication rightAssociated = new DependentChainApplication(
                DependentChainKind.ARROW,
                fixture.first(),
                new DependentChainApplication(
                        DependentChainKind.ARROW,
                        fixture.second(),
                        fixture.second()));
        CertifiedDependentChainConstruction left = construct(leftAssociated);
        CertifiedDependentChainConstruction right = construct(rightAssociated);
        check(left.node().equals(right.node()),
                "ARROW reassociation reaches one ordered dependent Seq node");
        check(left.node().outputType().equals(GraphType.relation(a, b, b)),
                "ARROW concatenates exact relation columns in source order");
        SeqPort sequence = (SeqPort) left.node().ports().get(0);
        check(sequence.elements().size() == 3
                        && sequence.elements().get(1).equals(
                                sequence.elements().get(2)),
                "Dependent Seq retains repeated operand occurrences");
        check(!left.node().operator().usesFlatConstruction(),
                "Dependent ARROW does not claim a homogeneous flat license");
        ContainerLawDeclaration declaration = left.node().operator()
                .lawForPath(PortPath.at(0));
        check(declaration.kind() == ContainerLawDeclaration.Kind.SEQ
                        && !declaration.associative()
                        && !declaration.commutative()
                        && !declaration.idempotent()
                        && !declaration.hasUnit(),
                "The Seq carrier itself has no generic A/C/I/U authority");
    }

    private static void testJoinMultiplicity() {
        GraphType atom = GraphType.constructor("AlloySig:A");
        GraphType binary = GraphType.relation(atom, atom);
        ChainFixture fixture = fixture(binary, binary, binary);
        DependentChainApplication repeated = new DependentChainApplication(
                DependentChainKind.JOIN,
                fixture.first(),
                fixture.first());
        CertifiedDependentChainConstruction construction = construct(repeated);
        SeqPort sequence = (SeqPort) construction.node().ports().get(0);
        check(sequence.elements().equals(List.of(
                        fixture.first().port(), fixture.first().port())),
                "Dependent JOIN Seq retains a repeated source occurrence");
        check(construction.node().outputType().equals(binary),
                "Repeated binary JOIN derives the exact boundary result type");
    }

    private static void testSubtypeJoinBoundary() throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig Product {}\n"
                        + "sig Component extends Product {}\n"
                        + "sig Position {}\n"
                        + "sig Unrelated {}\n");
        PrimSig productSig = signature(module, "Product");
        PrimSig componentSig = signature(module, "Component");
        PrimSig positionSig = signature(module, "Position");
        PrimSig unrelatedSig = signature(module, "Unrelated");
        GraphType product = GraphType.constructor("AlloySig:Product");
        GraphType component = GraphType.constructor("AlloySig:Component");
        GraphType position = GraphType.constructor("AlloySig:Position");
        GraphType leftType = GraphType.relation(product);
        GraphType rightType = GraphType.relation(component, position);
        TypedSlot leftSlot = TypedSlot.source(leftType, 70_020);
        TypedSlot rightSlot = TypedSlot.source(rightType, 70_021);
        TypedSlotContext context = TypedSlotContext.of(leftSlot, rightSlot);
        DependentChainLeaf left = new DependentChainLeaf(
                OnePort.slot(context, leftSlot),
                leftType,
                AlloyTypeBridge.dependentColumns(
                        ExactAlloyType.fromParser(productSig.type(), module)));
        DependentChainLeaf right = new DependentChainLeaf(
                OnePort.slot(context, rightSlot),
                rightType,
                AlloyTypeBridge.dependentColumns(ExactAlloyType.fromParser(
                        componentSig.type().product(positionSig.type()), module)));
        DependentChainApplication application = new DependentChainApplication(
                DependentChainKind.JOIN, left, right);
        check(application.outputType().equals(GraphType.relation(position)),
                "subtype-compatible JOIN derives the exact surviving columns");
        check(application.boundaryCorrespondence().rule()
                        == DependentBoundaryCorrespondence.Rule.RIGHT_SUBTYPE_OF_LEFT,
                "subtype-compatible JOIN records its correspondence direction");
        check(application.boundaryCorrespondence().witnessPath().equals(
                        List.of(component, product)),
                "subtype-compatible JOIN records the shortest direct-parent witness");
        CertifiedDependentChainConstruction construction = construct(application);
        check(construction.node().ports().get(0) instanceof SeqPort
                        && ((SeqPort) construction.node().ports().get(0))
                                .elements().size() == 2,
                "subtype-compatible JOIN remains an ordered dependent Seq");

        GraphType unrelated = GraphType.constructor("AlloySig:Unrelated");
        TypedSlot unrelatedSlot = TypedSlot.source(
                GraphType.relation(unrelated, position), 70_022);
        DependentChainLeaf unrelatedLeaf = new DependentChainLeaf(
                OnePort.slot(TypedSlotContext.of(leftSlot, unrelatedSlot),
                        unrelatedSlot),
                GraphType.relation(unrelated, position),
                AlloyTypeBridge.dependentColumns(ExactAlloyType.fromParser(
                        unrelatedSig.type().product(positionSig.type()), module)));
        DependentChainLeaf leftInUnrelatedContext = new DependentChainLeaf(
                OnePort.slot(TypedSlotContext.of(leftSlot, unrelatedSlot), leftSlot),
                leftType,
                left.outputColumns());
        DependentBoundaryCorrespondence disjoint =
                DependentBoundaryCorrespondence.derive(
                        leftInUnrelatedContext.outputColumns().get(0),
                        unrelatedLeaf.outputColumns().get(0));
        check(disjoint.rule()
                        == DependentBoundaryCorrespondence.Rule.DISJOINT_BRANCHES
                        && !disjoint.overlaps()
                        && disjoint.commonAncestor().equals(
                                GraphType.constructor("AlloySig:univ")),
                "distinct authenticated top-level PrimSig branches are disjoint at univ");
        DependentChainApplication disjointApplication =
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        leftInUnrelatedContext,
                        unrelatedLeaf);
        check(disjointApplication.outputType().equals(
                        AlloyTypeBridge.emptyRelation(1))
                        && disjointApplication.outputTypeDag()
                                .alternatives().isEmpty(),
                "a disjoint positive-arity JOIN derives a typed empty family");
        assertDependentSequence(
                construct(disjointApplication),
                List.of(leftInUnrelatedContext.port(), unrelatedLeaf.port()));
    }

    private static void testCorrelatedDependentTypeDag() throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig P {}\n"
                        + "sig A extends P {}\n"
                        + "sig B extends P {}\n"
                        + "sig C {}\n");
        DependentTypeDag a = dag(module, "A");
        DependentTypeDag b = dag(module, "B");
        DependentTypeDag p = dag(module, "P");
        DependentTypeDag c = dag(module, "C");

        DependentTypeDag siblingUnion = DependentTypeDag.union(List.of(a, b));
        check(siblingUnion.alternatives().size() == 2,
                "a union retains distinct sibling PrimSig alternatives");
        check(siblingUnion.commonAncestorType().orElseThrow().equals(
                        GraphType.relation(
                                GraphType.constructor("AlloySig:P"))),
                "a union records its first authenticated common ancestor");
        check(DependentTypeDag.union(List.of(a, p)).equals(p),
                "a union antichain absorbs only an authenticated subtype alternative");
        check(DependentTypeDag.intersection(List.of(a, p)).equals(a),
                "intersection retains the authenticated more-specific carrier");
        DependentTypeDag disjointIntersection =
                DependentTypeDag.intersection(List.of(a, b));
        check(disjointIntersection.alternatives().isEmpty()
                        && disjointIntersection.relationType().equals(
                                AlloyTypeBridge.emptyRelation(1)),
                "a proven-disjoint intersection retains its positive-arity empty type");

        DependentTypeDag correlated = dag(
                module, "(A->B) + (B->A)");
        check(correlated.alternatives().size() == 2
                        && correlated.alternatives().stream().noneMatch(
                                product -> product.stream().anyMatch(
                                        column -> "AlloyRelationUnion".equals(
                                                column.exactColumn().symbol()))),
                "a relation union retains correlated products instead of widening columns");

        DependentTypeDag arrowProduct = DependentTypeDag.combine(
                DependentChainKind.ARROW,
                siblingUnion,
                siblingUnion).result();
        check(arrowProduct.alternatives().size() == 4,
                "ARROW takes the correlated Cartesian product of alternatives");

        DependentTypeDag left = dag(module, "(A->A) + (B->B)");
        DependentTypeDag right = dag(module, "(A->C) + (B->C)");
        DependentTypeDag.ChainCombination joined = DependentTypeDag.combine(
                DependentChainKind.JOIN, left, right);
        long overlaps = joined.cases().stream()
                .filter(proof -> proof.decision()
                        == DependentTypeDag.CombinationDecision.JOIN_OVERLAP)
                .count();
        long disjoint = joined.cases().stream()
                .filter(proof -> proof.decision()
                        == DependentTypeDag.CombinationDecision.JOIN_DISJOINT)
                .count();
        check(joined.cases().size() == 4 && overlaps == 2 && disjoint == 2,
                "JOIN records every alternative pair, including omitted disjoint branches");
        check(joined.result().alternatives().size() == 2
                        && joined.result().relationType().equals(AlloyTypeBridge.graphType(
                                ExactAlloyType.fromParser(
                                        CompUtil.parseOneExpression_fromString(
                                                module,
                                                "((A->A)+(B->B)).((A->C)+(B->C))")
                                                .type(),
                                        module))),
                "JOIN derives the parser's correlated result family without widening");

        DependentBoundaryCorrespondence onlyUniv =
                DependentBoundaryCorrespondence.derive(
                        p.alternatives().get(0).get(0),
                        c.alternatives().get(0).get(0));
        check(!onlyUniv.overlaps()
                        && onlyUniv.commonAncestor().equals(
                                GraphType.constructor("AlloySig:univ")),
                "sharing only univ proves disjointness and never overlap");

        GraphType leftType = left.relationType();
        GraphType rightType = right.relationType();
        TypedSlot leftSlot = TypedSlot.source(leftType, 70_030);
        TypedSlot rightSlot = TypedSlot.source(rightType, 70_031);
        TypedSlotContext context = TypedSlotContext.of(leftSlot, rightSlot);
        DependentChainApplication application = new DependentChainApplication(
                DependentChainKind.JOIN,
                new DependentChainLeaf(
                        OnePort.slot(context, leftSlot), left),
                new DependentChainLeaf(
                        OnePort.slot(context, rightSlot), right));
        CertifiedDependentChainConstruction construction = construct(application);
        check(application.combinationCases().size() == 4
                        && construction.node().ports().get(0) instanceof SeqPort
                        && ((SeqPort) construction.node().ports().get(0))
                                .elements().size() == 2,
                "a correlated-family JOIN remains an ordered duplicate-preserving Seq");

        TypedSlot middleSlot = TypedSlot.source(leftType, 70_032);
        TypedSlot thirdSlot = TypedSlot.source(rightType, 70_033);
        TypedSlotContext chainContext = TypedSlotContext.of(
                leftSlot, middleSlot, thirdSlot);
        DependentChainLeaf first = new DependentChainLeaf(
                OnePort.slot(chainContext, leftSlot), left);
        DependentChainLeaf middle = new DependentChainLeaf(
                OnePort.slot(chainContext, middleSlot), left);
        DependentChainLeaf third = new DependentChainLeaf(
                OnePort.slot(chainContext, thirdSlot), right);
        DependentChainApplication leftAssociated = new DependentChainApplication(
                DependentChainKind.JOIN,
                new DependentChainApplication(
                        DependentChainKind.JOIN, first, middle),
                third);
        DependentChainApplication rightAssociated = new DependentChainApplication(
                DependentChainKind.JOIN,
                first,
                new DependentChainApplication(
                        DependentChainKind.JOIN, middle, third));
        check(leftAssociated.outputTypeDag().equals(
                        rightAssociated.outputTypeDag())
                        && construct(leftAssociated).node().equals(
                                construct(rightAssociated).node()),
                "guarded correlated-family JOIN reassociation reaches one typed Seq target");
    }

    private static DependentTypeDag dag(
            CompModule module,
            String expression) throws Exception {
        return AlloyTypeBridge.dependentTypeDag(ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(module, expression).type(),
                module));
    }

    private static void testSubsetSignatureCarrierCorrespondence()
            throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig P {}\n"
                        + "sig X in P {}\n"
                        + "sig A extends P {}\n");
        DependentTypeDag subset = dag(module, "X");
        DependentTypeDag child = dag(module, "A");
        GraphType p = GraphType.constructor("AlloySig:P");
        GraphType a = GraphType.constructor("AlloySig:A");
        GraphType univ = GraphType.constructor("AlloySig:univ");
        check(subset.relationType().equals(GraphType.relation(p))
                        && subset.alternatives().get(0).get(0).ancestry()
                                .equals(List.of(p, univ)),
                "a subset signature uses the parser's declared carrier instead of minting a nominal subtype edge");
        check(child.relationType().equals(GraphType.relation(a))
                        && child.alternatives().get(0).get(0).ancestry()
                                .equals(List.of(a, p, univ)),
                "an extending primitive signature retains its authenticated nominal parent path");
    }

    private static void testConsumedBoundaryCannotHideConflictingHierarchy()
            throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig P {}\n"
                        + "sig Q {}\n"
                        + "sig X extends P {}\n"
                        + "sig L {}\n"
                        + "sig R {}\n");
        ExactAlloyType authority = ExactAlloyType.fromParser(
                signature(module, "X").type(), module);
        GraphType x = GraphType.constructor("AlloySig:X");
        GraphType p = GraphType.constructor("AlloySig:P");
        GraphType q = GraphType.constructor("AlloySig:Q");
        GraphType univ = GraphType.constructor("AlloySig:univ");
        DependentColumnEvidence xUnderP = malformedColumnForAttack(
                x, List.of(x, p, univ), authority);
        DependentColumnEvidence xUnderQ = malformedColumnForAttack(
                x, List.of(x, q, univ), authority);
        DependentColumnEvidence l = DependentColumnEvidence.exact(
                GraphType.constructor("AlloySig:L"));
        DependentColumnEvidence r = DependentColumnEvidence.exact(
                GraphType.constructor("AlloySig:R"));
        DependentTypeDag left = DependentTypeDag.exactAlternative(
                GraphType.relation(l.exactColumn(), x), List.of(l, xUnderP));
        DependentTypeDag right = DependentTypeDag.exactAlternative(
                GraphType.relation(x, r.exactColumn()), List.of(xUnderQ, r));
        expectThrows(IllegalArgumentException.class, () ->
                DependentTypeDag.combine(
                        DependentChainKind.JOIN, left, right));
    }

    private static void testAllDisjointJoinProducesTypedEmptySequence()
            throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig A {}\nsig B {}\nsig C {}\nsig D {}\n");
        DependentTypeDag leftDag = dag(module, "A->B");
        DependentTypeDag rightDag = dag(module, "C->D");
        DependentTypeDag thirdDag = dag(module, "D->A");
        TypedSlot leftSlot = TypedSlot.source(leftDag.relationType(), 70_050);
        TypedSlot rightSlot = TypedSlot.source(rightDag.relationType(), 70_051);
        TypedSlot thirdSlot = TypedSlot.source(thirdDag.relationType(), 70_052);
        TypedSlotContext context = TypedSlotContext.of(
                leftSlot, rightSlot, thirdSlot);
        DependentChainLeaf left = new DependentChainLeaf(
                OnePort.slot(context, leftSlot), leftDag);
        DependentChainLeaf right = new DependentChainLeaf(
                OnePort.slot(context, rightSlot), rightDag);
        DependentChainLeaf third = new DependentChainLeaf(
                OnePort.slot(context, thirdSlot), thirdDag);

        DependentChainApplication binary = new DependentChainApplication(
                DependentChainKind.JOIN, left, right);
        check(binary.outputType().equals(AlloyTypeBridge.emptyRelation(2))
                        && binary.outputTypeDag().alternatives().isEmpty(),
                "an all-disjoint binary JOIN derives one positive-arity empty family");
        check(binary.combinationCases().size() == 1
                        && binary.combinationCases().get(0).decision()
                                == DependentTypeDag.CombinationDecision.JOIN_DISJOINT,
                "an all-disjoint JOIN retains its complete disjoint case matrix");
        CertifiedDependentChainConstruction binaryConstruction = construct(binary);
        assertDependentSequence(
                binaryConstruction, List.of(left.port(), right.port()));

        DependentChainApplication variadic = new DependentChainApplication(
                DependentChainKind.JOIN, binary, third);
        check(variadic.outputType().equals(AlloyTypeBridge.emptyRelation(2)),
                "a later associative JOIN preserves the typed empty result arity");
        assertDependentSequence(
                construct(variadic),
                List.of(left.port(), right.port(), third.port()));

        DependentTypeDag emptyDag = DependentTypeDag.empty(2);
        TypedSlot emptySlot = TypedSlot.source(
                emptyDag.relationType(), 70_053);
        TypedSlotContext emptyInteriorContext = TypedSlotContext.of(
                leftSlot, emptySlot, thirdSlot);
        DependentChainLeaf emptyInteriorLeft = new DependentChainLeaf(
                OnePort.slot(emptyInteriorContext, leftSlot), leftDag);
        DependentChainLeaf emptyInterior = new DependentChainLeaf(
                OnePort.slot(emptyInteriorContext, emptySlot), emptyDag);
        DependentChainLeaf emptyInteriorRight = new DependentChainLeaf(
                OnePort.slot(emptyInteriorContext, thirdSlot), thirdDag);
        DependentChainApplication emptyInteriorChain =
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        new DependentChainApplication(
                                DependentChainKind.JOIN,
                                emptyInteriorLeft,
                                emptyInterior),
                        emptyInteriorRight);
        check(emptyInteriorChain.outputType().equals(
                        AlloyTypeBridge.emptyRelation(2)),
                "a typed-empty interior operand satisfies the arity-two JOIN guard");
        assertDependentSequence(
                construct(emptyInteriorChain),
                List.of(
                        emptyInteriorLeft.port(),
                        emptyInterior.port(),
                        emptyInteriorRight.port()));
    }

    private static DependentColumnEvidence malformedColumnForAttack(
            GraphType exact,
            List<GraphType> ancestry,
            ExactAlloyType authority) throws Exception {
        Constructor<DependentColumnEvidence> constructor =
                DependentColumnEvidence.class.getDeclaredConstructor(
                        GraphType.class, List.class, ExactAlloyType.class);
        constructor.setAccessible(true);
        return constructor.newInstance(exact, ancestry, authority);
    }

    private static void testExplicitUnivChainsAndForeignCarrierRejection()
            throws Exception {
        GraphType a = GraphType.constructor("AlloySig:A");
        GraphType b = GraphType.constructor("AlloySig:B");
        GraphType c = GraphType.constructor("AlloySig:C");
        GraphType univ = GraphType.constructor("AlloySig:univ");
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig A {}\nsig B {}\nsig C {}\n");
        DependentTypeDag aSet = dag(module, "A");
        DependentTypeDag cSet = dag(module, "C");
        DependentTypeDag trans = dag(module, "A->B->C");
        DependentTypeDag universe = dag(module, "univ");

        DependentBoundaryCorrespondence exact =
                DependentBoundaryCorrespondence.derive(
                        universe.alternatives().get(0).get(0),
                        universe.alternatives().get(0).get(0));
        DependentBoundaryCorrespondence concreteToUniv =
                DependentBoundaryCorrespondence.derive(
                        aSet.alternatives().get(0).get(0),
                        universe.alternatives().get(0).get(0));
        DependentBoundaryCorrespondence univToConcrete =
                DependentBoundaryCorrespondence.derive(
                        universe.alternatives().get(0).get(0),
                        aSet.alternatives().get(0).get(0));
        check(exact.rule() == DependentBoundaryCorrespondence.Rule.EXACT,
                "parser-provided univ is an exact dependent boundary");
        check(concreteToUniv.rule()
                        == DependentBoundaryCorrespondence.Rule.LEFT_SUBTYPE_OF_RIGHT
                        && concreteToUniv.witnessPath().equals(List.of(a, univ)),
                "a concrete-to-univ boundary consumes its parser ancestry");
        check(univToConcrete.rule()
                        == DependentBoundaryCorrespondence.Rule.RIGHT_SUBTYPE_OF_LEFT
                        && univToConcrete.witnessPath().equals(List.of(a, univ)),
                "a univ-to-concrete boundary consumes the reverse correspondence");

        TypedSlot firstSlot = TypedSlot.source(aSet.relationType(), 70_040);
        TypedSlot transSlot = TypedSlot.source(trans.relationType(), 70_041);
        TypedSlot univSlot = TypedSlot.source(universe.relationType(), 70_042);
        TypedSlot lastSlot = TypedSlot.source(cSet.relationType(), 70_043);
        TypedSlotContext context = TypedSlotContext.of(
                firstSlot, transSlot, univSlot, lastSlot);
        DependentChainLeaf first = new DependentChainLeaf(
                OnePort.slot(context, firstSlot), aSet);
        DependentChainLeaf relation = new DependentChainLeaf(
                OnePort.slot(context, transSlot), trans);
        DependentChainLeaf explicitUniv = new DependentChainLeaf(
                OnePort.slot(context, univSlot), universe);
        DependentChainLeaf last = new DependentChainLeaf(
                OnePort.slot(context, lastSlot), cSet);

        DependentChainApplication rightUnivLeftAssociated =
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        new DependentChainApplication(
                                DependentChainKind.JOIN, first, relation),
                        explicitUniv);
        DependentChainApplication rightUnivRightAssociated =
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        first,
                        new DependentChainApplication(
                                DependentChainKind.JOIN, relation, explicitUniv));
        check(rightUnivLeftAssociated.outputType().equals(GraphType.relation(b))
                        && rightUnivLeftAssociated.outputType().equals(
                                rightUnivRightAssociated.outputType())
                        && construct(rightUnivLeftAssociated).node().equals(
                                construct(rightUnivRightAssociated).node()),
                "(x.trans).univ and x.(trans.univ) flatten to one typed Seq");
        assertDependentSequence(
                construct(rightUnivLeftAssociated),
                List.of(first.port(), relation.port(), explicitUniv.port()));

        DependentChainApplication leftUnivLeftAssociated =
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        new DependentChainApplication(
                                DependentChainKind.JOIN, explicitUniv, relation),
                        last);
        DependentChainApplication leftUnivRightAssociated =
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        explicitUniv,
                        new DependentChainApplication(
                                DependentChainKind.JOIN, relation, last));
        check(leftUnivLeftAssociated.outputType().equals(GraphType.relation(b))
                        && leftUnivLeftAssociated.outputType().equals(
                                leftUnivRightAssociated.outputType())
                        && construct(leftUnivLeftAssociated).node().equals(
                                construct(leftUnivRightAssociated).node()),
                "(univ.trans).x and univ.(trans.x) flatten to one typed Seq");
        assertDependentSequence(
                construct(leftUnivLeftAssociated),
                List.of(explicitUniv.port(), relation.port(), last.port()));

        DependentChainApplication arrow = new DependentChainApplication(
                DependentChainKind.ARROW, explicitUniv, first);
        check(arrow.outputType().equals(GraphType.relation(univ, a)),
                "ARROW accepts parser-provided univ as an exact ordered column");
        expectThrows(IllegalStateException.class,
                () -> AlloyTypeBridge.graphType(ExactAlloyType.from(null)));
        expectThrows(IllegalArgumentException.class, () ->
                DependentTypeDag.exactRelation(GraphType.relation(
                        GraphType.constructor("BogusCarrier"))));
        expectThrows(IllegalArgumentException.class, () ->
                DependentColumnEvidence.exact(
                        GraphType.constructor("AlloySig:")));
    }

    private static void testConflictingSubtypeParentsRejected() throws Exception {
        CompModule leftModule = CompUtil.parseEverything_fromString(null,
                "sig P {}\nsig X extends P {}\n");
        CompModule rightModule = CompUtil.parseEverything_fromString(null,
                "sig Q {}\nsig X extends Q {}\n");
        PrimSig leftX = signature(leftModule, "X");
        PrimSig rightX = signature(rightModule, "X");
        GraphType x = GraphType.constructor("AlloySig:X");
        GraphType relation = GraphType.relation(x);
        TypedSlot leftSlot = TypedSlot.source(relation, 70_023);
        TypedSlot rightSlot = TypedSlot.source(relation, 70_024);
        TypedSlotContext context = TypedSlotContext.of(leftSlot, rightSlot);
        DependentChainLeaf left = new DependentChainLeaf(
                OnePort.slot(context, leftSlot),
                relation,
                AlloyTypeBridge.dependentColumns(
                        ExactAlloyType.fromParser(leftX.type(), leftModule)));
        DependentChainLeaf right = new DependentChainLeaf(
                OnePort.slot(context, rightSlot),
                relation,
                AlloyTypeBridge.dependentColumns(
                        ExactAlloyType.fromParser(rightX.type(), rightModule)));
        expectThrows(IllegalArgumentException.class, () ->
                new DependentChainApplication(
                        DependentChainKind.ARROW, left, right));
    }

    private static void testIdenticalForeignModulesRejected() throws Exception {
        CompModule leftModule = CompUtil.parseEverything_fromString(null,
                "sig P {}\nsig X extends P {}\n");
        CompModule rightModule = CompUtil.parseEverything_fromString(null,
                "sig P {}\nsig X extends P {}\n");
        PrimSig leftX = signature(leftModule, "X");
        PrimSig rightX = signature(rightModule, "X");
        GraphType relation = GraphType.relation(
                GraphType.constructor("AlloySig:X"));
        TypedSlot leftSlot = TypedSlot.source(relation, 70_025);
        TypedSlot rightSlot = TypedSlot.source(relation, 70_026);
        TypedSlotContext context = TypedSlotContext.of(leftSlot, rightSlot);
        DependentChainLeaf left = new DependentChainLeaf(
                OnePort.slot(context, leftSlot),
                relation,
                AlloyTypeBridge.dependentColumns(
                        ExactAlloyType.fromParser(leftX.type(), leftModule)));
        DependentChainLeaf right = new DependentChainLeaf(
                OnePort.slot(context, rightSlot),
                relation,
                AlloyTypeBridge.dependentColumns(
                        ExactAlloyType.fromParser(rightX.type(), rightModule)));
        expectThrows(IllegalArgumentException.class, () ->
                new DependentChainApplication(
                        DependentChainKind.ARROW, left, right));
    }

    private static void testOccurrenceStoreRejectsForeignAuthorityCollision()
            throws Exception {
        CompModule leftModule = CompUtil.parseEverything_fromString(null,
                "sig P {}\nsig X extends P {}\n");
        CompModule rightModule = CompUtil.parseEverything_fromString(null,
                "sig Q {}\nsig X extends Q {}\n");
        ExactAlloyType left = ExactAlloyType.fromParser(
                signature(leftModule, "X").type(), leftModule);
        ExactAlloyType right = ExactAlloyType.fromParser(
                signature(rightModule, "X").type(), rightModule);
        check(left.equals(right),
                "value equality remains independent of runtime parser authority");
        check(!left.sameOccurrenceEvidenceAs(right),
                "occurrence equality retains ancestry and parser-module identity");
        DependentTypeDag leftDag = AlloyTypeBridge.dependentTypeDag(left);
        DependentTypeDag rightDag = AlloyTypeBridge.dependentTypeDag(right);
        check(!leftDag.equals(rightDag)
                        && !leftDag.sameOccurrenceEvidenceAs(rightDag),
                "dependent DAG occurrence comparison retains distinct ancestry paths");
        CompModule sameLabelsModule = CompUtil.parseEverything_fromString(null,
                "sig P {}\nsig X extends P {}\n");
        ExactAlloyType sameLabelsForeign = ExactAlloyType.fromParser(
                signature(sameLabelsModule, "X").type(), sameLabelsModule);
        CompModule localSameLabelsModule = CompUtil.parseEverything_fromString(null,
                "sig P {}\nsig X extends P {}\n");
        ExactAlloyType localSameLabels = ExactAlloyType.fromParser(
                signature(localSameLabelsModule, "X").type(),
                localSameLabelsModule);
        DependentTypeDag sameLabelsForeignDag = AlloyTypeBridge.dependentTypeDag(
                sameLabelsForeign);
        DependentTypeDag localSameLabelsDag = AlloyTypeBridge.dependentTypeDag(
                localSameLabels);
        check(sameLabelsForeignDag.equals(localSameLabelsDag)
                        && !sameLabelsForeignDag.sameOccurrenceEvidenceAs(
                                localSameLabelsDag),
                "equal-looking dependent DAGs retain distinct parser-module authority");
        check(localSameLabelsDag.sameOccurrenceEvidenceAs(
                        AlloyTypeBridge.dependentTypeDag(ExactAlloyType.fromParser(
                                signature(localSameLabelsModule, "X").type(),
                                localSameLabelsModule))),
                "dependent DAG occurrence comparison admits one parser module");
        AugmentedNode occurrence = new AugmentedNode(126, 90_001);
        Multigraph graph = new Multigraph();
        occurrence.setExactType(graph, 1, left);
        expectThrows(IllegalStateException.class,
                () -> occurrence.setExactType(graph, 1, right));
    }

    private static void testSubtypeStackIsSingleValued() throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig X {}\n");
        GraphType x = GraphType.constructor("AlloySig:X");
        List<DependentColumnEvidence> authenticated =
                AlloyTypeBridge.dependentColumns(ExactAlloyType.fromParser(
                        signature(module, "X").type(), module));
        check(authenticated.get(0).ancestry().equals(List.of(
                        x, GraphType.constructor("AlloySig:univ"))),
                "a subtype stack is keyed by its concrete top and terminates at univ");
        List<DependentColumnEvidence> truncated = List.of(
                DependentColumnEvidence.exact(x));
        expectThrows(IllegalArgumentException.class, () ->
                DependentChainKind.ARROW.foldColumns(
                        List.of(authenticated, truncated)));
    }

    private static void testConsumedAuthorityCannotHideForeignModule()
            throws Exception {
        CompModule firstModule = CompUtil.parseEverything_fromString(null,
                "sig X {}\n");
        CompModule secondModule = CompUtil.parseEverything_fromString(null,
                "sig Y {}\nsig Z {}\n");
        List<DependentColumnEvidence> first = AlloyTypeBridge.dependentColumns(
                ExactAlloyType.fromParser(
                        signature(firstModule, "X").type(), firstModule));
        List<DependentColumnEvidence> neutral = List.of(
                DependentColumnEvidence.exact(
                        GraphType.constructor("AlloySig:X")),
                DependentColumnEvidence.exact(
                        GraphType.constructor("AlloySig:Y")));
        List<DependentColumnEvidence> foreign = AlloyTypeBridge.dependentColumns(
                ExactAlloyType.fromParser(
                        signature(secondModule, "Y").type().product(
                                signature(secondModule, "Z").type()),
                        secondModule));
        expectThrows(IllegalArgumentException.class, () ->
                DependentChainKind.JOIN.foldColumns(
                        List.of(first, neutral, foreign)));
        DependentTypeDag firstDag = DependentTypeDag.exactAlternative(
                DependentChainKind.typeOf(first), first);
        DependentTypeDag neutralDag = DependentTypeDag.exactAlternative(
                DependentChainKind.typeOf(neutral), neutral);
        DependentTypeDag foreignDag = DependentTypeDag.exactAlternative(
                DependentChainKind.typeOf(foreign), foreign);
        expectThrows(IllegalArgumentException.class, () ->
                DependentTypeDag.fold(
                        DependentChainKind.JOIN,
                        List.of(firstDag, neutralDag, foreignDag)));
    }

    private static void testSequenceBuiltinAncestryAccepted() throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig A {}\nsig B { f: seq A }\n");
        PrimSig owner = signature(module, "B");
        Sig.Field field = null;
        for (Sig.Field candidate : owner.getFields()) {
            if (candidate.label.equals("f")) {
                field = candidate;
                break;
            }
        }
        if (field == null) {
            throw new AssertionError("missing sequence field f");
        }
        ExactAlloyType sequence = ExactAlloyType.fromParser(field.type(), module);
        check(sequence.hasParserAuthenticatedAncestry(),
                "Alloy-owned seq/Int ancestry retains live parser authority");
        check(sequence.ancestryAlternatives().get(0).get(1).equals(
                        List.of("seq/Int", "Int", "univ")),
                "sequence indices retain the exact built-in parent path");
        check(AlloyTypeBridge.dependentColumns(sequence).size() == 3,
                "legal sequence field columns enter dependent chain evidence");

        PrimSig fakeInt = new PrimSig("Int");
        PrimSig fakeSequenceIndex = new PrimSig("seq/Int", fakeInt);
        ExactAlloyType synthetic = ExactAlloyType.fromParser(
                fakeSequenceIndex.type(), module);
        check(!synthetic.hasParserAuthenticatedAncestry(),
                "a same-label synthetic sequence index cannot mint built-in authority");
        expectThrows(IllegalArgumentException.class,
                () -> AlloyTypeBridge.dependentColumns(synthetic));
    }

    private static PrimSig signature(CompModule module, String name) {
        for (Sig candidate : module.getAllReachableSigs()) {
            if (candidate instanceof PrimSig
                    && (candidate.label.equals(name)
                            || candidate.label.endsWith("/" + name))) {
                return (PrimSig) candidate;
            }
        }
        throw new AssertionError("missing parser signature " + name);
    }

    private static void testUnaryInteriorJoinHasNoReassociationLicense() {
        GraphType x = GraphType.constructor("AlloySig:X");
        ChainFixture fixture = fixture(
                GraphType.relation(x, x),
                GraphType.relation(x),
                GraphType.relation(x, x));
        DependentChainApplication leftInner = new DependentChainApplication(
                DependentChainKind.JOIN,
                fixture.first(),
                fixture.second());
        DependentChainApplication rightInner = new DependentChainApplication(
                DependentChainKind.JOIN,
                fixture.second(),
                fixture.third());
        expectThrows(DependentChainTheory.UnsupportedFlattening.class, () ->
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        leftInner,
                        fixture.third()));
        expectThrows(DependentChainTheory.UnsupportedFlattening.class, () ->
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        fixture.first(),
                        rightInner));
        expectThrows(DependentChainTheory.UnsupportedFlattening.class, () ->
                DependentChainKind.JOIN.fold(fixture.types()));
        expectThrows(DependentChainTheory.UnsupportedFlattening.class, () ->
                DependentChainKind.JOIN.foldColumns(List.of(
                        List.of(
                                DependentColumnEvidence.exact(x),
                                DependentColumnEvidence.exact(x)),
                        List.of(DependentColumnEvidence.exact(x)),
                        List.of(
                                DependentColumnEvidence.exact(x),
                                DependentColumnEvidence.exact(x)))));
        expectThrows(DependentChainTheory.UnsupportedFlattening.class, () ->
                DependentTypeDag.fold(
                        DependentChainKind.JOIN,
                        fixture.types().stream()
                                .map(DependentTypeDag::fromRelationFamilyType)
                                .toList()));
    }

    private static void testExactTypingRejectsUnsoundChains() {
        GraphType a = GraphType.constructor("AlloySig:A");
        GraphType b = GraphType.constructor("AlloySig:B");
        GraphType c = GraphType.constructor("AlloySig:C");
        ChainFixture mismatch = fixture(
                GraphType.relation(a, b),
                GraphType.relation(c, a),
                GraphType.relation(a));
        expectThrows(IllegalArgumentException.class, () ->
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        mismatch.first(),
                        mismatch.second()));

        ChainFixture roles = fixture(
                GraphType.relation(a, b),
                GraphType.relation(b, c),
                GraphType.relation(c));
        DependentChainApplication ordered = new DependentChainApplication(
                DependentChainKind.JOIN,
                roles.first(),
                roles.second());
        expectThrows(IllegalArgumentException.class, () ->
                new DependentChainApplication(
                        DependentChainKind.JOIN,
                        roles.second(),
                        roles.first()));
        CertifiedDependentChainConstruction construction = construct(ordered);
        SeqPortSchema schema = (SeqPortSchema) construction.node().operator()
                .portSchemas().get(0);
        expectThrows(IllegalArgumentException.class, () -> new SeqPort(
                schema,
                roles.context(),
                List.of(roles.second().port(), roles.first().port())));
        GraphType x = GraphType.constructor("AlloySig:X");
        GraphType parameterType = GraphType.constructor("AlloyCarrier", x);
        TypedSlot parameterSlot = TypedSlot.source(parameterType, 70_010);
        TypedSlotContext parameterContext = TypedSlotContext.of(parameterSlot);
        DependentChainLeaf parameterLeaf = new DependentChainLeaf(
                OnePort.slot(parameterContext, parameterSlot),
                GraphType.relation(x));
        check(parameterLeaf.typeRule()
                        == DependentChainTheory.LeafTypeRule.PRIMITIVE_SET_SINGLETON,
                "a parameter uses its exact underlying carrier proof");
        GraphType forgedParameter = GraphType.constructor(
                "Parameter0",
                GraphType.constructor("AlloyCarrier", x));
        TypedSlot forgedSlot = TypedSlot.source(forgedParameter, 70_011);
        TypedSlotContext forgedContext = TypedSlotContext.of(forgedSlot);
        expectThrows(IllegalArgumentException.class, () ->
                new DependentChainLeaf(
                        OnePort.slot(forgedContext, forgedSlot),
                        GraphType.relation(x)));
        expectThrows(IllegalArgumentException.class, () ->
                GraphType.constructor("AlloySig:"));
        expectThrows(IllegalArgumentException.class, () ->
                GraphType.constructor("AlloySig:this/this/A"));
        expectThrows(IllegalArgumentException.class, () ->
                GraphType.constructor("AlloySig: "));
        expectThrows(IllegalArgumentException.class, () ->
                GraphType.constructor("AlloySig:\u00a0"));
        expectThrows(IllegalArgumentException.class, () ->
                GraphType.constructor("S\u200b"));
        expectThrows(IllegalArgumentException.class, () ->
                GraphType.constructor("S\ue000"));
        expectThrows(IllegalArgumentException.class, () ->
                GraphType.constructor("S\u0378"));
        expectThrows(IllegalArgumentException.class, () ->
                ExactAlloyType.relation(List.of("\u00a0")));
        expectThrows(IllegalArgumentException.class, () ->
                AlloyTypeBridge.alloyColumn(" S "));
        expectThrows(IllegalArgumentException.class, () ->
                ExactAlloyType.relation(List.of("S\u200b")));
        expectThrows(IllegalArgumentException.class, () ->
                ExactAlloyType.relation(List.of("S\u0000T")));
        expectThrows(IllegalArgumentException.class, () ->
                ExactAlloyType.relation(List.of("\ud800")));
        expectThrows(IllegalArgumentException.class, () ->
                AlloyTypeBridge.alloyColumn("S\u200b"));
        expectThrows(IllegalArgumentException.class, () ->
                AlloyTypeBridge.alloyColumn("\ud800"));
        expectThrows(IllegalArgumentException.class, () ->
                ExactAlloyType.relation(List.of("S\ue000")));
        expectThrows(IllegalArgumentException.class, () ->
                ExactAlloyType.relation(List.of("S\u0378")));
        expectThrows(IllegalArgumentException.class, () ->
                AlloyTypeBridge.alloyColumn("S\ue000"));
        expectThrows(IllegalArgumentException.class, () ->
                AlloyTypeBridge.alloyColumn("S\u0378"));
        expectThrows(IllegalArgumentException.class, () ->
                new is.fivefivefive.ACGN.alloy.SigSymbol("S\u200b"));
        expectThrows(IllegalArgumentException.class, () ->
                new is.fivefivefive.ACGN.alloy.SigSymbol("S\ud800"));
        expectThrows(IllegalArgumentException.class, () ->
                new is.fivefivefive.ACGN.alloy.SigSymbol("S\ue000"));
        expectThrows(IllegalArgumentException.class, () ->
                new is.fivefivefive.ACGN.alloy.SigSymbol("S\u0378"));
        String supplementary = new String(Character.toChars(0x10400));
        check(AlloyTypeBridge.isAdmittedIdentity("S" + supplementary),
                "a valid supplementary-plane identity remains admitted");
        check(GraphType.constructor("S" + supplementary).symbol()
                        .equals("S" + supplementary),
                "graph types preserve valid supplementary-plane identities");
        check(GraphType.constructor("AlloySig:this/A")
                        .equals(GraphType.constructor("AlloySig:A")),
                "graph types normalize the parser's this/ prefix");
        check(ExactAlloyType.unaryRelation("this/A")
                        .equals(ExactAlloyType.unaryRelation("A")),
                "public exact types normalize the parser's this/ prefix");
        expectThrows(IllegalArgumentException.class, () ->
                ExactAlloyType.unaryRelation("this/this/A"));
        check(AlloyTypeBridge.graphType(ExactAlloyType.unaryRelation("this/A"))
                        .equals(AlloyTypeBridge.graphType(
                                ExactAlloyType.unaryRelation("A"))),
                "dependent type conversion preserves this/ normalization");
        check(AlloyTypeBridge.alloyColumn("this/A")
                        .equals(AlloyTypeBridge.alloyColumn("A")),
                "package column conversion normalizes this/ consistently");
        check(new is.fivefivefive.ACGN.alloy.SigSymbol(
                        "S" + supplementary).getName().equals("S" + supplementary),
                "signature symbols preserve valid supplementary-plane identities");
        check(new is.fivefivefive.ACGN.alloy.SigSymbol("this/A")
                        .equals(new is.fivefivefive.ACGN.alloy.SigSymbol("A")),
                "signature symbols normalize the parser's this/ prefix");
        expectThrows(IllegalArgumentException.class, () ->
                CertificateBundleWriter.encodeCanonicalUtf8("S\ud800"));
        check(Arrays.equals(
                        CertificateBundleWriter.encodeCanonicalUtf8(
                                "S" + supplementary),
                        ("S" + supplementary).getBytes(StandardCharsets.UTF_8)),
                "producer canonical UTF-8 preserves supplementary-plane scalars");
        GraphType bogusRelation = GraphType.relation(
                GraphType.constructor("Bogus"));
        expectThrows(IllegalArgumentException.class, () ->
                DependentChainTheory.requireLeafTypeProof(
                        bogusRelation, bogusRelation));
        ChainFixture polymorphic = fixture(
                GraphType.relation(GraphType.constructor("AlloySig:univ")),
                GraphType.relation(x),
                GraphType.relation(x));
        check(new DependentChainApplication(
                        DependentChainKind.ARROW,
                        polymorphic.first(),
                        polymorphic.second()).outputType().equals(
                                GraphType.relation(
                                        GraphType.constructor("AlloySig:univ"), x)),
                "an explicit univ GraphType is not confused with absent typing");
        check(DependentChainTheory.DIGEST.length() == 64,
                "Dependent-chain source theory has a fixed SHA-256 identity");
    }

    private static CertifiedDependentChainConstruction construct(
            DependentChainApplication application) {
        CertifiedDependentChainConstruction construction =
                TypedENode.constructDependentChainCertified(
                        application,
                        SemanticProfile.alloyOverflowForbidding());
        CertificateVerifier.verify(construction.certificate());
        return construction;
    }

    private static void assertDependentSequence(
            CertifiedDependentChainConstruction construction,
            List<OnePort> expected) {
        check(construction.node().ports().size() == 1
                        && construction.node().ports().get(0) instanceof SeqPort,
                "Dependent chain has exactly one Seq carrier");
        SeqPort sequence = (SeqPort) construction.node().ports().get(0);
        check(sequence.schema().isDependent()
                        && sequence.schema().positionalElementSchemas().size()
                                == expected.size(),
                "Seq carries one exact schema per operand position");
        check(sequence.elements().equals(expected),
                "Seq preserves every operand in source order");
        for (int index = 0; index < expected.size(); index++) {
            check(sequence.schema().schemaAt(index).equals(
                            expected.get(index).schema()),
                    "Position " + index + " retains its independent type proof");
        }
    }

    private static ChainFixture fixture(
            GraphType first,
            GraphType second,
            GraphType third) {
        TypedSlot a = TypedSlot.source(first, 70_001);
        TypedSlot b = TypedSlot.source(second, 70_002);
        TypedSlot c = TypedSlot.source(third, 70_003);
        TypedSlotContext context = TypedSlotContext.of(a, b, c);
        OnePort pa = OnePort.slot(context, a);
        OnePort pb = OnePort.slot(context, b);
        OnePort pc = OnePort.slot(context, c);
        return new ChainFixture(
                context,
                new DependentChainLeaf(pa),
                new DependentChainLeaf(pb),
                new DependentChainLeaf(pc),
                List.of(pa, pb, pc),
                List.of(first, second, third));
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
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName() + " but got " + thrown,
                    thrown);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private record ChainFixture(
            TypedSlotContext context,
            DependentChainLeaf first,
            DependentChainLeaf second,
            DependentChainLeaf third,
            List<OnePort> ports,
            List<GraphType> types) {
    }
}

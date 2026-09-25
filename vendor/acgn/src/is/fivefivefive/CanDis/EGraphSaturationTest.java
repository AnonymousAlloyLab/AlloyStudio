package is.fivefivefive.CanDis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.alloy.SigSymbol;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.NormalForm.TemporalOp;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.core.QuantiVar.Cardinality;
import is.fivefivefive.CanDis.core.QuantiVar.Quantifier;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;

public final class EGraphSaturationTest {
    private static long nextSyntheticCallOccurrence;

    private EGraphSaturationTest() {
    }

    public static void main(String[] args) {
        testAssociativeCommutativeSaturation();
        testDeMorganSaturation();
        testAssociativeNoncommutativeJoin();
        testSetOperatorsUseSetFlexibleArity();
        testRenamedIdUnionFind();
        testDoubleNegationAndIdempotence();
        testVariadicAbsorptionPreservesUnrelatedOperands();
        testAllNoNotQuantifierEquivalence();
        testBooleanIdentitySaturation();
        testBooleanIdentityRequiresExactBooleanType();
        testCertificationSnapshotRetainsForgedBooleanName();
        testBooleanIdentityRequiresConcordantSourceType();
        testBooleanIdentityRequiresBooleanContainerType();
        testBooleanRewritesRequireBooleanOperands();
        testGenericBooleanTypeDoesNotAuthorizeOperators();
        testPublicMutationRevokesDerivedBooleanAuthority();
        testMalformedDescendantCallRejectedBeforeAbsorption();
        testUnionEquivalentMalformedCallRejectedBeforeAbsorption();
        testUnionEquivalentAlternativeFrozenForCertification();
        testFrozenSharedDescendantBlocksOtherParentSaturation();
        testCrossThreadSaturationInheritsOwningArena();
        testCrossThreadTemporalNegationInheritsOwningArena();
        testTemporalFreezeAndRewriteAreSerialized();
        testRejectedChildMutationsAreAtomic();
        testReachabilityPrunesDisconnectedUnionComponents();
        testEmptyReachabilityPrunesEntireArena();
        testEGraphNodeAdmissionBoundaryIsFinal();
        testMalformedTemporalDescendantRejectedBeforeDualization();
        testForgedTemporalReferenceRejectedAtAdapterBoundary();
        testUnreferencedTemporalChildRejected();
        testTemporalSourceVisitCannotBorrowEdges();
        testTemporalAuthorityTracksLiveSourceGraph();
        testPreparedTemporalAuthorityDropsLiveGraphDependency();
        testCrossArenaChildrenRejected();
        testCrossArenaSnapshotsAndComparisonsRejected();
        testMalformedBranchConnectiveRetained();
        testSetIdentitySaturation();
        testNestedDifferenceRequiresParserAuthority();
        testReservedSetIdentityRequiresAuthority();
        testImplicationSaturation();
        testEmptyDomainQuantifierRewrite();
        testIteEliminatedFromNormalForm();
        testEndEliminatedFromNormalForm();
        testLetReferenceSurvivesEndCleanupUntilBetaReduction();
        testNegatedRelationDoesNotNegateSetOperands();
        testPrimitiveDomainConstraintNotDuplicatedInMatrix();
        testCommutingPrenexBindingsIgnoreBranchOrder();
        testNegatedSomeAndNoBindingPathsAreEquivalent();
        testCommutativeComplexDomainsUseCanonicalCarrier();
        testComplementEliminatesRedundantSlot();
        testSlotPermutationGroups();
        testDisjModifierIsPreserved();
        testDisjClassesDistinguishDeclarationGroups();
        testDisjModifierAffectsCanonicalDistance();
        testBagMultiplicityPreservedUntilExplicitRewrite();
        testImplicationPrenexPolarityDoesNotDoubleNegate();
        testImplicationScopeAffectsCanonicalDistance();
        testIffPrenexPolarity();
        testAlphaRenamingKeepsCanonicalDistanceZero();
        testOneAndLoneQuantifierNegation();
        testQuantifierPolarityRules();
        testCommutativeDistanceUsesUnorderedMatching();
        testTemporalNegationCrossesPhaseBoundary();
        testDistanceAllocationInstrumentation();
        System.out.println("EGraphSaturationTest passed");
    }

    private static void testAssociativeCommutativeSaturation() {
        EGraphNode nested = node(Opcode.AND, true, true, variable("b"), variable("a"));
        EGraphNode root = node(Opcode.AND, true, true, variable("c"), nested);

        root.saturate();

        assertTrue(root.isSetFlexibleArity(), "AND must use set flexible arity");
        assertEquals(3, root.getChildren().size(), "AND must flatten to flexible arity");
        assertEquals(Arrays.asList("a", "b", "c"), variableNames(root.getChildren()),
                "commutative children must be canonicalized");
        assertTrue(root.getEClass().getNodes().size() >= 2,
                "the original and flattened terms must share an e-class");
        assertEquals(3, root.getChildClasses().size(), "an e-node must reference one e-class per operand");
        for (EGraphNode.EClassRef child : root.getChildClasses()) {
            assertEquals(1, child.getEClass().getSlots().size(), "variable e-class must expose one slot");
            assertEquals(1, child.getSlotMap().size(), "e-class invocation must map its slot");
        }

        EGraphNode disjunction = node(Opcode.OR, true, true, variable("z"), variable("y"), variable("x"));
        disjunction.saturate();
        assertTrue(disjunction.isSetFlexibleArity(), "OR must use set flexible arity");
        assertEquals(Arrays.asList("x", "y", "z"), variableNames(disjunction.getChildren()),
                "OR set operands must be canonicalized without preserving source order");
    }

    private static void testDistanceAllocationInstrumentation() {
        NormalForm left = new NormalForm();
        left.addEClass(node(Opcode.AND, true, true, variable("a"), variable("b")));
        NormalForm right = new NormalForm();
        right.addEClass(node(Opcode.AND, true, true, variable("b"), variable("a")));
        CanonicalDistance.Prepared leftPrepared = CanonicalDistance.prepare(List.of(left));
        CanonicalDistance.Prepared rightPrepared = CanonicalDistance.prepare(List.of(right));

        CanonicalDistance.beginAllocationTracking();
        int distance = CanonicalDistance.distance(leftPrepared, rightPrepared);
        CanonicalDistance.AllocationStats allocations = CanonicalDistance.endAllocationTracking();

        assertEquals(0, distance, "allocation diagnostics must not change canonical distance");
        assertTrue(allocations.estimatedBytes() > 0, "distance diagnostics must count scratch arrays");
        assertTrue(allocations.matrixCount() > 0, "distance diagnostics must count DP matrices");
    }

    private static void testDeMorganSaturation() {
        EGraphNode conjunction = node(Opcode.AND, true, true, variable("x"), variable("y"));
        EGraphNode negation = node(Opcode.NOT, false, false, conjunction);

        negation.saturate();

        assertEquals(Opcode.OR, negation.getOpcode(), "De Morgan must rewrite NOT(AND) to OR");
        assertEquals(2, negation.getChildren().size(), "De Morgan must preserve operand count");
        for (EGraphNode child : negation.getChildren()) {
            assertEquals(Opcode.NOT, child.getOpcode(), "De Morgan must negate every operand");
        }
        assertTrue(negation.getEClass().getNodes().size() >= 2,
                "De Morgan alternatives must remain in one e-class");
    }

    private static void testAssociativeNoncommutativeJoin() {
        EGraphNode nested = node(Opcode.JOIN, false, false, variable("b"), variable("c"));
        EGraphNode join = node(Opcode.JOIN, false, false, variable("a"), nested);

        join.saturate();

        assertTrue(!join.isFlexibleArity(), "JOIN must remain fixed binary");
        assertTrue(!join.hasFlatLicense(), "JOIN must not receive a homogeneous flat license");
        assertEquals(2, join.getChildren().size(), "JOIN must preserve its two source roles");
        assertEquals("a", join.getChildren().get(0).getAlphaName(),
                "JOIN must preserve its left operand");
        assertEquals(Opcode.JOIN, join.getChildren().get(1).getOpcode(),
                "JOIN must retain the nested right-associated term");
        assertEquals(Arrays.asList("b", "c"), variableNames(join.getChildren().get(1).getChildren()),
                "JOIN must preserve nested operand order");
    }

    private static void testSetOperatorsUseSetFlexibleArity() {
        EGraphNode nestedUnion = node(Opcode.PLUS, true, true, variable("b"), variable("a"));
        EGraphNode union = node(Opcode.PLUS, true, true, variable("c"), nestedUnion);
        union.saturate();

        assertTrue(union.isSetFlexibleArity(), "set union PLUS must use set flexible arity");
        assertEquals(Arrays.asList("a", "b", "c"), variableNames(union.getChildren()),
                "set flexible arity must canonicalize set union operands without preserving source order");

        EGraphNode nestedIntersection = node(Opcode.INTERSECT, true, true, variable("right"), variable("left"));
        EGraphNode intersection = node(Opcode.INTERSECT, true, true, variable("tail"), nestedIntersection);
        intersection.saturate();

        assertTrue(intersection.isSetFlexibleArity(), "set intersection must use set flexible arity");
        assertEquals(Arrays.asList("left", "right", "tail"), variableNames(intersection.getChildren()),
                "set flexible arity must canonicalize set intersection operands");

        EGraphNode arrow = node(Opcode.ARROW, false, false, variable("a"),
                node(Opcode.ARROW, false, false, variable("b"), variable("c")));
        arrow.saturate();

        assertTrue(!arrow.isFlexibleArity(), "relational product ARROW must remain fixed binary");
        assertTrue(!arrow.hasFlatLicense(), "relational product ARROW must not flatten");
        assertEquals(Opcode.ARROW, arrow.getChildren().get(1).getOpcode(),
                "relational product must preserve its nested typed chain");
    }

    private static void testRenamedIdUnionFind() {
        EGraphNode x = variable("x");
        EGraphNode y = variable("y");
        EGraphNode z = variable("z");
        EGraphNode.EClassRef xAtA = x.getEClass().invoke(rename("x", "a"));
        EGraphNode.EClassRef yAtA = y.getEClass().invoke(rename("y", "a"));
        EGraphNode.EClassRef zAtA = z.getEClass().invoke(rename("z", "a"));

        EGraphNode.union(xAtA, yAtA);
        EGraphNode.union(yAtA, zAtA);

        assertTrue(xAtA.equivalentTo(yAtA), "alpha-equivalent invocations must be unioned");
        assertTrue(xAtA.equivalentTo(zAtA), "renamed-ID union must be transitive");
        assertEquals(xAtA.canonical().getEClass().getId(), zAtA.canonical().getEClass().getId(),
                "path compression must find one leader e-class");

        EGraphNode.EClassRef yAtB = y.getEClass().invoke(rename("y", "b"));
        assertTrue(!xAtA.equivalentTo(yAtB),
                "the same e-class under a different caller-slot renaming is not automatically equivalent");
    }

    private static void testDoubleNegationAndIdempotence() {
        EGraphNode x = variable("doubleNegationX");
        EGraphNode innerNot = node(Opcode.NOT, false, false, x);
        EGraphNode outerNot = node(Opcode.NOT, false, false, innerNot);
        outerNot.saturate();

        assertEquals(Opcode.VARIABLE, outerNot.getOpcode(), "double negation must collapse to its operand");
        assertEquals("doubleNegationX", outerNot.getAlphaName(), "double negation must preserve the slot");
        assertTrue(outerNot.getEClass().getNodes().size() >= 2,
                "double negation and its operand must remain equivalent alternatives");

        EGraphNode duplicate = variable("duplicateX");
        EGraphNode disjunction = node(Opcode.OR, true, true, duplicate, duplicate);
        disjunction.saturate();
        assertEquals(Opcode.VARIABLE, disjunction.getOpcode(), "A OR A must collapse to A");

        EGraphNode duplicateAnd = variable("duplicateAndX");
        EGraphNode conjunction = node(Opcode.AND, true, true, duplicateAnd, duplicateAnd);
        conjunction.saturate();
        assertEquals(Opcode.VARIABLE, conjunction.getOpcode(), "A AND A must collapse to A");

        EGraphNode duplicateUnion = variable("duplicateUnionX");
        EGraphNode union = node(Opcode.PLUS, true, true, duplicateUnion, duplicateUnion);
        union.saturate();
        assertEquals(Opcode.VARIABLE, union.getOpcode(), "A + A must collapse to A");

        EGraphNode duplicateIntersection = variable("duplicateIntersectionX");
        EGraphNode intersection = node(Opcode.INTERSECT, true, true, duplicateIntersection, duplicateIntersection);
        intersection.saturate();
        assertEquals(Opcode.VARIABLE, intersection.getOpcode(), "A & A must collapse to A");
    }

    private static void testVariadicAbsorptionPreservesUnrelatedOperands() {
        EGraphNode andLeft = predicate("andContextLeft");
        EGraphNode andAbsorbed = predicate("andContextAbsorbed");
        EGraphNode andAlternative = predicate("andContextAlternative");
        EGraphNode andRight = predicate("andContextRight");
        EGraphNode conjunction = node(
                Opcode.AND,
                true,
                true,
                andLeft,
                andAbsorbed,
                node(Opcode.OR, true, true, andAbsorbed, andAlternative),
                andRight);

        conjunction.saturate();

        assertEquals(Opcode.AND, conjunction.getOpcode(),
                "variadic AND absorption must retain its enclosing operator");
        assertEquals(3, conjunction.getChildren().size(),
                "AND absorption must remove only the covered OR operand");
        assertCallChildren(
                conjunction,
                "andContextAbsorbed",
                "andContextLeft",
                "andContextRight");

        EGraphNode orLeft = predicate("orContextLeft");
        EGraphNode orAbsorbed = predicate("orContextAbsorbed");
        EGraphNode orAlternative = predicate("orContextAlternative");
        EGraphNode orRight = predicate("orContextRight");
        EGraphNode disjunction = node(
                Opcode.OR,
                true,
                true,
                orLeft,
                orAbsorbed,
                node(Opcode.AND, true, true, orAbsorbed, orAlternative),
                orRight);

        disjunction.saturate();

        assertEquals(Opcode.OR, disjunction.getOpcode(),
                "variadic OR absorption must retain its enclosing operator");
        assertEquals(3, disjunction.getChildren().size(),
                "OR absorption must remove only the covered AND operand");
        assertCallChildren(
                disjunction,
                "orContextAbsorbed",
                "orContextLeft",
                "orContextRight");

        EGraphNode unrelatedLeft = predicate("unrelatedLeft");
        EGraphNode unrelatedCandidate = predicate("unrelatedCandidate");
        EGraphNode unrelatedAlternative = predicate("unrelatedAlternative");
        EGraphNode nearMiss = node(
                Opcode.AND,
                true,
                true,
                unrelatedLeft,
                andAbsorbed,
                node(Opcode.OR, true, true,
                        unrelatedCandidate, unrelatedAlternative),
                andRight);

        nearMiss.saturate();

        assertEquals(Opcode.AND, nearMiss.getOpcode(),
                "an unrelated dual container must not trigger absorption");
        assertEquals(4, nearMiss.getChildren().size(),
                "an absorption near miss must retain every owner operand");
        assertTrue(nearMiss.getChildren().stream()
                        .anyMatch(child -> child.getOpcode() == Opcode.OR),
                "an unmatched OR operand must remain in the AND container");

    }

    private static void testAllNoNotQuantifierEquivalence() {
        NormalForm all = new NormalForm();
        all.addEClass(node(Opcode.FORALL, false, false, relDecl("x"), predicate("P", variable("x"))));
        all.normalize();

        NormalForm noNot = new NormalForm();
        noNot.addEClass(node(
                Opcode.NO,
                false,
                false,
                relDecl("x"),
                node(Opcode.NOT, false, false, predicate("P", variable("x")))));
        noNot.normalize();

        assertEquals(0, normalFormDistance(all, noNot),
                "all x:S | P must be equivalent to no x:S | not P");
    }

    private static void testBooleanIdentitySaturation() {
        EGraphNode andTrue = node(Opcode.AND, true, true, variable("andTrueX"), bool(true));
        andTrue.saturate();
        assertEquals(Opcode.VARIABLE, andTrue.getOpcode(), "A AND true must collapse to A");

        EGraphNode orFalse = node(Opcode.OR, true, true, variable("orFalseX"), bool(false));
        orFalse.saturate();
        assertEquals(Opcode.VARIABLE, orFalse.getOpcode(), "A OR false must collapse to A");

        EGraphNode andFalse = node(Opcode.AND, true, true, variable("andFalseX"), bool(false));
        andFalse.saturate();
        assertEquals(Opcode.CONSTANT, andFalse.getOpcode(), "A AND false must collapse to false");
        assertEquals("false", andFalse.getSourceName(), "A AND false must collapse to false");

        EGraphNode orTrue = node(Opcode.OR, true, true, variable("orTrueX"), bool(true));
        orTrue.saturate();
        assertEquals(Opcode.CONSTANT, orTrue.getOpcode(), "A OR true must collapse to true");
        assertEquals("true", orTrue.getSourceName(), "A OR true must collapse to true");
    }

    private static void testBooleanIdentityRequiresExactBooleanType() {
        EGraphNode relationNamedTrue = new EGraphNode(
                10_001, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                Metatype.SET);
        relationNamedTrue.setSourceName("true");
        relationNamedTrue.setSourceType("S");
        relationNamedTrue.setExactAlloyType(ExactAlloyType.unaryRelation("S"));
        EGraphNode setMetatype = node(
                Opcode.AND, true, true, variable("typedGuardA"), relationNamedTrue);
        setMetatype.saturate();
        assertEquals(Opcode.AND, setMetatype.getOpcode(),
                "a relation named true must not act as the Boolean identity");
        assertEquals(2, setMetatype.getChildren().size(),
                "the relation-typed true leaf must remain observable");

        EGraphNode booleanMetatypeRelation = new EGraphNode(
                10_002, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                Metatype.BOOLEAN);
        booleanMetatypeRelation.setSourceName("false");
        booleanMetatypeRelation.setSourceType("S");
        booleanMetatypeRelation.setExactAlloyType(ExactAlloyType.unaryRelation("S"));
        EGraphNode exactType = node(
                Opcode.OR, true, true, variable("typedGuardB"), booleanMetatypeRelation);
        exactType.saturate();
        assertEquals(Opcode.OR, exactType.getOpcode(),
                "a non-Boolean exact type must block Boolean identity reduction");
        assertEquals(2, exactType.getChildren().size(),
                "the exact-relation false leaf must remain observable");
    }

    private static void testCertificationSnapshotRetainsForgedBooleanName() {
        EGraphNode relationNamedTrue = new EGraphNode(
                10_003, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                Metatype.SET);
        relationNamedTrue.setSourceName("true");
        relationNamedTrue.setSourceType("S");
        relationNamedTrue.setExactAlloyType(ExactAlloyType.unaryRelation("S"));
        EGraphNode conjunction = node(
                Opcode.AND,
                true,
                true,
                node(Opcode.SOME, false, false, global("S")),
                relationNamedTrue);
        NormalForm form = new NormalForm();
        form.addEClass(conjunction);
        form.normalize();

        assertEquals(Opcode.AND, form.getMatrixEGraph().getOpcode(),
                "normalization must retain a relation merely named true");
        assertEquals(2, form.getMatrixEGraph().getChildren().size(),
                "the live matrix must retain the forged Boolean-name operand");
        assertEquals(Opcode.AND, form.getCertificationMatrixEGraph().getOpcode(),
                "the certification snapshot must retain the forged operand");
        assertEquals(2, form.getCertificationMatrixEGraph().getChildren().size(),
                "the certification snapshot must expose the malformed term");
    }

    private static void testBooleanIdentityRequiresConcordantSourceType() {
        EGraphNode inconsistentTrue = new EGraphNode(
                10_004, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                Metatype.BOOLEAN);
        inconsistentTrue.setSourceName("true");
        inconsistentTrue.setSourceType("S");
        inconsistentTrue.setExactAlloyType(ExactAlloyType.boolType());
        EGraphNode conjunction = node(
                Opcode.AND, true, true, variable("typedGuardC"), inconsistentTrue);
        conjunction.saturate();
        assertEquals(Opcode.AND, conjunction.getOpcode(),
                "inconsistent source type must block Boolean identity reduction");
        assertEquals(2, conjunction.getChildren().size(),
                "an inconsistently typed literal must remain observable");
    }

    private static void testBooleanIdentityRequiresBooleanContainerType() {
        EGraphNode malformedAnd = new EGraphNode(
                10_005,
                Opcode.AND,
                Arrays.asList(variable("typedGuardD"), bool(true)),
                true,
                -1,
                true,
                Metatype.SET);
        malformedAnd.setSourceType("Rel(S)");
        malformedAnd.setExactAlloyType(ExactAlloyType.unaryRelation("S"));
        malformedAnd.saturate();
        assertEquals(Opcode.AND, malformedAnd.getOpcode(),
                "a non-Boolean AND container must not apply Boolean laws");
        assertEquals(2, malformedAnd.getChildren().size(),
                "a malformed Boolean container must remain observable");

        EGraphNode contradictorySourceType = new EGraphNode(
                10_007,
                Opcode.AND,
                Arrays.asList(variable("typedGuardE"), bool(true)),
                true,
                -1,
                true,
                Metatype.BOOLEAN);
        contradictorySourceType.setSourceType("Rel(S)");
        contradictorySourceType.setExactAlloyType(ExactAlloyType.boolType());
        contradictorySourceType.saturate();
        assertEquals(Opcode.AND, contradictorySourceType.getOpcode(),
                "a contradictory container source type must block Boolean laws");
    }

    private static void testMalformedDescendantCallRejectedBeforeAbsorption() {
        EGraphNode malformedCall = new EGraphNode(
                10_010,
                Opcode.CALL,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.BOOLEAN);
        malformedCall.setSourceType("call/formula");
        malformedCall.setExactAlloyType(ExactAlloyType.boolType());

        EGraphNode wrapper = new EGraphNode(
                10_011,
                Opcode.PREDICATE,
                List.of(malformedCall),
                false,
                1,
                false,
                Metatype.BOOLEAN);
        wrapper.setSourceType("predroot");
        wrapper.setExactAlloyType(ExactAlloyType.boolType());

        NormalForm form = new NormalForm();
        form.addEClass(node(Opcode.AND, true, true, bool(false), wrapper));
        assertThrows(
                IllegalStateException.class,
                form::normalize,
                "whole-graph admission must reject a malformed CALL before an absorber hides it");
    }

    private static void testUnionEquivalentMalformedCallRejectedBeforeAbsorption() {
        EGraphNode.beginGraph();
        try {
            EGraphNode visible = predicate("visibleUnionCall");
            EGraphNode bridge = predicate("bridgeUnionCall");
            EGraphNode hidden = predicate("hiddenUnionCall");
            EGraphNode.union(visible.getEClassRef(), bridge.getEClassRef());
            EGraphNode.union(bridge.getEClassRef(), hidden.getEClassRef());
            hidden.setSourceName(null);

            EGraphNode wrapper = node(Opcode.PREDICATE, false, true, visible);
            wrapper.setSourceType("predroot");
            wrapper.setExactAlloyType(ExactAlloyType.boolType());
            NormalForm form = new NormalForm();
            form.addEClass(node(Opcode.AND, true, true, bool(false), wrapper));
            assertThrows(
                    IllegalStateException.class,
                    form::normalize,
                    "admission must inspect every e-class in a reachable union component");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testUnionEquivalentAlternativeFrozenForCertification() {
        EGraphNode.beginGraph();
        try {
            EGraphNode visible = predicate("visibleFrozenUnionCall");
            EGraphNode bridge = predicate("bridgeFrozenUnionCall");
            EGraphNode equivalent = predicate("equivalentFrozenUnionCall");
            EGraphNode.union(visible.getEClassRef(), bridge.getEClassRef());
            EGraphNode.union(bridge.getEClassRef(), equivalent.getEClassRef());
            visible.freezeForCertification();
            assertThrows(
                    IllegalStateException.class,
                    () -> equivalent.setSourceName("mutatedAfterFreeze"),
                    "freezing one invocation must freeze its entire union component");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testFrozenSharedDescendantBlocksOtherParentSaturation() {
        EGraphNode.beginGraph();
        try {
            EGraphNode mutableChild = node(
                    Opcode.IN, false, false, global("none"), global("none"));
            EGraphNode frozenChild = node(
                    Opcode.IN, false, false, global("none"), global("none"));
            EGraphNode certifiedParent = new EGraphNode(
                    10_010, Opcode.PREDICATE, List.of(frozenChild), false, 1,
                    false, Metatype.BOOLEAN);
            EGraphNode liveParent = new EGraphNode(
                    10_011, Opcode.LIST, List.of(mutableChild, frozenChild),
                    false, -1, true, Metatype.CONTROL);

            certifiedParent.freezeForCertification();

            assertThrows(
                    IllegalStateException.class,
                    liveParent::saturate,
                    "an unfrozen parent must not rewrite a shared frozen descendant");
            assertEquals(Opcode.IN, frozenChild.getOpcode(),
                    "the shared frozen descendant must retain its admitted opcode");
            assertEquals(Opcode.IN, mutableChild.getOpcode(),
                    "whole-graph mutability preflight must prevent partial rewrites");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testCrossThreadSaturationInheritsOwningArena() {
        EGraphNode.beginGraph();
        try {
            EGraphNode implication = node(
                    Opcode.IMPLIES,
                    false,
                    false,
                    predicate("crossThreadLeft"),
                    predicate("crossThreadRight"));
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    implication.saturate();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            }, "egraph-cross-thread-saturation");
            worker.start();
            try {
                worker.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("cross-thread saturation was interrupted", interrupted);
            }
            if (failure.get() != null) {
                throw new AssertionError(
                        "rewrite-created nodes must inherit the owning graph arena",
                        failure.get());
            }
            assertEquals(Opcode.OR, implication.getOpcode(),
                    "cross-thread implication saturation must complete in its owning arena");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testCrossThreadTemporalNegationInheritsOwningArena() {
        List<NormalForm> forms = temporalNormalForms(
                "not (always (some Trash))", TemporalFixtureAction.DELEGATE_REWRITE);
        NormalForm parent = forms.get(0);
        NormalForm child = parent.getTemporalChildren().get(0);

        assertEquals(TemporalOp.EVENTUALLY, child.getTemporalOp(),
                "cross-thread NOT ALWAYS must commit the EVENTUALLY dual");
        assertTrue(containsOpcode(child.getMatrixEGraph(), Opcode.NOT),
                "cross-thread temporal dualization must commit the negated child matrix");
        assertTrue(!containsOpcode(parent.getMatrixEGraph(), Opcode.NOT),
                "the parent must commit only after its temporal child rewrite is staged");
    }

    private static void testTemporalFreezeAndRewriteAreSerialized() {
        List<NormalForm> forms = temporalNormalForms(
                "not (always (some Trash))", TemporalFixtureAction.STOP_BEFORE_REWRITE);
        NormalForm parent = forms.get(0);
        NormalForm child = parent.getTemporalChildren().get(0);
        assertEquals(TemporalOp.ALWAYS, child.getTemporalOp(),
                "the race fixture must stop before temporal dualization");

        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> rewriteFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> freezeFailure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread rewrite = new Thread(() -> {
            await(start);
            try {
                parent.pushTemporalNegations();
            } catch (Throwable failure) {
                rewriteFailure.set(failure);
            }
        }, "normal-form-temporal-rewrite-race");
        Thread freeze = new Thread(() -> {
            await(start);
            try {
                parent.freezeForCertification();
            } catch (Throwable failure) {
                freezeFailure.set(failure);
            }
        }, "normal-form-temporal-freeze-race");
        rewrite.start();
        freeze.start();
        start.countDown();
        join(rewrite, "temporal rewrite race");
        join(freeze, "temporal freeze race");

        if (freezeFailure.get() != null) {
            throw new AssertionError(
                    "certification freeze must complete as one serialized lifecycle action",
                    freezeFailure.get());
        }
        Throwable rejectedRewrite = rewriteFailure.get();
        if (rejectedRewrite == null) {
            assertEquals(TemporalOp.EVENTUALLY, child.getTemporalOp(),
                    "a rewrite that wins the lifecycle lock must commit before freeze");
            assertTrue(!containsOpcode(parent.getMatrixEGraph(), Opcode.NOT),
                    "the winning rewrite must commit its parent matrix before freeze");
        } else {
            assertTrue(rejectedRewrite instanceof IllegalStateException,
                    "a freeze-winning race may reject only as an immutable form");
            assertEquals(TemporalOp.ALWAYS, child.getTemporalOp(),
                    "a rejected rewrite must leave the child operation unchanged");
            assertTrue(containsOpcode(parent.getMatrixEGraph(), Opcode.NOT),
                    "a rejected rewrite must leave the parent negation unchanged");
        }
        assertTrue(parent.isFrozenForCertification()
                        && child.isFrozenForCertification(),
                "the complete temporal tree must publish frozen together");
        assertThrows(
                IllegalStateException.class,
                () -> new NormalForm(parent, TemporalOp.AFTER, 10_019),
                "a frozen temporal parent must reject late child construction");
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrency regression was interrupted", interrupted);
        }
    }

    private static void join(Thread thread, String label) {
        try {
            thread.join(10_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(label + " was interrupted", interrupted);
        }
        if (thread.isAlive()) {
            throw new AssertionError(label + " did not terminate");
        }
    }

    private static void testRejectedChildMutationsAreAtomic() {
        EGraphNode.beginGraph();
        try {
            EGraphNode original = bool(true);
            EGraphNode extra = bool(false);
            EGraphNode negation = node(Opcode.NOT, false, false, original);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> negation.setChildren(List.of(original, extra)),
                    "an over-arity child replacement must reject");
            assertEquals(1, negation.getChildren().size(),
                    "a rejected child replacement must preserve the original arity");
            assertTrue(negation.getChildren().get(0) == original,
                    "a rejected child replacement must preserve the original child");

            assertThrows(
                    IllegalArgumentException.class,
                    () -> negation.addChild(extra),
                    "an over-arity child append must reject");
            assertEquals(1, negation.getChildren().size(),
                    "a rejected child append must preserve the original arity");
            assertTrue(negation.getChildren().get(0) == original,
                    "a rejected child append must preserve the original child");

            assertThrows(
                    IllegalArgumentException.class,
                    () -> negation.addChildInvocation(extra.getEClassRef()),
                    "an over-arity invocation append must reject");
            assertEquals(1, negation.getChildren().size(),
                    "a rejected invocation append must preserve the original arity");
            negation.requireAdmittedArity();

            EGraphNode call = predicate("atomicMetadata", original);
            long occurrence = call.getCallOccurrenceId();
            int declaredArity = call.getDeclaredArity();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> call.setCallOccurrenceId(-2L),
                    "an invalid occurrence id must reject");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> call.setDeclaredArity(-2),
                    "an invalid declared arity must reject");
            assertEquals(occurrence, call.getCallOccurrenceId(),
                    "a rejected occurrence-id update must preserve metadata");
            assertEquals(declaredArity, call.getDeclaredArity(),
                    "a rejected declared-arity update must preserve metadata");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testReachabilityPrunesDisconnectedUnionComponents() {
        EGraphNode.beginGraph();
        try {
            EGraphNode retained = variable("retained");
            EGraphNode retainedPeer = variable("retained");
            EGraphNode orphan = variable("orphan");
            EGraphNode orphanPeer = variable("orphan");
            EGraphNode.union(retained.getEClassRef(), retainedPeer.getEClassRef());
            EGraphNode.union(orphan.getEClassRef(), orphanPeer.getEClassRef());

            int removed = EGraphNode.retainReachable(List.of(retained));

            assertEquals(2, removed,
                    "reachable cleanup must prune a disconnected registered union component");
            assertTrue(retained.getEClassRef().equivalentTo(retainedPeer.getEClassRef()),
                    "reachable cleanup must preserve every member of a retained union component");
            assertEquals(0, EGraphNode.retainReachable(List.of(retained)),
                    "repeated reachable cleanup must be stable");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testEmptyReachabilityPrunesEntireArena() {
        EGraphNode.beginGraph();
        try {
            EGraphNode first = variable("emptyReachabilityFirst");
            EGraphNode second = variable("emptyReachabilitySecond");
            EGraphNode.EClassRef firstRef = first.getEClassRef();
            EGraphNode.EClassRef secondRef = second.getEClassRef();
            List<EGraphNode> escapedChildren = first.getChildren();
            EGraphNode.union(firstRef, secondRef);

            assertEquals(2, EGraphNode.retainReachable(List.of()),
                    "an empty retained-root set must prune every registered e-class");
            assertThrows(
                    IllegalStateException.class,
                    first::getEClassRef,
                    "a pruned e-class handle must be permanently retired");
            assertThrows(
                    IllegalStateException.class,
                    first::requireAdmittedGraph,
                    "a retired e-class must fail admission before traversal");
            assertThrows(
                    IllegalStateException.class,
                    first::getOpcode,
                    "a retired node must not expose its former opcode");
            assertThrows(
                    IllegalStateException.class,
                    first::getSourceName,
                    "a retired node must not expose its former source metadata");
            assertThrows(
                    IllegalStateException.class,
                    escapedChildren::size,
                    "an escaped child view must honor later retirement");
            assertThrows(
                    IllegalStateException.class,
                    () -> EGraphNode.union(firstRef, secondRef),
                    "a pruned union component must not re-enter erased union-find state");
            assertEquals(0, EGraphNode.retainReachable(List.of()),
                    "empty-root pruning must also clear retained union-find state");

            EGraphNode nullableFirst = variable("nullableReachabilityFirst");
            EGraphNode nullableSecond = variable("nullableReachabilitySecond");
            EGraphNode.union(
                    nullableFirst.getEClassRef(), nullableSecond.getEClassRef());
            assertEquals(
                    2,
                    EGraphNode.retainReachable(
                            java.util.Collections.singletonList(null)),
                    "an all-null root list must denote the same empty closure");
            assertThrows(
                    IllegalStateException.class,
                    nullableFirst::requireAdmittedGraph,
                    "all-null cleanup must retire every registered class");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testEGraphNodeAdmissionBoundaryIsFinal() {
        EGraphNode.beginGraph();
        try {
            SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
            EGraphNode root = new EGraphNode(
                    10_020, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                    Metatype.BOOLEAN, profile);
            root.setSourceName("true");
            root.setSourceType("Bool");
            root.setExactAlloyType(ExactAlloyType.boolType());
            NormalForm form = new NormalForm();
            form.addEClass(root);
            TheoryAlloyAdapter.adapt(List.of(form), profile);

            assertTrue(java.lang.reflect.Modifier.isFinal(
                            EGraphNode.class.getModifiers()),
                    "trusted e-node admission must have no subclass dispatch surface");
            assertTrue(form.isFrozenForCertification(),
                    "the NormalForm may publish frozen only after the arena freeze");
            assertEquals("true", root.getSourceName(),
                    "the certified source must retain the admitted value");
            assertThrows(
                    IllegalStateException.class,
                    () -> root.setSourceName("false"),
                    "mutation after the atomic arena freeze must reject");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void testBooleanRewritesRequireBooleanOperands() {
        EGraphNode relation = exactRelation("operandS");
        EGraphNode innerNot = node(Opcode.NOT, false, false, relation);
        EGraphNode outerNot = node(Opcode.NOT, false, false, innerNot);
        outerNot.saturate();
        assertEquals(Opcode.NOT, outerNot.getOpcode(),
                "double negation must not erase a relation-typed operand");
        assertEquals(Opcode.NOT, outerNot.getChildren().get(0).getOpcode(),
                "the malformed inner negation must remain observable");

        EGraphNode complementRelation = exactRelation("complementS");
        EGraphNode complement = node(
                Opcode.OR,
                true,
                true,
                complementRelation,
                node(Opcode.NOT, false, false, complementRelation));
        complement.saturate();
        assertEquals(Opcode.OR, complement.getOpcode(),
                "complement elimination must reject relation-typed operands");

        for (Opcode opcode : List.of(Opcode.IMPLIES, Opcode.IFF)) {
            EGraphNode malformed = node(
                    opcode, false, false, exactRelation(opcode + "S"), bool(true));
            NormalForm form = new NormalForm();
            form.addEClass(malformed);
            form.normalize();
            assertEquals(opcode, form.getMatrixEGraph().getOpcode(),
                    opcode + " must retain a relation-typed branch operand");
        }

        EGraphNode malformedIte = node(
                Opcode.ITE,
                false,
                false,
                exactRelation("iteConditionS"),
                bool(true),
                bool(false));
        NormalForm iteForm = new NormalForm();
        iteForm.addEClass(malformedIte);
        iteForm.normalize();
        assertEquals(Opcode.ITE, iteForm.getMatrixEGraph().getOpcode(),
                "Boolean ITE expansion must reject a relation-typed condition");

        EGraphNode forgedBooleanRelation = new EGraphNode(
                10_011,
                Opcode.GLOBALBINDING,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.BOOLEAN);
        forgedBooleanRelation.setSourceName("forgedBoolRelation");
        forgedBooleanRelation.setSourceType("Bool");
        forgedBooleanRelation.setExactAlloyType(ExactAlloyType.boolType());
        EGraphNode forgedComplement = node(
                Opcode.OR,
                true,
                true,
                forgedBooleanRelation,
                node(Opcode.NOT, false, false, forgedBooleanRelation));
        forgedComplement.saturate();
        assertEquals(Opcode.OR, forgedComplement.getOpcode(),
                "Boolean metadata cannot authorize a relation-only operand opcode");

        EGraphNode forgedJoin = new EGraphNode(
                10_012,
                Opcode.JOIN,
                List.of(exactRelation("joinLeft"), exactRelation("joinRight")),
                false,
                2,
                false,
                Metatype.BOOLEAN);
        forgedJoin.setSourceName("BOPEXPR_JOIN");
        forgedJoin.setSourceType("MIDDLENODE_BOPEXPR_JOIN");
        forgedJoin.setExactAlloyType(ExactAlloyType.boolType());
        EGraphNode forgedJoinComplement = node(
                Opcode.OR,
                true,
                true,
                forgedJoin,
                node(Opcode.NOT, false, false, forgedJoin));
        forgedJoinComplement.saturate();
        assertEquals(Opcode.OR, forgedJoinComplement.getOpcode(),
                "forged Boolean metadata cannot authorize a JOIN as a formula operand");
    }

    private static void testGenericBooleanTypeDoesNotAuthorizeOperators() {
        EGraphNode forgedAnd = new EGraphNode(
                10_013,
                Opcode.AND,
                List.of(bool(false), predicate("genericBoolGuard")),
                true,
                -1,
                true,
                Metatype.BOOLEAN);
        forgedAnd.setSourceType("Bool");
        forgedAnd.setExactAlloyType(ExactAlloyType.boolType());
        forgedAnd.saturate();
        assertEquals(Opcode.AND, forgedAnd.getOpcode(),
                "generic Bool type metadata must not authorize an AND rewrite");

        EGraphNode forgedIte = new EGraphNode(
                10_014,
                Opcode.ITE,
                List.of(predicate("genericIteCondition"), bool(true), bool(false)),
                false,
                3,
                false,
                Metatype.BOOLEAN);
        forgedIte.setSourceType("Bool");
        forgedIte.setExactAlloyType(ExactAlloyType.boolType());
        NormalForm form = new NormalForm();
        form.addEClass(forgedIte);
        form.normalize();
        assertEquals(Opcode.ITE, form.getMatrixEGraph().getOpcode(),
                "generic Bool type metadata must not authorize ITE expansion");
        assertEquals(Opcode.ITE, form.getCertificationMatrixEGraph().getOpcode(),
                "the certification source must retain an unauthorised ITE");
    }

    private static void testPublicMutationRevokesDerivedBooleanAuthority() {
        NormalForm form = new NormalForm();
        form.addEClass(node(
                Opcode.IMPLIES,
                false,
                false,
                predicate("staleAuthorityLeft"),
                predicate("staleAuthorityRight")));
        form.normalize();
        EGraphNode derivedOr = findOpcode(form.getMatrixEGraph(), Opcode.OR);
        assertTrue(derivedOr != null,
                "implication elimination must produce a derived OR witness");

        derivedOr.setSourceType("Rel(S)");
        derivedOr.setExactAlloyType(ExactAlloyType.unaryRelation("S"));
        derivedOr.setSourceType("Bool");
        derivedOr.setExactAlloyType(ExactAlloyType.boolType());
        derivedOr.setChildren(List.of(bool(false), predicate("staleAuthorityGuard")));
        derivedOr.saturate();

        assertEquals(Opcode.OR, derivedOr.getOpcode(),
                "public semantic mutation must revoke derived Boolean rewrite authority");
        assertEquals(2, derivedOr.getChildren().size(),
                "a stale derivation token must not authorize Boolean absorption");
    }

    private static void testMalformedTemporalDescendantRejectedBeforeDualization() {
        NormalForm parent = new NormalForm();
        NormalForm child = new NormalForm(parent, TemporalOp.ALWAYS, 10_015);
        EGraphNode malformedCall = new EGraphNode(
                10_016,
                Opcode.CALL,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.BOOLEAN);
        malformedCall.setSourceType("call/formula");
        malformedCall.setExactAlloyType(ExactAlloyType.boolType());
        child.addEClass(malformedCall);
        parent.addTemporalChild(child);

        assertThrows(
                IllegalStateException.class,
                parent::normalize,
                "normalization must admit every temporal descendant before parent rewrites");
        assertThrows(
                IllegalStateException.class,
                parent::pushTemporalNegations,
                "temporal dualization must not discard a malformed descendant CALL");
    }

    private static void testForgedTemporalReferenceRejectedAtAdapterBoundary() {
        NormalForm parent = new NormalForm();
        NormalForm child = new NormalForm(parent, TemporalOp.ALWAYS, 10_017);
        child.addEClass(bool(true));
        parent.addTemporalChild(child);

        EGraphNode forged = node(Opcode.REF, false, false);
        forged.setSourceName("temporal[0:1]");
        forged.setSourceType("Bool");
        forged.setExactAlloyType(ExactAlloyType.boolType());
        parent.addEClass(forged);

        assertThrows(
                IllegalStateException.class,
                parent::requireAdmittedTemporalTree,
                "a direct temporal-looking REF must lack owner-bound authority");
        assertThrows(
                IllegalStateException.class,
                () -> TheoryAlloyAdapter.adapt(
                        List.of(parent, child), SemanticProfile.alloyOverflowForbidding()),
                "the certified adapter must reject a forged temporal reference");
    }

    private static void testUnreferencedTemporalChildRejected() {
        NormalForm parent = new NormalForm();
        parent.addEClass(bool(true));
        NormalForm child = new NormalForm(parent, TemporalOp.ALWAYS, 10_018);
        child.addEClass(bool(true));
        parent.addTemporalChild(child);

        assertThrows(
                IllegalStateException.class,
                parent::requireAdmittedTemporalTree,
                "every temporal child phase must have an owner-issued matrix reference");
        assertThrows(
                IllegalStateException.class,
                parent::freezeForCertification,
                "an unreferenced temporal child must not cross the freeze boundary");
    }

    private static void testTemporalSourceVisitCannotBorrowEdges() {
        is.fivefivefive.ACGN.asg.AugmentedNode until =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        -5, 20,
                        new is.fivefivefive.ACGN.alloy.MiddleSymbol("BOP_UNTIL"));
        is.fivefivefive.ACGN.asg.AugmentedNode left =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        1, 101,
                        new is.fivefivefive.ACGN.alloy.ConstSymbol("true", true, false));
        is.fivefivefive.ACGN.asg.AugmentedNode right =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        1, 102,
                        new is.fivefivefive.ACGN.alloy.ConstSymbol("false", true, false));
        is.fivefivefive.ACGN.asg.AugmentedNode alternate =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        1, 103,
                        new is.fivefivefive.ACGN.alloy.ConstSymbol("true", true, false));
        for (is.fivefivefive.ACGN.asg.AugmentedNode node
                : List.of(until, left, right, alternate)) {
            node.setDefaultExactType(ExactAlloyType.boolType());
        }
        is.fivefivefive.ACGN.asg.Multigraph graph =
                new is.fivefivefive.ACGN.asg.Multigraph(
                        new java.util.HashSet<>(List.of(until, left, right, alternate)),
                        new ArrayList<>(),
                        until);
        graph.connect(until, left, graph, 1, 1);
        graph.connect(until, right, graph, 1, 2);
        graph.connect(until, alternate, graph, 2, 2);
        graph.updateTimeOfVisitMap(until, 2);

        assertThrows(
                IllegalStateException.class,
                () -> new is.fivefivefive.CanDis.ir.IRAgent(graph).computeNormalForm(),
                "a temporal occurrence cannot borrow a complete child bucket from another visit");
    }

    private static void testTemporalAuthorityTracksLiveSourceGraph() {
        is.fivefivefive.ACGN.asg.AugmentedNode negation =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        -6, 5,
                        new is.fivefivefive.ACGN.alloy.MiddleSymbol("UNOPF_NOT"));
        is.fivefivefive.ACGN.asg.AugmentedNode after =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        -6, 11,
                        new is.fivefivefive.ACGN.alloy.MiddleSymbol("UNOPF_AFTER"));
        is.fivefivefive.ACGN.asg.AugmentedNode truth =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        1, 104,
                        new is.fivefivefive.ACGN.alloy.ConstSymbol("true", true, false));
        negation.setDefaultExactType(ExactAlloyType.boolType());
        after.setDefaultExactType(ExactAlloyType.boolType());
        truth.setDefaultExactType(ExactAlloyType.boolType());
        is.fivefivefive.ACGN.asg.Multigraph graph =
                new is.fivefivefive.ACGN.asg.Multigraph(
                        new java.util.HashSet<>(List.of(negation, after, truth)),
                        new ArrayList<>(),
                        negation);
        graph.connect(negation, after, graph, 1, 1);
        graph.connect(after, truth, graph, 1, 1);
        graph.updateTimeOfVisitMap(negation, 1);
        graph.updateTimeOfVisitMap(after, 1);
        is.fivefivefive.CanDis.ir.IRAgent agent =
                new is.fivefivefive.CanDis.ir.IRAgent(graph);
        agent.computeNormalForm();
        NormalForm owner = agent.normalForms().get(0);

        graph.getVertices().remove(truth);
        assertThrows(
                IllegalStateException.class,
                owner::requireAdmittedTemporalTree,
                "removing only a temporal child vertex must revoke its occurrence claim");
        assertThrows(
                IllegalStateException.class,
                () -> TheoryAlloyAdapter.adapt(
                        agent.normalForms(), SemanticProfile.alloyOverflowForbidding()),
                "direct adaptation must reject a missing temporal child vertex");

        graph.getVertices().add(truth);
        owner.requireAdmittedTemporalTree();

        graph.getVertices().remove(negation);
        assertThrows(
                IllegalStateException.class,
                owner::requireAdmittedTemporalTree,
                "removing the captured graph root must revoke temporal evidence");
        assertThrows(
                IllegalStateException.class,
                () -> TheoryAlloyAdapter.adapt(
                        agent.normalForms(), SemanticProfile.alloyOverflowForbidding()),
                "direct adaptation must reject a missing captured graph root");

        graph.getVertices().add(negation);
        owner.requireAdmittedTemporalTree();
        graph.getEdges().clear();
        assertThrows(
                IllegalStateException.class,
                owner::requireAdmittedTemporalTree,
                "removing temporal source edges must revoke its occurrence claim");
        assertThrows(
                IllegalStateException.class,
                () -> TheoryAlloyAdapter.adapt(
                        agent.normalForms(), SemanticProfile.alloyOverflowForbidding()),
                "direct adaptation must revalidate the live temporal source claim");
    }

    private static void testPreparedTemporalAuthorityDropsLiveGraphDependency() {
        is.fivefivefive.ACGN.asg.AugmentedNode after =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        -6, 11,
                        new is.fivefivefive.ACGN.alloy.MiddleSymbol("UNOPF_AFTER"));
        is.fivefivefive.ACGN.asg.AugmentedNode truth =
                new is.fivefivefive.ACGN.asg.AugmentedNode(
                        1, 105,
                        new is.fivefivefive.ACGN.alloy.ConstSymbol("true", true, false));
        after.setDefaultExactType(ExactAlloyType.boolType());
        truth.setDefaultExactType(ExactAlloyType.boolType());
        is.fivefivefive.ACGN.asg.Multigraph graph =
                new is.fivefivefive.ACGN.asg.Multigraph(
                        new java.util.HashSet<>(List.of(after, truth)),
                        new ArrayList<>(),
                        after);
        graph.connect(after, truth, graph, 1, 1);
        graph.updateTimeOfVisitMap(after, 1);
        is.fivefivefive.CanDis.ir.IRAgent agent =
                new is.fivefivefive.CanDis.ir.IRAgent(graph);
        agent.computeNormalForm();

        CanonicalDistance.Prepared prepared =
                CanonicalDistance.prepare(agent.normalForms());
        graph.getVertices().clear();
        graph.getEdges().clear();

        agent.normalForms().get(0).requireAdmittedTemporalTree();
        assertTrue(prepared.canonicalSize() > 0,
                "a prepared temporal snapshot must remain independent of its released MASG");
    }

    private static void testCrossArenaChildrenRejected() {
        EGraphNode foreignProfile = new EGraphNode(
                10_006, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                Metatype.BOOLEAN, SemanticProfile.alloyModular());
        foreignProfile.setSourceName("true");
        foreignProfile.setSourceType("Bool");
        foreignProfile.setExactAlloyType(ExactAlloyType.boolType());
        EGraphNode profileParent = node(
                Opcode.AND, true, true, variable("profileLocal"));
        assertThrows(IllegalArgumentException.class,
                () -> profileParent.addChild(foreignProfile),
                "a child from another semantic profile must be rejected");

        EGraphNode.beginGraph();
        EGraphNode foreign;
        try {
            foreign = bool(true);
            EGraphNode.beginGraph();
            EGraphNode parent = node(
                    Opcode.AND, true, true, variable("arenaLocal"));
            assertThrows(IllegalArgumentException.class,
                    () -> parent.addChild(foreign),
                    "a child from another e-graph arena must be rejected");
        } finally {
            EGraphNode.endGraph();
            EGraphNode.beginGraph();
        }
    }

    private static void testCrossArenaSnapshotsAndComparisonsRejected() {
        EGraphNode.beginGraph();
        EGraphNode first = node(
                Opcode.AND, true, true, variable("snapshotLocal"), bool(true));
        EGraphNode firstPeer = node(
                Opcode.AND, true, true, variable("peerLocal"), bool(false));
        EGraphNode.beginGraph();
        try {
            EGraphNode second = node(
                    Opcode.AND, true, true, variable("snapshotLocal"), bool(true));
            assertEquals(false, EGraphNode.sameSemanticInvocation(first, second),
                    "structural equality must not cross e-graph arenas");
            assertEquals(false, first.sameFlatOperatorInstance(second),
                    "flat-operator equality must not cross e-graph arenas");

            first.saturate();
            first.freezeForCertification();
            for (EGraphNode alternative : first.getEClass().getNodes()) {
                assertThrows(IllegalStateException.class,
                        () -> alternative.setSourceName("tampered"),
                        "every preserved snapshot must freeze in its owning arena");
            }
            assertTrue(firstPeer.sameFlatOperatorInstance(firstPeer),
                    "same-arena Boolean flat operators retain their license");
        } finally {
            EGraphNode.endGraph();
            EGraphNode.beginGraph();
        }
    }

    private static void testMalformedBranchConnectiveRetained() {
        EGraphNode malformedImplies = new EGraphNode(
                10_008,
                Opcode.IMPLIES,
                Arrays.asList(
                        node(Opcode.SOME, false, false, global("S")),
                        node(Opcode.NO, false, false, global("S"))),
                false,
                2,
                false,
                Metatype.SET);
        malformedImplies.setSourceType("Rel(S)");
        malformedImplies.setExactAlloyType(ExactAlloyType.unaryRelation("S"));
        NormalForm form = new NormalForm();
        form.addEClass(malformedImplies);
        form.normalize();
        assertEquals(Opcode.IMPLIES, form.getMatrixEGraph().getOpcode(),
                "an ill-typed IMPLIES must not be rewritten before certification");
    }

    private static void testSetIdentitySaturation() {
        EGraphNode inNone = node(Opcode.IN, false, false, variable("inNoneX"), global("none"));
        inNone.saturate();
        assertEquals(Opcode.IN, inNone.getOpcode(),
                "x in none must remain when x has no nonemptiness proof");

        EGraphNode noneInNone = node(
                Opcode.IN, false, false, global("none"), global("none"));
        noneInNone.saturate();
        assertEquals(Opcode.CONSTANT, noneInNone.getOpcode(),
                "none in none must collapse to true");
        assertEquals("true", noneInNone.getSourceName(),
                "none is a subset of none");

        EGraphNode typedNone = exactNone(1);
        EGraphNode noneInTypedRelation = node(
                Opcode.IN, false, false, typedNone, exactRelation("TypedSet"));
        noneInTypedRelation.saturate();
        assertEquals(Opcode.CONSTANT, noneInTypedRelation.getOpcode(),
                "an exact empty relation must be a subset of a same-arity relation");

        EGraphNode mismatchedEmptySubset = node(
                Opcode.IN,
                false,
                false,
                exactNone(1),
                exactRelation("Binary", "Left", "Right"));
        mismatchedEmptySubset.saturate();
        assertEquals(Opcode.IN, mismatchedEmptySubset.getOpcode(),
                "empty-subset normalization must not cross exact relation arities");

        EGraphNode emptyArrow = relationNode(
                Opcode.ARROW,
                ExactAlloyType.emptyRelation(2),
                exactNone(1),
                exactRelation("ArrowRight"));
        emptyArrow.saturate();
        assertEquals(Opcode.GLOBALBINDING, emptyArrow.getOpcode(),
                "an empty relational product must derive an empty relation");
        assertEquals("none", emptyArrow.getSourceName(),
                "empty product must preserve the authenticated empty identity");

        EGraphNode emptyJoin = relationNode(
                Opcode.JOIN,
                ExactAlloyType.emptyRelation(1),
                exactNone(1),
                exactRelation("JoinRight", "Middle", "Last"));
        emptyJoin.saturate();
        assertEquals(Opcode.GLOBALBINDING, emptyJoin.getOpcode(),
                "an empty relational composition must derive an empty relation");
        assertEquals("none", emptyJoin.getSourceName(),
                "empty join must preserve the authenticated empty identity");

        EGraphNode emptyTranspose = relationNode(
                Opcode.TRANSPOSE,
                ExactAlloyType.emptyRelation(2),
                exactNone(2));
        emptyTranspose.saturate();
        assertEquals(Opcode.GLOBALBINDING, emptyTranspose.getOpcode(),
                "converse of an authenticated empty binary relation is empty");
        assertEquals("none", emptyTranspose.getSourceName(),
                "empty converse must retain authenticated empty identity");

        EGraphNode emptyClosure = relationNode(
                Opcode.CLOSURE,
                ExactAlloyType.emptyRelation(2),
                exactNone(2));
        emptyClosure.saturate();
        assertEquals(Opcode.GLOBALBINDING, emptyClosure.getOpcode(),
                "transitive closure of an authenticated empty relation is empty");

        EGraphNode emptyReflexiveClosure = relationNode(
                Opcode.RCLOSURE,
                ExactAlloyType.relation(List.of("univ", "univ")),
                exactNone(2));
        emptyReflexiveClosure.saturate();
        assertEquals(Opcode.CONSTANT, emptyReflexiveClosure.getOpcode(),
                "reflexive closure of an authenticated empty relation is iden");
        assertEquals("iden", emptyReflexiveClosure.getSourceName(),
                "empty reflexive closure must retain authenticated iden identity");

        EGraphNode forgedEmpty = global("none");
        forgedEmpty.setExactAlloyType(ExactAlloyType.emptyRelation(2));
        EGraphNode forgedEmptyTranspose = relationNode(
                Opcode.TRANSPOSE,
                ExactAlloyType.emptyRelation(2),
                forgedEmpty);
        forgedEmptyTranspose.saturate();
        assertEquals(Opcode.TRANSPOSE, forgedEmptyTranspose.getOpcode(),
                "an unauthenticated none spelling must not authorize empty converse");

        EGraphNode syntheticOneInNone = node(
                Opcode.IN,
                false,
                false,
                node(Opcode.ONE, false, false, global("S")),
                global("none"));
        syntheticOneInNone.saturate();
        assertEquals(Opcode.IN, syntheticOneInNone.getOpcode(),
                "a synthetic ONE label is not a nonemptiness certificate");

        EGraphNode inUniv = node(Opcode.IN, false, false, variable("inUnivX"), global("univ"));
        inUniv.saturate();
        assertEquals(Opcode.CONSTANT, inUniv.getOpcode(), "x in univ must collapse to true");
        assertEquals("true", inUniv.getSourceName(), "x in univ must collapse to true");

        EGraphNode userNone = node(
                Opcode.SOME, false, false, global("None"));
        userNone.saturate();
        assertEquals(Opcode.SOME, userNone.getOpcode(),
                "a user signature named None must not become the empty relation");
        EGraphNode userUniv = node(
                Opcode.IN, false, false, variable("userUnivX"), global("Univ"));
        userUniv.saturate();
        assertEquals(Opcode.IN, userUniv.getOpcode(),
                "a user signature named Univ must not become the universal relation");

        EGraphNode intersectNone = node(Opcode.INTERSECT, true, true, global("R"), global("none"));
        intersectNone.saturate();
        assertEquals(Opcode.GLOBALBINDING, intersectNone.getOpcode(), "R & none must collapse to none");
        assertEquals("none", intersectNone.getSourceName(), "R & none must collapse to none");

        EGraphNode plusNone = node(Opcode.PLUS, true, true, global("R"), global("none"));
        plusNone.saturate();
        assertEquals(Opcode.GLOBALBINDING, plusNone.getOpcode(), "R + none must collapse to R");
        assertEquals("R", plusNone.getSourceName(), "R + none must collapse to R");

        EGraphNode selfDifference = node(
                Opcode.MINUS, false, false, global("R"), global("R"));
        selfDifference.saturate();
        assertEquals(Opcode.GLOBALBINDING, selfDifference.getOpcode(),
                "R - R must collapse to the empty relation");
        assertEquals(SigSymbol.BUILTIN_NONE_IDENTITY,
                selfDifference.getSemanticIdentity(),
                "R - R must carry authenticated empty-relation identity");
    }

    private static void testReservedSetIdentityRequiresAuthority() {
        EGraphNode forged = new EGraphNode(
                10_030,
                Opcode.GLOBALBINDING,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.SET);
        forged.setSourceName("userDefinedSet");
        forged.setSourceType("Signature");
        forged.setExactAlloyType(ExactAlloyType.unaryRelation("UserDefinedSet"));
        assertThrows(
                IllegalArgumentException.class,
                () -> forged.setSemanticIdentity(SigSymbol.BUILTIN_UNIV_IDENTITY),
                "public metadata mutation must not mint reserved univ authority");
        assertTrue(forged.getSemanticIdentity() == null,
                "rejected reserved identity mutation must preserve prior metadata");

        EGraphNode spellingOnly = new EGraphNode(
                10_031,
                Opcode.GLOBALBINDING,
                new ArrayList<>(),
                false,
                0,
                false,
                Metatype.SET);
        spellingOnly.setSourceName("univ");
        spellingOnly.setSourceType("Signature");
        spellingOnly.setExactAlloyType(ExactAlloyType.unaryRelation("univ"));
        EGraphNode unprovedMembership = node(
                Opcode.IN, false, false, variable("unprovedUnivX"), spellingOnly);
        unprovedMembership.saturate();
        assertEquals(Opcode.IN, unprovedMembership.getOpcode(),
                "reserved spelling without factory authority must remain explicit");

        NormalForm unprovedForm = new NormalForm();
        unprovedForm.addEClass(node(
                Opcode.IN, false, false,
                variable("unprovedNormalFormUnivX"), spellingOnly));
        unprovedForm.normalize();
        assertEquals(Opcode.IN, unprovedForm.getMatrixEGraph().getOpcode(),
                "NormalForm must not trust a reserved spelling without authority");

        EGraphNode authenticUniv = EGraphNode.builtinSetConstant(
                10_032,
                SigSymbol.builtinUniv(),
                ExactAlloyType.unaryRelation("univ"),
                SemanticProfile.alloyOverflowForbidding());
        EGraphNode authenticatedMembership = node(
                Opcode.IN, false, false,
                variable("authenticatedUnivX"), authenticUniv);
        authenticatedMembership.saturate();
        assertEquals(Opcode.CONSTANT, authenticatedMembership.getOpcode(),
                "the dedicated built-in factory must retain the valid subset law");
        assertEquals("true", authenticatedMembership.getSourceName(),
                "x in authenticated univ must fold to true");
    }

    private static void testNestedDifferenceRequiresParserAuthority() {
        ExactAlloyType unary = ExactAlloyType.unaryRelation("U");
        EGraphNode left = global("nestedDifferenceLeft");
        left.setExactAlloyType(unary);
        EGraphNode firstRemoval = global("nestedDifferenceFirstRemoval");
        firstRemoval.setExactAlloyType(unary);
        EGraphNode secondRemoval = global("nestedDifferenceSecondRemoval");
        secondRemoval.setExactAlloyType(unary);
        EGraphNode inner = relationNode(
                Opcode.MINUS, unary, left, firstRemoval);
        EGraphNode outer = relationNode(
                Opcode.MINUS, unary, inner, secondRemoval);

        outer.saturate();

        assertEquals(Opcode.MINUS, outer.getOpcode(),
                "synthetic exact types must not authorize difference-chain factoring");
        assertEquals(Opcode.MINUS, outer.getChildren().get(0).getOpcode(),
                "an unauthenticated left-nested difference must remain explicit");

        EGraphNode rightLeft = global("rightNestedDifferenceLeft");
        rightLeft.setExactAlloyType(unary);
        EGraphNode rightRemoved = global("rightNestedDifferenceRemoved");
        rightRemoved.setExactAlloyType(unary);
        EGraphNode rightRestored = global("rightNestedDifferenceRestored");
        rightRestored.setExactAlloyType(unary);
        EGraphNode rightInner = relationNode(
                Opcode.MINUS, unary, rightRemoved, rightRestored);
        EGraphNode rightOuter = relationNode(
                Opcode.MINUS, unary, rightLeft, rightInner);
        rightOuter.saturate();
        assertEquals(Opcode.MINUS, rightOuter.getOpcode(),
                "synthetic evidence must not authorize right-difference expansion");
        assertEquals(Opcode.MINUS, rightOuter.getChildren().get(1).getOpcode(),
                "an unauthenticated right-nested difference must remain explicit");

        EGraphNode intersectionLeft = global("intersectionDifferenceLeft");
        intersectionLeft.setExactAlloyType(unary);
        EGraphNode intersectionRemoved = global("intersectionDifferenceRemoved");
        intersectionRemoved.setExactAlloyType(unary);
        EGraphNode intersectionOther = global("intersectionDifferenceOther");
        intersectionOther.setExactAlloyType(unary);
        EGraphNode difference = relationNode(
                Opcode.MINUS, unary, intersectionLeft, intersectionRemoved);
        EGraphNode intersection = new EGraphNode(
                10_035,
                Opcode.INTERSECT,
                new ArrayList<>(List.of(difference, intersectionOther)),
                true,
                -1,
                true,
                Metatype.SET);
        intersection.setSourceType("Rel(U)");
        intersection.setExactAlloyType(unary);
        intersection.saturate();
        assertEquals(Opcode.INTERSECT, intersection.getOpcode(),
                "synthetic evidence must not extract a difference from intersection");

        ExactAlloyType binary = ExactAlloyType.relation(List.of("U", "U"));
        EGraphNode productLeftA = global("productDifferenceLeftA");
        productLeftA.setExactAlloyType(unary);
        EGraphNode productLeftB = global("productDifferenceLeftB");
        productLeftB.setExactAlloyType(unary);
        EGraphNode productRightA = global("productDifferenceRightA");
        productRightA.setExactAlloyType(unary);
        EGraphNode productRightB = global("productDifferenceRightB");
        productRightB.setExactAlloyType(unary);
        EGraphNode leftProduct = relationNode(
                Opcode.ARROW, binary, productLeftA, productLeftB);
        EGraphNode rightProduct = relationNode(
                Opcode.ARROW, binary, productRightA, productRightB);
        EGraphNode productDifference = relationNode(
                Opcode.MINUS, binary, leftProduct, rightProduct);
        productDifference.saturate();
        assertEquals(Opcode.MINUS, productDifference.getOpcode(),
                "synthetic exact types must not authorize product-difference factoring");
        assertEquals(Opcode.ARROW,
                productDifference.getChildren().get(0).getOpcode(),
                "an unauthenticated product difference must remain explicit");

        EGraphNode productIntersection = new EGraphNode(
                10_036,
                Opcode.INTERSECT,
                new ArrayList<>(List.of(leftProduct, rightProduct)),
                true,
                -1,
                true,
                Metatype.SET);
        productIntersection.setSourceType("Rel(U->U)");
        productIntersection.setExactAlloyType(binary);
        productIntersection.saturate();
        assertEquals(Opcode.INTERSECT, productIntersection.getOpcode(),
                "synthetic exact types must not authorize product-intersection factoring");
        assertEquals(Opcode.ARROW,
                productIntersection.getChildren().get(0).getOpcode(),
                "an unauthenticated product intersection must remain explicit");

        EGraphNode restrictionSetA = global("restrictionSyntheticA");
        restrictionSetA.setExactAlloyType(unary);
        EGraphNode restrictionSetB = global("restrictionSyntheticB");
        restrictionSetB.setExactAlloyType(unary);
        EGraphNode restrictedRelation = global("restrictionSyntheticR");
        restrictedRelation.setExactAlloyType(binary);
        EGraphNode domainA = relationNode(
                Opcode.DOMAIN, binary, restrictionSetA, restrictedRelation);
        EGraphNode domainB = relationNode(
                Opcode.DOMAIN, binary, restrictionSetB, restrictedRelation);
        EGraphNode restrictionUnion = new EGraphNode(
                10_037,
                Opcode.PLUS,
                new ArrayList<>(List.of(domainA, domainB)),
                true,
                -1,
                true,
                Metatype.SET);
        restrictionUnion.setSourceType("Rel(U->U)");
        restrictionUnion.setExactAlloyType(binary);
        restrictionUnion.saturate();
        assertEquals(Opcode.PLUS, restrictionUnion.getOpcode(),
                "synthetic exact types must not authorize restriction factoring");
        assertEquals(Opcode.DOMAIN,
                restrictionUnion.getChildren().get(0).getOpcode(),
                "an unauthenticated restriction union must remain explicit");

        EGraphNode transposeLeft = global("transposeSyntheticLeft");
        transposeLeft.setExactAlloyType(binary);
        EGraphNode transposeRight = global("transposeSyntheticRight");
        transposeRight.setExactAlloyType(binary);
        EGraphNode syntheticJoin = relationNode(
                Opcode.JOIN, binary, transposeLeft, transposeRight);
        EGraphNode transposeJoin = relationNode(
                Opcode.TRANSPOSE, binary, syntheticJoin);
        transposeJoin.saturate();
        assertEquals(Opcode.TRANSPOSE, transposeJoin.getOpcode(),
                "synthetic exact types must not authorize converse-JOIN reversal");
        assertEquals(Opcode.JOIN,
                transposeJoin.getChildren().get(0).getOpcode(),
                "an unauthenticated converse-JOIN term must remain explicit");

        EGraphNode closureRelation = global("transposeSyntheticClosureRelation");
        closureRelation.setExactAlloyType(binary);
        EGraphNode syntheticClosure = relationNode(
                Opcode.CLOSURE, binary, closureRelation);
        EGraphNode transposeClosure = relationNode(
                Opcode.TRANSPOSE, binary, syntheticClosure);
        transposeClosure.saturate();
        assertEquals(Opcode.TRANSPOSE, transposeClosure.getOpcode(),
                "synthetic exact types must not authorize converse-closure commutation");
        assertEquals(Opcode.CLOSURE,
                transposeClosure.getChildren().get(0).getOpcode(),
                "an unauthenticated converse-closure term must remain explicit");

        EGraphNode restrictionSet = global("transposeSyntheticRestrictionSet");
        restrictionSet.setExactAlloyType(unary);
        EGraphNode restrictionRelation = global(
                "transposeSyntheticRestrictionRelation");
        restrictionRelation.setExactAlloyType(binary);
        EGraphNode syntheticDomain = relationNode(
                Opcode.DOMAIN, binary, restrictionSet, restrictionRelation);
        EGraphNode transposeDomain = relationNode(
                Opcode.TRANSPOSE, binary, syntheticDomain);
        transposeDomain.saturate();
        assertEquals(Opcode.TRANSPOSE, transposeDomain.getOpcode(),
                "synthetic exact types must not authorize converse-restriction swapping");
        assertEquals(Opcode.DOMAIN,
                transposeDomain.getChildren().get(0).getOpcode(),
                "an unauthenticated converse-restriction term must remain explicit");

        EGraphNode contextualDomain = relationNode(
                Opcode.DOMAIN, binary, restrictionSet, transposeLeft);
        EGraphNode contextualJoin = relationNode(
                Opcode.JOIN, binary, contextualDomain, transposeRight);
        contextualJoin.saturate();
        assertEquals(Opcode.JOIN, contextualJoin.getOpcode(),
                "synthetic exact types must not authorize restriction movement through JOIN");
        assertEquals(Opcode.DOMAIN,
                contextualJoin.getChildren().get(0).getOpcode(),
                "an unauthenticated contextual restriction must remain on its source operand");

        EGraphNode syntheticUnaryRelation = global(
                "syntheticUnaryRestrictionRelation");
        syntheticUnaryRelation.setExactAlloyType(unary);
        EGraphNode syntheticUnaryRange = relationNode(
                Opcode.RANGE, unary, syntheticUnaryRelation, restrictionSet);
        syntheticUnaryRange.saturate();
        assertEquals(Opcode.RANGE, syntheticUnaryRange.getOpcode(),
                "synthetic exact types must not equate unary restriction sides");

        EGraphNode syntheticReflexiveSubset = new EGraphNode(
                10_038,
                Opcode.IN,
                new ArrayList<>(List.of(
                        syntheticUnaryRelation, syntheticUnaryRelation)),
                false,
                2,
                false,
                Metatype.BOOLEAN);
        syntheticReflexiveSubset.setSourceName("BOP_IN");
        syntheticReflexiveSubset.setSourceType("MIDDLENODE_BOP_IN");
        syntheticReflexiveSubset.setExactAlloyType(ExactAlloyType.boolType());
        syntheticReflexiveSubset.saturate();
        assertEquals(Opcode.IN, syntheticReflexiveSubset.getOpcode(),
                "synthetic exact types must not authorize reflexive comparison folding");

        EGraphNode syntheticUnion = new EGraphNode(
                10_039,
                Opcode.PLUS,
                new ArrayList<>(List.of(
                        syntheticUnaryRelation, restrictionSet)),
                true,
                -1,
                true,
                Metatype.SET);
        syntheticUnion.setSourceName("BOPEXPR_PLUS");
        syntheticUnion.setSourceType("MIDDLENODE_BOPEXPR_PLUS");
        syntheticUnion.setExactAlloyType(unary);
        EGraphNode syntheticContainedInUnion = new EGraphNode(
                10_040,
                Opcode.IN,
                new ArrayList<>(List.of(
                        syntheticUnaryRelation, syntheticUnion)),
                false,
                2,
                false,
                Metatype.BOOLEAN);
        syntheticContainedInUnion.setSourceName("BOP_IN");
        syntheticContainedInUnion.setSourceType("MIDDLENODE_BOP_IN");
        syntheticContainedInUnion.setExactAlloyType(ExactAlloyType.boolType());
        syntheticContainedInUnion.saturate();
        assertEquals(Opcode.IN, syntheticContainedInUnion.getOpcode(),
                "synthetic exact types must not authorize structural subset proofs");

        EGraphNode syntheticUnionSubset = new EGraphNode(
                10_041,
                Opcode.IN,
                new ArrayList<>(List.of(syntheticUnion, restrictionSet)),
                false,
                2,
                false,
                Metatype.BOOLEAN);
        syntheticUnionSubset.setSourceName("BOP_IN");
        syntheticUnionSubset.setSourceType("MIDDLENODE_BOP_IN");
        syntheticUnionSubset.setExactAlloyType(ExactAlloyType.boolType());
        syntheticUnionSubset.saturate();
        assertEquals(Opcode.IN, syntheticUnionSubset.getOpcode(),
                "synthetic exact types must not authorize subset-lattice expansion");

        EGraphNode syntheticDifference = relationNode(
                Opcode.MINUS,
                unary,
                syntheticUnaryRelation,
                restrictionSet);
        EGraphNode syntheticSomeDifference = new EGraphNode(
                10_042,
                Opcode.SOME,
                new ArrayList<>(List.of(syntheticDifference)),
                false,
                1,
                false,
                Metatype.BOOLEAN);
        syntheticSomeDifference.setSourceName("UNOPF_SOME");
        syntheticSomeDifference.setSourceType("MIDDLENODE_UNOPF_SOME");
        syntheticSomeDifference.setExactAlloyType(ExactAlloyType.boolType());
        syntheticSomeDifference.saturate();
        assertEquals(Opcode.SOME, syntheticSomeDifference.getOpcode(),
                "synthetic difference metadata must not authorize the cardinality bridge");

        EGraphNode syntheticPartitionIntersection = new EGraphNode(
                10_043,
                Opcode.INTERSECT,
                new ArrayList<>(List.of(syntheticDifference, restrictionSet)),
                true,
                -1,
                true,
                Metatype.SET);
        syntheticPartitionIntersection.setSourceName("BOPEXPR_INTERSECT");
        syntheticPartitionIntersection.setSourceType(
                "MIDDLENODE_BOPEXPR_INTERSECT");
        syntheticPartitionIntersection.setExactAlloyType(unary);
        syntheticPartitionIntersection.saturate();
        assertEquals(Opcode.INTERSECT,
                syntheticPartitionIntersection.getOpcode(),
                "synthetic difference metadata must not authorize disjointness");

        EGraphNode syntheticMeet = new EGraphNode(
                10_044,
                Opcode.INTERSECT,
                new ArrayList<>(List.of(syntheticUnaryRelation, restrictionSet)),
                true,
                -1,
                true,
                Metatype.SET);
        syntheticMeet.setSourceName("BOPEXPR_INTERSECT");
        syntheticMeet.setSourceType("MIDDLENODE_BOPEXPR_INTERSECT");
        syntheticMeet.setExactAlloyType(unary);
        EGraphNode syntheticPartitionUnion = new EGraphNode(
                10_045,
                Opcode.PLUS,
                new ArrayList<>(List.of(syntheticDifference, syntheticMeet)),
                true,
                -1,
                true,
                Metatype.SET);
        syntheticPartitionUnion.setSourceName("BOPEXPR_PLUS");
        syntheticPartitionUnion.setSourceType("MIDDLENODE_BOPEXPR_PLUS");
        syntheticPartitionUnion.setExactAlloyType(unary);
        syntheticPartitionUnion.saturate();
        assertEquals(Opcode.PLUS, syntheticPartitionUnion.getOpcode(),
                "synthetic difference metadata must not authorize partition recombination");

        EGraphNode typedNone = exactNone(1);
        EGraphNode syntheticSubsetNone = new EGraphNode(
                10_046,
                Opcode.IN,
                new ArrayList<>(List.of(syntheticUnaryRelation, typedNone)),
                false,
                2,
                false,
                Metatype.BOOLEAN);
        syntheticSubsetNone.setSourceName("BOP_IN");
        syntheticSubsetNone.setSourceType("MIDDLENODE_BOP_IN");
        syntheticSubsetNone.setExactAlloyType(ExactAlloyType.boolType());
        syntheticSubsetNone.saturate();
        assertEquals(Opcode.IN, syntheticSubsetNone.getOpcode(),
                "typed none cannot bridge an unauthenticated relation occurrence");
    }

    private static void testImplicationSaturation() {
        EGraphNode implication = node(Opcode.IMPLIES, false, false, variable("impliesA"), variable("impliesB"));
        implication.saturate();
        assertEquals(Opcode.OR, implication.getOpcode(), "A implies B must become not A or B");
        assertTrue(containsOpcode(implication, Opcode.NOT), "A implies B must negate the antecedent");

        EGraphNode falseAntecedent = node(Opcode.IMPLIES, false, false, bool(false), variable("impliesX"));
        falseAntecedent.saturate();
        assertEquals(Opcode.CONSTANT, falseAntecedent.getOpcode(), "false implies A must collapse to true");
        assertEquals("true", falseAntecedent.getSourceName(), "false implies A must collapse to true");
    }

    private static void testEmptyDomainQuantifierRewrite() {
        NormalForm existential = new NormalForm();
        existential.addEClass(node(Opcode.EXISTS, false, false, relDeclOfType("none", "x"), predicate("P", variable("x"))));
        existential.normalize();
        assertEquals(Opcode.CONSTANT, existential.getMatrixEGraph().getOpcode(), "some x: none | P must be false");
        assertEquals("false", existential.getMatrixEGraph().getSourceName(), "some x: none | P must be false");
        assertEquals(0, existential.getMatrixQuantiVars().size(), "empty-domain existential must not retain a binding");

        NormalForm universal = new NormalForm();
        universal.addEClass(node(Opcode.FORALL, false, false, relDeclOfType("none", "x"), predicate("P", variable("x"))));
        universal.normalize();
        assertEquals(Opcode.CONSTANT, universal.getMatrixEGraph().getOpcode(), "all x: none | P must be true");
        assertEquals("true", universal.getMatrixEGraph().getSourceName(), "all x: none | P must be true");
        assertEquals(0, universal.getMatrixQuantiVars().size(), "empty-domain universal must not retain a binding");

        NormalForm notOne = new NormalForm();
        notOne.addEClass(node(
                Opcode.NOT,
                false,
                false,
                node(Opcode.ONE, false, false,
                        relDeclOfType("none", "x"), predicate("P", variable("x")))));
        notOne.normalize();
        assertEquals(Opcode.CONSTANT, notOne.getMatrixEGraph().getOpcode(),
                "not one x: none | P must be true");
        assertEquals("true", notOne.getMatrixEGraph().getSourceName(),
                "not one x: none | P must be true");
        assertEquals(0, notOne.getMatrixQuantiVars().size(),
                "empty-domain NOTONE must not retain a binding");

        NormalForm notLone = new NormalForm();
        notLone.addEClass(node(
                Opcode.NOT,
                false,
                false,
                node(Opcode.LONE, false, false,
                        relDeclOfType("none", "x"), predicate("P", variable("x")))));
        notLone.normalize();
        assertEquals(Opcode.CONSTANT, notLone.getMatrixEGraph().getOpcode(),
                "not lone x: none | P must be false");
        assertEquals("false", notLone.getMatrixEGraph().getSourceName(),
                "not lone x: none | P must be false");
        assertEquals(0, notLone.getMatrixQuantiVars().size(),
                "empty-domain NOTLONE must not retain a binding");
    }

    private static void testIteEliminatedFromNormalForm() {
        NormalForm normalForm = new NormalForm();
        normalForm.addEClass(node(
                Opcode.ITE,
                false,
                false,
                predicate("C"),
                predicate("T"),
                predicate("E")));
        normalForm.normalize();

        assertTrue(!containsOpcode(normalForm.getMatrixEGraph(), Opcode.ITE),
                "boolean ITE must be expanded out of the normal-form matrix");
        assertEquals(Opcode.OR, normalForm.getMatrixEGraph().getOpcode(),
                "boolean ITE must normalize to disjunction of guarded branches");
    }

    private static void testEndEliminatedFromNormalForm() {
        NormalForm normalForm = new NormalForm();
        normalForm.addEClass(node(
                Opcode.AND,
                true,
                true,
                variable("x"),
                node(Opcode.END, false, false)));
        normalForm.normalize();

        assertTrue(!containsOpcode(normalForm.getMatrixEGraph(), Opcode.END),
                "normal-form matrix must not retain flexible-arity END sentinels");
        assertEquals(Opcode.VARIABLE, normalForm.getMatrixEGraph().getOpcode(),
                "AND with only a real operand after END pruning must collapse to that operand");
    }

    private static void testLetReferenceSurvivesEndCleanupUntilBetaReduction() {
        EGraphNode comprehension = node(
                Opcode.COMPREHENSION,
                false,
                true,
                relDeclOfType("State", "x", "y"),
                predicate("edge", variable("x"), variable("y")));
        EGraphNode letReference = node(Opcode.LET, false, false);
        letReference.setSourceName("t");
        EGraphNode closure = node(Opcode.CLOSURE, false, false, letReference);
        EGraphNode join = node(Opcode.JOIN, false, true, variable("i"), closure);
        EGraphNode let = node(Opcode.LET, false, false, comprehension, join);
        let.setSourceName("t");

        NormalForm normalForm = new NormalForm();
        normalForm.addEClass(let);
        normalForm.normalize();

        assertTrue(containsOpcode(normalForm.getMatrixEGraph(), Opcode.COMPREHENSION),
                "END cleanup must not erase a bound LET reference before beta reduction");
        assertTrue(containsOpcode(normalForm.getMatrixEGraph(), Opcode.CLOSURE),
                "beta reduction must retain operators surrounding the LET reference");
        assertTrue(!containsOpcode(normalForm.getMatrixEGraph(), Opcode.LET),
                "normalization must eliminate the LET binder and all bound references");
    }

    private static void testNegatedRelationDoesNotNegateSetOperands() {
        NormalForm normalForm = new NormalForm();
        normalForm.addEClass(node(
                Opcode.NOT_IN,
                false,
                false,
                node(Opcode.ARROW, false, false,
                        node(Opcode.ARROW, false, false, variable("c"), variable("s")),
                        variable("g")),
                global("Groups")));
        normalForm.normalize();

        assertEquals(Opcode.NOT_IN, normalForm.getMatrixEGraph().getOpcode(),
                "negated membership must stay a negated relation");
        assertTrue(!containsOpcode(normalForm.getMatrixEGraph(), Opcode.NOT),
                "negated membership must not push NOT into set or relation operands");
    }

    private static void testPrimitiveDomainConstraintNotDuplicatedInMatrix() {
        NormalForm primitiveDomain = new NormalForm();
        primitiveDomain.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDeclWithDomain(node(Opcode.ONE, false, false, global("Person")), "Person", "x"),
                predicate("P", variable("x"))));
        primitiveDomain.normalize();

        assertEquals(Quantifier.ALL, primitiveDomain.getMatrixQuantiVars().get(0).getQuantifier(),
                "quantifier must stay encoded in QuantiVar");
        assertEquals("Person", primitiveDomain.getMatrixQuantiVars().get(0).getTypeName(),
                "primitive type must stay encoded in QuantiVar");
        assertEquals(Cardinality.ONE, primitiveDomain.getMatrixQuantiVars().get(0).getCardinality(),
                "primitive cardinality must stay encoded in QuantiVar");
        assertEquals(Opcode.CALL, primitiveDomain.getMatrixEGraph().getOpcode(),
                "primitive one Person domain must not add x in one Person to the matrix");
        assertTrue(!containsOpcode(primitiveDomain.getMatrixEGraph(), Opcode.IN),
                "primitive domain constraint already encoded by QuantiVar must not be duplicated");
        assertTrue(!containsOpcode(primitiveDomain.getMatrixEGraph(), Opcode.ONE),
                "primitive multiplicity wrapper already encoded by QuantiVar must not be duplicated");

        NormalForm complexDomain = new NormalForm();
        complexDomain.addEClass(node(
                Opcode.EXISTS,
                false,
                false,
                relDeclWithDomain(
                        node(Opcode.ONE, false, false,
                                node(Opcode.JOIN, false, true, global("Field"), variable("owner"))),
                        "Person",
                        "x"),
                predicate("P", variable("x"))));
        complexDomain.normalize();

        assertEquals(Cardinality.ONE, complexDomain.getMatrixQuantiVars().get(0).getCardinality(),
                "complex-domain cardinality must stay encoded in QuantiVar");
        assertEquals("Person", complexDomain.getMatrixQuantiVars().get(0).getCarrierTypeName(),
                "a matrix guard must preserve the primitive renaming carrier");
        assertTrue(containsOpcode(complexDomain.getMatrixEGraph(), Opcode.IN),
                "non-primitive domain must still be pushed down into the matrix");
        assertTrue(!containsOpcode(complexDomain.getMatrixEGraph(), Opcode.ONE),
                "pushed-down complex domain must not duplicate cardinality already encoded in QuantiVar");

        NormalForm plainPrimitiveDomain = new NormalForm();
        plainPrimitiveDomain.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDeclWithDomain(global("Person"), "Person", "x"),
                predicate("P", variable("x"))));
        plainPrimitiveDomain.normalize();

        assertEquals(Cardinality.ONE,
                plainPrimitiveDomain.getMatrixQuantiVars().get(0).getCardinality(),
                "a bare Alloy declaration domain defaults to one");
        assertEquals(0, normalFormDistance(primitiveDomain, plainPrimitiveDomain),
                "one Person and bare Person must have the same binding cardinality");

        NormalForm setPrimitiveDomain = new NormalForm();
        setPrimitiveDomain.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDeclWithDomain(
                        node(Opcode.SETOF, false, false, global("Person")),
                        "Person",
                        "x"),
                predicate("P", variable("x"))));
        setPrimitiveDomain.normalize();
        assertEquals(Cardinality.SET,
                setPrimitiveDomain.getMatrixQuantiVars().get(0).getCardinality(),
                "explicit set Person must retain set cardinality");
        assertTrue(normalFormDistance(primitiveDomain, setPrimitiveDomain) > 0,
                "one Person vs set Person binding cardinality must affect distance");
    }

    private static void testCommutingPrenexBindingsIgnoreBranchOrder() {
        NormalForm left = new NormalForm();
        left.addEClass(node(
                Opcode.AND,
                true,
                true,
                quantifiedOver("Material", "m", "NoParts"),
                quantifiedOver("Component", "c", "SomeParts")));
        left.normalize();

        NormalForm right = new NormalForm();
        right.addEClass(node(
                Opcode.AND,
                true,
                true,
                quantifiedOver("Component", "c", "SomeParts"),
                quantifiedOver("Material", "m", "NoParts")));
        right.normalize();

        assertEquals(0, normalFormDistance(left, right),
                "commutative branches must permit the corresponding ALL bindings to commute");
    }

    private static void testNegatedSomeAndNoBindingPathsAreEquivalent() {
        NormalForm negatedSome = new NormalForm();
        negatedSome.addEClass(node(
                Opcode.NOT,
                false,
                false,
                node(Opcode.EXISTS, false, false, relDecl("t"), predicate("Cycle", variable("t")))));
        negatedSome.normalize();

        NormalForm no = new NormalForm();
        no.addEClass(node(Opcode.NO, false, false, relDecl("t"), predicate("Cycle", variable("t"))));
        no.normalize();

        assertEquals(0, normalFormDistance(negatedSome, no),
                "not some and no must not differ only because one binding came through a negated path");
    }

    private static void testCommutativeComplexDomainsUseCanonicalCarrier() {
        NormalForm left = quantifiedIntersectionDomain("Protected", "Trash", "File");
        NormalForm right = quantifiedIntersectionDomain("Trash", "Protected", "File");

        assertEquals("File", left.getMatrixQuantiVars().get(0).getCarrierTypeName(),
                "a complex intersection domain must retain its primitive carrier");
        assertEquals("File", right.getMatrixQuantiVars().get(0).getCarrierTypeName(),
                "commuting an intersection must not change its effective carrier");
        assertEquals(0, normalFormDistance(left, right),
                "A & B and B & A domains must produce the same guarded quantifier form");
    }

    private static EGraphNode quantifiedOver(String type, String variable, String predicate) {
        return node(
                Opcode.FORALL,
                false,
                false,
                relDeclWithDomain(global(type), type, variable),
                predicate(predicate, variable(variable)));
    }

    private static NormalForm quantifiedIntersectionDomain(String left, String right, String inferredType) {
        NormalForm normalForm = new NormalForm();
        normalForm.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDeclWithDomain(
                        node(Opcode.INTERSECT, true, true, global(left), global(right)),
                        inferredType,
                        "f"),
                predicate("P", variable("f"))));
        normalForm.normalize();
        return normalForm;
    }

    private static void testComplementEliminatesRedundantSlot() {
        EGraphNode proposition = variable("tautologyX");
        EGraphNode negated = node(Opcode.NOT, false, false, proposition);
        EGraphNode tautology = node(Opcode.OR, true, true, proposition, negated);
        EGraphNode.EClassRef beforeSaturation = tautology.getEClassRef();

        tautology.saturate();

        assertEquals(Opcode.CONSTANT, tautology.getOpcode(), "A OR NOT A must collapse to a constant");
        assertEquals("true", tautology.getSourceName(), "A OR NOT A must collapse to true");
        assertTrue(tautology.getEClass().getSlots().isEmpty(),
                "a slot unused by an equivalent constant must become redundant");
        assertTrue(beforeSaturation.canonical().getSlotMap().isEmpty(),
                "path-compressed renamed IDs must drop redundant slot bindings");
        assertTrue(tautology.getEClass().getNodes().size() >= 2,
                "the original tautology and true must remain in one e-class");

        EGraphNode contradictionX = variable("contradictionX");
        EGraphNode contradiction = node(
                Opcode.AND,
                true,
                true,
                contradictionX,
                node(Opcode.NOT, false, false, contradictionX));
        contradiction.saturate();
        assertEquals("false", contradiction.getSourceName(), "A AND NOT A must collapse to false");
        assertTrue(contradiction.getEClass().getSlots().isEmpty(),
                "contradiction elimination must also remove the unused slot");

        EGraphNode member = variable("memberX");
        EGraphNode set = variable("setS");
        EGraphNode membership = node(Opcode.IN, false, false, member, set);
        EGraphNode nonMembership = node(Opcode.NOT_IN, false, false, member, set);
        EGraphNode nnfTautology = node(Opcode.OR, true, true, membership, nonMembership);
        nnfTautology.saturate();
        assertEquals("true", nnfTautology.getSourceName(),
                "dual NNF atoms must be recognized as complements");
        assertTrue(nnfTautology.getEClass().getSlots().isEmpty(),
                "dual-atom tautology must eliminate all unused slots");
    }

    private static void testSlotPermutationGroups() {
        EGraphNode pair = node(Opcode.CALL, false, true, variable("groupX"), variable("groupY"));
        EGraphNode.EClassRef identity = pair.getEClass().invoke(rename(
                "groupX", "left",
                "groupY", "right"));
        EGraphNode.EClassRef swapped = pair.getEClass().invoke(rename(
                "groupX", "right",
                "groupY", "left"));
        assertTrue(!identity.equivalentTo(swapped),
                "slot order must matter before a binder symmetry is registered");

        pair.getEClass().addSlotSwap("groupX", "groupY");
        assertTrue(identity.equivalentTo(swapped),
                "all x,y:S must identify f[x,y] with the consistently renamed f[y,x]");
        assertEquals(2, pair.getEClass().symmetryCount(), "one transposition must generate S2");

        EGraphNode triple = node(
                Opcode.CALL,
                false,
                true,
                variable("groupA"),
                variable("groupB"),
                variable("groupC"));
        triple.getEClass().addSlotSwap("groupA", "groupB");
        triple.getEClass().addSlotSwap("groupB", "groupC");
        assertEquals(6, triple.getEClass().symmetryCount(),
                "adjacent binder swaps must generate the full S3 group");
    }

    private static void testDisjModifierIsPreserved() {
        NormalForm disjoint = new NormalForm();
        disjoint.addEClass(node(Opcode.FORALL, false, false, disjRelDecl("x", "y"),
                predicate("P", variable("x"), variable("y"))));
        disjoint.normalize();
        assertTrue(disjoint.getMatrixQuantiVars().get(0).isDisj(),
                "disj declaration modifier must survive prenexing");

        NormalForm plain = new NormalForm();
        plain.addEClass(node(Opcode.FORALL, false, false, relDecl("x", "y"),
                predicate("P", variable("x"), variable("y"))));
        plain.normalize();
        assertTrue(!plain.getMatrixQuantiVars().get(0).isDisj(),
                "plain declaration must not be marked disj");
    }

    private static void testDisjModifierAffectsCanonicalDistance() {
        NormalForm disjoint = new NormalForm();
        disjoint.addEClass(node(Opcode.FORALL, false, false, disjRelDecl("x", "y"),
                predicate("P", variable("x"), variable("y"))));
        disjoint.normalize();

        NormalForm plain = new NormalForm();
        plain.addEClass(node(Opcode.FORALL, false, false, relDecl("x", "y"),
                predicate("P", variable("x"), variable("y"))));
        plain.normalize();

        assertTrue(normalFormDistance(disjoint, plain) > 0,
                "disj vs non-disj bindings must have nonzero canonical distance");
    }

    private static void testDisjClassesDistinguishDeclarationGroups() {
        NormalForm grouped = new NormalForm();
        grouped.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                disjRelDecl("x1", "x2"),
                node(
                        Opcode.EXISTS,
                        false,
                        false,
                        disjRelDecl("x3", "x4"),
                        predicate("P", variable("x1"), variable("x2"), variable("x3"), variable("x4")))));
        grouped.normalize();

        List<QuantiVar> bindings = grouped.getMatrixQuantiVars();
        assertEquals(4, bindings.size(), "two disj declarations must produce four bindings");
        assertEquals(Quantifier.ALL, bindings.get(0).getQuantifier(), "first group must stay universal");
        assertEquals(Quantifier.SOME, bindings.get(2).getQuantifier(), "second group must stay existential");
        assertTrue(bindings.get(0).isDisj(), "first disj group must be marked disj");
        assertEquals(bindings.get(0).getDisjointnessClass(), bindings.get(1).getDisjointnessClass(),
                "variables from the same disj declaration must share a class");
        assertEquals(bindings.get(2).getDisjointnessClass(), bindings.get(3).getDisjointnessClass(),
                "variables from the second disj declaration must share a class");
        assertTrue(bindings.get(0).getDisjointnessClass() != bindings.get(2).getDisjointnessClass(),
                "separate disj declarations must not be merged into one global disjointness class");
    }

    private static void testBagMultiplicityPreservedUntilExplicitRewrite() {
        EGraphNode duplicate = variable("bagDuplicateX");
        EGraphNode product = node(Opcode.MUL, true, true, duplicate, duplicate);
        product.saturate();
        assertTrue(!product.isFlexibleArity(),
                "overflow-forbidding MUL must remain fixed binary");
        assertTrue(product.isOrderInsensitive(),
                "overflow-forbidding MUL retains commutativity");
        assertTrue(!product.hasFlatLicense(),
                "overflow-forbidding MUL must not receive associativity");
        assertEquals(2, product.getChildClasses().size(),
                "non-Boolean bag nodes must retain duplicate e-class invocations until an explicit rewrite removes them");
        assertEquals(1, product.getChildClassCardinalities().size(),
                "duplicate bag invocations should be grouped by e-class identity");
        assertEquals(Integer.valueOf(2), product.getChildClassCardinalities().values().iterator().next(),
                "duplicate bag invocations must expose cardinality two");
    }

    private static void testImplicationPrenexPolarityDoesNotDoubleNegate() {
        NormalForm antecedentQuantifier = new NormalForm();
        antecedentQuantifier.addEClass(node(
                Opcode.IMPLIES,
                false,
                false,
                node(Opcode.FORALL, false, false, relDecl("a"), predicate("A", variable("a"))),
                predicate("B")));
        antecedentQuantifier.normalize();

        assertEquals(0, antecedentQuantifier.getMatrixQuantiVars().size(),
                "an empty-domain-sensitive antecedent quantifier must remain locally scoped");
        assertTrue(containsOpcode(antecedentQuantifier.getMatrixEGraph(), Opcode.EXISTS),
                "forall in an implication antecedent must become a local existential after NNF");
        assertTrue(!containsOpcode(antecedentQuantifier.getMatrixEGraph(), Opcode.IN),
                "a retained primitive local binder must not acquire a synthetic univ guard");
        assertTrue(containsOpcode(antecedentQuantifier.getMatrixEGraph(), Opcode.NOT),
                "the antecedent matrix must remain negated exactly once after strict prenexing");

        NormalForm scopedImplication = new NormalForm();
        scopedImplication.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDecl("a"),
                node(Opcode.IMPLIES, false, false, predicate("A", variable("a")), predicate("B"))));
        scopedImplication.normalize();

        assertEquals(Quantifier.ALL, scopedImplication.getMatrixQuantiVars().get(0).getQuantifier(),
                "all a | A(a) => B must keep universal quantification over the implication body");
    }

    private static void testImplicationScopeAffectsCanonicalDistance() {
        NormalForm antecedentQuantifier = new NormalForm();
        antecedentQuantifier.addEClass(node(
                Opcode.IMPLIES,
                false,
                false,
                node(Opcode.FORALL, false, false, relDecl("a"), predicate("A", variable("a"))),
                predicate("B")));
        antecedentQuantifier.normalize();

        NormalForm outerQuantifier = new NormalForm();
        outerQuantifier.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDecl("a"),
                node(Opcode.IMPLIES, false, false, predicate("A", variable("a")), predicate("B"))));
        outerQuantifier.normalize();

        assertTrue(normalFormDistance(antecedentQuantifier, outerQuantifier) > 0,
                "a quantifier scoped only over an implication antecedent must not collapse with an outer quantifier");
    }

    private static void testIffPrenexPolarity() {
        NormalForm iff = new NormalForm();
        iff.addEClass(node(
                Opcode.IFF,
                false,
                false,
                node(Opcode.EXISTS, false, false, relDecl("x"), predicate("P", variable("x"))),
                predicate("Q")));
        iff.normalize();

        assertTrue(containsOpcode(iff.getMatrixEGraph(), Opcode.FORALL),
                "IFF expansion must retain its empty-domain-sensitive local universal branch");
        assertTrue(containsOpcode(iff.getMatrixEGraph(), Opcode.EXISTS),
                "IFF expansion must retain its empty-domain-sensitive local existential branch");
        assertTrue(containsOpcode(iff.getMatrixEGraph(), Opcode.NOT),
                "IFF expansion must account for the implicit negated implication branch");
    }

    private static void testAlphaRenamingKeepsCanonicalDistanceZero() {
        NormalForm left = new NormalForm();
        left.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDecl("x", "y"),
                predicate("F", variable("x"), variable("y"))));
        left.normalize();

        NormalForm right = new NormalForm();
        right.addEClass(node(
                Opcode.FORALL,
                false,
                false,
                relDecl("a", "b"),
                predicate("F", variable("a"), variable("b"))));
        right.normalize();

        assertEquals(0, normalFormDistance(left, right),
                "alpha-renamed binders with the same De Bruijn structure must remain equivalent");
    }

    private static void testOneAndLoneQuantifierNegation() {
        EGraphNode nestedAll = node(
                Opcode.FORALL,
                false,
                false,
                relDecl("y"),
                node(Opcode.CALL, false, true, variable("x"), variable("y")));
        EGraphNode one = node(Opcode.ONE, false, false, relDecl("x"), nestedAll);
        NormalForm quantified = new NormalForm();
        quantified.addEClass(node(Opcode.NOT, false, false, one));
        quantified.normalize();

        assertEquals(Quantifier.NOTONE, quantified.getMatrixQuantiVars().get(0).getQuantifier(),
                "not one x must become a NOTONE quantifier");
        assertEquals(Quantifier.ALL, quantified.getMatrixQuantiVars().get(1).getQuantifier(),
                "a negation consumed by NOTONE must not flip nested quantifiers");
        assertEquals(Opcode.CALL, quantified.getMatrixEGraph().getOpcode(),
                "a negation consumed by NOTONE must not negate its matrix");

        NormalForm loneQuantified = new NormalForm();
        loneQuantified.addEClass(node(
                Opcode.NOT,
                false,
                false,
                node(Opcode.LONE, false, false, relDecl("z"), node(Opcode.CALL, false, true, variable("z")))));
        loneQuantified.normalize();
        assertEquals(Quantifier.NOTLONE, loneQuantified.getMatrixQuantiVars().get(0).getQuantifier(),
                "not lone z must become a NOTLONE quantifier");

        NormalForm unaryMultiplicity = new NormalForm();
        unaryMultiplicity.addEClass(node(
                Opcode.NOT,
                false,
                false,
                node(Opcode.LONE, false, false, global("S"))));
        unaryMultiplicity.normalize();
        assertEquals(Opcode.NOT, unaryMultiplicity.getMatrixEGraph().getOpcode(),
                "not lone S is a multiplicity test and must retain its negation");
        assertEquals(Opcode.LONE, unaryMultiplicity.getMatrixEGraph().getChildren().get(0).getOpcode(),
                "the retained negation must wrap the unary LONE test");
    }

    private static void testQuantifierPolarityRules() {
        assertNegatedQuantifier(Opcode.FORALL, Quantifier.SOME, Opcode.NOT,
                "not all x must become some x with a negated matrix");
        assertNegatedQuantifier(Opcode.EXISTS, Quantifier.ALL, Opcode.NOT,
                "not some x must become all x with a negated matrix");
        assertNegatedQuantifier(Opcode.NO, Quantifier.SOME, Opcode.CALL,
                "not no x must become some x without negating the matrix");

        assertAntecedentQuantifierLocal(Opcode.FORALL, Opcode.EXISTS, true,
                "ALL in an implication antecedent must remain local when its domain may be empty");
        assertAntecedentQuantifierLifted(Opcode.EXISTS, Quantifier.ALL, true, "S",
                "SOME in an implication antecedent may become ALL and cross OR safely");
        assertAntecedentQuantifierLocal(Opcode.NO, Opcode.EXISTS, false,
                "NO in an implication antecedent must remain local when its domain may be empty");
        assertAntecedentQuantifierLocal(Opcode.ONE, Opcode.ONE, true,
                "ONE in an implication antecedent must retain a local negated multiplicity test");
        assertAntecedentQuantifierLocal(Opcode.LONE, Opcode.LONE, true,
                "LONE in an implication antecedent must retain a local negated multiplicity test");
    }

    private static void assertNegatedQuantifier(
            Opcode source,
            Quantifier expectedQuantifier,
            Opcode expectedMatrixRoot,
            String message) {
        NormalForm normalForm = new NormalForm();
        EGraphNode quantified = node(source, false, false, relDecl("v"), predicate("P", variable("v")));
        normalForm.addEClass(node(Opcode.NOT, false, false, quantified));
        normalForm.normalize();
        assertEquals(expectedQuantifier, normalForm.getMatrixQuantiVars().get(0).getQuantifier(), message);
        assertEquals(expectedMatrixRoot, normalForm.getMatrixEGraph().getOpcode(), message);
    }

    private static void assertAntecedentQuantifierLifted(
            Opcode source,
            Quantifier expectedQuantifier,
            boolean expectedMatrixNegation,
            String expectedCarrierType,
            String message) {
        NormalForm normalForm = new NormalForm();
        EGraphNode quantified = node(source, false, false, relDecl("a"), predicate("P", variable("a")));
        normalForm.addEClass(node(Opcode.IMPLIES, false, false, quantified, predicate("Q")));
        normalForm.normalize();
        assertEquals(expectedQuantifier, normalForm.getMatrixQuantiVars().get(0).getQuantifier(), message);
        assertEquals(expectedCarrierType, normalForm.getMatrixQuantiVars().get(0).getCarrierTypeName(), message);
        assertEquals(expectedMatrixNegation, containsOpcode(normalForm.getMatrixEGraph(), Opcode.NOT), message);
    }

    private static void assertAntecedentQuantifierLocal(
            Opcode source,
            Opcode expectedLocalOpcode,
            boolean expectedBodyNegation,
            String message) {
        NormalForm normalForm = new NormalForm();
        EGraphNode quantified = node(source, false, false, relDecl("a"), predicate("P", variable("a")));
        normalForm.addEClass(node(Opcode.IMPLIES, false, false, quantified, predicate("Q")));
        normalForm.normalize();
        assertEquals(0, normalForm.getMatrixQuantiVars().size(), message);
        assertEquals(true, containsOpcode(normalForm.getMatrixEGraph(), expectedLocalOpcode), message);
        assertEquals(expectedBodyNegation, containsOpcode(normalForm.getMatrixEGraph(), Opcode.NOT), message);
    }

    private static boolean containsOpcode(EGraphNode node, Opcode opcode) {
        if (node.getOpcode() == opcode) {
            return true;
        }
        for (EGraphNode child : node.getChildren()) {
            if (containsOpcode(child, opcode)) {
                return true;
            }
        }
        return false;
    }

    private static EGraphNode findOpcode(EGraphNode node, Opcode opcode) {
        if (node.getOpcode() == opcode) {
            return node;
        }
        for (EGraphNode child : node.getChildren()) {
            EGraphNode found = findOpcode(child, opcode);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void testCommutativeDistanceUsesUnorderedMatching() {
        NormalForm left = new NormalForm();
        left.addEClass(node(Opcode.OR, true, true,
                predicate("P", global("Human")), predicate("P", global("Robot"))));
        left.normalize();

        NormalForm right = new NormalForm();
        right.addEClass(node(Opcode.OR, true, true,
                predicate("P", global("Robot")), predicate("P", global("Human"))));
        right.normalize();

        assertEquals(0, normalFormDistance(left, right),
                "commutative matrix distance must minimize over child permutations");
    }

    private static void testTemporalNegationCrossesPhaseBoundary() {
        List<NormalForm> forms = temporalNormalForms(
                "not ((some Trash) triggered (no Protected))");
        NormalForm parent = forms.get(0);
        NormalForm left = parent.getTemporalChildren().get(0);
        NormalForm right = parent.getTemporalChildren().get(1);

        assertEquals(TemporalOp.SINCEL, left.getTemporalOp(),
                "negated TRIGGERED left phase must become SINCE");
        assertEquals(TemporalOp.SINCER, right.getTemporalOp(),
                "negated TRIGGERED right phase must become SINCE");
        assertTrue(containsOpcode(left.getMatrixEGraph(), Opcode.NO)
                        && !containsOpcode(left.getMatrixEGraph(), Opcode.SOME),
                "temporal dualization must negate the left phase matrix");
        assertTrue(containsOpcode(right.getMatrixEGraph(), Opcode.SOME)
                        && !containsOpcode(right.getMatrixEGraph(), Opcode.NO),
                "temporal dualization must negate the right phase matrix");
        assertTrue(!containsOpcode(parent.getMatrixEGraph(), Opcode.NOT),
                "the parent phase must retain only the dualized temporal reference");
    }

    private static List<NormalForm> temporalNormalForms(String body) {
        return temporalNormalForms(body, TemporalFixtureAction.COMPLETE);
    }

    private static List<NormalForm> temporalNormalForms(
            String body,
            TemporalFixtureAction action) {
        java.nio.file.Path directory = null;
        java.nio.file.Path sourcePath = null;
        try {
            directory = java.nio.file.Files.createTempDirectory("candis-temporal-authority-");
            sourcePath = directory.resolve("temporal_authority.als");
            java.nio.file.Files.writeString(
                    sourcePath,
                    "module temporal_authority\n"
                            + "var sig Trash, Protected {}\n"
                            + "pred source { " + body + " }\n",
                    java.nio.charset.StandardCharsets.UTF_8);
            edu.mit.csail.sdg.parser.CompModule module =
                    parser.util.AlloyUtil.compileAlloyModule(sourcePath.toString());
            parser.ast.nodes.ModelUnit model = new parser.ast.nodes.ModelUnit(null, module);
            is.fivefivefive.ACGN.visitor.MASGVisitor visitor =
                    new is.fivefivefive.ACGN.visitor.MASGVisitor(
                            new is.fivefivefive.ACGN.util.GlobalVariables(), module);
            visitor.visit(model, null);
            Integer id = visitor.getForestId("source");
            if (id == null || visitor.getForest().get(id) == null) {
                throw new AssertionError("temporal fixture has no MASG predicate");
            }
            is.fivefivefive.CanDis.ir.IRAgent agent =
                    new is.fivefivefive.CanDis.ir.IRAgent(visitor.getForest().get(id));
            java.util.concurrent.atomic.AtomicBoolean delegated =
                    new java.util.concurrent.atomic.AtomicBoolean();
            try {
                agent.computeNormalForm((stage, active, forms) -> {
                    if (action == TemporalFixtureAction.COMPLETE
                            || !"begin-temporal-negation".equals(stage)
                            || active == null
                            || active.getTemporalChildren().isEmpty()
                            || !delegated.compareAndSet(false, true)) {
                        return;
                    }
                    if (action == TemporalFixtureAction.DELEGATE_REWRITE) {
                        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                                new java.util.concurrent.atomic.AtomicReference<>();
                        Thread worker = new Thread(() -> {
                            try {
                                active.pushTemporalNegations();
                            } catch (Throwable throwable) {
                                failure.set(throwable);
                            }
                        }, "normal-form-cross-thread-temporal-negation");
                        worker.start();
                        join(worker, "cross-thread temporal normalization");
                        if (failure.get() != null) {
                            throw new AssertionError(
                                    "NormalForm-derived nodes must inherit their source arena",
                                    failure.get());
                        }
                    }
                    throw new DelegatedTemporalRewriteComplete();
                });
            } catch (DelegatedTemporalRewriteComplete expected) {
                if (action == TemporalFixtureAction.COMPLETE) {
                    throw expected;
                }
            }
            if (action != TemporalFixtureAction.COMPLETE && !delegated.get()) {
                throw new AssertionError(
                        "temporal fixture never reached its delegated rewrite stage");
            }
            return agent.normalForms();
        } catch (Exception failure) {
            throw new AssertionError("temporal parser fixture failed", failure);
        } finally {
            try {
                if (sourcePath != null) {
                    java.nio.file.Files.deleteIfExists(sourcePath);
                }
                if (directory != null) {
                    java.nio.file.Files.deleteIfExists(directory);
                }
            } catch (java.io.IOException cleanupFailure) {
                throw new AssertionError("temporal fixture cleanup failed", cleanupFailure);
            }
        }
    }

    private static final class DelegatedTemporalRewriteComplete
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private enum TemporalFixtureAction {
        COMPLETE,
        DELEGATE_REWRITE,
        STOP_BEFORE_REWRITE
    }

    private static boolean hasQuantifier(List<QuantiVar> bindings, Quantifier quantifier) {
        for (QuantiVar binding : bindings) {
            if (binding.getQuantifier() == quantifier) {
                return true;
            }
        }
        return false;
    }

    private static int normalFormDistance(NormalForm left, NormalForm right) {
        try {
            java.lang.reflect.Method quantification = CanonicalDistance.class.getDeclaredMethod(
                    "quantificationDistance",
                    List.class,
                    List.class);
            java.lang.reflect.Method matrix = CanonicalDistance.class.getDeclaredMethod(
                    "matrixDistance",
                    List.class,
                    List.class);
            quantification.setAccessible(true);
            matrix.setAccessible(true);
            List<NormalForm> leftList = Arrays.asList(left);
            List<NormalForm> rightList = Arrays.asList(right);
            return (int) quantification.invoke(null, leftList, rightList)
                    + (int) matrix.invoke(null, leftList, rightList);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not validate normal-form distance", e);
        }
    }

    private static Map<String, String> rename(String from, String to) {
        Map<String, String> renaming = new LinkedHashMap<>();
        renaming.put(from, to);
        return renaming;
    }

    private static Map<String, String> rename(String from1, String to1, String from2, String to2) {
        Map<String, String> renaming = new LinkedHashMap<>();
        renaming.put(from1, to1);
        renaming.put(from2, to2);
        return renaming;
    }

    private static EGraphNode variable(String name) {
        EGraphNode variable = new EGraphNode(name.hashCode(), Opcode.VARIABLE, new ArrayList<>(), false, 0, false,
                Metatype.ATOMIC);
        variable.setSourceName(name);
        variable.setAlphaName(name);
        return variable;
    }

    private static EGraphNode global(String name) {
        if ("none".equals(name)) {
            return EGraphNode.builtinSetConstant(
                    name.hashCode(),
                    SigSymbol.builtinNone(),
                    null,
                    SemanticProfile.alloyOverflowForbidding());
        }
        if ("univ".equals(name)) {
            return EGraphNode.builtinSetConstant(
                    name.hashCode(),
                    SigSymbol.builtinUniv(),
                    null,
                    SemanticProfile.alloyOverflowForbidding());
        }
        EGraphNode binding = new EGraphNode(name.hashCode(), Opcode.GLOBALBINDING, new ArrayList<>(), false, 0,
                false, Metatype.SET);
        binding.setSourceName(name);
        binding.setSourceType(name);
        return binding;
    }

    private static EGraphNode exactRelation(String name) {
        EGraphNode relation = global(name);
        relation.setExactAlloyType(ExactAlloyType.unaryRelation(name));
        relation.setSourceType("Rel(" + name + ")");
        return relation;
    }

    private static EGraphNode exactRelation(String name, String... columns) {
        EGraphNode relation = global(name);
        relation.setExactAlloyType(ExactAlloyType.relation(Arrays.asList(columns)));
        relation.setSourceType("Rel(" + String.join("->", columns) + ")");
        return relation;
    }

    private static EGraphNode exactNone(int arity) {
        return EGraphNode.builtinSetConstant(
                -10_000 - arity,
                SigSymbol.builtinNone(),
                ExactAlloyType.emptyRelation(arity),
                SemanticProfile.alloyOverflowForbidding());
    }

    private static EGraphNode relationNode(
            Opcode opcode,
            ExactAlloyType exactType,
            EGraphNode... children) {
        EGraphNode relation = new EGraphNode(
                opcode.hashCode(),
                opcode,
                new ArrayList<>(Arrays.asList(children)),
                false,
                children.length,
                false,
                Metatype.SET);
        relation.setSourceType("Rel(Test)");
        relation.setExactAlloyType(exactType);
        return relation;
    }

    private static EGraphNode bool(boolean value) {
        EGraphNode constant = new EGraphNode(Boolean.hashCode(value), Opcode.CONSTANT, new ArrayList<>(), false, 0,
                false, Metatype.BOOLEAN);
        constant.setSourceName(Boolean.toString(value));
        constant.setSourceType("Bool");
        constant.setExactAlloyType(ExactAlloyType.boolType());
        return constant;
    }

    private static EGraphNode relDecl(String... variableNames) {
        return relDeclOfType("S", variableNames);
    }

    private static EGraphNode relDeclOfType(String typeName, String... variableNames) {
        EGraphNode[] children = new EGraphNode[variableNames.length + 1];
        children[0] = global(typeName);
        for (int i = 0; i < variableNames.length; i++) {
            EGraphNode declared = variable(variableNames[i]);
            declared.setSourceType(typeName);
            children[i + 1] = declared;
        }
        return node(Opcode.GENERICRELDECL, true, true, children);
    }

    private static EGraphNode relDeclWithDomain(EGraphNode domain, String primitiveTypeName, String... variableNames) {
        EGraphNode[] children = new EGraphNode[variableNames.length + 1];
        children[0] = domain;
        for (int i = 0; i < variableNames.length; i++) {
            EGraphNode declared = variable(variableNames[i]);
            declared.setSourceType(primitiveTypeName);
            children[i + 1] = declared;
        }
        return node(Opcode.GENERICRELDECL, true, true, children);
    }

    private static EGraphNode disjRelDecl(String... variableNames) {
        EGraphNode[] children = new EGraphNode[variableNames.length + 1];
        children[0] = global("S");
        for (int i = 0; i < variableNames.length; i++) {
            EGraphNode declared = variable(variableNames[i]);
            declared.setSourceType("S");
            children[i + 1] = declared;
        }
        return node(Opcode.DISJ, true, true, children);
    }

    private static EGraphNode predicate(String name, EGraphNode... arguments) {
        EGraphNode predicate = node(Opcode.CALL, false, true, arguments);
        predicate.setSourceName(name);
        predicate.setSemanticIdentity("egraph-test/" + name);
        return predicate;
    }

    private static EGraphNode temporalSource(Opcode opcode) {
        int arity = opcode == Opcode.UNTIL || opcode == Opcode.RELEASES
                || opcode == Opcode.SINCE || opcode == Opcode.TRIGGERED ? 2 : 1;
        EGraphNode source = new EGraphNode(
                opcode.hashCode(), opcode, new ArrayList<>(), false, arity, false,
                Metatype.BOOLEAN);
        source.setSourceType("Bool");
        source.setExactAlloyType(ExactAlloyType.boolType());
        return source;
    }

    private static EGraphNode node(
            Opcode opcode,
            boolean commutative,
            boolean flexible,
            EGraphNode... children) {
        boolean call = opcode == Opcode.CALL;
        EGraphNode node = new EGraphNode(
                opcode.hashCode(),
                opcode,
                new ArrayList<>(Arrays.asList(children)),
                call ? false : commutative,
                call || !flexible ? children.length : -1,
                call ? false : flexible,
                Metatype.BOOLEAN);
        if (opcode == Opcode.PLUS || opcode == Opcode.INTERSECT) {
            node.setSourceType("Rel(Test)");
        }
        if (opcode == Opcode.NOT || opcode == Opcode.AND || opcode == Opcode.OR
                || opcode == Opcode.IMPLIES || opcode == Opcode.IFF
                || opcode == Opcode.ITE || opcode == Opcode.FORALL
                || opcode == Opcode.EXISTS || opcode == Opcode.NO
                || opcode == Opcode.ONE || opcode == Opcode.LONE
                || opcode == Opcode.IN || opcode == Opcode.NOT_IN
                || opcode == Opcode.EQUALS || opcode == Opcode.NOT_EQUALS
                || opcode == Opcode.GT || opcode == Opcode.GTE
                || opcode == Opcode.LT || opcode == Opcode.LTE
                || opcode == Opcode.NOT_GT || opcode == Opcode.NOT_GTE
                || opcode == Opcode.NOT_LT || opcode == Opcode.NOT_LTE) {
            node.setSourceType("Bool");
            node.setExactAlloyType(ExactAlloyType.boolType());
        }
        String booleanOperatorSource = booleanOperatorSource(opcode);
        if (booleanOperatorSource != null) {
            node.setSourceName(booleanOperatorSource);
            node.setSourceType("MIDDLENODE_" + booleanOperatorSource);
        }
        if (call) {
            node.setSourceName("call");
            node.setSemanticIdentity("egraph-test/call");
            node.setSourceType("call/formula");
            node.setCallOccurrenceId(nextSyntheticCallOccurrence++);
            node.setDeclaredArity(children.length);
            node.setCallArityAuthority(CallSymbol.ArityAuthority.DECLARATION.name());
            node.setExactAlloyType(ExactAlloyType.boolType());
        }
        return node;
    }

    private static String booleanOperatorSource(Opcode opcode) {
        switch (opcode) {
            case NOT:
                return "UNOPF_NOT";
            case AND:
                return "BOP_AND";
            case OR:
                return "BOP_OR";
            case IMPLIES:
                return "BOP_IMPLIES";
            case IFF:
                return "BOP_IFF";
            case ITE:
                return "ITE_FORMULA";
            default:
                return null;
        }
    }

    private static List<String> variableNames(List<EGraphNode> nodes) {
        List<String> names = new ArrayList<>();
        for (EGraphNode node : nodes) {
            if (node.getOpcode() == Opcode.VARIABLE) {
                names.add(node.getAlphaName());
            }
        }
        return names;
    }

    private static void assertCallChildren(
            EGraphNode owner,
            String... expectedNames) {
        for (EGraphNode child : owner.getChildren()) {
            assertEquals(Opcode.CALL, child.getOpcode(),
                    "Boolean absorption must retain complete call operands");
        }
        assertSourceChildren(owner, expectedNames);
    }

    private static void assertSourceChildren(
            EGraphNode owner,
            String... expectedNames) {
        List<String> actual = new ArrayList<>();
        for (EGraphNode child : owner.getChildren()) {
            actual.add(child.getSourceName());
        }
        assertEquals(Arrays.asList(expectedNames), actual,
                "absorption must preserve every unrelated sibling");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertThrows(
            Class<? extends Throwable> expected,
            Runnable action,
            String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(message + ": unexpected " + thrown, thrown);
        }
        throw new AssertionError(message + ": no exception was thrown");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}

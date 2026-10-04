package is.fivefivefive.CanDis.metric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import is.fivefivefive.CanDis.metric.RepairView.Binding;
import is.fivefivefive.CanDis.metric.RepairView.BindingRole;
import is.fivefivefive.CanDis.metric.RepairView.ContainerKind;
import is.fivefivefive.CanDis.metric.RepairView.Declaration;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.metric.RepairView.Phase;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.StructuralKey;

/** Focused executable checks for the certificate-backed Fast Rewrite metric port. */
public final class QuotientRepairDistanceTest {
    private static int checks;
    private static final StructuralKey TEST_OBSERVATION =
            StructuralKey.leaf("test-certified-observation", "shared");
    private static final SemanticProfile TEST_PROFILE =
            SemanticProfile.alloyOverflowForbidding();
    private static final OrderedTreeEditDistance.Adapter<TemporalNode> TEMPORAL_ADAPTER =
            new OrderedTreeEditDistance.Adapter<>() {
                @Override
                public String label(TemporalNode node) {
                    return node.label();
                }

                @Override
                public List<? extends TemporalNode> children(TemporalNode node) {
                    return node.children();
                }
            };

    private QuotientRepairDistanceTest() {
    }

    public static void main(String[] args) {
        EGraphNode untypedRelation = new EGraphNode(
                900_001,
                Opcode.GLOBALBINDING,
                List.of(),
                false,
                0,
                false,
                Metatype.SET);
        untypedRelation.setSourceName("R");
        untypedRelation.setSourceType("Rel(A->B)");
        expectThrows(IllegalStateException.class,
                () -> RepairProjection.requireExactResultType(untypedRelation));
        untypedRelation.setExactAlloyType(
                ExactAlloyType.relation(List.of("A", "B")));
        GraphType exactRelation = RepairProjection.requireExactResultType(untypedRelation);
        check(exactRelation.kind() == GraphType.Kind.RELATION
                        && exactRelation.arguments().size() == 2,
                "repair projection consumes the exact ordered relation type");

        Node a = atom("A");
        Node b = atom("B");
        Node c = atom("C");

        check(distance(view(node("SEQ", ContainerKind.SEQUENCE, a, b)),
                view(node("SEQ", ContainerKind.SEQUENCE, b, a))) > 0,
                "sequence order remains editable");
        check(distance(view(node("AND", ContainerKind.SET, a, b)),
                view(node("AND", ContainerKind.SET, b, c))) == 1,
                "ACI operands use minimum-cost assignment");
        check(distance(view(node("BAG", ContainerKind.BAG, a, a)),
                view(node("BAG", ContainerKind.BAG, a))) == 1,
                "bag matching preserves multiplicity");

        Declaration all = declaration("ALL", 0);
        Declaration some = declaration("SOME", 0);
        check(distance(view(List.of(all), List.of(binding(all, "all", 0)), a),
                view(List.of(some), List.of(binding(some, "some", 0)), a)) == 1,
                "one quantifier tuple modification costs one");

        Declaration parameterS = typedDeclaration("PARAMETER", "S", 0);
        Declaration parameterT = typedDeclaration("PARAMETER", "T", 0);
        Binding sourceParameter = parameter(parameterS, 0);
        Binding targetParameter = parameter(parameterT, 0);
        QuotientRepairDistance.Result parameterTypeComponents =
                QuotientRepairDistance.evaluateUncheckedForTesting(
                view(Collections.emptyList(), List.of(sourceParameter),
                        node("SOME", ContainerKind.ONE, variable("x", 0))),
                view(Collections.emptyList(), List.of(targetParameter),
                        node("SOME", ContainerKind.ONE, variable("renamed", 0))));
        check(parameterTypeComponents.distance() == 1
                        && parameterTypeComponents.quantifierDistance() == 1
                        && parameterTypeComponents.matrixDistance() == 0,
                "one positional parameter-type modification is charged once and carries identity");
        check(distance(
                view(Collections.emptyList(), List.of(sourceParameter),
                        node("SOME", ContainerKind.ONE, variable("x", 0))),
                view(Collections.emptyList(), List.of(targetParameter),
                        node("NO", ContainerKind.ONE, variable("renamed", 0)))) == 2,
                "a parameter-type modification cannot disappear from a positive matrix repair");
        Binding insertedParameter = parameter(parameterT, 0);
        Binding shiftedParameter = parameter(parameterS, 1);
        RepairView oneParameter = view(
                Collections.emptyList(),
                List.of(sourceParameter),
                node("SOME", ContainerKind.ONE, variable("x", 0)));
        RepairView prefixedParameter = view(
                Collections.emptyList(),
                List.of(insertedParameter, shiftedParameter),
                node("SOME", ContainerKind.ONE, variable("shifted-x", 1)));
        check(distance(oneParameter, prefixedParameter) == 1,
                "one inserted positional parameter shifts an unchanged binding without a matrix edit");
        check(distance(prefixedParameter, oneParameter) == 1,
                "one deleted positional parameter shifts an unchanged binding without a matrix edit");
        RepairView unboundSameSpelling = view(
                node("SOME", ContainerKind.ONE, variable("x", -1)));
        QuotientRepairDistance.Result boundToUnbound =
                QuotientRepairDistance.evaluateUncheckedForTesting(
                        oneParameter, unboundSameSpelling);
        QuotientRepairDistance.Result unboundToBound =
                QuotientRepairDistance.evaluateUncheckedForTesting(
                        unboundSameSpelling, oneParameter);
        check(boundToUnbound.distance() == 2
                        && boundToUnbound.quantifierDistance() == 1
                        && boundToUnbound.matrixDistance() == 1,
                "a bound variable cannot become a same-spelled free variable for zero matrix cost");
        check(unboundToBound.distance() == boundToUnbound.distance()
                        && unboundToBound.matrixDistance()
                                == boundToUnbound.matrixDistance(),
                "bound/free variable identity edits are symmetric");

        List<List<String>> boundedParameterTypes = parameterTypeSequences(3);
        for (List<String> leftTypes : boundedParameterTypes) {
            for (List<String> rightTypes : boundedParameterTypes) {
                for (int leftReference = 0;
                        leftReference < leftTypes.size(); leftReference++) {
                    for (int rightReference = 0;
                            rightReference < rightTypes.size(); rightReference++) {
                        int[] expected = exhaustiveParameterDistance(
                                leftTypes, rightTypes, leftReference, rightReference);
                        QuotientRepairDistance.Result actual =
                                QuotientRepairDistance.evaluateUncheckedForTesting(
                                        parameterView(leftTypes, leftReference),
                                        parameterView(rightTypes, rightReference));
                        check(actual.quantifierDistance() == expected[0]
                                        && actual.matrixDistance() == expected[1]
                                        && actual.distance() == expected[0] + expected[1],
                                "parameter edit plans must equal the independent bounded oracle"
                                        + " (left=" + leftTypes + "/" + leftReference
                                        + ", right=" + rightTypes + "/" + rightReference
                                        + ", expected=" + Arrays.toString(expected)
                                        + ", actual=" + actual.quantifierDistance()
                                        + "+" + actual.matrixDistance() + ")");
                    }
                }
            }
        }

        Declaration leftS0 = typedDeclaration("ALL", "S", 0);
        Declaration leftT = typedDeclaration("ALL", "T", 0);
        Declaration leftS2 = typedDeclaration("ALL", "S", 0);
        Declaration rightT = typedDeclaration("ALL", "T", 0);
        Declaration rightS1 = typedDeclaration("ALL", "S", 0);
        Declaration rightS2 = typedDeclaration("ALL", "S", 0);
        check(distance(
                view(
                        List.of(leftS0, leftT, leftS2),
                        List.of(
                                binding(leftS0, "typed", 0, List.of(0, 2)),
                                binding(leftT, "typed", 1, List.of(1)),
                                binding(leftS2, "typed", 2, List.of(0, 2))),
                        variable("left-S", 0)),
                view(
                        List.of(rightT, rightS1, rightS2),
                        List.of(
                                binding(rightT, "typed", 0, List.of(0)),
                                binding(rightS1, "typed", 1, List.of(1, 2)),
                                binding(rightS2, "typed", 2, List.of(1, 2))),
                        variable("right-T", 0))) == 1,
                "typed alpha alignment cannot use a same-coordinate incompatible fallback");

        Binding x = binding(all, "root", 0);
        Binding y = binding(all, "root", 0);
        check(distance(
                view(List.of(all), List.of(x), variable("x", 0)),
                view(List.of(all), List.of(y), variable("renamed", 0))) == 0,
                "pairwise alpha alignment uses a certified scope orbit");

        Declaration lone = declaration("LONE", 1);
        check(distance(
                view(List.of(lone),
                        List.of(binding(lone, "root/body[2]/decl[0]", 0)),
                        variable("nested-lone-left", 0)),
                view(List.of(lone),
                        List.of(binding(lone, "root/body[1]/body[1]/decl[0]", 0)),
                        variable("nested-lone-right", 0))) == 0,
                "certified prenex coordinates ignore obsolete lexical binder paths");
        check(distance(
                view(List.of(lone),
                        List.of(uncertifiedBinding(lone, "left/scope", 0)),
                        variable("uncertified-left", 0)),
                view(List.of(lone),
                        List.of(uncertifiedBinding(lone, "right/scope", 0)),
                        variable("uncertified-right", 0))) > 0,
                "uncertified bindings cannot erase distinct lexical scope paths");
        expectThrows(IllegalArgumentException.class, () -> new Binding(
                BindingRole.PARAMETER, 0, -1, -1, parameterS,
                "parameter/0", List.of(0), false));
        expectThrows(IllegalArgumentException.class, () -> new Binding(
                BindingRole.MATRIX, 0, 0, 0, lone,
                "", List.of(0), false));
        expectThrows(IllegalArgumentException.class, () -> new Node(
                "VARIABLE", null, "negative", -2,
                ContainerKind.SEQUENCE, false, List.of()));
        expectThrows(IllegalArgumentException.class, () -> new Phase(
                List.of(), List.of(), variable("absent-binding", 7)));

        Declaration relabeledLone = declaration("LONE", 0);
        check(distance(
                view(List.of(lone),
                        List.of(binding(lone, "left/lone", 0)),
                        variable("left-lone", 0)),
                view(List.of(relabeledLone),
                        List.of(binding(relabeledLone, "right/lone", 0)),
                        variable("right-lone", 0))) == 0,
                "exchange-block identifiers are local and align order-preservingly");

        Declaration loneBlockZero = declaration("LONE", 0);
        Declaration loneBlockOne = declaration("LONE", 1);
        check(distance(
                view(List.of(loneBlockZero, loneBlockOne),
                        List.of(
                                binding(loneBlockZero, "left/lone-zero", 0),
                                binding(loneBlockOne, "left/lone-one", 1)),
                        node("PAIR", ContainerKind.SEQUENCE,
                                variable("left-zero", 0), variable("left-one", 1))),
                view(List.of(loneBlockZero, loneBlockOne),
                        List.of(
                                binding(loneBlockZero, "right/lone-zero", 0),
                                binding(loneBlockOne, "right/lone-one", 1)),
                        node("PAIR", ContainerKind.SEQUENCE,
                                variable("right-one", 1), variable("right-zero", 0)))) > 0,
                "distinct non-ALL exchange blocks cannot cross after path erasure");

        Declaration blockZero = declaration("ALL", 0);
        Declaration blockOne = declaration("ALL", 1);
        Binding leftZero = binding(blockZero, "left/zero", 0);
        Binding leftOne = binding(blockOne, "left/one", 1);
        Binding rightZero = binding(blockZero, "right/zero", 0);
        Binding rightOne = binding(blockOne, "right/one", 1);
        check(distance(
                view(List.of(blockZero, blockOne), List.of(leftZero, leftOne),
                        node("PAIR", ContainerKind.SEQUENCE,
                                variable("x", 0), variable("y", 1))),
                view(List.of(blockZero, blockOne), List.of(rightZero, rightOne),
                        node("PAIR", ContainerKind.SEQUENCE,
                                variable("b", 1), variable("a", 0)))) > 0,
                "separate certified scope blocks cannot cross-permute");

        expectThrows(IllegalStateException.class, () -> distance(
                view(List.of(all, all), List.of(
                                binding(all, "malformed-orbit", 0, List.of(0)),
                                binding(all, "malformed-orbit", 1, List.of(1))),
                        node("PAIR", ContainerKind.SEQUENCE,
                                variable("m0", 0), variable("m1", 1))),
                view(List.of(all, all), List.of(
                                binding(all, "complete-orbit", 0, List.of(0, 1)),
                                binding(all, "complete-orbit", 1, List.of(0, 1))),
                        node("PAIR", ContainerKind.SEQUENCE,
                                variable("n0", 0), variable("n1", 1)))));

        Binding x0 = binding(all, "same-block", 0, List.of(0, 1, 2));
        Binding x1 = binding(all, "same-block", 1, List.of(0, 1, 2));
        Binding x2 = binding(all, "same-block", 2, List.of(0, 1, 2));
        Binding only = binding(all, "same-block", 0, List.of(0));
        Node partialLeft = node("ROOT", ContainerKind.SET,
                node("P", ContainerKind.ONE, variable("x0", 0)),
                node("Q", ContainerKind.ONE, variable("x1", 1)),
                node("R", ContainerKind.ONE, variable("x2", 2)));
        Node partialRight = node("ROOT", ContainerKind.SET,
                node("Q", ContainerKind.ONE, variable("y", 0)));
        check(distance(
                view(List.of(all, all, all), List.of(x0, x1, x2), partialLeft),
                view(List.of(all), List.of(only), partialRight)) == 6,
                "unequal-arity alpha alignment minimizes over every maximum partial mapping");

        Binding ownerLeft0 = binding(all, "temporal-owner", 0, List.of(0, 1));
        Binding ownerLeft1 = binding(all, "temporal-owner", 1, List.of(0, 1));
        Binding ownerRight0 = binding(all, "temporal-owner", 0, List.of(0, 1));
        Binding ownerRight1 = binding(all, "temporal-owner", 1, List.of(0, 1));
        Binding inheritedLeft0 = inherited(all, "temporal-owner", 0, List.of(0, 1));
        Binding inheritedLeft1 = inherited(all, "temporal-owner", 1, List.of(0, 1));
        Binding inheritedRight0 = inherited(all, "temporal-owner", 0, List.of(0, 1));
        Binding inheritedRight1 = inherited(all, "temporal-owner", 1, List.of(0, 1));
        RepairView temporalTarget = multiPhaseView(
                new Phase(
                        List.of(all, all),
                        List.of(ownerLeft0, ownerLeft1),
                        node("ARROW", ContainerKind.SEQUENCE,
                                variable("x", 0), variable("y", 1))),
                new Phase(
                        Collections.emptyList(),
                        List.of(inheritedLeft0, inheritedLeft1),
                        node("TARGET", ContainerKind.ONE, variable("y", 1))));
        RepairView wrongTemporalTarget = multiPhaseView(
                new Phase(
                        List.of(all, all),
                        List.of(ownerRight0, ownerRight1),
                        node("ARROW", ContainerKind.SEQUENCE,
                                variable("a", 0), variable("b", 1))),
                new Phase(
                        Collections.emptyList(),
                        List.of(inheritedRight0, inheritedRight1),
                        node("TARGET", ContainerKind.ONE, variable("a", 0))));
        RepairView consistentlyPermutedTarget = multiPhaseView(
                new Phase(
                        List.of(all, all),
                        List.of(ownerRight0, ownerRight1),
                        node("ARROW", ContainerKind.SEQUENCE,
                                variable("b", 1), variable("a", 0))),
                new Phase(
                        Collections.emptyList(),
                        List.of(inheritedRight0, inheritedRight1),
                        node("TARGET", ContainerKind.ONE, variable("a", 0))));
        check(distance(temporalTarget, wrongTemporalTarget) == 1,
                "one binder owner cannot choose inconsistent alpha mappings across temporal phases");
        check(distance(temporalTarget, consistentlyPermutedTarget) == 0,
                "one certified binder permutation must apply consistently to every inherited phase");

        RepairView semantic = view(a);
        RepairView proofPresentation = new RepairView(
                semantic.temporalRoot(),
                semantic.phases(),
                TEST_PROFILE,
                StructuralKey.leaf("test-certified-observation", "different"));
        check(distance(semantic, proofPresentation) == 0,
                "proof and serialization identity are not repair edits");
        expectThrows(IllegalStateException.class, () ->
                QuotientRepairDistance.evaluate(semantic, proofPresentation));

        check(distance(
                        view(certifiedAtom("this/S", "identity=S;type=Rel(AlloySig:S)")),
                        view(certifiedAtom("S", "identity=S;type=Rel(AlloySig:S)"))) == 0,
                "readable source spelling must not override certified atom identity");
        check(distance(
                        view(certifiedAtom("same", "identity=same;type=Rel(AlloySig:S)")),
                        view(certifiedAtom("same", "identity=same;type=Rel(AlloySig:T)"))) == 1,
                "one exact atom-type change must remain one matrix-node edit");
        check(distance(
                        view(node("A", ContainerKind.SEQUENCE, atom("X"))),
                        view(atom("X"))) == 2,
                "matrix insertion and deletion operate on complete operand subtrees");
        check(distance(
                        view(node("A", ContainerKind.SEQUENCE, atom("X"))),
                        view((Node) null)) == 2,
                "deleting a complete matrix operand charges its complete subtree size");
        check(distance(
                        view((Node) null),
                        view(node("A", ContainerKind.SEQUENCE, atom("X")))) == 2,
                "inserting a complete matrix operand charges its complete subtree size");

        RepairView after = new RepairView(
                new TemporalNode("NONE", List.of(new TemporalNode("AFTER", List.of()))),
                semantic.phases(),
                TEST_PROFILE,
                StructuralKey.leaf("test-certified-observation", "after"));
        check(distance(semantic, after) == 1,
                "temporal edits remain a separate ordered-tree component");

        TemporalNode xTemporal = temporal("X");
        TemporalNode yTemporal = temporal("Y");
        TemporalNode internalDeletionLeft = temporal(
                "R", temporal("A", xTemporal));
        TemporalNode internalDeletionRight = temporal("R", xTemporal);
        check(temporalDistance(internalDeletionLeft, internalDeletionRight) == 1,
                "ordered TED deletes an internal node and promotes its child");
        check(temporalDistance(internalDeletionRight, internalDeletionLeft) == 1,
                "ordered TED inserts an internal node around an existing child");
        check(temporalDistance(temporal("R", xTemporal, yTemporal),
                temporal("R", yTemporal, xTemporal)) == 2,
                "ordered TED does not silently commute temporal siblings");
        check(temporalDistance(xTemporal, yTemporal) == 1,
                "ordered TED leaf relabeling is one boundary edit");
        expectThrows(NullPointerException.class, () ->
                OrderedTreeEditDistance.distance(null, xTemporal, TEMPORAL_ADAPTER));

        List<TemporalNode> boundedTrees = List.of(
                xTemporal,
                yTemporal,
                temporal("A", xTemporal),
                temporal("R", xTemporal, yTemporal),
                internalDeletionLeft,
                temporal("R", temporal("A", xTemporal), yTemporal),
                temporal("R", xTemporal, temporal("A", yTemporal)));
        for (TemporalNode leftTree : boundedTrees) {
            for (TemporalNode rightTree : boundedTrees) {
                int expected = exhaustiveOrderedMappingDistance(leftTree, rightTree);
                int actual = temporalDistance(leftTree, rightTree);
                check(actual == expected,
                        "Zhang-Shasha result must equal the independent bounded mapping oracle"
                                + " (expected=" + expected + ", actual=" + actual + ")");
            }
        }

        QuotientRepairDistance.Result components =
                QuotientRepairDistance.evaluateUncheckedForTesting(
                view(List.of(all), List.of(binding(all, "all", 0)), a),
                view(List.of(some), List.of(binding(some, "some", 0)), c));
        check(components.distance() == components.temporalDistance()
                        + components.quantifierDistance() + components.matrixDistance(),
                "reported temporal, quantifier, and matrix components sum exactly");

        RepairView falseSameObservation = new RepairView(
                semantic.temporalRoot(),
                List.of(new Phase(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        atom("DIFFERENT"))),
                TEST_PROFILE,
                TEST_OBSERVATION);
        expectThrows(IllegalStateException.class, () ->
                QuotientRepairDistance.evaluate(semantic, falseSameObservation));
        expectThrows(IllegalStateException.class, () ->
                QuotientRepairDistance.evaluate(semantic, semantic));
        check(QuotientRepairDistance.evaluateUncheckedForTesting(
                        semantic, semantic).kernelAuthority()
                        == QuotientRepairDistance.KernelAuthority
                                .TEST_ONLY_UNCHECKED,
                "an unbound fixture cannot advertise producer-kernel authority");
        RepairView modular = new RepairView(
                semantic.temporalRoot(),
                semantic.phases(),
                SemanticProfile.alloyModular(),
                TEST_OBSERVATION);
        expectThrows(IllegalArgumentException.class, () ->
                QuotientRepairDistance.evaluate(semantic, modular));

        expectThrows(IllegalArgumentException.class, () ->
                QuotientRepairDistance.minimumAssignmentCost(
                        new int[][] {{0, 1}, {2}}));
        expectThrows(IllegalArgumentException.class, () ->
                QuotientRepairDistance.minimumAssignmentCost(
                        new int[][] {{-1}}));
        expectThrows(ArithmeticException.class, () ->
                QuotientRepairDistance.minimumAssignmentCost(new int[][] {
                        {Integer.MAX_VALUE, Integer.MAX_VALUE},
                        {Integer.MAX_VALUE, Integer.MAX_VALUE}}));
        expectThrows(ArithmeticException.class, () ->
                QuotientRepairDistance.checkedTotal(Integer.MAX_VALUE, 1, 0));
        expectThrows(IllegalArgumentException.class, () ->
                QuotientRepairDistance.checkedTotal(-1, 0, 0));
        Map<Integer, Integer> correspondence = new LinkedHashMap<>();
        check(QuotientRepairDistance.addInjectiveCorrespondence(
                        correspondence, 0, 1),
                "the first quantifier correspondence is inserted");
        check(!QuotientRepairDistance.addInjectiveCorrespondence(
                        correspondence, 0, 1),
                "repeating the exact quantifier correspondence is idempotent");
        expectThrows(IllegalStateException.class, () ->
                QuotientRepairDistance.addInjectiveCorrespondence(
                        correspondence, 0, 2));
        expectThrows(IllegalStateException.class, () ->
                QuotientRepairDistance.addInjectiveCorrespondence(
                        correspondence, 2, 1));
        Random assignmentRandom = new Random(0x5eedc0deL);
        for (int size = 0; size <= 6; size++) {
            for (int sample = 0; sample < 30; sample++) {
                int[][] costs = new int[size][size];
                for (int row = 0; row < size; row++) {
                    for (int column = 0; column < size; column++) {
                        costs[row][column] = assignmentRandom.nextInt(21);
                    }
                }
                int expected = exhaustiveAssignmentCost(costs);
                int actual = QuotientRepairDistance.minimumAssignmentCost(costs);
                check(actual == expected,
                        "Hungarian assignment must equal bounded exhaustive permutation");
            }
        }

        Binding duplicateParameter0 = parameter(parameterS, 0);
        Binding duplicateParameter1 = parameter(parameterS, 1);
        RepairView duplicateParameters = view(
                Collections.emptyList(),
                List.of(duplicateParameter0, duplicateParameter1),
                node("SOME", ContainerKind.ONE, variable("duplicate", 1)));
        withSystemProperty("acgn.metric.maxQuantifierAlignments", "1", () ->
                expectThrows(
                        QuotientRepairDistance.ResourceLimitException.class,
                        () -> distance(oneParameter, duplicateParameters)));

        RepairView twoScopeBlocks = view(
                List.of(blockZero, blockOne),
                List.of(leftZero, leftOne),
                node("PAIR", ContainerKind.SEQUENCE,
                        variable("scope-0", 0), variable("scope-1", 1)));
        withSystemProperty("acgn.metric.maxScopeAlignments", "1", () ->
                expectThrows(
                        QuotientRepairDistance.ResourceLimitException.class,
                        () -> distance(twoScopeBlocks, twoScopeBlocks)));

        withSystemProperty("acgn.metric.maxAlphaAlignments", "1", () ->
                expectThrows(
                        QuotientRepairDistance.ResourceLimitException.class,
                        () -> distance(
                                view(
                                        List.of(all, all),
                                        List.of(
                                                binding(all, "bounded", 0, List.of(0, 1)),
                                                binding(all, "bounded", 1, List.of(0, 1))),
                                        node("ROOT", ContainerKind.SEQUENCE,
                                                variable("a0", 0), variable("a1", 1),
                                                atom("A"))),
                                view(
                                        List.of(all, all),
                                        List.of(
                                                binding(all, "bounded", 0, List.of(0, 1)),
                                                binding(all, "bounded", 1, List.of(0, 1))),
                                        node("ROOT", ContainerKind.SEQUENCE,
                                                variable("b0", 0), variable("b1", 1),
                                                atom("B"))))));

        testBoundedGlobalAlphaEnumerationCompleteness();

        System.out.println("QuotientRepairDistanceTest passed: " + checks + " checks");
    }

    private static void testBoundedGlobalAlphaEnumerationCompleteness() {
        for (int bindingCount = 1; bindingCount <= 3; bindingCount++) {
            List<int[]> words = coordinateWords(bindingCount, 3);
            List<int[]> permutations = coordinatePermutations(bindingCount);
            for (int[] leftWord : words) {
                for (int[] rightWord : words) {
                    int expected = Integer.MAX_VALUE;
                    for (int[] permutation : permutations) {
                        int mismatches = 0;
                        for (int index = 0; index < leftWord.length; index++) {
                            if (permutation[leftWord[index]] != rightWord[index]) {
                                mismatches++;
                            }
                        }
                        expected = Math.min(expected, mismatches);
                    }
                    int actual = distance(
                            completeOrbitView(bindingCount, leftWord, "left"),
                            completeOrbitView(bindingCount, rightWord, "right"));
                    check(actual == expected,
                            "global alpha enumeration must equal the independent bounded "
                                    + "permutation oracle (bindings=" + bindingCount
                                    + ", left=" + Arrays.toString(leftWord)
                                    + ", right=" + Arrays.toString(rightWord)
                                    + ", expected=" + expected + ", actual=" + actual + ")");
                }
            }
        }
    }

    private static RepairView completeOrbitView(
            int bindingCount,
            int[] word,
            String prefix) {
        List<Integer> orbit = new ArrayList<>(bindingCount);
        List<Declaration> declarations = new ArrayList<>(bindingCount);
        for (int coordinate = 0; coordinate < bindingCount; coordinate++) {
            orbit.add(coordinate);
            declarations.add(typedDeclaration("ALL", "S", 0));
        }
        List<Binding> bindings = new ArrayList<>(bindingCount);
        for (int coordinate = 0; coordinate < bindingCount; coordinate++) {
            bindings.add(binding(
                    declarations.get(coordinate),
                    "bounded-complete-orbit",
                    coordinate,
                    orbit));
        }
        Node[] uses = new Node[word.length];
        for (int index = 0; index < word.length; index++) {
            uses[index] = variable(prefix + "-" + index, word[index]);
        }
        return view(declarations, bindings, node("ROOT", ContainerKind.SEQUENCE, uses));
    }

    private static List<int[]> coordinateWords(int alphabetSize, int length) {
        List<int[]> result = new ArrayList<>();
        enumerateCoordinateWords(
                alphabetSize, new int[length], 0, result);
        return result;
    }

    private static void enumerateCoordinateWords(
            int alphabetSize,
            int[] current,
            int index,
            List<int[]> result) {
        if (index == current.length) {
            result.add(current.clone());
            return;
        }
        for (int value = 0; value < alphabetSize; value++) {
            current[index] = value;
            enumerateCoordinateWords(alphabetSize, current, index + 1, result);
        }
    }

    private static List<int[]> coordinatePermutations(int size) {
        List<int[]> result = new ArrayList<>();
        enumerateCoordinatePermutations(
                new int[size], new boolean[size], 0, result);
        return result;
    }

    private static void enumerateCoordinatePermutations(
            int[] current,
            boolean[] used,
            int index,
            List<int[]> result) {
        if (index == current.length) {
            result.add(current.clone());
            return;
        }
        for (int value = 0; value < current.length; value++) {
            if (used[value]) {
                continue;
            }
            used[value] = true;
            current[index] = value;
            enumerateCoordinatePermutations(current, used, index + 1, result);
            used[value] = false;
        }
    }

    private static int distance(RepairView left, RepairView right) {
        return QuotientRepairDistance.evaluateUncheckedForTesting(
                left, right).distance();
    }

    private static int temporalDistance(TemporalNode left, TemporalNode right) {
        RepairView leftView = new RepairView(
                left,
                List.of(new Phase(Collections.emptyList(), Collections.emptyList(), atom("M"))),
                TEST_PROFILE,
                StructuralKey.leaf("test-certified-observation", "left-temporal"));
        RepairView rightView = new RepairView(
                right,
                List.of(new Phase(Collections.emptyList(), Collections.emptyList(), atom("M"))),
                TEST_PROFILE,
                StructuralKey.leaf("test-certified-observation", "right-temporal"));
        return QuotientRepairDistance.evaluateUncheckedForTesting(
                leftView, rightView).temporalDistance();
    }

    private static int exhaustiveAssignmentCost(int[][] costs) {
        int[] best = {Integer.MAX_VALUE};
        exhaustiveAssignmentCost(costs, 0, new boolean[costs.length], 0, best);
        return costs.length == 0 ? 0 : best[0];
    }

    private static List<List<String>> parameterTypeSequences(int maximumLength) {
        List<List<String>> result = new ArrayList<>();
        for (int length = 1; length <= maximumLength; length++) {
            enumerateParameterTypeSequences(length, new ArrayList<>(), result);
        }
        return List.copyOf(result);
    }

    private static void enumerateParameterTypeSequences(
            int remaining,
            List<String> prefix,
            List<List<String>> result) {
        if (remaining == 0) {
            result.add(List.copyOf(prefix));
            return;
        }
        for (String type : List.of("S", "T")) {
            prefix.add(type);
            enumerateParameterTypeSequences(remaining - 1, prefix, result);
            prefix.remove(prefix.size() - 1);
        }
    }

    private static RepairView parameterView(List<String> types, int reference) {
        List<Binding> bindings = new ArrayList<>();
        for (int ordinal = 0; ordinal < types.size(); ordinal++) {
            bindings.add(parameter(
                    typedDeclaration("PARAMETER", types.get(ordinal), 0), ordinal));
        }
        return view(
                Collections.emptyList(),
                bindings,
                node("USE", ContainerKind.ONE,
                        variable("parameter-" + reference, reference)));
    }

    /** Returns exact [declaration edit cost, selected-reference matrix cost]. */
    private static int[] exhaustiveParameterDistance(
            List<String> left,
            List<String> right,
            int leftReference,
            int rightReference) {
        int[][] distance = new int[left.size() + 1][right.size() + 1];
        for (int row = 1; row <= left.size(); row++) {
            distance[row][0] = row;
        }
        for (int column = 1; column <= right.size(); column++) {
            distance[0][column] = column;
        }
        for (int row = 1; row <= left.size(); row++) {
            for (int column = 1; column <= right.size(); column++) {
                int diagonal = distance[row - 1][column - 1]
                        + (left.get(row - 1).equals(right.get(column - 1)) ? 0 : 1);
                distance[row][column] = Math.min(
                        diagonal,
                        Math.min(
                                distance[row - 1][column] + 1,
                                distance[row][column - 1] + 1));
            }
        }
        int[] mapping = new int[left.size()];
        Arrays.fill(mapping, -1);
        boolean[] usedRight = new boolean[right.size()];
        int matrix = exhaustiveParameterMatrixCost(
                left,
                right,
                leftReference,
                rightReference,
                distance,
                left.size(),
                right.size(),
                mapping,
                usedRight,
                1);
        return new int[] {distance[left.size()][right.size()], matrix};
    }

    private static int exhaustiveParameterMatrixCost(
            List<String> left,
            List<String> right,
            int leftReference,
            int rightReference,
            int[][] distance,
            int row,
            int column,
            int[] mapping,
            boolean[] usedRight,
            int best) {
        if (row == 0 && column == 0) {
            int mapped = mapping[leftReference];
            if (mapped < 0
                    && !usedRight[rightReference]
                    && leftReference == rightReference
                    && left.get(leftReference).equals(right.get(rightReference))) {
                mapped = rightReference;
            }
            return Math.min(best, mapped == rightReference ? 0 : 1);
        }
        if (row > 0 && column > 0) {
            int update = left.get(row - 1).equals(right.get(column - 1)) ? 0 : 1;
            if (distance[row][column] == distance[row - 1][column - 1] + update) {
                mapping[row - 1] = column - 1;
                usedRight[column - 1] = true;
                best = exhaustiveParameterMatrixCost(
                        left, right, leftReference, rightReference,
                        distance, row - 1, column - 1, mapping, usedRight, best);
                mapping[row - 1] = -1;
                usedRight[column - 1] = false;
            }
        }
        if (best == 0) {
            return 0;
        }
        if (row > 0 && distance[row][column] == distance[row - 1][column] + 1) {
            best = exhaustiveParameterMatrixCost(
                    left, right, leftReference, rightReference,
                    distance, row - 1, column, mapping, usedRight, best);
        }
        if (best == 0) {
            return 0;
        }
        if (column > 0
                && distance[row][column] == distance[row][column - 1] + 1) {
            best = exhaustiveParameterMatrixCost(
                    left, right, leftReference, rightReference,
                    distance, row, column - 1, mapping, usedRight, best);
        }
        return best;
    }

    private static void exhaustiveAssignmentCost(
            int[][] costs,
            int row,
            boolean[] usedColumns,
            int cost,
            int[] best) {
        if (row == costs.length) {
            best[0] = Math.min(best[0], cost);
            return;
        }
        for (int column = 0; column < costs.length; column++) {
            if (usedColumns[column]) {
                continue;
            }
            int next = Math.addExact(cost, costs[row][column]);
            if (next >= best[0]) {
                continue;
            }
            usedColumns[column] = true;
            exhaustiveAssignmentCost(costs, row + 1, usedColumns, next, best);
            usedColumns[column] = false;
        }
    }

    private static TemporalNode temporal(String label, TemporalNode... children) {
        return new TemporalNode(label, List.of(children));
    }

    private static int exhaustiveOrderedMappingDistance(
            TemporalNode left,
            TemporalNode right) {
        List<OracleNode> leftNodes = flatten(left);
        List<OracleNode> rightNodes = flatten(right);
        int[] mapping = new int[leftNodes.size()];
        Arrays.fill(mapping, -1);
        int[] best = {leftNodes.size() + rightNodes.size()};
        enumerateMappings(
                leftNodes, rightNodes, mapping, new boolean[rightNodes.size()], 0, best);
        return best[0];
    }

    private static void enumerateMappings(
            List<OracleNode> left,
            List<OracleNode> right,
            int[] mapping,
            boolean[] usedRight,
            int leftIndex,
            int[] best) {
        if (leftIndex == left.size()) {
            int mapped = 0;
            int updates = 0;
            for (int index = 0; index < mapping.length; index++) {
                if (mapping[index] >= 0) {
                    mapped++;
                    if (!left.get(index).label.equals(right.get(mapping[index]).label)) {
                        updates++;
                    }
                }
            }
            best[0] = Math.min(
                    best[0], left.size() + right.size() - 2 * mapped + updates);
            return;
        }

        mapping[leftIndex] = -1;
        enumerateMappings(left, right, mapping, usedRight, leftIndex + 1, best);
        for (int rightIndex = 0; rightIndex < right.size(); rightIndex++) {
            if (usedRight[rightIndex]
                    || !mappingCompatible(
                            left, right, mapping, leftIndex, rightIndex)) {
                continue;
            }
            mapping[leftIndex] = rightIndex;
            usedRight[rightIndex] = true;
            enumerateMappings(left, right, mapping, usedRight, leftIndex + 1, best);
            usedRight[rightIndex] = false;
            mapping[leftIndex] = -1;
        }
    }

    private static boolean mappingCompatible(
            List<OracleNode> left,
            List<OracleNode> right,
            int[] mapping,
            int newLeft,
            int newRight) {
        for (int oldLeft = 0; oldLeft < newLeft; oldLeft++) {
            int oldRight = mapping[oldLeft];
            if (oldRight < 0) {
                continue;
            }
            boolean leftAncestor = ancestor(left, oldLeft, newLeft);
            boolean leftDescendant = ancestor(left, newLeft, oldLeft);
            boolean rightAncestor = ancestor(right, oldRight, newRight);
            boolean rightDescendant = ancestor(right, newRight, oldRight);
            if (leftAncestor != rightAncestor || leftDescendant != rightDescendant) {
                return false;
            }
            if (!leftAncestor && !leftDescendant
                    && Integer.compare(oldLeft, newLeft)
                            != Integer.compare(oldRight, newRight)) {
                return false;
            }
        }
        return true;
    }

    private static boolean ancestor(
            List<OracleNode> nodes,
            int possibleAncestor,
            int possibleDescendant) {
        int parent = nodes.get(possibleDescendant).parent;
        while (parent >= 0) {
            if (parent == possibleAncestor) {
                return true;
            }
            parent = nodes.get(parent).parent;
        }
        return false;
    }

    private static List<OracleNode> flatten(TemporalNode root) {
        List<OracleNode> result = new ArrayList<>();
        flatten(root, -1, result);
        return result;
    }

    private static void flatten(
            TemporalNode node,
            int parent,
            List<OracleNode> result) {
        int current = result.size();
        result.add(new OracleNode(node.label(), parent));
        for (TemporalNode child : node.children()) {
            flatten(child, current, result);
        }
    }

    private static final class OracleNode {
        private final String label;
        private final int parent;

        private OracleNode(String label, int parent) {
            this.label = label;
            this.parent = parent;
        }
    }

    private static RepairView view(Node matrix) {
        return view(Collections.emptyList(), Collections.emptyList(), matrix);
    }

    private static RepairView view(
            List<Declaration> declarations,
            List<Binding> bindings,
            Node matrix) {
        return new RepairView(
                new TemporalNode("NONE", Collections.emptyList()),
                List.of(new Phase(declarations, bindings, matrix)),
                TEST_PROFILE,
                TEST_OBSERVATION);
    }

    private static RepairView multiPhaseView(Phase... phases) {
        return new RepairView(
                new TemporalNode("NONE", Collections.emptyList()),
                List.of(phases),
                TEST_PROFILE,
                TEST_OBSERVATION);
    }

    private static Declaration declaration(String quantifier, int exchangeClass) {
        return typedDeclaration(quantifier, "S", exchangeClass);
    }

    private static Declaration typedDeclaration(
            String quantifier,
            String type,
            int exchangeClass) {
        return new Declaration(
                quantifier,
                type,
                "ONE",
                0,
                type,
                exchangeClass,
                Collections.emptyList());
    }

    private static Binding parameter(Declaration declaration, int ordinal) {
        return new Binding(
                BindingRole.PARAMETER,
                ordinal,
                -1,
                -1,
                declaration,
                "parameter/" + ordinal,
                Collections.emptyList(),
                false);
    }

    private static Binding binding(
            Declaration declaration,
            String path,
            int coordinate) {
        return binding(declaration, path, coordinate, List.of(coordinate));
    }

    private static Binding binding(
            Declaration declaration,
            String path,
            int coordinate,
            List<Integer> orbit) {
        return new Binding(
                BindingRole.MATRIX,
                coordinate,
                0,
                coordinate,
                declaration,
                path,
                orbit,
                true);
    }

    private static Binding inherited(
            Declaration declaration,
            String path,
            int coordinate,
            List<Integer> orbit) {
        return new Binding(
                BindingRole.INHERITED,
                coordinate,
                0,
                coordinate,
                declaration,
                path,
                orbit,
                true);
    }

    private static Binding uncertifiedBinding(
            Declaration declaration,
            String path,
            int coordinate) {
        return new Binding(
                BindingRole.MATRIX,
                coordinate,
                0,
                coordinate,
                declaration,
                path,
                List.of(coordinate),
                false);
    }

    private static Node variable(String name, int binding) {
        return new Node(
                "VARIABLE", null, name, binding,
                ContainerKind.SEQUENCE, false, Collections.emptyList());
    }

    private static Node atom(String symbol) {
        return new Node(
                symbol, null, null, -1,
                ContainerKind.SEQUENCE, false, Collections.emptyList());
    }

    private static Node certifiedAtom(String display, String semanticIdentity) {
        return new Node(
                "GLOBALBINDING", display, semanticIdentity, null, -1,
                ContainerKind.SEQUENCE, false, Collections.emptyList());
    }

    private static Node node(
            String symbol,
            ContainerKind kind,
            Node... children) {
        return new Node(
                symbol, null, null, -1, kind,
                kind == ContainerKind.BAG || kind == ContainerKind.SET,
                List.of(children));
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
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static void withSystemProperty(
            String name,
            String value,
            Runnable action) {
        String previous = System.getProperty(name);
        try {
            System.setProperty(name, value);
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, previous);
            }
        }
    }
}

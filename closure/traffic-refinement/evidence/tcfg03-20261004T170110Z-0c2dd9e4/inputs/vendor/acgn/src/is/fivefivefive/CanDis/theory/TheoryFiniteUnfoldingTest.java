package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Deterministic Phase H positive, generated, and malformed-trace gate. */
public final class TheoryFiniteUnfoldingTest {
    private static final long SEED = 555_202_608_23L;
    private static final FiniteUnfoldingBounds BOUNDS =
            new FiniteUnfoldingBounds(5, 8_192);
    private static int checks;

    private TheoryFiniteUnfoldingTest() {
    }

    public static void main(String[] args) {
        testAcyclicAndCyclicConformance();
        testCertifiedSymmetryReachability();
        testFreshRedundantCoordinates();
        testScopedBinderMaterialization();
        testGeneratedValidGraphs();
        testMalformedTracesRejected();
        System.out.println("TheoryFiniteUnfoldingTest passed: " + checks
                + " checks; deterministic seed=" + SEED);
    }

    private static void testAcyclicAndCyclicConformance() {
        BooleanGraph fixture = booleanGraph("phase-h/cycle");
        TypedEClassInterface falseClass = fixture.falseClass;
        TypedEClassInterface trueClass = fixture.trueClass;

        CertifiedInsertionResult aliasFalse = insert(
                fixture.graph,
                unaryNode("h/alias/cycle", falseClass));
        unionInto(
                fixture.graph,
                aliasFalse.insertedClass(),
                falseClass,
                "alias-false",
                0);
        CertifiedInsertionResult notFalse = insert(
                fixture.graph,
                unaryNode("h/not/cycle", falseClass));
        unionInto(
                fixture.graph,
                notFalse.insertedClass(),
                trueClass,
                "not-false",
                1);

        BoundedFiniteUnfoldingOracle oracle = oracle(fixture.graph, BOUNDS);
        TypedInvocation falseRoot = TypedInvocation.identity(falseClass);
        FiniteUnfoldingConformanceReport semantic = oracle.validate(
                falseRoot,
                falseRoot,
                booleanObserver(Collections.emptyMap(), false));
        semantic.requireConformant();
        check(semantic.leftUnfoldings().size() >= 5,
                "A productive cycle has bounded complete unfoldings at several depths");
        check(semantic.leftObservations().equals(Collections.singleton(boolKey(false))),
                "Every finite alias-cycle representation evaluates to false");
        for (FiniteUnfoldingTree tree : semantic.leftUnfoldings()) {
            check(tree.height() <= BOUNDS.maximumDepth(),
                    "Every emitted tree respects the depth bound");
            CertificateVerifier.verify(tree.shapeCoherence());
        }

        FiniteUnfoldingConformanceReport structural = oracle.validateNormalized(
                falseRoot, falseRoot);
        check(!structural.conformant(),
                "Structural normalization alone does not pretend to discharge an input equation");
        check(structural.leftNormalizedTerms().size() > 1,
                "The cycle exposes several syntactically distinct finite terms");

        FiniteUnfoldingEqualityWitness equality = semantic.equalityWitness();
        check(equality.leftFind().leaderInvocation().eclass().equals(falseClass),
                "The conformance witness retains the reached leader");
        CertificateVerifier.verify(equality.reachabilityCertificate());
    }

    private static void testCertifiedSymmetryReachability() {
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.certifiedFixture();
        TypedSlot x = TypedSlot.source(GraphType.BOOL, 0);
        TypedSlot y = TypedSlot.source(GraphType.BOOL, 1);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        BagPortSchema schema = new BagPortSchema(new OnePortSchema(GraphType.BOOL));
        InstantiatedOperator operator = bagOperator("h/sym-and", schema);
        BagPort values = new BagPort(
                schema,
                context,
                Arrays.asList(
                        OnePort.slot(context, x),
                        OnePort.slot(context, y)));
        CertifiedInsertionResult insertion = insert(
                graph,
                TypedENode.construct(operator, context, Collections.singletonList(values)));
        TypedEClassInterface eclass = insertion.insertedClass();
        List<TypedSlot> exposed = new ArrayList<>(eclass.exposedSlots().slots());
        TypedPermutation swap = TypedPermutation.of(
                eclass.exposedSlots(),
                mapOf(exposed.get(0), exposed.get(1), exposed.get(1), exposed.get(0)));
        TypedInvocation identity = TypedInvocation.identity(eclass);
        TypedInvocation swapped = new TypedInvocation(eclass, swap);
        InputEquationCertificate equation = InputEquationCertificate.betweenInvocations(
                CertificateOrigin.rewriteAxiom("phase-h", "bag-commutativity", 0),
                identity,
                swapped);
        graph.addSymmetryCertified(
                eclass.id(), new SymmetryCertificate(identity, swapped, equation));
        graph.rebuild();

        BoundedFiniteUnfoldingOracle oracle = oracle(graph, new FiniteUnfoldingBounds(2, 32));
        FiniteUnfoldingConformanceReport report = oracle.validateNormalized(
                identity, swapped);
        report.requireConformant();
        check(report.equalityWitness().leaderSymmetry().equals(swap),
                "The reachability premise records the required certified permutation");
        check(report.leftNormalizedTerms().equals(report.rightNormalizedTerms()),
                "Bag AC normalization agrees across a swapped invocation");
        check(report.leftNormalizedTerms().size() == 1,
                "The symmetry comparison has one normalized finite term");
    }

    private static void testFreshRedundantCoordinates() {
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        TypedSlot x = TypedSlot.source(GraphType.BOOL, 10);
        TypedSlot redundant = TypedSlot.source(GraphType.BOOL, 11);
        TypedSlotContext exposed = TypedSlotContext.singleton(x);
        TypedSlotContext ambient = TypedSlotContext.of(x, redundant);
        TypedSlot c0 = TypedSlot.canonicalFree(GraphType.BOOL, 0);
        TypedSlot c1 = TypedSlot.canonicalFree(GraphType.BOOL, 1);
        TypedSlotContext canonical = TypedSlotContext.of(c0, c1);
        InstantiatedOperator first = OperatorDeclaration.monomorphic(
                "h/first",
                Arrays.asList(
                        new OnePortSchema(GraphType.BOOL),
                        new OnePortSchema(GraphType.BOOL)),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        CanonicalShape shape = CanonicalShape.of(TypedENode.construct(
                first,
                canonical,
                Arrays.asList(
                        OnePort.slot(canonical, c0),
                        OnePort.slot(canonical, c1))));
        ShapeWitness witness = new ShapeWitness(
                canonical,
                ambient,
                exposed,
                TypedRenaming.of(canonical, ambient, mapOf(c0, x, c1, redundant)));
        TypedEClassInterface owner = new TypedEClassInterface(
                EClassId.of(80), GraphType.BOOL, exposed);
        TypedEClassRecord record = TypedEClassRecord.of(
                owner,
                Collections.singletonMap(shape, witness),
                TypedSymmetryGroup.identity(exposed));
        TypedENode restored = shape.node().act(witness.instantiatingRenaming());
        InputEquationCertificate ec = new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase-h", "first-ignores-second", 0),
                TypedCertificateEndpoint.node(restored),
                TypedCertificateEndpoint.invocation(new TypedInvocation(
                        owner, TypedEmbedding.inclusion(exposed, ambient))));
        graph.admitFixedBatchRecordCertified(
                record, Collections.singletonMap(shape, ec));

        TypedInvocation root = TypedInvocation.identity(owner);
        BoundedFiniteUnfoldingOracle oracle = oracle(
                graph, new FiniteUnfoldingBounds(2, 16));
        List<FiniteUnfoldingTree> trees = oracle.enumerate(root);
        check(trees.size() == 1,
                "A leaf shape produces one complete finite unfolding");
        FiniteUnfoldingTree tree = trees.get(0);
        check(tree.shapeWitness().ambientSupport().minus(
                        tree.shapeWitness().exposedInterface()).size() == 1,
                "The unfolding retains one explicit redundant witness coordinate");

        Map<TypedSlot, Boolean> environment = Collections.singletonMap(x, true);
        boolean withFalseFresh = evaluateBoolean(tree, environment, false);
        boolean withTrueFresh = evaluateBoolean(tree, environment, true);
        check(withFalseFresh && withTrueFresh,
                "The finite model confirms independence from either fresh redundant value");
        oracle.validate(root, root, booleanObserver(environment, false)).requireConformant();
        FiniteUnfoldingConformanceReport weakened = oracle.validate(
                root, root, booleanObserver(environment, true));
        weakened.requireConformant();
        check(weakened.commonWeakenings().size() == 1,
                "The report retains one common-context witness for the leaf pair");
        check(weakened.commonWeakenings().get(0).sharedRootWeakening().source()
                        .equals(root.callerContext()),
                "Both final weakenings agree on the original root context");

        expectThrows(IllegalArgumentException.class, () -> FiniteUnfoldingTree.create(
                root,
                record,
                shape,
                witness,
                ec,
                Collections.singletonList(tree)));
    }

    private static void testScopedBinderMaterialization() {
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        TypedEClassInterface child = insert(
                graph, constantNode("h/binder-child", false)).insertedClass();
        TypedSlotContext empty = TypedSlotContext.empty();
        TypedSlot bound = TypedSlot.canonicalBound(GraphType.BOOL, 9);
        TypedSlotContext bodyContext = empty.plus(bound);
        BindPortSchema schema = new BindPortSchema(
                GraphType.BOOL, new OnePortSchema(GraphType.BOOL));
        TypedInvocation childInBody = new TypedInvocation(
                child, TypedEmbedding.of(empty, bodyContext, Collections.emptyMap()));
        BindPort binder = new BindPort(
                schema,
                empty,
                bound,
                OnePort.invocation(bodyContext, childInBody));
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "h/binder/scoped",
                Collections.singletonList(schema),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        TypedEClassInterface rootClass = insert(
                graph,
                TypedENode.construct(
                        operator, empty, Collections.singletonList(binder)))
                .insertedClass();

        TypedInvocation root = TypedInvocation.identity(rootClass);
        BoundedFiniteUnfoldingOracle oracle = oracle(
                graph, new FiniteUnfoldingBounds(3, 16));
        FiniteUnfoldingTree tree = oracle.enumerate(root).get(0);
        FiniteUnfoldingIndexTrace trace = tree.indexTrace();
        check(trace.steps().size() == 2,
                "Binder unfolding indexes both the enclosing and child shape steps");
        check(trace.finalContext().isEmpty(),
                "A bound coordinate does not escape into the final free context");
        check(trace.steps().get(1).invocationAtStep().callerContext().size() == 1,
                "The child indexed step retains its lexical bound caller coordinate");
        check(trace.steps().get(1).finalWeakening().codomain().size() == 1,
                "The child final weakening remains scoped under the binder");
        oracle.validate(
                root,
                root,
                booleanObserver(Collections.emptyMap(), true)).requireConformant();
    }

    private static void testGeneratedValidGraphs() {
        Random random = new Random(SEED);
        for (int round = 0; round < 48; round++) {
            BooleanGraph fixture = booleanGraph("phase-h/generated/" + round);
            for (int step = 0; step < 1 + random.nextInt(3); step++) {
                int kind = random.nextInt(3);
                boolean leftValue = random.nextBoolean();
                TypedEClassInterface left = leftValue
                        ? fixture.trueClass : fixture.falseClass;
                TypedENode node;
                boolean result;
                if (kind == 0) {
                    node = unaryNode("h/alias/" + round + "/" + step, left);
                    result = leftValue;
                } else if (kind == 1) {
                    node = unaryNode("h/not/" + round + "/" + step, left);
                    result = !leftValue;
                } else {
                    boolean rightValue = random.nextBoolean();
                    TypedEClassInterface right = rightValue
                            ? fixture.trueClass : fixture.falseClass;
                    node = binaryBagNode(
                            "h/and/" + round + "/" + step, left, right);
                    result = leftValue && rightValue;
                }
                CertifiedInsertionResult inserted = insert(fixture.graph, node);
                unionInto(
                        fixture.graph,
                        inserted.insertedClass(),
                        result ? fixture.trueClass : fixture.falseClass,
                        "generated-" + round + "-" + step,
                        step);
            }

            BoundedFiniteUnfoldingOracle oracle = oracle(
                    fixture.graph, new FiniteUnfoldingBounds(4, 8_192));
            for (boolean expected : new boolean[]{false, true}) {
                TypedEClassInterface rootClass = expected
                        ? fixture.trueClass : fixture.falseClass;
                TypedInvocation root = TypedInvocation.identity(rootClass);
                FiniteUnfoldingConformanceReport report = oracle.validate(
                        root,
                        root,
                        booleanObserver(Collections.emptyMap(), random.nextBoolean()));
                check(report.conformant(),
                        "Generated well-typed graph satisfies bounded semantic conformance");
                check(report.leftObservations().equals(
                                Collections.singleton(boolKey(expected))),
                        "Generated finite unfoldings retain their intended Boolean value");
                check(!report.leftUnfoldings().isEmpty(),
                        "Generated graph has a productive bounded representation");
            }
            fixture.graph.checkInvariants();
        }
    }

    private static void testMalformedTracesRejected() {
        expectThrows(IllegalArgumentException.class,
                () -> new FiniteUnfoldingBounds(0, 1));
        expectThrows(IllegalArgumentException.class,
                () -> new FiniteUnfoldingBounds(1, 0));

        BooleanGraph fixture = booleanGraph("phase-h/malformed");
        BoundedFiniteUnfoldingOracle oracle = oracle(fixture.graph, BOUNDS);
        StructuralKey beforeUnequal = fixture.graph.stateStructuralKey();
        expectThrows(IllegalArgumentException.class, () -> oracle.establishEquality(
                TypedInvocation.identity(fixture.falseClass),
                TypedInvocation.identity(fixture.trueClass)));
        check(beforeUnequal.equals(fixture.graph.stateStructuralKey()),
                "A failed reachability premise cannot mutate graph state");

        TypedEClassInterface missing = new TypedEClassInterface(
                EClassId.of(999), GraphType.BOOL, TypedSlotContext.empty());
        StructuralKey beforeMissing = fixture.graph.stateStructuralKey();
        expectThrows(IllegalArgumentException.class,
                () -> oracle.enumerate(TypedInvocation.identity(missing)));
        check(beforeMissing.equals(fixture.graph.stateStructuralKey()),
                "An unknown unfolding root is rejected without graph contamination");

        CoherentWitnessFamily oldFamily = fixture.graph.coherentWitnessFamily();
        BoundedFiniteUnfoldingOracle stale = fixture.graph.finiteUnfoldingOracle(
                oldFamily, BOUNDS);
        insert(fixture.graph, constantNode("h/extra/malformed", false));
        StructuralKey beforeStale = fixture.graph.stateStructuralKey();
        expectThrows(IllegalStateException.class, () -> stale.enumerate(
                TypedInvocation.identity(fixture.falseClass)));
        check(beforeStale.equals(fixture.graph.stateStructuralKey()),
                "A stale unfolding snapshot fails before any state change");

        CertifiedInsertionResult alias = insert(
                fixture.graph,
                unaryNode("h/alias/malformed", fixture.falseClass));
        CoherentWitnessFamily beforeDirtyFamily = fixture.graph.coherentWitnessFamily();
        BoundedFiniteUnfoldingOracle beforeDirty = fixture.graph.finiteUnfoldingOracle(
                beforeDirtyFamily, BOUNDS);
        ParentEdgeCertificate pending = parentEquation(
                alias.insertedClass(), fixture.falseClass, "dirty", 0);
        fixture.graph.unionCertified(pending);
        StructuralKey dirtyState = fixture.graph.stateStructuralKey();
        expectThrows(IllegalStateException.class, () -> beforeDirty.enumerate(
                TypedInvocation.identity(fixture.falseClass)));
        check(dirtyState.equals(fixture.graph.stateStructuralKey()),
                "A dirty-graph unfolding attempt leaves the dirty trace unchanged");
        fixture.graph.rebuild();

        BoundedFiniteUnfoldingOracle postRebuild = oracle(fixture.graph, BOUNDS);
        StructuralKey beforeNonleaderFailure = fixture.graph.stateStructuralKey();
        expectThrows(IllegalArgumentException.class, () ->
                postRebuild.establishEquality(
                        TypedInvocation.identity(alias.insertedClass()),
                        TypedInvocation.identity(fixture.trueClass)));
        check(beforeNonleaderFailure.equals(fixture.graph.stateStructuralKey()),
                "Failed nonleader reachability does not path-compress graph state");

        BoundedFiniteUnfoldingOracle capped = oracle(
                fixture.graph, new FiniteUnfoldingBounds(8, 1));
        StructuralKey beforeLimit = fixture.graph.stateStructuralKey();
        expectThrows(IllegalStateException.class, () -> capped.enumerate(
                TypedInvocation.identity(fixture.falseClass)));
        check(beforeLimit.equals(fixture.graph.stateStructuralKey()),
                "An enumeration-limit failure is read-only");

        TypedSlottedPortEGraph empty = new TypedSlottedPortEGraph();
        TypedENode constant = constantNode("h/bad-ec", false);
        CanonicalShape shape = CanonicalShape.of(constant);
        TypedEClassInterface owner = new TypedEClassInterface(
                EClassId.of(40), GraphType.BOOL, TypedSlotContext.empty());
        TypedEClassInterface wrong = new TypedEClassInterface(
                EClassId.of(41), GraphType.BOOL, TypedSlotContext.empty());
        ShapeWitness witness = new ShapeWitness(
                TypedSlotContext.empty(),
                TypedSlotContext.empty(),
                TypedSlotContext.empty(),
                TypedRenaming.identity(TypedSlotContext.empty()));
        TypedEClassRecord record = TypedEClassRecord.of(
                owner,
                Collections.singletonMap(shape, witness),
                TypedSymmetryGroup.identity(TypedSlotContext.empty()));
        InputEquationCertificate wrongEc = new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase-h", "malformed-ec", 0),
                TypedCertificateEndpoint.node(constant),
                TypedCertificateEndpoint.eclassWitness(wrong));
        StructuralKey emptyBefore = empty.stateStructuralKey();
        expectThrows(IllegalArgumentException.class, () ->
                empty.admitFixedBatchRecordCertified(
                        record, Collections.singletonMap(shape, wrongEc)));
        check(emptyBefore.equals(empty.stateStructuralKey()) && empty.classes().isEmpty(),
                "Malformed EC admission is rejected before U, M, or H publication");
    }

    private static BooleanGraph booleanGraph(String label) {
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.certifiedFixture();
        TypedEClassInterface falseClass = insert(
                graph, constantNode("h/false/" + label, false)).insertedClass();
        TypedEClassInterface trueClass = insert(
                graph, constantNode("h/true/" + label, true)).insertedClass();
        return new BooleanGraph(graph, falseClass, trueClass);
    }

    private static CertifiedInsertionResult insert(
            TypedSlottedPortEGraph graph,
            TypedENode node) {
        return graph.insertNode(node, graph.coherentWitnessFamily());
    }

    private static void unionInto(
            TypedSlottedPortEGraph graph,
            TypedEClassInterface child,
            TypedEClassInterface parent,
            String label,
            int ordinal) {
        graph.unionCertified(parentEquation(child, parent, label, ordinal));
        graph.rebuild();
        check(graph.status() == GraphStatus.QUIESCENT,
                "A generated union rebuilds to a quiescent graph");
    }

    private static ParentEdgeCertificate parentEquation(
            TypedEClassInterface child,
            TypedEClassInterface parent,
            String label,
            int ordinal) {
        TypedInvocation parentInvocation = TypedInvocation.identity(parent);
        InputEquationCertificate equation = new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase-h", label, ordinal),
                TypedCertificateEndpoint.eclassWitness(child),
                TypedCertificateEndpoint.invocation(parentInvocation));
        return new ParentEdgeCertificate(child, parentInvocation, equation);
    }

    private static BoundedFiniteUnfoldingOracle oracle(
            TypedSlottedPortEGraph graph,
            FiniteUnfoldingBounds bounds) {
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        return graph.finiteUnfoldingOracle(family, bounds);
    }

    private static TypedENode constantNode(String name, boolean value) {
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name + (value ? "/true" : "/false"),
                Collections.emptyList(),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        return TypedENode.construct(
                operator, TypedSlotContext.empty(), Collections.emptyList());
    }

    private static TypedENode unaryNode(
            String name,
            TypedEClassInterface child) {
        TypedSlotContext empty = TypedSlotContext.empty();
        OnePortSchema schema = new OnePortSchema(GraphType.BOOL);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                Collections.singletonList(schema),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        return TypedENode.construct(
                operator,
                empty,
                Collections.singletonList(
                        OnePort.invocation(empty, TypedInvocation.identity(child))));
    }

    private static TypedENode binaryBagNode(
            String name,
            TypedEClassInterface left,
            TypedEClassInterface right) {
        TypedSlotContext empty = TypedSlotContext.empty();
        BagPortSchema schema = new BagPortSchema(new OnePortSchema(GraphType.BOOL));
        BagPort values = new BagPort(
                schema,
                empty,
                Arrays.asList(
                        OnePort.invocation(empty, TypedInvocation.identity(left)),
                        OnePort.invocation(empty, TypedInvocation.identity(right))));
        return TypedENode.construct(
                bagOperator(name, schema),
                empty,
                Collections.singletonList(values));
    }

    private static InstantiatedOperator bagOperator(
            String name,
            BagPortSchema schema) {
        List<ContainerLawCertificate> certificates = Arrays.asList(
                ContainerLawCertificate.testFixture(
                        schema,
                        ContainerLawCertificate.Law.ASSOCIATIVITY,
                        CertificateOrigin.containerLaw(name, "0:A", 0)),
                ContainerLawCertificate.testFixture(
                        schema,
                        ContainerLawCertificate.Law.COMMUTATIVITY,
                        CertificateOrigin.containerLaw(name, "0:C", 1)));
        Map<PortPath, ContainerLawDeclaration> laws = new LinkedHashMap<>();
        laws.put(PortPath.at(0), ContainerLawDeclaration.certified(
                schema, certificates));
        return OperatorDeclaration.monomorphic(
                name,
                Collections.singletonList(schema),
                GraphType.BOOL,
                laws,
                null).instantiateMonomorphic();
    }

    private static FiniteUnfoldingObserver booleanObserver(
            Map<TypedSlot, Boolean> environment,
            boolean freshValue) {
        Map<TypedSlot, Boolean> copied = Collections.unmodifiableMap(
                new LinkedHashMap<>(environment));
        return tree -> boolKey(evaluateBoolean(tree, copied, freshValue));
    }

    private static boolean evaluateBoolean(
            FiniteUnfoldingTree tree,
            Map<TypedSlot, Boolean> callerEnvironment,
            boolean freshValue) {
        if (!callerEnvironment.keySet().equals(
                tree.rootInvocation().callerContext().slots())) {
            throw new IllegalArgumentException(
                    "Boolean environment must cover the finite root caller context");
        }
        Map<TypedSlot, Boolean> ambient = new LinkedHashMap<>();
        TypedSlotContext exposed = tree.shapeWitness().exposedInterface();
        for (TypedSlot slot : exposed) {
            ambient.put(
                    slot,
                    callerEnvironment.get(tree.rootInvocation().embedding().apply(slot)));
        }
        for (TypedSlot slot : tree.shapeWitness().ambientSupport().minus(exposed)) {
            ambient.put(slot, freshValue);
        }
        Iterator<FiniteUnfoldingTree> children = tree.invocationChildren().iterator();
        List<Boolean> arguments = new ArrayList<>();
        for (PortValue port : tree.restoredRoot().ports()) {
            evaluatePort(port, ambient, freshValue, children, arguments);
        }
        if (children.hasNext()) {
            throw new IllegalStateException("Boolean evaluator left an invocation child unused");
        }
        String operator = tree.restoredRoot().operator().operator();
        if (operator.endsWith("/false")) {
            return false;
        }
        if (operator.endsWith("/true")) {
            return true;
        }
        if (operator.startsWith("h/alias/") || operator.equals("h/first")) {
            return requireArgument(arguments, operator, 0);
        }
        if (operator.startsWith("h/binder/")) {
            return requireArgument(arguments, operator, 0);
        }
        if (operator.startsWith("h/not/")) {
            return !requireArgument(arguments, operator, 0);
        }
        if (operator.startsWith("h/and/") || operator.equals("h/sym-and")) {
            boolean result = true;
            for (boolean argument : arguments) {
                result &= argument;
            }
            return result;
        }
        throw new IllegalArgumentException("Unknown Phase H Boolean operator " + operator);
    }

    private static void evaluatePort(
            PortValue port,
            Map<TypedSlot, Boolean> environment,
            boolean freshValue,
            Iterator<FiniteUnfoldingTree> children,
            List<Boolean> output) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof SlotPortLeaf) {
                Boolean value = environment.get(((SlotPortLeaf) leaf).slot());
                if (value == null) {
                    throw new IllegalStateException("Unbound Boolean slot in finite unfolding");
                }
                output.add(value);
            } else {
                if (!children.hasNext()) {
                    throw new IllegalStateException("Missing finite child during evaluation");
                }
                FiniteUnfoldingTree child = children.next();
                TypedInvocation expected = ((InvocationPortLeaf) leaf).invocation();
                if (!expected.equals(child.rootInvocation())) {
                    throw new IllegalStateException("Finite child occurrence is out of order");
                }
                output.add(evaluateBoolean(child, environment, freshValue));
            }
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue element : ((SeqPort) port).elements()) {
                evaluatePort(element, environment, freshValue, children, output);
            }
            return;
        }
        if (port instanceof BagPort) {
            for (PortValue element : ((BagPort) port).occurrences()) {
                evaluatePort(element, environment, freshValue, children, output);
            }
            return;
        }
        if (port instanceof SetPort) {
            for (PortValue element : ((SetPort) port).elements()) {
                evaluatePort(element, environment, freshValue, children, output);
            }
            return;
        }
        if (port instanceof BindPort) {
            BindPort binder = (BindPort) port;
            Map<TypedSlot, Boolean> body = new LinkedHashMap<>(environment);
            body.put(binder.boundSlot(), false);
            evaluatePort(binder.body(), body, freshValue, children, output);
            return;
        }
        if (port instanceof BindBlockPort) {
            BindBlockPort block = (BindBlockPort) port;
            Map<TypedSlot, Boolean> body = new LinkedHashMap<>(environment);
            for (TypedSlot slot : block.boundContext()) {
                body.put(slot, false);
            }
            evaluatePort(block.body(), body, freshValue, children, output);
            return;
        }
        throw new IllegalStateException("Unhandled Boolean port " + port.getClass().getName());
    }

    private static boolean requireArgument(
            List<Boolean> arguments,
            String operator,
            int index) {
        if (arguments.size() <= index) {
            throw new IllegalStateException(operator + " is missing argument " + index);
        }
        return arguments.get(index);
    }

    private static StructuralKey boolKey(boolean value) {
        return StructuralKey.leaf("finite-model/bool", Boolean.toString(value));
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
            Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (expected.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName()
                            + " but received " + throwable,
                    throwable);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static final class BooleanGraph {
        private final TypedSlottedPortEGraph graph;
        private final TypedEClassInterface falseClass;
        private final TypedEClassInterface trueClass;

        private BooleanGraph(
                TypedSlottedPortEGraph graph,
                TypedEClassInterface falseClass,
                TypedEClassInterface trueClass) {
            this.graph = graph;
            this.falseClass = falseClass;
            this.trueClass = trueClass;
        }
    }
}

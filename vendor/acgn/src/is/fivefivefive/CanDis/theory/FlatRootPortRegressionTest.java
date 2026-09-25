package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P2-05 bounded constructor-to-result audit using structural fixture laws.
 * This test grants no semantic/certificate authority and does not establish
 * Java-Lean refinement. The main audit's javac source extraction is separate.
 */
public final class FlatRootPortRegressionTest {
    private static final List<ContainerLawDeclaration.Kind> KINDS = List.of(
            ContainerLawDeclaration.Kind.SEQ,
            ContainerLawDeclaration.Kind.BAG,
            ContainerLawDeclaration.Kind.SET);
    private static final List<GraphType> TYPES = List.of(GraphType.INT, GraphType.BOOL);
    private static final NodeSealer UNUSED_SEALER = node -> {
        throw new AssertionError("Same-headed visible inputs must not invoke the sealer");
    };
    private static int checks;
    private static int matrixCases;
    private static int matrixAdmitted;
    private static int matrixRejected;
    private static int polymorphicInstances;
    private static int flatNodes;

    private FlatRootPortRegressionTest() {
    }

    public static void main(String[] args) {
        runAssertions();
        System.out.println("FlatRootPortRegressionTest passed: " + checks + " checks"
                + " (" + matrixCases + " constructor matrix cases: "
                + matrixAdmitted + " admitted, " + matrixRejected + " rejected; "
                + polymorphicInstances + " polymorphic instances; "
                + flatNodes + " flat node results)");
    }

    static int runAssertions() {
        checks = matrixCases = matrixAdmitted = matrixRejected = 0;
        polymorphicInstances = flatNodes = 0;
        testConstructorMatrix();
        testIndependentPostconditions();
        testPolymorphicInstantiation();
        check(matrixCases == 1080 && matrixAdmitted == 132 && matrixRejected == 948,
                "Constructor matrix coverage changed");
        check(polymorphicInstances == 54, "Polymorphic coverage changed");
        check(flatNodes == 24, "Flat-result coverage changed");
        return checks;
    }

    private static void testConstructorMatrix() {
        Integer[] indices = {null, Integer.MIN_VALUE, -1, 0, 1, 2, 3, 4, Integer.MAX_VALUE};
        for (boolean factory : new boolean[] {false, true}) {
            for (ContainerLawDeclaration.Kind kind : KINDS) {
                for (GraphType element : TYPES) {
                    for (GraphType result : TYPES) {
                        for (int count = 0; count <= 4; count++) {
                            List<PortSchema> ports = new ArrayList<>();
                            Map<PortPath, ContainerLawDeclaration> laws = new TreeMap<>();
                            for (int port = 0; port < count; port++) {
                                ports.add(container(kind, ArityPolicy.nonemptyVariadic(),
                                        new OnePortSchema(element)));
                                laws.put(PortPath.at(port), laws(kind, true, false));
                            }
                            for (Integer index : indices) {
                                String label = "factory=" + factory + ",kind=" + kind
                                        + ",ports=" + count + ",index=" + index
                                        + ",element=" + element + ",result=" + result;
                                matrixCases++;
                                // The expected result uses no production guard/helper.
                                boolean admitted = index == null || (count == 1
                                        && index == 0 && element.equals(result));
                                if (admitted) {
                                    OperatorDeclaration declaration = declaration(
                                            factory, ports, result, laws, index);
                                    assertDeclaration(declaration, ports, result, laws, index, label);
                                    matrixAdmitted++;
                                } else {
                                    String message;
                                    if (index < 0) message = "Port path components must be non-negative";
                                    else if (index >= count) message = "Flat port index is out of range";
                                    else if (count != 1 || index != 0)
                                        message = "requires one container-valued port";
                                    else message = "must contain One(outputType) elements";
                                    reject(() -> declaration(factory, ports, result, laws, index),
                                            message, label);
                                    matrixRejected++;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void assertDeclaration(OperatorDeclaration declaration,
            List<PortSchema> ports, GraphType result,
            Map<PortPath, ContainerLawDeclaration> laws, Integer index, String label) {
        check(declaration.portSchemas().equals(ports), label + ": schemas preserved");
        check(declaration.outputType().equals(result), label + ": result preserved");
        check(declaration.containerLaws().equals(laws), label + ": laws preserved");
        check(java.util.Objects.equals(declaration.flatPortIndex(), index), label + ": index preserved");
        check(declaration.usesFlatConstruction() == (index != null), label + ": enabled state");
        if (index == null) {
            check(declaration.flatLicense() == FlatLicense.none(), label + ": disabled license");
        } else {
            check(declaration.flatLicense().path().depth() == 0, label + ": root depth");
            check(declaration.flatLicense().path().portIndex() == 0, label + ": root index");
        }
        InstantiatedOperator instance = declaration.instantiateMonomorphic();
        check(instance.portSchemas().equals(ports), label + ": instantiated schemas");
        check(instance.outputType().equals(result), label + ": instantiated result");
        check(instance.flatLicense() == declaration.flatLicense(), label + ": same license object");
        check(instance.usesFlatConstruction() == (index != null), label + ": instantiated flatness");
    }

    private static void testIndependentPostconditions() {
        OnePortSchema one = new OnePortSchema(GraphType.INT);
        for (boolean factory : new boolean[] {false, true}) {
            for (ContainerLawDeclaration.Kind kind : KINDS) {
                PortSchema positive = container(kind, ArityPolicy.nonemptyVariadic(), one);
                Map<PortPath, ContainerLawDeclaration> noA = Map.of(PortPath.at(0), laws(kind, false, false));
                reject(() -> declaration(factory, List.of(positive), GraphType.INT, noA, 0),
                        "requires an associative law declaration", "independent A check " + kind);
                check(!declaration(factory, List.of(positive), GraphType.INT, noA, null).usesFlatConstruction(),
                        "Nonflat has no A requirement");

                // {1,2} is valid even for Set but is not closed under 2+2-1.
                PortSchema finite = container(kind, ArityPolicy.finite(1, 2), one);
                Map<PortPath, ContainerLawDeclaration> positiveLaws = Map.of(
                        PortPath.at(0), laws(kind, true, false));
                reject(() -> declaration(factory, List.of(finite), GraphType.INT, positiveLaws, 0),
                        "requires closure under k+l-1 flat splicing", "independent splice check " + kind);
                check(!declaration(factory, List.of(finite), GraphType.INT, positiveLaws, null).usesFlatConstruction(),
                        "Nonflat has no splice-closure requirement");

                PortSchema zero = container(kind, ArityPolicy.zeroOrMore(), one);
                reject(() -> declaration(factory, List.of(zero), GraphType.INT, positiveLaws, 0),
                        "admitting zero requires an exact unit license", "independent unit check " + kind);
                check(!declaration(factory, List.of(zero), GraphType.INT, positiveLaws, null).usesFlatConstruction(),
                        "Nonflat zero does not imply a unit");
                InstantiatedOperator withUnit = declaration(factory, List.of(zero), GraphType.INT,
                        Map.of(PortPath.at(0), laws(kind, true, true)), 0).instantiateMonomorphic();
                TypedENode empty = TypedENode.flatConstruct(new FlatApplication(
                        withUnit, TypedSlotContext.empty(), List.of()), UNUSED_SEALER);
                assertFlatNode(empty, withUnit, 0);

                PortSchema nested = container(kind, ArityPolicy.nonemptyVariadic(), new SeqPortSchema(one));
                Map<PortPath, ContainerLawDeclaration> nestedLaws = Map.of(
                        PortPath.at(0), laws(kind, true, false),
                        PortPath.at(0).child(), laws(ContainerLawDeclaration.Kind.SEQ, false, false));
                reject(() -> declaration(factory, List.of(nested), GraphType.INT, nestedLaws, 0),
                        "must contain One(outputType) elements", "nested element is not One " + kind);
                check(!declaration(factory, List.of(nested), GraphType.INT, nestedLaws, null).usesFlatConstruction(),
                        "Nonflat may have nested container elements");
                reject(() -> declaration(factory, List.of(positive), GraphType.INT, Map.of(), null),
                        "requires exactly one explicit law declaration", "nonflat still validates laws");
            }
            reject(() -> declaration(factory, List.of(one), GraphType.INT, Map.of(), 0),
                    "Schema is not an outer container", "flat One root rejected");
            check(!declaration(factory, List.of(one), GraphType.INT, Map.of(), null).usesFlatConstruction(),
                    "Nonflat One root admitted");
        }
    }

    private static void testPolymorphicInstantiation() {
        GraphType alpha = GraphType.typeVariable("a");
        List<GraphType> substitutions = List.of(GraphType.INT, GraphType.BOOL,
                GraphType.relation(GraphType.constructor("FlatRootFixture")));
        for (ContainerLawDeclaration.Kind kind : KINDS) {
            for (int count = 0; count <= 4; count++) {
                for (boolean flat : new boolean[] {false, true}) {
                    if (flat && count != 1) continue;
                    List<PortSchema> ports = new ArrayList<>();
                    Map<PortPath, ContainerLawDeclaration> laws = new TreeMap<>();
                    for (int port = 0; port < count; port++) {
                        ports.add(container(kind, ArityPolicy.nonemptyVariadic(), new OnePortSchema(alpha)));
                        laws.put(PortPath.at(port), laws(kind, flat, false));
                    }
                    OperatorDeclaration declaration = new OperatorDeclaration(
                            "p2-05-poly", List.of("a"), ports, alpha, laws, flat ? 0 : null);
                    for (GraphType type : substitutions) {
                        Map<String, GraphType> arguments = Map.of("a", type);
                        InstantiatedOperator instance = declaration.instantiate(arguments);
                        polymorphicInstances++;
                        check(instance.declaration() == declaration, "Instantiation retains declaration");
                        check(instance.typeArguments().equals(arguments), "Instantiation retains arguments");
                        check(instance.portSchemas().size() == count, "Instantiation preserves port count");
                        check(instance.outputType().equals(type), "Result type substitutes");
                        check(instance.flatLicense() == declaration.flatLicense(), "License object is preserved");
                        check(instance.containerLaws() == declaration.containerLaws(), "Law map is preserved");
                        check(instance.usesFlatConstruction() == flat, "Flatness is preserved");
                        PortSchema expected = container(kind, ArityPolicy.nonemptyVariadic(), new OnePortSchema(type));
                        for (PortSchema schema : instance.portSchemas()) {
                            check(schema.equals(expected), "Each port substitutes to the exact element type");
                        }
                        if (flat) exerciseFlatResult(instance);
                        else exerciseNonflatResult(instance, type);
                        check(declaration.portSchemas().equals(ports) && declaration.outputType().equals(alpha),
                                "Instantiation does not mutate the polymorphic declaration");
                    }
                    reject(() -> declaration.instantiate(Map.of()), "exactly the declared type parameters",
                            "missing polymorphic assignment");
                    reject(() -> declaration.instantiate(Map.of("a", GraphType.INT, "b", GraphType.INT)),
                            "exactly the declared type parameters", "extra polymorphic assignment");
                }
            }
            reject(() -> new OperatorDeclaration("p2-05-poly-mismatch", List.of("a", "b"),
                    List.of(container(kind, ArityPolicy.nonemptyVariadic(), new OnePortSchema(alpha))),
                    GraphType.typeVariable("b"), Map.of(PortPath.at(0), laws(kind, true, false)), 0),
                    "must contain One(outputType) elements", "distinct declared variables are not equated");
        }
    }

    private static void exerciseFlatResult(InstantiatedOperator instance) {
        check(instance.flatLicense().path().equals(PortPath.at(0)), "Polymorphic flat license is at root zero");
        TypedSlot a = TypedSlot.source(instance.outputType(), 0);
        TypedSlot b = TypedSlot.source(instance.outputType(), 1);
        TypedSlot c = TypedSlot.source(instance.outputType(), 2);
        TypedSlotContext context = TypedSlotContext.of(a, b, c);
        FlatLeaf fa = new FlatLeaf(OnePort.slot(context, a));
        FlatLeaf fb = new FlatLeaf(OnePort.slot(context, b));
        FlatLeaf fc = new FlatLeaf(OnePort.slot(context, c));
        FlatApplication child = new FlatApplication(instance, context, List.of(fb, fc));
        TypedENode nested = TypedENode.flatConstruct(new FlatApplication(
                instance, context, List.of(fa, child)), UNUSED_SEALER);
        TypedENode direct = TypedENode.flatConstruct(new FlatApplication(
                instance, context, List.of(fa, fb, fc)), UNUSED_SEALER);
        assertFlatNode(nested, instance, 3);
        assertFlatNode(direct, instance, 3);
        check(nested.equals(direct), "Same-headed splicing produces the same sole root container");
        check(nested.support().equals(context), "Flat result preserves exact fixture support");
        reject(() -> TypedENode.construct(instance, context, direct.ports()),
                "must be constructed through flatConstruct", "ordinary construction cannot bypass flat routing");
        reject(() -> new FlatApplication(instance, context, List.of()),
                "is not admitted", "positive flat source rejects zero operands");
        GraphType wrong = instance.outputType().equals(GraphType.INT) ? GraphType.BOOL : GraphType.INT;
        TypedSlot other = TypedSlot.source(wrong, 0);
        TypedSlotContext wrongContext = TypedSlotContext.singleton(other);
        reject(() -> new FlatApplication(instance, wrongContext,
                List.of(new FlatLeaf(OnePort.slot(wrongContext, other)))),
                "output must equal the recursive operator output type", "flat source rejects mismatched leaf type");
    }

    private static void exerciseNonflatResult(InstantiatedOperator instance, GraphType type) {
        TypedSlot slot = TypedSlot.source(type, 0);
        TypedSlotContext context = TypedSlotContext.singleton(slot);
        List<PortValue> ports = new ArrayList<>();
        for (PortSchema schema : instance.portSchemas()) {
            ports.add(value(schema, context, List.of(OnePort.slot(context, slot))));
        }
        TypedENode node = TypedENode.construct(instance, context, ports);
        check(node.ports().size() == ports.size(), "Nonflat result preserves zero/multiple root ports");
        check(node.outputType().equals(type), "Nonflat result preserves substituted output");
        reject(() -> new FlatApplication(instance, context, List.of()),
                "requires an operator declared for flat construction", "nonflat is not a FlatApplication");
        for (int sourceCount = 0; sourceCount <= 4; sourceCount++) {
            if (sourceCount == ports.size()) continue;
            List<PortValue> badCount = new ArrayList<>();
            for (int port = 0; port < sourceCount; port++) {
                badCount.add(ports.isEmpty() ? OnePort.slot(context, slot) : ports.get(0));
            }
            reject(() -> TypedENode.construct(instance, context, badCount),
                    "Node port count does not match its signature", "nonflat rejects source count " + sourceCount);
        }
    }

    private static void assertFlatNode(TypedENode node, InstantiatedOperator instance, int elements) {
        flatNodes++;
        check(node.operator() == instance, "Flat node retains its instantiated operator");
        check(node.ports().size() == 1, "Flat node has exactly one root port");
        PortValue root = node.ports().get(0);
        check(root.schema().equals(instance.portSchemas().get(0)), "Root has the declared container schema");
        check(node.outputType().equals(instance.outputType()), "Flat result has exact output type");
        int size;
        if (root instanceof SeqPort) size = ((SeqPort) root).elements().size();
        else if (root instanceof BagPort) size = ((BagPort) root).occurrences().size();
        else if (root instanceof SetPort) size = ((SetPort) root).elements().size();
        else throw new AssertionError("Flat result is not a Seq/Bag/Set");
        check(size == elements, "Flat result retains expected fixture element count");
    }

    private static OperatorDeclaration declaration(boolean factory, List<PortSchema> ports,
            GraphType output, Map<PortPath, ContainerLawDeclaration> laws, Integer index) {
        return factory
                ? OperatorDeclaration.monomorphic("p2-05-fixture", ports, output, laws, index)
                : new OperatorDeclaration("p2-05-fixture", List.of(), ports, output, laws, index);
    }

    private static PortSchema container(ContainerLawDeclaration.Kind kind, ArityPolicy arities,
            PortSchema element) {
        switch (kind) {
            case SEQ: return new SeqPortSchema(arities, element);
            case BAG: return new BagPortSchema(arities, element);
            case SET: return new SetPortSchema(arities, element);
            default: throw new AssertionError("Not a container kind: " + kind);
        }
    }

    private static PortValue value(PortSchema schema, TypedSlotContext context, List<PortValue> elements) {
        if (schema instanceof SeqPortSchema) return new SeqPort((SeqPortSchema) schema, context, elements);
        if (schema instanceof BagPortSchema) return new BagPort((BagPortSchema) schema, context, elements);
        if (schema instanceof SetPortSchema) return new SetPort((SetPortSchema) schema, context, elements);
        throw new AssertionError("Not a container schema: " + schema);
    }

    private static ContainerLawDeclaration laws(ContainerLawDeclaration.Kind kind, boolean associative,
            boolean unit) {
        return ContainerLawDeclaration.of(kind, associative,
                kind != ContainerLawDeclaration.Kind.SEQ, kind == ContainerLawDeclaration.Kind.SET, unit);
    }

    private static void reject(Runnable action, String message, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage() != null && expected.getMessage().contains(message),
                    label + ": wrong rejection boundary: " + expected.getMessage());
            return;
        }
        throw new AssertionError(label + ": invalid input accepted");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Finite P2-06 constructor/substitution checks; fixture laws confer no authority. */
public final class FlatTypeSubstitutionRegressionTest {
    private static int checks;
    private static int observations;

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Optional observation TSV path only");
        StringBuilder trace = new StringBuilder("case\tkind\telement\toutput\texpected\n");
        GraphType a = GraphType.typeVariable("a");
        GraphType b = GraphType.typeVariable("b");
        List<GraphType> patterns = List.of(a, b, GraphType.INT, GraphType.BOOL,
                GraphType.arrow(a, b), GraphType.relation(a, b),
                GraphType.constructor("Box", a, GraphType.arrow(b, a)),
                GraphType.relation(GraphType.constructor("Pair", a, b)),
                GraphType.arrow(GraphType.relation(a, a), GraphType.constructor("Box", b)));
        List<GraphType> replacements = List.of(GraphType.INT, GraphType.BOOL,
                GraphType.constructor("AlloySig:Person"),
                GraphType.relation(GraphType.constructor("AlloySig:Person")),
                GraphType.arrow(GraphType.INT, GraphType.BOOL));
        for (PortSchema.Kind kind : List.of(PortSchema.Kind.SEQ, PortSchema.Kind.BAG, PortSchema.Kind.SET)) {
            for (GraphType pattern : patterns) {
                OperatorDeclaration declaration = declaration(kind, pattern, pattern);
                for (GraphType left : replacements) for (GraphType right : replacements) {
                    Map<String, GraphType> substitution = Map.of("a", left, "b", right);
                    GraphType expected = interpret(pattern, substitution);
                    InstantiatedOperator instance = declaration.instantiate(substitution);
                    GraphType element = ((OnePortSchema) OperatorDeclaration.elementSchema(
                            instance.portSchemas().get(0))).type();
                    check(element.equals(expected), "Element substitutes recursively");
                    check(instance.outputType().equals(expected), "Result substitutes recursively");
                    check(element.equals(instance.outputType()), "Exact type, not operator-name equality");
                    check(instance.declaration() == declaration, "Declaration provenance survives");
                    check(instance.typeArguments().equals(substitution), "Same substitution at both sites");
                    check(instance.portSchemas().get(0).kind() == kind, "Container kind survives");
                    check(declaration.outputType().equals(pattern), "Source declaration remains polymorphic");
                    TypedSlot slot = TypedSlot.source(expected, 0);
                    TypedSlotContext context = TypedSlotContext.of(slot);
                    FlatLeaf leaf = new FlatLeaf(OnePort.slot(context, slot));
                    TypedENode node = TypedENode.flatConstruct(new FlatApplication(instance, context,
                            List.of(leaf, leaf)), ignored -> {
                                throw new AssertionError("No foreign application may be sealed");
                            });
                    check(node.outputType().equals(expected), "Constructed result has exact type");
                    check(node.ports().get(0).schema().equals(instance.portSchemas().get(0)),
                            "Constructed root has exact instantiated schema");
                    trace.append(observations++).append('\t').append(kind).append('\t')
                            .append(encode(element)).append('\t').append(encode(instance.outputType()))
                            .append('\t').append(encode(expected)).append('\n');
                }
            }
            for (GraphType element : patterns) for (GraphType result : patterns) {
                if (element.equals(result)) continue;
                reject(() -> declaration(kind, element, result), "One(outputType)");
            }
            reject(() -> declaration(kind, a, b).instantiate(Map.of("a", GraphType.INT, "b", GraphType.INT)),
                    "One(outputType)");
            OperatorDeclaration polymorphic = declaration(kind, a, a);
            reject(() -> polymorphic.instantiate(Map.of("a", GraphType.INT)), "exactly the declared");
            reject(() -> polymorphic.instantiate(Map.of("a", GraphType.INT, "b", GraphType.INT,
                    "extra", GraphType.INT)), "exactly the declared");
            InstantiatedOperator ints = polymorphic.instantiate(Map.of("a", GraphType.INT, "b", GraphType.INT));
            InstantiatedOperator bools = polymorphic.instantiate(Map.of("a", GraphType.BOOL, "b", GraphType.INT));
            check(((OnePortSchema) OperatorDeclaration.elementSchema(ints.portSchemas().get(0))).type()
                    .equals(GraphType.INT) && ints.outputType().equals(GraphType.INT),
                    "A later Bool instantiation must not alter the retained Int instance");
            check(((OnePortSchema) OperatorDeclaration.elementSchema(bools.portSchemas().get(0))).type()
                    .equals(GraphType.BOOL) && bools.outputType().equals(GraphType.BOOL),
                    "Both exact instantiations coexist independently");
            check(!ints.equals(bools), "Same declaration does not erase different type arguments");
            TypedSlot boolSlot = TypedSlot.source(GraphType.BOOL, 0);
            TypedSlotContext boolContext = TypedSlotContext.of(boolSlot);
            reject(() -> new FlatApplication(ints, boolContext,
                    List.of(new FlatLeaf(OnePort.slot(boolContext, boolSlot)))), "type");
        }
        check(observations == 675, "Frozen 3 * 9 * 5 * 5 instance census");
        if (args.length == 1) Files.writeString(Path.of(args[0]), trace, StandardCharsets.UTF_8);
        System.out.println("FlatTypeSubstitutionRegressionTest passed: " + checks
                + " checks; " + observations + " observations");
    }

    private static OperatorDeclaration declaration(PortSchema.Kind kind, GraphType element, GraphType output) {
        PortSchema one = new OnePortSchema(element);
        PortSchema schema;
        ContainerLawDeclaration.Kind lawKind;
        switch (kind) {
            case SEQ: schema = new SeqPortSchema(one); lawKind = ContainerLawDeclaration.Kind.SEQ; break;
            case BAG: schema = new BagPortSchema(one); lawKind = ContainerLawDeclaration.Kind.BAG; break;
            case SET: schema = new SetPortSchema(one); lawKind = ContainerLawDeclaration.Kind.SET; break;
            default: throw new AssertionError(kind);
        }
        return new OperatorDeclaration("p2-06-fixture", List.of("a", "b"), List.of(schema), output,
                Map.of(PortPath.at(0), ContainerLawDeclaration.of(lawKind, true,
                        kind != PortSchema.Kind.SEQ, kind == PortSchema.Kind.SET, false)), 0);
    }

    // Independent recursive expectation: deliberately does not call GraphType.substitute.
    private static GraphType interpret(GraphType type, Map<String, GraphType> substitution) {
        if (type.kind() == GraphType.Kind.TYPE_VARIABLE) return substitution.get(type.symbol());
        List<GraphType> children = new ArrayList<>();
        for (GraphType child : type.arguments()) children.add(interpret(child, substitution));
        switch (type.kind()) {
            case INT: return GraphType.INT;
            case BOOL: return GraphType.BOOL;
            case ARROW: return GraphType.arrow(children.get(0), children.get(1));
            case RELATION: return GraphType.relation(children);
            case CONSTRUCTOR: return GraphType.constructor(type.symbol(), children);
            default: throw new AssertionError(type.kind());
        }
    }

    private static String encode(GraphType type) {
        String symbol = type.symbol() == null ? "-" : type.symbol().length() + ":" + type.symbol();
        List<String> arguments = new ArrayList<>();
        for (GraphType child : type.arguments()) arguments.add(encode(child));
        return type.kind().ordinal() + "[" + symbol + ";" + String.join(",", arguments) + "]";
    }

    private static void reject(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(message), "Wrong boundary: " + expected.getMessage());
            return;
        }
        throw new AssertionError("Expected rejection at " + message);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

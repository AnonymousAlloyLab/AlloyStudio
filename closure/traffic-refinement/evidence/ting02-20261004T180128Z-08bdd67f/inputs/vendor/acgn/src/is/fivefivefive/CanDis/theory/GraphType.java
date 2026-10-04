package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable structural representation of the type grammar in Definition 1. */
public final class GraphType implements Comparable<GraphType> {
    public enum Kind {
        TYPE_VARIABLE,
        INT,
        BOOL,
        ARROW,
        RELATION,
        CONSTRUCTOR
    }

    public static final GraphType INT = new GraphType(Kind.INT, null, Collections.emptyList());
    public static final GraphType BOOL = new GraphType(Kind.BOOL, null, Collections.emptyList());

    private final Kind kind;
    private final String symbol;
    private final List<GraphType> arguments;

    private GraphType(Kind kind, String symbol, List<GraphType> arguments) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.symbol = symbol;
        Objects.requireNonNull(arguments, "arguments");
        List<GraphType> copied = new ArrayList<>(arguments.size());
        for (GraphType argument : arguments) {
            copied.add(Objects.requireNonNull(argument, "type argument"));
        }
        this.arguments = Collections.unmodifiableList(copied);
        validateShape();
    }

    public static GraphType typeVariable(String name) {
        return new GraphType(Kind.TYPE_VARIABLE, requireSymbol(name), Collections.emptyList());
    }

    public static GraphType arrow(GraphType source, GraphType target) {
        List<GraphType> arguments = new ArrayList<>(2);
        arguments.add(Objects.requireNonNull(source, "source"));
        arguments.add(Objects.requireNonNull(target, "target"));
        return new GraphType(Kind.ARROW, null, arguments);
    }

    public static GraphType relation(GraphType... columns) {
        Objects.requireNonNull(columns, "columns");
        List<GraphType> arguments = new ArrayList<>(columns.length);
        Collections.addAll(arguments, columns);
        return relation(arguments);
    }

    public static GraphType relation(List<GraphType> columns) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("A relation type must have at least one column");
        }
        return new GraphType(Kind.RELATION, null, columns);
    }

    public static GraphType constructor(String name, GraphType... arguments) {
        Objects.requireNonNull(arguments, "arguments");
        List<GraphType> copied = new ArrayList<>(arguments.length);
        Collections.addAll(copied, arguments);
        return constructor(name, copied);
    }

    public static GraphType constructor(String name, List<GraphType> arguments) {
        return new GraphType(Kind.CONSTRUCTOR, requireSymbol(name), arguments);
    }

    private static String requireSymbol(String symbol) {
        if (!AlloyTypeBridge.isAdmittedIdentity(symbol)) {
            throw new IllegalArgumentException(
                    "Type symbol must be a well-formed visible identity");
        }
        if (symbol.startsWith("AlloySig:")) {
            return "AlloySig:" + AlloyTypeBridge.normalizeAlloyIdentity(
                    symbol.substring("AlloySig:".length()));
        }
        return symbol;
    }

    private void validateShape() {
        switch (kind) {
            case TYPE_VARIABLE:
            case CONSTRUCTOR:
                requireSymbol(symbol);
                break;
            default:
                if (symbol != null) {
                    throw new IllegalArgumentException(kind + " must not carry a symbol");
                }
        }
        switch (kind) {
            case TYPE_VARIABLE:
            case INT:
            case BOOL:
                if (!arguments.isEmpty()) {
                    throw new IllegalArgumentException(kind + " must not carry type arguments");
                }
                break;
            case ARROW:
                if (arguments.size() != 2) {
                    throw new IllegalArgumentException("An arrow type requires two arguments");
                }
                break;
            case RELATION:
                if (arguments.isEmpty()) {
                    throw new IllegalArgumentException("A relation type requires at least one column");
                }
                break;
            case CONSTRUCTOR:
                break;
            default:
                throw new IllegalStateException("Unhandled type kind " + kind);
        }
    }

    public Kind kind() {
        return kind;
    }

    public String symbol() {
        return symbol;
    }

    public List<GraphType> arguments() {
        return arguments;
    }

    public Set<String> typeVariables() {
        Set<String> variables = new TreeSet<>();
        collectTypeVariables(variables);
        return Collections.unmodifiableSet(variables);
    }

    private void collectTypeVariables(Set<String> variables) {
        if (kind == Kind.TYPE_VARIABLE) {
            variables.add(symbol);
        }
        for (GraphType argument : arguments) {
            argument.collectTypeVariables(variables);
        }
    }

    public GraphType substitute(Map<String, GraphType> substitution) {
        Objects.requireNonNull(substitution, "substitution");
        if (kind == Kind.TYPE_VARIABLE) {
            if (!substitution.containsKey(symbol)) {
                return this;
            }
            return Objects.requireNonNull(substitution.get(symbol), "replacement type");
        }
        if (arguments.isEmpty()) {
            return this;
        }
        List<GraphType> replaced = new ArrayList<>(arguments.size());
        boolean changed = false;
        for (GraphType argument : arguments) {
            GraphType next = argument.substitute(substitution);
            replaced.add(next);
            changed |= next != argument;
        }
        if (!changed) {
            return this;
        }
        switch (kind) {
            case ARROW:
                return arrow(replaced.get(0), replaced.get(1));
            case RELATION:
                return relation(replaced);
            case CONSTRUCTOR:
                return constructor(symbol, replaced);
            default:
                throw new IllegalStateException("Unexpected parameterized type kind " + kind);
        }
    }

    @Override
    public int compareTo(GraphType other) {
        Objects.requireNonNull(other, "other");
        int compared = Integer.compare(kind.ordinal(), other.kind.ordinal());
        if (compared != 0) {
            return compared;
        }
        compared = compareNullable(symbol, other.symbol);
        if (compared != 0) {
            return compared;
        }
        int shared = Math.min(arguments.size(), other.arguments.size());
        for (int i = 0; i < shared; i++) {
            compared = arguments.get(i).compareTo(other.arguments.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(arguments.size(), other.arguments.size());
    }

    private static int compareNullable(String left, String right) {
        if (left == null) {
            return right == null ? 0 : -1;
        }
        return right == null ? 1 : left.compareTo(right);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GraphType)) {
            return false;
        }
        GraphType type = (GraphType) other;
        return kind == type.kind
                && Objects.equals(symbol, type.symbol)
                && arguments.equals(type.arguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, symbol, arguments);
    }

    @Override
    public String toString() {
        switch (kind) {
            case TYPE_VARIABLE:
                return "'" + symbol;
            case INT:
                return "Int";
            case BOOL:
                return "Bool";
            case ARROW:
                return "(" + arguments.get(0) + " -> " + arguments.get(1) + ")";
            case RELATION:
                return formatApplication("Rel", arguments);
            case CONSTRUCTOR:
                return arguments.isEmpty() ? symbol : formatApplication(symbol, arguments);
            default:
                throw new IllegalStateException("Unhandled type kind " + kind);
        }
    }

    private static String formatApplication(String head, List<GraphType> arguments) {
        StringBuilder result = new StringBuilder(head).append('(');
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(arguments.get(i));
        }
        return result.append(')').toString();
    }
}

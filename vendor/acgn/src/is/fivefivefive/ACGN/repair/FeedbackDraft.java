package is.fivefivefive.ACGN.repair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import edu.mit.csail.sdg.ast.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Prefix semantic choices; arities, source syntax and fresh names are executor-owned. */
final class FeedbackDraft {
    enum Family {
        TEMPORAL_INSERT, TEMPORAL_DELETE, TEMPORAL_UPDATE,
        BINDING_INSERT, BINDING_DELETE, BINDING_UPDATE,
        MATRIX_INSERT, MATRIX_DELETE, MATRIX_UPDATE
    }
    private static final Set<ExprUnary.Op> UNARY = Set.of(ExprUnary.Op.NOT, ExprUnary.Op.NO,
            ExprUnary.Op.SOME, ExprUnary.Op.LONE, ExprUnary.Op.ONE, ExprUnary.Op.TRANSPOSE,
            ExprUnary.Op.CLOSURE, ExprUnary.Op.RCLOSURE, ExprUnary.Op.CARDINALITY,
            ExprUnary.Op.AFTER, ExprUnary.Op.ALWAYS, ExprUnary.Op.EVENTUALLY, ExprUnary.Op.BEFORE,
            ExprUnary.Op.HISTORICALLY, ExprUnary.Op.ONCE, ExprUnary.Op.PRIME);
    private static final Set<ExprBinary.Op> BINARY = Set.of(ExprBinary.Op.AND, ExprBinary.Op.OR,
            ExprBinary.Op.IMPLIES, ExprBinary.Op.IFF, ExprBinary.Op.EQUALS, ExprBinary.Op.NOT_EQUALS,
            ExprBinary.Op.IN, ExprBinary.Op.NOT_IN, ExprBinary.Op.JOIN, ExprBinary.Op.ARROW,
            ExprBinary.Op.PLUS, ExprBinary.Op.MINUS, ExprBinary.Op.INTERSECT,
            ExprBinary.Op.DOMAIN, ExprBinary.Op.RANGE, ExprBinary.Op.LT, ExprBinary.Op.LTE,
            ExprBinary.Op.GT, ExprBinary.Op.GTE, ExprBinary.Op.UNTIL, ExprBinary.Op.RELEASES,
            ExprBinary.Op.SINCE, ExprBinary.Op.TRIGGERED);
    private static boolean binarySupported(ExprBinary.Op op) {
        return BINARY.contains(op) || op.isArrow && op != ExprBinary.Op.ISSEQ_ARROW_LONE;
    }
    final int site;
    final Family family;
    private final FeedbackSourceGraph graph;
    private final Set<Integer> available;
    private final List<String> freshNames = new ArrayList<>();
    private Node root;
    // Coarse public sorts. Exact products and overloads are checked on source commit.
    static final int ANY = 0, BOOL = 1, REL = 2, INT = 3;

    private static final class Node {
        final int opcode, payload;
        int arity;
        final String template;
        final List<Node> children = new ArrayList<>();
        final List<Integer> introduced;
        final List<Group> groups = new ArrayList<>();
        int disjoint = -1;
        Node(int opcode, int arity, int payload, String template, List<Integer> introduced) {
            this.opcode = opcode; this.arity = arity; this.payload = payload;
            this.template = template; this.introduced = introduced;
        }
        String render() {
            if (children.size() != arity) throw new IllegalArgumentException("DRAFT_INCOMPLETE");
            if (arity == 0) return template;
            if (!groups.isEmpty()) {
                List<String> domains = new ArrayList<>();
                for (int i = 0; i < groups.size(); i++) domains.add(groups.get(i).prefix
                        + " (" + children.get(i).render() + ")");
                return "(" + template + " " + String.join(", ", domains) + " | ("
                        + children.get(groups.size()).render() + "))";
            }
            if (arity == 1 && template.equals("'")) return "((" + children.get(0).render() + ")')";
            if (arity == 1) return "(" + template + " (" + children.get(0).render() + "))";
            return "((" + children.get(0).render() + ") " + template + " (" + children.get(1).render() + "))";
        }
    }
    private record Group(List<Integer> slots, String prefix, int cardinality, int disjoint) { }
    private record Hole(Node parent, List<Integer> scope) { }

    FeedbackDraft(FeedbackSourceGraph graph, int site, Family family) {
        graph.source(site);
        this.graph = graph; this.site = site; this.family = family;
        if (family == Family.TEMPORAL_UPDATE && !FeedbackSourceGraph.temporal(graph.expressions.get(site))) {
            throw new IllegalArgumentException("TEMPORAL_SITE_REQUIRED");
        }
        if (family.name().endsWith("DELETE")) throw new IllegalArgumentException("USE_DELETE_CONTROL");
        if (family == Family.BINDING_UPDATE && !(graph.expressions.get(site) instanceof ExprQt)) {
            throw new IllegalArgumentException("BINDING_SITE_REQUIRED");
        }
        available = Set.copyOf(graph.scopes.get(site).values());
    }

    private Hole next() { return root == null ? new Hole(null, List.of()) : next(root, List.of()); }
    private Hole next(Node node, List<Integer> scope) {
        for (int i = 0; i < node.children.size(); i++) {
            Hole result = next(node.children.get(i), childScope(node, scope, i));
            if (result != null) return result;
        }
        return node.children.size() < node.arity
                ? new Hole(node, childScope(node, scope, node.children.size())) : null;
    }
    private static List<Integer> childScope(Node node, List<Integer> scope, int index) {
        if (node.groups.isEmpty()) return scope;
        List<Integer> combined = new ArrayList<>(scope);
        for (int i = 0; i < Math.min(index, node.groups.size()); i++) combined.addAll(node.groups.get(i).slots);
        return combined;
    }
    private void add(Node node) {
        Hole hole = next();
        if (hole == null) throw new IllegalArgumentException("DRAFT_COMPLETE");
        if (hole.parent == null) root = node; else hole.parent.children.add(node);
    }

    private static boolean accepts(int expected, Type type) {
        return expected == ANY || expected == BOOL && type.is_bool
                || expected == REL && !type.is_bool || expected == INT && type.is_int();
    }

    private int holeSort(Hole hole) {
        if (hole.parent == null) return graph.expressions.get(site).type().is_bool ? BOOL : REL;
        Node parent = hole.parent;
        if (!parent.groups.isEmpty()) return parent.children.size() < parent.groups.size() ? REL : BOOL;
        String name = FeedbackSourceGraph.OPERATORS.get(parent.opcode);
        if (name.startsWith("U:")) {
            return switch (ExprUnary.Op.valueOf(name.substring(2))) {
                case NOT, AFTER, ALWAYS, EVENTUALLY, BEFORE, HISTORICALLY, ONCE -> BOOL;
                default -> REL;
            };
        }
        return switch (ExprBinary.Op.valueOf(name.substring(2))) {
            case AND, OR, IMPLIES, IFF, UNTIL, RELEASES, SINCE, TRIGGERED -> BOOL;
            case EQUALS, NOT_EQUALS -> ANY;
            case LT, LTE, GT, GTE -> INT;
            default -> REL;
        };
    }

    private static int resultSort(int opcode) {
        String name = FeedbackSourceGraph.OPERATORS.get(opcode);
        if (name.startsWith("Q:")) return BOOL;
        if (name.startsWith("U:")) return switch (ExprUnary.Op.valueOf(name.substring(2))) {
            case NOT, NO, SOME, LONE, ONE, AFTER, ALWAYS, EVENTUALLY, BEFORE, HISTORICALLY, ONCE -> BOOL;
            case CARDINALITY -> INT;
            default -> REL;
        };
        return switch (ExprBinary.Op.valueOf(name.substring(2))) {
            case AND, OR, IMPLIES, IFF, EQUALS, NOT_EQUALS, IN, NOT_IN, LT, LTE, GT, GTE,
                    UNTIL, RELEASES, SINCE, TRIGGERED -> BOOL;
            default -> REL;
        };
    }

    private boolean rootAllows(int opcode) {
        if (root != null) return true;
        String name = opcode < 0 ? "" : FeedbackSourceGraph.OPERATORS.get(opcode);
        if (family.name().startsWith("BINDING")) return name.startsWith("Q:");
        if (family.name().startsWith("TEMPORAL")) return Set.of("U:AFTER", "U:ALWAYS",
                "U:EVENTUALLY", "U:BEFORE", "U:HISTORICALLY", "U:ONCE", "U:PRIME",
                "B:UNTIL", "B:RELEASES", "B:SINCE", "B:TRIGGERED").contains(name);
        return true;
    }

    private static boolean compatible(int expected, int actual) {
        return expected == ANY || expected == actual || expected == REL && actual == INT;
    }

    JSONObject choices(List<Expr> returned) {
        JSONObject result = FeedbackActions.empty();
        Hole hole = next();
        FeedbackActions.add(result, "controls", FeedbackRepairSession.Control.ABORT_DRAFT.ordinal());
        FeedbackActions.add(result, "controls", FeedbackRepairSession.Control.ABSTAIN.ordinal());
        if (hole == null) {
            try { replacement(); FeedbackActions.add(result, "controls", FeedbackRepairSession.Control.COMMIT.ordinal()); }
            catch (IllegalArgumentException invalidRoot) { /* An invalid draft can still be aborted. */ }
            return result;
        }
        int sort = holeSort(hole), parent = hole.parent == null ? -1 : nodeIndex(hole.parent);
        JSONArray sourceRow = graph.json().getJSONArray("nodes").getJSONArray(site);
        FeedbackActions.add(result, "holes", parent, hole.parent == null ? 0 : hole.parent.children.size(),
                sort, hole.parent == null ? sourceRow.getInt(1) : -1,
                sourceRow.getInt(3), sourceRow.getInt(4), family.ordinal(), site);
        if (rootAllows(-1)) {
            for (int i = 0; i < graph.expressions.size(); i++) {
                if (graph.spans.get(i)[0] >= 0 && graph.canCopy(i, site) && accepts(sort, graph.expressions.get(i).type())) {
                    FeedbackActions.add(result, "copies", i);
                }
            }
            for (int i = 0; i < graph.symbols.size(); i++) {
                if (accepts(sort, graph.symbols.get(i).type())) FeedbackActions.add(result, "symbols", i);
            }
            for (int i = 0; i < returned.size(); i++) {
                if (accepts(sort, returned.get(i).type())) FeedbackActions.add(result, "feedbackSymbols", i);
            }
            for (int i : available.stream().sorted().toList()) {
                if (accepts(sort, graph.variables.get(i).type())) FeedbackActions.add(result, "variables", i);
            }
            // New draft domains are not parsed yet. Keep their pointers available in
            // relational/integer holes; commit supplies the exact dependent type check.
            if (sort != BOOL) for (int i : hole.scope) FeedbackActions.add(result, "draftVariables", i);
            for (int kind = 0; kind <= 5; kind++) {
                int actual = kind == 3 || kind == 4 ? BOOL : kind == 5 ? INT : REL;
                if (compatible(sort, actual)) FeedbackActions.add(result, "constants", kind, 0, kind == 5 ? 4 : 0);
            }
        }
        for (int code = 0; code < FeedbackSourceGraph.OPERATORS.size(); code++) {
            String name = FeedbackSourceGraph.OPERATORS.get(code);
            boolean op = name.startsWith("U:") && UNARY.contains(ExprUnary.Op.valueOf(name.substring(2)))
                    || name.startsWith("B:") && binarySupported(ExprBinary.Op.valueOf(name.substring(2)));
            boolean qt = Set.of("Q:ALL", "Q:SOME", "Q:NO", "Q:ONE", "Q:LONE").contains(name);
            if ((op || qt) && rootAllows(code) && compatible(sort, resultSort(code))) {
                FeedbackActions.add(result, qt ? "quantifiers" : "operators", code);
            }
        }
        String[] tables = {"copies", "symbols", "variables", "draftVariables", "constants", "operators", "quantifiers"};
        FeedbackRepairSession.Control[] controls = {FeedbackRepairSession.Control.COPY,
                FeedbackRepairSession.Control.SYMBOL, FeedbackRepairSession.Control.VARIABLE,
                FeedbackRepairSession.Control.DRAFT_VARIABLE, FeedbackRepairSession.Control.CONSTANT,
                FeedbackRepairSession.Control.OPERATOR, FeedbackRepairSession.Control.QUANTIFIER};
        for (int i = 0; i < tables.length; i++) if (!result.getJSONArray(tables[i]).isEmpty()) {
            FeedbackActions.add(result, "controls", controls[i].ordinal());
        }
        if (!result.getJSONArray("feedbackSymbols").isEmpty()) {
            FeedbackActions.add(result, "controls", FeedbackRepairSession.Control.FEEDBACK_SYMBOL.ordinal());
        }
        if (!result.getJSONArray("quantifiers").isEmpty()) {
            addDeclarationArguments(result);
        }
        if (hole.parent != null && !hole.parent.groups.isEmpty()
                && hole.parent.children.size() == hole.parent.groups.size()) {
            FeedbackActions.add(result, "controls", FeedbackRepairSession.Control.DECLARATION.ordinal());
            if (result.getJSONArray("counts").isEmpty()) addDeclarationArguments(result);
        }
        return result;
    }
    private static void addDeclarationArguments(JSONObject result) {
            FeedbackActions.add(result, "counts", 1, 16);
            FeedbackActions.add(result, "disjointness", 0, 1);
            FeedbackActions.add(result, "cardinalities", 0, 3);
    }

    private int nodeIndex(Node target) {
        List<Node> flattened = new ArrayList<>(); collect(root, flattened);
        return flattened.indexOf(target);
    }
    private static void collect(Node node, List<Node> into) {
        into.add(node); for (Node child : node.children) collect(child, into);
    }

    void copy(int source) {
        if (!graph.canCopy(source, site)) throw new IllegalArgumentException("COPY_SCOPE_MISMATCH");
        add(new Node(-1, 0, source, "(" + graph.source(source) + ")", List.of()));
    }
    void symbol(int pointer) {
        symbol(pointer, graph.symbols.get(pointer), false);
    }
    void feedbackSymbol(int pointer, Expr value) { symbol(pointer, value, true); }
    private void symbol(int pointer, Expr value, boolean feedback) {
        String rendered;
        if (value instanceof Sig sig) rendered = sig.builtin ? sig.label : local(sig.label);
        else if (value instanceof Sig.Field field) rendered = "(" + local(field.sig.label) + " <: " + field.label + ")";
        else throw new IllegalArgumentException("SYMBOL_KIND_UNSUPPORTED");
        add(new Node(feedback ? -11 : -2, 0, pointer, rendered, List.of()));
    }
    private static String local(String label) {
        if (!label.startsWith("this/")) throw new RepairTask.UnsupportedInput("IMPORTED_SYMBOL_UNSUPPORTED");
        return label.substring(5);
    }
    void variable(int pointer, boolean draft) {
        Hole hole = next();
        if (hole == null) throw new IllegalArgumentException("DRAFT_COMPLETE");
        if (draft ? !hole.scope.contains(pointer) : !available.contains(pointer)) {
            throw new IllegalArgumentException("VARIABLE_SCOPE_MISMATCH");
        }
        add(new Node(draft ? -4 : -3, 0, pointer,
                draft ? freshNames.get(pointer) : graph.variables.get(pointer).label, List.of()));
    }
    void constant(int kind, int value) {
        String rendered = switch (kind) {
            case 0 -> "none";
            case 1 -> "univ";
            case 2 -> "iden";
            case 3 -> "(no none)";
            case 4 -> "(some none)";
            case 5 -> {
                if (value < 0 || value > 4) throw new IllegalArgumentException("LITERAL_RANGE_UNSUPPORTED");
                yield Integer.toString(value);
            }
            default -> throw new IllegalArgumentException("CONSTANT_KIND_UNSUPPORTED");
        };
        add(new Node(-5 - kind, 0, value, rendered, List.of()));
    }
    void operator(int code) {
        String name = FeedbackSourceGraph.OPERATORS.get(code);
        if (name.startsWith("U:")) {
            var op = ExprUnary.Op.valueOf(name.substring(2));
            if (!UNARY.contains(op)) throw new RepairTask.UnsupportedInput("DRAFT_OPERATOR_UNSUPPORTED");
            add(new Node(code, 1, -1, op.toString(), List.of()));
        } else if (name.startsWith("B:")) {
            var op = ExprBinary.Op.valueOf(name.substring(2));
            if (!binarySupported(op)) throw new RepairTask.UnsupportedInput("DRAFT_OPERATOR_UNSUPPORTED");
            add(new Node(code, 2, -1, op.toString(), List.of()));
        } else throw new RepairTask.UnsupportedInput("DRAFT_OPERATOR_UNSUPPORTED");
    }
    void quantifier(int code, int count, boolean disjoint, int cardinality) {
        if (next() == null) throw new IllegalArgumentException("DRAFT_COMPLETE");
        String name = FeedbackSourceGraph.OPERATORS.get(code);
        if (!Set.of("Q:ALL", "Q:SOME", "Q:NO", "Q:ONE", "Q:LONE").contains(name)
                || count < 1 || count > 16) throw new IllegalArgumentException("QUANTIFIER_UNSUPPORTED");
        Group group = group(count, disjoint, cardinality);
        Node node = new Node(code, 2, cardinality, name.substring(2).toLowerCase(java.util.Locale.ROOT),
                new ArrayList<>(group.slots));
        node.disjoint = disjoint ? 1 : 0;
        node.groups.add(group);
        add(node);
    }

    void declaration(int count, boolean disjoint, int cardinality) {
        Hole hole = next();
        if (hole == null || hole.parent == null || hole.parent.groups.isEmpty()
                || hole.parent.children.size() != hole.parent.groups.size()) {
            throw new IllegalArgumentException("DECLARATION_REQUIRES_UNFILLED_BODY");
        }
        Group group = group(count, disjoint, cardinality);
        hole.parent.groups.add(group); hole.parent.introduced.addAll(group.slots); hole.parent.arity++;
    }

    private Group group(int count, boolean disjoint, int cardinality) {
        if (count < 1 || count > 16) throw new IllegalArgumentException("DECLARATION_COUNT_UNSUPPORTED");
        String card = switch (cardinality) {
            case 0 -> "one"; case 1 -> "set"; case 2 -> "lone"; case 3 -> "some";
            default -> throw new IllegalArgumentException("CARDINALITY_UNSUPPORTED");
        };
        List<Integer> declared = new ArrayList<>(); List<String> labels = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int id = freshNames.size();
            String label = "__repair" + id;
            // Names depend only on public source and earlier draft choices, not hidden declarations.
            while (graph.state.body().contains(label) || freshNames.contains(label)) label += "_";
            freshNames.add(label); declared.add(id); labels.add(label);
        }
        String prefix = (disjoint ? "disj " : "") + String.join(",", labels) + ": " + card;
        return new Group(declared, prefix, cardinality, disjoint ? 1 : 0);
    }

    String replacement() {
        if (root == null || next() != null) throw new IllegalArgumentException("DRAFT_INCOMPLETE");
        if (family.name().startsWith("BINDING") && root.introduced.isEmpty()) {
            throw new IllegalArgumentException("BINDING_ROOT_REQUIRED");
        }
        if (family.name().startsWith("TEMPORAL") && (root.opcode < 0
                || !Set.of("U:AFTER", "U:ALWAYS", "U:EVENTUALLY", "U:BEFORE", "U:HISTORICALLY", "U:ONCE",
                "U:PRIME", "B:UNTIL", "B:RELEASES", "B:SINCE", "B:TRIGGERED")
                .contains(FeedbackSourceGraph.OPERATORS.get(root.opcode)))) {
            throw new IllegalArgumentException("TEMPORAL_ROOT_REQUIRED");
        }
        return root.render();
    }

    JSONArray rows() {
        JSONArray rows = new JSONArray(); if (root != null) rows(root, -1, 0, rows); return rows;
    }
    JSONArray declarationRows() {
        JSONArray rows = new JSONArray();
        if (root == null) return rows;
        List<Node> nodes = new ArrayList<>(); collect(root, nodes);
        for (int i = 0; i < nodes.size(); i++) {
            List<Group> groups = nodes.get(i).groups;
            for (int j = 0; j < groups.size(); j++) {
                Group group = groups.get(j);
                for (int slot : group.slots) rows.put(FeedbackSourceGraph.row(i, j, slot, group.cardinality, group.disjoint));
            }
        }
        return rows;
    }
    private void rows(Node node, int parent, int position, JSONArray rows) {
        int id = rows.length();
        rows.put(FeedbackSourceGraph.row(node.opcode, node.payload, parent, position,
                node.arity, node.children.size(), node.introduced.size(), node.disjoint,
                node.introduced.isEmpty() ? -1 : node.introduced.get(0)));
        for (int i = 0; i < node.children.size(); i++) rows(node.children.get(i), id, i, rows);
    }
}

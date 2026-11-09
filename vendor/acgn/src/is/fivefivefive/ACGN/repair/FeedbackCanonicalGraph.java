package is.fivefivefive.ACGN.repair;

import java.util.*;
import edu.mit.csail.sdg.ast.*;
import org.json.*;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.metric.RepairView;
import is.fivefivefive.CanDis.theory.*;

/** Numeric read-only projection. Names resolve only against the permitted source namespace. */
final class FeedbackCanonicalGraph {
    static final List<String> OPERATORS = Arrays.stream(Opcode.values()).map(Enum::name).toList();
    private final FeedbackSourceGraph source;
    private final JSONObject packet;
    private final Map<GraphType, Integer> types = new HashMap<>();
    private final Map<RepairView.Node, Integer> nodes = new IdentityHashMap<>();
    private final List<Map<Integer, Integer>> bindingIds = new ArrayList<>();
    private final List<Map<Integer, Integer>> coordinateIds = new ArrayList<>();

    FeedbackCanonicalGraph(FeedbackSourceGraph source, JSONObject packet) {
        this.source = source; this.packet = packet;
        for (String table : List.of("canonicalNodes", "canonicalPorts", "canonicalTypes", "canonicalTypePorts",
                "canonicalBindings", "canonicalDependencies", "canonicalOrbits", "canonicalPhases", "canonicalPhasePorts")) {
            packet.put(table, new JSONArray());
        }
        if (OPERATORS.size() > 128) throw unsupported("OPERATOR_DICTIONARY");
        RepairView view = source.state.repairView();
        for (int phase = 0; phase < view.phases().size(); phase++) {
            bindingIds.add(new HashMap<>()); coordinateIds.add(new HashMap<>());
            int index = 0;
            for (var binding : view.phases().get(phase).bindings()) {
                if (!binding.prenexPathErasureCertified()) throw unsupported("LOCAL_BINDER_CONTEXT");
                var d = binding.declaration();
                var domain = d.observationDomain();
                if (domain == null || !domain.tag().equals("alloy-binder-domain")
                        || domain.scalars().size() != 1 || !domain.children().isEmpty()) {
                    throw unsupported("DOMAIN");
                }
                int id = table("canonicalBindings").length();
                bindingIds.get(phase).put(index++, id);
                if (binding.ownerPhase() == phase) coordinateIds.get(phase).put(binding.coordinate(), id);
                add("canonicalBindings", phase, binding.role().ordinal(), binding.ordinal(), binding.ownerPhase(),
                        binding.coordinate(), type(d.observationType()), QuantiVar.Quantifier.valueOf(d.quantifier()).ordinal(),
                        QuantiVar.Cardinality.valueOf(d.cardinality()).ordinal(), d.disjointnessClass(), d.exchangeClass(),
                        symbol(domain.scalars().get(0), true));
            }
        }
        for (int phase = 0; phase < view.phases().size(); phase++) {
            int index = 0;
            for (var binding : view.phases().get(phase).bindings()) {
                int id = bindingIds.get(phase).get(index++);
                var owner = coordinateIds.get(binding.ownerPhase());
                for (int coordinate : binding.declaration().dependencies()) {
                    if (!owner.containsKey(coordinate)) throw unsupported("DEPENDENCY");
                    add("canonicalDependencies", id, owner.get(coordinate));
                }
                for (int coordinate : binding.certifiedOrbit()) {
                    if (!owner.containsKey(coordinate)) throw unsupported("ORBIT");
                    add("canonicalOrbits", id, owner.get(coordinate));
                }
            }
            add("canonicalPhases", node(view.phases().get(phase).matrix(), phase));
        }
    }

    private RepairTask.UnsupportedInput unsupported(String reason) {
        return new RepairTask.UnsupportedInput("CANONICAL_OBSERVATION_UNSUPPORTED:" + reason);
    }
    private JSONArray table(String name) { return packet.getJSONArray(name); }
    private void add(String name, int... row) {
        table(name).put(FeedbackSourceGraph.row(row));
        int count = 0;
        for (String key : packet.keySet()) count += table(key).length();
        if (count > FeedbackSourceGraph.MAX_ROWS) throw unsupported("ROW_BUDGET");
    }
    private static String identity(String name) {
        return name.startsWith("this/") ? name.substring(5) : name;
    }
    private int symbol(String name, boolean signature) {
        int result = -1;
        for (int i = 0; i < source.symbols.size(); i++) {
            Expr e = source.symbols.get(i);
            String label = e instanceof Sig s ? s.label : e instanceof Sig.Field f ? f.label : "";
            if ((!signature || e instanceof Sig) && identity(label).equals(identity(name))) {
                if (result != -1) throw unsupported("AMBIGUOUS_SYMBOL");
                result = i;
            }
        }
        if (result == -1) throw unsupported("NON_SOURCE_SYMBOL:" + name);
        return result;
    }
    private int type(GraphType t) {
        if (t == null) throw unsupported("TYPE_MISSING");
        if (types.containsKey(t)) return types.get(t);
        int kind, payload = -1;
        List<GraphType> arguments = t.arguments();
        if (t.equals(GraphType.BOOL)) kind = 0;
        else if (t.equals(GraphType.INT)) kind = 1;
        else if (t.kind() == GraphType.Kind.RELATION) kind = 2;
        else if (t.kind() == GraphType.Kind.CONSTRUCTOR && t.symbol().equals("AlloyRelationUnion")) kind = 3;
        else if (AlloyTypeBridge.emptyRelationArity(t) != null) { kind = 4; payload = AlloyTypeBridge.emptyRelationArity(t); }
        else if (t.kind() == GraphType.Kind.CONSTRUCTOR && t.symbol().startsWith("AlloySig:") && arguments.isEmpty()) {
            kind = 5; payload = symbol(t.symbol().substring(9), true);
        } else if (t.kind() == GraphType.Kind.CONSTRUCTOR && t.symbol().equals("AlloyCarrier")
                && arguments.size() == 1 && arguments.get(0).arguments().isEmpty()) {
            kind = 6; payload = symbol(arguments.get(0).symbol(), true); arguments = List.of();
        } else throw unsupported("TYPE:" + t);
        int id = table("canonicalTypes").length(); types.put(t, id);
        add("canonicalTypes", kind, payload);
        for (int i = 0; i < arguments.size(); i++) add("canonicalTypePorts", id, type(arguments.get(i)), i);
        return id;
    }
    private int node(RepairView.Node n, int phase) {
        if (n == null) throw unsupported("EMPTY_MATRIX");
        if (nodes.containsKey(n)) return nodes.get(n);
        int payloadKind = 0, payload = -1;
        String op = n.operator();
        if (!n.observationPhases().isEmpty()) op = n.observationTemporalOperator();
        else if (n.isVariable()) {
            payloadKind = 2;
            Integer b = bindingIds.get(phase).get(n.bindingIndex());
            if (b == null) throw unsupported("LOCAL_VARIABLE");
            payload = b;
        } else if (op.equals("GLOBALBINDING")) { payloadKind = 1; payload = symbol(n.payload(), false); }
        else if (op.equals("CONSTANT")) {
            payloadKind = 3;
            payload = switch (n.payload()) {
                case "none" -> 0; case "univ" -> 1; case "iden" -> 2;
                case "true" -> 3; case "false" -> 4;
                default -> -1;
            };
            if (payload == -1) {
                try { payload = Integer.parseInt(n.payload()); payloadKind = 4; }
                catch (NumberFormatException e) { throw unsupported("CONSTANT"); }
            }
        } else if (n.payload() != null || Set.of("CALL", "SHADOW", "REF", "GENERICRELDECL", "DISJ", "VAR", "DISJVAR").contains(op)) {
            throw unsupported("NODE:" + op);
        }
        int opcode = OPERATORS.indexOf(op);
        if (opcode < 0) throw unsupported("OPERATOR");
        int id = table("canonicalNodes").length(); nodes.put(n, id);
        add("canonicalNodes", opcode, type(n.observationType()), n.containerKind().ordinal(), payloadKind, payload, phase);
        for (int i = 0; i < n.children().size(); i++) {
            add("canonicalPorts", id, node(n.children().get(i), phase), n.orderInsensitive() ? -1 : i, 0);
        }
        for (var alternative : n.certifiedAlternatives()) add("canonicalPorts", id, node(alternative, phase), -1, 1);
        for (int i = 0; i < n.observationPhases().size(); i++) add("canonicalPhasePorts", id, n.observationPhases().get(i), i);
        return id;
    }
}

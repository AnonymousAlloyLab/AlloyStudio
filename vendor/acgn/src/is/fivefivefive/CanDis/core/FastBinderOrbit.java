package is.fivefivefive.CanDis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import is.fivefivefive.CanDis.WorkBudget;
import is.fivefivefive.CanDis.metric.TraceResources;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.StructuralKey;

/**
 * Metric-only owner-local binder orbit for Fast Rewrite.
 *
 * <p>The certified projection retains every automorphism of a local binder
 * block. Fast metric search previously had no corresponding candidates. This
 * class derives the same owner-local orbit from the frozen normal form's
 * authenticated declaration frames (quantifier multiplicity, exact result type,
 * domain key, disjointness class and comprehension column) without building or
 * verifying a certificate. It returns, per local binder comparison node, the
 * primary member plus one metric copy per non-identity permutation of the
 * equal-payload declaration slots.
 */
final class FastBinderOrbit {
    private static final long MAX_ORBIT_ELEMENTS = 4096L;
    private static final int MAX_CLONE_BUDGET = 2_000_000;

    private FastBinderOrbit() {
    }

    static Map<EGraphNode, List<EGraphNode>> build(NormalForm source, EGraphNode root) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(root, "root");
        Map<String, QuantiVar> byAlpha = new LinkedHashMap<>();
        for (QuantiVar variable : source.getLocalQuantiVars()) {
            WorkBudget.charge(1);
            byAlpha.put(variable.getName(), variable);
        }
        var result = new IdentityHashMap<EGraphNode, List<EGraphNode>>();
        var budget = new int[]{MAX_CLONE_BUDGET};
        register(root, byAlpha, result, budget, true);
        // Admission checks remain valid after alternatives escape through Prepared.
        for (List<EGraphNode> alternatives : result.values()) {
            for (EGraphNode alternative : alternatives) {
                WorkBudget.charge(1);
                if (!alternative.isFrozenForCertification()) alternative.freezeForCertification();
            }
        }
        return result;
    }

    private static void register(
            EGraphNode node,
            Map<String, QuantiVar> byAlpha,
            IdentityHashMap<EGraphNode, List<EGraphNode>> result,
            int[] budget,
            boolean includeRoot) {
        WorkBudget.charge(1);
        if (--budget[0] < 0) {
            throw new TraceResources.Exhausted("FAST_ORBIT_BUDGET");
        }
        if (includeRoot && localBinder(node) && !containsTemporalReference(node)) {
            List<EGraphNode> alternatives = orbit(node, byAlpha, budget);
            if (!alternatives.isEmpty()) {
                if (result.size() >= MAX_ORBIT_ELEMENTS) {
                    throw new TraceResources.Exhausted("FAST_ORBIT_BUDGET");
                }
                result.put(node, alternatives);
                // A selected alternative is a fresh subtree. Its nested local
                // binders need their own registered orbit so a nested selected
                // representative remains replayable.
                for (int index = 1; index < alternatives.size(); index++) {
                    for (EGraphNode child : alternatives.get(index).getChildren()) {
                        register(child, byAlpha, result, budget, true);
                    }
                }
            }
        }
        for (EGraphNode child : node.getChildren()) {
            register(child, byAlpha, result, budget, true);
        }
    }

    private record Payload(
            String cardinality,
            int disjointnessClass,
            GraphType type,
            StructuralKey domain,
            int exchangeClass) { }

    private static List<EGraphNode> orbit(
            EGraphNode owner, Map<String, QuantiVar> byAlpha, int[] budget) {
        List<EGraphNode> slots = new ArrayList<>();
        for (EGraphNode child : owner.getChildren()) {
            WorkBudget.charge(1);
            if (!declaration(child)) {
                continue;
            }
            List<EGraphNode> parts = child.getChildren();
            WorkBudget.charge(parts.size());
            for (int index = 1; index < parts.size(); index++) {
                EGraphNode variable = parts.get(index);
                if (variable.getOpcode() != EGraphNode.Opcode.VARIABLE) {
                    return List.of();
                }
                slots.add(variable);
            }
        }
        if (slots.size() < 2) {
            return List.of();
        }
        int depth = localDepth(slots.get(0));
        if (depth < 0) {
            return List.of();
        }
        List<Payload> payloads = new ArrayList<>(slots.size());
        List<QuantiVar> bindings = new ArrayList<>(slots.size());
        for (int index = 0; index < slots.size(); index++) {
            EGraphNode variable = slots.get(index);
            EGraphNode sourceVariable = variable.comparisonVariableSource() == null
                    ? variable : variable.comparisonVariableSource();
            QuantiVar qv = byAlpha.get(sourceVariable.getAlphaName());
            if (qv == null || qv.getDeclarationFrames().isEmpty()
                    || qv.getExactAlloyType() == null) {
                return List.of();
            }
            NormalForm.DeclarationFrame frame = qv.getDeclarationFrames().get(0);
            bindings.add(qv);
            GraphType type;
            try {
                type = AlloyTypeBridge.graphType(qv.getExactAlloyType());
            } catch (RuntimeException unsupported) {
                return List.of();
            }
            int exchangeClass = owner.getOpcode() == EGraphNode.Opcode.COMPREHENSION ? index : 0;
            payloads.add(new Payload(qv.getCardinality().name(),
                    qv.getDisjointnessClass(), type, frame.domain(), exchangeClass));
        }
        Map<Payload, List<Integer>> classes = new LinkedHashMap<>();
        for (int index = 0; index < payloads.size(); index++) {
            classes.computeIfAbsent(payloads.get(index), ignored -> new ArrayList<>()).add(index);
        }
        List<List<Integer>> groups = new ArrayList<>();
        for (List<Integer> members : classes.values()) {
            if (members.size() >= 2) {
                groups.add(List.copyOf(members));
            }
        }
        if (groups.isEmpty()) {
            return List.of();
        }
        int[] identity = new int[slots.size()];
        for (int index = 0; index < identity.length; index++) {
            identity[index] = index;
        }
        List<int[]> permutations = enumerate(groups, identity);
        if (permutations.isEmpty()) {
            return List.of();
        }
        List<EGraphNode> result = new ArrayList<>(permutations.size() + 1);
        result.add(EGraphNode.comparisonPrimaryMember(owner));
        for (int[] permutation : permutations) {
            WorkBudget.charge(slots.size());
            if (!preservesDependencies(bindings, permutation, true)) continue;
            Map<String, String> renames = new LinkedHashMap<>();
            boolean valid = true;
            for (int index = 0; index < slots.size(); index++) {
                String oldName = slots.get(index).getAlphaName();
                if (oldName == null || !oldName.startsWith("@fast-local:")) {
                    valid = false;
                    break;
                }
                String rename = "@fast-local:" + depth + ":" + permutation[index];
                if (renames.put(oldName, rename) != null) {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                return List.of();
            }
            EGraphNode alternative = EGraphNode.comparisonRenameSubtree(owner, renames, budget);
            LocalBinderTransport.requireRenamed(owner, alternative, renames, EGraphNode.BINDER_TRANSPORT);
            result.add(alternative);
        }
        return List.copyOf(result);
    }

    // Fixed-frame automorphisms must still preserve dependency identities. Whole
    // binder transport above instead checks the renamed domains and body together.
    private static boolean preservesDependencies(List<QuantiVar> bindings, int[] permutation) {
        return preservesDependencies(bindings, permutation, false);
    }

    private static boolean preservesDependencies(List<QuantiVar> bindings, int[] permutation,
            boolean wholeBinderTransport) {
        var transport = new IdentityHashMap<QuantiVar, QuantiVar>();
        for (int i = 0; i < bindings.size(); i++) transport.put(bindings.get(i), bindings.get(permutation[i]));
        for (int i = 0; i < bindings.size(); i++) {
            var source = bindings.get(i).getDeclarationFrames().get(0);
            var target = bindings.get(permutation[i]).getDeclarationFrames().get(0);
            if (source.owner() != target.owner()) return false;
            // A fixed slot's domain is transported with its reads, not equated to
            // its old frame. Moved slots still obey the original orbit partition.
            if (wholeBinderTransport && permutation[i] == i) continue;
            if (source.dependencies().size() != target.dependencies().size()) return false;
            WorkBudget.charge(source.dependencies().size());
            for (int j = 0; j < source.dependencies().size(); j++) {
                var dependency = source.dependencies().get(j);
                if (transport.getOrDefault(dependency, dependency) != target.dependencies().get(j)) return false;
            }
        }
        return true;
    }

    private static List<int[]> enumerate(List<List<Integer>> groups, int[] identity) {
        long orbitSize = 1L;
        for (List<Integer> group : groups) {
            for (int factor = 2; factor <= group.size(); factor++) {
                WorkBudget.charge(1);
                if (orbitSize > MAX_ORBIT_ELEMENTS / factor) {
                    throw new TraceResources.Exhausted(
                            "FAST_ORBIT_BUDGET: orbit exceeds the admitted " + MAX_ORBIT_ELEMENTS);
                }
                orbitSize *= factor;
            }
        }
        List<int[]> bases = new ArrayList<>((int) orbitSize);
        WorkBudget.allocate(1L + identity.length);
        bases.add(identity.clone());
        for (List<Integer> group : groups) {
            List<int[]> orders = new ArrayList<>();
            int[] positions = new int[group.size()];
            for (int index = 0; index < positions.length; index++) {
                positions[index] = group.get(index);
            }
            permutePositions(0, positions, orders);
            long product = (long) bases.size() * orders.size();
            if (product > MAX_ORBIT_ELEMENTS) {
                throw new TraceResources.Exhausted(
                        "FAST_ORBIT_BUDGET: allocated orbit exceeds " + MAX_ORBIT_ELEMENTS);
            }
            List<int[]> next = new ArrayList<>((int) product);
            for (int[] base : bases) {
                for (int[] order : orders) {
                    WorkBudget.allocate(1L + base.length);
                    int[] combined = base.clone();
                    for (int index = 0; index < group.size(); index++) {
                        combined[group.get(index)] = order[index];
                    }
                    next.add(combined);
                }
            }
            bases = next;
        }
        List<int[]> result = new ArrayList<>();
        for (int[] permutation : bases) {
            WorkBudget.charge(permutation.length);
            boolean identityPermutation = true;
            for (int index = 0; index < permutation.length; index++) {
                if (permutation[index] != index) {
                    identityPermutation = false;
                    break;
                }
            }
            if (!identityPermutation) {
                result.add(permutation);
            }
        }
        return List.copyOf(result);
    }

    private static void permutePositions(
            int offset, int[] values, List<int[]> output) {
        WorkBudget.charge(1);
        if (offset == values.length) {
            WorkBudget.allocate(1L + values.length);
            int[] order = new int[values.length];
            for (int index = 0; index < values.length; index++) {
                order[index] = values[index];
            }
            output.add(order);
            return;
        }
        for (int index = offset; index < values.length; index++) {
            swap(values, offset, index);
            permutePositions(offset + 1, values, output);
            swap(values, offset, index);
        }
    }

    private static void swap(int[] values, int left, int right) {
        int value = values[left];
        values[left] = values[right];
        values[right] = value;
    }

    private static int localDepth(EGraphNode variable) {
        String name = variable.getAlphaName();
        if (name == null || !name.startsWith("@fast-local:")) {
            return -1;
        }
        int separator = name.indexOf(':', "@fast-local:".length());
        if (separator < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(name.substring("@fast-local:".length(), separator));
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }

    private static boolean localBinder(EGraphNode node) {
        switch (node.getOpcode()) {
            case COMPREHENSION:
            case SUM:
            case FORALL:
            case EXISTS:
            case NO:
            case ONE:
            case LONE:
                for (EGraphNode child : node.getChildren()) {
                    if (declaration(child)) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    private static boolean declaration(EGraphNode node) {
        EGraphNode.Opcode opcode = node.getOpcode();
        return opcode == EGraphNode.Opcode.GENERICRELDECL
                || opcode == EGraphNode.Opcode.DISJ
                || opcode == EGraphNode.Opcode.VAR
                || opcode == EGraphNode.Opcode.DISJVAR;
    }

    private static boolean containsTemporalReference(EGraphNode node) {
        var pending = new java.util.ArrayDeque<EGraphNode>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<EGraphNode, Boolean>());
        pending.add(node);
        while (!pending.isEmpty()) {
            EGraphNode current = pending.removeFirst();
            WorkBudget.charge(1);
            if (!seen.add(current)) {
                continue;
            }
            if (current.temporalReferenceAuthorityId() >= 0) {
                return true;
            }
            for (EGraphNode child : current.getChildren()) {
                pending.addLast(child);
            }
        }
        return false;
    }
}

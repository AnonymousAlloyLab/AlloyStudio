package is.fivefivefive.CanDis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;

/**
 * Authenticates a selected certified representative against the Fast owner-local
 * orbit by the actual source occurrences and typed slot action, never by a list
 * index. The certified alternative order and the Fast order need not agree.
 *
 * <p>A slot action carries the declaration owner occurrence, the variable source
 * occurrence, the exact slot type and the local coordinate. The certified and
 * Fast actions must have the same coordinate bijection over the same source
 * slots and owners. Duplicate projected representatives with the same action are
 * interchangeable; an action that names another owner, type, source or a
 * nonbijective/incomplete map is rejected.
 */
final class FastRepresentativeAuthentication {
    private static final Pattern CERTIFIED_LOCAL = Pattern.compile("@local\\[([0-9]+):([0-9]+)\\]");
    private static final Pattern FAST_LOCAL = Pattern.compile("@fast-local:([0-9]+):([0-9]+)");

    private FastRepresentativeAuthentication() {
    }

    record Slot(long ownerOccurrence, long sourceOccurrence, String exactType, String coordinate) { }

    record Action(List<Slot> slots, String canonical) { }

    static EGraphNode authenticate(
            EGraphNode fastPrimary, Node certifiedSelected,
            List<EGraphNode> fastAlternatives) {
        Objects.requireNonNull(fastPrimary, "fastPrimary");
        Objects.requireNonNull(fastAlternatives, "fastAlternatives");
        if (certifiedSelected == null || fastAlternatives.isEmpty()) return null;
        Action certified = certifiedAction(certifiedSelected);
        Action primary = fastAction(fastPrimary);
        if (certified == null || primary == null
                || certified.slots().size() != primary.slots().size()) {
            return null;
        }
        Set<String> certifiedOwners = identitySet(certified);
        Set<String> primaryOwners = identitySet(primary);
        if (!certifiedOwners.equals(primaryOwners)) return null;
        EGraphNode match = null;
        for (EGraphNode alternative : fastAlternatives) {
            Action action = fastAction(alternative);
            if (action == null) continue;
            if (action.canonical().equals(certified.canonical())) {
                if (match != null && !sameSlots(action, certified)) return null;
                if (match == null) match = alternative;
            }
        }
        return match;
    }

    /** Canonical typed-slot action signature for a certified binder node. */
    static String certifiedActionSignature(Node node) {
        Action action = certifiedAction(node);
        return action == null ? null : action.canonical();
    }

    /** Canonical typed-slot action signature for a Fast binder node. */
    static String fastActionSignature(EGraphNode node) {
        Action action = fastAction(node);
        return action == null ? null : action.canonical();
    }

    private static Action certifiedAction(Node binder) {
        List<Slot> slots = new ArrayList<>();
        Set<String> coordinates = new LinkedHashSet<>();
        Set<Long> sources = new LinkedHashSet<>();
        for (Node child : binder.children()) {
            if (!declaration(child.operator())) continue;
            List<Node> parts = child.children();
            long owner = child.originalSourceOccurrence() == null ? 0L
                    : child.originalSourceOccurrence().getSourceOccurrenceLineage();
            if (owner <= 0L) return null;
            for (int index = 1; index < parts.size(); index++) {
                Node variable = parts.get(index);
                if (!variable.isVariable() || variable.bindingIndex() >= 0) return null;
                EGraphNode source = variable.originalVariableSource() == null
                        ? variable.originalSourceOccurrence() : variable.originalVariableSource();
                if (source == null || source.getSourceOccurrenceLineage() <= 0L) return null;
                String coordinate = localCoordinate(CERTIFIED_LOCAL, variable.lexicalVariable());
                if (coordinate == null || !coordinates.add(coordinate)) return null;
                if (!sources.add(source.getSourceOccurrenceLineage())) return null;
                if (variable.observationType() == null) return null;
                slots.add(new Slot(owner, source.getSourceOccurrenceLineage(),
                        variable.observationType().toString(), coordinate));
            }
        }
        return action(slots);
    }

    private static Action fastAction(EGraphNode binder) {
        List<Slot> slots = new ArrayList<>();
        Set<String> coordinates = new LinkedHashSet<>();
        Set<Long> sources = new LinkedHashSet<>();
        for (EGraphNode child : binder.getChildren()) {
            if (!declaration(child.getOpcode().name())) continue;
            EGraphNode declarationSource = child.comparisonDeclarationSource();
            long owner = declarationSource == null
                    ? child.getSourceOccurrenceLineage()
                    : declarationSource.getSourceOccurrenceLineage();
            if (owner <= 0L) return null;
            List<EGraphNode> parts = child.getChildren();
            for (int index = 1; index < parts.size(); index++) {
                EGraphNode variable = parts.get(index);
                if (variable.getOpcode() != EGraphNode.Opcode.VARIABLE) return null;
                EGraphNode source = variable.comparisonVariableSource() == null
                        ? variable : variable.comparisonVariableSource();
                if (source.getSourceOccurrenceLineage() <= 0L) return null;
                String coordinate = localCoordinate(FAST_LOCAL, variable.getAlphaName());
                if (coordinate == null || !coordinates.add(coordinate)) return null;
                if (!sources.add(source.getSourceOccurrenceLineage())) return null;
                if (variable.getExactAlloyType() == null) return null;
                slots.add(new Slot(owner, source.getSourceOccurrenceLineage(),
                        AlloyTypeBridge.graphType(variable.getExactAlloyType()).toString(),
                        coordinate));
            }
        }
        return action(slots);
    }

    private static Action action(List<Slot> slots) {
        if (slots.isEmpty()) return null;
        List<Slot> ordered = new ArrayList<>(slots);
        ordered.sort(Comparator.comparingLong(Slot::ownerOccurrence)
                .thenComparingLong(Slot::sourceOccurrence)
                .thenComparing(Slot::exactType)
                .thenComparing(Slot::coordinate));
        StringBuilder canonical = new StringBuilder();
        for (Slot slot : ordered) {
            canonical.append(slot.ownerOccurrence()).append(':')
                    .append(slot.sourceOccurrence()).append(':')
                    .append(slot.exactType().length()).append(':')
                    .append(slot.exactType()).append(':')
                    .append(slot.coordinate()).append(';');
        }
        return new Action(List.copyOf(ordered), canonical.toString());
    }

    private static Set<String> identitySet(Action action) {
        Set<String> result = new LinkedHashSet<>();
        for (Slot slot : action.slots()) {
            result.add(slot.ownerOccurrence() + ":" + slot.sourceOccurrence()
                    + ":" + slot.exactType());
        }
        return result;
    }

    private static boolean sameSlots(Action left, Action right) {
        return left.canonical().equals(right.canonical());
    }

    private static String localCoordinate(Pattern pattern, String value) {
        if (value == null) return null;
        Matcher matcher = pattern.matcher(value);
        if (!matcher.matches()) return null;
        return matcher.group(1) + ":" + matcher.group(2);
    }

    private static boolean declaration(String operator) {
        return "GENERICRELDECL".equals(operator) || "DISJ".equals(operator)
                || "VAR".equals(operator) || "DISJVAR".equals(operator);
    }
}

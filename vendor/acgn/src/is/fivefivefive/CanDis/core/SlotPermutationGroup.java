package is.fivefivefive.CanDis.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** Exact finite permutation group over the slots exposed by one e-class. */
public final class SlotPermutationGroup {
    private List<String> slots = new ArrayList<>();
    private final List<Map<String, String>> generators = new ArrayList<>();
    private Set<List<String>> elements = identityElements(Collections.emptyList());

    public SlotPermutationGroup(Set<String> slots) {
        setSlots(slots);
    }

    public synchronized void setSlots(Set<String> exposedSlots) {
        List<String> newSlots = new ArrayList<>(exposedSlots);
        Collections.sort(newSlots);
        if (slots.equals(newSlots)) {
            return;
        }
        List<Map<String, String>> retained = new ArrayList<>();
        for (Map<String, String> generator : generators) {
            Map<String, String> restricted = restrict(generator, newSlots);
            if (restricted != null) {
                retained.add(restricted);
            }
        }
        slots = newSlots;
        generators.clear();
        generators.addAll(retained);
        rebuildClosure();
    }

    public synchronized void addGenerator(Map<String, String> permutation) {
        Map<String, String> normalized = normalize(permutation);
        if (!isBijection(normalized)) {
            throw new IllegalArgumentException("Slot symmetry must be a permutation of the exposed slots");
        }
        if (elements.contains(encode(normalized))) {
            return;
        }
        generators.add(normalized);
        rebuildClosure();
    }

    public synchronized void addSwap(String left, String right) {
        if (!slots.contains(left) || !slots.contains(right)) {
            return;
        }
        Map<String, String> swap = identity();
        swap.put(left, right);
        swap.put(right, left);
        addGenerator(swap);
    }

    public synchronized boolean contains(Map<String, String> permutation) {
        Map<String, String> normalized = normalize(permutation);
        return isBijection(normalized) && elements.contains(encode(normalized));
    }

    public synchronized boolean equivalentInvocations(
            Map<String, String> left,
            Map<String, String> right) {
        if (!left.keySet().equals(new LinkedHashSet<>(slots))
                || !right.keySet().equals(new LinkedHashSet<>(slots))) {
            return false;
        }
        Map<String, String> rightInverse = invert(right);
        Map<String, String> relative = new LinkedHashMap<>();
        for (String slot : slots) {
            String caller = left.get(slot);
            String rightSlot = rightInverse.get(caller);
            if (rightSlot == null) {
                return false;
            }
            relative.put(slot, rightSlot);
        }
        return contains(relative);
    }

    public synchronized void addInvocationEquivalence(
            Map<String, String> left,
            Map<String, String> right) {
        Map<String, String> rightInverse = invert(right);
        Map<String, String> relative = new LinkedHashMap<>();
        for (String slot : slots) {
            String rightSlot = rightInverse.get(left.get(slot));
            if (rightSlot == null) {
                throw new IllegalArgumentException("Invocations must have the same caller-slot image");
            }
            relative.put(slot, rightSlot);
        }
        addGenerator(relative);
    }

    public synchronized int size() {
        return elements.size();
    }

    private void rebuildClosure() {
        if (generators.isEmpty()) {
            elements = identityElements(slots);
            return;
        }
        elements = new LinkedHashSet<>();
        Map<String, String> identity = identity();
        Queue<Map<String, String>> pending = new ArrayDeque<>();
        elements.add(encode(identity));
        pending.add(identity);
        while (!pending.isEmpty()) {
            Map<String, String> current = pending.remove();
            for (Map<String, String> generator : generators) {
                Map<String, String> composed = compose(current, generator);
                if (elements.add(encode(composed))) {
                    pending.add(composed);
                }
            }
        }
    }

    private static Set<List<String>> identityElements(List<String> slots) {
        Set<List<String>> identity = new LinkedHashSet<>();
        identity.add(Collections.unmodifiableList(new ArrayList<>(slots)));
        return identity;
    }

    private Map<String, String> normalize(Map<String, String> permutation) {
        Map<String, String> normalized = identity();
        for (Map.Entry<String, String> entry : permutation.entrySet()) {
            if (normalized.containsKey(entry.getKey())) {
                normalized.put(entry.getKey(), entry.getValue());
            }
        }
        return normalized;
    }

    private Map<String, String> identity() {
        Map<String, String> identity = new LinkedHashMap<>();
        for (String slot : slots) {
            identity.put(slot, slot);
        }
        return identity;
    }

    private boolean isBijection(Map<String, String> permutation) {
        return permutation.keySet().equals(new LinkedHashSet<>(slots))
                && new LinkedHashSet<>(permutation.values()).equals(new LinkedHashSet<>(slots));
    }

    private List<String> encode(Map<String, String> permutation) {
        List<String> encoded = new ArrayList<>(slots.size());
        for (String slot : slots) {
            encoded.add(permutation.get(slot));
        }
        return encoded;
    }

    private static Map<String, String> compose(
            Map<String, String> first,
            Map<String, String> second) {
        Map<String, String> composed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : first.entrySet()) {
            composed.put(entry.getKey(), second.get(entry.getValue()));
        }
        return composed;
    }

    private static Map<String, String> invert(Map<String, String> permutation) {
        Map<String, String> inverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : permutation.entrySet()) {
            inverse.put(entry.getValue(), entry.getKey());
        }
        return inverse;
    }

    private static Map<String, String> restrict(Map<String, String> permutation, List<String> retainedSlots) {
        Set<String> retained = new LinkedHashSet<>(retainedSlots);
        Map<String, String> restricted = new LinkedHashMap<>();
        for (String slot : retainedSlots) {
            String image = permutation.get(slot);
            if (image == null || !retained.contains(image)) {
                return null;
            }
            restricted.put(slot, image);
        }
        return restricted;
    }
}

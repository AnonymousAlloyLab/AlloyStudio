package is.fivefivefive.CanDis.core.egraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Streams exact-type permutations within admissible scope-local binding blocks. */
final class SlotPermutationSearch {
    private static final long MAX_CANDIDATES = 40_320;
    private final List<Block> blocks = new ArrayList<>();
    private final boolean uncapped;
    private final Consumer<Map<String, String>> visitor;
    private long candidates;

    private record Block(String type, List<String> names) {}

    private SlotPermutationSearch(AlloyTerm term, boolean uncapped,
            Consumer<Map<String, String>> visitor) {
        this.uncapped = uncapped;
        this.visitor = visitor;
        collect(term);
    }

    static long forEach(AlloyTerm scopedTerm, boolean uncapped,
            Consumer<Map<String, String>> visitor) {
        SlotPermutationSearch search = new SlotPermutationSearch(scopedTerm, uncapped, visitor);
        search.visit(0, Collections.emptyMap());
        return search.candidates;
    }

    private void collect(AlloyTerm term) {
        if (SlotBindingBlocks.isExchangeBlock(term)) {
            Map<String, LinkedHashSet<String>> partitions = new TreeMap<>();
            for (int i = 0; i < term.children().size() - 1; i++) {
                addSlots(term.children().get(i), partitions);
            }
            addPartitions(partitions, true);
            for (AlloyTerm child : term.children()) collect(child);
            return;
        }
        for (AlloyTerm child : term.children()) {
            if (symmetricBinder(term.head()) && child.head().startsWith("DECL/")) {
                Map<String, LinkedHashSet<String>> partitions = new TreeMap<>();
                addSlots(child, partitions);
                addPartitions(partitions, false);
            }
            collect(child);
        }
    }

    private static void addSlots(AlloyTerm declaration, Map<String, LinkedHashSet<String>> partitions) {
        for (AlloyTerm slot : declaration.children()) {
            if (slot.isVariable()) partitions.computeIfAbsent(slot.slotType(), ignored -> new LinkedHashSet<>())
                    .add(slot.atom());
        }
    }

    private void addPartitions(Map<String, LinkedHashSet<String>> partitions, boolean includeSingletons) {
        for (var partition : partitions.entrySet()) {
            if (includeSingletons || partition.getValue().size() > 1) {
                blocks.add(new Block(partition.getKey(), List.copyOf(partition.getValue())));
            }
        }
    }

    private static boolean symmetricBinder(String head) {
        return switch (head) {
            case "QF/ALL", "QF/SOME", "QF/NO", "QF/ONE", "QF/LONE", "QF/NOTONE", "QF/NOTLONE" -> true;
            default -> false;
        };
    }

    private void visit(int index, Map<String, String> names) {
        if (!uncapped && candidates >= MAX_CANDIDATES) return;
        if (index == blocks.size()) {
            candidates++;
            visitor.accept(Collections.unmodifiableMap(names));
            return;
        }
        Block block = blocks.get(index);
        int[] permutation = new int[block.names().size()];
        for (int i = 0; i < permutation.length; i++) permutation[i] = i;
        do {
            Map<String, String> extended = new HashMap<>(names);
            for (int i = 0; i < permutation.length; i++) {
                extended.put(block.names().get(i), "@group:" + index + ':' + permutation[i]);
            }
            visit(index + 1, extended);
            if (!uncapped && candidates >= MAX_CANDIDATES) return;
        } while (nextPermutation(permutation));
    }

    private static boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) pivot--;
        if (pivot < 0) return false;
        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) successor--;
        int swap = values[pivot]; values[pivot] = values[successor]; values[successor] = swap;
        for (int left = pivot + 1, right = values.length - 1; left < right; left++, right--) {
            swap = values[left]; values[left] = values[right]; values[right] = swap;
        }
        return true;
    }
}

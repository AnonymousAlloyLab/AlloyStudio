package live;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded LFU witness retention, frequency means observed classification defects. */
final class BehaviorPool<T> {
    static final class Entry<T> {
        final String identity;
        final T value;
        private int frequency = 1;
        Entry(String identity, T value) { this.identity = identity; this.value = value; }
        int frequency() { return frequency; }
    }

    private final int capacity;
    private final Map<String, Entry<T>> entries = new LinkedHashMap<>();
    private long epoch;

    BehaviorPool(int capacity) {
        if (capacity < 1 || capacity > 100) throw new IllegalArgumentException();
        this.capacity = capacity;
    }

    void admit(String identity, T value) {
        if (identity == null || value == null) throw new IllegalArgumentException();
        if (entries.containsKey(identity)) return;
        if (entries.size() == capacity) {
            Entry<T> victim = null;
            for (Entry<T> entry : entries.values())
                if (victim == null || entry.frequency < victim.frequency) victim = entry;
            entries.remove(victim.identity);
        }
        entries.put(identity, new Entry<>(identity, value));
        changed();
    }

    void mismatch(Entry<T> entry) {
        if (entries.get(entry.identity) != entry) throw new IllegalArgumentException();
        if (entry.frequency != Integer.MAX_VALUE) {
            entry.frequency++;
            changed();
        }
    }

    private void changed() { if (epoch != Long.MAX_VALUE) epoch++; }
    List<Entry<T>> snapshot() { return new ArrayList<>(entries.values()); }
    int size() { return entries.size(); }
    long epoch() { return epoch; }
}

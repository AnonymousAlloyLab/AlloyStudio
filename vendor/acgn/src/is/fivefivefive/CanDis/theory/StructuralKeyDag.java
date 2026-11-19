package is.fivefivefive.CanDis.theory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Exact, collision-safe, run-owned structural-key DAG cache.
 *
 * <p>The cache is consulted by exact structural equality <em>before</em> a key's
 * children are expanded. A validated hit returns the previously materialized
 * row id and never recurses into the key's children; a miss materializes exactly
 * one fresh row, in postorder, after its children have been resolved. This is
 * the Java correspondence of {@code ReceiptStructuralSharing.Table} with
 * {@code CacheHit}, {@code miss_materializes_exact}, {@code reuse_exact},
 * {@code ordered_roots_preserved} and {@code history_exact_expansions}.
 *
 * <p>A hash code is only a bucket selector, never equality. Candidates in one
 * bucket are compared with full tag/scalar/child equality. Identity memoisation
 * is retained as a constant-time fast path. Equality work and row allocations
 * are counted separately from expansion work; a caller budget bounds each
 * independent guard (depth, visits, units, bytes, output). Traversal is
 * iterative, so the cache itself imposes no hidden recursion/stack limit.
 *
 * <p>Row encoding is ordered and repeated: {@code children} stores exactly one
 * id per edge in source order, so duplicated edges and roots are retained.
 * {@link #decode(JSONArray, int)} rejects missing, forward, self and cyclic
 * references and never treats a hash as a key.
 */
public final class StructuralKeyDag {
    public static final String BUDGET_REASON = "RECEIPT_BUDGET";

    /** Independent per-reference and per-materialization guards, owned by the caller. */
    public interface Budget {
        void reference(StructuralKey key, int depth);

        void materialize(StructuralKey key);
    }

    /** A failed independent guard. Never a replay result. */
    public static final class Overflow extends IllegalArgumentException {
        public Overflow(String detail) {
            super(BUDGET_REASON + ": " + detail);
        }
    }

    private static final class Entry {
        final StructuralKey key;
        final int id;
        final int height;

        Entry(StructuralKey key, int id, int height) {
            this.key = key;
            this.id = id;
            this.height = height;
        }
    }

    private static final class Frame {
        final StructuralKey key;
        final int depth;
        boolean entered;
        List<Entry> bucket;
        List<Integer> children;
        int nextChild;

        Frame(StructuralKey key, int depth) {
            this.key = key;
            this.depth = depth;
        }
    }

    private record Pair(StructuralKey left, StructuralKey right) { }

    private final Map<Integer, List<Entry>> buckets = new HashMap<>();
    private final IdentityHashMap<StructuralKey, Entry> identity = new IdentityHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private final JSONArray rows = new JSONArray();
    private final int maxDepth;
    private final long maxEqualityComparisons;
    private final Budget budget;
    private long references;
    private long hits;
    private long expansions;
    private final List<Integer> roots = new ArrayList<>();
    private long equalityComparisons;
    private int maxHeight;

    public StructuralKeyDag(int maxDepth, long maxEqualityComparisons, Budget budget) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be nonnegative");
        }
        if (maxEqualityComparisons < 0) {
            throw new IllegalArgumentException("maxEqualityComparisons must be nonnegative");
        }
        this.maxDepth = maxDepth;
        this.maxEqualityComparisons = maxEqualityComparisons;
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public int intern(StructuralKey root, int rootDepth) {
        Objects.requireNonNull(root, "key");
        if (rootDepth < 0) {
            throw new Overflow("negative depth");
        }
        var stack = new ArrayDeque<Frame>();
        stack.push(new Frame(root, rootDepth));
        while (true) {
            Frame frame = stack.peek();
            if (!frame.entered) {
                frame.entered = true;
                references++;
                budget.reference(frame.key, frame.depth);
                if (frame.depth > maxDepth) {
                    throw new Overflow("depth");
                }
                Entry known = identity.get(frame.key);
                if (known == null) {
                    List<Entry> bucket = buckets.get(frame.key.hashCode());
                    if (bucket != null) {
                        for (Entry candidate : bucket) {
                            countEqualityStep();
                            if (exactEquals(frame.key, candidate.key)) {
                                known = candidate;
                                break;
                            }
                        }
                    }
                }
                if (known != null) {
                    hits++;
                    identity.put(frame.key, known);
                    requireHeight(frame.depth, known.height);
                    int id = known.id;
                    stack.pop();
                    if (stack.isEmpty()) {
                        roots.add(id);
                        return id;
                    }
                    stack.peek().children.add(id);
                    continue;
                }
                frame.bucket = buckets.computeIfAbsent(
                        frame.key.hashCode(), ignored -> new ArrayList<>());
                frame.children = new ArrayList<>(frame.key.children().size());
                frame.nextChild = 0;
            }
            if (frame.nextChild < frame.key.children().size()) {
                StructuralKey child = frame.key.children().get(frame.nextChild++);
                stack.push(new Frame(child, frame.depth + 1));
                continue;
            }
            int height = 1;
            for (int childId : frame.children) {
                height = Math.max(height, entries.get(childId).height + 1);
            }
            requireHeight(frame.depth, height);
            budget.materialize(frame.key);
            int id = entries.size();
            Entry entry = new Entry(frame.key, id, height);
            entries.add(entry);
            frame.bucket.add(entry);
            identity.put(frame.key, entry);
            expansions++;
            maxHeight = Math.max(maxHeight, height);
            rows.put(new JSONObject().put("id", id).put("tag", frame.key.tag())
                    .put("scalars", frame.key.scalars()).put("children", frame.children));
            stack.pop();
            if (stack.isEmpty()) {
                roots.add(id);
                return id;
            }
            stack.peek().children.add(id);
        }
    }

    public JSONArray rows() {
        return rows;
    }

    public long references() {
        return references;
    }

    public long hits() {
        return hits;
    }

    public long expansions() {
        return expansions;
    }

    public long equalityComparisons() {
        return equalityComparisons;
    }

    public int size() {
        return entries.size();
    }

    public int maxHeight() {
        return maxHeight;
    }

    /** Exact root ids in reference order, including repeated roots. */
    public List<Integer> roots() {
        return List.copyOf(roots);
    }

    public JSONObject accounting() {
        return new JSONObject().put("references", references).put("hits", hits)
                .put("expansions", expansions).put("equalityComparisons", equalityComparisons)
                .put("rows", entries.size()).put("maxHeight", maxHeight)
                .put("roots", new JSONArray(roots));
    }

    private void requireHeight(int depth, int height) {
        if (depth > maxDepth || (long) height > (long) maxDepth - depth + 1L) {
            throw new Overflow("depth=" + depth + " height=" + height);
        }
    }

    private void countEqualityStep() {
        if (++equalityComparisons > maxEqualityComparisons) {
            throw new Overflow("equality work");
        }
    }

    /** Full structural equality; the bucket hash only narrows candidates. */
    private boolean exactEquals(StructuralKey left, StructuralKey right) {
        var pending = new ArrayDeque<Pair>();
        pending.push(new Pair(left, right));
        while (!pending.isEmpty()) {
            Pair pair = pending.pop();
            StructuralKey first = pair.left();
            StructuralKey second = pair.right();
            if (first == second) {
                continue;
            }
            countEqualityStep();
            if (!first.tag().equals(second.tag())
                    || !first.scalars().equals(second.scalars())) {
                return false;
            }
            List<StructuralKey> firstChildren = first.children();
            List<StructuralKey> secondChildren = second.children();
            if (firstChildren.size() != secondChildren.size()) {
                return false;
            }
            for (int index = firstChildren.size() - 1; index >= 0; index--) {
                pending.push(new Pair(firstChildren.get(index), secondChildren.get(index)));
            }
        }
        return true;
    }

    /**
     * Decodes row ids back to exact immutable keys. Rows are required to be a
     * postorder DAG: a child id is strictly less than its parent id. Missing,
     * forward, self and cyclic references are rejected, as are duplicate or
     * reordered ids and malformed scalar/child fields. Every row is validated;
     * no truncated-prefix success is possible.
     */
    public static StructuralKey decode(JSONArray rows, int id) {
        Objects.requireNonNull(rows, "rows");
        StructuralKey[] decoded = validateAndDecode(rows);
        if (id < 0 || id >= rows.length()) {
            throw new IllegalArgumentException("Missing structural-key row " + id);
        }
        return decoded[id];
    }

    /** Decodes several roots with one shared exact table, preserving cross-root sharing. */
    public static List<StructuralKey> decodeAll(JSONArray rows, List<Integer> ids) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(ids, "ids");
        StructuralKey[] decoded = validateAndDecode(rows);
        List<StructuralKey> result = new ArrayList<>(ids.size());
        for (int id : ids) {
            if (id < 0 || id >= rows.length()) {
                throw new IllegalArgumentException("Missing structural-key row " + id);
            }
            result.add(decoded[id]);
        }
        return result;
    }

    private static StructuralKey[] validateAndDecode(JSONArray rows) {
        StructuralKey[] decoded = new StructuralKey[rows.length()];
        for (int current = 0; current < rows.length(); current++) {
            if (!(rows.get(current) instanceof JSONObject row)) {
                throw new IllegalArgumentException("Structural-key row " + current + " is not an object");
            }
            if (!(row.opt("id") instanceof Integer declared) || declared != current) {
                throw new IllegalArgumentException("Structural-key row id mismatch at " + current);
            }
            if (!(row.opt("tag") instanceof String tag) || tag.isEmpty()) {
                throw new IllegalArgumentException("Structural-key row " + current + " lacks a tag");
            }
            if (!(row.opt("scalars") instanceof JSONArray scalarArray)) {
                throw new IllegalArgumentException("Structural-key row " + current + " lacks scalars");
            }
            List<String> scalars = new ArrayList<>(scalarArray.length());
            for (Object scalar : scalarArray) {
                if (!(scalar instanceof String text)) {
                    throw new IllegalArgumentException("Non-string structural scalar at row " + current);
                }
                scalars.add(text);
            }
            if (!(row.opt("children") instanceof JSONArray childArray)) {
                throw new IllegalArgumentException("Structural-key row " + current + " lacks children");
            }
            List<StructuralKey> children = new ArrayList<>(childArray.length());
            for (Object value : childArray) {
                if (!(value instanceof Integer childId)) {
                    throw new IllegalArgumentException("Non-integer structural child at row " + current);
                }
                if (childId < 0 || childId >= current) {
                    throw new IllegalArgumentException("Forward, self or cyclic structural reference "
                            + childId + " at row " + current);
                }
                children.add(decoded[childId]);
            }
            decoded[current] = StructuralKey.of(tag, scalars, children);
        }
        return decoded;
    }
}

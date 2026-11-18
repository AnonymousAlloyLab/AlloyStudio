package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import is.fivefivefive.CanDis.theory.StructuralKey;
import is.fivefivefive.CanDis.theory.StructuralKeyDag;

/**
 * Slot 12 controls for lossless structural sharing.
 *
 * <p>A validated exact-key hit must reuse one materialized row before recursive
 * expansion; a miss must allocate exactly one fresh row. The tests exercise both
 * directions of the retained natural event36 source with complete native replay
 * and action comparison, plus synthetic decode, repeated-edge, changed-payload,
 * hash-collision and depth/visit/unit/byte/equality overflows.
 */
public final class StructuralSharingTest {
    private static int checks;
    private static int negatives;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static void reject(Runnable action, String message) {
        checks++;
        negatives++;
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("Accepted " + message);
    }

    private static StructuralKey copy(StructuralKey key) {
        List<StructuralKey> children = new ArrayList<>(key.children().size());
        for (StructuralKey child : key.children()) {
            children.add(copy(child));
        }
        return StructuralKey.of(key.tag(), key.scalars(), children);
    }

    private static int size(StructuralKey key) {
        int result = 1;
        for (StructuralKey child : key.children()) {
            result += size(child);
        }
        return result;
    }

    private static StructuralKey chain(int depth) {
        StructuralKey key = StructuralKey.leaf("leaf", "a", "b");
        for (int level = 0; level < depth; level++) {
            key = StructuralKey.of("level" + level, List.of(), List.of(key));
        }
        return key;
    }

    private static StructuralKeyDag dag(int maxDepth, long equality, StructuralKeyDag.Budget budget) {
        return new StructuralKeyDag(maxDepth, equality, budget);
    }

    private static final StructuralKeyDag.Budget PERMISSIVE = new StructuralKeyDag.Budget() {
        @Override public void reference(StructuralKey key, int depth) { }
        @Override public void materialize(StructuralKey key) { }
    };

    private static void deduplicatesBeforeExpansion() {
        StructuralKey shape = chain(40);
        int nodes = size(shape);
        var cache = dag(4096, 8_000_000, PERMISSIVE);
        int copies = 5_000;
        List<Integer> ids = new ArrayList<>();
        for (int index = 0; index < copies; index++) {
            ids.add(cache.intern(index == 0 ? shape : copy(shape), 0));
        }
        long legacyIdentityVisits = (long) nodes * copies;
        check(legacyIdentityVisits > 120_000, "legacy identity traversal exceeds the visit budget");
        check(cache.expansions() == nodes, "one fresh row per distinct shape");
        check(cache.references() < 120_000, "shared references stay below the visit budget: " + cache.references());
        check(cache.hits() == copies - 1, "every duplicate root is an exact hit");
        check(ids.stream().distinct().count() == 1, "duplicate roots retain one exact id");
        check(cache.roots().size() == copies && cache.roots().stream().allMatch(id -> id.equals(ids.get(0))),
                "ordered root references, including repeats");
        check(StructuralKeyDag.decode(cache.rows(), ids.get(0)).stableString().equals(shape.stableString()),
                "decoded shared root equals the source key");
        check(cache.rows().length() == nodes, "row count equals the distinct-shape census");
    }

    private static void exactDecodeAndRepeatedEdges() {
        StructuralKey leaf = StructuralKey.leaf("typed-variable", "owner:0", "slot:0", "Person");
        StructuralKey pair = StructuralKey.of("pair", List.of(), List.of(leaf, leaf));
        StructuralKey other = StructuralKey.leaf("typed-variable", "owner:1", "slot:0", "Person");
        var cache = dag(4096, 8_000_000, PERMISSIVE);
        int leafId = cache.intern(leaf, 0);
        int pairId = cache.intern(pair, 0);
        int againId = cache.intern(copy(pair), 0);
        int otherId = cache.intern(other, 0);
        check(pairId == againId, "independent equal key reuses the exact row");
        check(otherId != leafId, "changed owner payload is not equal");
        JSONObject pairRow = cache.rows().getJSONObject(pairId);
        check(pairRow.getJSONArray("children").length() == 2
                && pairRow.getJSONArray("children").getInt(0) == leafId
                && pairRow.getJSONArray("children").getInt(1) == leafId,
                "repeated edge multiplicity is retained in order");
        check(cache.roots().equals(List.of(leafId, pairId, pairId, otherId)),
                "root order retains repeated roots and the changed payload root");
        var decoded = new ArrayList<StructuralKey>();
        for (int root : cache.roots()) {
            decoded.add(StructuralKeyDag.decode(cache.rows(), root));
        }
        var rebuilt = dag(4096, 8_000_000, PERMISSIVE);
        for (StructuralKey key : decoded) {
            rebuilt.intern(key, 0);
        }
        check(rebuilt.rows().similar(cache.rows()), "exact decode/re-encode preserves every row and id");
        check(decoded.get(1).children().size() == 2
                && decoded.get(1).children().get(0) == decoded.get(1).children().get(1),
                "decoded repeated edge preserves object identity");
        check(!decoded.get(0).equals(decoded.get(2)), "decoded changed payload remains distinct");
    }

    private static void rejectsMalformedRows() {
        var cache = dag(4096, 8_000_000, PERMISSIVE);
        int leafId = cache.intern(StructuralKey.leaf("leaf", "x"), 0);
        int rootId = cache.intern(StructuralKey.of("root", List.of(), List.of(
                StructuralKey.leaf("leaf", "x"))), 0);
        JSONArray rows = cache.rows();
        reject(() -> StructuralKeyDag.decode(rows, rows.length()), "missing row id");
        int root = rows.getJSONObject(rootId).getInt("id");
        for (int mode = 0; mode < 4; mode++) {
            JSONArray corrupt = new JSONArray(rows.toString());
            JSONObject row = corrupt.getJSONObject(rootId);
            JSONArray children = row.getJSONArray("children");
            switch (mode) {
                case 0 -> children.put(root); // self reference
                case 1 -> children.put(rootId + 1); // forward reference (also missing)
                case 2 -> children.put("not-an-id");
                default -> row.put("id", root + 7); // reordered/duplicate id
            }
            reject(() -> StructuralKeyDag.decode(corrupt, root), "malformed structural row " + mode);
        }
        JSONArray truncated = new JSONArray(rows.toString());
        truncated.remove(0);
        reject(() -> StructuralKeyDag.decode(truncated, rootId), "truncated prefix");
        check(leafId == 0, "postorder starts at the leaf");
    }

    private static void rejectsHashOnlyEquality() {
        StructuralKey first = StructuralKey.leaf("tag", "Aa");
        StructuralKey second = StructuralKey.leaf("tag", "BB");
        check(first.hashCode() == second.hashCode() && !first.equals(second),
                "forced hash collision with different exact keys");
        var cache = dag(4096, 8_000_000, PERMISSIVE);
        int firstId = cache.intern(first, 0);
        int secondId = cache.intern(second, 0);
        check(firstId != secondId, "hash collision does not establish equality");
        check(cache.intern(StructuralKey.leaf("tag", "Aa"), 0) == firstId, "exact first key remains addressable");
        check(cache.intern(StructuralKey.leaf("tag", "BB"), 0) == secondId, "exact second key remains addressable");
        check(!StructuralKeyDag.decode(cache.rows(), firstId)
                .stableString().equals(StructuralKeyDag.decode(cache.rows(), secondId).stableString()),
                "colliding keys decode to different exact values");
    }

    private static void enforcesIndependentGuards() {
        StructuralKey chain5 = chain(4);
        reject(() -> dag(3, 8_000_000, PERMISSIVE).intern(chain5, 0), "actual depth overflow");
        var visits = new StructuralKeyDag.Budget() {
            int references;
            @Override public void reference(StructuralKey key, int depth) {
                if (++references > 2) throw new StructuralKeyDag.Overflow("reference");
            }
            @Override public void materialize(StructuralKey key) { }
        };
        var visitCache = dag(4096, 8_000_000, visits);
        visitCache.intern(StructuralKey.leaf("a", "1"), 0);
        visitCache.intern(StructuralKey.leaf("a", "2"), 0);
        reject(() -> visitCache.intern(StructuralKey.leaf("a", "3"), 0), "actual reference/visit overflow");
        var units = new StructuralKeyDag.Budget() {
            int rows;
            @Override public void reference(StructuralKey key, int depth) { }
            @Override public void materialize(StructuralKey key) {
                if (++rows > 2) throw new StructuralKeyDag.Overflow("unit");
            }
        };
        var unitCache = dag(4096, 8_000_000, units);
        unitCache.intern(StructuralKey.leaf("a", "1"), 0);
        unitCache.intern(StructuralKey.leaf("b", "1"), 0);
        reject(() -> unitCache.intern(StructuralKey.leaf("c", "1"), 0), "actual unit overflow");
        var bytes = new StructuralKeyDag.Budget() {
            long total;
            @Override public void reference(StructuralKey key, int depth) { }
            @Override public void materialize(StructuralKey key) {
                total += key.tag().length();
                for (String scalar : key.scalars()) total += scalar.length();
                if (total > 10) throw new StructuralKeyDag.Overflow("byte");
            }
        };
        var byteCache = dag(4096, 8_000_000, bytes);
        byteCache.intern(StructuralKey.leaf("tag", "12345"), 0);
        reject(() -> byteCache.intern(StructuralKey.leaf("tag", "123456"), 0), "actual byte overflow");
        var equalityCache = dag(4096, 0, PERMISSIVE);
        equalityCache.intern(StructuralKey.leaf("leaf", "x"), 0);
        reject(() -> equalityCache.intern(StructuralKey.leaf("leaf", "x"), 0), "actual equality-work overflow");
    }

    private static void enforcesCompleteResultLimits() {
        var tooManyValues = new JSONArray();
        for (int index = 0; index <= 200_000; index++) tooManyValues.put(1);
        reject(() -> NativeOperationComparison.requireReceiptBudget(tooManyValues), "Result value limit");
        var withinValues = new JSONArray();
        for (int index = 0; index < 199_000; index++) withinValues.put(1);
        NativeOperationComparison.requireReceiptBudget(withinValues);
        checks++;
        StringBuilder text = new StringBuilder(6_000_001);
        for (int index = 0; index <= 6_000_000; index++) text.append('x');
        reject(() -> NativeOperationComparison.requireReceiptBudget(
                new JSONObject().put("text", text.toString())), "Result character limit");
    }

    private static void natural(Path identityFile, Path out) throws Exception {
        JSONObject identity = new JSONObject(Files.readString(identityFile));
        Files.createDirectories(out);
        long start = System.nanoTime();
        var pair = LocalOwnerTraceTest.prepare(identity);
        var prepared = new ArrayList<CanonicalAlloyPipeline.Prepared>();
        for (var endpoint : pair) prepared.add(CanonicalAlloyPipeline.prepare(endpoint));
        var perDirection = new JSONArray();
        for (int direction = 0; direction < 2; direction++) {
            var a = pair.get(direction);
            var b = pair.get(1 - direction);
            var fast = CanonicalRepairTraces.fast(a, b);
            var certified = CanonicalRepairTraces.certified(prepared.get(direction), prepared.get(1 - direction));
            var row = new JSONObject().put("identity", identity).put("direction", direction)
                    .put("fast", fast.json()).put("certified", certified.json());
            QueueProbe.orderedReplay(fast.distance(), certified.distance(),
                    () -> fast.replay(fast.json()), () -> certified.replay(certified.json()), row);
            long compareStart = System.nanoTime();
            var result = NativeOperationComparison.compare(a, b, fast, certified);
            long compareNanos = System.nanoTime() - compareStart;
            row.put("comparison", result.evidence());
            check(result.status() == NativeOperationComparison.Status.MATCHED,
                    "natural shared receipt acceptance " + result.status() + " " + result.reason());
            check(result.evidence().getBoolean("endpointCorrespondenceChecked")
                    && result.evidence().getBoolean("fixedChoiceReplaysChecked"),
                    "complete native replay and action comparison checked");
            var endpoints = result.evidence().getJSONArray("constructionEndpoints");
            check(endpoints.length() == 2, "both construction endpoints retained");
            for (int index = 0; index < endpoints.length(); index++) {
                JSONObject endpoint = endpoints.getJSONObject(index);
                JSONArray rows = endpoint.getJSONArray("structuralKeys");
                JSONObject work = endpoint.getJSONObject("structuralKeyWork");
                JSONArray roots = work.getJSONArray("roots");
                check(work.getLong("expansions") == rows.length(), "one row per exact miss");
                check(work.getLong("references") <= 120_000, "reference budget");
                check(work.getLong("equalityComparisons") <= 8_000_000, "equality-work budget");
                check(work.getInt("maxHeight") <= 4096, "measured key depth within its guard");
                check(roots.length() > 0, "retained root order");
                var rootIds = new ArrayList<Integer>();
                for (int rootIndex = 0; rootIndex < roots.length(); rootIndex++) {
                    rootIds.add(roots.getInt(rootIndex));
                }
                var decoded = StructuralKeyDag.decodeAll(rows, rootIds);
                var rebuilt = dag(4096, 8_000_000, PERMISSIVE);
                for (StructuralKey key : decoded) rebuilt.intern(key, 0);
                check(rebuilt.rows().similar(rows), "natural rows decode exactly and re-encode identically");
                check(rebuilt.roots().size() == roots.length(), "retained roots are complete");
                for (int rootIndex = 0; rootIndex < roots.length(); rootIndex++) {
                    check(rebuilt.roots().get(rootIndex) == roots.getInt(rootIndex),
                            "retained root order is exact");
                }
            }
            perDirection.put(row);
        }
        long elapsed = System.nanoTime() - start;
        JSONObject report = new JSONObject().put("identity", identity).put("status", "PASS")
                .put("checks", checks).put("negativeControls", negatives)
                .put("directions", 2).put("elapsedNanos", elapsed)
                .put("publicCertification", "BLOCKED")
                .put("scope", "exact-key cache hit before expansion; complete native replay and action comparison")
                .put("perDirection", perDirection);
        Files.writeString(out.resolve("result.json"), report.toString(2));
    }

    public static void main(String[] args) throws Exception {
        deduplicatesBeforeExpansion();
        exactDecodeAndRepeatedEdges();
        rejectsMalformedRows();
        rejectsHashOnlyEquality();
        enforcesIndependentGuards();
        enforcesCompleteResultLimits();
        if (args.length >= 2) {
            natural(Path.of(args[0]), Path.of(args[1]));
        }
        System.out.println("StructuralSharingTest: " + checks + " checks passed, "
                + negatives + " negative controls" + (args.length >= 2 ? ", natural directions=2" : ""));
    }
}

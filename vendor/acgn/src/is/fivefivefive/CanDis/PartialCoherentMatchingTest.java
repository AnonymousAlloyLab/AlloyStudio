package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.metric.DeclarationReplay;

/** Unread declaration locks remain in the ledger, not in the free read search. */
public final class PartialCoherentMatchingTest {
    private static int checks, negatives;
    private static void check(boolean ok, String reason) {
        checks++; if (!ok) throw new AssertionError(reason);
    }
    private static Object get(Object value, String name) throws Exception {
        var field = value.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.get(value);
    }
    private static void set(Object value, String name, Object content) throws Exception {
        var field = value.getClass().getDeclaredField(name); field.setAccessible(true);
        field.set(value, content);
    }
    private static Object call(String name, Object... args) throws Exception {
        var method = Arrays.stream(CanonicalDistance.class.getDeclaredMethods())
            .filter(m -> m.getName().equals(name) && m.getParameterCount() == args.length)
            .findFirst().orElseThrow();
        method.setAccessible(true); return method.invoke(null, args);
    }
    private static void reject(Runnable action, String reason) {
        checks++; negatives++;
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Accepted tampering: " + reason);
    }
    private static int maximum(Object a, Object b, List<String> left, List<String> right) throws Exception {
        return (Integer) call("maximumCoherentMatches", left, right, a, b);
    }
    @SuppressWarnings("unchecked")
    private static void partialTables(Canonical.Prepared source, Canonical.Prepared target) throws Exception {
        var a = get(source, "delegate"); var b = get(target, "delegate");
        var left = call("coherentVariableSpace", get(a, "normalForms"));
        var right = call("coherentVariableSpace", get(b, "normalForms"));
        var leftEntries = (Map<String,Object>) get(left, "variables");
        var rightEntries = (Map<String,Object>) get(right, "variables");
        check(!leftEntries.containsKey("_q0") && leftEntries.containsKey("_q1"), "actual unread source slot");
        check(rightEntries.keySet().containsAll(List.of("_q0", "_q1")), "actual live target slots");
        check(maximum(left, right, List.of("_q0"), List.of("_q0")) == 0, "missing source has no edge");
        check(maximum(right, left, List.of("_q0"), List.of("_q0")) == 0, "missing target has no edge");
        check(maximum(left, left, List.of("_q0"), List.of("_q0")) == 0, "both absent have no edge");
        check(maximum(left, left, List.of("_q1"), List.of("_q1")) == 1, "compatible live pair");
        check(maximum(left, right, List.of("_q1"), List.of("_q0")) == 0, "incompatible live type");
        check(maximum(left, right, List.of("_q1"), List.of("_q1")) == 0, "quantifier update is not a free edge");
        var visited = new HashSet<String>(); var matched = new HashMap<String,String>();
        check(!(Boolean) call("augmentCoherentMatching", "_q0", List.of("_q0", "_q1"),
            left, right, matched, visited), "absent source cannot augment");
        check(matched.isEmpty() && visited.isEmpty(), "absent source consumes no target");
        for (int mask = 0; mask < 8; mask++) {
            var names = new ArrayList<String>(); names.add("_q1");
            for (int i = 0; i < 3; i++) if ((mask & (1 << i)) != 0) names.add("unread" + i);
            check(maximum(left, left, names, names) == 1, "multiple unread locks preserve live matching");
        }
        var variable = leftEntries.get("_q1");
        set(variable, "ambiguous", true);
        try { negatives++; check(maximum(left, left, List.of("_q1"), List.of("_q1")) == 0,
                "ambiguous live descriptor has no free link"); }
        finally { set(variable, "ambiguous", false); }
        var duplicate = call("coherentVariableSpace", get(a, "normalForms"));
        var foreign = ((Map<String,Object>) get(duplicate, "variables")).get("_q1");
        set(foreign, "ownerPhase", (Integer) get(foreign, "ownerPhase") + 1);
        negatives++; check(maximum(left, duplicate, List.of("_q1"), List.of("_q1")) == 0,
            "foreign owner cannot become a free read link");
    }
    @SuppressWarnings("unchecked")
    private static void retained(Canonical.Prepared source, Canonical.Prepared target, int slots,
            boolean identitySelection) throws Exception {
        var captured = call("captureDecisions", get(source, "delegate"), get(target, "delegate"));
        var alignment = get(get(captured, "decisions"), "alignment");
        var mapping = (Map<String,String>) get(alignment, "mapping");
        var paid = (Map<String,String>) get(alignment, "paidCorrespondence");
        var leftLocks = (Set<String>) get(alignment, "lockedLeftNames");
        var rightLocks = (Set<String>) get(alignment, "lockedRightNames");
        var path = (List<DeclarationReplay.Move>) get(alignment, "declarationPath");
        check(mapping.size() == slots && leftLocks.size() == slots && rightLocks.size() == slots,
            "all declared identities and locks retained");
        check(path.size() == slots, "entire selected declaration path retained");
        for (int i = 0; i < slots; i++) {
            String name = "_q" + i;
            check(leftLocks.contains(name) && rightLocks.contains(name), "both declaration locks");
            check(path.get(i).left() >= 0 && path.get(i).right() >= 0, "selected declaration remains paired");
            if (identitySelection) {
                check(name.equals(mapping.get(name)), "actual selected identity");
                check(path.get(i).left() == i && path.get(i).right() == i, "original selected coordinates");
            }
        }
        check(paid.size() == 1, "only the actual head update is paid");
    }
    public static void main(String[] args) throws Exception {
        var manifest = new JSONObject(Files.readString(Path.of(args[0])));
        var out = Path.of(args[1]); Files.createDirectory(out);
        var rows = new JSONArray();
        for (Object value : manifest.getJSONArray("cases")) {
            var identity = (JSONObject) value;
            long start = System.nanoTime();
            var pair = LocalOwnerTraceTest.prepare(identity);
            if (identity.getBoolean("censusControl")) partialTables(pair.get(0), pair.get(1));
            if (identity.optBoolean("multipleUnread")) {
                var space = call("coherentVariableSpace", get(get(pair.get(0), "delegate"), "normalForms"));
                var descriptors = (Map<?,?>) get(space, "variables");
                check(identity.getInt("slots") - descriptors.size() >= 2, "multiple retained unread slots exercised");
            }
            for (int direction = 0; direction < 2; direction++) {
                var a = pair.get(direction); var b = pair.get(1-direction);
                var fast = CanonicalRepairTraces.fast(a, b);
                var certified = CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a), CanonicalAlloyPipeline.prepare(b));
                var order = new JSONObject();
                QueueProbe.orderedReplay(fast.distance(), certified.distance(),
                    () -> fast.replay(fast.json()), () -> certified.replay(certified.json()), order);
                var comparison = NativeOperationComparison.compare(a, b, fast, certified);
                var row = new JSONObject().put("identity", identity).put("direction", direction)
                    .put("fast", fast.json()).put("certified", certified.json()).put("replayOrder", order)
                    .put("comparison", comparison.evidence());
                Files.writeString(out.resolve(identity.getString("id") + "-" + direction + ".json"),
                    row.toString(2), StandardOpenOption.CREATE_NEW);
                check(comparison.status() == NativeOperationComparison.Status.MATCHED,
                    "actual action correspondence: " + comparison.reason());
                check(Canonical.distance(a, b) == fast.distance(), "numeric and captured selection agree");
                if (identity.has("expectedDistance"))
                    check(fast.distance() == identity.getInt("expectedDistance"), "retained incident distance");
                retained(a, b, identity.getInt("slots"), identity.getBoolean("censusControl"));
                var cost = fast.json(); cost.put("distance", fast.distance() + 1);
                reject(() -> fast.replay(cost), "distance");
                var paid = fast.json(); var links = paid.getJSONObject("selectedPaidDeclarationCorrespondence");
                String key = links.keySet().iterator().next(); links.put(key, "foreign-owner");
                reject(() -> fast.replay(paid), "paid ownership");
                var declaration = fast.json(); boolean changed = false;
                for (Object raw : declaration.getJSONArray("nativeDeclarationPrograms")) {
                    var operations = ((JSONObject) raw).getJSONArray("operations");
                    if (operations.isEmpty()) continue;
                    operations.getJSONObject(0).getJSONObject("source").getJSONArray("tuple").put(1, "FORGED_TYPE");
                    changed = true; break;
                }
                check(changed, "actual selected declaration program available");
                reject(() -> fast.replay(declaration), "original declaration type");
                rows.put(new JSONObject().put("id", identity.getString("id")).put("direction", direction)
                    .put("status", comparison.status()).put("distance", fast.distance()));
            }
            check((System.nanoTime() - start) < 60_000_000_000L, "file stays below sixty seconds");
        }
        Files.writeString(out.resolve("summary.json"), new JSONObject().put("checks", checks)
            .put("negativeControls", negatives).put("directions", rows).toString(2), StandardOpenOption.CREATE_NEW);
        System.out.println("PartialCoherentMatchingTest: " + checks + " checks, " + negatives + " negative controls");
    }
}

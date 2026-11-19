package is.fivefivefive.CanDis;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.metric.RepairView;
import is.fivefivefive.CanDis.metric.RepairView.ContainerKind;
import is.fivefivefive.CanDis.metric.RepairView.Node;

/**
 * Slot 9 controls for authenticated repeated occurrences.
 *
 * <p>The replacement gate must choose index identity at ordered parents, keep
 * the old refusal when no unused structural candidate exists, and otherwise
 * refine by exact source-occurrence provenance before choosing the lowest
 * admitted twin. It never relabels a non-forced choice as a demonstrated
 * mismatch. The tests exercise the natural event123 inv14/inv14c pair in both
 * directions with complete native replay, the prior-bridged F5 directions when
 * supplied, and synthetic provenance/twin/used-set negatives.
 */
public final class AuthenticatedOccurrenceTest {
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

    private static final Constructor<Node> NODE;
    private static final Field LINEAGE;

    static {
        try {
            NODE = RepairView.Node.class.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, int.class,
                    ContainerKind.class, boolean.class, List.class, List.class,
                    is.fivefivefive.CanDis.theory.GraphType.class, List.class, String.class,
                    EGraphNode.class, is.fivefivefive.CanDis.core.NormalForm.TemporalReferenceOrigin.class);
            NODE.setAccessible(true);
            LINEAGE = EGraphNode.class.getDeclaredField("sourceOccurrenceLineage");
            LINEAGE.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static EGraphNode source(String opcode, long lineage) {
        try {
            var op = EGraphNode.Opcode.valueOf(opcode);
            var node = new EGraphNode(900_000 + (int) lineage,
                    op, List.of(), false, 0, false, EGraphNode.Metatype.BOOLEAN);
            LINEAGE.setLong(node, lineage);
            return node;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Node node(String operator, String payload, EGraphNode occurrence) {
        try {
            return NODE.newInstance(operator, payload, payload,
                    "VARIABLE".equals(operator) ? "@local[0:0]" : null, -1,
                    ContainerKind.SEQUENCE, false, List.of(), List.of(),
                    null, List.of(), null, occurrence, null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static List<String> keys(int count) {
        var result = new ArrayList<String>(count);
        for (int index = 0; index < count; index++) result.add("same");
        return result;
    }

    private static void syntheticGateControls() {
        var ten = node("VARIABLE", "x", source("VARIABLE", 10));
        var eleven = node("VARIABLE", "x", source("VARIABLE", 11));
        var tenAgain = node("VARIABLE", "x", source("VARIABLE", 10));
        var different = node("VARIABLE", "changed", source("VARIABLE", 10));
        var provenance = new ArrayList<RepeatedOccurrenceGate.Provenance>();
        provenance.add(RepeatedOccurrenceGate.certifiedProvenance(ten));
        provenance.add(RepeatedOccurrenceGate.certifiedProvenance(eleven));

        var found = RepeatedOccurrenceGate.pick(List.of(ten, eleven), keys(2), provenance,
                "same", RepeatedOccurrenceGate.fastProvenance(source("VARIABLE", 10)), Set.of());
        check(found.kind() == RepeatedOccurrenceGate.Kind.FOUND && found.index() == 0,
                "lowest provenance-equal candidate is chosen");
        check(found.admitted().equals(List.of(0)) && !found.nonForced(),
                "a provenance-singleton class is forced, not non-forced");

        var second = RepeatedOccurrenceGate.pick(List.of(ten, eleven), keys(2), provenance,
                "same", RepeatedOccurrenceGate.fastProvenance(source("VARIABLE", 11)), Set.of());
        check(second.kind() == RepeatedOccurrenceGate.Kind.FOUND && second.index() == 1,
                "the second provenance token selects the second sibling");

        var foreign = RepeatedOccurrenceGate.pick(List.of(ten, eleven), keys(2), provenance,
                "same", RepeatedOccurrenceGate.fastProvenance(source("VARIABLE", 12)), Set.of());
        check(foreign.kind() == RepeatedOccurrenceGate.Kind.AMBIGUOUS,
                "foreign lineage does not select an equal-key sibling");

        var twinProvenance = new ArrayList<RepeatedOccurrenceGate.Provenance>();
        twinProvenance.add(RepeatedOccurrenceGate.certifiedProvenance(ten));
        twinProvenance.add(RepeatedOccurrenceGate.certifiedProvenance(tenAgain));
        var twins = RepeatedOccurrenceGate.pick(List.of(ten, tenAgain), keys(2), twinProvenance,
                "same", RepeatedOccurrenceGate.fastProvenance(source("VARIABLE", 10)), Set.of());
        check(twins.kind() == RepeatedOccurrenceGate.Kind.FOUND && twins.index() == 0
                        && twins.admitted().equals(List.of(0, 1)) && twins.nonForced(),
                "equal-lineage twins form a recorded non-forced class");

        var nonTwinProvenance = new ArrayList<RepeatedOccurrenceGate.Provenance>();
        nonTwinProvenance.add(RepeatedOccurrenceGate.certifiedProvenance(ten));
        nonTwinProvenance.add(RepeatedOccurrenceGate.certifiedProvenance(different));
        var nonTwins = RepeatedOccurrenceGate.pick(List.of(ten, different), keys(2), nonTwinProvenance,
                "same", RepeatedOccurrenceGate.fastProvenance(source("VARIABLE", 10)), Set.of());
        check(nonTwins.kind() == RepeatedOccurrenceGate.Kind.AMBIGUOUS,
                "equal-key equal-provenance non-twins stay ambiguous");

        var exhausted = RepeatedOccurrenceGate.pick(List.of(ten, tenAgain), keys(2), twinProvenance,
                "same", RepeatedOccurrenceGate.fastProvenance(source("VARIABLE", 10)), Set.of(0, 1));
        check(exhausted.kind() == RepeatedOccurrenceGate.Kind.UNSUPPORTED,
                "an exhausted class keeps the old unsupported refusal");

        var partial = RepeatedOccurrenceGate.pick(List.of(ten, tenAgain), keys(2), twinProvenance,
                "same", RepeatedOccurrenceGate.fastProvenance(source("VARIABLE", 10)), Set.of(0));
        check(partial.kind() == RepeatedOccurrenceGate.Kind.FOUND && partial.index() == 1
                        && partial.admitted().equals(List.of(0, 1)),
                "a used twin is retained in the admitted class but not chosen twice");

        var absentA = node("AND", "and", null);
        var absentB = node("AND", "and", null);
        var absentKeys = new ArrayList<RepeatedOccurrenceGate.Provenance>();
        absentKeys.add(RepeatedOccurrenceGate.certifiedProvenance(absentA));
        absentKeys.add(RepeatedOccurrenceGate.certifiedProvenance(absentB));
        var absent = RepeatedOccurrenceGate.pick(List.of(absentA, absentB), keys(2), absentKeys,
                "same", RepeatedOccurrenceGate.fastProvenance(source("AND", 0)), Set.of());
        check(absent.kind() == RepeatedOccurrenceGate.Kind.FOUND && absent.nonForced(),
                "absence matches only absence and admits equal twins");
        var spurious = RepeatedOccurrenceGate.pick(List.of(absentA, absentB), keys(2), absentKeys,
                "same", RepeatedOccurrenceGate.fastProvenance(source("VARIABLE", 10)), Set.of());
        check(spurious.kind() == RepeatedOccurrenceGate.Kind.AMBIGUOUS,
                "a present lineage never matches an absent provenance token");

        check(RepeatedOccurrenceGate.twin(ten, tenAgain), "equal source lineage is a twin");
        check(!RepeatedOccurrenceGate.twin(ten, different), "changed payload breaks the twin relation");
        check(!RepeatedOccurrenceGate.twin(ten, eleven), "changed lineage breaks the twin relation");

        var record = RepeatedOccurrenceGate.classRecord("source", "$/0", "$/0",
                "same", RepeatedOccurrenceGate.certifiedProvenance(ten), List.of(1, 2), 2);
        check(RepeatedOccurrenceGate.blocked(new JSONArray().put(record), "source", "$/0/1"),
                "an action at a non-forced member is blocked");
        check(RepeatedOccurrenceGate.blocked(new JSONArray().put(record), "source", "$/0/2/3"),
                "an action below a non-forced member is blocked");
        check(!RepeatedOccurrenceGate.blocked(new JSONArray().put(record), "source", "$/0/3"),
                "an action outside the class is not blocked");
        check(!RepeatedOccurrenceGate.blocked(new JSONArray().put(record), "target", "$/0/1"),
                "the other endpoint's classes do not block");
    }

    private static void priorPaths(Path input) throws Exception {
        JSONArray rows = new JSONArray(Files.readString(input));
        var cache = new java.util.HashMap<String, List<Canonical.Prepared>>();
        int matched = 0;
        for (Object value : rows) {
            JSONObject row = (JSONObject) value;
            JSONObject identity = row.getJSONObject("identity");
            int direction = row.getInt("direction");
            String cacheKey = identity.getString("source") + identity.getString("left") + identity.getString("right");
            if (!cache.containsKey(cacheKey)) cache.put(cacheKey, LocalOwnerTraceTest.prepare(identity));
            var pair = cache.get(cacheKey);
            var a = pair.get(direction);
            var b = pair.get(1 - direction);
            var fast = CanonicalRepairTraces.fast(a, b);
            var certified = CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a), CanonicalAlloyPipeline.prepare(b));
            var result = NativeOperationComparison.compare(a, b, fast, certified);
            check(result.status().name().equals(row.getString("retainedStatus")),
                    "prior bridged status " + identity.getString("id") + "-" + direction);
            check(result.reason().equals(row.getString("retainedReason")),
                    "prior bridged reason " + identity.getString("id") + "-" + direction);
            check(fast.distance() == row.getInt("retainedFast")
                            && certified.distance() == row.getInt("retainedCertified"),
                    "prior bridged distances " + identity.getString("id") + "-" + direction);
            matched++;
        }
        check(matched == rows.length(), "every prior bridged direction replayed");
    }

    private static void natural(Path identityFile, Path out) throws Exception {
        JSONObject identity = new JSONObject(Files.readString(identityFile));
        Files.createDirectories(out);
        var pair = LocalOwnerTraceTest.prepare(identity);
        var prepared = List.of(CanonicalAlloyPipeline.prepare(pair.get(0)), CanonicalAlloyPipeline.prepare(pair.get(1)));
        var rows = new JSONArray();
        long start = System.nanoTime();
        for (int direction = 0; direction < 2; direction++) {
            var a = pair.get(direction);
            var b = pair.get(1 - direction);
            var fast = CanonicalRepairTraces.fast(a, b);
            var certified = CanonicalRepairTraces.certified(prepared.get(direction), prepared.get(1 - direction));
            var row = new JSONObject().put("identity", identity).put("direction", direction);
            QueueProbe.orderedReplay(fast.distance(), certified.distance(),
                    () -> fast.replay(fast.json()), () -> certified.replay(certified.json()), row);
            var result = NativeOperationComparison.compare(a, b, fast, certified);
            row.put("comparison", result.evidence());
            check(result.status() == NativeOperationComparison.Status.MATCHED,
                    "natural repeated-occurrence acceptance " + result.status() + " " + result.reason());
            check(result.evidence().getBoolean("endpointCorrespondenceChecked")
                            && result.evidence().getBoolean("fixedChoiceReplaysChecked"),
                    "complete native replay and action comparison checked");
            JSONObject classes = result.evidence().getJSONObject("nonForcedOccurrenceClasses");
            for (String side : List.of("source", "target")) {
                JSONArray recorded = classes.getJSONArray(side);
                for (Object value : recorded) {
                    JSONObject entry = (JSONObject) value;
                    JSONArray members = entry.getJSONArray("members");
                    check(members.length() >= 2, "recorded class has at least two admitted siblings");
                    boolean chosen = false;
                    for (int index = 0; index < members.length(); index++) {
                        chosen |= members.getInt(index) == entry.getInt("chosen");
                    }
                    check(chosen, "chosen occurrence is a class member");
                }
            }
            rows.put(row);
        }
        Files.writeString(out.resolve("result.json"), new JSONObject()
                .put("identity", identity).put("status", "PASS").put("checks", checks)
                .put("negativeControls", negatives).put("directions", 2)
                .put("elapsedNanos", System.nanoTime() - start)
                .put("publicCertification", "BLOCKED")
                .put("scope", "authenticated provenance refinement, deep twins, used-set injectivity, verdict rule")
                .put("rows", rows).toString(2));
    }

    public static void main(String[] args) throws Exception {
        syntheticGateControls();
        if (args.length >= 2) {
            natural(Path.of(args[0]), Path.of(args[1]));
        }
        if (args.length >= 3) {
            priorPaths(Path.of(args[2]));
        }
        System.out.println("AuthenticatedOccurrenceTest: " + checks + " checks passed, "
                + negatives + " negative controls"
                + (args.length >= 2 ? ", natural directions=2" : "")
                + (args.length >= 3 ? ", prior paths replayed" : ""));
    }
}

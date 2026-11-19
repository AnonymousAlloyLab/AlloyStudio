package is.fivefivefive.CanDis;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.FastChainGeometry;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.metric.RepairView;
import is.fivefivefive.CanDis.metric.RepairView.ContainerKind;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.GuardedJoinCertificate;
import is.fivefivefive.CanDis.theory.GuardedJoinTree;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;

/**
 * JOIN Seq and Terra J1-J4 controls.
 *
 * <p>Checks the actual source-linked receipts and geometry, not a scalar or a
 * status flag: ordered segment arities in both paths, typed-empty admission with
 * retained intermediate arities, authenticated owner/typed-slot selection,
 * checked orbit exhaustion, immutable orbit collections, and counted negative
 * correspondence controls. UNCHECKABLE is never counted as a pass.
 */
public final class JoinSeqRepairTest {
    private static int checks;
    private static int negatives;
    private static final Pattern EMPTY = Pattern.compile("EMPTY_RELATION\\[arity=([0-9]+)\\]");

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static void rejectAuth(java.util.function.BooleanSupplier accepted, String message) {
        checks++;
        negatives++;
        if (accepted.getAsBoolean()) throw new AssertionError("Accepted " + message);
    }

    private static void reject(Class<? extends RuntimeException> expected,
            Runnable action, String message) {
        checks++;
        negatives++;
        try {
            action.run();
        } catch (RuntimeException actual) {
            if (expected != null && !expected.isInstance(actual)) {
                throw new AssertionError(message + " (unexpected " + actual + ")");
            }
            return;
        }
        throw new AssertionError("Accepted " + message);
    }

    private static List<EGraphNode> nodes(EGraphNode root) {
        var result = new ArrayList<EGraphNode>();
        var pending = new java.util.ArrayDeque<EGraphNode>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<EGraphNode, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            var node = pending.remove();
            if (!seen.add(node)) continue;
            result.add(node);
            pending.addAll(node.getChildren());
        }
        return result;
    }

    private static List<EGraphNode> slots(EGraphNode binder) {
        var result = new ArrayList<EGraphNode>();
        for (var child : binder.getChildren()) {
            if (!declaration(child.getOpcode().name())) continue;
            result.addAll(child.getChildren().subList(1, child.getChildren().size()));
        }
        return result;
    }

    private static boolean declaration(String operator) {
        return operator.equals("GENERICRELDECL") || operator.equals("DISJ")
                || operator.equals("VAR") || operator.equals("DISJVAR");
    }

    private static List<Node> certNodes(Node root) {
        var result = new ArrayList<Node>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        collectCert(root, result, seen);
        return result;
    }

    private static void collectCert(Node node, List<Node> result, Set<Node> seen) {
        if (node == null || !seen.add(node)) return;
        result.add(node);
        for (var child : node.children()) collectCert(child, result, seen);
        for (var child : node.certifiedAlternatives()) collectCert(child, result, seen);
    }

    private static int certArity(Node node) {
        if (node.observationType() != null) {
            try {
                return AlloyTypeBridge.relationArity(node.observationType());
            } catch (RuntimeException ignored) { }
        }
        if (node.originalVariableSource() != null
                && node.originalVariableSource().getExactAlloyType() != null) {
            return node.originalVariableSource().getExactAlloyType().relationArity();
        }
        return -1;
    }

    private static List<String> certJoinArities(Node root) {
        var result = new ArrayList<String>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        collectCertArities(root, result, seen);
        return result;
    }

    private static void collectCertArities(Node node, List<String> result, Set<Node> seen) {
        if (node == null || !seen.add(node)) return;
        if (node.operator().equals("JOIN")) {
            var arities = new ArrayList<Integer>();
            for (var child : node.children()) arities.add(certArity(child));
            result.add(arities.toString());
        }
        for (var child : node.children()) collectCertArities(child, result, seen);
    }

    private static List<String> fastJoinArities(EGraphNode root) {
        var result = new ArrayList<String>();
        var all = nodes(root);
        var parents = Collections.newSetFromMap(new IdentityHashMap<EGraphNode, Boolean>());
        for (var node : all) {
            if (node.getOpcode() != EGraphNode.Opcode.JOIN) continue;
            for (var child : node.getChildren()) parents.add(child);
        }
        for (var node : all) {
            if (node.getOpcode() != EGraphNode.Opcode.JOIN || parents.contains(node)) continue;
            collectFastArities(node, result,
                    Collections.newSetFromMap(new IdentityHashMap<EGraphNode, Boolean>()));
        }
        return result;
    }

    private static void collectFastArities(EGraphNode node, List<String> result,
            Set<EGraphNode> seen) {
        if (node == null || node.getOpcode() != EGraphNode.Opcode.JOIN || !seen.add(node)) return;
        var children = FastChainGeometry.children(node);
        var arities = new ArrayList<Integer>();
        for (var child : children) {
            arities.add(child.getExactAlloyType() == null ? -1
                    : child.getExactAlloyType().relationArity());
        }
        result.add(arities.toString());
        for (var child : children) collectFastArities(child, result, seen);
    }

    private static JSONObject direction(JSONObject manifest, String id) {
        for (Object value : manifest.getJSONArray("directions")) {
            var row = (JSONObject) value;
            if (row.getJSONObject("identity").getString("id").equals(id)
                    && row.getInt("direction") == 0) return row;
        }
        throw new AssertionError("manifest lacks " + id);
    }

    private static void receipts(JSONObject manifest, Path out) throws Exception {
        var summary = new JSONArray();
        int matched = 0;
        for (Object value : manifest.getJSONArray("directions")) {
            var row = (JSONObject) value;
            var identity = row.getJSONObject("identity");
            int direction = row.getInt("direction");
            var pair = LocalOwnerTraceTest.prepare(identity);
            var certifiedPrepared = List.of(CanonicalAlloyPipeline.prepare(pair.get(0)),
                    CanonicalAlloyPipeline.prepare(pair.get(1)));
            var fast = CanonicalRepairTraces.fast(pair.get(direction), pair.get(1 - direction));
            var certified = CanonicalRepairTraces.certified(
                    certifiedPrepared.get(direction), certifiedPrepared.get(1 - direction));
            var evidence = new JSONObject();
            QueueProbe.orderedReplay(fast.distance(), certified.distance(),
                    () -> fast.replay(fast.json()), () -> certified.replay(certified.json()),
                    evidence);
            check(evidence.getJSONArray("replayOrder")
                    .similar(evidence.getJSONArray("replayCompleted")),
                    identity.getString("id") + "-" + direction + " complete ordered native replay");
            var result = NativeOperationComparison.compare(pair.get(direction), pair.get(1 - direction),
                    fast, certified);
            check(result.status() == NativeOperationComparison.Status.MATCHED,
                    identity.getString("id") + "-" + direction + " MATCHED: "
                            + result.status() + " " + result.reason());
            var comparison = result.evidence();
            check(fast.distance() == certified.distance(),
                    identity.getString("id") + "-" + direction + " exact component parity");
            check(comparison.getJSONArray("fastActions")
                    .similar(comparison.getJSONArray("certifiedActions")),
                    identity.getString("id") + "-" + direction + " ordered actions");
            matched++;
            summary.put(new JSONObject().put("id", identity.getString("id"))
                    .put("direction", direction).put("status", result.status().name())
                    .put("reason", result.reason()).put("fast", fast.distance())
                    .put("certified", certified.distance())
                    .put("replayOrder", evidence.getJSONArray("replayOrder")));
        }
        check(matched == 18, "all 18 boundary directions receive actual receipts");
        Files.createDirectories(out);
        Files.writeString(out.resolve("boundary18-receipts.json"), summary.toString(2));
    }

    private static void segments(JSONObject manifest, Path out) throws Exception {
        var expected = Map.of(
                "event82", List.of("[1, 3, 1]", "[1, 2]"),
                "event109", List.of("[2, 1]", "[1, 3, 2, 1]"),
                "event115", List.of("[2, 1]", "[1, 3, 2, 1]"),
                "event328", List.of("[1, 3, 1]"));
        var forbidden = Map.of(
                "event82", "[1, 3, 1, 2]",
                "event109", "[2, 1, 3, 2, 1]",
                "event115", "[2, 1, 3, 2, 1]",
                "event328", "[1, 3, 1, 3, 1]");
        var evidence = new JSONObject();
        for (var id : expected.keySet()) {
            var identity = direction(manifest, id).getJSONObject("identity");
            var pair = LocalOwnerTraceTest.prepare(identity);
            var certified = CanonicalAlloyPipeline.prepare(pair.get(0));
            var certifiedArities = certJoinArities(certified.repairView().phases().get(0).matrix());
            var fast = CanonicalDistance.prepare(pair.get(0).normalizedForms());
            var fastArities = fastJoinArities(fast.comparisonForms().get(0).getMatrixEGraph());
            for (var sequence : expected.get(id)) {
                check(certifiedArities.contains(sequence), id + " certified segment " + sequence);
                check(fastArities.contains(sequence), id + " Fast segment " + sequence);
            }
            check(!certifiedArities.contains(forbidden.get(id)),
                    id + " certified path does not flatten across the unary barrier");
            check(!fastArities.contains(forbidden.get(id)),
                    id + " Fast path does not flatten across the unary barrier");
            evidence.put(id, new JSONObject().put("certified", certifiedArities)
                    .put("fast", fastArities));
        }
        Files.writeString(out.resolve("segment-arities.json"), evidence.toString(2));
    }

    private static int treeArities(GuardedJoinTree tree, Set<Integer> output) {
        int arity = tree.type().relationType() == null ? -1
                : AlloyTypeBridge.relationArity(tree.type().relationType());
        output.add(arity);
        if (!tree.isLeaf()) {
            treeArities(tree.left(), output);
            treeArities(tree.right(), output);
        }
        return arity;
    }

    private static void typedEmptyGate(JSONObject manifest, JSONObject oldGate, Path out) throws Exception {
        var families = new LinkedHashMap<String, List<Integer>>();
        for (Object value : oldGate.getJSONArray("rows")) {
            var row = (JSONObject) value;
            var id = row.getJSONObject("identity").getString("id");
            if (!List.of("event82", "event109", "event115", "event328").contains(id)) continue;
            if (row.getInt("alternatives") != 0) continue;
            Matcher matcher = EMPTY.matcher(row.getString("exactType"));
            if (matcher.find()) {
                families.computeIfAbsent(id, ignored -> new ArrayList<>())
                        .add(Integer.parseInt(matcher.group(1)));
            }
        }
        check(families.size() == 4, "old gate evidence covers all four family sources");
        var evidence = new JSONObject();
        boolean invalidRotationChecked = false;
        GuardedJoinCertificate sampleCertificate = null;
        for (var entry : families.entrySet()) {
            var identity = direction(manifest, entry.getKey()).getJSONObject("identity");
            var pair = LocalOwnerTraceTest.prepare(identity);
            var adapted = TheoryAlloyAdapter.adapt(pair.get(0).normalizedForms());
            check(!adapted.semanticArtifact().guardedJoinConstructions().isEmpty(),
                    entry.getKey() + " typed-empty JOIN is admitted by the new gate");
            var arities = new LinkedHashSet<Integer>();
            for (var certificate : adapted.semanticArtifact().guardedJoinConstructions()) {
                if (sampleCertificate == null) sampleCertificate = certificate;
                int source = treeArities(certificate.source(), arities);
                int target = treeArities(certificate.target(), arities);
                check(source == target, entry.getKey() + " retained target result arity");
                check(certificate.source().type().alternatives().isEmpty()
                                || certificate.source().type().alternatives().size() == 1,
                        entry.getKey() + " no multi-product blanket admission");
            }
            for (int oldArity : entry.getValue()) {
                check(arities.contains(oldArity),
                        entry.getKey() + " retained old empty arity " + oldArity);
            }
            for (var certificate : adapted.semanticArtifact().guardedJoinConstructions()) {
                var invalid = invalidUnaryRotation(certificate.source());
                if (invalid != null) {
                    reject(IllegalArgumentException.class, invalid::rotate,
                            "invalid unary interior rotation");
                    invalidRotationChecked = true;
                    break;
                }
            }
            evidence.put(entry.getKey(), new JSONObject().put("oldEmptyArities", entry.getValue())
                    .put("newTreeArities", new ArrayList<>(arities))
                    .put("constructions", adapted.semanticArtifact().guardedJoinConstructions().size()));
        }
        if (!invalidRotationChecked && sampleCertificate != null) {
            outer:
            for (var first : sampleCertificate.source().leaves()) {
                for (var middle : sampleCertificate.source().leaves()) {
                    if (AlloyTypeBridge.relationArity(middle.type().relationType()) >= 2) continue;
                    for (var last : sampleCertificate.source().leaves()) {
                        try {
                            var nested = GuardedJoinTree.join(middle, last);
                            var tree = GuardedJoinTree.join(first, nested);
                            reject(IllegalArgumentException.class, tree::rotate,
                                    "constructed invalid unary interior rotation");
                            invalidRotationChecked = true;
                            break outer;
                        } catch (IllegalArgumentException unsupported) {
                            // This combination does not form a valid binary typed source.
                        }
                    }
                }
            }
        }
        check(invalidRotationChecked, "at least one invalid unary rotation is rejected");
        // A typed-empty DAG is admitted; the direct call is the gate control.
        GuardedJoinTree.requireAdmittedJoinType(
                is.fivefivefive.CanDis.theory.DependentTypeDag.empty(2), "test");
        checks++;
        var one = is.fivefivefive.CanDis.theory.DependentTypeDag.fromRelationFamilyType(
                GraphType.relation(GraphType.constructor("AlloySig:A")));
        var two = is.fivefivefive.CanDis.theory.DependentTypeDag.fromRelationFamilyType(
                GraphType.relation(GraphType.constructor("AlloySig:B")));
        var multiple = is.fivefivefive.CanDis.theory.DependentTypeDag.union(List.of(one, two));
        check(multiple.alternatives().size() > 1, "constructed a multi-product type");
        reject(IllegalArgumentException.class, () -> GuardedJoinTree.requireAdmittedJoinType(
                multiple, "test"), "blanket multi-product admission");
        Files.writeString(out.resolve("typed-empty-gate.json"), evidence.toString(2));
    }

    private static GuardedJoinTree invalidUnaryRotation(GuardedJoinTree tree) {
        if (tree.isLeaf()) return null;
        if (!tree.right().isLeaf()
                && AlloyTypeBridge.relationArity(tree.right().left().type().relationType()) < 2) {
            return tree;
        }
        var left = invalidUnaryRotation(tree.left());
        return left != null ? left : invalidUnaryRotation(tree.right());
    }

    private static void threeSlotAuthentication(Path out) throws Exception {
        var pair = NativeOperationComparisonTest.pair(
                "sig A { r:set A } pred inv { all x:A | some x.r implies "
                        + "(one y,z,w:A | y in x.r and z in y.r and w in z.r) } pred invc { some A }",
                "inv", "invc");
        var fast = CanonicalDistance.prepare(pair.left().normalizedForms());
        var owner = nodes(fast.comparisonForms().get(0).getMatrixEGraph()).stream()
                .filter(node -> slots(node).size() == 3 && !fast.fastAlternatives(node).isEmpty())
                .findFirst().orElseThrow();
        var orbit = fast.fastAlternatives(owner);
        check(orbit.size() == 6, "three-slot orbit has six representatives");
        reject(UnsupportedOperationException.class, () -> orbit.add(owner), "mutable orbit add");
        reject(UnsupportedOperationException.class, () -> orbit.remove(0), "mutable orbit remove");
        reject(UnsupportedOperationException.class, () -> orbit.set(0, owner), "mutable orbit set");
        var prepared = CanonicalAlloyPipeline.prepare(pair.left());
        Node certifiedOwner = certNodes(prepared.repairView().phases().get(0).matrix()).stream()
                .filter(node -> node.certifiedAlternatives().size() > 1
                        && node.children().stream().anyMatch(child -> declaration(child.operator())))
                .findFirst().orElseThrow();
        check(certifiedOwner.certifiedAlternatives().size() == orbit.size(),
                "certified and Fast three-slot orbit have equal size");
        var fastOrder = orbit.stream().map(JoinSeqRepairTest::fastImage).toList();
        var certifiedOrder = certifiedOwner.certifiedAlternatives().stream()
                .map(JoinSeqRepairTest::certImage).toList();
        check(!fastOrder.equals(certifiedOrder), "three-slot list orders genuinely differ");
        check(new LinkedHashSet<>(fastOrder).equals(new LinkedHashSet<>(certifiedOrder)),
                "three-slot typed actions are the same set");
        int authenticated = 0;
        for (var alternative : certifiedOwner.certifiedAlternatives()) {
            var chosen = FastRepresentativeAuthentication.authenticate(owner, alternative, orbit);
            check(chosen != null, "every certified alternative has an authenticated Fast action");
            check(FastRepresentativeAuthentication.fastActionSignature(chosen)
                            .equals(FastRepresentativeAuthentication.certifiedActionSignature(alternative)),
                    "authenticated action equality");
            authenticated++;
        }
        check(authenticated == 6, "all six certified alternatives authenticated");
        var real = certifiedOwner.certifiedAlternatives().get(1);
        rejectAuth(() -> FastRepresentativeAuthentication.authenticate(owner,
                forge(real, 0, 0), orbit) != null, "foreign coordinate action");
        rejectAuth(() -> FastRepresentativeAuthentication.authenticate(owner,
                forge(real, 0, 1), orbit) != null, "foreign exact type action");
        rejectAuth(() -> FastRepresentativeAuthentication.authenticate(owner,
                forge(real, 0, 2), orbit) != null, "foreign declaration owner action");
        rejectAuth(() -> FastRepresentativeAuthentication.authenticate(owner,
                forge(real, 0, 3), orbit) != null, "incomplete slot map");
        rejectAuth(() -> FastRepresentativeAuthentication.authenticate(owner,
                forge(real, 0, 4), orbit) != null, "nonbijective duplicate coordinate");
        rejectAuth(() -> FastRepresentativeAuthentication.authenticate(owner,
                forge(real, 0, 5), orbit) != null, "foreign source occurrence");
        rejectAuth(() -> FastRepresentativeAuthentication.authenticate(owner,
                forge(real, 0, 6), orbit) != null, "changed source-to-coordinate order");
        Files.createDirectories(out);
        Files.writeString(out.resolve("three-slot-auth.json"), new JSONObject()
                .put("fastOrder", fastOrder).put("certifiedOrder", certifiedOrder)
                .put("authenticated", authenticated).toString(2));
    }

    private static Method enumerate;
    private static Constructor<Node> nodeConstructor;
    private static Field lineage;
    private static Constructor<EGraphNode> graphConstructor;

    static {
        try {
            enumerate = Class.forName("is.fivefivefive.CanDis.core.FastBinderOrbit")
                    .getDeclaredMethod("enumerate", List.class, int[].class);
            enumerate.setAccessible(true);
            nodeConstructor = Node.class.getDeclaredConstructor(
                    String.class, String.class, String.class, String.class, int.class,
                    ContainerKind.class, boolean.class, List.class, List.class,
                    GraphType.class, List.class, String.class, EGraphNode.class,
                    NormalForm.TemporalReferenceOrigin.class);
            nodeConstructor.setAccessible(true);
            lineage = EGraphNode.class.getDeclaredField("sourceOccurrenceLineage");
            lineage.setAccessible(true);
            graphConstructor = EGraphNode.class.getDeclaredConstructor(
                    int.class, EGraphNode.Opcode.class, List.class, boolean.class, int.class,
                    boolean.class, EGraphNode.Metatype.class);
            graphConstructor.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static EGraphNode source(EGraphNode.Opcode opcode, long value) {
        try {
            var node = graphConstructor.newInstance(900_000 + (int) value, opcode,
                    List.of(), false, 0, false, EGraphNode.Metatype.BOOLEAN);
            lineage.setLong(node, value);
            return node;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Node node(String operator, String semanticPayload, String lexical,
            GraphType type, List<Node> children, EGraphNode source) {
        try {
            return nodeConstructor.newInstance(operator, operator.equals("VARIABLE") ? lexical : null,
                    semanticPayload, operator.equals("VARIABLE") ? lexical : null,
                    operator.equals("VARIABLE") ? -1 : -1, ContainerKind.SEQUENCE, false,
                    children, List.of(), type, List.of(), null, source, null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Node forge(Node real, int variableIndex, int mode) {
        var declarations = new ArrayList<Node>();
        int seen = 0;
        for (var child : real.children()) {
            if (!declaration(child.operator())) {
                declarations.add(child);
                continue;
            }
            var parts = new ArrayList<Node>(child.children());
            var ownerSource = child.originalSourceOccurrence();
            slots:
            for (int index = 1; index < parts.size(); index++) {
                if (seen++ != variableIndex) continue;
                var original = parts.get(index);
                Node replacement;
                switch (mode) {
                    case 0 -> replacement = node("VARIABLE", original.semanticPayload(),
                            "@local[0:9]", original.observationType(), List.of(),
                            original.originalVariableSource());
                    case 1 -> replacement = node("VARIABLE", original.semanticPayload(),
                            original.lexicalVariable(), GraphType.INT, List.of(),
                            original.originalVariableSource());
                    case 2 -> {
                        ownerSource = source(EGraphNode.Opcode.GENERICRELDECL,
                                (ownerSource == null ? 0L : ownerSource.getSourceOccurrenceLineage()) + 1);
                        break slots;
                    }
                    case 3 -> {
                        parts.remove(index);
                        break slots;
                    }
                    case 4 -> replacement = node("VARIABLE", original.semanticPayload(),
                            parts.get(parts.size() > 2 ? 2 : 1).lexicalVariable(),
                            original.observationType(), List.of(),
                            original.originalVariableSource());
                    case 5 -> replacement = node("VARIABLE", original.semanticPayload(),
                            original.lexicalVariable(), original.observationType(), List.of(),
                            null);
                    case 6 -> replacement = node("VARIABLE", original.semanticPayload(),
                            original.lexicalVariable(), original.observationType(), List.of(),
                            parts.get(parts.size() > 2 ? 2 : 1).originalVariableSource());
                    default -> throw new IllegalArgumentException("mode");
                }
                parts.set(index, replacement);
            }
            declarations.add(node(child.operator(), child.semanticPayload(), null,
                    child.observationType(), parts, ownerSource));
        }
        if (mode == 2 || mode == 3) checkIsolatedForgery(real, declarations, variableIndex, mode);
        return node("FORALL", null, null, GraphType.BOOL, declarations,
                real.originalSourceOccurrence());
    }

    private static void checkIsolatedForgery(Node real, List<Node> forged, int removed, int mode) {
        check(forged.size() == real.children().size(), "forgery retains declaration count");
        int seen = 0, changes = 0;
        var coordinates = new LinkedHashSet<String>();
        var sources = new LinkedHashSet<Long>();
        for (int i = 0; i < forged.size(); i++) {
            var before = real.children().get(i);
            var after = forged.get(i);
            if (!declaration(before.operator())) {
                check(before == after, "forgery retains non-declaration payload");
                continue;
            }
            var expected = new ArrayList<>(before.children());
            boolean selected = removed >= seen && removed < seen + expected.size() - 1;
            if (selected && mode == 3) expected.remove(1 + removed - seen);
            seen += before.children().size() - 1;
            check(expected.equals(after.children()), "only selected slot omission changes children");
            check(before.operator().equals(after.operator())
                    && java.util.Objects.equals(before.semanticPayload(), after.semanticPayload())
                    && java.util.Objects.equals(before.observationType(), after.observationType()),
                    "forgery retains declaration opcode/payload/type");
            check((before.originalSourceOccurrence() != after.originalSourceOccurrence())
                    == (selected && mode == 2), "only selected owner changes");
            if (selected) changes++;
            for (int j = 1; j < after.children().size(); j++) {
                var slot = after.children().get(j);
                check(coordinates.add(slot.lexicalVariable()), "forgery has no duplicate coordinates");
                check(sources.add(slot.originalVariableSource().getSourceOccurrenceLineage()),
                        "forgery has no duplicate source slots");
            }
        }
        check(changes == 1, "exactly one declaration is targeted");
    }

    private static String fastImage(EGraphNode node) {
        return slots(node).stream()
                .map(v -> v.getAlphaName().substring(v.getAlphaName().lastIndexOf(':') + 1)).toList().toString();
    }

    private static String certImage(Node node) {
        var result = new ArrayList<Integer>();
        for (var child : node.children()) {
            if (!declaration(child.operator())) continue;
            for (int index = 1; index < child.children().size(); index++) {
                var lexical = child.children().get(index).lexicalVariable();
                result.add(Integer.parseInt(lexical.substring(lexical.lastIndexOf(':') + 1,
                        lexical.length() - 1)));
            }
        }
        return result.toString();
    }

    private static void orbitResourceControls(Path out) throws Exception {
        int six = ((List<?>) invokeEnumerate(6)).size();
        check(six == 719, "six-slot orbit emits 719 nonidentity actions");
        for (int size : new int[]{7, 8, 21, 64}) {
            checks++;
            negatives++;
            try {
                invokeEnumerate(size);
                throw new AssertionError("accepted a factorial orbit beyond the checked cap");
            } catch (InvocationTargetException wrapped) {
                check(wrapped.getCause() instanceof IllegalStateException
                                && String.valueOf(wrapped.getCause().getMessage()).contains("FAST_ORBIT_BUDGET"),
                        "orbit exhaustion is an explicit resource obligation");
            }
        }
        checks++;
        negatives++;
        try {
            invokeEnumerate(6, 3);
            throw new AssertionError("accepted product of group factorials beyond cap");
        } catch (InvocationTargetException wrapped) {
            check(wrapped.getCause() instanceof IllegalStateException
                            && wrapped.getCause().getMessage().contains("FAST_ORBIT_BUDGET"),
                    "cross-group orbit exhaustion retains explicit resource outcome");
        }
        Files.createDirectories(out);
        Files.writeString(out.resolve("orbit-resource.json"), new JSONObject()
                .put("sixSlotActions", six).put("sevenSlot", "FAST_ORBIT_BUDGET")
                .put("eightSlot", "FAST_ORBIT_BUDGET").put("twentyOneSlot", "FAST_ORBIT_BUDGET")
                .put("sixtyFourSlot", "FAST_ORBIT_BUDGET")
                .put("sixAndThreeSlots", "FAST_ORBIT_BUDGET").toString(2));
    }

    private static Object invokeEnumerate(int... sizes) throws Exception {
        var groups = new ArrayList<List<Integer>>();
        var identity = new int[java.util.Arrays.stream(sizes).sum()];
        int offset = 0;
        for (int size : sizes) {
            var group = new ArrayList<Integer>();
            for (int index = 0; index < size; index++) {
                group.add(offset);
                identity[offset] = offset;
                offset++;
            }
            groups.add(group);
        }
        return enumerate.invoke(null, groups, identity);
    }

    private static void dedupAndReceiptControls(JSONObject manifest, Path out) throws Exception {
        int event1560 = 0;
        int event1495 = 0;
        for (Object value : manifest.getJSONArray("directions")) {
            var row = (JSONObject) value;
            var identity = row.getJSONObject("identity");
            var pair = LocalOwnerTraceTest.prepare(identity);
            var prepared = CanonicalDistance.prepare(pair.get(0).normalizedForms());
            var dedup = prepared.fastSetDeduplications();
            for (Object entryValue : dedup) {
                var entry = (JSONObject) entryValue;
                check(entry.getBoolean("orderInsensitive"), "dedup remains SET-only");
                check(!entry.getBoolean("bagMultiplicityTouched"),
                        "dedup never changes BAG multiplicity");
                check(entry.getInt("retainedIndex") != entry.getInt("droppedIndex"),
                        "dedup retains the eliminated duplicate");
            }
            if (identity.getString("id").equals("event1560")) event1560 += dedup.length();
            if (identity.getString("id").equals("event1495")) event1495 += dedup.length();
        }
        check(event1560 > 0, "event1560 SET dedup evidence retained");
        check(event1495 == 0, "unequal Workstation terms are not deduplicated");
        // Native receipt tamper: a selected cost/head change must not replay.
        var identity = direction(manifest, "event1495").getJSONObject("identity");
        var pair = LocalOwnerTraceTest.prepare(identity);
        var fast = CanonicalRepairTraces.fast(pair.get(0), pair.get(1));
        var tampered = new JSONObject(fast.json().toString());
        var operations = tampered.getJSONArray("phases").getJSONObject(0)
                .getJSONObject("program").getJSONArray("operations");
        var operation = operations.getJSONObject(0);
        operation.put("cost", operation.getInt("cost") + 1);
        reject(null, () -> fast.replay(tampered), "tampered selected operation cost");
        var headTampered = new JSONObject(fast.json().toString());
        var headOperation = headTampered.getJSONArray("phases").getJSONObject(0)
                .getJSONObject("program").getJSONArray("operations").getJSONObject(0);
        var before = headOperation.optJSONObject("before");
        if (before != null) before.put("head", before.getString("head") + "foreign");
        reject(null, () -> fast.replay(headTampered), "tampered selected head");
        Files.createDirectories(out);
        Files.writeString(out.resolve("dedup-receipt-controls.json"), new JSONObject()
                .put("event1560Rows", event1560).put("event1495Rows", event1495)
                .put("tamperedReceiptsRejected", 2).toString(2));
    }

    public static void main(String[] args) throws Exception {
        var manifest = new JSONObject(Files.readString(Path.of(args[0])));
        var oldGate = new JSONObject(Files.readString(Path.of(args[1])));
        var out = Path.of(args[2]);
        Files.createDirectories(out);
        receipts(manifest, out);
        segments(manifest, out);
        typedEmptyGate(manifest, oldGate, out);
        threeSlotAuthentication(out);
        orbitResourceControls(out);
        dedupAndReceiptControls(manifest, out);
        Files.writeString(out.resolve("result.json"), new JSONObject()
                .put("schema", "join-seq-java-repair-test-v1")
                .put("checks", checks).put("negativeControls", negatives)
                .put("directions", 18).put("matched", 18)
                .put("publicCertification", "BLOCKED")
                .put("fullFocused", "NOT_RUN").toString(2));
        System.out.println("JoinSeqRepairTest: " + checks + " checks passed, "
                + negatives + " negative controls, 18/18 boundary directions");
    }
}

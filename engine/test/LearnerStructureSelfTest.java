package is.fivefivefive.CanDis;

import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.QuantiVar;
import live.LiveFeedback;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finite learner structure/provenance witnesses, with no reference text logs. */
public final class LearnerStructureSelfTest {
    private static int checks;
    private static final String ENV = "module learner_structure\nsig PrivateName { privateRelation: set PrivateName }\n";

    public static void main(String[] args) throws Exception {
        PrintStream report = System.out, errors = System.err;
        try (PrintStream discard = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(discard);
            System.setErr(discard);
            Canonical.Prepared prepared = prepare("all hidden: PrivateName | some hidden.privateRelation");
            // Reflection/JIT setup is not an assumption in production: the first
            // optional response can explicitly be partial under its 25ms cap.
            LearnerStructure.export(prepared);
            JSONObject structure = complete(prepared);
            validate(prepared, structure);
            check(!structure.toString().contains("PrivateName") && !structure.toString().contains("privateRelation")
                    && !structure.toString().contains("hidden"), "all learner names are masked");
            check(structure.getJSONArray("phases").getJSONObject(0).getInt("bindingCount") == 1,
                    "prefix count admits existing binding 0 and insertion gap 1");
            boolean binding = false;
            for (Object item : structure.getJSONArray("nodes")) {
                JSONObject node = (JSONObject) item;
                JSONArray address = node.getJSONArray("address");
                if (address.get(1) instanceof Integer) {
                    binding = true;
                    check(node.getString("category").equals("BINDING"), "binding category is explicit");
                    check(address.getJSONArray(2).isEmpty() && node.getInt("childCount") == 0,
                            "binding positions have no matrix descendants");
                }
            }
            check(binding, "metric prefix binding exported");

            Canonical.Prepared repeated = prepare("some privateRelation and no (PrivateName.privateRelation)");
            JSONObject repeats = complete(repeated);
            validate(repeated, repeats);
            Map<String, Set<String>> categories = new HashMap<>();
            for (Object item : repeats.getJSONArray("nodes")) {
                JSONObject node = (JSONObject) item;
                categories.computeIfAbsent(node.getString("category"), ignored -> new HashSet<>())
                        .add(node.getJSONArray("address").toString());
            }
            check(categories.get("REFERENCE").size() >= 2,
                    "repeated reference labels retain separate structural addresses");
            sharedOccurrenceWitness();

            Canonical.Prepared temporal = prepare("(always some PrivateName) until (eventually no privateRelation)");
            JSONObject time = complete(temporal);
            validate(temporal, time);
            Set<String> temporalOperators = new HashSet<>();
            for (Object item : time.getJSONArray("nodes")) {
                JSONObject node = (JSONObject) item;
                if (node.getJSONArray("address").get(0).equals("T")) {
                    check(node.getString("category").equals("TEMPORAL"), "separate skeleton category");
                    if (node.has("operator")) temporalOperators.add(node.getString("operator"));
                }
            }
            check(temporalOperators.containsAll(Set.of("always", "until", "eventually")),
                    "binary temporal skeleton uses metric operators and merged branches");

            JSONObject capped = LearnerStructure.export(repeated, 1, 64, 262144, 25);
            check(capped.getString("status").equals("partial") && capped.getString("reason").equals("node_limit")
                    && capped.getJSONArray("nodes").length() == 1, "node cap returns explicit partial output");
            JSONObject shallow = LearnerStructure.export(repeated, 2048, 0, 262144, 25);
            check(shallow.getString("status").equals("partial") && shallow.getString("reason").equals("depth_limit"),
                    "depth cap never invents a descendant");
            JSONObject small = LearnerStructure.export(repeated, 2048, 64, 1024, 25);
            check(small.getString("status").equals("partial") && small.getString("reason").equals("byte_limit")
                    && small.toString().getBytes(StandardCharsets.UTF_8).length <= 1024,
                    "byte cap bounds serialized optional metadata");
            WorkBudget.begin(0);
            boolean refused = false;
            try { LearnerStructure.export(prepared); }
            catch (IllegalStateException expected) { refused = true; }
            finally { WorkBudget.end(); }
            check(refused, "optional projection cannot consume an active metric budget");

            JSONObject one = feedback("some PrivateName", "no PrivateName");
            JSONObject two = feedback("some PrivateName", "one privateRelation");
            check(one.getString("status").equals("ok") && two.getString("status").equals("ok"),
                    "feedback succeeds with alternative reference targets");
            check(one.getJSONObject("canonicalStructure").similar(two.getJSONObject("canonicalStructure")),
                    "same learner structure is independent of reference choice");
            JSONObject zero = feedback("some PrivateName", "some PrivateName");
            check(zero.getInt("distance") == 0 && zero.getJSONArray("operations").isEmpty()
                    && zero.has("canonicalStructure"), "zero distance retains structure for alternative-solution guidance");
            check(!feedback("some (", "some PrivateName").has("canonicalStructure"),
                    "invalid predicates never publish structure");
            JSONObject ast = LiveFeedback.evaluate(new JSONObject().put("studentSource", source("some PrivateName"))
                    .put("oracleSource", source("no PrivateName")).put("predicate", "target").put("metric", "ast"));
            check(ast.getString("status").equals("ok") && !ast.has("canonicalStructure"),
                    "AST response is not mislabeled as a canonical snapshot");
        } finally {
            System.setOut(report);
            System.setErr(errors);
        }
        report.println("LearnerStructureSelfTest passed (" + checks + " checks)");
    }

    private static JSONObject complete(Canonical.Prepared prepared) {
        // Retry the pure optional projection after possible one-time class/JIT
        // initialization. This never repeats parsing or canonical normalization.
        JSONObject result = null;
        for (int retry = 0; retry < 5; retry++) {
            result = LearnerStructure.export(prepared);
            if (result.getString("status").equals("ok")) return result;
        }
        check(false, "small fixture fits optional bounds");
        return result;
    }

    private static void sharedOccurrenceWitness() throws Exception {
        EGraphNode shared = new EGraphNode(0, EGraphNode.Opcode.CONSTANT, List.of(), false, 0, false,
                EGraphNode.Metatype.BOOLEAN);
        EGraphNode root = new EGraphNode(1, EGraphNode.Opcode.IMPLIES, List.of(shared, shared), false, 2, false,
                EGraphNode.Metatype.BOOLEAN);
        Class<?> type = Class.forName(LearnerStructure.class.getName() + "$Index");
        var constructor = type.getDeclaredConstructor(int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Method matrix = type.getDeclaredMethod("matrix", EGraphNode.class, int.class, List.class);
        Method result = type.getDeclaredMethod("result");
        matrix.setAccessible(true);
        result.setAccessible(true);
        Object index = constructor.newInstance(2048, 64, 262144, 25);
        matrix.invoke(index, root, 0, new java.util.ArrayList<Integer>());
        JSONArray nodes = ((JSONObject) result.invoke(index)).getJSONArray("nodes");
        check(nodes.length() == 3, "shared representative still has two child occurrences");
        check(nodes.getJSONObject(1).getString("nodeId").equals(nodes.getJSONObject(2).getString("nodeId"))
                        && !nodes.getJSONObject(1).getJSONArray("address").similar(nodes.getJSONObject(2).getJSONArray("address")),
                "shared object ID does not collapse distinct positions");
    }

    private static void validate(Canonical.Prepared prepared, JSONObject structure) {
        check(structure.getInt("version") == 1 && structure.getString("status").equals("ok"), "complete v1 snapshot");
        Set<String> addresses = new HashSet<>();
        Map<Object, String> ids = new IdentityHashMap<>();
        Map<String, Object> identities = new HashMap<>();
        Map<String, JSONObject> indexed = new HashMap<>();
        for (Object item : structure.getJSONArray("nodes")) {
            JSONObject node = (JSONObject) item;
            JSONArray address = node.getJSONArray("address");
            check(addresses.add(address.toString()), "one row per occurrence");
            indexed.put(address.toString(), node);
            check(node.keySet().equals(node.has("operator")
                    ? Set.of("address", "nodeId", "category", "operator", "childCount")
                    : Set.of("address", "nodeId", "category", "childCount")), "finite public node fields only");
            if (!(address.get(0) instanceof Integer)) continue;
            Object identity;
            int phase = address.getInt(0);
            String path = "normalForm[" + phase + "]";
            if (address.get(1).equals("M")) {
                path += ".matrix";
                for (Object index : address.getJSONArray(2)) path += ".child[" + index + "]";
                EGraphNode resolved = LiveTrace.learnerNode(prepared, path);
                check(resolved != null && resolved.getChildren().size() == node.getInt("childCount"),
                        "matrix address resolves exact existing learner occurrence");
                identity = resolved;
            } else {
                QuantiVar resolved = LiveTrace.learnerBinding(prepared, path + ".quantifier[" + address.getInt(1) + "]");
                check(resolved != null, "binding address resolves metric ordering");
                identity = resolved;
            }
            String id = node.getString("nodeId");
            check(!ids.containsKey(identity) || ids.get(identity).equals(id), "shared identity retains same opaque ID");
            check(!identities.containsKey(id) || identities.get(id) == identity, "distinct graph objects do not merge by text");
            ids.put(identity, id);
            identities.put(id, identity);
        }
        for (JSONObject node : indexed.values()) {
            JSONArray address = node.getJSONArray("address");
            for (int child = 0; child < node.getInt("childCount"); child++) {
                JSONArray children = new JSONArray(address.getJSONArray(2).toString()).put(child);
                JSONArray wanted = new JSONArray().put(address.get(0)).put(address.get(1)).put(children);
                check(indexed.containsKey(wanted.toString()), "complete snapshot contains every ordered child occurrence");
            }
        }
        check(structure.toString().getBytes(StandardCharsets.UTF_8).length <= LearnerStructure.MAX_BYTES,
                "wire-size bound");
    }

    private static Canonical.Prepared prepare(String body) throws Exception {
        Method prepare = LiveFeedback.class.getDeclaredMethod("prepare", String.class, String.class);
        prepare.setAccessible(true);
        return (Canonical.Prepared) prepare.invoke(null, source(body), "target");
    }

    private static JSONObject feedback(String student, String reference) {
        return LiveFeedback.evaluate(new JSONObject().put("studentSource", source(student))
                .put("oracleSource", source(reference)).put("predicate", "target"));
    }

    private static String source(String body) { return ENV + "pred target { " + body + " }\n"; }
    private static void check(boolean valid, String name) {
        checks++;
        if (!valid) throw new AssertionError("Learner structure check failed: " + name);
    }
}

package is.fivefivefive.ACGN.repair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.ast.*;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.metric.RepairView;

/** Separate read-only export and exhaustive one-step label worker. */
public final class HybridCorpusWorker {
    private RepairTask task;
    private RepairEnvironment env;
    private RepairEvaluator evaluator;
    private RepairEnvironment.State initial;
    private RepairTask.EvaluatorContext context;
    private List<RepairAction> actions;
    private String source;
    private final JSONArray sourceNodes = new JSONArray();
    private final Map<String,Integer> paths = new HashMap<>();
    private int slots;

    public static void main(String[] args) throws Exception {
        PrintStream output = System.out;
        System.setOut(System.err);
        HybridCorpusWorker worker = new HybridCorpusWorker();
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JSONObject request = new JSONObject(line), reply;
                try {
                    reply = switch (request.getString("op")) {
                        case "load" -> worker.load(request);
                        case "labels" -> worker.labels(request);
                        default -> throw new IllegalArgumentException("Unknown operation");
                    };
                    reply.put("ok", true);
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                    reply = new JSONObject().put("ok", false).put("error", e.toString());
                }
                output.println(reply.put("id", request.get("id")).put("protocol", RepairWorkerMain.VERSION));
                output.flush();
            }
        }
    }

    private JSONObject load(JSONObject r) throws Exception {
        if (task != null) throw new IllegalStateException("One task per worker");
        source = Files.readString(Path.of(r.getString("path")), StandardCharsets.UTF_8);
        String oracle = r.getString("oracle");
        task = RepairTask.fromSource(source, r.getString("target"), r.getInt("command"), Set.of(oracle));
        env = new RepairEnvironment(task, 1);
        initial = env.initial();
        context = task.evaluatorContext(oracle);
        evaluator = new RepairEvaluator(task, oracle, 2, 1);
        var validation = evaluator.validate(initial);
        if (validation.verdict() != RepairFeedback.Verdict.COUNTEREXAMPLE) {
            throw new RepairTask.UnsupportedInput("INITIAL_" + validation.verdict());
        }
        var menu = env.actions(initial);
        if (menu.truncated()) throw new RepairTask.UnsupportedInput("UPSTREAM_MENU_TRUNCATED");
        actions = menu.actions();
        int root = sourceNode(initial.candidate.expression(), "root", new IdentityHashMap<>());
        int facts = sourceNode(context.module().getAllReachableFacts(), "facts", new IdentityHashMap<>());
        JSONArray sigs = new JSONArray(), fields = new JSONArray();
        for (Sig sig : context.module().getAllReachableSigs()) {
            if (sig.builtin) continue;
            if (!sig.getFacts().isEmpty()) throw new RepairTask.UnsupportedInput("SIGNATURE_FACT_CONTEXT");
            JSONArray parents = new JSONArray();
            if (sig instanceof Sig.PrimSig p && p.parent != null) parents.put(p.parent.label);
            if (sig instanceof Sig.SubsetSig s) for (Sig p : s.parents) parents.put(p.label);
            sigs.put(new JSONObject().put("name", sig.label).put("parents", parents)
                    .put("subset", sig instanceof Sig.SubsetSig).put("abstract", sig.isAbstract != null)
                    .put("multiplicity", sig.isOne != null ? "ONE" : sig.isLone != null ? "LONE" : sig.isSome != null ? "SOME" : "SET"));
            for (Sig.Field field : sig.getFields()) {
                fields.put(new JSONObject().put("name", sig.label + "." + field.label)
                        .put("label", field.label).put("type", type(field.type()))
                        .put("domain", sourceNode(field.decl().expr, "field/" + fields.length(), new IdentityHashMap<>())));
            }
        }
        JSONArray phases = new JSONArray();
        for (RepairView.Phase phase : initial.repairView().phases()) {
            JSONArray bindings = new JSONArray();
            for (RepairView.Binding b : phase.bindings()) {
                var d = b.declaration();
                bindings.put(new JSONObject().put("quantifier", d.quantifier()).put("type", d.type())
                        .put("cardinality", d.cardinality()).put("disjoint", d.disjointnessClass())
                        .put("dependencies", d.dependencies()).put("orbit", b.certifiedOrbit())
                        .put("role", b.role().name()).put("path", b.bindingPath())
                        .put("coordinate", b.coordinate()).put("exchange", d.exchangeClass()));
            }
            phases.put(new JSONObject().put("bindings", bindings).put("matrix", matrix(phase.matrix())));
        }
        JSONArray exportedActions = new JSONArray();
        for (int i = 0; i < actions.size(); i++) {
            RepairAction a = actions.get(i);
            if (a.kind() != RepairAction.Kind.REPLACE_OPERATOR && a.kind() != RepairAction.Kind.MODIFY_QUANTIFIER) continue;
            Integer occurrence = paths.get(a.path());
            if (occurrence == null) throw new IllegalStateException("Action occurrence missing");
            exportedActions.put(new JSONObject().put("index", i).put("node", occurrence)
                    .put("kind", a.kind().name()).put("after", a.operation().toUpperCase(Locale.ROOT))
                    .put("before", sourceNodes.getJSONObject(occurrence).getString("op"))
                    .put("path", a.path()).put("start", a.start()).put("end", a.end())
                    .put("expected", a.expected()).put("replacement", a.replacement()));
        }
        return new JSONObject().put("schema", "hybrid-corpus-v1").put("source", sourceNodes)
                .put("root", root).put("facts", facts).put("signatures", sigs).put("fields", fields)
                .put("phases", phases).put("actions", exportedActions)
                .put("worlds", evaluator.witnessTuples(initial)).put("sourceSlots", slots)
                .put("profile", task.semanticProfile().fingerprint()).put("sourceHash", initial.candidateHash())
                .put("canonicalHash", initial.observationDigest()).put("initialValidation", validation.json())
                .put("bitwidth", context.command().bitwidth < 0 ? 4 : context.command().bitwidth);
    }

    private JSONObject labels(JSONObject request) {
        int[] range = context.oracle().span().toStartEnd(source);
        if (range == null || source.charAt(range[0]) != '{' || source.charAt(range[1] - 1) != '}') {
            throw new IllegalStateException("Oracle body range missing");
        }
        var truth = env.propose(source.substring(range[0]+1, range[1]-1));
        JSONArray outcomes = new JSONArray();
        Set<Integer> seen = new HashSet<>();
        var originalBank = evaluator.snapshot();
        int initialDistance = CanonicalAlloyPipeline.distance(initial.candidate.prepared(), truth.candidate.prepared());
        // Score every edit on the same bank before validation can add witnesses.
        List<RepairEnvironment.Transition> transitions = new ArrayList<>();
        for (Object object : request.getJSONArray("indices")) {
            int index = (Integer) object;
            if (!seen.add(index) || index < 0 || index >= actions.size()) throw new IllegalArgumentException("Bad action index");
            var transition = env.step(initial, actions.get(index));
            transitions.add(transition);
            JSONObject out = new JSONObject().put("index", index).put("valid", transition.outcome() == RepairKernel.Outcome.APPLIED);
            if (transition.failure() == RepairEnvironment.Failure.CONSTRUCTION_ERROR
                    || transition.failure() == RepairEnvironment.Failure.RESOURCE_LIMIT) {
                throw new IllegalStateException(transition.detail());
            }
            if (transition.outcome() == RepairKernel.Outcome.APPLIED) {
                out.put("distance", CanonicalAlloyPipeline.distance(transition.state().candidate.prepared(), truth.candidate.prepared()))
                        .put("score", evaluator.score(transition.state(), originalBank).potential())
                        .put("body", transition.state().body()).put("sourceHash", transition.state().candidateHash());
            } else out.put("failure", transition.failure().name());
            outcomes.put(out);
        }
        for (int i = 0; i < transitions.size(); i++) {
            if (outcomes.getJSONObject(i).getBoolean("valid")) {
                var verdict = evaluator.validate(transitions.get(i).state());
                if (verdict.verdict() == RepairFeedback.Verdict.UNRESOLVED || verdict.verdict() == RepairFeedback.Verdict.VACUOUS) {
                    throw new IllegalStateException("Unusable solver result");
                }
                outcomes.getJSONObject(i).put("validation", verdict.json())
                        .put("solved", verdict.verdict() == RepairFeedback.Verdict.SOLVED_WITHIN_PROFILE);
            }
        }
        return new JSONObject().put("initialDistance", initialDistance).put("outcomes", outcomes);
    }

    private static JSONArray type(Type type) {
        JSONArray result = new JSONArray();
        for (var product : type.fold()) {
            JSONArray columns = new JSONArray();
            for (Sig sig : product) columns.put(sig.label);
            result.put(columns);
        }
        return result;
    }

    private int sourceNode(Expr expr, String path, Map<Expr,Integer> scope) {
        if (sourceNodes.length() >= 256) throw new RepairTask.UnsupportedInput("SOURCE_SIZE");
        if (expr instanceof ExprUnary u && u.op == ExprUnary.Op.NOOP) {
            int child = sourceNode(u.sub, path + "/0", scope);
            paths.put(path, child);
            return child;
        }
        String op;
        JSONObject node = new JSONObject().put("type", type(expr.type()));
        if (expr instanceof ExprUnary u) op = u.op.name();
        else if (expr instanceof ExprBinary b) op = b.op.name();
        else if (expr instanceof ExprList l) op = l.op.name();
        else if (expr instanceof ExprQt q) op = "QUANT_" + q.op.name();
        else if (expr instanceof ExprConstant c) { op = c.op.name(); node.put("literal", c.num); }
        else if (expr instanceof Sig s) { op = "SIG"; node.put("symbol", s.label); }
        else if (expr instanceof Sig.Field f) { op = "FIELD"; node.put("symbol", f.sig.label + "." + f.label); }
        else if (expr instanceof ExprVar) {
            op = "VAR";
            if (!scope.containsKey(expr)) throw new RepairTask.UnsupportedInput("FREE_SOURCE_VAR");
            node.put("slot", scope.get(expr));
        } else throw new RepairTask.UnsupportedInput("SOURCE_FEATURE_" + expr.getClass().getSimpleName());
        int id = sourceNodes.length();
        paths.put(path, id);
        sourceNodes.put(node.put("op", op).put("path", path));
        JSONArray children = new JSONArray(), binders = new JSONArray();
        if (expr instanceof ExprQt q) {
            Map<Expr,Integer> nested = new IdentityHashMap<>(scope);
            int index = 0;
            for (Decl d : q.decls) {
                int domain = sourceNode(d.expr, path + "/" + index++, nested);
                children.put(domain);
                for (ExprHasName var : d.names) {
                    int slot = slots++;
                    nested.put(var, slot);
                    binders.put(new JSONObject().put("slot", slot).put("domain", domain)
                            .put("disjoint", d.disjoint == null ? -1 : domain));
                }
            }
            children.put(sourceNode(q.sub, path + "/" + index, nested));
        } else {
            List<Expr> operands = RepairTask.children(expr);
            for (int i = 0; i < operands.size(); i++) children.put(sourceNode(operands.get(i), path + "/" + i, scope));
        }
        node.put("children", children).put("binders", binders);
        return id;
    }

    private static JSONObject matrix(RepairView.Node node) {
        if (node == null) throw new RepairTask.UnsupportedInput("EMPTY_MATRIX");
        JSONArray children = new JSONArray(), alternatives = new JSONArray();
        for (var child : node.children()) children.put(matrix(child));
        for (var alt : node.certifiedAlternatives()) alternatives.put(matrix(alt));
        return new JSONObject().put("op", node.operator()).put("payload", node.payload() == null ? "" : node.payload())
                .put("variable", node.isVariable()).put("slot", node.bindingIndex())
                .put("container", node.containerKind().name()).put("children", children).put("alternatives", alternatives);
    }
}

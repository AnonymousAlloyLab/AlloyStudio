package live;

import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.ast.ExprVar;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.AlloyLibraryCallableLedger;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.Call;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent bundled-source census and real Canonical calls; one bounded JVM.
 * The parser, not the production ledger, determines the tested declarations and
 * symbolic argument types. No solver, credentials, network or private model.
 */
public final class LibraryCallableSelfTest {
    private static Path scratch;
    private static int jobs;
    private static final Set<String> PARAMETERIZED = Set.of("graph", "ordering", "seqrel", "sequence");

    public static void main(String[] arguments) throws Exception {
        scratch = Path.of(System.getProperty("java.io.tmpdir"));
        PrintStream wire = System.out, errors = System.err;
        JSONObject output = new JSONObject();
        try (PrintStream discard = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(discard);
            System.setErr(discard);
            Map<String, String> sources = bundledSources(Path.of(arguments[0]));
            Set<String> census = new TreeSet<>();
            JSONObject counts = new JSONObject();
            for (var entry : sources.entrySet()) {
                // Parse declarations without resolving bodies: util/sequence has
                // a separately tested upstream type error in this bundled jar.
                CompModule module = CompUtil.parseOneModule(entry.getValue());
                int count = 0;
                for (Func function : module.getAllFunc()) if (function.isPrivate == null) {
                    String member = member(function);
                    CallSymbol.Kind kind = kind(function);
                    String key = key("util/" + entry.getKey(), member, kind, function.count());
                    if (!census.add(key)) throw new AssertionError("Duplicate bundled declaration: " + key);
                    count++;
                }
                counts.put(entry.getKey(), count);
            }
            output.put("declarations", new JSONArray(census));
            output.put("counts", counts);
            output.put("resources", sources.size());
            output.put("ledger", ledgerControls(census));
            JSONArray identities = new JSONArray();
            for (String name : sources.keySet()) {
                if (name.equals("sequence") || name.equals("time")) continue;
                begin();
                try {
                    String environment = environment(name);
                    CompModule root = WorkerSafety.parse(environment + "pred target {}\n");
                    CompModule library = null;
                    for (CompModule module : root.getAllReachableModules())
                        if (module.getModelName().equals("util/" + name)) library = module;
                    if (library == null) throw new AssertionError("Missing bundled module: " + name);
                    for (Func function : library.getAllFunc()) if (function.isPrivate == null) {
                        List<String> declarations = new ArrayList<>(), argumentsList = new ArrayList<>();
                        int index = 0;
                        for (ExprVar parameter : function.params()) {
                            String variable = "argument" + index++;
                            declarations.add(variable + ": set (" + parameter.type().toExpr() + ")");
                            argumentsList.add(variable);
                        }
                        String header = "pred target" + (declarations.isEmpty() ? "" :
                                "[" + String.join(", ", declarations) + "]") + " {\n";
                        String call = "lib/" + member(function) + "[" + String.join(", ", argumentsList) + "]";
                        String body = function.isPred ? call : "some " + call;
                        String source = environment + header + body + "\n}\n";
                        String mutationHeader = header, changed = "not (" + body + ")";
                        if (!function.params().isEmpty()) {
                            declarations.add("replacement: set (" + function.params().get(0).type().toExpr() + ")");
                            mutationHeader = "pred target[" + String.join(", ", declarations) + "] {\n";
                            argumentsList.set(0, "replacement");
                            String changedCall = "lib/" + member(function) + "[" + String.join(", ", argumentsList) + "]";
                            changed = function.isPred ? changedCall : "some " + changedCall;
                        }
                        identities.put(new JSONObject().put("declaration", key("util/" + name,
                                member(function), kind(function), function.count()))
                                .put("source", source).put("calls", callIdentities(WorkerSafety.parse(source)))
                                .put("feedback", compareWithinJob(environment, header, body, body))
                                .put("mutationFeedback", compareWithinJob(environment, mutationHeader, body, changed)));
                    }
                } finally { WorkerSafety.end(); }
            }
            output.put("identities", identities);
            output.put("controls", controls());
            output.put("forgedNestedCallsRejected", forgedNestedCalls());
        } finally {
            System.setOut(wire);
            System.setErr(errors);
        }
        wire.println(output);
    }

    private static Map<String, String> bundledSources(Path path) throws Exception {
        Map<String, String> sources = new TreeMap<>();
        try (JarFile jar = new JarFile(path.toFile())) {
            for (var entry : jar.stream().toList()) {
                String name = entry.getName();
                if (!name.startsWith("models/util/") || !name.endsWith(".als")) continue;
                String module = name.substring("models/util/".length(), name.length() - 4);
                if (module.contains("/")) throw new AssertionError("Unexpected nested bundled module");
                sources.put(module, new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    private static String environment(String module) {
        return "module library_fixture\nopen util/" + module
                + (PARAMETERIZED.contains(module) ? "[Node]" : "") + " as lib\nsig Node {}\n";
    }
    private static String member(Func function) {
        return function.label.substring(function.label.lastIndexOf('/') + 1);
    }
    private static CallSymbol.Kind kind(Func function) {
        return function.isPred ? CallSymbol.Kind.FORMULA : CallSymbol.Kind.EXPRESSION;
    }
    private static String key(String module, String member, CallSymbol.Kind kind, int arity) {
        return module + "/" + member + "/" + kind + "/" + arity;
    }

    private static JSONObject ledgerControls(Set<String> census) throws Exception {
        Field field = AlloyLibraryCallableLedger.class.getDeclaredField("SIGNATURES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, AlloyLibraryCallableLedger.Signature> signatures =
                (Map<String, AlloyLibraryCallableLedger.Signature>) field.get(null);
        Set<String> missing = new TreeSet<>(census); missing.removeAll(signatures.keySet());
        Set<String> extra = new TreeSet<>(signatures.keySet()); extra.removeAll(census);
        int accepted = 0, rejected = 0;
        for (String signature : census) {
            String[] parts = signature.split("/");
            String module = parts[0] + "/" + parts[1], member = parts[2];
            CallSymbol.Kind kind = CallSymbol.Kind.valueOf(parts[3]);
            int arity = Integer.parseInt(parts[4]);
            try {
                var actual = AlloyLibraryCallableLedger.require(module, member, kind, arity);
                if (actual.arity() != arity || actual.kind() != kind)
                    throw new AssertionError("Wrong accepted declaration metadata");
                accepted++;
            } catch (IllegalStateException absent) { /* Missing is reported independently. */ }
            rejected += reject(module, member, kind, 100);
            rejected += reject(module, member, kind == CallSymbol.Kind.FORMULA
                    ? CallSymbol.Kind.EXPRESSION : CallSymbol.Kind.FORMULA, arity);
            rejected += reject("foreign/module", member, kind, arity);
        }
        return new JSONObject().put("missing", new JSONArray(missing)).put("extra", new JSONArray(extra))
                .put("accepted", accepted).put("rejected", rejected);
    }
    private static int reject(String module, String member, CallSymbol.Kind kind, int arity) {
        try { AlloyLibraryCallableLedger.require(module, member, kind, arity); }
        catch (IllegalStateException expected) { return 1; }
        throw new AssertionError("Malformed library signature admitted");
    }
    private static void begin() throws Exception {
        WorkerSafety.begin(Files.createDirectory(scratch.resolve("library-job-" + (++jobs))));
    }
    private static JSONObject compareWithinJob(String environment, String header, String learner, String reference) {
        String prefix = environment + header;
        return LiveFeedback.evaluate(new JSONObject().put("metric", "canonical").put("predicate", "target")
                .put("studentSource", prefix + learner + "\n}\n")
                .put("referencePrefix", prefix).put("referenceSuffix", "\n}\n")
                .put("referenceBodies", new JSONArray().put(reference)).put("blockedPredicates", new JSONArray()));
    }
    private static JSONObject compare(String environment, String header, String learner, String reference) throws Exception {
        begin();
        try { return compareWithinJob(environment, header, learner, reference); }
        finally { WorkerSafety.end(); }
    }
    private static JSONArray callIdentities(CompModule module) {
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), Set.of("target"), module);
        visitor.visit(MASGVisitor.modelWithSourceMap(module), null);
        var graph = visitor.getForest().get(visitor.getForestId("target"));
        JSONArray calls = new JSONArray();
        for (var node : graph.getVertices()) if (node.getSymbol() instanceof CallSymbol call
                && call.getArityAuthority() == CallSymbol.ArityAuthority.TYPECHECKED_IMPORT)
            calls.put(new JSONObject().put("callee", call.getCallee()).put("arity", call.getDeclaredArity())
                    .put("kind", call.getKind().name()));
        return calls;
    }
    private static Call findCall(ModelUnit model, String name) {
        ArrayDeque<Node> pending = new ArrayDeque<>(); pending.add(model);
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (node instanceof Call call && call.getName().equals(name)) return call;
            pending.addAll(node.getChildren());
        }
        throw new AssertionError("Expected synthetic call fixture not found");
    }
    private static int forgedNestedCalls() throws Exception {
        int rejected = 0;
        String graph = "module library_fixture\nopen util/graph[Node] as g\nsig Node {}\n"
                + "pred target[r: Node -> Node] { some g/rel/dom[r] }\n";
        for (boolean mapped : List.of(false, true)) {
            for (String renamed : List.of("g/rel/missing", "g/rel/private", "g/other/dom", "g/rel/ran")) {
                begin();
                try {
                    CompModule module = WorkerSafety.parse(graph);
                    ModelUnit model = mapped ? MASGVisitor.modelWithSourceMap(module) : new ModelUnit(null, module);
                    findCall(model, "g/rel/dom").setName(renamed);
                    try { new MASGVisitor(new GlobalVariables(), Set.of("target"), module).visit(model, null); }
                    catch (IllegalStateException expected) { rejected++; continue; }
                    throw new AssertionError("Forged nested call was admitted: " + renamed);
                } finally { WorkerSafety.end(); }
            }
            begin();
            try {
                CompModule module = WorkerSafety.parse(graph);
                ModelUnit model = mapped ? MASGVisitor.modelWithSourceMap(module) : new ModelUnit(null, module);
                findCall(model, "g/rel/dom").setArguments(List.of());
                try { new MASGVisitor(new GlobalVariables(), Set.of("target"), module).visit(model, null); }
                catch (IllegalStateException expected) { rejected++; continue; }
                throw new AssertionError("Wrong-arity nested call was admitted");
            } finally { WorkerSafety.end(); }
        }
        // natural's ordering import is private. A synthetic wrapper must not
        // manufacture access merely because its library name is allowlisted.
        for (boolean mapped : List.of(false, true)) {
            begin();
            try {
                CompModule module = WorkerSafety.parse(environment("natural")
                        + "pred target[n: lib/Natural] { some lib/inc[n] }\n");
                ModelUnit model = mapped ? MASGVisitor.modelWithSourceMap(module) : new ModelUnit(null, module);
                Call call = findCall(model, "lib/inc");
                call.setName("lib/ord/first"); call.setArguments(List.of());
                try { new MASGVisitor(new GlobalVariables(), Set.of("target"), module).visit(model, null); }
                catch (IllegalStateException expected) { rejected++; continue; }
                throw new AssertionError("Forged private import was admitted");
            } finally { WorkerSafety.end(); }
        }
        return rejected;
    }
    private static JSONObject controls() throws Exception {
        JSONObject output = new JSONObject();
        String graph = "module library_fixture\nopen util/graph[Node] as g\nsig Node {}\n";
        String relation = environment("relation");
        String ordering = environment("ordering");
        String seqrel = "module library_fixture\nopen util/seqrel[Node] as seqs\nsig Node {}\n";
        String seq = "module library_fixture\nsig Node {}\n";
        output.put("transitive-relation", compare(graph, "pred target[r: Node -> Node] {\n",
                "some g/rel/dom[r]", "some g/rel/dom[r]"));
        output.put("transitive-ordering", compare(seqrel, "pred target {\n",
                "some seqs/ord/first", "some seqs/ord/first"));
        output.put("nested-formula", compare(graph, "pred target[r: Node -> Node] {\n",
                "g/rel/symmetric[r]", "g/rel/symmetric[r]"));
        String two = "module library_fixture\nopen util/seqrel[A] as left\n"
                + "open util/seqrel[B] as right\nsig A,B {}\n";
        output.put("nested-instantiation", compare(two, "pred target {\n",
                "some left/ord/first", "some right/ord/first"));
        String aliases = "module library_fixture\nopen util/graph[Node] as g1\n"
                + "open util/graph[Node] as g2\nsig Node {}\n";
        output.put("nested-alias-synonyms", compare(aliases, "pred target[r: Node -> Node] {\n",
                "some g1/rel/dom[r]", "some g2/rel/dom[r]"));
        output.put("implicit-sequence", compare(seq, "pred target[s: seq Node] {\n",
                "some seq/elems[s]", "some seq/elems[s]"));
        output.put("sequence-relation-parameter", compare(seq, "pred target[s: seq Node] {\n",
                "some seq/first[s]", "some seq/last[s]"));
        output.put("changed-relation-callee", compare(relation, "pred target[r: univ -> univ] {\n",
                "some lib/dom[r]", "some lib/ran[r]"));
        output.put("changed-ordering-arguments", compare(ordering, "pred target[a,b: Node] {\n",
                "lib/lt[a,b]", "lib/lt[b,a]"));
        output.put("changed-boolean-callee", compare(environment("boolean"), "pred target[b: lib/Bool] {\n",
                "lib/isTrue[b]", "lib/isFalse[b]"));
        output.put("sequence-upstream-invalid", compare(environment("sequence"), "pred target {\n",
                "lib/noDuplicates[]", "lib/noDuplicates[]"));
        output.put("time-macro", compare("module library_fixture\nopen util/time as clock\nsig Node {}\n",
                "pred target[r: Node -> clock/Time] {\n", "r in clock/dynamicSet[Node]", "r in clock/dynamicSet[Node]"));
        output.put("after-invalid", compare(environment("ternary"), "pred target[r: univ -> univ -> univ] {\n",
                "some lib/mid[r]", "some lib/mid[r]"));
        return output;
    }
}

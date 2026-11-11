package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.ast.Type;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.metric.RepairView;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;
import parser.util.AlloyUtil;

/** Read-only canonical graph pairs for supervised graph patch construction. */
public final class CanonicalPairExport {
    private final Map<String, String> symbols = new LinkedHashMap<>();
    private final Set<String> ambiguous = new HashSet<>();
    private final JSONArray provenance = new JSONArray();

    private static JSONArray list(Object... values) { return new JSONArray(Arrays.asList(values)); }
    private static JSONObject node(JSONArray label, JSONArray children) {
        return new JSONObject().put("label", label).put("children", children);
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private void alias(String original, String generic) {
        if (ambiguous.contains(original)) return;
        String old = symbols.putIfAbsent(original, generic);
        if (old != null && !old.equals(generic)) {
            symbols.remove(original);
            ambiguous.add(original);
        }
    }
    private String name(String value) {
        return value == null ? "" : symbols.getOrDefault(value, value);
    }
    private JSONArray type(Type type) {
        JSONArray products = new JSONArray();
        for (var product : type.fold()) {
            JSONArray columns = new JSONArray();
            for (Sig sig : product) columns.put(name(sig.label));
            products.put(columns);
        }
        return products;
    }
    private JSONObject matrix(RepairView.Node value) {
        JSONArray children = new JSONArray();
        for (var child : value.children()) children.put(matrix(child));
        for (var child : value.certifiedAlternatives()) children.put(matrix(child));
        provenance.put(new JSONObject().put("kind", "matrix").put("operator", value.operator())
                .put("semanticPayload", value.semanticPayload() == null ? "" : value.semanticPayload()));
        // Bound uses have explicit coordinates. Local lexical identities remain explicit.
        Object variable = value.isVariable() ? (value.bindingIndex() >= 0
                ? "$" + value.bindingIndex() : value.lexicalVariable()) : "";
        return node(list(value.operator(), value.containerKind().name(),
                name(value.payload()), variable, value.children().size(), value.orderInsensitive(),
                value.semanticPayload() == null ? "" : value.semanticPayload()), children);
    }
    private JSONObject temporal(RepairView.TemporalNode value) {
        JSONArray children = new JSONArray();
        for (var child : value.children()) children.put(temporal(child));
        return node(list("TEMPORAL", value.label()), children);
    }
    private JSONObject view(RepairView value) {
        JSONArray children = new JSONArray().put(temporal(value.temporalRoot()));
        for (var phase : value.phases()) {
            JSONArray terms = new JSONArray();
            for (var binding : phase.bindings()) {
                var d = binding.declaration();
                provenance.put(new JSONObject().put("kind", "binding").put("domain", d.certifiedDomain())
                        .put("ownerContext", binding.ownerContext()).put("orbit", binding.certifiedOrbit()));
                terms.put(node(list("BINDING", d.quantifier(), name(d.type()), d.cardinality(),
                        d.disjointnessClass(), binding.role().name(), binding.ownerPhase(), binding.coordinate(),
                        d.exchangeClass(), new JSONArray(d.dependencies()), binding.bindingPath(),
                        binding.ownerContext(), d.certifiedDomain()), new JSONArray()));
            }
            terms.put(matrix(phase.matrix()));
            children.put(node(list("PHASE", phase.bindings().size()), terms));
        }
        return node(list("ROOT"), children);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("source output-json");
        Path path = Path.of(args[0]).toRealPath(), output = Path.of(args[1]);
        byte[] bytes = Files.readAllBytes(path);
        var module = AlloyUtil.compileAlloyModule(path.toString());
        if (module == null) throw new IllegalArgumentException("PARSE_FAILED");
        ModelUnit model = new ModelUnit(null, module);
        Map<String, Predicate> predicates = new LinkedHashMap<>();
        for (Predicate p : model.getPredDeclList()) predicates.put(p.getName(), p);
        String fileName = path.getFileName().toString();
        String preferred = fileName.substring(fileName.lastIndexOf('_') + 1, fileName.length() - 4);
        String[] pair = DatasetConventions.findPredicatePairNames(preferred, predicates);
        if (pair == null) throw new IllegalArgumentException("NO_PAIR");
        if (DatasetConventions.sameRawAst(predicates.get(pair[0]).getBody(), predicates.get(pair[1]).getBody())) {
            throw new IllegalArgumentException("AST_IDENTICAL");
        }
        CanonicalPairExport export = new CanonicalPairExport();
        int sigIndex = 0, fieldIndex = 0;
        for (Sig sig : module.getAllReachableSigs()) {
            if (sig.builtin) continue;
            String id = "$sig" + sigIndex++;
            export.alias(sig.label, id);
            export.alias(sig.label.replaceFirst("^this/", ""), id);
            for (Sig.Field field : sig.getFields()) {
                String fid = "$rel" + fieldIndex++;
                export.alias(field.label, fid);
                export.alias(sig.label + "." + field.label, fid);
            }
        }
        JSONArray schema = new JSONArray();
        for (Sig sig : module.getAllReachableSigs()) {
            if (sig.builtin) continue;
            JSONArray parents = new JSONArray(), fields = new JSONArray();
            if (sig instanceof Sig.PrimSig p && p.parent != null) parents.put(export.name(p.parent.label));
            if (sig instanceof Sig.SubsetSig s) for (Sig p : s.parents) parents.put(export.name(p.label));
            for (Sig.Field field : sig.getFields()) fields.put(node(list("FIELD", export.name(sig.label + "." + field.label),
                    export.type(field.type())), new JSONArray()));
            schema.put(node(list("SIG", export.name(sig.label), parents, sig instanceof Sig.SubsetSig,
                    sig.isAbstract != null, sig.isOne != null ? "ONE" : sig.isLone != null ? "LONE" : sig.isSome != null ? "SOME" : "SET"), fields));
        }
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(model, null);
        var left = CanonicalAlloyPipeline.prepare(visitor.getForest().get(visitor.getForestId(pair[0])));
        var right = CanonicalAlloyPipeline.prepare(visitor.getForest().get(visitor.getForestId(pair[1])));
        JSONObject sourceGraph = export.view(left.repairView());
        JSONObject targetGraph = export.view(right.repairView());
        JSONObject result = new JSONObject().put("schema", "canonical-graph-pair-v1")
                .put("sourceHash", hash(bytes)).put("student", pair[0]).put("oracle", pair[1])
                .put("context", node(list("SCHEMA"), schema)).put("source", sourceGraph).put("target", targetGraph)
                .put("sourceObservation", left.canonicalObservation().digest())
                .put("targetObservation", right.canonicalObservation().digest())
                .put("proofMetadata", export.provenance).put("symbolMap", export.symbols)
                .put("pipeline", CanonicalAlloyPipeline.PIPELINE_VERSION)
                .put("equalityAuthority", "NONE: learning projection only");
        Files.writeString(output, result.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}

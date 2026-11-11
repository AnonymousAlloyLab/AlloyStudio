package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import parser.ast.nodes.ModelUnit;

/** Fixed-choice native replay against fresh incident endpoints, not public admission. */
public final class ProductShorterReplayTest {
    static final Path SOURCE = Path.of("classified-data/classroom_fol/both/2S5sD2BA5mvLdj46b_inv12.als");
    private static int checks;
    private static void check(boolean value, String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    private static void reject(Runnable action, String why) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static void require(boolean value) {
        if (!value) throw new IllegalArgumentException("Outside fixed one-phase replay slice");
    }
    private static void noChoices(Node n) {
        require(n.certifiedAlternatives().isEmpty());
        for (Node child : n.children()) noChoices(child);
    }
    private static List<DeclarationReplay.Atom> atoms(Phase phase, JSONArray ordered) {
        require(ordered.length() == phase.quantifiers().size());
        Set<Integer> seen = new HashSet<>();
        List<DeclarationReplay.Atom> result = new ArrayList<>();
        // This binds the archived order to actual declaration occurrences. It
        // does not certify admission of that permutation as a public free edit.
        for (Object item : ordered) {
            JSONArray id = new JSONArray(((JSONObject)item).getString("occurrence"));
            int ordinal = id.getInt(4);
            require(ordinal >= 0 && ordinal < phase.quantifiers().size() && seen.add(ordinal));
            Binding binding = phase.bindings().stream().filter(b -> b.role() == BindingRole.MATRIX
                    && b.ownerPhase() == 0 && b.ordinal() == ordinal).findFirst().orElseThrow();
            Declaration d = phase.quantifiers().get(ordinal);
            require(d.sameCertifiedPayload(binding.declaration()) && d.dependencies().isEmpty());
            String occurrence = new JSONArray().put(false).put(binding.ownerPhase()).put(binding.ownerContext())
                    .put(binding.coordinate()).put(ordinal).toString();
            String admission = new JSONArray().put(d.certifiedDomain()).put(d.exchangeClass())
                    .put(new JSONArray(d.dependencies())).put(String.valueOf(d.observationType()))
                    .put(String.valueOf(d.observationDomain())).toString();
            result.add(new DeclarationReplay.Atom(occurrence,List.of(d.quantifier(),d.type(),d.cardinality(),
                    Integer.toString(d.disjointnessClass())),admission));
        }
        return result;
    }
    private static MatrixReplay<Node> checker(int[] mapping) {
        return new MatrixReplay<>(new MatrixReplay.NativeCost<Node>() {
            public String label(Node n) {
                return new JSONArray().put(n.operator()).put(n.semanticPayload()).put(n.bindingIndex())
                        .put(String.valueOf(n.lexicalVariable())).put(n.containerKind().toString())
                        .put(n.orderInsensitive()).put(Arrays.toString(mapping)).toString();
            }
            public List<Node> children(Node n) { return n.children(); }
            public List<Node> alternatives(Node n) { return List.of(); }
            public boolean unordered(Node a, Node b) {
                return a.operator().equals(b.operator()) && a.orderInsensitive() && b.orderInsensitive();
            }
            public int size(Node n) {
                if (n == null) return 0;
                int sum = 1; for (Node c : n.children()) sum = Math.addExact(sum,size(c)); return sum;
            }
            public int update(Node a, Node b) {
                if (!a.operator().equals(b.operator())) return 1;
                if (!a.isVariable()) return Objects.equals(a.semanticPayload(),b.semanticPayload()) ? 0 : 1;
                if (a.bindingIndex() < 0 || b.bindingIndex() < 0)
                    return a.bindingIndex() == b.bindingIndex()
                            && Objects.equals(a.lexicalVariable(),b.lexicalVariable()) ? 0 : 1;
                require(a.bindingIndex() < mapping.length);
                return mapping[a.bindingIndex()] == b.bindingIndex() ? 0 : 1;
            }
            public int distance(Node a, Node b) { throw new AssertionError("Replay cannot search for distance"); }
        });
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("[ARCHIVED_RECEIPT]");
        byte[] bytes = Files.readAllBytes(SOURCE);
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        List<Canonical.Prepared> prepared = new ArrayList<>();
        for (String name : List.of("inv12","inv12c")) {
            var visitor = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            prepared.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        JSONObject report = args.length == 0 ? DistanceReplayDiagnostics.capture(prepared.get(0),prepared.get(1))
                : new JSONObject(Files.readString(Path.of(args[0])));
        if (args.length != 0) check(report.getString("sourceSha256").equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))), "frozen source hash");
        var left = CanonicalAlloyPipeline.prepare(prepared.get(0)).repairView();
        var right = CanonicalAlloyPipeline.prepare(prepared.get(1)).repairView();
        require(left.phases().size() == 1 && right.phases().size() == 1);
        Phase a = left.phases().get(0), b = right.phases().get(0);
        for (Phase phase : List.of(a,b)) {
            require(phase.bindings().size() == phase.quantifiers().size());
            for (Binding binding : phase.bindings()) require(binding.role() == BindingRole.MATRIX
                    && binding.ownerPhase() == 0 && binding.declaration().dependencies().isEmpty());
            noChoices(phase.matrix());
        }
        JSONObject arm = report.getJSONObject("certified");
        JSONArray phases = arm.getJSONArray("phases");
        check(phases.length() == 1 && phases.getJSONObject(0).getInt("phase") == 0, "phase identity");
        JSONArray map = phases.getJSONObject(0).getJSONArray("alphaMapping");
        check(map.similar(arm.getJSONArray("selectedGlobalMapping")), "single-phase mapping linkage");
        int[] mapping = new int[map.length()];
        require(mapping.length == a.bindings().size());
        Set<Integer> targets = new HashSet<>();
        for (int i=0; i<mapping.length; i++) {
            mapping[i] = map.getInt(i);
            require(mapping[i] >= -1 && mapping[i] < b.bindings().size());
            if (mapping[i] >= 0) require(targets.add(mapping[i])
                    && a.bindings().get(i).declaration().sameCertifiedPayload(b.bindings().get(mapping[i]).declaration()));
        }
        check(Arrays.equals(mapping,new int[]{0,-1,-1,-1}), "archived scoped mapping");
        JSONObject receipt = phases.getJSONObject(0).getJSONObject("receipt");
        int matrix = checker(mapping).verify(a.matrix(),b.matrix(),receipt);
        check(matrix == 11, "shorter matrix11 independently replayed");
        reject(() -> checker(new int[]{-1,-1,-1,-1}).verify(a.matrix(),b.matrix(),receipt), "mapping substitution");
        reject(() -> checker(mapping).verify(b.matrix(),a.matrix(),receipt), "endpoint substitution");
        reject(() -> checker(mapping).verify(a.matrix(),b.matrix(),new JSONObject(receipt.toString()).put("cost",10)), "charge substitution");
        JSONObject decl = arm.getJSONObject("declarationSlice");
        JSONArray components = decl.getJSONArray("components");
        check(components.length() == 2, "parameter/quantifier coverage");
        check(DeclarationReplay.verify(List.of(),List.of(),components.getJSONObject(0).getJSONObject("receipt")) == 0,
                "empty parameters");
        JSONObject quant = components.getJSONObject(1).getJSONObject("receipt");
        var as = atoms(a,quant.getJSONArray("source")); var bs = atoms(b,quant.getJSONArray("target"));
        int declarations = DeclarationReplay.verify(as,bs,quant);
        check(declarations == 3, "three declaration deletions");
        JSONObject changedOwner = new JSONObject(quant.toString());
        changedOwner.getJSONArray("source").getJSONObject(0).put("occurrence","[false,9,\"\",0,1]");
        reject(() -> DeclarationReplay.verify(as,bs,changedOwner), "foreign binder owner rejected");
        check(arm.getJSONObject("selectedQuantifierCorrespondence").isEmpty(), "no paid correspondence");
        JSONObject temporal = arm.getJSONObject("temporalSlice");
        check(TemporalReplay.verify(left.temporalRoot(),right.temporalRoot(),TemporalNode::label,TemporalNode::children,temporal) == 0,
                "no temporal edits");
        check(PhaseReplay.verifyDiagnosticOrder(temporal,decl,phases,arm.getJSONArray("orderedDiagnosticActions")) == 14
                && matrix+declarations == arm.getInt("distance"), "native total14 and diagnostic ordering");
        check(!report.getBoolean("complete") && !report.getBoolean("publicSequenceLinked")
                && !report.getBoolean("sourceCertificateWireReplayChecked"), "full alignment remains blocked");
        System.out.println("ProductShorterReplayTest: "+checks+" checks passed; native14 replayed, full alignment BLOCKED");
    }
}

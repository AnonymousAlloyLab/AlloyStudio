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
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** Fixed-choice native replay against fresh incident endpoints, not public admission. */
public final class PaidDeclarationShorterReplayTest {
    static final Path SOURCE = Path.of("classified-data/classroom_fol/both/5dmBQdag4unR6mf3t_inv14.als");
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
    private static List<DeclarationReplay.Atom> atoms(Phase phase, JSONArray ordered,int[] permutation) {
        require(ordered.length() == phase.quantifiers().size());
        Set<Integer> seen = new HashSet<>();
        List<DeclarationReplay.Atom> result = new ArrayList<>();
        var canonical=new ArrayList<Integer>();
        for(int i=0;i<phase.quantifiers().size();i++) canonical.add(i);
        require(phase.quantifiers().stream().map(Declaration::quantifier).distinct().count()==1);
        canonical.sort(Comparator.comparing(i -> phase.quantifiers().get(i).type()));
        for (Object item : ordered) {
            JSONArray id = new JSONArray(((JSONObject)item).getString("occurrence"));
            int ordinal = id.getInt(4);
            require(ordinal >= 0 && ordinal < phase.quantifiers().size() && seen.add(ordinal));
            require(ordinal==permutation[canonical.get(result.size())]);
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

    private static int[] permutation(JSONObject arm,String side,int n) {
        int[] result=new int[n]; for(int i=0;i<n;i++) result[i]=i;
        if(arm.has("selectedDeclarationPermutation")) {
            var array=arm.getJSONObject("selectedDeclarationPermutation").getJSONArray(side);
            require(array.length()==n);
            for(int i=0;i<n;i++) result[i]=array.getInt(i);
        }
        return result;
    }
    private static void unfolding(FiniteUnfoldingTree tree) {
        CertificateVerifier.verify(tree.shapeCoherence());
        for(var child:tree.invocationChildren()) unfolding(child);
    }
    private static RepairView checkedProjection(Canonical.Prepared source,Canonical.Prepared foreign,int[] permutation) {
        var forms=source.normalizedForms();
        var evidence=TheoryAlloyAdapter.adapt(forms,source.semanticProfile());
        evidence.requireRepairProjectionSources(forms);
        reject(()->RepairProjection.project(evidence,foreign.normalizedForms()),"foreign source cannot reuse certificate");
        var artifact=evidence.semanticArtifact();
        List<TypedEqualityCertificate> roots=new ArrayList<>();
        roots.addAll(artifact.flatConstructions()); roots.addAll(artifact.containerConstructions());
        roots.addAll(artifact.dependentChainConstructions()); roots.addAll(artifact.guardedJoinConstructions());
        roots.addAll(artifact.binderOccurrenceCertificates());
        for(var proof:roots) CertificateVerifier.verify(proof);
        for(var tree:artifact.unfoldings()) unfolding(tree);
        for(var entry:evidence.dependentChainSourceBindings().entrySet()) {
            var binding=entry.getValue(); binding.requireMatches(entry.getKey());
            check(artifact.dependentChainConstructions().stream().anyMatch(c->c==binding.certificate())
                    && binding.sourceOccurrenceCommitment().equals(binding.certificate().sourceOccurrenceCommitment()),
                    "actual source-linked chain premise");
        }
        if(!artifact.flatConstructions().isEmpty())
            reject(()->artifact.withFlatConstructions(List.of()),"missing flat source premise");
        if(!artifact.dependentChainConstructions().isEmpty())
            reject(()->artifact.withDependentChainConstructions(List.of()),"missing chain source premise");
        var descriptor=evidence.phaseBinderDescriptors().get(0);
        var coordinates=evidence.phaseSourceCoordinates().get(0);
        var view=RepairProjection.project(evidence,forms);
        var qs=forms.get(0).getMatrixQuantiVars();
        require(permutation.length==qs.size() && coordinates.size()==qs.size());
        var action=new LinkedHashMap<TypedSlot,TypedSlot>();
        for(var slot:descriptor.boundContext()) action.put(slot,slot);
        Set<Integer> seen=new HashSet<>();
        for(int i=0;i<qs.size();i++) {
            int j=permutation[i]; require(j>=0 && j<qs.size() && seen.add(j));
            var from=descriptor.coordinates().get(coordinates.get(i));
            var to=descriptor.coordinates().get(coordinates.get(j));
            var d=view.phases().get(0).quantifiers().get(i);
            check(from.quantifier().equals(qs.get(i).getQuantifier().name())
                    && from.multiplicity().equals(qs.get(i).getCardinality().name())
                    && from.type().equals(d.observationType()) && from.domain().toString().equals(d.certifiedDomain())
                    && from.exchangeClass()==d.exchangeClass(),"original typed binder coordinate "+i);
            action.put(from.canonicalSlot(),to.canonicalSlot());
        }
        for(var proof:descriptor.automorphisms().generatorCertificates()) CertificateVerifier.verify(proof);
        check(descriptor.hasCertifiedAutomorphisms()
                && descriptor.automorphisms().contains(TypedPermutation.of(descriptor.boundContext(),action)),
                "selected whole permutation belongs to actual source-certified group");
        return view;
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("[ARCHIVED_RECEIPT]");
        byte[] bytes = Files.readAllBytes(SOURCE);
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        List<Canonical.Prepared> prepared = new ArrayList<>();
        for (String name : List.of("inv14","inv14c")) {
            var visitor = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            prepared.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        JSONObject report = args.length == 0 ? DistanceReplayDiagnostics.capture(prepared.get(0),prepared.get(1))
                : new JSONObject(Files.readString(Path.of(args[0])));
        boolean reverse=args.length!=0 && report.getString("leftPredicate").equals("inv14c");
        if(reverse) Collections.reverse(prepared);
        if (args.length != 0) check(report.getString("sourceSha256").equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))), "frozen source hash");
        JSONObject arm = report.getJSONObject("certified");
        int[] lp=permutation(arm,"left",reverse?2:4),rp=permutation(arm,"right",reverse?4:2);
        int[] originalPermutation=reverse?rp:lp;
        boolean orbit=!Arrays.equals(originalPermutation,new int[]{0,1,2,3});
        check(Arrays.equals(originalPermutation,orbit?new int[]{3,1,2,0}:new int[]{0,1,2,3})
                && Arrays.equals(reverse?lp:rp,new int[]{0,1}),"exact investigated tie or archived ordering");
        var left = checkedProjection(prepared.get(0),prepared.get(1),lp);
        var right = checkedProjection(prepared.get(1),prepared.get(0),rp);
        reject(()->checkedProjection(prepared.get(reverse?1:0),prepared.get(reverse?0:1),new int[]{2,1,0,3}),
                "forged Person/Group source permutation");
        require(left.phases().size() == 1 && right.phases().size() == 1);
        Phase a = left.phases().get(0), b = right.phases().get(0);
        for (Phase phase : List.of(a,b)) {
            require(phase.bindings().size() == phase.quantifiers().size());
            for (Binding binding : phase.bindings()) require(binding.role() == BindingRole.MATRIX
                    && binding.ownerPhase() == 0 && binding.declaration().dependencies().isEmpty());
            noChoices(phase.matrix());
        }
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
                    );
        }
        int[] expected=reverse?(orbit?new int[]{3,0}:new int[]{2,0}):
                (orbit?new int[]{1,-1,-1,0}:new int[]{1,-1,0,-1});
        check(Arrays.equals(mapping,expected), "source-certified attaining mapping");
        JSONObject receipt = phases.getJSONObject(0).getJSONObject("receipt");
        int matrix = checker(mapping).verify(a.matrix(),b.matrix(),receipt);
        check(matrix == 25, "shorter matrix25 independently replayed");
        int[] absent=new int[mapping.length]; Arrays.fill(absent,-1);
        reject(() -> checker(absent).verify(a.matrix(),b.matrix(),receipt), "mapping substitution");
        reject(() -> checker(mapping).verify(b.matrix(),a.matrix(),receipt), "endpoint substitution");
        reject(() -> checker(mapping).verify(a.matrix(),b.matrix(),new JSONObject(receipt.toString()).put("cost",10)), "charge substitution");
        JSONObject decl = arm.getJSONObject("declarationSlice");
        JSONArray components = decl.getJSONArray("components");
        check(components.length() == 2, "parameter/quantifier coverage");
        check(DeclarationReplay.verify(List.of(),List.of(),components.getJSONObject(0).getJSONObject("receipt")) == 0,
                "empty parameters");
        JSONObject quant = components.getJSONObject(1).getJSONObject("receipt");
        var as = atoms(a,quant.getJSONArray("source"),lp); var bs = atoms(b,quant.getJSONArray("target"),rp);
        if(orbit) reject(()->atoms(reverse?b:a,quant.getJSONArray(reverse?"target":"source"),new int[]{0,1,2,3}),
                "omitted source permutation cannot replay the candidate ordering");
        int declarations = DeclarationReplay.verify(as,bs,quant);
        check(declarations == 4, "two deletions and two paid updates");
        JSONObject changedOwner = new JSONObject(quant.toString());
        changedOwner.getJSONArray("source").getJSONObject(0).put("occurrence","[false,9,\"\",0,1]");
        reject(() -> DeclarationReplay.verify(as,bs,changedOwner), "foreign binder owner rejected");
        JSONObject paid = arm.getJSONObject("selectedQuantifierCorrespondence");
        JSONObject expectedPaid=reverse?new JSONObject().put("0",orbit?3:2).put("1",0):
                new JSONObject().put("0",1).put(orbit?"3":"2",0);
        check(paid.similar(expectedPaid), "selected paid correspondence");
        Map<Integer,Integer> replayed = new HashMap<>();
        for (Object item : quant.getJSONArray("operations")) {
            JSONObject op = (JSONObject)item;
            if (op.getString("kind").equals("update") && op.getInt("cost") == 1) {
                int sourceOrdinal = new JSONArray(op.getJSONObject("source").getString("occurrence")).getInt(4);
                int targetOrdinal = new JSONArray(op.getJSONObject("target").getString("occurrence")).getInt(4);
                require(mapping[sourceOrdinal] == targetOrdinal);
                replayed.put(sourceOrdinal,targetOrdinal);
            }
        }
        check(new JSONObject(replayed).similar(paid), "paid mapping carried only by replayed declaration updates");
        Phase original=reverse?b:a,target=reverse?a:b;
        check(original.quantifiers().get(0).quantifier().equals("SOME")
                && target.quantifiers().get(1).quantifier().equals("ALL"), "s to p changes quantifier, not free alpha");
        check(original.quantifiers().get(orbit?3:2).type().equals(orbit?"Person":"Group")
                && target.quantifiers().get(0).type().equals("Class"), "g or t to c type update remains paid");
        JSONObject temporal = arm.getJSONObject("temporalSlice");
        check(TemporalReplay.verify(left.temporalRoot(),right.temporalRoot(),TemporalNode::label,TemporalNode::children,temporal) == 0,
                "no temporal edits");
        check(PhaseReplay.verifyDiagnosticOrder(temporal,decl,phases,arm.getJSONArray("orderedDiagnosticActions")) == 29
                && matrix+declarations == arm.getInt("distance"), "native total29 and diagnostic ordering");
        check(!report.getBoolean("complete") && !report.getBoolean("publicSequenceLinked")
                && !report.getBoolean("sourceCertificateWireReplayChecked"), "full alignment remains blocked");
        System.out.println("PaidDeclarationShorterReplayTest: "+checks+" checks passed; native29 replayed, full alignment BLOCKED");
    }
}

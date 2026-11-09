package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import parser.ast.nodes.ModelUnit;
import is.fivefivefive.CanDis.theory.*;

/** Fixed-choice native replay against fresh incident endpoints, not public admission. */
public final class GroupedPaidDeclarationShorterReplayTest {
    static final Path SOURCE = Path.of("classified-data/classroom_fol/both/8dTfKqTs26w6fL87K_inv15.als");
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
        // Check the actual independent block and its deterministic typed order.
        List<Integer> admittedOrder=new ArrayList<>();
        for(int i=0;i<phase.quantifiers().size();i++) admittedOrder.add(i);
        for(int start=0;start<admittedOrder.size();) {
            String kind=phase.quantifiers().get(start).quantifier();
            int end=start+1;
            if(kind.equals("ALL") || kind.equals("SOME")) {
                while(end<admittedOrder.size() && phase.quantifiers().get(end).quantifier().equals(kind)) {
                    require(phase.quantifiers().get(end).exchangeClass()==phase.quantifiers().get(start).exchangeClass());
                    end++;
                }
                admittedOrder.subList(start,end).sort(Comparator.comparing(i -> phase.quantifiers().get(i).type()));
            }
            start=end;
        }
        for (Object item : ordered) {
            JSONArray id = new JSONArray(((JSONObject)item).getString("occurrence"));
            int ordinal = id.getInt(4);
            require(ordinal >= 0 && ordinal < phase.quantifiers().size() && seen.add(ordinal));
            require(ordinal == admittedOrder.get(result.size()));
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

    private static boolean satisfiable(edu.mit.csail.sdg.parser.CompModule module,String formula,int scope) throws Exception {
        var options=new A4Options(); options.solver=kodkod.engine.satlab.SATFactory.get("sat4j");
        return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,module.getAllReachableSigs(),
                new Command(false,scope,4,4,null,CompUtil.parseOneExpression_fromString(module,formula)),options).satisfiable();
    }

    private static void boundedSourceLaw(String original) throws Exception {
        var model=CompUtil.parseEverything_fromString(A4Reporter.NOP,original+"""
                pred splitSource {
                  some p1:Person | some p2:Person | some p3:Person |
                    p1->p2 in Tutors and p2->p3 in Tutors and p1 in Teacher
                }
                """);
        for(int scope=0;scope<=4;scope++)
            check(!satisfiable(model,"not (inv15 iff splitSource)",scope),"grouped SOME law scope "+scope);
        check(satisfiable(model,"no Person and not inv15 and inv15c",0),
                "empty carrier preserves distinct SOME/ALL semantics");
        check(satisfiable(model,"under",3) || satisfiable(model,"over",3),
                "repair endpoints are not asserted semantically equal");
    }

    private static void unfolding(FiniteUnfoldingTree tree) {
        CertificateVerifier.verify(tree.shapeCoherence());
        for (var child : tree.invocationChildren()) unfolding(child);
    }
    private static RepairView checkedProjection(Canonical.Prepared source, Canonical.Prepared foreign) {
        var forms = source.normalizedForms();
        var evidence = TheoryAlloyAdapter.adapt(forms,source.semanticProfile());
        evidence.requireRepairProjectionSources(forms);
        reject(() -> RepairProjection.project(evidence,foreign.normalizedForms()),"foreign source occurrence");
        var artifact = evidence.semanticArtifact();
        List<TypedEqualityCertificate> roots = new ArrayList<>();
        roots.addAll(artifact.flatConstructions());
        roots.addAll(artifact.containerConstructions());
        roots.addAll(artifact.dependentChainConstructions());
        roots.addAll(artifact.guardedJoinConstructions());
        roots.addAll(artifact.binderOccurrenceCertificates());
        for (var proof : roots) CertificateVerifier.verify(proof);
        for (var tree : artifact.unfoldings()) unfolding(tree);
        check(!roots.isEmpty(),"actual construction evidence present");
        for (var entry : evidence.dependentChainSourceBindings().entrySet()) {
            var binding=entry.getValue();
            binding.requireMatches(entry.getKey());
            check(artifact.dependentChainConstructions().stream().anyMatch(c -> c==binding.certificate())
                    && binding.sourceOccurrenceCommitment().equals(binding.certificate().sourceOccurrenceCommitment()),
                    "exact occurrence-linked chain certificate retained");
            reject(() -> binding.requireMatches(foreign.normalizedForms().get(0).getMatrixEGraph()),
                    "foreign occurrence cannot reuse typed chain certificate");
        }
        if (!artifact.flatConstructions().isEmpty())
            reject(() -> artifact.withFlatConstructions(List.of()),"missing original flat source premise");
        if (!artifact.dependentChainConstructions().isEmpty())
            reject(() -> artifact.withDependentChainConstructions(List.of()),"missing original dependent-chain premise");
        var view = RepairProjection.project(evidence,forms);
        var descriptor = evidence.phaseBinderDescriptors().get(0);
        var coordinates = evidence.phaseSourceCoordinates().get(0);
        var qs = forms.get(0).getMatrixQuantiVars();
        check(coordinates.size() == qs.size(),"source declaration coordinate coverage");
        for (int i=0;i<qs.size();i++) {
            var q=qs.get(i); var c=descriptor.coordinates().get(coordinates.get(i));
            var d=view.phases().get(0).quantifiers().get(i);
            check(c.quantifier().equals(q.getQuantifier().name())
                    && c.multiplicity().equals(q.getCardinality().name())
                    && c.dependencies().isEmpty()
                    && c.exchangeClass()==d.exchangeClass()
                    && c.domain().toString().equals(d.certifiedDomain())
                    && c.type().equals(d.observationType()),"actual typed binder admission " + i);
        }
        for (var proof : descriptor.automorphisms().generatorCertificates()) CertificateVerifier.verify(proof);
        for (var permutation : descriptor.automorphisms().elements())
            for (var c : descriptor.coordinates()) {
                var target=descriptor.coordinates().stream()
                        .filter(t -> t.canonicalSlot().equals(permutation.apply(c.canonicalSlot()))).findFirst().orElseThrow();
                check(c.exchangeClass()==target.exchangeClass() && c.quantifier().equals(target.quantifier())
                        && c.type().equals(target.type()),"no free mixed-block exchange");
            }
        check(evidence.localBinderDescriptors().isEmpty(),"no local-binder admission involved");
        if(qs.size()==3) {
            check(qs.stream().map(q -> q.getQuantifier().name()).toList().equals(List.of("SOME","SOME","SOME")),
                    "all three actual grouped slots are existential");
            check(qs.stream().map(q -> q.getBindingPath()).distinct().count()==1
                    && qs.stream().map(q -> q.getDeBruijnKey()).distinct().count()==3,
                    "one declaration owner, three distinct source slots");
            for(int i=0;i<3;i++) {
                check(coordinates.get(i)==i && descriptor.coordinates().get(i).exchangeClass()
                        ==descriptor.coordinates().get(0).exchangeClass(),
                        "actual independent grouped source coordinates "+i);
                System.out.println("grouped slot "+i+": "+qs.get(i).getDeBruijnKey()
                        +" aliases="+qs.get(i).getOriginalNames());
            }
        }
        System.out.println("source certificates: flat="+artifact.flatConstructions().size()
                +" container="+artifact.containerConstructions().size()
                +" chain="+artifact.dependentChainConstructions().size()
                +" join="+artifact.guardedJoinConstructions().size()
                +" binder="+artifact.binderOccurrenceCertificates().size()
                +"; typed independent-block declaration order; wire correspondence NOT claimed");
        return view;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("[ARCHIVED_RECEIPT]");
        byte[] bytes = Files.readAllBytes(SOURCE);
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        List<Canonical.Prepared> prepared = new ArrayList<>();
        for (String name : List.of("inv15","inv15c")) {
            var visitor = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            prepared.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        JSONObject archived=args.length==0 ? null : new JSONObject(Files.readString(Path.of(args[0])));
        boolean reverse=archived!=null && archived.getString("leftPredicate").equals("inv15c");
        if(reverse) Collections.reverse(prepared);
        JSONObject report = archived==null ? DistanceReplayDiagnostics.capture(prepared.get(0),prepared.get(1)) : archived;
        if (args.length != 0) check(report.getString("sourceSha256").equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))), "frozen source hash");
        var left = checkedProjection(prepared.get(0),prepared.get(1));
        var right = checkedProjection(prepared.get(1),prepared.get(0));
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
                    );
        }
        check(Arrays.equals(mapping,reverse ? new int[]{2} : new int[]{-1,-1,0}), "archived scoped mapping");
        JSONObject receipt = phases.getJSONObject(0).getJSONObject("receipt");
        int matrix = checker(mapping).verify(a.matrix(),b.matrix(),receipt);
        check(matrix == 15, "shorter matrix15 independently replayed");
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
        var as = atoms(a,quant.getJSONArray("source")); var bs = atoms(b,quant.getJSONArray("target"));
        Phase orderPhase=reverse?b:a;
        JSONArray reordered=new JSONArray(quant.getJSONArray(reverse?"target":"source").toString());
        Object first=reordered.get(0),last=reordered.get(reordered.length()-1);
        reordered.put(0,last).put(reordered.length()-1,first);
        reject(() -> atoms(orderPhase,reordered),"unadmitted original declaration reorder rejected");
        int declarations = DeclarationReplay.verify(as,bs,quant);
        check(declarations == 3, "two deletions/insertions and one paid update");
        JSONObject changedOwner = new JSONObject(quant.toString());
        changedOwner.getJSONArray("source").getJSONObject(0).put("occurrence","[false,9,\"\",0,1]");
        reject(() -> DeclarationReplay.verify(as,bs,changedOwner), "foreign binder owner rejected");
        JSONObject changedAdmission=new JSONObject(quant.toString());
        changedAdmission.getJSONArray("source").getJSONObject(0).put("admission","foreign domain");
        reject(() -> DeclarationReplay.verify(as,bs,changedAdmission),"foreign typed declaration premise rejected");
        JSONObject paid = arm.getJSONObject("selectedQuantifierCorrespondence");
        check(paid.similar(reverse ? new JSONObject().put("0",2)
                : new JSONObject().put("2",0)), "selected paid correspondence");
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
        Phase original=reverse?b:a, target=reverse?a:b;
        check(original.quantifiers().get(2).quantifier().equals("SOME")
                && target.quantifiers().get(0).quantifier().equals("ALL"),"paid p3-to-p kind update");
        check(original.quantifiers().get(2).type().equals("Person") && target.quantifiers().get(0).type().equals("Person"),
                "same carrier does not erase the paid SOME-to-ALL update");
        JSONObject temporal = arm.getJSONObject("temporalSlice");
        check(TemporalReplay.verify(left.temporalRoot(),right.temporalRoot(),TemporalNode::label,TemporalNode::children,temporal) == 0,
                "no temporal edits");
        check(PhaseReplay.verifyDiagnosticOrder(temporal,decl,phases,arm.getJSONArray("orderedDiagnosticActions")) == 18
                && matrix+declarations == arm.getInt("distance"), "native total18 and diagnostic ordering");
        check(!report.getBoolean("complete") && !report.getBoolean("publicSequenceLinked")
                && !report.getBoolean("sourceCertificateWireReplayChecked"), "full alignment remains blocked");
        boundedSourceLaw(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("GroupedPaidDeclarationShorterReplayTest: "+checks+" checks passed; native18 replayed, full alignment BLOCKED");
    }
}

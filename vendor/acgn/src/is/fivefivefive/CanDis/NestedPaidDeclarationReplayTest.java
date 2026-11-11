package is.fivefivefive.CanDis;

import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import is.fivefivefive.CanDis.theory.*;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.DistanceReplayDiagnostics;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.GraphType;
import parser.ast.nodes.ModelUnit;

/** Checks original nested-scope paid49 linkage without restoring implicit binder identity. */
public final class NestedPaidDeclarationReplayTest {
    public static final Path SOURCE=Path.of("classified-data/coursesOld/both/xJnitBpFv9K74WNvf_inv14.als");
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++; try { action.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static String variable(EGraphNode n) { return n.getAlphaName()==null?n.getSourceName():n.getAlphaName(); }
    static GraphType type(EGraphNode n) {
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE || n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL) return null;
        if(n.getMetatype()==EGraphNode.Metatype.BOOLEAN) return GraphType.BOOL;
        return AlloyTypeBridge.graphType(Objects.requireNonNull(n.getExactAlloyType()));
    }
    private static String atom(EGraphNode n) {
        if(n.getOpcode()==EGraphNode.Opcode.CALL) throw new IllegalArgumentException("Call outside incident slice");
        if(n.getSemanticIdentity()==null && n.getOpcode()==EGraphNode.Opcode.GLOBALBINDING
                && n.getSourceType()!=null && n.getSourceType().startsWith("FieldRelation")) {
            String name=Objects.requireNonNull(n.getSourceName());
            return "alloy/field/"+name.length()+":"+name+"/"+n.getExactAlloyType().stableString();
        }
        return n.getSemanticIdentity()==null?n.getSourceName():n.getSemanticIdentity();
    }
    static MatrixReplay<EGraphNode> checker(Map<String,String> labelMapping,Map<String,String> mapping) {
        Map<String,String> reverse=new HashMap<>();
        for(var entry:mapping.entrySet()) if(reverse.put(entry.getValue(),entry.getKey())!=null)
            throw new IllegalArgumentException("Noninjective selected mapping");
        return new MatrixReplay<>(new MatrixReplay.NativeCost<EGraphNode>() {
            public String label(EGraphNode n) {
                return new JSONArray().put(n.getOpcode().name()).put(String.valueOf(type(n)))
                        .put(String.valueOf(n.getSourceName())).put(String.valueOf(atom(n))).put("")
                        .put(n.getFlexibleArityKind().name()).put(n.isOrderInsensitive())
                        .put(new TreeMap<>(labelMapping).toString()).toString();
            }
            public List<EGraphNode> children(EGraphNode n) { return FastChainGeometry.children(n); }
            public List<EGraphNode> alternatives(EGraphNode n) { return List.of(); }
            public boolean unordered(EGraphNode a,EGraphNode b) { return a.getOpcode()==b.getOpcode() && a.isOrderInsensitive() && b.isOrderInsensitive(); }
            public int size(EGraphNode n) {
                if(n==null) return 0;
                int sum=1; for(var child:children(n)) sum=Math.addExact(sum,size(child)); return sum;
            }
            public int update(EGraphNode a,EGraphNode b) {
                if(a.getOpcode()!=b.getOpcode()) return 1;
                if(a.getOpcode()==EGraphNode.Opcode.VARIABLE) {
                    String x=variable(a),y=variable(b);
                    if(mapping.containsKey(x)) return mapping.get(x).equals(y)?0:1;
                    if(reverse.containsKey(y) || a.getAlphaName()!=null || b.getAlphaName()!=null) return 1;
                    return x.equals(y)?0:1;
                }
                if(!Objects.equals(type(a),type(b))) return 1;
                return switch(a.getOpcode()) {
                    case GLOBALBINDING,CONSTANT,REF -> Objects.equals(atom(a),atom(b))?0:1;
                    default -> 0;
                };
            }
            public int distance(EGraphNode a,EGraphNode b) { throw new AssertionError("Replay cannot search distance"); }
        });
    }
    static List<DeclarationReplay.Atom> declarations(NormalForm form) {
        List<QuantiVar> qs=form.getMatrixQuantiVars();
        List<Integer> order=new ArrayList<>(); for(int i=0;i<qs.size();i++) order.add(i);
        for(int start=0;start<order.size();) {
            int end=start+1;
            while(end<order.size() && (qs.get(start).getQuantifier()==QuantiVar.Quantifier.ALL
                    || qs.get(start).getQuantifier()==QuantiVar.Quantifier.SOME)
                    && qs.get(end).getQuantifier()==qs.get(start).getQuantifier()) end++;
            order.subList(start,end).sort(Comparator.comparing(i -> qs.get(i).getCarrierTypeName())); start=end;
        }
        List<DeclarationReplay.Atom> result=new ArrayList<>();
        for(int i:order) {
            QuantiVar q=qs.get(i);
            String admission=new JSONArray().put(q.getName()).put(q.getOriginalName()).put(q.getBindingPath())
                    .put(q.getCarrierTypeName()).put(String.valueOf(q.getExactAlloyType())).toString();
            result.add(new DeclarationReplay.Atom("phase/0/declaration/"+i,List.of(q.getQuantifier().name(),
                    q.getCarrierTypeName(),q.getCardinality().name(),Integer.toString(q.getDisjointnessClass())),admission));
        }
        return result;
    }
    private static void unfolding(FiniteUnfoldingTree tree) {
        CertificateVerifier.verify(tree.shapeCoherence());
        for (var child : tree.invocationChildren()) unfolding(child);
    }
    static RepairView checkedProjection(Canonical.Prepared source, Canonical.Prepared foreign) {
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
        check(evidence.localBinderDescriptors().isEmpty()==forms.get(0).getLocalQuantiVars().isEmpty(),"local source descriptors retained");
        System.out.println("source certificates: flat="+artifact.flatConstructions().size()
                +" container="+artifact.containerConstructions().size()
                +" chain="+artifact.dependentChainConstructions().size()
                +" join="+artifact.guardedJoinConstructions().size()
                +" binder="+artifact.binderOccurrenceCertificates().size()
                +"; typed independent-block declaration order; wire correspondence NOT claimed");
        return view;
    }


    private static void require(boolean value) {
        if (!value) throw new IllegalArgumentException("Outside fixed one-phase replay slice");
    }
    static void noChoices(Node n) {
        require(n.certifiedAlternatives().isEmpty());
        for (Node child : n.children()) noChoices(child);
    }
    static List<DeclarationReplay.Atom> atoms(Phase phase, JSONArray ordered) {
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
    static MatrixReplay<Node> certChecker(int[] mapping) {
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


    private record Time(String label,List<Time> children) { }
    private static int[][] dp(List<DeclarationReplay.Atom> a,List<DeclarationReplay.Atom> b) {
        int[][] d=new int[a.size()+1][b.size()+1];
        for(int i=0;i<=a.size();i++)d[i][0]=i;for(int j=0;j<=b.size();j++)d[0][j]=j;
        for(int i=1;i<=a.size();i++)for(int j=1;j<=b.size();j++)
            d[i][j]=Math.min(d[i-1][j-1]+(a.get(i-1).tuple().equals(b.get(j-1).tuple())?0:1),
                    Math.min(d[i-1][j]+1,d[i][j-1]+1));
        return d;
    }
    public static void main(String[] args) throws Exception {
        if(args.length>1)throw new IllegalArgumentException("[ARCHIVED_49_RECEIPT]");
        byte[] bytes=Files.readAllBytes(SOURCE);
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        var prepared=new ArrayList<Canonical.Prepared>();
        for(String name:List.of("inv14","inv14c")){
            var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);
            prepared.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));
        }
        var views=List.of(checkedProjection(prepared.get(0),prepared.get(1)),checkedProjection(prepared.get(1),prepared.get(0)));
        for(var v:views)noChoices(v.phases().get(0).matrix());
        var archived=args.length==0?null:new JSONObject(Files.readString(Path.of(args[0])));
        if(archived!=null)check(archived.getString("sourceSha256").equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))),"original source hash");
        for(boolean reverse:new boolean[]{false,true}){
            if(archived!=null && reverse!=archived.getString("leftPredicate").equals("inv14c"))continue;
            var p=prepared.get(reverse?1:0);var q=prepared.get(reverse?0:1);
            var report=archived==null?DistanceReplayDiagnostics.capture(p,q):archived;
            var a=p.normalizedForms().get(0);var b=q.normalizedForms().get(0);
            var left=BoundVariableCostTest.comparison(a);var right=BoundVariableCostTest.comparison(b);
            var fast=report.getJSONObject("fast");var phase=fast.getJSONArray("phases").getJSONObject(0);
            var label=new HashMap<String,String>();var raw=phase.getJSONObject("alphaMapping");
            for(String key:raw.keySet())label.put(key,raw.getString(key));
            var selected=new HashMap<>(label);
            if(archived!=null){
                check(label.equals(Map.of("_q0","_q0")),"archived native lacks paid link");
                reject(()->checker(label,label).verify(left,right,phase.getJSONObject("receipt")),"old raw49 link is incomplete");
                selected.put("_q1","_q1");
            } else check(selected.equals(reverse?Map.of("_q0","_q0","_q2","_q1"):Map.of("_q0","_q0","_q1","_q2")),
                    "new independently selected paid link");
            check(checker(label,selected).verify(left,right,phase.getJSONObject("receipt"))==47,"matrix47 with explicit original paid endpoints");
            reject(()->checker(label,selected).verify(right,left,phase.getJSONObject("receipt")),"matrix endpoint corruption");
            var da=declarations(a);var db=declarations(b);
            var supplement=DeclarationReplay.capture(da,db,dp(da,db),(i,j)->{
                if(i<0)return !selected.containsValue(new JSONArray(db.get(j).admission()).getString(0));
                if(j<0)return !selected.containsKey(new JSONArray(da.get(i).admission()).getString(0));
                String x=new JSONArray(da.get(i).admission()).getString(0),y=new JSONArray(db.get(j).admission()).getString(0);
                return y.equals(selected.get(x));
            });
            check(DeclarationReplay.verify(da,db,supplement)==2,"matching declaration cost2 path");
            int paid=0,oneSided=0;
            for(Object item:supplement.getJSONArray("operations")){
                var op=(JSONObject)item;
                if(op.getInt("leftIndex")>=0 && op.getInt("rightIndex")>=0){
                    String x=new JSONArray(op.getJSONObject("source").getString("admission")).getString(0);
                    String y=new JSONArray(op.getJSONObject("target").getString("admission")).getString(0);
                    check(y.equals(selected.get(x)),"each diagonal is the same matrix correspondence");
                    if(op.getInt("cost")==1)paid++;
                }else oneSided++;
            }
            check(paid==1 && oneSided==1,"one type/disjointness update plus one insertion/deletion");
            var forged=new JSONObject(supplement.toString());forged.getJSONArray("source").getJSONObject(0).put("admission","foreign");
            reject(()->DeclarationReplay.verify(da,db,forged),"foreign declaration admission");
            if(archived==null){
                var emitted=fast.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
                check(DeclarationReplay.verify(da,db,emitted)==2 && emitted.getJSONArray("operations").similar(supplement.getJSONArray("operations")),
                        "native emitted declaration path matches the independently checked paid path");
                check(fast.getBoolean("matrixReplayChecked") && fast.getInt("distance")==49,"native paid49, not scalar override");
            }
            var cert=report.getJSONObject("certified");var cp=cert.getJSONArray("phases").getJSONObject(0);
            int[] cm=cp.getJSONArray("alphaMapping").toList().stream().mapToInt(v->((Number)v).intValue()).toArray();
            var va=views.get(reverse?1:0).phases().get(0);var vb=views.get(reverse?0:1).phases().get(0);
            check(certChecker(cm).verify(va.matrix(),vb.matrix(),cp.getJSONObject("receipt"))==47,"independent certified matrix47");
            var cd=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
            check(DeclarationReplay.verify(atoms(va,cd.getJSONArray("source")),atoms(vb,cd.getJSONArray("target")),cd)==2,
                    "actual typed certified declarations2");
            check(cert.getInt("distance")==49 && !report.getBoolean("complete") && !report.getBoolean("publicSequenceLinked")
                    && !report.getBoolean("javaLeanCorrespondenceChecked"),"numeric49, full alignment BLOCKED");
        }
        System.out.println("NestedPaidDeclarationReplayTest: "+checks+" checks passed; "+
                (archived==null?"native explicit paid49 both directions":"archived49 completed with independent cost2 declaration path")+
                "; full alignment BLOCKED");
    }
}


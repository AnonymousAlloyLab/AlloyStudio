package is.fivefivefive.CanDis;

import is.fivefivefive.CanDis.core.*;
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

/** Rejects an unselected bound-variable identity in the archived shorter19 witness. */
public final class BoundVariableShorterReplayTest {
    public static final Path SOURCE=Path.of("classified-data/classroom_fol/both/YKy8LYsKdapW3xqbn_inv14.als");
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++; try { action.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static String variable(EGraphNode n) { return n.getAlphaName()==null?n.getSourceName():n.getAlphaName(); }
    private static GraphType type(EGraphNode n) {
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE) return null;
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
    private static MatrixReplay<EGraphNode> checker(Map<String,String> mapping,boolean strict) {
        Map<String,String> reverse=new HashMap<>();
        for(var entry:mapping.entrySet()) if(reverse.put(entry.getValue(),entry.getKey())!=null)
            throw new IllegalArgumentException("Noninjective selected mapping");
        return new MatrixReplay<>(new MatrixReplay.NativeCost<EGraphNode>() {
            public String label(EGraphNode n) {
                return new JSONArray().put(n.getOpcode().name()).put(String.valueOf(type(n)))
                        .put(String.valueOf(n.getSourceName())).put(String.valueOf(atom(n))).put("")
                        .put(n.getFlexibleArityKind().name()).put(n.isOrderInsensitive())
                        .put(new TreeMap<>(mapping).toString()).toString();
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
                    if(reverse.containsKey(y) || strict && (a.getAlphaName()!=null || b.getAlphaName()!=null)) return 1;
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
    private static List<DeclarationReplay.Atom> declarations(NormalForm form) {
        List<QuantiVar> qs=form.getMatrixQuantiVars();
        List<Integer> order=new ArrayList<>(); for(int i=0;i<qs.size();i++) order.add(i);
        for(int start=0;start<order.size();) {
            int end=start+1;
            while(end<order.size() && qs.get(end).getQuantifier()==qs.get(start).getQuantifier()) end++;
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
        System.out.println("source certificates: flat="+artifact.flatConstructions().size()
                +" container="+artifact.containerConstructions().size()
                +" chain="+artifact.dependentChainConstructions().size()
                +" join="+artifact.guardedJoinConstructions().size()
                +" binder="+artifact.binderOccurrenceCertificates().size()
                +"; typed independent-block declaration order; wire correspondence NOT claimed");
        return view;
    }


    private record Time(String label,List<Time> children) { }
    public static void main(String[] args) throws Exception {
        if(args.length>1) throw new IllegalArgumentException("[ARCHIVED_RECEIPT]");
        byte[] bytes=Files.readAllBytes(SOURCE);
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        List<Canonical.Prepared> prepared=new ArrayList<>();
        for(String name:List.of("inv14","inv14c")) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            prepared.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        // These actual checked source certificates are retained, not replaced by a metric guess.
        checkedProjection(prepared.get(0),prepared.get(1));
        checkedProjection(prepared.get(1),prepared.get(0));
        var archived=args.length==0?null:new JSONObject(Files.readString(Path.of(args[0])));
        if(archived!=null) check(archived.getString("sourceSha256").equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))),"original source hash");
        for(boolean reverse:new boolean[]{false,true}) {
            if(archived!=null && reverse!=archived.getString("leftPredicate").equals("inv14c")) continue;
            var p=prepared.get(reverse?1:0); var q=prepared.get(reverse?0:1);
            var report=archived==null?DistanceReplayDiagnostics.capture(p,q):archived;
            var a=p.normalizedForms().get(0); var b=q.normalizedForms().get(0);
            for(var f:List.of(a,b)) check(f.getTemporalChildren().isEmpty() && f.getParams().isEmpty()
                    && f.getInheritedQuantiVars().isEmpty() && f.getLocalQuantiVars().isEmpty()
                    && f.getPhaseLocalBindingImports().isEmpty(),"single closed prefix ownership");
            var source=prepared.get(0).normalizedForms().get(0); var target=prepared.get(1).normalizedForms().get(0);
            check(source.getMatrixQuantiVars().stream().map(QuantiVar::getCarrierTypeName).toList()
                    .equals(List.of("Person","Class","Group","Person")),"source typed original binder order");
            check(target.getMatrixQuantiVars().stream().map(QuantiVar::getCarrierTypeName).toList()
                    .equals(List.of("Class","Person")),"target original binder order");
            var left=BoundVariableCostTest.comparison(a);
            var right=BoundVariableCostTest.comparison(b);
            var arm=report.getJSONObject("fast"); var phases=arm.getJSONArray("phases");
            var raw=phases.getJSONObject(0).getJSONObject("alphaMapping"); var mapping=new HashMap<String,String>();
            for(String key:raw.keySet()) mapping.put(key,raw.getString(key));
            if(archived!=null) check(mapping.equals(reverse?Map.of("_q1","_q3"):Map.of("_q3","_q1")),
                    "retired selected mapping contains no q0 identity");
            var receipt=phases.getJSONObject(0).getJSONObject("receipt");
            if(archived!=null) {
                check(checker(mapping,false).verify(left,right,receipt)==17,"legacy native17 replicated, not admitted");
                reject(()->checker(mapping,true).verify(left,right,receipt),"shorter19 rejected: bound identity lacks selected source link");
                int unsupported=0;
                for(Object item:arm.getJSONArray("orderedDiagnosticActions")) {
                    var op=(JSONObject)item;
                    if(!op.optString("kind").equals("node") || op.getInt("cost")!=0) continue;
                    var x=new JSONArray(op.getString("sourceHead")); var y=new JSONArray(op.getString("targetHead"));
                    if(x.getString(0).equals("VARIABLE") && y.getString(0).equals("VARIABLE")
                            && x.getString(2).equals(reverse?"c":"s") && y.getString(2).equals(reverse?"s":"c")) unsupported++;
                }
                check(unsupported==1,"one exact unsupported Student/Class zero edit");
            } else {
                check(checker(mapping,true).verify(left,right,receipt)==18 && arm.getInt("distance")==20,
                        "corrected native20 independently replayed");
                check(report.getJSONObject("certified").getInt("distance")==20,"unchanged certified20");
            }
            reject(()->checker(mapping,true).verify(right,left,receipt),"endpoint substitution");
            reject(()->checker(mapping,true).verify(left,right,new JSONObject(receipt.toString()).put("cost",0)),"cost substitution");
            reject(()->checker(Map.of("_q0","_q1","_q3","_q1"),true),"noninjective forged choice");
            var declarations=arm.getJSONObject("declarationSlice");
            var decl=declarations.getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
            check(DeclarationReplay.verify(declarations(a),declarations(b),decl)==2,"original declaration path cost2");
            check(arm.getJSONObject("selectedPaidDeclarationCorrespondence").isEmpty(),"no paid diagonal licenses Student/Class edit");
            for(Object item:decl.getJSONArray("operations")) {
                var op=(JSONObject)item;
                if(op.getString("kind").equals("keep")) {
                    String x=new JSONArray(op.getJSONObject("source").getString("admission")).getString(0);
                    String y=new JSONArray(op.getJSONObject("target").getString("admission")).getString(0);
                    check(!x.equals(y),"zero diagonal never supports q0/q0 fallback");
                }
            }
            var corrupt=new JSONObject(decl.toString());
            corrupt.getJSONArray("source").getJSONObject(0).put("admission","foreign owner");
            reject(()->DeclarationReplay.verify(declarations(a),declarations(b),corrupt),"foreign declaration owner");
            var time=new Time("NONE",List.of()); var temporal=arm.getJSONObject("temporalSlice");
            check(TemporalReplay.verify(time,time,Time::label,Time::children,temporal)==0,"no temporal edits");
            check(PhaseReplay.verifyDiagnosticOrder(temporal,declarations,phases,arm.getJSONArray("orderedDiagnosticActions"))
                    ==arm.getInt("distance"),"diagnostic ordering replay, not public authority");
            check(!report.getBoolean("complete") && !report.getBoolean("sourceCertificateWireReplayChecked")
                    && !report.getBoolean("publicSequenceLinked") && !report.getBoolean("javaLeanCorrespondenceChecked"),
                    "full alignment stays BLOCKED");
        }
        System.out.println("BoundVariableShorterReplayTest: "+checks+" checks passed; "+
                (archived==null?"native20 replayed both directions":"retired shorter19 REJECTED")+"; full alignment BLOCKED");
    }
}


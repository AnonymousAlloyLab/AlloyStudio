package is.fivefivefive.CanDis.core;

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

/** Rejects the old shorter17 witness at its conflicting zero-cost variable edit. */
public final class InjectiveVariableReplayTest {
    public static final Path SOURCE=Path.of("classified-data/classroom_fol/both/2vLMTEbEhvra9ad9z_inv12.als");
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
                    if(strict && reverse.containsKey(y)) return 1;
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
    private record Time(String label,List<Time> children) { }
    public static void main(String[] args) throws Exception {
        if(args.length>1) throw new IllegalArgumentException("[RETIRED_SHORTER_RECEIPT]");
        byte[] bytes=Files.readAllBytes(SOURCE);
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        List<Canonical.Prepared> prepared=new ArrayList<>(); List<NormalForm> forms=new ArrayList<>();
        for(String name:List.of("inv12","inv12c")) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module); visitor.visit(new ModelUnit(null,module),null);
            var graph=visitor.getForest().get(visitor.getForestId(name));
            var agent=new IRAgent(graph); agent.computeNormalForm(); check(agent.normalForms().size()==1,"one non-temporal phase");
            NormalForm form=agent.normalForms().get(0); form.freezeForCertification(); forms.add(form);
            check(form.getTemporalChildren().isEmpty() && form.getParams().isEmpty(),"incident slice boundaries");
            prepared.add(Canonical.prepare(graph));
        }
        JSONObject report=args.length==0?DistanceReplayDiagnostics.capture(prepared.get(0),prepared.get(1)):
                new JSONObject(Files.readString(Path.of(args[0])));
        if(args.length!=0) check(report.getString("sourceSha256").equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))),"frozen source hash");
        var a=forms.get(0); var b=forms.get(1);
        check(a.getMatrixQuantiVars().get(0).getName().equals("_q0")
                && a.getMatrixQuantiVars().get(0).getCarrierTypeName().equals("Class")
                && b.getMatrixQuantiVars().get(0).getName().equals("_q0")
                && b.getMatrixQuantiVars().get(0).getCarrierTypeName().equals("Person"),"same alpha spelling, different source binders/types");
        check(a.getMatrixQuantiVars().get(1).getName().equals("_q1")
                && a.getMatrixQuantiVars().get(1).getCarrierTypeName().equals("Person"),"selected compatible source is q1, not q0");
        var left=NormalForm.fastComparisonViews(List.of(a)).get(0).getMatrixEGraph();
        var right=NormalForm.fastComparisonViews(List.of(b)).get(0).getMatrixEGraph();
        JSONObject arm=report.getJSONObject("fast"); var phases=arm.getJSONArray("phases");
        check(phases.length()==1 && phases.getJSONObject(0).getInt("phase")==0,"phase linkage");
        var mapping=Map.of("_q1","_q0");
        check(phases.getJSONObject(0).getJSONObject("alphaMapping").similar(new JSONObject(mapping)),"selected mapping");
        var receipt=phases.getJSONObject(0).getJSONObject("receipt");
        if(args.length!=0) {
            check(checker(mapping,false).verify(left,right,receipt)==14,"reproduce legacy native cost, not authority");
            reject(() -> checker(mapping,true).verify(left,right,receipt),"shorter17 rejected by one-to-one target contract");
        } else check(checker(mapping,true).verify(left,right,receipt)==15 && arm.getInt("distance")==18,"repaired native18 replay");
        reject(() -> checker(mapping,true).verify(right,left,receipt),"substituted endpoint");
        reject(() -> checker(Map.of("_q0","_q0","_q1","_q0"),true),"noninjective selected map");
        var declarations=arm.getJSONObject("declarationSlice");
        var components=declarations.getJSONArray("components");
        check(components.length()==1 && DeclarationReplay.verify(declarations(a),declarations(b),
                components.getJSONObject(0).getJSONObject("receipt"))==3,"source-linked three declaration edits");
        var time=new Time("NONE",List.of()); var temporal=arm.getJSONObject("temporalSlice");
        check(TemporalReplay.verify(time,time,Time::label,Time::children,temporal)==0,"no temporal edits");
        check(PhaseReplay.verifyDiagnosticOrder(temporal,declarations,phases,arm.getJSONArray("orderedDiagnosticActions"))
                ==arm.getInt("distance"),"reported diagnostic charge order only");
        check(!report.getBoolean("complete") && !report.getBoolean("sourceCertificateWireReplayChecked")
                && !report.getBoolean("publicSequenceLinked"),"public certification remains BLOCKED");
        System.out.println("InjectiveVariableReplayTest: "+checks+" checks passed; "+
                (args.length==0?"repaired native18 replayed":"retired shorter17 REJECTED")+"; full alignment BLOCKED");
    }
}

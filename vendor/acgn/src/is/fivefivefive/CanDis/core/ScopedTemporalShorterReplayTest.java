package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.DistanceReplayDiagnostics;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.GraphType;
import parser.ast.nodes.ModelUnit;

/** Fresh source-linked fixed-choice replay of the shorter native21 incident. */
public final class ScopedTemporalShorterReplayTest {
    public static final Path SOURCE = Path.of("classified-data/trash_ltl/both/oivT9pBECZFsYCGWq_inv18.als");
    private static int checks;
    private static void check(boolean value,String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("Outside scoped incident replay slice"); }
    private static void reject(Runnable action,String why) {
        checks++; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private record Time(String label,List<Time> children) { }
    private static Time time(NormalForm form) {
        List<Time> children = new ArrayList<>();
        var source = form.getTemporalChildren();
        for (int i=0; i<source.size(); i++) {
            NormalForm child = source.get(i);
            if (child.getTemporalOp() == NormalForm.TemporalOp.RELEASESL) {
                require(i+1 < source.size() && source.get(i+1).getTemporalOp() == NormalForm.TemporalOp.RELEASESR);
                List<Time> both = new ArrayList<>(time(child).children());
                both.addAll(time(source.get(++i)).children());
                children.add(new Time("RELEASES",both));
            } else children.add(time(child));
        }
        return new Time(form.getTemporalOp().name(),children);
    }
    private static String atom(EGraphNode n) {
        require(n.getOpcode() != EGraphNode.Opcode.CALL);
        if (n.hasComparisonTemporalReferenceName()) return n.comparisonTemporalReferenceName();
        return n.getSemanticIdentity() == null ? n.getSourceName() : n.getSemanticIdentity();
    }
    private static GraphType type(EGraphNode n) {
        if (n.getOpcode() == EGraphNode.Opcode.VARIABLE) return null;
        if (n.getMetatype() == EGraphNode.Metatype.BOOLEAN) return GraphType.BOOL;
        require(n.getExactAlloyType() != null);
        return AlloyTypeBridge.graphType(n.getExactAlloyType());
    }
    private static String variable(EGraphNode n) { return n.getAlphaName() == null ? n.getSourceName() : n.getAlphaName(); }
    private static MatrixReplay<EGraphNode> checker(Map<String,String> mapping) {
        return new MatrixReplay<>(new MatrixReplay.NativeCost<EGraphNode>() {
            public String label(EGraphNode n) {
                return new JSONArray().put(n.getOpcode().name()).put(String.valueOf(type(n)))
                        .put(String.valueOf(n.getSourceName())).put(String.valueOf(atom(n))).put("")
                        .put(n.getFlexibleArityKind().name()).put(n.isOrderInsensitive())
                        .put(new TreeMap<>(mapping).toString()).toString();
            }
            public List<EGraphNode> children(EGraphNode n) { return n.getChildren(); }
            public List<EGraphNode> alternatives(EGraphNode n) { return List.of(); }
            public boolean unordered(EGraphNode a,EGraphNode b) { return a.getOpcode() == b.getOpcode() && a.isOrderInsensitive() && b.isOrderInsensitive(); }
            public int size(EGraphNode n) {
                if (n == null) return 0;
                int sum=1; for (EGraphNode child:n.getChildren()) sum=Math.addExact(sum,size(child)); return sum;
            }
            public int update(EGraphNode a,EGraphNode b) {
                if (a.getOpcode() != b.getOpcode()) return 1;
                if (a.getOpcode() == EGraphNode.Opcode.VARIABLE)
                    return mapping.getOrDefault(variable(a),variable(a)).equals(variable(b)) ? 0 : 1;
                if (!Objects.equals(type(a),type(b))) return 1;
                return switch (a.getOpcode()) {
                    case GLOBALBINDING, CONSTANT, REF -> Objects.equals(atom(a),atom(b)) ? 0 : 1;
                    default -> 0;
                };
            }
            public int distance(EGraphNode a,EGraphNode b) { throw new AssertionError("Replay cannot search a new distance"); }
        });
    }
    private static List<DeclarationReplay.Atom> declarations(List<NormalForm> forms,int phase) {
        if (phase >= forms.size()) return List.of();
        List<QuantiVar> qs = forms.get(phase).getMatrixQuantiVars();
        require(qs.size() <= 1 && forms.get(phase).getParams().isEmpty());
        if (qs.isEmpty()) return List.of();
        QuantiVar q=qs.get(0);
        require(q.getCarrierTypeName().equals("File"));
        String admission=new JSONArray().put(q.getName()).put(q.getOriginalName()).put(q.getBindingPath())
                .put(q.getCarrierTypeName()).put(String.valueOf(q.getExactAlloyType())).toString();
        return List.of(new DeclarationReplay.Atom("phase/"+phase+"/declaration/0",
                List.of(q.getQuantifier().name(),"File",q.getCardinality().name(),Integer.toString(q.getDisjointnessClass())),admission));
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("[ARCHIVED_RECEIPT]");
        byte[] bytes=Files.readAllBytes(SOURCE);
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        List<Canonical.Prepared> prepared=new ArrayList<>();
        List<List<NormalForm>> forms=new ArrayList<>();
        for (String name:List.of("inv18","inv18c")) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            var graph=visitor.getForest().get(visitor.getForestId(name));
            var agent=new IRAgent(graph); agent.computeNormalForm();
            forms.add(List.copyOf(agent.normalForms()));
            prepared.add(Canonical.prepare(graph));
        }
        JSONObject report=args.length==0 ? DistanceReplayDiagnostics.capture(prepared.get(0),prepared.get(1))
                : new JSONObject(Files.readString(Path.of(args[0])));
        if (args.length!=0) check(report.getString("sourceSha256").equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))),"source hash");
        var a=forms.get(0); var b=forms.get(1);
        for (var phase:a) phase.freezeForCertification();
        for (var phase:b) phase.freezeForCertification();
        a.get(0).requireAdmittedTemporalTree(); b.get(0).requireAdmittedTemporalTree();
        check(a.size()==3 && b.size()==4,"actual admitted source phase counts");
        Time source=time(a.get(0)), target=time(b.get(0));
        JSONObject arm=report.getJSONObject("fast"), temporal=arm.getJSONObject("temporalSlice");
        check(TemporalReplay.verify(source,target,Time::label,Time::children,temporal)==1,"one executable AFTER->RELEASES edit");
        var wrong=new JSONObject(temporal.toString());
        wrong.getJSONArray("operations").getJSONObject(0).put("from","ALWAYS");
        reject(() -> TemporalReplay.verify(source,target,Time::label,Time::children,wrong),"wrong source operator");
        var av=NormalForm.fastComparisonViews(a); var bv=NormalForm.fastComparisonViews(b);
        check(av.size()==a.size() && bv.size()==b.size(),"no closed-phase pruning used");
        JSONArray phases=arm.getJSONArray("phases");
        check(phases.length()==4,"all phases covered");
        int matrix=0;
        for (int i=0;i<4;i++) {
            JSONObject phase=phases.getJSONObject(i);
            check(phase.getInt("phase")==i,"phase index");
            Map<String,String> mapping=new HashMap<>();
            JSONObject map=phase.getJSONObject("alphaMapping");
            for (String key:map.keySet()) mapping.put(key,map.getString(key));
            require(mapping.equals(i<3 ? Map.of("_q0","_q0") : Map.of()));
            EGraphNode left=i<av.size()?av.get(i).getMatrixEGraph():null;
            EGraphNode right=bv.get(i).getMatrixEGraph();
            JSONObject receipt=phase.getJSONObject("receipt");
            matrix+=checker(mapping).verify(left,right,receipt);
            reject(() -> checker(mapping).verify(right,left,receipt),"substituted matrix endpoints");
        }
        check(matrix==20,"native matrix20 independently replayed");
        JSONObject decl=arm.getJSONObject("declarationSlice");
        JSONArray components=decl.getJSONArray("components");
        check(components.length()==4,"declaration phase coverage");
        for(int i=0;i<4;i++) check(DeclarationReplay.verify(declarations(a,i),declarations(b,i),
                components.getJSONObject(i).getJSONObject("receipt"))==0,"declaration owner/type endpoints");
        check(PhaseReplay.verifyDiagnosticOrder(temporal,decl,phases,arm.getJSONArray("orderedDiagnosticActions"))==21,
                "native21 diagnostic ordering");
        check(!report.getBoolean("complete") && !report.getBoolean("sourceCertificateWireReplayChecked")
                && !report.getBoolean("publicSequenceLinked"),"public full alignment remains BLOCKED");
        System.out.println("ScopedTemporalShorterReplayTest: "+checks+" checks passed; native21 replayed, full alignment BLOCKED");
    }
}

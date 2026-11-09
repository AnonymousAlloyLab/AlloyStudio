package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** Source-fiber enumeration before native DP, never receipt/comparator normalization. */
public final class CertifiedSetEncounterOrderReplayTest {
    private static int checks;
    private static final Path SOURCE=Path.of("classified-data/classroom_fol/both/wdiLn4gC6q3bbYBad_inv3.als");
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(why);}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private record Pair(Canonical.Prepared a,Canonical.Prepared b,CanonicalAlloyPipeline.Prepared ca,CanonicalAlloyPipeline.Prepared cb){Pair reverse(){return new Pair(b,a,cb,ca);}}
    private static Pair prepare(String text,String left,String right)throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var v=new MASGVisitor(new GlobalVariables(),Set.of(left,right),module);v.visit(new ModelUnit(null,module),null);
        var a=Canonical.prepare(v.getForest().get(v.getForestId(left)));var b=Canonical.prepare(v.getForest().get(v.getForestId(right)));
        return new Pair(a,b,CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
    }
    private static NormalForm source(Canonical.Prepared p){return p.normalizedForms().get(0);}
    private static List<String> signatureTails(EGraphNode n){return n.getChildren().stream().map(x->x.getChildren().get(x.getChildren().size()-1).getSourceName()).toList();}
    private static void invalid(Pair p,JSONObject raw,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,raw).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    private static Object invoke(Object o,String name,Class<?>[] types,Object...args){
        try{var m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static Object containers(TheoryAlloyAdapter.Result evidence)throws Exception{
        var c=Class.forName("is.fivefivefive.CanDis.metric.RepairProjection$CertifiedContainers");
        var m=c.getDeclaredMethod("from",CertifiedSemanticArtifact.class,Map.class);m.setAccessible(true);return m.invoke(null,evidence.semanticArtifact(),evidence.certifiedSetOperandPartitions());
    }
    @SuppressWarnings("unchecked") private static List<List<Integer>> fibers(Object c,EGraphNode n,ContainerKind kind){return (List<List<Integer>>)invoke(c,"childFibers",new Class[]{EGraphNode.class,ContainerKind.class},n,kind);}
    public static void main(String[] args)throws Exception{
        Path out=args.length==0?Files.createTempDirectory("set-encounter-"):Path.of(args[0]);Files.createDirectories(out);
        var pair=prepare(Files.readString(SOURCE),"inv3","inv3c");
        var evidence=TheoryAlloyAdapter.adapt(pair.a.normalizedForms(),pair.a.semanticProfile());evidence.requireRepairProjectionSources(pair.a.normalizedForms());
        var av=NestedPaidDeclarationReplayTest.checkedProjection(pair.a,pair.b);var bv=NestedPaidDeclarationReplayTest.checkedProjection(pair.b,pair.a);
        var source=source(pair.a);var or=source.getMatrixEGraph().getChildren().get(0);var partition=evidence.certifiedSetOperandPartitions().get(or);
        check(or.getOpcode()==EGraphNode.Opcode.OR&&partition!=null,"actual source-linked Set partition");
        check(signatureTails(or).equals(List.of("Student","Teacher","Student","Teacher")),"shared normalized encounter order including duplicate occurrences");
        check(partition.inputFibers(or).equals(List.of(List.of(1,3),List.of(0,2))),"unchanged certified trace order is the original divergence");
        var c=containers(evidence);var ordered=fibers(c,or,ContainerKind.SET);
        check(ordered.equals(List.of(List.of(0,2),List.of(1,3))),"whole source-linked fibers re-enumerated before metric");
        check(new HashSet<>(ordered).equals(new HashSet<>(partition.inputFibers(or)))&&ordered.size()==partition.inputFibers(or).size(),"same actual partition, no dropped premise or replacement representative");
        for(ContainerKind kind:List.of(ContainerKind.SEQUENCE,ContainerKind.BAG,ContainerKind.ONE))
            check(fibers(c,or,kind).equals(List.of(List.of(0),List.of(1),List.of(2),List.of(3))),"non-Set branch retains all source occurrences in order: "+kind);
        var projected=av.phases().get(0).matrix().children().get(0);
        check(projected.children().size()==2&&projected.children().get(0).children().get(1).semanticPayload().contains("alloy/signature/Student")&&projected.children().get(1).children().get(1).semanticPayload().contains("alloy/signature/Teacher"),"selected certified matrix enumeration follows source");
        reject(()->partition.inputFibers(source(pair.b).getMatrixEGraph().getChildren().get(0)),"another original operator/source cannot use partition");
        var foreign=prepare(Files.readString(SOURCE),"inv3","inv3c");
        reject(()->partition.inputFibers(source(foreign.a).getMatrixEGraph().getChildren().get(0)),"same spelling foreign parser/occurrence cannot use partition");
        reject(()->RepairProjection.project(evidence,foreign.a.normalizedForms()),"foreign original source ledger");
        reject(()->evidence.semanticArtifact().withFlatConstructions(List.of()),"missing original flat proof premise");
        check(partition.inputFibers(or).equals(List.of(List.of(1,3),List.of(0,2))),"projection did not mutate certificate fibers");
        for(int direction=0;direction<2;direction++){
            var p=direction==0?pair:pair.reverse();var result=NativeOperationComparison.compare(p.a,p.b,p.ca,p.cb);var e=result.evidence();
            Files.writeString(out.resolve("after-"+direction+".json"),e.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"ordered native actions: "+e.opt("firstDifference"));
            check(result.bindScanCosts(new int[]{7,0,1,6},new int[]{7,0,1,6}).status()==NativeOperationComparison.Status.MATCHED,"own native costs unchanged, exact scan linkage");
            check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("endpointCorrespondenceChecked")&&e.getBoolean("fixedChoiceReplaysChecked"),"actual source/typed premises and separate fixed replays");
            check(e.getInt("fastActionCount")==7&&e.getInt("certifiedActionCount")==7&&e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"seven actual ordered actions including zeros and declaration edit");
            var report=e.getJSONObject("diagnostic");check(NativeOperationComparison.compareCaptured(p.a,p.b,report).status()==NativeOperationComparison.Status.MATCHED,"independent captured replay");
            if(args.length==2){var old=new JSONObject(Files.readString(Path.of(args[1]).resolve("before-"+direction+".json"))).getJSONObject("native");
                check(old.getJSONObject("diagnostic").getJSONObject("fast").similar(report.getJSONObject("fast")),"Fast native receipt entirely unchanged");
                check(!old.getJSONObject("diagnostic").getJSONObject("certified").similar(report.getJSONObject("certified")),"certified producer actually emits new ordered derivation");}
            check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"full certification stays blocked");
            var bad=copy(report);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(p,bad,"foreign source commitment");
            bad=copy(report);bad.getJSONObject("certified").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",5);invalid(p,bad,"cost forgery");
            bad=copy(report);var edges=bad.getJSONObject("certified").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").getJSONObject("derivation").getJSONArray("children").getJSONObject(0).getJSONObject("edit").getJSONArray("children");
            Object first=edges.get(0);edges.put(0,edges.get(1)).put(1,first);invalid(p,bad,"old delete/update ordering is not silently sorted by verifier");
            bad=copy(report);bad.getJSONObject("certified").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").getJSONObject("derivation").put("unordered",true);invalid(p,bad,"forged sequence versus Set edit semantics");
            bad=copy(report);var dr=bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");dr.getJSONArray(direction==0?"source":"target").getJSONObject(0).put("admission","foreign owner/type");invalid(p,bad,"declaration owner/type corruption");
            bad=copy(report);var actions=bad.getJSONObject("certified").getJSONArray("orderedDiagnosticActions");first=actions.get(0);actions.put(0,actions.get(1)).put(1,first);invalid(p,bad,"public diagnostic ordering cannot be discarded");
        }
        String reduced="sig P {} sig S,T in P {} pred a { all p:P | p in T or p in S } pred b { no S & T } pred repeated { all p:P | p in T or p in S or p in T or p in S }";
        for(String lhs:List.of("a","repeated")){var p=prepare(reduced,lhs,"b");for(var q:List.of(p,p.reverse())){
            var r=NativeOperationComparison.compare(q.a,q.b,q.ca,q.cb);check(r.status()==NativeOperationComparison.Status.MATCHED,"reduced same general Set enumeration "+lhs);
            check(r.bindScanCosts(new int[]{7,0,1,6},new int[]{7,0,1,6}).status()==NativeOperationComparison.Status.MATCHED,"reduced original cost7");}}
        var controls=new JSONArray();
        for(var row:List.of(new String[]{"p3hJuKvQ3SpwKDZHa_inv12","inv12","26"},new String[]{"JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"SASEHD8jPo2HgsuoJ_inv14","inv14","27"},new String[]{"YKy8LYsKdapW3xqbn_inv14","inv14","20"},new String[]{"xJnitBpFv9K74WNvf_inv14","inv14","49"},new String[]{"85tWoTeBhoEDhCKmw_inv14","inv14","37"},new String[]{"Wq9Au27ys6iRip6CD_inv14","inv14","36"},new String[]{"ft2Q8x9bQa59gFBR4_inv14","inv14","17"},new String[]{"hJWX73Rdw3RziF3uu_inv14","inv14","17"})){
            var p=prepare(Files.readString(Path.of("classified-data",row[0].startsWith("xJnit")?"coursesOld":"classroom_fol","both",row[0]+".als")),row[1],row[1]+"c");
            for(var q:List.of(p,p.reverse())){var report=DistanceReplayDiagnostics.capture(q.a,q.b,q.ca,q.cb);for(String arm:List.of("fast","certified"))check(report.getJSONObject(arm).getInt("distance")==Integer.parseInt(row[2])&&report.getJSONObject(arm).getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"prior native cost/joint link "+row[0]);
                var comparison=NativeOperationComparison.compare(q.a,q.b,q.ca,q.cb);if(row[0].startsWith("p3h"))check(comparison.status()==NativeOperationComparison.Status.MATCHED
                        &&comparison.evidence().getJSONArray("fastActions").similar(comparison.evidence().getJSONArray("certifiedActions")),"checked vector/canonical-slot correspondence");
                else if(!row[0].startsWith("xJnit")&&!row[0].startsWith("YKy"))check(comparison.status()==NativeOperationComparison.Status.MATCHED,"prior ordered native actions "+row[0]);}
            controls.put(new JSONObject().put("source",row[0]).put("distance",Integer.parseInt(row[2])));
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("CertifiedSetEncounterOrderReplayTest: "+checks+" checks passed; native7/actions7 both directions, unchanged Fast receipts");
    }
}

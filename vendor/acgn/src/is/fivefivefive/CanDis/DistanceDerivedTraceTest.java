package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import parser.ast.nodes.ModelUnit;

/** New focused native-execution tests; never runs any existing test main. */
public final class DistanceDerivedTraceTest {
    private static int checks;
    private static final Set<String> exercised=new java.util.HashSet<>();
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static void reject(Runnable action,String message) {
        checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted corruption: "+message);
    }
    private static List<Canonical.Prepared> prepare(String text,String a,String b)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var out=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(a,b)) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            out.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        return out;
    }
    private static JSONObject firstMatrixOperation(JSONObject trace) {
        for(Object value:trace.getJSONArray("phases")) {
            JSONArray actions=((JSONObject)value).getJSONObject("program").getJSONArray("operations");
            if(!actions.isEmpty())return actions.getJSONObject(0);
        }
        throw new AssertionError("Fixture has no matrix operations");
    }
    private static void exercise(NativeRepairTrace trace,int scalar,String label) {
        check(trace.distance()==scalar,label+" unchanged numeric metric");
        check(trace.replay(trace.json())==scalar,label+" executable endpoint and every component");
        var json=trace.json();check(json.getBoolean("metricRepresentationExecutionChecked")&&!json.getBoolean("publicSequenceLinked"),"native/public separation");
        JSONObject cost=trace.json();firstMatrixOperation(cost).put("cost",999);reject(()->trace.replay(cost),"cost");
        JSONObject address=trace.json();firstMatrixOperation(address).put("source","foreign/occurrence");reject(()->trace.replay(address),"address");
        JSONObject payload=trace.json();firstMatrixOperation(payload).put("after",new JSONObject().put("head","foreign"));reject(()->trace.replay(payload),"payload");
        JSONObject premise=trace.json();firstMatrixOperation(premise).put("premise","foreign");reject(()->trace.replay(premise),"premise");
        JSONObject occurrence=trace.json();var first=firstMatrixOperation(occurrence);
        if(!first.isNull("before")){first.getJSONObject("before").put("nativeEvidence","foreign type/owner");reject(()->trace.replay(occurrence),"typed occurrence");}
        for(int i=0;i<json.getJSONArray("nativeDeclarationPrograms").length();i++) {
            JSONObject declarations=trace.json();var ops=declarations.getJSONArray("nativeDeclarationPrograms").getJSONObject(i).getJSONArray("operations");
            if(!ops.isEmpty()) {ops.getJSONObject(0).put("leftIndex",999);reject(()->trace.replay(declarations),"declaration coordinate");break;}
        }
        JSONObject temporal=trace.json();var temporalOps=temporal.getJSONObject("nativeTemporalProgram").getJSONArray("operations");
        if(!temporalOps.isEmpty()) {temporalOps.getJSONObject(0).put("path",new JSONArray().put(999));reject(()->trace.replay(temporal),"temporal occurrence");}
        if(json.has("constructionDerivations")) {
            JSONObject construction=trace.json();construction.getJSONObject("constructionDerivations").put("source","foreign artifact");
            reject(()->trace.replay(construction),"construction endpoint substitution");
        }
        JSONObject sum=trace.json();sum.put("distance",scalar+1);reject(()->trace.replay(sum),"native total");
        JSONObject order=trace.json();order.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");
        reject(()->trace.replay(order),"ordered selected operation reference");
        check(trace.replay(trace.json())==scalar,"caller mutation cannot change retained decision");
    }
    private static void pair(String id,String text,String a,String b,Integer expected,Path out,JSONArray rows)throws Exception {
        check(exercised.add(id),"No duplicate focused invocation");
        var prepared=prepare(text,a,b);
        var fast=prepared.stream().map(p->CanonicalDistance.prepare(p.normalizedForms())).toList();
        var certified=prepared.stream().map(CanonicalAlloyPipeline::prepare).toList();
        for(int direction=0;direction<4;direction++) {
            int i=direction==1||direction==3?1:0,j=direction==0||direction==3?1:0;
            int f=CanonicalDistance.evaluate(fast.get(i),fast.get(j)).distance();
            int c=QuotientRepairDistance.evaluate(certified.get(i).repairView(),certified.get(j).repairView()).distance();
            if(direction<2&&expected!=null)check(f==expected&&c==expected,"frozen family cost "+id+" "+direction+" "+f+"/"+c);
            if(direction>=2)check(f==0&&c==0,"native identity "+id);
            var ft=CanonicalDistance.evaluateWithTrace(fast.get(i),fast.get(j));
            var ct=CanonicalRepairTraces.certified(certified.get(i),certified.get(j));
            exercise(ft,f,"fast "+id);exercise(ct,c,"certified "+id);
            CanonicalDistance.beginAllocationTracking();ft.replay(ft.json());
            var allocations=CanonicalDistance.endAllocationTracking();
            check(allocations.arrayCount()==0,"Fixed replay must not run a Fast DP/assignment");
            check(ct.json().getJSONObject("constructionDerivations").getBoolean("retained"),"actual retained construction premises, separate from edits");
            if(direction<2 && expected!=null) {
                CanonicalDistance.beginAllocationTracking();
                var cross=NativeOperationComparison.compare(prepared.get(i),prepared.get(j),ft,ct);
                check(CanonicalDistance.endAllocationTracking().arrayCount()==0,"comparison consumes selected records without Fast optimization");
                Files.writeString(out.resolve("cross-"+rows.length()+".json"),cross.evidence().toString(),StandardOpenOption.CREATE_NEW);
                check(cross.status()==NativeOperationComparison.Status.MATCHED,"prior checked family native actions preserved "+id+" "+cross.reason());
            }
            if(direction==0) {
                var repeated=CanonicalDistance.evaluateWithTrace(fast.get(i),fast.get(j));
                check(repeated.json().similar(ft.json()),"deterministic retained numeric choice");
            }
            var evidence=new JSONObject().put("id",id).put("direction",direction)
                    .put("sourceSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                    .put("fast",ft.json()).put("certified",ct.json());
            String file="row-"+rows.length()+".json";Files.writeString(out.resolve(file),evidence.toString(),StandardOpenOption.CREATE_NEW);
            rows.put(new JSONObject().put("id",id).put("direction",direction).put("fast",f).put("certified",c).put("evidence",file));
            System.out.println(id+" "+direction+" "+f+"/"+c+" executed");
        }
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);JSONArray rows=new JSONArray();
        pair("ordered-unordered","sig A {r:set A} pred a {some A and no r} pred b {one A or some r}","a","b",null,out,rows);
        pair("temporal","var sig A {} pred a {always some A} pred b {eventually (some A and after no A)}","a","b",null,out,rows);
        pair("local-group-call","sig A {r:set A} fun f[x,y:A]:set A {x+y} pred a {all x:A | some x.r implies (one y,z:A | x in f[y,z])} pred b {some A}","a","b",null,out,rows);
        pair("parameters","sig A {r:set A} pred a[x:A] {some x.r} pred b[y:A] {no y.r}","a","b",null,out,rows);
        pair("dependent","sig A {r:set A} pred a {all x:A | some y:x.r | y in x.r} pred b {some A}","a","b",null,out,rows);
        pair("temporal-owner","var sig A {var r:set A} pred a {all x:A | always some x.r} pred b {all x:A | eventually some x.r}","a","b",null,out,rows);
        pair("source-identical","sig A {r:set A} pred a {all x:A | some x.r} pred b {all x:A | some x.r}","a","b",0,out,rows);
        String original7Xiq=Files.readString(Path.of("classified-data/coursesOld/both/7XiqRnyXeoftBf2No_inv15.als"));
        String nested=original7Xiq.replace("some g1,g2 : Grade |","some g1 : Grade | some g2 : Grade |");
        String nexts=original7Xiq.replace("prevs[g2]","nexts[g2]");
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(nested.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .equals("cb730511256d915356c7d3f1b3abce9058c43cbda6c5486391088ace60e3cddd"),"same retained nested family identity");
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(nexts.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .equals("cf6db4e8e4a550061b4ae3277233d1b6725a412474cc4f16e67826a27ffd36e6"),"same retained nexts family identity");
        pair("7Xiq-nested",nested,"inv15","inv15c",56,out,rows);
        pair("7Xiq-nexts",nexts,"inv15","inv15c",51,out,rows);
        String body="all x:A | some x.r implies (some y,z:B | y in prevs[z])";
        String identical="open util/ordering[B]\nsig A {r:set A}\nsig B {}\npred a {"+body+"}\npred b {"+body+"}\n";
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identical.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .equals("76057c35a977daa0ef99b778e2cb8094cba699a175e668dc0db196c4e52d657c"),"same retained source-identical family identity");
        pair("7Xiq-identical",identical,"a","b",0,out,rows);
        for(var row:List.of(new String[]{"coursesOld/both/7XiqRnyXeoftBf2No_inv15","inv15","51"},
                new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"},
                new String[]{"classroom_fol/both/SASEHD8jPo2HgsuoJ_inv14","inv14","27"},
                new String[]{"coursesOld/both/xJnitBpFv9K74WNvf_inv14","inv14","49"},
                new String[]{"coursesOld/both/2EKgnipH28DLyp8uK_inv13","inv13","15"},
                new String[]{"coursesOld/both/3mNrsLxL8mPXSK6gH_inv13","inv13","14"},
                new String[]{"coursesNew/correct/wnqMPtpNxnPYzru3g_inv14","inv14","16"},
                new String[]{"coursesNew/both/LJrfWwJdKiTdyytci_inv14","inv14","26"},
                new String[]{"coursesNew/both/5vxYh7NCpEPMmC47p_inv14","inv14","47"}))
            pair(row[0],Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c",Integer.valueOf(row[2]),out,rows);
        var registry=new JSONObject(Files.readString(Path.of("docs/distance-parity-20261107/focused-witnesses.json")));
        for(Object value:registry.getJSONArray("witnesses")) {
            var row=(JSONObject)value;Path path=Path.of(row.getString("source"));byte[] bytes=Files.readAllBytes(path);
            check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(row.getString("sourceSha256")),"registered original source hash");
            String id=row.getString("source").replaceFirst("^classified-data/","").replaceFirst("\\.als$","");
            String predicate=id.substring(id.lastIndexOf('_')+1);
            // Historical baseline scalars are not an oracle for later integrated repairs.
            if(!exercised.contains(id))pair(id,new String(bytes,java.nio.charset.StandardCharsets.UTF_8),predicate,predicate+"c",null,out,rows);
        }
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("rows",rows)
                .put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("DistanceDerivedTraceTest: "+checks+" checks");
    }
}

package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** 5TvX closed-call paid paths; no call unfolding or full public certification. */
public final class ClosedCallPaidReplayTest {
    private static int checks;
    private static final Path SOURCE=Path.of("classified-data/coursesNew/both/5TvX7MDJN22SSvqyR_inv13.als");
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static boolean supports(List<NormalForm> forms){try{var m=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment").getDeclaredMethod("supports",List.class);m.setAccessible(true);return (boolean)m.invoke(null,forms);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static Pair prepare(String source,String left,String right)throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,source);var ps=new ArrayList<Canonical.Prepared>();for(String name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(ps.get(0),ps.get(1));
    }
    private static EGraphNode call(NormalForm f){var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());var pending=new ArrayDeque<EGraphNode>();pending.add(f.getMatrixEGraph());while(!pending.isEmpty()){var n=pending.remove();if(!seen.add(n))continue;if(seen.size()>4096)throw new AssertionError("budget");if(n.getOpcode()==EGraphNode.Opcode.CALL)return n;pending.addAll(n.getChildren());}throw new AssertionError("missing actual call");}
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static int[] map(JSONArray j){int[] a=new int[j.length()];for(int i=0;i<a.length;i++)a[i]=j.getInt(i);return a;}
    private static Map<String,String> map(JSONObject j){var m=new TreeMap<String,String>();for(String k:j.keySet())m.put(k,j.getString(k));return m;}
    private static void malformed(Pair p,JSONObject j,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,j).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    private static void sourceCalls(Canonical.Prepared p){
        var e=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());e.requireRepairProjectionSources(p.normalizedForms());var cs=e.semanticArtifact().callOccurrenceCertificates();check(cs.size()==1,"exact original CALL premise count");var c=cs.get(0);var n=call(p.normalizedForms().get(0));var meta=CallMetadata.require(n);
        check(c.occurrenceId()==meta.occurrenceId()&&c.qualifiedCallee().equals(meta.identity())&&c.declaredArity()==meta.arity()&&c.arityAuthority()==meta.authority()&&c.sourceEndpoint().operator().outputType().equals(AlloyTypeBridge.graphType(n.getExactAlloyType())),"actual typed source call premise");
        reject(()->e.semanticArtifact().withCallOccurrenceCertificates(List.of()),"missing call premise rejected");
        if(meta.arity()>0)reject(()->CallOccurrenceCertificate.create(meta,c.sourcePath(),c.sourceEndpoint(),List.of()),"missing ordered argument premise rejected");
    }
    private static JSONObject replay(Pair p,JSONObject old)throws Exception{
        var va=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var vb=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);sourceCalls(p.a);sourceCalls(p.b);
        var ca=CanonicalAlloyPipeline.prepare(p.a);var cb=CanonicalAlloyPipeline.prepare(p.b);var capture=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);var cert=capture.getJSONObject("certified");var cp=cert.getJSONArray("phases").getJSONObject(0);var cm=map(cp.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(va,vb,cm,cm,cp.getJSONObject("receipt"))==9,"shorter fixed matrix9 first");
        if(old!=null){check(old.getJSONObject("certified").getJSONArray("phases").similar(cert.getJSONArray("phases")),"certified matrix receipts unchanged");check(old.getJSONObject("certified").getJSONObject("declarationSlice").similar(cert.getJSONObject("declarationSlice")),"certified declaration receipts unchanged");}
        var na=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var nb=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var chosen=new TreeMap<String,String>();for(int i=0;i<cm.length;i++)if(cm[i]>=0)chosen.put(na.get(i),nb.get(cm[i]));
        var dr=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");var aa=NestedPaidDeclarationReplayTest.atoms(va.phases().get(0),dr.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(vb.phases().get(0),dr.getJSONArray("target"));
        check(new DeclarationMatrixLink(na,nb,chosen,Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,dr)==1,"actual selected keep and paid kind update");reject(()->new DeclarationMatrixLink(na,nb,Map.of("_q0","_q0"),Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,dr),"old incomplete paid mapping rejected");
        var fast=capture.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=map(fp.getJSONObject("alphaMapping"));check(fm.equals(chosen)&&fm.equals(Map.of("_q0","_q0","_q1","_q1")),"same original-coordinate mapping");
        check(CanonicalDistance.verifyFixedMatrixReplay(CanonicalDistance.prepare(p.a.normalizedForms()),CanonicalDistance.prepare(p.b.normalizedForms()),fm,fm,fp.getJSONObject("receipt"))==9,"Fast fixed original matrix9");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==10&&arm.getInt("quantifierDistance")==1&&arm.getInt("temporalDistance")==0&&arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"linked numerical coverage10");
        var cmp=NativeOperationComparison.compare(p.a,p.b,ca,cb);var e=cmp.evidence();check(cmp.status()==NativeOperationComparison.Status.MATCHED,"ordered closed-call native actions "+cmp.reason()+" "+e.opt("firstDifference"));
        check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all ordered native actions equal");check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"actual original source and call correspondence checked");check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"full public certification remains BLOCKED");
        var bad=copy(e.getJSONObject("diagnostic"));bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");malformed(p,bad,"foreign endpoint");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("_q1","_q0");malformed(p,bad,"capturing map");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(1).getJSONArray("tuple").put(0,"ONE");malformed(p,bad,"forged original quantifier kind");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",8);malformed(p,bad,"forged action cost");return e;
    }
    public static void main(String[] args)throws Exception{
        Path out=args.length==0?Files.createTempDirectory("closed-call-"):Path.of(args[0]);Files.createDirectories(out);var p=prepare(Files.readString(SOURCE),"inv13","inv13c");
        for(var endpoint:List.of(p.a,p.b)){var f=endpoint.normalizedForms().get(0);check(supports(endpoint.normalizedForms()),"original paid endpoint supported");var proof=ClosedCallEvidence.from(f);check(proof.admits(call(f))&&proof.admits(call(CanonicalDistance.prepare(endpoint.normalizedForms()).comparisonForms().get(0))),"original and comparison call source link");}
        var f=p.a.normalizedForms().get(0);var proof=ClosedCallEvidence.from(f);var original=call(f);var foreign=call(prepare(Files.readString(SOURCE),"inv13","inv13c").a.normalizedForms().get(0));check(!proof.admits(foreign),"same spelled foreign source call rejected");
        for(var change:List.<Consumer<EGraphNode>>of(n->field(n,"semanticIdentity","other/module/max"),n->field(n,"callOccurrenceId",999L),n->field(n,"exactAlloyType",foreign.getExactAlloyType()),n->field(n,"sourceOccurrenceLineage",999999L))){var n=call(CanonicalDistance.prepare(p.a.normalizedForms()).comparisonForms().get(0));check(n!=original,"corrupt comparison is not original source");change.accept(n);check(!proof.admits(n),"corrupt callee/type/parser occurrence/lineage rejected");}
        var malformed=call(CanonicalDistance.prepare(p.a.normalizedForms()).comparisonForms().get(0));field(malformed,"declaredArity",2);reject(()->proof.admits(malformed),"declared arity corruption rejected");
        for(int d=0;d<2;d++){var old=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+d+".json")));Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse(),old).toString(2),StandardOpenOption.CREATE_NEW);}
        String model="open util/ordering[A] sig A {} pred b {some A}";
        for(String text:List.of("pred a { all x:A | x in max[x] }","pred a { all x:A | x in max[A - x] }")){var pair=prepare(model+text,"a","b");var form=pair.a.normalizedForms().get(0);check(supports(pair.a.normalizedForms())&&!ClosedCallEvidence.closed(call(form))&&BoundCallEvidence.from(form).admits(call(form)),"source-linked imported bound/composite argument, not closed: "+text);}
        for(var control:List.of(Map.entry("pred a[z:A] {all x:A | max[A] in z}",NativeOperationComparison.Status.UNCHECKABLE),
                Map.entry("pred a {all x:A | always x in max[A]}",NativeOperationComparison.Status.MATCHED))) {
            var pair=prepare(model+control.getKey(),"a","b");check(!supports(pair.a.normalizedForms()),"legacy paid-prefix helper scope unchanged");
            var result=NativeOperationComparison.compare(pair.a,pair.b,CanonicalAlloyPipeline.prepare(pair.a),CanonicalAlloyPipeline.prepare(pair.b));
            check(result.status()==control.getValue(),"actual parameter gap versus checked temporal call-source correspondence");
            if(control.getValue()==NativeOperationComparison.Status.MATCHED)check(result.evidence().getBoolean("allNativeEntriesRetained"),"temporal call paid/zero operations retained");
        }
        var absorbed=prepare(model+"pred a { all x:A | x in max[A + x] }","a","b");var absorbedCall=call(absorbed.a.normalizedForms().get(0));check(absorbedCall.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GLOBALBINDING&&supports(absorbed.a.normalizedForms()),"old negative actually normalizes to closed max[A]");
        var eliminated=prepare(model+" pred a {all x:A | x in max[A]}","a","b");check(eliminated.a.normalizedForms().get(0).getMatrixQuantiVars().isEmpty()&&ClosedCallEvidence.closed(call(eliminated.a.normalizedForms().get(0))),"single-membership rule removes binder, not call authority");
        var reduced=prepare(model+" pred a {all x:A | x in max[A] and x in min[A]}","a","b");check(reduced.a.normalizedForms().get(0).getMatrixQuantiVars().size()==1&&supports(reduced.a.normalizedForms()),"closed imported literal calls with retained binder general positive");
        var controls=new JSONArray();for(var row:List.of(new String[]{"coursesNew/both/2CCEGTS6iiHpRqvCu_inv14","inv14","18"},new String[]{"classroom_rl/under/5xC5nGeD2S6zDjCCo_inv14","inv14","10"},new String[]{"classroom_rl/over/AE26k3aCggeBrqCrh_inv8","inv8","23"},new String[]{"classroom_rl/both/D3n2EfPhBboewRm7z_inv14","inv14","20"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"coursesOld/both/xJnitBpFv9K74WNvf_inv14","inv14","49"})){
            var pair=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");for(var dir:List.of(pair,pair.reverse()))check(Canonical.distance(dir.a,dir.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b))==Integer.parseInt(row[2]),"prior cost "+row[0]);controls.put(row[0]);}
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("ClosedCallPaidReplayTest: "+checks+" checks passed");
    }
}

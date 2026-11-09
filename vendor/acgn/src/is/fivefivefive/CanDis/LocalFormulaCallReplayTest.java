package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;

/** Formula-call result admission never transfers a retained local owner. */
public final class LocalFormulaCallReplayTest {
    private static int checks,negative;
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static void field(Object o,String n,Object v){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static List<EGraphNode> nodes(EGraphNode root,EGraphNode.Opcode op){var out=new ArrayList<EGraphNode>();var todo=new ArrayDeque<EGraphNode>();var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());todo.add(root);while(!todo.isEmpty()){var n=todo.remove();if(!seen.add(n))continue;if(n.getOpcode()==op)out.add(n);todo.addAll(n.getChildren());}return out;}
    private static EGraphNode call(NormalForm f){return nodes(f.getMatrixEGraph(),EGraphNode.Opcode.CALL).get(0);}
    private static NormalForm comparison(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);}
    private static void reject(NormalForm f,String why){negative++;boolean accepted=false;try{accepted=!FastLocalPaidScope.checkedCalls(f).isEmpty();}catch(IllegalArgumentException|IllegalStateException expected){}check(!accepted,why);}
    private static JSONObject surface(JSONObject arm){var out=new JSONObject();for(String k:List.of("metricVersion","phases","nativeDeclarationPrograms","nativeTemporalProgram","nativeDeclarationRepresentationSteps","orderedNativeOperations","selectedDeclarationPermutation","selectedQuantifierCorrespondence"))if(arm.has(k))out.put(k,arm.get(k));return out;}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);boolean before=args.length>1&&args[1].equals("before");
        Path src=Path.of("classified-data/classroom_fol/both/c5NJt2KNJ26vqg37u_inv5.als");String text=Files.readString(src);
        check(hash(text).equals("5418526a40a6e0431c75183ea46171f112e7f0d80a3e98b59a714aab2b87b55b"),"natural hash");
        var input=new JSONObject().put("source",src.toAbsolutePath().toString()).put("sha256",hash(text)).put("left","inv5").put("right","inv5c");
        var ps=LocalOwnerTraceTest.prepare(input);var f=ps.get(0).normalizedForms().get(0);var n=call(f);
        check(CallMetadata.require(n).kind().equals("call/formula")&&n.getExactAlloyType().kind()==is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.BOOL,"actual formula BOOL");
        check(n.getChildren().get(0).getSourceName().equals("t")&&n.getChildren().get(1).getSourceName().equals("c"),"ordered actual arguments");
        if(before){check(FastLocalPaidScope.checkedCalls(f).isEmpty()&&FastLocalPaidScope.checkedCalls(comparison(ps.get(0))).isEmpty(),"exact old result gate rejects source and comparison");Files.writeString(out.resolve("old-rejection.json"),new JSONObject().put("input",input).put("checks",checks).put("oldAdmission",false).toString(2),StandardOpenOption.CREATE_NEW);return;}
        check(FastLocalPaidScope.checkedCalls(f).contains(n),"original actual scoped call admitted");
        var cf=comparison(ps.get(0));check(FastLocalPaidScope.checkedCalls(cf).contains(call(cf)),"actual comparison scoped call admitted");
        var foreign=LocalOwnerTraceTest.prepare(input);var foreignCall=call(foreign.get(0).normalizedForms().get(0));negative++;check(!FastLocalPaidScope.checkedCalls(f).contains(foreignCall),"same-metadata foreign call is not an actual selected object");
        for(var change:List.<Consumer<NormalForm>>of(
            x->field(call(x),"sourceOccurrenceLineage",-1L),x->field(call(x),"callOccurrenceId",999L),
            x->field(call(x),"semanticIdentity","foreign/callee"),x->field(call(x),"sourceType","call/expression"),
            x->field(call(x),"exactAlloyType",n.getChildren().get(0).getExactAlloyType()),
            x->field(call(x),"declaredArity",1),
            x->field(call(x).getChildren().get(1),"sourceOccurrenceLineage",190L),
            x->field(call(x).getChildren().get(1),"alphaName","_q0"),
            x->field(call(x).getChildren().get(1),"exactAlloyType",foreignCall.getChildren().get(1).getExactAlloyType()),
            x->{var cs=new ArrayList<>(call(x).getChildClasses());Collections.reverse(cs);field(call(x),"childClasses",cs);},
            x->field(call(x),"childClasses",foreignCall.getChildClasses()),
            x->field(nodes(x.getMatrixEGraph(),EGraphNode.Opcode.EXISTS).get(0),"sourceOccurrenceLineage",999L),
            x->field(nodes(x.getMatrixEGraph(),EGraphNode.Opcode.GENERICRELDECL).get(0),"sourceOccurrenceLineage",999L),
            x->field(x,"temporalOp",NormalForm.TemporalOp.ALWAYS))) {
            var changed=comparison(ps.get(0));change.accept(changed);reject(changed,"retained source/call/owner/type/phase substitution");
        }
        var adapter=TheoryAlloyAdapter.adapt(ps.get(0).normalizedForms(),ps.get(0).semanticProfile());adapter.requireRepairProjectionSources(ps.get(0).normalizedForms());
        var cert=adapter.semanticArtifact().callOccurrenceCertificates().stream().filter(x->x.occurrenceId()==CallMetadata.require(n).occurrenceId()).findFirst().orElseThrow();
        check(cert.kind().equals("call/formula")&&cert.sourceEndpoint().operator().outputType().equals(AlloyTypeBridge.graphType(n.getExactAlloyType())),"actual source call certificate");
        var wrong=new ArrayList<>(cert.orderedArguments());Collections.reverse(wrong);boolean refused=false;try{CallOccurrenceCertificate.create(CallMetadata.require(n),cert.sourcePath(),cert.sourceEndpoint(),wrong);}catch(IllegalArgumentException|IllegalStateException expected){refused=true;}negative++;check(refused,"wrong ordered certificate arguments");
        var premise=new JSONObject().put("input",input).put("sourceLineage",n.getSourceOccurrenceLineage()).put("callee",cert.qualifiedCallee()).put("occurrence",cert.occurrenceId()).put("sourcePath",cert.sourcePath()).put("typedEndpoint",cert.sourceEndpoint().structuralKey());var arguments=new JSONArray();
        var owners=new ArrayList<QuantiVar>(f.getMatrixQuantiVars());owners.addAll(f.getLocalQuantiVars());
        for(var a:n.getChildren()){var q=owners.stream().filter(x->x.getName().equals(a.getAlphaName())).findFirst().orElseThrow();arguments.put(new JSONObject().put("occurrence",a.getSourceOccurrenceLineage()).put("alpha",a.getAlphaName()).put("name",a.getSourceName()).put("type",a.getExactAlloyType().stableString()).put("owner",q.getBindingPath()).put("slot",q.getDeBruijnKey()));}premise.put("arguments",arguments);Files.writeString(out.resolve("source-premise.json"),premise.toString(2),StandardOpenOption.CREATE_NEW);
        var cases=new LinkedHashMap<String,JSONObject>();cases.put("natural",input);Path renamed=out.resolve("renamed.als");String variant=text.replace("teaches_class","instructs");Files.writeString(renamed,variant,StandardOpenOption.CREATE_NEW);cases.put("synthetic-renamed",new JSONObject().put("source",renamed.toString()).put("sha256",hash(variant)).put("left","inv5").put("right","inv5c"));
        var results=new JSONArray();for(var entry:cases.entrySet()){var pair=LocalOwnerTraceTest.prepare(entry.getValue());for(int d=0;d<2;d++){
            var a=pair.get(d);var b=pair.get(1-d);var fast=CanonicalRepairTraces.fast(a,b);var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));var result=NativeOperationComparison.compare(a,b,fast,certified);var e=result.evidence();Files.writeString(out.resolve(entry.getKey()+"-"+d+".json"),e.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"actual local formula-call path "+result.reason());for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(e.getBoolean(flag),flag);
            var report=e.getJSONObject("diagnostic");check(NativeOperationComparison.compareCaptured(a,b,fast,certified,report).status()==NativeOperationComparison.Status.MATCHED,"immutable selected-path replay");
            for(String arm:List.of("fast","certified")){var armData=report.getJSONObject(arm);check(armData.getInt("distance")==13&&armData.getInt("matrixDistance")==12&&armData.getInt("quantifierDistance")==1,"unchanged selected costs");var ops=armData.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations");check(ops.getJSONObject(7).getString("kind").equals(d==0?"delete":"insert")&&ops.getJSONObject(7).getInt("cost")==1,"selected charged call operation7");
                for(String key:List.of("cost",d==0?"source":"target","premise")){var bad=new JSONObject(report.toString());bad.getJSONObject(arm).getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(7).put(key,key.equals("cost")?0:"foreign");negative++;check(NativeOperationComparison.compareCaptured(a,b,fast,certified,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"tampered selected call "+key);}}
            if(entry.getKey().equals("natural")&&args.length==3){var old=new JSONObject(Files.readString(Path.of(args[1+d])));var preserved=new JSONObject();for(String arm:List.of("fast","certified"))preserved.put(arm,LocalOwnerTraceTest.sameExecution(surface(old.getJSONObject(arm)),surface(report.getJSONObject(arm))));Files.writeString(out.resolve("native-preservation-"+d+".json"),preserved.toString(2),StandardOpenOption.CREATE_NEW);}
            results.put(new JSONObject().put("input",entry.getValue()).put("natural",entry.getKey().equals("natural")).put("direction",d).put("status",result.status().name()));
        }}
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negative).put("directions",results.length()).put("results",results).put("publicCertification","BLOCKED");Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary);
    }
}

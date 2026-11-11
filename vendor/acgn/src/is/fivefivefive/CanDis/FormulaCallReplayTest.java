package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;

/** Source-bound Boolean call admission; charged edits retain the opaque call. */
public final class FormulaCallReplayTest {
    private static int checks,negatives;
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static void field(Object o,String n,Object v){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static EGraphNode call(NormalForm f){var q=new ArrayDeque<EGraphNode>();q.add(f.getMatrixEGraph());var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());while(!q.isEmpty()){var n=q.remove();if(!seen.add(n))continue;if(n.getOpcode()==EGraphNode.Opcode.CALL)return n;q.addAll(n.getChildren());}throw new AssertionError("missing call");}
    private static NormalForm comparison(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);}
    private static JSONObject copy(JSONObject x){return new JSONObject(x.toString());}
    private static void rejected(BoundCallEvidence proof,EGraphNode n,String why){negatives++;boolean accepted=false;try{accepted=proof.admits(n);}catch(IllegalArgumentException|IllegalStateException expected){}check(!accepted,why);}
    private static JSONObject nativeSurface(JSONObject arm){var out=new JSONObject();for(String k:List.of("metricVersion","phases","nativeDeclarationPrograms","nativeTemporalProgram","nativeDeclarationRepresentationSteps","orderedNativeOperations","selectedDeclarationPermutation","selectedQuantifierCorrespondence"))if(arm.has(k))out.put(k,arm.get(k));return out;}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);boolean before=args.length>1&&args[1].equals("before");
        Path src=Path.of("classified-data/classroom_fol/both/ZNPHyDTAwMRjjSK2M_inv14.als");String text=Files.readString(src);
        check(hash(text).equals("89fbf1866df597b689d1c1b1e9790ede43579932168b40b2ecff38758dbd6d5d"),"natural source hash");
        var input=new JSONObject().put("source",src.toAbsolutePath().toString()).put("sha256",hash(text)).put("left","inv14").put("right","inv14c");
        var ps=LocalOwnerTraceTest.prepare(input);var f=ps.get(0).normalizedForms().get(0);var n=call(f);var proof=BoundCallEvidence.from(f);
        check(CallMetadata.require(n).kind().equals("call/formula")&&n.getExactAlloyType().kind()==is.fivefivefive.ACGN.alloy.ExactAlloyType.Kind.BOOL,"actual formula and BOOL type");
        check(n.getChildren().size()==2&&n.getChildren().get(0).getSourceName().equals("s")&&n.getChildren().get(1).getSourceName().equals("c"),"actual ordered s,c occurrences");
        if(before){check(!proof.admits(n)&&!proof.admits(call(comparison(ps.get(0)))),"exact original gate rejects both source/comparison");Files.writeString(out.resolve("old-rejection.json"),new JSONObject().put("checks",checks).put("input",input).put("kind",CallMetadata.require(n).kind()).put("resultType",n.getExactAlloyType().stableString()).put("oldAdmission",false).toString(2),StandardOpenOption.CREATE_NEW);return;}
        check(proof.admits(n)&&proof.admits(call(comparison(ps.get(0)))),"source-backed formula admission");
        var foreign=LocalOwnerTraceTest.prepare(input);rejected(proof,call(foreign.get(0).normalizedForms().get(0)),"identical text from foreign parser module");
        for(var change:List.<Consumer<EGraphNode>>of(
            x->field(x,"sourceOccurrenceLineage",-1L),x->field(x,"callOccurrenceId",999L),
            x->field(x,"semanticIdentity","foreign/studentInClass"),x->field(x,"sourceType","call/expression"),
            x->field(x,"exactAlloyType",n.getChildren().get(0).getExactAlloyType()),x->field(x,"declaredArity",1),
            x->field(x,"callArityAuthority","TYPECHECKED_IMPORT"),
            x->field(x.getChildren().get(0),"alphaName","_q2"),
            x->field(x.getChildren().get(0),"sourceOccurrenceLineage",999L),
            x->field(x.getChildren().get(0),"semanticIdentity","var/scope:3/slot:0"),
            x->field(x.getChildren().get(0),"exactAlloyType",call(foreign.get(0).normalizedForms().get(0)).getChildren().get(0).getExactAlloyType()),
            x->{var children=new ArrayList<>(x.getChildClasses());Collections.reverse(children);field(x,"childClasses",children);},
            x->field(x,"childClasses",List.of(x.getChildClasses().get(0),x.getChildClasses().get(0))))) {
            var changed=call(comparison(ps.get(0)));change.accept(changed);rejected(proof,changed,"source/type/owner/ordered argument corruption");
        }
        for(String scope:List.of("params","inheritedQuantiVars","phaseLocalBindingImports")){var changed=comparison(ps.get(0));field(changed,scope,List.of(new Object()));rejected(BoundCallEvidence.from(changed),call(changed),"foreign owner scope");}
        var temporal=comparison(ps.get(0));field(temporal,"temporalOp",NormalForm.TemporalOp.ALWAYS);rejected(BoundCallEvidence.from(temporal),call(temporal),"foreign phase scope");
        var adapter=TheoryAlloyAdapter.adapt(ps.get(0).normalizedForms(),ps.get(0).semanticProfile());adapter.requireRepairProjectionSources(ps.get(0).normalizedForms());
        var metadata=CallMetadata.require(n);var cert=adapter.semanticArtifact().callOccurrenceCertificates().stream().filter(x->x.occurrenceId()==metadata.occurrenceId()).findFirst().orElseThrow();
        check(cert.kind().equals(metadata.kind())&&cert.qualifiedCallee().equals(metadata.identity())&&cert.declaredArity()==2&&cert.sourceEndpoint().operator().outputType().equals(AlloyTypeBridge.graphType(n.getExactAlloyType())),"actual typed source call certificate");
        var badArgs=new ArrayList<>(cert.orderedArguments());Collections.reverse(badArgs);boolean refused=false;try{CallOccurrenceCertificate.create(metadata,cert.sourcePath(),cert.sourceEndpoint(),badArgs);}catch(IllegalArgumentException|IllegalStateException expected){refused=true;}negatives++;check(refused,"wrong ordered certificate arguments");
        var premise=new JSONObject().put("source",input).put("sourcePath",cert.sourcePath()).put("callee",cert.qualifiedCallee()).put("occurrence",cert.occurrenceId()).put("kind",cert.kind()).put("typedEndpoint",cert.sourceEndpoint().structuralKey()).put("sourceLineage",n.getSourceOccurrenceLineage());var argData=new JSONArray();
        for(var a:n.getChildren()){var owner=f.getMatrixQuantiVars().stream().filter(q->q.getName().equals(a.getAlphaName())).findFirst().orElseThrow();argData.put(new JSONObject().put("lineage",a.getSourceOccurrenceLineage()).put("alpha",a.getAlphaName()).put("name",a.getSourceName()).put("type",a.getExactAlloyType().stableString()).put("owner",owner.getBindingPath()).put("slot",owner.getDeBruijnKey()));}premise.put("arguments",argData);Files.writeString(out.resolve("source-premise.json"),premise.toString(2),StandardOpenOption.CREATE_NEW);
        var cases=new LinkedHashMap<String,JSONObject>();cases.put("natural",input);Path renamed=out.resolve("renamed.als");String variant=text.replace("studentInClass","enrolledAt");Files.writeString(renamed,variant,StandardOpenOption.CREATE_NEW);cases.put("synthetic-renamed",new JSONObject().put("source",renamed.toString()).put("sha256",hash(variant)).put("left","inv14").put("right","inv14c"));
        var results=new JSONArray();
        for(var entry:cases.entrySet()){var pair=LocalOwnerTraceTest.prepare(entry.getValue());for(int d=0;d<2;d++){
            var a=pair.get(d);var b=pair.get(1-d);var fast=CanonicalRepairTraces.fast(a,b);var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));var result=NativeOperationComparison.compare(a,b,fast,certified);var e=result.evidence();Files.writeString(out.resolve(entry.getKey()+"-"+d+".json"),e.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"actual formula-call path "+result.reason());for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(e.getBoolean(flag),flag);
            var report=e.getJSONObject("diagnostic");check(NativeOperationComparison.compareCaptured(a,b,fast,certified,report).status()==NativeOperationComparison.Status.MATCHED,"exact immutable selected-path replay");
            for(String arm:List.of("fast","certified")){var armData=report.getJSONObject(arm);check(armData.getInt("distance")==22&&armData.getInt("matrixDistance")==21&&armData.getInt("quantifierDistance")==1,"unchanged selected costs");var ops=armData.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations");check(ops.getJSONObject(23).getString("kind").equals(d==0?"delete":"insert")&&ops.getJSONObject(23).getInt("cost")==1,"actual selected charged call operation23");
                for(String key:List.of("cost",d==0?"source":"target","premise")){var bad=copy(report);var op=bad.getJSONObject(arm).getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(23);op.put(key,key.equals("cost")?0:"foreign");negatives++;check(NativeOperationComparison.compareCaptured(a,b,fast,certified,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"tampered selected call "+key);}
            }
            if(entry.getKey().equals("natural")&&args.length==3){var old=new JSONObject(Files.readString(Path.of(args[1+d])));var preserved=new JSONObject();for(String arm:List.of("fast","certified"))preserved.put(arm,LocalOwnerTraceTest.sameExecution(nativeSurface(old.getJSONObject(arm)),nativeSurface(report.getJSONObject(arm))));Files.writeString(out.resolve("native-preservation-"+d+".json"),preserved.toString(2),StandardOpenOption.CREATE_NEW);}
            results.put(new JSONObject().put("input",entry.getValue()).put("natural",entry.getKey().equals("natural")).put("direction",d).put("status",result.status().name()));
        }}
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",results.length()).put("results",results).put("publicCertification","BLOCKED");Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary);
    }
}

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

/** wnq: paid prefix links through source-declared, ordered opaque call arguments. */
public final class BoundCallPaidReplayTest {
    private static final Path SOURCE=Path.of("classified-data/coursesNew/correct/wnqMPtpNxnPYzru3g_inv14.als");
    private static int checks;
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static boolean supports(List<NormalForm> fs){try{var m=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment").getDeclaredMethod("supports",List.class);m.setAccessible(true);return (boolean)m.invoke(null,fs);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static Pair prepare(String text,String left,String right)throws Exception{
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();for(String name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(ps.get(0),ps.get(1));
    }
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static List<EGraphNode> calls(NormalForm f){var result=new ArrayList<EGraphNode>();var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());var pending=new ArrayDeque<EGraphNode>();pending.add(f.getMatrixEGraph());while(!pending.isEmpty()){var n=pending.remove();if(!seen.add(n))continue;if(seen.size()>4096)throw new AssertionError("test node budget");if(n.getOpcode()==EGraphNode.Opcode.CALL)result.add(n);pending.addAll(n.getChildren());}return result;}
    private static NormalForm comparison(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);}
    private static EGraphNode variableCall(NormalForm f){return calls(f).stream().filter(n->n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.VARIABLE).findFirst().orElseThrow();}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static int[] map(JSONArray j){int[] a=new int[j.length()];for(int i=0;i<a.length;i++)a[i]=j.getInt(i);return a;}
    private static Map<String,String> map(JSONObject j){var m=new TreeMap<String,String>();for(String k:j.keySet())m.put(k,j.getString(k));return m;}
    private static void malformed(Pair p,JSONObject j,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,j).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    private static void callPremises(Canonical.Prepared p,int expectedOccurrences){
        var e=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());e.requireRepairProjectionSources(p.normalizedForms());var cs=e.semanticArtifact().callOccurrenceCertificates();check(cs.size()==expectedOccurrences,"actual parser CALL occurrence premise count");
        for(var n:calls(p.normalizedForms().get(0))){var m=CallMetadata.require(n);var c=cs.stream().filter(v->v.occurrenceId()==m.occurrenceId()).findFirst().orElseThrow();check(c.qualifiedCallee().equals(m.identity())&&c.kind().equals(m.kind())&&c.arityAuthority()==m.authority()&&c.declaredArity()==m.arity()&&c.sourceEndpoint().operator().outputType().equals(AlloyTypeBridge.graphType(n.getExactAlloyType())),"actual source callee/arity/result-type premise");check(c.orderedArguments().size()==n.getChildren().size(),"original ordered argument coverage");reject(()->CallOccurrenceCertificate.create(m,c.sourcePath(),c.sourceEndpoint(),List.of()),"missing ordered argument premise");var substituted=cs.stream().filter(v->v!=c).map(CallOccurrenceCertificate::orderedArguments).findFirst().orElseGet(()->{var arguments=new ArrayList<>(c.orderedArguments());Collections.reverse(arguments);return arguments;});reject(()->CallOccurrenceCertificate.create(m,c.sourcePath(),c.sourceEndpoint(),substituted),"substituted argument context/endpoint");}
        reject(()->e.semanticArtifact().withCallOccurrenceCertificates(List.of()),"missing parser occurrence premise");
    }
    private static JSONObject replay(Pair p,JSONObject old){
        var va=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var vb=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);
        var ca=CanonicalAlloyPipeline.prepare(p.a);var cb=CanonicalAlloyPipeline.prepare(p.b);var capture=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);var cert=capture.getJSONObject("certified");var cp=cert.getJSONArray("phases").getJSONObject(0);var cm=map(cp.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(va,vb,cm,cm,cp.getJSONObject("receipt"))==14,"shorter certified original matrix14 first");
        if(old!=null){check(old.getJSONObject("certified").getJSONArray("phases").similar(cert.getJSONArray("phases")),"certified matrix unchanged");check(old.getJSONObject("certified").getJSONObject("declarationSlice").similar(cert.getJSONObject("declarationSlice")),"certified declaration unchanged");}
        var na=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var nb=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var chosen=new TreeMap<String,String>();for(int i=0;i<cm.length;i++)if(cm[i]>=0)chosen.put(na.get(i),nb.get(cm[i]));
        var cd=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");var aa=NestedPaidDeclarationReplayTest.atoms(va.phases().get(0),cd.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(vb.phases().get(0),cd.getJSONArray("target"));
        check(new DeclarationMatrixLink(na,nb,chosen,Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,cd)==2,"actual original joint keep0/insert-or-delete1/update1");reject(()->new DeclarationMatrixLink(na,nb,Map.of("_q0","_q0"),Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,cd),"old incomplete paid link rejected");
        var fast=capture.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=map(fp.getJSONObject("alphaMapping"));check(fm.equals(chosen),"same original paid mapping");
        check(CanonicalDistance.verifyFixedMatrixReplay(CanonicalDistance.prepare(p.a.normalizedForms()),CanonicalDistance.prepare(p.b.normalizedForms()),fm,fm,fp.getJSONObject("receipt"))==14,"Fast fixed original matrix14");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==16&&arm.getInt("quantifierDistance")==2&&arm.getInt("temporalDistance")==0&&arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"joint coverage16");
        var cmp=NativeOperationComparison.compare(p.a,p.b,ca,cb);var e=cmp.evidence();check(cmp.status()==NativeOperationComparison.Status.MATCHED,"ordered bound-call actions "+cmp.reason()+" "+e.opt("firstDifference"));check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all ordered native actions equal");check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"original source/call/binder/type correspondence checked");check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"full certification BLOCKED");
        var bad=copy(e.getJSONObject("diagnostic"));bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");malformed(p,bad,"foreign source");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("_q1","_q0");malformed(p,bad,"capturing/unpaid correspondence");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(0,"ONE");malformed(p,bad,"forged declaration kind");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",13);malformed(p,bad,"forged action cost");return e;
    }
    public static void main(String[] args)throws Exception{
        var out=args.length==0?Files.createTempDirectory("bound-call-"):Path.of(args[0]);Files.createDirectories(out);var p=prepare(Files.readString(SOURCE),"inv14","inv14c");var original=p.a.normalizedForms().get(0);var proof=BoundCallEvidence.from(original);
        check(supports(p.a.normalizedForms())&&supports(p.b.normalizedForms()),"actual endpoint admission");check(calls(original).size()==2,"two opaque calls, not unfolded");for(var n:calls(original))check(!ClosedCallEvidence.closed(n)&&proof.admits(n),"declared bound source evidence distinct from closed import");for(var n:calls(comparison(p.a)))check(proof.admits(n),"original/comparison argument correspondence");callPremises(p.a,2);
        var foreign=prepare(Files.readString(SOURCE),"inv14","inv14c");check(!proof.admits(variableCall(foreign.a.normalizedForms().get(0))),"foreign identical source module");
        for(var change:List.<Consumer<EGraphNode>>of(n->field(n,"semanticIdentity","other/module/getProjectStudents"),n->field(n,"callOccurrenceId",999L),n->field(n,"sourceOccurrenceLineage",-1L),n->field(n,"callArityAuthority","TYPECHECKED_IMPORT"),n->field(n,"exactAlloyType",variableCall(foreign.a.normalizedForms().get(0)).getExactAlloyType()),n->field(n.getChildren().get(0),"sourceOccurrenceLineage",-1L),n->field(n.getChildren().get(0),"alphaName","_q0"),n->field(n.getChildren().get(0),"semanticIdentity","var/foreign/slot:0"),n->field(n.getChildren().get(0),"exactAlloyType",null))){var n=variableCall(comparison(p.a));change.accept(n);check(!proof.admits(n),"callee/occurrence/module/type/binder substitution");}
        var badArity=variableCall(comparison(p.a));field(badArity,"declaredArity",2);reject(()->proof.admits(badArity),"declared arity corruption");
        var minus=calls(comparison(p.a)).stream().filter(n->n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.MINUS).findFirst().orElseThrow();var refs=new ArrayList<>(minus.getChildren().get(0).getChildClasses());Collections.swap(refs,0,1);field(minus.getChildren().get(0),"childClasses",refs);check(!proof.admits(minus),"difference argument order corruption");
        for(String fieldName:List.of("params","inheritedQuantiVars","phaseLocalBindingImports")){var f=comparison(p.a);field(f,fieldName,List.of(new Object()));check(!BoundCallEvidence.from(f).admits(variableCall(f)),"unsupported owner scope "+fieldName);}
        var temporal=comparison(p.a);field(temporal,"temporalOp",NormalForm.TemporalOp.ALWAYS);check(!BoundCallEvidence.from(temporal).admits(variableCall(temporal)),"temporal scope");
        // Malformed repeated-DAG evidence must be bounded before correspondence.
        // Build the synthetic DAG in one fresh arena, not across live endpoints.
        var budgetCall=variableCall(comparison(p.a));var seed=budgetCall.getChildren().get(0);
        var repeated=new EGraphNode(79999,EGraphNode.Opcode.VARIABLE,List.of(),false,0,false,EGraphNode.Metatype.ATOMIC,budgetCall.getSemanticProfile());
        repeated.setAlphaName(seed.getAlphaName());repeated.setSourceName(seed.getSourceName());repeated.setSemanticIdentity(seed.getSemanticIdentity());repeated.setExactAlloyType(seed.getExactAlloyType());
        for(int i=0;i<10;i++){var n=new EGraphNode(80000+i,EGraphNode.Opcode.MINUS,List.of(repeated,repeated),false,2,false,EGraphNode.Metatype.SET,budgetCall.getSemanticProfile());n.setExactAlloyType(repeated.getExactAlloyType());repeated=n;}
        field(budgetCall,"childClasses",List.of(repeated.getEClassRef()));reject(()->proof.admits(budgetCall),"expanded DAG argument budget");
        for(int d=0;d<2;d++){var old=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+d+".json")));Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse(),old).toString(2),StandardOpenOption.CREATE_NEW);}
        String base="sig A { r:set A } fun f[x,y:A]:set A {x+y} pred b {some A} ";
        var two=prepare(base+"pred a {all x:A | all y:A | x in f[x,y]}","a","b");var tf=two.a.normalizedForms().get(0);var tp=BoundCallEvidence.from(tf);check(supports(two.a.normalizedForms())&&tp.admits(calls(tf).get(0)),"general ordered two-argument declared call");
        var swapped=calls(comparison(two.a)).get(0);var sr=new ArrayList<>(swapped.getChildClasses());Collections.swap(sr,0,1);field(swapped,"childClasses",sr);check(!tp.admits(swapped),"same-type argument permutation rejected");var duplicate=calls(comparison(two.a)).get(0);field(duplicate,"childClasses",List.of(duplicate.getChildClasses().get(0),duplicate.getChildClasses().get(0)));check(!tp.admits(duplicate),"argument multiplicity substitution rejected");
        var repeatedArgs=prepare(base+"pred a {all x:A | x in f[x,x]}","a","b");check(calls(repeatedArgs.a.normalizedForms().get(0)).get(0).getChildren().size()==2&&supports(repeatedArgs.a.normalizedForms()),"genuine repeated argument roles retained");
        var local=prepare(base+"pred a {all x:A | x in x.r implies (one y:A | x in f[y,x])}","a","b");
        check(supports(local.a.normalizedForms())&&!FastLocalPaidScope.checkedCalls(local.a.normalizedForms().get(0)).isEmpty(),"retained source-linked local argument, not a free outer link");callPremises(local.a,1);
        for(var direction:List.of(local,local.reverse())){var nativeResult=NativeOperationComparison.compare(direction.a,direction.b,CanonicalAlloyPipeline.prepare(direction.a),CanonicalAlloyPipeline.prepare(direction.b));check(nativeResult.status()==NativeOperationComparison.Status.MATCHED,"local call independently replayed in original owner");}
        for(String text:List.of("pred a {all x:A | all y:A | x in f[x+y,y]}","pred a {all x:A | x in f[f[x,x],x]}","pred a[z:A] {all x:A | x in f[x,z]}")){
            var pair=prepare(base+text,"a","b");check(!supports(pair.a.normalizedForms()),"outside expression/owner slice "+text);check(NativeOperationComparison.compare(pair.a,pair.b,CanonicalAlloyPipeline.prepare(pair.a),CanonicalAlloyPipeline.prepare(pair.b)).status()==NativeOperationComparison.Status.UNCHECKABLE,"unsupported remains unchecked");}
        // The independent temporal checker is not this non-temporal admission route.
        var temporalPair=prepare(base+"pred a {all x:A | always x in f[x,x]}","a","b");
        check(!supports(temporalPair.a.normalizedForms()),"not a non-temporal paid admission");
        var temporalResult=NativeOperationComparison.compare(temporalPair.a,temporalPair.b,CanonicalAlloyPipeline.prepare(temporalPair.a),CanonicalAlloyPipeline.prepare(temporalPair.b));
        check(temporalResult.status()==NativeOperationComparison.Status.MATCHED,"existing temporal path remains checked");
        for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(temporalResult.evidence().getBoolean(flag),"temporal "+flag);
        var imported=prepare("open util/ordering[A] "+base+"pred a {all x:A | x in max[x]}","a","b");var importedForm=imported.a.normalizedForms().get(0);check(supports(imported.a.normalizedForms())&&BoundCallEvidence.from(importedForm).admits(calls(importedForm).get(0))&&!ClosedCallEvidence.closed(calls(importedForm).get(0)),"source-linked imported prefix argument, not a closed call");
        var controls=new JSONArray();for(var row:List.of(new String[]{"coursesNew/both/5TvX7MDJN22SSvqyR_inv13","inv13","10"},new String[]{"coursesNew/both/LJrfWwJdKiTdyytci_inv14","inv14","26"},new String[]{"coursesNew/both/5vxYh7NCpEPMmC47p_inv14","inv14","47"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"})){
            var pair=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");for(var dir:List.of(pair,pair.reverse()))check(Canonical.distance(dir.a,dir.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b))==Integer.parseInt(row[2]),"prior family "+row[0]);controls.put(row[0]);}
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("BoundCallPaidReplayTest: "+checks+" checks passed");
    }
}

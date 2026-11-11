package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** 3mNrs: exact empty-typed call arguments retain source shape, arity and binders. */
public final class EmptyCallArgumentPaidReplayTest {
    private static final Path SOURCE=Path.of("classified-data/coursesOld/both/3mNrsLxL8mPXSK6gH_inv13.als");
    private static int checks;
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static Pair prepare(String text,String left,String right)throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}
        return new Pair(ps.get(0),ps.get(1));
    }
    private static boolean supports(Canonical.Prepared p){try{var m=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment").getDeclaredMethod("supports",List.class);m.setAccessible(true);return (boolean)m.invoke(null,p.normalizedForms());}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static List<EGraphNode> nodes(EGraphNode root,EGraphNode.Opcode op){var out=new ArrayList<EGraphNode>();var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());var q=new ArrayDeque<EGraphNode>();q.add(root);while(!q.isEmpty()){var n=q.remove();if(!seen.add(n))continue;if(seen.size()>4096)throw new AssertionError("test traversal budget");if(n.getOpcode()==op)out.add(n);q.addAll(n.getChildren());}return out;}
    private static EGraphNode call(NormalForm f){return nodes(f.getMatrixEGraph(),EGraphNode.Opcode.CALL).get(0);}
    private static NormalForm comparison(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static int[] mapping(JSONArray j){int[] a=new int[j.length()];for(int i=0;i<a.length;i++)a[i]=j.getInt(i);return a;}
    private static Map<String,String> mapping(JSONObject j){var a=new TreeMap<String,String>();for(String k:j.keySet())a.put(k,j.getString(k));return a;}
    private static JSONObject node(EGraphNode n,int[] budget,int depth){
        check(--budget[0]>=0&&depth<48,"per-predicate evidence budget");var children=new JSONArray();for(var c:n.getChildren())children.put(node(c,budget,depth+1));
        return new JSONObject().put("operator",n.getOpcode()).put("lineage",n.getSourceOccurrenceLineage()).put("type",String.valueOf(n.getExactAlloyType())).put("sourceName",String.valueOf(n.getSourceName())).put("semanticIdentity",String.valueOf(n.getSemanticIdentity())).put("alphaName",String.valueOf(n.getAlphaName())).put("children",children);
    }
    private static JSONObject predicateEvidence(Canonical.Prepared p,String name){
        // compare() constructs the entire typed original-coordinate bridge before
        // replay. Self distance0 alone would not establish cross-path forms.
        var c=CanonicalAlloyPipeline.prepare(p);var cmp=NativeOperationComparison.compare(p,p,c,c);var e=cmp.evidence();
        check(cmp.status()==NativeOperationComparison.Status.MATCHED,"per-predicate typed canonical correspondence "+name+": "+cmp.reason());
        check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"per-predicate construction and full endpoint bridge");
        for(Object a:e.getJSONArray("fastActions"))check(((JSONObject)a).getInt("cost")==0,"self plan must retain every zero action");
        check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"per-predicate zero action correspondence");
        var f=p.normalizedForms().get(0);var q=new JSONArray();for(var v:f.getMatrixQuantiVars())q.put(new JSONObject().put("alpha",v.getName()).put("owner",v.getBindingPath()).put("slot",v.getDeBruijnKey()).put("kind",v.getQuantifier()).put("type",v.getExactAlloyType().stableString()));
        sourcePremises(p);
        return new JSONObject().put("predicate",name).put("constructionAndTypedBinderCorrespondence","CHECKED_IN_PROCESS").put("canonicalFormCorrespondence","CHECKED_SUPPORTED_NATIVE_SLICE").put("sourceBoundary","FROZEN_NORMALIZED_IR").put("publicOriginalSourceProof","BLOCKED").put("binders",q).put("original",node(f.getCertificationMatrixEGraph(),new int[]{512},0)).put("fastComparison",node(comparison(p).getMatrixEGraph(),new int[]{512},0)).put("checkedBridgeAndNativeEvidence",e);
    }
    private static void sourcePremises(Canonical.Prepared p){
        var e=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());e.requireRepairProjectionSources(p.normalizedForms());var cs=e.semanticArtifact().callOccurrenceCertificates();check(cs.size()==1,"one original imported CALL premise");
        var n=call(p.normalizedForms().get(0));var m=CallMetadata.require(n);var c=cs.get(0);
        check(c.arityAuthority()==m.authority()&&c.occurrenceId()==m.occurrenceId()&&c.qualifiedCallee().equals(m.identity())&&c.kind().equals(m.kind())&&c.declaredArity()==m.arity(),"actual qualified callee/arity/authority/occurrence premise");
        check(c.sourceEndpoint().operator().outputType().equals(AlloyTypeBridge.graphType(n.getExactAlloyType()))&&c.orderedArguments().equals(c.sourceEndpoint().ports()),"actual typed result and ordered source ports");
        reject(()->e.semanticArtifact().withCallOccurrenceCertificates(List.of()),"missing original call premise");
        if(m.arity()>0)reject(()->CallOccurrenceCertificate.create(m,c.sourcePath(),c.sourceEndpoint(),List.of()),"missing ordered argument premise");
    }
    private static JSONObject replay(Pair p,JSONObject before){
        var va=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var vb=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);sourcePremises(p.a);sourcePremises(p.b);
        var ca=CanonicalAlloyPipeline.prepare(p.a);var cb=CanonicalAlloyPipeline.prepare(p.b);var capture=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);var cert=capture.getJSONObject("certified");var cp=cert.getJSONArray("phases").getJSONObject(0);var cm=mapping(cp.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(va,vb,cm,cm,cp.getJSONObject("receipt"))==13,"shorter certified original endpoint matrix13 first");
        if(before!=null){check(before.getJSONObject("certified").getJSONArray("phases").similar(cert.getJSONArray("phases")),"certified matrix unchanged from frozen JAR");check(before.getJSONObject("certified").getJSONObject("declarationSlice").similar(cert.getJSONObject("declarationSlice")),"certified declaration unchanged from frozen JAR");}
        var na=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var nb=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var chosen=new TreeMap<String,String>();for(int i=0;i<cm.length;i++)if(cm[i]>=0)chosen.put(na.get(i),nb.get(cm[i]));
        var dr=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");var aa=NestedPaidDeclarationReplayTest.atoms(va.phases().get(0),dr.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(vb.phases().get(0),dr.getJSONArray("target"));
        check(new DeclarationMatrixLink(na,nb,chosen,Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,dr)==1,"original joint ALL keep0 and SOME/ALL update1");
        reject(()->new DeclarationMatrixLink(na,nb,Map.of("_q0","_q0"),Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,dr),"old incomplete paid mapping rejected");
        var fast=capture.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=mapping(fp.getJSONObject("alphaMapping"));check(fm.equals(chosen)&&fm.equals(Map.of("_q0","_q0","_q1","_q1")),"both independently select original paid map");
        check(CanonicalDistance.verifyFixedMatrixReplay(CanonicalDistance.prepare(p.a.normalizedForms()),CanonicalDistance.prepare(p.b.normalizedForms()),fm,fm,fp.getJSONObject("receipt"))==13,"Fast original fixed matrix13");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==14&&arm.getInt("temporalDistance")==0&&arm.getInt("quantifierDistance")==1&&arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"joint native coverage14");
        var cmp=NativeOperationComparison.compare(p.a,p.b,ca,cb);var e=cmp.evidence();check(cmp.status()==NativeOperationComparison.Status.MATCHED,"ordered native actions "+cmp.reason()+" "+e.opt("firstDifference"));check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all native actions and zero actions ordered equally");
        check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"original typed call/binder/source correspondence checked");check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"public certification BLOCKED");
        for(int k=0;k<4;k++){var bad=copy(e.getJSONObject("diagnostic"));switch(k){case 0 -> bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");case 1 -> bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("_q1","_q0");case 2 -> bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(1).getJSONArray("tuple").put(0,"ONE");case 3 -> bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",12);}check(NativeOperationComparison.compareCaptured(p.a,p.b,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"foreign endpoint/capturing map/kind/action cost "+k);}
        return e;
    }
    public static void main(String[] args)throws Exception {
        var out=Path.of(args[0]);Files.createDirectories(out);var p=prepare(Files.readString(SOURCE),"inv13","inv13c");var f=p.a.normalizedForms().get(0);var proof=BoundCallEvidence.from(f);var original=call(f);
        check(supports(p.a)&&supports(p.b),"both original paid admissions");check(CallMetadata.require(original).authority()==CallSymbol.ArityAuthority.TYPECHECKED_IMPORT&&!ClosedCallEvidence.closed(original)&&proof.admits(original)&&proof.admits(call(comparison(p.a))),"bound imported source/comparison call, not closed");
        check(original.getChildren().get(0).getOpcode()==EGraphNode.Opcode.JOIN&&nodes(original,EGraphNode.Opcode.VARIABLE).size()==1,"actual nested JOIN argument retains one prefix owner");
        var empty=original.getChildren().get(0).getExactAlloyType();
        check(empty.kind()==ExactAlloyType.Kind.EMPTY_RELATION&&empty.stableString().equals("EMPTY_RELATION[arity=1]")&&empty.hasParserAuthenticatedAncestry()&&empty.sharesParserModuleAuthorityWith(original.getExactAlloyType()),"actual empty unary argument, not unknown or absent type");
        var foreign=prepare(Files.readString(SOURCE),"inv13","inv13c");check(!proof.admits(call(foreign.a.normalizedForms().get(0))),"same spelling foreign module");
        var emptyTwo=ExactAlloyType.parserCertifiedCartesianProduct(List.of(empty,f.getMatrixQuantiVars().get(0).getExactAlloyType()));
        check(emptyTwo.kind()==ExactAlloyType.Kind.EMPTY_RELATION&&emptyTwo.sharesParserModuleAuthorityWith(empty)&&!emptyTwo.equals(empty),"same-module authenticated different empty arity control");
        for(var type:List.of(ExactAlloyType.emptyRelation(1),emptyTwo,original.getExactAlloyType(),call(foreign.a.normalizedForms().get(0)).getChildren().get(0).getExactAlloyType())){var n=call(comparison(p.a));field(n.getChildren().get(0),"exactAlloyType",type);check(!proof.admits(n),"unauthenticated/different-arity/nonempty/foreign empty argument type");}
        var emptyResult=call(comparison(p.a));field(emptyResult,"exactAlloyType",empty);check(!proof.admits(emptyResult),"argument admission does not admit or erase empty call result types");
        var nullType=call(comparison(p.a));field(nullType.getChildren().get(0),"exactAlloyType",null);check(!proof.admits(nullType),"missing empty argument type");
        for(var change:List.<Consumer<EGraphNode>>of(n->field(n,"semanticIdentity","util/ordering<Person>/max"),n->field(n,"callOccurrenceId",999L),n->field(n,"sourceOccurrenceLineage",-1L),n->field(n,"callArityAuthority","DECLARATION"),n->field(n,"exactAlloyType",call(foreign.a.normalizedForms().get(0)).getExactAlloyType()),n->field(nodes(n,EGraphNode.Opcode.VARIABLE).get(0),"alphaName","_q1"),n->field(nodes(n,EGraphNode.Opcode.VARIABLE).get(0),"semanticIdentity","var/foreign/slot:0"),n->field(nodes(n,EGraphNode.Opcode.VARIABLE).get(0),"exactAlloyType",null),n->field(n.getChildren().get(0),"sourceOccurrenceLineage",-1L))){var n=call(comparison(p.a));change.accept(n);check(!proof.admits(n),"source/callee/authority/owner/type/argument corruption");}
        var badArity=call(comparison(p.a));field(badArity,"declaredArity",2);reject(()->proof.admits(badArity),"arity corruption");
        var order=call(comparison(p.a));var join=order.getChildren().get(0);var refs=new ArrayList<>(join.getChildClasses());Collections.swap(refs,0,1);field(join,"childClasses",refs);check(!proof.admits(order),"JOIN order not silently changed");
        for(String scope:List.of("params","inheritedQuantiVars","phaseLocalBindingImports")){var c=comparison(p.a);field(c,scope,List.of(new Object()));check(!BoundCallEvidence.from(c).admits(call(c)),"unsupported owner scope "+scope);}
        var t=comparison(p.a);field(t,"temporalOp",NormalForm.TemporalOp.ALWAYS);check(!BoundCallEvidence.from(t).admits(call(t)),"temporal owner rejected");
        for(int d=0;d<2;d++){var before=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+d+".json")));Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse(),before).toString(2),StandardOpenOption.CREATE_NEW);}
        for(var e:List.of(Map.entry("inv13",p.a),Map.entry("inv13c",p.b)))Files.writeString(out.resolve("predicate-"+e.getKey()+".json"),predicateEvidence(e.getValue(),e.getKey()).toString(2),StandardOpenOption.CREATE_NEW);
        String base="open util/ordering[A] sig A {r:set A} pred b {some A} ";
        for(String arg:List.of("x","A-x","x.r")){var q=prepare(base+"pred a {all x:A | x in max["+arg+"]}","a","b");var g=q.a.normalizedForms().get(0);check(supports(q.a)&&!ClosedCallEvidence.closed(call(g))&&BoundCallEvidence.from(g).admits(call(g))&&BoundCallEvidence.from(g).admits(call(comparison(q.a))),"general original imported argument "+arg);sourcePremises(q.a);}
        String empties="open util/ordering[A] sig A {r:A -> B} sig B {} fun f[x:set A]:set A {x} ";
        for(String fun:List.of("max","f")){String body="all x:A | x in "+fun+"[x.r.A]";var q=prepare(empties+"pred a {"+body+"} pred b {"+body+"}","a","b");
            check(supports(q.a)&&supports(q.b)&&call(q.a.normalizedForms().get(0)).getChildren().get(0).getExactAlloyType().kind()==ExactAlloyType.Kind.EMPTY_RELATION,"general declared/imported empty JOIN argument");
            for(var dir:List.of(q,q.reverse()))check(NativeOperationComparison.compare(dir.a,dir.b,CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b)).status()==NativeOperationComparison.Status.MATCHED,"AST-identical-body control checked, not excluded from predicate coverage");
            for(var e:List.of(Map.entry("a",q.a),Map.entry("b",q.b)))Files.writeString(out.resolve("identical-"+fun+"-"+e.getKey()+".json"),predicateEvidence(e.getValue(),e.getKey()).toString(2),StandardOpenOption.CREATE_NEW);
        }
        var local=prepare(base+"pred a {all x:A | x in x.r implies (one y:A | x in max[y])}","a","b");
        check(supports(local.a)&&!FastLocalPaidScope.checkedCalls(local.a.normalizedForms().get(0)).isEmpty(),"retained imported local argument with actual owner");sourcePremises(local.a);
        for(var direction:List.of(local,local.reverse()))check(NativeOperationComparison.compare(direction.a,direction.b,CanonicalAlloyPipeline.prepare(direction.a),CanonicalAlloyPipeline.prepare(direction.b)).status()==NativeOperationComparison.Status.MATCHED,"local imported call replay both directions");
        for(var control:List.of(Map.entry("pred a[z:A] {all x:A | max[x] in z}",NativeOperationComparison.Status.UNCHECKABLE),
                Map.entry("pred a {all x:A | always x in max[x]}",NativeOperationComparison.Status.MATCHED),
                Map.entry("pred a {all x:A | x in max[min[x]]}",NativeOperationComparison.Status.UNCHECKABLE),
                Map.entry("pred a {all x:A | x in max[x+x.r]}",NativeOperationComparison.Status.UNCHECKABLE))) {
            var q=prepare(base+control.getKey(),"a","b");check(!supports(q.a),"legacy paid-prefix helper scope unchanged");
            var result=NativeOperationComparison.compare(q.a,q.b,CanonicalAlloyPipeline.prepare(q.a),CanonicalAlloyPipeline.prepare(q.b));
            check(result.status()==control.getValue(),"retained expression gaps versus checked temporal imported-call owner");
            if(control.getValue()==NativeOperationComparison.Status.MATCHED)check(result.evidence().getBoolean("allNativeEntriesRetained"),"temporal bound-call paid/zero operations retained");
        }
        var controls=new JSONArray();for(var row:List.of(new String[]{"coursesOld/both/2EKgnipH28DLyp8uK_inv13","inv13","15"},new String[]{"coursesNew/correct/wnqMPtpNxnPYzru3g_inv14","inv14","16"},new String[]{"coursesNew/both/5TvX7MDJN22SSvqyR_inv13","inv13","10"},new String[]{"coursesNew/both/LJrfWwJdKiTdyytci_inv14","inv14","26"},new String[]{"coursesNew/both/5vxYh7NCpEPMmC47p_inv14","inv14","47"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"})){
            var q=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");for(var dir:List.of(q,q.reverse()))check(Canonical.distance(dir.a,dir.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b))==Integer.parseInt(row[2]),"prior family cost "+row[0]);controls.put(row[0]);}
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("EmptyCallArgumentPaidReplayTest: "+checks+" checks passed");
    }
}

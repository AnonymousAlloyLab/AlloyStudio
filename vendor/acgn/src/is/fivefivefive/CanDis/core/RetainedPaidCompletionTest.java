package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.*;
import is.fivefivefive.CanDis.metric.*;
import parser.ast.nodes.ModelUnit;

/** One targeted replay/authority test, not an invocation of prior test mains. */
public final class RetainedPaidCompletionTest {
    private static int checks;
    private static void check(boolean b,String why) { checks++; if(!b) throw new AssertionError(why); }
    private static void reject(Runnable r,String why) {
        checks++; try { r.run(); } catch(IllegalArgumentException|IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static Object invoke(Class<?> c,String name,Class<?>[] types,Object... args) throws Exception {
        var method=c.getDeclaredMethod(name,types); method.setAccessible(true);
        try { return method.invoke(null,args); }
        catch(java.lang.reflect.InvocationTargetException e) {
            if(e.getCause() instanceof RuntimeException r) throw r;
            if(e.getCause() instanceof Error error) throw error;
            throw e;
        }
    }
    private static List<Canonical.Prepared> prepare(String text,String... names) throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text); var result=new ArrayList<Canonical.Prepared>();
        for(var name:names) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module); visitor.visit(new ModelUnit(null,module),null);
            result.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private static List<NormalForm> forms(Canonical.Prepared prepared) throws Exception {
        var method=Canonical.Prepared.class.getDeclaredMethod("normalizedForms"); method.setAccessible(true);
        return (List<NormalForm>)method.invoke(prepared);
    }
    private static void field(Object x,String name,Object value) {
        try { var f=x.getClass().getDeclaredField(name); f.setAccessible(true); f.set(x,value); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static List<EGraphNode> nodes(EGraphNode n) {
        var out=new ArrayList<EGraphNode>(); out.add(n); for(var c:n.getChildren()) out.addAll(nodes(c)); return out;
    }
    private static EGraphNode owner(NormalForm f) {
        return nodes(f.getMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.EXISTS
                && n.getChildren().size()==2 && n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GENERICRELDECL)
                .findFirst().orElseThrow();
    }
    private static void corrupt(NormalForm source,Consumer<NormalForm> edit,String why) {
        var copy=NormalForm.fastComparisonViews(List.of(source)).get(0); edit.accept(copy);
        check(!FastNestedPaidScope.retainedExistentials(copy),why);
    }
    @SuppressWarnings("unchecked")
    private static List<DeclarationReplay.Atom> atoms(NormalForm f) throws Exception {
        return (List<DeclarationReplay.Atom>)invoke(DeclarationOrbitShorterReplayTest.class,"declarations",
                new Class<?>[]{NormalForm.class},f);
    }
    @SuppressWarnings("unchecked")
    private static MatrixReplay<EGraphNode> checker(Map<String,String> label,Map<String,String> cost) throws Exception {
        return (MatrixReplay<EGraphNode>)invoke(DeclarationOrbitShorterReplayTest.class,"checker",new Class<?>[]{Map.class,Map.class},label,cost);
    }
    private static Map<String,String> map(JSONObject object) {
        var m=new TreeMap<String,String>(); for(var k:object.keySet()) m.put(k,object.getString(k)); return m;
    }
    private static JSONObject direction(List<Canonical.Prepared> endpoints,boolean reverse) throws Exception {
        var left=endpoints.get(reverse?1:0); var right=endpoints.get(reverse?0:1);
        var a=forms(left).get(0); var b=forms(right).get(0);
        // Reuse only the fixed source/certificate checker, never its test main.
        var av=(RepairView)invoke(DeclarationOrbitSourceTest.class,"checkedProjection",
                new Class<?>[]{Canonical.Prepared.class,Canonical.Prepared.class},left,right);
        var bv=(RepairView)invoke(DeclarationOrbitSourceTest.class,"checkedProjection",
                new Class<?>[]{Canonical.Prepared.class,Canonical.Prepared.class},right,left);
        var report=DistanceReplayDiagnostics.capture(left,right); var fast=report.getJSONObject("fast");
        var cert=report.getJSONObject("certified");
        check(fast.getInt("distance")==27 && cert.getInt("distance")==27,"unchanged native27/27");
        check(fast.getBoolean("numericalCostCoverage") && !report.getBoolean("complete")
                && !report.getBoolean("publicSequenceLinked"),"native coverage, public proof still BLOCKED");
        check(!FastPaidDeclarationAlignment.supportsPair(forms(left),forms(right))
                && !fast.has("selectedPaidDeclarationCorrespondence"),"no scalar candidate expansion or fabricated selected paid map");
        var phase=fast.getJSONArray("phases").getJSONObject(0); var selected=map(phase.getJSONObject("alphaMapping"));
        check(selected.equals(reverse?Map.of("_q1","_q0"):Map.of("_q0","_q1")),"original partial matrix choice retained");
        var component=fast.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0);
        var receipt=component.getJSONObject("receipt"); var completion=receipt.getJSONObject("matrixCorrespondenceCompletion");
        var completed=map(completion.getJSONObject("completedMapping"));
        check(completed.equals(Map.of("_q0","_q1","_q1","_q0")),"actual paid complement in original coordinates");
        check(completed.equals(FastRetainedDeclarationCompletion.complete(a.getMatrixQuantiVars(),b.getMatrixQuantiVars(),selected)),
                "single missing pair determined from original outer occurrence census");
        var ac=NormalForm.fastComparisonViews(List.of(a)).get(0); var bc=NormalForm.fastComparisonViews(List.of(b)).get(0);
        var matrix=phase.getJSONObject("receipt");
        check(checker(selected,selected).verify(ac.getMatrixEGraph(),bc.getMatrixEGraph(),matrix)==26,"original matrix derivation replay");
        check(checker(selected,completed).verify(ac.getMatrixEGraph(),bc.getMatrixEGraph(),matrix)==26,"unchanged per-action body charges under completion, search disabled");
        @SuppressWarnings("unchecked") var cc=(MatrixReplay<RepairView.Node>)invoke(DeclarationOrbitSourceTest.class,"checker",new Class<?>[]{int[].class},(Object)new int[]{1,0});
        check(cc.verify(av.phases().get(0).matrix(),bv.phases().get(0).matrix(),cert.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"))==26,
                "certified source-linked fixed-choice replay");
        var as=atoms(a); var bs=atoms(b);
        if(reverse) Collections.swap(bs,0,1); else Collections.swap(as,0,1);
        check(DeclarationReplay.verify(as,bs,receipt)==1,"original-coordinate declaration replay");
        var an=as.stream().map(x->new JSONArray(x.admission()).getString(0)).toList();
        var bn=bs.stream().map(x->new JSONArray(x.admission()).getString(0)).toList();
        var joint=new DeclarationMatrixLink(an,bn,completed,Set.copyOf(an),Set.copyOf(bn));
        check(joint.verify(as,bs,receipt)==1,"every keep/delete/insert linked to completed matrix mapping");
        var permutation=component.getJSONObject("connectingPermutation");
        check(permutation.getJSONArray(reverse?"target":"source").similar(new JSONArray(List.of(1,0)))
                && permutation.getJSONArray(reverse?"source":"target").similar(new JSONArray(List.of(0,1))),"correct source vs target exchange");
        var ordered=fast.getJSONArray("orderedDiagnosticActions");
        check(ordered.getJSONObject(0).getString("phaseKind").equals("declarationPermutation"),"actual native sequence starts with admitted exchange");
        reject(()->new DeclarationMatrixLink(an,bn,selected,Set.copyOf(an),Set.copyOf(bn)).verify(as,bs,receipt),"omitted paid link rejected");
        reject(()->new DeclarationMatrixLink(an,bn,Map.of("_q0","_q0","_q1","_q1"),Set.copyOf(an),Set.copyOf(bn)).verify(as,bs,receipt),"wrong completion rejected");
        var bad=new JSONObject(receipt.toString()); bad.getJSONArray("source").getJSONObject(0).put("admission","foreign owner");
        reject(()->joint.verify(as,bs,bad),"source authority corruption");
        var deleted=new JSONObject(receipt.toString()); deleted.getJSONArray("operations").getJSONObject(0).put("rightIndex",-1);
        reject(()->joint.verify(as,bs,deleted),"mapped endpoint deleted");
        var uncharged=new JSONObject(receipt.toString());
        for(Object item:uncharged.getJSONArray("operations")) if(((JSONObject)item).getInt("cost")==1) ((JSONObject)item).put("cost",0);
        reject(()->joint.verify(as,bs,uncharged),"paid update cannot be relabelled keep");
        var changedMatrix=new JSONObject(matrix.toString()); changedMatrix.put("cost",25);
        var verifier=checker(selected,completed);
        reject(()->verifier.verify(ac.getMatrixEGraph(),bc.getMatrixEGraph(),changedMatrix),"matrix charge corruption");
        reject(()->verifier.verify(bc.getMatrixEGraph(),ac.getMatrixEGraph(),matrix),"body endpoint substitution");
        check(FastNestedPaidScope.retainedExistentialOwners(a).size()+FastNestedPaidScope.retainedExistentialOwners(b).size()==2,
                "two live local owners retained; historical allocator seeds are not source occurrences");
        var comparison=(JSONObject)invoke(ScopedPaidAdmissionReplayTest.class,"matrixComparison",
                new Class<?>[]{EGraphNode.class,EGraphNode.class,NormalForm.class,NormalForm.class,
                        RepairView.Phase.class,RepairView.Phase.class,JSONObject.class,JSONObject.class},
                ac.getMatrixEGraph(),bc.getMatrixEGraph(),a,b,av.phases().get(0),bv.phases().get(0),matrix,
                cert.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"));
        var certifiedDeclaration=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
        var fastDecl=(JSONArray)invoke(ScopedPaidAdmissionReplayTest.class,"declActions",new Class<?>[]{JSONObject.class},receipt);
        var certDecl=(JSONArray)invoke(ScopedPaidAdmissionReplayTest.class,"declActions",new Class<?>[]{JSONObject.class},certifiedDeclaration);
        // Source/certificate checks above bind these reordered slots to the
        // original ordinals; tuple text is not used to admit their exchange.
        for(var side:List.of("source","target")) {
            var list=side.equals("source")?as:bs;
            var wire=certifiedDeclaration.getJSONArray(side);
            for(int i=0;i<list.size();i++) {
                var occurrence=list.get(i).occurrence(); int ordinal=Integer.parseInt(occurrence.substring(occurrence.lastIndexOf('/')+1));
                check(new JSONArray(wire.getJSONObject(i).getString("occurrence")).getInt(4)==ordinal,"cross-arm original declaration ordinal "+side+" "+i);
            }
        }
        comparison.put("orderedDeclarationActionsEqual",fastDecl.similar(certDecl))
                .put("fastDeclarationActions",fastDecl).put("certifiedDeclarationActions",certDecl)
                .put("fastPermutation",permutation).put("certifiedPermutation",cert.getJSONObject("selectedDeclarationPermutation"));
        report.put("crossArmSequenceComparison",comparison);
        System.out.println((reverse?"reverse":"forward")+" cross-arm sequence: "+comparison);
        return report;
    }
    private static void guards(List<Canonical.Prepared> endpoints) throws Exception {
        var source=forms(endpoints.get(0)).get(0); var target=forms(endpoints.get(1)).get(0);
        var authority=new FastRetainedDeclarationCompletion.Authority(source,source.getMatrixQuantiVars());
        check(source.getLocalQuantiVars().size()>FastNestedPaidScope.retainedExistentialOwners(source).size(),
                "incident distinguishes historical seeds from live source owners");
        check(authority.admits(new int[]{1,0}),"full source-certified transposition");
        check(!authority.admits(new int[]{0,0}) && !authority.admits(new int[]{1}),"bijection/coverage corruption");
        check(!new FastRetainedDeclarationCompletion.Authority(target,target.getMatrixQuantiVars()).admits(new int[]{1,0}),"cross-carrier exchange rejected");
        var original=source.getMatrixQuantiVars().get(0); var foreign=new QuantiVar(original.getId(),original.getName(),original.getTypeName());
        for(var f:QuantiVar.class.getDeclaredFields()) {
            if(java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true); f.set(foreign,f.get(original));
        }
        check(foreign.equals(original) && foreign!=original,"same-id forged slot exercises nonidentity equality");
        var order=new ArrayList<>(source.getMatrixQuantiVars()); order.set(0,foreign);
        reject(()->new FastRetainedDeclarationCompletion.Authority(source,order),"same-id foreign order membership");
        var copy=NormalForm.fastComparisonViews(List.of(source)).get(0); field(copy,"matrixQuantiVars",List.copyOf(order));
        var tc=NormalForm.fastComparisonViews(List.of(target)).get(0);
        reject(()->FastRetainedDeclarationCompletion.plans(source,target,copy,tc,source.getMatrixQuantiVars(),target.getMatrixQuantiVars(),Map.of("_q0","_q1"),1),
                "same-id foreign comparison binding");
        corrupt(source,f->field(owner(f),"sourceOccurrenceLineage",-1L),"foreign local owner");
        corrupt(source,f->field(owner(f).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign declaration occurrence");
        corrupt(source,f->field(owner(f).getChildren().get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign domain occurrence");
        corrupt(source,f->field(owner(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",null),"missing local type");
        corrupt(source,f->field(owner(f).getChildren().get(0).getChildren().get(1),"alphaName","_q0"),"local capture");
        corrupt(source,f->field(owner(f),"opcode",EGraphNode.Opcode.ONE),"ONE not existential completion");
        corrupt(source,f->field(owner(f).getChildren().get(0),"opcode",EGraphNode.Opcode.DISJ),"disjoint local declaration");
        corrupt(source,f->field(owner(f).getChildren().get(0).getChildren().get(0),"opcode",EGraphNode.Opcode.SETOF),"set-valued local declaration");
        corrupt(source,f->field(f,"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal owner not admitted");
        corrupt(source,f->field(f,"params",source.getMatrixQuantiVars()),"parameter boundary");
        corrupt(source,f->field(f,"inheritedQuantiVars",source.getMatrixQuantiVars()),"inherited owner boundary");
        var foreignSource=forms(prepare(Files.readString(DeclarationOrbitShorterReplayTest.SOURCE),"inv14").get(0)).get(0);
        corrupt(source,f->field(f,"certificationMatrixEGraphRoot",foreignSource.getCertificationMatrixEGraph()),"foreign source ledger");
        var foreignType=owner(foreignSource).getChildren().get(0).getChildren().get(1).getExactAlloyType();
        corrupt(source,f->field(owner(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",foreignType),"foreign parser module");
        check(FastRetainedDeclarationCompletion.plans(source,source,source,source,source.getMatrixQuantiVars(),source.getMatrixQuantiVars(),Map.of("_q0","_q1"),1).isEmpty(),
                "no new local/local completion");
        reject(()->FastRetainedDeclarationCompletion.complete(source.getMatrixQuantiVars(),target.getMatrixQuantiVars(),Map.of("foreign","_q1")),"foreign matrix coordinate");
        reject(()->FastRetainedDeclarationCompletion.complete(source.getMatrixQuantiVars(),target.getMatrixQuantiVars(),Map.of()),"multiple holes unsupported");
    }
    public static void main(String[] args) throws Exception {
        if(args.length>1) throw new IllegalArgumentException("[NEW_OUTPUT_JSON]");
        var endpoints=prepare(Files.readString(DeclarationOrbitShorterReplayTest.SOURCE),"inv14","inv14c");
        var out=new JSONObject().put("forward",direction(endpoints,false)).put("reverse",direction(endpoints,true));
        guards(endpoints);
        var controls=new JSONArray();
        for(var fixture:List.of(new String[]{"coursesOld","xJnitBpFv9K74WNvf_inv14","inv14","49"},
                new String[]{"classroom_fol","JoNXaXfKNJzGh3BFa_inv12","inv12","22"},
                new String[]{"classroom_fol","YKy8LYsKdapW3xqbn_inv14","inv14","20"})) {
            var pair=prepare(Files.readString(Path.of("classified-data",fixture[0],"both",fixture[1]+".als")),fixture[2],fixture[2]+"c");
            for(int i=0;i<2;i++) {
                var report=DistanceReplayDiagnostics.capture(pair.get(i),pair.get(1-i));
                for(var arm:List.of("fast","certified")) {
                    var r=report.getJSONObject(arm);
                    check(r.getInt("distance")==Integer.parseInt(fixture[3]) && r.getBoolean("numericalCostCoverage"),"prior family native coverage "+fixture[1]+" "+arm+" "+i);
                    check(!r.toString().contains("SINGLE_PAID_OUTER_LINK_RETAINED_EXISTS"),"prior family does not use new completion");
                }
                controls.put(new JSONObject().put("source",fixture[1]).put("reverse",i==1).put("report",report));
            }
        }
        out.put("controls",controls).put("checks",checks).put("complete",false).put("certificationStatus","BLOCKED");
        if(args.length==1) Files.writeString(Path.of(args[0]),out.toString(2),StandardOpenOption.CREATE_NEW);
        for(var direction:List.of("forward","reverse")) {
            var comparison=out.getJSONObject(direction).getJSONObject("crossArmSequenceComparison");
            check(comparison.getBoolean("orderedMatrixActionsEqual") && comparison.getBoolean("orderedDeclarationActionsEqual"),
                    "Remaining ordered native sequence gap "+direction+": "+comparison);
        }
        System.out.println("RetainedPaidCompletionTest: "+checks+" checks passed; both native27 receipts complete numerically, public/wire/Java-Lean BLOCKED");
    }
}

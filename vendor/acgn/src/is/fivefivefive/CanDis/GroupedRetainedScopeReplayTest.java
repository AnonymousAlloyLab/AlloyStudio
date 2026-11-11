package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** LJrf: a grouped outer owner does not erase distinct slots or a retained local owner. */
public final class GroupedRetainedScopeReplayTest {
    private static final Path SOURCE=Path.of("classified-data/coursesNew/both/LJrfWwJdKiTdyytci_inv14.als");
    private static int checks;
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static Object invoke(String owner,String name,Class<?>[] types,Object... args){try{var m=Class.forName("is.fivefivefive.CanDis.core."+owner).getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static boolean supports(List<NormalForm> forms){return (boolean)invoke("FastPaidDeclarationAlignment","supports",new Class[]{List.class},forms);}
    private static Pair prepare(String text,String left,String right)throws Exception{
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();for(String name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(ps.get(0),ps.get(1));
    }
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static EGraphNode owner(NormalForm f){var pending=new ArrayDeque<EGraphNode>();pending.add(f.getMatrixEGraph());int budget=4096;while(!pending.isEmpty()){if(--budget<0)throw new AssertionError("node budget");var n=pending.remove();if(n.getChildren().size()==2&&n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GENERICRELDECL)return n;pending.addAll(n.getChildren());}throw new AssertionError("missing retained owner");}
    private static void corrupt(Pair p,Consumer<NormalForm> edit,String why){var forms=CanonicalDistance.prepare(p.a.normalizedForms()).comparisonForms();edit.accept(forms.get(0));check(!supports(forms),why);}
    private static QuantiVar bindingCopy(QuantiVar q){var c=new QuantiVar(q.getId(),q.getName(),q.getOriginalName(),q.getTypeName());q.getOriginalNames().forEach(c::addOriginalName);c.setCarrierTypeName(q.getCarrierTypeName());c.setBindingPath(q.getBindingPath());c.setDeBruijnKey(q.getDeBruijnKey());c.setQuantifier(q.getQuantifier());c.setCardinality(q.getCardinality());c.setDisjointnessClass(q.getDisjointnessClass());c.mergeExactAlloyType(q.getExactAlloyType());return c;}
    private static void corruptBinding(Pair p,Consumer<QuantiVar> edit,String why){corrupt(p,f->{var qs=new ArrayList<>(f.getMatrixQuantiVars());var q=bindingCopy(qs.get(1));edit.accept(q);q.freezeForCertification();qs.set(1,q);field(f,"matrixQuantiVars",List.copyOf(qs));},why);}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static int[] map(JSONArray j){int[] a=new int[j.length()];for(int i=0;i<a.length;i++)a[i]=j.getInt(i);return a;}
    private static Map<String,String> map(JSONObject j){var m=new TreeMap<String,String>();for(String k:j.keySet())m.put(k,j.getString(k));return m;}
    private static void malformed(Pair p,JSONObject j,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,j).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    private static void sourcePremises(Canonical.Prepared p){
        var f=p.normalizedForms().get(0);var qs=f.getMatrixQuantiVars();check(qs.size()==2&&qs.get(0).getBindingPath().equals(qs.get(1).getBindingPath()),"actual grouped outer owner");
        for(int i=0;i<2;i++){var q=qs.get(i);check(q.getQuantifier()==QuantiVar.Quantifier.ALL&&q.getCardinality()==QuantiVar.Cardinality.ONE&&q.getDisjointnessClass()==0&&q.getDeBruijnKey().equals(q.getBindingPath()+"#"+i+":ONE:Person"),"distinct original source slot "+i);}
        var e=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());e.requireRepairProjectionSources(p.normalizedForms());check(e.localBinderDescriptors().size()==1,"one retained local descriptor");
        for(var entry:e.localBinderDescriptors().entrySet()){
            var n=entry.getKey();var d=entry.getValue();check(n.getOpcode()==EGraphNode.Opcode.LONE&&d.coordinates().size()==1,"source LONE owner retained");
            var c=d.coordinates().get(0);check(c.quantifier().equals("LONE")&&c.multiplicity().equals("ONE")&&c.domain().toString().contains("alloy/signature/Project")&&c.type().equals(GraphType.constructor("AlloyCarrier",List.of(GraphType.constructor("Project")))),"exact local kind/domain/type premise");
            check(e.localBinderSourceCoordinates().get(n).size()==1,"exact local coordinate coverage");for(var proof:d.automorphisms().generatorCertificates())CertificateVerifier.verify(proof);
        }
        @SuppressWarnings("unchecked") var admission=(BiPredicate<Integer,Integer>)invoke("FastDeclarationPermutation","admission",new Class[]{NormalForm.class,List.class},f,qs);
        check(!admission.test(0,1),"retained local free exchange remains excluded");
        var orders=(List<?>)invoke("FastGroupedDeclarationOrbit","orders",new Class[]{NormalForm.class,NormalForm.class,List.class},f,CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0),qs);
        check(orders.size()==1,"grouped retained orbit remains identity-only");
    }
    private static JSONObject replay(Pair p,JSONObject old){
        var va=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var vb=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);
        var ca=CanonicalAlloyPipeline.prepare(p.a);var cb=CanonicalAlloyPipeline.prepare(p.b);var capture=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);var cert=capture.getJSONObject("certified");var cp=cert.getJSONArray("phases").getJSONObject(0);var cm=map(cp.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(va,vb,cm,cm,cp.getJSONObject("receipt"))==24,"shorter original certified matrix24 first");
        if(old!=null){check(old.getJSONObject("certified").getJSONArray("phases").similar(cert.getJSONArray("phases")),"certified matrix unchanged");check(old.getJSONObject("certified").getJSONObject("declarationSlice").similar(cert.getJSONObject("declarationSlice")),"certified declaration unchanged");}
        var na=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var nb=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var chosen=new TreeMap<String,String>();for(int i=0;i<cm.length;i++)if(cm[i]>=0)chosen.put(na.get(i),nb.get(cm[i]));
        var cd=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");var aa=NestedPaidDeclarationReplayTest.atoms(va.phases().get(0),cd.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(vb.phases().get(0),cd.getJSONArray("target"));
        check(new DeclarationMatrixLink(na,nb,chosen,Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,cd)==2,"source-linked paid map: keep0,insert/delete1,update1");reject(()->new DeclarationMatrixLink(na,nb,Map.of("_q0","_q0"),Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,cd),"old partial map cannot license paid path");
        var fast=capture.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=map(fp.getJSONObject("alphaMapping"));check(fm.equals(chosen),"same original-coordinate paid mapping");
        check(CanonicalDistance.verifyFixedMatrixReplay(CanonicalDistance.prepare(p.a.normalizedForms()),CanonicalDistance.prepare(p.b.normalizedForms()),fm,fm,fp.getJSONObject("receipt"))==24,"Fast fixed original matrix24");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==26&&arm.getInt("quantifierDistance")==2&&arm.getInt("temporalDistance")==0&&arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"joint numerical coverage26");
        var cmp=NativeOperationComparison.compare(p.a,p.b,ca,cb);var e=cmp.evidence();check(cmp.status()==NativeOperationComparison.Status.MATCHED,"ordered original native actions: "+cmp.reason()+" "+e.opt("firstDifference"));
        check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"every ordered native action including zero actions/permutations");check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"actual source certificate and fixed endpoint correspondence");check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"full certification BLOCKED");
        var bad=copy(e.getJSONObject("diagnostic"));bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");malformed(p,bad,"foreign source");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("_q1","_q0");malformed(p,bad,"capturing/unpaid correspondence");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(0,"ONE");malformed(p,bad,"forged source kind");bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",23);malformed(p,bad,"forged matrix cost");return e;
    }
    public static void main(String[] args)throws Exception{
        var out=args.length==0?Files.createTempDirectory("grouped-retained-"):Path.of(args[0]);Files.createDirectories(out);var p=prepare(Files.readString(SOURCE),"inv14","inv14c");check(supports(p.a.normalizedForms())&&supports(p.b.normalizedForms()),"original endpoints supported");sourcePremises(p.a);
        corruptBinding(p,q->q.setDeBruijnKey(p.a.normalizedForms().get(0).getMatrixQuantiVars().get(0).getDeBruijnKey()),"duplicate slot");corruptBinding(p,q->q.setBindingPath("foreign"),"foreign owner");corruptBinding(p,q->q.addOriginalName("x"),"overlapping original aliases");corruptBinding(p,q->q.setCarrierTypeName("Project"),"carrier substitution");corruptBinding(p,q->q.setCardinality(QuantiVar.Cardinality.SET),"set grouped slot");corruptBinding(p,q->q.setDisjointnessClass(1),"disjoint grouped slot");corruptBinding(p,q->q.setQuantifier(QuantiVar.Quantifier.ONE),"mixed grouped kind");
        corrupt(p,f->{var qs=new ArrayList<>(f.getMatrixQuantiVars());Collections.swap(qs,0,1);field(f,"matrixQuantiVars",qs);},"source slot order");
        corrupt(p,f->field(owner(f),"sourceOccurrenceLineage",-1L),"foreign local owner");corrupt(p,f->field(owner(f).getChildren().get(0).getChildren().get(0).getChildren().get(0),"semanticIdentity","alloy/signature/Other"),"different local domain");corrupt(p,f->field(owner(f),"opcode",EGraphNode.Opcode.ONE),"LONE changed to ONE");corrupt(p,f->field(owner(f).getChildren().get(0).getChildren().get(1),"alphaName","_q0"),"local declaration captures grouped slot");
        corrupt(p,f->field(f,"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal ownership");corrupt(p,f->field(f,"phaseLocalBindingImports",List.of(new Object())),"import ownership");
        var foreign=prepare(Files.readString(SOURCE),"inv14","inv14c");corrupt(p,f->field(owner(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",owner(foreign.a.normalizedForms().get(0)).getChildren().get(0).getChildren().get(1).getExactAlloyType()),"foreign parser type");corrupt(p,f->field(f,"certificationMatrixEGraphRoot",foreign.a.normalizedForms().get(0).getCertificationMatrixEGraph()),"foreign source ledger");
        for(int d=0;d<2;d++){var old=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+d+".json")));Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse(),old).toString(2),StandardOpenOption.CREATE_NEW);}
        String base="sig A {r:set A} sig B {} pred b {some A} ";
        for(String outer:List.of("all","some"))for(String local:List.of("one","lone")){var pair=prepare(base+"pred a {"+outer+" x,y:A | x in y.r implies ("+local+" z:B | some z)}","a","b");check(!pair.a.normalizedForms().get(0).getLocalQuantiVars().isEmpty()&&supports(pair.a.normalizedForms()),"analogous grouped/retained "+outer+"/"+local);}
        // Exact former negative from GroupedPaidDeclarationAlignmentTest.
        var oldLocal=prepare(base+"pred a {some x,y:A | x->y in r and (one z:B | some z)}","a","b");check((int)invoke("FastLocalPaidScope","checkedOwnerCount",new Class[]{NormalForm.class},oldLocal.a.normalizedForms().get(0))==1&&supports(oldLocal.a.normalizedForms()),"old blanket local negative now has both checked premises");
        for(String text:List.of("pred a {lone x,y:A | x in y.r implies (lone z:B | some z)}","pred a {one x,y:A | x in y.r implies (one z:B | some z)}","pred a {all disj x,y:A | x in y.r implies (one z:B | some z)}","pred a {all x,y:set A | x in y.r implies (one z:B | some z)}","pred a[p:A] {all x,y:A | p in x.r and (lone z:B | some z)}","pred a {all x,y:A | always (x in y.r implies (one z:B | some z))}","pred a {all x,y:A | x in y.r implies (one disj z,w:B | z=w)}"))check(!supports(prepare(base+text,"a","b").a.normalizedForms()),"unsupported boundary "+text);
        var controls=new JSONArray();for(var row:List.of(new String[]{"coursesNew/both/5vxYh7NCpEPMmC47p_inv14","inv14","47"},new String[]{"coursesOld/both/xJnitBpFv9K74WNvf_inv14","inv14","49"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"classroom_rl/over/AE26k3aCggeBrqCrh_inv8","inv8","23"},new String[]{"classroom_fol/both/8dTfKqTs26w6fL87K_inv15","inv15","18"})){
            var pair=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");for(var dir:List.of(pair,pair.reverse()))check(Canonical.distance(dir.a,dir.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b))==Integer.parseInt(row[2]),"prior family "+row[0]);controls.put(row[0]);}
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("GroupedRetainedScopeReplayTest: "+checks+" checks passed");
    }
}

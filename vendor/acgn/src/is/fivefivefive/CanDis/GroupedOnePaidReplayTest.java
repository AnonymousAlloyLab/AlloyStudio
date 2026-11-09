package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.BiPredicate;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import parser.ast.nodes.ModelUnit;

/** PvuB: ordered paid paths for source-produced grouped ONE slots, not exchange. */
public final class GroupedOnePaidReplayTest {
    private static final Path SOURCE=Path.of("classified-data/coursesNew/over/PvuBZf9iKQbBARbsj_inv14.als");
    private static int checks;
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static Object invoke(String owner,String name,Class<?>[] types,Object... args){try{var m=Class.forName(owner).getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static boolean supports(List<NormalForm> fs){return (boolean)invoke("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment","supports",new Class[]{List.class},fs);}
    private static Pair prepare(String text,String left,String right)throws Exception{
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(ps.get(0),ps.get(1));
    }
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static NormalForm comparison(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);}
    private static QuantiVar cloneBinding(QuantiVar q){
        var c=new QuantiVar(q.getId(),q.getName(),q.getOriginalName(),q.getTypeName());q.getOriginalNames().forEach(c::addOriginalName);c.setCarrierTypeName(q.getCarrierTypeName());c.setQuantifier(q.getQuantifier());c.setCardinality(q.getCardinality());c.setDisjointnessClass(q.getDisjointnessClass());c.setBindingPath(q.getBindingPath());c.setDeBruijnKey(q.getDeBruijnKey());c.mergeExactAlloyType(q.getExactAlloyType());return c;
    }
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static int[] map(JSONArray j){int[] a=new int[j.length()];for(int i=0;i<a.length;i++)a[i]=j.getInt(i);return a;}
    private static Map<String,String> map(JSONObject j){var m=new TreeMap<String,String>();for(String k:j.keySet())m.put(k,j.getString(k));return m;}
    private static void malformed(Pair p,JSONObject j,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,j).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    private static JSONObject replay(Pair p,JSONObject old){
        var va=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var vb=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);
        var ca=CanonicalAlloyPipeline.prepare(p.a);var cb=CanonicalAlloyPipeline.prepare(p.b);var capture=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);var cert=capture.getJSONObject("certified");var cp=cert.getJSONArray("phases").getJSONObject(0);var cm=map(cp.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(va,vb,cm,cm,cp.getJSONObject("receipt"))==28,"shorter certified original matrix28 first");
        if(old!=null){check(old.getJSONObject("certified").getJSONArray("phases").similar(cert.getJSONArray("phases")),"certified matrix unchanged");check(old.getJSONObject("certified").getJSONObject("declarationSlice").similar(cert.getJSONObject("declarationSlice")),"certified declaration unchanged");}
        var na=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var nb=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var chosen=new TreeMap<String,String>();for(int i=0;i<cm.length;i++)if(cm[i]>=0)chosen.put(na.get(i),nb.get(cm[i]));
        var cd=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");var aa=NestedPaidDeclarationReplayTest.atoms(va.phases().get(0),cd.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(vb.phases().get(0),cd.getJSONArray("target"));
        check(new DeclarationMatrixLink(na,nb,chosen,Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,cd)==4,"original linked delete-or-insert1 plus three updates1");
        reject(()->new DeclarationMatrixLink(na,nb,Map.of(),Set.copyOf(na),Set.copyOf(nb)).verify(aa,bb,cd),"old unlinked correspondence rejected");
        var fast=capture.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=map(fp.getJSONObject("alphaMapping"));check(fm.equals(chosen),"same original paid mapping");
        check(CanonicalDistance.verifyFixedMatrixReplay(CanonicalDistance.prepare(p.a.normalizedForms()),CanonicalDistance.prepare(p.b.normalizedForms()),fm,fm,fp.getJSONObject("receipt"))==28,"Fast fixed original matrix28");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==32&&arm.getInt("quantifierDistance")==4&&arm.getInt("temporalDistance")==0&&arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"joint original coverage32");
        var cmp=NativeOperationComparison.compare(p.a,p.b,ca,cb);var e=cmp.evidence();check(cmp.status()==NativeOperationComparison.Status.MATCHED,"ordered grouped ONE actions "+cmp.reason()+" "+e.opt("firstDifference"));check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all ordered native actions equal");check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"source/binder/type correspondence checked");check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"full certification BLOCKED");
        var bad=copy(e.getJSONObject("diagnostic"));bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");malformed(p,bad,"foreign endpoint");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("_q2","_q0");malformed(p,bad,"capturing paid correspondence");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(0,"LONE");malformed(p,bad,"forged source kind");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",27);malformed(p,bad,"forged action cost");
        return e;
    }
    public static void main(String[] args)throws Exception{
        var out=Path.of(args[0]);Files.createDirectories(out);var text=Files.readString(SOURCE);var p=prepare(text,"inv14","inv14c");var f=p.a.normalizedForms().get(0);var qs=f.getMatrixQuantiVars();
        check(supports(p.a.normalizedForms())&&supports(p.b.normalizedForms()),"actual endpoint admission");check(qs.size()==4&&f.getLocalQuantiVars().isEmpty(),"four original slots no local owners");check(qs.stream().allMatch(q->q.getQuantifier()==QuantiVar.Quantifier.ONE),"original ONE preserved");check(qs.stream().map(QuantiVar::getBindingPath).distinct().count()==2,"two declaration owners");
        var view=comparison(p.a);check(supports(List.of(view)),"comparison retains original evidence");for(int i=0;i<4;i++)check(qs.get(i)==view.getMatrixQuantiVars().get(i),"original identity "+i);
        @SuppressWarnings("unchecked") var free=(BiPredicate<Integer,Integer>)invoke("is.fivefivefive.CanDis.core.FastDeclarationPermutation","admission",new Class[]{NormalForm.class,List.class},f,qs);
        check(!free.test(0,1)&&!free.test(2,3),"no free ONE transpositions");
        var orders=(List<?>)invoke("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit","orders",new Class[]{NormalForm.class,NormalForm.class,List.class},f,view,qs);check(orders.size()==1,"ONE grouped orbit remains identity-only");
        for(int mode=0;mode<6;mode++){
            var corrupt=comparison(p.a);var bindings=new ArrayList<>(corrupt.getMatrixQuantiVars());var q=cloneBinding(bindings.get(0));
            switch(mode){case 0->{}case 1->q.setBindingPath("foreign");case 2->q.setDeBruijnKey(bindings.get(1).getDeBruijnKey());case 3->q.setCardinality(QuantiVar.Cardinality.SET);case 4->q.setQuantifier(QuantiVar.Quantifier.SOME);case 5->q.setDisjointnessClass(1);}
            q.freezeForCertification();bindings.set(0,q);field(corrupt,"matrixQuantiVars",bindings);check(!supports(List.of(corrupt)),"same-id foreign slot/type/owner/payload "+mode);
        }
        var reversed=comparison(p.a);var rs=new ArrayList<>(qs);Collections.swap(rs,0,1);field(reversed,"matrixQuantiVars",rs);check(!supports(List.of(reversed)),"ordered group substitution rejected");
        var synthetic=new NormalForm();synthetic.addEClass(f.getCertificationMatrixEGraph());qs.forEach(synthetic::addMatrixQuantiVar);synthetic.freezeForCertification();check(!supports(List.of(synthetic)),"copied body/bindings cannot manufacture producer origin");
        var foreign=prepare(text,"inv14","inv14c");var mixed=comparison(p.a);field(mixed,"matrixQuantiVars",foreign.a.normalizedForms().get(0).getMatrixQuantiVars());check(!supports(List.of(mixed)),"foreign identical parser module rejected");
        for(String scope:List.of("params","inheritedQuantiVars","phaseLocalBindingImports")){var scoped=comparison(p.a);field(scoped,scope,List.of(new Object()));check(!supports(List.of(scoped)),"scope boundary "+scope);}
        var temporal=comparison(p.a);field(temporal,"temporalOp",NormalForm.TemporalOp.ALWAYS);check(!supports(List.of(temporal)),"temporal boundary");
        var oversized=comparison(p.a);var repeated=new ArrayList<QuantiVar>();for(int i=0;i<17;i++)repeated.addAll(qs);field(oversized,"matrixQuantiVars",repeated);check(!supports(List.of(oversized)),"bounded original group census");
        for(int d=0;d<2;d++){var old=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+d+".json")));Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse(),old).toString(2),StandardOpenOption.CREATE_NEW);}
        String base="sig A { r:set A } pred b {some A} ";
        for(String kind:List.of("one","all","some")){var general=prepare(base+"pred a {"+kind+" x,y:A | x->y in r}","a","b");check(supports(general.a.normalizedForms()),"source-observed general group "+kind);}
        var some=prepare(base+"pred a {some x,y:A | x->y in r}","a","b");var forged=comparison(some.a);var changed=new ArrayList<QuantiVar>();for(var q:forged.getMatrixQuantiVars()){var c=cloneBinding(q);c.setQuantifier(QuantiVar.Quantifier.ONE);c.freezeForCertification();changed.add(c);}field(forged,"matrixQuantiVars",changed);check(!supports(List.of(forged)),"prior forged SOME-to-ONE negative remains rejected");
        for(String body:List.of("lone x,y:A | x->y in r","one disj x,y:A | x->y in r","one x,y:set A | x->y in r","one x,y:A | x->x in r","one x,y:A | always x->y in r","one x,y:A | x->y in r and (one z:A | z in x.r)"))check(!supports(prepare(base+"pred a {"+body+"}","a","b").a.normalizedForms()),"unsupported grouped boundary "+body);
        check(!supports(prepare(base+"pred a[z:A] {one x,y:A | x->y in r and y in z.r}","a","b").a.normalizedForms()),"parameter boundary");
        var controls=new JSONArray();for(var row:List.of(new String[]{"coursesNew/correct/wnqMPtpNxnPYzru3g_inv14","inv14","16"},new String[]{"coursesNew/both/LJrfWwJdKiTdyytci_inv14","inv14","26"},new String[]{"coursesNew/both/5vxYh7NCpEPMmC47p_inv14","inv14","47"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"})){
            var pair=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");for(var dir:List.of(pair,pair.reverse()))check(Canonical.distance(dir.a,dir.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b))==Integer.parseInt(row[2]),"prior family "+row[0]);controls.put(row[0]);}
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("GroupedOnePaidReplayTest: "+checks+" checks passed");
    }
}

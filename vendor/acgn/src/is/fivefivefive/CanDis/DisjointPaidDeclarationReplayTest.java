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

/** AE26 paid disjoint-group admission, not a new alpha/permutation law. */
public final class DisjointPaidDeclarationReplayTest {
    private static int checks;
    private static final Path SOURCE=Path.of("classified-data/classroom_rl/over/AE26k3aCggeBrqCrh_inv8.als");
    private record Pair(Canonical.Prepared a,Canonical.Prepared b) { Pair reverse(){return new Pair(b,a);} }
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static Object invoke(String owner,String method,Class<?>[] types,Object...args){
        try{var m=Class.forName(owner).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static boolean supports(List<NormalForm> forms){return (boolean)invoke("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment","supports",new Class[]{List.class},forms);}
    private static Pair prepare(String text,String left,String right)throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}
        return new Pair(ps.get(0),ps.get(1));
    }
    private static List<NormalForm> copyDeclarations(NormalForm source,Consumer<List<QuantiVar>> change){
        var result=new NormalForm();result.addEClass(source.getMatrixEGraph());var qs=new ArrayList<QuantiVar>();
        for(var q:source.getMatrixQuantiVars()){
            var c=new QuantiVar(q.getId(),q.getName(),q.getOriginalName(),q.getTypeName());
            c.setCarrierTypeName(q.getCarrierTypeName());c.setBindingPath(q.getBindingPath());c.setDeBruijnKey(q.getDeBruijnKey());
            c.setCardinality(q.getCardinality());c.setQuantifier(q.getQuantifier());c.setDisjointnessClass(q.getDisjointnessClass());
            for(var alias:q.getOriginalNames())c.addOriginalName(alias);c.mergeExactAlloyType(q.getExactAlloyType());qs.add(c);
        }
        change.accept(qs);qs.forEach(result::addMatrixQuantiVar);result.freezeForCertification();return List.of(result);
    }
    private static void field(Object value,String name,Object replacement){
        try{var f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,replacement);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static int[] ints(JSONArray a){int[] m=new int[a.length()];for(int i=0;i<m.length;i++)m[i]=a.getInt(i);return m;}
    private static Map<String,String> map(JSONObject a){Map<String,String> m=new TreeMap<>();for(String k:a.keySet())m.put(k,a.getString(k));return m;}
    private static JSONObject copy(JSONObject a){return new JSONObject(a.toString());}
    private static void malformed(Pair p,JSONObject report,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,report).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    private static JSONObject replay(Pair p,JSONObject old,int expected)throws Exception{
        var ca=CanonicalAlloyPipeline.prepare(p.a);var cb=CanonicalAlloyPipeline.prepare(p.b);
        var captured=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);
        // Preserve the shorter archived certified path, checked first with no search.
        var cert=captured.getJSONObject("certified");var phase=cert.getJSONArray("phases").getJSONObject(0);var m=ints(phase.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(ca.repairView(),cb.repairView(),m,m,phase.getJSONObject("receipt"))==cert.getInt("matrixDistance"),"certified fixed map replay first");
        if(old!=null)check(old.getJSONObject("captured").getJSONObject("certified").similar(cert),"entire shorter certified receipt unchanged");
        var fast=captured.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=map(fp.getJSONObject("alphaMapping"));
        var fa=CanonicalDistance.prepare(p.a.normalizedForms());var fb=CanonicalDistance.prepare(p.b.normalizedForms());
        check(CanonicalDistance.verifyFixedMatrixReplay(fa,fb,fm,fm,fp.getJSONObject("receipt"))==fast.getInt("matrixDistance"),"Fast fixed map replay");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==expected && arm.getInt("quantifierDistance")==2
                && arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"minimum declaration path retained");
        var comparison=NativeOperationComparison.compare(p.a,p.b,ca,cb);var e=comparison.evidence();
        check(comparison.status()==NativeOperationComparison.Status.MATCHED,"ordered native actions "+comparison.reason()+" "+e.opt("firstDifference"));
        check(comparison.bindScanCosts(new int[]{expected,0,2,expected-2},new int[]{expected,0,2,expected-2}).status()==NativeOperationComparison.Status.MATCHED,"scan cost linkage");
        check(e.getBoolean("sourceCertificatesChecked") && e.getBoolean("endpointCorrespondenceChecked") && e.getBoolean("fixedChoiceReplaysChecked"),"actual source-linked typed and fixed-choice checks");
        check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all action kinds/costs/order equal, including zero edits");
        check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"full certification blocked");
        var dr=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
        var namesA=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
        var namesB=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
        var chosen=new HashMap<String,String>();for(int i=0;i<m.length;i++)if(m[i]>=0)chosen.put(namesA.get(i),namesB.get(m[i]));
        var aa=NestedPaidDeclarationReplayTest.atoms(ca.repairView().phases().get(0),dr.getJSONArray("source"));
        var bb=NestedPaidDeclarationReplayTest.atoms(cb.repairView().phases().get(0),dr.getJSONArray("target"));
        check(new DeclarationMatrixLink(namesA,namesB,chosen,Set.copyOf(namesA),Set.copyOf(namesB)).verify(aa,bb,dr)==2,"independent original-coordinate joint path");
        reject(()->new DeclarationMatrixLink(namesA,namesB,Map.of(),Set.copyOf(namesA),Set.copyOf(namesB)).verify(aa,bb,dr),"empty map cannot claim paid update");
        var bad=copy(e.getJSONObject("diagnostic"));bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");malformed(p,bad,"foreign endpoint");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONObject("selectedPaidDeclarationCorrespondence").put("foreign","_q0");malformed(p,bad,"forged paid mapping");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1)
                .getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(3,"99");malformed(p,bad,"forged disjoint source class");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",0);malformed(p,bad,"forged matrix cost");
        return e;
    }
    public static void main(String[] args)throws Exception{
        Path out=args.length==0?Files.createTempDirectory("disjoint-paid-"):Path.of(args[0]);Files.createDirectories(out);
        var p=prepare(Files.readString(SOURCE),"inv8","inv8c");var source=p.a.normalizedForms().get(0);
        check(supports(p.a.normalizedForms())&&supports(p.b.normalizedForms()),"actual supported endpoints");
        check(source.getMatrixQuantiVars().stream().map(QuantiVar::getDisjointnessClass).toList().equals(List.of(1,1)),"original disjoint group retained");
        var evidence=TheoryAlloyAdapter.adapt(p.a.normalizedForms(),p.a.semanticProfile());evidence.requireRepairProjectionSources(p.a.normalizedForms());
        var view=RepairProjection.project(evidence,p.a.normalizedForms());
        for(var c:evidence.semanticArtifact().binderOccurrenceCertificates())CertificateVerifier.verify(c);
        for(int i=0;i<2;i++)check(view.phases().get(0).quantifiers().get(i).disjointnessClass()==1,"certified disjoint tuple retained");
        reject(()->RepairProjection.project(evidence,p.b.normalizedForms()),"foreign certified source");
        check(supports(copyDeclarations(source,qs->{})),"same source observed owner/type/slot evidence");
        for(var mutation:List.<Consumer<List<QuantiVar>>>of(q->q.remove(1),q->q.get(0).setDisjointnessClass(0),
                q->q.get(0).setDisjointnessClass(2),q->q.get(0).setBindingPath("foreign"),q->q.get(0).setDeBruijnKey("foreign#0"),
                q->q.get(0).setQuantifier(QuantiVar.Quantifier.ONE),q->q.get(0).setCardinality(QuantiVar.Cardinality.SET),
                q->q.get(0).setCarrierTypeName("Teacher"),q->Collections.reverse(q),q->field(q.get(0),"exactAlloyType",null)))
            check(!supports(copyDeclarations(source,mutation)),"malformed group/owner/slot/type rejected");
        var foreign=prepare(Files.readString(SOURCE),"inv8","inv8c");
        check(!supports(copyDeclarations(source,q->field(q.get(0),"exactAlloyType",foreign.a.normalizedForms().get(0).getMatrixQuantiVars().get(0).getExactAlloyType()))),"foreign parser-module type");
        @SuppressWarnings("unchecked") var exchange=(java.util.function.BiPredicate<Integer,Integer>)invoke("is.fivefivefive.CanDis.core.FastDeclarationPermutation","admission",new Class[]{NormalForm.class,List.class},source,source.getMatrixQuantiVars());
        check(!exchange.test(0,1),"no incidental new free disjoint permutation route");
        var orders=(List<?>)invoke("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit","orders",new Class[]{NormalForm.class,NormalForm.class,List.class},source,
                CanonicalDistance.prepare(p.a.normalizedForms()).comparisonForms().get(0),source.getMatrixQuantiVars());
        check(orders.size()==1,"existing grouped-orbit disjoint exclusion retained");
        for(int direction=0;direction<2;direction++){
            var old=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+direction+".json")));
            var result=replay(direction==0?p:p.reverse(),old,23);
            Files.writeString(out.resolve("after-"+direction+".json"),result.toString(2),StandardOpenOption.CREATE_NEW);
        }
        String reduced="sig A { r:set A } sig B { back:set A } pred a { all disj x,y:A | x in y.r } pred s { some disj x,y:A | x in y.r } pred b { all z:B | z in B } pred param[p:A] { all disj x,y:A | x in p.r and y in p.r } pred temporal { all disj x,y:A | always x in y.r } pred setVar { all disj x,y:set A | x in y } pred local { all disj x,y:A | x in y.r implies (some z:B | x in z.back and y in z.back) } pred fixedPrefix { all disj x,y:A | one z:A | x in z.r and y in z.r }";
        for(String name:List.of("a","s"))check(supports(prepare(reduced,name,"b").a.normalizedForms()),"closed "+name+" disjoint group");
        var fixed=prepare(reduced,"fixedPrefix","b").a.normalizedForms();
        check(fixed.get(0).getLocalQuantiVars().isEmpty() && supports(fixed),"initial negative fixture is actually a closed fixed prefix");
        check(!prepare(reduced,"local","b").a.normalizedForms().get(0).getLocalQuantiVars().isEmpty(),"negative actually retains a local owner");
        for(String name:List.of("param","temporal","setVar","local"))check(!supports(prepare(reduced,name,"b").a.normalizedForms()),"unsupported scope "+name);
        var controls=new JSONArray();
        for(var row:List.of(new String[]{"classroom_rl","both","D3n2EfPhBboewRm7z_inv14","inv14","20"},
                new String[]{"classroom_fol","both","wdiLn4gC6q3bbYBad_inv3","inv3","7"},
                new String[]{"classroom_fol","both","JoNXaXfKNJzGh3BFa_inv12","inv12","22"},
                new String[]{"classroom_fol","both","SASEHD8jPo2HgsuoJ_inv14","inv14","27"},
                new String[]{"coursesOld","both","xJnitBpFv9K74WNvf_inv14","inv14","49"},
                new String[]{"classroom_fol","both","YKy8LYsKdapW3xqbn_inv14","inv14","20"})){
            var pair=prepare(Files.readString(Path.of("classified-data",row[0],row[1],row[2]+".als")),row[3],row[3]+"c");
            for(var q:List.of(pair,pair.reverse())){var ca=CanonicalAlloyPipeline.prepare(q.a);var cb=CanonicalAlloyPipeline.prepare(q.b);
                check(Canonical.distance(q.a,q.b)==Integer.parseInt(row[4])&&CanonicalAlloyPipeline.distance(ca,cb)==Integer.parseInt(row[4]),"prior cost "+row[2]);
                if(row[2].startsWith("D3n2")||row[2].startsWith("wdi"))check(NativeOperationComparison.compare(q.a,q.b,ca,cb).status()==NativeOperationComparison.Status.MATCHED,"prior ordered native actions "+row[2]);}
            controls.put(row[2]);
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("DisjointPaidDeclarationReplayTest: "+checks+" checks passed");
    }
}

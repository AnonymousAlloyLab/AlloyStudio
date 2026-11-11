package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import parser.ast.nodes.ModelUnit;

/** 2CC: generated domain occurrences retain binding identity, never source lineage. */
public final class GroupedDomainEvidenceReplayTest {
    private static int checks;
    private static final Path SOURCE=Path.of("classified-data/coursesNew/both/2CCEGTS6iiHpRqvCu_inv14.als");
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalStateException|IllegalArgumentException e){return;}throw new AssertionError(why);}
    private static Object invoke(String owner,String method,Class<?>[] types,Object...args){
        try{var m=Class.forName(owner).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static boolean supports(List<NormalForm> f){return (boolean)invoke("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment","supports",new Class[]{List.class},f);}
    private static boolean bound(QuantiVar q,EGraphNode n){return (boolean)invoke(NormalForm.class.getName(),"hasSourceBoundDomainEvidence",new Class[]{QuantiVar.class,EGraphNode.class},q,n);}
    private static Pair prepare(String text,String a,String b)throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(a,b)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(ps.get(0),ps.get(1));
    }
    private static List<EGraphNode> nodes(EGraphNode root){
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());var todo=new ArrayDeque<EGraphNode>();var out=new ArrayList<EGraphNode>();todo.add(root);
        while(!todo.isEmpty()){var n=todo.remove();if(!seen.add(n))continue;if(seen.size()>4096)throw new AssertionError("budget");out.add(n);todo.addAll(n.getChildren());}return out;
    }
    private static QuantiVar copy(QuantiVar q,ExactAlloyType type,boolean aliases){
        var r=new QuantiVar(q.getId(),q.getName(),q.getOriginalName(),q.getTypeName());
        r.setBindingPath(q.getBindingPath());r.setDeBruijnKey(q.getDeBruijnKey());r.setQuantifier(q.getQuantifier());r.setCardinality(q.getCardinality());r.setDisjointnessClass(q.getDisjointnessClass());r.setCarrierTypeName(q.getCarrierTypeName());
        if(aliases)q.getOriginalNames().forEach(r::addOriginalName);r.mergeExactAlloyType(type);return r;
    }
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static Map<String,String> map(JSONObject j){var m=new TreeMap<String,String>();for(String k:j.keySet())m.put(k,j.getString(k));return m;}
    private static int[] map(JSONArray j){int[] m=new int[j.length()];for(int i=0;i<m.length;i++)m[i]=j.getInt(i);return m;}
    private static void malformed(Pair p,JSONObject j,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,j).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    private static JSONObject replay(Pair p,JSONObject old)throws Exception{
        var va=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var vb=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);
        var ca=CanonicalAlloyPipeline.prepare(p.a);var cb=CanonicalAlloyPipeline.prepare(p.b);var capture=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);
        var cert=capture.getJSONObject("certified");var phase=cert.getJSONArray("phases").getJSONObject(0);var selected=map(phase.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(va,vb,selected,selected,phase.getJSONObject("receipt"))==16,"shorter original-endpoint fixed matrix16 first");
        if(old!=null){check(old.getJSONObject("certified").getJSONArray("phases").similar(cert.getJSONArray("phases")),"entire certified matrix path unchanged");check(old.getJSONObject("certified").getJSONObject("declarationSlice").similar(cert.getJSONObject("declarationSlice")),"entire certified declaration path unchanged");}
        var namesA=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var namesB=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var chosen=new TreeMap<String,String>();for(int i=0;i<selected.length;i++)if(selected[i]>=0)chosen.put(namesA.get(i),namesB.get(selected[i]));
        var dr=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
        var aa=NestedPaidDeclarationReplayTest.atoms(va.phases().get(0),dr.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(vb.phases().get(0),dr.getJSONArray("target"));
        check(new DeclarationMatrixLink(namesA,namesB,chosen,Set.copyOf(namesA),Set.copyOf(namesB)).verify(aa,bb,dr)==2,"source-linked paid declaration2");
        reject(()->new DeclarationMatrixLink(namesA,namesB,Map.of("_q0","_q0"),Set.copyOf(namesA),Set.copyOf(namesB)).verify(aa,bb,dr),"old incomplete correspondence rejected");
        var fast=capture.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=map(fp.getJSONObject("alphaMapping"));check(fm.equals(chosen),"same selected original-coordinate map");
        check(CanonicalDistance.verifyFixedMatrixReplay(CanonicalDistance.prepare(p.a.normalizedForms()),CanonicalDistance.prepare(p.b.normalizedForms()),fm,fm,fp.getJSONObject("receipt"))==16,"Fast fixed-choice replay16");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==18&&arm.getInt("quantifierDistance")==2&&arm.getInt("temporalDistance")==0&&arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"linked numerical coverage18");
        var cmp=NativeOperationComparison.compare(p.a,p.b,ca,cb);var e=cmp.evidence();check(cmp.status()==NativeOperationComparison.Status.MATCHED,"ordered native actions "+cmp.reason()+" "+e.opt("firstDifference"));
        check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all ordered native actions including zero edits");
        check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"source/type/certificate and replay checks");
        check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"full certification remains BLOCKED");
        var bad=copy(e.getJSONObject("diagnostic"));bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");malformed(p,bad,"foreign endpoint");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("_q1","_q0");malformed(p,bad,"capturing mapping");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(3,"99");malformed(p,bad,"forged source disjoint payload");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",15);malformed(p,bad,"forged action cost");return e;
    }
    public static void main(String[] args)throws Exception{
        Path out=args.length==0?Files.createTempDirectory("grouped-domain-"):Path.of(args[0]);Files.createDirectories(out);var p=prepare(Files.readString(SOURCE),"inv14","inv14c");
        for(var endpoint:List.of(p.a,p.b)){
            var f=endpoint.normalizedForms().get(0);check(supports(endpoint.normalizedForms()),"actual grouped source endpoint admitted");
            for(var q:f.getMatrixQuantiVars()){
                var vars=nodes(f.getCertificationMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE&&q.getName().equals(n.getAlphaName())).toList();
                for(var n:vars)check(bound(q,n),"every body/generated occurrence retains original alias, slot and exact module type");
                check(vars.stream().map(EGraphNode::getSourceOccurrenceLineage).distinct().count()==vars.size(),"generated and original occurrences keep distinct lineages");
            }
        }
        var f=p.a.normalizedForms().get(0);var q=f.getMatrixQuantiVars().get(0);var n=nodes(f.getCertificationMatrixEGraph()).stream().filter(x->x.getOpcode()==EGraphNode.Opcode.VARIABLE&&q.getName().equals(x.getAlphaName())).findFirst().orElseThrow();
        check(!bound(f.getMatrixQuantiVars().get(1),n),"another source slot cannot authenticate generated occurrence");
        check(!bound(copy(q,null,true),n)&&!bound(copy(q,q.getExactAlloyType(),false),n),"missing parser type/semantic alias rejected");
        check(!bound(copy(q,ExactAlloyType.unaryRelation(q.getTypeName()),true),n),"same text unauthenticated type rejected");
        var foreign=prepare(Files.readString(SOURCE),"inv14","inv14c").a.normalizedForms().get(0).getMatrixQuantiVars().get(0);
        check(q.getId()==foreign.getId()&&!bound(foreign,n),"same-id foreign parser owner rejected");
        var target=p.b.normalizedForms().get(0);check(target.getMatrixQuantiVars().stream().map(QuantiVar::getDisjointnessClass).toList().equals(List.of(0,1,1)),"original disjoint payload retained");
        @SuppressWarnings("unchecked")var perm=(java.util.function.BiPredicate<Integer,Integer>)invoke("is.fivefivefive.CanDis.core.FastDeclarationPermutation","admission",new Class[]{NormalForm.class,List.class},target,target.getMatrixQuantiVars());check(!perm.test(1,2),"no new free disjoint permutation");
        for(int d=0;d<2;d++){var old=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+d+".json")));Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse(),old).toString(2),StandardOpenOption.CREATE_NEW);}
        String model="sig A {r:set A} sig S in A {} pred b {some A}";
        for(String kind:List.of("all","some")){var reduced=prepare(model+" pred a { "+kind+" x,y:S | x in y.r }","a","b").a;check(supports(reduced.normalizedForms()),"subset guard evidence "+kind);check(nodes(reduced.normalizedForms().get(0).getCertificationMatrixEGraph()).stream().anyMatch(x->"S".equals(x.getSourceName())),"subset domain retained "+kind);}
        for(String text:List.of("pred a[p:A] {all x,y:S| x in p.r and y in p.r}","pred a {all x,y:S| always x in y.r}","pred a {all x,y:set S | x in y.r}"))check(!supports(prepare(model+text,"a","b").a.normalizedForms()),"parameter/temporal/set boundary retained");
        var controls=new JSONArray();for(var row:List.of(new String[]{"classroom_rl/under/5xC5nGeD2S6zDjCCo_inv14","inv14","10"},new String[]{"classroom_rl/over/AE26k3aCggeBrqCrh_inv8","inv8","23"},new String[]{"classroom_rl/both/D3n2EfPhBboewRm7z_inv14","inv14","20"},new String[]{"classroom_fol/both/wdiLn4gC6q3bbYBad_inv3","inv3","7"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"coursesOld/both/xJnitBpFv9K74WNvf_inv14","inv14","49"})){
            var pair=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");for(var dir:List.of(pair,pair.reverse()))check(Canonical.distance(dir.a,dir.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b))==Integer.parseInt(row[2]),"prior cost "+row[0]);controls.put(row[0]);}
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("GroupedDomainEvidenceReplayTest: "+checks+" checks passed");
    }
}

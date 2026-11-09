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

/** 5xC5: preserve empty source-slot evidence without removing a domain or type cost. */
public final class EmptyBindingEvidenceReplayTest {
    private static int checks;
    private static final Path SOURCE=Path.of("classified-data/classroom_rl/under/5xC5nGeD2S6zDjCCo_inv14.als");
    private record Pair(Canonical.Prepared a,Canonical.Prepared b) { Pair reverse(){return new Pair(b,a);} }
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static Object invoke(String owner,String method,Class<?>[] types,Object...args){
        try{var m=Class.forName(owner).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);}
        catch(java.lang.reflect.InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static boolean usable(ExactAlloyType t){return (boolean)invoke(NormalForm.class.getName(),"usableBindingTypeEvidence",new Class[]{ExactAlloyType.class},t);}
    private static boolean supports(List<NormalForm> f){return (boolean)invoke("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment","supports",new Class[]{List.class},f);}
    private static Pair prepare(String text,String a,String b)throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(a,b)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}
        return new Pair(ps.get(0),ps.get(1));
    }
    private static List<EGraphNode> nodes(EGraphNode root){
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());var todo=new ArrayDeque<EGraphNode>();var out=new ArrayList<EGraphNode>();todo.add(root);
        while(!todo.isEmpty()){var n=todo.remove();if(!seen.add(n))continue;if(seen.size()>4096)throw new AssertionError("node budget");out.add(n);todo.addAll(n.getChildren());}return out;
    }
    private static Map<String,String> mapping(JSONObject j){var m=new TreeMap<String,String>();for(String k:j.keySet())m.put(k,j.getString(k));return m;}
    private static int[] mapping(JSONArray j){var m=new int[j.length()];for(int i=0;i<m.length;i++)m[i]=j.getInt(i);return m;}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static void malformed(Pair p,JSONObject j,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,j).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    private static JSONObject replay(Pair p,JSONObject before)throws Exception{
        var va=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var vb=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);
        var ca=CanonicalAlloyPipeline.prepare(p.a);var cb=CanonicalAlloyPipeline.prepare(p.b);
        var captured=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);var cert=captured.getJSONObject("certified");
        var cp=cert.getJSONArray("phases").getJSONObject(0);var cm=mapping(cp.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(va,vb,cm,cm,cp.getJSONObject("receipt"))==9,"shorter fixed matrix9 first");
        if(before!=null)check(before.getJSONObject("certified").getJSONArray("phases").similar(cert.getJSONArray("phases")),"certified matrix path unchanged");
        var namesA=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
        var namesB=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
        var chosen=new TreeMap<String,String>();for(int i=0;i<cm.length;i++)if(cm[i]>=0)chosen.put(namesA.get(i),namesB.get(cm[i]));
        var dr=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
        var aa=NestedPaidDeclarationReplayTest.atoms(va.phases().get(0),dr.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(vb.phases().get(0),dr.getJSONArray("target"));
        check(new DeclarationMatrixLink(namesA,namesB,chosen,Set.copyOf(namesA),Set.copyOf(namesB)).verify(aa,bb,dr)==1,"source-linked declaration1");
        reject(()->new DeclarationMatrixLink(namesA,namesB,Map.of("_q0","_q0"),Set.copyOf(namesA),Set.copyOf(namesB)).verify(aa,bb,dr),"old incomplete map cannot justify paid path");
        var fast=captured.getJSONObject("fast");var fp=fast.getJSONArray("phases").getJSONObject(0);var fm=mapping(fp.getJSONObject("alphaMapping"));
        check(fm.equals(chosen)&&chosen.equals(Map.of("_q0","_q0","_q1","_q1")),"actual same paid/zero declaration correspondence");
        check(CanonicalDistance.verifyFixedMatrixReplay(CanonicalDistance.prepare(p.a.normalizedForms()),CanonicalDistance.prepare(p.b.normalizedForms()),fm,fm,fp.getJSONObject("receipt"))==9,"Fast independent fixed matrix9");
        for(var arm:List.of(fast,cert))check(arm.getInt("distance")==10&&arm.getInt("quantifierDistance")==1&&arm.getInt("temporalDistance")==0
                &&arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"complete numerical coverage10 with unchanged paid1");
        var comparison=NativeOperationComparison.compare(p.a,p.b,ca,cb);var e=comparison.evidence();
        check(comparison.status()==NativeOperationComparison.Status.MATCHED,"ordered native actions: "+comparison.reason()+" "+e.opt("firstDifference"));
        check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"ordered actions including zero actions agree");
        check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"actual typed source and replay checks");
        check(!e.getBoolean("complete")&&!e.getBoolean("javaLeanCorrespondenceChecked")&&!e.getBoolean("publicSequenceLinked"),"public proof boundary retained");
        var bad=copy(e.getJSONObject("diagnostic"));bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");malformed(p,bad,"foreign endpoint rejected");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("_q1","_q0");malformed(p,bad,"capturing paid map rejected");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"foreign");malformed(p,bad,"foreign original carrier rejected");
        bad=copy(e.getJSONObject("diagnostic"));bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",8);malformed(p,bad,"literal type cost cannot be erased");
        return e;
    }
    public static void main(String[] args)throws Exception{
        Path out=args.length==0?Files.createTempDirectory("empty-binding-"):Path.of(args[0]);Files.createDirectories(out);
        var p=prepare(Files.readString(SOURCE),"inv14","inv14c");var f=p.a.normalizedForms().get(0);var q=f.getMatrixQuantiVars().get(1);var type=q.getExactAlloyType();
        check(type!=null&&type.kind()==ExactAlloyType.Kind.EMPTY_RELATION&&type.relationArity()==1&&type.hasParserAuthenticatedAncestry(),"original authenticated unary empty binder retained");
        check(q.getTypeName().equals("none")&&q.getCarrierTypeName().equals("none")&&q.getBindingPath().equals("root/predicate[0]/decl[1]@pos")&&q.getDeBruijnKey().endsWith("#0:ONE:none"),"original literal type/owner/slot unchanged");
        check(supports(p.a.normalizedForms())&&supports(p.b.normalizedForms()),"paid endpoint coverage restored without guard relaxation");
        var variables=nodes(f.getCertificationMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE&&"_q1".equals(n.getAlphaName())).toList();
        check(variables.size()==2,"body and generated domain variable retained");
        for(var n:variables)check(type.sameOccurrenceEvidenceAs(n.getExactAlloyType()),"same parser type authority on every bound occurrence");
        check(nodes(f.getCertificationMatrixEGraph()).stream().anyMatch(n->n.getOpcode()==EGraphNode.Opcode.JOIN&&n.getExactAlloyType().kind()==ExactAlloyType.Kind.EMPTY_RELATION),"empty JOIN domain not removed");
        check(usable(type)&&!usable(null)&&!usable(ExactAlloyType.emptyRelation(1))&&!usable(ExactAlloyType.emptyRelation(2))&&!usable(ExactAlloyType.from(null)),"missing/unauthenticated/unknown evidence rejected");
        var binaryModule=CompUtil.parseEverything_fromString(A4Reporter.NOP,"sig A {}");
        var binaryEmpty=ExactAlloyType.fromParser(CompUtil.parseOneExpression_fromString(binaryModule,"none -> none").type(),binaryModule);
        check(binaryEmpty.hasParserAuthenticatedAncestry()&&!usable(binaryEmpty),"authenticated higher-arity empty binding remains outside unary slice");
        var foreign=prepare(Files.readString(SOURCE),"inv14","inv14c").a.normalizedForms().get(0).getMatrixQuantiVars().get(1).getExactAlloyType();
        check(type.equals(foreign)&&!type.sameOccurrenceEvidenceAs(foreign),"same empty type from foreign module is not occurrence authority");
        var slot=new QuantiVar(999,"_q","s","none");slot.mergeExactAlloyType(type);reject(()->slot.mergeExactAlloyType(foreign),"foreign parser module merge rejected");
        slot.freezeForCertification();reject(()->slot.mergeExactAlloyType(type),"frozen empty binding evidence immutable");
        for(int d=0;d<2;d++){var old=args.length<2?null:new JSONObject(Files.readString(Path.of(args[1]).resolve("walk-"+d+".json")));Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse(),old).toString(2),StandardOpenOption.CREATE_NEW);}
        String model="sig A {r:A->B} sig B {} pred b {some A}";
        check(!supports(prepare(model+" pred a[p:A] { all x:A,y:x.r.A | y in p.r.B }","a","b").a.normalizedForms()),"parameter boundary retained with empty binder");
        check(!supports(prepare(model+" pred a { all x:A,y:x.r.A | always y in x.r.B }","a","b").a.normalizedForms()),"temporal owner boundary retained with empty binder");
        for(String quant:List.of("all","some")){
            var reduced=prepare(model+" pred a { "+quant+" x:A,y:x.r.A | y in x.r.B }","a","b").a.normalizedForms().get(0);
            check(reduced.getMatrixQuantiVars().size()==2&&reduced.getMatrixQuantiVars().get(1).getExactAlloyType().sameOccurrenceEvidenceAs(nodes(reduced.getCertificationMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE&&"_q1".equals(n.getAlphaName())).findFirst().orElseThrow().getExactAlloyType()),"reduced "+quant+" preserves original empty slot");
        }
        for(String quant:List.of("all","some"))check(prepare(model+" pred a { "+quant+" x:none | x in A }","a","b").a.normalizedForms().get(0).getMatrixQuantiVars().isEmpty(),"existing literal empty-domain rule unchanged "+quant);
        var controls=new JSONArray();
        for(var row:List.of(new String[]{"classroom_rl/over/AE26k3aCggeBrqCrh_inv8","inv8","23"},new String[]{"classroom_rl/both/D3n2EfPhBboewRm7z_inv14","inv14","20"},new String[]{"classroom_fol/both/wdiLn4gC6q3bbYBad_inv3","inv3","7"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"coursesOld/both/xJnitBpFv9K74WNvf_inv14","inv14","49"})){
            var pair=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");
            for(var direction:List.of(pair,pair.reverse())){check(Canonical.distance(direction.a,direction.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(direction.a),CanonicalAlloyPipeline.prepare(direction.b))==Integer.parseInt(row[2]),"prior-family cost "+row[0]);}
            controls.put(row[0]);
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("EmptyBindingEvidenceReplayTest: "+checks+" checks passed");
    }
}

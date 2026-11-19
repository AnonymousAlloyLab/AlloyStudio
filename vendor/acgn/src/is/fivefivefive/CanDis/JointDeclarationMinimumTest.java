package is.fivefivefive.CanDis;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.*;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import parser.ast.nodes.ModelUnit;

/** Full native path constraints, separate paid links, and source-bound replay. */
public final class JointDeclarationMinimumTest {
    private static int checks, negatives, constrained, zeroChargeOrbits, keeps, oneSided;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++;negatives++;
        try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted "+why);
    }
    private static Object get(Object o,String field)throws Exception {
        var f=o.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(o);
    }
    private static Method method(Class<?> owner,String name,Class<?>... args)throws Exception {
        var m=owner.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }
    private static Canonical.Prepared prepare(CompModule module,String name) {
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static Map<String,String> map(JSONObject object) {
        var result=new LinkedHashMap<String,String>();for(String name:object.keySet())result.put(name,object.getString(name));return result;
    }
    private static List<QuantiVar> permute(List<QuantiVar> vars,int[] order) {
        if(order==null)return vars;
        var result=new ArrayList<QuantiVar>();for(int i:order)result.add(vars.get(i));return result;
    }
    @SuppressWarnings("unchecked")
    private static void constraints(Canonical.Prepared a,Canonical.Prepared b,JSONObject receipt)throws Exception {
        var x=(CanonicalDistance.Prepared)get(a,"delegate");var y=(CanonicalDistance.Prepared)get(b,"delegate");
        var owner=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment");
        if(!(Boolean)method(owner,"supportsPair",List.class,List.class).invoke(null,x.comparisonForms(),y.comparisonForms()))return;
        if(receipt.getInt("quantifierDistance")==0) {
            zeroChargeOrbits++;
            check(receipt.getJSONObject("selectedPaidDeclarationCorrespondence").isEmpty(),"zero declaration cost has no paid links");
            for(Object raw:receipt.getJSONArray("nativeDeclarationPrograms").getJSONObject(0).getJSONArray("operations")) {
                var op=(JSONObject)raw;
                check(op.getString("kind").equals("keep")&&op.getInt("cost")==0,"zero declaration cost retains native keeps");
            }
            return;
        }
        constrained++;
        var captured=method(CanonicalDistance.class,"captureDecisions",CanonicalDistance.Prepared.class,CanonicalDistance.Prepared.class).invoke(null,x,y);
        var alignment=get(get(captured,"decisions"),"alignment");
        var order=method(CanonicalDistance.class,"canonicalQuantifierOrder",List.class);
        var left=x.comparisonForms().get(0);var right=y.comparisonForms().get(0);
        var av=permute((List<QuantiVar>)order.invoke(null,left.getMatrixQuantiVars()),(int[])get(alignment,"sourceOrder"));
        var bv=permute((List<QuantiVar>)order.invoke(null,right.getMatrixQuantiVars()),(int[])get(alignment,"targetOrder"));
        var atom=method(CanonicalDistance.class,"declarationAtoms",List.class,List.class,int.class);
        var as=(List<DeclarationReplay.Atom>)atom.invoke(null,left.getMatrixQuantiVars(),av,0);
        var bs=(List<DeclarationReplay.Atom>)atom.invoke(null,right.getMatrixQuantiVars(),bv,0);
        var nativePath=receipt.getJSONArray("nativeDeclarationPrograms").getJSONObject(0);
        var selected=map(receipt.getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping"));
        var full=new LinkedHashMap<String,String>();var paid=new LinkedHashMap<String,String>();
        for(Object raw:nativePath.getJSONArray("operations")) {
            var op=(JSONObject)raw;int i=op.getInt("leftIndex"),j=op.getInt("rightIndex");
            if(i>=0&&j>=0) {
                full.put(av.get(i).getName(),bv.get(j).getName());
                check(bv.get(j).getName().equals(selected.get(av.get(i).getName())),"selected keep/update link retained");
                if(op.getInt("cost")>0)paid.put(av.get(i).getName(),bv.get(j).getName());else keeps++;
            } else {
                oneSided++;
                check(i<0?!selected.containsValue(bv.get(j).getName()):!selected.containsKey(av.get(i).getName()),"one-sided declaration remains unmapped");
            }
        }
        check(map(receipt.getJSONObject("selectedPaidDeclarationCorrespondence")).equals(paid),"all and only charged updates in paid map");
        check(((Map<String,String>)get(alignment,"mapping")).equals(full),"numeric candidate contains full selected path");
        check(((Set<String>)get(alignment,"lockedLeftNames")).equals(new HashSet<>(av.stream().map(QuantiVar::getName).toList())),"all source declarations locked");
        check(((Set<String>)get(alignment,"lockedRightNames")).equals(new HashSet<>(bv.stream().map(QuantiVar::getName).toList())),"all target declarations locked");
        var namesA=av.stream().map(QuantiVar::getName).toList();var namesB=bv.stream().map(QuantiVar::getName).toList();
        var checker=new DeclarationMatrixLink(namesA,namesB,selected,Set.copyOf(namesA),Set.copyOf(namesB));
        check(checker.verify(as,bs,nativePath)==nativePath.getInt("cost"),"independent original-atom joint replay");
        if(!full.isEmpty()) {
            var missing=new LinkedHashMap<>(selected);missing.remove(full.keySet().iterator().next());
            reject(()->new DeclarationMatrixLink(namesA,namesB,missing,Set.copyOf(namesA),Set.copyOf(namesB)).verify(as,bs,nativePath),"missing selected link");
        }
        if(!nativePath.getJSONArray("operations").isEmpty()) {
            var bad=new JSONObject(nativePath.toString());var first=bad.getJSONArray("operations").getJSONObject(0);
            first.getJSONObject(first.isNull("source")?"target":"source").put("occurrence","foreign/owner");
            reject(()->checker.verify(as,bs,bad),"foreign owner occurrence");
            var wrong=new JSONObject(nativePath.toString()).put("cost",nativePath.getInt("cost")+1);
            reject(()->checker.verify(as,bs,wrong),"wrong native declaration charge");
        }
    }
    private static JSONObject pair(Canonical.Prepared a,Canonical.Prepared b,Path output)throws Exception {
        var fast=CanonicalRepairTraces.fast(a,b);
        var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));var replay=new JSONObject();
        var result=new JSONObject().put("fast",fast.json()).put("certified",cert.json()).put("replay",replay);
        try {
            QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),replay);
            check(Canonical.distance(a,b)==fast.distance(),"numeric/captured minimum equality");
            var receipt=fast.json();constraints(a,b,receipt);
            var comparison=NativeOperationComparison.compare(a,b,fast,cert);
            result.put("comparison",comparison.evidence()).put("status",comparison.status()).put("reason",comparison.reason());
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"actual joint action equality: "+comparison.reason());
            var bad=fast.json();bad.getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("foreign","foreign");
            reject(()->fast.replay(bad),"foreign selected mapping");
            check(!receipt.getBoolean("complete")&&!receipt.getBoolean("publicSequenceLinked"),"no public promotion");
            return result;
        } catch(Exception|AssertionError failure) {
            result.put("failure",failure.toString());throw failure;
        } finally {
            Files.writeString(output,result.toString(2),StandardOpenOption.CREATE_NEW);
        }
    }
    public static void main(String[] args)throws Exception {
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));Path out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        for(Object raw:manifest.getJSONArray("cases")) {
            var identity=((JSONObject)raw).getJSONObject("identity");var endpoints=LocalOwnerTraceTest.prepare(identity);
            for(int direction=0;direction<2;direction++) {
                var result=pair(endpoints.get(direction),endpoints.get(1-direction),out.resolve(identity.getString("id")+"-"+direction+".json"));
                rows.put(new JSONObject().put("identity",identity).put("direction",direction).put("distance",result.getJSONObject("fast").getInt("distance")));
            }
        }
        var model=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A {}
                sig B in A {}
                pred three { all a,b,c:A | a in B and b in B and c in B }
                pred two { all x,y:A | x in B and y in B }
                pred renamed { all u,v,w:A | u in B and v in B and w in B }
                """);
        for(var names:List.of(List.of("three","two"),List.of("three","renamed"))) {
            var a=prepare(model,names.get(0));var b=prepare(model,names.get(1));
            for(int direction=0;direction<2;direction++) {
                var result=direction==0?pair(a,b,out.resolve(names.get(1)+"-"+direction+".json")):
                        pair(b,a,out.resolve(names.get(1)+"-"+direction+".json"));
                if(names.get(1).equals("renamed"))check(result.getJSONObject("fast").getInt("distance")==0,"alpha-only path remains zero");
            }
        }
        check(constrained>=4&&zeroChargeOrbits>=2&&keeps>0&&oneSided>0,"actual paid full-path, zero-charge orbit, zero-keep and one-sided controls exercised");
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives).put("constrained",constrained).put("zeroChargeOrbits",zeroChargeOrbits).put("keeps",keeps).put("oneSided",oneSided).put("rows",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("JointDeclarationMinimumTest: "+checks+" checks, "+negatives+" negatives");
    }
}

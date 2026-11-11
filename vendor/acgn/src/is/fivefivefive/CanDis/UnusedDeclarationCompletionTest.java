package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.*;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;

/** Partial-map extension, not a new alpha candidate or source equivalence. */
public final class UnusedDeclarationCompletionTest {
    private static int checks;
    private static void check(boolean v,String why) { checks++; if(!v) throw new AssertionError(why); }
    private static void reject(Runnable r,String why) {
        checks++; try { r.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static JSONObject copy(JSONObject x) { return new JSONObject(x.toString()); }
    private static Canonical.Prepared prepare(CompModule m,String name) {
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m); v.visit(new ModelUnit(null,m),null);
        return Canonical.prepare(v.getForest().get(v.getForestId(name)));
    }
    private static void units() {
        var a=List.of(new DeclarationReplay.Atom("source/owner/0",List.of("ALL","A","ONE","0"),"source-domain"));
        var b=List.of(new DeclarationReplay.Atom("target/owner/0",List.of("ALL","A","ONE","0"),"target-domain"));
        for(boolean sourceSeen:new boolean[]{false,true}) for(boolean targetSeen:new boolean[]{false,true}) {
            Set<String> l=sourceSeen?Set.of("a"):Set.of(),r=targetSeen?Set.of("b"):Set.of();
            var joint=new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of(),l,r,true);
            if(sourceSeen && targetSeen) {
                reject(()->DeclarationReplay.capture(a,b,new int[][]{{0,1},{1,0}},(i,j)->joint.allows(i,j,0)),"observed/observed cannot complete");
                continue;
            }
            var receipt=DeclarationReplay.capture(a,b,new int[][]{{0,1},{1,0}},(i,j)->joint.allows(i,j,0));
            var completion=joint.completeAndVerify(a,b,receipt);
            check(completion.getJSONObject("completedMapping").getString("a").equals("b"),"explicit fixed completed map");
            joint.verifyCompletion(a,b,receipt,completion);
            var bad=copy(completion); bad.getJSONArray("absencePremises").getJSONObject(0).put("sourceAbsent",sourceSeen);
            reject(()->joint.verifyCompletion(a,b,receipt,bad),"forged absence premise");
            var map=copy(completion); map.getJSONObject("completedMapping").put("a","foreign");
            reject(()->joint.verifyCompletion(a,b,receipt,map),"substituted completed mapping");
            var foreign=copy(receipt); foreign.getJSONArray("source").getJSONObject(0).put("occurrence","other-owner");
            reject(()->joint.completeAndVerify(a,b,foreign),"foreign owner");
        }
        check(!new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of(),Set.of(),Set.of("b")).allows(0,0,0),
                "absence alone without scope admission is insufficient");
        check(!new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of("a","c"),Set.of(),Set.of("b"),true).allows(0,0,0),"cannot rebind source");
        check(!new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of("c","b"),Set.of(),Set.of("b"),true).allows(0,0,0),"cannot steal target");
        var changed=List.of(new DeclarationReplay.Atom("target/owner/0",List.of("ALL","B","ONE","0"),"other-domain"));
        var paid=DeclarationReplay.capture(a,changed,new int[][]{{0,1},{1,1}},(i,j)->true);
        reject(()->new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of(),Set.of(),Set.of(),true)
                .completeAndVerify(a,changed,paid),"nonzero diagonal needs paid admission, not absence");
    }
    private static Set<String> used(EGraphNode n) {
        Set<String> names=new HashSet<>();
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE) names.add(n.getAlphaName()==null?n.getSourceName():n.getAlphaName());
        for(var c:n.getChildren()) names.addAll(used(c)); return names;
    }
    private static Set<String> used(RepairView.Node n) {
        Set<String> names=new HashSet<>();
        if(n.isVariable() && n.bindingIndex()>=0) names.add(Integer.toString(n.bindingIndex()));
        for(var c:n.children()) names.addAll(used(c));
        for(var c:n.certifiedAlternatives()) names.addAll(used(c)); return names;
    }
    private static Map<String,String> map(JSONObject o) {
        var result=new LinkedHashMap<String,String>(); for(String k:o.keySet()) result.put(k,o.getString(k)); return result;
    }
    private static MatrixReplay<RepairView.Node> certChecker(int[] labels,int[] costs) {
        return new MatrixReplay<>(new MatrixReplay.NativeCost<RepairView.Node>() {
            public String label(RepairView.Node n) { return new JSONArray().put(n.operator()).put(n.semanticPayload()).put(n.bindingIndex())
                    .put(String.valueOf(n.lexicalVariable())).put(n.containerKind().toString()).put(n.orderInsensitive()).put(Arrays.toString(labels)).toString(); }
            public List<RepairView.Node> children(RepairView.Node n) { return n.children(); }
            public List<RepairView.Node> alternatives(RepairView.Node n) { return n.certifiedAlternatives(); }
            public boolean unordered(RepairView.Node a,RepairView.Node b) { return a.operator().equals(b.operator()) && a.orderInsensitive() && b.orderInsensitive(); }
            public int size(RepairView.Node n) { if(n==null)return 0; int sum=1; for(var c:n.children())sum+=size(c); return sum; }
            public int update(RepairView.Node a,RepairView.Node b) {
                if(!a.operator().equals(b.operator()))return 1;
                if(!a.isVariable())return Objects.equals(a.semanticPayload(),b.semanticPayload())?0:1;
                if((a.bindingIndex()>=0)!=(b.bindingIndex()>=0))return 1;
                if(a.bindingIndex()<0)return Objects.equals(a.lexicalVariable(),b.lexicalVariable())?0:1;
                return costs[a.bindingIndex()]>=0 && costs[a.bindingIndex()]==b.bindingIndex()?0:1;
            }
            public int distance(RepairView.Node a,RepairView.Node b) { throw new AssertionError("Independent replay must not search"); }
        });
    }
    private static JSONObject affected() throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(BoundVariableShorterReplayTest.SOURCE));
        var p=prepare(m,"inv14"); var q=prepare(m,"inv14c");
        var pv=NestedPaidDeclarationReplayTest.checkedProjection(p,q); var qv=NestedPaidDeclarationReplayTest.checkedProjection(q,p);
        var out=new JSONObject();
        for(boolean reverse:new boolean[]{false,true}) {
            var a=reverse?q:p; var b=reverse?p:q; var av=reverse?qv:pv; var bv=reverse?pv:qv;
            var x=BoundVariableCostTest.comparison(a.normalizedForms().get(0)); var y=BoundVariableCostTest.comparison(b.normalizedForms().get(0));
            var report=DistanceReplayDiagnostics.capture(a,b);
            for(String name:List.of("fast","certified")) {
                var arm=report.getJSONObject(name); var phase=arm.getJSONArray("phases").getJSONObject(0);
                check(arm.getInt("distance")==20 && arm.getInt("matrixDistance")==18 && arm.getBoolean("numericalCostCoverage"),"native20 and full numeric slices "+name);
                var receipt=arm.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(name.equals("fast")?0:1).getJSONObject("receipt");
                var c=receipt.getJSONObject("matrixCorrespondenceCompletion");
                check(c.getBoolean("matrixActionsUnchanged") && c.getInt("replayedMatrixCost")==18,"all existing matrix charges replayed");
                check(c.getJSONArray("absencePremises").length()==1,"one missing zero keep");
                var premise=c.getJSONArray("absencePremises").getJSONObject(0);
                check(premise.getBoolean("sourceAbsent")==!reverse && premise.getBoolean("targetAbsent")==reverse,"actual one-sided absence");
                var original=map(c.getJSONObject("selectedMapping")); var completed=map(c.getJSONObject("completedMapping"));
                List<DeclarationReplay.Atom> da,db; List<String> ka,kb; Set<String> ua,ub;
                if(name.equals("fast")) {
                    da=NestedPaidDeclarationReplayTest.declarations(a.normalizedForms().get(0)); db=NestedPaidDeclarationReplayTest.declarations(b.normalizedForms().get(0));
                    ka=da.stream().map(v->new JSONArray(v.admission()).getString(0)).toList(); kb=db.stream().map(v->new JSONArray(v.admission()).getString(0)).toList();
                    ua=used(x); ub=used(y);
                    check(NestedPaidDeclarationReplayTest.checker(original,completed).verify(x,y,phase.getJSONObject("receipt"))==18,"independent Fast extension replay");
                    var invalid=new HashMap<>(original); invalid.put("_q0","_q0");
                    reject(()->NestedPaidDeclarationReplayTest.checker(original,invalid).verify(x,y,phase.getJSONObject("receipt")),"observed pair would change an existing head charge");
                } else {
                    da=NestedPaidDeclarationReplayTest.atoms(av.phases().get(0),receipt.getJSONArray("source")); db=NestedPaidDeclarationReplayTest.atoms(bv.phases().get(0),receipt.getJSONArray("target"));
                    ka=da.stream().map(v->Integer.toString(new JSONArray(v.occurrence()).getInt(4))).toList(); kb=db.stream().map(v->Integer.toString(new JSONArray(v.occurrence()).getInt(4))).toList();
                    ua=used(av.phases().get(0).matrix()); ub=used(bv.phases().get(0).matrix());
                    int[] labels=phase.getJSONArray("alphaMapping").toList().stream().mapToInt(v->((Number)v).intValue()).toArray();
                    int[] costs=labels.clone(); for(var e:completed.entrySet())costs[Integer.parseInt(e.getKey())]=Integer.parseInt(e.getValue());
                    check(certChecker(labels,costs).verify(av.phases().get(0).matrix(),bv.phases().get(0).matrix(),phase.getJSONObject("receipt"))==18,"independent certified extension replay");
                }
                var joint=new DeclarationMatrixLink(ka,kb,original,ua,ub,true);
                joint.verifyCompletion(da,db,receipt,c);
                check(new DeclarationMatrixLink(ka,kb,completed,ua,ub).verify(da,db,receipt)==2,"fixed completed map reconciles every original keep/delete");
                var forged=copy(c); forged.getJSONArray("absencePremises").getJSONObject(0).put("sourceAbsent",reverse);
                reject(()->joint.verifyCompletion(da,db,receipt,forged),"original endpoint census rejects forged absence");
                check(!report.getBoolean("complete") && !report.getBoolean("publicSequenceLinked"),"public proof remains blocked");
            }
            out.put(reverse?"reverse":"forward",report);
        }
        return out;
    }
    private static void consumers(String file,String left,String right,int expected) throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(Path.of("classified-data",file)));
        var a=prepare(m,left); var b=prepare(m,right);
        for(boolean reverse:new boolean[]{false,true}) {
            var report=DistanceReplayDiagnostics.capture(reverse?b:a,reverse?a:b);
            for(String name:List.of("fast","certified")) {
                var arm=report.getJSONObject(name);
                check(arm.getInt("distance")==expected && arm.getBoolean("numericalCostCoverage"),"consumer scalar and declaration components "+file+" "+name);
                check(arm.getJSONObject("declarationSlice").has("components"),"consumer components remain emitted");
            }
        }
    }
    private static void temporalGuard() throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,"var sig T {} pred temporal { all t:T | always t in T }");
        var p=prepare(m,"temporal");
        var fast=CanonicalDistance.prepare(p.normalizedForms());
        var method=CanonicalDistance.class.getDeclaredMethod("closedPrefix",CanonicalDistance.Prepared.class); method.setAccessible(true);
        check(!(Boolean)method.invoke(null,fast),"temporal/inherited Fast owner is outside absence admission");
        var evidence=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());
        var view=RepairProjection.project(evidence,p.normalizedForms());
        var certified=QuotientRepairDistance.class.getDeclaredMethod("closedPrefix",RepairView.class); certified.setAccessible(true);
        check(!(Boolean)certified.invoke(null,view),"temporal/inherited certified owner is outside absence admission");
    }
    public static void main(String[] args) throws Exception {
        boolean withConsumer=args.length==2 && args[0].equals("--with-bound-consumer");
        if(args.length>1 && !withConsumer) throw new IllegalArgumentException("[OUTPUT] or --with-bound-consumer OUTPUT");
        units(); temporalGuard(); var result=affected();
        // Development-only validation. The combined runner has its own single entry.
        if(withConsumer) BoundVariableShorterReplayTest.main(new String[0]);
        consumers("coursesOld/both/xJnitBpFv9K74WNvf_inv14.als","inv14","inv14c",49);
        consumers("classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12.als","inv12","inv12c",22);
        consumers("classroom_fol/both/2KmASqDBmqEueukiz_inv5.als","inv5","inv5c",10);
        result.put("checks",checks).put("complete",false);
        if(args.length>0) Files.writeString(Path.of(args[withConsumer?1:0]),result.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("UnusedDeclarationCompletionTest: "+checks+" checks passed; explicit absence completion20, unchanged matrix actions; public BLOCKED");
    }
}

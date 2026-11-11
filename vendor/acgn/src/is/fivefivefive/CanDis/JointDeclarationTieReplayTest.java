package is.fivefivefive.CanDis;

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

/** The demonstrated tie, plus an admitted crossing permutation and fail-closed linkage controls. */
public final class JointDeclarationTieReplayTest {
    private static int checks;
    private static final String SOURCE = """
            sig A {}
            sig B in A {}
            pred three { all a,b,c:A | a in B and b in B and c in B }
            pred two { all x,y:A | x in B and y in B }
            pred renamed { all u,v,w:A | u in B and v in B and w in B }
            """;
    private static final String CROSS_SOURCE = """
            sig A {}
            sig B, C in A {}
            sig D { edge: set D }
            pred crossLeft { all a,b:A | a in B and b in C }
            pred crossRight { all x,y:A | x in C and y in B }
            pred typed { all a:A, b:D | a in C and b in b.edge }
            """;

    private static void check(boolean value,String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    private static void reject(Runnable action,String why) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static Canonical.Prepared prepare(CompModule module,String name) {
        var visitor = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
    private static JSONObject declaration(JSONObject arm) {
        check(arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"joint incidence checked");
        int index = arm.getString("arm").equals("fast") ? 0 : 1;
        return arm.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(index).getJSONObject("receipt");
    }
    private static List<String> indices(int n) {
        var result = new ArrayList<String>(); for(int i=0;i<n;i++) result.add(Integer.toString(i)); return result;
    }
    private static Map<String,String> mapping(JSONObject arm) {
        var result = new LinkedHashMap<String,String>();
        var phase = arm.getJSONArray("phases").getJSONObject(0);
        if(arm.getString("arm").equals("fast")) {
            var raw = phase.getJSONObject("alphaMapping");
            for(String key:raw.keySet()) result.put(key.substring(2),raw.getString(key).substring(2));
        } else {
            var raw = phase.getJSONArray("alphaMapping");
            for(int i=0;i<raw.length();i++) if(raw.getInt(i)>=0) result.put(Integer.toString(i),Integer.toString(raw.getInt(i)));
        }
        return result;
    }
    private static JSONArray actions(JSONObject receipt) {
        var result = new JSONArray();
        for(Object item:receipt.getJSONArray("operations")) {
            var op=(JSONObject)item;
            result.put(new JSONArray().put(op.getString("kind")).put(op.getInt("leftIndex"))
                    .put(op.getInt("rightIndex")).put(op.getInt("cost")));
        }
        return result;
    }
    private static JSONObject compareMatrix(Canonical.Prepared a,Canonical.Prepared b,RepairView av,RepairView bv,
            JSONObject fast,JSONObject certified) throws Exception {
        var method=ScopedPaidAdmissionReplayTest.class.getDeclaredMethod("matrixComparison",EGraphNode.class,EGraphNode.class,
                NormalForm.class,NormalForm.class,RepairView.Phase.class,RepairView.Phase.class,JSONObject.class,JSONObject.class);
        method.setAccessible(true);
        return (JSONObject)method.invoke(null,BoundVariableCostTest.comparison(a.normalizedForms().get(0)),
                BoundVariableCostTest.comparison(b.normalizedForms().get(0)),a.normalizedForms().get(0),b.normalizedForms().get(0),
                av.phases().get(0),bv.phases().get(0),fast,certified);
    }
    private static JSONObject pair(Canonical.Prepared a,Canonical.Prepared b,boolean equal) throws Exception {
        var av=NestedPaidDeclarationReplayTest.checkedProjection(a,b);
        var bv=NestedPaidDeclarationReplayTest.checkedProjection(b,a);
        var report=DistanceReplayDiagnostics.capture(a,b);
        var fast=report.getJSONObject("fast"); var cert=report.getJSONObject("certified");
        int n=a.normalizedForms().get(0).getMatrixQuantiVars().size(),m=b.normalizedForms().get(0).getMatrixQuantiVars().size();
        var expected=new LinkedHashMap<String,String>();
        for(int i=0;i<Math.min(n,m);i++) expected.put(Integer.toString(i),Integer.toString(i));
        for(var arm:List.of(fast,cert)) {
            check(arm.getInt("distance")== (equal?0:4) && arm.getInt("matrixDistance")== (equal?0:3),"unchanged native minimum");
            check(mapping(arm).equals(expected),"source coordinate tie, independent arm search");
            check(!arm.getBoolean("complete") && !arm.getBoolean("publicSequenceLinked"),"public proof still blocked");
            var receipt=arm.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt");
            int cost;
            if(arm==fast) {
                var selected=new HashMap<String,String>();
                for(var e:expected.entrySet()) selected.put("_q"+e.getKey(),"_q"+e.getValue());
                var checker=NestedPaidDeclarationReplayTest.checker(selected,selected);
                var x=BoundVariableCostTest.comparison(a.normalizedForms().get(0));
                var y=BoundVariableCostTest.comparison(b.normalizedForms().get(0));
                cost=checker.verify(x,y,receipt);
                reject(()->checker.verify(x,y,copy(receipt).put("cost",100)),"wrong Fast matrix charge");
            } else {
                int[] selected=new int[n]; Arrays.fill(selected,-1);
                for(int i=0;i<Math.min(n,m);i++) selected[i]=i;
                var checker=NestedPaidDeclarationReplayTest.certChecker(selected);
                cost=checker.verify(av.phases().get(0).matrix(),bv.phases().get(0).matrix(),receipt);
                reject(()->checker.verify(av.phases().get(0).matrix(),bv.phases().get(0).matrix(),copy(receipt).put("cost",100)),"wrong certified matrix charge");
            }
            check(cost==(equal?0:3),"independent fixed-map replay without distance search");
            var decl=declaration(arm);
            var as=arm==fast?NestedPaidDeclarationReplayTest.declarations(a.normalizedForms().get(0)):
                    NestedPaidDeclarationReplayTest.atoms(av.phases().get(0),decl.getJSONArray("source"));
            var bs=arm==fast?NestedPaidDeclarationReplayTest.declarations(b.normalizedForms().get(0)):
                    NestedPaidDeclarationReplayTest.atoms(bv.phases().get(0),decl.getJSONArray("target"));
            var joint=new DeclarationMatrixLink(indices(n),indices(m),expected,Set.copyOf(indices(n)),Set.copyOf(indices(m)));
            check(joint.verify(as,bs,decl)==(equal?0:1),"original-coordinate declaration + matrix linkage incl free keeps");
            var wrong=new LinkedHashMap<>(expected); wrong.put("0","1"); wrong.put("1","0");
            reject(()->new DeclarationMatrixLink(indices(n),indices(m),wrong,Set.copyOf(indices(n)),Set.copyOf(indices(m))).verify(as,bs,decl),"substituted selected map");
            var corrupt=copy(decl); corrupt.getJSONArray("operations").getJSONObject(0).put("kind","delete");
            reject(()->joint.verify(as,bs,corrupt),"keep relabelled as deletion");
            var substituted=copy(decl); substituted.getJSONArray("operations").getJSONObject(0).getJSONObject("source").put("occurrence","foreign-owner");
            reject(()->joint.verify(as,bs,substituted),"foreign source occurrence");
            // Old independently optimal declaration DP path is numerically valid
            // but incompatible with the selected original-coordinate matrix map.
            int[][] dp=new int[n+1][m+1];
            for(int i=0;i<=n;i++) for(int j=0;j<=m;j++) dp[i][j]=Math.abs(i-j);
            var unconstrained=DeclarationReplay.capture(as,bs,dp,(i,j)->true);
            if(!equal) reject(()->joint.verify(as,bs,unconstrained),"old leading delete/insert cannot consume a mapped coordinate");
        }
        check(actions(declaration(fast)).similar(actions(declaration(cert))),"ordered declaration actions equal");
        var comparison=compareMatrix(a,b,av,bv,fast.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"),
                cert.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"));
        check(comparison.getBoolean("orderedMatrixActionsEqual"),"ordered matrix actions differ: "+comparison);
        return new JSONObject().put("native",report).put("orderedActions",comparison)
                .put("declarationActions",actions(declaration(fast))).put("complete",false);
    }

    private static JSONObject certifiedDeclarations(RepairView a,RepairView b,JSONObject matrix) {
        var trace=QuotientRepairDistance.evaluateWithTrace(a,b);
        trace.replay(matrix);
        return matrix.getJSONObject("declarationSlice");
    }
    private static JSONObject permutationControl(Canonical.Prepared a,Canonical.Prepared b) throws Exception {
        var av=NestedPaidDeclarationReplayTest.checkedProjection(a,b);
        var bv=NestedPaidDeclarationReplayTest.checkedProjection(b,a);
        var nativeMatrix=QuotientRepairDistance.evaluateWithTrace(av,bv).json();
        var report=DistanceReplayDiagnostics.capture(a,b);
        check(nativeMatrix.getInt("distance")==0,"crossing attaining mapping retained, no ordered-DP scalar restriction");
        check(mapping(nativeMatrix).equals(Map.of("0","1","1","0")),"actual native crossing map");
        var automatic=certifiedDeclarations(av,bv,nativeMatrix);
        check(automatic.getJSONObject("selectedDeclarationPermutation").getJSONArray("left").similar(new JSONArray(new int[]{1,0})),
                "existing certified source orbit automatically materialized");
        var explicit=copy(nativeMatrix);
        // The joint diagnostic's connecting permutation is not a replacement
        // for the immutable orbit choice made by the metric evaluator.
        var linked=certifiedDeclarations(av,bv,explicit);
        check(linked.getBoolean("selectedMatrixDeclarationLinksChecked"),"actual certified orbit permutation links full mapping");
        check(linked.getInt("cost")==0 && !linked.getBoolean("complete"),"permutation preserves charge, no public promotion");
        var d=linked.getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
        var original=NestedPaidDeclarationReplayTest.atoms(av.phases().get(0),nativeMatrixSource(av));
        var target=NestedPaidDeclarationReplayTest.atoms(bv.phases().get(0),nativeMatrixSource(bv));
        var source=List.of(original.get(1),original.get(0));
        check(new DeclarationMatrixLink(List.of("1","0"),List.of("0","1"),Map.of("0","1","1","0"),
                Set.of("0","1"),Set.of("0","1")).verify(source,target,d)==0,"independent original-coordinate permuted replay");
        for(int[] bad:List.of(new int[]{1,1},new int[]{2,0},new int[]{1})) {
            var changed=copy(explicit); changed.getJSONObject("selectedDeclarationPermutation").put("left",new JSONArray(bad));
            reject(()->certifiedDeclarations(av,bv,changed),"corrupt permutation");
        }
        var changed=copy(explicit); changed.put("selectedGlobalMapping",new JSONArray(new int[]{0,1}));
        reject(()->certifiedDeclarations(av,bv,changed),"global/phase mapping substitution");
        for(String name:List.of("fast","certified")) {
            var arm=report.getJSONObject(name);
            check(arm.getInt("distance")==0 && arm.getBoolean("numericalCostCoverage"),"crossing zero minimum and joint numerical coverage "+name);
            check(!arm.getBoolean("complete") && !arm.getBoolean("publicSequenceLinked"),"crossing no public promotion");
            var component=arm.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(name.equals("fast")?0:1);
            var perm=component.getJSONObject("connectingPermutation");
            check(perm.getJSONArray("source").similar(new JSONArray(new int[]{1,0}))
                    && perm.getJSONArray("target").similar(new JSONArray(new int[]{0,1})),"actual connecting permutation "+name);
            check(arm.getJSONArray("orderedDiagnosticActions").getJSONObject(0).getString("phaseKind").equals("declarationPermutation"),
                    "permutation precedes declaration keeps");
            var receipt=declaration(arm);
            var as=name.equals("fast")?NestedPaidDeclarationReplayTest.declarations(a.normalizedForms().get(0)):original;
            var bs=name.equals("fast")?NestedPaidDeclarationReplayTest.declarations(b.normalizedForms().get(0)):target;
            var reordered=List.of(as.get(1),as.get(0));
            var joint=new DeclarationMatrixLink(List.of("1","0"),List.of("0","1"),Map.of("0","1","1","0"),Set.of("0","1"),Set.of("0","1"));
            check(joint.verify(reordered,bs,receipt)==0,"automatic original-coordinate replay "+name);
            reject(()->joint.verify(as,bs,receipt),"missing connecting permutation endpoints "+name);
            var mr=arm.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt");
            if(name.equals("fast")) {
                var map=Map.of("_q0","_q1","_q1","_q0");
                check(NestedPaidDeclarationReplayTest.checker(map,map).verify(BoundVariableCostTest.comparison(a.normalizedForms().get(0)),
                        BoundVariableCostTest.comparison(b.normalizedForms().get(0)),mr)==0,"Fast crossed matrix independent replay");
                var helper=Class.forName("is.fivefivefive.CanDis.core.FastDeclarationPermutation");
                var admission=helper.getDeclaredMethod("admission",NormalForm.class,List.class); admission.setAccessible(true);
                @SuppressWarnings("unchecked") var owned=(java.util.function.BiPredicate<Integer,Integer>)admission.invoke(null,
                        a.normalizedForms().get(0),a.normalizedForms().get(0).getMatrixQuantiVars());
                DeclarationMatrixLink.requirePermutation(as,new int[]{1,0},owned);
                @SuppressWarnings("unchecked") var foreign=(java.util.function.BiPredicate<Integer,Integer>)admission.invoke(null,
                        b.normalizedForms().get(0),a.normalizedForms().get(0).getMatrixQuantiVars());
                reject(()->DeclarationMatrixLink.requirePermutation(as,new int[]{1,0},foreign),"foreign Fast declaration owner");
            } else check(NestedPaidDeclarationReplayTest.certChecker(new int[]{1,0})
                    .verify(av.phases().get(0).matrix(),bv.phases().get(0).matrix(),mr)==0,"certified crossed matrix independent replay");
        }
        var fast=report.getJSONObject("fast"); var cert=report.getJSONObject("certified");
        check(actions(declaration(fast)).similar(actions(declaration(cert))),"crossing declaration actions equal");
        var comparison=compareMatrix(a,b,av,bv,fast.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"),
                cert.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"));
        check(comparison.getBoolean("orderedMatrixActionsEqual"),"crossed ordered matrix actions differ: "+comparison);
        return new JSONObject().put("nativeSelected",nativeMatrix).put("explicitCheckedPermutation",linked)
                .put("automatic",report).put("orderedActions",comparison).put("complete",false);
    }
    private static JSONArray nativeMatrixSource(RepairView view) {
        var self=QuotientRepairDistance.replayWithSlices(view,view);
        return declaration(self).getJSONArray("source");
    }
    public static void main(String[] args) throws Exception {
        var model=CompUtil.parseEverything_fromString(A4Reporter.NOP,SOURCE);
        var crossModel=CompUtil.parseEverything_fromString(A4Reporter.NOP,CROSS_SOURCE);
        var three=prepare(model,"three"); var two=prepare(model,"two"); var renamed=prepare(model,"renamed");
        var crossLeft=prepare(crossModel,"crossLeft"); var crossRight=prepare(crossModel,"crossRight");
        var out=new JSONObject().put("source",SOURCE).put("crossSource",CROSS_SOURCE).put("forward",pair(three,two,false))
                .put("reverse",pair(two,three,false)).put("equalArity",pair(three,renamed,true))
                .put("crossingForward",permutationControl(crossLeft,crossRight))
                .put("crossingReverse",permutationControl(crossRight,crossLeft));
        var typed=NestedPaidDeclarationReplayTest.checkedProjection(prepare(crossModel,"typed"),three);
        var wrongDomain=QuotientRepairDistance.evaluateWithTrace(typed,typed).json();
        wrongDomain.getJSONObject("selectedDeclarationPermutation").put("left",new JSONArray(new int[]{1,0}));
        reject(()->certifiedDeclarations(typed,typed,wrongDomain),"typed/domain exchange lacks source orbit premise");
        reject(()->new DeclarationMatrixLink(List.of("0","1"),List.of("0"),Map.of("0","0","1","0"),Set.of(),Set.of()),"noninjective map");
        check(new DeclarationMatrixLink(List.of("0"),List.of("0"),Map.of(),Set.of(),Set.of(),true).allows(0,0,0),
                "unused declaration keep requires explicit admitted completion");
        out.put("checks",checks).put("publicCertification","BLOCKED");
        if(args.length==1) Files.writeString(Path.of(args[0]),out.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("JointDeclarationTieReplayTest: "+checks+" checks passed; ties and crossing permutation linked, public certification BLOCKED");
    }
}

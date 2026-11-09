package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import parser.ast.nodes.ModelUnit;

/** Native replay and cross-arm operation comparison, not a public source-edit proof. */
public final class ScopedPaidAdmissionReplayTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable action,String why){
        checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(why);
    }
    private static final Path SOURCE=Path.of("classified-data/classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12.als");
    private static boolean localCoordinate(EGraphNode n){
        try{var m=EGraphNode.class.getDeclaredMethod("hasComparisonLocalCoordinate");m.setAccessible(true);return (Boolean)m.invoke(n);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }

    // This finite comparison uses checked projection premises and the source
    // binder bridge, not raw IR spelling or erasure of literal result types.
    private static String fastHead(EGraphNode n,NormalForm form,Phase phase){
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE){
            String name=n.getAlphaName();
            if(localCoordinate(n)){
                check(name.matches("@fast-local:[0-9]+:[0-9]+"),"actual local coordinate syntax");
                return "VARIABLE/local:"+name.substring("@fast-local:".length());
            }
            for(int i=0;i<form.getMatrixQuantiVars().size();i++)if(form.getMatrixQuantiVars().get(i).getName().equals(name)){
                final int ordinal=i;
                int c=phase.bindings().stream().filter(b->b.role()==BindingRole.MATRIX&&b.ordinal()==ordinal).findFirst().orElseThrow().coordinate();
                return "VARIABLE/outer:"+c;
            }
            throw new IllegalArgumentException("Unlinked variable "+name);
        }
        String identity=switch(n.getOpcode()){
            // Projection names fields directly; Fast's native receipt adds a
            // field/type envelope. The original node and exact type bind both.
            case GLOBALBINDING,CONSTANT,REF -> n.getSemanticIdentity()==null?n.getSourceName():n.getSemanticIdentity();
            default -> "";
        };
        String payload="identity="+identity+(n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL
                ?";structure=certified-local-declaration":";type="+NestedPaidDeclarationReplayTest.type(n));
        return n.getOpcode()+"/"+payload;
    }
    private static String certHead(Node n){
        if(n.isVariable()){
            if(n.bindingIndex()>=0)return "VARIABLE/outer:"+n.bindingIndex();
            String local=n.lexicalVariable();
            check(local!=null && local.matches("@local\\[[0-9]+:[0-9]+\\]"),"actual projected local coordinate syntax");
            return "VARIABLE/local:"+local.substring("@local[".length(),local.length()-1);
        }
        return n.operator()+"/"+n.semanticPayload();
    }
    private static String fastKey(EGraphNode n,NormalForm f,Phase p){
        var children=new ArrayList<String>();for(var c:FastChainGeometry.children(n))children.add(fastKey(c,f,p));
        if(n.isOrderInsensitive())Collections.sort(children);
        return new JSONArray().put(fastHead(n,f,p)).put(new JSONArray(children.stream().map(JSONArray::new).toList())).toString();
    }
    private static String certKey(Node n){
        var children=new ArrayList<String>();for(var c:n.children())children.add(certKey(c));
        if(n.orderInsensitive())Collections.sort(children);
        return new JSONArray().put(certHead(n)).put(new JSONArray(children.stream().map(JSONArray::new).toList())).toString();
    }
    private static void correspondence(EGraphNode a,Node b,NormalForm f,Phase p,String ap,String bp,Map<String,String> paths){
        check(fastHead(a,f,p).equals(certHead(b)),"typed/bound endpoint head correspondence "+ap);
        check(a.isOrderInsensitive()==b.orderInsensitive(),"endpoint ordering correspondence "+ap);
        paths.put(ap,bp);
        var ac=FastChainGeometry.children(a);var bc=b.children();
        check(ac.size()==bc.size(),"metric child coverage "+ap);
        Set<Integer> used=new HashSet<>();
        for(int i=0;i<ac.size();i++){
            int j=i;
            if(a.isOrderInsensitive()){
                var matches=new ArrayList<Integer>();String key=fastKey(ac.get(i),f,p);
                for(int k=0;k<bc.size();k++)if(!used.contains(k)&&key.equals(certKey(bc.get(k))))matches.add(k);
                check(matches.size()==1,"unambiguous occurrence correspondence "+ap+"/"+i+" source="+key
                        +" targets="+bc.stream().map(ScopedPaidAdmissionReplayTest::certKey).toList());
                j=matches.get(0);
            }
            check(used.add(j),"injective occurrence bridge");
            correspondence(ac.get(i),bc.get(j),f,p,ap+"/"+i,bp+"/"+j,paths);
        }
    }
    private static JSONArray declActions(JSONObject receipt){
        var result=new JSONArray();
        for(Object x:receipt.getJSONArray("operations")){
            var op=(JSONObject)x;var v=new JSONObject();
            for(String k:List.of("kind","cost","leftIndex","rightIndex"))v.put(k,op.get(k));
            for(String k:List.of("source","target"))v.put(k,op.isNull(k)?JSONObject.NULL:op.getJSONObject(k).getJSONArray("tuple"));
            result.put(v);
        }return result;
    }
    private static void actions(JSONObject d,String a,String b,Map<String,String> ap,Map<String,String> bp,JSONArray out){
        String kind=d.getString("kind");
        check(List.of("node","insert","delete").contains(kind),"no unverified matrix alternative");
        out.put(new JSONObject().put("kind",kind).put("cost",d.getInt(kind.equals("node")?"headCost":"cost"))
                .put("source",a==null?JSONObject.NULL:Objects.requireNonNull(ap.get(a)))
                .put("target",b==null?JSONObject.NULL:Objects.requireNonNull(bp.get(b))));
        if(kind.equals("node"))for(Object item:d.getJSONArray("children")){
            var c=(JSONObject)item;int i=c.getInt("leftIndex"),j=c.getInt("rightIndex");
            actions(c.getJSONObject("edit"),i<0?null:a+"/"+i,j<0?null:b+"/"+j,ap,bp,out);
        }
    }
    private static JSONObject matrixComparison(EGraphNode a,EGraphNode b,NormalForm af,NormalForm bf,Phase av,Phase bv,JSONObject ar,JSONObject br){
        var ap=new HashMap<String,String>();var bp=new HashMap<String,String>();
        correspondence(a,av.matrix(),af,av,"$","$",ap);correspondence(b,bv.matrix(),bf,bv,"$","$",bp);
        var ai=new HashMap<String,String>();var bi=new HashMap<String,String>();
        ap.values().forEach(x->ai.put(x,x));bp.values().forEach(x->bi.put(x,x));
        var fast=new JSONArray();var cert=new JSONArray();
        actions(ar.getJSONObject("derivation"),"$","$",ap,bp,fast);
        actions(br.getJSONObject("derivation"),"$","$",ai,bi,cert);
        int first=0;while(first<Math.min(fast.length(),cert.length())&&fast.getJSONObject(first).similar(cert.getJSONObject(first)))first++;
        var result=new JSONObject().put("metricEndpointCorrespondenceChecked",true).put("publicCorrespondenceChecked",false)
                .put("orderedMatrixActionsEqual",fast.similar(cert)).put("fastActions",fast).put("certifiedActions",cert);
        if(!fast.similar(cert))result.put("firstDifference",first).put("fastFirst",first<fast.length()?fast.get(first):JSONObject.NULL)
                .put("certifiedFirst",first<cert.length()?cert.get(first):JSONObject.NULL);
        return result;
    }
    public static void main(String[] args)throws Exception{
        if(args.length>1)throw new IllegalArgumentException("[EXISTING_FRESH_OUTPUT_DIRECTORY]");
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(SOURCE));
        var prepared=new ArrayList<Canonical.Prepared>();
        for(String name:List.of("inv12","inv12c")){
            var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);
            prepared.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));
        }
        var views=List.of(NestedPaidDeclarationReplayTest.checkedProjection(prepared.get(0),prepared.get(1)),
                NestedPaidDeclarationReplayTest.checkedProjection(prepared.get(1),prepared.get(0)));
        for(var view:views)NestedPaidDeclarationReplayTest.noChoices(view.phases().get(0).matrix());
        for(boolean reverse:new boolean[]{false,true}){
            int l=reverse?1:0,r=1-l;var p=prepared.get(l);var q=prepared.get(r);
            var a=p.normalizedForms().get(0);var b=q.normalizedForms().get(0);var av=views.get(l).phases().get(0);var bv=views.get(r).phases().get(0);
            var report=DistanceReplayDiagnostics.capture(p,q);var fast=report.getJSONObject("fast");var cert=report.getJSONObject("certified");
            var fp=fast.getJSONArray("phases").getJSONObject(0);var cp=cert.getJSONArray("phases").getJSONObject(0);
            var map=Map.of("_q0","_q0");
            check(fp.getJSONObject("alphaMapping").similar(new JSONObject(map)),"explicit paid source map");
            int[] cm=cp.getJSONArray("alphaMapping").toList().stream().mapToInt(v->((Number)v).intValue()).toArray();
            check(Arrays.equals(cm,reverse?new int[]{0}:new int[]{0,-1,-1}),"same certified paid map");
            var left=BoundVariableCostTest.comparison(a);var right=BoundVariableCostTest.comparison(b);
            var fr=fp.getJSONObject("receipt");var cr=cp.getJSONObject("receipt");
            check(NestedPaidDeclarationReplayTest.checker(map,map).verify(left,right,fr)==19,"independent Fast matrix19, search disabled");
            check(NestedPaidDeclarationReplayTest.certChecker(cm).verify(av.matrix(),bv.matrix(),cr)==19,"independent certified matrix19, search disabled");
            reject(()->NestedPaidDeclarationReplayTest.checker(map,Map.of()).verify(left,right,fr),"unpaid matrix link");
            reject(()->NestedPaidDeclarationReplayTest.checker(map,map).verify(right,left,fr),"substituted matrix endpoints");
            reject(()->NestedPaidDeclarationReplayTest.checker(map,map).verify(left,right,new JSONObject(fr.toString()).put("cost",18)),"corrupted matrix cost");
            int[] none=new int[cm.length];Arrays.fill(none,-1);
            reject(()->NestedPaidDeclarationReplayTest.certChecker(none).verify(av.matrix(),bv.matrix(),cr),"certified missing link");
            var fd=fast.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
            var cd=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
            var fa=NestedPaidDeclarationReplayTest.declarations(a);var fb=NestedPaidDeclarationReplayTest.declarations(b);
            check(DeclarationReplay.verify(fa,fb,fd)==3,"Fast paid declaration3");
            var ca=NestedPaidDeclarationReplayTest.atoms(av,cd.getJSONArray("source"));var cb=NestedPaidDeclarationReplayTest.atoms(bv,cd.getJSONArray("target"));
            check(DeclarationReplay.verify(ca,cb,cd)==3,"certified paid declaration3");
            for(var receipt:List.of(fd,cd)){
                int diagonal=0;
                for(Object item:receipt.getJSONArray("operations")){
                    var op=(JSONObject)item;
                    if(op.getInt("leftIndex")>=0&&op.getInt("rightIndex")>=0){
                        check(op.getInt("leftIndex")==0&&op.getInt("rightIndex")==0&&op.getInt("cost")==1,"exact paid diagonal licenses matrix map");diagonal++;
                    }
                }check(diagonal==1,"one paid diagonal, two one-sided declarations");
            }
            var corrupt=new JSONObject(fd.toString());corrupt.getJSONArray("source").getJSONObject(0).put("admission","foreign");
            reject(()->DeclarationReplay.verify(fa,fb,corrupt),"foreign declaration evidence");
            check(declActions(fd).similar(declActions(cd)),"ordered declaration actions agree under original ordinal bridge");
            var comparison=matrixComparison(left,right,a,b,av,bv,fr,cr).put("orderedDeclarationActionsEqual",true);
            check(comparison.getBoolean("orderedMatrixActionsEqual"),"ordered matrix derivations differ under checked endpoint bridge: "+comparison);
            check(comparison.getJSONArray("fastActions").length()==15&&fd.getJSONArray("operations").length()==3,
                    "three declaration and fifteen matrix actions, including zero-charge heads");
            for(var arm:List.of(fast,cert)){
                check(PhaseReplay.verifyDiagnosticOrder(arm.getJSONObject("temporalSlice"),arm.getJSONObject("declarationSlice"),arm.getJSONArray("phases"),arm.getJSONArray("orderedDiagnosticActions"))==22,"native diagnostic sequence22");
                check(arm.getInt("distance")==22,"native total22");
                var changed=new JSONArray(arm.getJSONArray("orderedDiagnosticActions").toString());
                Object first=changed.get(0);changed.put(0,changed.get(1)).put(1,first);
                reject(()->PhaseReplay.verifyDiagnosticOrder(arm.getJSONObject("temporalSlice"),arm.getJSONObject("declarationSlice"),arm.getJSONArray("phases"),changed),
                        "same-cost action reorder rejected");
            }
            check(!report.getBoolean("complete")&&!report.getBoolean("publicSequenceLinked")&&!report.getBoolean("javaLeanCorrespondenceChecked"),"public alignment BLOCKED");
            System.out.println((reverse?"reverse":"forward")+" action comparison: "+comparison);
            if(args.length==1){
                var dir=Path.of(args[0]);String prefix=reverse?"reverse":"forward";
                Files.writeString(dir.resolve(prefix+".json"),report.toString(),StandardOpenOption.CREATE_NEW);
                Files.writeString(dir.resolve(prefix+"-actions.json"),comparison.toString(),StandardOpenOption.CREATE_NEW);
            }
        }
        System.out.println("ScopedPaidAdmissionReplayTest: "+checks+" checks passed; native scoped replay only, public BLOCKED");
    }
}

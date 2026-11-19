package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.GraphType;

/** Actual source-object, shared cost and retained-program controls for Fast REF transport. */
public final class FastTemporalReferenceTest {
    private static int checks,negatives,costs,equalAliasNonfree;
    private static Object get(Object value,String name)throws Exception {
        var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static void set(Object value,String name,Object replacement)throws Exception {
        var f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,replacement);
    }
    private static java.lang.reflect.Method method(Class<?> type,String name,Class<?>... args)throws Exception {
        var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }
    private static void check(boolean value,String why) {checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable action,String why) {
        checks++;negatives++;
        try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted "+why);
    }
    private static OrderedTreeEditDistance.Alignment alignment(JSONObject trace) {
        var program=trace.getJSONObject("nativeTemporalProgram");
        var matches=new ArrayList<OrderedTreeEditDistance.Match>();
        for(Object value:program.getJSONArray("selectedCorrespondence")) {
            var pair=(JSONArray)value;matches.add(new OrderedTreeEditDistance.Match(pair.getInt(0),pair.getInt(1)));
        }
        return new OrderedTreeEditDistance.Alignment(program.getInt("cost"),matches);
    }
    private static List<RepairView.Node> refs(RepairView.Node root) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<RepairView.Node,Boolean>());
        var result=new ArrayList<RepairView.Node>();var pending=new ArrayDeque<RepairView.Node>();
        if(root!=null)pending.push(root);
        while(!pending.isEmpty()) {var node=pending.pop();if(!seen.add(node))continue;
            if(node.isTemporalReference())result.add(node);
            pending.addAll(node.children());pending.addAll(node.certifiedAlternatives());}
        return result;
    }
    private static RepairView.Node corresponding(RepairView view,int phase,EGraphNode node) {
        return refs(view.phases().get(phase).matrix()).stream().filter(n->
                n.originalReferenceOrigin().reference().getSourceOccurrenceLineage()==node.getSourceOccurrenceLineage())
                .findFirst().orElseThrow(()->new AssertionError("Missing certified source REF"));
    }
    @SuppressWarnings("unchecked")
    private static void pair(JSONObject identity,Path out,JSONArray rows)throws Exception {
        var pair=LocalOwnerTraceTest.prepare(identity);
        for(int direction=0;direction<2;direction++) {
            var a=pair.get(direction);var b=pair.get(1-direction);
            var pa=(CanonicalDistance.Prepared)get(a,"delegate");var pb=(CanonicalDistance.Prepared)get(b,"delegate");
            var fa=(FastTemporalReferences)get(pa,"temporalReferences");var fb=(FastTemporalReferences)get(pb,"temporalReferences");
            long start=System.nanoTime();int scalar=Canonical.distance(a,b);
            long scalarNanos=System.nanoTime()-start;start=System.nanoTime();
            var fast=CanonicalRepairTraces.fast(a,b);long traceNanos=System.nanoTime()-start;
            var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
            var cert=CanonicalRepairTraces.certified(ca,cb);
            var order=new JSONObject();QueueProbe.orderedReplay(fast.distance(),cert.distance(),
                    ()->fast.replay(fast.json()),()->cert.replay(cert.json()),order);
            check(scalar==fast.distance(),"scalar and retained temporal context agree");
            check(scalar==cert.distance(),"shared native component totals");
            check(Canonical.distance(b,a)==CanonicalRepairTraces.fast(b,a).distance(),"reverse context isolation");
            var selected=alignment(fast.json());var payment=fa.select(fb,selected);
            payment.requireReceipt(fast.json().getJSONObject("nativeTemporalProgram"),payment.json());
            reject(()->fa.select(fb,new OrderedTreeEditDistance.Alignment(selected.cost()+1,selected.matches())),"unpaid alignment");
            var cls=Class.forName("is.fivefivefive.CanDis.metric.SelectedTemporalPayments");
            var shared=method(cls,"from",RepairView.class,RepairView.class,OrderedTreeEditDistance.Alignment.class)
                    .invoke(null,ca.repairView(),cb.repairView(),alignment(cert.json()));
            var pays=method(cls,"pays",int.class,RepairView.Node.class,RepairView.Node.class);
            var left=(IdentityHashMap<EGraphNode,?>)get(fa,"descriptors");
            var right=(IdentityHashMap<EGraphNode,?>)get(fb,"descriptors");
            for(var x:left.entrySet())for(var y:right.entrySet()) {
                int p=(int)get(x.getValue(),"phase");if(p!=(int)get(y.getValue(),"phase"))continue;
                var cx=corresponding(ca.repairView(),p,x.getKey());var cy=corresponding(cb.repairView(),p,y.getKey());
                boolean exact=cx.observationPhases().equals(cy.observationPhases())
                        &&Objects.equals(cx.semanticPayload(),cy.semanticPayload());
                int expected=exact||(boolean)pays.invoke(shared,p,cx,cy)?0:1;
                check(payment.updateCost(x.getKey(),y.getKey())==expected,"authenticated REF cost correspondence");costs++;
                var alias=method(EGraphNode.class,"comparisonTemporalReferenceName");
                if(expected==1&&Objects.equals(alias.invoke(x.getKey()),alias.invoke(y.getKey())))equalAliasNonfree++;
                var clone=(EGraphNode)method(EGraphNode.class,"fastComparisonMatrix",EGraphNode.class).invoke(null,x.getKey());
                reject(()->payment.updateCost(clone,y.getKey()),"same-metadata foreign source occurrence");
                set(clone,"temporalReferenceAuthorityId",-1L);
                reject(()->payment.updateCost(clone,y.getKey()),"missing-marker foreign source occurrence");
                var targetClone=(EGraphNode)method(EGraphNode.class,"fastComparisonMatrix",EGraphNode.class).invoke(null,y.getKey());
                for(long absent:List.of(-1L,0L)) {
                    set(clone,"temporalReferenceAuthorityId",absent);set(targetClone,"temporalReferenceAuthorityId",absent);
                    reject(()->payment.updateCost(x.getKey(),targetClone),"missing-marker foreign target occurrence");
                    reject(()->payment.updateCost(clone,targetClone),"both missing-marker foreign occurrences");
                }
                var targetOwner=pb.comparisonForms().get(p).comparisonSource();
                reject(()->targetOwner.temporalReferenceOrigin(x.getKey()),"foreign owner registry");
            }
            if(!left.isEmpty()) {
                var tree=get(pa,"temporalTree");
                var shape=(FastTemporalReferences.Shape)method(CanonicalDistance.class,"temporalShape",tree.getClass()).invoke(null,tree);
                reject(()->new FastTemporalReferences(pa.comparisonForms(),shape,pa::fastAlternatives,n->GraphType.INT),"non-Boolean REF");
                var duplicate=new ArrayList<>(pa.comparisonForms());duplicate.add(duplicate.get(0));
                reject(()->new FastTemporalReferences(duplicate,shape,pa::fastAlternatives,n->GraphType.BOOL),"duplicate source phase");
                var missing=new FastTemporalReferences.Shape(shape.label(),shape.branches(),shape.incomingRole(),List.of());
                reject(()->new FastTemporalReferences(pa.comparisonForms(),missing,pa::fastAlternatives,n->GraphType.BOOL)
                        .select(fb,selected),"missing temporal occurrence");
                var child=shape.children().get(0);var children=new ArrayList<>(shape.children());
                children.set(0,new FastTemporalReferences.Shape(child.label(),child.branches(),999,child.children()));
                var badRole=new FastTemporalReferences.Shape(shape.label(),shape.branches(),shape.incomingRole(),children);
                reject(()->new FastTemporalReferences(pa.comparisonForms(),badRole,pa::fastAlternatives,n->GraphType.BOOL),"foreign typed owner role");
                for(var entry:left.entrySet()) {
                    int phase=(int)get(entry.getValue(),"phase");var form=pa.comparisonForms().get(phase);
                    var original=form.getMatrixEGraph();
                    for(String corruption:List.of("type","source-lineage","authority","missing-authority","zero-authority","source-slot")) {
                        var copy=(EGraphNode)method(EGraphNode.class,"fastComparisonMatrix",EGraphNode.class).invoke(null,original);
                        var pending=new ArrayDeque<EGraphNode>();pending.push(copy);EGraphNode changed=null;
                        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
                        while(!pending.isEmpty()){var n=pending.pop();if(!seen.add(n))continue;
                            if(n.getOpcode()==EGraphNode.Opcode.REF&&n.hasTemporalReferenceAuthority()){changed=n;break;}
                            pending.addAll(n.getChildren());}
                        if(changed==null)continue;
                        switch(corruption) {
                            case "type" -> set(changed,"exactAlloyType",is.fivefivefive.ACGN.alloy.ExactAlloyType.intType());
                            case "source-lineage" -> set(changed,"sourceOccurrenceLineage",Long.MAX_VALUE);
                            case "authority" -> set(changed,"temporalReferenceAuthorityId",Long.MAX_VALUE);
                            case "missing-authority" -> set(changed,"temporalReferenceAuthorityId",-1L);
                            case "zero-authority" -> set(changed,"temporalReferenceAuthorityId",0L);
                            case "source-slot" -> set(changed,"sourceName","temporal[999999:1]");
                        }
                        try {set(form,"matrixEGraphRoot",copy);
                            reject(()->new FastTemporalReferences(pa.comparisonForms(),shape,pa::fastAlternatives,n->GraphType.BOOL),"actual REF "+corruption);
                        }finally {set(form,"matrixEGraphRoot",original);}
                    }
                    break;
                }
            }
            for(String mode:List.of("owner","slot","branches","operator","source","type","map","cost","missing","duplicate","typed")) {
                var bad=fast.json();var receipt=bad.getJSONObject("selectedTemporalReferencePayments");
                var endpoints=receipt.getJSONArray("source");boolean changed=true;
                int index=endpoints.length()>1?1:0;var endpoint=endpoints.getJSONObject(index);
                switch(mode) {
                    case "owner" -> endpoint.put("ownerPostorder",99999);
                    case "slot" -> endpoint.put("sourceLocalSlot",99999);
                    case "branches" -> endpoint.put("branches",new JSONArray().put(99999));
                    case "operator" -> endpoint.put("operator","foreign");
                    case "source","type" -> {
                        var branches=endpoint.getJSONArray("typedBranches");
                        if(branches.isEmpty()){changed=false;break;}
                        branches.getJSONObject(0).put(mode.equals("source")?"sourcePath":"kind","foreign");
                    }
                    case "map" -> receipt.put("selectedCorrespondence",new JSONArray());
                    case "cost" -> receipt.put("temporalCost",selected.cost()+1);
                    case "missing" -> endpoints.remove(index);
                    case "duplicate" -> endpoints.put(new JSONObject(endpoint.toString()));
                    case "typed" -> {
                        if(receipt.isNull("typedDerivation")){changed=false;break;}
                        receipt.getJSONObject("typedDerivation").put("operations",new JSONArray());
                    }
                }
                if(changed)reject(()->fast.replay(bad),"retained payment "+mode);
            }
            check(fast.replay(fast.json())==fast.distance(),"original trace remains executable");
            rows.put(new JSONObject().put("id",identity.getString("id")).put("direction",direction)
                    .put("scalarNanos",scalarNanos).put("traceNanos",traceNanos).put("distance",scalar).put("replay",order));
        }
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        var synthetic=new NormalForm();
        var ref=new EGraphNode(1,EGraphNode.Opcode.REF,List.of(),false,0,false,EGraphNode.Metatype.BOOLEAN);
        ref.setSourceName("fixture-reference");ref.setSourceType("Bool");
        ref.setExactAlloyType(is.fivefivefive.ACGN.alloy.ExactAlloyType.boolType());
        set(synthetic,"matrixEGraphRoot",ref);
        var fixture=CanonicalDistance.prepare(List.of(synthetic));
        check(CanonicalDistance.distance(fixture,fixture)==0,"genuine unbound synthetic fixture remains supported");
        var fixtureTrace=CanonicalDistance.evaluateWithTrace(fixture,fixture);
        check(fixtureTrace.replay(fixtureTrace.json())==0,"unbound fixture original replay");
        for(Object value:new JSONObject(Files.readString(Path.of(args[0]))).getJSONArray("cases"))
            pair(((JSONObject)value).getJSONObject("identity"),out,rows);
        check(costs>0,"source-linked REF costs exercised");
        check(equalAliasNonfree>0,"equal-alias different-edge negative witnesses exercised");
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("negatives",negatives)
                .put("costComparisons",costs).put("equalAliasNonfree",equalAliasNonfree).put("rows",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("FastTemporalReferenceTest: "+checks+" checks, "+negatives+" negatives, "+costs+" cost comparisons");
    }
}

package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.theory.*;

/** Source-linked explicit-domain sharing; no scalar-only acceptance. */
public final class LocalDomainPortSharingTest {
    private static int checks,negatives;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void reject(Runnable action,String message){
        checks++;negatives++;
        try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted "+message);
    }
    private static void ports(CanonicalAlloyPipeline.Prepared p) {
        int found=0;
        for(var c:p.semanticArtifact().classes().values())for(var shape:c.shapeWitnesses().keySet()){
            var n=shape.node();if(!n.operator().operator().equals("ALLOY/LOCAL-BIND-DOMAINS/COMPREHENSION"))continue;
            found++;check(n.ports().size()==2,"one exact domain operand and one binder body");
            var domain=(OnePort)n.ports().get(0);var block=(BindBlockPort)n.ports().get(1);
            var coordinates=block.schema().descriptor().coordinates();
            check(coordinates.size()==2,"both ordered comprehension columns retained");
            check(coordinates.get(0).domain().equals(coordinates.get(1).domain()),"both descriptors reference the shared exact operand");
            var invocation=((InvocationPortLeaf)domain.leaf()).invocation();
            var from=invocation.embedding().source().iterator().next();
            var old=invocation.embedding().apply(from);
            var foreign=invocation.callerContext().slots().stream().filter(s->!s.equals(old)&&s.type().equals(old.type())).findFirst().orElseThrow();
            var redirected=new LinkedHashMap<>(invocation.embedding().mapping());redirected.put(from,foreign);
            var other=OnePort.invocation(domain.context(),new TypedInvocation(invocation.eclass(),
                TypedEmbedding.of(invocation.embedding().source(),domain.context(),redirected)));
            check(!domain.equals(other)&&List.of(domain).indexOf(other)==-1,"same eclass with different real typed embedding does not share");negatives++;
            var context=domain.context().plus(TypedSlot.canonicalFree(old.type(),99));
            var ambient=OnePort.invocation(context,new TypedInvocation(invocation.eclass(),
                TypedEmbedding.of(invocation.embedding().source(),context,invocation.embedding().mapping())));
            check(!domain.equals(ambient)&&List.of(domain).indexOf(ambient)==-1,"foreign caller context does not share");negatives++;
            reject(()->new OnePort(new OnePortSchema(GraphType.BOOL),domain.context(),domain.leaf()),"changed domain result type");
            reject(()->TypedEmbedding.of(invocation.embedding().source(),domain.context(),Map.of()),"missing dependency mapping");
        }
        check(found>0,"actual explicit local domain constructor present");
    }
    public static void main(String[] args)throws Exception {
        var id=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(id);var prepared=new ArrayList<CanonicalAlloyPipeline.Prepared>();
        for(var p:pair){var c=CanonicalAlloyPipeline.prepare(p);ports(c);prepared.add(c);}
        check(prepared.get(0).canonicalObservation().equivalentTo(prepared.get(1).canonicalObservation()),"actual producer observations agree after typed sharing");
        for(int direction=0;direction<2;direction++){
            var a=pair.get(direction);var b=pair.get(1-direction);
            var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(prepared.get(direction),prepared.get(1-direction));
            var row=new JSONObject().put("identity",id).put("direction",direction).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),row.toString(),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),row);
            var tampered=new JSONObject(certified.json().toString());
            var operation=tampered.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0);
            operation.put("cost",operation.getInt("cost")+1);
            reject(()->certified.replay(tampered),"altered selected operation cost");
            var typed=new JSONObject(certified.json().toString());
            var before=typed.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0).getJSONObject("before");
            before.put("head",before.getString("head")+"foreign");
            reject(()->certified.replay(typed),"altered selected typed head");
            var comparison=NativeOperationComparison.compare(a,b,fast,certified);row.put("comparison",comparison.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),row.toString(),StandardOpenOption.CREATE_NEW);
            check(comparison.evidence().getString("status").equals("MATCHED"),"source/path correspondence "+comparison.evidence());
        }
        Files.writeString(out.resolve("result.json"),new JSONObject().put("status","PASS").put("checks",checks).put("negativeControls",negatives)
            .put("directions",2).put("synthetic",id.optBoolean("synthetic")).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("LocalDomainPortSharingTest: "+checks+" checks, "+negatives+" negatives");
    }
}

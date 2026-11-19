package is.fivefivefive.CanDis;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** Source-issued normalized guard domains, never receipt-supplied equivalences. */
public final class NormalizedDeclarationDomainTest {
    private static int checks, negatives, normalizedGuards;
    private static void check(boolean ok,String why) {checks++;if(!ok)throw new AssertionError(why);}
    private static Object get(Object owner,String name)throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static void set(Object owner,String name,Object value)throws Exception {
        var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);
    }
    private static Method method(Class<?> owner,String name,Class<?>... types)throws Exception {
        var m=owner.getDeclaredMethod(name,types);m.setAccessible(true);return m;
    }
    private static is.fivefivefive.ACGN.alloy.SigSymbol signature(EGraphNode node)throws Exception {
        return (is.fivefivefive.ACGN.alloy.SigSymbol)method(EGraphNode.class,"authenticatedSignature").invoke(node);
    }
    private static void reject(Runnable action,String why) {
        checks++;negatives++;
        try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted "+why);
    }
    private static int guards(List<NormalForm> forms)throws Exception {
        int found=0;
        var evidence=method(NormalForm.class,"retainedDeclarationDomainEvidence",NormalForm.DeclarationFrame.class);
        var clone=method(EGraphNode.class,"fastComparisonMatrix",EGraphNode.class);
        for(var form:forms)for(var q:form.getMatrixQuantiVars())for(var frame:q.getDeclarationFrames()) {
            var retained=(JSONObject)evidence.invoke(form,frame);
            if(retained==null||!retained.has("normalization"))continue;
            found++;normalizedGuards++;
            var normalization=retained.getJSONObject("normalization");
            check(normalization.getString("rule").equals("full-carrier-intersection"),"existing typed carrier rule");
            check(!normalization.getString("source").equals(normalization.getString("target")),"real normalization");
            var original=(EGraphNode)get(form,"matrixEGraphRoot");
            for(String corruption:List.of("domain","domain-type","binding","type","issuer","polarity","context","missing","duplicate")) {
                var copy=(EGraphNode)clone.invoke(null,original);
                var guard=copy;
                EGraphNode parent=null;
                var path=retained.getString("path").split("/");
                for(int i=1;i<path.length;i+=2){parent=guard;guard=guard.getChildren().get(Integer.parseInt(path[i+1]));}
                switch(corruption) {
                    case "domain" -> set(guard.getChildren().get(1),"semanticIdentity","foreign-domain");
                    case "domain-type" -> set(guard.getChildren().get(1),"exactAlloyType",ExactAlloyType.boolType());
                    case "binding" -> set(guard.getChildren().get(0),"alphaName","foreign-binding");
                    case "type" -> set(guard.getChildren().get(0),"exactAlloyType",ExactAlloyType.boolType());
                    case "issuer" -> set(guard,"domainGuardOrigin",null);
                    case "polarity" -> set(guard,"opcode",guard.getOpcode()==Opcode.IN?Opcode.NOT_IN:Opcode.IN);
                    case "context" -> copy=guard;
                    case "missing","duplicate" -> {
                        check(parent!=null,"guard has enclosing context");
                        var children=new ArrayList<>(parent.getChildClasses());
                        if(corruption.equals("missing"))children.remove(Integer.parseInt(path[path.length-1]));
                        else children.add(guard.getEClassRef());
                        set(parent,"childClasses",children);
                    }
                }
                try {
                    set(form,"matrixEGraphRoot",copy);
                    check(evidence.invoke(form,frame)==null,"normalized guard rejects "+corruption);negatives++;
                }finally {set(form,"matrixEGraphRoot",original);}
            }
            var phase=get(form,"phasePath");
            try {set(form,"phasePath","foreign-phase");check(evidence.invoke(form,frame)==null,"foreign phase");negatives++;}
            finally {set(form,"phasePath",phase);}
            check(evidence.invoke(form,frame)!=null,"restored normalized guard");
        }
        return found;
    }
    private static void pair(JSONObject identity,Path out,JSONArray rows)throws Exception {
        var endpoints=LocalOwnerTraceTest.prepare(identity);
        for(int direction=0;direction<2;direction++) {
            var a=endpoints.get(direction);var b=endpoints.get(1-direction);
            var fast=CanonicalRepairTraces.fast(a,b);
            var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var order=new JSONObject();
            QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),order);
            var comparison=NativeOperationComparison.compare(a,b,fast,cert);
            Files.writeString(out.resolve(identity.getString("id")+"-"+direction+".json"),new JSONObject()
                    .put("identity",identity).put("direction",direction).put("fast",fast.json()).put("certified",cert.json())
                    .put("replay",order).put("comparison",comparison.evidence()).toString(2),StandardOpenOption.CREATE_NEW);
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"actual native paths "+identity.getString("id")+": "+comparison.reason());
            check(Canonical.distance(a,b)==fast.distance(),"numeric/captured parity");
            guards(fast.comparisonForms(true));guards(fast.comparisonForms(false));
            var bad=fast.json().put("distance",fast.distance()+1);reject(()->fast.replay(bad),"changed total payment");
            if(!fast.json().optJSONArray("selectedPaidSlotRoutes").isEmpty()) {
                var changed=fast.json();changed.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0)
                        .getJSONArray("sourceFrames").getJSONObject(0).getJSONArray("frames").getJSONObject(0)
                        .getJSONArray("dependencies").put("foreign-owner");
                reject(()->fast.replay(changed),"changed raw source dependencies");
            }
            rows.put(new JSONObject().put("id",identity.getString("id")).put("direction",direction).put("distance",fast.distance()));
        }
    }
    private static EGraphNode intersection(EGraphNode node) {
        if(node.getOpcode()==Opcode.INTERSECT)return node;
        for(var child:node.getChildren()){var found=intersection(child);if(found!=null)return found;}
        return null;
    }
    private static EGraphNode intersection(List<NormalForm> forms) {
        for(var form:forms){var found=intersection(form.getMatrixEGraph());if(found!=null)return found;}
        throw new AssertionError("Missing non-contained sibling intersection");
    }
    private static JSONObject identity(Path source,String id)throws Exception {
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        return new JSONObject().put("id",id).put("source",source.toString()).put("sha256",hash).put("left","left").put("right","right");
    }
    private static void issuanceControls(Path out)throws Exception {
        var source=out.resolve("non-contained.als");
        Files.writeString(source,"""
                var sig Carrier {}
                var sig S, T in Carrier {}
                pred left { always (all x:S&T | always x in S) }
                pred right { always (all x:S&T | always x in S) }
                """,StandardOpenOption.CREATE_NEW);
        var id=identity(source,"non-contained");var p=LocalOwnerTraceTest.prepare(id);
        var forms=CanonicalRepairTraces.fast(p.get(0),p.get(1)).comparisonForms(true);
        var clone=method(EGraphNode.class,"fastComparisonMatrix",EGraphNode.class);
        var domain=(EGraphNode)clone.invoke(null,intersection(forms));
        var normalize=method(NormalForm.class,"declarationDomainNormalization",EGraphNode.class,Map.class);
        check(normalize.invoke(null,domain,Map.of())==null,"same-static-type siblings have no containment proof");negatives++;
        var candidate=domain.getChildren().get(0);var carrier=domain.getChildren().get(1);
        var symbol=signature(carrier).parserUnaryCarrier();
        set(carrier,"sourceName",symbol.getName());set(carrier,"semanticIdentity","alloy/signature/"+symbol.getName());
        set(carrier,"parserSignatureEvidence",symbol);set(carrier,"exactAlloyType",symbol.parserExactType());
        check(normalize.invoke(null,domain,Map.of())!=null,"same parser carrier issues a normalized domain");
        set(candidate,"temporalSnapshotBinding",true);
        check(normalize.invoke(null,domain,Map.of())==null,"old snapshot cannot use current mutable carrier");negatives++;
        set(candidate,"temporalSnapshotBinding",false);
        var other=LocalOwnerTraceTest.prepare(id);
        var foreign=signature(intersection(CanonicalRepairTraces.fast(other.get(0),other.get(1)).comparisonForms(true))
                .getChildren().get(0)).parserUnaryCarrier();
        set(carrier,"parserSignatureEvidence",foreign);set(carrier,"exactAlloyType",foreign.parserExactType());
        check(normalize.invoke(null,domain,Map.of())==null,"foreign parser module cannot issue containment");negatives++;
    }
    public static void main(String[] args)throws Exception {
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        for(Object value:manifest.getJSONArray("cases"))pair(((JSONObject)value).getJSONObject("identity"),out,rows);
        for(var domain:List.of("Small & Carrier","Carrier & Middle & Small")) {
            String id="variant-"+rows.length();Path source=out.resolve(id+".als");
            Files.writeString(source,"""
                    var sig Carrier {}
                    var sig Middle in Carrier {}
                    var sig Small in Middle {}
                    pred left { always (all item: %s | always item in Small) }
                    pred right { eventually some item: Carrier | always item in Small }
                    """.formatted(domain),StandardOpenOption.CREATE_NEW);
            pair(identity(source,id),out,rows);
        }
        issuanceControls(out);
        check(normalizedGuards>=6,"natural and renamed/reordered/n-ary normalized source guards exercised");
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives)
                .put("normalizedGuards",normalizedGuards).put("rows",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("NormalizedDeclarationDomainTest: "+checks+" checks, "+negatives+" negatives");
    }
}

package is.fivefivefive.CanDis.metric;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.*;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import is.fivefivefive.CanDis.metric.RepairView.*;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** Source-backed selected temporal payment, not a temporal equivalence solver. */
public final class SelectedTemporalPaymentTest {
    private static int checks;
    private static final OrderedTreeEditDistance.Adapter<TemporalNode> ADAPTER =
            new OrderedTreeEditDistance.Adapter<>() {
                public String label(TemporalNode n) { return n.label(); }
                public List<TemporalNode> children(TemporalNode n) { return n.children(); }
            };
    private static void check(boolean value,String message) {
        checks++;if(!value)throw new AssertionError(message);
    }
    private static void reject(Runnable action,String message) {
        checks++;try {action.run();} catch(IllegalArgumentException|IllegalStateException expected) {return;}
        throw new AssertionError("Accepted "+message);
    }
    private static String hash(String text)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void write(Path path,JSONObject value)throws Exception {
        Files.writeString(path,value.toString(),StandardOpenOption.CREATE_NEW);
    }
    private static List<Canonical.Prepared> prepare(JSONObject identity)throws Exception {
        String source=Files.readString(Path.of(identity.getString("source")));
        check(hash(source).equals(identity.getString("sha256")),"source identity");
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,source);
        var result=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(identity.getString("left"),identity.getString("right"))) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            result.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        return result;
    }
    private static JSONObject identity(String id,Path path,String a,String b)throws Exception {
        return new JSONObject().put("id",id).put("source",path.toAbsolutePath().toString())
                .put("sha256",hash(Files.readString(path))).put("left",a).put("right",b);
    }
    private static List<Node> refs(Node root) {
        var result=new ArrayList<Node>();var pending=new ArrayDeque<Node>();if(root!=null)pending.push(root);
        var seen=Collections.newSetFromMap(new IdentityHashMap<Node,Boolean>());
        while(!pending.isEmpty()) {var n=pending.pop();if(!seen.add(n))continue;
            if(n.isTemporalReference())result.add(n);pending.addAll(n.children());pending.addAll(n.certifiedAlternatives());}
        return result;
    }
    private static void checkedLinks(RepairView a,RepairView b,NativeRepairTrace trace) {
        var selection=OrderedTreeEditDistance.alignment(a.temporalRoot(),b.temporalRoot(),ADAPTER);
        var links=SelectedTemporalPayments.from(a,b,selection);
        var receipt=trace.json();var temporal=receipt.getJSONObject("nativeTemporalProgram");
        check(links.json().similar(receipt.getJSONObject("selectedTemporalReferencePayments")),"deterministic selected temporal decision");
        links.requireReceipt(temporal,links.json());
        reject(()->SelectedTemporalPayments.from(a,b,new OrderedTreeEditDistance.Alignment(selection.cost()+1,selection.matches())),"incorrect temporal payment cost");
        var reverse=new ArrayList<>(selection.matches());Collections.reverse(reverse);
        if(reverse.size()>1)reject(()->SelectedTemporalPayments.from(a,b,new OrderedTreeEditDistance.Alignment(selection.cost(),reverse)),"reordered occurrence map");
        for(String mode:List.of("owner","type","source","branch","map","cost")) {
            var bad=links.json();var endpoint=bad.getJSONArray("source").getJSONObject(0);
            switch(mode) {
                case "owner" -> endpoint.put("ownerPostorder",999);
                case "type" -> {var typed=endpoint.getJSONArray("typedBranches").getJSONObject(0);
                    typed.put("kind",typed.getString("kind").equals("NONE")?"AFTER":"NONE");}
                case "source" -> endpoint.getJSONArray("typedBranches").getJSONObject(0).put("sourcePath","foreign/phase");
                case "branch" -> endpoint.put("branches",new JSONArray().put(999));
                case "map" -> bad.put("selectedCorrespondence",new JSONArray());
                case "cost" -> bad.put("temporalCost",999);
            }
            reject(()->links.requireReceipt(temporal,bad),"typed payment "+mode);
        }
        var badTemporal=new JSONObject(temporal.toString());badTemporal.put("selectedCorrespondence",new JSONArray());
        reject(()->links.requireReceipt(badTemporal,links.json()),"different selected temporal program");
        var foreignPayment=SelectedTemporalPayments.from(b,b,
                OrderedTreeEditDistance.alignment(b.temporalRoot(),b.temporalRoot(),ADAPTER));
        for(int phase=0;phase<a.phases().size();phase++) {
            final int p=phase;
            for(var node:refs(a.phases().get(phase).matrix())) {
                var evidence=(JSONObject)links.referenceEvidence(phase,node);
                check(evidence.getString("status").equals("BOUND_ACTUAL_OBJECTS"),"actual source object retained");
                var origin=node.referenceOrigin();
                check(origin.reference()==node.sourceOccurrence(),"projected REF retains actual source object");
                check(evidence.getInt("paymentObject")==links.json().getInt("captureObject"),"actual selected payment identity");
                reject(()->links.referenceEvidence(p,node.withObservation(node.observationType(),
                        node.observationPhases(),node.observationTemporalOperator())),"same-metadata foreign capture object");
                reject(()->foreignPayment.referenceEvidence(p,node),"wrong selected payment object");
                var foreignOwner=b.temporalRoot().sourceBranches().get(0);
                reject(()->foreignOwner.temporalReferenceOrigin(origin.reference()),"foreign original source owner");
            }
        }
        for(int phase=0;phase<Math.min(a.phases().size(),b.phases().size());phase++) {
            for(var x:refs(a.phases().get(phase).matrix()))for(var y:refs(b.phases().get(phase).matrix())) {
                // Copying the right spelling, type and ordinal does not copy source authority.
                var fake=x.withObservation(x.observationType(),x.observationPhases(),x.observationTemporalOperator());
                check(!links.pays(phase,fake,y),"same-metadata foreign matrix node rejected");
                check(!links.pays(phase,x,y.withObservation(GraphType.INT,y.observationPhases(),y.observationTemporalOperator())),"foreign reference type rejected");
            }
        }
        if(!a.temporalRoot().children().isEmpty()&&!b.temporalRoot().sourceBranches().isEmpty()) {
            var x=a.temporalRoot().children().get(0);
            reject(()->new TemporalNode(x.label(),x.children(),x.ownerPhase(),x.branchPhases(),
                    b.temporalRoot().sourceBranches().get(0),x.sourceBranches()),"same-kind/path foreign source owner");
            reject(()->new TemporalNode("FOREIGN",x.children(),x.ownerPhase(),x.branchPhases(),
                    x.sourceOwner(),x.sourceBranches()),"original phase enum versus forged temporal label");
            if(x.sourceBranches().size()>1) {
                var branches=new ArrayList<>(x.sourceBranches());Collections.reverse(branches);
                reject(()->new TemporalNode(x.label(),x.children(),x.ownerPhase(),x.branchPhases(),
                        x.sourceOwner(),branches),"binary left/right role substitution");
            }
        }
    }
    private static void corrupt(NativeRepairTrace trace) {
        for(String mode:List.of("cost","address","payload","order","construction","temporal","typed-link",
                "source-object","source-owner","source-payment")) {
            var bad=trace.json();var ops=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations");
            switch(mode) {
                case "cost" -> ops.getJSONObject(0).put("cost",999);
                case "address" -> ops.getJSONObject(0).put("source","foreign");
                case "payload" -> ops.getJSONObject(0).put("before",new JSONObject().put("head","foreign"));
                case "order" -> bad.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");
                case "construction" -> bad.put("constructionDerivations","foreign");
                case "temporal" -> bad.getJSONObject("nativeTemporalProgram").put("selectedCorrespondence",new JSONArray());
                case "typed-link" -> {if(!bad.has("selectedTemporalReferencePayments"))continue;
                    bad.getJSONObject("selectedTemporalReferencePayments").put("referenceTransports",new JSONArray().put("foreign"));}
                case "source-object","source-owner","source-payment" -> {
                    if(!bad.has("selectedTemporalReferencePayments"))continue;
                    boolean changed=false;
                    for(Object phaseValue:bad.getJSONArray("phases")) {
                        var phase=(JSONObject)phaseValue;
                        for(Object operationValue:phase.getJSONObject("program").getJSONArray("sourceObjects")) {
                            var operation=(JSONObject)operationValue;
                            if(operation.isNull("before"))continue;
                            var binding=operation.getJSONObject("before");
                            String key=mode.equals("source-object")?"matrixObject":mode.equals("source-owner")?"ownerObject":"paymentObject";
                            binding.put(key,999999);changed=true;break;
                        }
                        if(changed)break;
                    }
                    if(!changed)continue;
                }
            }
            reject(()->trace.replay(bad),"native "+mode);
        }
        check(trace.replay(trace.json())==trace.distance(),"immutable original trace survives all corruptions");
    }
    private static void sourceAuthorityJoins(NativeRepairTrace fast,NativeRepairTrace certified) {
        for(String side:List.of("before","after")) {
            var authorities=new HashSet<Long>();
            for(Object phaseValue:fast.json().getJSONArray("phases"))
                for(Object operationValue:((JSONObject)phaseValue).getJSONObject("program").getJSONArray("sourceObjects")) {
                    var operation=(JSONObject)operationValue;if(operation.isNull(side))continue;
                    var evidence=operation.getJSONObject(side);
                    long authority=evidence.optLong("temporalReferenceAuthority",-1);if(authority>0)authorities.add(authority);
                }
            for(Object phaseValue:certified.json().getJSONArray("phases"))
                for(Object operationValue:((JSONObject)phaseValue).getJSONObject("program").getJSONArray("sourceObjects")) {
                    var operation=(JSONObject)operationValue;if(operation.isNull(side))continue;
                    var binding=operation.getJSONObject(side);
                    check(binding.getString("status").equals("BOUND_ACTUAL_OBJECTS"),"selected REF is source-bound");
                    check(authorities.contains(binding.getLong("authorityId")),"Fast/certified actual source authority join");
                }
        }
    }
    private static JSONObject run(JSONObject identity,Path out,Integer expected,boolean paymentControls)throws Exception {
        var pair=prepare(identity);var rows=new JSONArray();
        for(int d=0;d<2;d++) {
            var a=pair.get(d);var b=pair.get(1-d);
            var fast=CanonicalRepairTraces.fast(a,b);
            check(fast.replay(fast.json())==fast.distance(),"Fast original selected path first");
            var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
            var cert=CanonicalRepairTraces.certified(ca,cb);
            check(cert.replay(cert.json())==cert.distance(),"certified selected path and construction premises");
            check(cert.json().getJSONObject("constructionDerivations").getBoolean("retained"),"retained construction distinct from repair");
            sourceAuthorityJoins(fast,cert);
            var comparison=LocalOwnerTraceTest.compareSelected(a,b,fast,cert);
            var row=new JSONObject().put("identity",identity).put("direction",d).put("fast",fast.json()).put("certified",cert.json())
                    .put("comparison",comparison.evidence());
            write(out.resolve(identity.getString("id").replace('/','_')+"-"+d+".json"),row);
            if(expected!=null)check(fast.distance()==expected&&cert.distance()==expected,"retained expected native cost "+identity.getString("id")+" "+fast.distance()+"/"+cert.distance());
            if(paymentControls) {
                checkedLinks(ca.repairView(),cb.repairView(),cert);corrupt(fast);corrupt(cert);
                check(fast.json().getJSONObject("nativeTemporalProgram").similar(cert.json().getJSONObject("nativeTemporalProgram")),"same actual paid temporal program and zero actions");
                var fi=CanonicalRepairTraces.fast(a,a);var ci=CanonicalRepairTraces.certified(ca,ca);
                check(fi.distance()==0&&ci.distance()==0&&fi.replay(fi.json())==0&&ci.replay(ci.json())==0,"original-source identity not excluded");
            } else check(comparison.status()==NativeOperationComparison.Status.MATCHED,"previously matched family retained "+identity.getString("id"));
            rows.put(new JSONObject().put("direction",d).put("fast",fast.distance()).put("certified",cert.distance()).put("comparison",comparison.status()));
        }
        return new JSONObject().put("identity",identity).put("rows",rows);
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]).toAbsolutePath();Files.createDirectories(out);var summary=new JSONArray();
        var fixtures=new LinkedHashMap<String,String>();
        fixtures.put("shift","var sig A {} pred a { always eventually some A } pred b { eventually some A }");
        fixtures.put("temporal","var sig A {} pred a {always some A} pred b {eventually (some A and after no A)}");
        fixtures.put("owner","var sig A {var r:set A} pred a {all x:A | always some x.r} pred b {all x:A | eventually some x.r}");
        fixtures.put("binary-role","var sig A {} pred a {(always some A) until (eventually no A)} pred b {(eventually no A) until (always some A)}");
        fixtures.put("deep-promotion","var sig A {} pred a {always after eventually some A} pred b {eventually some A}");
        fixtures.put("binary-promotion","var sig A {} pred a {always ((eventually some A) until (after no A))} pred b {(eventually some A) until (after no A)}");
        fixtures.put("reparent","var sig A {} pred a {(always eventually some A) until (after no A)} pred b {(eventually some A) until (always after no A)}");
        var expected=Map.of("shift",6,"temporal",9,"owner",1,"reparent",6,"deep-promotion",9,"binary-role",4);
        for(var entry:fixtures.entrySet()) {
            var path=out.resolve(entry.getKey()+".als");Files.writeString(path,entry.getValue(),StandardOpenOption.CREATE_NEW);
            summary.put(run(identity(entry.getKey(),path,"a","b"),out,expected.get(entry.getKey()),true));
        }
        if(args.length>1)for(Object value:new JSONObject(Files.readString(Path.of(args[1]))).getJSONArray("cases")) {
            var row=(JSONObject)value;
            if(!Set.of("original","dependent","identical","coursesOld/both/7XiqRnyXeoftBf2No_inv15",
                    "coursesNew/both/5vxYh7NCpEPMmC47p_inv14","coursesNew/under/dcxEP6ZgQAktuDbeT_inv10").contains(row.getString("id")))continue;
            summary.put(run(row,out,row.getString("id").equals("dependent")?40:null,false));
        }
        for(var spec:List.of(new String[]{"classroom_fol/both/4J8sS2Kr8KrhgsN6R_inv4","inv4","11"},new String[]{"trash_ltl/both/oivT9pBECZFsYCGWq_inv18","inv18","21"}))
            summary.put(run(identity(spec[0],Path.of("classified-data",spec[0]+".als"),spec[1],spec[1]+"c"),out,Integer.valueOf(spec[2]),true));
        write(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("cases",summary)
                .put("publicCertification","BLOCKED").put("queueCleared",false));
        System.out.println("SelectedTemporalPaymentTest: "+checks+" checks");
    }
}

package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Source-linked product-metric reconstruction, including the prior full finite family. */
public final class TemporalReferenceReconstructionTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++;if(!ok)throw new AssertionError(message); }
    private static void reject(Runnable run,String message) {
        checks++;try {run.run();}catch(IllegalArgumentException|IllegalStateException|JSONException expected){return;}
        throw new AssertionError("Accepted "+message);
    }
    private static void write(Path path,JSONObject data)throws Exception {
        Files.writeString(path,data.toString(),StandardOpenOption.CREATE_NEW);
    }
    private static String hash(String text)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static JSONObject identity(Path file,String id,String a,String b)throws Exception {
        return new JSONObject().put("source",file.toString()).put("sha256",hash(Files.readString(file)))
                .put("id",id).put("left",a).put("right",b);
    }
    private static void corruptReconstruction(NativeRepairTrace trace) {
        var links=trace.json().getJSONObject("selectedTemporalReferencePayments").getJSONArray("referenceTransports");
        for(int i=0;i<links.length();i++)if(links.getJSONObject(i).getString("kind").equals("selected-skeleton-reconstruction")) {
            for(String field:List.of("sourceOccurrence","targetOccurrence","sourceOwner","targetOwner","ownerRole","targetOwnerRole","sourceLocalSlot","targetLocalSlot","branchArity")) {
                var bad=trace.json();bad.getJSONObject("selectedTemporalReferencePayments").getJSONArray("referenceTransports").getJSONObject(i).put(field,999);
                reject(()->trace.replay(bad),"typed reconstruction "+field);
            }
            var removed=trace.json();removed.getJSONObject("selectedTemporalReferencePayments").getJSONArray("referenceTransports").remove(i);
            reject(()->trace.replay(removed),"missing reconstruction premise");
        }
        for(String field:List.of("selectedCorrespondence","operations","zeroCostReads")) {
            var bad=trace.json();bad.getJSONObject("nativeTemporalProgram").put(field,new JSONArray().put("foreign"));
            reject(()->trace.replay(bad),"whole selected skeleton "+field);
        }
        check(trace.replay(trace.json())==trace.distance(),"original reconstruction after corruption");
    }
    private static JSONObject declarationEndpoint(JSONObject atom,boolean certified,Canonical.Prepared source,RepairView view) {
        int phase,ordinal;
        if(certified) {
            var coordinate=new JSONArray(atom.getString("occurrence"));
            phase=coordinate.getInt(1);ordinal=coordinate.getInt(4);
            var bindings=view.phases().get(phase).bindings();
            var binding=bindings.stream().filter(b->b.ownerPhase()==phase&&b.coordinate()==coordinate.getInt(3)
                    &&b.ordinal()==ordinal&&b.ownerContext().equals(coordinate.getString(2))).findFirst().orElseThrow();
            check(!coordinate.getBoolean(0),"observed declaration is a source quantifier, not a parameter");
            var original=source.normalizedForms().get(phase).getMatrixQuantiVars().get(ordinal);
            check(original.getBindingPath().equals(binding.bindingPath()),"certified declaration owner resolves to original binding occurrence");
        } else {
            var parts=atom.getString("occurrence").split("/");
            check(parts.length==4&&parts[0].equals("phase")&&parts[2].equals("declaration"),"original declaration address grammar");
            phase=Integer.parseInt(parts[1]);ordinal=Integer.parseInt(parts[3]);
            var original=CanonicalDistance.diagnosticDeclarationAtoms(source.normalizedForms().get(phase)).get(ordinal);
            check(original.admission().equals(atom.getString("admission")),"Fast declaration original type/owner admission");
        }
        var form=source.normalizedForms().get(phase);var original=form.getMatrixQuantiVars().get(ordinal);
        var tuple=new JSONArray().put(original.getQuantifier().name()).put(original.getTypeName())
                .put(original.getCardinality().name()).put(Integer.toString(original.getDisjointnessClass()));
        check(tuple.similar(atom.getJSONArray("tuple")),"original declaration tuple and paid metric units");
        return new JSONObject().put("phasePath",form.getPhasePath()).put("bindingPath",original.getBindingPath())
                .put("ordinal",ordinal).put("tuple",tuple).put("exactType",original.getExactAlloyType().stableString());
    }
    private static JSONArray declarationActions(NativeRepairTrace trace,boolean certified,Canonical.Prepared source,Canonical.Prepared target,RepairView a,RepairView b) {
        var result=new JSONArray();
        // Empty receipt blocks are bookkeeping, not operations. Retain every
        // actual operation, including zero-cost reads and permutations.
        for(Object value:trace.json().getJSONArray("nativeDeclarationPrograms"))
            for(Object operation:((JSONObject)value).getJSONArray("operations")) {
                var op=new JSONObject(operation.toString());
                if(!op.isNull("source"))op.put("source",declarationEndpoint(op.getJSONObject("source"),certified,source,a));
                if(!op.isNull("target"))op.put("target",declarationEndpoint(op.getJSONObject("target"),certified,target,b));
                result.put(op);
            }
        return result;
    }
    private static void referenceOnlyDelta(JSONObject before,JSONObject after) {
        var a=before.getJSONArray("phases");var b=after.getJSONArray("phases");
        check(a.length()==b.length(),"same selected phase coverage for newly exposed gap");
        int changes=0;
        for(int p=0;p<a.length();p++) {
            var x=a.getJSONObject(p).getJSONObject("program").getJSONArray("operations");
            var y=b.getJSONObject(p).getJSONObject("program").getJSONArray("operations");
            check(x.length()==y.length(),"same selected operation coverage for newly exposed gap");
            for(int i=0;i<x.length();i++) {
                var old=x.getJSONObject(i);var now=y.getJSONObject(i);
                for(String field:List.of("source","target"))check(Objects.equals(old.get(field),now.get(field)),"same selected operation address");
                for(String side:List.of("before","after"))check(old.isNull(side)==now.isNull(side)
                        &&(old.isNull(side)||old.getJSONObject(side).getString("head").equals(now.getJSONObject(side).getString("head"))),"same selected typed endpoint head");
                if(old.getInt("cost")!=now.getInt("cost")) {
                    check(old.getInt("cost")==1&&now.getInt("cost")==0
                            &&new JSONArray(now.getJSONObject("before").getString("head")).getString(0).equals("REF")
                            &&new JSONArray(now.getJSONObject("after").getString("head")).getString(0).equals("REF"),"only checked reference reconstruction changes a charge");
                    changes++;
                }
            }
        }
        check(changes==1,"one reference charge exposes existing offsetting difference");
    }
    private static JSONObject gaps(JSONObject identity,Path out)throws Exception {
        var pair=LocalOwnerTraceTest.prepare(identity);var rows=new JSONArray();
        for(int d=0;d<2;d++) {
            var a=pair.get(d);var b=pair.get(1-d);
            var fast=CanonicalRepairTraces.fast(a,b);check(fast.replay(fast.json())==fast.distance(),"shorter actual Fast path first");
            var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
            var cert=CanonicalRepairTraces.certified(ca,cb);check(cert.replay(cert.json())==cert.distance(),"actual typed certified path");
            var comparison=BinaryPhaseEnumerationTest.actualAttempt(a,b,ca.repairView(),cb.repairView(),fast,cert);
            check(comparison.getBoolean("orderedActionsEqual"),"all original-coordinate matrix operations agree");
            var fastDeclarations=declarationActions(fast,false,a,b,ca.repairView(),cb.repairView());
            var certDeclarations=declarationActions(cert,true,a,b,ca.repairView(),cb.repairView());
            comparison.put("fastDeclarationActions",fastDeclarations).put("certifiedDeclarationActions",certDeclarations);
            check(fastDeclarations.similar(certDeclarations),"selected zero/paid declaration actions agree: "+fastDeclarations+" / "+certDeclarations);
            corruptReconstruction(cert);
            rows.put(new JSONObject().put("direction",d).put("fast",fast.json()).put("certified",cert.json()).put("selectedPathAttempt",comparison));
        }
        var result=new JSONObject().put("identity",identity).put("rows",rows);write(out.resolve(identity.getString("id")+"-reconstruction.json"),result);return result;
    }
    private static JSONObject reordered(JSONObject identity,Path out)throws Exception {
        var pair=LocalOwnerTraceTest.prepare(identity);var rows=new JSONArray();
        for(int d=0;d<2;d++) {
            var a=pair.get(d);var b=pair.get(1-d);var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
            check(ca.equivalentTo(cb),"original certified ACI observation retained");
            var cert=CanonicalRepairTraces.certified(ca,cb);check(cert.replay(cert.json())==0&&cert.distance()==0,"original certified ACI zero-distance expectation retained");
            var fast=CanonicalRepairTraces.fast(a,b);check(fast.replay(fast.json())==fast.distance(),"source-order Fast selected execution after shorter certified path");
            check(fast.distance()==0,"whole-phase ACI reindex agrees with retained certified zero");
            corruptReconstruction(cert);
            rows.put(new JSONObject().put("direction",d).put("fast",fast.json()).put("certified",cert.json())
                    .put("comparison",NativeOperationComparison.compare(a,b,fast,cert).evidence()));
        }
        var result=new JSONObject().put("identity",identity).put("rows",rows);write(out.resolve(identity.getString("id")+".json"),result);return result;
    }
    public static void main(String[] args)throws Exception {
        run(args);
    }
    public static void run(String[] args)throws Exception {
        Path out=Path.of(args[0]).toAbsolutePath();Files.createDirectories(out);
        var prior=new JSONObject(Files.readString(Path.of(args[1]).resolve("summary.json")));var cases=new JSONArray();var gaps=new JSONArray();var newlyExposed=new JSONArray();
        for(Object value:prior.getJSONArray("cases")) {
            var old=(JSONObject)value;var identity=old.getJSONObject("identity");var id=identity.getString("id");
            var current=BinaryPhaseEnumerationTest.run(identity,out,id.equals("binary-promotion"));
            boolean gap=false;
            for(int d=0;d<2;d++) {
                var before=old.getJSONArray("rows").getJSONObject(d);var after=current.getJSONArray("rows").getJSONObject(d);
                if(before.getInt("fast")!=after.getInt("fast")) {
                    String file=id.replace('/','_')+"-"+d+".json";
                    ownerOnlyDelta(new JSONObject(Files.readString(Path.of(args[1]).resolve(file))).getJSONObject("fast"),
                            new JSONObject(Files.readString(out.resolve(file))).getJSONObject("fast"));
                    check(after.getInt("fast")==after.getInt("certified"),"checked declaration-owner completion equals certified path");
                } else check(before.getInt("fast")==after.getInt("fast"),"unchanged original Fast cost "+id);
                if(before.getInt("fast")!=before.getInt("certified")) {
                    check(after.getInt("fast")==after.getInt("certified"),"previous directional scalar gap discharged "+id);
                    gap=true;
                } else if(before.getInt("certified")!=after.getInt("certified")) {
                    String file=id.replace('/','_')+"-"+d+".json";
                    var oldReceipt=new JSONObject(Files.readString(Path.of(args[1]).resolve(file)));
                    var newReceipt=new JSONObject(Files.readString(out.resolve(file)));
                    referenceOnlyDelta(oldReceipt.getJSONObject("certified"),newReceipt.getJSONObject("certified"));
                    newlyExposed.put(new JSONObject().put("identity",identity).put("direction",d).put("before",before).put("after",after)
                            .put("status",after.getInt("fast")==after.getInt("certified")?"CHECKED_ORIGINAL_OWNER_COMPLETION":"OPEN_OFFSETTING_ACTION_DIFFERENCE_EXPOSED").put("receipt",out.resolve(file).toString()));
                } else check(before.getInt("certified")==after.getInt("certified"),"prior certified cost preserved "+id);
                if(before.getString("crossPath").equals("MATCHED"))check(after.getString("crossPath").equals("MATCHED"),"prior checked comparison preserved "+id);
            }
            cases.put(current);if(gap)gaps.put(gaps(identity,out));
        }
        var order=out.resolve("source-order.als");
        Files.writeString(order,"var sig S { var r, s: set S }\n"
            +"pred a { (always (all x:S | no x.r)) and (eventually (some y:S | some y.r)) }\n"
            +"pred b { (eventually (some y:S | some y.r)) and (always (all x:S | no x.r)) }\n"
            +"pred c { (always (all x:S | no x.r)) and (eventually (some y:S | some y.r)) and (historically (all z:S | no z.s)) }\n"
            +"pred d { (historically (all z:S | no z.s)) and (always (all x:S | no x.r)) and (eventually (some y:S | some y.r)) }\n",
            StandardOpenOption.CREATE_NEW);
        var ordering=new JSONArray().put(reordered(identity(order,"source-order-two","a","b"),out))
                .put(reordered(identity(order,"source-order-three","c","d"),out));
        write(out.resolve("summary.json"),new JSONObject().put("checks",checks+BinaryPhaseEnumerationTest.checkCount()).put("cases",cases)
                .put("gapReconstructions",gaps).put("sourceOrderControls",ordering).put("newlyExposed",newlyExposed)
                .put("queueCleared",false).put("publicCertification","BLOCKED"));
        System.out.println("TemporalReferenceReconstructionTest: "+(checks+BinaryPhaseEnumerationTest.checkCount())+" checks");
    }

    private static void ownerOnlyDelta(JSONObject old,JSONObject now) {
        check(old.getJSONObject("nativeTemporalProgram").similar(now.getJSONObject("nativeTemporalProgram")),"owner repair keeps selected temporal program");
        check(old.getJSONArray("nativeDeclarationPrograms").similar(now.getJSONArray("nativeDeclarationPrograms")),"owner repair keeps selected declaration program");
        for(String side:List.of("sourceBindingContexts","targetBindingContexts"))
            check(old.getJSONArray(side).similar(now.getJSONArray(side)),"owner source contexts unchanged");
        int changes=0;
        check(old.getJSONArray("phases").length()==now.getJSONArray("phases").length(),"owner repair phase coverage");
        for(int p=0;p<old.getJSONArray("phases").length();p++) {
            var a=old.getJSONArray("phases").getJSONObject(p).getJSONObject("program").getJSONArray("operations");
            var b=now.getJSONArray("phases").getJSONObject(p).getJSONObject("program").getJSONArray("operations");
            check(a.length()==b.length(),"owner repair same selected matrix coverage");
            for(int i=0;i<a.length();i++) {
                var x=a.getJSONObject(i);var y=b.getJSONObject(i);
                check(Objects.equals(x.get("source"),y.get("source"))&&Objects.equals(x.get("target"),y.get("target")),"owner repair same selected addresses");
                for(String side:List.of("before","after"))if(!x.isNull(side)) {
                    var hx=new JSONArray(x.getJSONObject(side).getString("head"));var hy=new JSONArray(y.getJSONObject(side).getString("head"));
                    for(int k=0;k<hx.length()-1;k++)check(Objects.equals(hx.get(k),hy.get(k)),"owner repair original typed head except selected mapping");
                }
                if(x.getInt("cost")!=y.getInt("cost")) {
                    check(x.getInt("cost")==1&&y.getInt("cost")==0
                            &&new JSONArray(x.getJSONObject("before").getString("head")).getString(0).equals("VARIABLE")
                            &&new JSONArray(x.getJSONObject("after").getString("head")).getString(0).equals("VARIABLE"),"only original binder map removes variable charge");changes++;
                }
            }
        }
        check(changes==1&&old.getInt("distance")==now.getInt("distance")+1,"one demonstrated owner charge corrected");
    }
}

package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.metric.RepairProjection;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;
import is.fivefivefive.CanDis.theory.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Selected-path regressions for the retained dependent-domain and temporal queue. */
public final class QueuedPatternReplayTest {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++; if(!value) throw new AssertionError(message);
    }
    private static void reject(Runnable action,String message) {
        checks++; try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { return; }
        throw new AssertionError("Accepted " + message);
    }
    private static void write(Path path,JSONObject value)throws Exception {
        Files.writeString(path,value.toString(),StandardOpenOption.CREATE_NEW);
    }
    private static String hash(String text)throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static JSONObject fixture(Path out,String id,String text)throws Exception {
        Path source=out.resolve(id+".als"); Files.writeString(source,text,StandardOpenOption.CREATE_NEW);
        return new JSONObject().put("id",id).put("source",source.toString()).put("sha256",hash(text)).put("left","a").put("right","b");
    }
    private static void nodes(EGraphNode node,Set<EGraphNode> result) {
        if(result.add(node)) for(var child:node.getChildren()) nodes(child,result);
    }
    private static void domains(EGraphNode node,Set<EGraphNode> result) {
        if(node.getOpcode()==EGraphNode.Opcode.GENERICRELDECL || node.getOpcode()==EGraphNode.Opcode.DISJ)
            nodes(node.getChildren().get(0),result);
        for(var child:node.getChildren()) domains(child,result);
    }
    private static void tamper(Object object,String field,Object value,Runnable assertion)throws Exception {
        var f=object.getClass().getDeclaredField(field); f.setAccessible(true); Object old=f.get(object);
        try {f.set(object,value); assertion.run();} finally {f.set(object,old);}
    }
    private static JSONObject domainProof(Canonical.Prepared source,Canonical.Prepared foreign)throws Exception {
        var evidence=TheoryAlloyAdapter.adapt(source.normalizedForms(),source.semanticProfile());
        RepairProjection.project(evidence,source.normalizedForms());
        reject(()->RepairProjection.project(evidence,foreign.normalizedForms()),"foreign source owner");
        var domainNodes=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        domains(source.normalizedForms().get(0).getMatrixEGraph(),domainNodes);
        var records=new JSONArray();
        for(var entry:evidence.dependentChainSourceBindings().entrySet()) {
            if(!domainNodes.contains(entry.getKey())) continue;
            var node=entry.getKey();var binding=entry.getValue();var cert=binding.certificate();
            CertificateVerifier.verify(cert);binding.requireMatches(node);
            check(binding.sourceOccurrenceCommitment().equals(cert.sourceOccurrenceCommitment()),"exact original-domain certificate endpoint");
            check(cert.source().context().size()==3,"ambient t plus earlier g,c; current s absent");
            check(cert.source().leaves().size()==3,"ordered c,Groups,g chain, no operand erasure");
            reject(()->binding.requireMatches(foreign.normalizedForms().get(0).getMatrixEGraph()),"foreign domain occurrence");
            tamper(node,"sourceOccurrenceLineage",-1L,()->reject(()->binding.requireMatches(node),"domain occurrence substitution"));
            tamper(node,"exactAlloyType",ExactAlloyType.boolType(),()->reject(()->binding.requireMatches(node),"domain result-type substitution"));
            var variable=node.getChildren().get(1);
            tamper(variable,"alphaName","foreign-owner-slot",()->reject(()->binding.requireMatches(node),"domain binding substitution"));
            binding.requireMatches(node);
            records.put(new JSONObject().put("path",binding.sourceOccurrencePath())
                    .put("lineage",node.getSourceOccurrenceLineage()).put("sourceCommitment",binding.sourceOccurrenceCommitment().toString())
                    .put("callerContext",cert.source().context().toString()).put("source",cert.source().structuralKey().toString())
                    .put("target",cert.target().structuralKey().toString()));
        }
        check(records.length()>0,"actual declaration-domain JOIN certificate retained");
        return new JSONObject().put("domainCertificates",records).put("sourceWireReplayChecked",false);
    }
    private static void corrupt(NativeRepairTrace trace) {
        for(String mode:List.of("cost","address","payload","premise","order","construction")) {
            JSONObject bad=trace.json();var op=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0);
            switch(mode) {
                case "cost" -> op.put("cost",99);
                case "address" -> op.put("source","foreign/source");
                case "payload" -> op.put("before",new JSONObject().put("head","foreign type/binder/phase"));
                case "premise" -> op.put("premise","foreign-construction");
                case "order" -> bad.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");
                case "construction" -> bad.put("constructionDerivations","foreign");
            }
            reject(()->trace.replay(bad),"selected trace " + mode);
        }
        check(trace.replay(trace.json())==trace.distance(),"trace immutable after corrupted copies");
    }
    private static JSONObject run(JSONObject row,List<Canonical.Prepared> pair,int direction,Path out)throws Exception {
        var a=pair.get(direction);var b=pair.get(1-direction);
        var fast=CanonicalRepairTraces.fast(a,b);check(fast.replay(fast.json())==fast.distance(),"shorter Fast original-endpoint execution first");
        var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
        check(certified.replay(certified.json())==certified.distance(),"certified original-endpoint execution");
        var comparison=NativeOperationComparison.compare(a,b,fast,certified);var e=comparison.evidence();
        var record=new JSONObject().put("identity",row).put("direction",direction).put("fast",fast.json()).put("certified",certified.json()).put("comparison",e);
        write(out.resolve(row.getString("id").replace('/','_')+"-"+direction+".json"),record);
        check(!e.getBoolean("complete")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"public alignment still blocked");
        if(row.getString("id").startsWith("temporal")) {
            int expected=row.getString("id").equals("temporal")?9:1;
            check(fast.distance()==expected&&certified.distance()==expected,"temporal operator charged once");
            check(fast.json().getJSONObject("nativeTemporalProgram").similar(certified.json().getJSONObject("nativeTemporalProgram")),"same temporal operation path and zero reads");
            for(var trace:List.of(fast,certified)) {
                corrupt(trace);
                var bad=trace.json();bad.getJSONObject("nativeTemporalProgram").getJSONArray("operations").getJSONObject(0).put("path",new JSONArray().put(99));
                reject(()->trace.replay(bad),"temporal owner/path corruption");
                check(trace.json().getInt("temporalDistance")>0,"operator update not erased");
            }
            check(comparison.status()==NativeOperationComparison.Status.UNCHECKABLE,"multiphase bridge remains an explicit queue obligation");
        } else {
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,row.getString("id")+" "+e);
            check(LocalOwnerTraceTest.crud(fast.json(),e,true).similar(LocalOwnerTraceTest.crud(certified.json(),e,false)),"all expanded ordered CRUD endpoints/costs equal");
            check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"paid and zero action equality");
            if(row.getString("id").equals("dependent")) {
                check(fast.distance()==40&&certified.distance()==40,"40/41 becomes40/40 by actual domain construction");
                corrupt(fast);corrupt(certified);
            }
        }
        return record;
    }
    private static void naturalTemporalControls(Path out)throws Exception {
        for(var spec:List.of(new String[]{"classroom_fol/both/4J8sS2Kr8KrhgsN6R_inv4","inv4","11"},new String[]{"trash_ltl/both/oivT9pBECZFsYCGWq_inv18","inv18","21"})) {
            var source=Path.of("classified-data",spec[0]+".als").toAbsolutePath();
            var row=new JSONObject().put("source",source.toString()).put("sha256",hash(Files.readString(source))).put("left",spec[1]).put("right",spec[1]+"c");
            var pair=LocalOwnerTraceTest.prepare(row);
            for(int d=0;d<2;d++) {
                var a=pair.get(d);var b=pair.get(1-d);var ft=CanonicalRepairTraces.fast(a,b);
                check(ft.replay(ft.json())==ft.distance(),"natural temporal Fast selected path");
                var ct=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                check(ct.replay(ct.json())==ct.distance(),"natural temporal certified selected path");
                write(out.resolve("control-"+spec[1]+"-"+d+".json"),new JSONObject().put("identity",row).put("direction",d).put("fast",ft.json()).put("certified",ct.json()));
                check(ft.distance()==Integer.parseInt(spec[2])&&ct.distance()==ft.distance(),"natural temporal prior components preserved "+spec[0]+" "+ft.distance()+"/"+ct.distance());
            }
        }
    }
    private static void dependentVariants(Path out)throws Exception {
        String text=Files.readString(Path.of("classified-data/classroom_fol/both/2vEm4FQHNpQECkJwp_inv12.als"));
        String dependent=text.replace("g:Group, c:Class, s:Student","g:Group, c:Class, s:Student & c.Groups.g");
        var variants=new LinkedHashMap<String,String>();
        variants.put("dependent-renamed",dependent.replace("g:Group, c:Class, s:Student & c.Groups.g","h:Group, k:Class, u:Student & k.Groups.h").replace("t->c in Teaches  implies c->s->g in Groups","t->k in Teaches  implies k->u->h in Groups"));
        variants.put("dependent-grouped",dependent.replace("g:Group, c:Class","g,h:Group, c:Class"));
        variants.put("dependent-difference",dependent.replace("Student & c.Groups.g","Student - c.Groups.g"));
        for(var variant:variants.entrySet()) {
            var source=out.resolve(variant.getKey()+".als");Files.writeString(source,variant.getValue(),StandardOpenOption.CREATE_NEW);
            var row=new JSONObject().put("id",variant.getKey()).put("source",source.toString()).put("sha256",hash(variant.getValue())).put("left","inv12").put("right","inv12c");
            var pair=LocalOwnerTraceTest.prepare(row);
            for(int d=0;d<2;d++) run(row,pair,d,out);
        }
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]).toAbsolutePath();Files.createDirectories(out);
        if(args.length>1&&args[1].equals("temporal-controls")) {
            naturalTemporalControls(out);
            write(out.resolve("summary.json"),new JSONObject().put("directChecks",checks).put("publicCertification","BLOCKED"));
            System.out.println("QueuedPatternReplayTest temporal controls: "+checks+" checks");return;
        }
        if(args.length>1&&args[1].equals("domain-variants")) {
            dependentVariants(out);
            write(out.resolve("summary.json"),new JSONObject().put("directChecks",checks).put("publicCertification","BLOCKED"));
            System.out.println("QueuedPatternReplayTest dependent variants: "+checks+" checks");return;
        }
        var family=args.length>1?new JSONObject(Files.readString(Path.of(args[1]))):LocalOwnerTraceTest.family(out.resolve("inputs"));
        var summary=new JSONArray();int index=0;
        for(Object value:family.getJSONArray("cases")) {
            var row=(JSONObject)value;var pair=LocalOwnerTraceTest.prepare(row);
            if(row.getString("id").equals("dependent")) write(out.resolve("domain-proof.json"),domainProof(pair.get(0),pair.get(1)));
            for(int d=0;d<2;d++) {
                var result=run(row,pair,d,out);
                if(args.length>2) {
                    var old=new JSONObject(Files.readString(Path.of(args[2]).resolve("row-"+index+"-"+d+".json")));
                    check(old.getJSONObject("identity").similar(row),"same frozen family source identity");
                    for(String arm:List.of("fast","certified")) {
                        if(row.getString("id").equals("dependent")&&arm.equals("certified")) {
                            check(old.getJSONObject(arm).getInt("distance")==41,"archived longer path retained");
                        } else LocalOwnerTraceTest.sameExecution(old.getJSONObject(arm),result.getJSONObject(arm));
                    }
                }
                summary.put(new JSONObject().put("id",row.getString("id")).put("direction",d).put("fast",result.getJSONObject("fast").getInt("distance")).put("certified",result.getJSONObject("certified").getInt("distance")).put("comparison",result.getJSONObject("comparison").getString("status")));
            }
            index++;
        }
        for(var spec:List.of(new String[]{"temporal","var sig A {} pred a {always some A} pred b {eventually (some A and after no A)}"},new String[]{"temporal-owner","var sig A {var r:set A} pred a {all x:A | always some x.r} pred b {all x:A | eventually some x.r}"})) {
            var row=fixture(out,spec[0],spec[1]);var pair=LocalOwnerTraceTest.prepare(row);
            for(int d=0;d<2;d++) {
                var result=run(row,pair,d,out);
                summary.put(new JSONObject().put("id",spec[0]).put("direction",d).put("fast",result.getJSONObject("fast").getInt("distance")).put("certified",result.getJSONObject("certified").getInt("distance")).put("comparison",result.getJSONObject("comparison").getString("status")));
                var a=pair.get(d);var ft=CanonicalRepairTraces.fast(a,a);var ct=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(a));
                check(ft.distance()==0&&ct.distance()==0&&ft.replay(ft.json())==0&&ct.replay(ct.json())==0,"source-identical temporal native execution");
            }
        }
        naturalTemporalControls(out);
        dependentVariants(out);
        write(out.resolve("summary.json"),new JSONObject().put("directChecks",checks).put("rows",summary).put("publicCertification","BLOCKED").put("queueCleared",false));
        System.out.println("QueuedPatternReplayTest: "+checks+" direct checks plus existing shared correspondence assertions");
    }
}

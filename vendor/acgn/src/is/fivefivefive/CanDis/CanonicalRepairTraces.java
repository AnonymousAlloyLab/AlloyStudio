package is.fivefivefive.CanDis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.metric.TraceResources;
import is.fivefivefive.CanDis.theory.CertificateVerifier;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.StructuralKey;
import is.fivefivefive.CanDis.theory.TypedEqualityCertificate;

/** Native between-endpoint traces; no cross-arm representation bridge is required. */
public final class CanonicalRepairTraces {
    private CanonicalRepairTraces() { }

    public static NativeRepairTrace fast(Canonical.Prepared left,Canonical.Prepared right) {
        return Canonical.evaluateWithTrace(left,right);
    }

    public static NativeRepairTrace certified(CanonicalAlloyPipeline.Prepared left,CanonicalAlloyPipeline.Prepared right) {
        var trace=QuotientRepairDistance.evaluateWithTrace(left.repairView(),right.repairView());
        try {
        // Compaction deliberately drops proof objects. Native edits remain executable;
        // the missing retained construction is reported, never called verified.
        if(!left.retainsSemanticArtifact()||!right.retainsSemanticArtifact())return trace.linkConstruction(
                new JSONObject().put("retained",false).put("extractionObligation","Construction artifacts were compacted before trace capture"),()->{});
        var a=left.semanticArtifact();var b=right.semanticArtifact();
        JSONObject links=new JSONObject().put("retained",true)
                .put("source",construction(a,left.repairView().producerObservationDigest(),left.repairView().phaseRepresentation("source")))
                .put("target",construction(b,right.repairView().producerObservationDigest(),right.repairView().phaseRepresentation("target")))
                .put("constructionIsNotRepair",true).put("wireReplayChecked",false);
        return trace.linkConstruction(links,()->{check(a);check(b);});
        } catch(RuntimeException failure) {throw NativeRepairTrace.captureFailure(trace.json(),failure);}
    }

    private static List<TypedEqualityCertificate> roots(CertifiedSemanticArtifact artifact) {
        var roots=new ArrayList<TypedEqualityCertificate>();
        roots.addAll(artifact.flatConstructions());roots.addAll(artifact.containerConstructions());
        roots.addAll(artifact.dependentChainConstructions());roots.addAll(artifact.guardedJoinConstructions());
        roots.addAll(artifact.binderOccurrenceCertificates());return List.copyOf(roots);
    }
    private static void check(CertifiedSemanticArtifact artifact) {
        for(var proof:roots(artifact))CertificateVerifier.verify(proof);
    }

    private static JSONObject construction(CertifiedSemanticArtifact artifact,String projectionDigest,JSONObject phaseRepresentation) {
        check(artifact);
        var resources=new TraceResources();
        var keys=new KeyTable(resources);
        var ids=new IdentityHashMap<TypedEqualityCertificate,Integer>();
        var pending=new ArrayDeque<TypedEqualityCertificate>();var rootIds=new JSONArray();
        for(var root:roots(artifact)) {
            if(!ids.containsKey(root)){ids.put(root,ids.size());pending.add(root);}
            rootIds.put(ids.get(root));
        }
        var proofs=new JSONArray();
        while(!pending.isEmpty()) {
            var proof=pending.removeFirst();resources.spend(128);var premises=new JSONArray();
            for(var premise:proof.premises()) {
                if(!ids.containsKey(premise)){ids.put(premise,ids.size());pending.add(premise);}
                premises.put(ids.get(premise));
            }
            proofs.put(new JSONObject().put("id",ids.get(proof)).put("category",proof.category())
                    .put("premises",premises).put("sourceEndpoint",keys.id(proof.leftEndpoint().structuralKey()))
                    .put("constructedEndpoint",keys.id(proof.rightEndpoint().structuralKey()))
                    .put("proof",keys.id(proof.structuralKey())));
        }
        int artifactRoot=keys.id(is.fivefivefive.CanDis.theory.TypedCertificateEndpoint.invocation(artifact.root()).structuralKey());
        var calls=new JSONArray();
        for(var call:artifact.callOccurrenceCertificates())calls.put(new JSONObject()
                .put("sourceOccurrence",call.occurrenceId()).put("sourcePath",call.sourcePath())
                .put("callee",call.qualifiedCallee()).put("authority",call.arityAuthority())
                .put("typedEndpoint",keys.id(call.sourceEndpoint().structuralKey())));
        var result=new JSONObject().put("artifactRoot",artifactRoot).put("projectionObservationDigest",projectionDigest)
                .put("proofRoots",rootIds).put("proofs",proofs).put("structuralKeys",keys.rows)
                .put("callPremises",calls).put("premisesCheckedInProcess",true)
                .put("linkAuthority","Same retained Prepared producer owns this artifact and sealed projection; not independent wire correspondence");
        if(phaseRepresentation!=null)result.put("projectionPhaseRepresentation",phaseRepresentation);
        return result;
    }

    /** Exact structural sharing, without recursively expanding equal proof subterms. */
    private static final class KeyTable {
        private record Visit(StructuralKey key,boolean finish) { }
        private record Shape(String tag,List<String> scalars,List<Integer> children) { }
        final IdentityHashMap<StructuralKey,Integer> ids=new IdentityHashMap<>();
        final java.util.Map<Shape,Integer> intern=new java.util.HashMap<>();
        final JSONArray rows=new JSONArray();
        final TraceResources resources;
        KeyTable(TraceResources resources){this.resources=resources;}
        int id(StructuralKey key) {
            var pending=new ArrayDeque<Visit>();pending.push(new Visit(key,false));
            while(!pending.isEmpty()) {
                var visit=pending.pop();var value=visit.key;if(ids.containsKey(value))continue;
                if(!visit.finish){resources.spend(32);pending.push(new Visit(value,true));
                    for(var child:value.children())pending.push(new Visit(child,false));continue;}
                var children=value.children().stream().map(ids::get).toList();
                var shape=new Shape(value.tag(),value.scalars(),children);Integer id=intern.get(shape);
                if(id==null){id=intern.size();intern.put(shape,id);resources.spend(128);
                    for(var scalar:value.scalars())resources.spend(Math.multiplyExact(2L,scalar.length()));
                    rows.put(new JSONObject().put("id",id).put("tag",value.tag()).put("scalars",new JSONArray(value.scalars()))
                            .put("children",new JSONArray(children)));}
                ids.put(value,id);
            }
            return ids.get(key);
        }
    }
}

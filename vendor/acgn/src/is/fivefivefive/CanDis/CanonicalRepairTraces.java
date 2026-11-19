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
import is.fivefivefive.CanDis.theory.StructuralKeyDag;
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
        return trace.linkConstruction(links,()->{check(a);check(b);},a,b);
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
                .put("structuralKeyWork",keys.accounting())
                .put("callPremises",calls).put("premisesCheckedInProcess",true)
                .put("linkAuthority","Same retained Prepared producer owns this artifact and sealed projection; not independent wire correspondence");
        if(phaseRepresentation!=null)result.put("projectionPhaseRepresentation",phaseRepresentation);
        return result;
    }

    /** Exact structural sharing, without recursively expanding equal proof subterms.
     * The run-owned cache is consulted before child expansion; a hit allocates no
     * row and performs no recursive walk. */
    private static final class KeyTable {
        final JSONArray rows;
        final TraceResources resources;
        private final StructuralKeyDag dag;
        KeyTable(TraceResources resources) {
            this.resources=resources;
            // Construction-key depth is bounded by the 64 MiB TraceResources
            // budget and the iterative traversal; the receipt JSON depth guard
            // remains separate in NativeOperationComparison/Result.
            this.dag=new StructuralKeyDag(Integer.MAX_VALUE,8_000_000,new StructuralKeyDag.Budget() {
                @Override public void reference(StructuralKey key,int depth){resources.spend(32);}
                @Override public void materialize(StructuralKey key){
                    resources.spend(128);
                    for(var scalar:key.scalars())resources.spend(Math.multiplyExact(2L,scalar.length()));
                }
            });
            this.rows=dag.rows();
        }
        int id(StructuralKey key) {return dag.intern(key,0);}
        JSONObject accounting() {return dag.accounting();}
    }
}

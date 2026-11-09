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
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** D3n2: distinct original path proofs survive a shared repaired JOIN node. */
public final class GuardedJoinOccurrenceReplayTest {
    private static int checks;
    private static final Path SOURCE = Path.of("classified-data/classroom_rl/both/D3n2EfPhBboewRm7z_inv14.als");
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static void reject(Runnable action, String why) {
        checks++; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private record Pair(Canonical.Prepared a, Canonical.Prepared b) { Pair reverse() { return new Pair(b,a); } }
    private static Pair prepare(String text, String left, String right) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var results = new ArrayList<Canonical.Prepared>();
        for (var name : List.of(left,right)) {
            var v = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            v.visit(new ModelUnit(null,module),null);
            results.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));
        }
        return new Pair(results.get(0),results.get(1));
    }
    private static EGraphNode original(Canonical.Prepared p, String path) {
        var parts = path.split("/");
        var node = p.normalizedForms().get(Integer.parseInt(parts[1])).getCertificationMatrixEGraph();
        for (int i=3; i<parts.length; i+=2) node = node.getChildren().get(Integer.parseInt(parts[i+1]));
        return node;
    }
    private static Map<String,String> mapping(JSONObject j) {
        Map<String,String> result = new TreeMap<>(); for (var key : j.keySet()) result.put(key,j.getString(key)); return result;
    }
    private static int[] mapping(JSONArray j) {
        int[] result = new int[j.length()]; for (int i=0; i<result.length; i++) result[i]=j.getInt(i); return result;
    }
    private static JSONObject copy(JSONObject j) { return new JSONObject(j.toString()); }
    private static JSONObject replay(Pair p) {
        var ca=CanonicalAlloyPipeline.prepare(p.a); var cb=CanonicalAlloyPipeline.prepare(p.b);
        var report=DistanceReplayDiagnostics.capture(p.a,p.b,ca,cb);
        // Replay the shorter arm first, using the recorded map and no search.
        var order = report.getJSONObject("fast").getInt("distance") < report.getJSONObject("certified").getInt("distance")
                ? List.of("fast","certified") : List.of("certified","fast");
        for (var arm : order) {
            var row=report.getJSONObject(arm); var phase=row.getJSONArray("phases").getJSONObject(0);
            var receipt=phase.getJSONObject("receipt"); int cost;
            if (arm.equals("certified")) {
                var m=mapping(phase.getJSONArray("alphaMapping"));
                cost=QuotientRepairDistance.verifyFixedMatrixReplay(ca.repairView(),cb.repairView(),m,m,receipt);
                var bad=copy(receipt).put("cost",receipt.getInt("cost")+1);
                reject(() -> QuotientRepairDistance.verifyFixedMatrixReplay(ca.repairView(),cb.repairView(),m,m,bad),"forged certified cost");
                if (m.length>0) { var corrupt=m.clone(); corrupt[0]=10000;
                    reject(() -> QuotientRepairDistance.verifyFixedMatrixReplay(ca.repairView(),cb.repairView(),corrupt,corrupt,receipt),"forged binding map"); }
            } else {
                var a=CanonicalDistance.prepare(p.a.normalizedForms()); var b=CanonicalDistance.prepare(p.b.normalizedForms());
                var m=mapping(phase.getJSONObject("alphaMapping"));
                cost=CanonicalDistance.verifyFixedMatrixReplay(a,b,m,m,receipt);
                var bad=copy(receipt).put("cost",receipt.getInt("cost")+1);
                reject(() -> CanonicalDistance.verifyFixedMatrixReplay(a,b,m,m,bad),"forged Fast cost");
            }
            check(cost==row.getInt("matrixDistance"),arm+" independent fixed-choice cost");
            check(row.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),arm+" paid declaration linkage");
            check(!row.getBoolean("complete"),"public authority remains incomplete");
        }
        var comparison=NativeOperationComparison.compare(p.a,p.b,ca,cb);
        check(comparison.status()==NativeOperationComparison.Status.MATCHED,"ordered native actions: "+comparison.evidence().opt("firstDifference"));
        check(comparison.bindScanCosts(new int[]{20,0,1,19},new int[]{20,0,1,19}).status()==NativeOperationComparison.Status.MATCHED,"exact scan costs linked");
        var evidence=comparison.evidence();
        check(evidence.getBoolean("sourceCertificatesChecked") && evidence.getBoolean("endpointCorrespondenceChecked")
                && evidence.getBoolean("fixedChoiceReplaysChecked"),"source-linked native comparison premises");
        check(evidence.getJSONArray("fastActions").similar(evidence.getJSONArray("certifiedActions")),"all ordered native actions including zero keeps");
        check(!evidence.getBoolean("complete") && !evidence.getBoolean("publicSequenceLinked")
                && !evidence.getBoolean("javaLeanCorrespondenceChecked"),"full public alignment stays blocked");
        var captured=copy(evidence.getJSONObject("diagnostic"));
        captured.getJSONArray("nativeOperationEndpoints").put(0,"foreign");
        check(NativeOperationComparison.compareCaptured(p.a,p.b,captured).status()==NativeOperationComparison.Status.INVALID_REPLAY,"foreign original endpoint commitment");
        captured=copy(evidence.getJSONObject("diagnostic"));
        captured.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1)
                .getJSONObject("receipt").getJSONArray("source").getJSONObject(0).put("admission","foreign domain/owner");
        check(NativeOperationComparison.compareCaptured(p.a,p.b,captured).status()==NativeOperationComparison.Status.INVALID_REPLAY,"forged source declaration owner/domain");
        System.out.println("native comparison: "+comparison.status()+" "+comparison.reason());
        return new JSONObject().put("diagnostic",report).put("comparison",comparison.evidence())
                .put("status",comparison.status()).put("reason",comparison.reason()).put("replayOrder",order);
    }
    public static void main(String[] args) throws Exception {
        Path out=args.length==0?Files.createTempDirectory("join-occurrence-"):Path.of(args[0]); Files.createDirectories(out);
        String text=Files.readString(SOURCE); var p=prepare(text,"inv14","inv14c");
        var adapted=TheoryAlloyAdapter.adapt(p.a.normalizedForms(),p.a.semanticProfile());
        adapted.requireRepairProjectionSources(p.a.normalizedForms()); var artifact=adapted.semanticArtifact();
        var proofs=artifact.guardedJoinConstructions(); check(proofs.size()==2,"two original JOIN constructions");
        var a=proofs.get(0); var b=proofs.get(1);
        check(a!=b && a.sourceOccurrenceLineage()==b.sourceOccurrenceLineage(),"distinct proofs share ancestry");
        check(!a.sourceOccurrenceCommitment().equals(b.sourceOccurrenceCommitment()) && !a.leftEndpoint().equals(b.leftEndpoint()),"distinct original source endpoints retained");
        check(a.source().structuralKey().equals(b.source().structuralKey()) && a.rightEndpoint().equals(b.rightEndpoint()),"complete typed/owner and realization agreement");
        for (var proof : proofs) {
            CertificateVerifier.verify(proof);
            proof.requireOriginalMatches(original(p.a,proof.sourceOccurrencePath()),proof.sourceOccurrencePath());
            reject(() -> proof.requireOriginalMatches(original(p.a,proof.sourceOccurrencePath()),"phase/99/matrix"),"foreign source path");
            check(proof.source().type().equals(proof.target().type()),"literal result type retained");
            check(proof.steps().isEmpty(),"unary middle remains unrotated");
            reject(() -> DependentChainTheory.requireSoundFlattening(DependentChainKind.JOIN,
                    proof.source().leaves().stream().map(t -> t.type().relationType()).toList()),"whole-chain unary guard retained");
        }
        reject(() -> a.requireOriginalMatches(original(p.a,b.sourceOccurrencePath()),b.sourceOccurrencePath()),"same-lineage cross-path substitution");
        var fibers=GuardedJoinSourceFiber.bind(artifact,p.a.normalizedForms());
        check(fibers.size()==1 && fibers.values().iterator().next().premises().equals(proofs),"shared repaired node retains both original proofs");
        for (var entry : fibers.entrySet()) entry.getValue().requireMatches(entry.getKey());
        var reversed=new ArrayList<>(proofs); Collections.reverse(reversed);
        check(GuardedJoinSourceFiber.bind(artifact.withGuardedJoinConstructions(reversed),p.a.normalizedForms()).values().iterator().next().target().structuralKey().equals(a.target().structuralKey()),"not arbitrary first-proof selection");
        reject(() -> artifact.withGuardedJoinConstructions(List.of(a)),"missing original source premise");
        reject(() -> artifact.withGuardedJoinConstructions(List.of(a,a)),"duplicate proof cannot replace second original");
        var foreign=prepare(text,"inv14","inv14c");
        reject(() -> GuardedJoinSourceFiber.bind(artifact,foreign.a.normalizedForms()),"same spelling foreign occurrence rejected");
        reject(() -> RepairProjection.project(adapted,foreign.a.normalizedForms()),"foreign original source ledger rejected");
        reject(() -> GuardedJoinSourceFiber.bind(artifact,List.of(p.a.normalizedForms().get(0),p.a.normalizedForms().get(0))),"same repaired node cannot be reused in another temporal phase");
        reject(() -> a.requireMatches(p.b.normalizedForms().get(0).getMatrixEGraph()),"foreign opcode/type/owner source");
        for (int i=0;i<2;i++) {
            var q=i==0?p:p.reverse(); var result=replay(q);
            Files.writeString(out.resolve("after-"+i+".json"),result.toString(2),StandardOpenOption.CREATE_NEW);
            System.out.println("D3n2 direction "+i+": Fast="+result.getJSONObject("diagnostic").getJSONObject("fast").getInt("distance")
                    +" certified="+result.getJSONObject("diagnostic").getJSONObject("certified").getInt("distance"));
            check(result.getJSONObject("diagnostic").getJSONObject("fast").getInt("distance")==20,"unchanged Fast cost20");
            check(result.getJSONObject("diagnostic").getJSONObject("certified").getInt("distance")==20,"certified path available at20");
        }
        var controls=new JSONArray();
        for (var row : List.of(new String[]{"classroom_fol","wdiLn4gC6q3bbYBad_inv3","inv3","7"},
                new String[]{"classroom_fol","JoNXaXfKNJzGh3BFa_inv12","inv12","22"},
                new String[]{"classroom_fol","SASEHD8jPo2HgsuoJ_inv14","inv14","27"},
                new String[]{"coursesOld","xJnitBpFv9K74WNvf_inv14","inv14","49"},
                new String[]{"classroom_fol","p3hJuKvQ3SpwKDZHa_inv12","inv12","26"})) {
            var pair=prepare(Files.readString(Path.of("classified-data",row[0],"both",row[1]+".als")),row[2],row[2]+"c");
            for(var q:List.of(pair,pair.reverse())) {
                var ca=CanonicalAlloyPipeline.prepare(q.a); var cb=CanonicalAlloyPipeline.prepare(q.b);
                check(Canonical.distance(q.a,q.b)==Integer.parseInt(row[3]) && CanonicalAlloyPipeline.distance(ca,cb)==Integer.parseInt(row[3]),"prior cost "+row[1]);
                if(row[1].startsWith("wdi")) check(NativeOperationComparison.compare(q.a,q.b,ca,cb).status()==NativeOperationComparison.Status.MATCHED,"wdi ordered actions retained");
            }
            controls.put(row[1]);
        }
        var partial=prepare(Files.readString(Path.of("docs/distance-parity-20261107/partial-join.als")),"lhs","rhs");
        for(var q:List.of(partial,partial.reverse())) check(Canonical.distance(q.a,q.b)==0 && CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(q.a),CanonicalAlloyPipeline.prepare(q.b))==0,"P03 rotations retained");
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("GuardedJoinOccurrenceReplayTest: "+checks+" checks passed");
    }
}

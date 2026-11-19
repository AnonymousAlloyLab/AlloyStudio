package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Actual source support, retained roots and selected native programs. */
public final class LivePhaseSupportTest {
    private static int checks,negatives;
    private static void check(boolean value,String why) {
        checks++;if(!value)throw new AssertionError(why);
    }
    private static void reject(Runnable action,String why) {
        checks++;negatives++;
        try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { return; }
        throw new AssertionError("Accepted "+why);
    }
    private static <T> Set<T> identities(Collection<T> values) {
        var result=Collections.newSetFromMap(new IdentityHashMap<T,Boolean>());
        result.addAll(values);return result;
    }
    @SuppressWarnings("unchecked")
    private static List<NormalForm> children(NormalForm form)throws Exception {
        var method=NormalForm.class.getDeclaredMethod("comparisonTemporalChildren");
        method.setAccessible(true);return (List<NormalForm>)method.invoke(form);
    }
    private static void support(Canonical.Prepared original,NativeRepairTrace fast,NativeRepairTrace cert,boolean side)throws Exception {
        var originals=original.normalizedForms();var forms=fast.comparisonForms(side);
        var sources=forms.stream().map(NormalForm::comparisonSource).toList();
        var projected=cert.projectedView(side).originalPhaseSources(originals);
        var join=PhaseIdentityJoin.live(originals,sources,projected);
        var live=identities(sources);
        check(live.size()==sources.size()&&live.equals(identities(projected)),"equal unique live source sets");
        for(var root:NormalForm.originalPhaseRoots(originals))check(live.contains(root),"every original root retained");
        for(int i=0;i<forms.size();i++) {
            check(projected.get(join.certified(i))==sources.get(i)&&join.fast(join.certified(i))==i,"true inverse maps on live support");
            check(originals.get(join.original(i,false))==sources.get(i),"original address preserved");
            for(var child:children(forms.get(i)))check(live.contains(child.comparisonSource()),"actual child closure");
            for(var q:forms.get(i).getInheritedQuantiVars()) {
                boolean owned=false;
                for(var source:live)for(var bindings:List.of(source.getParams(),source.getMatrixQuantiVars(),source.getLocalQuantiVars()))
                    owned|=bindings.stream().anyMatch(binding->binding==q);
                check(owned,"actual inherited owner retained");
            }
            for(var imported:forms.get(i).checkedPhaseLocalBindingImports())check(live.contains(imported.ownerPhase()),"actual local owner retained");
        }
        if(sources.size()<originals.size())reject(()->new PhaseIdentityJoin(originals,sources,projected),"old complete gate remains complete");
        reject(()->join.fast(projected.size()),"absent selected phase address");
        reject(()->join.certified(-1),"negative owner address");
        var missing=new ArrayList<>(sources);missing.remove(0);
        reject(()->PhaseIdentityJoin.live(originals,missing,projected),"unequal live source sets");
        var root=NormalForm.originalPhaseRoots(originals).get(0);
        var rootless=sources.stream().filter(source->source!=root).toList();
        reject(()->PhaseIdentityJoin.live(originals,rootless,rootless),"missing root on both sides");
        var duplicate=new ArrayList<>(sources);duplicate.add(sources.get(0));
        reject(()->PhaseIdentityJoin.live(originals,duplicate,projected),"duplicate live source");
    }
    private static void forestControls(List<Canonical.Prepared> pair)throws Exception {
        var forest=new ArrayList<NormalForm>();for(var prepared:pair)forest.addAll(prepared.normalizedForms());
        var roots=NormalForm.originalPhaseRoots(forest);
        check(roots.size()==2,"independent forest roots are both retained");
        var reversed=new ArrayList<>(forest);Collections.reverse(reversed);
        var join=PhaseIdentityJoin.live(forest,forest,reversed);
        for(int i=0;i<forest.size();i++)check(reversed.get(join.certified(i))==forest.get(i),"multiple-root identity correspondence");
        var method=NormalForm.class.getDeclaredMethod("fastComparisonViews",List.class);method.setAccessible(true);
        @SuppressWarnings("unchecked") var views=(List<NormalForm>)method.invoke(null,forest);
        var live=identities(views.stream().map(NormalForm::comparisonSource).toList());
        for(var root:roots)check(live.contains(root),"production projection preserves independent root");
        var root=roots.get(0);var child=root.getTemporalChildren().stream().findFirst();
        if(child.isPresent()) {
            var incomplete=new ArrayList<>(forest);incomplete.remove(child.get());
            reject(()->NormalForm.originalPhaseRoots(incomplete),"foreign or missing original child");
        }
        var field=NormalForm.class.getDeclaredField("temporalChildren");field.setAccessible(true);var old=field.get(root);
        field.set(root,List.of(root));
        try { reject(()->NormalForm.originalPhaseRoots(forest),"cyclic original source component"); }
        finally { field.set(root,old); }
        reject(()->PhaseIdentityJoin.live(pair.get(0).normalizedForms(),pair.get(1).normalizedForms(),pair.get(1).normalizedForms()),"foreign equal-looking source objects");
    }
    public static void main(String[] args)throws Exception {
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        for(Object raw:manifest.getJSONArray("cases")) {
            var identity=((JSONObject)raw).getJSONObject("identity");String id=identity.getString("id");
            if(!Set.of("event2058","event2060","event2066","event2069","event2071","event2073","event2075").contains(id))continue;
            long start=System.nanoTime();var pair=LocalOwnerTraceTest.prepare(identity);forestControls(pair);
            var inventories=pair.stream().map(p->List.copyOf(p.normalizedForms())).toList();
            for(int direction=0;direction<2;direction++) {
                var a=pair.get(direction);var b=pair.get(1-direction);var fast=CanonicalRepairTraces.fast(a,b);
                var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),new JSONObject());
                support(a,fast,cert,true);support(b,fast,cert,false);
                var result=NativeOperationComparison.compare(a,b,fast,cert);
                check(result.status()==NativeOperationComparison.Status.MATCHED||result.status()==NativeOperationComparison.Status.MISMATCH,
                        "actual checked correspondence "+id+" "+direction+" "+result.evidence());
                var report=result.evidence().getJSONObject("diagnostic");
                for(String arm:List.of("fast","certified"))for(String mode:List.of("cost","owner","phase","branch")) {
                    var bad=new JSONObject(report.toString());var value=bad.getJSONObject(arm);
                    switch(mode) {
                        case "cost" -> value.getJSONArray("orderedNativeOperations").getJSONObject(0).put("cost",999);
                        case "owner" -> value.put("sourceProducerObservationDigest","foreign-owner");
                        case "phase" -> value.getJSONArray("phases").getJSONObject(0).put("phase",999);
                        case "branch" -> value.getJSONObject("nativeTemporalProgram").put("selectedCorrespondence",new JSONArray());
                    }
                    check(NativeOperationComparison.compareCaptured(a,b,fast,cert,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"tampered "+arm+" "+mode);negatives++;
                }
                for(int i=0;i<2;i++)check(pair.get(i).normalizedForms().equals(inventories.get(i)),"original inventory retained");
                Files.writeString(out.resolve(id+"-"+direction+".json"),result.evidence().toString(),StandardOpenOption.CREATE_NEW);
                rows.put(new JSONObject().put("identity",identity).put("direction",direction).put("status",result.status()).put("fast",fast.distance()).put("certified",cert.distance()));
            }
            check(System.nanoTime()-start<60_000_000_000L,"60-second complete-file target");
        }
        check(rows.length()==14,"complete seven-pair family");
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("negatives",negatives).put("rows",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("LivePhaseSupportTest: "+checks+" checks, "+negatives+" negative controls");
    }
}

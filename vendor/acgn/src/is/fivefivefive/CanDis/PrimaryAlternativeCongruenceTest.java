package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.Node;

/** Actual primary-only temporal selections with unused alternative inventory. */
public final class PrimaryAlternativeCongruenceTest {
    static int checks,graphs,unused;
    static final StringBuilder lean=new StringBuilder("import SelectedPrimaryCongruence\nnamespace PrimaryGenerated\nopen SelectedPrimaryCongruence\n");
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    static String payload(Node n){return new JSONArray().put(n.operator()).put(n.semanticPayload()).put(n.payload())
        .put(n.bindingIndex()).put(n.lexicalVariable()).put(String.valueOf(n.observationType()))
        .put(n.observationPhases()).put(String.valueOf(n.observationTemporalOperator())).put(n.containerKind()).put(n.orderInsensitive()).toString();}
    static final class Graph {
        final IdentityHashMap<Node,Integer> ids=new IdentityHashMap<>();
        final List<Node> nodes=new ArrayList<>();
        int add(Node node){var old=ids.get(node);if(old!=null)return old;int i=nodes.size();ids.put(node,i);nodes.add(node);
            for(var c:node.children())add(c);for(var c:node.certifiedAlternatives())add(c);return i;}
        String indices(List<Node> ns){return ns.stream().map(n->ids.get(n).toString()).toList().toString();}
        void emit(){String name="g"+(graphs++);lean.append("def ").append(name).append("Nodes : List (Node String) := [\n");
            for(int i=0;i<nodes.size();i++){var n=nodes.get(i);if(i>0)lean.append(",\n");
                lean.append("  ⟨").append(JSONObject.quote(payload(n))).append(", ").append(indices(n.children()))
                    .append(", ").append(indices(n.certifiedAlternatives())).append("⟩");}
            lean.append("]\ndef ").append(name).append(" : Graph String := fun i => ").append(name).append("Nodes[i]?\n");
            lean.append("theorem ").append(name).append("_payloads : store (eraseAlternatives ").append(name)
                .append(") 0 = store ").append(name).append(" 0 := all_primary_payloads _ _\n");
            if(nodes.stream().anyMatch(n->!n.certifiedAlternatives().isEmpty())){
                unused++;lean.append("theorem ").append(name).append("_old : (").append(name)
                    .append("Nodes.all oldGate) = false := by decide\n");}
        }
    }
    static JSONArray selected(JSONObject nativeArm,boolean sourceSide,int phase){
        var paths=new JSONArray();
        var operations=nativeArm.getJSONArray("phases").getJSONObject(phase).getJSONObject("program").getJSONArray("operations");
        for(Object raw:operations){var op=(JSONObject)raw;String field=sourceSide?"source":"target";
            if(!op.isNull(field)){String path=op.getString(field);check(path.matches("root(?:/child/[0-9]+)*"),"actual selected address is primary: "+path);paths.put(path);}}
        return paths;
    }
    public static void main(String[] args)throws Exception {
        var input=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        boolean repaired=true;var rows=new JSONArray();
        for(Object raw:input.getJSONArray("cases")){
            var identity=((JSONObject)raw).getJSONObject("identity");String id=identity.getString("id");
            if(!Set.of("event1723","event1756","event1833").contains(id))continue;
            long start=System.nanoTime();var pair=LocalOwnerTraceTest.prepare(identity);
            for(int d=0;d<2;d++){
                var a=pair.get(d);var b=pair.get(1-d);var f=CanonicalRepairTraces.fast(a,b);
                var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                QueueProbe.orderedReplay(f.distance(),c.distance(),()->f.replay(f.json()),()->c.replay(c.json()),new JSONObject());
                var comparison=NativeOperationComparison.compare(a,b,f,c);var selected=new JSONArray();int before=unused;
                for(boolean side:new boolean[]{true,false}){
                    var view=c.projectedView(side);
                    for(int phase=0;phase<view.phases().size();phase++){
                        var graph=new Graph();graph.add(view.phases().get(phase).matrix());graph.emit();
                        selected.put(new JSONObject().put("sourceSide",side).put("phase",phase).put("addresses",selected(c.json(),side,phase)));
                    }
                }
                int naturalUnused=unused-before;
                // Unary port normalization can remove the old duplicate identity inventory.
                // Keep a source-owned inventory control independent of the optimizer's choice.
                for(boolean side:new boolean[]{true,false}) {
                    Node wrapper=SelectedIdentityAlternativeTest.unusedAlternativeControls(side?a:b,f,c,side);
                    check(!wrapper.certifiedAlternatives().isEmpty(),"old gate rejects explicit unused inventory");
                    var graph=new Graph();graph.add(wrapper);graph.emit();
                }
                if(repaired)check(comparison.status()!=NativeOperationComparison.Status.UNCHECKABLE&&comparison.status()!=NativeOperationComparison.Status.INVALID_REPLAY,"new primary-only correspondence must be checked");
                else check(comparison.status()==NativeOperationComparison.Status.UNCHECKABLE&&comparison.evidence().toString().contains("selected representative"),"exact old gate rejection");
                var bad=c.json();var ops=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations");
                var op=ops.getJSONObject(0);op.put("cost",op.getInt("cost")+1);boolean rejected=false;
                try{c.replay(bad);}catch(ReplayValidationException expected){rejected=true;}check(rejected,"tampered native cost rejected");
                c.replay(c.json());
                for(String key:List.of("source","target")) {
                    var altered=c.json();var first=altered.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0);
                    first.put(key,"root/child/99999");boolean refused=false;
                    try{c.replay(altered);}catch(ReplayValidationException expected){refused=true;}
                    check(refused,"unissued primary address rejected");
                }
                var row=new JSONObject().put("identity",identity).put("direction",d).put("status",comparison.status()).put("reason",comparison.reason())
                    .put("comparison",comparison.evidence()).put("selected",selected).put("fast",f.json()).put("certified",c.json())
                    .put("naturalUnusedAlternativeGraphs",naturalUnused).put("sourceOwnedSyntheticInventoryControls",2);
                Files.writeString(out.resolve(id+"-"+d+".json"),row.toString(),StandardOpenOption.CREATE_NEW);
                rows.put(new JSONObject().put("id",id).put("direction",d).put("status",comparison.status()));
            }
            check((System.nanoTime()-start)<60_000_000_000L,"60-second natural file target");
        }
        check(rows.length()==6,"all6 source-linked directions");
        for(Object raw:input.getJSONArray("cases")) {
            var identity=((JSONObject)raw).getJSONObject("identity");
            if(!Set.of("event1724","event1725").contains(identity.getString("id")))continue;
            var pair=LocalOwnerTraceTest.prepare(identity);
            for(int d=0;d<2;d++) {
                var a=pair.get(d);var b=pair.get(1-d);var f=CanonicalRepairTraces.fast(a,b);
                var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                QueueProbe.orderedReplay(f.distance(),c.distance(),()->f.replay(f.json()),()->c.replay(c.json()),new JSONObject());
                boolean alternative=false;
                for(Object phase:c.json().getJSONArray("phases")) for(Object rawOp:((JSONObject)phase).getJSONObject("program").getJSONArray("operations")) {
                    var op=(JSONObject)rawOp;
                    for(String field:List.of("source","target"))if(!op.isNull(field)&&op.getString(field).contains("/alternative/"))alternative=true;
                }
                var result=NativeOperationComparison.compare(a,b,f,c);
                check(result.status()==NativeOperationComparison.Status.MATCHED,"identity selected alternative must match");
                for(boolean side:new boolean[]{true,false})SelectedIdentityAlternativeTest.unusedAlternativeControls(side?a:b,f,c,side);
                Files.writeString(out.resolve(identity.getString("id")+"-"+d+"-selected-alternative.json"),new JSONObject().put("identity",identity).put("direction",d).put("status",result.status()).put("evidence",result.evidence()).put("nativeAlternativeSelected",alternative).put("sourceOwnedSyntheticInventoryControls",2).toString(),StandardOpenOption.CREATE_NEW);
            }
        }lean.append("end PrimaryGenerated\n");
        Files.writeString(out.resolve("PrimaryGenerated.lean"),lean.toString(),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("graphs",graphs).put("graphsWithUnusedAlternatives",unused).put("rows",rows).toString(2),StandardOpenOption.CREATE_NEW);
    }
}

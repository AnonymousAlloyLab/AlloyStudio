package is.fivefivefive.CanDis.metric;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.*;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;
import parser.ast.nodes.ModelUnit;

/** Direct derivation corruption checks, independent of NativeRepairTrace envelope checks. */
public final class TypedTemporalDerivationTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable test,String why){checks++;try{test.run();}catch(IllegalArgumentException|IllegalStateException|JSONException expected){return;}throw new AssertionError("Accepted "+why);}
    private static List<Canonical.Prepared> prepare(Path source)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(source));
        var pair=new ArrayList<Canonical.Prepared>();
        for(String name:List.of("a","b")) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            pair.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }return pair;
    }
    private static final OrderedTreeEditDistance.Adapter<TemporalNode> ADAPTER=new OrderedTreeEditDistance.Adapter<>() {
        public String label(TemporalNode n){return n.label();}
        public List<TemporalNode> children(TemporalNode n){return n.children();}
    };
    private static void extraControls(Path root,JSONArray rows)throws Exception {
        var fixtures=new LinkedHashMap<String,String>();
        fixtures.put("update","var sig A {} pred a {always some A} pred b {eventually some A}");
        fixtures.put("deep-wrap","var sig A {} pred a {eventually some A} pred b {always after eventually some A}");
        fixtures.put("repeated-label-roles","var sig A {} pred a {(eventually some A) until (eventually no A)} pred b {(eventually some A) until (eventually no A)}");
        for(var entry:fixtures.entrySet()) {
            Path file=root.resolve(entry.getKey()+".als");Files.writeString(file,entry.getValue(),StandardOpenOption.CREATE_NEW);
            var pair=prepare(file);
            for(int d=0;d<2;d++) {
                var a=CanonicalAlloyPipeline.prepare(pair.get(d)).repairView().temporalRoot();
                var b=CanonicalAlloyPipeline.prepare(pair.get(1-d)).repairView().temporalRoot();
                var selection=OrderedTreeEditDistance.alignment(a,b,ADAPTER);
                var program=TemporalReplay.capture(a,b,selection.cost(),TemporalNode::label,TemporalNode::children,selection);
                var derivation=TypedTemporalDerivation.capture(a,b,program);
                TypedTemporalDerivation.verify(a,b,program,derivation);checks++;
                rows.put(new JSONObject().put("id",entry.getKey()).put("direction",d).put("source",file.toString())
                        .put("program",program).put("typedDerivation",derivation));
                if(entry.getKey().equals("repeated-label-roles")) {
                    var until=b.children().get(0);check(until.children().size()==2,"two original distinct branches");
                    var reversed=new ArrayList<>(until.children());Collections.reverse(reversed);
                    var forgedUntil=new TemporalNode(until.label(),reversed,until.ownerPhase(),until.branchPhases(),until.sourceOwner(),until.sourceBranches());
                    var forged=new TemporalNode(b.label(),List.of(forgedUntil),b.ownerPhase(),b.branchPhases(),b.sourceOwner(),b.sourceBranches());
                    check(TemporalReplay.verify(a,forged,TemporalNode::label,TemporalNode::children,program)==0,
                            "label-only execution cannot see equal-label binary role swap");
                    reject(()->TypedTemporalDerivation.capture(a,forged,program),"actual equal-label role-swapped endpoint derivation");
                }
            }
        }
    }
    public static void main(String[] args)throws Exception {
        run(args);
    }
    public static void run(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        // Reuse the prior finite checks as helpers, not another registered main.
        TemporalReferenceReconstructionTest.run(new String[]{root.resolve("family").toString(),args[1]});
        var rows=new JSONArray();
        for(String id:List.of("binary-promotion","binary-releases","binary-since","binary-triggered","binary-local")) {
            Path source=Path.of(args[1],id+".als");var pair=prepare(source);
            for(int direction=0;direction<2;direction++) {
                var a=pair.get(direction);var b=pair.get(1-direction);
                var fast=CanonicalRepairTraces.fast(a,b);check(fast.replay(fast.json())==fast.distance(),"original Fast path first");
                var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
                var trace=CanonicalRepairTraces.certified(ca,cb);
                check(trace.replay(trace.json())==trace.distance(),"original selected certified execution");
                var program=trace.json().getJSONObject("nativeTemporalProgram");
                var derivation=TypedTemporalDerivation.capture(ca.repairView().temporalRoot(),cb.repairView().temporalRoot(),program);
                TypedTemporalDerivation.verify(ca.repairView().temporalRoot(),cb.repairView().temporalRoot(),program,derivation);checks++;
                check(derivation.similar(trace.json().getJSONObject("selectedTemporalReferencePayments").getJSONObject("typedDerivation")),"same numeric admission derivation");
                for(String field:List.of("origin","role","owner","operator","sourcePath","step","selectedEdit")) {
                    var bad=new JSONObject(derivation.toString());var line=bad.getJSONArray("lineage").getJSONObject(0);
                    switch(field) {
                        case "origin" -> line.put("sourceOccurrence",999);
                        case "role" -> line.put("role",999);
                        case "owner" -> line.put("ownerOccurrence",999);
                        case "operator" -> line.put("branchKinds",new JSONArray().put("NONE"));
                        case "sourcePath" -> line.put("targetSourcePaths",new JSONArray().put("foreign"));
                        case "step" -> bad.getJSONArray("operations").getJSONObject(0).getJSONArray("after").put(2,999);
                        case "selectedEdit" -> bad.getJSONArray("operations").getJSONObject(0).getJSONObject("selectedEdit").put("cost",999);
                    }
                    reject(()->TypedTemporalDerivation.verify(ca.repairView().temporalRoot(),cb.repairView().temporalRoot(),program,bad),"actual typed derivation "+field);
                }
                for(String field:List.of("cost","path","label","operations","selectedCorrespondence")) {
                    var bad=new JSONObject(program.toString());
                    switch(field){
                        case "cost" -> bad.getJSONArray("operations").getJSONObject(0).put("cost",0);
                        case "path" -> bad.getJSONArray("operations").getJSONObject(0).put("path",new JSONArray().put(999));
                        case "label" -> bad.getJSONArray("operations").getJSONObject(0).put("label","FOREIGN");
                        case "operations" -> bad.put("operations",new JSONArray());
                        case "selectedCorrespondence" -> bad.put("selectedCorrespondence",new JSONArray());
                    }
                    reject(()->TypedTemporalDerivation.capture(ca.repairView().temporalRoot(),cb.repairView().temporalRoot(),bad),"actual selected program "+field);
                }
                rows.put(new JSONObject().put("id",id).put("direction",direction).put("fast",fast.distance()).put("certified",trace.distance())
                        .put("program",program).put("typedDerivation",derivation));
            }
        }
        extraControls(root,rows);
        Files.writeString(root.resolve("typed-controls.json"),new JSONObject().put("checks",checks).put("rows",rows)
                .put("queueCleared",false).put("publicCertification","BLOCKED").toString(),StandardOpenOption.CREATE_NEW);
        System.out.println("TypedTemporalDerivationTest: "+checks+" direct checks plus retained family helper checks");
    }
}

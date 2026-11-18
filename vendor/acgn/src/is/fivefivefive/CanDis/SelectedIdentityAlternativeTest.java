package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.theory.GraphType;

/** Selected identity, unselected nonidentity, and corrupted source-evidence controls. */
public final class SelectedIdentityAlternativeTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Node at(Node node,String path) {
        String[] pieces=path.split("/");check(pieces[0].equals("root"),"rooted address");
        for(int i=1;i<pieces.length;i+=2)node=(pieces[i].equals("child")?node.children():node.certifiedAlternatives()).get(Integer.parseInt(pieces[i+1]));
        return node;
    }
    private static boolean same(Node a,Node b){return TemporalOperationCorrespondence.samePrimaryBody(a,b,new TraceResources());}
    private static Node changed(Node n,int field,Object value)throws Exception {
        Object[] args={n.operator(),n.payload(),n.semanticPayload(),n.lexicalVariable(),n.bindingIndex(),n.containerKind(),
            n.orderInsensitive(),n.children(),n.certifiedAlternatives(),n.observationType(),n.observationPhases(),
            n.observationTemporalOperator(),n.originalSourceOccurrence(),n.originalReferenceOrigin()};
        args[field]=value;
        Constructor<?> constructor=Arrays.stream(Node.class.getDeclaredConstructors()).filter(c->c.getParameterCount()==14).findFirst().orElseThrow();
        constructor.setAccessible(true);return (Node)constructor.newInstance(args);
    }
    private static Node changeRead(Node node)throws Exception {
        if(node.isVariable())return changed(node,3,"@local[999:999]");
        for(int i=0;i<node.children().size();i++) {
            Node altered=changeRead(node.children().get(i));
            if(altered!=null){var children=new ArrayList<>(node.children());children.set(i,altered);return changed(node,7,children);}
        }
        return null;
    }
    private static Node changeOrder(Node node)throws Exception {
        var children=new ArrayList<>(node.children());
        if(children.size()>1&&!same(children.get(0),children.get(1))) {
            Collections.swap(children,0,1);return changed(node,7,children);
        }
        for(int i=0;i<children.size();i++) {
            var altered=changeOrder(children.get(i));
            if(altered!=null){children.set(i,altered);return changed(node,7,children);}
        }
        return null;
    }
    private static Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception {
        var method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(target,args);}
        catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    private interface CheckedAction { void run()throws Exception; }
    private static void rejects(Class<? extends Exception> expected,String why,CheckedAction action)throws Exception {
        try{action.run();}catch(Exception e){check(expected.isInstance(e),why+": "+e);return;}
        throw new AssertionError(why);
    }
    private static JSONObject payload(Node node) {
        return new JSONObject().put("nativeEvidence",new JSONObject().put("semanticPayload",String.valueOf(node.semanticPayload()))
                .put("bindingIndex",node.bindingIndex()).put("observationType",String.valueOf(node.observationType())).toString());
    }
    @SuppressWarnings("unchecked")
    private static void nestedSelection(Canonical.Prepared original,NativeRepairTrace fast,NativeRepairTrace certified,
            boolean source,int phase,String path,Node primary)throws Exception {
        Class<?> endpointClass=Class.forName(TemporalOperationCorrespondence.class.getName()+"$Endpoint");
        var constructor=endpointClass.getDeclaredConstructors()[0];constructor.setAccessible(true);
        Object endpoint=constructor.newInstance(original,fast,certified,certified.json(),source?"source":"target");
        var joinField=endpointClass.getDeclaredField("phaseJoin");joinField.setAccessible(true);
        int fastPhase=((PhaseIdentityJoin)joinField.get(endpoint)).fast(phase);
        var nodesField=endpointClass.getDeclaredField("nodes");nodesField.setAccessible(true);
        var nodes=((List<Map<String,Object>>)nodesField.get(endpoint)).get(fastPhase);Object pair=nodes.get(path);
        // Only the synthetic alternative inventory differs; the authenticated primary body stays intact.
        Node inner=changed(primary,8,List.of()),nonidentity=changed(inner,2,"nonidentity-nested-body");
        Node outer=changed(primary,8,List.of(nonidentity,inner)),wrapper=changed(primary,8,List.of(outer));
        invoke(endpoint,"node",new Class<?>[]{is.fivefivefive.CanDis.core.EGraphNode.class,Node.class,int.class,String.class,Map.class},
                invoke(pair,"fast",new Class<?>[0]),wrapper,fastPhase,path,nodes);
        Class<?>[] selectionTypes={int.class,String.class,int.class},occurrenceTypes={int.class,String.class,JSONObject.class,boolean.class};
        String outerPath=path+"/alternative/0",innerPath=outerPath+"/alternative/1";
        JSONObject selected=(JSONObject)invoke(endpoint,"selectIdentity",selectionTypes,phase,path,0);
        check(selected.getString("primaryPath").equals(path),"outer canonical address");
        selected=(JSONObject)invoke(endpoint,"selectIdentity",selectionTypes,phase,outerPath,1);
        check(selected.getString("selectedPath").equals(innerPath)&&selected.getString("primaryPath").equals(path),"nested selected inventory and canonical address");
        Object selectedPair=nodes.get(innerPath);
        check(invoke(selectedPair,"certified",new Class<?>[0])==inner,"actual nested selected node authority retained");
        JSONObject actual=(JSONObject)invoke(endpoint,"occurrence",occurrenceTypes,phase,innerPath,payload(inner),true);
        JSONObject expected=(JSONObject)invoke(endpoint,"occurrence",occurrenceTypes,phase,path,payload(primary),true);
        check(actual.similar(expected)&&actual.getString("path").equals(path),"nested occurrence authenticates and normalizes to primary");
        rejects(TemporalOperationCorrespondence.Missing.class,"nonidentity selected body rejected by admission",
                ()->invoke(endpoint,"selectIdentity",selectionTypes,phase,outerPath,0));
        check(!nodes.containsKey(outerPath+"/alternative/0"),"rejected selection installs no alias");
        rejects(ReplayValidationException.class,"unissued nested index rejected",
                ()->invoke(endpoint,"selectIdentity",selectionTypes,phase,outerPath,2));
        rejects(TemporalOperationCorrespondence.Missing.class,"unissued selected address rejected",
                ()->invoke(endpoint,"occurrence",occurrenceTypes,phase,outerPath+"/alternative/0",payload(nonidentity),true));
        rejects(ReplayValidationException.class,"foreign selected payload rejected",
                ()->invoke(endpoint,"occurrence",occurrenceTypes,phase,innerPath,payload(nonidentity),true));
        rejects(ReplayValidationException.class,"foreign selected type rejected",
                ()->invoke(endpoint,"occurrence",occurrenceTypes,phase,innerPath,payload(changed(inner,9,GraphType.INT)),true));
    }
    static Node unusedAlternativeControls(Canonical.Prepared source,NativeRepairTrace fast,
            NativeRepairTrace certified,boolean left)throws Exception {
        Node primary=certified.projectedView(left).phases().get(0).matrix();
        Node foreign=changed(primary,2,"unselected-foreign-body");
        Node wrapper=changed(primary,8,List.of(foreign));
        check(same(primary,wrapper)&&!same(primary,foreign),"unused inventory preserves only the primary body");
        nestedSelection(source,fast,certified,left,0,"root",primary);
        return wrapper;
    }
    private static void retained(JSONObject evidence,String arm,int count) {
        var entries=new ArrayList<JSONObject>();
        for(Object block:evidence.getJSONArray(arm+"Blocks"))for(Object entry:((JSONObject)block).getJSONArray("entries"))entries.add((JSONObject)entry);
        for(Object entry:evidence.getJSONArray(arm+"IdentityRepresentationSteps"))entries.add((JSONObject)entry);
        var seen=new HashSet<Integer>();for(var entry:entries)check(seen.add(entry.getInt("nativeIndex")),"native entry retained exactly once");
        check(seen.size()==count,"complete native entry coverage");for(int i=0;i<count;i++)check(seen.contains(i),"native ordinal retained");
    }
    private record IdentityFixture(int phase,String path,Node primary) { }
    private static IdentityFixture identityFixture(Node node,int phase,String path)throws Exception {
        if(changeRead(node)!=null&&changeOrder(node)!=null)return new IdentityFixture(phase,path,node);
        for(int i=0;i<node.children().size();i++) {
            var found=identityFixture(node.children().get(i),phase,path+"/child/"+i);
            if(found!=null)return found;
        }
        return null;
    }
    private static void bodyControls(Node primary,Node body,Canonical.Prepared source)throws Exception {
        check(!same(primary,changed(body,9,GraphType.INT)),"wrong type rejected");
        check(!same(primary,changed(body,2,"foreign-semantic-head")),"wrong head rejected");
        check(!same(primary,changed(body,10,List.of(999))),"wrong phase evidence rejected");
        check(!same(primary,changed(body,12,source.normalizedForms().get(0).getMatrixEGraph())),"foreign source rejected");
        var changedRead=changeRead(body);check(changedRead!=null&&!same(primary,changedRead),"wrong lexical coordinate rejected");
        var reordered=changeOrder(body);check(reordered!=null&&!same(primary,reordered),"ordered children preserved");
    }
    private static void independentIdentityControls(Canonical.Prepared source,NativeRepairTrace fast,
            NativeRepairTrace certified,boolean left)throws Exception {
        var view=certified.projectedView(left);IdentityFixture fixture=null;
        for(int phase=0;phase<view.phases().size()&&fixture==null;phase++)
            fixture=identityFixture(view.phases().get(phase).matrix(),phase,"root");
        check(fixture!=null,"source-owned identity fixture contains lexical and ordered-child controls");
        var primary=fixture.primary();var body=changed(primary,8,List.of());
        check(same(primary,body),"inventory is not a primary typed-body change");
        bodyControls(primary,body,source);
        nestedSelection(source,fast,certified,left,fixture.phase(),fixture.path(),primary);
    }
    public static void main(String[] args)throws Exception {
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));Path out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        for(Object value:manifest.getJSONArray("cases")) {
            var identity=((JSONObject)value).getJSONObject("identity");long start=System.nanoTime();var pair=LocalOwnerTraceTest.prepare(identity);
            for(int direction=0;direction<2;direction++) {
                var a=pair.get(direction);var b=pair.get(1-direction);var fast=CanonicalRepairTraces.fast(a,b);
                var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),new JSONObject());
                var result=NativeOperationComparison.compare(a,b,fast,certified);
                Files.writeString(out.resolve(identity.getString("id")+"-"+direction+".json"),new JSONObject().put("identity",identity).put("direction",direction).put("result",result.evidence()).put("status",result.status()).toString(),StandardOpenOption.CREATE_NEW);
                check(result.status()==NativeOperationComparison.Status.MATCHED&&result.reason().equals("CHECKED_NATIVE_ACTIONS_EQUAL"),"identity selected alternative must match: "+result.reason());
                retained(result.evidence(),"fast",fast.json().getJSONArray("orderedNativeOperations").length());
                retained(result.evidence(),"certified",certified.json().getJSONArray("orderedNativeOperations").length());
                var identities=result.evidence().getJSONArray("certifiedIdentityRepresentationSteps");
                // A normalized primary may make a formerly selected identity alternative redundant.
                // Exercise the authenticated selection kernel independently of that incidental choice.
                independentIdentityControls(a,fast,certified,true);
                independentIdentityControls(b,fast,certified,false);
                int selections=0,stepCount=0;
                for(int phase=0;phase<certified.json().getJSONArray("phases").length();phase++) {
                    var steps=certified.json().getJSONArray("phases").getJSONObject(phase).getJSONObject("program").getJSONArray("representationSteps");
                    stepCount+=steps.length();
                    for(int stepIndex=0;stepIndex<steps.length();stepIndex++) {
                        var step=steps.getJSONObject(stepIndex);
                        for(boolean source:new boolean[]{true,false}) {
                            String key=source?"leftAlternative":"rightAlternative";int selected=step.getInt(key);if(selected<0)continue;selections++;
                            Node primary=at(certified.projectedView(source).phases().get(phase).matrix(),step.getString(source?"source":"target"));
                            Node body=primary.certifiedAlternatives().get(selected);check(same(primary,body),"actual selected body is typed source identity");
                            nestedSelection(source?a:b,fast,certified,source,phase,step.getString(source?"source":"target"),primary);
                            bodyControls(primary,body,source?a:b);
                            check(primary.certifiedAlternatives().stream().anyMatch(n->!same(primary,n)),"nonidentity alternative not mistaken for identity");
                            for(String mutation:List.of("index","cost","path")) {
                                var report=DistanceReplayDiagnostics.captureTraces(fast,certified);
                                var bad=report.getJSONObject("certified").getJSONArray("phases").getJSONObject(phase).getJSONObject("program").getJSONArray("representationSteps").getJSONObject(stepIndex);
                                if(mutation.equals("index"))bad.put(key,999);else if(mutation.equals("cost"))bad.put("cost",1);else bad.put(source?"source":"target","root/child/999");
                                check(NativeOperationComparison.compareCaptured(a,b,fast,certified,report).status()==NativeOperationComparison.Status.INVALID_REPLAY,"tampered selected "+mutation+" rejected");
                            }
                        }
                    }
                }
                check(identities.length()==stepCount,"every actually selected identity step retained");
                var corrupted=DistanceReplayDiagnostics.captureTraces(fast,certified);
                corrupted.getJSONObject("certified").put("distance",-1);
                check(NativeOperationComparison.compareCaptured(a,b,fast,certified,corrupted).status()==NativeOperationComparison.Status.INVALID_REPLAY,"tampered retained native cost rejected");
                rows.put(new JSONObject().put("identity",identity).put("direction",direction).put("status",result.status())
                        .put("nativeIdentitySelections",selections).put("sourceOwnedSyntheticSelectionFixtures",2));
            }
            check(System.nanoTime()-start<60_000_000_000L,"60-second per-file invariant");
        }
        check(rows.length()==2*manifest.getJSONArray("cases").length(),"both directions complete");
        var summary=new JSONObject().put("checks",checks).put("rows",rows);Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary.toString(2));
    }
}

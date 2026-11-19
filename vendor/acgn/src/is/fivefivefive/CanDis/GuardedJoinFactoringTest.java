package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;

/** Factoring is distinct from duplicate deletion and never rotates unary JOINs. */
public final class GuardedJoinFactoringTest {
    private static int checks, negatives;
    private record State(List<NormalForm> forms,CertifiedSemanticArtifact artifact,GuardedJoinSourceFiber.Binding binding) {
        GuardedJoinFactorEvidence evidence() { return binding.factors().get(0); }
        NormalForm form() { return forms.get(0); }
    }
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++;negatives++;try {action.run();}catch(IllegalArgumentException|IllegalStateException e){return;}
        throw new AssertionError("Accepted "+why);
    }
    private static Object helper(String name,Class<?>[] types,Object... args) throws Exception {
        var m=GuardedJoinDuplicateReplayTest.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);
    }
    private static void field(Object o,String name,Object value) {
        try {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static EGraphNode cloneNode(EGraphNode n) throws Exception {return (EGraphNode)helper("cloneNode",new Class<?>[]{EGraphNode.class},n);}
    private static EGraphNode bool(EGraphNode n,EGraphNode.Opcode op,List<EGraphNode> children) throws Exception {
        return (EGraphNode)helper("boolNode",new Class<?>[]{EGraphNode.class,EGraphNode.Opcode.class,List.class},n,op,children);
    }
    private static int joins(EGraphNode n) {return (n.getOpcode()==EGraphNode.Opcode.JOIN?1:0)+n.getChildren().stream().mapToInt(GuardedJoinFactoringTest::joins).sum();}
    @SuppressWarnings("unchecked")
    private static State state(boolean dual,int copies) throws Exception {
        var forms=(List<NormalForm>)helper("mutableSeed",new Class<?>[]{});var form=forms.get(0);
        var atoms=(List<EGraphNode>)helper("someAtoms",new Class<?>[]{EGraphNode.class},form.getCertificationMatrixEGraph());
        var guard=atoms.stream().filter(a->joins(a)==2).findFirst().orElseThrow();var other=atoms.stream().filter(a->a!=guard).toList();
        var inner=dual?EGraphNode.Opcode.OR:EGraphNode.Opcode.AND;var outer=dual?EGraphNode.Opcode.AND:EGraphNode.Opcode.OR;
        var left=new ArrayList<EGraphNode>();for(int i=0;i<copies;i++)left.add(cloneNode(guard));left.add(cloneNode(other.get(0)));
        var root=bool(guard,outer,List.of(bool(guard,inner,left),bool(guard,inner,List.of(cloneNode(guard),cloneNode(other.get(1))))));
        var reseed=EGraphNode.class.getDeclaredMethod("reseedSourceOccurrenceLineages");reseed.setAccessible(true);reseed.invoke(root);
        var source=cloneNode(root);root.saturate();source.freezeForCertification();root.freezeForCertification();
        field(form,"certificationMatrixEGraphRoot",source);field(form,"matrixEGraphRoot",root);form.freezeForCertification();
        check(root.getOpcode()==inner,"actual dual factoring applied");
        var construction=TheoryAlloyAdapter.adapt(forms,source.getSemanticProfile());
        var binding=GuardedJoinSourceFiber.bindWithEvidence(construction.semanticArtifact(),forms);
        check(binding.factors().size()==copies,"every absent common JOIN accounted as factoring");
        check(binding.erased().isEmpty()&&binding.duplicates().isEmpty(),"factoring not mislabeled");
        var accounted=new HashSet<GuardedJoinCertificate>();for(var f:binding.live().values())accounted.addAll(f.premises());
        for(var e:binding.factors()) {
            e.requireMatches(forms);accounted.add(e.removedPremise());
            check(e.removedPremise()!=e.retainedPremise()&&e.removedPremise().steps().isEmpty()&&e.retainedPremise().steps().isEmpty(),"separate original premises and unary barrier retained");
        }
        check(accounted.equals(new HashSet<>(construction.semanticArtifact().guardedJoinConstructions())),"exact construction accounting");
        return new State(forms,construction.semanticArtifact(),binding);
    }
    private static EGraphNode at(State s,String path) {
        var n=s.form().getCertificationMatrixEGraph();var parts=path.substring("phase/0/matrix".length()).split("/");
        for(int i=1;i<parts.length;i+=2)n=n.getChildren().get(Integer.parseInt(parts[i+1]));return n;
    }
    private static void corrupt(Consumer<State> edit,String why) throws Exception {
        var s=state(false,2);edit.accept(s);reject(()->s.evidence().requireMatches(s.forms()),why);
    }
    private static List<EGraphNode> nodes(EGraphNode n) {
        var result=new ArrayList<EGraphNode>();result.add(n);for(var c:n.getChildren())result.addAll(nodes(c));return result;
    }
    public static void main(String[] args) throws Exception {
        var out=Path.of(args[0]);Files.createDirectories(out);long start=System.nanoTime();
        for(boolean dual:List.of(false,true))for(int copies:List.of(1,2,4))state(dual,copies);
        var s=state(false,2);var foreign=state(false,2);
        reject(()->s.evidence().requireMatches(foreign.forms()),"foreign owner");
        reject(()->s.evidence().requireMatches(List.of()),"missing phase");
        helper("separateOwnerControl",new Class<?>[]{});check(true,"existing invalid mixed-owner deletion still rejected");
        corrupt(x->field(x.form(),"phasePath","foreign"),"phase path");
        corrupt(x->field(x.form(),"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal role");
        for(var name:List.of("sourcePath","lineage","transferContent"))corrupt(x->field(x.evidence().removedPremise(),name,name.equals("lineage")?-1L:"forged"),"removed certificate "+name);
        corrupt(x->field(at(x,x.evidence().removedPremise().sourceOccurrencePath()),"exactAlloyType",null),"source JOIN type");
        corrupt(x->field(x.form().getMatrixEGraph(),"sourceOccurrenceLineage",-1L),"factoring owner lineage");
        corrupt(x->{var n=nodes(x.form().getMatrixEGraph()).stream().filter(a->a.getSourceOccurrenceLineage()==x.evidence().retainedPremise().sourceOccurrenceLineage()).findFirst().orElseThrow();
            field(n,"sourceOccurrenceLineage",-1L);},"retained JOIN lineage");
        corrupt(x->{var n=at(x,x.evidence().removedPremise().sourceOccurrencePath());var children=new ArrayList<>(n.getChildClasses());Collections.reverse(children);field(n,"childClasses",children);},"ordered unary JOIN ports");
        corrupt(x->{var refs=nodes(x.form().getMatrixEGraph()).stream().flatMap(a->a.getChildClasses().stream()).filter(a->!a.getSlotMap().isEmpty()).toList();
            check(!refs.isEmpty(),"fixture has owned invocation");var map=new HashMap<>(refs.get(0).getSlotMap());map.replaceAll((k,v)->"foreign");field(refs.get(0),"slotMap",map);},"captured slot transport");
        corrupt(x->{var residual=nodes(x.form().getMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.OR).findFirst().orElseThrow();
            field(residual,"opcode",EGraphNode.Opcode.AND);},"different residual support operator");
        var identity=new JSONObject(Files.readString(Path.of(args[1])));var pair=LocalOwnerTraceTest.prepare(identity);var replays=new JSONArray();
        for(int dir=0;dir<2;dir++) {
            var a=pair.get(dir);var b=pair.get(1-dir);var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            int f=fast.json().getInt("distance"),c=certified.json().getInt("distance");
            for(var trace:f<c?List.of(fast,certified):List.of(certified,fast)) {
                check(trace.replay(trace.json())==trace.json().getInt("distance"),"actual selected replay");
                reject(()->trace.replay(new JSONObject(trace.json().toString()).put("distance",-1)),"tampered receipt cost");
            }
            var comparison=LocalOwnerTraceTest.compareSelected(a,b,fast,certified);
            check(comparison.status()!=NativeOperationComparison.Status.INVALID_REPLAY,"no invalid replay");
            replays.put(new JSONObject().put("direction",dir).put("fastDistance",f).put("certifiedDistance",c).put("status",comparison.status()).put("reason",comparison.reason()).put("fast",fast.json()).put("certified",certified.json()));
        }
        double seconds=(System.nanoTime()-start)/1e9;check(seconds<60,"bounded factoring controls under 60 seconds");
        Files.writeString(out.resolve("replays.json"),replays.toString(2),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives).put("seconds",seconds).put("scope","normalized-IR factoring and parsed prior path; not corpus clearance").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("GuardedJoinFactoringTest: "+checks+" checks, "+negatives+" negatives; "+seconds+"s");
    }
}

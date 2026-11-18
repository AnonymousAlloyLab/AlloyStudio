package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** Source-linked regression plus normalized-IR relational duplicate controls. */
public final class GuardedJoinRelationalDuplicateTest {
    private static int checks, negatives;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static void reject(Runnable r,String why) {
        checks++;negatives++;try { r.run(); } catch(IllegalArgumentException|IllegalStateException expected) { return; }
        throw new AssertionError("Accepted "+why);
    }
    private static void field(Object o,String name,Object value) {
        try { var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Object invoke(Class<?> c,String name,Class<?>[] types,Object o,Object... args) {
        try { var m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args); }
        catch(java.lang.reflect.InvocationTargetException e) {
            if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());
        } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static EGraphNode cloneNode(EGraphNode n) {
        return (EGraphNode)invoke(NormalForm.class,"cloneEGraph",new Class<?>[]{EGraphNode.class},null,n);
    }
    private static EGraphNode node(EGraphNode seed,EGraphNode.Opcode op,List<EGraphNode> children,boolean relation) {
        boolean variadic=relation||op==EGraphNode.Opcode.AND||op==EGraphNode.Opcode.OR;
        var n=(EGraphNode)invoke(EGraphNode.class,"inOwningArena",new Class<?>[]{EGraphNode.class,int.class,
            EGraphNode.Opcode.class,List.class,boolean.class,int.class,boolean.class,EGraphNode.Metatype.class,SemanticProfile.class},
            null,seed,0,op,children,variadic,variadic?-1:1,variadic,
            relation?EGraphNode.Metatype.SET:EGraphNode.Metatype.BOOLEAN,seed.getSemanticProfile());
        String prefix=relation?"BOPEXPR_":op==EGraphNode.Opcode.AND||op==EGraphNode.Opcode.OR?"BOP_":"UNOPF_";
        n.setSourceName(prefix+op);n.setSourceType("MIDDLENODE_"+prefix+op);
        n.setExactAlloyType(relation?seed.getExactAlloyType():ExactAlloyType.boolType());return n;
    }
    private static List<EGraphNode> nodes(EGraphNode root) {
        var result=new ArrayList<EGraphNode>();var pending=new ArrayDeque<EGraphNode>();pending.add(root);
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        while(!pending.isEmpty()){var n=pending.remove();if(seen.add(n)){result.add(n);pending.addAll(n.getChildren());}}return result;
    }
    private static EGraphNode at(NormalForm form,String path) {
        var n=form.getCertificationMatrixEGraph();var parts=path.substring("phase/0/matrix".length()).split("/");
        for(int i=1;i<parts.length;i+=2)n=n.getChildren().get(Integer.parseInt(parts[i+1]));return n;
    }
    private record State(List<NormalForm> forms,CertifiedSemanticArtifact artifact,GuardedJoinSourceFiber.Binding binding) {
        NormalForm form(){return forms.get(0);} GuardedJoinDuplicateEvidence duplicate(){return binding.duplicates().get(0);}
    }
    private static State bind(List<NormalForm> forms) {
        var f=forms.get(0);var artifact=TheoryAlloyAdapter.adapt(forms,f.getMatrixEGraph().getSemanticProfile()).semanticArtifact();
        var b=GuardedJoinSourceFiber.bindWithEvidence(artifact,forms);
        check(!b.duplicates().isEmpty(),"relational duplicate accounted");check(b.erased().isEmpty(),"not Boolean erasure");
        var accounted=new HashSet<GuardedJoinCertificate>();for(var fiber:b.live().values())accounted.addAll(fiber.premises());
        for(var d:b.duplicates()){d.requireMatches(forms);accounted.add(d.removedPremise());
            check(d.removedPremise()!=d.retainedPremise(),"original distinct certificates preserved");}
        check(accounted.equals(new HashSet<>(artifact.guardedJoinConstructions())),"every original proof accounted");return new State(forms,artifact,b);
    }
    private static State natural(JSONObject identity) throws Exception { return bind(LocalOwnerTraceTest.prepare(identity).get(0).normalizedForms()); }
    private static void corrupt(JSONObject identity,Consumer<State> edit,String why) throws Exception {
        var s=natural(identity);edit.accept(s);reject(()->s.duplicate().requireMatches(s.forms()),why);
    }
    private static State fixture(EGraphNode.Opcode op,boolean retainedOwner,boolean nested) throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,"sig A { r:set A, s:A->A } pred p {some ^(((r.A).r).s) and some r}");
        var v=new MASGVisitor(new GlobalVariables(),Set.of("p"),module);v.visit(new ModelUnit(null,module),null);
        var agent=new IRAgent(v.getForest().get(v.getForestId("p")));agent.computeNormalForm();var forms=agent.normalForms();var f=forms.get(0);
        var joins=nodes(f.getCertificationMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.JOIN).toList();
        var seed=joins.stream().filter(n->nodes(n).stream().filter(c->c.getOpcode()==EGraphNode.Opcode.JOIN).count()==3).findFirst().orElseThrow();
        // Parser-typed closure preserves a nonempty relative JOIN tail.
        var atom=nested?nodes(f.getCertificationMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.CLOSURE).findFirst().orElseThrow():seed;
        var children=new ArrayList<EGraphNode>(List.of(cloneNode(atom),cloneNode(atom)));
        if(retainedOwner)children.add(cloneNode(nodes(f.getCertificationMatrixEGraph()).stream()
                .filter(n->n.getOpcode()==EGraphNode.Opcode.GLOBALBINDING&&"r".equals(n.getSourceName())
                        &&seed.getExactAlloyType().equals(n.getExactAlloyType())).findFirst().orElseThrow()));
        var root=node(seed,EGraphNode.Opcode.LONE,List.of(node(seed,op,children,true)),false);
        invoke(EGraphNode.class,"reseedSourceOccurrenceLineages",new Class<?>[]{},root);var original=cloneNode(root);
        root.saturate();original.freezeForCertification();root.freezeForCertification();
        field(f,"certificationMatrixEGraphRoot",original);field(f,"matrixEGraphRoot",root);f.freezeForCertification();
        State s;
        try { s=bind(forms); } catch(RuntimeException error) {
            System.err.println("FIXTURE_FAILURE "+op+" retained="+retainedOwner+" nested="+nested);
            for(var tree:List.of(original,root)) {
                System.err.println(tree==original?"SOURCE":"LIVE");
                for(var n:nodes(tree))System.err.println(n.getSourceOccurrenceLineage()+" "+n.getOpcode()+" "+n.getExactAlloyType()+" "+n.getEClassRef().getSlotMap()+" children="+n.getChildren().stream().map(EGraphNode::getSourceOccurrenceLineage).toList());
            }
            throw error;
        }
        var d=s.duplicate();var owner=at(f,d.ancestorPath());
        check(nodes(root).stream().anyMatch(n->n.getSourceOccurrenceLineage()==owner.getSourceOccurrenceLineage())==retainedOwner,"expected owner survival");
        return s;
    }
    private static void heterogeneousBoolean() throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,"sig A { r:set A, s:A->A } sig B { t:set B } pred p {lone (^(((r.A).r).s) + t) and some r}");
        var v=new MASGVisitor(new GlobalVariables(),Set.of("p"),module);v.visit(new ModelUnit(null,module),null);
        var agent=new IRAgent(v.getForest().get(v.getForestId("p")));agent.computeNormalForm();var forms=agent.normalForms();var f=forms.get(0);
        var sourceNodes=nodes(f.getCertificationMatrixEGraph());var atom=sourceNodes.stream().filter(n->n.getOpcode()==EGraphNode.Opcode.LONE).findFirst().orElseThrow();
        var plus=nodes(atom).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.PLUS).findFirst().orElseThrow();
        check(plus.getChildren().stream().anyMatch(n->!plus.getExactAlloyType().equals(n.getExactAlloyType())),"parser-issued heterogeneous binary union");
        var other=sourceNodes.stream().filter(n->n.getOpcode()==EGraphNode.Opcode.SOME).findFirst().orElseThrow();
        var root=node(atom,EGraphNode.Opcode.AND,List.of(cloneNode(atom),cloneNode(atom),cloneNode(other)),false);
        invoke(EGraphNode.class,"reseedSourceOccurrenceLineages",new Class<?>[]{},root);var original=cloneNode(root);root.saturate();
        original.freezeForCertification();root.freezeForCertification();field(f,"certificationMatrixEGraphRoot",original);field(f,"matrixEGraphRoot",root);f.freezeForCertification();
        var s=bind(forms);check(s.binding().duplicates().size()==1,"old enclosing Boolean duplicate still selected");
        check(at(f,s.duplicate().ancestorPath()).getOpcode()==EGraphNode.Opcode.AND,"heterogeneous relational rule did not claim authority");
    }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[1]);Files.createDirectory(out);
        if(args[0].equals("--heterogeneous")){heterogeneousBoolean();Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("status","PASS").toString(2),StandardOpenOption.CREATE_NEW);return;}
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));
        var identity=manifest.getJSONArray("cases").getJSONObject(0).getJSONObject("identity");long start=System.nanoTime();
        var pair=LocalOwnerTraceTest.prepare(identity);var s=bind(pair.get(0).normalizedForms());var d=s.duplicate();
        var owner=at(s.form(),d.ancestorPath());
        check(!(Boolean)invoke(GuardedJoinDuplicateEvidence.class,"booleanOwner",new Class<?>[]{EGraphNode.class},null,owner),"exact old owner gate rejects");
        check(d.removedPremise().sourceOccurrencePath().equals(d.ancestorPath()+"/child/1"),"direct JOIN tail");
        check(d.removedPremise().steps().isEmpty()&&d.retainedPremise().steps().isEmpty(),"unary intermediate not rotated");
        var replays=new JSONArray();
        for(int dir=0;dir<2;dir++) {
            var a=pair.get(dir);var b=pair.get(1-dir);var fast=CanonicalRepairTraces.fast(a,b);
            var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            int fd=fast.json().getInt("distance"),cd=cert.json().getInt("distance");
            for(var trace:fd<cd?List.of(fast,cert):List.of(cert,fast)) {
                check(trace.replay(trace.json())==trace.json().getInt("distance"),"actual shorter-first replay");
                reject(()->trace.replay(new JSONObject(trace.json().toString()).put("distance",-1)),"tampered receipt cost");
            }
            var comparison=LocalOwnerTraceTest.compareSelected(a,b,fast,cert);check(comparison.status()==NativeOperationComparison.Status.MATCHED,"both paths matched: "+comparison.status()+" "+comparison.reason());
            replays.put(new JSONObject().put("direction",dir).put("fast",fast.json()).put("certified",cert.json()).put("comparison",comparison.evidence()));
        }
        Files.writeString(out.resolve("replays.json"),replays.toString(2),StandardOpenOption.CREATE_NEW);
        double seconds=(System.nanoTime()-start)/1e9;check(seconds<60,"source file below60s both paths/directions");
        var foreign=natural(identity);reject(()->d.requireMatches(foreign.forms()),"foreign same-shaped owner/arena");
        reject(()->d.requireMatches(List.of(pair.get(1).normalizedForms().get(0),s.form())),"wrong phase");
        reject(()->s.artifact().withGuardedJoinConstructions(List.of()),"missing original certificate");
        for(String key:List.of("lineage","sourcePath","transferContent"))corrupt(identity,x->field(x.duplicate().removedPremise(),key,key.equals("lineage")?-1L:"forged"),"proof "+key);
        corrupt(identity,x->field(x.form(),"temporalOp",NormalForm.TemporalOp.ALWAYS),"phase role");
        corrupt(identity,x->field(x.form(),"phasePath","foreign"),"phase path");
        corrupt(identity,x->field(at(x.form(),x.duplicate().ancestorPath()),"exactAlloyType",ExactAlloyType.boolType()),"owner type/integer confusion");
        corrupt(identity,x->field(at(x.form(),x.duplicate().ancestorPath()),"exactAlloyType",x.form().getMatrixQuantiVars().get(0).getExactAlloyType()),"issued homogeneous frame became heterogeneous");
        corrupt(identity,x->field(at(x.form(),x.duplicate().ancestorPath()),"exactAlloyType",at(x.form(),x.duplicate().removedPremise().sourceOccurrencePath()).getChildren().get(1).getExactAlloyType()),"owner/operand relation arity mismatch");
        corrupt(identity,x->field(at(x.form(),x.duplicate().ancestorPath()),"opcode",EGraphNode.Opcode.MINUS),"nonidempotent owner");
        corrupt(identity,x->field(at(x.form(),x.duplicate().ancestorPath()),"metatype",EGraphNode.Metatype.BOOLEAN),"nonrelational port policy");
        corrupt(identity,x->field(at(x.form(),x.duplicate().ancestorPath()),"semanticProfile",new SemanticProfile(4,SemanticProfile.OverflowMode.FORBID,"bad","bad","bad")),"unapproved profile");
        corrupt(identity,x->field(at(x.form(),x.duplicate().removedPremise().sourceOccurrencePath()),"exactAlloyType",null),"removed type");
        corrupt(identity,x->{var n=at(x.form(),x.duplicate().removedPremise().sourceOccurrencePath());var ports=new ArrayList<>(n.getChildClasses());Collections.reverse(ports);field(n,"childClasses",ports);},"ordered JOIN ports");
        corrupt(identity,x->{var n=at(x.form(),x.duplicate().ancestorPath());var ref=n.getChildClasses().get(1);field(ref,"slotMap",Map.of("_q0","foreign","_q1","_q1"));},"effective composed slots");
        corrupt(identity,x->{var n=at(x.form(),x.duplicate().removedPremise().sourceOccurrencePath());field(n,"sourceName","nonduplicate");},"nonsingleton support");
        corrupt(identity,x->{var n=nodes(x.form().getMatrixEGraph()).stream().filter(y->y.getSourceOccurrenceLineage()==x.duplicate().retainedPremise().sourceOccurrenceLineage()).findFirst().orElseThrow();field(n,"sourceOccurrenceLineage",-1L);},"missing original surviving lineage");
        corrupt(identity,x->field(x.form(),"matrixEGraphRoot",foreign.form().getMatrixEGraph()),"foreign live arena/content match");
        corrupt(identity,x->{
            var or=nodes(x.form().getMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.OR).findFirst().orElseThrow();
            field(or,"sourceOccurrenceLineage",x.duplicate().retainedPremise().sourceOccurrenceLineage());
            check(EGraphNode.sameSemanticInvocation(x.form().getCertificationMatrixEGraph(),x.form().getMatrixEGraph()),"ambiguous occurrence still has equal ACI content");
        },"ambiguous original surviving lineage despite equal ACI content");
        for(var op:List.of(EGraphNode.Opcode.PLUS,EGraphNode.Opcode.INTERSECT))for(boolean retained:List.of(false,true))for(boolean nested:List.of(false,true))fixture(op,retained,nested);
        heterogeneousBoolean();
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives).put("sourceFileSeconds",seconds).put("identity",identity).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("GuardedJoinRelationalDuplicateTest: "+checks+" checks, "+negatives+" negatives; file "+seconds+"s");
    }
}

package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** Duplicate Boolean guards retain both original JOIN proofs, without rotating unary ports. */
public final class GuardedJoinDuplicateReplayTest {
    private static final Path SOURCE=Path.of("classified-data/coursesOld/over/SQ7gyYaHhL5KJeXoM_inv13.als");
    private static final String SHA="8095cb096dc63fff972bc91ec32da4be1a00a87781ba1263590d19bf631988be";
    private static int checks, negatives;
    private record State(List<Canonical.Prepared> pair, TheoryAlloyAdapter.Result construction,
            GuardedJoinSourceFiber.Binding binding) {
        NormalForm form() { return pair.get(0).normalizedForms().get(0); }
        GuardedJoinDuplicateEvidence duplicate() { return binding.duplicates().get(0); }
    }
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++;negatives++;
        try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { return; }
        throw new AssertionError("Accepted "+why);
    }
    private static void field(Object object,String name,Object value) {
        try { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static List<Canonical.Prepared> prepare(String text) throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var result=new ArrayList<Canonical.Prepared>();
        for(var name:List.of("inv13","inv13c")) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            result.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        return result;
    }
    private static State state(String text) throws Exception {
        var pair=prepare(text);var p=pair.get(0);
        var construction=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());
        construction.requireRepairProjectionSources(p.normalizedForms());
        var binding=GuardedJoinSourceFiber.bindWithEvidence(construction.semanticArtifact(),p.normalizedForms());
        check(binding.duplicates().size()==1,"one accounted duplicate guard");
        check(binding.erased().isEmpty(),"duplicate not relabeled tautology");
        var d=binding.duplicates().get(0);d.requireMatches(p.normalizedForms());
        check(d.removedPremise()!=d.retainedPremise()
                && !d.removedPremise().sourceOccurrencePath().equals(d.retainedPremise().sourceOccurrencePath()),
                "distinct original occurrences retained");
        var accounted=new HashSet<GuardedJoinCertificate>();
        for(var fiber:binding.live().values())accounted.addAll(fiber.premises());
        for(var erased:binding.erased())accounted.add(erased.premise());
        for(var duplicate:binding.duplicates())accounted.add(duplicate.removedPremise());
        check(accounted.equals(new HashSet<>(construction.semanticArtifact().guardedJoinConstructions())),
                "every original JOIN construction accounted");
        check(d.removedPremise().steps().isEmpty() && d.retainedPremise().steps().isEmpty(),
                "3,1,1 unary barrier remains unrotated");
        return new State(pair,construction,binding);
    }
    private static EGraphNode original(State s,String path) {
        var node=s.form().getCertificationMatrixEGraph();
        var tail=path.substring("phase/0/matrix".length()).split("/");
        for(int i=1;i<tail.length;i+=2)node=node.getChildren().get(Integer.parseInt(tail[i+1]));
        return node;
    }
    private static EGraphNode live(State s,long lineage) {
        var pending=new ArrayDeque<EGraphNode>();pending.add(s.form().getMatrixEGraph());
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        while(!pending.isEmpty()) { var node=pending.remove();if(!seen.add(node))continue;
            if(node.getSourceOccurrenceLineage()==lineage)return node;pending.addAll(node.getChildren()); }
        throw new AssertionError("Missing live lineage");
    }
    private static void corrupt(String text,Consumer<State> edit,String why) throws Exception {
        var s=state(text);edit.accept(s);
        reject(()->s.duplicate().requireMatches(s.pair().get(0).normalizedForms()),why);
    }
    private static Object invoke(Class<?> type,String method,Class<?>[] types,Object receiver,Object... args) {
        try { var m=type.getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(receiver,args); }
        catch(java.lang.reflect.InvocationTargetException e) {
            if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());
        } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static EGraphNode cloneNode(EGraphNode source) {
        return (EGraphNode)invoke(NormalForm.class,"cloneEGraph",new Class<?>[]{EGraphNode.class},null,source);
    }
    private static EGraphNode boolNode(EGraphNode owner,EGraphNode.Opcode opcode,List<EGraphNode> children) {
        var n=(EGraphNode)invoke(EGraphNode.class,"inOwningArena",new Class<?>[]{EGraphNode.class,int.class,
                EGraphNode.Opcode.class,List.class,boolean.class,int.class,boolean.class,EGraphNode.Metatype.class,SemanticProfile.class},
                null,owner,0,opcode,children,true,-1,true,EGraphNode.Metatype.BOOLEAN,owner.getSemanticProfile());
        n.setSourceName("BOP_"+opcode);n.setSourceType("MIDDLENODE_BOP_"+opcode);
        n.setExactAlloyType(ExactAlloyType.boolType());return n;
    }
    private static List<EGraphNode> someAtoms(EGraphNode root) {
        var result=new ArrayList<EGraphNode>();var queue=new ArrayDeque<EGraphNode>();queue.add(root);
        while(!queue.isEmpty()) { var n=queue.remove();if(n.getOpcode()==EGraphNode.Opcode.SOME)result.add(n);queue.addAll(n.getChildren()); }
        return result;
    }
    private static int joins(EGraphNode node) {
        int result=node.getOpcode()==EGraphNode.Opcode.JOIN?1:0;
        for(var child:node.getChildren())result+=joins(child);return result;
    }
    private record Fixture(List<NormalForm> forms,CertifiedSemanticArtifact artifact,GuardedJoinSourceFiber.Binding binding) { }
    private static List<NormalForm> mutableSeed() throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A { r:set A } pred inv13 {all x:A | some ((r.A).r) and some r.x and some x.r}");
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of("inv13"),module);visitor.visit(new ModelUnit(null,module),null);
        var agent=new IRAgent(visitor.getForest().get(visitor.getForestId("inv13")));agent.computeNormalForm();
        return agent.normalForms();
    }
    // These are normalized-IR fixtures, not extra source-linked corpus witnesses.
    // The parser supplies typed atoms/slots; fresh occurrences are issued before
    // construction, then the ordinary ACI implementation supplies the repair tree.
    private static Fixture fixture(String operator,int copies,boolean separateOwners) throws Exception {
        var forms=mutableSeed();var form=forms.get(0);var atoms=someAtoms(form.getCertificationMatrixEGraph());
        check(atoms.size()==3,"typed fixture seed atoms");
        atoms.sort(Comparator.comparingInt(GuardedJoinDuplicateReplayTest::joins).reversed());
        check(joins(atoms.get(0))==2,"fixture targets the guarded unary-middle JOIN");
        var children=new ArrayList<EGraphNode>();for(int i=0;i<copies;i++)children.add(cloneNode(atoms.get(0)));
        children.add(cloneNode(atoms.get(1)));
        var opcode=operator.equals("and")?EGraphNode.Opcode.AND:EGraphNode.Opcode.OR;
        var root=boolNode(atoms.get(0),opcode,children);
        if(separateOwners)root=boolNode(root,EGraphNode.Opcode.OR,List.of(root,
                boolNode(root,EGraphNode.Opcode.AND,List.of(cloneNode(atoms.get(0)),cloneNode(atoms.get(2))))));
        invoke(EGraphNode.class,"reseedSourceOccurrenceLineages",new Class<?>[]{},root);
        var original=cloneNode(root);
        if(separateOwners) {
            // Invalid repair fixture: remove both copies only from the first
            // owner, leaving the identical JOIN in a different AND branch.
            root.getChildren().get(0).setChildren(List.of(cloneNode(atoms.get(1)),cloneNode(atoms.get(2))));
        } else root.saturate();
        original.freezeForCertification();root.freezeForCertification();
        field(form,"certificationMatrixEGraphRoot",original);field(form,"matrixEGraphRoot",root);
        form.freezeForCertification();
        var construction=TheoryAlloyAdapter.adapt(forms,original.getSemanticProfile());
        var binding=separateOwners?null:GuardedJoinSourceFiber.bindWithEvidence(construction.semanticArtifact(),forms);
        return new Fixture(forms,construction.semanticArtifact(),binding);
    }
    private static JSONObject booleanFamily(String operator,int copies) throws Exception {
        System.out.println("DUPLICATE_FIXTURE_BEGIN "+operator+" "+copies);
        long start=System.nanoTime();var s=fixture(operator,copies,false);var binding=s.binding();
        check(binding.duplicates().size()==copies-1,"all "+operator+" duplicates accounted: "+binding.duplicates().size());
        for(var d:binding.duplicates())check(d.ownerPhase()==0,"same Boolean phase");
        double seconds=(System.nanoTime()-start)/1e9;
        check(seconds<60,"wide "+operator+" file below 60 seconds");
        var result=new JSONObject().put("operator",operator).put("copies",copies).put("duplicates",binding.duplicates().size()).put("seconds",seconds);
        System.out.println("DUPLICATE_FIXTURE_END "+result);return result;
    }
    private static void separateOwnerControl() throws Exception {
        var s=fixture("and",2,true);var original=s.forms().get(0).getCertificationMatrixEGraph();
        var live=s.forms().get(0).getMatrixEGraph();
        for(int branch=0;branch<2;branch++)check(original.getChildren().get(branch).getOpcode()==EGraphNode.Opcode.AND
                && live.getChildren().get(branch).getOpcode()==EGraphNode.Opcode.AND
                && original.getChildren().get(branch).getExactAlloyType().equals(live.getChildren().get(branch).getExactAlloyType()),
                "separate owners retain their own typed opcode");
        var proofs=s.artifact().guardedJoinConstructions();
        check(proofs.size()==3 && proofs.stream().allMatch(p->p.source().structuralKey().equals(proofs.get(0).source().structuralKey())),
                "otherwise identical JOIN certificates on both sides of owner barrier");
        reject(()->GuardedJoinSourceFiber.bindWithEvidence(s.artifact(),s.forms()),"same-typed atom behind a distinct mixed-operator owner");
    }
    private static List<?> operands(EGraphNode root) {
        return (List<?>)invoke(GuardedJoinDuplicateEvidence.class,"operands",
                new Class<?>[]{EGraphNode.class,String.class,EGraphNode.Opcode.class},null,root,"fixture",EGraphNode.Opcode.AND);
    }
    private static void slotCompositionControl() throws Exception {
        var atom=someAtoms(mutableSeed().get(0).getCertificationMatrixEGraph()).stream()
                .filter(n->!n.getEClassRef().getSlotMap().isEmpty()).findFirst().orElseThrow();
        String slot=atom.getEClassRef().getSlotMap().keySet().iterator().next();
        var inner=boolNode(atom,EGraphNode.Opcode.AND,new ArrayList<>());
        inner.addChildInvocation(atom.getEClass().invoke(Map.of(slot,"middle")));
        var outer=boolNode(atom,EGraphNode.Opcode.AND,new ArrayList<>());
        outer.addChildInvocation(inner.getEClass().invoke(Map.of("middle",slot)));
        var rows=operands(outer);check(rows.size()==1,"nonidentity nested invocation flattened once");
        var row=rows.get(0);var slots=invoke(row.getClass(),"slots",new Class<?>[]{},row);
        check(slots.equals(Map.of(slot,slot)),"actual inner-to-middle-to-outer transport composes");
        var ref=inner.getChildClasses().get(0);field(ref,"slotMap",Map.of(slot,"foreign"));
        reject(()->operands(outer),"forged child transport has no outer binder");
    }
    private static JSONObject replay(List<Canonical.Prepared> pair,int direction) {
        var a=pair.get(direction);var b=pair.get(1-direction);
        var fast=CanonicalRepairTraces.fast(a,b);
        var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
        int f=fast.json().getInt("distance"),c=certified.json().getInt("distance");
        var order=f<c?List.of(fast,certified):List.of(certified,fast);
        for(var trace:order) {
            var receipt=trace.json();check(trace.replay(receipt)==receipt.getInt("distance"),"selected native replay");
            reject(()->trace.replay(new JSONObject(receipt.toString()).put("distance",-1)),"tampered native cost");
        }
        check(f==40,"Fast actual selected path preserved");
        var comparison=LocalOwnerTraceTest.compareSelected(a,b,fast,certified);
        check(comparison.status()!=NativeOperationComparison.Status.INVALID_REPLAY,"no invalid native replay");
        return new JSONObject().put("direction",direction).put("fast",fast.json()).put("certified",certified.json())
                .put("fastDistance",f).put("certifiedDistance",c).put("replayOrder",f<c?"fast-first":"certified-first")
                .put("status",comparison.status()).put("reason",comparison.reason()).put("comparison",comparison.evidence());
    }
    public static void main(String[] args) throws Exception {
        var out=Path.of(args[0]);Files.createDirectories(out);var text=Files.readString(SOURCE);
        if(args.length>1 && args[1].equals("--wide128")) {
            for(var operator:List.of("and","or"))booleanFamily(operator,128);
            return;
        }
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(SOURCE))).equals(SHA),"original source hash");
        long start=System.nanoTime();var s=state(text);var d=s.duplicate();
        check(d.ownerPhase()==0 && d.ancestorPath().equals("phase/0/matrix/child/0/child/2/child/1"),"measured typed owner");
        var replays=new JSONArray();for(int direction=0;direction<2;direction++)replays.put(replay(s.pair(),direction));
        double seconds=(System.nanoTime()-start)/1e9;
        Files.writeString(out.resolve("replays.json"),replays.toString(2),StandardOpenOption.CREATE_NEW);
        check(seconds<60,"original file both directions below 60 seconds");
        var foreign=state(text);
        reject(()->d.requireMatches(foreign.pair().get(0).normalizedForms()),"foreign owner");
        reject(()->d.requireMatches(List.of(s.pair().get(1).normalizedForms().get(0),s.form())),"wrong phase index");
        reject(()->s.construction().semanticArtifact().withGuardedJoinConstructions(List.of()),"missing original proof");
        for(String key:List.of("lineage","sourcePath","transferContent"))corrupt(text,x->
                field(x.duplicate().removedPremise(),key,key.equals("lineage")?-1L:"forged"),"removed proof "+key);
        corrupt(text,x->field(x.form(),"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal role");
        corrupt(text,x->field(x.form(),"phasePath","foreign"),"phase coordinate");
        corrupt(text,x->field(original(x,x.duplicate().removedPremise().sourceOccurrencePath()),"exactAlloyType",null),"removed JOIN type");
        corrupt(text,x->field(live(x,x.duplicate().retainedPremise().sourceOccurrenceLineage()),"sourceOccurrenceLineage",-1L),"surviving lineage");
        corrupt(text,x->field(original(x,x.duplicate().ancestorPath()),"opcode",EGraphNode.Opcode.OR),"different Boolean owner");
        corrupt(text,x->{var join=original(x,x.duplicate().removedPremise().sourceOccurrencePath());
            var ports=new ArrayList<>(join.getChildClasses());Collections.reverse(ports);field(join,"childClasses",ports);},"ordered unary ports");
        corrupt(text,x->{var join=original(x,x.duplicate().removedPremise().sourceOccurrencePath());
            field(join.getChildren().get(1),"alphaName","_q1");},"captured variable slot");
        var renamed=text.replace("all e : grades.Grade.c", "all other : grades.Grade.c").replace("c.grades.e]","c.grades.other]");
        check(!renamed.equals(text),"renamed source differs");state(renamed);
        var scaling=new JSONArray();for(var operator:List.of("and","or"))for(int copies:List.of(2,8,32))scaling.put(booleanFamily(operator,copies));
        Files.writeString(out.resolve("scaling.json"),scaling.toString(2),StandardOpenOption.CREATE_NEW);
        separateOwnerControl();slotCompositionControl();
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives)
                .put("originalSource",SOURCE.toString()).put("sha256",SHA).put("originalFileSeconds",seconds)
                .put("familyVariants",List.of("original","renamed-local-binder")).put("publicCertification","BLOCKED")
                .toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("GuardedJoinDuplicateReplayTest: "+checks+" checks, "+negatives+" negative controls; file "+seconds+"s");
    }
}

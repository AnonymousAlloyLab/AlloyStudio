package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;
import parser.ast.nodes.ModelUnit;

/** dcx: retain construction proofs while checking why their occurrence was erased. */
public final class GuardedJoinErasureReplayTest {
    private static final Path SOURCE=Path.of("classified-data/coursesNew/under/dcxEP6ZgQAktuDbeT_inv10.als");
    private static int checks;
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static Pair prepare(String text,String left,String right)throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();
        for(var name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(ps.get(0),ps.get(1));
    }
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static TheoryAlloyAdapter.Result evidence(Canonical.Prepared p){var e=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());e.requireRepairProjectionSources(p.normalizedForms());return e;}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static Map<String,String> mapping(JSONObject j){var m=new TreeMap<String,String>();for(var k:j.keySet())m.put(k,j.getString(k));return m;}
    private static int[] mapping(JSONArray j){var a=new int[j.length()];for(int i=0;i<a.length;i++)a[i]=j.getInt(i);return a;}
    private static List<DeclarationReplay.Atom> fastAtoms(NormalForm form) {
        var source=CanonicalDistance.diagnosticDeclarationAtoms(form);
        return CanonicalDistance.diagnosticDeclarationOrder(form).stream().map(source::get).toList();
    }
    private static List<EGraphNode> nodes(EGraphNode root){var result=new ArrayList<EGraphNode>();var pending=new ArrayDeque<EGraphNode>();pending.add(root);while(!pending.isEmpty()){check(result.size()<4096,"test traversal budget");var n=pending.removeFirst();result.add(n);pending.addAll(n.getChildren());}return result;}
    private static void corrupt(String text,Consumer<NormalForm> edit,String why)throws Exception {
        var pair=prepare(text,"inv10","inv10c");var e=evidence(pair.a);edit.accept(pair.a.normalizedForms().get(0));
        reject(()->GuardedJoinSourceFiber.bindWithEvidence(e.semanticArtifact(),pair.a.normalizedForms()),why);
    }
    private static JSONObject replay(Pair p) {
        var a=CanonicalAlloyPipeline.prepare(p.a);var b=CanonicalAlloyPipeline.prepare(p.b);
        var report=DistanceReplayDiagnostics.capture(p.a,p.b,a,b);
        var order=report.getJSONObject("fast").getInt("distance")<report.getJSONObject("certified").getInt("distance")
                ?List.of("fast","certified"):List.of("certified","fast");
        for(var arm:order) {
            var row=report.getJSONObject(arm);var phase=row.getJSONArray("phases").getJSONObject(0);var receipt=phase.getJSONObject("receipt");int cost;
            if(arm.equals("certified")) {
                var m=mapping(phase.getJSONArray("alphaMapping"));cost=QuotientRepairDistance.verifyFixedMatrixReplay(a.repairView(),b.repairView(),m,m,receipt);
                reject(()->QuotientRepairDistance.verifyFixedMatrixReplay(a.repairView(),b.repairView(),m,m,copy(receipt).put("cost",-1)),"corrupt certified cost");
            } else {
                var fa=CanonicalDistance.prepare(p.a.normalizedForms());var fb=CanonicalDistance.prepare(p.b.normalizedForms());var m=mapping(phase.getJSONObject("alphaMapping"));
                cost=CanonicalDistance.verifyFixedMatrixReplay(fa,fb,m,m,receipt);
                reject(()->CanonicalDistance.verifyFixedMatrixReplay(fa,fb,m,m,copy(receipt).put("cost",-1)),"corrupt Fast cost");
            }
            check(cost==row.getInt("matrixDistance"),"independent fixed-choice matrix "+arm);
            var declarations=row.getJSONObject("declarationSlice");
            var components=declarations.getJSONArray("components");
            var dr=components.getJSONObject(arm.equals("fast")?0:1).getJSONObject("receipt");
            var fa=p.a.normalizedForms().get(0);var fb=p.b.normalizedForms().get(0);
            var namesA=fa.getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
            var namesB=fb.getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
            check(nodes(fa.getMatrixEGraph()).stream().noneMatch(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE)
                    && nodes(fb.getMatrixEGraph()).stream().noneMatch(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE),
                    "both actual repaired endpoints have no variable uses");
            var atomsA=arm.equals("fast")?fastAtoms(fa):NestedPaidDeclarationReplayTest.atoms(a.repairView().phases().get(0),dr.getJSONArray("source"));
            var atomsB=arm.equals("fast")?fastAtoms(fb):NestedPaidDeclarationReplayTest.atoms(b.repairView().phases().get(0),dr.getJSONArray("target"));
            var link=new DeclarationMatrixLink(namesA,namesB,Map.of(),Set.of(),Set.of());
            check(link.verify(atomsA,atomsB,dr)==2,"independent original declaration delete/insert2 "+arm);
            reject(()->link.verify(atomsA,atomsB,copy(dr).put("cost",1)),"forged declaration charge");
            check(declarations.getBoolean("selectedMatrixDeclarationLinksChecked"),"native joint link emitted "+arm);
            check(!row.getBoolean("complete"),"native slice not public certificate");
            check(row.getInt("distance")==8 && row.getInt("quantifierDistance")==2 && cost==6,"native components8/0/2/6 "+arm);
        }
        var cmp=NativeOperationComparison.compare(p.a,p.b,a,b);var nativeEvidence=cmp.evidence();
        check(cmp.status()==NativeOperationComparison.Status.MATCHED
                && nativeEvidence.getJSONArray("fastActions").similar(nativeEvidence.getJSONArray("certifiedActions")),
                "original-coordinate native actions after checked erasure");
        check(!nativeEvidence.getBoolean("complete"),"native correspondence is not public certification");
        return new JSONObject().put("diagnostic",report).put("comparison",nativeEvidence).put("status",cmp.status()).put("reason",cmp.reason()).put("replayOrder",order).put("certificationStatus","BLOCKED");
    }
    public static void main(String[] args)throws Exception {
        var out=Path.of(args[0]);Files.createDirectories(out);var text=Files.readString(SOURCE);var p=prepare(text,"inv10","inv10c");var e=evidence(p.a);var a=e.semanticArtifact();
        check(a.guardedJoinConstructions().size()==1,"one retained original JOIN construction");
        var binding=GuardedJoinSourceFiber.bindWithEvidence(a,p.a.normalizedForms());
        check(binding.live().isEmpty()&&binding.erased().size()==1,"exact live/erased partition");var erased=binding.erased().get(0);
        check(erased.premise()==a.guardedJoinConstructions().get(0),"actual original proof retained, not omitted");
        erased.requireMatches(p.a.normalizedForms());check(erased.ownerPhase()==0&&erased.ancestorPath().equals("phase/0/matrix/child/0"),"source-linked OR ancestor and same phase");
        CertificateVerifier.verify(erased.premise());check(erased.premise().steps().isEmpty(),"unary JOIN interior remains unrotated");
        reject(()->a.withGuardedJoinConstructions(List.of()),"missing construction premise");
        reject(()->a.withGuardedJoinConstructions(List.of(erased.premise(),erased.premise())),"duplicated source premise");
        var foreign=prepare(text,"inv10","inv10c");reject(()->erased.requireMatches(foreign.a.normalizedForms()),"foreign original owner");
        reject(()->GuardedJoinSourceFiber.bindWithEvidence(a,foreign.a.normalizedForms()),"foreign source/module");
        reject(()->erased.requireMatches(List.of(p.b.normalizedForms().get(0),p.a.normalizedForms().get(0))),"different phase owner");
        reject(()->GuardedJoinSourceFiber.bindWithEvidence(a,List.of(p.a.normalizedForms().get(0),p.a.normalizedForms().get(0))),"shared node across temporal phases");
        corrupt(text,f->field(f.getMatrixEGraph().getChildren().get(0),"sourceName","false"),"wrong repaired constant");
        corrupt(text,f->field(f.getMatrixEGraph().getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign repaired lineage");
        corrupt(text,f->field(f.getMatrixEGraph().getChildren().get(0),"exactAlloyType",null),"missing repaired Boolean type");
        corrupt(text,f->field(f,"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal owner not admitted");
        for(String scope:List.of("params","inheritedQuantiVars","phaseLocalBindingImports"))corrupt(text,f->field(f,scope,List.of(new Object())),"unadmitted scope "+scope);
        corrupt(text,f->{var positive=nodes(f.getCertificationMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.IN).findFirst().orElseThrow();field(positive.getChildren().get(0),"alphaName","_q1");},"wrong complement binder");
        corrupt(text,f->{var positive=nodes(f.getCertificationMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.IN).findFirst().orElseThrow();field(positive.getChildren().get(1),"semanticIdentity","alloy/signature/Professor");},"different complement relation");
        corrupt(text,f->{var positive=nodes(f.getCertificationMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.IN).findFirst().orElseThrow();field(positive.getChildren().get(0),"exactAlloyType",f.getMatrixQuantiVars().get(1).getExactAlloyType());},"wrong complement literal type");
        // A missing source is not enough: force a non-tautology's repair tree to
        // look like true, but preserve its actual checked source construction.
        var other=prepare(text.replace("implies x in Student","implies x in Professor"),"inv10","inv10c");var oe=evidence(other.a);var of=other.a.normalizedForms().get(0);var fake=of.getMatrixEGraph().getChildren().get(0);
        field(fake,"opcode",EGraphNode.Opcode.CONSTANT);field(fake,"sourceName","true");field(fake,"sourceType","Bool");field(fake,"childClasses",new ArrayList<>());
        reject(()->GuardedJoinSourceFiber.bindWithEvidence(oe.semanticArtifact(),other.a.normalizedForms()),"absent JOIN without complement evidence rejected");
        for(int d=0;d<2;d++)Files.writeString(out.resolve("after-"+d+".json"),replay(d==0?p:p.reverse()).toString(2),StandardOpenOption.CREATE_NEW);
        var d3=prepare(Files.readString(Path.of("classified-data/classroom_rl/both/D3n2EfPhBboewRm7z_inv14.als")),"inv14","inv14c");var de=evidence(d3.a);var db=GuardedJoinSourceFiber.bindWithEvidence(de.semanticArtifact(),d3.a.normalizedForms());
        check(db.live().size()==1&&db.erased().isEmpty()&&db.live().values().iterator().next().premises().size()==2,"D3n2 retains both shared surviving proofs");
        for(var q:List.of(d3,d3.reverse()))check(Canonical.distance(q.a,q.b)==20&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(q.a),CanonicalAlloyPipeline.prepare(q.b))==20,"D3n2 cost20");
        var partial=prepare(Files.readString(Path.of("docs/distance-parity-20261107/partial-join.als")),"lhs","rhs");for(var q:List.of(partial,partial.reverse()))check(Canonical.distance(q.a,q.b)==0&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(q.a),CanonicalAlloyPipeline.prepare(q.b))==0,"P03 guarded rotations");
        var controls=new JSONArray();for(var row:List.of(new String[]{"classroom_fol/both/wdiLn4gC6q3bbYBad_inv3","inv3","7"},new String[]{"coursesNew/correct/wnqMPtpNxnPYzru3g_inv14","inv14","16"},new String[]{"coursesNew/over/PvuBZf9iKQbBARbsj_inv14","inv14","32"})){
            var q=prepare(Files.readString(Path.of("classified-data",row[0]+".als")),row[1],row[1]+"c");for(var dir:List.of(q,q.reverse()))check(Canonical.distance(dir.a,dir.b)==Integer.parseInt(row[2])&&CanonicalAlloyPipeline.distance(CanonicalAlloyPipeline.prepare(dir.a),CanonicalAlloyPipeline.prepare(dir.b))==Integer.parseInt(row[2]),"prior family "+row[0]);controls.put(row[0]);}
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("GuardedJoinErasureReplayTest: "+checks+" checks passed");
    }
}

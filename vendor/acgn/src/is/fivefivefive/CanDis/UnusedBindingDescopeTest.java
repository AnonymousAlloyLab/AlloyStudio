package is.fivefivefive.CanDis;

import java.util.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;
import parser.ast.nodes.ModelUnit;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;

/** Shared-source descope and Fast/certified selected-path controls. */
public final class UnusedBindingDescopeTest {
    private static int checks;
    private static void check(boolean ok, String why) {
        checks++; if (!ok) throw new AssertionError(why);
    }
    public static Canonical.Prepared prepare(String source, String name) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of(name), module);
        visitor.visit(new ModelUnit(null,module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static void tamper(NativeRepairTrace trace) {
        var bad = trace.json(); bad.put("distance", trace.distance() + 1);
        try { trace.replay(bad); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Changed distance was accepted");
    }
    private static int slots(Canonical.Prepared p) {
        return p.normalizedForms().stream().mapToInt(f -> f.getMatrixQuantiVars().size() + f.getLocalQuantiVars().size()).sum();
    }
    private static void sourceLaw(String original,String reduced) throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {r:set A} pred p {"+original+"} pred q {"+reduced+"}");
        var options=new A4Options(); options.solver=kodkod.engine.satlab.SATFactory.get("sat4j");
        for(int size=0;size<=3;size++) {
            var cmd=new Command(false,3,4,4,null,CompUtil.parseOneExpression_fromString(m,
                    "#A = "+size+" and not (p iff q)"));
            check(!TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,m.getAllReachableSigs(),cmd,options).satisfiable(),
                    "independent bounded source equivalence size="+size+": "+original);
        }
    }
    private static void equivalent(String original, String reduced, int count) throws Exception {
        String source = "sig A {r:set A} sig B {} pred p {" + original + "} pred q {" + reduced + "}";
        var a = prepare(source,"p"); var b = prepare(source,"q");
        check(slots(a)==count,"retained slots: " + original + " got " + slots(a));
        check(a.normalizedForms().stream().anyMatch(f -> !f.getUnusedBindingReductions().isEmpty()),
                "source reduction evidence: " + original);
        var ca = CanonicalAlloyPipeline.prepare(a); var cb = CanonicalAlloyPipeline.prepare(b);
        for (boolean reverse : List.of(false,true)) {
            var l = reverse ? b : a; var r = reverse ? a : b;
            var cl = reverse ? cb : ca; var cr = reverse ? ca : cb;
            var cert = CanonicalRepairTraces.certified(cl,cr);
            check(cert.replay(cert.json())==0,"certified equivalence: " + original);
            var fast = CanonicalRepairTraces.fast(l,r);
            check(fast.replay(fast.json())==0,"Fast equivalence: " + original);
            tamper(cert); tamper(fast);
            check(NativeOperationComparison.compare(l,r,fast,cert).status()==NativeOperationComparison.Status.MATCHED,
                    "checked native correspondence: " + original);
        }
    }
    public static void main(String[] args) throws Exception {
        equivalent("all x,y:A | x in x.r", "all x:A | x in x.r", 1);
        equivalent("all y,x:A | x in x.r", "all x:A | x in x.r", 1);
        equivalent("some x,y,z:A | y in y.r", "some y:A | y in y.r", 1);
        equivalent("no x,y:A | x in x.r", "no x:A | x in x.r", 1);
        equivalent("all x:A | some B", "some A implies some B", 0);
        equivalent("some x,y:A | some B", "some A and some B", 0);
        equivalent("no x,y:A | some B", "not (some A and some B)", 0);
        equivalent("some x:set A | some B", "some B", 0);
        equivalent("all x:lone A | some B", "some B", 0);
        equivalent("all x:A | all y:B | x in x.r", "all x:A | some B implies x in x.r", 1);
        equivalent("all x:A | x in x.r or not (x in x.r)","no none",0);
        equivalent("some x:A | x in x.r or not (x in x.r)","some A",0);
        equivalent("no x:A | x in x.r or not (x in x.r)","no A",0);
        equivalent("all x:A | all y:B | x in x.r or not (x in x.r)","no none",0);
        equivalent("some x:A | all y:B | x in x.r or not (x in x.r)","some A",0);
        equivalent("all x:A | x in x.r and not (x in x.r)","no A",0);
        equivalent("some x:A,y:set x.r | no none","some A",0);
        equivalent("all x:A,y:set x.r | no none","no none",0);
        equivalent("some x:A | some y:set x.r | no none","some A",0);
        var dependent = prepare("sig A {r:set A} pred p {all x,y:A | some z:x.r | z->z in r and z in y.r}","p");
        check(slots(dependent)==3,"domain references are uses, not orphan coordinates");
        var disjoint = prepare("sig A {} pred p {some disj x,y:A | some A}","p");
        check(slots(disjoint)==2,"disjointness implicitly uses both coordinates");
        for(String kind:List.of("one","lone")) {
            var counted=prepare("sig A {r:set A} pred p {"+kind+" x,y:A | x in x.r or not (x in x.r)}","p");
            check(slots(counted)==2,"counting declaration retained after tautology: "+kind);
        }
        sourceLaw("some x:A | x in x.r or not (x in x.r)","some A");
        sourceLaw("all x:A | x in x.r and not (x in x.r)","no A");
        // Alloy's SAT backend cannot negate this higher-order source assertion.
        // Check every relation through size 3 independently; Lean supplies the
        // unbounded empty-subset witness, including the empty carrier case.
        for(int size=0;size<=3;size++) for(int relation=0;relation<(1<<(size*size));relation++) {
            boolean original=false;
            for(int x=0;x<size;x++) for(int subset=0;subset<(1<<size);subset++) {
                int domain=(relation>>(x*size))&((1<<size)-1);
                if((subset & ~domain)==0) original=true;
            }
            check(original==(size>0),"unused SET domain keeps no false dependency");
        }
        var shadowed=prepare("sig A {r:set A} pred p {all x:A | some x:A | x in x.r}","p");
        check(slots(shadowed)==1,"shadowed inner x does not keep outer x live");
        var temporal=prepare("sig A {r:set A} pred p {all x,y:A | always x in x.r}","p");
        check(temporal.normalizedForms().stream().flatMap(f->f.getMatrixQuantiVars().stream())
                .anyMatch(q->q.getOriginalNames().contains("x")),"temporal-only reference retained");
        System.out.println("UnusedBindingDescopeTest: " + checks + " checks passed");
    }
}

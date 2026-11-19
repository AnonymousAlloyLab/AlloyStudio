package is.fivefivefive.CanDis;

import java.lang.reflect.Method;
import java.util.*;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

/** Source-linked old-gate rejection, composed-orbit and forged-owner controls. */
public final class CrossReferenceAdmissionTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static Canonical.Prepared source(String file, String predicate) throws Exception {
        var module=AlloyUtil.compileAlloyModule(file);
        return prepare(module,predicate);
    }
    private static Canonical.Prepared prepare(edu.mit.csail.sdg.parser.CompModule module,String predicate) throws Exception {
        var visitor=new MASGVisitor(new GlobalVariables(),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(predicate)));
    }
    public static void main(String[] args) throws Exception {
        String data=args[0];
        var dependentReference = java.nio.file.Path.of(data,"socialMedia/correct/frYeGpTJ6HqHkkAPG_inv7.als");
        check(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.nio.file.Files.readAllBytes(dependentReference)))
                .equals("a955443775d28c73d68f7810c618c3cf81995f83c3b882b8ed92467fdea59153"), "Lean source witness hash");
        LocalBinderTransportTest.run(source(dependentReference.toString(),"inv7").normalizedForms().get(0));
        LocalBinderTransportTest.publicationControls(source(dependentReference.toString(),"inv7").normalizedForms(), false);
        var regrouped=AlloyUtil.compileAlloyModule(data+"/coursesNew/both/65RDEj22kn4Z3CToj_inv13.als");
        MatrixCaptureTest.run(prepare(regrouped,"inv13"),prepare(regrouped,"inv13c"));
        for(String phase:List.of("", "always ")) {
            var fixture=edu.mit.csail.sdg.parser.CompUtil.parseEverything_fromString(
                    edu.mit.csail.sdg.alloy4.A4Reporter.NOP,
                    "var sig P { var r:set C } sig C {} pred left { "+phase
                    +"(all p:P | all c:C | c in p.r) } pred right { "+phase
                    +"(all c:C,p:P | c in p.r) }");
            MatrixCaptureTest.run(prepare(fixture,"left"),prepare(fixture,"right"));
        }
        var repeated=source(data+"/classroom_fol/both/WEdygNkrar3xy8Frz_inv15.als","inv15");
        var form=repeated.normalizedForms().get(0);
        LiveReadMapTest.run(form);
        for(var fixture:List.of(
                new String[]{"productionLine_v2/both/nSKNSxWYRfEj6EKMP_inv10.als","inv10"},
                new String[]{"productionLine_v2/both/xqGAdbaTJSY5WsrqK_inv10.als","inv10"},
                new String[]{"coursesOld/over/837w8rxhJL5puGnni_inv13.als","inv13"},
                new String[]{"trainStationNew/both/9aov7tBy2LfDmizfK_inv10.als","inv10"},
                new String[]{"trainStationNew/both/aZaQ7xkeZ5KipJGXu_inv10.als","inv10"},
                new String[]{"coursesOld/correct/T3spZ7j3BkNNjJZZb_inv15.als","inv15"})) {
            var module=AlloyUtil.compileAlloyModule(data+"/"+fixture[0]);
            var left=prepare(module,fixture[1]);var right=prepare(module,fixture[1]+"c");
            for(int direction=0;direction<2;direction++) {
                var x=direction==0?left:right;var y=direction==0?right:left;
                var fast=CanonicalRepairTraces.fast(x,y);
                var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(x),CanonicalAlloyPipeline.prepare(y));
                check(Canonical.distance(x,y)==fast.distance(),"live-read numeric/capture selection");
                var comparison=LocalOwnerTraceTest.compareSelected(x,y,fast,certified);
                check(comparison.status()==NativeOperationComparison.Status.MATCHED,
                        fixture[0]+"/"+direction+": "+comparison.reason());
                for(var trace:List.of(fast,certified)) {
                    check(trace.replay(trace.json())==trace.distance(),"live-read selected native replay");
                    var bad=trace.json();bad.put("distance",-1);boolean rejected=false;
                    try {trace.replay(bad);}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}
                    check(rejected,"live-read corrupted payment rejected");
                }
            }
        }
        var aliases=new HashSet<String>();boolean collision=false;
        for(var q:form.getMatrixQuantiVars())for(var alias:q.getOriginalNames())collision|=!aliases.add(alias);
        check(collision,"exact old global spelling gate rejects");
        Method admission=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment")
                .getDeclaredMethod("supportsDirectEndpoint",List.class);admission.setAccessible(true);
        check((boolean)admission.invoke(null,List.of(form)),"source-owned repeated spellings admitted");
        var q=form.getMatrixQuantiVars().get(2);
        var field=QuantiVar.class.getDeclaredField("bindingPath");field.setAccessible(true);
        Object old=field.get(q);
        try { field.set(q,"foreign");check(!(boolean)admission.invoke(null,List.of(form)),"forged source owner rejected"); }
        finally { field.set(q,old); }
        check((boolean)admission.invoke(null,List.of(form)),"restored source witness");

        var merged=source(data+"/coursesNew/correct/vJ6jnsfJKnboP76GA_inv5.als","inv5");
        var prefix=merged.normalizedForms().get(0);boolean falseTuple=false;
        for(var x:prefix.getMatrixQuantiVars())for(var y:prefix.getMatrixQuantiVars())
            falseTuple|=x!=y&&x.getBindingPath().equals(y.getBindingPath())
                &&!x.getCarrierTypeName().equals(y.getCarrierTypeName())
                &&x.getDeclarationFrames().stream().noneMatch(f->y.getDeclarationFrames().stream()
                    .anyMatch(g->g.declarationOccurrence()==f.declarationOccurrence()));
        check(falseTuple,"exact old path grouping conflates separately issued typed declarations");
        check((boolean)admission.invoke(null,List.of(prefix)),"issued occurrence groups retain separate payloads");

        var localSource=source(data+"/socialMedia/correct/m785Qwt2gkm4sZMkL_inv1.als","inv1");
        InvocationCopyTest.run(localSource);
        var nested=edu.mit.csail.sdg.parser.CompUtil.parseEverything_fromString(
                edu.mit.csail.sdg.alloy4.A4Reporter.NOP,
                "var sig N { var edge:set N } pred specimen { always (no edge and (all disj x,y,z:N | "
                +"some disj p,q:x.edge | no p.edge & q.edge and y in N and z in N)) }");
        InvocationCopyTest.run(prepare(nested,"specimen"));
        LocalBinderTransportTest.publicationControls(prepare(nested,"specimen").normalizedForms(), true);
        var local=localSource.normalizedForms().get(0);
        var live=new HashSet<String>();var pending=new ArrayDeque<EGraphNode>();pending.add(local.getCertificationMatrixEGraph());
        while(!pending.isEmpty()){var n=pending.remove();if(n.getOpcode()==EGraphNode.Opcode.VARIABLE)live.add(n.getAlphaName());pending.addAll(n.getChildren());}
        var disj=local.getLocalQuantiVars().stream().filter(v->v.getDisjointnessClass()>0&&live.contains(v.getName())).toList();
        check(disj.size()>=2,"exact old gate rejects retained disjoint tuple");
        check((boolean)admission.invoke(null,List.of(local)),"source-owned local disjoint scope admitted");
        var groupField=QuantiVar.class.getDeclaredField("disjointnessClass");groupField.setAccessible(true);
        Object group=groupField.get(disj.get(0));
        try {groupField.set(disj.get(0),0);check(!(boolean)admission.invoke(null,List.of(local)),"erased disjoint group rejected");}
        finally {groupField.set(disj.get(0),group);}

        var cv=source(data+"/cv_v2/both/s24Bc38fMWfdDhZLY_inv2.als","inv2").normalizedForms().get(0);
        var cvTarget=source(data+"/cv_v2/correct/rcc6rb8D7mgkr9akM_inv2.als","inv2").normalizedForms().get(0);
        var user=cv.getMatrixQuantiVars().get(0);var institution=cv.getMatrixQuantiVars().get(1);var workSlot=cv.getMatrixQuantiVars().get(2);
        var wf=workSlot.getDeclarationFrames().get(0);var tw=cvTarget.getMatrixQuantiVars().get(1).getDeclarationFrames().get(0);
        check(institution.getDeclarationFrames().get(0).domain().scalars().get(0).equals("alloy/signature/Institution"),"actual Institution domain");
        check(wf.domain().tag().equals("JOIN") && wf.domain().children().get(0).scalars().get(0).equals("dependency/0")
                && wf.domain().children().get(1).scalars().get(0).equals("profile"),"actual typed dependent profile domain");
        check(wf.dependencies().equals(List.of(user)) && wf.domain().equals(tw.domain()),"concrete selected dependency and guard");
        check(wf.declarationOccurrence()!=institution.getDeclarationFrames().get(0).declarationOccurrence(),"separate issued domains");
        var domainField=NormalForm.DeclarationFrame.class.getDeclaredField("domain");domainField.setAccessible(true);
        var issued=NormalForm.class.getDeclaredMethod("matrixDeclarationsMatchSource");issued.setAccessible(true);
        Object domain=domainField.get(wf);
        try {domainField.set(wf,user.getDeclarationFrames().get(0).domain());check(!(boolean)issued.invoke(cv),"forged profile domain rejected by issuance");}
        finally {domainField.set(wf,domain);}
        check((boolean)issued.invoke(cv),"restored dependent source frames");

        var a=source(data+"/coursesOld/both/4Na3Kyrvzbptnqhhu_inv10.als","inv10");
        var b=source(data+"/coursesOld/correct/AngG4RJCPYrN4n4kr_inv10.als","inv10");
        var univ=b.normalizedForms().get(0);
        Method orders=Class.forName("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit")
                .getDeclaredMethod("orders",NormalForm.class,NormalForm.class,List.class);orders.setAccessible(true);
        @SuppressWarnings("unchecked") var permutations=(List<int[]>)orders.invoke(null,univ,univ,univ.getMatrixQuantiVars());
        check(permutations.size()==6,"full three-slot orbit, not four single-swap candidates");
        check(permutations.stream().anyMatch(p->Arrays.equals(p,new int[]{2,0,1})),"three-cycle admitted");
        check(permutations.stream().anyMatch(p->Arrays.equals(p,new int[]{1,2,0})),"inverse three-cycle admitted");
        String work=System.getProperty("acgn.maxCanonicalWorkItems");
        try {
            System.setProperty("acgn.maxCanonicalWorkItems","1");
            check(Canonical.distance(a,b)==14 && Canonical.distance(b,a)==14,"Fast-only composed transport both directions");
        }finally {if(work==null)System.clearProperty("acgn.maxCanonicalWorkItems");else System.setProperty("acgn.maxCanonicalWorkItems",work);}
        var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
        check(CanonicalAlloyPipeline.distance(ca,cb)==14 && CanonicalAlloyPipeline.distance(cb,ca)==14,"certified composed transport");
        for(var pair:List.of(
                new String[]{"classroom_fol/both/Zw99mDLEs3F3SEdAT_inv14.als","classroom_fol/correct/GRSuLtEunFAkAWM97_inv14.als","inv14"},
                new String[]{"classroom_fol/both/mAjXPLufKDPbBtqsH_inv14.als","classroom_fol/correct/2ACBt6wKCXpZaDLCK_inv14.als","inv14"},
                new String[]{"cv_v2/both/s24Bc38fMWfdDhZLY_inv2.als","cv_v2/correct/rcc6rb8D7mgkr9akM_inv2.als","inv2"},
                new String[]{"coursesNew/both/Crfa78pfwnfn3BmoC_inv5.als","coursesNew/correct/vJ6jnsfJKnboP76GA_inv5.als","inv5"},
                new String[]{"coursesNew/both/GSJYXr7LbjqqBCox3_inv5.als","coursesNew/correct/vJ6jnsfJKnboP76GA_inv5.als","inv5"},
                new String[]{"coursesOld/both/mQTiiJRnphSxEscmT_inv7.als","coursesOld/correct/zFyEFhnjJroPfbxgJ_inv7.als","inv7"},
                new String[]{"productionLine_v2/both/54rvKhEtHxu4PgTCn_inv2.als","productionLine_v2/correct/y6N6rYXgHoZFZnPdB_inv2.als","inv2"},
                new String[]{"socialMedia/both/r8beekhodwfSzHqGC_inv1.als","socialMedia/correct/m785Qwt2gkm4sZMkL_inv1.als","inv1"},
                new String[]{"socialMedia/under/WKQNxQ3Wdtcu2yc2N_inv4.als","socialMedia/correct/4P7tfCGEuWRkvjCiD_inv4.als","inv4"},
                new String[]{"socialMedia/under/WKQNxQ3Wdtcu2yc2N_inv4.als","socialMedia/correct/D5Y8Sj2zhLvhti4KH_inv4.als","inv4"},
                new String[]{"trash_ltl/both/57TuLfdtDJDrsFDiP_inv11.als","trash_ltl/correct/bNE7jtzJmxk9osNoG_inv11.als","inv11"})) {
            var x=source(data+"/"+pair[0],pair[2]);var y=source(data+"/"+pair[1],pair[2]);
            for(int direction=0;direction<2;direction++) {
                var left=direction==0?x:y;var right=direction==0?y:x;
                var fast=CanonicalRepairTraces.fast(left,right);
                var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(left),CanonicalAlloyPipeline.prepare(right));
                var result=LocalOwnerTraceTest.compareSelected(left,right,fast,certified);
                check(result.status()==NativeOperationComparison.Status.MATCHED,pair[0]+"/"+direction+": "+result.reason());
                if(pair[0].startsWith("trash_ltl/")) {
                    var guards=NormalizedDeclarationDomainTest.class.getDeclaredMethod("guards",List.class);guards.setAccessible(true);
                    int retained=(int)guards.invoke(null,fast.comparisonForms(true))+(int)guards.invoke(null,fast.comparisonForms(false));
                    check(retained>0,"nested normalized guard with all existing corruption controls");
                }
                for(var trace:List.of(fast,certified)) {
                    var bad=trace.json();bad.put("distance",-1);boolean rejected=false;
                    try {trace.replay(bad);}catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}
                    check(rejected,"changed native charge rejected after transport");
                }
            }
        }
        String chain="p0.r=p1 and p1.r=p2 and p2.r=p3 and p3.r=p4 and p4.r=p5 and p5.r=p0";
        String text="sig P { r:set P } sig Q { s:set P } pred left { all q:Q,p0,p1,p2,p3,p4,p5:P | q.s=p0 and "+chain
                +" } pred right { all p0,p1,p2,p3,p4,p5:P | "+chain+" }";
        var module=edu.mit.csail.sdg.parser.CompUtil.parseEverything_fromString(edu.mit.csail.sdg.alloy4.A4Reporter.NOP,text);
        var sixLeft=prepare(module,"left");var sixRight=prepare(module,"right");
        long start=System.nanoTime();
        for(int direction=0;direction<2;direction++) {
            var x=direction==0?sixLeft:sixRight;var y=direction==0?sixRight:sixLeft;
            int numeric=Canonical.distance(x,y);
            var fast=CanonicalRepairTraces.fast(x,y);
            var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(x),CanonicalAlloyPipeline.prepare(y));
            check(numeric==fast.distance()&&numeric==cert.distance(),"six-survivor numeric/capture boundary");
            check(fast.replay(fast.json())==numeric&&cert.replay(cert.json())==numeric,
                    "six-survivor native replay with actual source permutations");
            var comparison=LocalOwnerTraceTest.compareSelected(x,y,fast,cert);
            System.out.println("sixSurvivorCrossPath direction="+direction+" status="+comparison.status()+" reason="+comparison.reason());
            check(comparison.status()!=NativeOperationComparison.Status.INVALID_REPLAY
                    && comparison.status()!=NativeOperationComparison.Status.ERROR,"six-survivor native validity");
        }
        double seconds=(System.nanoTime()-start)/1e9;
        check(seconds<60,"six-survivor boundary below per-file limit");
        System.out.println("sixSurvivorSeconds="+seconds);
        System.out.println("CrossReferenceAdmissionTest: "+checks+" checks passed");
    }
}

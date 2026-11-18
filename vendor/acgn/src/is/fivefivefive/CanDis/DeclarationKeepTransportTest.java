package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.metric.DeclarationReplay;
import is.fivefivefive.CanDis.metric.RepairView;

/** A selected deletion may join surviving typed slots without exchanging binders. */
public final class DeclarationKeepTransportTest {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++;
        if(!value)throw new AssertionError(message);
    }
    private static final String[][] CASES={
        {"coursesOld/both/HjBjDe69psgfLCxqG_inv13.als","inv13","a1a764b4525102550866985ae39b7cb27c797aeb6990adb691836c4feb1033f1"},
        {"coursesOld/both/WnKSFBYHaizWvRtpM_inv13.als","inv13","cc055cf8e81f040ea185af75c689f242c3183db5074dd518f7b8f5e1e81632bb"},
        {"coursesOld/both/pscJmn7TkMfagLLWT_inv13.als","inv13","2576dfc4245c170960c61e928a2e0e2748218b3831667e54b5e0e110a84b9b18"},
        {"coursesOld/both/wS6s4WsoZyefNGHT3_inv13.als","inv13","0a0d0152c22faa9e218711c0320d3d25b0d24ace87a18bbae7917a165aa7b18e"},
        {"coursesOld/both/z2uPT6C9gL4YS6Xqd_inv13.als","inv13","b794813d7b15884d4d7e33a98ec2a2060eddbfb121b22a3a032ec5530a8ee902"},
        {"coursesOld/under/qmNLxeA6ZoNHcEo6T_inv15.als","inv15","baa66ca9da433ff99146397942fb0710baf3c0265f607ffaa01458456b1171f3"},
        {"coursesOld/under/v82s62vFeMz9G5PgW_inv15.als","inv15","55626d487bc58c2a35f52c8a4479f44d48c1540893869ffc19a9db5fdbc03b53"},
        {"productionLine_v1/under/xctf9WMsHufP3x5fy_inv4.als","inv4","739f8a1b16d04c36feee7fefaa324424c3865872344c812c999cbfb3548ddf2a"}
    };
    private static JSONObject copy(JSONObject report){return new JSONObject(report.toString());}
    private static void rejects(Canonical.Prepared a,Canonical.Prepared b,JSONObject bad,String message) {
        check(NativeOperationComparison.compareCaptured(a,b,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,message);
    }
    private static Field field(Object o,String name)throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static Object get(Object o,String name)throws Exception { return field(o,name).get(o); }
    private static Method method(Class<?> type,String name) {
        var m=Arrays.stream(type.getDeclaredMethods()).filter(x->x.getName().equals(name)).findFirst().orElseThrow();
        m.setAccessible(true);return m;
    }
    @FunctionalInterface private interface Attempt { Object run()throws Exception; }
    private static void declined(Attempt attempt,String reason)throws Exception {
        boolean rejected=false;
        try { rejected=attempt.run()==null; }
        catch(InvocationTargetException e) {
            if(e.getCause() instanceof IllegalArgumentException || e.getCause() instanceof IllegalStateException)rejected=true;
            else throw e;
        }
        check(rejected,reason);
    }
    @SuppressWarnings("unchecked")
    private static void admissionControls(RepairView a,RepairView b)throws Exception {
        var captured=method(QuotientRepairDistance.class,"captureDecisions").invoke(null,a,b);
        var stats=method(captured.getClass(),"decisions").invoke(captured);
        var paths=(List<List<DeclarationReplay.Move>>)get(stats,"selectedDeclarationPaths");
        field(stats,"activeDeclarationPaths").set(stats,paths);
        var carried=(Map<Integer,Integer>)get(stats,"selectedCorrespondence");
        var constructor=Class.forName("is.fivefivefive.CanDis.metric.CertifiedDeclarationOrbit$Choice").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        int[] left=(int[])get(stats,"selectedDeclarationLeft"),right=(int[])get(stats,"selectedDeclarationRight");
        var choice=constructor.newInstance(carried,left,right);
        var extraction=method(QuotientRepairDistance.class,"selectedDeclarationMapping");
        Object space=get(stats,"quantification"),source=get(stats,"selectedLeft"),target=get(stats,"selectedRight");
        Attempt valid=()->extraction.invoke(null,space,source,target,choice,stats);
        check(Arrays.equals((int[])valid.run(),(int[])get(stats,"selectedMapping")),"independent selected-path extraction");
        var original=paths.get(1);
        var omitted=new ArrayList<>(original);omitted.remove(0);
        var duplicated=new ArrayList<>(original);duplicated.add(0,original.get(0));
        var reordered=new ArrayList<>(original);Collections.reverse(reordered);
        var emptyMove=new ArrayList<>(original);emptyMove.add(new DeclarationReplay.Move(-1,-1));
        var expensive=new ArrayList<DeclarationReplay.Move>();
        for(var move:original) {
            if(move.left()>=0)expensive.add(new DeclarationReplay.Move(move.left(),-1));
            if(move.right()>=0)expensive.add(new DeclarationReplay.Move(-1,move.right()));
        }
        for(var bad:List.of(omitted,duplicated,reordered,emptyMove,expensive)) {
            var changed=new ArrayList<>(paths);changed.set(1,bad);
            try { field(stats,"activeDeclarationPaths").set(stats,changed);declined(valid,"malformed selected path at admission"); }
            finally { field(stats,"activeDeclarationPaths").set(stats,paths); }
        }
        var falsePaid=new LinkedHashMap<>(carried);
        if(falsePaid.isEmpty())falsePaid.put(0,0);else falsePaid.remove(falsePaid.keySet().iterator().next());
        var badChoice=constructor.newInstance(falsePaid,left,right);
        declined(()->extraction.invoke(null,space,source,target,badChoice,stats),"paid links must be exact, not a subset");
        int[] badPermutation=left.clone();badPermutation[0]=left.length;
        var badOrbit=constructor.newInstance(carried,badPermutation,right);
        declined(()->extraction.invoke(null,space,source,target,badOrbit,stats),"foreign orbit index at admission");
        var binding=a.phases().get(0).bindings().get(0);
        for(var mutation:List.of(new Object[]{"ownerPhase",99},new Object[]{"bindingPath","foreign"},
                new Object[]{"originalBinding",null})) {
            var f=field(binding,(String)mutation[0]);var old=f.get(binding);
            try { f.set(binding,mutation[1]);declined(valid,"foreign original binding at admission: "+mutation[0]); }
            finally { f.set(binding,old); }
        }
        var declaration=binding.declaration();
        for(String name:List.of("observationType","observationDomain","dependencies")) {
            var f=field(declaration,name);var old=f.get(declaration);
            try { f.set(declaration,name.equals("dependencies")?List.of(99):null);declined(valid,"missing structured premise: "+name); }
            finally { f.set(declaration,old); }
        }
        check(Arrays.equals((int[])valid.run(),(int[])get(stats,"selectedMapping")),"all admission mutations restored");
    }
    public static void main(String[] args)throws Exception {
        var out=args.length==0?Files.createTempDirectory("declaration-keeps-"):Path.of(args[0]);Files.createDirectories(out);
        for(int c=0;c<CASES.length;c++) {
            var fixture=CASES[c];
            var identity=new JSONObject().put("source",Path.of("classified-data",fixture[0]).toAbsolutePath().toString())
                .put("sha256",fixture[2]).put("left",fixture[1]).put("right",fixture[1]+"c");
            var pair=LocalOwnerTraceTest.prepare(identity);
            for(int direction=0;direction<2;direction++) {
                long started=System.nanoTime();var a=pair.get(direction);var b=pair.get(1-direction);
                var ea=CanonicalAlloyPipeline.prepare(a);var eb=CanonicalAlloyPipeline.prepare(b);
                var fast=CanonicalRepairTraces.fast(a,b);var certified=CanonicalRepairTraces.certified(ea,eb);
                var fj=fast.json();var cj=certified.json();
                if(fj.getInt("distance")<=cj.getInt("distance")){fast.replay(fj);certified.replay(cj);}
                else{certified.replay(cj);fast.replay(fj);}
                var result=NativeOperationComparison.compare(a,b,fast,certified);
                check(result.status()==NativeOperationComparison.Status.MATCHED,fixture[0]+"/"+direction+" actual ordered actions");
                check(QuotientRepairDistance.distance(ea.repairView(),eb.repairView())==cj.getInt("distance"),"scalar/captured selection parity");
                var report=result.evidence().getJSONObject("diagnostic");
                check(NativeOperationComparison.compareCaptured(a,b,report).status()==NativeOperationComparison.Status.MATCHED,"retained receipt replay");
                if(c==0)admissionControls(ea.repairView(),eb.repairView());
                Files.writeString(out.resolve(c+"-"+direction+".json"),result.evidence().toString(2),StandardOpenOption.CREATE_NEW);
                var bad=copy(report);bad.getJSONObject("certified").getJSONArray("selectedGlobalMapping").put(0,999);
                rejects(a,b,bad,"foreign coordinate");
                bad=copy(report);bad.getJSONObject("certified").getJSONArray("phases").getJSONObject(0).put("phase",999);
                rejects(a,b,bad,"foreign phase");
                bad=copy(report);bad.getJSONObject("certified").getJSONArray("nativeDeclarationPrograms").getJSONObject(1)
                    .getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"ForeignType");
                rejects(a,b,bad,"foreign declaration type");
                bad=copy(report);bad.getJSONObject("certified").getJSONArray("nativeDeclarationPrograms").getJSONObject(1)
                    .getJSONArray("source").getJSONObject(0).put("occurrence","foreign-owner");
                rejects(a,b,bad,"foreign original owner");
                bad=copy(report);bad.getJSONObject("certified").put("matrixDistance",cj.getInt("matrixDistance")+1);
                rejects(a,b,bad,"changed component payment");
                bad=copy(report);bad.getJSONObject("certified").getJSONArray("nativeDeclarationPrograms").getJSONObject(1)
                    .getJSONArray("operations").remove(0);
                rejects(a,b,bad,"missing declaration operation");
                if(c==0&&direction==0) {
                    var source=ea.repairView().phases().get(0).bindings();var target=eb.repairView().phases().get(0).bindings();
                    check(source.get(0).declaration().exchangeClass()!=source.get(2).declaration().exchangeClass()
                        &&target.get(0).declaration().exchangeClass()==target.get(1).declaration().exchangeClass(),"old injective block gate rejects surviving typed slots");
                    check(cj.getJSONArray("selectedGlobalMapping").similar(new JSONArray(new int[]{0,-1,1})),"selected slot links after deletion");
                    check(cj.getJSONObject("selectedQuantifierCorrespondence").isEmpty(),"zero keeps not relabeled as paid edits");
                    check(source.get(2).declaration().sameCertifiedPayload(target.get(1).declaration()),"actual type/domain/dependency premise");
                }
                check((System.nanoTime()-started)<60_000_000_000L,"natural direction including corrupt controls below sixty seconds");
            }
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("directions",16).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("DeclarationKeepTransportTest: "+checks+" checks passed; 16 matched directions; no public certification claim");
    }
}

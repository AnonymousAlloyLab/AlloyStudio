package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.DeclarationReplay;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.metric.RepairView;

/** Select an evaluated owner-linked path without adding multi-phase searches. */
public final class OwnerLinkedDeclarationSelectionTest {
    private static int checks, negatives;
    private static void check(boolean ok, String why) {
        checks++; if (!ok) throw new AssertionError(why);
    }
    private static Field field(Object value, String name) throws Exception {
        var f=value.getClass().getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object get(Object value, String name) throws Exception { return field(value,name).get(value); }
    private static Method method(Class<?> type, String name) {
        var m=Arrays.stream(type.getDeclaredMethods()).filter(x->x.getName().equals(name)).findFirst().orElseThrow();
        m.setAccessible(true); return m;
    }
    @FunctionalInterface private interface Attempt { Object run() throws Exception; }
    private static void declined(Attempt attempt, String why) throws Exception {
        boolean rejected=false; negatives++;
        try { rejected=attempt.run()==null; }
        catch(InvocationTargetException e) {
            if(e.getCause() instanceof IllegalArgumentException || e.getCause() instanceof IllegalStateException)rejected=true;
            else throw e;
        }
        check(rejected,why);
    }
    @SuppressWarnings("unchecked")
    private static void admissionControls(RepairView a, RepairView b, Canonical.Prepared pa,
            Canonical.Prepared pb, is.fivefivefive.CanDis.metric.NativeRepairTrace fast,
            is.fivefivefive.CanDis.metric.NativeRepairTrace cert, Path out, String label) throws Exception {
        var capture=method(QuotientRepairDistance.class,"captureDecisions");
        var captured=capture.invoke(null,a,b);
        var stats=method(captured.getClass(),"decisions").invoke(captured);
        var paths=(List<List<DeclarationReplay.Move>>)get(stats,"selectedDeclarationPaths");
        field(stats,"activeDeclarationPaths").set(stats,paths);
        var paid=(Map<Integer,Integer>)get(stats,"selectedCorrespondence");
        var ctor=Class.forName("is.fivefivefive.CanDis.metric.CertifiedDeclarationOrbit$Choice").getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        int[] left=(int[])get(stats,"selectedDeclarationLeft"),right=(int[])get(stats,"selectedDeclarationRight");
        var choice=ctor.newInstance(paid,left,right);
        var extract=method(QuotientRepairDistance.class,"selectedDeclarationMapping");
        Object space=get(stats,"quantification"),source=get(stats,"selectedLeft"),target=get(stats,"selectedRight");
        Attempt valid=()->extract.invoke(null,space,source,target,choice,stats);
        check(Arrays.equals((int[])valid.run(),(int[])get(stats,"selectedMapping")),"actual selected path yields its owner map");
        check(a.phases().size()==3&&b.phases().size()==3,"old phase gate rejects this instance");
        check(paths.size()==4&&paths.get(3).isEmpty(),"inherited phase has no new declaration payment");
        var original=paths.get(2);
        var omitted=new ArrayList<>(original); omitted.remove(0);
        var duplicated=new ArrayList<>(original); duplicated.add(0,original.get(0));
        var reversed=new ArrayList<>(original); Collections.reverse(reversed);
        var empty=new ArrayList<>(original); empty.add(new DeclarationReplay.Move(-1,-1));
        var expensive=new ArrayList<DeclarationReplay.Move>();
        for(var m:original) {
            if(m.left()>=0)expensive.add(new DeclarationReplay.Move(m.left(),-1));
            if(m.right()>=0)expensive.add(new DeclarationReplay.Move(-1,m.right()));
        }
        for(var bad:List.of(omitted,duplicated,reversed,empty,expensive)) {
            var changed=new ArrayList<>(paths); changed.set(2,bad);
            try { field(stats,"activeDeclarationPaths").set(stats,changed); declined(valid,"malformed owner-local declaration path"); }
            finally { field(stats,"activeDeclarationPaths").set(stats,paths); }
        }
        var falsePaid=new LinkedHashMap<>(paid); falsePaid.put(0,0);
        var badChoice=ctor.newInstance(falsePaid,left,right);
        declined(()->extract.invoke(null,space,source,target,badChoice,stats),"zero keep cannot become a carried payment");
        var binding=a.phases().get(1).bindings().get(0);
        for(var mutation:List.of(new Object[]{"ownerPhase",99},new Object[]{"ownerContext","foreign"},
                new Object[]{"bindingPath","foreign"},new Object[]{"originalBinding",null})) {
            var f=field(binding,(String)mutation[0]); var old=f.get(binding);
            try { f.set(binding,mutation[1]); declined(valid,"foreign/unissued owner: "+mutation[0]); }
            finally { f.set(binding,old); }
        }
        for(String name:List.of("observationType","observationDomain","dependencies")) {
            var declaration=binding.declaration(); var f=field(declaration,name); var old=f.get(declaration);
            try { f.set(declaration,name.equals("dependencies")?List.of(99):null); declined(valid,"missing structured premise: "+name); }
            finally { f.set(declaration,old); }
        }
        var inherited=a.phases().get(2).bindings().get(0);
        for(String name:List.of("ownerContext","ownerPhase","coordinate")) {
            var f=field(inherited,name); var old=f.get(inherited);
            try {
                f.set(inherited,name.equals("ownerContext")?"foreign":99);
                declined(()->method(inherited.getClass(),"originalBinding").invoke(inherited),"inherited original authority: "+name);
                var rejected=NativeOperationComparison.compare(pa,pb,fast,cert);
                Files.writeString(out.resolve(label+"-object-"+name+".json"),new JSONObject()
                    .put("status",rejected.status()).put("reason",rejected.reason()).put("evidence",rejected.evidence()).toString(2),StandardOpenOption.CREATE_NEW);
                negatives++;
                check(rejected.status()==NativeOperationComparison.Status.INVALID_REPLAY
                    ||rejected.status()==NativeOperationComparison.Status.ERROR
                        &&rejected.evidence().optString("error").equals("Foreign original projected binding object"),
                    "full boundary rejects inherited corruption: "+name+" "+rejected.status()+" "+rejected.reason());
            }
            finally { f.set(inherited,old); }
        }
        check(Arrays.equals((int[])valid.run(),(int[])get(stats,"selectedMapping")),"all object mutations restored");
    }
    public static void main(String[] args) throws Exception {
        var family=new JSONArray(Files.readString(Path.of(args[0])));
        var out=Path.of(args[1]); Files.createDirectory(out); var rows=new JSONArray();
        for(Object raw:family) {
            var id=(JSONObject)raw; long start=System.nanoTime(); var pair=LocalOwnerTraceTest.prepare(id);
            for(int d=0;d<2;d++) {
                var a=pair.get(d); var b=pair.get(1-d); String old=System.getProperty("acgn.maxCanonicalWorkItems");
                is.fivefivefive.CanDis.metric.NativeRepairTrace fast;
                try { if(id.optBoolean("nestedIncident"))System.setProperty("acgn.maxCanonicalWorkItems","1"); fast=CanonicalRepairTraces.fast(a,b); }
                finally { if(old==null)System.clearProperty("acgn.maxCanonicalWorkItems"); else System.setProperty("acgn.maxCanonicalWorkItems",old); }
                var ca=CanonicalAlloyPipeline.prepare(a); var cb=CanonicalAlloyPipeline.prepare(b);
                var cert=CanonicalRepairTraces.certified(ca,cb); var order=new JSONObject();
                QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),order);
                var result=NativeOperationComparison.compare(a,b,fast,cert);
                var row=new JSONObject().put("identity",id).put("direction",d).put("fast",fast.json())
                    .put("certified",cert.json()).put("comparison",result.evidence()).put("replayOrder",order);
                Files.writeString(out.resolve(id.getString("id")+"-"+d+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
                check(result.status()==NativeOperationComparison.Status.MATCHED,"action equality: "+result.status()+" "+result.reason());
                check(fast.distance()==id.getInt("expectedDistance")&&cert.distance()==fast.distance(),"retained scalar distance");
                check(Canonical.distance(a,b)==fast.distance()&&QuotientRepairDistance.distance(ca.repairView(),cb.repairView())==cert.distance(),"numeric and captured selection parity");
                for(String f:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked","allNativeEntriesRetained"))
                    check(result.evidence().getBoolean(f),f);
                if(id.has("priorReceipts")) {
                    var previous=new JSONObject(Files.readString(Path.of(id.getJSONArray("priorReceipts").getString(d))));
                    check(previous.getJSONObject("fast").similar(fast.json()),"Fast execution is byte-structurally unchanged");
                    var cj=cert.json(); var before=previous.getJSONObject("certified");
                    for(String f:List.of("phases","nativeTemporalProgram","selectedGlobalMapping","selectedDeclarationPermutation","selectedQuantifierCorrespondence"))
                        check(Objects.equals(before.get(f).toString(),cj.get(f).toString()),"retained matrix/temporal selection: "+f);
                    check(before.getLong("binderAlignments")==cj.getLong("binderAlignments"),"no additional multi-phase mapping evaluations");
                }
                if(id.optBoolean("nestedIncident")) {
                    admissionControls(ca.repairView(),cb.repairView(),a,b,fast,cert,out,id.getString("id")+"-"+d);
                    var cj=cert.json(); check(cj.getInt("matrixDistance")==7&&cj.getInt("quantifierDistance")==1,"component costs retained");
                    check(cj.getJSONObject("selectedQuantifierCorrespondence").isEmpty(),"zero keep remains unpaid");
                    var ops=cj.getJSONArray("nativeDeclarationPrograms").getJSONObject(2).getJSONArray("operations");
                    check(ops.length()==2&&ops.getJSONObject(0).getString("kind").equals("keep")
                        &&ops.getJSONObject(0).getInt("leftIndex")==0&&ops.getJSONObject(0).getInt("rightIndex")==0,"actual linked path captured first");
                    check(cj.getJSONArray("nativeDeclarationPrograms").getJSONObject(3).getJSONArray("operations").isEmpty(),"no inherited declaration duplication");
                    var report=result.evidence().getJSONObject("diagnostic");
                    for(String mode:List.of("drop-map","one-sided-map","phase","type","owner","cost","omit")) {
                        var bad=new JSONObject(report.toString()); var c=bad.getJSONObject("certified");
                        var program=c.getJSONArray("nativeDeclarationPrograms").getJSONObject(2);
                        switch(mode) {
                            case "drop-map" -> c.getJSONArray("selectedGlobalMapping").put(0,-1);
                            case "one-sided-map" -> c.getJSONArray("selectedGlobalMapping").put(d==0?1:0,d==0?0:1);
                            case "phase" -> c.getJSONArray("phases").getJSONObject(1).put("phase",99);
                            case "type" -> program.getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"ForeignType");
                            case "owner" -> program.getJSONArray("source").getJSONObject(0).put("occurrence","foreign");
                            case "cost" -> program.put("cost",2);
                            case "omit" -> program.getJSONArray("operations").remove(0);
                        }
                        var rejected=NativeOperationComparison.compareCaptured(a,b,fast,cert,bad);
                        Files.writeString(out.resolve(id.getString("id")+"-"+d+"-"+mode+".json"),
                            new JSONObject().put("status",rejected.status()).put("reason",rejected.reason()).put("evidence",rejected.evidence()).toString(2),StandardOpenOption.CREATE_NEW);
                        negatives++; check(rejected.status()==NativeOperationComparison.Status.INVALID_REPLAY,"invalid receipt rejected: "+mode+" "+rejected.status()+" "+rejected.reason());
                    }
                }
                rows.put(new JSONObject().put("id",id.getString("id")).put("direction",d).put("status",result.status()).put("distance",fast.distance()));
            }
            check(System.nanoTime()-start<60_000_000_000L,"file including both directions and corruption controls below60seconds");
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("OwnerLinkedDeclarationSelectionTest: "+checks+" checks, "+negatives+" negatives; "+rows.length()+" MATCHED directions");
    }
}

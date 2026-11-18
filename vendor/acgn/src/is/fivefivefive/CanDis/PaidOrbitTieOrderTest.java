package is.fivefivefive.CanDis;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Equal-score witness priority must preserve actual native replay authority. */
public final class PaidOrbitTieOrderTest {
    private static int checks, negatives;
    private static void check(boolean value,String why) {
        checks++;if(!value)throw new AssertionError(why);
    }
    private static void supportControls()throws Exception {
        Method support=Class.forName("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit")
                .getDeclaredMethod("paidSupported",List.class,Collection.class,int[].class);
        support.setAccessible(true);
        for(int n=0;n<=6;n++) {
            var slots=new ArrayList<QuantiVar>();
            for(int i=0;i<n;i++)slots.add(new QuantiVar(i,"slot"+i,"v"+i,"A"));
            for(int mask=0;mask<(1<<n);mask++) {
                var paid=new HashSet<String>();
                for(int i=0;i<n;i++)if((mask&(1<<i))!=0)paid.add(slots.get(i).getName());
                int[] identity=java.util.stream.IntStream.range(0,n).toArray();
                check((Boolean)support.invoke(null,slots,paid,identity),"identity supported");
                for(int i=0;i<n;i++)for(int j=i+1;j<n;j++) {
                    int[] swap=identity.clone();swap[i]=j;swap[j]=i;
                    boolean expected=(mask&((1<<i)|(1<<j)))!=0;
                    check((Boolean)support.invoke(null,slots,paid,swap)==expected,"single-swap paid completion");
                    if(!expected)negatives++;
                }
            }
        }
    }
    private static void invalid(Canonical.Prepared a,Canonical.Prepared b,JSONObject bad,String why) {
        negatives++;
        check(NativeOperationComparison.compareCaptured(a,b,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);
    }
    private static JSONObject copy(JSONObject x){return new JSONObject(x.toString());}
    public static void main(String[] args)throws Exception {
        supportControls();var family=new JSONArray(Files.readString(Path.of(args[0])));
        var out=Path.of(args[1]);Files.createDirectories(out);var rows=new JSONArray();
        for(int index=0;index<family.length();index++) {
            var id=family.getJSONObject(index);long start=System.nanoTime();
            var pair=LocalOwnerTraceTest.prepare(id);
            for(int d=0;d<2;d++) {
                var a=pair.get(d);var b=pair.get(1-d);
                String old=System.getProperty("acgn.maxCanonicalWorkItems");NativeRepairTrace fast;
                try {
                    System.setProperty("acgn.maxCanonicalWorkItems","1");
                    fast=CanonicalRepairTraces.fast(a,b);
                } finally {
                    if(old==null)System.clearProperty("acgn.maxCanonicalWorkItems");
                    else System.setProperty("acgn.maxCanonicalWorkItems",old);
                }
                var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
                var cert=CanonicalRepairTraces.certified(ca,cb);var fj=fast.json();var cj=cert.json();
                var replayOrder=fj.getInt("distance")<=cj.getInt("distance")?List.of("fast","certified"):List.of("certified","fast");
                for(var arm:replayOrder)if(arm.equals("fast"))fast.replay(fj);else cert.replay(cj);
                var result=NativeOperationComparison.compare(a,b,fast,cert);var evidence=result.evidence();
                Files.writeString(out.resolve(id.getString("id")+"-"+d+".json"),evidence.toString(2),StandardOpenOption.CREATE_NEW);
                check(result.status()==NativeOperationComparison.Status.MATCHED,"complete action equality "+result.status()+" "+result.reason());
                int expected=id.getInt("expectedDistance");
                check(fj.getInt("distance")==expected&&cj.getInt("distance")==expected,"unchanged component sum");
                check(Canonical.distance(a,b)==expected&&QuotientRepairDistance.distance(ca.repairView(),cb.repairView())==expected,"scalar and capture agree");
                check(evidence.getBoolean("sourceCertificatesChecked")&&evidence.getBoolean("endpointCorrespondenceChecked")&&evidence.getBoolean("fixedChoiceReplaysChecked"),"source/typed/native checks");
                var diagnostic=evidence.getJSONObject("diagnostic");
                check(NativeOperationComparison.compareCaptured(a,b,diagnostic).status()==NativeOperationComparison.Status.MATCHED,"captured independent replay");
                var bad=copy(diagnostic);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(a,b,bad,"foreign source rejected");
                bad=copy(diagnostic);bad.getJSONObject("fast").put("quantifierDistance",0);invalid(a,b,bad,"lost declaration payment rejected");
                bad=copy(diagnostic);bad.getJSONObject("certified").getJSONObject("selectedDeclarationPermutation").getJSONArray("left").put(0,1);invalid(a,b,bad,"nonbijective order rejected");
                bad=copy(diagnostic);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",0);invalid(a,b,bad,"forged matrix charge rejected");
                rows.put(new JSONObject().put("id",id.getString("id")).put("direction",d).put("status",result.status()).put("distance",expected).put("replayOrder",replayOrder));
            }
            double seconds=(System.nanoTime()-start)/1e9;check(seconds<60,"whole file plus negative controls below60seconds");
        }
        var receipt=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",rows);
        Files.writeString(out.resolve("checks.json"),receipt.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("PaidOrbitTieOrderTest: "+checks+" checks, "+negatives+" negative controls; "+rows.length()+" MATCHED directions");
    }
}

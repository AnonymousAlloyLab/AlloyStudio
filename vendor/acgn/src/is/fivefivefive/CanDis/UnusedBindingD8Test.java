package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;

/** Original-source D8 selected-path regression, not a full-pool parity claim. */
public final class UnusedBindingD8Test {
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static void tamper(NativeRepairTrace trace) {
        var bad = trace.json(); bad.put("distance",trace.distance()+1);
        try { trace.replay(bad); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Changed distance accepted");
    }
    public static void main(String[] args) throws Exception {
        String prefix = "sig User {follows:set User,sees:set Photo,posts:set Photo,suggested:set User}\n"
                + "sig Influencer extends User {} sig Photo {date:one Day} sig Ad extends Photo {} sig Day {}\npred inv4 {";
        String learner = prefix + "all x:User | some y:Photo | y in x.posts and y in Ad => (all p:Photo | p in x.posts and p in Ad)}";
        String body = "all u1,u2:User | all a:Ad | u1->a in posts implies (all p:Photo | u1->p in posts implies p in Ad )";
        String suffix = "}";
        JSONObject input = null;
        String inputPath = System.getProperty("acgn.d8.input");
        if (inputPath != null) {
            byte[] bytes = Files.readAllBytes(Path.of(inputPath));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            require(hash.equals("41af5eba8c4b3ad4c9b37f0a35d243d1d94fa477f446a2e12c4c87c3881db097"),"fixed original source manifest");
            input = new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
            learner = input.getString("studentSource"); prefix = input.getString("referencePrefix"); suffix = input.getString("referenceSuffix");
        }
        for (int index : List.of(49,259)) {
            String reference = prefix + (input == null ? body : input.getJSONArray("referenceBodies").getString(index)) + suffix;
            var a = UnusedBindingDescopeTest.prepare(learner,"inv4");
            var b = UnusedBindingDescopeTest.prepare(reference,"inv4");
            var form = b.normalizedForms().get(0);
            require(form.getMatrixQuantiVars().size()==3,"u2 removed, u1/a/p retained");
            require(form.getMatrixQuantiVars().stream().noneMatch(q -> q.getOriginalNames().contains("u2")),"no u2 orphan");
            var reduction = form.getUnusedBindingReductions().stream().filter(r -> r.sourceNames().equals(List.of("u1","u2"))).findFirst().orElseThrow();
            require(reduction.retainedSourceSlots().equals(List.of(0)),"original owner slot 0 retained; slot 1 removed");
            require(reduction.exactTypes().get(0).equals(reduction.exactTypes().get(1)),"same typed source domain");
            var ca = CanonicalAlloyPipeline.prepare(a); var cb = CanonicalAlloyPipeline.prepare(b);
            for (boolean reverse : List.of(false,true)) {
                var l=reverse?b:a; var r=reverse?a:b;
                var cert=CanonicalRepairTraces.certified(reverse?cb:ca,reverse?ca:cb);
                var fast=CanonicalRepairTraces.fast(l,r);
                var first=cert.distance()<=fast.distance()?cert:fast;
                var second=first==cert?fast:cert;
                require(first.replay(first.json())==first.distance(),"shorter selected replay");
                require(second.replay(second.json())==second.distance(),"other selected replay");
                tamper(cert); tamper(fast);
                var comparison=NativeOperationComparison.compare(l,r,fast,cert);
                var evidence=new JSONObject().put("index",index).put("reverse",reverse)
                        .put("fast",fast.json()).put("certified",cert.json()).put("comparison",comparison.evidence())
                        .put("sourceSlots",reduction.sourceNames()).put("sourceToRetained",reduction.retainedSourceSlots());
                String out=System.getProperty("acgn.evidence");
                if(out!=null) Files.writeString(Path.of(out,index+"-"+reverse+".json"),evidence.toString(2),StandardOpenOption.CREATE_NEW);
                require(fast.distance()==cert.distance(),"D8 distance mismatch "+fast.distance()+"/"+cert.distance());
                require(comparison.status()==NativeOperationComparison.Status.MATCHED,"D8 correspondence: "+comparison.reason());
                System.out.println("D8 "+index+" reverse="+reverse+" Fast="+fast.distance()+" certified="+cert.distance()+" "+comparison.reason());
            }
        }
    }
}

package is.fivefivefive.CanDis.metric;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.*;
import parser.ast.nodes.ModelUnit;

/** Source-linked paid routing and corruption controls. No optimization in replay. */
public final class PaidTemporalRoutingTest {
    private static int checks, rejected, paidUpdates, paidInsertions, binaryUpdates, binaryInsertions, splitInsertions;
    private static void check(boolean ok,String why) {checks++;if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable test,String why) {
        checks++;try {test.run();}catch(IllegalArgumentException|IllegalStateException|JSONException expected){rejected++;return;}
        throw new AssertionError("Accepted "+why);
    }
    private static void rejectAt(Runnable test,String message) {
        checks++;try {test.run();}catch(IllegalArgumentException expected) {
            if(!expected.getMessage().contains(message))throw new AssertionError("Wrong failed premise: "+expected.getMessage(),expected);
            rejected++;return;
        }
        throw new AssertionError("Accepted coherent wrong partition");
    }
    private static List<Canonical.Prepared> prepare(String text) throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var result=new ArrayList<Canonical.Prepared>();
        for(String name:List.of("a","b")) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);visitor.visit(new ModelUnit(null,module),null);
            result.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        return result;
    }
    private static JSONArray at(JSONArray state,JSONArray path) {
        for(Object index:path)state=state.getJSONArray(3).getJSONArray(((Number)index).intValue());return state;
    }
    private static JSONArray childOrigins(JSONArray children) {
        var out=new JSONArray();for(Object child:children)out.put(((JSONArray)child).getInt(1));return out;
    }
    private static void ruleEffect(JSONObject step) {
        var edit=step.getJSONObject("selectedEdit");String kind=edit.getString("kind");
        if(kind.equals("delete"))return;
        var rule=step.getJSONObject("routingRule");var path=edit.getJSONArray("path");
        var before=at(step.getJSONArray("before"),path);var after=at(step.getJSONArray("after"),path);
        check(rule.getInt("metricCharge")==edit.getInt("cost")&&edit.getInt("cost")==1,"same selected paid charge");
        JSONArray oldChildren;
        if(kind.equals("update")) {
            paidUpdates++;oldChildren=before.getJSONArray(3);
            check(rule.getString("rule").equals("paid-head-update"),"head refinement rule");
            check(before.getInt(1)==after.getInt(1)&&before.getInt(2)==after.getInt(2),"head update retains occurrence/incoming role");
            if(edit.getString("to").equals("UNTIL")||edit.getString("from").equals("UNTIL"))binaryUpdates++;
        } else {
            paidInsertions++;check(rule.getString("rule").equals("paid-interval-insert"),"interval refinement rule");
            int start=edit.getInt("index"),count=edit.getInt("childCount");oldChildren=new JSONArray();
            for(int j=start;j<start+count;j++)oldChildren.put(before.getJSONArray(3).get(j));
            after=after.getJSONArray(3).getJSONArray(start);
            check(after.getInt(1)==-step.getInt("targetOccurrence"),"insert actual selected target occurrence");
            if(edit.getString("label").equals("UNTIL")) {
                binaryInsertions++;
                if(step.getJSONArray("childRoles").toList().containsAll(List.of(0,1)))splitInsertions++;
            }
        }
        check(childOrigins(oldChildren).similar(childOrigins(after.getJSONArray(3))),"all children in selected order, no target-copy shortcut");
        check(childOrigins(oldChildren).similar(rule.getJSONArray("childOrigins")),"rule bound to actual child identities");
        for(int j=0;j<oldChildren.length();j++) {
            var x=oldChildren.getJSONArray(j);var y=after.getJSONArray(3).getJSONArray(j);
            check(x.getString(0).equals(y.getString(0))&&x.getJSONArray(3).similar(y.getJSONArray(3)),"child operator and entire descendant payload unchanged");
            check(y.getInt(2)==step.getJSONArray("childRoles").getInt(j),"executed explicit routing payload");
        }
    }
    private static void run(Path source,Path out,JSONArray rows) throws Exception {
        String text=Files.readString(source);var pair=prepare(text);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        for(int d=0;d<2;d++) {
            var ca=CanonicalAlloyPipeline.prepare(pair.get(d));var cb=CanonicalAlloyPipeline.prepare(pair.get(1-d));
            var cert=CanonicalRepairTraces.certified(ca,cb);
            check(cert.replay(cert.json())==cert.distance(),"selected certified execution first");
            var fast=CanonicalRepairTraces.fast(pair.get(d),pair.get(1-d));
            check(fast.replay(fast.json())==fast.distance(),"selected Fast execution");
            var a=ca.repairView().temporalRoot();var b=cb.repairView().temporalRoot();
            var program=cert.json().getJSONObject("nativeTemporalProgram");
            var proof=TypedTemporalDerivation.capture(a,b,program);
            TypedTemporalDerivation.verify(a,b,program,proof);checks++;
            check(program.similar(cert.json().getJSONObject("nativeTemporalProgram")),"capture does not alter selected program");
            check(cert.json().getJSONObject("constructionDerivations").getBoolean("retained"),"construction separately retained");
            for(int i=0;i<proof.getJSONArray("operations").length();i++) {
                var step=proof.getJSONArray("operations").getJSONObject(i);ruleEffect(step);
                if(step.getJSONObject("selectedEdit").getString("kind").equals("delete"))continue;
                final int index=i;
                for(String mode:List.of("missing-rule","forged-rule","charge","child-origin","child-kind","missing-roles","foreign-role","string-role","selected-edit","destination","owner")) {
                    var bad=new JSONObject(proof.toString());var op=bad.getJSONArray("operations").getJSONObject(index);
                    switch(mode) {
                        case "missing-rule" -> op.remove("routingRule");
                        case "forged-rule" -> op.getJSONObject("routingRule").put("rule","endpoint-agreement");
                        case "charge" -> op.getJSONObject("routingRule").put("metricCharge",0);
                        case "child-origin" -> op.getJSONObject("routingRule").put("childOrigins",new JSONArray().put(999));
                        case "child-kind" -> op.getJSONObject("routingRule").put("afterChildKinds",new JSONArray().put("NONE"));
                        case "missing-roles" -> op.remove("childRoles");
                        case "foreign-role" -> op.put("childRoles",new JSONArray().put(999));
                        case "string-role" -> op.put("childRoles",new JSONArray().put("0"));
                        case "selected-edit" -> op.getJSONObject("selectedEdit").put("cost",0);
                        case "destination" -> op.put("targetOccurrence",999);
                        case "owner" -> op.put("incomingRole",999);
                    }
                    reject(()->TypedTemporalDerivation.verify(a,b,program,bad),"actual paid derivation "+mode);
                }
                if(step.getJSONArray("childRoles").length()>0) {
                    var bad=new JSONObject(proof.toString());var op=bad.getJSONArray("operations").getJSONObject(index);
                    var roles=op.getJSONArray("childRoles");roles.put(0,1-roles.getInt(0));
                    reject(()->TypedTemporalDerivation.verify(a,b,program,bad),"swapped actual binary routing payload");
                }
                var selectedEdit=step.getJSONObject("selectedEdit");
                if(i==proof.getJSONArray("operations").length()-1&&selectedEdit.getString("kind").equals("update")
                        &&selectedEdit.getString("to").equals("UNTIL")&&step.getJSONArray("childRoles").toList().contains(1)) {
                    // A different valid partition passes the local paid-head rule,
                    // but must fail final original typed ownership, not metadata equality.
                    var bad=new JSONObject(proof.toString());var op=bad.getJSONArray("operations").getJSONObject(index);
                    var after=at(op.getJSONArray("after"),selectedEdit.getJSONArray("path"));
                    for(int j=0;j<op.getJSONArray("childRoles").length();j++) {
                        op.getJSONArray("childRoles").put(j,0);
                        op.getJSONObject("routingRule").getJSONArray("afterChildKinds").put(j,"UNTILL");
                        after.getJSONArray(3).getJSONArray(j).put(2,0).put(4,"UNTILL");
                    }
                    rejectAt(()->TypedTemporalDerivation.verify(a,b,program,bad),"final owner/role/operator");
                }
                for(String mode:List.of("cost","path","identity-update","interval","order")) {
                    var bad=new JSONObject(program.toString());var edits=bad.getJSONArray("operations");var edit=edits.getJSONObject(index);
                    switch(mode) {
                        case "cost" -> edit.put("cost",0);
                        case "path" -> edit.put("path",new JSONArray().put(999));
                        case "identity-update" -> {if(edit.getString("kind").equals("update"))edit.put("to",edit.getString("from"));else edit.put("label","FOREIGN");}
                        case "interval" -> {if(edit.getString("kind").equals("insert"))edit.put("childCount",999);else edit.put("from","FOREIGN");}
                        case "order" -> edits.put(edit);
                    }
                    reject(()->TypedTemporalDerivation.capture(a,b,bad),"actual selected program "+mode);
                }
            }
            var receipt=new JSONObject().put("source",source.toString()).put("sha256",hash).put("direction",d)
                    .put("fast",fast.json()).put("certified",cert.json()).put("typedDerivation",proof);
            String file=source.getFileName().toString().replace(".als","")+"-"+d+".json";
            Files.writeString(out.resolve(file),receipt.toString(),StandardOpenOption.CREATE_NEW);
            rows.put(new JSONObject().put("receipt",file).put("sha256",hash).put("direction",d)
                    .put("fast",fast.distance()).put("certified",cert.distance()).put("typedReplay",true));
        }
    }
    public static void main(String[] args) throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);var rows=new JSONArray();
        var fixtures=new LinkedHashMap<String,String>();
        fixtures.put("head-partition","var sig A {} pred a {always ((eventually some A) and (after no A))} pred b {(eventually some A) until (after no A)}");
        fixtures.put("binary-wrap","var sig A {} pred a {(eventually some A) and (after no A)} pred b {(eventually some A) until (after no A)}");
        fixtures.put("binary-wrap-aligned","var sig A {} pred a {(after no A) and (eventually some A)} pred b {(after no A) until (eventually some A)}");
        fixtures.put("binary-head","var sig A {} pred a {(eventually some A) until (after no A)} pred b {(eventually some A) since (after no A)}");
        fixtures.put("identity-binary","var sig A {} pred a {(eventually some A) until (eventually no A)} pred b {(eventually some A) until (eventually no A)}");
        for(var f:fixtures.entrySet()) {
            Path source=out.resolve(f.getKey()+".als");Files.writeString(source,f.getValue(),StandardOpenOption.CREATE_NEW);run(source,out,rows);
        }
        for(int i=1;i<args.length;i++)run(Path.of(args[i]),out,rows);
        check(binaryUpdates>=4&&binaryInsertions>0&&splitInsertions>0,"nontrivial outgoing binary routing actually exercised");
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("rejectedCorruptions",rejected)
                .put("paidUpdates",paidUpdates).put("paidInsertions",paidInsertions).put("binaryUpdates",binaryUpdates)
                .put("binaryInsertions",binaryInsertions).put("splitInsertions",splitInsertions).put("directions",rows).put("queueCleared",false)
                .put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("PaidTemporalRoutingTest: "+checks+" checks, "+rejected+" rejected corruptions, "+rows.length()+" directions");
    }
}

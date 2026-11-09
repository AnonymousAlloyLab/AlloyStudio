package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Evidence-first owner/whole-phase coordinate pattern regression. */
public final class ImportedAciPatternTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable run,String why){checks++;try{run.run();}catch(IllegalArgumentException|IllegalStateException|JSONException expected){return;}throw new AssertionError("Accepted "+why);}
    private static String hash(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static String firstDifference(JSONArray a,JSONArray b) {
        for(int i=0;i<Math.max(a.length(),b.length());i++)
            if(i>=a.length()||i>=b.length()||!a.getJSONObject(i).similar(b.getJSONObject(i)))
                return "operation "+i+": "+a.opt(i)+" / "+b.opt(i);
        return "none";
    }
    private static List<Map<String,String>> endpoints(Canonical.Prepared p,RepairView view,JSONObject payment,String side) {
        var forms=CanonicalDistance.prepare(p.normalizedForms()).comparisonForms();
        var phases=BinaryPhaseEnumerationTest.phases(payment,side);var result=new ArrayList<Map<String,String>>();
        check(forms.size()==view.phases().size(),"source-linked phase coverage");
        for(int i=0;i<forms.size();i++) {
            var heads=new HashMap<String,String>();
            BinaryPhaseEnumerationTest.endpoint(forms.get(i).getMatrixEGraph(),view.phases().get(i).matrix(),forms.get(i),view.phases().get(i).bindings(),phases,"root",heads);
            result.add(heads);
        }return result;
    }
    static JSONObject declaration(JSONObject atom,boolean certified,Canonical.Prepared original,JSONObject payment,String side) {
        int phase,ordinal;NormalForm owner;
        if(certified) {
            var coordinate=new JSONArray(atom.getString("occurrence"));phase=coordinate.getInt(1);ordinal=coordinate.getInt(4);
            String path=BinaryPhaseEnumerationTest.phases(payment,side).get(phase).getString("sourcePath");
            owner=original.normalizedForms().stream().filter(f->f.getPhasePath().equals(path)).findFirst().orElseThrow();
        } else {
            var parts=atom.getString("occurrence").split("/");phase=Integer.parseInt(parts[1]);ordinal=Integer.parseInt(parts[3]);
            owner=CanonicalDistance.prepare(original.normalizedForms()).comparisonForms().get(phase);
        }
        var q=owner.getMatrixQuantiVars().get(ordinal);
        check(original.normalizedForms().stream().flatMap(f->f.getMatrixQuantiVars().stream()).anyMatch(v->v==q),"actual original declaration object");
        var tuple=new JSONArray().put(q.getQuantifier().name()).put(q.getTypeName()).put(q.getCardinality().name()).put(Integer.toString(q.getDisjointnessClass()));
        check(tuple.similar(atom.getJSONArray("tuple")),"unchanged typed declaration tuple");
        return new JSONObject().put("phasePath",owner.getPhasePath()).put("bindingPath",q.getBindingPath()).put("tuple",tuple).put("exactType",q.getExactAlloyType().stableString());
    }
    private static JSONArray declarations(NativeRepairTrace trace,boolean certified,Canonical.Prepared a,Canonical.Prepared b,JSONObject payment) {
        var rows=new JSONArray();
        for(Object value:trace.json().getJSONArray("nativeDeclarationPrograms"))for(Object item:((JSONObject)value).getJSONArray("operations")) {
            var step=new JSONObject(item.toString());
            if(!step.isNull("source"))step.put("source",declaration(step.getJSONObject("source"),certified,a,payment,"source"));
            if(!step.isNull("target"))step.put("target",declaration(step.getJSONObject("target"),certified,b,payment,"target"));
            if(step.getInt("cost")==0)step.put("payloadAction","checked-original-coordinate-transport");
            rows.put(step);
        }return rows;
    }
    static void forcedUnaryRouting(JSONObject cert,String expectedKind) {
        var links=cert.getJSONObject("selectedTemporalReferencePayments");
        if(links.isNull("typedDerivation"))throw new IllegalArgumentException("Missing required unary derivation");
        var derivation=links.getJSONObject("typedDerivation");
        var selected=cert.getJSONObject("nativeTemporalProgram").getJSONArray("operations");
        if(selected.length()!=1||derivation.getJSONArray("operations").length()!=1
                ||!selected.getJSONObject(0).getString("kind").equals(expectedKind)
                ||!selected.getJSONObject(0).getString("label").equals("ALWAYS")
                ||selected.getJSONObject(0).getInt("cost")!=1
                ||selected.getJSONObject(0).getInt("childCount")!=1
                ||!selected.getJSONObject(0).getJSONArray("path").similar(new JSONArray(expectedKind.equals("insert")?"[0]":"[0,0]"))
                ||(expectedKind.equals("insert")&&selected.getJSONObject(0).getInt("index")!=0)
                ||!selected.getJSONObject(0).similar(derivation.getJSONArray("operations").getJSONObject(0).getJSONObject("selectedEdit")))
            throw new IllegalArgumentException("Required selected unary edit changed");
        for(Object value:derivation.getJSONArray("operations")) {
            var op=(JSONObject)value;var edit=op.getJSONObject("selectedEdit");
            if(edit.getString("kind").equals("delete"))continue;
            // Requested imported reverse path inserts a unary ALWAYS at the
            // unary NONE root. Its routing choices are uniquely zero.
            if(!edit.getString("kind").equals("insert")||!edit.getString("label").equals("ALWAYS"))throw new IllegalArgumentException("Unexpected unary edit");
            JSONArray owner=op.getJSONArray("before");for(Object index:edit.getJSONArray("path"))owner=owner.getJSONArray(3).getJSONArray(((Number)index).intValue());
            if(!owner.getString(0).equals("NONE")||op.getInt("incomingRole")!=0
                    ||op.getJSONArray("childRoles").length()!=edit.getInt("childCount")||edit.getInt("childCount")!=1)
                throw new IllegalArgumentException("Required unary owner/child coverage changed");
            for(Object role:op.getJSONArray("childRoles"))if(((Number)role).intValue()!=0)throw new IllegalArgumentException("Wrong unary child role");
        }
    }
    private static JSONObject run(JSONObject identity,Path out,Integer expected,boolean compareActions)throws Exception {
        var pair=LocalOwnerTraceTest.prepare(identity);var rows=new JSONArray();
        for(int d=0;d<2;d++) {
            var a=pair.get(d);var b=pair.get(1-d);var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
            var cert=CanonicalRepairTraces.certified(ca,cb);check(cert.replay(cert.json())==cert.distance(),"shorter certified selected replay first");
            check(cert.json().getJSONObject("constructionDerivations").getBoolean("retained"),"separate retained source construction");
            var fast=CanonicalRepairTraces.fast(a,b);check(fast.replay(fast.json())==fast.distance(),"Fast selected replay");
            if(expected!=null)check(fast.distance()==expected&&cert.distance()==expected,"pattern cost "+identity.getString("id")+" "+fast.distance()+"/"+cert.distance());
            var data=new JSONObject().put("identity",identity).put("direction",d).put("fast",fast.json()).put("certified",cert.json());
            if(compareActions) {
                var payments=cert.json().getJSONObject("selectedTemporalReferencePayments");
                var ah=endpoints(a,ca.repairView(),payments,"source");var bh=endpoints(b,cb.repairView(),payments,"target");
                var fa=BinaryPhaseEnumerationTest.actions(fast.json(),ah,bh);var cc=BinaryPhaseEnumerationTest.actions(cert.json(),ah,bh);
                data.put("checkedFastMatrixActions",fa).put("checkedCertifiedMatrixActions",cc);
                Files.writeString(out.resolve(identity.getString("id")+"-"+d+"-attempt.json"),data.toString(),StandardOpenOption.CREATE_NEW);
                check(fa.similar(cc),"ordered typed original-coordinate matrix actions: "+firstDifference(fa,cc));
                check(declarations(fast,false,a,b,payments).similar(declarations(cert,true,a,b,payments)),"all paid and zero declaration actions");
                check(fast.json().getJSONObject("nativeTemporalProgram").similar(cert.json().getJSONObject("nativeTemporalProgram")),"selected temporal edits and zero reads");
                if(identity.getString("id").equals("binary-import")) {
                    String expectedKind=d==0?"delete":"insert";
                    forcedUnaryRouting(cert.json(),expectedKind);checks++;
                    for(String mode:List.of("missing","empty","selected","label","role","children","malformed","wrong-position","wrong-index")) {
                        var bad=cert.json();var derivation=bad.getJSONObject("selectedTemporalReferencePayments").getJSONObject("typedDerivation");
                        switch(mode) {
                            case "missing" -> bad.getJSONObject("selectedTemporalReferencePayments").put("typedDerivation",JSONObject.NULL);
                            case "empty" -> derivation.put("operations",new JSONArray());
                            case "selected" -> bad.getJSONObject("nativeTemporalProgram").put("operations",new JSONArray());
                            case "label" -> derivation.getJSONArray("operations").getJSONObject(0).getJSONObject("selectedEdit").put("label","EVENTUALLY");
                            case "role" -> {if(d==0)derivation.getJSONArray("operations").getJSONObject(0).getJSONObject("selectedEdit").put("kind","insert");else derivation.getJSONArray("operations").getJSONObject(0).put("incomingRole",1);}
                            case "children" -> {if(d==0)derivation.getJSONArray("operations").getJSONObject(0).getJSONObject("selectedEdit").put("cost",0);else derivation.getJSONArray("operations").getJSONObject(0).put("childRoles",new JSONArray().put(1));}
                            case "malformed" -> bad.getJSONObject("selectedTemporalReferencePayments").put("typedDerivation",new JSONObject());
                            case "wrong-position" -> {
                                bad.getJSONObject("nativeTemporalProgram").getJSONArray("operations").getJSONObject(0).put("path",new JSONArray().put(0).put(1));
                                derivation.getJSONArray("operations").getJSONObject(0).getJSONObject("selectedEdit").put("path",new JSONArray().put(0).put(1));
                            }
                            case "wrong-index" -> {
                                String field=d==0?"childCount":"index";int value=d==0?2:1;
                                bad.getJSONObject("nativeTemporalProgram").getJSONArray("operations").getJSONObject(0).put(field,value);
                                derivation.getJSONArray("operations").getJSONObject(0).getJSONObject("selectedEdit").put(field,value);
                            }
                        }
                        reject(()->forcedUnaryRouting(bad,expectedKind),"required unary premise "+mode);
                    }
                }
                data.put("orderedBlockCorrespondence",OrderedPhaseCorrespondenceTest.compare(fast,cert,a,b,payments,fa,cc,
                        declarations(fast,false,a,b,payments),declarations(cert,true,a,b,payments)));
                if(cert.json().getJSONObject("nativeTemporalProgram").getInt("cost")==0) {
                    for(Object value:payments.getJSONArray("referenceTransports"))
                        check(!((JSONObject)value).getString("kind").equals("selected-skeleton-reconstruction"),
                                "identity temporal program cannot redirect a reference");
                }
            } else check(fast.distance()>0&&cert.distance()>0,"ordered/typed semantic distinction retained");
            for(String field:List.of("typedOwner","selectedCorrespondence","matrixHead")) {
                var bad=cert.json();
                if(field.equals("typedOwner"))bad.getJSONObject("selectedTemporalReferencePayments").getJSONArray("source").getJSONObject(0).put("ownerPhase",999);
                else if(field.equals("selectedCorrespondence"))bad.getJSONObject("nativeTemporalProgram").put(field,new JSONArray());
                else bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0).getJSONObject("before").put("head","foreign");
                reject(()->cert.replay(bad),"certified original typed/selected evidence "+field);
            }
            for(String mode:List.of("phase","cost","owner","mapping","order")) {
                var bad=fast.json();
                if(mode.equals("phase")) {
                    if(!bad.has("nativePhaseRepresentationSteps"))continue;
                    bad.getJSONArray("nativePhaseRepresentationSteps").getJSONObject(0).getJSONArray("phases").getJSONObject(0).put("originalPhase",999);
                } else if(mode.equals("cost"))bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0).put("cost",999);
                else if(mode.equals("owner"))bad.getJSONArray("sourceBindingContexts").getJSONObject(0).put("bindings",new JSONArray().put("foreign"));
                else if(mode.equals("mapping"))bad.getJSONArray("phases").getJSONObject(0).put("alphaMapping",new JSONObject().put("foreign","_q0"));
                else bad.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");
                reject(()->fast.replay(bad),"source/selection evidence "+mode);
            }
            Files.writeString(out.resolve(identity.getString("id")+"-"+d+".json"),data.toString(),StandardOpenOption.CREATE_NEW);
            rows.put(new JSONObject().put("direction",d).put("fast",fast.distance()).put("certified",cert.distance()));
        }
        return new JSONObject().put("identity",identity).put("rows",rows);
    }
    public static void main(String[] args)throws Exception {
        run(args);
    }
    public static void run(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        if(args.length<4||!args[3].equals("target-only"))
            TypedTemporalDerivationTest.run(new String[]{out.resolve("retained").toString(),args[1]});
        var extracted=new JSONObject(Files.readString(Path.of(args[2])));var cases=new JSONArray();var seen=new HashSet<String>();
        for(Object value:extracted.getJSONArray("cases")) {
            var identity=((JSONObject)value).getJSONObject("identity");if(!seen.add(identity.getString("id")))continue;
            cases.put(run(identity,out,identity.getString("id").equals("binary-import")?13:0,true));
        }
        var variants=new LinkedHashMap<String,String>();
        variants.put("same-op-alpha","var sig A {var r:set A} pred a {(always(all x:A|no x.r)) and (always(some y:A|some y.r))} pred b {(always(some v:A|some v.r)) and (always(all u:A|no u.r))}");
        variants.put("or-order","var sig A {} pred a {(always some A) or (eventually no A)} pred b {(eventually no A) or (always some A)}");
        variants.put("ordered-until","var sig A {} pred a {(always some A) until (eventually no A)} pred b {(eventually no A) until (always some A)}");
        variants.put("different-field","var sig A {var r,s:set A} pred a {always(all x:A|no x.r)} pred b {always(all x:A|no x.s)}");
        for(var entry:variants.entrySet()) {
            Path file=out.resolve(entry.getKey()+".als");Files.writeString(file,entry.getValue(),StandardOpenOption.CREATE_NEW);
            var identity=new JSONObject().put("id",entry.getKey()).put("source",file.toString()).put("sha256",hash(file)).put("left","a").put("right","b");
            boolean equal=entry.getKey().equals("same-op-alpha")||entry.getKey().equals("or-order");
            cases.put(run(identity,out,equal?0:null,equal));
        }
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("cases",cases).put("queueCleared",false)
                .put("paidBinaryRoutingPremise","OPEN").put("publicCertification","BLOCKED").toString(),StandardOpenOption.CREATE_NEW);
        System.out.println("ImportedAciPatternTest: "+checks+" direct checks plus retained typed/family checks");
    }
}

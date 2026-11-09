package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import parser.ast.nodes.ModelUnit;

/** Frozen same-identity baseline/candidate test; no existing test main calls. */
public final class LocalOwnerTraceTest {
    /** Test-package bridge retaining the supplied decisions; does not capture again. */
    public static NativeOperationComparison.Result compareSelected(Canonical.Prepared a,Canonical.Prepared b,
            NativeRepairTrace fast,NativeRepairTrace certified) {
        return NativeOperationComparison.compare(a,b,fast,certified);
    }
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable action,String why){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Accepted "+why);}
    private static String hash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    static List<Canonical.Prepared> prepare(JSONObject row)throws Exception {
        var text=Files.readString(Path.of(row.getString("source")));check(hash(text).equals(row.getString("sha256")),"Frozen source hash");
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(row.getString("left"),row.getString("right"))){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return ps;
    }
    private static void add(JSONArray rows,String id,Path source,String left,String right)throws Exception {
        rows.put(new JSONObject().put("id",id).put("source",source.toAbsolutePath().toString()).put("sha256",hash(Files.readString(source))).put("left",left).put("right",right));
    }
    static JSONObject family(Path out)throws Exception {
        Files.createDirectories(out);var rows=new JSONArray();var source=Path.of("classified-data/classroom_fol/both/2vEm4FQHNpQECkJwp_inv12.als");var text=Files.readString(source);
        check(hash(text).equals("9f00099c6f1ed752d021cffd77581cef82607524e827402db074d82faf10d88f"),"Actual incident source");add(rows,"original",source,"inv12","inv12c");
        var variants=new LinkedHashMap<String,String>();
        variants.put("renamed",text.replace("g:Group, c:Class, s:Student","h:Group, k:Class, u:Student").replace("t->c in Teaches  implies c->s->g in Groups","t->k in Teaches  implies k->u->h in Groups"));
        variants.put("ordered",text.replace("g:Group, c:Class, s:Student","s:Student, g:Group, c:Class"));
        variants.put("grouped",text.replace("g:Group, c:Class, s:Student","g,h:Group, c:Class, s:Student"));
        variants.put("dependent",text.replace("g:Group, c:Class, s:Student","g:Group, c:Class, s:Student & c.Groups.g"));
        variants.put("identical",text.replace("all x:Teacher | some x.Teaches.Groups","all t: Teacher | some g:Group, c:Class, s:Student | t->c in Teaches implies c->s->g in Groups"));
        for(var e:variants.entrySet()){var p=out.resolve(e.getKey()+".als");Files.writeString(p,e.getValue(),StandardOpenOption.CREATE_NEW);add(rows,e.getKey(),p,"inv12","inv12c");}
        for(var r:List.of(new String[]{"coursesOld/both/7XiqRnyXeoftBf2No_inv15","inv15"},new String[]{"coursesNew/both/5vxYh7NCpEPMmC47p_inv14","inv14"},new String[]{"coursesNew/under/dcxEP6ZgQAktuDbeT_inv10","inv10"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12"},new String[]{"classroom_fol/both/SASEHD8jPo2HgsuoJ_inv14","inv14"},new String[]{"coursesOld/both/xJnitBpFv9K74WNvf_inv14","inv14"},new String[]{"coursesNew/correct/wnqMPtpNxnPYzru3g_inv14","inv14"},new String[]{"coursesNew/both/LJrfWwJdKiTdyytci_inv14","inv14"}))add(rows,r[0],Path.of("classified-data",r[0]+".als"),r[1],r[1]+"c");
        var result=new JSONObject().put("cases",rows);Files.writeString(out.resolve("family.json"),result.toString(2),StandardOpenOption.CREATE_NEW);return result;
    }
    private static List<EGraphNode> nodes(EGraphNode root,EGraphNode.Opcode opcode){var out=new ArrayList<EGraphNode>();var q=new ArrayDeque<EGraphNode>();q.add(root);while(!q.isEmpty()){var n=q.remove();if(n.getOpcode()==opcode)out.add(n);q.addAll(n.getChildren());}return out;}
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static String path(String nativePath){return nativePath.replaceAll("/alternative/[0-9]+","").replace("root","$").replace("/child/","/");}
    static JSONArray crud(JSONObject trace,JSONObject bridge,boolean fast) {
        var paths=new ArrayList<Map<String,String>>();var heads=new ArrayList<Map<String,String>>();
        for(Object e:bridge.getJSONArray("constructionEndpoints")){var map=new HashMap<String,String>();var labels=new HashMap<String,String>();for(Object v:((JSONObject)e).getJSONArray("matrixEndpoints")){var n=(JSONObject)v;map.put(fast?n.getString("fastPath"):n.getString("certifiedPath"),n.getString("certifiedPath"));labels.put(n.getString("certifiedPath"),n.getString("typedCoordinateHead"));}paths.add(map);heads.add(labels);}
        var result=new JSONArray();
        for(Object p:trace.getJSONArray("phases"))for(Object item:((JSONObject)p).getJSONObject("program").getJSONArray("operations")) {
            var op=(JSONObject)item;String a=op.isNull("source")?null:paths.get(0).get(path(op.getString("source")));String b=op.isNull("target")?null:paths.get(1).get(path(op.getString("target")));
            check(op.isNull("source")||a!=null,"CRUD source occurrence has checked typed bridge");check(op.isNull("target")||b!=null,"CRUD target occurrence has checked typed bridge");
            // Raw READ versus coordinate-alignment labels differ because the two
            // native payload formats retain different provenance. Preserve both
            // raw programs and compare their checked typed endpoints, not strings.
            String kind=a==null?"insert":b==null?"delete":op.getInt("cost")==0?"zero-transport":"update";
            result.put(new JSONObject().put("kind",kind).put("source",a==null?JSONObject.NULL:a).put("target",b==null?JSONObject.NULL:b)
                    .put("before",a==null?JSONObject.NULL:heads.get(0).get(a)).put("after",b==null?JSONObject.NULL:heads.get(1).get(b)).put("cost",op.getInt("cost")));
        }
        return result;
    }
    static JSONObject sameExecution(JSONObject before,JSONObject after) {
        var a=new JSONObject(before.toString());var b=new JSONObject(after.toString());
        var provenanceExtensions=new JSONArray();
        retainCaptureExtension(a,provenanceExtensions);retainCaptureExtension(b,provenanceExtensions);
        var forward=new HashMap<Long,Long>();var inverse=new HashMap<Long,Long>();
        var transport=new JSONArray();
        var ap=a.getJSONArray("phases");var bp=b.getJSONArray("phases");check(ap.length()==bp.length(),"same native phase count");
        for(int phase=0;phase<ap.length();phase++) {
            var ao=ap.getJSONObject(phase).getJSONObject("program").getJSONArray("operations");var bo=bp.getJSONObject(phase).getJSONObject("program").getJSONArray("operations");check(ao.length()==bo.length(),"same CRUD count");
            for(int i=0;i<ao.length();i++)for(String side:List.of("before","after")) {
                var x=ao.getJSONObject(i);var y=bo.getJSONObject(i);check(x.isNull(side)==y.isNull(side),"same insertion/deletion endpoints");if(x.isNull(side))continue;
                var xe=new JSONObject(x.getJSONObject(side).getString("nativeEvidence"));var ye=new JSONObject(y.getJSONObject(side).getString("nativeEvidence"));
                for(String key:List.of("sourceOccurrenceLineage","certificationOccurrenceLineage","callOccurrence"))if(xe.has(key)) {
                    long old=xe.getLong(key),now=ye.getLong(key);
                    if(old<0){check(old==now,"negative occurrence sentinel unchanged");continue;}
                    var prior=forward.putIfAbsent(old,now);var back=inverse.putIfAbsent(now,old);
                    check((prior==null||prior==now)&&(back==null||back==old),"fresh-run occurrence transport is bijective");
                    if(prior==null)transport.put(new JSONObject().put("old",old).put("current",now).put("phase",phase).put("operation",i).put("side",side));
                    xe.put(key,now);
                }
                check(xe.similar(ye),"source/type/owner/call payload unchanged under occurrence transport");
                x.getJSONObject(side).put("nativeEvidence",xe.toString());
            }
        }
        // Construction DAGs retain each parse's actual occurrence identities;
        // each is checked independently. They are not repair operations.
        a.remove("constructionDerivations");b.remove("constructionDerivations");
        if(a.getString("metricVersion").equals("typed-fast-distance-v34-retained-native-decisions")
                &&b.getString("metricVersion").equals("typed-fast-distance-v35-original-owner-phase-order"))
            a.put("metricVersion",b.getString("metricVersion"));
        if(!a.getString("metricVersion").equals(b.getString("metricVersion"))) {
            check(List.of("certified-fast-rewrite-repair-distance-v18-retained-native-decisions",
                            "certified-fast-rewrite-repair-distance-v19-temporal-edge-charge",
                            "certified-fast-rewrite-repair-distance-v21-typed-temporal-payment",
                            "certified-fast-rewrite-repair-distance-v22-typed-skeleton-reconstruction",
                            "certified-fast-rewrite-repair-distance-v23-executed-typed-splice",
                            "certified-fast-rewrite-repair-distance-v24-no-identity-edge-redirection").contains(a.getString("metricVersion"))
                    && List.of("certified-fast-rewrite-repair-distance-v22-typed-skeleton-reconstruction",
                            "certified-fast-rewrite-repair-distance-v23-executed-typed-splice",
                            "certified-fast-rewrite-repair-distance-v24-no-identity-edge-redirection",
                            "certified-fast-rewrite-repair-distance-v25-retained-phase-representation").contains(b.getString("metricVersion")),
                    "explicit certified metric version transition");
            a.put("metricVersion",b.getString("metricVersion"));
        }
        if(!a.has("selectedTemporalReferencePayments")&&b.has("selectedTemporalReferencePayments")) {
            var p=b.getJSONObject("selectedTemporalReferencePayments");
            check(p.getInt("temporalCost")==0&&p.getJSONArray("selectedCorrespondence").similar(new JSONArray("[[1,1]]")),"only one zero temporal root in prior non-temporal family");
            for(String side:List.of("source","target"))check(p.getJSONArray(side).length()==1
                    &&p.getJSONArray(side).getJSONObject(0).getString("operator").equals("NONE"),"new occurrence metadata does not hide a temporal choice");
            b.remove("selectedTemporalReferencePayments");
        }
        if(a.has("selectedTemporalReferencePayments")&&b.has("selectedTemporalReferencePayments")) {
            var x=a.getJSONObject("selectedTemporalReferencePayments");var y=b.getJSONObject("selectedTemporalReferencePayments");
            if(!x.has("typedDerivation")&&y.has("typedDerivation")) {
                check(y.isNull("typedDerivation")&&y.isNull("typedDerivationFailure")
                        &&y.getInt("temporalCost")==0&&y.getJSONArray("referenceTransports").isEmpty()
                        &&y.getJSONArray("selectedCorrespondence").similar(new JSONArray("[[1,1]]")),
                        "only absent derivation in unchanged root-only temporal program");
                x.put("typedDerivation",JSONObject.NULL).put("typedDerivationFailure",JSONObject.NULL);
            }
            if(!x.getString("schema").equals(y.getString("schema"))) {
                check(x.getString("schema").equals("selected-temporal-payment-v2-typed")
                        &&y.getString("schema").equals("selected-temporal-payment-v3-skeleton-reconstruction"),"explicit typed receipt schema transition");
                for(var p:List.of(x,y)) {
                    check(p.getInt("temporalCost")==0&&p.getJSONArray("referenceTransports").isEmpty()
                            &&p.getJSONArray("selectedCorrespondence").similar(new JSONArray("[[1,1]]")),"no temporal operation hidden by metadata transition");
                    for(String side:List.of("source","target"))check(p.getJSONArray(side).length()==1
                            &&p.getJSONArray(side).getJSONObject(0).getString("operator").equals("NONE"),"original non-temporal root only");
                }
                x.put("schema",y.getString("schema"));x.put("reconstructionPremise",y.getString("reconstructionPremise"));
            }
        }
        check(a.similar(b),"all selected numeric/phase/declaration/matrix operations unchanged under checked fresh-run occurrence bijection");
        return new JSONObject().put("occurrenceBijection",transport).put("independentConstructionEvidenceRetained",after.has("constructionDerivations"))
                .put("checkedCaptureExtensions",provenanceExtensions);
    }
    // Compare the old receipt schema exactly; preserve and check the additive
    // identity links separately. Actual native replay is checked by the caller.
    private static void retainCaptureExtension(JSONObject trace,JSONArray retained) {
        var payment=trace.optJSONObject("selectedTemporalReferencePayments");
        var phases=trace.getJSONArray("phases");
        for(int phase=0;phase<phases.length();phase++) {
            var program=phases.getJSONObject(phase).getJSONObject("program");
            if(!program.has("sourceObjects"))continue;
            var operations=(JSONArray)program.remove("sourceObjects");
            check(operations.length()==program.getJSONArray("operations").length(),"capture covers exact selected operations");
            for(int index=0;index<operations.length();index++)for(String side:List.of("before","after")) {
                var operation=operations.getJSONObject(index);if(operation.isNull(side))continue;
                check(operation.getInt("operation")==index,"capture preserves selected operation ordinal");
                var evidence=operation.getJSONObject(side);
                if(evidence.has("temporalReferenceAuthority")) {
                    retained.put(new JSONObject().put("phase",phase).put("operation",index).put("side",side)
                            .put("fastAuthority",evidence.remove("temporalReferenceAuthority")));
                }
                else {
                    var binding=evidence;
                        check(binding.getString("status").equals("BOUND_ACTUAL_OBJECTS"),"new selected source identity is bound");
                        check(payment!=null&&binding.getInt("paymentObject")==payment.getInt("captureObject"),"new identity belongs to selected payment");
                        check(binding.getLong("authorityId")>0&&binding.getLong("parserOccurrence")>0&&binding.getLong("sourceOccurrence")>0,"positive original source authority");
                        boolean found=false;
                        for(String direction:List.of("source","target")) {
                            var links=binding.getJSONArray(direction+"Endpoints");
                            for(Object value:links) {
                                var link=(JSONObject)value;
                                check(binding.getInt("ownerObject")==link.getInt("ownerObject")
                                        &&binding.getJSONArray("branchObjects").similar(link.getJSONArray("branchObjects")),"new link preserves owner and ordered branch objects");
                                var actual=payment.getJSONArray(direction).getJSONObject(link.getInt("postorder")-1);
                                check(actual.similar(link),"new link is exact selected temporal occurrence");
                                check(payment.getJSONArray(direction+"MatrixReferences").getJSONArray(phase).toList().contains(binding.getInt("matrixObject")),"new link belongs to actual phase membership");
                                found=true;
                            }
                        }
                        check(found,"new identity has a selected endpoint");
                        retained.put(new JSONObject().put("phase",phase).put("operation",index).put("side",side).put("binding",binding));
                }
            }
        }
        if(payment!=null&&payment.has("captureObject")) {
            retained.put(new JSONObject().put("payment",new JSONObject(payment.toString())));
            payment.remove("captureObject");payment.remove("sourceMatrixReferences");payment.remove("targetMatrixReferences");
            for(String side:List.of("source","target"))for(Object value:payment.getJSONArray(side)) {
                var endpoint=(JSONObject)value;
                endpoint.remove("temporalObject");endpoint.remove("ownerObject");endpoint.remove("branchObjects");
            }
        }
    }
    private static void corrupt(Canonical.Prepared a,Canonical.Prepared b,NativeRepairTrace ft,NativeRepairTrace ct,JSONObject bridge) {
        var source=a.normalizedForms().get(0);
        for(var change:List.<Consumer<EGraphNode>>of(
                r->field(nodes(r,EGraphNode.Opcode.EXISTS).get(0),"sourceOccurrenceLineage",-1L),
                r->field(nodes(r,EGraphNode.Opcode.GENERICRELDECL).get(0),"sourceOccurrenceLineage",-1L),
                r->field(nodes(r,EGraphNode.Opcode.GENERICRELDECL).get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),
                r->field(nodes(r,EGraphNode.Opcode.GENERICRELDECL).get(0).getChildren().get(1),"alphaName","@fast-local:9:9"),
                r->field(nodes(r,EGraphNode.Opcode.GENERICRELDECL).get(0).getChildren().get(1),"exactAlloyType",ExactAlloyType.unaryRelation("Group")),
                r->{var n=nodes(r,EGraphNode.Opcode.EXISTS).get(0);var cs=new ArrayList<>(n.getChildClasses());Collections.swap(cs,0,1);field(n,"childClasses",cs);})) {
            var root=CanonicalDistance.prepare(a.normalizedForms()).comparisonForms().get(0).getMatrixEGraph();change.accept(root);reject(()->LocalOwnerCorrespondence.check(source,root),"owner/declaration/domain/tuple/type/order corruption");
        }
        for(var trace:List.of(ft,ct)) {
            for(String mode:List.of("cost","source","payload","premise","order")) {
                var bad=trace.json();var op=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0);
                switch(mode){case "cost"->op.put("cost",99);case "source"->op.put("source","foreign");case "payload"->op.getJSONObject("before").put("nativeEvidence","foreign owner/type/call");case "premise"->op.put("premise","foreign");case "order"->bad.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");}
                reject(()->trace.replay(bad),"immutable selected program "+mode);
            }
            check(trace.replay(trace.json())==30,"mutated copies do not alter native authority");
        }
        var bad=new JSONObject(bridge.getJSONObject("diagnostic").toString());bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");check(NativeOperationComparison.compareCaptured(a,b,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"foreign endpoint source rejected");
    }
    private static void corruptCall(NativeRepairTrace fast,NativeRepairTrace certified) {
        var bad=fast.json();boolean found=false;
        for(Object p:bad.getJSONArray("phases"))for(Object value:((JSONObject)p).getJSONObject("program").getJSONArray("operations")) {
            var op=(JSONObject)value;
            if(!op.isNull("before")&&new JSONArray(op.getJSONObject("before").getString("head")).getString(0).equals("CALL")) {
                var evidence=new JSONObject(op.getJSONObject("before").getString("nativeEvidence"));evidence.put("callOccurrence",-99).put("semanticIdentity","foreign/module/call");op.getJSONObject("before").put("nativeEvidence",evidence.toString());found=true;break;
            }
        }
        check(found,"actual retained call operation present");reject(()->fast.replay(bad),"call occurrence/callee substitution");
        var proof=certified.json();var calls=proof.getJSONObject("constructionDerivations").getJSONObject("source").getJSONArray("callPremises");
        check(!calls.isEmpty(),"actual retained call construction premise");calls.getJSONObject(0).put("callee","foreign/module/call");reject(()->certified.replay(proof),"immutable construction call-premise substitution");
    }
    public static void main(String[] args)throws Exception {
        var out=Path.of(args[0]);Files.createDirectories(out);boolean baseline=args.length>1&&args[1].equals("baseline");
        var family=args.length>2?new JSONObject(Files.readString(Path.of(args[2]))):family(out.resolve("inputs"));var results=new JSONArray();int index=0;
        for(Object value:family.getJSONArray("cases")){var row=(JSONObject)value;var ps=prepare(row);
            for(int direction=0;direction<2;direction++){var a=ps.get(direction);var b=ps.get(1-direction);var ft=CanonicalRepairTraces.fast(a,b);var ct=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                check(ft.replay(ft.json())==ft.distance()&&ct.replay(ct.json())==ct.distance(),"both actual native endpoint executions");
                var result=NativeOperationComparison.compare(a,b,ft,ct);var e=result.evidence();var record=new JSONObject().put("identity",row).put("direction",direction).put("fast",ft.json()).put("certified",ct.json()).put("comparison",e);
                String name="row-"+index+"-"+direction+".json";
                if(!baseline) {
                    // Preserve actual first failure before assertions, never hide
                    // new shape/operation disagreement behind a total comparison.
                    Files.writeString(out.resolve(name),record.toString(),StandardOpenOption.CREATE_NEW);
                    if(row.getString("id").equals("dependent")) {
                        check(ft.distance()==40&&ct.distance()==40,"dependent domain is constructed in its original prefix");
                        check(result.status()==NativeOperationComparison.Status.MATCHED,"dependent domain action correspondence");
                        check(crud(ft.json(),e,true).similar(crud(ct.json(),e,false)),"dependent expanded ordered CRUD correspondence");
                        check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"dependent paid and zero actions");
                        var f=ps.get(0).normalizedForms().get(0);
                        var links=LocalOwnerCorrespondence.check(f,CanonicalDistance.prepare(ps.get(0).normalizedForms()).comparisonForms().get(0).getMatrixEGraph()).json();
                        check(links.getJSONArray("owners").getJSONObject(0).getJSONArray("orderedDeclarations").length()==3,"dependent declaration ownership and previous-slot dependencies checked");
                        Files.writeString(out.resolve("dependent-"+direction+".json"),new JSONObject().put("identity",row).put("ownerCorrespondence",links).put("comparison",e).put("status","NATIVE_MATCHED_PUBLIC_BLOCKED").toString(),StandardOpenOption.CREATE_NEW);
                    } else {
                        check(result.status()==NativeOperationComparison.Status.MATCHED,row.getString("id")+" "+direction+" "+result.reason()+" "+e.opt("error"));
                        var fa=crud(ft.json(),e,true);var ca=crud(ct.json(),e,false);
                        Files.writeString(out.resolve("crud-"+index+"-"+direction+".json"),new JSONObject().put("fast",fa).put("certified",ca).toString(),StandardOpenOption.CREATE_NEW);
                        check(fa.similar(ca),"expanded ordered native CRUD correspondence "+row.getString("id"));
                        check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"paid/zero declaration and matrix action ordering");
                    }
                    if(args.length>3){var old=new JSONObject(Files.readString(Path.of(args[3]).resolve(name)));check(old.getJSONObject("identity").similar(row),"same-identity family transition");var transport=new JSONObject();for(String arm:List.of("fast","certified")) {
                        if(row.getString("id").equals("dependent")&&arm.equals("certified")) {
                            check(old.getJSONObject(arm).getInt("distance")==41&&record.getJSONObject(arm).getInt("distance")==40,"explicit dependent certificate path repair");
                            transport.put(arm,"original domain chain certificate added; full old/new receipts retained");
                        } else transport.put(arm,sameExecution(old.getJSONObject(arm),record.getJSONObject(arm)));
                    }Files.writeString(out.resolve("transport-"+index+"-"+direction+".json"),transport.toString(),StandardOpenOption.CREATE_NEW);}
                    if(index==0){check(ft.distance()==30&&ct.distance()==30,"actual30 unchanged");if(direction==0){var groups=e.getJSONArray("constructionEndpoints").getJSONObject(0).getJSONObject("localOwnerCorrespondence").getJSONArray("owners").getJSONObject(0).getJSONArray("orderedDeclarations");check(groups.length()==3,"three original declaration groups, not a grouped tuple surrogate");corrupt(a,b,ft,ct,e);}}
                    if(row.getString("id").contains("7Xiq")&&direction==0){check(ft.distance()==51&&ct.distance()==51,"7Xiq51 preserved");corruptCall(ft,ct);}
                    check(!e.getBoolean("complete")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"public boundary remains blocked");
                }else Files.writeString(out.resolve(name),record.toString(),StandardOpenOption.CREATE_NEW);
                results.put(new JSONObject().put("id",row.getString("id")).put("direction",direction).put("status",result.status()).put("reason",result.reason()).put("fast",ft.distance()).put("certified",ct.distance()));
                System.out.println(row.getString("id")+" "+direction+" "+result.status()+" "+ft.distance()+"/"+ct.distance());
            }index++;
        }
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("baseline",baseline).put("rows",results).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("LocalOwnerTraceTest: "+checks+" checks");
    }
}

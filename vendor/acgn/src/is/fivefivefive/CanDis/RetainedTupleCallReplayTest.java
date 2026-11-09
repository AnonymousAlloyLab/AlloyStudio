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

/** Same-identity retained tuple/call traces; baseline mode uses the frozen JAR. */
public final class RetainedTupleCallReplayTest {
    private static int checks;
    private record Pair(Canonical.Prepared a,Canonical.Prepared b){Pair reverse(){return new Pair(b,a);}}
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException e){return;}throw new AssertionError(why);}
    private static Pair prepare(JSONObject row)throws Exception {
        byte[] bytes=Files.readAllBytes(Path.of(row.getString("source")));
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(row.getString("sha256")),"frozen family source hash");
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));var ps=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(row.getString("left"),row.getString("right"))){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(ps.get(0),ps.get(1));
    }
    private static Pair prepareText(String text,String left,String right)throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var ps=new ArrayList<Canonical.Prepared>();
        for(String name:List.of(left,right)){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);ps.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return new Pair(ps.get(0),ps.get(1));
    }
    private static JSONObject selection(JSONObject d){if(d.getString("kind").equals("select"))return d;var cs=d.optJSONArray("children");if(cs!=null)for(Object o:cs){var found=selection(((JSONObject)o).getJSONObject("edit"));if(found!=null)return found;}return null;}
    private static int[] mapping(JSONArray raw){int[] out=new int[raw.length()];for(int i=0;i<out.length;i++)out[i]=raw.getInt(i);return out;}
    private static List<String> orderedNames(JSONArray rows,List<String> names){var out=new ArrayList<String>();for(Object o:rows)out.add(names.get(new JSONArray(((JSONObject)o).getString("occurrence")).getInt(4)));return out;}
    private static void shorter(Pair p,JSONObject cert){
        var a=NestedPaidDeclarationReplayTest.checkedProjection(p.a,p.b);var b=NestedPaidDeclarationReplayTest.checkedProjection(p.b,p.a);
        var phase=cert.getJSONArray("phases").getJSONObject(0);var map=mapping(phase.getJSONArray("alphaMapping"));
        check(QuotientRepairDistance.verifyFixedMatrixReplay(a,b,map,map,phase.getJSONObject("receipt"))==49,"shorter selected matrix49 independently replayed");
        var an=p.a.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var bn=p.b.normalizedForms().get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();var links=new TreeMap<String,String>();for(int i=0;i<map.length;i++)if(map[i]>=0)links.put(an.get(i),bn.get(map[i]));
        var r=cert.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
        var aa=NestedPaidDeclarationReplayTest.atoms(a.phases().get(0),r.getJSONArray("source"));var bb=NestedPaidDeclarationReplayTest.atoms(b.phases().get(0),r.getJSONArray("target"));
        check(new DeclarationMatrixLink(orderedNames(r.getJSONArray("source"),an),orderedNames(r.getJSONArray("target"),bn),links,Set.copyOf(an),Set.copyOf(bn)).verify(aa,bb,r)==2,"sorted actions linked through original ordinals, disjoint updates2");
        reject(()->new DeclarationMatrixLink(an,bn,links,Set.copyOf(an),Set.copyOf(bn)).verify(aa,bb,r),"source-order substitution cannot masquerade as action order");
    }
    private static void traceLinks(JSONObject e){
        var endpoints=e.getJSONArray("constructionEndpoints");check(endpoints.length()==2,"construction endpoints distinct from edit list");
        var paths=new ArrayList<Set<String>>();
        for(Object o:endpoints){var endpoint=(JSONObject)o;check(endpoint.getBoolean("proofsCheckedInProcess")&&endpoint.getBoolean("constructionIsNotRepair")&&!endpoint.getBoolean("wireReplayChecked"),"construction scope exact");var rows=endpoint.getJSONArray("matrixEndpoints");var set=new HashSet<String>();for(Object n:rows){var v=(JSONObject)n;check(set.add(v.getString("certifiedPath"))&&v.has("sourceOccurrenceLineage")&&v.has("typedCoordinateHead"),"typed occurrence-path bijection");}paths.add(set);var ids=new HashSet<Integer>();for(Object n:endpoint.getJSONArray("structuralKeys"))check(ids.add(((JSONObject)n).getInt("id")),"unique DAG key id");for(Object n:endpoint.getJSONArray("structuralKeys"))for(Object child:((JSONObject)n).getJSONArray("children"))check(ids.contains((Integer)child),"retained proof key child");check(endpoint.getJSONArray("constructionProofs").length()>0,"actual construction premises");}
        for(Object o:e.getJSONArray("fastActions")){var a=(JSONObject)o;if(!a.getString("stage").equals("matrix"))continue;check(a.isNull("source")||paths.get(0).contains(a.getString("source")),"action before typed construction endpoint");check(a.isNull("target")||paths.get(1).contains(a.getString("target")),"action after typed construction endpoint");}
    }
    private static List<EGraphNode> nodes(EGraphNode root,EGraphNode.Opcode op){var out=new ArrayList<EGraphNode>();var q=new ArrayDeque<EGraphNode>();var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());q.add(root);while(!q.isEmpty()){var n=q.remove();if(!seen.add(n))continue;if(seen.size()>4096)throw new AssertionError("test budget");if(n.getOpcode()==op)out.add(n);q.addAll(n.getChildren());}return out;}
    private static void field(Object o,String name,Object value){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException x){throw new AssertionError(x);}}
    private static EGraphNode call(NormalForm f){return nodes(f.getMatrixEGraph(),EGraphNode.Opcode.CALL).get(0);}
    private static void corruptions(Pair p,JSONObject e){
        check(e.getJSONObject("certifiedRepresentationSelections").getJSONArray("source").length()==1,"actual certified local representative selection retained");
        var badChoice=new JSONObject(e.getJSONObject("diagnostic").toString());selection(badChoice.getJSONObject("certified").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").getJSONObject("derivation")).put("leftAlternative",999);
        check(NativeOperationComparison.compareCaptured(p.a,p.b,badChoice).status()==NativeOperationComparison.Status.INVALID_REPLAY,"unproved selected representative rejected");
        var coordinateRows=e.getJSONArray("constructionEndpoints").getJSONObject(0).getJSONArray("bindings");boolean nonidentity=false;
        for(Object o:coordinateRows){var b=(JSONObject)o;nonidentity|=b.getInt("bindingVectorIndex")!=b.getInt("canonicalSlotCoordinate");}check(nonidentity,"actual vector and canonical slot orders differ");
        var form=p.a.normalizedForms().get(0);check(!FastLocalPaidScope.checkedCalls(form).isEmpty(),"original retained tuple/call checked");
        for(var change:List.<Consumer<NormalForm>>of(f->field(call(f),"callOccurrenceId",999L),f->field(call(f),"semanticIdentity","other/module/prevs"),f->field(call(f),"callArityAuthority","DECLARATION"),f->field(call(f).getChildren().get(0),"sourceOccurrenceLineage",-1L),f->field(call(f).getChildren().get(0),"alphaName","_q0"),f->field(call(f).getChildren().get(0),"exactAlloyType",ExactAlloyType.unaryRelation("Grade")),f->{var n=nodes(f.getMatrixEGraph(),EGraphNode.Opcode.GENERICRELDECL).get(0);var cs=new ArrayList<>(n.getChildClasses());Collections.swap(cs,1,2);field(n,"childClasses",cs);},f->field(nodes(f.getMatrixEGraph(),EGraphNode.Opcode.GENERICRELDECL).get(0),"sourceOccurrenceLineage",-1L))){var f=CanonicalDistance.prepare(p.a.normalizedForms()).comparisonForms().get(0);change.accept(f);check(FastLocalPaidScope.checkedCalls(f).isEmpty(),"corrupt call/owner/order/type/slot rejected");}
        for(int i=0;i<3;i++){var bad=new JSONObject(e.getJSONObject("diagnostic").toString());if(i==0)bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");if(i==1)bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",48);if(i==2)bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).put("admission","foreign");check(NativeOperationComparison.compareCaptured(p.a,p.b,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"corrupt source/cost/declaration receipt");}
        var wrongSlot=new JSONObject(e.getJSONObject("diagnostic").toString());var atom=wrongSlot.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0);var occurrence=new JSONArray(atom.getString("occurrence"));occurrence.put(3,occurrence.getInt(4));atom.put("occurrence",occurrence.toString());check(NativeOperationComparison.compareCaptured(p.a,p.b,wrongSlot).status()==NativeOperationComparison.Status.INVALID_REPLAY,"vector/ordinal cannot replace canonical slot evidence");
        var huge=new JSONObject(e.getJSONObject("diagnostic").toString()).put("extra","x".repeat(4_000_001));var resource=NativeOperationComparison.compareCaptured(p.a,p.b,huge);check(resource.status()==NativeOperationComparison.Status.UNCHECKABLE&&resource.evidence().has("receiptRetentionBlocker"),"retention budget fails explicitly, not match or unbounded serialization");
    }
    private static void consumers(Path out)throws Exception {
        Files.createDirectories(out);var samples=new LinkedHashMap<String,Pair>();
        String declared="sig A {r:set A} fun f[x,y:A]:set A {x+y} pred b {some A} pred a {all x:A | x in x.r implies (one y:A | x in f[y,x])}";
        String imported="open util/ordering[A] sig A {r:set A} pred b {some A} pred a {all x:A | x in x.r implies (one y:A | x in max[y])}";
        samples.put("declared-local",prepareText(declared,"a","b"));samples.put("imported-local",prepareText(imported,"a","b"));
        samples.put("grouped-native",prepareText("sig A { r:set A } pred inv { all x:A | some x.r implies (one y,z:A | y in x.r and z in y.r) } pred invc { some A }","inv","invc"));
        for(var row:List.of(new String[]{"mixed","classroom_fol/both/p3hJuKvQ3SpwKDZHa_inv12","inv12"},new String[]{"erasure","coursesNew/under/dcxEP6ZgQAktuDbeT_inv10","inv10"}))samples.put(row[0],prepareText(Files.readString(Path.of("classified-data",row[1]+".als")),row[2],row[2]+"c"));
        for(var entry:samples.entrySet())for(int direction=0;direction<2;direction++) {
            var p=direction==0?entry.getValue():entry.getValue().reverse();var result=NativeOperationComparison.compare(p.a,p.b,CanonicalAlloyPipeline.prepare(p.a),CanonicalAlloyPipeline.prepare(p.b));
            Files.writeString(out.resolve(entry.getKey()+"-"+direction+".json"),result.evidence().toString(2),StandardOpenOption.CREATE_NEW);
            if(entry.getKey().endsWith("local")){
                check(!FastLocalPaidScope.checkedCalls(entry.getValue().a.normalizedForms().get(0)).isEmpty(),"source-linked previously excluded local call");
                var supports=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment").getDeclaredMethod("supports",List.class);supports.setAccessible(true);
                check((boolean)supports.invoke(null,entry.getValue().a.normalizedForms()),"exact prior consumer admission, not just call census");
            }
            check(result.status()==NativeOperationComparison.Status.MATCHED,"affected consumer original-coordinate replay "+entry.getKey()+" "+result.evidence().opt("error"));
            check(result.evidence().getJSONArray("fastActions").similar(result.evidence().getJSONArray("certifiedActions")),"consumer ordered actions");
            System.out.println("consumer "+entry.getKey()+" "+direction+" "+result.status()+" "+result.reason());
        }
    }
    private static JSONObject family(Path out)throws Exception {
        Files.createDirectories(out);var cases=new JSONArray();
        var original=Path.of("classified-data/coursesOld/both/7XiqRnyXeoftBf2No_inv15.als").toAbsolutePath();var text=Files.readString(original);
        addCase(cases,"7Xiq",original,"inv15","inv15c",51);
        check(cases.getJSONObject(0).getString("sha256").equals("a3e225e8d7aefd4b807217e9c2cf7ab67a844d2fafcaa389dcc8a52bc18f8b7e"),"actual original incident");
        for(var variant:List.of(new String[]{"nested",text.replace("some g1,g2 : Grade |","some g1 : Grade | some g2 : Grade |")},new String[]{"nexts",text.replace("prevs[g2]","nexts[g2]")})){
            check(!variant[1].equals(text),"actual structural variant");var file=out.resolve(variant[0]+".als");Files.writeString(file,variant[1],StandardOpenOption.CREATE_NEW);addCase(cases,variant[0],file,"inv15","inv15c",null);
        }
        String body="all x:A | some x.r implies (some y,z:B | y in prevs[z])";
        var identical=out.resolve("identical.als");Files.writeString(identical,"open util/ordering[B]\nsig A {r:set A}\nsig B {}\npred a {"+body+"}\npred b {"+body+"}\n",StandardOpenOption.CREATE_NEW);addCase(cases,"identical",identical,"a","b",0);
        for(var row:List.of(new String[]{"coursesOld/both/2EKgnipH28DLyp8uK_inv13","inv13","15"},new String[]{"coursesOld/both/3mNrsLxL8mPXSK6gH_inv13","inv13","14"},new String[]{"coursesNew/correct/wnqMPtpNxnPYzru3g_inv14","inv14","16"},new String[]{"coursesNew/both/LJrfWwJdKiTdyytci_inv14","inv14","26"},new String[]{"classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"classroom_fol/both/SASEHD8jPo2HgsuoJ_inv14","inv14","27"},new String[]{"coursesOld/both/xJnitBpFv9K74WNvf_inv14","inv14","49"},new String[]{"coursesNew/both/5vxYh7NCpEPMmC47p_inv14","inv14","47"}))addCase(cases,row[0],Path.of("classified-data",row[0]+".als").toAbsolutePath(),row[1],row[1]+"c",Integer.parseInt(row[2]));
        return new JSONObject().put("cases",cases);
    }
    private static void addCase(JSONArray cases,String id,Path source,String left,String right,Integer expected)throws Exception {
        cases.put(new JSONObject().put("id",id).put("source",source.toAbsolutePath().toString())
                .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))))
                .put("left",left).put("right",right).put("discharge",true).put("expected",expected==null?JSONObject.NULL:expected));
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("consumers")){consumers(Path.of(args[1]));return;}
        boolean standalone=args.length==1;var out=Path.of(args[standalone?0:1]);Files.createDirectories(out);
        var family=standalone?family(out.resolve("inputs")):new JSONObject(Files.readString(Path.of(args[0])));
        boolean baseline=!standalone&&args[2].equals("baseline");var transitions=new JSONArray();int index=0;
        for(Object item:family.getJSONArray("cases")){var row=(JSONObject)item;var p=prepare(row);
            for(int d=0;d<2;d++){var q=d==0?p:p.reverse();var ca=CanonicalAlloyPipeline.prepare(q.a);var cb=CanonicalAlloyPipeline.prepare(q.b);var cmp=NativeOperationComparison.compare(q.a,q.b,ca,cb);var e=cmp.evidence();var diagnostic=e.optJSONObject("diagnostic");if(diagnostic==null)diagnostic=DistanceReplayDiagnostics.capture(q.a,q.b,ca,cb);
                var record=new JSONObject().put("identity",row).put("direction",d).put("native",e).put("diagnostic",diagnostic);String file="row-"+index+"-"+d+".json";Files.writeString(out.resolve(file),record.toString(2),StandardOpenOption.CREATE_NEW);
                if(baseline)continue;
                var cert=diagnostic.getJSONObject("certified");var prior=new JSONObject().put("status","NOT_RECAPTURED");
                if(!standalone){var old=new JSONObject(Files.readString(Path.of(args[2]).resolve(file)));check(old.getJSONObject("identity").similar(row),"same frozen identity");prior=old.getJSONObject("native");check(cert.getJSONArray("phases").similar(old.getJSONObject("diagnostic").getJSONObject("certified").getJSONArray("phases")),"certified selected matrix unchanged");check(cert.getJSONObject("declarationSlice").similar(old.getJSONObject("diagnostic").getJSONObject("certified").getJSONObject("declarationSlice")),"certified declaration receipt unchanged");}
                if(row.getBoolean("discharge")||prior.getString("status").equals("MATCHED")){check(cmp.status()==NativeOperationComparison.Status.MATCHED,row.getString("id")+" "+d+" "+cmp.reason()+" "+e.opt("error"));check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"actual ordered actions equal");traceLinks(e);}
                if(prior.getString("status").equals("MATCHED"))check(e.getJSONArray("fastActions").similar(prior.getJSONArray("fastActions")),"previously matched actions unchanged");
                if(!row.isNull("expected")){check(diagnostic.getJSONObject("fast").getInt("distance")==row.getInt("expected")&&cert.getInt("distance")==row.getInt("expected"),"expected native costs "+row.getString("id"));}
                if(cmp.status()==NativeOperationComparison.Status.UNCHECKABLE)check(e.has("diagnostic"),"mapping gap cannot discard actual selected receipts");
                check(!e.getBoolean("complete")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"public trust boundary retained");
                if(index==0){shorter(q,cert);if(d==0)corruptions(q,e);}
                transitions.put(new JSONObject().put("identity",row).put("direction",d).put("before",prior.getString("status")).put("after",cmp.status()).put("reason",cmp.reason()));
            }index++;
        }
        if(standalone)consumers(out.resolve("consumers"));
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("baseline",baseline).put("checks",checks).put("transitions",transitions).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);System.out.println("RetainedTupleCallReplayTest "+(baseline?"baseline":"candidate")+": "+checks+" assertions");
    }
}

package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;

/** Same-execution original source anchors, typed call premises and selected replay. */
public final class NullarySourceCallTest {
    private static int checks,negatives,links;
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private static Field field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object get(Object o,String name)throws Exception{return field(o,name).get(o);}
    private static void set(Object o,String name,Object value)throws Exception{field(o,name).set(o,value);}
    private static Map<List<Integer>,EGraphNode> calls(EGraphNode root){var out=new LinkedHashMap<List<Integer>,EGraphNode>();collect(root,List.of(),out);return out;}
    private static void collect(EGraphNode n,List<Integer> path,Map<List<Integer>,EGraphNode> out){if(n.getOpcode()==EGraphNode.Opcode.CALL)out.put(path,n);for(int i=0;i<n.getChildren().size();i++){var next=new ArrayList<>(path);next.add(i);collect(n.getChildren().get(i),List.copyOf(next),out);}}
    private static void rejected(ClosedCallEvidence proof,EGraphNode n,List<Integer> branch,String why){boolean accepted=false;try{accepted=proof.admitsNullary(n,branch);}catch(IllegalArgumentException|IllegalStateException expected){}negatives++;check(!accepted,why);}
    private static void changed(ClosedCallEvidence proof,EGraphNode n,List<Integer> branch,String name,Object value)throws Exception{var old=get(n,name);try{set(n,name,value);rejected(proof,n,branch,"actual current "+name);}finally{set(n,name,old);}check(proof.admitsNullary(n,branch),"restored "+name);}
    private static void phaseNegatives(Canonical.Prepared p)throws Exception{
        var form=p.normalizedForms().get(0);var ctor=Class.forName(NativeOperationComparison.class.getName()+"$Endpoint").getDeclaredConstructor(Canonical.Prepared.class);ctor.setAccessible(true);
        for(String name:List.of("temporalOp","params","inheritedQuantiVars","phaseLocalBindingImports")){
            var old=get(form,name);try{set(form,name,name.equals("temporalOp")?NormalForm.TemporalOp.ALWAYS:List.of(new Object()));boolean refused=false;try{ctor.newInstance(p);}catch(InvocationTargetException expected){refused=expected.getCause() instanceof RuntimeException;}negatives++;check(refused,"actual endpoint phase/role/context rejects "+name);}finally{set(form,name,old);}
        }
    }
    private static JSONArray sourceLinks(Canonical.Prepared p,NativeRepairTrace fast,NativeRepairTrace cert,boolean sourceSide,boolean corrupt)throws Exception{
        var form=p.normalizedForms().get(0);var originals=calls(form.getCertificationMatrixEGraph());var selected=calls(fast.comparisonForms(sourceSide).get(0).getMatrixEGraph());var proof=ClosedCallEvidence.from(form);var rows=new JSONArray();
        for(var entry:selected.entrySet()){
            var n=entry.getValue();var metadata=CallMetadata.require(n);if(metadata.arity()!=0)continue;
            var original=originals.get(entry.getKey());check(original!=null,"actual original branch");
            check(proof.admitsNullary(n,entry.getKey()),"actual selected Fast call has issued original anchor");
            check(get(original,"callSourceAnchor")==get(n,"callSourceAnchor")&&get(n,"callSourceAnchor")!=null,"private producer anchor identity, not copied metadata");
            var premises=cert.constructionArtifact(sourceSide).callOccurrenceCertificates().stream().filter(c->c.occurrenceId()==metadata.occurrenceId()).toList();
            check(premises.size()==1,"unique retained construction call premise");var c=premises.get(0);
            check(c.qualifiedCallee().equals(metadata.identity())&&c.sourceName().equals(metadata.sourceName())&&c.kind().equals(metadata.kind())&&c.declaredArity()==0&&c.orderedArguments().isEmpty()&&c.arityAuthority()==metadata.authority()
                    &&c.sourceEndpoint().operator().outputType().equals(AlloyTypeBridge.graphType(n.getExactAlloyType())),"same-execution actual typed source/callee/result premise");
            rows.put(new JSONObject().put("branch",entry.getKey()).put("callee",metadata.identity()).put("sourceName",metadata.sourceName()).put("kind",metadata.kind()).put("authority",metadata.authority()).put("arity",0)
                    .put("type",n.getExactAlloyType().stableString()).put("originalSourceLineage",original.getSourceOccurrenceLineage()).put("parserOccurrence",metadata.occurrenceId()).put("certificatePath",c.sourcePath())
                    .put("sameActualSourceAnchor",true).put("retainedConstructionPremiseChecked",true).put("captureIdsNotEquated",true));links++;
            if(corrupt){
                check(!ClosedCallEvidence.closed(n)&&!BoundCallEvidence.from(form).admits(n),"old gates reject actual declared nullary formula");
                rejected(proof,n,List.of(999),"foreign branch");
                for(var change:List.of(new Object[]{"callSourceAnchor",new Object()},new Object[]{"sourceOccurrenceLineage",-1L},new Object[]{"callOccurrenceId",99999L},
                        new Object[]{"semanticIdentity","foreign/other"},new Object[]{"sourceName","other"},new Object[]{"sourceType","call/expression"},new Object[]{"exactAlloyType",ExactAlloyType.unaryRelation("Person")},
                        new Object[]{"declaredArity",1},new Object[]{"maxArity",1},new Object[]{"callArityAuthority","TYPECHECKED_IMPORT"},new Object[]{"alphaName","captured"},new Object[]{"metatype",EGraphNode.Metatype.SET}))
                    changed(proof,n,entry.getKey(),(String)change[0],change[1]);
                check(!n.isFlexibleArity()&&!n.isOrderInsensitive(),"CALL operator policy fixes ordered arity");
                var oldChildren=get(n,"childClasses");try{set(n,"childClasses",List.of(form.getMatrixEGraph().getEClassRef()));rejected(proof,n,entry.getKey(),"nonempty argument");}finally{set(n,"childClasses",oldChildren);}
                var forged=new EGraphNode(n.getId(),n.getOpcode(),List.of(),n.isCommutative(),n.getMaxArity(),n.isFlexibleArity(),n.getMetatype(),n.getSemanticProfile());
                for(String name:List.of("sourceOccurrenceLineage","certificationOccurrenceLineage","callOccurrenceId","declaredArity","callArityAuthority","sourceName","sourceType","exactAlloyType","semanticIdentity","alphaName"))set(forged,name,get(n,name));
                rejected(proof,forged,entry.getKey(),"all copied metadata and original parser type are not issuance");
                check(proof.admitsNullary(n,entry.getKey()),"all actual source checks restored");
            }
        }
        return rows;
    }
    private static JSONObject run(JSONObject id,Path out,boolean natural)throws Exception{
        var pair=LocalOwnerTraceTest.prepare(id);if(natural)phaseNegatives(pair.get(0));var directions=new JSONArray();
        for(int d=0;d<2;d++){
            var a=pair.get(d);var b=pair.get(1-d);var f=CanonicalRepairTraces.fast(a,b);var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));var selected=new JSONObject().put("fast",f.json()).put("certified",c.json());
            var replay=new JSONObject();QueueProbe.orderedReplay(f.distance(),c.distance(),()->f.replay(f.json()),()->c.replay(c.json()),replay);
            var source=sourceLinks(a,f,c,true,natural);var target=sourceLinks(b,f,c,false,natural);check(source.length()+target.length()>0,"actual nullary source call retained");
            var result=NativeOperationComparison.compare(a,b,f,c);check(result.status()==NativeOperationComparison.Status.MATCHED,"source/certificate/path correspondence "+result.reason()+" "+result.evidence().opt("error"));
            for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(result.evidence().getBoolean(flag),flag);
            if(natural){var fresh=LocalOwnerTraceTest.prepare(id);for(int side=0;side<2;side++){
                var p=pair.get(side);var proof=ClosedCallEvidence.from(p.normalizedForms().get(0));for(var entry:calls(p.normalizedForms().get(0).getCertificationMatrixEGraph()).entrySet())if(CallMetadata.require(entry.getValue()).arity()==0){
                    var foreign=calls(fresh.get(side).normalizedForms().get(0).getCertificationMatrixEGraph()).get(entry.getKey());
                    check(foreign!=null&&CallMetadata.semanticKey(foreign).equals(CallMetadata.semanticKey(entry.getValue())),"foreign parser same metadata");
                    var old=get(foreign,"sourceOccurrenceLineage");try{set(foreign,"sourceOccurrenceLineage",entry.getValue().getSourceOccurrenceLineage());rejected(proof,foreign,entry.getKey(),"foreign parser module/source despite copied lineage");}finally{set(foreign,"sourceOccurrenceLineage",old);}
                }
            }
                var report=result.evidence().getJSONObject("diagnostic");for(String arm:List.of("fast","certified"))for(String mode:List.of("payment","source","type")){
                    var bad=new JSONObject(report.toString());var x=bad.getJSONObject(arm);if(mode.equals("payment"))x.put("distance",x.getInt("distance")+1);else if(mode.equals("source"))x.put("sourceProducerObservationDigest","foreign");else x.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0).getJSONObject("before").put("head","foreign");
                    negatives++;check(NativeOperationComparison.compareCaptured(a,b,f,c,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"tampered retained "+arm+" "+mode);
                }
            }
            check(selected.getJSONObject("fast").similar(f.json())&&selected.getJSONObject("certified").similar(c.json()),"actual selected programs unchanged after checks");
            String name=id.getString("id")+"-"+d+".json";Files.writeString(out.resolve(name),new JSONObject().put("identity",id).put("direction",d).put("selected",selected).put("replay",replay).put("comparison",result.evidence()).put("sourceCalls",source).put("targetCalls",target).toString(),StandardOpenOption.CREATE_NEW);
            directions.put(new JSONObject().put("direction",d).put("status",result.status()).put("fast",f.distance()).put("certified",c.distance()).put("receipt",name));
        }return new JSONObject().put("identity",id).put("directions",directions);
    }
    public static void main(String[] args)throws Exception{
        var id=new JSONArray(Files.readString(Path.of(args[0]))).getJSONObject(0).getJSONObject("identity");var out=Path.of(args[1]);Files.createDirectory(out);var cases=new JSONArray().put(run(id,out,true));
        for(String kind:List.of("formula","relation")){
            String text=kind.equals("formula")?"sig A {r:set A} pred p {some A} pred a {p and some r} pred b {p and some r}":"sig A {r:set A} fun f:set A {A} pred a {some f and some r} pred b {some f and some r}";
            var file=out.resolve("heldout-"+kind+".als");Files.writeString(file,text,StandardOpenOption.CREATE_NEW);var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            var fixture=new JSONObject().put("id","heldout-"+kind).put("source",file.toString()).put("sha256",hash).put("left","a").put("right","b");cases.put(run(fixture,out,false));
            if(kind.equals("relation")){var p=LocalOwnerTraceTest.prepare(fixture).get(0);var form=p.normalizedForms().get(0);var proof=ClosedCallEvidence.from(form);var n=calls(CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0).getMatrixEGraph()).entrySet().iterator().next();changed(proof,n.getValue(),n.getKey(),"exactAlloyType",ExactAlloyType.unaryRelation("A"));}
        }
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("negativeRejections",negatives).put("actualSourceCallLinks",links).put("cases",cases).put("leanJobs",0).put("categoryCleared",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("NullarySourceCallTest: "+checks+" checks, "+negatives+" negatives, "+links+" actual source call links");
    }
}

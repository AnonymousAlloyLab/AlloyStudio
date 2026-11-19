package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.theory.StructuralKey;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.ACGN.util.GlobalVariables;
import parser.ast.nodes.ModelUnit;

/** Actual pre-reseed issued-object selection and complete native path controls. */
public final class SourceOwnedFrameSelectionTest {
    private static int checks,negatives,oldAmbiguities;
    private static final JSONArray controls=new JSONArray();
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Object get(Object o,String field)throws Exception{var f=o.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String field,Object value)throws Exception{var f=o.getClass().getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static void groups(EGraphNode n,List<EGraphNode> groups){if(List.of(EGraphNode.Opcode.DISJ,EGraphNode.Opcode.VAR,EGraphNode.Opcode.DISJVAR).contains(n.getOpcode()))groups.add(n);for(var c:n.getChildren())groups(c,groups);}
    private static Map<?,?> capture(NormalForm phase,EGraphNode group,Map<QuantiVar,List<NormalForm.DeclarationFrame>> issued)throws Exception {
        var result=new IdentityHashMap<EGraphNode,Object>();method(NormalForm.class,"captureDeclarationTransport",EGraphNode.class,Map.class,Map.class).invoke(phase,group,result,issued);return result;
    }
    private static void rejected(NormalForm phase,EGraphNode group,Map<QuantiVar,List<NormalForm.DeclarationFrame>> pool,String name)throws Exception {
        check(!capture(phase,group,pool).containsKey(group),"pre-reseed source gate rejects "+name);negatives++;controls.put(name);
    }
    private static void mutate(NormalForm phase,EGraphNode group,Map<QuantiVar,List<NormalForm.DeclarationFrame>> pool,Object object,String field,Object bad,String name)throws Exception {
        var saved=get(object,field);set(object,field,bad);try{rejected(phase,group,pool,name);}finally{set(object,field,saved);}
        check(capture(phase,group,pool).containsKey(group),"restored issued association "+name);
    }
    private static void preReseed(JSONObject identity)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(Path.of(identity.getString("source"))));
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(identity.getString("left")),module);visitor.visit(new ModelUnit(null,module),null);
        var agent=new IRAgent(visitor.getForest().get(visitor.getForestId(identity.getString("left"))));
        agent.computeNormalForm((stage,phase,all)->{if(!stage.equals("post-prenex-nnf"))return;
            try{
                var rows=new ArrayList<EGraphNode>();groups(phase.getMatrixEGraph(),rows);var pool=new IdentityHashMap<QuantiVar,List<NormalForm.DeclarationFrame>>();for(var q:phase.getLocalQuantiVars())pool.put(q,q.getDeclarationFrames());
                for(var group:rows){var transport=capture(phase,group,pool);check(transport.containsKey(group),"source-issued slot association before reseed");
                    var selected=(List<?>)get(transport.get(group),"frames");
                    for(int i=1;i<group.getChildren().size();i++){
                        final int tuple=i-1;var slot=group.getChildren().get(i);var association=get(slot,"currentDeclarationSlot");check(association!=null,"normalizer issued actual slot authority");
                        var frame=(NormalForm.DeclarationFrame)get(association,"frame");var q=frame.variable();
                        var old=pool.values().stream().flatMap(List::stream).filter(f->f.owner()==phase&&f.declarationOccurrence()==group.getSourceOccurrenceLineage()&&f.variableOccurrence()==slot.getSourceOccurrenceLineage()&&f.tupleSlot()==tuple).toList();
                        if(old.size()>1)oldAmbiguities++;
                        check(selected.get(tuple)==frame&&get(association,"slot")==slot,"selection retains very same issued frame and actual slot");
                        var reversed=new IdentityHashMap<QuantiVar,List<NormalForm.DeclarationFrame>>();for(var entry:pool.entrySet()){var values=new ArrayList<>(entry.getValue());Collections.reverse(values);reversed.put(entry.getKey(),values);}
                        check(((List<?>)get(capture(phase,group,reversed).get(group),"frames")).get(tuple)==frame,"pool ordering is not selection authority");
                        mutate(phase,group,pool,slot,"currentDeclarationSlot",null,"missing current slot association");
                        var clone=(EGraphNode)method(NormalForm.class,"cloneEGraph",EGraphNode.class).invoke(null,slot);
                        check(get(clone,"currentDeclarationSlot")!=null,"private normalization copy transports binding association");
                        mutate(phase,group,pool,slot,"currentDeclarationSlot",get(clone,"currentDeclarationSlot"),"foreign same-metadata slot object association");
                        var fake=new QuantiVar(q.getId(),q.getName(),q.getTypeName());for(var alias:q.getOriginalNames())fake.addOriginalName(alias);fake.mergeExactAlloyType(q.getExactAlloyType());fake.setBindingPath(q.getBindingPath());fake.setDeBruijnKey(q.getDeBruijnKey());fake.setQuantifier(q.getQuantifier());fake.setCardinality(q.getCardinality());fake.setDisjointnessClass(q.getDisjointnessClass());
                        mutate(phase,group,pool,frame,"variable",fake,"foreign same-metadata binding object");
                        mutate(phase,group,pool,frame,"owner",new NormalForm(),"stale foreign owner");
                        mutate(phase,group,pool,frame,"tupleSlot",tuple+1,"wrong tuple slot");
                        mutate(phase,group,pool,frame,"domain",StructuralKey.of("foreign-domain",List.of(),List.of()),"wrong original selected domain");
                        mutate(phase,group,pool,frame,"dependencies",List.of(q),"wrong ordered dependency frame");
                        mutate(phase,group,pool,phase,"phasePath",phase.getPhasePath()+"/foreign","stale phase context");
                        var missing=new IdentityHashMap<>(pool);missing.put(q,List.of());rejected(phase,group,missing,"missing current frame");
                        var duplicated=new IdentityHashMap<>(pool);duplicated.put(q,List.of(frame,frame));rejected(phase,group,duplicated,"duplicate current issued frame");
                        check(capture(phase,group,pool).containsKey(group),"restored full pre-reseed source gate");
                    }
                }
            }catch(ReflectiveOperationException e){throw new RuntimeException(e);}catch(Exception e){throw new RuntimeException(e);}
        });
    }
    private static JSONObject paths(JSONObject id,Path out)throws Exception {
        preReseed(id);var pair=LocalOwnerTraceTest.prepare(id);var rows=new JSONArray();
        for(int direction=0;direction<2;direction++){
            var a=pair.get(direction);var b=pair.get(1-direction);var f=CanonicalRepairTraces.fast(a,b);var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var fast=f.json();var cert=c.json();var replay=new JSONObject();QueueProbe.orderedReplay(f.distance(),c.distance(),()->f.replay(fast),()->c.replay(cert),replay);
            var result=NativeOperationComparison.compare(a,b,f,c);check(result.status()==NativeOperationComparison.Status.MATCHED,"source-linked both-direction path "+id.getString("id")+" "+direction+" "+result.reason()+" "+result.evidence().opt("error"));
            check(fast.similar(f.json())&&cert.similar(c.json()),"complete selected native paths unchanged");
            var row=new JSONObject().put("identity",id).put("direction",direction).put("fast",fast).put("certified",cert).put("comparison",result.evidence()).put("replay",replay);
            String file=id.getString("id")+"-"+direction+".json";Files.writeString(out.resolve(file),row.toString(),StandardOpenOption.CREATE_NEW);
            rows.put(new JSONObject().put("direction",direction).put("fast",f.distance()).put("certified",c.distance()).put("status",result.status()).put("receipt",file));
        }return new JSONObject().put("identity",id).put("directions",rows);
    }
    private static JSONObject fixture(Path out,String id,String declarations,String body)throws Exception{
        String text=declarations+" pred a {"+body+"} pred b {"+body+"}";var file=out.resolve(id+".als");Files.writeString(file,text,StandardOpenOption.CREATE_NEW);
        var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        return new JSONObject().put("id",id).put("source",file.toString()).put("sha256",hash).put("left","a").put("right","b");
    }
    public static void main(String[]args)throws Exception{
        var input=new JSONArray(Files.readString(Path.of(args[0])));var id=input.getJSONObject(0).getJSONObject("identity");var out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        rows.put(paths(id,out));check(oldAmbiguities>0,"natural old lineage-only selector actually ambiguous");
        rows.put(paths(fixture(out,"derived-three-slot","sig A {r:set A}","all x,y:A | x in y.r implies (one disj p,q,t:A | x in p.r and y in q.r and x in t.r)"),out));
        rows.put(paths(fixture(out,"heldout-dependent","sig B {r:set B}","all x,y:B | x in y.r implies (one disj p,q:B, t:p.r | x in p.r and y in q.r and x in t.r)"),out));
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("actualSourcePremiseRejections",negatives).put("observedOldLineageAmbiguities",oldAmbiguities).put("negativeControls",controls).put("cases",rows).put("leanJobs",0).put("categoryClearance",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("SourceOwnedFrameSelectionTest: "+checks+" checks, "+negatives+" actual pre-reseed premise rejections");
    }
}

package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.MessageDigest;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Source-object joins and fixed-choice replay; a checked mismatch is not path equality. */
public final class AuthenticatedPhaseTransportTest {
    private static int checks,negatives,premiseRejections,integrityRejections,liveReindexedFixtures;
    private static final JSONArray premiseControls=new JSONArray();
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable action,String why){checks++;negatives++;try{action.run();}
        catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Accepted "+why);}
    @FunctionalInterface private interface Checked {void run()throws Exception;}
    private static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static void field(Object target,String name,Object value)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static void boundary(Checked action,String expected,String control)throws Exception {
        checks++;try{action.run();}catch(InvocationTargetException e){
            check(e.getCause() instanceof IllegalArgumentException&&expected.equals(e.getCause().getMessage()),"intended premise boundary "+control+": "+e.getCause());
            negatives++;premiseRejections++;premiseControls.put(new JSONObject().put("control",control).put("boundary",expected));return;
        }throw new AssertionError("Accepted live premise substitution "+control);
    }
    private record Actual(int phase,String path,EGraphNode fast,RepairView.Node certified) { }
    @SuppressWarnings("unchecked")
    private static void livePremises(Canonical.Prepared original,NativeRepairTrace fast,NativeRepairTrace cert,JSONObject report,String side)throws Exception {
        boolean sourceSide=side.equals("source");
        var join=new PhaseIdentityJoin(original.normalizedForms(),fast.comparisonForms(sourceSide).stream().map(NormalForm::comparisonSource).toList(),
                cert.projectedView(sourceSide).originalPhaseSources(original.normalizedForms()));
        boolean changed=false;for(int i=0;i<original.normalizedForms().size();i++)changed|=join.certified(i)!=i;
        if(changed)liveReindexedFixtures++;
        // Test the private premise checker directly, not a modified receipt through the integrity wrapper.
        // The production entry point retains its mandatory replay/integrity checks unchanged.
        var type=Class.forName("is.fivefivefive.CanDis.TemporalOperationCorrespondence$Endpoint");
        var constructor=type.getDeclaredConstructor(Canonical.Prepared.class,NativeRepairTrace.class,NativeRepairTrace.class,JSONObject.class,String.class);constructor.setAccessible(true);
        var context=constructor.newInstance(original,fast,cert,report.getJSONObject("certified"),side);
        var method=type.getDeclaredMethod("node",EGraphNode.class,RepairView.Node.class,int.class,String.class,Map.class);method.setAccessible(true);
        var actuals=new ArrayList<Actual>();var maps=(List<Map<String,?>>)field(context,"nodes");
        for(int i=0;i<maps.size();i++)for(var item:maps.get(i).entrySet()){
            var pair=item.getValue();var fm=pair.getClass().getDeclaredMethod("fast");var cm=pair.getClass().getDeclaredMethod("certified");fm.setAccessible(true);cm.setAccessible(true);
            actuals.add(new Actual(i,item.getKey(),(EGraphNode)fm.invoke(pair),(RepairView.Node)cm.invoke(pair)));
        }
        var ref=actuals.stream().filter(p->p.certified().isTemporalReference()&&!p.certified().observationPhases().isEmpty()).findFirst().orElseThrow();
        var foreign=actuals.stream().filter(p->p.certified().isTemporalReference()&&p.certified().originalReferenceOrigin().owner()!=ref.certified().originalReferenceOrigin().owner()).findFirst().orElseThrow();
        var node=ref.certified();var origin=field(node,"referenceOrigin");
        field(node,"referenceOrigin",foreign.certified().originalReferenceOrigin());
        try{boundary(()->method.invoke(context,ref.fast(),node,ref.phase(),ref.path(),new LinkedHashMap<>()),"Original REF occurrence/owner authority","actual foreign REF owner with unchanged typed payload");}
        finally{field(node,"referenceOrigin",origin);}
        var oldType=field(node,"observationType");field(node,"observationType",is.fivefivefive.CanDis.theory.GraphType.INT);
        try{boundary(()->method.invoke(context,ref.fast(),node,ref.phase(),ref.path(),new LinkedHashMap<>()),"Temporal original literal result type","actual REF result type substitution");}
        finally{field(node,"observationType",oldType);}
        var oldPhases=field(node,"observationPhases");var oldPayload=field(node,"semanticPayload");
        var wrong=new ArrayList<>(node.observationPhases());wrong.set(0,(wrong.get(0)+1)%original.normalizedForms().size());
        field(node,"observationPhases",List.copyOf(wrong));field(node,"semanticPayload","temporal-phase["+String.join(",",wrong.stream().map(String::valueOf).toList())+"]/"+node.observationTemporalOperator());
        try{boundary(()->method.invoke(context,ref.fast(),node,ref.phase(),ref.path(),new LinkedHashMap<>()),"Original reference owner/typed branch identity","coherent REF branch ordinal and embedded payload substitution");}
        finally{field(node,"observationPhases",oldPhases);field(node,"semanticPayload",oldPayload);}
        var ownerUse=actuals.stream().filter(p->p.certified().isVariable()&&p.certified().bindingIndex()>=0
                &&cert.projectedView(sourceSide).phases().get(join.certified(p.phase())).bindings().get(p.certified().bindingIndex()).ownerPhase()>=0).findFirst();
        if(ownerUse.isPresent()){
            var use=ownerUse.get();var binding=cert.projectedView(sourceSide).phases().get(join.certified(use.phase())).bindings().get(use.certified().bindingIndex());var oldOwner=field(binding,"ownerPhase");
            field(binding,"ownerPhase",((Integer)oldOwner+1)%original.normalizedForms().size());
            try{boundary(()->method.invoke(context,use.fast(),use.certified(),use.phase(),use.path(),new LinkedHashMap<>()),"Foreign original projected binding object","actual reindexed binder owner substitution");}
            finally{field(binding,"ownerPhase",oldOwner);}
        }
        var changedRole=new JSONObject(report.getJSONObject("certified").toString());
        for(Object item:changedRole.getJSONObject("selectedTemporalReferencePayments").getJSONArray(side))for(Object value:((JSONObject)item).getJSONArray("typedBranches")){
            var row=(JSONObject)value;if(row.getInt("ordinal")==0)row.put("kind","HISTORICALLY").put("sourcePath","coherent-but-foreign-source");
        }
        boundary(()->constructor.newInstance(original,fast,cert,changedRole,side),"Original typed phase/source correspondence","coherent typed role/source association against retained objects");
        constructor.newInstance(original,fast,cert,report.getJSONObject("certified"),side);
        method.invoke(context,ref.fast(),node,ref.phase(),ref.path(),new LinkedHashMap<>());check(true,"restored actual authority control passes");
    }
    private static void identityControls(Canonical.Prepared actual,Canonical.Prepared foreign,NativeRepairTrace fast,NativeRepairTrace cert) {
        var originals=actual.normalizedForms();var forms=fast.comparisonForms(true);
        var view=cert.projectedView(true);var sources=forms.stream().map(NormalForm::comparisonSource).toList();
        var projected=view.originalPhaseSources(originals);var join=new PhaseIdentityJoin(originals,sources,projected);
        for(int i=0;i<sources.size();i++) {
            check(projected.get(join.certified(i))==sources.get(i),"actual identity forward join");
            check(join.fast(join.certified(i))==i,"both inverse laws, not an involution");
        }
        reject(()->view.originalPhaseSources(foreign.normalizedForms()),"same-metadata foreign source objects");
        reject(()->new PhaseIdentityJoin(originals,sources.subList(1,sources.size()),projected),"missing phase");
        var duplicate=new ArrayList<>(sources);duplicate.set(1,duplicate.get(0));
        reject(()->new PhaseIdentityJoin(originals,duplicate,projected),"duplicate phase");
        var forged=new ArrayList<>(sources);forged.set(0,foreign.normalizedForms().get(0));
        reject(()->new PhaseIdentityJoin(originals,forged,projected),"foreign equal-looking phase");
        if(originals.size()>=3) {
            var three=originals.subList(0,3);var cycle=List.of(three.get(1),three.get(2),three.get(0));
            var inverse=List.of(three.get(2),three.get(0),three.get(1));var rotated=new PhaseIdentityJoin(three,cycle,inverse);
            check(rotated.certified(0)==2&&rotated.fast(2)==0,"distinct three-cycle maps use true inverses");
        }
    }
    private static JSONObject run(JSONObject identity,int direction,Path out,boolean synthetic)throws Exception {
        var pair=LocalOwnerTraceTest.prepare(identity);var foreign=LocalOwnerTraceTest.prepare(identity);
        var a=pair.get(direction);var b=pair.get(1-direction);
        var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
        var fast=CanonicalRepairTraces.fast(a,b);var cert=CanonicalRepairTraces.certified(ca,cb);
        identityControls(a,foreign.get(direction),fast,cert);
        var f=fast.json();var c=cert.json();
        // The same selected immutable programs are reused; no optimizer invocation below.
        var result=NativeOperationComparison.compare(a,b,fast,cert);var evidence=result.evidence();
        check(result.status()==NativeOperationComparison.Status.MATCHED,
                "focused source correspondence "+identity.getString("id")+" "+direction+" "+result.reason()+" "+evidence.opt("error")+" "+evidence.opt("correspondenceBoundary"));
        check(evidence.getBoolean("endpointCorrespondenceChecked")&&evidence.getBoolean("allNativeEntriesRetained")
                &&evidence.getBoolean("fixedChoiceReplaysChecked"),"complete correspondence premises checked");
        var report=evidence.getJSONObject("diagnostic");
        if(!synthetic)livePremises(direction==0?a:b,fast,cert,report,direction==0?"source":"target");
        check(NativeOperationComparison.compareCaptured(a,b,fast,cert,report).status()==result.status(),"retained choices recheck");
        LocalOwnerTraceTest.sameExecution(f,fast.json());LocalOwnerTraceTest.sameExecution(c,cert.json());checks+=2;
        for(String arm:List.of("fast","certified"))for(String mode:List.of("owner","type","domain","dependency","phase","role","branch","reference","payment","selection","coherent")) {
            var corrupt=new JSONObject(report.toString());var x=corrupt.getJSONObject(arm);
            switch(mode) {
                case "owner","type" -> x.getJSONArray("sourceBindingContexts").getJSONObject(0).put(mode,"foreign");
                case "domain","dependency" -> x.getJSONArray("nativeDeclarationPrograms").getJSONObject(0).put(mode,"foreign");
                case "phase" -> x.getJSONArray("phases").getJSONObject(0).put("phase",999);
                case "role","branch","reference" -> x.getJSONObject("nativeTemporalProgram").put(mode,"foreign");
                case "payment" -> x.getJSONArray("orderedNativeOperations").getJSONObject(0).put("cost",999);
                case "selection" -> x.getJSONObject("nativeTemporalProgram").put("selectedCorrespondence",new JSONArray());
                case "coherent" -> {for(String side:List.of("fast","certified"))corrupt.getJSONObject(side).put("sourceProducerObservationDigest","same-forged-value");}
            }
            check(NativeOperationComparison.compareCaptured(a,b,fast,cert,corrupt).status()==NativeOperationComparison.Status.INVALID_REPLAY,"retained-report integrity "+arm+" "+mode);negatives++;integrityRejections++;
        }
        check(NativeOperationComparison.compareCaptured(foreign.get(direction),foreign.get(1-direction),fast,cert,report).status()==NativeOperationComparison.Status.INVALID_REPLAY,"borrowed receipt cannot authenticate foreign objects");negatives++;
        Files.writeString(out.resolve(identity.getString("id")+"-"+direction+".json"),evidence.toString(),StandardOpenOption.CREATE_NEW);
        return new JSONObject().put("identity",identity).put("direction",direction).put("status",result.status())
                .put("endpointCorrespondenceChecked",true).put("fast",fast.distance()).put("certified",cert.distance());
    }
    public static void main(String[] args)throws Exception {
        var ids=new JSONArray(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        for(Object request:ids){var row=(JSONObject)request;rows.put(run(row.getJSONObject("identity"),row.getInt("direction"),out,false));}
        String text="var sig A {} pred a {always eventually some A} pred b {always eventually some A}";
        var path=out.resolve("variant.als");Files.writeString(path,text,StandardOpenOption.CREATE_NEW);
        var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        var id=new JSONObject().put("id","derived-three-phase-variant").put("source",path.toString()).put("sha256",hash).put("left","a").put("right","b");
        rows.put(run(id,0,out,true));rows.put(run(id,1,out,true));
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("negativeRejections",negatives)
                .put("retainedReportIntegrityRejections",integrityRejections).put("directLivePremiseRejections",premiseRejections).put("directLivePremiseControls",premiseControls).put("liveReindexedFixtures",liveReindexedFixtures).put("rows",rows)
                .put("leanRuns",0).put("categoryClearance",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("AuthenticatedPhaseTransportTest: "+checks+" checks, "+negatives+" negative rejections");
    }
}

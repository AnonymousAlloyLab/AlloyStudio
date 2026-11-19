package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;

/** Real paid carrier routes, with provenance multiplicity and corrupted evidence controls. */
public final class CarrierFrameMultiplicityTest {
    private static int checks, negatives, routes;
    private static void check(boolean value,String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    private static Object get(Object object,String name) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static Method method(Class<?> type,String name,Class<?>... args) throws Exception {
        var result=type.getDeclaredMethod(name,args);result.setAccessible(true);return result;
    }
    private static void reject(Runnable action,String why) {
        checks++;negatives++;
        try {action.run();} catch (IllegalArgumentException|IllegalStateException expected) {return;}
        throw new AssertionError("Accepted "+why);
    }
    private static void corrupt(Object object,String field,Object bad,Object route,Method valid) throws Exception {
        Object original=get(object,field);
        try {set(object,field,bad);check(!(Boolean)valid.invoke(route),"corrupted "+field);negatives++;}
        finally {set(object,field,original);}
        check((Boolean)valid.invoke(route),"restored "+field);
    }
    @SuppressWarnings("unchecked")
    private static void routeControls(Canonical.Prepared a,Canonical.Prepared b,JSONObject receipt) throws Exception {
        var left=(CanonicalDistance.Prepared)get(a,"delegate");var right=(CanonicalDistance.Prepared)get(b,"delegate");
        var captured=method(CanonicalDistance.class,"captureDecisions",CanonicalDistance.Prepared.class,CanonicalDistance.Prepared.class).invoke(null,left,right);
        var decisions=get(captured,"decisions");var alignment=get(decisions,"alignment");var phases=(List<?>)get(alignment,"phases");
        var declarations=method(CanonicalDistance.class,"selectedImportDeclarations",CanonicalDistance.Prepared.class,
                CanonicalDistance.Prepared.class,alignment.getClass(),decisions.getClass()).invoke(null,left,right,alignment,decisions);
        var linked=method(CanonicalDistance.class,"declarationPathsMatch",List.class,List.class);
        check((Boolean)linked.invoke(null,declarations,phases),"actual per-phase declaration links");
        check(!(Boolean)linked.invoke(null,declarations,List.of()),"missing phase map rejected");negatives++;
        for (Object route:(List<?>)get(alignment,"paidSlotRoutes")) {
            var source=(QuantiVar)get(route,"source");var target=(QuantiVar)get(route,"target");
            if(source.getDeclarationFrames().size()==target.getDeclarationFrames().size())continue;
            routes++;check(!source.getDeclarationFrames().isEmpty(),"old nonempty gate");
            check(source.getDeclarationFrames().size()!=target.getDeclarationFrames().size(),"exact old count gate rejects");
            var valid=method(route.getClass(),"valid");var bind=method(route.getClass(),"bind",List.class);
            check((Boolean)valid.invoke(route)&&(Boolean)bind.invoke(route,phases),"real carrier route and typed reads");
            var frame=source.getDeclarationFrames().get(0);var sourceFrames=(List<NormalForm.DeclarationFrame>)get(source,"declarationFrames");
            var targetFrames=(List<NormalForm.DeclarationFrame>)get(target,"declarationFrames");
            var oldSource=new ArrayList<>(sourceFrames);var oldTarget=new ArrayList<>(targetFrames);
            try {
                // Held-out multiplicities of already issued frames, not invented source declarations.
                sourceFrames.add(frame);targetFrames.addAll(Collections.nCopies(3,targetFrames.get(0)));
                check((Boolean)valid.invoke(route)&&(Boolean)bind.invoke(route,phases),"held-out issued multiplicity");
                sourceFrames.clear();check(!(Boolean)valid.invoke(route),"empty source rejected");negatives++;
            } finally {sourceFrames.clear();sourceFrames.addAll(oldSource);targetFrames.clear();targetFrames.addAll(oldTarget);}
            check((Boolean)valid.invoke(route),"original frame lists restored");
            corrupt(frame,"owner",new NormalForm(),route,valid);
            corrupt(frame,"variable",target,route,valid);
            corrupt(frame,"phasePath","foreign/phase",route,valid);
            corrupt(frame,"typeName","FORGED",route,valid);
            corrupt(frame,"dependencies",List.of(target),route,valid);
            corrupt(frame,"domain",target.getDeclarationFrames().get(0).domain(),route,valid);
            var original=frame.owner();var issued=(List<Object>)get(original,"issuedDeclarationFrames");
            int issuedIndex=issued.indexOf(frame);check(issuedIndex>=0,"frame initially issued");issued.remove(issuedIndex);
            try {check(!(Boolean)valid.invoke(route),"unissued original rejected");negatives++;}
            finally {issued.add(issuedIndex,frame);}
            var lowerings=(Map<Object,Object>)get(original,"issuedDomainLowerings");Object lowering=lowerings.remove(frame);
            try {
                check(!(Boolean)valid.invoke(route),"missing lowering rejected");negatives++;
                lowerings.put(frame,true);check(!(Boolean)valid.invoke(route),"unretained matrix guard rejected");negatives++;
            } finally {lowerings.put(frame,lowering);}
            check((Boolean)bind.invoke(route,phases),"original read decisions rebound");
            var bindProgram=method(route.getClass(),"bindProgram",JSONObject.class);
            check((Boolean)bindProgram.invoke(route,receipt),"actual emitted program bound");
            var phase=phases.get((Integer)get(route,"owner"));var changed=new ArrayList<Object>(phases);
            var constructor=phase.getClass().getDeclaredConstructors()[0];constructor.setAccessible(true);
            var wrong=new HashMap<>((Map<String,String>)get(phase,"mapping"));wrong.remove(source.getName());
            changed.set((Integer)get(route,"owner"),constructor.newInstance(wrong,get(phase,"matrix")));
            check(!(Boolean)linked.invoke(null,declarations,changed),"unlinked equal-cost map rejected");negatives++;
        }
    }
    public static void main(String[] args) throws Exception {
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();
        for (Object raw:manifest.getJSONArray("cases")) {
            var identity=((JSONObject)raw).getJSONObject("identity");var pair=LocalOwnerTraceTest.prepare(identity);
            for(int direction=0;direction<2;direction++) {
                var a=pair.get(direction);var b=pair.get(1-direction);var fast=CanonicalRepairTraces.fast(a,b);
                var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                var order=new JSONObject();QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),order);
                check(fast.distance()==Canonical.distance(a,b),"numeric and captured distances agree");
                var receipt=fast.json();check(receipt.has("selectedPaidSlotRoutes"),"real selected carrier route");routeControls(a,b,receipt);
                var comparison=NativeOperationComparison.compare(a,b,fast,certified);
                check(comparison.status()==NativeOperationComparison.Status.MATCHED,"both-direction action equality "+comparison.reason());
                var original=receipt.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0);
                for(String field:List.of("owner","source","target")) {
                    var bad=fast.json();bad.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0).put(field,field.equals("owner")?-1:"FORGED");
                    reject(()->fast.replay(bad),field);
                }
                var badType=fast.json();badType.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0).getJSONArray("sourceFrames").getJSONObject(0).put("type","FORGED");reject(()->fast.replay(badType),"source type");
                var unpaid=fast.json();unpaid.getJSONArray("nativeDeclarationPrograms").getJSONObject(original.getInt("owner")).put("cost",0);reject(()->fast.replay(unpaid),"unpaid declaration");
                var misrouted=fast.json();var reads=misrouted.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0).getJSONArray("selectedReads");
                if(reads.isEmpty())reads.put(new JSONObject().put("phase",original.getInt("owner")).put("sourcePath","root").put("targetPath","root/foreign"));
                else reads.getJSONObject(0).put("targetPath","root/foreign");
                reject(()->fast.replay(misrouted),"invented or misrouted typed read");
                Files.writeString(out.resolve(identity.getString("id")+"-"+direction+".json"),new JSONObject().put("identity",identity).put("direction",direction).put("fast",receipt).put("certified",certified.json()).put("comparison",comparison.evidence()).put("replay",order).toString(2),StandardOpenOption.CREATE_NEW);
                rows.put(new JSONObject().put("id",identity.getString("id")).put("direction",direction).put("status",comparison.status()).put("distance",fast.distance()));
            }
        }
        check(routes>=2,"unequal authentic carrier multiplicity in both directions");
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives).put("routes",routes).put("rows",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("CarrierFrameMultiplicityTest: "+checks+" checks, "+negatives+" negative controls");
    }
}

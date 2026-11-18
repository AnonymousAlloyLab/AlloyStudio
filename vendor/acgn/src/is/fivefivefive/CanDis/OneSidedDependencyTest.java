package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.DeclarationReplay;

/** One-sided dependency edits retain source authority, native payment and guard replay. */
public final class OneSidedDependencyTest {
    private static int checks,negatives,routes,reads;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static void negative(boolean rejected,String why) { negatives++;check(rejected,why); }
    private static Object get(Object o,String name)throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void set(Object o,String name,Object v)throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);
    }
    private static Method method(Class<?> c,String name,Class<?>... args)throws Exception {
        var m=c.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }
    private static void corrupt(Object object,String field,Object bad,Object route,Method valid)throws Exception {
        Object old=get(object,field);
        try {set(object,field,bad);negative(!(Boolean)valid.invoke(route),"corrupted "+field);}
        finally {set(object,field,old);}
        check((Boolean)valid.invoke(route),"restored "+field);
    }
    private static void reject(Runnable action,String why) {
        negatives++;checks++;
        try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted "+why);
    }
    @SuppressWarnings("unchecked")
    private static void controls(Canonical.Prepared a,Canonical.Prepared b,JSONObject receipt)throws Exception {
        var left=(CanonicalDistance.Prepared)get(a,"delegate");var right=(CanonicalDistance.Prepared)get(b,"delegate");
        var captured=method(CanonicalDistance.class,"captureDecisions",CanonicalDistance.Prepared.class,CanonicalDistance.Prepared.class).invoke(null,left,right);
        var alignment=get(get(captured,"decisions"),"alignment");var phases=(List<?>)get(alignment,"phases");
        for(Object route:(List<?>)get(alignment,"paidSlotRoutes")) {
            var payments=(List<?>)method(route.getClass(),"dependencyPayments").invoke(route);if(payments.isEmpty())continue;
            routes++;var valid=method(route.getClass(),"valid");var bind=method(route.getClass(),"bind",List.class);
            var bindProgram=method(route.getClass(),"bindProgram",JSONObject.class);
            check((Boolean)valid.invoke(route)&&(Boolean)bind.invoke(route,phases),"actual source-bound route");
            check((Boolean)bindProgram.invoke(route,receipt),"actual native guard and declaration program");
            var source=(QuantiVar)get(route,"source");var target=(QuantiVar)get(route,"target");
            check(source.getDeclarationFrames().get(0).dependencies().size()!=target.getDeclarationFrames().get(0).dependencies().size(),"old dependency length gate rejects");
            int owner=(Integer)get(route,"owner");
            for(Object payment:payments) {
                boolean side=(Boolean)get(payment,"sourceSide");var dependency=(QuantiVar)get(payment,"binding");
                int operation=(Integer)get(payment,"operationIndex"),index=(Integer)get(payment,"declarationIndex");
                var selected=(Map<QuantiVar,QuantiVar>)get(route,"selected");
                check(!(side?selected.containsKey(dependency):selected.containsValue(dependency)),"not a paired dependency");
                var forged=new IdentityHashMap<>(selected);if(side)forged.put(dependency,target);else forged.put(source,dependency);
                corrupt(route,"selected",forged,route,valid);
                var frame=dependency.getDeclarationFrames().get(0);
                corrupt(frame,"owner",new NormalForm(),route,valid);
                corrupt(frame,"typeName","FORGED",route,valid);
                corrupt(frame,"phasePath","foreign/phase",route,valid);
                var issued=(List<Object>)get(frame.owner(),"issuedDeclarationFrames");int issuedIndex=issued.indexOf(frame);
                check(issuedIndex>=0,"actual dependency issuer");issued.remove(issuedIndex);
                try {negative(!(Boolean)valid.invoke(route),"missing dependency issuer");}finally{issued.add(issuedIndex,frame);}
                Object declarations=get(route,"declarations");var moves=new ArrayList<>((List<DeclarationReplay.Move>)get(declarations,"moves"));
                moves.set(operation,new DeclarationReplay.Move(-1,-1));var constructor=declarations.getClass().getDeclaredConstructors()[0];constructor.setAccessible(true);
                Object missing=constructor.newInstance(get(declarations,"source"),get(declarations,"target"),moves);
                corrupt(route,"declarations",missing,route,valid);
                var bad=new JSONObject(receipt.toString());bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(owner).getJSONArray("operations").getJSONObject(operation).put("cost",0);
                negative(!(Boolean)bindProgram.invoke(route,bad),"unpaid one-sided operation");
                bad=new JSONObject(receipt.toString());bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(owner).getJSONArray("operations").getJSONObject(operation).put(side?"leftIndex":"rightIndex",index+1);
                negative(!(Boolean)bindProgram.invoke(route,bad),"wrong native dependency index");
                for(Object phase:phases) {
                    var map=(Map<String,String>)get(phase,"mapping");
                    check(!(side?map.containsKey(dependency.getName()):map.containsValue(dependency.getName())),"excluded from complete phase mapping");
                }
            }
            var dependent=source.getDeclarationFrames().get(0).dependencies().isEmpty()?target:source;
            var guardFrame=dependent.getDeclarationFrames().get(0);var lowerings=(Map<Object,Object>)get(guardFrame.owner(),"issuedDomainLowerings");Object lowering=lowerings.remove(guardFrame);
            try {negative(!(Boolean)valid.invoke(route),"missing parent lowering");}finally{lowerings.put(guardFrame,lowering);}
            check((Boolean)bind.invoke(route,phases)&&(Boolean)bindProgram.invoke(route,receipt),"source evidence restored");
            var evidence=(JSONObject)method(route.getClass(),"evidence").invoke(route);
            var selectedReads=evidence.getJSONArray("selectedReads");reads+=selectedReads.length();check(!selectedReads.isEmpty(),"actual selected typed read");
            var guards=evidence.getJSONArray("selectedGuardOperations");check(!guards.isEmpty(),"actual selected dependent guard");
            var guard=guards.getJSONObject(0);var bad=new JSONObject(receipt.toString());
            bad.getJSONArray("phases").getJSONObject(owner).getJSONObject("program").getJSONArray("operations").getJSONObject(guard.getInt("operationIndex")).put("cost",guard.getInt("headCost")+1);
            negative(!(Boolean)bindProgram.invoke(route,bad),"wrong emitted guard charge");
            check((Boolean)bindProgram.invoke(route,receipt),"original program restored");
        }
    }
    public static void main(String[] args)throws Exception {
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));var expected=new JSONObject(Files.readString(Path.of(manifest.getJSONObject("extraction").getString("file")))).getJSONArray("links");
        Path out=Path.of(args[1]);Files.createDirectory(out);var rows=new JSONArray();int expectedRoutes=0;
        for(Object raw:manifest.getJSONArray("cases")) {
            var identity=((JSONObject)raw).getJSONObject("identity");var pair=LocalOwnerTraceTest.prepare(identity);
            for(int direction=0;direction<2;direction++) {
                var a=pair.get(direction);var b=pair.get(1-direction);var fast=CanonicalRepairTraces.fast(a,b);
                var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));var order=new JSONObject();
                QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),order);
                check(Canonical.distance(a,b)==fast.distance(),"numeric and captured paths agree");
                var receipt=fast.json();var selected=receipt.getJSONArray("selectedPaidSlotRoutes");
                for(Object e:expected){var link=(JSONObject)e;if(!link.getJSONObject("identity").similar(identity)||link.getInt("direction")!=direction)continue;
                    expectedRoutes++;int found=0;for(Object s:selected){var r=(JSONObject)s;if(r.getString("source").equals(link.getString("source"))&&r.getString("target").equals(link.getString("target"))&&!r.optJSONArray("dependencyOperations").isEmpty())found++;}
                    check(found==1,"each extracted route actually selected");
                }
                controls(a,b,receipt);var comparison=NativeOperationComparison.compare(a,b,fast,certified);
                check(comparison.status()==NativeOperationComparison.Status.MATCHED,"action equality, not just totals: "+comparison.reason());
                for(int i=0;i<selected.length();i++) {
                    if(!selected.getJSONObject(i).has("dependencyOperations"))continue;final int route=i;
                    for(String field:List.of("operationIndex","declarationIndex","binding","sourceSide","cost")) {
                        var bad=fast.json();var op=bad.getJSONArray("selectedPaidSlotRoutes").getJSONObject(route).getJSONArray("dependencyOperations").getJSONObject(0);
                        op.put(field,field.equals("binding")?"FORGED":field.equals("sourceSide")?!op.getBoolean(field):-1);reject(()->fast.replay(bad),"dependency "+field);
                    }
                    var bad=fast.json();bad.getJSONArray("selectedPaidSlotRoutes").getJSONObject(route).getJSONArray("dependencyOperations").getJSONObject(0).getJSONArray("frames").getJSONObject(0).put("type","FORGED");reject(()->fast.replay(bad),"dependency exact type");
                }
                var nativeProgram=receipt.getJSONArray("nativeDeclarationPrograms").getJSONObject(0);int total=0;for(Object o:nativeProgram.getJSONArray("operations"))total+=((JSONObject)o).getInt("cost");check(total==nativeProgram.getInt("cost"),"shared payment counted only by native program");
                Files.writeString(out.resolve(identity.getString("id")+"-"+direction+".json"),new JSONObject().put("identity",identity).put("direction",direction).put("fast",receipt).put("certified",certified.json()).put("comparison",comparison.evidence()).put("replay",order).toString(2),StandardOpenOption.CREATE_NEW);
                rows.put(new JSONObject().put("identity",identity).put("direction",direction).put("status",comparison.status()).put("distance",fast.distance()));
            }
        }
        check(routes==expectedRoutes&&routes==expected.length(),"all extracted routes checked");
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives).put("routes",routes).put("selectedReads",reads).put("rows",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("OneSidedDependencyTest: "+checks+" checks, "+negatives+" negatives, "+routes+" routes");
    }
}

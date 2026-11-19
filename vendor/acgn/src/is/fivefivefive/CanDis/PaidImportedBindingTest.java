package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;

public final class PaidImportedBindingTest {
    private static int checks, negatives;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static void negative(boolean ok,String why) { negatives++;check(ok,why); }
    private static Object get(Object o,String key) throws Exception {
        var f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);
    }
    private static void set(Object o,String key,Object value) throws Exception {
        var f=o.getClass().getDeclaredField(key);f.setAccessible(true);f.set(o,value);
    }
    private static Method method(String name,Class<?>... types) throws Exception {
        var m=CanonicalDistance.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;
    }
    private static CanonicalDistance.Prepared delegate(Canonical.Prepared p) throws Exception {
        return (CanonicalDistance.Prepared)get(p,"delegate");
    }
    private static Object variable(Canonical.Prepared p) throws Exception {
        var space=method("coherentVariableSpace",List.class).invoke(null,delegate(p).comparisonForms());
        var vars=(Map<?,?>)get(space,"variables");check(vars.size()==1,"one actual lexical binder");
        return vars.values().iterator().next();
    }
    private static void postorder(Object tree,List<Object> out) throws Exception {
        for(var child:(List<?>)get(tree,"children"))postorder(child,out);out.add(tree);
    }
    private static void reject(Runnable action,String why) {
        negatives++;checks++;try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted corrupted "+why);
    }
    private static void frameControls(Path out) throws Exception {
        var same=method("sameDeclarationFrame",QuantiVar.class,QuantiVar.class,Map.class,Set.class);
        var retain=method("retainPaidImportDependencies",QuantiVar.class,Map.class,Map.class,Map.class,Set.class);
        String header="var sig File { var link: set File }\nvar sig Trash in File {}\n";
        String body=" | eventually (x in Trash and y in Trash and z in Trash) }\n";
        for (String mode:List.of("same","domain","dependency","ordered")) {
            String aDomain=mode.equals("domain")?"File":"x.link - y.link";
            String bDomain=switch(mode) {case "domain" -> "Trash";case "dependency" -> "x.link - Trash";case "ordered" -> "y.link - x.link";default -> aDomain;};
            String text=header+"pred a { once all x,y: File, z: "+aDomain+body
                +"pred b { eventually some x,y: File, z: "+bDomain+body;
            Path source=out.resolve("frame-"+mode+".als");Files.writeString(source,text,StandardOpenOption.CREATE_NEW);
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var row=new JSONObject().put("source",source.toString()).put("sha256",hash).put("left","a").put("right","b");
            var pair=LocalOwnerTraceTest.prepare(row);
            var a=delegate(pair.get(0)).comparisonForms().get(1).getMatrixQuantiVars();
            var b=delegate(pair.get(1)).comparisonForms().get(1).getMatrixQuantiVars();
            check(a.size()==3&&b.size()==3,"authentic three-slot source frame "+mode);
            var selected=new IdentityHashMap<QuantiVar,QuantiVar>();for(int i=0;i<3;i++)selected.put(a.get(i),b.get(i));
            var az=a.get(2);var bz=b.get(2);
            check(az.getExactAlloyType().sameOccurrenceEvidenceAs(bz.getExactAlloyType()),"same carrier control "+mode);
            boolean accepted=(Boolean)same.invoke(null,az,bz,selected,Collections.newSetFromMap(new IdentityHashMap<QuantiVar,Boolean>()));
            if(mode.equals("same")) {
                check(accepted,"same actual dependent domain and owners");
                check(az.getDeclarationFrames().get(0).dependencies().size()==2,"dependencies extracted, not []");
                var paid=new HashMap<String,String>();for(int i=0;i<3;i++)paid.put(a.get(i).getName(),b.get(i).getName());
                var fixed=new HashMap<String,String>();fixed.put(az.getName(),bz.getName());
                check((Boolean)retain.invoke(null,az,selected,paid,fixed,new HashSet<QuantiVar>()),"retain actual dependency mapping");
                check(fixed.equals(paid),"body mapping retains every actual domain dependency");
                fixed.put(a.get(0).getName(),b.get(1).getName());
                negative(!(Boolean)retain.invoke(null,az,selected,paid,fixed,new HashSet<QuantiVar>()),"body dependency mapping conflict");
                negative(!(Boolean)retain.invoke(null,az,selected,Map.of(az.getName(),bz.getName()),new HashMap<String,String>(),new HashSet<QuantiVar>()),"changed dependency requires its own selected payment");
                var frames=bz.getDeclarationFrames();var frame=frames.get(0);var deps=frame.dependencies();
                set(frame,"dependencies",List.of());
                negative(!(Boolean)same.invoke(null,az,bz,selected,new HashSet<QuantiVar>()),"erased dependency evidence");set(frame,"dependencies",deps);
                selected.put(a.get(0),b.get(1));
                negative(!(Boolean)same.invoke(null,az,bz,selected,new HashSet<QuantiVar>()),"substituted selected dependency owner");
            } else negative(!accepted,"same carrier different "+mode+" rejected");
            Files.writeString(out.resolve("frame-"+mode+".json"),row.put("accepted",accepted)
                .put("sourceDomain",az.getDeclarationFrames().get(0).domain().stableString())
                .put("targetDomain",bz.getDeclarationFrames().get(0).domain().stableString())
                .put("sourceDependencies",new JSONArray(az.getDeclarationFrames().get(0).dependencies().stream().map(QuantiVar::getDeBruijnKey).toList()))
                .put("targetDependencies",new JSONArray(bz.getDeclarationFrames().get(0).dependencies().stream().map(QuantiVar::getDeBruijnKey).toList())).toString(2),StandardOpenOption.CREATE_NEW);
        }
    }
    public static void main(String[] args) throws Exception {
        var id=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(id);var summary=new JSONArray();var associations=new JSONArray();
        for(int direction=0;direction<2;direction++) {
            var a=pair.get(direction);var b=pair.get(1-direction);var da=delegate(a);var db=delegate(b);
            Object av=variable(a),bv=variable(b);
            var aq=da.comparisonForms().get(1).getMatrixQuantiVars().get(0);
            var bq=db.comparisonForms().get(1).getMatrixQuantiVars().get(0);
            check(get(get(av,"descriptor"),"variable")==aq,"actual source declaration object");
            check(get(get(bv,"descriptor"),"variable")==bq,"actual target declaration object");
            check(da.comparisonForms().get(2).getInheritedQuantiVars().get(0)==aq,"source imported object, not metadata equality");
            check(db.comparisonForms().get(2).getInheritedQuantiVars().get(0)==bq,"target imported object, not metadata equality");
            var frame=method("paidImportFrame",av.getClass(),bv.getClass(),QuantiVar.class,QuantiVar.class,int.class,Map.class);
            var selectedFrame=new IdentityHashMap<QuantiVar,QuantiVar>();selectedFrame.put(aq,bq);
            check((Boolean)frame.invoke(null,av,bv,aq,bq,1,selectedFrame),"actual paid frame");
            check(aq.getDeclarationFrames().size()==1&&bq.getDeclarationFrames().size()==1,"retained original domain frames");
            check(aq.getDeclarationFrames().get(0).domain().equals(bq.getDeclarationFrames().get(0).domain()),"actual domains equal");
            check(aq.getDeclarationFrames().get(0).dependencies().isEmpty(),"actual File domain has no references");
            var old=av.getClass().getDeclaredMethod("compatibleWith",av.getClass());old.setAccessible(true);
            negative(!(Boolean)old.invoke(av,bv),"old free kind gate rejects");
            var foreign=delegate(LocalOwnerTraceTest.prepare(id).get(direction)).comparisonForms().get(1).getMatrixQuantiVars().get(0);
            negative(!(Boolean)frame.invoke(null,av,bv,foreign,bq,1,selectedFrame),"same metadata foreign declaration object");
            negative(!(Boolean)frame.invoke(null,av,bv,aq,foreign,1,selectedFrame),"foreign payment target");
            negative(!(Boolean)frame.invoke(null,av,bv,aq,bq,0,selectedFrame),"wrong payment owner phase");
            negative(!(Boolean)frame.invoke(null,av,av,aq,aq,1,selectedFrame),"no paid kind change");
            set(av,"ambiguous",true);
            negative(!(Boolean)frame.invoke(null,av,bv,aq,bq,1,selectedFrame),"ambiguous source identity");set(av,"ambiguous",false);
            set(bv,"ownerPhase",999);
            negative(!(Boolean)frame.invoke(null,av,bv,aq,bq,1,selectedFrame),"foreign target owner");set(bv,"ownerPhase",1);
            var fast=CanonicalRepairTraces.fast(a,b);
            var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var raw=new JSONObject().put("identity",id).put("direction",direction).put("fast",fast.json()).put("certified",cert.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),raw.toString(2),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),raw);
            var comparison=NativeOperationComparison.compare(a,b,fast,cert);raw.put("comparison",comparison.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),raw.toString(2),StandardOpenOption.CREATE_NEW);
            check(fast.distance()==3&&cert.distance()==3,"actual 2 temporal + 1 declaration + 0 matrix path");
            check(Canonical.distance(a,b)==fast.distance(),"numeric/captured selected path agree");
            check(fast.json().getInt("temporalDistance")==2&&fast.json().getInt("quantifierDistance")==1&&fast.json().getInt("matrixDistance")==0,"no scalar substitution");
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"source/path correspondence: "+comparison.reason());
            for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(comparison.evidence().getBoolean(flag),flag);
            var as=new ArrayList<Object>();var bs=new ArrayList<Object>();postorder(get(da,"temporalTree"),as);postorder(get(db,"temporalTree"),bs);
            var selected=new IdentityHashMap<Object,Object>();
            for(Object value:fast.json().getJSONObject("nativeTemporalProgram").getJSONArray("selectedCorrespondence")) {
                var p=(JSONArray)value;selected.put(as.get(p.getInt(0)-1),bs.get(p.getInt(1)-1));
            }
            var route=method("sameSelectedTemporalRoute",CanonicalDistance.Prepared.class,CanonicalDistance.Prepared.class,int.class,Map.class);
            check((Boolean)route.invoke(null,da,db,1,selected),"selected original declaration owner route");
            check((Boolean)route.invoke(null,da,db,2,selected),"selected original descendant route");
            negative(!(Boolean)route.invoke(null,da,db,2,new IdentityHashMap<>()),"missing temporal payment");
            Object original=selected.put(as.get(0),bs.get(1));
            negative(!(Boolean)route.invoke(null,da,db,2,selected),"wrong selected temporal occurrence");selected.put(as.get(0),original);
            set(bs.get(0),"incomingRole",1);
            negative(!(Boolean)route.invoke(null,da,db,2,selected),"wrong binary branch role");set(bs.get(0),"incomingRole",0);
            @SuppressWarnings("unchecked")
            var owners=(List<NormalForm>)get(bs.get(0),"owners");
            owners.add(db.comparisonForms().get(1));
            negative(!(Boolean)route.invoke(null,da,db,2,selected),"unary versus binary role shape");
            owners.remove(owners.size()-1);
            associations.put(new JSONObject().put("direction",direction).put("sourceDeclarationIsImportedObject",true)
                .put("targetDeclarationIsImportedObject",true).put("selectedOriginalTemporalRoutesChecked",true)
                .put("sourceBindingContexts",fast.json().getJSONArray("sourceBindingContexts"))
                .put("targetBindingContexts",fast.json().getJSONArray("targetBindingContexts"))
                .put("selectedDeclaration",fast.json().getJSONArray("nativeDeclarationPrograms").getJSONObject(1))
                .put("selectedTemporal",fast.json().getJSONObject("nativeTemporalProgram")));
            for(String mode:List.of("owner","slot","type","phase","mapping","cost","premise","occurrence","declaration","payment","temporal","construction")) {
                var bad=fast.json();var phase=bad.getJSONArray("phases").getJSONObject(2);
                var op=phase.getJSONObject("program").getJSONArray("operations").getJSONObject(2);
                switch(mode) {
                    case "owner","slot","type" -> bad.getJSONArray("sourceBindingContexts").getJSONObject(2).getJSONArray("bindings").getJSONObject(0).put(mode,"foreign");
                    case "phase" -> phase.put("phase",999);
                    case "mapping" -> phase.put("alphaMapping",new JSONObject().put("_q0","foreign"));
                    case "cost" -> op.put("cost",1);
                    case "premise" -> op.put("premise","foreign");
                    case "occurrence" -> {var e=new JSONObject(op.getJSONObject("before").getString("nativeEvidence"));e.put("sourceOccurrenceLineage",999999);op.getJSONObject("before").put("nativeEvidence",e.toString());}
                    case "declaration" -> bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(1).getJSONArray("operations").getJSONObject(0).put("cost",0);
                    case "payment" -> bad.put("selectedPaidDeclarationCorrespondence",new JSONObject().put("_q0","foreign"));
                    case "temporal" -> bad.getJSONObject("nativeTemporalProgram").put("selectedCorrespondence",new JSONArray());
                    case "construction" -> bad.put("constructionLink","foreign");
                }
                reject(()->fast.replay(bad),mode);
            }
            summary.put(new JSONObject().put("direction",direction).put("status",comparison.status()).put("fast",fast.distance()).put("certified",cert.distance()));
        }
        frameControls(out);
        var result=new JSONObject().put("identity",id).put("checks",checks).put("negativeControls",negatives)
            .put("directions",summary).put("associations",associations).put("publicCertification","BLOCKED");
        Files.writeString(out.resolve("summary.json"),result.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println(new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",summary));
    }
}

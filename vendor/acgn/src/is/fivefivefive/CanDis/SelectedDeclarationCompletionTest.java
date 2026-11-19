package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.*;

/** Original selected objects and fixed per-action replay, not equal scalar inference. */
public final class SelectedDeclarationCompletionTest {
    private static int checks,negatives,addedLinks;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static Field field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object get(Object o,String name)throws Exception{return field(o,name).get(o);}
    private static void set(Object o,String name,Object value)throws Exception{field(o,name).set(o,value);}
    private static Method method(Class<?> c,String name,Class<?>...types)throws Exception{var m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
    private static Object part(Object o,String name)throws Exception{return method(o.getClass(),name).invoke(o);}
    @FunctionalInterface private interface Attempt{void run()throws Exception;}
    private static void rejected(Attempt r,String why)throws Exception{boolean refused=false;try{r.run();}catch(InvocationTargetException e){refused=e.getCause() instanceof IllegalArgumentException||e.getCause() instanceof IllegalStateException;}catch(IllegalArgumentException|IllegalStateException e){refused=true;}negatives++;check(refused,why);}
    private static int[] ints(JSONArray a){return a.toList().stream().mapToInt(x->((Number)x).intValue()).toArray();}
    @SuppressWarnings("unchecked") private static JSONObject units(RepairView a,RepairView b,int source,int target)throws Exception{
        var captured=method(QuotientRepairDistance.class,"captureDecisions",RepairView.class,RepairView.class).invoke(null,a,b);var matrix=(JSONObject)part(captured,"matrix");var stats=part(captured,"decisions");
        var decisions=(List<MatrixDecision<Node>>)get(stats,"selectedPhases");var selected=decisions.get(0);int[] mapping=ints(matrix.getJSONArray("phases").getJSONObject(0).getJSONArray("alphaMapping"));
        int[] retained=mapping.clone();mapping[source]=-1;
        check(QuotientRepairDistance.verifyFixedMatrixReplay(a,b,retained,mapping,
                matrix.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"))==matrix.getInt("matrixDistance"),
                "removing the unqueried link preserves every selected matrix charge");
        var cls=Class.forName("is.fivefivefive.CanDis.metric.SelectedDeclarationCompletion");var ctor=cls.getDeclaredConstructor(RepairView.class,RepairView.class,MatrixDecision.class,int[].class);ctor.setAccessible(true);var helper=ctor.newInstance(a,b,selected,mapping);
        var allows=method(cls,"allows",int.class,Binding.class,int.class,Binding.class);var x=a.phases().get(0).bindings().get(source);var y=b.phases().get(0).bindings().get(target);
        check((Boolean)allows.invoke(helper,source,x,target,y),"actual fixed selected footprint permits proposed fresh pair");
        var xq=(QuantiVar)method(Binding.class,"originalBinding").invoke(x);var yq=(QuantiVar)method(Binding.class,"originalBinding").invoke(y);
        check(xq!=yq&&xq.isFrozenForCertification()&&yq.isFrozenForCertification(),"actual original frozen source bindings");
        for(var entry:List.of(new Object[]{x,"ordinal",999},new Object[]{x,"coordinate",999},new Object[]{x,"ownerPhase",1},new Object[]{x,"role",BindingRole.INHERITED},new Object[]{x,"bindingPath","foreign/owner"},new Object[]{x,"ownerContext","foreign/branch"})){
            var binding=(Binding)entry[0];String name=(String)entry[1];var old=get(binding,name);try{set(binding,name,entry[2]);rejected(()->allows.invoke(helper,source,x,target,y),"actual binding "+name);}finally{set(binding,name,old);}
        }
        var bindingCtor=Binding.class.getDeclaredConstructor(BindingRole.class,int.class,int.class,int.class,Declaration.class,String.class,String.class,List.class,boolean.class);bindingCtor.setAccessible(true);
        var foreign=(Binding)bindingCtor.newInstance(x.role(),x.ordinal(),x.ownerPhase(),x.coordinate(),x.declaration(),x.bindingPath(),x.ownerContext(),x.certifiedOrbit(),x.prenexPathErasureCertified());
        rejected(()->allows.invoke(helper,source,foreign,target,y),"equal-metadata foreign projected Binding is not issuance");
        var origin=get(x,"originalBinding");try{set(x,"originalBinding",get(y,"originalBinding"));rejected(()->allows.invoke(helper,source,x,target,y),"foreign physical source binding authority");}finally{set(x,"originalBinding",origin);}
        var reversed=new ArrayList<>(a.phases().get(0).bindings());Collections.reverse(reversed);var oldBindings=get(a.phases().get(0),"bindings");try{set(a.phases().get(0),"bindings",List.copyOf(reversed));rejected(()->allows.invoke(helper,source,x,target,y),"binding vector order is not coordinate or ordinal");}finally{set(a.phases().get(0),"bindings",oldBindings);}
        var reads=(Set<Long>)get(helper,"reads");check(!reads.isEmpty(),"actual selected VARIABLE comparisons captured");
        long query=reads.iterator().next();int i=(int)(query>>>32),j=(int)query;
        check(!(Boolean)allows.invoke(helper,i,a.phases().get(0).bindings().get(i),j,b.phases().get(0).bindings().get(j)),"new unpaid variable diagonal is forbidden");negatives++;
        var occupied=new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of("other","b"),Set.of("a"),Set.of("b"),true);
        check(!occupied.allows(0,0,0),"occupied target cannot be completed");negatives++;
        var rebound=new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of("a","other"),Set.of("a"),Set.of("b"),true);
        check(!rebound.allows(0,0,0),"occupied source cannot be rebound");negatives++;
        var old=new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of(),Set.of("a"),Set.of("b"),true);check(!old.allows(0,0,0),"independent exact old observed/observed rejection");
        var pending=new ArrayDeque<MatrixDecision<Node>>();pending.add(selected);Node variable=null;
        while(!pending.isEmpty()){var op=pending.remove();if(op.kind().equals("node")&&op.source().isVariable()&&op.target().isVariable()&&op.source().bindingIndex()>=0&&op.target().bindingIndex()>=0){variable=op.source();break;}if(op.selected()!=null)pending.add(op.selected());for(var edge:op.children())pending.add(edge.decision());}
        check(variable!=null,"foreign-object negative targets an actually queried variable");var raw=variable.originalVariableSource();
        var forged=new EGraphNode(raw.getId(),raw.getOpcode(),List.of(),raw.isCommutative(),raw.getMaxArity(),raw.isFlexibleArity(),raw.getMetatype(),raw.getSemanticProfile());
        for(String name:List.of("sourceName","sourceType","alphaName","exactAlloyType","semanticIdentity","sourceOccurrenceLineage","certificationOccurrenceLineage"))set(forged,name,get(raw,name));
        var actual=get(variable,"sourceOccurrence");try{set(variable,"sourceOccurrence",forged);rejected(()->ctor.newInstance(a,b,selected,mapping),"same-metadata foreign original variable object");}finally{set(variable,"sourceOccurrence",actual);}
        var sourceRoot=a.phases().get(0).matrix();var targetRoot=b.phases().get(0).matrix();
        var wrongRoot=MatrixDecision.node(targetRoot,targetRoot,0,0,false,List.of());
        rejected(()->ctor.newInstance(a,b,wrongRoot,mapping),"foreign same-shape selected source root");
        check((Boolean)allows.invoke(helper,source,x,target,y),"all source/census mutations restored");
        return new JSONObject().put("sourceVector",source).put("sourceCoordinate",x.coordinate()).put("sourceOrdinal",x.ordinal()).put("sourceOwner",x.bindingPath())
                .put("targetVector",target).put("targetCoordinate",y.coordinate()).put("targetOrdinal",y.ordinal()).put("targetOwner",y.bindingPath()).put("actualOriginalQuantiVarObjectsChecked",true)
                .put("actualSelectedDecisionObjectsChecked",true).put("selectedVariablePairs",reads.stream().map(v->List.of((int)(v>>>32),(int)(long)v)).toList()).put("oldObservedObservedRejected",true);
    }
    @SuppressWarnings("unchecked") private static JSONArray directUnqueriedLinks(RepairView a,RepairView b)throws Exception {
        var captured=method(QuotientRepairDistance.class,"captureDecisions",RepairView.class,RepairView.class).invoke(null,a,b);
        var matrix=(JSONObject)part(captured,"matrix");var stats=part(captured,"decisions");
        var selected=((List<MatrixDecision<Node>>)get(stats,"selectedPhases")).get(0);
        int[] mapping=ints(matrix.getJSONArray("phases").getJSONObject(0).getJSONArray("alphaMapping"));
        var cls=Class.forName("is.fivefivefive.CanDis.metric.SelectedDeclarationCompletion");
        var ctor=cls.getDeclaredConstructor(RepairView.class,RepairView.class,MatrixDecision.class,int[].class);ctor.setAccessible(true);
        var helper=ctor.newInstance(a,b,selected,mapping);var allows=method(cls,"allows",int.class,Binding.class,int.class,Binding.class);
        var sourceNodes=(Set<Node>)get(helper,"sourceNodes");var targetNodes=(Set<Node>)get(helper,"targetNodes");
        var links=new JSONArray();
        for(int i=0;i<mapping.length;i++)if(mapping[i]>=0) {
            int from=i,to=mapping[i];
            if(sourceNodes.stream().anyMatch(n->n.isVariable()&&n.bindingIndex()==from)
                    &&targetNodes.stream().anyMatch(n->n.isVariable()&&n.bindingIndex()==to)
                    &&(Boolean)allows.invoke(helper,from,a.phases().get(0).bindings().get(from),to,b.phases().get(0).bindings().get(to)))
                links.put(new JSONArray().put(from).put(to));
        }
        return links;
    }
    private static JSONObject run(JSONObject id,Path out,boolean natural)throws Exception{
        var pair=LocalOwnerTraceTest.prepare(id);var rows=new JSONArray();
        for(int direction=0;direction<2;direction++){
            var a=pair.get(direction);var b=pair.get(1-direction);var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));var f=CanonicalRepairTraces.fast(a,b);var selected=new JSONObject().put("fast",f.json()).put("certified",c.json());var replay=new JSONObject();
            QueueProbe.orderedReplay(f.distance(),c.distance(),()->f.replay(f.json()),()->c.replay(c.json()),replay);
            var result=NativeOperationComparison.compare(a,b,f,c);check(result.status()==NativeOperationComparison.Status.MATCHED,"actual both-arm source correspondence "+result.reason()+" "+result.evidence().opt("error"));
            for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(result.evidence().getBoolean(flag),flag);
            var arm=c.json();var component=arm.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1);var completion=component.getJSONObject("receipt").optJSONObject("matrixCorrespondenceCompletion");
            var links=new JSONArray();
            if(completion==null) {
                check(arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"direct native selected path is linked");
                for(var item:directUnqueriedLinks(c.projectedView(true),c.projectedView(false))) {
                    var edge=(JSONArray)item;links.put(units(c.projectedView(true),c.projectedView(false),edge.getInt(0),edge.getInt(1)));addedLinks++;
                }
            } else {
              check(completion.getBoolean("matrixActionsUnchanged")&&completion.getInt("replayedMatrixCost")==c.matrixDistance(),"unchanged fixed selected matrix actions");
              var filled=completion.getJSONObject("completedMapping");var original=completion.getJSONObject("selectedMapping");
              for(String key:filled.keySet())if(!original.has(key)){
                int i=Integer.parseInt(key),j=Integer.parseInt(filled.getString(key));var evidence=units(c.projectedView(true),c.projectedView(false),i,j);links.put(evidence);addedLinks++;
                var both=completion.getJSONArray("absencePremises").toList().stream().map(v->new JSONObject((Map<?,?>)v)).filter(v->v.getString("sourceBinding").equals(key)).findFirst().orElseThrow();
                check(!both.getBoolean("sourceAbsent")&&!both.getBoolean("targetAbsent")&&both.getBoolean("selectedPathNoNewVariableDiagonalChecked"),"new gate is selected footprint, not fake absence");
              }
            }
            check(links.length()>0,"actual observed unqueried links exercise completion authority");
            for(String armName:List.of("fast","certified"))for(String mode:List.of("owner","type","domain","order","payment","map")){
                var bad=new JSONObject(result.evidence().getJSONObject("diagnostic").toString());var data=bad.getJSONObject(armName);var programs=data.getJSONArray("nativeDeclarationPrograms");var program=programs.getJSONObject(armName.equals("fast")?0:1);
                switch(mode){case "owner" -> program.getJSONArray("source").getJSONObject(0).put("occurrence","foreign/owner");case "type" -> program.getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"foreignType");case "domain" -> program.getJSONArray("source").getJSONObject(0).put("admission","foreignDomain");case "order" -> {var ops=program.getJSONArray("operations");var first=ops.getJSONObject(0);ops.put(0,ops.getJSONObject(ops.length()-1));ops.put(ops.length()-1,first);}case "payment" -> data.put("distance",data.getInt("distance")+1);case "map" -> data.getJSONArray("phases").getJSONObject(0).put("alphaMapping",new JSONArray());}
                negatives++;check(NativeOperationComparison.compareCaptured(a,b,f,c,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"tampered selected "+armName+" "+mode);
            }
            check(selected.getJSONObject("fast").similar(f.json())&&selected.getJSONObject("certified").similar(c.json()),"same original native selected programs after checks");
            String name=id.getString("id")+"-"+direction+".json";Files.writeString(out.resolve(name),new JSONObject().put("identity",id).put("direction",direction).put("selected",selected).put("replay",replay).put("comparison",result.evidence()).put("testedUnqueriedLinks",links).put("selectionMode",completion==null?"DIRECT_PATH":"CHECKED_COMPLETION").put("completion",completion==null?JSONObject.NULL:completion).toString(),StandardOpenOption.CREATE_NEW);
            rows.put(new JSONObject().put("direction",direction).put("status",result.status()).put("fast",f.distance()).put("certified",c.distance()).put("receipt",name));
        }
        return new JSONObject().put("identity",id).put("natural",natural).put("directions",rows);
    }
    public static void main(String[] args)throws Exception{
        var id=new JSONArray(Files.readString(Path.of(args[0]))).getJSONObject(0).getJSONObject("identity");var out=Path.of(args[1]);Files.createDirectory(out);var cases=new JSONArray().put(run(id,out,true));
        String text=Files.readString(Path.of(id.getString("source"))).replace("lone g : Grade","some g : Grade");check(!text.equals(Files.readString(Path.of(id.getString("source")))),"held-out quantifier variant actually differs");var file=out.resolve("heldout-some.als");Files.writeString(file,text,StandardOpenOption.CREATE_NEW);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var fixture=new JSONObject(id.toString()).put("id","heldout-some").put("source",file.toString()).put("sha256",hash);cases.put(run(fixture,out,false));
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("negativeRejections",negatives).put("testedUnqueriedLinks",addedLinks).put("cases",cases).put("leanJobs",0).put("categoryCleared",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("SelectedDeclarationCompletionTest: "+checks+" checks, "+negatives+" negatives, "+addedLinks+" observed unqueried links tested");
    }
}

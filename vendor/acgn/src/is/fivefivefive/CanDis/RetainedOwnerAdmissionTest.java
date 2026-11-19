package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;

/** Actual retained scopes and selected paid typed reads, not capture-ID equality. */
public final class RetainedOwnerAdmissionTest {
    private static int checks,negatives,paidReads,integrityRejections,sourceObjectRejections;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Object get(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static Object part(Object o,String name)throws Exception{return method(o.getClass(),name).invoke(o);}
    private static int owners(NormalForm form)throws Exception{return (Integer)method(FastLocalPaidScope.class,"checkedOwnerCount",NormalForm.class).invoke(null,form);}
    private static void collect(EGraphNode n,Set<EGraphNode> nodes){if(!nodes.add(n))return;for(var c:n.getChildren())collect(c,nodes);}
    private static Object at(Object node,String path){var pieces=path.split("/");if(!pieces[0].equals("root"))throw new IllegalArgumentException("Read address root");for(int i=1;i<pieces.length;i+=2){if(!pieces[i].equals("child"))throw new IllegalArgumentException("Read address child");int index=Integer.parseInt(pieces[i+1]);node=node instanceof EGraphNode n?FastChainGeometry.children(n).get(index):((RepairView.Node)node).children().get(index);}return node;}
    private record Read(QuantiVar binding,EGraphNode original,Object selected,String occurrence,String carrier) { }
    private static Read read(Canonical.Prepared source,NativeRepairTrace trace,boolean projected,boolean sourceSide,int phase,String path)throws Exception{
        var original=source.normalizedForms().get(phase);var live=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());collect(original.getMatrixEGraph(),live);
        Object selected;EGraphNode raw;QuantiVar q;String occurrence;
        if(projected){var view=trace.projectedView(sourceSide);var n=(RepairView.Node)at(view.phases().get(phase).matrix(),path);selected=n;raw=n.originalVariableSource();
            if(!n.isVariable()||n.bindingIndex()<0||raw==null||!live.contains(raw))throw new IllegalArgumentException("Original projected typed read object");
            var binding=view.phases().get(phase).bindings().get(n.bindingIndex());
            var matches=original.getMatrixQuantiVars().stream().filter(v->v.getBindingPath().equals(binding.bindingPath())&&v.getName().equals(raw.getAlphaName())).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("Original projected binding owner");q=matches.get(0);
            if(!AlloyTypeBridge.graphType(q.getExactAlloyType()).equals(n.observationType()))throw new IllegalArgumentException("Original projected read carrier");
            occurrence=new JSONArray().put(false).put(binding.ownerPhase()).put(binding.ownerContext()).put(binding.coordinate()).put(binding.ordinal()).toString();
        }else{var form=trace.comparisonForms(sourceSide).get(phase);var n=(EGraphNode)at(form.getMatrixEGraph(),path);selected=n;raw=n.comparisonVariableSource();
            if(n.getOpcode()!=EGraphNode.Opcode.VARIABLE||raw==null||!live.contains(raw)||raw.getSourceOccurrenceLineage()!=n.getSourceOccurrenceLineage())throw new IllegalArgumentException("Original Fast typed read object");
            var matches=form.getMatrixQuantiVars().stream().filter(v->v.getName().equals(n.getAlphaName())&&v.getName().equals(raw.getAlphaName())).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("Original Fast binding owner");q=matches.get(0);
            if(!q.getExactAlloyType().sameOccurrenceEvidenceAs(n.getExactAlloyType()))throw new IllegalArgumentException("Original Fast read carrier");
            int ordinal=-1;for(int i=0;i<original.getMatrixQuantiVars().size();i++)if(original.getMatrixQuantiVars().get(i)==q)ordinal=i;
            occurrence="phase/"+phase+"/declaration/"+ordinal;
        }
        if(!q.isFrozenForCertification()||original.getMatrixQuantiVars().stream().noneMatch(v->v==q)||!q.getExactAlloyType().hasParserAuthenticatedAncestry()
                ||!q.getExactAlloyType().sameOccurrenceEvidenceAs(raw.getExactAlloyType()))throw new IllegalArgumentException("Original typed read binding");
        return new Read(q,raw,selected,occurrence,AlloyTypeBridge.graphType(q.getExactAlloyType()).toString());
    }
    private static JSONArray tuple(QuantiVar q){return new JSONArray().put(q.getQuantifier().name()).put(q.getTypeName()).put(q.getCardinality().name()).put(Integer.toString(q.getDisjointnessClass()));}
    private static JSONObject binding(Read r){var frames=new JSONArray();for(var f:r.binding().getDeclarationFrames())frames.put(new JSONObject().put("actualBindingObject",f.variable()==r.binding()).put("phase",f.owner().getPhasePath()).put("domain",f.domain().stableString()).put("dependencies",f.dependencies().stream().map(QuantiVar::getBindingPath).toList()));
        return new JSONObject().put("owner",r.binding().getBindingPath()).put("type",r.binding().getExactAlloyType().stableString()).put("carrier",r.carrier()).put("tuple",tuple(r.binding())).put("sourceFrames",frames).put("readSourceOccurrence",r.original().getSourceOccurrenceLineage());}
    private static JSONArray paid(Canonical.Prepared a,Canonical.Prepared b,NativeRepairTrace trace,boolean projected)throws Exception{
        var json=trace.json();var links=new JSONArray();for(int phase=0;phase<json.getJSONArray("phases").length();phase++){
            var operations=json.getJSONArray("phases").getJSONObject(phase).getJSONObject("program").getJSONArray("operations");
            for(int i=0;i<operations.length();i++){var op=operations.getJSONObject(i);if(op.isNull("source")||op.isNull("target")||op.getInt("cost")!=0)continue;
                if(!new JSONArray(op.getJSONObject("before").getString("head")).getString(0).equals("VARIABLE")||!new JSONArray(op.getJSONObject("after").getString("head")).getString(0).equals("VARIABLE"))continue;
                var before=new JSONObject(op.getJSONObject("before").getString("nativeEvidence"));var after=new JSONObject(op.getJSONObject("after").getString("nativeEvidence"));
                boolean localBefore=projected?before.getInt("bindingIndex")<0:before.getBoolean("localCoordinate"),localAfter=projected?after.getInt("bindingIndex")<0:after.getBoolean("localCoordinate");
                if(localBefore||localAfter){check(localBefore==localAfter,"local read is not captured by a paid outer link");continue;}
                var source=read(a,trace,projected,true,phase,op.getString("source"));var target=read(b,trace,projected,false,phase,op.getString("target"));if(source.carrier().equals(target.carrier()))continue;
                var matches=new ArrayList<JSONObject>();for(int program=0;program<json.getJSONArray("nativeDeclarationPrograms").length();program++){
                    var ds=json.getJSONArray("nativeDeclarationPrograms").getJSONObject(program).getJSONArray("operations");for(int j=0;j<ds.length();j++){var d=ds.getJSONObject(j);if(!d.isNull("source")&&!d.isNull("target")&&d.getJSONObject("source").getString("occurrence").equals(source.occurrence())&&d.getJSONObject("target").getString("occurrence").equals(target.occurrence()))matches.add(new JSONObject().put("program",program).put("index",j).put("operation",d));}}
                check(matches.size()==1,"actual selected declaration uniquely licenses typed read endpoints");var declaration=matches.get(0).getJSONObject("operation");
                check(declaration.getInt("cost")==1&&declaration.getString("kind").equals("update")&&declaration.getJSONObject("source").getJSONArray("tuple").similar(tuple(source.binding()))&&declaration.getJSONObject("target").getJSONArray("tuple").similar(tuple(target.binding())),"charged declaration1, not free alpha carrier equivalence");
                links.put(new JSONObject().put("phase",phase).put("matrixOperation",i).put("selectedDeclaration",matches.get(0)).put("matrixRead",op).put("source",binding(source)).put("target",binding(target)).put("actualSourceObjectsCheckedInSameExecution",true));paidReads++;
                String field=projected?"sourceOccurrence":"comparisonVariableSource";var saved=get(source.selected(),field);var foreign=target.original();set(source.selected(),field,foreign);
                try{try{read(a,trace,projected,true,phase,op.getString("source"));throw new AssertionError("Accepted foreign source read object");}catch(IllegalArgumentException expected){check(expected.getMessage().contains("read object"),"intended original source object boundary");negatives++;sourceObjectRejections++;}}finally{set(source.selected(),field,saved);}
                check(read(a,trace,projected,true,phase,op.getString("source")).original()==source.original(),"restored live read authority");
            }
        }return links;
    }
    private static List<EGraphNode> nodes(EGraphNode root){var set=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());collect(root,set);return new ArrayList<>(set);}
    private static void scopeNegatives(Canonical.Prepared prepared)throws Exception{
        var form=CanonicalDistance.prepare(prepared.normalizedForms()).comparisonForms().get(0);check(owners(form)>1,"actual retained multi-owner plan admitted");
        var all=nodes(form.getMatrixEGraph());var owner=all.stream().filter(n->n.getOpcode()==EGraphNode.Opcode.COMPREHENSION).findFirst().orElseThrow();
        var declaration=owner.getChildren().get(0);var slot=declaration.getChildren().get(1);var domain=declaration.getChildren().get(0);
        for(var change:List.of(new Object[]{owner,"sourceOccurrenceLineage",-1L},new Object[]{declaration,"sourceOccurrenceLineage",-1L},new Object[]{slot,"sourceOccurrenceLineage",-1L},new Object[]{domain,"sourceOccurrenceLineage",-1L},new Object[]{slot,"alphaName","foreign-slot"},new Object[]{slot,"exactAlloyType",null},new Object[]{domain,"opcode",EGraphNode.Opcode.SETOF})){
            var saved=get(change[0],(String)change[1]);set(change[0],(String)change[1],change[2]);try{check(owners(form)==0,"retained source gate rejects "+change[1]);negatives++;}finally{set(change[0],(String)change[1],saved);}check(owners(form)>1,"restored retained scope");
        }
        var original=form.comparisonSource();var saved=get(form,"certificationMatrixEGraphRoot");var foreign=CanonicalDistance.prepare(prepared.normalizedForms()).comparisonForms().get(0).getMatrixEGraph();set(form,"certificationMatrixEGraphRoot",foreign);
        try{check(owners(form)==0,"comparison display cannot impersonate original source plan");negatives++;}finally{set(form,"certificationMatrixEGraphRoot",saved);}check(owners(form)>1&&form.comparisonSource()==original,"source preserved after scope controls");
        planNegatives(form);
    }
    @SuppressWarnings("unchecked")
    private static void planNegatives(NormalForm form)throws Exception{
        var constructor=FastLocalPaidScope.class.getDeclaredConstructor(is.fivefivefive.ACGN.alloy.ExactAlloyType.class);constructor.setAccessible(true);var context=constructor.newInstance(form.getMatrixQuantiVars().get(0).getExactAlloyType());
        ((Set<QuantiVar>)get(context,"outerBindings")).addAll(form.getMatrixQuantiVars());var locals=(Map<String,QuantiVar>)get(context,"locals");for(var q:form.getLocalQuantiVars())locals.put(q.getName(),q);
        var outer=new HashMap<String,QuantiVar>();for(var q:form.getMatrixQuantiVars())outer.put(q.getName(),q);var walk=method(FastLocalPaidScope.class,"walk",EGraphNode.class,Map.class,int.class,long.class);
        check((Boolean)walk.invoke(context,form.getCertificationMatrixEGraph(),outer,0,Long.MIN_VALUE),"actual original source walk");var plans=(Map<Long,Object>)get(context,"sourcePlans");var saved=new HashMap<>(plans);boolean old=true;
        for(var plan:plans.values()){var group=((List<?>)part(plan,"groups")).get(0);var slot=((List<?>)part(group,"slots")).get(0);var q=(QuantiVar)part(slot,"binding");old&=q.getQuantifier()==QuantiVar.Quantifier.ALL||q.getQuantifier()==QuantiVar.Quantifier.SOME;}
        check(plans.size()>1&&!old,"actual old multi-owner kind whitelist rejects this retained context");set(context,"checkingSource",false);
        var key=plans.keySet().iterator().next();var plan=plans.get(key);var groups=(List<?>)part(plan,"groups");var parent=(Long)part(plan,"parent");var pc=plan.getClass().getDeclaredConstructor(List.class,long.class);pc.setAccessible(true);
        var alternatives=new ArrayList<Map<Long,Object>>();var missing=new HashMap<>(saved);missing.remove(key);alternatives.add(missing);
        var duplicate=new HashMap<>(saved);long extra=Long.MAX_VALUE;while(duplicate.containsKey(extra))extra--;duplicate.put(extra,plan);alternatives.add(duplicate);
        var wrongParent=new HashMap<>(saved);wrongParent.put(key,pc.newInstance(groups,parent+1));alternatives.add(wrongParent);
        var group=groups.get(0);var slots=new ArrayList<Object>((List<?>)part(group,"slots"));var slot=slots.get(0);var q=(QuantiVar)part(slot,"binding");
        var fake=new QuantiVar(q.getId(),q.getName(),q.getOriginalNames().iterator().next(),q.getTypeName());for(var name:q.getOriginalNames())fake.addOriginalName(name);fake.mergeExactAlloyType(q.getExactAlloyType());fake.setBindingPath(q.getBindingPath());fake.setDeBruijnKey(q.getDeBruijnKey());fake.setCardinality(q.getCardinality());fake.setQuantifier(q.getQuantifier());fake.setDisjointnessClass(q.getDisjointnessClass());fake.freezeForCertification();
        var sc=slot.getClass().getDeclaredConstructor(QuantiVar.class,long.class);sc.setAccessible(true);slots.set(0,sc.newInstance(fake,(Long)part(slot,"variable")));var gc=group.getClass().getDeclaredConstructor(List.class,long.class,EGraphNode.class);gc.setAccessible(true);
        var changedGroups=new ArrayList<Object>(groups);changedGroups.set(0,gc.newInstance(List.copyOf(slots),(Long)part(group,"declaration"),(EGraphNode)part(group,"domain")));var wrongSlot=new HashMap<>(saved);wrongSlot.put(key,pc.newInstance(List.copyOf(changedGroups),parent));alternatives.add(wrongSlot);
        for(var replacement:alternatives){plans.clear();plans.putAll(replacement);((Set<?>)get(context,"usedOwners")).clear();int expected=0;for(var p:plans.values())for(var g:(List<?>)part(p,"groups"))expected+=((List<?>)part(g,"slots")).size();
            boolean accepted=(Boolean)walk.invoke(context,form.getMatrixEGraph(),outer,0,Long.MIN_VALUE)&&((Set<?>)get(context,"usedOwners")).size()==expected;check(!accepted,"missing/duplicate/parent/foreign same-metadata slot plan rejected");negatives++;}
        plans.clear();plans.putAll(saved);((Set<?>)get(context,"usedOwners")).clear();check((Boolean)walk.invoke(context,form.getMatrixEGraph(),outer,0,Long.MIN_VALUE),"restored authentic ordered owner plan");
    }
    private static JSONObject run(JSONObject id,Path out,boolean natural)throws Exception{
        var pair=LocalOwnerTraceTest.prepare(id);if(natural)scopeNegatives(pair.stream().filter(p->!p.normalizedForms().get(0).getLocalQuantiVars().isEmpty()).findFirst().orElseThrow());var rows=new JSONArray();
        for(int d=0;d<2;d++){var a=pair.get(d);var b=pair.get(1-d);var f=CanonicalRepairTraces.fast(a,b);var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));var selected=new JSONObject().put("fast",f.json()).put("certified",c.json());
            var replay=new JSONObject();QueueProbe.orderedReplay(f.distance(),c.distance(),()->f.replay(f.json()),()->c.replay(c.json()),replay);
            var links=new JSONObject().put("fast",paid(a,b,f,false)).put("certified",paid(a,b,c,true));if(natural)check(links.getJSONArray("fast").length()>0&&links.getJSONArray("certified").length()>0,"actual changed-carrier paid reads in both arms");
            var result=NativeOperationComparison.compare(a,b,f,c);check(result.status()==NativeOperationComparison.Status.MATCHED,"both-direction source correspondence "+id.getString("id")+" "+d+" "+result.reason()+" "+result.evidence().opt("error"));
            var report=result.evidence().getJSONObject("diagnostic");if(natural)for(String arm:List.of("fast","certified"))for(String mode:List.of("payment","type","source","selection")){var corrupt=new JSONObject(report.toString());var x=corrupt.getJSONObject(arm);switch(mode){case "payment" -> x.getJSONArray("nativeDeclarationPrograms").getJSONObject(arm.equals("fast")?0:1).getJSONArray("operations").getJSONObject(0).put("cost",0);case "type" -> x.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(4).getJSONObject("before").put("head","foreign");case "source" -> x.put("sourceProducerObservationDigest","foreign");case "selection" -> x.put("selectedPaidDeclarationCorrespondence",new JSONObject());}check(NativeOperationComparison.compareCaptured(a,b,f,c,corrupt).status()==NativeOperationComparison.Status.INVALID_REPLAY,"immutable selected "+mode);negatives++;integrityRejections++;}
            check(selected.getJSONObject("fast").similar(f.json())&&selected.getJSONObject("certified").similar(c.json()),"same complete selected paths after source-link verification");String file=id.getString("id")+"-"+d+".json";
            Files.writeString(out.resolve(file),new JSONObject().put("identity",id).put("direction",d).put("selected",selected).put("fast",f.json()).put("certified",c.json()).put("comparison",result.evidence()).put("paidReadLinks",links).put("replay",replay).toString(),StandardOpenOption.CREATE_NEW);
            rows.put(new JSONObject().put("direction",d).put("status",result.status()).put("fast",f.distance()).put("certified",c.distance()).put("receipt",file));
        }return new JSONObject().put("identity",id).put("directions",rows);
    }
    public static void main(String[]args)throws Exception{
        var id=new JSONArray(Files.readString(Path.of(args[0]))).getJSONObject(0).getJSONObject("identity");var out=Path.of(args[1]);Files.createDirectory(out);var cases=new JSONArray();cases.put(run(id,out,true));
        for(String kind:List.of("some","lone")){String body="all s:A | some s.r and some {x,y:A | "+kind+" z:A | x in z.r and y in z.r}";String text="sig A {r:set A} pred a {"+body+"} pred b {"+body+"}";var file=out.resolve("retained-"+kind+".als");Files.writeString(file,text,StandardOpenOption.CREATE_NEW);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            var fixture=new JSONObject().put("id","retained-"+kind).put("source",file.toString()).put("sha256",hash).put("left","a").put("right","b");var prepared=LocalOwnerTraceTest.prepare(fixture);check(owners(CanonicalDistance.prepare(prepared.get(0).normalizedForms()).comparisonForms().get(0))>1,"structural retained/held-out owner kind "+kind);cases.put(run(fixture,out,false));}
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("negativeRejections",negatives).put("retainedReportIntegrityRejections",integrityRejections).put("actualSourceReadObjectRejections",sourceObjectRejections).put("actualScopePlanRejections",negatives-integrityRejections-sourceObjectRejections).put("paidReadLinks",paidReads).put("cases",cases).put("leanJobs",0).put("categoryClearance",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("RetainedOwnerAdmissionTest: "+checks+" checks, "+negatives+" negative rejections, "+paidReads+" authentic paid reads");
    }
}

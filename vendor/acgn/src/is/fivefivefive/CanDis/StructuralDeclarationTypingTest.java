package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.RepairView;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.theory.StructuralKey;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;

public final class StructuralDeclarationTypingTest {
    private static int checks,negatives;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static EGraphNode find(EGraphNode n){if(n.getOpcode()==EGraphNode.Opcode.DISJ)return n;for(var c:n.getChildren()){var r=find(c);if(r!=null)return r;}return null;}
    private static Object get(Object o,String field)throws Exception{var f=o.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String field,Object value)throws Exception{var f=o.getClass().getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
    private static Method method(Class<?> c,String name,Class<?>...args)throws Exception{var m=c.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static void reject(Runnable action,String why){checks++;negatives++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Accepted "+why);}
    private static RepairView.Node projected(RepairView.Node n){if(n.operator().equals("DISJ"))return n;for(var c:n.children()){var r=projected(c);if(r!=null)return r;}for(var c:n.certifiedAlternatives()){var r=projected(c);if(r!=null)return r;}return null;}
    private static void origins(NormalForm phase,EGraphNode n,JSONArray rows){if(n.getOpcode()==EGraphNode.Opcode.DISJ){var o=phase.structuralDeclarationOrigin(n);check(o!=null&&o.valid(),"derived source declaration authority");rows.put(o.evidence());}for(var c:n.getChildren())origins(phase,c,rows);}
    private static Canonical.Prepared prepareText(String text,String predicate)throws Exception{var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var v=new MASGVisitor(new GlobalVariables(),Set.of(predicate),module);v.visit(new ModelUnit(null,module),null);return Canonical.prepare(v.getForest().get(v.getForestId(predicate)));}
    public static void main(String[]args)throws Exception{
        var identity=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(identity);var original=pair.get(0).normalizedForms().get(0);var declaration=find(original.getCertificationMatrixEGraph());
        check(declaration!=null,"actual retained DISJ declaration");
        check(declaration.getMetatype()!=EGraphNode.Metatype.BOOLEAN&&declaration.getExactAlloyType()==null,"exact old result-type gate rejection");
        var origin=original.structuralDeclarationOrigin(declaration);
        check(origin!=null&&origin.valid(),"source-issued original structural group");
        var results=new JSONArray();
        for(int d=0;d<2;d++){
            var a=pair.get(d);var b=pair.get(1-d);var fast=CanonicalRepairTraces.fast(a,b);var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var row=new JSONObject().put("identity",identity).put("direction",d).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("selected-"+d+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),row);
            var result=NativeOperationComparison.compare(a,b,fast,certified);row.put("comparison",result.evidence());
            Files.writeString(out.resolve("replayed-"+d+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            check(!result.reason().equals("LITERAL_TYPE_AUTHORITY_UNSUPPORTED"),"structural admission replaces old expression-type refusal");
            check(result.status()==NativeOperationComparison.Status.MATCHED,"complete native/source correspondence "+result.status()+" "+result.reason());
            check(Canonical.distance(a,b)==fast.distance(),"numeric/captured parity");results.put(new JSONObject().put("direction",d).put("fast",fast.distance()).put("certified",certified.distance()));
            var report=result.evidence().getJSONObject("diagnostic");
            for(var mode:List.of("declaration-cost","declaration-kind","declaration-path","missing-slot","reordered-slots","coherent-type")){
                var bad=fast.json();var ops=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations");
                int index=-1;for(int i=0;i<ops.length();i++){var op=ops.getJSONObject(i);var payload=op.optJSONObject(d==0?"before":"after");if(payload!=null&&payload.getString("head").startsWith("[\"DISJ\"")){index=i;break;}}
                check(index>=0,"selected exact DISJ payment");var op=ops.getJSONObject(index);
                switch(mode){
                    case "declaration-cost" -> op.put("cost",0);
                    case "declaration-kind" -> op.put("kind","metric-align");
                    case "declaration-path" -> op.put(d==0?"source":"target","root/foreign");
                    case "missing-slot" -> ops.remove(index+2);
                    case "reordered-slots" -> {var x=ops.get(index+2);ops.put(index+2,ops.get(index+3));ops.put(index+3,x);}
                    case "coherent-type" -> {var head=op.getJSONObject(d==0?"before":"after");head.put("head",head.getString("head").replace("DISJ","GENERICRELDECL"));var metadata=new JSONObject(head.getString("nativeEvidence"));metadata.put("sourceType","DECLROOT");head.put("nativeEvidence",metadata.toString());}
                }
                reject(()->fast.replay(bad),mode);
            }
            var q=original.getLocalQuantiVars().get(0);var frame=q.getDeclarationFrames().get(0);var domain=declaration.getChildren().get(0);var variable=declaration.getChildren().get(1);
            var foreignPhase=LocalOwnerTraceTest.prepare(identity).get(0).normalizedForms().get(0);var foreign=find(foreignPhase.getCertificationMatrixEGraph());
            var changes=new ArrayList<Object[]>();
            changes.add(new Object[]{origin.originalOwner(),"opcode",EGraphNode.Opcode.EXISTS});
            changes.add(new Object[]{declaration,"metatype",EGraphNode.Metatype.BOOLEAN});
            changes.add(new Object[]{declaration,"opcode",EGraphNode.Opcode.DISJVAR});
            changes.add(new Object[]{declaration,"sourceOccurrenceLineage",999999L});
            changes.add(new Object[]{domain,"exactAlloyType",ExactAlloyType.boolType()});
            changes.add(new Object[]{variable,"exactAlloyType",ExactAlloyType.boolType()});
            changes.add(new Object[]{variable,"exactAlloyType",foreign.getChildren().get(1).getExactAlloyType()});
            changes.add(new Object[]{q,"disjointnessClass",0});changes.add(new Object[]{q,"cardinality",QuantiVar.Cardinality.SET});
            changes.add(new Object[]{q,"quantifier",QuantiVar.Quantifier.SOME});changes.add(new Object[]{original,"phasePath","foreign-phase"});
            changes.add(new Object[]{frame,"dependencies",List.of(q)});changes.add(new Object[]{frame,"owner",foreignPhase});
            changes.add(new Object[]{frame,"domain",StructuralKey.of("foreign-domain",List.of(),List.of())});
            var slots=new ArrayList<>((List<?>)get(declaration,"childClasses"));Collections.swap(slots,1,2);changes.add(new Object[]{declaration,"childClasses",slots});
            for(var change:changes){var object=change[0];var field=(String)change[1];var saved=get(object,field);set(object,field,change[2]);
                try{check(!origin.valid(),"live original "+field+" rejected");negatives++;check(NativeOperationComparison.compareCaptured(a,b,fast,certified,new JSONObject(report.toString())).status()!=NativeOperationComparison.Status.MATCHED,"unchanged receipt cannot reauthorize "+field);negatives++;}
                finally{set(object,field,saved);}
            }
            var comparison=CanonicalDistance.prepare(pair.get(0).normalizedForms()).comparisonForms().get(0);var selected=find(comparison.getMatrixEGraph());
            check(origin.admits(selected),"actual comparison object association");
            for(var node:List.of(declaration,selected)){
                var ref=node.getChildClasses().get(0);var invocation=get(ref,"slotMap");var changed=new LinkedHashMap<String,String>();changed.putAll(ref.getSlotMap());changed.put("foreign-slot","foreign-owner");set(ref,"slotMap",changed);
                try{check(node==declaration?!origin.valid():!origin.admits(selected),"original/comparison invocation substitution");negatives++;}finally{set(ref,"slotMap",invocation);}
            }
            var foreignComparison=CanonicalDistance.prepare(List.of(foreignPhase)).comparisonForms().get(0);var fake=find(foreignComparison.getMatrixEGraph());
            set(fake,"sourceOccurrenceLineage",selected.getSourceOccurrenceLineage());set(fake,"sourceName",selected.getSourceName());set(fake,"sourceType",selected.getSourceType());
            check(!origin.admits(fake),"coherently relabelled foreign declaration lacks original object authority");negatives++;
            var savedRoot=get(original,"certificationMatrixEGraphRoot");var savedLive=get(original,"matrixEGraphRoot");
            set(original,"certificationMatrixEGraphRoot",foreignPhase.getCertificationMatrixEGraph());set(original,"matrixEGraphRoot",foreignPhase.getMatrixEGraph());
            try{check(!origin.valid(),"coherent original/live root substitution");negatives++;}finally{set(original,"certificationMatrixEGraphRoot",savedRoot);set(original,"matrixEGraphRoot",savedLive);}
            var endpointClass=Class.forName("is.fivefivefive.CanDis.NativeOperationComparison$Endpoint");var constructor=endpointClass.getDeclaredConstructor(Canonical.Prepared.class);constructor.setAccessible(true);var endpoint=constructor.newInstance(pair.get(0));
            var head=method(endpointClass,"fastHead",EGraphNode.class);var expression=origin.originalOwner();var expressionType=get(expression,"exactAlloyType");var expressionRole=get(expression,"metatype");
            set(expression,"exactAlloyType",null);set(expression,"metatype",EGraphNode.Metatype.CONTROL);
            try{try{head.invoke(endpoint,expression);throw new AssertionError("Accepted untyped ordinary expression");}catch(InvocationTargetException expected){check(expected.getCause() instanceof RuntimeException,"ordinary untyped expression still refuses");negatives++;}}
            finally{set(expression,"exactAlloyType",expressionType);set(expression,"metatype",expressionRole);}
            var view=(RepairView)get(endpoint,"view");var projected=projected(view.phases().get(0).matrix());check(projected!=null,"actual selected certified declaration node");
            var priorSource=get(projected,"sourceOccurrence");set(projected,"sourceOccurrence",foreign);
            try{try{method(NativeOperationComparison.class,"bridge",endpointClass).invoke(null,endpoint);throw new AssertionError("Accepted foreign certified construction association");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException,"certified pointer not metadata authorizes bridge");negatives++;}}
            finally{set(projected,"sourceOccurrence",priorSource);}
            check(origin.valid()&&fast.replay(fast.json())==28,"restored authentic source and replay");
        }
        var derivedText="var sig N { var edge: set N }\npred specimen { always (no edge and (all disj x,y,z:N | some disj p,q:x.edge | no p.edge & q.edge and y in N and z in N)) }";
        Files.writeString(out.resolve("derived-dependent-phase.als"),derivedText,StandardOpenOption.CREATE_NEW);var derived=prepareText(derivedText,"specimen");var derivedGroups=new JSONArray();
        for(var phase:derived.normalizedForms())origins(phase,phase.getCertificationMatrixEGraph(),derivedGroups);
        check(derivedGroups.length()>=2,"derived grouped and dependent structural controls");
        boolean dependent=false,three=false,nonzero=false;for(var value:derivedGroups){var row=(JSONObject)value;nonzero|=!row.getString("phase").equals("phase[0]");three|=row.getJSONArray("slots").length()==3;for(var slot:row.getJSONArray("slots"))dependent|=!((JSONObject)slot).getJSONArray("dependencies").isEmpty();}
        check(dependent&&three&&nonzero,"ordered dependency, three slots and nonzero phase retained");
        for(var phase:derived.normalizedForms())for(var q:phase.getLocalQuantiVars())for(var frame:q.getDeclarationFrames())if(!frame.dependencies().isEmpty()){
            var groups=new ArrayDeque<EGraphNode>();groups.add(phase.getCertificationMatrixEGraph());while(!groups.isEmpty()){
                var node=groups.remove();if(node.getOpcode()==EGraphNode.Opcode.DISJ){var authority=phase.structuralDeclarationOrigin(node);if(authority!=null&&authority.evidence().getJSONArray("slots").getJSONObject(0).getString("binding").equals(q.getDeBruijnKey())){
                    var dependency=frame.dependencies().get(0);var type=get(dependency,"exactAlloyType");set(dependency,"exactAlloyType",ExactAlloyType.boolType());
                    try{check(!authority.valid(),"actual dependent owner type substitution");negatives++;}finally{set(dependency,"exactAlloyType",type);}
                }}groups.addAll(node.getChildren());
            }
        }
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",results).put("origin",origin.evidence()).put("derivedControls",derivedGroups).put("publicCertification","BLOCKED");
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary);
    }
}

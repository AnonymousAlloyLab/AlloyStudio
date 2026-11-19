package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.Node;

/** Source-linked CALL and alternative-census controls, separate from metrics. */
public final class ClosedSourceCensusTest {
    private static int checks,negatives;
    private static final StringBuilder lean=new StringBuilder("import Focus40Congruence\nnamespace Focus40Generated\nopen ProjectedAlternativeCensus\n");
    private static int nodeId;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static Method method(Class<?> c,String name,Class<?>... types)throws Exception {
        var m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m;
    }
    private static void set(Object o,String name,Object value)throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    private static EGraphNode copy(EGraphNode n)throws Exception {
        var children=new ArrayList<EGraphNode>();for(var c:n.getChildren())children.add(copy(c));
        return (EGraphNode)method(EGraphNode.class,"comparisonWithChildren",EGraphNode.class,List.class).invoke(null,n,children);
    }
    private static List<EGraphNode> calls(EGraphNode n){
        var result=new ArrayList<EGraphNode>();if(n.getOpcode()==EGraphNode.Opcode.CALL)result.add(n);
        for(var c:n.getChildren())result.addAll(calls(c));return result;
    }
    private static boolean oldClosed(EGraphNode n) {
        var metadata=CallMetadata.require(n);var type=n.getExactAlloyType();
        if(!metadata.kind().equals("call/expression")||!metadata.authority().name().equals("TYPECHECKED_IMPORT")
                ||metadata.arity()>64||n.isOrderInsensitive()||type==null||type.kind()!=ExactAlloyType.Kind.RELATION
                ||!type.hasParserAuthenticatedAncestry())return false;
        for(var c:n.getChildren())if(c.getOpcode()!=EGraphNode.Opcode.GLOBALBINDING||!c.getChildren().isEmpty()
                ||c.getSemanticIdentity()==null||c.getAlphaName()!=null||c.getExactAlloyType()==null
                ||!c.getExactAlloyType().sharesParserModuleAuthorityWith(type))return false;
        return true;
    }
    private static void rejectCall(ClosedCallEvidence evidence,EGraphNode n,String reason) {
        boolean accepted=false;try{accepted=evidence.admits(n);}catch(IllegalArgumentException|IllegalStateException expected){}
        negatives++;check(!accepted,reason);
    }
    private static String quote(Object value){return JSONObject.quote(String.valueOf(value));}
    private static String shape(EGraphNode n) {
        String children=".nil";var cs=n.getChildren();
        for(int i=cs.size()-1;i>=0;i--)children="(.cons "+shape(cs.get(i))+" "+children+")";
        var metadata=List.of(String.valueOf(n.getMetatype()),String.valueOf(n.isFlexibleArity()),String.valueOf(n.isOrderInsensitive()),
                String.valueOf(n.getMaxArity()),n.getSemanticProfile().fingerprint());
        String head="(ClosedSourceArguments.head "+quote(n.getOpcode())+" "+quote(n.getExactAlloyType().stableString())
                +" "+quote(n.getSourceName())+" "+n.getSourceOccurrenceLineage()+")";
        head="{ "+head+" with identity := "+quote(n.getSemanticIdentity())+", metadata := ["+String.join(",",metadata.stream().map(ClosedSourceCensusTest::quote).toList())+"] }";
        return "(.node "+head+" "+children+")";
    }
    private static void callControls(Canonical.Prepared original,NormalForm comparison,String suffix)throws Exception {
        var form=original.normalizedForms().get(0);var evidence=ClosedCallEvidence.from(form);
        for(var current:calls(comparison.getMatrixEGraph())) {
            if(oldClosed(current))continue;
            var source=calls(form.getCertificationMatrixEGraph()).stream().filter(c->c.getSourceOccurrenceLineage()==current.getSourceOccurrenceLineage()).findFirst().orElseThrow();
            check(evidence.admits(current),"new closed compound source admission: sourceClosed="+ClosedCallEvidence.closed(source)
                    +", currentClosed="+ClosedCallEvidence.closed(current)+", same="+BoundCallEvidence.sameSourceScopedCall(source,current)
                    +", source="+shape(source)+", current="+shape(current));
            check(!oldClosed(source),"exact old closed gate rejected");
            check(!BoundCallEvidence.from(form).admits(current),"old bound-call gate rejected");
            String name="call"+suffix;
            lean.append("def ").append(name).append("Source : Focused225CallViews.Shape := ").append(shape(source)).append('\n');
            lean.append("def ").append(name).append("Current : Focused225CallViews.Shape := ").append(shape(current)).append('\n');
            lean.append("def ").append(name).append("SourceArgument : Focused225CallViews.Shape := ").append(shape(source.getChildren().get(0))).append('\n');
            lean.append("def ").append(name).append("CurrentArgument : Focused225CallViews.Shape := ").append(shape(current.getChildren().get(0))).append('\n');
            lean.append("theorem ").append(name).append("_union : (Category72.Calls.leaves (ClosedSourceArguments.unionView ")
                .append(name).append("SourceArgument)).Perm (Category72.Calls.leaves (ClosedSourceArguments.unionView ")
                .append(name).append("CurrentArgument)) := by decide\n");
            for(var change:List.of(new Object[]{"callSourceAnchor",new Object()},new Object[]{"semanticIdentity","foreign/callee"},
                    new Object[]{"sourceOccurrenceLineage",-1L},new Object[]{"callOccurrenceId",99999L},
                    new Object[]{"exactAlloyType",ExactAlloyType.unaryRelation("Position")},new Object[]{"declaredArity",2})) {
                var bad=copy(current);set(bad,(String)change[0],change[1]);rejectCall(evidence,bad,"CALL corruption "+change[0]);
            }
            var bad=copy(current);var plus=bad.getChildren().get(0);var integer=plus.getChildren().stream().filter(n->n.getOpcode()==EGraphNode.Opcode.CONSTANT).findFirst().orElseThrow();
            check(integer.getOpcode()==EGraphNode.Opcode.CONSTANT,"natural integer source leaf");
            set(integer,"sourceName","2");rejectCall(evidence,bad,"changed nested integer");
            bad=copy(current);plus=bad.getChildren().get(0);var join=plus.getChildren().stream().filter(n->n.getOpcode()==EGraphNode.Opcode.JOIN).findFirst().orElseThrow();
            var field=EGraphNode.class.getDeclaredField("childClasses");field.setAccessible(true);
            var children=new ArrayList<>((List<?>)field.get(join));Collections.reverse(children);field.set(join,children);
            rejectCall(evidence,bad,"swapped ordered JOIN operands");
            var forged=new EGraphNode(current.getId(),current.getOpcode(),List.of(),current.isCommutative(),current.getMaxArity(),current.isFlexibleArity(),current.getMetatype(),current.getSemanticProfile());
            for(String fieldName:List.of("childClasses","sourceOccurrenceLineage","callOccurrenceId","declaredArity","callArityAuthority","sourceName","sourceType","exactAlloyType","semanticIdentity")) {
                var f=EGraphNode.class.getDeclaredField(fieldName);f.setAccessible(true);f.set(forged,f.get(current));
            }
            rejectCall(evidence,forged,"copied metadata is not an issued anchor");
            check(evidence.admits(current),"original remains accepted after controls");
        }
    }
    private static int census(Node node)throws Exception {
        int[] remaining={512};method(NativeOperationComparison.class,"projectedCensus",Node.class,int[].class,int.class).invoke(null,node,remaining,0);
        return 512-remaining[0];
    }
    private static void rejectCensus(Node node,String reason)throws Exception {
        boolean rejected=false;try{census(node);}catch(InvocationTargetException expected){rejected=expected.getCause() instanceof RuntimeException;}
        negatives++;check(rejected,reason);
    }
    private static Node node(String payload,List<Node> children,List<Node> alternatives)throws Exception {
        var c=Node.class.getDeclaredConstructor(String.class,String.class,String.class,String.class,int.class,RepairView.ContainerKind.class,boolean.class,List.class,List.class);
        c.setAccessible(true);return c.newInstance("AND",payload,payload,null,-1,RepairView.ContainerKind.SET,true,children,alternatives);
    }
    private static String inventory(Node n,int depth) {
        boolean valid=depth<=48&&n.children().size()<=64&&(n.semanticPayload()==null||n.semanticPayload().length()<=16384);
        String value="(.node "+valid+")";
        for(var c:n.children())value="(.together "+value+" "+inventory(c,depth+1)+")";
        for(var a:n.certifiedAlternatives())value="(.choice "+value+" "+inventory(a,depth+1)+")";
        String name="inventory"+(nodeId++);lean.append("def ").append(name).append(" : Inventory := ").append(value).append('\n');return name;
    }
    private static int stored(Node n){int total=1;for(var c:n.children())total+=stored(c);for(var c:n.certifiedAlternatives())total+=stored(c);return total;}
    private static void censusControls(Node actual,String suffix)throws Exception {
        check(stored(actual)==712,"exact old gate over budget");check(census(actual)==122,"largest selectable endpoint");
        String witness=inventory(actual,0);
        lean.append("theorem inventory_").append(suffix).append("_old : oldGate 512 ").append(witness).append(" = false := by decide\n");
        lean.append("theorem inventory_").append(suffix).append("_new : admits 512 ").append(witness).append(" = true := by decide\n");
        var leaf=node("x",List.of(),List.of());
        rejectCensus(node("x",List.of(),List.of(node("x".repeat(16385),List.of(),List.of()))),"invalid unselected alternative");
        rejectCensus(node("x",Collections.nCopies(65,leaf),List.of()),"width guard");
        var deep=leaf;for(int i=0;i<49;i++)deep=node("x",List.of(deep),List.of());rejectCensus(deep,"depth guard");
        var branch=node("x",Collections.nCopies(64,leaf),List.of());
        rejectCensus(node("x",Collections.nCopies(8,branch),List.of()),"selected endpoint remains capped at512");
        rejectCensus(node("x",List.of(),Collections.nCopies(550000,leaf)),"cumulative byte accounting across alternatives");
        check(census(node("x",List.of(leaf),List.of()))==2,"prior primary-only path");
    }
    public static void main(String[] args)throws Exception {
        var input=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var results=new JSONArray();
        for(Object raw:input.getJSONArray("cases")) {
            var identity=((JSONObject)raw).getJSONObject("identity");String id=identity.getString("id");
            if(!Set.of("event1502","event71").contains(id))continue;
            var pair=LocalOwnerTraceTest.prepare(identity);long start=System.nanoTime();
            for(int d=0;d<2;d++) {
                var a=pair.get(d);var b=pair.get(1-d);var f=CanonicalRepairTraces.fast(a,b);
                var c=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                QueueProbe.orderedReplay(f.distance(),c.distance(),()->f.replay(f.json()),()->c.replay(c.json()),new JSONObject());
                if(id.equals("event1502"))callControls(pair.get(0),f.comparisonForms(d==0).get(0),Integer.toString(d));
                else censusControls(c.projectedView(d==0).phases().get(0).matrix(),Integer.toString(d));
                var result=NativeOperationComparison.compare(a,b,f,c);
                var row=new JSONObject().put("identity",identity).put("direction",d).put("status",result.status()).put("reason",result.reason())
                    .put("fast",f.json()).put("certified",c.json()).put("comparison",result.evidence());
                Files.writeString(out.resolve(id+"-"+d+".json"),row.toString(),StandardOpenOption.CREATE_NEW);
                results.put(new JSONObject().put("id",id).put("direction",d).put("status",result.status()).put("reason",result.reason()));
                check(result.status()!=NativeOperationComparison.Status.INVALID_REPLAY,"no invalid native replay");
                check(result.status()==NativeOperationComparison.Status.MATCHED,"new comparison: "+id+" "+result.reason());
            }
            check((System.nanoTime()-start)/1e9<60,"60-second per-file invariant");
        }
        lean.append("end Focus40Generated\n");Files.writeString(out.resolve("Focus40Generated.lean"),lean.toString(),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("negatives",negatives).put("rows",results).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("ClosedSourceCensusTest: "+checks+" checks, "+negatives+" negatives");
    }
}

package is.fivefivefive.CanDis.core;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.*;
import is.fivefivefive.CanDis.metric.DeclarationMatrixLink;
import is.fivefivefive.CanDis.metric.ReplayValidationException;
import parser.ast.nodes.ModelUnit;

/** Source-linked unread declaration admission; comparisons retain unresolved outcomes. */
public final class DeclaredSlotReplayTest {
    private static int checks;
    private static int readMutationSnapshots, constantSnapshots;
    private static int localNameWireRejections;
    private static int retainedRouteControls;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private record Endpoint(Canonical.Prepared prepared,List<NormalForm> forms) { }
    private record Boundary(Endpoint endpoint, Endpoint other, boolean singletonAdmission) {
        private Boundary(Endpoint endpoint,Endpoint other) {
            this(endpoint,other,FastPaidDeclarationAlignment.supports(endpoint.forms));
        }
        private void checkAdmission(boolean expected, String why) {
            check(FastPaidDeclarationAlignment.supports(endpoint.forms)==(expected&&singletonAdmission),"Production "+why);
            for(boolean reverse:new boolean[]{false,true}) {
                var a=reverse?other:endpoint;var b=reverse?endpoint:other;
                boolean admitted=FastPaidDeclarationAlignment.supportsPair(a.forms,b.forms)
                        ||FastPaidDeclarationAlignment.supportsSupplementalPair(a.forms,b.forms);
                check(admitted==expected,"Production direction "+reverse+" "+why);
            }
        }
    }
    private static Endpoint prepare(edu.mit.csail.sdg.parser.CompModule module,String name)throws Exception{
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        var p=Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
        Field f=Canonical.Prepared.class.getDeclaredField("delegate");f.setAccessible(true);
        return new Endpoint(p,((CanonicalDistance.Prepared)f.get(p)).comparisonForms());
    }
    @FunctionalInterface private interface Check { boolean valid() throws Exception; }
    private static Object field(Object object,String name)throws Exception {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void corrupt(Object target,String name,Object value,Check valid,Boundary boundary)throws Exception{
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);Object before=f.get(target);
        try{
            f.set(target,value);check(!valid.valid(),"Authority rejected "+name);
            if(boundary!=null)boundary.checkAdmission(false,"rejected "+name);
        }finally{f.set(target,before);}
        check(valid.valid(),"Restored "+name);
        if(boundary!=null)boundary.checkAdmission(true,"restored "+name);
    }
    private static boolean readsMatch(Endpoint endpoint)throws Exception{
        var m=FastPaidDeclarationAlignment.class.getDeclaredMethod("declaredReadsMatchSource",NormalForm.class);
        m.setAccessible(true);return (Boolean)m.invoke(null,endpoint.forms.get(0));
    }
    private static List<EGraphNode> reads(EGraphNode root){
        var result=new ArrayList<EGraphNode>();var pending=new ArrayDeque<EGraphNode>();pending.add(root);
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        while(!pending.isEmpty()){
            var node=pending.remove();if(!seen.add(node))continue;
            if(node.getOpcode()==EGraphNode.Opcode.VARIABLE)result.add(node);
            pending.addAll(node.getChildren());
        }
        return result;
    }
    private static void controls(Endpoint endpoint,Endpoint partner)throws Exception{
        controls(endpoint,partner,true);
    }
    private static void controls(Endpoint endpoint,Endpoint partner,boolean checkCandidate)throws Exception{
        var form=endpoint.forms.get(0);Boundary boundary=partner==null||!checkCandidate?null:new Boundary(endpoint,partner);
        Check authority=form::matrixDeclarationsMatchSource;
        check(authority.valid(),"Independent complete source declaration inventory");
        for(var q:form.getMatrixQuantiVars()){
            corrupt(q,"quantifier",QuantiVar.Quantifier.SUM,authority,boundary);
            corrupt(q,"cardinality",QuantiVar.Cardinality.SET,authority,boundary);
            corrupt(q,"disjointnessClass",999,authority,boundary);
            corrupt(q,"bindingPath","foreign-owner",authority,boundary);
            corrupt(q,"deBruijnKey","foreign-slot",authority,boundary);
            corrupt(q,"name","foreign-name",authority,boundary);
            corrupt(q,"id",999,authority,boundary);
            corrupt(q,"typeName","foreign-type",authority,boundary);
            corrupt(q,"carrierTypeName","foreign-carrier",authority,boundary);
            corrupt(q,"exactAlloyType",null,authority,boundary);
            corrupt(q,"originalNames",Set.of("foreign-alias"),authority,boundary);
            corrupt(q,"declarationFrames",List.of(),authority,boundary);
            var duplicate=new ArrayList<>(q.getDeclarationFrames());duplicate.add(duplicate.get(0));
            corrupt(q,"declarationFrames",duplicate,authority,boundary);
            if(form.getMatrixQuantiVars().size()>1){
                var other=form.getMatrixQuantiVars().stream().filter(x->x!=q).findFirst().orElseThrow();
                corrupt(q,"declarationFrames",other.getDeclarationFrames(),authority,boundary);
            }
            for(var frame:q.getDeclarationFrames()){
                corrupt(frame,"declarationOccurrence",-1L,authority,boundary);
                corrupt(frame,"variableOccurrence",-1L,authority,boundary);
                corrupt(frame,"tupleSlot",frame.tupleSlot()+1,authority,boundary);
                corrupt(frame,"domain",is.fivefivefive.CanDis.theory.StructuralKey.of("foreign",List.of(),List.of()),authority,boundary);
                var dependency=frame.dependencies().isEmpty()?q:frame.dependencies().get(0);
                var sameId=new QuantiVar(dependency.getId(),dependency.getName(),dependency.getOriginalName(),dependency.getTypeName());
                var foreign=new ArrayList<>(frame.dependencies());
                if(foreign.isEmpty())foreign.add(sameId);else foreign.set(0,sameId);
                corrupt(frame,"dependencies",foreign,authority,boundary);
                var origins=q.getDeclarationFrames();
                if(origins.size()>1){
                    var other=origins.stream().filter(x->x!=frame).findFirst().orElseThrow();
                    corrupt(frame,"sourceOrigin",field(other,"sourceOrigin"),authority,boundary);
                }
            }
        }
        var first=form.getMatrixQuantiVars().get(0);
        var original=first.getDeclarationFrames().get(0).owner();
        corrupt(original,"phasePath","foreign-phase",authority,boundary);
        var order=new ArrayList<>(original.getMatrixQuantiVars());
        if(order.size()>1)Collections.swap(order,0,1);
        else order.set(0,new QuantiVar(first.getId(),first.getName(),first.getOriginalName(),first.getTypeName()));
        corrupt(original,"matrixQuantiVars",order,authority,boundary);
        for(var root:List.of(form.getCertificationMatrixEGraph(),form.getMatrixEGraph())){
            var actual=reads(root);
            if(actual.isEmpty()){constantSnapshots++;continue;}
            readMutationSnapshots++;
            Check readAuthority=()->readsMatch(endpoint)&&(form.getLocalQuantiVars().isEmpty()
                    ||FastPaidDeclarationAlignment.localsAbsentFromSnapshots(form)
                    ||FastLocalPaidScope.supports(form)
                    ||partner!=null&&FastNestedPaidScope.supportsPair(endpoint.forms,partner.forms));
            for(var read:actual){
                corrupt(read,"alphaName","unresolved-read",readAuthority,boundary);
                if(form.getMatrixQuantiVars().stream().anyMatch(q->q.getOriginalNames().contains(read.getSemanticIdentity())))
                    corrupt(read,"sourceName","foreign-read",readAuthority,boundary);
                corrupt(read,"exactAlloyType",null,readAuthority,boundary);
                corrupt(read,"sourceOccurrenceLineage",-1L,readAuthority,boundary);
            }
        }
    }
    private static void reusedAndDependentControls()throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r:set A, s:set A }
                pred reused {
                  some disj p,q:A | p in p.r and
                    ((all x,y:A | x in y.r) and (all u,v:A | u in v.s))
                }
                pred dependent { all disj x,z:A | some y:x.r | x in y.s }
                """);
        var reused=prepare(module,"reused");
        check(reused.forms.get(0).getMatrixQuantiVars().stream().anyMatch(q->q.getDeclarationFrames().size()>1),
                "Actual source declarations reuse a representative");
        var dependent=prepare(module,"dependent");
        check(dependent.forms.get(0).getMatrixQuantiVars().stream()
                .flatMap(q->q.getDeclarationFrames().stream()).anyMatch(f->!f.dependencies().isEmpty()),
                "Actual source has a dependent declaration");
        controls(reused,dependent);
        controls(dependent,reused);
    }
    private static NativeOperationComparison.Result comparison(Endpoint a,Endpoint b) {
        var fast=CanonicalRepairTraces.fast(a.prepared,b.prepared);
        var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a.prepared),
                CanonicalAlloyPipeline.prepare(b.prepared));
        return LocalOwnerTraceTest.compareSelected(a.prepared,b.prepared,fast,certified);
    }
    private static int readOccurrences(EGraphNode node) {
        int count=node.getOpcode()==EGraphNode.Opcode.VARIABLE?1:0;
        for(var child:node.getChildren())count=Math.addExact(count,readOccurrences(child));
        return count;
    }
    private static void omittedReadControls(Path out)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r:set A, s:set A }
                pred multiple { all disj x,y,z:A | x in y.r and x in y.s }
                pred duplicate { all disj x,y,z:A | x in y.r and x in y.r }
                pred single { all disj x,y,z:A | x in y.r }
                pred truth { no none }
                """);
        var duplicate=prepare(module,"duplicate");var single=prepare(module,"single");
        var form=duplicate.forms.get(0);
        int before=readOccurrences(form.getCertificationMatrixEGraph()),after=readOccurrences(form.getMatrixEGraph());
        var evidence=new JSONArray().put(new JSONObject().put("control","deduplication-checkpoints")
                .put("sourceReadOccurrences",before).put("currentReadOccurrences",after)
                .put("postCheckpointRemovalObserved",before>after));
        for(boolean reverse:new boolean[]{false,true}) {
            var result=comparison(reverse?single:duplicate,reverse?duplicate:single);
            evidence.put(new JSONObject().put("control","legitimate-deduplication").put("reverse",reverse)
                    .put("result",result.evidence()));
            check(result.status()==NativeOperationComparison.Status.MATCHED,"Source-linked read deduplication accepted");
        }
        var multiple=prepare(module,"multiple");form=multiple.forms.get(0);
        var truth=prepare(module,"truth").forms.get(0).getMatrixEGraph();
        var root=form.getMatrixEGraph();var source=form.getCertificationMatrixEGraph();
        check(readOccurrences(root)>0 && readOccurrences(truth)==0,"Live reads before whole-body omission");
        var originalTraces=List.of(CanonicalRepairTraces.fast(multiple.prepared,single.prepared),
                CanonicalRepairTraces.fast(single.prepared,multiple.prepared));
        Field matrix=NormalForm.class.getDeclaredField("matrixEGraphRoot");matrix.setAccessible(true);
        for(var replacement:List.of(truth,single.forms.get(0).getMatrixEGraph())) {
            try {
                matrix.set(form,replacement);
                check(form.getCertificationMatrixEGraph()==source,"Sealed source retained while current root is corrupted");
                for(boolean reverse:new boolean[]{false,true}) {
                    var a=reverse?single:multiple;var b=reverse?multiple:single;
                    var altered=CanonicalRepairTraces.fast(a.prepared,b.prepared);
                    String rejected=null;
                    try {originalTraces.get(reverse?1:0).replay(altered.json());}
                    catch(is.fivefivefive.CanDis.metric.ReplayValidationException invalid){rejected=invalid.getMessage();}
                    check(rejected!=null,"Original source-bound replay rejects omitted/substituted current reads");
                    var result=comparison(a,b);
                    String kind=replacement==truth?"omitted-current-read":"foreign-current-subtree";
                    var observation=new JSONObject().put("control",kind).put("reverse",reverse)
                            .put("sourceBoundReplayRejection",rejected).put("result",result.evidence());
                    evidence.put(observation);
                    Files.writeString(out.resolve(kind+"-"+reverse+".json"),observation.toString(2),StandardOpenOption.CREATE_NEW);
                    check(result.status()!=NativeOperationComparison.Status.MATCHED,
                            "Corrupted current matrix is never promoted to a match");
                }
            } finally {matrix.set(form,root);}
        }
        Files.writeString(out.resolve("omitted-read-controls.json"),evidence.toString(2),StandardOpenOption.CREATE_NEW);
    }
    private static void rigidRouteControls(Endpoint a,Endpoint b)throws Exception {
        if(FastPaidDeclarationAlignment.supportsDirectEndpoint(a.forms)
                &&FastPaidDeclarationAlignment.supportsDirectEndpoint(b.forms))return;
        if(!FastNestedPaidScope.supportsPair(a.forms,b.forms)) {
            check(!FastPaidDeclarationAlignment.supportsPair(a.forms,b.forms),"New rigid admission does not replace old route");
            check(FastPaidDeclarationAlignment.supportsSupplementalPair(a.forms,b.forms),"New rigid candidate supplements old route");
        }
        for(var endpoint:List.of(a,b))if(!FastPaidDeclarationAlignment.supportsDirectEndpoint(endpoint.forms)
                &&!FastNestedPaidScope.supportsPair(endpoint.forms,endpoint.forms)) {
            var trace=CanonicalRepairTraces.fast(endpoint.prepared,endpoint.prepared);var json=trace.json();
            check(trace.replay(json)==0,"Rigid self comparison replays at zero");
            check(!json.has("selectedPaidDeclarationCorrespondence"),"Rigid candidate tie preserves original path");
        }
    }
    private static void paidIncidenceControls(Endpoint left,Endpoint right,JSONObject fast)throws Exception {
        if(!fast.has("selectedPaidDeclarationCorrespondence"))return;
        var paid=new TreeMap<String,String>();var selected=fast.getJSONObject("selectedPaidDeclarationCorrespondence");
        for(String key:selected.keySet())paid.put(key,selected.getString(key));
        if(paid.isEmpty())return;
        var a=left.forms.get(0);var b=right.forms.get(0);
        var source=CanonicalDistance.diagnosticDeclarationAtoms(a);var target=CanonicalDistance.diagnosticDeclarationAtoms(b);
        var an=a.getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
        var bn=b.getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
        var receipt=fast.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
        var link=new DeclarationMatrixLink(an,bn,paid,Set.copyOf(an),Set.copyOf(bn));
        check(link.verify(source,target,receipt)==fast.getInt("quantifierDistance"),"Selected charged declaration incidence");
        var phase=fast.getJSONArray("phases").getJSONObject(0);
        check(phase.getJSONObject("alphaMapping").similar(selected),"Same paid map reaches selected matrix");
        check(CanonicalDistance.verifyFixedMatrixReplay((CanonicalDistance.Prepared)field(left.prepared,"delegate"),
                (CanonicalDistance.Prepared)field(right.prepared,"delegate"),paid,paid,phase.getJSONObject("receipt"))
                ==fast.getInt("matrixDistance"),"Fixed selected paid matrix replays");
        var missing=new TreeMap<>(paid);missing.remove(missing.firstKey());boolean rejected=false;
        try{new DeclarationMatrixLink(an,bn,missing,Set.copyOf(an),Set.copyOf(bn)).verify(source,target,receipt);}
        catch(ReplayValidationException expected){rejected=true;}
        check(rejected,"Missing selected paid link rejected");
        var wrongCharge=new JSONObject(receipt.toString());
        var operation=wrongCharge.getJSONArray("operations").getJSONObject(0);operation.put("cost",operation.getInt("cost")+1);
        rejected=false;try{link.verify(source,target,wrongCharge);}catch(ReplayValidationException expected){rejected=true;}
        check(rejected,"Changed native declaration charge rejected");
    }
    private static boolean changeLocalWireName(JSONArray node,Set<String> identities) {
        var label=new JSONArray(node.getString(0));
        if(label.getString(0).equals("VARIABLE")&&identities.contains(label.getString(3))) {
            label.put(2,"foreign-local-name");node.put(0,label.toString());return true;
        }
        for(Object child:node.getJSONArray(1))if(changeLocalWireName((JSONArray)child,identities))return true;
        return false;
    }
    public static void main(String[] args)throws Exception{
        var cases=new JSONArray(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        boolean rigid=args.length>2&&args[2].equals("rigid");
        var summary=new JSONArray();
        reusedAndDependentControls();
        omittedReadControls(out);
        for(Object value:cases){
            var identity=(JSONObject)value;long start=System.nanoTime();
            var text=Files.readString(Path.of(identity.getString("source")));
            check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .equals(identity.getString("sha256")),"Exact original source");
            var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
            var a=prepare(module,identity.getString("left"));var b=prepare(module,identity.getString("right"));
            check(FastPaidDeclarationAlignment.supportsPair(a.forms,b.forms)
                    ||FastPaidDeclarationAlignment.supportsSupplementalPair(a.forms,b.forms),"Production candidate admitted");
            if(rigid){rigidRouteControls(a,b);rigidRouteControls(b,a);}
            boolean retainedRoute=rigid&&FastNestedPaidScope.supportsPair(a.forms,b.forms);
            if(retainedRoute)retainedRouteControls++;
            controls(a,b,!retainedRoute);controls(b,null);
            for(int direction=0;direction<2;direction++){
                var left=direction==0?a:b;var right=direction==0?b:a;
                var fast=CanonicalRepairTraces.fast(left.prepared,right.prepared);
                var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(left.prepared),CanonicalAlloyPipeline.prepare(right.prepared));
                var fj=fast.json();var cj=cert.json();
                for(boolean target:new boolean[]{false,true}) {
                    var form=(target?right:left).forms.get(0);
                    if(form.getLocalQuantiVars().isEmpty()||FastPaidDeclarationAlignment.localsAbsentFromSnapshots(form))continue;
                    var identities=new HashSet<String>();for(var q:form.getLocalQuantiVars())identities.addAll(q.getOriginalNames());
                    var changed=new JSONObject(fj.toString());
                    check(changeLocalWireName(changed.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt")
                            .getJSONArray(target?"target":"source"),identities),"Actual local variable in native wire");
                    boolean rejected=false;try{fast.replay(changed);}catch(ReplayValidationException expected){rejected=true;}
                    check(rejected,"Corrupted local source name rejected by native replay");localNameWireRejections++;
                }
                if(fast.distance()<=cert.distance()){
                    check(fast.replay(fj)==fast.distance(),"Fast shorter-first replay");check(cert.replay(cj)==cert.distance(),"Certified replay");
                }else{
                    check(cert.replay(cj)==cert.distance(),"Certified shorter-first replay");check(fast.replay(fj)==fast.distance(),"Fast replay");
                }
                var result=LocalOwnerTraceTest.compareSelected(left.prepared,right.prepared,fast,cert);
                var row=new JSONObject().put("identity",identity).put("direction",direction).put("fast",fj).put("certified",cj)
                        .put("status",result.status().name()).put("reason",result.reason()).put("evidence",result.evidence());
                Files.writeString(out.resolve(identity.getString("id")+"-"+direction+".json"),row.toString(),StandardOpenOption.CREATE_NEW);
                var compact=new JSONObject().put("id",identity.getString("id")).put("direction",direction)
                        .put("fast",fast.distance()).put("certified",cert.distance()).put("status",result.status().name()).put("reason",result.reason());
                summary.put(compact);System.out.println(compact);
                check(!result.status().name().equals("INVALID_REPLAY")&&!result.status().name().equals("ERROR"),"First incident: "+compact);
                check(fast.distance()==cert.distance(),"Independent selected totals agree");
                if(rigid){paidIncidenceControls(left,right,fj);check(result.status()==NativeOperationComparison.Status.MATCHED,"Rigid family selected actions match");}
            }
            check((System.nanoTime()-start)/1e9<60,"Natural file below60seconds including controls");
        }
        check(readMutationSnapshots>0,"Actual-read mutation controls executed");
        Files.writeString(out.resolve("summary.json"),new JSONObject().put("checks",checks).put("directions",summary)
                .put("readMutationSnapshots",readMutationSnapshots).put("constantSnapshots",constantSnapshots)
                .put("localNameWireRejections",localNameWireRejections)
                .put("retainedRouteControls",retainedRouteControls)
                .put("retainedRouteMutationScope","Source/read authority helpers plus native-wire and paid-incidence checks; not new eligibility claims about the retained route")
                .toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("DeclaredSlotReplayTest: "+checks+" checks passed; see distinct comparison outcomes");
    }
}

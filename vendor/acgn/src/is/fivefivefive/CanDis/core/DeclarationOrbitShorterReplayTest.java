package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.DistanceReplayDiagnostics;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.GraphType;
import parser.ast.nodes.ModelUnit;

/** Source-linked paid declaration paths and independently replayed native endpoints. */
public final class DeclarationOrbitShorterReplayTest {
    public static final Path SOURCE=Path.of("classified-data/classroom_fol/both/SASEHD8jPo2HgsuoJ_inv14.als");
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++; try { action.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static String variable(EGraphNode n) { return n.getAlphaName()==null?n.getSourceName():n.getAlphaName(); }
    private static GraphType type(EGraphNode n) {
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE || n.getOpcode()==EGraphNode.Opcode.GENERICRELDECL) return null;
        if(n.getMetatype()==EGraphNode.Metatype.BOOLEAN) return GraphType.BOOL;
        return AlloyTypeBridge.graphType(Objects.requireNonNull(n.getExactAlloyType()));
    }
    private static String atom(EGraphNode n) {
        if(n.getOpcode()==EGraphNode.Opcode.CALL) throw new IllegalArgumentException("Call outside incident slice");
        if(n.getSemanticIdentity()==null && n.getOpcode()==EGraphNode.Opcode.GLOBALBINDING
                && n.getSourceType()!=null && n.getSourceType().startsWith("FieldRelation")) {
            String name=Objects.requireNonNull(n.getSourceName());
            return "alloy/field/"+name.length()+":"+name+"/"+n.getExactAlloyType().stableString();
        }
        return n.getSemanticIdentity()==null?n.getSourceName():n.getSemanticIdentity();
    }
    private static MatrixReplay<EGraphNode> checker(Map<String,String> labelMapping,Map<String,String> mapping) {
        Map<String,String> reverse=new HashMap<>();
        for(var entry:mapping.entrySet()) if(reverse.put(entry.getValue(),entry.getKey())!=null)
            throw new IllegalArgumentException("Noninjective selected mapping");
        return new MatrixReplay<>(new MatrixReplay.NativeCost<EGraphNode>() {
            public String label(EGraphNode n) {
                return new JSONArray().put(n.getOpcode().name()).put(String.valueOf(type(n)))
                        .put(String.valueOf(n.getSourceName())).put(String.valueOf(atom(n))).put("")
                        .put(n.getFlexibleArityKind().name()).put(n.isOrderInsensitive())
                        .put(new TreeMap<>(labelMapping).toString()).toString();
            }
            public List<EGraphNode> children(EGraphNode n) { return FastChainGeometry.children(n); }
            public List<EGraphNode> alternatives(EGraphNode n) { return List.of(); }
            public boolean unordered(EGraphNode a,EGraphNode b) { return a.getOpcode()==b.getOpcode() && a.isOrderInsensitive() && b.isOrderInsensitive(); }
            public int size(EGraphNode n) {
                if(n==null) return 0;
                int sum=1; for(var child:children(n)) sum=Math.addExact(sum,size(child)); return sum;
            }
            public int update(EGraphNode a,EGraphNode b) {
                if(a.getOpcode()!=b.getOpcode()) return 1;
                if(a.getOpcode()==EGraphNode.Opcode.VARIABLE) {
                    String x=variable(a),y=variable(b);
                    if(mapping.containsKey(x)) return mapping.get(x).equals(y)?0:1;
                    if(reverse.containsKey(y)) return 1;
                    return x.equals(y)?0:1;
                }
                if(!Objects.equals(type(a),type(b))) return 1;
                return switch(a.getOpcode()) {
                    case GLOBALBINDING,CONSTANT,REF -> Objects.equals(atom(a),atom(b))?0:1;
                    default -> 0;
                };
            }
            public int distance(EGraphNode a,EGraphNode b) { throw new AssertionError("Replay cannot search distance"); }
        });
    }
    private static List<DeclarationReplay.Atom> declarations(NormalForm form) {
        List<QuantiVar> qs=form.getMatrixQuantiVars();
        List<Integer> order=new ArrayList<>(); for(int i=0;i<qs.size();i++) order.add(i);
        for(int start=0;start<order.size();) {
            int end=start+1;
            var kind=qs.get(start).getQuantifier();
            if(kind==QuantiVar.Quantifier.ALL || kind==QuantiVar.Quantifier.SOME) {
                while(end<order.size() && qs.get(end).getQuantifier()==kind) end++;
                order.subList(start,end).sort(Comparator.comparing(i -> qs.get(i).getCarrierTypeName()));
            }
            start=end;
        }
        List<DeclarationReplay.Atom> result=new ArrayList<>();
        for(int i:order) {
            QuantiVar q=qs.get(i);
            String admission=new JSONArray().put(q.getName()).put(q.getOriginalName()).put(q.getBindingPath())
                    .put(q.getCarrierTypeName()).put(String.valueOf(q.getExactAlloyType())).toString();
            result.add(new DeclarationReplay.Atom("phase/0/declaration/"+i,List.of(q.getQuantifier().name(),
                    q.getCarrierTypeName(),q.getCardinality().name(),Integer.toString(q.getDisjointnessClass())),admission));
        }
        return result;
    }

    private static int[][] dp(List<DeclarationReplay.Atom> a,List<DeclarationReplay.Atom> b) {
        int[][] d=new int[a.size()+1][b.size()+1];
        for(int i=0;i<=a.size();i++) d[i][0]=i;
        for(int j=0;j<=b.size();j++) d[0][j]=j;
        for(int i=1;i<=a.size();i++) for(int j=1;j<=b.size();j++)
            d[i][j]=Math.min(d[i-1][j-1]+(a.get(i-1).tuple().equals(b.get(j-1).tuple())?0:1),
                    Math.min(d[i-1][j]+1,d[i][j-1]+1));
        return d;
    }
    private static Object sourceCheck(String name,Class<?>[] types,Object... arguments) throws Exception {
        var method=is.fivefivefive.CanDis.DeclarationOrbitSourceTest.class.getDeclaredMethod(name,types);
        method.setAccessible(true);
        return method.invoke(null,arguments);
    }

    private static int fixedNativeCost(EGraphNode left,EGraphNode right,Map<String,String> mapping) throws Exception {
        var method=CanonicalDistance.class.getDeclaredMethod("eGraphDistance",EGraphNode.class,EGraphNode.class,Map.class);
        method.setAccessible(true);
        return (Integer)method.invoke(null,left,right,mapping);
    }

    private static JSONObject verify(String[] args,boolean nativeReverse) throws Exception {
        byte[] bytes=Files.readAllBytes(SOURCE);
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        var forms=new ArrayList<NormalForm>(); var prepared=new ArrayList<Canonical.Prepared>();
        for(String name:List.of("inv14","inv14c")) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
            visitor.visit(new ModelUnit(null,module),null);
            var graph=visitor.getForest().get(visitor.getForestId(name));
            var agent=new IRAgent(graph); agent.computeNormalForm();
            check(agent.normalForms().size()==1,"single original phase");
            var form=agent.normalForms().get(0); form.freezeForCertification(); forms.add(form);
            prepared.add(Canonical.prepare(graph));
        }
        var archived=args.length==0?null:new JSONObject(Files.readString(Path.of(args[0])));
        boolean reverse=archived==null?nativeReverse:archived.getString("leftPredicate").equals("inv14c");
        if(reverse) { Collections.reverse(forms); Collections.reverse(prepared); }
        var live=DistanceReplayDiagnostics.capture(prepared.get(0),prepared.get(1));
        var report=archived==null?live:archived;
        if(args.length!=0) check(report.getString("sourceSha256").equals(HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes))),"frozen original source hash");
        var left=forms.get(0); var right=forms.get(1);
        for(var f:forms) check(f.getTemporalChildren().isEmpty() && f.getParams().isEmpty()
                && f.getInheritedQuantiVars().isEmpty() && f.getPhaseLocalBindingImports().isEmpty(),"owner boundary");
        var original=reverse?right:left;
        check(original.getLocalQuantiVars().size()>=2,"nested local binders not hoisted");
        for(var q:original.getMatrixQuantiVars()) check(q.getQuantifier()==QuantiVar.Quantifier.ALL
                && q.getCarrierTypeName().equals("Person") && q.isFrozenForCertification()
                && q.getExactAlloyType().hasParserAuthenticatedAncestry(),"typed original ALL Person");
        check(!FastPaidDeclarationAlignment.supportsPair(List.of(left),List.of(right)),
                "symmetric retained-owner prefix excluded from canonical paid candidate search");
        check(original.getMatrixQuantiVars().size()==2
                && original.getMatrixQuantiVars().get(0).getTypeName().equals(original.getMatrixQuantiVars().get(1).getTypeName())
                && original.getMatrixQuantiVars().stream().allMatch(q -> q.getCardinality()==QuantiVar.Cardinality.ONE
                        && q.getDisjointnessClass()==0),"two equal outer ALL Person repair tuples require exchange route");
        check(FastLocalPaidScope.checkedOwnerCount(original)==2
                && FastLocalPaidScope.checkedOwnerCount(NormalForm.fastComparisonViews(List.of(original)).get(0))==2,
                "original and comparison retain the checked two-EXISTS owner chain");
        check(!FastNestedPaidScope.supportsPair(List.of(left),List.of(right)),"ONE/ALL admission does not cover EXISTS scopes");
        var sourceView=(RepairView)sourceCheck("checkedProjection",new Class<?>[]{Canonical.Prepared.class,Canonical.Prepared.class},
                prepared.get(0),prepared.get(1));
        var targetView=(RepairView)sourceCheck("checkedProjection",new Class<?>[]{Canonical.Prepared.class,Canonical.Prepared.class},
                prepared.get(1),prepared.get(0));
        var certified=live.getJSONObject("certified");
        check(certified.getInt("distance")==27 && certified.getInt("quantifierDistance")==1,"certified27 and declaration1 unchanged");
        @SuppressWarnings("unchecked") var certifiedChecker=(MatrixReplay<RepairView.Node>)sourceCheck("checker",new Class<?>[]{int[].class},(Object)new int[]{1,0});
        check(certifiedChecker.verify(sourceView.phases().get(0).matrix(),targetView.phases().get(0).matrix(),
                certified.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"))==26,"certified fixed-choice matrix26 replay");
        var arm=report.getJSONObject("fast"); var phase=arm.getJSONArray("phases").getJSONObject(0);
        var raw=phase.getJSONObject("alphaMapping"); var selected=new HashMap<String,String>();
        for(String key:raw.keySet()) selected.put(key,raw.getString(key));
        check(selected.equals(reverse?Map.of("_q1","_q0"):Map.of("_q0","_q1")),"native shorter alpha choice");
        var completed=Map.of("_q0","_q1","_q1","_q0");
        var l=NormalForm.fastComparisonViews(List.of(left)).get(0).getMatrixEGraph();
        var r=NormalForm.fastComparisonViews(List.of(right)).get(0).getMatrixEGraph();
        var receipt=phase.getJSONObject("receipt");
        check(checker(selected,selected).verify(l,r,receipt)==26,"native matrix26 independently replayed");
        check(checker(selected,completed).verify(l,r,receipt)==26,
                "same matrix operations admit the paid complement after certified outer exchange");
        check(fixedNativeCost(l,r,selected)==26 && fixedNativeCost(l,r,completed)==26,
                "actual Fast evaluator fixed-choice costs, no copied certified scalar");
        reject(() -> checker(selected,completed).verify(r,l,receipt),"endpoint substitution");
        reject(() -> checker(selected,completed).verify(l,r,new JSONObject(receipt.toString()).put("cost",25)),"cost corruption");
        var a=declarations(left); var b=declarations(right);
        // This old standalone path is retained as negative evidence, never read
        // from the live fail-closed slice or promoted to a joint witness.
        var decl=archived==null?DeclarationReplay.capture(a,b,dp(a,b),(i,j)->true):
                archived.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components")
                        .getJSONObject(0).getJSONObject("receipt");
        check(DeclarationReplay.verify(a,b,decl)==1,"old standalone declaration receipt");
        int conflicts=0;
        for(Object item:decl.getJSONArray("operations")) {
            var op=(JSONObject)item;
            if(op.getInt("cost")==1 && op.getString("kind").equals("update")) {
                String x=new JSONArray(op.getJSONObject("source").getString("admission")).getString(0);
                String y=new JSONArray(op.getJSONObject("target").getString("admission")).getString(0);
                if(!y.equals(completed.get(x))) conflicts++;
            }
        }
        check(conflicts==1,"raw archived slices are not one coherent public plan");
        var joint=new DeclarationMatrixLink(List.of("_q0","_q1"),List.of("_q0","_q1"),selected,
                Set.of("_q0","_q1"),Set.of("_q0","_q1"));
        reject(()->joint.verify(a,b,decl),"historical standalone receipt rejected by joint original-coordinate linkage");
        var liveFast=live.getJSONObject("fast"); var emitted=liveFast.getJSONObject("declarationSlice");
        check(emitted.getBoolean("sliceReplayChecked") && emitted.getJSONArray("components").length()==1,
                "native paid completion emitted only after source certificate and unchanged body replay");
        check(!liveFast.has("selectedPaidDeclarationCorrespondence") && liveFast.getBoolean("numericalCostCoverage")
                && liveFast.has("orderedDiagnosticActions") && liveFast.getInt("distance")==27,
                "scalar search unchanged; explicit replay completion, not a new scalar paid candidate");
        if(reverse) Collections.swap(b,0,1); else Collections.swap(a,0,1);
        var supplement=DeclarationReplay.capture(a,b,dp(a,b),(i,j)->true);
        check(DeclarationReplay.verify(a,b,supplement)==1,"source-permuted declaration path independently replayed");
        var nativeReceipt=emitted.getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
        check(DeclarationReplay.verify(a,b,nativeReceipt)==1
                && nativeReceipt.getJSONArray("operations").similar(supplement.getJSONArray("operations")),
                "actual native declaration sequence equals independently constructed source-permuted path");
        check(nativeReceipt.getJSONObject("matrixCorrespondenceCompletion").getJSONObject("completedMapping")
                .similar(new JSONObject(completed)),"actual native completion equals checked fixed body map");
        for(Object item:supplement.getJSONArray("operations")) {
            var op=(JSONObject)item;
            check(op.getInt("leftIndex")>=0 && op.getInt("rightIndex")>=0,"diagonal witness");
            String x=new JSONArray(op.getJSONObject("source").getString("admission")).getString(0);
            String y=new JSONArray(op.getJSONObject("target").getString("admission")).getString(0);
            check(y.equals(completed.get(x)),"every diagonal links to completed matrix choice");
        }
        var forged=new JSONObject(supplement.toString());
        forged.getJSONArray("source").getJSONObject(0).put("admission","foreign owner");
        reject(()->DeclarationReplay.verify(a,b,forged),"forged source admission");
        check(arm.getInt("distance")==27 && !report.getBoolean("complete")
                && !report.getBoolean("publicSequenceLinked"),"native27, full alignment blocked");
        var result=new JSONObject()
                .put("complete",false).put("certificationStatus","BLOCKED")
                .put("direction",reverse?"reverse":"forward")
                .put("sourceSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("rawDeclarationLinkage",false).put("sourcePermutation",reverse?"right:[1,0]":"left:[1,0]")
                .put("certificateCheck","DeclarationOrbitSourceTest.checkedProjection")
                .put("completedMapping",new JSONObject(completed)).put("matrixCost",26).put("declarationCost",1)
                .put("historicalInvalidDeclarationReceipt",decl).put("historicalConflicts",conflicts)
                .put("liveNativeReport",live).put("nativeFastCompletionEmitted",true)
                .put("supplementalDeclarationReceipt",supplement);
        if(args.length==2) Files.writeString(Path.of(args[1]),result.toString(2),StandardOpenOption.CREATE_NEW);
        return result;
    }

    public static void main(String[] args) throws Exception {
        if(args.length>2) throw new IllegalArgumentException("[ARCHIVED_RECEIPT [NEW_SUPPLEMENT_JSON]] or --live-output OUTPUT");
        if(args.length==2 && args[0].equals("--live-output")) {
            var out=new JSONObject().put("forward",verify(new String[0],false)).put("reverse",verify(new String[0],true));
            out.put("checks",checks).put("complete",false);
            Files.writeString(Path.of(args[1]),out.toString(2),StandardOpenOption.CREATE_NEW);
        } else {
            verify(args,false);
            if(args.length==0) verify(args,true);
        }
        System.out.println("DeclarationOrbitShorterReplayTest: "+checks+" checks passed; historical conflict rejected, native completion27 checked; full alignment BLOCKED");
    }
}

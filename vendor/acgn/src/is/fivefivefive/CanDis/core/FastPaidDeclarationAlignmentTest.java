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
public final class FastPaidDeclarationAlignmentTest {
    public static final Path SOURCE=Path.of("classified-data/classroom_fol/both/5dmBQdag4unR6mf3t_inv14.als");
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
    private static MatrixReplay<EGraphNode> checker(Map<String,String> mapping,boolean strict) {
        Map<String,String> reverse=new HashMap<>();
        for(var entry:mapping.entrySet()) if(reverse.put(entry.getValue(),entry.getKey())!=null)
            throw new IllegalArgumentException("Noninjective selected mapping");
        return new MatrixReplay<>(new MatrixReplay.NativeCost<EGraphNode>() {
            public String label(EGraphNode n) {
                return new JSONArray().put(n.getOpcode().name()).put(String.valueOf(type(n)))
                        .put(String.valueOf(n.getSourceName())).put(String.valueOf(atom(n))).put("")
                        .put(n.getFlexibleArityKind().name()).put(n.isOrderInsensitive())
                        .put(new TreeMap<>(mapping).toString()).toString();
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
                    if(strict && reverse.containsKey(y)) return 1;
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

    private record Endpoint(Canonical.Prepared prepared,List<NormalForm> forms) { }
    private static Endpoint prepare(edu.mit.csail.sdg.parser.CompModule module,String name) {
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        var graph=visitor.getForest().get(visitor.getForestId(name));
        var agent=new IRAgent(graph); agent.computeNormalForm();
        var forms=List.copyOf(agent.normalForms());
        forms.forEach(NormalForm::freezeForCertification);
        return new Endpoint(Canonical.prepare(graph),forms);
    }
    private static void pair(Endpoint a,Endpoint b,int expected) {
        var sourceA=CanonicalDistance.sourceIrTemporalFol(a.forms());
        var sourceB=CanonicalDistance.sourceIrTemporalFol(b.forms());
        for(boolean reverse:new boolean[]{false,true}) {
            Endpoint left=reverse?b:a, right=reverse?a:b;
            JSONObject report=DistanceReplayDiagnostics.capture(left.prepared(),right.prepared());
            JSONObject fast=report.getJSONObject("fast");
            var paid=fast.getJSONObject("selectedPaidDeclarationCorrespondence");
            var phases=fast.getJSONArray("phases");
            var raw=phases.getJSONObject(0).getJSONObject("alphaMapping");
            Map<String,String> mapping=new HashMap<>();
            for(String key:raw.keySet()) mapping.put(key,raw.getString(key));
            for(String key:paid.keySet()) check(paid.getString(key).equals(mapping.get(key)),"paid link retained in matrix");
            var l=NormalForm.fastComparisonViews(left.forms()).get(0);
            var r=NormalForm.fastComparisonViews(right.forms()).get(0);
            var receipt=phases.getJSONObject(0).getJSONObject("receipt");
            int matrix=checker(mapping,true).verify(l.getMatrixEGraph(),r.getMatrixEGraph(),receipt);
            check(matrix==fast.getInt("matrixDistance"),"independent native matrix cost");
            var decl=fast.getJSONObject("declarationSlice");
            var quant=decl.getJSONArray("components").getJSONObject(0).getJSONObject("receipt");
            int declarations=DeclarationReplay.verify(declarations(l),declarations(r),quant);
            Map<String,String> replayed=new HashMap<>();
            for(Object item:quant.getJSONArray("operations")) {
                JSONObject op=(JSONObject)item;
                if(op.getString("kind").equals("update") && op.getInt("cost")!=0) {
                    String x=new JSONArray(op.getJSONObject("source").getString("admission")).getString(0);
                    String y=new JSONArray(op.getJSONObject("target").getString("admission")).getString(0);
                    check(replayed.put(x,y)==null && mapping.get(x).equals(y),"exact paid declaration-to-matrix link");
                }
            }
            check(new JSONObject(replayed).similar(paid),"all and only paid diagonals");
            check(decl.getBoolean("selectedPaidCorrespondenceChecked"),"native linkage exported");
            check(matrix+declarations==expected && fast.getInt("distance")==expected,"native decomposition");
            check(report.getJSONObject("certified").getInt("distance")==expected,"independent certified scalar");
            check(!report.getBoolean("complete") && !report.getBoolean("sourceCertificateWireReplayChecked")
                    && !report.getBoolean("publicSequenceLinked"),"full alignment BLOCKED");
            reject(() -> checker(Map.of(),true).verify(l.getMatrixEGraph(),r.getMatrixEGraph(),receipt),"mapping substitution");
            reject(() -> checker(mapping,true).verify(r.getMatrixEGraph(),l.getMatrixEGraph(),receipt),"endpoint substitution");
            JSONObject changed=new JSONObject(quant.toString());
            changed.getJSONArray("source").getJSONObject(0).put("occurrence","phase/9/declaration/0");
            reject(() -> DeclarationReplay.verify(declarations(l),declarations(r),changed),"foreign owner");
            JSONObject badCost=new JSONObject(receipt.toString()).put("cost",matrix+1);
            reject(() -> checker(mapping,true).verify(l.getMatrixEGraph(),r.getMatrixEGraph(),badCost),"cost substitution");
        }
        check(sourceA.equals(CanonicalDistance.sourceIrTemporalFol(a.forms()))
                && sourceB.equals(CanonicalDistance.sourceIrTemporalFol(b.forms())),"original source certificate trees unchanged");
    }
    private static int[][] dp(List<QuantiVar> a,List<QuantiVar> b) {
        int[][] d=new int[a.size()+1][b.size()+1];
        for(int i=0;i<=a.size();i++) d[i][0]=i;
        for(int j=0;j<=b.size();j++) d[0][j]=j;
        for(int i=1;i<=a.size();i++) for(int j=1;j<=b.size();j++)
            d[i][j]=Math.min(d[i-1][j-1]+update(a.get(i-1),b.get(j-1)),Math.min(d[i-1][j]+1,d[i][j-1]+1));
        return d;
    }
    private static int update(QuantiVar a,QuantiVar b) { return a.getTypeName().equals(b.getTypeName())?0:1; }
    private static List<QuantiVar> vars(String prefix,int size,String type) {
        List<QuantiVar> result=new ArrayList<>();
        for(int i=0;i<size;i++) result.add(new QuantiVar(i,prefix+i,type));
        return result;
    }
    private static NormalForm withFirstType(NormalForm source,is.fivefivefive.ACGN.alloy.ExactAlloyType type) {
        NormalForm result=new NormalForm(); result.addEClass(source.getMatrixEGraph());
        int i=0;
        for(QuantiVar q:source.getMatrixQuantiVars()) {
            QuantiVar copy=new QuantiVar(q.getId(),q.getName(),q.getOriginalName(),q.getTypeName());
            copy.setCarrierTypeName(q.getCarrierTypeName()); copy.setBindingPath(q.getBindingPath());
            copy.setCardinality(q.getCardinality()); copy.setQuantifier(q.getQuantifier());
            copy.mergeExactAlloyType(i++==0?type:q.getExactAlloyType()); result.addMatrixQuantiVar(copy);
        }
        result.freezeForCertification(); return result;
    }
    private static void brute(List<QuantiVar> a,List<QuantiVar> b,int i,int j,int cost,
            Map<String,String> map,int minimum,Set<Map<String,String>> result) {
        if(cost>minimum) return;
        if(i==a.size() && j==b.size()) { if(cost==minimum) result.add(Map.copyOf(map)); return; }
        if(i<a.size()) brute(a,b,i+1,j,cost+1,map,minimum,result);
        if(j<b.size()) brute(a,b,i,j+1,cost+1,map,minimum,result);
        if(i<a.size() && j<b.size()) {
            int c=update(a.get(i),b.get(j));
            if(c!=0) map.put(a.get(i).getName(),b.get(j).getName());
            brute(a,b,i+1,j+1,cost+c,map,minimum,result);
            if(c!=0) map.remove(a.get(i).getName());
        }
    }
    public static void main(String[] args) throws Exception {
        var natural=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(SOURCE));
        var a=prepare(natural,"inv14"); var b=prepare(natural,"inv14c");
        check(FastPaidDeclarationAlignment.supports(a.forms()) && FastPaidDeclarationAlignment.supports(b.forms()),"actual closed homogeneous scope");
        pair(a,b,29);
        var mixedNatural=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(
                Path.of("classified-data/classroom_fol/both/7KzgkuDqY7Q4ZfQ4Z_inv14.als")));
        var mixedA=prepare(mixedNatural,"inv14"); var mixedB=prepare(mixedNatural,"inv14c");
        check(FastPaidDeclarationAlignment.supports(mixedA.forms()),"closed mixed source admitted for paid edits");
        pair(mixedA,mixedB,19);
        var localNatural=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(
                Path.of("classified-data/classroom_fol/both/7TYSDPnXqtud3bjku_inv12.als")));
        var localA=prepare(localNatural,"inv12"); var localB=prepare(localNatural,"inv12c");
        check(FastPaidDeclarationAlignment.supportsPair(localA.forms(),localB.forms()),"outer paid link with retained local binder");
        pair(localA,localB,27);
        var loneNatural=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(
                Path.of("classified-data/classroom_fol/both/83RWHQkLc3bdocWg4_inv12.als")));
        var loneA=prepare(loneNatural,"inv12"); var loneB=prepare(loneNatural,"inv12c");
        check(FastPaidDeclarationAlignment.supportsPair(loneA.forms(),loneB.forms()),"LONE fixed-prefix paid path");
        pair(loneA,loneB,15);
        var groupedNatural=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(
                Path.of("classified-data/classroom_fol/both/8dTfKqTs26w6fL87K_inv15.als")));
        var groupedA=prepare(groupedNatural,"inv15"); var groupedB=prepare(groupedNatural,"inv15c");
        check(FastPaidDeclarationAlignment.supportsPair(groupedA.forms(),groupedB.forms()),
                "distinct slots of one original declaration retain paid links");
        pair(groupedA,groupedB,18);
        var oneNatural=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(
                Path.of("classified-data/classroom_fol/both/TA943B2hFMmbp96Pj_inv8.als")));
        var oneA=prepare(oneNatural,"inv8"); var oneB=prepare(oneNatural,"inv8c");
        check(FastPaidDeclarationAlignment.supportsPair(oneA.forms(),oneB.forms()),"ONE prefix admits paid links only");
        pair(oneA,oneB,8);
        var model=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r:set A }
                sig B {}
                pred someA { some x:A | x in x.r }
                pred allA { all renamed:A | renamed in renamed.r }
                pred mixed { some x:A | all y:B | x->y in A->B }
                pred alternating { all x:A | some y:A | all z:A | x->y in r and y->z in r }
                pred alternatingRenamed { all first:A | some second:A | all third:A | first->second in r and second->third in r }
                pred forward { all x:A | some y:A | x = y }
                pred swapped { some y:A | all x:A | x = y }
                pred param[x:A] { some y:A | x in y }
                pred temporal { some x:A | always x in A }
                pred local { one x:A | x in A }
                pred setVar { some x:set A | x in A }
                pred disjoint { some disj x,y:A | x in y }
                """);
        var some=prepare(model,"someA"); var all=prepare(model,"allA");
        pair(some,all,1);
        check(FastPaidDeclarationAlignment.supports(prepare(model,"mixed").forms()),"mixed blocks retain paid-link eligibility");
        var alternating=prepare(model,"alternating"); var renamed=prepare(model,"alternatingRenamed");
        check(alternating.forms().get(0).getMatrixQuantiVars().stream().map(q -> q.getQuantifier().name()).toList()
                .equals(List.of("ALL","SOME","ALL")),"three original blocks are not globally sorted");
        check(FastPaidDeclarationAlignment.supports(alternating.forms()),"repeated carrier with alternating blocks");
        check(Canonical.distance(alternating.prepared(),renamed.prepared())==0
                && Canonical.distance(renamed.prepared(),alternating.prepared())==0,"scope-preserving alpha renaming both ways");
        var forward=prepare(model,"forward"); var swapped=prepare(model,"swapped");
        int mixedCost=Canonical.distance(forward.prepared(),swapped.prepared());
        check(mixedCost>0 && mixedCost==Canonical.distance(swapped.prepared(),forward.prepared()),"mixed exchange never free");
        var onePrefix=prepare(model,"local");
        check(onePrefix.forms().get(0).getLocalQuantiVars().isEmpty()
                && onePrefix.forms().get(0).getMatrixQuantiVars().get(0).getQuantifier()==QuantiVar.Quantifier.ONE
                && FastPaidDeclarationAlignment.supports(onePrefix.forms()),"single ONE is a fixed prefix, not a retained local binder");
        check(FastPaidDeclarationAlignment.supports(prepare(model,"disjoint").forms()),
                "source-observed disjoint SOME group admits paid links without erasing its payload");
        for(String name:List.of("param","temporal","setVar")) {
            var unsupported=prepare(model,name);
            check(!FastPaidDeclarationAlignment.supports(unsupported.forms()),"unsupported scope "+name);
        }
        check(!FastPaidDeclarationAlignment.supports(List.of()),"no phase");
        check(!FastPaidDeclarationAlignment.supports(List.of(a.forms().get(0),b.forms().get(0))),"multiple owners");
        check(!FastPaidDeclarationAlignment.supports(List.of(new NormalForm())),"unfrozen synthetic source");
        reject(() -> a.forms().get(0).getMatrixQuantiVars().get(0).setBindingPath("foreign"),"frozen source owner immutable");
        reject(() -> a.forms().get(0).getMatrixQuantiVars().get(0).setCarrierTypeName("foreign"),"frozen source type immutable");
        reject(() -> a.forms().get(0).getMatrixQuantiVars().get(0).setCardinality(QuantiVar.Cardinality.SET),"frozen multiplicity immutable");
        NormalForm original=a.forms().get(0);
        var firstType=original.getMatrixQuantiVars().get(0).getExactAlloyType();
        check(FastPaidDeclarationAlignment.supports(List.of(withFirstType(original,firstType))),"same parser type evidence retained");
        check(!FastPaidDeclarationAlignment.supports(List.of(withFirstType(original,null))),"missing declaration type evidence");
        var untrusted=is.fivefivefive.ACGN.alloy.ExactAlloyType.relation(firstType.alternatives().get(0));
        check(!FastPaidDeclarationAlignment.supports(List.of(withFirstType(original,untrusted))),"untrusted type lookalike");
        var foreign=prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(SOURCE)),"inv14");
        var foreignType=foreign.forms().get(0).getMatrixQuantiVars().get(0).getExactAlloyType();
        check(!FastPaidDeclarationAlignment.supports(List.of(withFirstType(original,foreignType))),"mixed parser-module binder evidence");
        for(int n=0;n<=3;n++) for(int m=0;m<=3;m++) for(int mask=0;mask<(1<<(n+m));mask++) {
            var xs=vars("x",n,"A"); var ys=vars("y",m,"A");
            for(int i=0;i<n;i++) xs.set(i,new QuantiVar(i,"x"+i,((mask>>i)&1)==0?"A":"B"));
            for(int j=0;j<m;j++) ys.set(j,new QuantiVar(j,"y"+j,((mask>>(n+j))&1)==0?"A":"B"));
            int[][] d=dp(xs,ys);
            Set<Map<String,String>> actual=new HashSet<>(), expected=new HashSet<>();
            FastPaidDeclarationAlignment.enumerate(xs,ys,d,FastPaidDeclarationAlignmentTest::update,actual::add);
            brute(xs,ys,0,0,0,new HashMap<>(),d[n][m],expected);
            check(actual.equals(expected),"all minimum paid correspondences, no invented links");
            for(var map:actual) check(new HashSet<>(map.values()).size()==map.size(),"injective occurrence links");
        }
        var many=vars("x",20,"A"); var fewer=vars("y",10,"B");
        reject(() -> FastPaidDeclarationAlignment.enumerate(many,fewer,dp(many,fewer),
                FastPaidDeclarationAlignmentTest::update,ignored -> {}),"adversarial path count budget");
        reject(() -> FastPaidDeclarationAlignment.enumerate(vars("x",65,"A"),List.of(),new int[0][],
                FastPaidDeclarationAlignmentTest::update,ignored -> {}),"size budget before table traversal");
        System.out.println("FastPaidDeclarationAlignmentTest: "+checks+" checks passed; native paid alignment only, full alignment BLOCKED");
    }
}

package is.fivefivefive.CanDis.core;

import java.util.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.DistanceReplayDiagnostics;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;

/** Focused bound-versus-lexical cost controls and source comparison view access. */
public final class BoundVariableCostTest {
    private static int checks;
    private static int nextId=90000;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    public static EGraphNode comparison(NormalForm source) {
        return NormalForm.fastComparisonViews(List.of(source)).get(0).getMatrixEGraph();
    }
    private static EGraphNode variable(String name,boolean bound) {
        var n=new EGraphNode(nextId++,EGraphNode.Opcode.VARIABLE,List.of(),false,0,false,EGraphNode.Metatype.ATOMIC);
        n.setSourceName(name);
        if(bound) {
            n.setAlphaName(name);
            // Unit-level truth table only; source-issued flags are checked below.
            try { var f=EGraphNode.class.getDeclaredField("comparisonRegisteredBinding"); f.setAccessible(true); f.set(n,true); }
            catch(ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        return n;
    }
    private static Canonical.Prepared prepare(CompModule module,String name) {
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static List<EGraphNode> marked(EGraphNode node) {
        var result=new ArrayList<EGraphNode>();
        if(node.hasComparisonLocalCoordinate()) result.add(node);
        for(var child:node.getChildren()) result.addAll(marked(child));
        return result;
    }
    public static void main(String[] args) throws Exception {
        var names=List.of("a","b","c");
        var nodes=new HashMap<String,EGraphNode>();
        for(String name:names) for(boolean bound:new boolean[]{false,true}) nodes.put(name+bound,variable(name,bound));
        int admitted=0;
        for(int code=0;code<64;code++) {
            int value=code; var map=new HashMap<String,String>(); var targets=new HashSet<String>(); boolean injective=true;
            for(String name:names) {
                int choice=value%4; value/=4;
                if(choice!=0) { String target=names.get(choice-1); if(!targets.add(target)) injective=false; map.put(name,target); }
            }
            if(!injective) continue;
            admitted++;
            for(String x:names) for(String y:names) for(boolean l:new boolean[]{false,true}) for(boolean r:new boolean[]{false,true}) {
                int actual=CanonicalDistance.variableUpdateCost(nodes.get(x+l),nodes.get(y+r),map);
                boolean explicit=y.equals(map.get(x));
                boolean lexical=!l && !r && !map.containsKey(x) && x.equals(y) && !targets.contains(y);
                check(actual==(explicit||lexical?0:1),"bound requires selected edge; lexical fallback unchanged");
            }
        }
        check(admitted==34,"all injective partial maps on three names");
        var x=variable("_q0",true); var y=variable("_q0",true);
        x.setSemanticIdentity("original/source/ownerA"); y.setSemanticIdentity("original/target/ownerB");
        check(CanonicalDistance.variableUpdateCost(x,y,Map.of())==1,"same alpha does not equate source owners");
        check(CanonicalDistance.variableUpdateCost(x,y,Map.of("_q0","_q0"))==0,"checked explicit self correspondence retained");
        check(CanonicalDistance.variableUpdateCost(variable("@fast-local:0:0",true),
                variable("@fast-local:0:0",true),Map.of())==1,"forged local spelling cannot obtain producer marker");
        var lexical=variable("_q0",false);lexical.setAlphaName("_q0");
        var raw=new NormalForm();raw.addEClass(lexical);raw.freezeForCertification();
        var projected=comparison(raw);
        check(!projected.hasComparisonRegisteredBinding()
                && CanonicalDistance.variableUpdateCost(projected,projected,Map.of())==0,
                "alpha spelling alone does not register a lexical variable as a bound coordinate");
        var model=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A, B { }
                pred typedA { all x:A | some x }
                pred typedB { all x:B | some x }
                pred renamedA { all first:A | some first }
                pred fixed { one x:A | some x }
                pred scoped { all x:A | (some y:A | x != y) or some x }
                pred scopedRenamed { all first:A | (some second:A | first != second) or some first }
                pred parameter[x:A] { some x }
                pred parameterRenamed[y:A] { some y }
                pred temporal { all x:A | always some x }
                pred temporalRenamed { all first:A | always some first }
                """);
        for(var pair:List.of(List.of("typedA","renamedA"),List.of("scoped","scopedRenamed"),
                List.of("parameter","parameterRenamed"),List.of("temporal","temporalRenamed"))) {
            var a=prepare(model,pair.get(0)); var b=prepare(model,pair.get(1));
            check(Canonical.distance(a,b)==0 && Canonical.distance(b,a)==0,"selected alpha renaming retains owner role "+pair);
        }
        for(var pair:List.of(List.of("typedA","typedB"),List.of("typedA","fixed"))) {
            var a=prepare(model,pair.get(0)); var b=prepare(model,pair.get(1));
            for(boolean reverse:new boolean[]{false,true}) {
                var report=DistanceReplayDiagnostics.capture(reverse?b:a,reverse?a:b);
                var fast=report.getJSONObject("fast");
                check(fast.getInt("quantifierDistance")==1,"typed/multiplicity update remains charged");
                check(!fast.getJSONObject("selectedPaidDeclarationCorrespondence").isEmpty(),"paid path explicitly carries changed binding");
                check(fast.getInt("distance")==report.getJSONObject("certified").getInt("distance"),"paid update parity without scalar substitution");
                check(fast.getBoolean("matrixReplayChecked") && !report.getBoolean("complete"),"native replay, no public admission");
            }
        }
        var local=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig Event {}
                sig State { trans: Event -> State }
                sig Init in State {}
                pred grouped {
                  let t = { x,y:State | some e:Event | x->e->y in trans } |
                    all s:State | some i:Init | s in i.^t
                }
                pred split {
                  let t = { x:State,y:State | some e:Event | x->e->y in trans } |
                    all s:State | some i:Init | s in i.^t
                }
                """);
        var grouped=prepare(local,"grouped"); var split=prepare(local,"split");
        check(Canonical.distance(grouped,split)==0 && Canonical.distance(split,grouped)==0,
                "existing local grouping law unchanged, not newly admitted");
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of("grouped"),local);
        visitor.visit(new ModelUnit(null,local),null);
        var agent=new IRAgent(visitor.getForest().get(visitor.getForestId("grouped"))); agent.computeNormalForm();
        var source=agent.normalForms().get(0); source.freezeForCertification();
        var locals=marked(comparison(source));
        check(!locals.isEmpty() && marked(source.getMatrixEGraph()).isEmpty(),"marker belongs only to detached local projection");
        for(var n:locals) {
            check(CanonicalDistance.variableUpdateCost(n,n,Map.of())==0,"existing local coordinate identity retained");
            check(CanonicalDistance.variableUpdateCost(n,variable(n.getAlphaName(),true),Map.of())==1,
                    "one-sided or forged coordinate ownership cannot enable fallback");
        }
        check(nodesRegistered(comparison(source))>locals.size(),"original outer and local declarations issue binding markers");
        System.out.println("BoundVariableCostTest: "+checks+" checks passed");
    }
    private static int nodesRegistered(EGraphNode n) {
        int count=n.hasComparisonRegisteredBinding()?1:0;
        for(var child:n.getChildren())count+=nodesRegistered(child);
        return count;
    }
}

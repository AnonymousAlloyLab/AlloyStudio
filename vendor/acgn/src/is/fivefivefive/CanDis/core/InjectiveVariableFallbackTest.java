package is.fivefivefive.CanDis.core;

import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.DistanceReplayDiagnostics;
import parser.ast.nodes.ModelUnit;

/** Injective selected alignments cannot gain a second preimage by name fallback. */
public final class InjectiveVariableFallbackTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static int cost(String a,String b,Map<String,String> m) { return CanonicalDistance.variableUpdateCost(a,b,m); }
    private static Canonical.Prepared prepare(CompModule module,String name) {
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    public static void main(String[] args) throws Exception {
        check(cost("_q0","_q0",Map.of("_q1","_q0"))==1,"occupied target rejects implicit identity");
        check(cost("_q1","_q0",Map.of("_q1","_q0"))==0,"explicit mapped source retained");
        check(cost("_q1","_q1",Map.of("_q1","_q0"))==1,"explicit mapping forbids old spelling");
        check(cost("free","free",Map.of())==0,"unused lexical identity retained");
        check(cost("free","other",Map.of())==1,"different lexical identity charged");
        check(cost("free","free",Map.of("bound","target"))==0,"unrelated selected mapping does not affect free identity");
        check(cost("same","same",Map.of("same","same"))==0,"explicit self-map is not rejected as reserved");
        List<String> names=List.of("a","b","c"); int admitted=0;
        for(int code=0;code<64;code++) {
            int value=code; Map<String,String> map=new HashMap<>(); Set<String> used=new HashSet<>(); boolean injective=true;
            for(String name:names) {
                int choice=value%4; value/=4;
                if(choice!=0) { String target=names.get(choice-1); if(!used.add(target)) injective=false; map.put(name,target); }
            }
            if(!injective) continue; admitted++;
            for(String a:names) for(String b:names) for(String target:names)
                check(a.equals(b) || cost(a,target,map)!=0 || cost(b,target,map)!=0,"zero-cost extension remains injective");
            for(var entry:map.entrySet()) check(cost(entry.getKey(),entry.getValue(),map)==0,"selected edge retained");
            for(String name:names) if(!map.containsKey(name) && !used.contains(name))
                check(cost(name,name,map)==0,"unused identity extension retained");
        }
        check(admitted==34,"all34 injective partial maps on three names");
        var natural=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(InjectiveVariableReplayTest.SOURCE));
        var a=prepare(natural,"inv12"); var b=prepare(natural,"inv12c");
        for(boolean reverse:new boolean[]{false,true}) {
            var report=DistanceReplayDiagnostics.capture(reverse?b:a,reverse?a:b);
            for(String arm:List.of("fast","certified")) {
                JSONObject result=report.getJSONObject(arm);
                check(result.getInt("distance")==18 && result.getInt("temporalDistance")==0
                        && result.getInt("quantifierDistance")==3 && result.getInt("matrixDistance")==15,"actual18=[0,3,15] "+arm);
                check(result.getBoolean("numericalCostCoverage") && !result.getBoolean("complete"),"native replay only");
            }
        }
        var reduced=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A, B {}
                pred lhs { some x:A | all p:B | x->p in A->B }
                pred renamed { some first:A | all second:B | first->second in A->B }
                pred rhs { all x:B | some x }
                pred rhsRenamed { all target:B | some target }
                """);
        var left=prepare(reduced,"lhs"); var renamed=prepare(reduced,"renamed");
        var right=prepare(reduced,"rhs"); var rightRenamed=prepare(reduced,"rhsRenamed");
        check(Canonical.distance(left,renamed)==0 && Canonical.distance(renamed,left)==0,"source binder renaming retains zero");
        check(Canonical.distance(right,rightRenamed)==0 && Canonical.distance(rightRenamed,right)==0,"target binder renaming retains zero");
        int distance=Canonical.distance(left,right);
        check(distance==Canonical.distance(right,left),"reduced variable collision symmetric");
        check(distance==Canonical.distance(renamed,rightRenamed),"display spelling cannot supply a second binder correspondence");
        System.out.println("InjectiveVariableFallbackTest: "+checks+" checks passed");
    }
}

package is.fivefivefive.CanDis.metric;

import java.util.List;
import is.fivefivefive.CanDis.metric.RepairView.*;
import is.fivefivefive.CanDis.theory.*;

/** Metric-unit controls; these synthetic views do not claim source certification. */
public final class TemporalReferenceChargeTest {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++; if(!value) throw new AssertionError(message);
    }
    private static Node reference(String label,List<Integer> branches,GraphType type) {
        return new Node("REF","original-edge","semantic/"+branches+"/"+label,null,-1,
                ContainerKind.SEQUENCE,false,List.of()).withObservation(type,branches,label);
    }
    private static RepairView view(Node node) {
        return new RepairView(new TemporalNode("NONE",List.of()),
                List.of(new Phase(List.of(),List.of(),node)),SemanticProfile.alloyOverflowForbidding(),
                StructuralKey.leaf("synthetic-metric-only","no-source-authority"));
    }
    private static void cost(Node a,Node b,int expected) {
        for(var pair:List.of(List.of(a,b),List.of(b,a))) {
            var left=view(pair.get(0));var right=view(pair.get(1));
            check(QuotientRepairDistance.evaluateUncheckedForTesting(left,right).matrixDistance()==expected,"typed edge cost in metric-only fixture");
            boolean rejected=false;
            try {QuotientRepairDistance.evaluate(left,right);} catch(IllegalStateException requiredAuthority) {rejected=true;}
            check(rejected,"synthetic view never acquires certified projection authority");
        }
    }
    public static void main(String[] args) {
        var a=reference("ALWAYS",List.of(1),GraphType.BOOL);
        // Equal ordinals without an actual selected temporal payment cannot waive an edit.
        cost(a,reference("EVENTUALLY",List.of(1),GraphType.BOOL),1);
        cost(a,reference("ALWAYS",List.of(2),GraphType.BOOL),1);
        cost(a,reference("ALWAYS",List.of(1,2),GraphType.BOOL),1);
        cost(reference("UNTIL",List.of(1,2),GraphType.BOOL),reference("UNTIL",List.of(2,1),GraphType.BOOL),1);
        cost(reference("UNTIL",List.of(1,2),GraphType.BOOL),reference("UNTIL",List.of(1,1),GraphType.BOOL),1);
        cost(a,reference("ALWAYS",List.of(1),GraphType.relation(GraphType.constructor("Foreign"))),1);
        var x=new Node("REF","foreign/module/A",null,-1,ContainerKind.SEQUENCE,false,List.of());
        var y=new Node("REF","foreign/module/B",null,-1,ContainerKind.SEQUENCE,false,List.of());
        cost(x,y,1);cost(a,x,1);cost(a,a,0);
        System.out.println("TemporalReferenceChargeTest: "+checks+" checks");
    }
}

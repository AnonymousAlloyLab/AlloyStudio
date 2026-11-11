package is.fivefivefive.CanDis.core;

import java.lang.reflect.*;
import java.util.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import parser.ast.nodes.ModelUnit;

/** Endpoint-local memoization must preserve occurrence authority and trace choices. */
public final class PreparedOrbitCacheTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static Object field(Object o,String name)throws Exception {
        var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static Canonical.Prepared[] pair()throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A { r: set A } pred p { all x,y:A | x in y.r } pred q { all y,x:A | y in x.r }");
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of("p","q"),module);
        visitor.visit(new ModelUnit(null,module),null);
        return new Canonical.Prepared[]{Canonical.prepare(visitor.getForest().get(visitor.getForestId("p"))),
                Canonical.prepare(visitor.getForest().get(visitor.getForestId("q")))};
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        var pair=pair();var foreign=pair();
        for(int d=0;d<2;d++) {
            var left=pair[d];var right=pair[1-d];
            var first=Canonical.evaluateWithTrace(left,right);
            var metadata=field(field(left,"delegate"),"metadata");
            var cache=(Map<Object,Object>)field(metadata,"groupedOrders");
            check(cache.size()==1,"one frozen endpoint order entry");
            var entry=cache.values().iterator().next();
            check(Canonical.distance(left,right)==first.distance(),"same scalar after trace");
            check(first.json().similar(Canonical.evaluateWithTrace(left,right).json()),"same decisions and replay");
            check(entry==cache.values().iterator().next(),"successful admission reused");
            var form=(NormalForm)cache.keySet().iterator().next();
            var source=(NormalForm)((Map<?,?>)field(metadata,"originals")).get(form);
            var bindings=(List<QuantiVar>)field(entry,"bindings");
            var method=metadata.getClass().getDeclaredMethod("groupedOrders",NormalForm.class,List.class);
            method.setAccessible(true);
            var orders=(List<int[]>)method.invoke(metadata,form,bindings);
            var direct=FastGroupedDeclarationOrbit.orders(source,form,bindings);
            check(orders.size()==direct.size(),"unchanged candidate count");
            for(int i=0;i<orders.size();i++)check(Arrays.equals(orders.get(i),direct.get(i)),"unchanged candidate order");
            orders.get(0)[0]=999;
            check(((List<int[]>)method.invoke(metadata,form,bindings)).get(0)[0]==0,"no mutable array escape");
            var foreignMetadata=field(field(foreign[d],"delegate"),"metadata");
            check(field(foreignMetadata,"groupedOrders")!=cache,"separate prepared scope");
            var foreignForms=(List<NormalForm>)field(field(foreign[d],"delegate"),"normalForms");
            try {
                method.invoke(metadata,form,foreignForms.get(0).getMatrixQuantiVars());
                throw new AssertionError("foreign occurrence order accepted");
            } catch(InvocationTargetException expected) {
                check(expected.getCause() instanceof IllegalArgumentException,"foreign occurrence rejection");
            }
            check(entry==cache.values().iterator().next(),"failed admission never replaces cached success");
            String prior=System.getProperty("acgn.maxGroupElements");
            try {
                System.setProperty("acgn.maxGroupElements","1");
                try {
                    Canonical.distance(left,right);
                    throw new AssertionError("cached endpoint bypassed changed resource policy");
                } catch(IllegalStateException expected) { checks++; }
            } finally {
                if(prior==null)System.clearProperty("acgn.maxGroupElements");
                else System.setProperty("acgn.maxGroupElements",prior);
            }
            check(Canonical.distance(left,right)==first.distance(),"restored resource policy preserves result");
        }
        System.out.println("PreparedOrbitCacheTest passed: "+checks+" checks");
    }
}

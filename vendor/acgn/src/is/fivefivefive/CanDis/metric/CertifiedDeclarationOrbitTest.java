package is.fivefivefive.CanDis.metric;

import java.util.*;
import is.fivefivefive.CanDis.metric.RepairView.*;
import is.fivefivefive.CanDis.theory.*;

/** Finite search and admission controls; synthetic bindings are not public certified views. */
public final class CertifiedDeclarationOrbitTest {
    private static int checks;
    private static void check(boolean b,String why) { checks++; if(!b) throw new AssertionError(why); }
    private static void reject(Runnable r,String why) {
        checks++; try { r.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static Declaration declaration(String kind,String carrier,int exchange,int disj,String cardinality,List<Integer> deps) {
        return new Declaration(kind,carrier,cardinality,disj,"domain/"+carrier,exchange,deps)
                .withObservation(GraphType.typeVariable(carrier),StructuralKey.leaf("domain",carrier));
    }
    private static Binding binding(int ordinal,Declaration d,int owner,List<Integer> orbit,boolean erased) {
        return new Binding(BindingRole.MATRIX,ordinal,owner,ordinal,d,"source/"+ordinal,orbit,erased);
    }
    private static List<Binding> group(int n,String carrier) {
        List<Integer> orbit=new ArrayList<>(); for(int i=0;i<n;i++) orbit.add(i);
        List<Binding> result=new ArrayList<>();
        for(int i=0;i<n;i++) result.add(binding(i,declaration("ALL",carrier,0,0,"ONE",List.of()),0,orbit,true));
        return result;
    }
    public static void main(String[] args) {
        var group=group(2,"P"); var a=group.get(0); var b=group.get(1);
        check(CertifiedDeclarationOrbit.exchangeable(a,b),"typed full orbit");
        CertifiedDeclarationOrbit.requirePermutation(group,new int[]{1,0});
        reject(()->CertifiedDeclarationOrbit.requirePermutation(group,new int[]{1,1}),"duplicate target");
        reject(()->CertifiedDeclarationOrbit.requirePermutation(group,new int[]{-1,0}),"negative target");
        reject(()->CertifiedDeclarationOrbit.requirePermutation(group,new int[]{0,2}),"foreign coordinate");
        reject(()->CertifiedDeclarationOrbit.requirePermutation(group,new int[]{0}),"missing premise");
        for(var d:List.of(declaration("SOME","P",0,0,"ONE",List.of()),
                declaration("ALL","Q",0,0,"ONE",List.of()),declaration("ALL","P",1,0,"ONE",List.of()),
                declaration("ALL","P",0,1,"ONE",List.of()),declaration("ALL","P",0,0,"SET",List.of()),
                declaration("ALL","P",0,0,"ONE",List.of(0)),
                new Declaration("ALL","P","ONE",0,"domain/P",0,List.of())))
            check(!CertifiedDeclarationOrbit.exchangeable(a,binding(1,d,0,List.of(0,1),true)),"changed typed premise");
        check(!CertifiedDeclarationOrbit.exchangeable(a,binding(1,b.declaration(),1,List.of(0,1),true)),"temporal owner");
        check(!CertifiedDeclarationOrbit.exchangeable(a,binding(1,b.declaration(),0,List.of(1),true)),"missing inverse orbit premise");
        check(!CertifiedDeclarationOrbit.exchangeable(a,binding(1,b.declaration(),0,List.of(0,1),false)),"path-erasure absent");
        var local=new Binding(BindingRole.LOCAL_INHERITED,1,0,1,b.declaration(),"path","local-owner",List.of(0,1),false);
        check(!CertifiedDeclarationOrbit.exchangeable(a,local),"local/outer capture");
        for(String kind:List.of("ONE","LONE","NO")) {
            var d=declaration(kind,"P",0,0,"ONE",List.of());
            check(!CertifiedDeclarationOrbit.exchangeable(binding(0,d,0,List.of(0,1),true),binding(1,d,0,List.of(0,1),true)),
                    "ordered kind "+kind);
        }
        for(int n=1;n<=5;n++) for(int k=0;k<=n;k++) {
            var left=group(n,"P"); var right=new ArrayList<Binding>(); var original=new LinkedHashMap<Integer,Integer>();
            for(int i=0;i<k;i++) {
                right.add(binding(i,declaration("ALL","T"+i,0,0,"ONE",List.of()),0,List.of(i),true)); original.put(i,i);
            }
            var results=new HashSet<Map<Integer,Integer>>();
            CertifiedDeclarationOrbit.forEach(left,right,original,choice->{
                CertifiedDeclarationOrbit.requirePermutation(left,choice.left());
                CertifiedDeclarationOrbit.requirePermutation(right,choice.right());
                check(choice.correspondence().size()==original.size(),"paid multiplicity retained");
                check(new HashSet<>(choice.correspondence().values()).size()==original.size(),"injective target links");
                for(var e:original.entrySet()) check(choice.correspondence().get(choice.left()[e.getKey()])==choice.right()[e.getValue()],
                        "transported exact endpoint link");
                results.add(choice.correspondence());
            },new CertifiedDeclarationOrbit.Budget());
            int expected=1; for(int i=0;i<k;i++) expected*=n-i;
            check(results.size()==expected,"all bounded partial injections, no lost path");
        }
        var both=new HashSet<Map<Integer,Integer>>();
        CertifiedDeclarationOrbit.forEach(group,group,Map.of(0,0),x->both.add(x.correspondence()),new CertifiedDeclarationOrbit.Budget());
        check(both.size()==4,"both endpoint orbits independently admitted");
        var priority=new ArrayList<CertifiedDeclarationOrbit.Choice>();
        CertifiedDeclarationOrbit.forEach(group,group,Map.of(1,1),priority::add,new CertifiedDeclarationOrbit.Budget());
        check(priority.get(0).correspondence().equals(Map.of(1,1))
                && Arrays.equals(priority.get(0).left(),new int[]{0,1})
                && Arrays.equals(priority.get(0).right(),new int[]{0,1}),"identity representative first preserves prior tie witness");
        check(CertifiedDeclarationOrbit.isIdentity(priority.get(0)),"canonical candidate identity");
        check(priority.stream().skip(1).noneMatch(CertifiedDeclarationOrbit::isIdentity),"nonidentity alternatives distinguished");
        for(int old=0;old<=5;old++) for(int next=0;next<=5;next++)
            for(boolean oldBase:new boolean[]{false,true}) for(boolean nextBase:new boolean[]{false,true}) {
                boolean prefer=CertifiedDeclarationOrbit.prefer(next,old,nextBase,oldBase);
                check((prefer?next:old)==Math.min(next,old),"global tie priority preserves minimum");
                if(next==old) check(prefer==(nextBase&&!oldBase),"only canonical witness wins an equal-cost replacement");
            }
        check(CertifiedDeclarationOrbit.prefer(25,25,true,false),"later canonical DP path displaces earlier orbit tie");
        check(CertifiedDeclarationOrbit.prefer(26,27,false,true),"strict orbit improvement retained");
        reject(()->new CertifiedDeclarationOrbit.Budget(0),"invalid budget");
        var shared=new CertifiedDeclarationOrbit.Budget(3);
        CertifiedDeclarationOrbit.forEach(group,group(1,"Q"),Map.of(0,0),x->{},shared);
        reject(()->CertifiedDeclarationOrbit.forEach(group,group(1,"Q"),Map.of(0,0),x->{},shared),"budget shared across minimum paths");
        var many=group(10,"P"); var paid=new LinkedHashMap<Integer,Integer>(); for(int i=0;i<5;i++) paid.put(i,i);
        reject(()->CertifiedDeclarationOrbit.forEach(many,many,paid,x->{},new CertifiedDeclarationOrbit.Budget(100)),
                "adversarial alternative growth fails closed before product materialization");
        var oversized=group(65,"P");
        reject(()->CertifiedDeclarationOrbit.forEach(oversized,group,Map.of(0,0),x->{},new CertifiedDeclarationOrbit.Budget()),"size bound");
        int[] count={0};
        CertifiedDeclarationOrbit.forEach(oversized,oversized,Map.of(),x->count[0]++,new CertifiedDeclarationOrbit.Budget(1));
        check(count[0]==1,"no alternatives expanded when no paid coordinate moves");
        System.out.println("CertifiedDeclarationOrbitTest: "+checks+" checks passed");
    }
}

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
        selectionControls();
        var group=group(2,"P"); var a=group.get(0); var b=group.get(1);
        retainedSourceControls(group);
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
        for(String kind:List.of("ALL","SOME"))for(int n=2;n<=5;n++) {
            var orbit=new ArrayList<Integer>();for(int i=0;i<n;i++)orbit.add(i);
            var disjoint=new ArrayList<Binding>();var d=declaration(kind,"P",0,1,"ONE",List.of());
            for(int i=0;i<n;i++)disjoint.add(new Binding(BindingRole.MATRIX,i,0,i,d,"shared-declaration",orbit,true));
            int[] reverse=new int[n];for(int i=0;i<n;i++)reverse[i]=n-1-i;
            CertifiedDeclarationOrbit.requirePermutation(disjoint,reverse);
            check(CertifiedDeclarationOrbit.exchangeable(disjoint.get(0),disjoint.get(1)),"same disjoint "+kind+" group of "+n);
            var foreignPath=new Binding(BindingRole.MATRIX,1,0,1,d,"another-declaration",orbit,true);
            check(!CertifiedDeclarationOrbit.exchangeable(disjoint.get(0),foreignPath),"same group integer is not lexical owner authority");
            int[] duplicate=reverse.clone();duplicate[0]=duplicate[1];
            reject(()->CertifiedDeclarationOrbit.requirePermutation(disjoint,duplicate),"disjoint group does not merge slots");
        }
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
        var keeps=new ArrayList<CertifiedDeclarationOrbit.Choice>();
        CertifiedDeclarationOrbit.forEach(group,group,Map.of(),Map.of(0,0,1,1),keeps::add,
                new CertifiedDeclarationOrbit.Budget());
        check(keeps.size()==2,"equivalent two-sided keep witnesses are quotiented");
        check(keeps.stream().allMatch(c->c.correspondence().isEmpty()),"zero keeps never become paid updates");
        check(keeps.stream().anyMatch(c->Arrays.equals(c.left(),new int[]{0,1})
                &&Arrays.equals(c.right(),new int[]{1,0})),"empty paid map retains nonidentity survivor transport");
        var six=group(6,"P");var sixLinks=new LinkedHashMap<Integer,Integer>();
        for(int i=0;i<6;i++)sixLinks.put(i,i);
        var withDeletion=new ArrayList<>(six);
        withDeletion.add(binding(6,declaration("SOME","Q",1,0,"ONE",List.of()),0,List.of(6),true));
        var sixMaps=new HashSet<Map<Integer,Integer>>();
        CertifiedDeclarationOrbit.forEach(withDeletion,six,Map.of(),sixLinks,c->{
            CertifiedDeclarationOrbit.requirePermutation(withDeletion,c.left());
            CertifiedDeclarationOrbit.requirePermutation(six,c.right());
            var map=new HashMap<Integer,Integer>();
            for(var e:sixLinks.entrySet())map.put(c.left()[e.getKey()],c.right()[e.getValue()]);
            sixMaps.add(Map.copyOf(map));
            check(c.left()[6]==6 && c.correspondence().isEmpty(),"unrelated deletion and zero keeps retained");
            int[] prior=new int[7];Arrays.fill(prior,-1);map.forEach((i,j)->prior[i]=j);
            int[] saved=prior.clone();
            var retained=CertifiedDeclarationOrbit.retainedSourceChoice(withDeletion,six,sixLinks,Map.of(),prior);
            check(Arrays.equals(prior,saved),"prior map not mutated");
            if(retained!=null) {
                check(retained.left()[6]==6 && retained.correspondence().isEmpty(),"retained deletion and no manufactured payment");
                check(Arrays.equals(retained.right(),CertifiedDeclarationOrbit.identity(6)),"retained target stays fixed");
                for(int i=0;i<6;i++)check(prior[retained.left()[i]]==i,"same attaining relative map");
            } else check(Arrays.equals(prior,new int[]{0,1,2,3,4,5,-1}),"only identity duplicate omitted");
        },new CertifiedDeclarationOrbit.Budget());
        check(sixMaps.size()==720,"six survivors cover every full map within unchanged budget");
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

    private static void selectionControls() {
        var paid=Map.of(2,2,3,1);
        int[] mapping={0,-1,2,1};
        var old=new CertifiedDeclarationOrbit.SelectionKey(27,false,true,true,false,paid,mapping);
        var next=new CertifiedDeclarationOrbit.SelectionKey(27,false,true,true,true,paid,mapping);
        check(!CertifiedDeclarationOrbit.prefer(27,27,false,false),"old priority retains first unsupported tie");
        check(CertifiedDeclarationOrbit.prefer(next,old),"same-map paid-supported tie selected");
        check(!CertifiedDeclarationOrbit.prefer(old,next),"no reverse tie demotion");
        check(!CertifiedDeclarationOrbit.paidSupported(paid.keySet(),new int[]{1,0,2,3}),"unpaid swap unsupported");
        check(CertifiedDeclarationOrbit.paidSupported(paid.values(),new int[]{0,2,1}),"paid target swap supported");
        for(int flags=0;flags<32;flags++)for(int cost=26;cost<=28;cost++) {
            boolean canonical=(flags&1)!=0,linked=(flags&2)!=0,existing=(flags&4)!=0;
            var candidate=new CertifiedDeclarationOrbit.SelectionKey(cost,canonical,linked,existing,true,
                    (flags&8)==0?paid:Map.of(1,2,3,1),(flags&16)==0?mapping:new int[]{-1,0,2,1});
            boolean prior=cost<27 || cost==27 && canonical;
            boolean tie=cost==27 && !canonical && linked && existing && (flags&24)==0;
            check(CertifiedDeclarationOrbit.prefer(candidate,old)==(prior||tie),"cost/priority/map negative controls");
        }
        for(boolean canonical:List.of(false,true))for(boolean linked:List.of(false,true))
            for(boolean existing:List.of(false,true)) {
                var incumbent=new CertifiedDeclarationOrbit.SelectionKey(27,canonical,linked,existing,false,paid,mapping);
                var candidate=new CertifiedDeclarationOrbit.SelectionKey(27,false,true,false,true,paid,mapping);
                boolean expected=!canonical && (!linked || !existing);
                check(CertifiedDeclarationOrbit.prefer(candidate,incumbent)==expected,"higher priorities retained");
            }
        var mutable=new HashMap<>(paid);
        var frozen=new CertifiedDeclarationOrbit.SelectionKey(27,false,true,true,true,mutable,mapping);
        mutable.clear();mapping[0]=1;
        check(frozen.paid().equals(paid)&&frozen.mapping()[0]==0&&frozen.mapping()[1]==-1,
                "winner keys retain owned paid links and one-sided slots");
    }

    private static void retainedSourceControls(List<Binding> group) {
        var links=Map.of(0,0,1,1);int[] swapped={1,0};
        var choice=CertifiedDeclarationOrbit.retainedSourceChoice(group,group,links,Map.of(),swapped);
        check(choice!=null && Arrays.equals(choice.left(),swapped)
                && Arrays.equals(choice.right(),new int[]{0,1}),"source-only prior presentation");
        for(int[] bad:List.of(new int[]{-1,0},new int[]{0,0},new int[]{2,0},new int[]{-2,0},new int[]{1}))
            check(CertifiedDeclarationOrbit.retainedSourceChoice(group,group,links,Map.of(),bad)==null,
                    "missing, duplicated or invalid inverse coordinate");
        check(CertifiedDeclarationOrbit.retainedSourceChoice(group,group,Map.of(0,0,1,0),Map.of(),swapped)==null,
                "noninjective declaration links");
        check(CertifiedDeclarationOrbit.retainedSourceChoice(group,group,links,Map.of(0,1),swapped)==null,
                "altered paid target");
        check(CertifiedDeclarationOrbit.retainedSourceChoice(group,group,links,Map.of(0,0),swapped)==null,
                "paid source cannot move");
        var extra=group(3,"P");
        check(CertifiedDeclarationOrbit.retainedSourceChoice(extra,group,links,Map.of(),new int[]{-1,0,1})==null,
                "deleted slot cannot become a survivor");
        check(CertifiedDeclarationOrbit.retainedSourceChoice(group,extra,links,Map.of(),new int[]{2,0})==null,
                "inserted target cannot acquire a source");
        var a=group.get(0);var b=group.get(1);
        for(var bad:List.of(
                binding(1,declaration("ALL","Q",0,0,"ONE",List.of()),0,List.of(0,1),true),
                binding(1,b.declaration().withObservation(GraphType.typeVariable("P"),StructuralKey.leaf("domain","Q")),0,List.of(0,1),true),
                binding(1,b.declaration(),1,List.of(0,1),true),
                binding(1,declaration("ALL","P",0,0,"ONE",List.of(0)),0,List.of(0,1),true),
                binding(1,b.declaration(),0,List.of(1),true),
                binding(1,b.declaration(),0,List.of(0,1),false),
                new Binding(BindingRole.PARAMETER,1,-1,-1,b.declaration(),"source/1",List.of(),false),
                new Binding(BindingRole.LOCAL_INHERITED,1,0,1,b.declaration(),"source/1","local-owner",List.of(0,1),false)))
            check(CertifiedDeclarationOrbit.retainedSourceChoice(List.of(a,bad),group,links,Map.of(),swapped)==null,
                    "retained choice keeps typed, domain, phase, owner, dependency, role and orbit guards");
        reject(()->new Binding(BindingRole.MATRIX,1,0,1,b.declaration(),"source/1","foreign-owner",List.of(0,1),true),
                "constructor rejects a foreign local owner on an outer slot");
        check(CertifiedDeclarationOrbit.retainedSourceChoice(group(65,"P"),group,links,Map.of(),new int[65])==null,
                "oversized supplementary work unavailable");
    }
}

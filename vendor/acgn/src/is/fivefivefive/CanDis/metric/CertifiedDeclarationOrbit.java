package is.fivefivefive.CanDis.metric;

import java.util.*;
import java.util.function.Consumer;
import is.fivefivefive.CanDis.metric.RepairView.Binding;
import is.fivefivefive.CanDis.metric.RepairView.BindingRole;

/** Paid paths transported only within the full symmetric classes checked by RepairProjection. */
final class CertifiedDeclarationOrbit {
    static final int MAX_BINDINGS = 64;
    private static final long MAX_VISITS = 100_000;
    record Choice(Map<Integer,Integer> correspondence,int[] left,int[] right) { }

    static final class Budget {
        private final long maximum;
        private long visits;
        Budget() { this(MAX_VISITS); }
        Budget(long maximum) {
            if(maximum<=0) throw new IllegalArgumentException("Declaration orbit budget must be positive");
            this.maximum=maximum;
        }
        void spend() {
            if(visits>=maximum) throw new IllegalStateException("DECLARATION_ORBIT_VISIT_BUDGET");
            visits++;
        }
    }

    private CertifiedDeclarationOrbit() { }

    static boolean exchangeable(Binding a,Binding b) {
        var x=a.declaration(); var y=b.declaration();
        return a.role()==BindingRole.MATRIX && b.role()==BindingRole.MATRIX
                && a.ownerPhase()==b.ownerPhase() && a.ownerContext().equals(b.ownerContext())
                && a.prenexPathErasureCertified() && b.prenexPathErasureCertified()
                && a.certifiedOrbit().contains(b.coordinate()) && b.certifiedOrbit().contains(a.coordinate())
                && (x.quantifier().equals("ALL") || x.quantifier().equals("SOME"))
                && x.cardinality().equals("ONE") && x.disjointnessClass()==0
                && x.dependencies().isEmpty() && y.dependencies().isEmpty()
                && x.exchangeClass()==y.exchangeClass() && x.sameCertifiedPayload(y)
                && x.observationType()!=null && x.observationType().equals(y.observationType())
                && x.observationDomain()!=null && x.observationDomain().equals(y.observationDomain());
    }

    static int[] identity(int n) {
        int[] result=new int[n]; for(int i=0;i<n;i++) result[i]=i; return result;
    }

    static boolean isIdentity(Choice choice) {
        for(int i=0;i<choice.left().length;i++) if(choice.left()[i]!=i) return false;
        for(int i=0;i<choice.right().length;i++) if(choice.right()[i]!=i) return false;
        return true;
    }

    static boolean prefer(int candidate,int incumbent,boolean candidateCanonical,boolean incumbentCanonical) {
        return candidate<incumbent || candidate==incumbent && candidateCanonical && !incumbentCanonical;
    }

    static void requirePermutation(List<Binding> bindings,int[] permutation) {
        if(permutation.length!=bindings.size()) throw new IllegalArgumentException("Declaration orbit size");
        boolean[] seen=new boolean[permutation.length];
        for(int i=0;i<permutation.length;i++) {
            int j=permutation[i];
            if(j<0 || j>=permutation.length || seen[j]
                    || i!=j && !exchangeable(bindings.get(i),bindings.get(j)))
                throw new IllegalArgumentException("Uncertified declaration permutation");
            seen[j]=true;
        }
    }

    static void forEach(List<Binding> left,List<Binding> right,Map<Integer,Integer> original,
            Consumer<Choice> accept,Budget budget) {
        boolean movable=false;
        for(var e:original.entrySet()) {
            for(int i=0;i<left.size();i++) movable |= i!=e.getKey() && exchangeable(left.get(e.getKey()),left.get(i));
            for(int j=0;j<right.size();j++) movable |= j!=e.getValue() && exchangeable(right.get(e.getValue()),right.get(j));
        }
        if(!movable) { accept.accept(new Choice(original,identity(left.size()),identity(right.size()))); return; }
        if(left.size()>MAX_BINDINGS || right.size()>MAX_BINDINGS)
            throw new IllegalStateException("DECLARATION_ORBIT_SIZE_BUDGET");
        var entries=new ArrayList<>(original.entrySet()); entries.sort(Map.Entry.comparingByKey());
        int[] a=new int[left.size()],b=new int[right.size()]; Arrays.fill(a,-1); Arrays.fill(b,-1);
        walk(left,right,entries,0,a,b,new boolean[a.length],new boolean[b.length],accept,budget);
    }

    private static void walk(List<Binding> left,List<Binding> right,List<Map.Entry<Integer,Integer>> entries,
            int position,int[] a,int[] b,boolean[] usedA,boolean[] usedB,Consumer<Choice> accept,Budget budget) {
        budget.spend();
        if(position==entries.size()) {
            int[] ap=complete(left,a,usedA),bp=complete(right,b,usedB);
            var links=new LinkedHashMap<Integer,Integer>();
            for(var e:entries) links.put(ap[e.getKey()],bp[e.getValue()]);
            accept.accept(new Choice(Map.copyOf(links),ap,bp)); return;
        }
        int x=entries.get(position).getKey(),y=entries.get(position).getValue();
        // Preserve existing attaining witnesses on ties; orbit alternatives only
        // replace the stable representative when they strictly improve its cost.
        for(int ai=0;ai<a.length;ai++) {
            int i=ai==0?x:ai<=x?ai-1:ai;
            if(usedA[i] || i!=x && !exchangeable(left.get(x),left.get(i))) continue;
            for(int bj=0;bj<b.length;bj++) {
                int j=bj==0?y:bj<=y?bj-1:bj;
                if(usedB[j] || j!=y && !exchangeable(right.get(y),right.get(j))) continue;
                a[x]=i; b[y]=j; usedA[i]=true; usedB[j]=true;
                walk(left,right,entries,position+1,a,b,usedA,usedB,accept,budget);
                usedA[i]=false; usedB[j]=false; a[x]=-1; b[y]=-1;
            }
        }
    }

    private static int[] complete(List<Binding> bindings,int[] partial,boolean[] occupied) {
        int[] result=partial.clone(); boolean[] used=occupied.clone();
        for(int i=0;i<result.length;i++) if(result[i]<0 && !used[i]) { result[i]=i; used[i]=true; }
        for(int i=0;i<result.length;i++) if(result[i]<0) {
            for(int j=0;j<result.length;j++) if(!used[j] && exchangeable(bindings.get(i),bindings.get(j))) {
                result[i]=j; used[j]=true; break;
            }
            if(result[i]<0) throw new IllegalStateException("Incomplete certified declaration orbit");
        }
        requirePermutation(bindings,result); return result;
    }
}

package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.CanDis.theory.*;

/** Closure of source-authenticated homogeneous-prefix transpositions. */
final class FastGroupedDeclarationOrbit {
    static final int MAX_BINDINGS=6;
    static final class Budget {
        private final long limit;
        private long used;
        Budget(){this(100_000);}
        Budget(long limit){if(limit<=0)throw new IllegalArgumentException("Positive orbit budget required");this.limit=limit;}
        void spend(){is.fivefivefive.CanDis.WorkBudget.charge(1);if(used>=limit)throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("FAST_GROUPED_ORBIT_BUDGET");used++;}
    }
    private FastGroupedDeclarationOrbit() { }
    static int[] identity(int n){return java.util.stream.IntStream.range(0,n).toArray();}
    static boolean isIdentity(int[] p){for(int i=0;i<p.length;i++)if(p[i]!=i)return false;return true;}

    // Prefer transport supported by paid endpoints over an unrelated exchange.
    static boolean paidSupported(List<QuantiVar> order,Collection<String> paid,int[] p) {
        for(int i=0;i<p.length;i++)if(p[i]!=i
                && !paid.contains(order.get(i).getName())
                && !paid.contains(order.get(p[i]).getName()))return false;
        return true;
    }

    static List<int[]> orders(NormalForm source,NormalForm comparison,List<QuantiVar> order) {
        var result=new ArrayList<int[]>();result.add(identity(order.size()));
        if(order.size()>MAX_BINDINGS
                || !FastPaidDeclarationAlignment.supports(List.of(source))
                || !FastPaidDeclarationAlignment.supports(List.of(comparison))) return List.copyOf(result);
        require(source.getCertificationMatrixEGraph()==comparison.getCertificationMatrixEGraph(),"Foreign grouped source ledger");
        var original=source.getMatrixQuantiVars();var current=comparison.getMatrixQuantiVars();
        require(original.size()==current.size()&&order.size()==original.size(),"Grouped occurrence coverage");
        var indices=new IdentityHashMap<QuantiVar,Integer>();
        for(int i=0;i<original.size();i++) {
            require(original.get(i)==current.get(i),"Foreign grouped comparison binding");
            require(indices.put(original.get(i),i)==null,"Repeated grouped source binding");
        }
        Set<QuantiVar> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(var q:order)require(indices.containsKey(q)&&seen.add(q),"Foreign grouped order binding");
        var candidates=new ArrayList<int[]>();
        for(int i=0;i<order.size();i++)for(int j=i+1;j<order.size();j++)
            if(sameBlock(order,i,j)&&samePayload(order.get(i),order.get(j))) {
                var p=identity(order.size());p[i]=j;p[j]=i;candidates.add(p);
            }
        if(candidates.isEmpty())return List.copyOf(result);
        var evidence=FastPrefixSource.checkedPlan(source,comparison);
        // supports() checks retained local owners against the original source.
        // This certified outer permutation transports references, not local slots.
        var descriptor=evidence.descriptor();
        for(var proof:descriptor.automorphisms().generatorCertificates())CertificateVerifier.verify(proof);
        var coordinates=evidence.sourceToCoordinate();var slots=new ArrayList<TypedSlot>();
        var payloads=new ArrayList<BinderCoordinateDescriptor>();
        for(var q:order) {
            var coordinate=descriptor.coordinates().get(coordinates.get(indices.get(q)));
            require(coordinate.quantifier().equals(q.getQuantifier().name())
                    &&coordinate.multiplicity().equals(q.getCardinality().name()),"Grouped source payload");
            if(!coordinate.dependencies().isEmpty())return List.copyOf(result);
            slots.add(coordinate.canonicalSlot());
            payloads.add(coordinate);
        }
        for(var p:candidates) {
            // Separate declaration paths are only a candidate. Actual source
            // domain/type/exchange-class evidence and the full certificate below
            // authorize the move; equal carrier text does not.
            boolean compatible=true;
            for(int i=0;i<p.length;i++)if(i!=p[i]) {
                var x=payloads.get(i);var y=payloads.get(p[i]);
                compatible &= x.type().equals(y.type())&&x.domain().equals(y.domain())
                        &&x.exchangeClass()==y.exchangeClass()&&x.quantifier().equals(y.quantifier())
                        &&x.multiplicity().equals(y.multiplicity())&&x.disjointnessClass()==y.disjointnessClass();
            }
            if(!compatible)continue;
            var mapping=new LinkedHashMap<TypedSlot,TypedSlot>();
            for(var slot:descriptor.boundContext())mapping.put(slot,slot);
            for(int i=0;i<p.length;i++)mapping.put(slots.get(i),slots.get(p[i]));
            var permutation=TypedPermutation.of(descriptor.boundContext(),mapping);
            if(!descriptor.hasCertifiedAutomorphisms()||!descriptor.automorphisms().contains(permutation))continue;
            CertificateVerifier.verify(descriptor.automorphisms().derivationFor(descriptor,permutation));
            result.add(p);
        }
        return closure(result);
    }

    // Composition preserves the checked owner, type, domain and exchange class.
    // Keep generators first so existing equal-cost witness order stays stable.
    private static List<int[]> closure(List<int[]> generators) {
        var result = new ArrayList<>(generators);
        var seen = new HashSet<List<Integer>>();
        for (var order : generators) seen.add(Arrays.stream(order).boxed().toList());
        for (int index = 0; index < result.size(); index++) for (var generator : generators) {
            var order = result.get(index);
            var composed = new int[order.length];
            for (int i = 0; i < order.length; i++) composed[i] = generator[order[i]];
            if (seen.add(Arrays.stream(composed).boxed().toList())) result.add(composed);
        }
        return List.copyOf(result);
    }

    static void requireOrder(NormalForm source,NormalForm comparison,List<QuantiVar> order,int[] p) {
        require(orders(source,comparison,order).stream().anyMatch(q->Arrays.equals(p,q)),"Unadmitted grouped permutation");
    }
    static List<int[]> quotientSources(List<QuantiVar> a,List<QuantiVar> b,
            Map<String,String> links,Map<String,String> paid,List<int[]> left,List<int[]> right) {
        var ai=new HashMap<String,Integer>();var bi=new HashMap<String,Integer>();
        for(int i=0;i<a.size();i++)ai.put(a.get(i).getName(),i);
        for(int i=0;i<b.size();i++)bi.put(b.get(i).getName(),i);
        var pairs=new HashMap<Integer,Integer>();var charged=new HashSet<Integer>();
        for(var e:links.entrySet())pairs.put(ai.get(e.getKey()),bi.get(e.getValue()));
        for(var name:paid.keySet())charged.add(ai.get(name));
        boolean[] fixed=is.fivefivefive.CanDis.metric.DeclarationOrbitQuotient.fixedSources(
                a.size(),b.size(),pairs,charged,(i,j)->hasSwap(left,i,j),(i,j)->hasSwap(right,i,j));
        return left.stream().filter(p->{for(int i=0;i<p.length;i++)if(fixed[i]&&p[i]!=i)return false;return true;}).toList();
    }
    private static boolean hasSwap(List<int[]> orders,int a,int b) {
        for(var p:orders) {
            boolean same=true;
            for(int i=0;i<p.length;i++)same &= p[i]==(i==a?b:i==b?a:i);
            if(same)return true;
        }
        return false;
    }
    static Map<String,String> transport(List<QuantiVar> a,List<QuantiVar> b,Map<String,String> paid,int[] p,int[] q) {
        var left=names(a,p);var right=names(b,q);var result=new LinkedHashMap<String,String>();
        for(var e:paid.entrySet()) {
            String x=left.get(e.getKey()),y=right.get(e.getValue());
            require(x!=null&&y!=null&&!result.containsKey(x)&&!result.containsValue(y),"Grouped paid link coverage/injectivity");
            result.put(x,y);
        }
        return Map.copyOf(result);
    }
    private static Map<String,String> names(List<QuantiVar> qs,int[] p) {
        require(p.length==qs.size(),"Grouped permutation size");var result=new HashMap<String,String>();var seen=new HashSet<Integer>();
        for(int i=0;i<p.length;i++) {
            require(p[i]>=0&&p[i]<p.length&&seen.add(p[i]),"Grouped permutation bijection");
            require(result.put(qs.get(i).getName(),qs.get(p[i]).getName())==null,"Duplicate grouped name");
        }
        return result;
    }
    private static boolean samePayload(QuantiVar a,QuantiVar b) {
        return a.getQuantifier()==b.getQuantifier()&&Objects.equals(a.getTypeName(),b.getTypeName())
                &&a.getCardinality()==b.getCardinality()&&a.getDisjointnessClass()==b.getDisjointnessClass()
                &&(a.getDisjointnessClass()==0||a.getBindingPath().equals(b.getBindingPath()));
    }
    private static boolean sameBlock(List<QuantiVar> order,int i,int j) {
        var kind=order.get(i).getQuantifier();
        if(kind!=QuantiVar.Quantifier.ALL&&kind!=QuantiVar.Quantifier.SOME)return false;
        for(int k=i+1;k<=j;k++)if(order.get(k).getQuantifier()!=kind)return false;
        return true;
    }
    private static void require(boolean b,String why){if(!b)throw new IllegalArgumentException(why);}
}

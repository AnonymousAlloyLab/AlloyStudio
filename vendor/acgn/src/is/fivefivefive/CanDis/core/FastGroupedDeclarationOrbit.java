package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.CanDis.metric.RepairProjection;
import is.fivefivefive.CanDis.theory.*;

/** Identity plus one source-certified homogeneous-prefix transposition per endpoint.
 * This bounded candidate slice is not enumeration of the full source orbit. */
final class FastGroupedDeclarationOrbit {
    static final int MAX_BINDINGS=6;
    static final class Budget {
        private final long limit;
        private long used;
        Budget(){this(100_000);}
        Budget(long limit){if(limit<=0)throw new IllegalArgumentException("Positive orbit budget required");this.limit=limit;}
        void spend(){is.fivefivefive.CanDis.WorkBudget.charge(1);if(used>=limit)throw new IllegalStateException("FAST_GROUPED_ORBIT_BUDGET");used++;}
    }
    private FastGroupedDeclarationOrbit() { }
    static int[] identity(int n){return java.util.stream.IntStream.range(0,n).toArray();}
    static boolean isIdentity(int[] p){for(int i=0;i<p.length;i++)if(p[i]!=i)return false;return true;}

    static List<int[]> orders(NormalForm source,NormalForm comparison,List<QuantiVar> order) {
        var result=new ArrayList<int[]>();result.add(identity(order.size()));
        if(order.size()>MAX_BINDINGS
                // Paid disjoint paths do not extend this permutation slice.
                || source.getMatrixQuantiVars().stream().anyMatch(q->q.getDisjointnessClass()!=0)
                || comparison.getMatrixQuantiVars().stream().anyMatch(q->q.getDisjointnessClass()!=0)
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
        var evidence=TheoryAlloyAdapter.adapt(List.of(source),source.getCertificationMatrixEGraph().getSemanticProfile());
        evidence.requireRepairProjectionSources(List.of(source));
        RepairProjection.project(evidence,List.of(source));
        // supports() checks retained local owners against the original source.
        // This certified outer permutation transports references, not local slots.
        var descriptor=evidence.phaseBinderDescriptors().get(0);
        for(var proof:descriptor.automorphisms().generatorCertificates())CertificateVerifier.verify(proof);
        for(var proof:evidence.semanticArtifact().binderOccurrenceCertificates())CertificateVerifier.verify(proof);
        var coordinates=evidence.phaseSourceCoordinates().get(0);var slots=new ArrayList<TypedSlot>();
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
        return List.copyOf(result);
    }

    static void requireOrder(NormalForm source,NormalForm comparison,List<QuantiVar> order,int[] p) {
        require(orders(source,comparison,order).stream().anyMatch(q->Arrays.equals(p,q)),"Unadmitted grouped permutation");
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
                &&a.getCardinality()==b.getCardinality()&&a.getDisjointnessClass()==b.getDisjointnessClass();
    }
    private static boolean sameBlock(List<QuantiVar> order,int i,int j) {
        var kind=order.get(i).getQuantifier();
        if(kind!=QuantiVar.Quantifier.ALL&&kind!=QuantiVar.Quantifier.SOME)return false;
        for(int k=i+1;k<=j;k++)if(order.get(k).getQuantifier()!=kind)return false;
        return true;
    }
    private static void require(boolean b,String why){if(!b)throw new IllegalArgumentException(why);}
}

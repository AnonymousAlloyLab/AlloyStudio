package is.fivefivefive.CanDis.core;

import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.theory.*;
import is.fivefivefive.CanDis.metric.RepairProjection;

/** Replay-only, single missing paid diagonal through retained existential owners.
 * No metric search, body rewrite, local exchange, or inference from equal scalars.
 * Source permutations use the actual adapter certificates, not equal tuple text. */
final class FastRetainedDeclarationCompletion {
    // Full source-group derivations are retained, so cap this extra replay slice
    // at 6! elements independently of the scalar candidate-search limits.
    private static final int MAX_BINDINGS=6;
    record Plan(List<QuantiVar> left,List<QuantiVar> right,Map<String,String> completed,
                int[] sourceOrder,int[] targetOrder) { }

    static List<Plan> plans(NormalForm sourceA,NormalForm sourceB,NormalForm comparisonA,NormalForm comparisonB,
            List<QuantiVar> a,List<QuantiVar> b,Map<String,String> selected,int expected) {
        if(expected!=1 || a.size()!=b.size() || a.isEmpty() || a.size()>MAX_BINDINGS || selected.size()!=a.size()-1
                || sourceA.getLocalQuantiVars().isEmpty()==sourceB.getLocalQuantiVars().isEmpty()
                || !FastNestedPaidScope.retainedExistentials(sourceA)
                || !FastNestedPaidScope.retainedExistentials(sourceB)
                || !FastNestedPaidScope.retainedExistentials(comparisonA)
                || !FastNestedPaidScope.retainedExistentials(comparisonB)) return List.of();
        requireSameBindings(sourceA,comparisonA); requireSameBindings(sourceB,comparisonB);
        var completed=complete(a,b,selected);
        var authorityA=new Authority(sourceA,a); var authorityB=new Authority(sourceB,b);
        int[] ai=identity(a.size()),bi=identity(b.size());
        // Only two deterministic attempts: align the source to target order,
        // then the target to source order. No permutation enumeration.
        int[] sourceOrder=new int[a.size()],targetOrder=new int[b.size()];
        for(int i=0;i<a.size();i++) {
            String target=completed.get(a.get(i).getName());
            int j=0; while(j<b.size() && !b.get(j).getName().equals(target)) j++;
            require(j<b.size(),"Completion target coverage"); sourceOrder[j]=i; targetOrder[i]=j;
        }
        var result=new ArrayList<Plan>();
        if(authorityA.admits(sourceOrder) && authorityB.admits(bi))
            result.add(new Plan(permute(a,sourceOrder),b,completed,sourceOrder,bi));
        if(!Arrays.equals(sourceOrder,ai) && authorityA.admits(ai) && authorityB.admits(targetOrder))
            result.add(new Plan(a,permute(b,targetOrder),completed,ai,targetOrder));
        return List.copyOf(result);
    }

    static Map<String,String> complete(List<QuantiVar> a,List<QuantiVar> b,Map<String,String> selected) {
        var left=new HashSet<String>(); var right=new HashSet<String>();
        for(var q:a) require(left.add(q.getName()),"Duplicate outer source identity");
        for(var q:b) require(right.add(q.getName()),"Duplicate outer target identity");
        require(selected.size()==a.size()-1 && a.size()==b.size()
                && left.containsAll(selected.keySet()) && right.containsAll(selected.values())
                && new HashSet<>(selected.values()).size()==selected.size(),"Partial outer correspondence");
        left.removeAll(selected.keySet()); right.removeAll(selected.values());
        require(left.size()==1 && right.size()==1,"Exactly one missing outer pair required");
        var result=new TreeMap<>(selected); result.put(left.iterator().next(),right.iterator().next());
        return Map.copyOf(result);
    }

    private static void requireSameBindings(NormalForm original,NormalForm comparison) {
        require(identical(original.getMatrixQuantiVars(),comparison.getMatrixQuantiVars())
                && identical(original.getLocalQuantiVars(),comparison.getLocalQuantiVars())
                && original.getCertificationMatrixEGraph()==comparison.getCertificationMatrixEGraph(),
                "Foreign source/comparison owner");
    }

    private static boolean identical(List<QuantiVar> a,List<QuantiVar> b) {
        if(a.size()!=b.size()) return false;
        for(int i=0;i<a.size();i++) if(a.get(i)!=b.get(i)) return false;
        return true;
    }

    /** Built only from the original frozen endpoint. Full permutation membership
     * is checked, including all unmoved slots and the source occurrence ledger. */
    static final class Authority {
        private final BinderBlockDescriptor descriptor;
        private final List<TypedSlot> ordered;
        Authority(NormalForm source,List<QuantiVar> order) {
            require(order.size()<=MAX_BINDINGS && source.getMatrixQuantiVars().size()<=MAX_BINDINGS
                    && FastNestedPaidScope.retainedExistentials(source),"Retained owner boundary/budget");
            var evidence=TheoryAlloyAdapter.adapt(List.of(source),source.getCertificationMatrixEGraph().getSemanticProfile());
            evidence.requireRepairProjectionSources(List.of(source));
            RepairProjection.project(evidence,List.of(source));
            descriptor=evidence.phaseBinderDescriptors().get(0);
            for(var proof:descriptor.automorphisms().generatorCertificates()) CertificateVerifier.verify(proof);
            for(var proof:evidence.semanticArtifact().binderOccurrenceCertificates()) CertificateVerifier.verify(proof);
            var live=FastNestedPaidScope.retainedExistentialOwners(source); var certified=new HashSet<Long>();
            for(var entry:evidence.localBinderDescriptors().entrySet()) {
                require(entry.getKey().getOpcode()==EGraphNode.Opcode.EXISTS
                        && entry.getValue().coordinates().size()==1,"Retained unary existential certificate");
                require(certified.add(entry.getKey().getSourceOccurrenceLineage()),"Repeated certified owner lineage");
            }
            require(certified.equals(live),"Live original owner certificate coverage");
            var coordinates=evidence.phaseSourceCoordinates().get(0); var qs=source.getMatrixQuantiVars();
            var indices=new IdentityHashMap<QuantiVar,Integer>();
            for(int i=0;i<qs.size();i++) require(indices.put(qs.get(i),i)==null,"Duplicate source declaration occurrence");
            Set<QuantiVar> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            require(order.size()==qs.size(),"Outer order coverage");
            var slots=new ArrayList<TypedSlot>();
            for(var q:order) {
                Integer index=indices.get(q); require(index!=null && seen.add(q),"Foreign outer declaration");
                var c=descriptor.coordinates().get(coordinates.get(index));
                require(c.quantifier().equals(q.getQuantifier().name()) && c.multiplicity().equals(q.getCardinality().name())
                        && c.dependencies().isEmpty(),"Original outer declaration payload");
                slots.add(c.canonicalSlot());
            }
            ordered=List.copyOf(slots);
        }
        boolean admits(int[] permutation) {
            if(permutation.length!=ordered.size()) return false;
            var seen=new HashSet<Integer>(); var mapping=new LinkedHashMap<TypedSlot,TypedSlot>();
            for(var slot:descriptor.boundContext()) mapping.put(slot,slot);
            for(int i=0;i<permutation.length;i++) {
                int j=permutation[i]; if(j<0 || j>=permutation.length || !seen.add(j)) return false;
                mapping.put(ordered.get(i),ordered.get(j));
            }
            try {
                var permutationObject=TypedPermutation.of(descriptor.boundContext(),mapping);
                if(!descriptor.hasCertifiedAutomorphisms() || !descriptor.automorphisms().contains(permutationObject)) return false;
                CertificateVerifier.verify(descriptor.automorphisms().derivationFor(descriptor,permutationObject));
                return true;
            } catch(IllegalArgumentException invalid) { return false; }
        }
    }
    static JSONObject evidence(Plan plan,Map<String,String> selected) {
        return new JSONObject().put("selectedMapping",new JSONObject(new TreeMap<>(selected)))
                .put("completedMapping",new JSONObject(new TreeMap<>(plan.completed())))
                .put("scope","SINGLE_PAID_OUTER_LINK_RETAINED_EXISTS")
                .put("sourceCertificateChecked",true).put("complete",false);
    }
    private static int[] identity(int n) { return java.util.stream.IntStream.range(0,n).toArray(); }
    private static <T> List<T> permute(List<T> list,int[] order) {
        return is.fivefivefive.CanDis.metric.DeclarationMatrixLink.permute(list,order);
    }
    private static void require(boolean condition,String reason) {
        if(!condition) throw new IllegalArgumentException(reason);
    }
}

package is.fivefivefive.CanDis.theory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Immutable finite typed subgroup with certificates for every generator. */
public final class TypedSymmetryGroup {
    private final TypedSlotContext context;
    private final List<TypedPermutation> generators;
    private final List<SymmetryCertificate> generatorCertificates;
    private volatile StructuralKey structuralKey;
    // Positive memo only; the group and interface values are immutable.
    private final Set<TypedEClassInterface> certifiedInterfaces = new java.util.HashSet<>();

    private TypedSymmetryGroup(
            TypedSlotContext context,
            List<? extends TypedPermutation> sourceGenerators,
            List<? extends SymmetryCertificate> certificates) {
        this.context = Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sourceGenerators, "generators");
        TypedPermutation identity = TypedPermutation.identity(context);
        Map<StructuralKey, TypedPermutation> normalizedGenerators = new TreeMap<>();
        for (TypedPermutation generator : sourceGenerators) {
            TypedPermutation permutation = Objects.requireNonNull(generator, "generator");
            requireContext(permutation);
            if (!identity.equals(permutation)) {
                putUnique(normalizedGenerators, permutation);
            }
        }
        this.generators = Collections.unmodifiableList(
                new ArrayList<>(normalizedGenerators.values()));

        Objects.requireNonNull(certificates, "certificates");
        Map<StructuralKey, SymmetryCertificate> certifiedByGenerator = new TreeMap<>();
        for (SymmetryCertificate certificate : certificates) {
            SymmetryCertificate checked = Objects.requireNonNull(
                    certificate, "symmetry certificate");
            CertificateVerifier.verifySymmetry(checked);
            if (!context.equals(checked.eclass().exposedSlots())) {
                throw new IllegalArgumentException(
                        "Symmetry certificate acts on a different context");
            }
            StructuralKey key = TheoryKeys.embedding(checked.inducedPermutation());
            SymmetryCertificate prior = certifiedByGenerator.putIfAbsent(key, checked);
            if (prior != null && !prior.equals(checked)) {
                throw new IllegalArgumentException(
                        "One symmetry generator cannot carry two certificates");
            }
        }
        List<SymmetryCertificate> orderedCertificates = new ArrayList<>();
        for (TypedPermutation generator : generators) {
            SymmetryCertificate certificate = certifiedByGenerator.get(
                    TheoryKeys.embedding(generator));
            if (certificate != null) {
                orderedCertificates.add(certificate);
            }
        }
        if (orderedCertificates.size() != certifiedByGenerator.size()) {
            throw new IllegalArgumentException(
                    "A symmetry certificate does not name a retained nonidentity generator");
        }
        this.generatorCertificates = Collections.unmodifiableList(orderedCertificates);
    }

    public static TypedSymmetryGroup identity(TypedSlotContext context) {
        return new TypedSymmetryGroup(
                context, Collections.emptyList(), Collections.emptyList());
    }

    public static TypedSymmetryGroup certified(
            TypedEClassInterface eclass,
            List<? extends SymmetryCertificate> certificates) {
        Objects.requireNonNull(eclass, "eclass");
        Objects.requireNonNull(certificates, "certificates");
        List<TypedPermutation> generators = new ArrayList<>(certificates.size());
        for (SymmetryCertificate certificate : certificates) {
            SymmetryCertificate checked = Objects.requireNonNull(
                    certificate, "symmetry certificate");
            CertificateVerifier.verifySymmetry(checked);
            if (!eclass.equals(checked.eclass())) {
                throw new IllegalArgumentException(
                        "Symmetry certificate names a different e-class");
            }
            generators.add(checked.inducedPermutation());
        }
        return new TypedSymmetryGroup(eclass.exposedSlots(), generators, certificates);
    }

    /* Structural-only fixture constructor retained for the completed Phase D/E gates. */
    static TypedSymmetryGroup generatedForPhaseD(
            TypedSlotContext context,
            List<? extends TypedPermutation> generators) {
        return new TypedSymmetryGroup(context, generators, Collections.emptyList());
    }

    TypedSymmetryGroup withCertifiedGenerator(
            TypedEClassInterface eclass,
            SymmetryCertificate certificate) {
        Objects.requireNonNull(eclass, "eclass");
        Objects.requireNonNull(certificate, "certificate");
        requireCertifiedFor(eclass);
        if (!eclass.equals(certificate.eclass())) {
            throw new IllegalArgumentException(
                    "Symmetry certificate names a different e-class");
        }
        if (contains(certificate.inducedPermutation())) {
            return this;
        }
        List<SymmetryCertificate> certificates = new ArrayList<>(generatorCertificates);
        certificates.add(certificate);
        return certified(eclass, certificates);
    }

    synchronized void requireCertifiedFor(TypedEClassInterface eclass) {
        Objects.requireNonNull(eclass, "eclass");
        if (certifiedInterfaces.contains(eclass)) {
            return;
        }
        if (!context.equals(eclass.exposedSlots())
                || generatorCertificates.size() != generators.size()) {
            throw new IllegalStateException(
                    "Every nonidentity e-class symmetry generator requires a certificate");
        }
        for (SymmetryCertificate certificate : generatorCertificates) {
            CertificateVerifier.verifySymmetry(certificate);
            if (!eclass.equals(certificate.eclass())) {
                throw new IllegalStateException(
                        "Symmetry certificate belongs to a different e-class");
            }
        }
        certifiedInterfaces.add(eclass);
    }

    /** Reconstructs the generator/inverse/composition proof for one group element. */
    public TypedEqualityCertificate derivationFor(
            TypedEClassInterface eclass,
            TypedPermutation permutation) {
        requireCertifiedForWithoutClosure(eclass);
        TypedEqualityCertificate result = deriveOne(
                eclass, Objects.requireNonNull(permutation, "permutation"));
        if (result == null) {
            throw new IllegalArgumentException(
                    "Permutation is outside this symmetry group");
        }
        CertificateVerifier.verify(result);
        return result;
    }

    private void requireCertifiedForWithoutClosure(TypedEClassInterface eclass) {
        Objects.requireNonNull(eclass, "eclass");
        if (!context.equals(eclass.exposedSlots())
                || generatorCertificates.size() != generators.size()) {
            throw new IllegalStateException(
                    "Every nonidentity e-class symmetry generator requires a certificate");
        }
        for (SymmetryCertificate certificate : generatorCertificates) {
            CertificateVerifier.verifySymmetry(certificate);
            if (!eclass.equals(certificate.eclass())) {
                throw new IllegalStateException(
                        "Symmetry certificate belongs to a different e-class");
            }
        }
    }

    private TypedEqualityCertificate deriveOne(
            TypedEClassInterface eclass,
            TypedPermutation target) {
        if (!context.equals(target.source()) || !context.equals(target.codomain())) {
            return null;
        }
        TypedPermutation identity = TypedPermutation.identity(context);
        Map<StructuralKey, TypedEqualityCertificate> derivations = new TreeMap<>();
        Deque<TypedPermutation> pending = new ArrayDeque<>();
        TypedEqualityCertificate identityProof = EqualityCertificates.reflexive(
                TypedCertificateEndpoint.eclassWitness(eclass));
        StructuralKey identityKey = TheoryKeys.embedding(identity);
        derivations.put(identityKey, identityProof);
        pending.add(identity);
        StructuralKey targetKey = TheoryKeys.embedding(target);

        List<CertifiedStep> steps = new ArrayList<>(generators.size() * 2);
        for (int index = 0; index < generators.size(); index++) {
            TypedPermutation generator = generators.get(index);
            TypedEqualityCertificate proof = generatorCertificates.get(index);
            steps.add(new CertifiedStep(generator, proof));
            TypedPermutation inverse = generator.inverse();
            TypedEqualityCertificate inverseProof = EqualityCertificates.symmetric(
                    EqualityCertificates.rename(proof, inverse));
            steps.add(new CertifiedStep(inverse, inverseProof));
        }

        long visited = 0;
        while (!pending.isEmpty() && !derivations.containsKey(targetKey)) {
            TypedPermutation current = pending.removeFirst();
            visited = Math.addExact(visited, 1L);
            if (visited > FinitePermutationTraversal.maximumElements()) {
                throw new CanonicalizationDomainException(
                        "Symmetry proof search exceeds configured group bound");
            }
            TypedEqualityCertificate currentProof = derivations.get(
                    TheoryKeys.embedding(current));
            for (CertifiedStep step : steps) {
                TypedPermutation candidate = current.andThen(step.permutation);
                StructuralKey key = TheoryKeys.embedding(candidate);
                if (derivations.containsKey(key)) {
                    continue;
                }
                TypedEqualityCertificate transportedCurrent = EqualityCertificates.rename(
                        currentProof, step.permutation);
                TypedEqualityCertificate candidateProof = EqualityCertificates.transitive(
                        step.certificate, transportedCurrent);
                derivations.put(key, candidateProof);
                pending.addLast(candidate);
            }
        }
        TypedEqualityCertificate result = derivations.get(targetKey);
        if (result == null) {
            return null;
        }
        TypedCertificateEndpoint expectedRight = TypedCertificateEndpoint.invocation(
                new TypedInvocation(eclass, target));
        if (!result.leftEndpoint().equals(
                        TypedCertificateEndpoint.eclassWitness(eclass))
                || !result.rightEndpoint().equals(expectedRight)) {
            throw new IllegalStateException(
                    "Reconstructed symmetry derivation has incorrect endpoints");
        }
        return result;
    }

    private void requireContext(TypedPermutation permutation) {
        if (!context.equals(permutation.source())
                || !context.equals(permutation.codomain())) {
            throw new IllegalArgumentException(
                    "Every symmetry must be a permutation of the declared interface");
        }
    }

    private static void putUnique(
            Map<StructuralKey, TypedPermutation> target,
            TypedPermutation permutation) {
        StructuralKey key = TheoryKeys.embedding(permutation);
        TypedPermutation prior = target.putIfAbsent(key, permutation);
        if (prior != null && !prior.equals(permutation)) {
            throw new IllegalStateException(
                    "Structural key collision between unequal typed permutations");
        }
    }

    public TypedSlotContext context() {
        return context;
    }

    public List<TypedPermutation> generators() {
        return generators;
    }

    public List<SymmetryCertificate> generatorCertificates() {
        return generatorCertificates;
    }

    public boolean hasCertifiedGenerators() {
        return generatorCertificates.size() == generators.size();
    }

    public void forEachElement(Consumer<TypedPermutation> consumer) {
        FinitePermutationTraversal.forEach(
                context, generators, TheoryKeys::embedding, consumer::accept);
    }

    <E extends Exception> long forEachElementChecked(
            FinitePermutationTraversal.CheckedConsumer<E> consumer) throws E {
        return FinitePermutationTraversal.forEach(
                context, generators, TheoryKeys::embedding, consumer);
    }

    public boolean anyMatch(Predicate<TypedPermutation> predicate) {
        return FinitePermutationTraversal.anyMatch(
                context, generators, TheoryKeys::embedding, predicate);
    }

    /** Explicit bounded snapshot retained for diagnostics and tests only. */
    public List<TypedPermutation> elements() {
        List<TypedPermutation> snapshot = new ArrayList<>();
        forEachElement(snapshot::add);
        snapshot.sort(java.util.Comparator.comparing(TheoryKeys::embedding));
        return Collections.unmodifiableList(snapshot);
    }

    public boolean contains(TypedPermutation permutation) {
        Objects.requireNonNull(permutation, "permutation");
        if (!context.equals(permutation.source())
                || !context.equals(permutation.codomain())) {
            return false;
        }
        return anyMatch(permutation::equals);
    }

    public StructuralKey structuralKey() {
        StructuralKey cached = structuralKey;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = structuralKey;
            if (cached == null) {
                List<StructuralKey> children = new ArrayList<>();
                children.add(TheoryKeys.context(context));
                for (TypedPermutation generator : CanonicalPermutationPresentation.of(
                        context, generators, TheoryKeys::embedding)) {
                    children.add(StructuralKey.branch(
                            "symmetry-canonical-generator",
                            Collections.singletonList(
                                    TheoryKeys.embedding(generator))));
                }
                cached = StructuralKey.branch(
                        "typed-symmetry-canonical-presentation-v1", children);
                structuralKey = cached;
            }
            return cached;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedSymmetryGroup)) {
            return false;
        }
        TypedSymmetryGroup group = (TypedSymmetryGroup) other;
        return structuralKey().equals(group.structuralKey());
    }

    @Override
    public int hashCode() {
        return structuralKey().hashCode();
    }

    @Override
    public String toString() {
        return "G(" + context + ")=<" + generators + ">";
    }

    private static final class CertifiedStep {
        private final TypedPermutation permutation;
        private final TypedEqualityCertificate certificate;

        private CertifiedStep(
                TypedPermutation permutation,
                TypedEqualityCertificate certificate) {
            this.permutation = permutation;
            this.certificate = certificate;
        }
    }
}

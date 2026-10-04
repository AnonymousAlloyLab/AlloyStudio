package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable complete descriptor {@code beta} for one binder-block schema. */
public final class BinderBlockDescriptor {
    private final List<BinderCoordinateDescriptor> coordinates;
    private final TypedSlotContext boundContext;
    private final BinderAutomorphismGroup automorphisms;
    private final StructuralKey structuralKey;

    /** Structural Phase C/E constructor; strict Phase F use rejects raw generators. */
    public BinderBlockDescriptor(
            List<? extends BinderCoordinateDescriptor> coordinates,
            List<? extends TypedPermutation> automorphismGenerators) {
        this(coordinates, automorphismGenerators, Collections.emptyList());
    }

    public static BinderBlockDescriptor certified(
            List<? extends BinderCoordinateDescriptor> coordinates,
            List<? extends BinderAutomorphismCertificate> certificates) {
        Objects.requireNonNull(certificates, "certificates");
        List<TypedPermutation> generators = new ArrayList<>(certificates.size());
        for (BinderAutomorphismCertificate certificate : certificates) {
            generators.add(Objects.requireNonNull(
                    certificate, "binder automorphism certificate").permutation());
        }
        return new BinderBlockDescriptor(coordinates, generators, certificates);
    }

    private BinderBlockDescriptor(
            List<? extends BinderCoordinateDescriptor> sourceCoordinates,
            List<? extends TypedPermutation> automorphismGenerators,
            List<? extends BinderAutomorphismCertificate> certificates) {
        this.coordinates = normalizedCoordinatesForCertificate(sourceCoordinates);
        this.boundContext = contextForCoordinates(this.coordinates);
        Objects.requireNonNull(automorphismGenerators, "automorphismGenerators");
        for (TypedPermutation generator : automorphismGenerators) {
            requireDescriptorAutomorphism(
                    coordinates,
                    boundContext,
                    Objects.requireNonNull(generator, "generator"));
        }
        if (certificates.isEmpty()) {
            this.automorphisms = new BinderAutomorphismGroup(
                    boundContext, automorphismGenerators);
        } else {
            StructuralKey payloadKey = payloadKeyForCoordinates(coordinates);
            for (BinderAutomorphismCertificate certificate : certificates) {
                if (!payloadKey.equals(certificate.descriptorKey())) {
                    throw new IllegalArgumentException(
                            "Binder certificate names a different complete descriptor");
                }
            }
            this.automorphisms = BinderAutomorphismGroup.certified(
                    boundContext, certificates);
        }
        List<StructuralKey> children = new ArrayList<>(coordinates.size() + 1);
        for (BinderCoordinateDescriptor coordinate : coordinates) {
            children.add(coordinate.structuralKey());
        }
        children.add(automorphisms.structuralKey());
        this.structuralKey = StructuralKey.branch("binder-block-descriptor", children);
    }

    static List<BinderCoordinateDescriptor> normalizedCoordinatesForCertificate(
            List<? extends BinderCoordinateDescriptor> source) {
        Objects.requireNonNull(source, "coordinates");
        List<BinderCoordinateDescriptor> result = new ArrayList<>(source.size());
        Map<Integer, Integer> normalizedClasses = new LinkedHashMap<>();
        Map<Integer, Integer> normalizedExchangeClasses = new LinkedHashMap<>();
        int nextClass = 0;
        int nextExchangeClass = 0;
        for (BinderCoordinateDescriptor value : source) {
            BinderCoordinateDescriptor coordinate = Objects.requireNonNull(
                    value, "binder coordinate");
            int disjointnessClass = coordinate.disjointnessClass();
            if (disjointnessClass >= 0) {
                Integer normalized = normalizedClasses.get(disjointnessClass);
                if (normalized == null) {
                    normalized = nextClass++;
                    normalizedClasses.put(disjointnessClass, normalized);
                }
                coordinate = coordinate.withDisjointnessClass(normalized);
            }
            Integer normalizedExchange = normalizedExchangeClasses.get(
                    coordinate.exchangeClass());
            if (normalizedExchange == null) {
                normalizedExchange = nextExchangeClass++;
                normalizedExchangeClasses.put(
                        coordinate.exchangeClass(), normalizedExchange);
            }
            coordinate = coordinate.withExchangeClass(normalizedExchange);
            result.add(coordinate);
        }
        return Collections.unmodifiableList(result);
    }

    static TypedSlotContext contextForCoordinates(
            List<? extends BinderCoordinateDescriptor> coordinates) {
        Objects.requireNonNull(coordinates, "coordinates");
        List<TypedSlot> slots = new ArrayList<>(coordinates.size());
        Set<TypedSlot> preceding = new TreeSet<>();
        for (BinderCoordinateDescriptor value : coordinates) {
            BinderCoordinateDescriptor coordinate = Objects.requireNonNull(
                    value, "binder coordinate");
            TypedSlot slot = coordinate.canonicalSlot();
            if (!preceding.containsAll(coordinate.dependencies().slots())) {
                throw new IllegalArgumentException(
                        "Binder dependencies must refer only to preceding coordinates");
            }
            if (!preceding.add(slot)) {
                throw new IllegalArgumentException("Duplicate binder coordinate " + slot);
            }
            slots.add(slot);
        }
        TypedSlotContext context = TypedSlotContext.of(slots);
        if (!context.equals(context.canonicalBoundContext())) {
            throw new IllegalArgumentException(
                    "A descriptor must use the initial canonical bound slots of each type");
        }
        return context;
    }

    static StructuralKey payloadKeyForCoordinates(
            List<? extends BinderCoordinateDescriptor> coordinates) {
        List<StructuralKey> children = new ArrayList<>();
        children.add(TheoryKeys.context(contextForCoordinates(coordinates)));
        for (BinderCoordinateDescriptor coordinate : coordinates) {
            children.add(coordinate.structuralKey());
        }
        return StructuralKey.branch("binder-block-payload", children);
    }

    static void requireDescriptorAutomorphism(
            List<? extends BinderCoordinateDescriptor> coordinates,
            TypedSlotContext boundContext,
            TypedPermutation permutation) {
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(boundContext, "boundContext");
        Objects.requireNonNull(permutation, "permutation");
        if (!boundContext.equals(permutation.source())
                || !boundContext.equals(permutation.codomain())) {
            throw new IllegalArgumentException(
                    "Binder automorphism domain must equal the complete bound context");
        }
        Map<TypedSlot, BinderCoordinateDescriptor> indexed = new LinkedHashMap<>();
        for (BinderCoordinateDescriptor coordinate : coordinates) {
            indexed.put(coordinate.canonicalSlot(), coordinate);
        }
        for (BinderCoordinateDescriptor source : coordinates) {
            BinderCoordinateDescriptor target = indexed.get(
                    permutation.apply(source.canonicalSlot()));
            if (!source.hasSamePayload(target)
                    || !permutation.imageOf(source.dependencies()).equals(target.dependencies())) {
                throw new IllegalArgumentException(
                        "Binder automorphism does not preserve the complete descriptor");
            }
        }
    }

    public List<BinderCoordinateDescriptor> coordinates() {
        return coordinates;
    }

    public TypedSlotContext boundContext() {
        return boundContext;
    }

    public BinderAutomorphismGroup automorphisms() {
        return automorphisms;
    }

    public boolean hasCertifiedAutomorphisms() {
        return automorphisms.hasCertifiedGenerators();
    }

    public StructuralKey payloadKey() {
        return payloadKeyForCoordinates(coordinates);
    }

    public Set<String> typeVariables() {
        Set<String> variables = new TreeSet<>();
        for (BinderCoordinateDescriptor coordinate : coordinates) {
            variables.addAll(coordinate.typeVariables());
        }
        return Collections.unmodifiableSet(variables);
    }

    public BinderBlockDescriptor substitute(Map<String, GraphType> substitution) {
        Objects.requireNonNull(substitution, "substitution");
        Map<TypedSlot, TypedSlot> slotMap = new LinkedHashMap<>();
        Map<GraphType, Integer> nextOrdinal = new TreeMap<>();
        boolean changed = false;
        for (BinderCoordinateDescriptor coordinate : coordinates) {
            GraphType oldType = coordinate.type();
            GraphType newType = oldType.substitute(substitution);
            int ordinal = nextOrdinal.getOrDefault(newType, 0);
            nextOrdinal.put(newType, ordinal + 1);
            TypedSlot mapped = TypedSlot.canonicalBound(newType, ordinal);
            slotMap.put(coordinate.canonicalSlot(), mapped);
            changed |= !coordinate.canonicalSlot().equals(mapped);
        }
        if (!changed) {
            return this;
        }
        List<BinderCoordinateDescriptor> mappedCoordinates = new ArrayList<>(coordinates.size());
        for (BinderCoordinateDescriptor coordinate : coordinates) {
            mappedCoordinates.add(coordinate.remap(
                    slotMap, coordinate.disjointnessClass()));
        }
        TypedSlotContext mappedContext = TypedSlotContext.of(slotMap.values());
        List<TypedPermutation> mappedGenerators = new ArrayList<>(
                automorphisms.generators().size());
        for (TypedPermutation generator : automorphisms.generators()) {
            Map<TypedSlot, TypedSlot> mapped = new LinkedHashMap<>();
            for (TypedSlot slot : boundContext) {
                mapped.put(slotMap.get(slot), slotMap.get(generator.apply(slot)));
            }
            mappedGenerators.add(TypedPermutation.of(mappedContext, mapped));
        }
        if (!automorphisms.hasCertifiedGenerators()) {
            return new BinderBlockDescriptor(mappedCoordinates, mappedGenerators);
        }
        List<BinderAutomorphismCertificate> mappedCertificates = new ArrayList<>(
                automorphisms.generatorCertificates().size());
        for (int index = 0; index < mappedGenerators.size(); index++) {
            mappedCertificates.add(new BinderAutomorphismCertificate(
                    mappedCoordinates,
                    mappedGenerators.get(index),
                    automorphisms.generatorCertificates().get(index).origin()));
        }
        return BinderBlockDescriptor.certified(mappedCoordinates, mappedCertificates);
    }

    /** Chooses the fixed fresh alpha-variant of {@code Delta_beta}. */
    public TypedRenaming freshOccurrenceRenaming(TypedSlotContext occupied) {
        Objects.requireNonNull(occupied, "occupied");
        TypedSlotContext allocated = TypedSlotContext.empty();
        Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
        for (BinderCoordinateDescriptor coordinate : coordinates) {
            TypedSlot fresh = CanonicalSlotAlphabet.fresh(
                    coordinate.type(),
                    SlotAlphabet.CANONICAL_BOUND,
                    occupied.union(allocated));
            allocated = allocated.plus(fresh);
            mapping.put(coordinate.canonicalSlot(), fresh);
        }
        return TypedRenaming.of(boundContext, allocated, mapping);
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BinderBlockDescriptor)) {
            return false;
        }
        BinderBlockDescriptor descriptor = (BinderBlockDescriptor) other;
        return coordinates.equals(descriptor.coordinates)
                && boundContext.equals(descriptor.boundContext)
                && automorphisms.equals(descriptor.automorphisms);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coordinates, boundContext, automorphisms);
    }

    @Override
    public String toString() {
        return "beta" + coordinates;
    }
}

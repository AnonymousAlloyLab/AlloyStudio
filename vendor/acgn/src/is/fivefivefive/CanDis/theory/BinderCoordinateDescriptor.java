package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete structural metadata for one coordinate of a binder block. */
public final class BinderCoordinateDescriptor {
    public static final int NO_DISJOINTNESS_CLASS = -1;

    private final TypedSlot canonicalSlot;
    private final StructuralKey domain;
    private final String quantifier;
    private final String multiplicity;
    private final int disjointnessClass;
    private final int exchangeClass;
    private final TypedSlotContext dependencies;

    public BinderCoordinateDescriptor(
            TypedSlot canonicalSlot,
            StructuralKey domain,
            String quantifier,
            String multiplicity,
            int disjointnessClass,
            TypedSlotContext dependencies) {
        this(canonicalSlot, domain, quantifier, multiplicity,
                disjointnessClass, 0, dependencies);
    }

    public BinderCoordinateDescriptor(
            TypedSlot canonicalSlot,
            StructuralKey domain,
            String quantifier,
            String multiplicity,
            int disjointnessClass,
            int exchangeClass,
            TypedSlotContext dependencies) {
        this.canonicalSlot = Objects.requireNonNull(canonicalSlot, "canonicalSlot");
        this.domain = Objects.requireNonNull(domain, "domain");
        this.quantifier = requireText(quantifier, "quantifier");
        this.multiplicity = requireText(multiplicity, "multiplicity");
        if (disjointnessClass < NO_DISJOINTNESS_CLASS) {
            throw new IllegalArgumentException("Disjointness class must be -1 or non-negative");
        }
        this.disjointnessClass = disjointnessClass;
        if (exchangeClass < 0) {
            throw new IllegalArgumentException("Exchange class must be non-negative");
        }
        this.exchangeClass = exchangeClass;
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
        if (canonicalSlot.alphabet() != SlotAlphabet.CANONICAL_BOUND) {
            throw new IllegalArgumentException(
                    "A binder descriptor coordinate must use the canonical bound alphabet");
        }
        if (dependencies.contains(canonicalSlot)) {
            throw new IllegalArgumentException("A binder coordinate cannot depend on itself");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public TypedSlot canonicalSlot() {
        return canonicalSlot;
    }

    public GraphType type() {
        return canonicalSlot.type();
    }

    public StructuralKey domain() {
        return domain;
    }

    public String quantifier() {
        return quantifier;
    }

    public String multiplicity() {
        return multiplicity;
    }

    public int disjointnessClass() {
        return disjointnessClass;
    }

    public int exchangeClass() {
        return exchangeClass;
    }

    public TypedSlotContext dependencies() {
        return dependencies;
    }

    public Set<String> typeVariables() {
        return type().typeVariables();
    }

    BinderCoordinateDescriptor remap(
            Map<TypedSlot, TypedSlot> slotMap,
            int normalizedDisjointnessClass) {
        TypedSlot mappedSlot = requireMapped(slotMap, canonicalSlot);
        java.util.List<TypedSlot> mappedDependencies = new java.util.ArrayList<>();
        for (TypedSlot dependency : dependencies) {
            mappedDependencies.add(requireMapped(slotMap, dependency));
        }
        return new BinderCoordinateDescriptor(
                mappedSlot,
                domain,
                quantifier,
                multiplicity,
                normalizedDisjointnessClass,
                exchangeClass,
                TypedSlotContext.of(mappedDependencies));
    }

    BinderCoordinateDescriptor withDisjointnessClass(int normalizedClass) {
        if (normalizedClass == disjointnessClass) {
            return this;
        }
        return new BinderCoordinateDescriptor(
                canonicalSlot,
                domain,
                quantifier,
                multiplicity,
                normalizedClass,
                exchangeClass,
                dependencies);
    }

    BinderCoordinateDescriptor withExchangeClass(int normalizedClass) {
        if (normalizedClass == exchangeClass) {
            return this;
        }
        return new BinderCoordinateDescriptor(
                canonicalSlot,
                domain,
                quantifier,
                multiplicity,
                disjointnessClass,
                normalizedClass,
                dependencies);
    }

    private static TypedSlot requireMapped(
            Map<TypedSlot, TypedSlot> slotMap,
            TypedSlot source) {
        TypedSlot mapped = slotMap.get(source);
        if (mapped == null) {
            throw new IllegalArgumentException("Missing binder-coordinate map entry for " + source);
        }
        return mapped;
    }

    boolean hasSamePayload(BinderCoordinateDescriptor other) {
        return other != null
                && type().equals(other.type())
                && domain.equals(other.domain)
                && quantifier.equals(other.quantifier)
                && multiplicity.equals(other.multiplicity)
                && disjointnessClass == other.disjointnessClass
                && exchangeClass == other.exchangeClass;
    }

    public StructuralKey structuralKey() {
        return StructuralKey.of(
                "binder-coordinate",
                Arrays.asList(
                        quantifier,
                        multiplicity,
                        Integer.toString(disjointnessClass),
                        Integer.toString(exchangeClass)),
                Arrays.asList(
                        TheoryKeys.slot(canonicalSlot),
                        domain,
                        TheoryKeys.context(dependencies)));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BinderCoordinateDescriptor)) {
            return false;
        }
        BinderCoordinateDescriptor descriptor = (BinderCoordinateDescriptor) other;
        return canonicalSlot.equals(descriptor.canonicalSlot)
                && domain.equals(descriptor.domain)
                && quantifier.equals(descriptor.quantifier)
                && multiplicity.equals(descriptor.multiplicity)
                && disjointnessClass == descriptor.disjointnessClass
                && exchangeClass == descriptor.exchangeClass
                && dependencies.equals(descriptor.dependencies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                canonicalSlot,
                domain,
                quantifier,
                multiplicity,
                disjointnessClass,
                exchangeClass,
                dependencies);
    }

    @Override
    public String toString() {
        return quantifier + " " + canonicalSlot + ":" + domain
                + "[mult=" + multiplicity
                + ",disj=" + disjointnessClass
                + ",exchange=" + exchangeClass
                + ",deps=" + dependencies + "]";
    }
}

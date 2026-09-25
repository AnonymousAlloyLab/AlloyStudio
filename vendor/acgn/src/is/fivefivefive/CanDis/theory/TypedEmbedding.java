package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A total type-preserving injection between two declared finite contexts. */
public sealed class TypedEmbedding permits TypedRenaming {
    private final TypedSlotContext source;
    private final TypedSlotContext codomain;
    private final Map<TypedSlot, TypedSlot> mapping;

    protected TypedEmbedding(
            TypedSlotContext source,
            TypedSlotContext codomain,
            Map<TypedSlot, TypedSlot> mapping) {
        this.source = Objects.requireNonNull(source, "source");
        this.codomain = Objects.requireNonNull(codomain, "codomain");
        Objects.requireNonNull(mapping, "mapping");
        if (!mapping.keySet().equals(source.slots())) {
            throw new IllegalArgumentException(
                    "Embedding mapping keys must equal its declared source context");
        }
        Map<TypedSlot, TypedSlot> ordered = new LinkedHashMap<>();
        for (TypedSlot slot : source) {
            ordered.put(slot, mapping.get(slot));
        }
        if (!isInjective(source, ordered)) {
            throw new IllegalArgumentException("Embedding mapping must be total and injective");
        }
        if (!isTypePreserving(source, codomain, ordered)) {
            throw new IllegalArgumentException(
                    "Embedding targets must belong to the codomain and preserve slot types");
        }
        this.mapping = Collections.unmodifiableMap(ordered);
    }

    public static TypedEmbedding of(
            TypedSlotContext source,
            TypedSlotContext codomain,
            Map<TypedSlot, TypedSlot> mapping) {
        return new TypedEmbedding(source, codomain, mapping);
    }

    public static TypedRenaming identity(TypedSlotContext context) {
        return TypedRenaming.identity(context);
    }

    public static TypedEmbedding inclusion(
            TypedSlotContext subcontext,
            TypedSlotContext context) {
        Objects.requireNonNull(subcontext, "subcontext");
        Objects.requireNonNull(context, "context");
        if (!subcontext.isSubcontextOf(context)) {
            throw new IllegalArgumentException("Inclusion source must be a subcontext of its codomain");
        }
        Map<TypedSlot, TypedSlot> inclusion = new LinkedHashMap<>();
        for (TypedSlot slot : subcontext) {
            inclusion.put(slot, slot);
        }
        return new TypedEmbedding(subcontext, context, inclusion);
    }

    public TypedSlotContext source() {
        return source;
    }

    public TypedSlotContext codomain() {
        return codomain;
    }

    public Map<TypedSlot, TypedSlot> mapping() {
        return mapping;
    }

    public TypedSlot apply(TypedSlot slot) {
        Objects.requireNonNull(slot, "slot");
        if (!source.contains(slot)) {
            throw new IllegalArgumentException("Slot is outside the embedding source: " + slot);
        }
        return mapping.get(slot);
    }

    public TypedSlotContext image() {
        return TypedSlotContext.of(mapping.values());
    }

    public TypedSlotContext imageOf(TypedSlotContext subcontext) {
        Objects.requireNonNull(subcontext, "subcontext");
        if (!subcontext.isSubcontextOf(source)) {
            throw new IllegalArgumentException("Image argument must be a subcontext of the source");
        }
        List<TypedSlot> image = new ArrayList<>(subcontext.size());
        for (TypedSlot slot : subcontext) {
            image.add(mapping.get(slot));
        }
        return TypedSlotContext.of(image);
    }

    public TypedEmbedding disjointExtension(TypedSlot sourceSlot, TypedSlot targetSlot) {
        Objects.requireNonNull(sourceSlot, "sourceSlot");
        Objects.requireNonNull(targetSlot, "targetSlot");
        if (source.contains(sourceSlot)) {
            throw new IllegalArgumentException("Extended source slot must be fresh");
        }
        if (codomain.contains(targetSlot)) {
            throw new IllegalArgumentException("Extended target slot must be fresh");
        }
        if (!sourceSlot.type().equals(targetSlot.type())) {
            throw new IllegalArgumentException("Disjoint extension must preserve the bound-slot type");
        }
        Map<TypedSlot, TypedSlot> extended = new LinkedHashMap<>(mapping);
        extended.put(sourceSlot, targetSlot);
        return new TypedEmbedding(source.plus(sourceSlot), codomain.plus(targetSlot), extended);
    }

    public TypedEmbedding disjointUnion(TypedEmbedding other) {
        Objects.requireNonNull(other, "other");
        if (!source.isDisjoint(other.source) || !codomain.isDisjoint(other.codomain)) {
            throw new IllegalArgumentException(
                    "Embedding union requires disjoint source and codomain contexts");
        }
        Map<TypedSlot, TypedSlot> combined = new LinkedHashMap<>(mapping);
        combined.putAll(other.mapping);
        return new TypedEmbedding(
                source.union(other.source),
                codomain.union(other.codomain),
                combined);
    }

    /** Returns {@code after o this}. */
    public TypedEmbedding andThen(TypedEmbedding after) {
        Objects.requireNonNull(after, "after");
        if (!codomain.equals(after.source)) {
            throw new IllegalArgumentException(
                    "Embedding composition requires this codomain to equal the next source");
        }
        Map<TypedSlot, TypedSlot> composed = new LinkedHashMap<>();
        for (TypedSlot slot : source) {
            composed.put(slot, after.apply(apply(slot)));
        }
        return new TypedEmbedding(source, after.codomain, composed);
    }

    /** Returns {@code after o before}, matching the notation in Proposition 1. */
    public static TypedEmbedding compose(TypedEmbedding after, TypedEmbedding before) {
        Objects.requireNonNull(before, "before");
        return before.andThen(Objects.requireNonNull(after, "after"));
    }

    public boolean isTypePreserving() {
        return isTypePreserving(source, codomain, mapping);
    }

    public boolean isInjective() {
        return isInjective(source, mapping);
    }

    public boolean isOntoDeclaredCodomain() {
        return isOntoDeclaredCodomain(codomain, mapping);
    }

    public boolean isRenaming() {
        return isTypePreserving() && isInjective() && isOntoDeclaredCodomain();
    }

    public boolean isPermutation() {
        return source.equals(codomain) && isRenaming();
    }

    public TypedRenaming asRenaming() {
        if (!isRenaming()) {
            throw new IllegalStateException("Embedding is not onto its declared codomain");
        }
        return TypedRenaming.of(source, codomain, mapping);
    }

    public static boolean isTypePreserving(
            TypedSlotContext source,
            TypedSlotContext codomain,
            Map<TypedSlot, TypedSlot> mapping) {
        if (source == null || codomain == null || mapping == null
                || !mapping.keySet().equals(source.slots())) {
            return false;
        }
        for (TypedSlot slot : source) {
            TypedSlot target = mapping.get(slot);
            if (target == null || !codomain.contains(target)
                    || !slot.type().equals(target.type())) {
                return false;
            }
        }
        return true;
    }

    public static boolean isInjective(
            TypedSlotContext source,
            Map<TypedSlot, TypedSlot> mapping) {
        if (source == null || mapping == null || !mapping.keySet().equals(source.slots())) {
            return false;
        }
        Set<TypedSlot> seen = new HashSet<>();
        for (TypedSlot slot : source) {
            TypedSlot target = mapping.get(slot);
            if (target == null || !seen.add(target)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isOntoDeclaredCodomain(
            TypedSlotContext codomain,
            Map<TypedSlot, TypedSlot> mapping) {
        if (codomain == null || mapping == null || mapping.containsValue(null)) {
            return false;
        }
        return new HashSet<>(mapping.values()).equals(codomain.slots());
    }

    public static boolean isRenaming(
            TypedSlotContext source,
            TypedSlotContext codomain,
            Map<TypedSlot, TypedSlot> mapping) {
        return isTypePreserving(source, codomain, mapping)
                && isInjective(source, mapping)
                && isOntoDeclaredCodomain(codomain, mapping);
    }

    public static boolean isPermutation(
            TypedSlotContext context,
            Map<TypedSlot, TypedSlot> mapping) {
        return context != null && isRenaming(context, context, mapping);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedEmbedding)) {
            return false;
        }
        TypedEmbedding embedding = (TypedEmbedding) other;
        return source.equals(embedding.source)
                && codomain.equals(embedding.codomain)
                && mapping.equals(embedding.mapping);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, codomain, mapping);
    }

    @Override
    public String toString() {
        return source + " -> " + codomain + " " + mapping;
    }
}

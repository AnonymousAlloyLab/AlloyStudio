package is.fivefivefive.CanDis.theory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A typed embedding that is onto its explicitly declared codomain. */
public sealed class TypedRenaming extends TypedEmbedding permits TypedPermutation {
    protected TypedRenaming(
            TypedSlotContext source,
            TypedSlotContext codomain,
            Map<TypedSlot, TypedSlot> mapping) {
        super(source, codomain, mapping);
        if (!isOntoDeclaredCodomain()) {
            throw new IllegalArgumentException("A typed renaming must be onto its codomain");
        }
    }

    public static TypedRenaming of(
            TypedSlotContext source,
            TypedSlotContext codomain,
            Map<TypedSlot, TypedSlot> mapping) {
        return new TypedRenaming(source, codomain, mapping);
    }

    public static TypedRenaming identity(TypedSlotContext context) {
        Objects.requireNonNull(context, "context");
        Map<TypedSlot, TypedSlot> identity = new LinkedHashMap<>();
        for (TypedSlot slot : context) {
            identity.put(slot, slot);
        }
        return new TypedRenaming(context, context, identity);
    }

    public TypedRenaming inverse() {
        Map<TypedSlot, TypedSlot> inverse = new LinkedHashMap<>();
        for (TypedSlot target : codomain()) {
            inverse.put(target, null);
        }
        for (Map.Entry<TypedSlot, TypedSlot> entry : mapping().entrySet()) {
            inverse.put(entry.getValue(), entry.getKey());
        }
        return new TypedRenaming(codomain(), source(), inverse);
    }

    public TypedRenaming andThen(TypedRenaming after) {
        Objects.requireNonNull(after, "after");
        TypedEmbedding composed = super.andThen(after);
        return new TypedRenaming(composed.source(), composed.codomain(), composed.mapping());
    }

    public static TypedRenaming compose(TypedRenaming after, TypedRenaming before) {
        Objects.requireNonNull(before, "before");
        return before.andThen(Objects.requireNonNull(after, "after"));
    }

    public TypedPermutation asPermutation() {
        if (!source().equals(codomain())) {
            throw new IllegalStateException("Renaming source and codomain differ");
        }
        return TypedPermutation.of(source(), mapping());
    }
}

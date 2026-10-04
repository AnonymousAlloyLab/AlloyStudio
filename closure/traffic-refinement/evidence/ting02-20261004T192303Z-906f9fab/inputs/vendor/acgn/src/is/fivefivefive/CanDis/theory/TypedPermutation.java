package is.fivefivefive.CanDis.theory;

import java.util.Map;
import java.util.Objects;

/** A typed renaming from one context to itself. */
public final class TypedPermutation extends TypedRenaming {
    private TypedPermutation(
            TypedSlotContext context,
            Map<TypedSlot, TypedSlot> mapping) {
        super(context, context, mapping);
    }

    public static TypedPermutation of(
            TypedSlotContext context,
            Map<TypedSlot, TypedSlot> mapping) {
        return new TypedPermutation(context, mapping);
    }

    public static TypedPermutation identity(TypedSlotContext context) {
        return TypedRenaming.identity(context).asPermutation();
    }

    @Override
    public TypedPermutation inverse() {
        TypedRenaming inverse = super.inverse();
        return new TypedPermutation(inverse.source(), inverse.mapping());
    }

    public TypedPermutation andThen(TypedPermutation after) {
        Objects.requireNonNull(after, "after");
        TypedRenaming composed = super.andThen(after);
        return new TypedPermutation(composed.source(), composed.mapping());
    }

    public static TypedPermutation compose(
            TypedPermutation after,
            TypedPermutation before) {
        Objects.requireNonNull(before, "before");
        return before.andThen(Objects.requireNonNull(after, "after"));
    }
}

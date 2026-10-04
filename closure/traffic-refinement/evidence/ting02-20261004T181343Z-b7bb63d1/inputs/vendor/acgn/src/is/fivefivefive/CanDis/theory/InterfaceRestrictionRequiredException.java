package is.fivefivefive.CanDis.theory;

/** Rebuild stopped because support contraction still lacks a separate certificate. */
public final class InterfaceRestrictionRequiredException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final TypedEClassInterface owner;
    private final TypedSlotContext effectiveSupport;

    InterfaceRestrictionRequiredException(
            TypedEClassInterface owner,
            TypedSlotContext effectiveSupport) {
        super("Rebuild of " + owner.id() + " contracts support from "
                + owner.exposedSlots() + " to " + effectiveSupport
                + "; call restrictInterfaceCertified with an independent factorization proof");
        this.owner = owner;
        this.effectiveSupport = effectiveSupport;
    }

    public TypedEClassInterface owner() {
        return owner;
    }

    public TypedSlotContext effectiveSupport() {
        return effectiveSupport;
    }
}

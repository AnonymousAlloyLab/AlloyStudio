package is.fivefivefive.CanDis.theory;

/** Future graph adapter used only when a visible nested node becomes an opaque invocation. */
@FunctionalInterface
public interface NodeSealer {
    TypedInvocation seal(TypedENode node);
}

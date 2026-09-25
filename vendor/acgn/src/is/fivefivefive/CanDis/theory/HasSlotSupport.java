package is.fivefivefive.CanDis.theory;

/** A graph value with structurally computed typed slot support. */
public sealed interface HasSlotSupport permits TypedInvocation, PortValue, TypedENode {
    TypedSlotContext support();
}

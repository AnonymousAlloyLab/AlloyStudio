package is.fivefivefive.CanDis.augmentation;

import java.util.Objects;

import edu.mit.csail.sdg.parser.CompModule;

/**
 * Fail-closed authority for the Alloy source closure used by adaptive evidence.
 *
 * <p>The root source bytes are committed elsewhere. Until opened-module bytes
 * are committed as part of endpoint provenance, an explicit {@code open}
 * would leave part of the solver input outside that commitment. Parser-owned
 * implicit modules remain part of the pinned Alloy dependency.</p>
 */
final class AlloyModuleClosureAuthority {
    static final String VERSION =
            "adaptive-alloy-module-closure-v1-closed-root";

    private AlloyModuleClosureAuthority() {
    }

    static void requireClosedRoot(CompModule module) {
        CompModule root = Objects.requireNonNull(module, "module").getRootModule();
        for (CompModule.Open open : root.getOpens()) {
            if (open.pos != null) {
                throw new IllegalArgumentException(
                        "Adaptive Alloy evidence cannot authorize an explicit open "
                                + "until the parser-resolved module closure is committed");
            }
            CompModule resolved = open.getRealModule();
            String filename = resolved == null || resolved.pos() == null
                    ? "" : Objects.toString(resolved.pos().filename, "");
            if (!filename.startsWith("/$alloy4$/models/")) {
                throw new IllegalArgumentException(
                        "Adaptive Alloy evidence requires parser-injected modules "
                                + "to resolve from the pinned Alloy bundle");
            }
        }
    }
}

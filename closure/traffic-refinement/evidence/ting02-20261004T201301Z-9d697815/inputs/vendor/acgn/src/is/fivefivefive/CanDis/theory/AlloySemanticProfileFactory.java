package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.CommandScope;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.translator.A4Options;

/** Derives an exact semantic profile from one parser-owned Alloy command. */
public final class AlloySemanticProfileFactory {
    public static final String CONTEXT_VERSION =
            "alloy-command-options-v4-independent-search-domain";

    /*
     * This is an explicit inventory of A4Options' public state. Bound fields
     * are conservatively committed even when they normally affect search or
     * diagnostics rather than denotational Alloy semantics. The excluded
     * fields name filesystem/provenance destinations or diagnostic emission;
     * they cannot change the interpreted command.
     */
    private static final Set<String> BOUND_OPTION_FIELDS = Set.of(
            "inferPartialInstance",
            "symmetry",
            "skolemDepth",
            "coreMinimization",
            "coreGranularity",
            "solver",
            "noOverflow",
            "unrolls",
            "decompose_mode",
            "decompose_threads");
    private static final Set<String> NONSEMANTIC_OPTION_FIELDS = Set.of(
            "solverDirectory",
            "tempDirectory",
            "originalFilename",
            "recordKodkod");

    private AlloySemanticProfileFactory() {
    }

    public static Set<String> boundOptionFields() {
        return BOUND_OPTION_FIELDS;
    }

    public static Set<String> nonsemanticOptionFields() {
        return NONSEMANTIC_OPTION_FIELDS;
    }

    public static SemanticProfile fromExactlyOne(
            CompModule module,
            List<? extends Command> selectedCommands,
            A4Options options) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(selectedCommands, "selectedCommands");
        if (selectedCommands.size() != 1) {
            throw new IllegalArgumentException(
                    "Exactly one Alloy source command must be selected; found "
                            + selectedCommands.size());
        }
        Command command = Objects.requireNonNull(
                selectedCommands.get(0), "selected command");
        requireParserOwnership(module, command);
        requireIndependentCommand(command);
        return fromParserOwnedCommand(module, command, options);
    }

    private static void requireIndependentCommand(Command command) {
        if (command.parent != null) {
            throw new IllegalArgumentException(
                    "Follow-up Alloy command chains are not represented by the "
                            + "source-command semantic profile");
        }
    }

    private static SemanticProfile fromParserOwnedCommand(
            CompModule module,
            Command command,
            A4Options options) {
        Objects.requireNonNull(options, "options");
        if (options.solver == null) {
            throw new IllegalArgumentException(
                    "A source-bound profile requires a selected Alloy solver");
        }
        int bitwidth;
        if (command.bitwidth == -1) {
            bitwidth = 4;
        } else if (command.bitwidth >= 0 && command.bitwidth <= 30) {
            bitwidth = command.bitwidth;
        } else {
            throw new IllegalArgumentException(
                    "Alloy bitwidth must be omitted (-1) or in [0,30]: "
                            + command.bitwidth);
        }
        requireScopeOwnership(module, command);
        SemanticProfile.OverflowMode overflow = options.noOverflow
                ? SemanticProfile.OverflowMode.FORBID
                : SemanticProfile.OverflowMode.MODULAR;
        return SemanticProfile.fromSourceCommand(
                bitwidth,
                overflow,
                contextKey(command, options, bitwidth));
    }

    private static void requireParserOwnership(CompModule module, Command selected) {
        for (Command parsed : module.getAllCommands()) {
            if (parsed == selected) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Selected command is not owned by the parsed Alloy module");
    }

    private static void requireScopeOwnership(CompModule module, Command command) {
        List<Sig> reachable = new ArrayList<>();
        for (Sig signature : module.getAllReachableSigs()) {
            reachable.add(signature);
        }
        for (CommandScope scope : command.scope) {
            requireOwnedSignature(reachable, scope.sig);
        }
        for (Sig signature : command.additionalExactScopes) {
            requireOwnedSignature(reachable, signature);
        }
    }

    private static void requireOwnedSignature(List<Sig> reachable, Sig selected) {
        for (Sig parsed : reachable) {
            if (parsed == selected) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Command scope contains a signature not owned by the parsed module");
    }

    private static StructuralKey contextKey(
            Command command,
            A4Options options,
            int effectiveBitwidth) {
        List<StructuralKey> scopes = new ArrayList<>();
        for (CommandScope scope : command.scope) {
            scopes.add(StructuralKey.leaf(
                    "scope",
                    signatureIdentity(scope.sig),
                    Boolean.toString(scope.isExact),
                    Integer.toString(scope.startingScope),
                    Integer.toString(scope.endingScope),
                    Integer.toString(scope.increment)));
        }
        scopes.sort(Comparator.naturalOrder());

        List<StructuralKey> exactScopes = new ArrayList<>();
        for (Sig signature : command.additionalExactScopes) {
            exactScopes.add(StructuralKey.leaf(
                    "exact-scope", signatureIdentity(signature)));
        }
        exactScopes.sort(Comparator.naturalOrder());

        List<StructuralKey> children = new ArrayList<>();
        children.add(StructuralKey.branch("scopes", scopes));
        children.add(StructuralKey.branch("additional-exact-scopes", exactScopes));
        children.add(StructuralKey.leaf(
                "execution-options",
                Boolean.toString(options.inferPartialInstance),
                Integer.toString(options.symmetry),
                Integer.toString(options.skolemDepth),
                Integer.toString(options.coreMinimization),
                Integer.toString(options.coreGranularity),
                options.solver.id(),
                Boolean.toString(options.noOverflow),
                Integer.toString(options.unrolls),
                Integer.toString(options.decompose_mode),
                Integer.toString(options.decompose_threads)));
        return StructuralKey.of(
                SemanticProfile.SOURCE_COMMAND_CONTEXT_TAG,
                List.of(
                        CONTEXT_VERSION,
                        requireText(command.label, "command label"),
                        requireText(command.formula.toString(), "command formula"),
                        Boolean.toString(command.check),
                        Integer.toString(command.overall),
                        Integer.toString(effectiveBitwidth),
                        Integer.toString(command.maxseq),
                        Integer.toString(command.minprefix),
                        Integer.toString(command.maxprefix),
                        Integer.toString(command.maxstring),
                        Integer.toString(command.expects)),
                children);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String signatureIdentity(Sig signature) {
        Objects.requireNonNull(signature, "scope signature");
        return (signature.builtin ? "builtin:" : "user:") + signature.label;
    }
}

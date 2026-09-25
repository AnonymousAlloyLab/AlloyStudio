package is.fivefivefive.CanDis.augmentation;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Browsable;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.ast.ExprBinary;
import edu.mit.csail.sdg.ast.ExprCall;
import edu.mit.csail.sdg.ast.ExprQt;
import edu.mit.csail.sdg.ast.ExprUnary;
import edu.mit.csail.sdg.ast.Type;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.theory.AlloySemanticProfileFactory;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.SemanticProfile;

/** Direct, bounded Alloy validation used as evidence rather than proof by absence. */
public final class AlloyEquivalenceValidator {
    public static final String VERSION =
            "adaptive-alloy-equivalence-validator-v5-closed-source-closure";
    private static final Object SOLVER_LOCK = new Object();

    public enum ComparisonKind {
        FORMULA_IFF,
        TERM_EQUALITY
    }

    public enum ExpectedOutcome {
        NO_COUNTEREXAMPLE,
        COUNTEREXAMPLE_REQUIRED
    }

    public enum Outcome {
        NO_COUNTEREXAMPLE,
        COUNTEREXAMPLE,
        TIMEOUT,
        ERROR
    }

    /** Immutable copy of every semantic/search option bound by a source profile. */
    public record ExecutionOptions(
            boolean inferPartialInstance,
            int symmetry,
            int skolemDepth,
            int coreMinimization,
            int coreGranularity,
            String solverId,
            boolean noOverflow,
            int unrolls,
            int decomposeMode,
            int decomposeThreads) {
        public ExecutionOptions {
            solverId = requireText(solverId, "solverId");
            if (A4Options.SatSolver.parse(solverId) == null) {
                throw new IllegalArgumentException("Unknown Alloy solver: " + solverId);
            }
        }

        public static ExecutionOptions from(A4Options options) {
            Objects.requireNonNull(options, "options");
            if (options.solver == null) {
                throw new IllegalArgumentException("Alloy execution has no solver");
            }
            return new ExecutionOptions(
                    options.inferPartialInstance,
                    options.symmetry,
                    options.skolemDepth,
                    options.coreMinimization,
                    options.coreGranularity,
                    options.solver.id(),
                    options.noOverflow,
                    options.unrolls,
                    options.decompose_mode,
                    options.decompose_threads);
        }

        private A4Options materialize() {
            A4Options result = new A4Options();
            result.inferPartialInstance = inferPartialInstance;
            result.symmetry = symmetry;
            result.skolemDepth = skolemDepth;
            result.coreMinimization = coreMinimization;
            result.coreGranularity = coreGranularity;
            result.solver = A4Options.SatSolver.parse(solverId);
            if (result.solver == null || !solverId.equals(result.solver.id())) {
                throw new IllegalArgumentException(
                        "Alloy solver identity cannot be replayed: " + solverId);
            }
            result.noOverflow = noOverflow;
            result.unrolls = unrolls;
            result.decompose_mode = decomposeMode;
            result.decompose_threads = decomposeThreads;
            return result;
        }

        public String stableString() {
            return String.join("/",
                    Boolean.toString(inferPartialInstance),
                    Integer.toString(symmetry),
                    Integer.toString(skolemDepth),
                    Integer.toString(coreMinimization),
                    Integer.toString(coreGranularity),
                    solverId,
                    Boolean.toString(noOverflow),
                    Integer.toString(unrolls),
                    Integer.toString(decomposeMode),
                    Integer.toString(decomposeThreads));
        }
    }

    /** Selects the parser-owned command whose bounds/options define the claim. */
    public record SourceCommandContext(
            int commandIndex,
            ExecutionOptions options) {
        public SourceCommandContext {
            if (commandIndex < 0) {
                throw new IllegalArgumentException(
                        "Source command index must be nonnegative");
            }
            Objects.requireNonNull(options, "options");
        }

        public static SourceCommandContext from(
                int commandIndex,
                A4Options options) {
            return new SourceCommandContext(
                    commandIndex, ExecutionOptions.from(options));
        }

        public String stableString() {
            return commandIndex + "/" + options.stableString();
        }
    }

    public record Request(
            String modelSource,
            String leftExpression,
            String rightExpression,
            String universalBinder,
            ComparisonKind comparisonKind,
            ExpectedOutcome expectedOutcome,
            int scope,
            Duration timeout,
            SourceCommandContext sourceCommandContext) {
        public Request(
                String modelSource,
                String leftExpression,
                String rightExpression,
                String universalBinder,
                ComparisonKind comparisonKind,
                ExpectedOutcome expectedOutcome,
                int scope,
                Duration timeout) {
            this(
                    modelSource,
                    leftExpression,
                    rightExpression,
                    universalBinder,
                    comparisonKind,
                    expectedOutcome,
                    scope,
                    timeout,
                    null);
        }

        public Request {
            modelSource = requireText(modelSource, "modelSource");
            leftExpression = requireText(leftExpression, "leftExpression");
            rightExpression = requireText(rightExpression, "rightExpression");
            universalBinder = universalBinder == null ? "" : universalBinder.trim();
            if (!universalBinder.isEmpty()
                    && (!universalBinder.startsWith("all ")
                            || !universalBinder.endsWith("|")
                            || universalBinder.indexOf('\n') >= 0
                            || universalBinder.indexOf(';') >= 0
                            || universalBinder.indexOf('{') >= 0
                            || universalBinder.indexOf('}') >= 0)) {
                throw new IllegalArgumentException(
                        "Universal binder must be one Alloy all-declaration prefix");
            }
            Objects.requireNonNull(comparisonKind, "comparisonKind");
            Objects.requireNonNull(expectedOutcome, "expectedOutcome");
            Objects.requireNonNull(timeout, "timeout");
            if (scope < 1 || scope > 12) {
                throw new IllegalArgumentException("Alloy scope must be in [1,12]");
            }
            if (timeout.isNegative() || timeout.isZero()
                    || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException(
                        "Alloy validation timeout must be in (0,5 minutes]");
            }
        }
    }

    /** Constructor is private to this validator; callers cannot mint successful evidence. */
    public static final class Evidence {
        private final Outcome outcome;
        private final ExpectedOutcome expectedOutcome;
        private final String generatedSourceSha256;
        private final String executedFormulaSha256;
        private final String leftExpressionSha256;
        private final String rightExpressionSha256;
        private final String expectedLeftType;
        private final String expectedRightType;
        private final String semanticProfileFingerprint;
        private final String executionContext;
        private final String solver;
        private final int scope;
        private final long elapsedNanos;
        private final String detail;
        private final String digest;

        private Evidence(
                Outcome outcome,
                ExpectedOutcome expectedOutcome,
                String generatedSourceSha256,
                String executedFormulaSha256,
                String leftExpressionSha256,
                String rightExpressionSha256,
                String expectedLeftType,
                String expectedRightType,
                String semanticProfileFingerprint,
                String executionContext,
                String solver,
                int scope,
                long elapsedNanos,
                String detail) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.expectedOutcome = Objects.requireNonNull(
                    expectedOutcome, "expectedOutcome");
            this.generatedSourceSha256 = requireDigest(
                    generatedSourceSha256, "generatedSourceSha256");
            this.executedFormulaSha256 = requireDigest(
                    executedFormulaSha256, "executedFormulaSha256");
            this.leftExpressionSha256 = requireDigest(
                    leftExpressionSha256, "leftExpressionSha256");
            this.rightExpressionSha256 = requireDigest(
                    rightExpressionSha256, "rightExpressionSha256");
            this.expectedLeftType = requireText(expectedLeftType, "expectedLeftType");
            this.expectedRightType = requireText(expectedRightType, "expectedRightType");
            this.semanticProfileFingerprint = requireText(
                    semanticProfileFingerprint, "semanticProfileFingerprint");
            this.executionContext = requireText(executionContext, "executionContext");
            this.solver = requireText(solver, "solver");
            this.scope = scope;
            this.elapsedNanos = elapsedNanos;
            this.detail = Objects.requireNonNull(detail, "detail");
            this.digest = AugmentationDigests.sha256(String.join("\n",
                    VERSION,
                    outcome.name(),
                    expectedOutcome.name(),
                    generatedSourceSha256,
                    executedFormulaSha256,
                    leftExpressionSha256,
                    rightExpressionSha256,
                    expectedLeftType,
                    expectedRightType,
                    semanticProfileFingerprint,
                    executionContext,
                    solver,
                    Integer.toString(scope),
                    detail));
        }

        public Outcome outcome() {
            return outcome;
        }

        public ExpectedOutcome expectedOutcome() {
            return expectedOutcome;
        }

        public String generatedSourceSha256() {
            return generatedSourceSha256;
        }

        public String executedFormulaSha256() {
            return executedFormulaSha256;
        }

        public String leftExpressionSha256() {
            return leftExpressionSha256;
        }

        public String rightExpressionSha256() {
            return rightExpressionSha256;
        }

        public String expectedLeftType() {
            return expectedLeftType;
        }

        public String expectedRightType() {
            return expectedRightType;
        }

        public String semanticProfileFingerprint() {
            return semanticProfileFingerprint;
        }

        public String executionContext() {
            return executionContext;
        }

        public String solver() {
            return solver;
        }

        public int scope() {
            return scope;
        }

        public long elapsedNanos() {
            return elapsedNanos;
        }

        public String detail() {
            return detail;
        }

        public String digest() {
            return digest;
        }

        public boolean validatesExpectedOutcome() {
            return expectedOutcome == ExpectedOutcome.NO_COUNTEREXAMPLE
                    ? outcome == Outcome.NO_COUNTEREXAMPLE
                    : outcome == Outcome.COUNTEREXAMPLE;
        }
    }

    public Evidence validate(
            Request request,
            GraphType expectedLeftType,
            GraphType expectedRightType) {
        return validate(request, expectedLeftType, expectedRightType, null);
    }

    public Evidence validate(
            Request request,
            GraphType expectedLeftType,
            GraphType expectedRightType,
            SemanticProfile expectedProfile) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(expectedLeftType, "expectedLeftType");
        Objects.requireNonNull(expectedRightType, "expectedRightType");
        String assertion = "__acgn_aug_"
                + AugmentationDigests.sha256(
                        request.leftExpression() + "\n" + request.rightExpression())
                        .substring(0, 16);
        String relation = request.comparisonKind() == ComparisonKind.FORMULA_IFF
                ? "((" + request.leftExpression() + ") iff ("
                        + request.rightExpression() + "))"
                : "((" + request.leftExpression() + ") = ("
                        + request.rightExpression() + "))";
        String quantifiedRelation = request.universalBinder().isEmpty()
                ? relation : request.universalBinder() + " (" + relation + ")";
        String generated = request.modelSource() + "\nassert " + assertion
                + " { " + quantifiedRelation + " }\ncheck " + assertion
                + " for " + request.scope() + "\n";
        long started = System.nanoTime();
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alloy-augmentation-validator");
            thread.setDaemon(true);
            return thread;
        });
        Future<OutcomeDetail> future = executor.submit(() -> execute(
                request,
                generated,
                assertion,
                quantifiedRelation,
                expectedLeftType,
                expectedRightType,
                expectedProfile));
        try {
            OutcomeDetail result = future.get(
                    request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            return evidence(
                    request,
                    generated,
                    started,
                    result.outcome,
                    result.detail,
                    expectedLeftType,
                    expectedRightType,
                    result.plan);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return evidence(
                    request, generated, started, Outcome.TIMEOUT,
                    "Alloy validation exceeded " + request.timeout(),
                    expectedLeftType,
                    expectedRightType,
                    ExecutionPlan.unresolved(expectedProfile));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return evidence(
                    request, generated, started, Outcome.ERROR,
                    "Alloy validation was interrupted",
                    expectedLeftType,
                    expectedRightType,
                    ExecutionPlan.unresolved(expectedProfile));
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null
                    ? exception : exception.getCause();
            return evidence(
                    request, generated, started, Outcome.ERROR,
                    cause.getClass().getSimpleName() + ": "
                            + Objects.toString(cause.getMessage(), ""),
                    expectedLeftType,
                    expectedRightType,
                    ExecutionPlan.unresolved(expectedProfile));
        } finally {
            executor.shutdownNow();
        }
    }

    /** Fails before ledger mutation when a request does not replay the certified profile. */
    void requireSemanticContext(
            Request request,
            SemanticProfile expectedProfile) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(expectedProfile, "expectedProfile");
        try {
            CompModule module = CompUtil.parseEverything_fromString(
                    A4Reporter.NOP, request.modelSource());
            AlloyModuleClosureAuthority.requireClosedRoot(module);
            executionPlan(request, module, expectedProfile);
        } catch (edu.mit.csail.sdg.alloy4.Err exception) {
            throw new IllegalArgumentException(
                    "Adaptive validation source context could not be parsed",
                    exception);
        }
    }

    private static OutcomeDetail execute(
            Request request,
            String generated,
            String assertion,
            String quantifiedRelation,
            GraphType expectedLeftType,
            GraphType expectedRightType,
            SemanticProfile expectedProfile) throws Exception {
        CompModule base = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, request.modelSource());
        AlloyModuleClosureAuthority.requireClosedRoot(base);
        ExecutionPlan plan = executionPlan(request, base, expectedProfile);
        requireExpectedComparisonKind(
                request.comparisonKind(), expectedLeftType, expectedRightType);
        ResolvedExpressions expressions = resolveExpressions(
                base, request, quantifiedRelation);
        requireExpectedType(
                expressions.left.type(), base, expectedLeftType, "left");
        requireExpectedType(
                expressions.right.type(), base, expectedRightType, "right");
        if (containsTemporal(expressions.left) || containsTemporal(expressions.right)) {
            throw new IllegalArgumentException(
                    "Alloy augmentation validation is atemporal; temporal claims need "
                            + "an independent temporal validator");
        }

        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, generated);
        AlloyModuleClosureAuthority.requireClosedRoot(module);
        List<Command> commands = module.getAllCommands();
        if (commands.isEmpty()) {
            throw new IllegalStateException(
                    "Generated validation module contains no command");
        }
        Command generatedValidation = commands.get(commands.size() - 1);
        if (!generatedValidation.check
                || !(generatedValidation.label.equals(assertion)
                        || generatedValidation.label.endsWith("/" + assertion))) {
            throw new IllegalStateException(
                    "The final generated command is not the augmentation assertion");
        }
        if (generatedValidation.parent != null) {
            throw new IllegalArgumentException(
                    "The generated equality command must execute independently");
        }
        if (containsTemporal(generatedValidation.formula)) {
            throw new IllegalArgumentException(
                    "SAT4J augmentation validation is atemporal; temporal claims need "
                            + "an independent temporal validator");
        }
        if (plan.sourceCommandIndex >= 0) {
            if (plan.sourceCommandIndex >= commands.size() - 1) {
                throw new IllegalArgumentException(
                        "Source command context does not name an original command");
            }
            SemanticProfile generatedProfile = AlloySemanticProfileFactory.fromExactlyOne(
                    module,
                    List.of(commands.get(plan.sourceCommandIndex)),
                    plan.options);
            if (!plan.profileFingerprint.equals(generatedProfile.fingerprint())) {
                throw new IllegalArgumentException(
                        "Generated validation module changed the source command context");
            }
        }
        Command validationCommand = plan.sourceCommandIndex < 0
                ? generatedValidation
                : sourceBoundValidationCommand(
                        commands.get(plan.sourceCommandIndex),
                        generatedValidation,
                        assertion);
        if (containsTemporal(validationCommand.formula)) {
            throw new IllegalArgumentException(
                    "SAT4J augmentation validation is atemporal; the selected command "
                            + "search domain contains temporal logic");
        }
        ExecutionPlan executedPlan = plan.withExecutedFormula(
                validationCommand.formula);
        A4Options options = plan.options;
        A4Solution solution;
        synchronized (SOLVER_LOCK) {
            solution = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    validationCommand,
                    options);
        }
        if (solution == null) {
            throw new IllegalStateException("Alloy returned no solution object");
        }
        return solution.satisfiable()
                ? new OutcomeDetail(
                        Outcome.COUNTEREXAMPLE, "SAT counterexample", executedPlan)
                : new OutcomeDetail(
                        Outcome.NO_COUNTEREXAMPLE,
                        "UNSAT at bounded scope",
                        executedPlan);
    }

    private static ExecutionPlan executionPlan(
            Request request,
            CompModule module,
            SemanticProfile expectedProfile) {
        SourceCommandContext context = request.sourceCommandContext();
        if (expectedProfile == null) {
            if (context == null) {
                A4Options options = new A4Options();
                options.solver = A4Options.SatSolver.SAT4J;
                return new ExecutionPlan(
                        -1,
                        options,
                        "UNBOUND_REQUEST_ONLY",
                        "REQUEST_SCOPE_ONLY/" + request.scope(),
                        request.scope(),
                        AugmentationDigests.sha256("PENDING_UNBOUND_VALIDATION"));
            }
            return sourceCommandPlan(request, module, context, null);
        }
        if (expectedProfile.isSourceCommandBound()) {
            if (context == null) {
                throw new IllegalArgumentException(
                        "A source-command-bound endpoint requires an explicit parser-owned "
                                + "validation command and execution options");
            }
            return sourceCommandPlan(request, module, context, expectedProfile);
        }
        if (!expectedProfile.isFixedCompatibilityProfile()) {
            throw new IllegalArgumentException(
                    "Adaptive Alloy evidence requires a source-bound profile or the "
                            + "explicit fixed compatibility fixture profile");
        }
        if (context != null) {
            throw new IllegalArgumentException(
                    "A source command context cannot be attached to a fixed-profile fixture");
        }
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        options.noOverflow = expectedProfile.overflowMode()
                == SemanticProfile.OverflowMode.FORBID;
        return new ExecutionPlan(
                -1,
                options,
                expectedProfile.fingerprint(),
                "FIXED_COMPATIBILITY_REQUEST_SCOPE/" + request.scope(),
                request.scope(),
                AugmentationDigests.sha256("PENDING_FIXED_VALIDATION"));
    }

    private static ExecutionPlan sourceCommandPlan(
            Request request,
            CompModule module,
            SourceCommandContext context,
            SemanticProfile expectedProfile) {
        List<Command> commands = module.getAllCommands();
        if (context.commandIndex() >= commands.size()) {
            throw new IllegalArgumentException(
                    "Source command index " + context.commandIndex()
                            + " is outside the parsed module's " + commands.size()
                            + " commands");
        }
        A4Options options = context.options().materialize();
        SemanticProfile actual = AlloySemanticProfileFactory.fromExactlyOne(
                module,
                List.of(commands.get(context.commandIndex())),
                options);
        if (expectedProfile != null && !actual.equals(expectedProfile)) {
            throw new IllegalArgumentException(
                    "Validation command/options do not match the certified semantic profile");
        }
        Command command = commands.get(context.commandIndex());
        return new ExecutionPlan(
                context.commandIndex(),
                options,
                actual.fingerprint(),
                "SOURCE_COMMAND/" + context.stableString(),
                command.overall,
                AugmentationDigests.sha256(
                        "PENDING_SOURCE_VALIDATION/" + actual.fingerprint()));
    }

    private static Command sourceBoundValidationCommand(
            Command source,
            Command generatedValidation,
            String assertion) {
        if (source.parent != null) {
            throw new IllegalArgumentException(
                    "Follow-up Alloy command chains cannot authorize adaptive equality");
        }
        // Alloy's parsed Command.formula is already the executable search
        // domain: C for run C and not C for check C. A parsed check command's
        // formula is likewise the counterexample condition for the generated
        // equality. Their conjunction searches exactly for a counterexample
        // inside the selected source command's model set.
        Expr guardedCounterexample = source.formula.and(
                generatedValidation.formula);
        return new Command(
                generatedValidation.pos,
                generatedValidation.nameExpr,
                assertion,
                true,
                source.overall,
                source.bitwidth,
                source.maxseq,
                source.minprefix,
                source.maxprefix,
                source.maxstring,
                source.scope,
                source.additionalExactScopes,
                guardedCounterexample,
                null);
    }

    private static ResolvedExpressions resolveExpressions(
            CompModule module,
            Request request,
            String quantifiedRelation) throws edu.mit.csail.sdg.alloy4.Err {
        if (request.universalBinder().isEmpty()) {
            return new ResolvedExpressions(
                    CompUtil.parseOneExpression_fromString(
                            module, request.leftExpression()).deNOP(),
                    CompUtil.parseOneExpression_fromString(
                            module, request.rightExpression()).deNOP());
        }
        Expr expression = CompUtil.parseOneExpression_fromString(
                module, quantifiedRelation).deNOP();
        while (expression instanceof ExprQt
                && ((ExprQt) expression).op == ExprQt.Op.ALL) {
            expression = ((ExprQt) expression).sub.deNOP();
        }
        if (!(expression instanceof ExprBinary)) {
            throw new IllegalArgumentException(
                    "Universal validation did not resolve to one binary comparison");
        }
        ExprBinary comparison = (ExprBinary) expression;
        ExprBinary.Op expected = request.comparisonKind() == ComparisonKind.FORMULA_IFF
                ? ExprBinary.Op.IFF : ExprBinary.Op.EQUALS;
        if (comparison.op != expected) {
            throw new IllegalArgumentException(
                    "Universal validation resolved to " + comparison.op
                            + " instead of " + expected);
        }
        return new ResolvedExpressions(
                comparison.left.deNOP(), comparison.right.deNOP());
    }

    private static void requireExpectedType(
            Type actual,
            CompModule module,
            GraphType expected,
            String side) {
        GraphType authenticated = AlloyTypeBridge.graphType(
                ExactAlloyType.fromParser(actual, module));
        if (!authenticated.equals(expected)) {
            throw new IllegalArgumentException(
                    side + " Alloy expression type " + actual
                            + " authenticates as " + authenticated
                            + ", not certified graph type " + expected);
        }
    }

    private static void requireExpectedComparisonKind(
            ComparisonKind kind,
            GraphType left,
            GraphType right) {
        if (kind == ComparisonKind.FORMULA_IFF) {
            if (!GraphType.BOOL.equals(left) || !GraphType.BOOL.equals(right)) {
                throw new IllegalArgumentException(
                        "IFF validation requires certified Boolean endpoints");
            }
            return;
        }
        if (GraphType.BOOL.equals(left)
                || GraphType.BOOL.equals(right)
                || left.kind() != right.kind()) {
            throw new IllegalArgumentException(
                    "Term equality requires compatible non-Boolean certified endpoints");
        }
    }

    private static boolean containsTemporal(Browsable root) {
        Set<Browsable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return containsTemporal(root, visited);
    }

    private static boolean containsTemporal(Browsable node, Set<Browsable> visited) {
        if (node == null || !visited.add(node)) {
            return false;
        }
        if (node instanceof ExprUnary) {
            ExprUnary.Op op = ((ExprUnary) node).op;
            if (op == ExprUnary.Op.AFTER
                    || op == ExprUnary.Op.ALWAYS
                    || op == ExprUnary.Op.EVENTUALLY
                    || op == ExprUnary.Op.BEFORE
                    || op == ExprUnary.Op.HISTORICALLY
                    || op == ExprUnary.Op.ONCE
                    || op == ExprUnary.Op.PRIME) {
                return true;
            }
        }
        if (node instanceof ExprBinary) {
            ExprBinary.Op op = ((ExprBinary) node).op;
            if (op == ExprBinary.Op.UNTIL
                    || op == ExprBinary.Op.RELEASES
                    || op == ExprBinary.Op.SINCE
                    || op == ExprBinary.Op.TRIGGERED) {
                return true;
            }
        }
        if (node instanceof ExprCall
                && containsTemporal(((ExprCall) node).fun.getBody(), visited)) {
            return true;
        }
        for (Browsable child : node.getSubnodes()) {
            if (containsTemporal(child, visited)) {
                return true;
            }
        }
        return false;
    }

    private static Evidence evidence(
            Request request,
            String generated,
            long started,
            Outcome outcome,
            String detail,
            GraphType expectedLeftType,
            GraphType expectedRightType,
            ExecutionPlan plan) {
        return new Evidence(
                outcome,
                request.expectedOutcome(),
                AugmentationDigests.sha256(generated),
                plan.executedFormulaSha256,
                AugmentationDigests.sha256(request.leftExpression()),
                AugmentationDigests.sha256(request.rightExpression()),
                expectedLeftType.toString(),
                expectedRightType.toString(),
                plan.profileFingerprint,
                plan.context,
                plan.options.solver.id(),
                plan.effectiveScope,
                System.nanoTime() - started,
                detail);
    }

    private record ResolvedExpressions(Expr left, Expr right) {
    }

    private record ExecutionPlan(
            int sourceCommandIndex,
            A4Options options,
            String profileFingerprint,
            String context,
            int effectiveScope,
            String executedFormulaSha256) {
        private ExecutionPlan {
            Objects.requireNonNull(options, "options");
            profileFingerprint = requireText(
                    profileFingerprint, "profileFingerprint");
            context = requireText(context, "context");
            executedFormulaSha256 = requireDigest(
                    executedFormulaSha256, "executedFormulaSha256");
            if (options.solver == null) {
                throw new IllegalArgumentException(
                        "Validation execution plan has no solver");
            }
        }

        private ExecutionPlan withExecutedFormula(Expr formula) {
            return new ExecutionPlan(
                    sourceCommandIndex,
                    options,
                    profileFingerprint,
                    context,
                    effectiveScope,
                    AugmentationDigests.sha256(formula.toString()));
        }

        private static ExecutionPlan unresolved(SemanticProfile profile) {
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            return new ExecutionPlan(
                    -1,
                    options,
                    profile == null ? "UNBOUND_REQUEST_ONLY" : profile.fingerprint(),
                    "UNRESOLVED_BEFORE_EXECUTION",
                    -1,
                    AugmentationDigests.sha256("UNRESOLVED_BEFORE_EXECUTION"));
        }
    }

    private record OutcomeDetail(
            Outcome outcome,
            String detail,
            ExecutionPlan plan) {
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireDigest(String value, String label) {
        String checked = requireText(value, label);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is not SHA-256");
        }
        return checked;
    }
}

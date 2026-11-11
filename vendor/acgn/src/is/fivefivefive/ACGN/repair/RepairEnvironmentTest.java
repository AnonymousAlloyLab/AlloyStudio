package is.fivefivefive.ACGN.repair;

import java.util.List;
import java.util.Set;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;

/** Registered bounded source-to-result checks; not a universal Java refinement proof. */
public final class RepairEnvironmentTest {
    private static int checks;
    private static final String MODEL = "sig S { r: set S }\n"
            + "pred candidate { all x: S | some x.r }\n"
            + "pred oracle { all y: S | no y.r }\nrun {} for 3 but 4 Int\n";

    private RepairEnvironmentTest() { }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--control")) { controllerMatrix(); return; }
        RepairTask task = task(MODEL);
        RepairEnvironment environment = new RepairEnvironment(task, 8);
        RepairEnvironment.State original = environment.initial();
        check(task.semanticProfile().isSourceCommandBound(), "Source-owned profile required");
        check(environment.distanceFromInitial(original) == 0, "Identity repair distance");
        check(environment.emitModule(original).equals(MODEL), "Source input retained exactly");
        RepairAction action = find(environment, original, "some", "no");
        RepairEnvironment.Transition transition = environment.step(original, action);
        check(transition.outcome() == RepairKernel.Outcome.APPLIED, "Cardinality repair applies: " + transition.detail());
        RepairEnvironment.State edited = transition.state();
        check(edited.body().equals(original.body().replace("some", "no")), "Only selected token changes");
        check(environment.emitModule(edited).equals(MODEL.replace("some x.r", "no x.r")), "Surrounding context unchanged");
        check(original.control().revision() == 0 && original.body().contains("some"), "Original remains immutable");
        check(edited.control().remaining() == 7 && edited.control().edits() == 1, "Attempt accounting");
        check(edited.control().context().equals(original.control().context()), "Context identity preserved");
        check(edited.control().theory().equals(original.control().theory()), "R0 identity preserved");
        check(!edited.observationDigest().equals(original.observationDigest()), "Repair does not union different meanings");
        check(environment.distanceFromInitial(edited) > 0, "Existing metric detects repair");
        check(hasCounterexample(MODEL), "Original differs from oracle in bounded Alloy");
        check(!hasCounterexample(environment.emitModule(edited)), "Repaired source matches oracle in bounded Alloy");

        RepairEnvironment expectedEnvironment = new RepairEnvironment(task(MODEL.replace("some x.r", "no x.r")), 8);
        check(edited.observationDigest().equals(expectedEnvironment.initial().observationDigest()),
                "Reparsing the emitted source produces the same observation");
        RepairEnvironment.Transition stale = environment.step(edited, action);
        check(stale.outcome() == RepairKernel.Outcome.STALE && stale.state().body().equals(edited.body()), "Stale edit is atomic");
        check(stale.state().control().remaining() == 6, "Stale edit consumes fuel");
        RepairAction forged = new RepairAction(action.taskId(), action.candidateHash(), action.revision(),
                action.kind(), action.path(), action.start(), action.end(), action.expected(), "no S } fact { no S } pred bad {");
        RepairEnvironment.Transition rejected = environment.step(original, forged);
        check(rejected.outcome() == RepairKernel.Outcome.REJECTED
                && rejected.failure() == RepairEnvironment.Failure.INVALID_ACTION, "Non-menu source injection rejects");
        check(rejected.state().body().equals(original.body()) && rejected.state().control().remaining() == 7,
                "Rejected edits preserve source and consume fuel");

        RepairAction stop = environment.actions(original).actions().stream()
                .filter(a -> a.kind() == RepairAction.Kind.STOP).findFirst().orElseThrow();
        RepairEnvironment.Transition stopped = environment.step(original, stop);
        check(stopped.outcome() == RepairKernel.Outcome.STOP_REQUESTED && stopped.state().body().equals(original.body()),
                "Stop is not a repair or a semantic success");
        check(environment.step(stopped.state(), action).outcome() == RepairKernel.Outcome.CLOSED, "Stopped state cannot edit");
        RepairEnvironment empty = new RepairEnvironment(task, 0);
        check(empty.step(empty.initial(), action).outcome() == RepairKernel.Outcome.EXHAUSTED, "Exhausted state cannot edit");
        expectFailure(() -> new RepairKernel.State(0, Integer.MAX_VALUE, 0, false, "c", "t"), "Control overflow rejected");
        expectFailure(() -> environment.actions(expectedEnvironment.initial()), "Foreign task state rejected");

        String sibling = "sig S {}\npred candidate { (some S) and (some S) }\nrun {} for 3\n";
        RepairEnvironment siblings = new RepairEnvironment(task(sibling), 4);
        List<RepairAction> siblingNo = siblings.actions(siblings.initial()).actions().stream()
                .filter(a -> a.expected().equals("some") && a.replacement().equals("no")).toList();
        check(siblingNo.size() == 2 && siblingNo.get(0).start() != siblingNo.get(1).start(), "Shared shapes retain two source occurrences");
        RepairEnvironment.State oneSibling = applied(siblings, siblings.initial(), siblingNo.get(0));
        check(oneSibling.body().contains("some S") && oneSibling.body().contains("no S"), "One-use edit does not affect sibling");

        RepairEnvironment bool = new RepairEnvironment(task("sig S {}\npred candidate { some S and no S }\nrun {} for 3\n"), 4);
        RepairAction boolOp = bool.actions(bool.initial()).actions().stream()
                .filter(a -> a.kind() == RepairAction.Kind.REPLACE_OPERATOR && a.replacement().contains(" or "))
                .findFirst().orElseThrow();
        check(applied(bool, bool.initial(), boolOp).body().contains(" or "), "Boolean list operator edit");

        RepairEnvironment relation = new RepairEnvironment(task("sig S {}\npred candidate { some (S-S) }\nrun {} for 3\n"), 4);
        RepairAction relPlus = relation.actions(relation.initial()).actions().stream()
                .filter(a -> a.kind() == RepairAction.Kind.REPLACE_OPERATOR && a.replacement().contains(" + "))
                .findFirst().orElseThrow();
        check(applied(relation, relation.initial(), relPlus).body().contains("+"),
                "Same-type relational operator replacement");
        RepairEnvironment intArithmetic = new RepairEnvironment(task("sig S {}\npred candidate { #S + #S > 0 }\nrun {} for 3\n"), 4);
        check(intArithmetic.actions(intArithmetic.initial()).actions().stream()
                .noneMatch(a -> a.replacement().contains(" & ")), "Integer PLUS not exposed as relational ACI");
        check(environment.actions(original).actions().stream().noneMatch(a -> a.expected().equals(".")), "JOIN not arbitrarily relabelled or flattened by repair");

        String precedence = "sig A, B, C {}\npred candidate { some A implies some B implies some C }\n"
                + "pred oracle { some A and (some B implies some C) }\nrun {} for 3\n";
        RepairEnvironment precedenceEnvironment = new RepairEnvironment(task(precedence), 4);
        RepairAction andRoot = precedenceEnvironment.actions(precedenceEnvironment.initial()).actions().stream()
                .filter(a -> a.kind() == RepairAction.Kind.REPLACE_OPERATOR
                        && a.expected().equals("some A implies some B implies some C")
                        && a.replacement().contains(" && ")).findFirst().orElseThrow();
        RepairEnvironment.State regrouped = applied(precedenceEnvironment, precedenceEnvironment.initial(), andRoot);
        check(!hasCounterexample(precedenceEnvironment.emitModule(regrouped)), "Binary relabel retains operand tree");
        check(hasCounterexample(precedence.replace("some A implies some B implies some C",
                "some A and some B implies some C")), "Token-only replacement would change semantic result");

        RepairAction wrap = environment.actions(original).actions().stream()
                .filter(a -> a.kind() == RepairAction.Kind.WRAP_NOT && a.expected().equals("some x.r"))
                .findFirst().orElseThrow();
        RepairEnvironment.State wrapped = applied(environment, original, wrap);
        RepairAction unwrap = environment.actions(wrapped).actions().stream()
                .filter(a -> a.kind() == RepairAction.Kind.UNWRAP_NOT).findFirst().orElseThrow();
        check(applied(environment, wrapped, unwrap).observationDigest().equals(original.observationDigest()),
                "Wrap/unwrap restores normalized meaning without merging repair states");
        RepairAction quantifier = find(environment, original, "all", "some");
        check(applied(environment, original, quantifier).body().contains("some x: S"), "Quantifier token replacement keeps declarations");
        check(environment.actions(original).actions().equals(environment.actions(original).actions()), "Menu deterministic");
        check(environment.actions(original).actions().stream().noneMatch(a -> a.replacement().contains("oracle")), "Oracle is not an action target");

        String alpha = MODEL.replace("x:", "z:").replace("x.r", "z.r");
        RepairEnvironment alphaEnvironment = new RepairEnvironment(task(alpha), 4);
        check(alphaEnvironment.initial().observationDigest().equals(original.observationDigest()), "Alpha-renamed source retains canonical identity");
        RepairEnvironment.State alphaEdited = applied(alphaEnvironment, alphaEnvironment.initial(),
                find(alphaEnvironment, alphaEnvironment.initial(), "some", "no"));
        check(alphaEdited.observationDigest().equals(edited.observationDigest()), "Corresponding occurrence repair commutes with alpha renaming");

        String unicode = "// Unicode comment: \u03bb\r\n" + MODEL.replace("\n", "\r\n");
        RepairEnvironment crlf = new RepairEnvironment(task(unicode), 4);
        RepairEnvironment.State crlfEdited = applied(crlf, crlf.initial(), find(crlf, crlf.initial(), "some", "no"));
        check(crlf.emitModule(crlfEdited).equals(unicode.replace("some x.r", "no x.r")), "CRLF and Unicode source offsets preserved");

        expectFailure(() -> task(MODEL.replace("run {}", "run candidate")), "Dependent command rejected");
        expectFailure(() -> task(MODEL.replace("run {}", "fact { candidate }\nrun {}")), "Dependent facts rejected");
        expectFailure(() -> task(MODEL.replace("all x: S | some x.r", "oracle")), "Direct oracle call rejected");
        expectFailure(() -> task(MODEL.replace("all x: S | some x.r", "helper")
                + "pred helper { oracle }\n"), "Transitive oracle call rejected");
        expectFailure(() -> task(MODEL.replace("some x.r", "always some x.r")), "Temporal expression rejected explicitly");
        expectFailure(() -> task(MODEL.replace("all x: S | some x.r", "helper")
                + "pred helper { always some S }\n"), "Temporal logic hidden in call rejected");
        expectFailure(() -> task(MODEL.replace("sig S", "var sig S")), "Mutable signature rejected");
        expectFailure(() -> task("open util/ordering[S]\n" + MODEL), "Explicit import rejected");
        expectFailure(() -> RepairTask.fromSource(MODEL, "oracle", 0, Set.of("oracle")), "Oracle cannot be selected as repair target");
        expectFailure(() -> RepairTask.fromSource(MODEL, "candidate", 99, Set.of()), "Invalid command index rejected");

        RepairSearch.Result beam = RepairSearch.explore(environment, RepairSearch.Mode.BEAM, 7, 5, 2, 2);
        check(beam.attempts() == 5 && beam.aborted().isEmpty(), "Beam expansion stays within total attempt budget");
        RepairSearch.Result randomA = RepairSearch.explore(environment, RepairSearch.Mode.RANDOM, 555, 5, 2, 2);
        RepairSearch.Result randomB = RepairSearch.explore(environment, RepairSearch.Mode.RANDOM, 555, 5, 2, 2);
        check(randomA.aborted().isEmpty() && bodies(randomA).equals(bodies(randomB)), "Seeded random exploration replay");
        System.out.println("RESULT,RepairEnvironmentTest," + checks + "," + edited.observationDigest());
    }

    private static List<String> bodies(RepairSearch.Result result) {
        return result.candidates().stream().map(RepairEnvironment.State::body).toList();
    }

    private static RepairTask task(String source) { return RepairTask.fromSource(source, "candidate", 0, Set.of("oracle")); }

    private static RepairAction find(RepairEnvironment environment, RepairEnvironment.State state, String old, String replacement) {
        return environment.actions(state).actions().stream()
                .filter(a -> a.expected().equals(old) && a.replacement().equals(replacement))
                .findFirst().orElseThrow(() -> new AssertionError("Missing action " + old + " -> " + replacement));
    }

    private static RepairEnvironment.State applied(RepairEnvironment environment, RepairEnvironment.State state, RepairAction action) {
        RepairEnvironment.Transition result = environment.step(state, action);
        check(result.outcome() == RepairKernel.Outcome.APPLIED, "Expected application: " + result.failure() + " " + result.detail());
        return result.state();
    }

    private static boolean hasCounterexample(String source) {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        Expr mismatch = CompUtil.parseOneExpression_fromString(module, "not (candidate[] iff oracle[])");
        Command command = module.getAllCommands().get(0).change(module.getAllReachableFacts().and(mismatch));
        A4Options options = new A4Options();
        options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        var solution = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.getAllReachableSigs(), command, options);
        check(solution != null, "Solver must return an actual result");
        return solution.satisfiable();
    }

    private static void expectFailure(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static int bit(boolean value) { return value ? 1 : 0; }

    private static void controllerMatrix() {
        for (int remaining : new int[] {0, 1, 2, 5})
            for (int revision : new int[] {0, 3})
                for (boolean stopped : new boolean[] {false, true})
                    for (boolean fresh : new boolean[] {false, true})
                        for (boolean stop : new boolean[] {false, true})
                            for (boolean admitted : new boolean[] {false, true}) {
                                RepairKernel.Result r = RepairKernel.step(new RepairKernel.State(
                                        revision, remaining, revision, stopped, "ctx", "R0"), fresh, stop, admitted);
                                System.out.println("CONTROL," + remaining + "," + revision + "," + bit(stopped)
                                        + "," + bit(fresh) + "," + bit(stop) + "," + bit(admitted) + "," + r.outcome().ordinal()
                                        + "," + r.state().remaining() + "," + r.state().revision() + "," + r.state().edits()
                                        + "," + bit(r.state().stopped()) + "," + r.state().context() + "," + r.state().theory());
                            }
    }
}

package is.fivefivefive.ACGN.repair;

import java.util.Set;
import org.json.JSONObject;

/** Bounded real-Alloy feedback and structured-observation regressions. */
public final class RepairLearningTest {
    private static int checks;
    private static final String MODEL = "sig S { r: set S }\n"
            + "pred candidate { all x: S | some x.r }\n"
            + "pred oracle { all y: S | no y.r }\nrun {} for 3 but 4 Int\n";

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--matrix")) { matrix(); return; }
        RepairTask task = task(MODEL);
        RepairEnvironment env = new RepairEnvironment(task, 4);
        RepairEvaluator evaluator = new RepairEvaluator(task, "oracle", 4, 2);
        RepairEvaluator.Snapshot bank = evaluator.snapshot();
        RepairEvaluator.Score initial = evaluator.score(env.initial(), bank);
        check(initial.positiveCount() > 0 && initial.negativeCount() > 0, "Both retained classes exist");
        check(initial.potential() < 1000, "Sampled disagreement is visible");
        check(evaluator.bankStatus().getString("profile").equals(task.semanticProfile().fingerprint()), "Source profile bound");
        RepairEvaluator.Validation bad = evaluator.validate(env.initial());
        check(bad.extra() == RepairFeedback.Answer.SAT && bad.missing() == RepairFeedback.Answer.SAT, "Both error directions queried");
        check(bad.verdict() == RepairFeedback.Verdict.COUNTEREXAMPLE, "Different predicates cannot solve");
        RepairAction action = env.actions(env.initial()).actions().stream()
                .filter(a -> a.expected().equals("some") && a.replacement().equals("no")).findFirst().orElseThrow();
        RepairEnvironment.State repaired = env.step(env.initial(), action).state();
        RepairEvaluator.Validation good = evaluator.validate(repaired);
        check(good.verdict() == RepairFeedback.Verdict.SOLVED_WITHIN_PROFILE, "Repaired source passes bounded equivalence");
        check(good.extra() == RepairFeedback.Answer.UNSAT && good.missing() == RepairFeedback.Answer.UNSAT, "Success has two UNSAT results");
        check(evaluator.score(repaired, evaluator.snapshot()).potential() == 1000, "Actual retained instances all agree");

        String corpus = "sig S {}\npred candidate { some S }\npred oracle { one S }\nrun {} for 3\n";
        RepairTask poolTask = task(corpus);
        RepairEnvironment poolEnv = new RepairEnvironment(poolTask, 4);
        RepairEvaluator pool = new RepairEvaluator(poolTask, "oracle", 2, 1);
        RepairEvaluator.Snapshot before = pool.snapshot();
        RepairEvaluator.Score beforeScore = pool.score(poolEnv.initial(), before);
        check(beforeScore.potential() == 1000, "Candidate fits initial retained samples");
        RepairEvaluator.Validation miss = pool.validate(poolEnv.initial());
        check(miss.verdict() == RepairFeedback.Verdict.COUNTEREXAMPLE, "Perfect samples trigger a real counterexample");
        check(miss.newVersion() > miss.oldVersion(), "New counterexample changes bank version");
        expectFailure(() -> pool.score(poolEnv.initial(), before), "Old snapshot cannot mix with updated bank");
        RepairEvaluator.Score after = pool.score(poolEnv.initial(), pool.snapshot());
        check(after.potential() < 1000 && after.negativeErrors() > 0, "Inserted counterexample is actually iterated later");
        check(after.negativeCount() <= 2 && after.positiveCount() <= 2, "Actual retained capacity bound");
        check(pool.validate(poolEnv.initial()).newVersion() == pool.snapshot().version(), "Stable duplicate evidence does not inflate capacity");

        RepairTask vacuousTask = task(MODEL + "fact { some S and no S }\n");
        RepairEnvironment vacuousEnv = new RepairEnvironment(vacuousTask, 4);
        RepairEvaluator vacuous = new RepairEvaluator(vacuousTask, "oracle", 2, 1);
        check(vacuous.validate(vacuousEnv.initial()).verdict() == RepairFeedback.Verdict.VACUOUS, "Vacuous context is not solved");
        check(vacuous.score(vacuousEnv.initial(), vacuous.snapshot()).potential() == 0, "Empty banks avoid division by zero");
        RepairTask tautologyTask = task(MODEL.replace("all y: S | no y.r", "no none"));
        RepairEvaluator tautology = new RepairEvaluator(tautologyTask, "oracle", 2, 1);
        check(tautology.bankStatus().getBoolean("negativeAbsent"), "Absent negative class explicit");
        check(!tautology.bankStatus().getBoolean("positiveAbsent"), "Tautological oracle still has context witnesses");
        RepairTask impossibleTask = task(MODEL.replace("all y: S | no y.r", "some none"));
        RepairEvaluator impossible = new RepairEvaluator(impossibleTask, "oracle", 2, 1);
        check(impossible.bankStatus().getBoolean("positiveAbsent"), "Absent positive class explicit");
        expectFailure(() -> new RepairEvaluator(task(MODEL.replace("all y: S | no y.r", "candidate")), "oracle", 2, 1), "Oracle depends on target");
        expectFailure(() -> evaluator.score(poolEnv.initial(), evaluator.snapshot()), "Unrelated source context rejected");

        JSONObject obs = RepairObservation.encode(env.initial(), env.actions(env.initial()), evaluator.score(env.initial(), evaluator.snapshot()));
        String rendered = obs.toString();
        check(!rendered.contains("oracle") && !rendered.contains("sourceHash") && !rendered.contains("taskId"), "Private metadata not in policy observation");
        check(obs.getJSONArray("actions").length() == env.actions(env.initial()).actions().size(), "Every action has structured features");
        check(rendered.contains("USE") && rendered.contains("CANONICAL:") && rendered.contains("SOURCE:"), "Bindings and both graphs present");
        for (Object value : obs.getJSONArray("actions")) {
            int node = ((JSONObject) value).getInt("node");
            check(node >= 0 && node < obs.getJSONArray("nodes").length(), "Action occurrence references a real graph node");
        }
        for (int p : new int[] {0, 250, 1000}) check(RepairFeedback.shaped(1000, p, 0) == 1000 - p, "Terminal potential is zero");
        System.out.println("RESULT,RepairLearningTest," + checks);
    }

    private static RepairTask task(String source) { return RepairTask.fromSource(source, "candidate", 0, Set.of("oracle")); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void expectFailure(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(message);
    }

    private static void matrix() {
        for (boolean compatible : new boolean[] {false, true})
            for (RepairFeedback.Answer context : RepairFeedback.Answer.values())
                for (RepairFeedback.Answer extra : RepairFeedback.Answer.values())
                    for (RepairFeedback.Answer missing : RepairFeedback.Answer.values())
                        System.out.println("FEEDBACK," + (compatible ? 1 : 0) + "," + context.ordinal() + ","
                                + extra.ordinal() + "," + missing.ordinal() + ","
                                + RepairFeedback.classify(compatible, context, extra, missing).ordinal());
        for (int base : new int[] {-50, -10, 1000})
            for (int old : new int[] {0, 250, 1000})
                for (int next : new int[] {0, 500, 1000})
                    System.out.println("REWARD," + base + "," + old + "," + next + "," + RepairFeedback.shaped(base, old, next));
    }
}

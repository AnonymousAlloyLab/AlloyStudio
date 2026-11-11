package is.fivefivefive.ACGN.repair;

/** Executable finite contract in formal/FeedbackLearning.lean. */
public final class RepairFeedback {
    private RepairFeedback() { }
    public enum Answer { SAT, UNSAT, UNKNOWN, ERROR }
    public enum Verdict { SOLVED_WITHIN_PROFILE, COUNTEREXAMPLE, VACUOUS, UNRESOLVED }

    public static Verdict classify(boolean compatible, Answer context, Answer extra, Answer missing) {
        if (!compatible) return Verdict.UNRESOLVED;
        if (context == Answer.UNSAT) return Verdict.VACUOUS;
        if (context != Answer.SAT) return Verdict.UNRESOLVED;
        if (extra == Answer.UNSAT && missing == Answer.UNSAT) return Verdict.SOLVED_WITHIN_PROFILE;
        if ((extra == Answer.SAT || extra == Answer.UNSAT)
                && (missing == Answer.SAT || missing == Answer.UNSAT)) return Verdict.COUNTEREXAMPLE;
        return Verdict.UNRESOLVED;
    }

    public static int shaped(int base, int oldPotential, int newPotential) {
        return Math.subtractExact(Math.addExact(base, newPotential), oldPotential);
    }
}

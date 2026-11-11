package is.fivefivefive.ACGN.repair;

import java.util.Objects;

/** Control contract in docs/rl-repair/formal/RepairKernel.lean; no semantic success state. */
public final class RepairKernel {
    public static final int MAX_ATTEMPTS = 1_000_000;

    private RepairKernel() { }

    public enum Outcome { CLOSED, EXHAUSTED, STALE, STOP_REQUESTED, REJECTED, APPLIED }

    public record State(int revision, int remaining, int edits, boolean stopped,
                        String context, String theory) {
        public State {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(theory, "theory");
            if (revision != edits || edits < 0 || remaining < 0
                    || (long) edits + remaining > MAX_ATTEMPTS) {
                throw new IllegalArgumentException("Invalid bounded repair control state");
            }
        }
    }

    public record Result(State state, Outcome outcome) { }

    public static Result step(State s, boolean fresh, boolean stop, boolean admitted) {
        Objects.requireNonNull(s, "state");
        if (s.stopped()) return new Result(s, Outcome.CLOSED);
        if (s.remaining() == 0) return new Result(s, Outcome.EXHAUSTED);
        int remaining = s.remaining() - 1;
        Outcome outcome = !fresh ? Outcome.STALE : stop ? Outcome.STOP_REQUESTED
                : !admitted ? Outcome.REJECTED : Outcome.APPLIED;
        boolean applied = outcome == Outcome.APPLIED;
        State next = new State(s.revision() + (applied ? 1 : 0), remaining,
                s.edits() + (applied ? 1 : 0), outcome == Outcome.STOP_REQUESTED,
                s.context(), s.theory());
        return new Result(next, outcome);
    }
}

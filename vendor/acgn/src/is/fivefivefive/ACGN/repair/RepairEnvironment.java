package is.fivefivefive.ACGN.repair;

import java.util.Objects;

import edu.mit.csail.sdg.alloy4.Err;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.metric.RepairView;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;

/** Pure candidate transitions; no reward, oracle access, equality admission, or learned policy. */
public final class RepairEnvironment {
    private final RepairTask task;
    private final State initial;

    public enum Failure { NONE, INVALID_ACTION, INVALID_ALLOY, UNSUPPORTED, CONSTRUCTION_ERROR, RESOURCE_LIMIT }

    public static final class State {
        final RepairTask task;
        final RepairTask.Candidate candidate;
        private final RepairKernel.State control;

        private State(RepairTask task, RepairTask.Candidate candidate, RepairKernel.State control) {
            this.task = task;
            this.candidate = candidate;
            this.control = control;
        }

        public String body() { return candidate.body(); }
        public String candidateHash() { return candidate.hash(); }
        public String observationDigest() { return candidate.prepared().canonicalObservation().digest(); }
        public RepairView repairView() { return candidate.prepared().repairView(); }
        public int repairSize() { return candidate.prepared().repairObservationSize(); }
        public RepairKernel.State control() { return control; }
    }

    public record Transition(State state, RepairKernel.Outcome outcome, Failure failure, String detail) { }

    public RepairEnvironment(RepairTask task, int attempts) {
        this.task = Objects.requireNonNull(task, "task");
        RepairKernel.State control = new RepairKernel.State(0, attempts, 0, false,
                task.id(), RepairTask.THEORY);
        initial = new State(task, task.prepare(task.originalBody()), control);
    }

    public State initial() { return initial; }

    /** Whole-body proposal for the separate search/LLM study; never an equality admission. */
    public State propose(String body) {
        RepairTask.Candidate candidate = task.prepare(body);
        RepairKernel.Result result = RepairKernel.step(initial.control, true, false, true);
        return new State(task, candidate, result.state());
    }

    public RepairActionEnumerator.Menu actions(State state) {
        requireOwner(state);
        return RepairActionEnumerator.enumerate(state);
    }

    public Transition step(State state, RepairAction action) {
        requireOwner(state);
        Objects.requireNonNull(action, "action");
        boolean fresh = action.taskId().equals(task.id())
                && action.candidateHash().equals(state.candidateHash())
                && action.revision() == state.control.revision();
        boolean stop = action.kind() == RepairAction.Kind.STOP;
        RepairTask.Candidate next = state.candidate;
        boolean admitted = false;
        Failure failure = Failure.NONE;
        String detail = "";
        if (!state.control.stopped() && state.control.remaining() > 0 && fresh) {
            boolean minted = actions(state).actions().contains(action);
            if (!minted) {
                stop = false;
                failure = Failure.INVALID_ACTION;
                detail = "Action is not in this candidate's current typed menu";
            } else if (!stop) {
                try {
                    String body = state.body();
                    if (action.end() > body.length()
                            || !body.substring(action.start(), action.end()).equals(action.expected())) {
                        throw new IllegalArgumentException("Source occurrence mismatch");
                    }
                    String edited = body.substring(0, action.start()) + action.replacement()
                            + body.substring(action.end());
                    next = task.prepare(edited);
                    admitted = true;
                } catch (RepairTask.UnsupportedInput unsupported) {
                    failure = Failure.UNSUPPORTED;
                    detail = unsupported.getMessage();
                } catch (Err invalid) {
                    failure = Failure.INVALID_ALLOY;
                    detail = invalid.getClass().getSimpleName() + ": " + invalid.getMessage();
                } catch (QuotientRepairDistance.ResourceLimitException limited) {
                    failure = Failure.RESOURCE_LIMIT;
                    detail = limited.getMessage();
                } catch (RuntimeException construction) {
                    failure = Failure.CONSTRUCTION_ERROR;
                    detail = construction.getClass().getSimpleName() + ": " + construction.getMessage();
                }
            }
        }
        RepairKernel.Result result = RepairKernel.step(state.control, fresh, stop, admitted);
        if (result.outcome() != RepairKernel.Outcome.APPLIED) next = state.candidate;
        return new Transition(new State(task, next, result.state()), result.outcome(), failure, detail);
    }

    public int distanceFromInitial(State state) {
        requireOwner(state);
        return CanonicalAlloyPipeline.distance(initial.candidate.prepared(), state.candidate.prepared());
    }

    /** Export only; do not put the paired module (including oracle bodies) in policy features. */
    public String emitModule(State state) {
        requireOwner(state);
        return task.source(state.body());
    }

    private void requireOwner(State state) {
        if (Objects.requireNonNull(state, "state").task != task) {
            throw new IllegalArgumentException("State belongs to another repair task");
        }
    }
}

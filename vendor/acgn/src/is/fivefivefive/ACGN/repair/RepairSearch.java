package is.fivefivefive.ACGN.repair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Finite exploration baseline. Returned candidates are not claimed to be solutions. */
public final class RepairSearch {
    private RepairSearch() { }
    public enum Mode { BEAM, RANDOM }
    public record Result(List<RepairEnvironment.State> candidates, int attempts,
                         int rejected, boolean truncatedMenus, String aborted) {
        public Result { candidates = List.copyOf(candidates); }
    }

    public static Result explore(RepairEnvironment environment, Mode mode, long seed,
                                  int maximumAttempts, int beamWidth, int maximumDepth) {
        if (maximumAttempts <= 0 || maximumAttempts > 10_000 || beamWidth <= 0 || beamWidth > 64
                || maximumDepth <= 0 || maximumDepth > 16 || mode == null) {
            throw new IllegalArgumentException("Invalid bounded exploration configuration");
        }
        Random random = new Random(seed);
        List<RepairEnvironment.State> frontier = List.of(environment.initial());
        List<RepairEnvironment.State> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(environment.initial().body());
        int attempts = 0;
        int rejected = 0;
        boolean truncated = false;
        for (int depth = 0; depth < maximumDepth && !frontier.isEmpty(); depth++) {
            List<RepairEnvironment.State> nextFrontier = new ArrayList<>();
            for (RepairEnvironment.State state : frontier) {
                if (state.control().remaining() == 0 || state.control().stopped()) continue;
                RepairActionEnumerator.Menu menu = environment.actions(state);
                truncated |= menu.truncated();
                List<RepairAction> actions = new ArrayList<>(menu.actions());
                actions.removeIf(a -> a.kind() == RepairAction.Kind.STOP);
                if (mode == Mode.RANDOM) java.util.Collections.shuffle(actions, random);
                for (RepairAction action : actions) {
                    if (attempts == maximumAttempts) {
                        return new Result(found, attempts, rejected, truncated, "");
                    }
                    attempts++;
                    RepairEnvironment.Transition transition = environment.step(state, action);
                    if (transition.failure() == RepairEnvironment.Failure.CONSTRUCTION_ERROR
                            || transition.failure() == RepairEnvironment.Failure.RESOURCE_LIMIT) {
                        return new Result(found, attempts, rejected, truncated, transition.detail());
                    }
                    if (transition.outcome() != RepairKernel.Outcome.APPLIED) { rejected++; continue; }
                    RepairEnvironment.State next = transition.state();
                    if (seen.add(next.body())) { found.add(next); nextFrontier.add(next); }
                }
            }
            if (mode == Mode.BEAM) {
                nextFrontier.sort(Comparator.comparingInt((RepairEnvironment.State s) -> s.body().length())
                        .thenComparing(RepairEnvironment.State::body));
            } else {
                java.util.Collections.shuffle(nextFrontier, random);
            }
            frontier = List.copyOf(nextFrontier.subList(0, Math.min(beamWidth, nextFrontier.size())));
        }
        return new Result(found, attempts, rejected, truncated, "");
    }
}

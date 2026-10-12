package live;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

/** Constructed counterexamples for dependency slots; one JVM, no source logging. */
public final class PracticeDependencySelfTest {
    private static int checks;
    private static final String ENV = "sig Node {}\npred prior {}\npred downstream {}\n";
    private PracticeDependencySelfTest() { }

    public static void main(String[] arguments) {
        PrintStream report = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            malformedPolicy();
            malformedParameterDomain();
            blankSlotCounterexamples();
            identityAndReachability();
            privateReferenceAndRetainedContext();
            legacyAndVerifiedPredecessor();
            report.println(new JSONObject().put("status", "PASS").put("checks", checks));
        } catch (AssertionError error) {
            // Every assertion label is a fixed witness description, not input.
            report.println(new JSONObject().put("status", "FAIL").put("checks", checks)
                    .put("failedCheck", error.getMessage()));
        } catch (Throwable error) {
            report.println(new JSONObject().put("status", "FAIL").put("checks", checks)
                    .put("failedCheck", error.getClass().getSimpleName()));
        }
    }

    private static void check(boolean accepted, String label) {
        if (!accepted) throw new AssertionError(label);
        checks++;
    }

    private static String source(String environment, String body) {
        return environment + "pred target {\n" + body + "\n}\n";
    }

    private static JSONObject feedback(String environment, String student, String oracle) {
        return new JSONObject().put("studentSource", source(environment, student))
                .put("oracleSource", source(environment, oracle)).put("studentBody", student)
                .put("predicate", "target");
    }

    private static JSONObject validator(String environment, String starter, String oracle) {
        return new JSONObject().put("predicate", "target")
                .put("sourcePrefix", environment + "pred target {\n").put("sourceSuffix", "\n}\n")
                .put("starter", starter).put("oracleBodies", new JSONArray().put(oracle))
                .put("correctBodies", new JSONArray());
    }

    private static void invalidAll(Object policy) {
        for (String metric : List.of("canonical", "ast")) {
            JSONObject result = LiveFeedback.evaluate(feedback(ENV, "some Node", "some Node")
                    .put("metric", metric).put("blockedPredicates", policy));
            check(result.getString("status").equals("invalid_request"), "invalid structural policy");
            check(!result.has("distance"), "no structural evidence for invalid policy");
        }
        JSONObject behavior = BehaviorFeedback.evaluate(feedback(ENV, "some Node", "some Node")
                .put("blockedPredicates", policy));
        check(behavior.getString("status").equals("invalid_request"), "invalid behavioral policy");
        check(!behavior.has("score"), "no behavioral evidence for invalid policy");
        JSONObject imported = ExerciseValidator.evaluate(validator(ENV, "some Node", "some Node")
                .put("blockedPredicates", policy));
        check(imported.getString("status").equals("rejected")
                && imported.getString("code").equals("INVALID_REQUEST"), "invalid private validation policy");
    }

    private static void malformedPolicy() {
        for (Object policy : List.of(JSONObject.NULL, "prior", new JSONObject(), true, 3,
                new JSONArray().put(3), new JSONArray().put(""), new JSONArray().put("other/prior"),
                new JSONArray().put("é"), new JSONArray().put("prior").put("prior"),
                new JSONArray().put("target"), new JSONArray().put("n".repeat(129)))) invalidAll(policy);
        JSONArray overflow = new JSONArray();
        for (int i = 0; i < 357; i++) overflow.put("p" + i);
        invalidAll(overflow);
        invalidAll(new JSONArray().put("Absent"));

        StringBuilder environment = new StringBuilder("sig Node {}\n");
        JSONArray maximum = new JSONArray();
        for (int i = 0; i < 356; i++) {
            environment.append("pred p").append(i).append(" {}\n");
            maximum.put("p" + i);
        }
        JSONObject policy = feedback(environment.toString(), "some Node", "some Node")
                .put("blockedPredicates", maximum);
        PracticeDependencyGuard.check(parse(policy.getString("studentSource")), "target",
                PracticeDependencyGuard.read(policy, "target"));
        check(PracticeDependencyGuard.read(policy, "target").size() == 356, "inclusive bounded policy capacity");
    }

    private static void blockedAll(String environment, String body, List<String> names) {
        JSONArray blocked = new JSONArray(names);
        for (String metric : List.of("canonical", "ast")) {
            JSONObject result = LiveFeedback.evaluate(feedback(environment, body, "some Node")
                    .put("metric", metric).put("blockedPredicates", blocked));
            check(result.getString("status").equals("unsupported")
                    && result.getJSONArray("diagnostics").getJSONObject(0).getString("code")
                            .equals("UNSOLVED_PRACTICE_CALL"), "structural blocked slot");
            check(!result.has("distance") && !result.has("operations"), "blocked slot cannot earn structural evidence");
        }
        JSONObject behavior = BehaviorFeedback.evaluate(feedback(environment, body, "some Node")
                .put("blockedPredicates", blocked));
        check(behavior.getString("status").equals("unsupported")
                && behavior.getJSONArray("diagnostics").getJSONObject(0).getString("code")
                        .equals("UNSOLVED_PRACTICE_CALL"), "behavioral blocked slot");
        check(!behavior.has("score") && !behavior.has("sampling"), "blocked slot cannot earn behavioral evidence");
        JSONObject starter = ExerciseValidator.evaluate(validator(environment, body, "some Node")
                .put("blockedPredicates", blocked));
        check(starter.getString("status").equals("rejected")
                && starter.getString("code").equals("UNSUPPORTED_DEPENDENCY")
                && !starter.has("candidateIndex"), "blocked private starter rejected");
        JSONObject oracle = ExerciseValidator.evaluate(validator(environment, "some Node", body)
                .put("blockedPredicates", blocked));
        check(oracle.getString("status").equals("rejected")
                && oracle.getString("code").equals("UNSUPPORTED_DEPENDENCY")
                && oracle.getInt("candidateIndex") == 0, "blocked private oracle rejected independently");
    }

    private static void blankSlotCounterexamples() {
        blockedAll(ENV, "prior[]", List.of("prior", "downstream"));
        blockedAll(ENV + "pred helper { prior[] }\n", "helper[]", List.of("prior"));
        blockedAll(ENV + "fun helper: set Node { { n: Node | prior[] } }\n", "some helper", List.of("prior"));
        blockedAll(ENV, "all n: {x: Node | prior[]} | some n", List.of("prior"));
        blockedAll(ENV + "pred receiver[n: set Node] { some n }\n",
                "receiver[{x: Node | prior[]}]", List.of("prior"));
        blockedAll(ENV + "fun helper[n: set Node] : set Node { {x: n | prior[]} }\n",
                "some helper[Node]", List.of("prior"));
        blockedAll(ENV + "pred priorC0 { prior[] }\n", "priorC0[]", List.of("priorC0"));
        blockedAll("sig Node {}\npred prior[n: Node] {}\npred priorC0[n: Node] { prior[n] }\n",
                "all n: Node | priorC0[n]", List.of("priorC0"));
    }

    /** Alloy does not resolve function calls inside a parameter domain. Such an
     * upload fails at parsing, before there can be a reachable blocked Func.
     * Keep this malformed-source witness separate from valid call-graph tests.
     */
    private static void malformedParameterDomain() {
        String environment = ENV + "fun helper[n: {x: Node | prior[]}] : set Node { n }\n";
        for (String metric : List.of("canonical", "ast")) {
            JSONObject result = LiveFeedback.evaluate(feedback(environment, "some helper[Node]", "some Node")
                    .put("metric", metric).put("blockedPredicates", new JSONArray().put("prior")));
            check(result.getString("status").equals("invalid") && !result.has("distance"),
                    "malformed parameter domain cannot earn structural evidence");
        }
        JSONObject behavior = BehaviorFeedback.evaluate(feedback(environment, "some helper[Node]", "some Node")
                .put("blockedPredicates", new JSONArray().put("prior")));
        check(behavior.getString("status").equals("invalid") && !behavior.has("score"),
                "malformed parameter domain cannot earn behavioral evidence");
        JSONObject imported = ExerciseValidator.evaluate(validator(environment, "some helper[Node]", "some Node")
                .put("blockedPredicates", new JSONArray().put("prior")));
        check(imported.getString("status").equals("rejected"), "malformed parameter domain cannot be imported");
    }

    private static CompModule parse(String source) {
        return CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
    }

    private static void permitted(String environment, String body, List<String> blocked) {
        JSONObject request = feedback(environment, body, body).put("blockedPredicates", new JSONArray(blocked));
        PracticeDependencyGuard.check(parse(request.getString("studentSource")), "target",
                PracticeDependencyGuard.read(request, "target"));
        check(true, "permitted dependency graph");
    }

    private static void identityAndReachability() {
        permitted(ENV + "pred helper { prior[] }\npred aggregate { target[] and helper[] }\n",
                "some Node", List.of("prior", "downstream"));
        permitted("open util/ordering[Node] as order\nsig Node {}\npred first {}\n",
                "some order/first", List.of("first"));
        permitted(ENV, "let prior = Node | some prior", List.of("prior"));
        permitted(ENV, "some Node // prior[]\n", List.of("prior"));

        JSONObject namedFunction = feedback("sig Node {}\nfun helper: set Node { Node }\n",
                "some helper", "some helper").put("blockedPredicates", new JSONArray().put("helper"));
        try {
            PracticeDependencyGuard.check(parse(namedFunction.getString("studentSource")), "target",
                    PracticeDependencyGuard.read(namedFunction, "target"));
            throw new AssertionError("function policy must reject");
        } catch (PracticeDependencyGuard.InvalidSelection expected) { check(true, "policy requires predicate identity"); }

        for (String environment : List.of("sig Node {}\nfact { target[] }\n",
                "sig Node {}\npred helper { target[] }\nfact { helper[] }\n")) {
            for (String metric : List.of("canonical", "ast")) {
                JSONObject result = LiveFeedback.evaluate(feedback(environment, "some Node", "some Node")
                        .put("metric", metric).put("blockedPredicates", new JSONArray()));
                check(result.getString("status").equals("unsupported")
                        && result.getJSONArray("diagnostics").getJSONObject(0).getString("code")
                                .equals("RECURSIVE_OR_CONTEXT_DEPENDENCY"), "facts remain outside practice graph");
            }
        }
        for (String body : List.of("target[]", "helper[]")) {
            for (String metric : List.of("canonical", "ast")) {
                JSONObject result = LiveFeedback.evaluate(feedback("sig Node {}\npred helper { target[] }\n", body, "some Node")
                        .put("metric", metric).put("blockedPredicates", new JSONArray()));
                check(!result.getString("status").equals("ok") && !result.has("distance"),
                        "target and helper cycles cannot produce structural evidence");
            }
        }
    }

    private static void privateReferenceAndRetainedContext() {
        for (String metric : List.of("canonical", "ast")) {
            JSONObject result = LiveFeedback.evaluate(feedback(ENV, "some Node", "prior[]")
                    .put("metric", metric).put("blockedPredicates", new JSONArray().put("prior")));
            check(result.getString("status").equals("engine_error") && !result.has("distance"),
                    "blocked private structural reference cannot be compared");
        }
        BehaviorFeedback.resetPools();
        JSONObject allowed = feedback(ENV, "some Node", "prior[]").put("blockedPredicates", new JSONArray());
        check(BehaviorFeedback.evaluate(allowed).getString("status").equals("ok"), "initial permissive behavior context");
        JSONObject denied = BehaviorFeedback.evaluate(new JSONObject(allowed.toString())
                .put("blockedPredicates", new JSONArray().put("prior")));
        check(denied.getString("status").equals("unsupported") && !denied.has("score"),
                "new policy rechecks an already retained oracle context");
        check(BehaviorFeedback.poolState()[0] == 0 && BehaviorFeedback.poolState()[1] == 0,
                "denied oracle context releases retained pools");
    }

    private static void legacyAndVerifiedPredecessor() {
        String environment = "sig Node {}\npred prior { some Node }\npred downstream {}\n";
        JSONObject oldShape = feedback(environment, "prior[]", "prior[]");
        check(PracticeDependencyGuard.read(oldShape, "target") == null, "absent policy remains legacy");
        for (String metric : List.of("canonical", "ast")) {
            JSONObject legacy = LiveFeedback.evaluate(new JSONObject(oldShape.toString()).put("metric", metric));
            JSONObject current = LiveFeedback.evaluate(new JSONObject(oldShape.toString()).put("metric", metric)
                    .put("blockedPredicates", new JSONArray().put("downstream")));
            check(legacy.toString().equals(current.toString()), "verified predecessor preserves structural behavior");
            check(current.getString("status").equals("ok") && current.getInt("distance") == 0,
                    "verified predecessor can earn zero distance");
        }
        JSONObject behavior = BehaviorFeedback.evaluate(new JSONObject(oldShape.toString())
                .put("blockedPredicates", new JSONArray().put("downstream")));
        check(behavior.getString("status").equals("ok") && behavior.getDouble("score") == 1,
                "verified predecessor uses actual implementation and full behavioral agreement");
        JSONObject imported = ExerciseValidator.evaluate(validator(environment, "prior[]", "prior[]")
                .put("blockedPredicates", new JSONArray().put("downstream")));
        check(imported.getString("status").equals("ok"), "verified private prerequisite environment accepted");

        String typedEnvironment = "sig Node {}\npred prior[n: set Node] { some n }\n"
                + "pred priorC0[n: set Node] { prior[n] }\npred downstream {}\n";
        String typedPrefix = typedEnvironment + "pred target[n: set Node] {\n";
        JSONObject typed = new JSONObject().put("predicate", "target").put("studentBody", "priorC0[n]")
                .put("studentSource", typedPrefix + "priorC0[n]\n}\n")
                .put("oracleSource", typedPrefix + "priorC0[n]\n}\n")
                .put("blockedPredicates", new JSONArray().put("downstream"));
        for (String metric : List.of("canonical", "ast")) {
            JSONObject result = LiveFeedback.evaluate(new JSONObject(typed.toString()).put("metric", metric));
            check(result.getString("status").equals("ok") && result.getInt("distance") == 0,
                    "typed target can call verified parameterized C forwarding alias");
        }
        JSONObject typedBehavior = BehaviorFeedback.evaluate(typed);
        check(typedBehavior.getString("status").equals("ok") && typedBehavior.getDouble("score") == 1,
                "typed prerequisite forwarding preserves pointwise behavioral agreement");
        BehaviorFeedback.resetPools();
    }
}

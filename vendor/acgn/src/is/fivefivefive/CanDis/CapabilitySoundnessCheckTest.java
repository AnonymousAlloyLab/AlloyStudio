package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;

/** Bounded source-to-solver checks for temporal capability command preparation. */
public final class CapabilitySoundnessCheckTest {
    private static int checks;

    private CapabilitySoundnessCheckTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        Path work = Files.createTempDirectory("acgn-capability-temporal-test-");
        try {
            commandPreparation(work);
            mutableTemporalDuals(work);
            negativeControls(work);
            reporting(work);
            System.out.println("CapabilitySoundnessCheckTest: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void commandPreparation(Path work) throws Exception {
        String[] formulas = {"after some A", "before some A", "always some A", "eventually some A",
                "historically some A", "once some A", "some A'", "(some A) until (no A)",
                "(some A) releases (no A)", "(some A) since (no A)", "(some A) triggered (no A)"};
        for (int i = 0; i < formulas.length; i++) {
            Path file = model(work, "operator" + i, "sig A {}\npred inner { " + formulas[i]
                    + " }\npred outer { inner }\nassert CapBenchEquivalent_captest { outer iff outer }\n"
                    + "check CapBenchEquivalent_captest for 4 but 5 Int, 2 seq, 2..4 steps, exactly 2 A\n");
            CompModule module = parse(file);
            Command original = module.getAllCommands().get(0);
            check(!CompUtil.isTemporalModel(module.getAllReachableSigs(), original),
                    "reproduce Alloy 6.1 call-body detection gap for " + formulas[i]);
            Command prepared = CapabilitySoundnessCheck.prepareCommand(module, original);
            check(prepared != original && CompUtil.isTemporalModel(module.getAllReachableSigs(), prepared),
                    "expose every temporal operator through nested calls");
            check(prepared.check == original.check && prepared.overall == original.overall
                            && prepared.bitwidth == original.bitwidth && prepared.maxseq == original.maxseq
                            && prepared.minprefix == original.minprefix && prepared.maxprefix == original.maxprefix
                            && prepared.scope.equals(original.scope)
                            && prepared.additionalExactScopes.equals(original.additionalExactScopes)
                            && prepared.parent == original.parent && prepared.nameExpr == original.nameExpr,
                    "retain the exact command context");
            check(CapabilitySoundnessCheck.prepareCommand(module, prepared) == prepared,
                    "command exposure must be idempotent");
            JSONObject result = checkFile(file).toJson();
            check(!result.getBoolean("inconclusive") && result.getString("error").isEmpty()
                            && !result.getBoolean("solverReportedCounterexample"),
                    "operator equivalence solves: " + formulas[i] + " " + result);
            check(result.getInt("minTrace") == 2 && result.getInt("maxTrace") == 4,
                    "explicit temporal bounds survive execution");
        }

        Path staticFile = model(work, "static", "sig A {}\npred p { some A }\n"
                + "assert CapBenchEquivalent_captest { p iff p }\ncheck CapBenchEquivalent_captest for 4\n");
        CompModule staticModule = parse(staticFile);
        Command staticCommand = staticModule.getAllCommands().get(0);
        check(CapabilitySoundnessCheck.prepareCommand(staticModule, staticCommand) == staticCommand,
                "static commands are not modified because of a family label");
        check(!checkFile(staticFile).toJson().getBoolean("temporal"), "static solve remains static");

        Path until = model(work, "untilStatic", "sig A {}\n"
                + "pred p { not ((some A) until (no A)) }\n"
                + "pred q { (no A) releases (some A) }\n"
                + "assert CapBenchEquivalent_captest { p iff q }\ncheck CapBenchEquivalent_captest for 4\n");
        CapabilitySoundnessCheck.Result untilResult = checkFile(until);
        check(!untilResult.failed() && untilResult.toJson().getBoolean("temporalModeExposed"),
                "minimal UNTIL dual avoids the spurious static-reduction counterexample");

        Path functionFile = model(work, "function", "sig A {}\nfun nextA : set A { A' }\n"
                + "pred p { some nextA }\nassert CapBenchEquivalent_captest { p iff p }\n"
                + "check CapBenchEquivalent_captest for 4\n");
        check(checkFile(functionFile).toJson().getBoolean("temporalModeExposed"),
                "relational function bodies participate in call-aware detection");

        Path directFile = model(work, "argument", "sig A {}\npred p[x:set A] { some x }\n"
                + "assert CapBenchEquivalent_captest { p[A'] iff some A' }\ncheck CapBenchEquivalent_captest for 4\n");
        JSONObject direct = checkFile(directFile).toJson();
        check(direct.getBoolean("temporal") && !direct.getBoolean("temporalModeExposed"),
                "already visible operators in arguments need no exposure");

        model(work, "temporalLibrary", "module temporalLibrary\nsig A {}\npred p { after some A }\n");
        Path imported = model(work, "imported", "open temporalLibrary\n"
                + "assert CapBenchEquivalent_captest { p iff p }\ncheck CapBenchEquivalent_captest for 4\n");
        CapabilitySoundnessCheck.Result importedResult = checkFile(imported);
        check(!importedResult.failed() && importedResult.toJson().getBoolean("temporalModeExposed"),
                "imported predicate bodies retain temporal detection");
    }

    private static void mutableTemporalDuals(Path work) throws Exception {
        String p = "(some State.p)";
        String q = "(some State.q)";
        String[][] duals = {
                {"not always " + p, "eventually not " + p},
                {"not eventually " + p, "always not " + p},
                {"not historically " + p, "once not " + p},
                {"not once " + p, "historically not " + p},
                {"not (" + p + " until " + q + ")", "(not " + p + ") releases (not " + q + ")"},
                {"not (" + p + " since " + q + ")", "(not " + p + ") triggered (not " + q + ")"}
        };
        for (int i = 0; i < duals.length; i++) {
            Path file = pair(work, "dual" + i, duals[i][0], duals[i][1]);
            JSONObject result = checkFile(file).toJson();
            check(!result.getBoolean("inconclusive") && result.getString("error").isEmpty()
                            && !result.getBoolean("solverReportedCounterexample"), "mutable temporal dual " + i + " " + result);
            check(result.getBoolean("temporal") && !result.getBoolean("temporalModeExposed")
                            && result.getInt("maxTrace") == 4, "mutable model uses native Pardinus mode");
        }
    }

    private static void negativeControls(Path work) throws Exception {
        String p = "(some State.p)";
        String q = "(some State.q)";
        String[][] negatives = {
                {"after " + p, p},
                {"before " + p, p},
                {"once " + p, "historically " + p},
                {"not (" + p + " until " + q + ")", "(not " + p + ") until (not " + q + ")"},
                {"not (" + p + " since " + q + ")", "(not " + p + ") since (not " + q + ")"}
        };
        for (int i = 0; i < negatives.length; i++) {
            CapabilitySoundnessCheck.Result result = checkFile(pair(work, "negative" + i,
                    negatives[i][0], negatives[i][1]));
            check(result.failed() && result.toJson().getBoolean("solverReportedCounterexample")
                            && !result.toJson().getBoolean("inconclusive"),
                    "wrong temporal equality has a conclusive counterexample " + i);
        }

        Path hidden = model(work, "hiddenNegative", "sig A {}\npred p { after some A }\n"
                + "pred q { after no A }\nassert CapBenchEquivalent_captest { p iff q }\n"
                + "check CapBenchEquivalent_captest for 4\n");
        CapabilitySoundnessCheck.Result hiddenResult = checkFile(hidden);
        check(hiddenResult.failed() && hiddenResult.toJson().getBoolean("temporalModeExposed")
                        && hiddenResult.toJson().getBoolean("solverReportedCounterexample"),
                "temporal mode exposure does not hide a real counterexample");
        Path missing = model(work, "missing", "sig A {}\nrun {} for 4\n");
        CapabilitySoundnessCheck.Result missingResult = checkFile(missing);
        check(missingResult.failed() && missingResult.toJson().getBoolean("inconclusive")
                        && !missingResult.toJson().getString("error").isEmpty(),
                "a missing temporal check remains a failed check");
    }

    private static void reporting(Path work) throws Exception {
        Path root = Files.createDirectory(work.resolve("runner"));
        Path models = Files.createDirectory(root.resolve("models"));
        model(models, "bad", "sig A {}\npred p { after some A }\npred q { after no A }\n"
                + "assert CapBenchEquivalent_captest { p iff q }\ncheck CapBenchEquivalent_captest for 4\n");
        Files.writeString(root.resolve("metadata.csv"),
                "relativePath,family,subtype\nbad.als,temporal_normalization,negative\n", StandardCharsets.UTF_8);
        Path output = work.resolve("reported");
        boolean failed = false;
        try {
            CapabilitySoundnessCheck.main(new String[] {"--root", root.toString(), "--output", output.toString()});
        } catch (AssertionError expected) {
            failed = true;
        }
        check(failed, "CLI fails instead of exempting temporal counterexamples");
        check(!Files.exists(root.resolve("soundness.json")), "separate report output preserves input snapshot");
        StringBuilder summary = new StringBuilder();
        CapabilityBenchmark.appendSoundnessSummary(summary, output.resolve("soundness.json"));
        check(summary.toString().contains("Conclusive failures: 1")
                        && summary.toString().contains("Completed bounded temporal checks: 1")
                        && summary.toString().contains("counterexamples among inconclusive checks: 0"),
                "report distinguishes conclusive temporal counterexamples from unknowns");

        Path oldReport = work.resolve("historical-soundness.json");
        Files.writeString(oldReport, new JSONObject().put("checks", new JSONArray().put(new JSONObject()
                .put("inconclusive", true).put("solverReportedCounterexample", true).put("error", "")))
                .toString(), StandardCharsets.UTF_8);
        summary.setLength(0);
        CapabilityBenchmark.appendSoundnessSummary(summary, oldReport);
        check(summary.toString().contains("Inconclusive checks: 1")
                        && summary.toString().contains("counterexamples among inconclusive checks: 1"),
                "historical uncertain reports are not silently upgraded");
    }

    private static Path pair(Path work, String name, String left, String right) throws Exception {
        return model(work, name, "one sig State { var p,q: set State }\n"
                + "pred left { " + left + " }\npred right { " + right + " }\n"
                + "assert CapBenchEquivalent_captest { always (left iff right) }\n"
                + "check CapBenchEquivalent_captest for 4 but 1..4 steps\n");
    }

    private static Path model(Path work, String name, String source) throws Exception {
        Path file = work.resolve(name + ".als");
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private static CompModule parse(Path file) throws Exception {
        return CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, file.toString());
    }

    private static CapabilitySoundnessCheck.Result checkFile(Path file) {
        return CapabilitySoundnessCheck.check(file, Map.of("relativePath", file.getFileName().toString(),
                "family", "temporal_normalization", "subtype", "regression"));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

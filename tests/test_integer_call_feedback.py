"""Portable regression for SmartContract's parameterized sendEther feedback.

One bounded JVM runs all comparisons and the tiny Alloy witness. The fixture is
self-contained: it does not require the uploader's private model or credentials.
Parser and solver scratch stay in the application's build directory.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from runtime_dependencies import clean_java_environment, run_engine, runtime_classpath


ROOT = Path(__file__).resolve().parents[1]
ENVIRONMENT = 'module ether_fixture\nsig Address { var ether: one Int }\n'
HEADER = 'pred sendEther[from, to: Address, amt: Int] {\n'
BODY = ('from.ether >= amt\n'
        "ether' = ether ++ (from -> minus[from.ether, amt] + to -> plus[to.ether, amt])")


JAVA = r'''package live;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.alloy.AlloyLibraryCallableLedger;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import org.json.JSONArray;
import org.json.JSONObject;

public final class IntegerCallFeedbackProbe {
    private static final String ENV = "module ether_fixture\nsig Address { var ether: one Int }\n";
    private static final String HEADER = "pred sendEther[from, to: Address, amt: Int] {\n";
    private static final String BODY = "from.ether >= amt\n"
            + "ether' = ether ++ (from -> minus[from.ether, amt] + to -> plus[to.ether, amt])";
    private static Path scratch;
    private static int jobs;

    public static void main(String[] args) throws Exception {
        scratch = Path.of(System.getProperty("java.io.tmpdir"));
        PrintStream wire = System.out, errors = System.err;
        JSONObject output = new JSONObject();
        try (PrintStream discard = new PrintStream(OutputStream.nullOutputStream())) {
            System.setOut(discard);
            System.setErr(discard);
            for (String metric : List.of("canonical", "ast")) {
                output.put(metric + "-identity", compare(metric, ENV, HEADER, "sendEther", BODY, BODY));
                output.put(metric + "-guard", compare(metric, ENV, HEADER, "sendEther",
                        BODY.replace(">=", ">"), BODY));
                String integerHeader = "pred target[n: Int] {\n";
                String integerEnv = "module integer_fixture\n";
                output.put(metric + "-order", compare(metric, integerEnv, integerHeader, "target",
                        "minus[n, 1] = 0", "minus[1, n] = 0"));
                output.put(metric + "-callee", compare(metric, integerEnv, integerHeader, "target",
                        "plus[n, 1] = 0", "minus[n, 1] = 0"));
                String aliasedBody = BODY.replace("minus[", "ints/minus[").replace("plus[", "ints/plus[");
                String aliasedEnv = ENV.replace("sig Address", "open util/integer as ints\nsig Address");
                output.put(metric + "-alias", compare(metric, aliasedEnv, HEADER, "sendEther",
                        aliasedBody, aliasedBody));
            }
            output.put("ledger", ledgerControls());
            output.put("callIdentities", callIdentities());
            output.put("strictGuardWitness", strictGuardWitness());
            // A completed solve and rejected signatures must not poison later feedback.
            output.put("after-controls", compare("canonical", ENV, HEADER, "sendEther", BODY, BODY));
        } finally {
            System.setOut(wire);
            System.setErr(errors);
        }
        wire.println(output.toString());
    }

    private static void begin() throws Exception {
        WorkerSafety.begin(Files.createDirectory(scratch.resolve("integer-call-job-" + (++jobs))));
    }

    private static JSONObject compare(String metric, String environment, String header,
            String predicate, String learner, String reference) throws Exception {
        begin();
        try {
            String prefix = environment + header;
            return LiveFeedback.evaluate(new JSONObject().put("metric", metric).put("predicate", predicate)
                    .put("studentSource", prefix + learner + "\n}\n")
                    .put("referencePrefix", prefix).put("referenceSuffix", "\n}\n")
                    .put("referenceBodies", new JSONArray().put(reference))
                    .put("blockedPredicates", new JSONArray()));
        } finally { WorkerSafety.end(); }
    }

    private static JSONObject ledgerControls() {
        int accepted = 0, rejected = 0;
        for (String name : List.of("plus", "minus")) {
            var signature = AlloyLibraryCallableLedger.require("util/integer", name,
                    CallSymbol.Kind.EXPRESSION, 2);
            if (signature.arity() != 2 || signature.kind() != CallSymbol.Kind.EXPRESSION)
                throw new AssertionError("Wrong pinned arithmetic declaration");
            accepted++;
            for (int arity : List.of(0, 1, 3)) {
                reject("util/integer", name, CallSymbol.Kind.EXPRESSION, arity,
                        "arity disagrees with imported declaration");
                rejected++;
            }
            reject("util/integer", name, CallSymbol.Kind.FORMULA, 2,
                    "lacks an independently pinned declaration");
            reject("foreign/module", name, CallSymbol.Kind.EXPRESSION, 2,
                    "lacks an independently pinned declaration");
            rejected += 2;
        }
        return new JSONObject().put("accepted", accepted).put("rejected", rejected);
    }

    private static void reject(String module, String member, CallSymbol.Kind kind, int arity,
            String expectedBoundary) {
        try { AlloyLibraryCallableLedger.require(module, member, kind, arity); }
        catch (IllegalStateException expected) {
            if (expected.getMessage() != null && expected.getMessage().contains(expectedBoundary)) return;
            throw new AssertionError("Rejected at wrong declaration boundary", expected);
        }
        throw new AssertionError("Malformed imported call was admitted");
    }

    private static JSONArray callIdentities() throws Exception {
        begin();
        try {
            String source = "module local_integer\nopen util/integer as ints\n"
                    + "fun minus[a,b: Int]: Int { a }\n"
                    + "pred target[a,b: Int] { this/minus[a,b] = ints/minus[a,b] }\n";
            CompModule module = WorkerSafety.parse(source);
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), Set.of("target"), module);
            visitor.visit(MASGVisitor.modelWithSourceMap(module), null);
            var graph = visitor.getForest().get(visitor.getForestId("target"));
            JSONArray calls = new JSONArray();
            for (var node : graph.getVertices()) {
                if (node.getSymbol() instanceof CallSymbol call)
                    calls.put(new JSONObject().put("callee", call.getCallee())
                            .put("authority", call.getArityAuthority().name())
                            .put("arity", call.getDeclaredArity()).put("kind", call.getKind().name()));
            }
            return calls;
        } finally { WorkerSafety.end(); }
    }

    private static boolean strictGuardWitness() throws Exception {
        begin();
        try {
            // Two addresses, four-bit integers and exactly two states suffice:
            // a sender with balance 1 can transfer 1, but the strict guard refuses.
            String source = ENV + HEADER + BODY + "\n}\n"
                    + HEADER.replace("sendEther", "strictSend") + BODY.replace(">=", ">") + "\n}\n"
                    + "run { some disj from, to: Address | from.ether = 1 and to.ether = 0 "
                    + "and sendEther[from, to, 1] and not strictSend[from, to, 1] } "
                    + "for exactly 2 Address, 4 Int, 2..2 steps\n";
            CompModule module = WorkerSafety.parse(source);
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            options.noOverflow = true;
            return TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, module.getAllReachableSigs(),
                    module.getAllCommands().get(0), options).satisfiable();
        } finally { WorkerSafety.end(); }
    }
}
'''


@unittest.skipUnless(shutil.which('java') and shutil.which('javac')
                     and (ROOT / 'build/engine/classes/live/LiveFeedback.class').is_file(),
                     'Compiled engine and JDK required')
class IntegerCallFeedbackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        scratch = ROOT / 'build/integer-call-feedback-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='probe-', dir=scratch) as directory:
            folder = Path(directory)
            source = folder / 'IntegerCallFeedbackProbe.java'
            source.write_text(JAVA, encoding='utf-8')
            classpath = runtime_classpath(ROOT)
            compiler = subprocess.run(
                [shutil.which('javac'), '-J-Xmx256m', '-encoding', 'UTF-8', '--release', '17',
                 '-cp', classpath, '-d', directory, str(source)], cwd=ROOT,
                capture_output=True, timeout=40, check=False, env=clean_java_environment())
            if compiler.returncode:
                raise AssertionError('Integer call probe did not compile: '
                                     + compiler.stderr.decode('utf-8', errors='replace'))
            command = [shutil.which('java'), '-Dfile.encoding=UTF-8', '-Xmx256m',
                       '-XX:ActiveProcessorCount=2', '-cp', directory + os.pathsep + classpath,
                       'live.IntegerCallFeedbackProbe']
            with patch.dict(os.environ, ALLOY_ENGINE_TMP_ROOT=str(folder / 'owned-tmp')):
                result = run_engine(command, root=folder, cwd=ROOT, text=True, capture_output=True,
                                    timeout=60, check=False, env=clean_java_environment())
            if result.returncode or result.stderr:
                raise AssertionError('Integer call probe failed: ' + result.stderr)
            cls.observations = json.loads(result.stdout)

    def feedback(self, name, metric):
        result = self.observations[metric + '-' + name]
        self.assertEqual(result['status'], 'ok', result.get('diagnostics'))
        self.assertTrue(result['comparison']['complete'])
        self.assertEqual(result['comparison']['evaluatedCandidates'], 1)
        self.assertEqual(result['diagnostics'], [])
        self.assertTrue(result['trace']['matchesDistance'])
        return result

    def test_parameterized_temporal_send_ether_has_zero_identity_distance_in_both_modes(self):
        for metric in ('canonical', 'ast'):
            with self.subTest(metric=metric):
                result = self.feedback('identity', metric)
                self.assertEqual(result['distance'], 0)
                self.assertEqual(result['operations'], [])

    def test_strict_guard_has_positive_replayable_located_edit_in_both_modes(self):
        learner = ENVIRONMENT + HEADER + BODY.replace('>=', '>') + '\n}\n'
        for metric in ('canonical', 'ast'):
            with self.subTest(metric=metric):
                result = self.feedback('guard', metric)
                self.assertGreater(result['distance'], 0)
                self.assertFalse(result['trace']['hasAggregates'])
                self.assertTrue(result['trace']['matrixReplayVerified' if metric == 'canonical'
                                              else 'astReplayVerified'])
                self.assertEqual(sum(step['cost'] for step in result['operations']), result['distance'])
                self.assertTrue(result['operations'])
                located = []
                for step in result['operations']:
                    location = step['sourceLocation']
                    if location['status'] == 'located':
                        self.assertEqual(location['coordinateSystem'], 'module')
                        for span in location['ranges']:
                            self.assertGreater(span['end'], span['start'])
                            self.assertLessEqual(span['end'], len(learner))
                            located.append(learner[span['start']:span['end']])
                self.assertTrue(any('from.ether > amt' in fragment for fragment in located), located)

    def test_arithmetic_arguments_and_callee_identity_remain_distinct(self):
        for metric in ('canonical', 'ast'):
            for case in ('order', 'callee'):
                with self.subTest(metric=metric, case=case):
                    result = self.feedback(case, metric)
                    self.assertGreater(result['distance'], 0)
                    self.assertTrue(result['operations'])

    def test_explicit_integer_module_alias_is_accepted(self):
        for metric in ('canonical', 'ast'):
            with self.subTest(metric=metric):
                self.assertEqual(self.feedback('alias', metric)['distance'], 0)

    def test_local_same_name_call_cannot_acquire_imported_authority(self):
        self.assertCountEqual(self.observations['callIdentities'], [
            dict(callee='local_integer/minus', authority='DECLARATION', arity=2, kind='EXPRESSION'),
            dict(callee='util/integer/minus', authority='TYPECHECKED_IMPORT', arity=2, kind='EXPRESSION'),
        ])

    def test_imported_arity_kind_and_module_boundaries_remain_closed(self):
        self.assertEqual(self.observations['ledger'], dict(accepted=2, rejected=10))

    def test_alloy_constructs_strict_guard_counterexample_and_feedback_still_works(self):
        self.assertIs(self.observations['strictGuardWitness'], True)
        result = self.observations['after-controls']
        self.assertEqual((result['status'], result['distance'], result['operations']), ('ok', 0, []))


if __name__ == '__main__':
    unittest.main()

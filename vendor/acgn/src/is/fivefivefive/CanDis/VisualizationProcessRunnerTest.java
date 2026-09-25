package is.fivefivefive.CanDis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;

/** Regression checks for worker completion, hard timeout, and explicit cancellation. */
public final class VisualizationProcessRunnerTest {
    private VisualizationProcessRunnerTest() {
    }

    public static void main(String[] args) throws Exception {
        inspectCompletesInWorker();
        standardExampleIsStableAcrossWorkers();
        timeoutKillsWorker();
        cancellationKillsWorker();
        cancellationBeforeRegistrationPreventsWorkerStart();
        timeoutBeforeRegistrationPreservesCause();
        firstPreRegistrationCauseWins();
        executeCloseRaceCannotStartAfterClosure();
        firstTerminalCauseDeterminesResponse();
        serverCancellationCauseIsClosed();
        System.out.println("VisualizationProcessRunnerTest passed");
    }

    private static void standardExampleIsStableAcrossWorkers() throws Exception {
        JSONObject request = new JSONObject()
                .put("model", "sig Item {}\npred simple { some Item }")
                .put("callable", new JSONObject().put("name", "simple").put("kind", "predicate"));
        try (VisualizationProcessRunner runner = new VisualizationProcessRunner(1, 30_000L, "256m")) {
            JSONObject first = runner.execute("stable-1", "analyze", request).body();
            JSONObject second = runner.execute("stable-2", "analyze", request).body();
            String firstCanonical = first.getJSONObject("callable").getString("canonicalText");
            String secondCanonical = second.getJSONObject("callable").getString("canonicalText");
            if (!firstCanonical.equals(secondCanonical)
                    || !first.getJSONObject("graph").toString()
                            .equals(second.getJSONObject("graph").toString())) {
                throw new AssertionError("Standard example changed across isolated workers");
            }
            if (!firstCanonical.equals("(some Item)")) {
                throw new AssertionError("Unexpected standard-example presentation: " + firstCanonical);
            }
        }
    }

    private static void inspectCompletesInWorker() throws Exception {
        try (VisualizationProcessRunner runner = new VisualizationProcessRunner(1, 30_000L, "256m")) {
            VisualizationProcessRunner.ExecutionResult result = runner.execute(
                    "inspect-test",
                    "inspect",
                    new JSONObject().put("model", "sig Item {}\npred simple { some Item }"));
            if (result.status() != 200
                    || result.body().getJSONArray("callables").length() != 1
                    || runner.activeJobCount() != 0) {
                throw new AssertionError("Isolated inspection failed: " + result.body());
            }
        }
    }

    private static void timeoutKillsWorker() throws Exception {
        try (VisualizationProcessRunner runner = new VisualizationProcessRunner(1, 500L, "128m")) {
            VisualizationProcessRunner.ExecutionResult result = runner.execute(
                    "timeout-test",
                    "test-sleep",
                    new JSONObject().put("milliseconds", 30_000L));
            if (result.status() != 504 || runner.activeJobCount() != 0) {
                throw new AssertionError("Timed-out worker was not reclaimed: " + result.body());
            }
            assertSuccessfulFollowUp(runner, "after-timeout");
        }
    }

    private static void cancellationKillsWorker() throws Exception {
        try (VisualizationProcessRunner runner = new VisualizationProcessRunner(1, 30_000L, "128m")) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<VisualizationProcessRunner.ExecutionResult> pending = executor.submit(() -> runner.execute(
                        "cancel-test",
                        "test-sleep",
                        new JSONObject().put("milliseconds", 30_000L)));
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (runner.activeJobCount() == 0 && System.nanoTime() < deadline) {
                    Thread.sleep(10L);
                }
                if (!runner.cancel(
                        "cancel-test",
                        VisualizationProcessRunner.TerminalCause.CANCELLED)) {
                    throw new AssertionError("Running worker could not be cancelled");
                }
                VisualizationProcessRunner.ExecutionResult result = pending.get();
                if (result.status() != 499 || runner.activeJobCount() != 0) {
                    throw new AssertionError("Cancelled worker was not reclaimed: " + result.body());
                }
                assertSuccessfulFollowUp(runner, "after-running-cancel");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void cancellationBeforeRegistrationPreventsWorkerStart() throws Exception {
        try (VisualizationProcessRunner runner = new VisualizationProcessRunner(1, 30_000L, "128m")) {
            if (!runner.cancel(
                    "cancel-before-start",
                    VisualizationProcessRunner.TerminalCause.CANCELLED)) {
                throw new AssertionError("Cancellation was not recorded");
            }

            VisualizationProcessRunner.ExecutionResult cancelled = runner.execute(
                    "cancel-before-start",
                    "test-sleep",
                    new JSONObject().put("milliseconds", 30_000L));
            if (cancelled.status() != 499 || runner.activeJobCount() != 0) {
                throw new AssertionError("Pre-registration cancellation was lost");
            }

            assertSuccessfulFollowUp(runner, "after-pre-registration-cancel");
        }
    }

    private static void timeoutBeforeRegistrationPreservesCause() throws Exception {
        try (VisualizationProcessRunner runner = new VisualizationProcessRunner(
                1, 30_000L, "128m")) {
            runner.cancel(
                    "timeout-before-start",
                    VisualizationProcessRunner.TerminalCause.TIMED_OUT);
            VisualizationProcessRunner.ExecutionResult result = runner.execute(
                    "timeout-before-start",
                    "test-sleep",
                    new JSONObject().put("milliseconds", 30_000L));
            if (result.status() != 504
                    || !"analysis_timeout".equals(result.body().getString("code"))) {
                throw new AssertionError(
                        "Pre-registration timeout was reclassified: " + result.body());
            }
            assertSuccessfulFollowUp(runner, "after-pre-registration-timeout");
        }
    }

    private static void firstPreRegistrationCauseWins() throws Exception {
        try (VisualizationProcessRunner runner = new VisualizationProcessRunner(
                1, 30_000L, "128m")) {
            runner.cancel(
                    "pending-first-cause",
                    VisualizationProcessRunner.TerminalCause.CANCELLED);
            runner.cancel(
                    "pending-first-cause",
                    VisualizationProcessRunner.TerminalCause.TIMED_OUT);
            VisualizationProcessRunner.ExecutionResult result = runner.execute(
                    "pending-first-cause",
                    "test-sleep",
                    new JSONObject().put("milliseconds", 30_000L));
            if (result.status() != 499
                    || !"request_cancelled".equals(result.body().getString("code"))) {
                throw new AssertionError(
                        "Later pre-registration cause replaced the first: " + result.body());
            }
        }
    }

    private static void executeCloseRaceCannotStartAfterClosure() throws Exception {
        CountDownLatch reachedGate = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        try (VisualizationProcessRunner runner = new VisualizationProcessRunner(
                1,
                30_000L,
                "128m",
                builder -> {
                    starts.incrementAndGet();
                    return builder.start();
                },
                ignored -> {
                    reachedGate.countDown();
                    releaseGate.await();
                })) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<VisualizationProcessRunner.ExecutionResult> pending =
                        executor.submit(() -> runner.execute(
                                "close-race",
                                "test-sleep",
                                new JSONObject().put("milliseconds", 30_000L)));
                if (!reachedGate.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Execution did not reach the deterministic start barrier");
                }
                runner.close();
                releaseGate.countDown();
                VisualizationProcessRunner.ExecutionResult result = pending.get(
                        5, TimeUnit.SECONDS);
                if (result.status() != 499
                        || starts.get() != 0
                        || runner.activeJobCount() != 0
                        || runner.availableCapacity() != 1) {
                    throw new AssertionError(
                            "Close/start ordering leaked a process or capacity: " + result.body());
                }
            } finally {
                releaseGate.countDown();
                executor.shutdownNow();
            }
        }
    }

    private static void firstTerminalCauseDeterminesResponse() {
        VisualizationProcessRunner.TerminalCauseLatch cancelledFirst =
                new VisualizationProcessRunner.TerminalCauseLatch();
        assertCause(
                cancelledFirst.cancel(),
                VisualizationProcessRunner.TerminalCause.CANCELLED,
                "Cancellation did not win its transition");
        assertCause(
                cancelledFirst.timeout(),
                VisualizationProcessRunner.TerminalCause.CANCELLED,
                "A later timeout replaced cancellation");
        VisualizationProcessRunner.ExecutionResult cancelled =
                VisualizationProcessRunner.terminalResult(cancelledFirst.cause());
        if (cancelled.status() != 499
                || !"request_cancelled".equals(cancelled.body().getString("code"))) {
            throw new AssertionError("Cancellation-first race did not map to HTTP 499");
        }

        VisualizationProcessRunner.TerminalCauseLatch timedOutFirst =
                new VisualizationProcessRunner.TerminalCauseLatch();
        assertCause(
                timedOutFirst.timeout(),
                VisualizationProcessRunner.TerminalCause.TIMED_OUT,
                "Timeout did not win its transition");
        assertCause(
                timedOutFirst.cancel(),
                VisualizationProcessRunner.TerminalCause.TIMED_OUT,
                "A later cancellation replaced timeout");
        VisualizationProcessRunner.ExecutionResult timedOut =
                VisualizationProcessRunner.terminalResult(timedOutFirst.cause());
        if (timedOut.status() != 504
                || !"analysis_timeout".equals(timedOut.body().getString("code"))) {
            throw new AssertionError("Timeout-first race did not map to HTTP 504");
        }
    }

    private static void serverCancellationCauseIsClosed() {
        assertCause(
                VisualizationServer.cancellationCause(
                        new JSONObject().put("cause", "cancelled")),
                VisualizationProcessRunner.TerminalCause.CANCELLED,
                "Server did not parse cancellation");
        assertCause(
                VisualizationServer.cancellationCause(
                        new JSONObject().put("cause", "timeout")),
                VisualizationProcessRunner.TerminalCause.TIMED_OUT,
                "Server did not parse timeout");
        try {
            VisualizationServer.cancellationCause(
                    new JSONObject().put("cause", " timed_out "));
            throw new AssertionError("Server accepted an open cancellation cause");
        } catch (VisualizationServer.HttpFailure expected) {
            // Closed enum rejects aliases and whitespace changes.
        }
    }

    private static void assertCause(
            VisualizationProcessRunner.TerminalCause actual,
            VisualizationProcessRunner.TerminalCause expected,
            String message) {
        if (actual != expected) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertSuccessfulFollowUp(
            VisualizationProcessRunner runner,
            String requestId) throws Exception {
        VisualizationProcessRunner.ExecutionResult next = runner.execute(
                requestId,
                "test-sleep",
                new JSONObject().put("milliseconds", 0L));
        if (next.status() != 200 || runner.activeJobCount() != 0) {
            throw new AssertionError("Worker capacity was not reusable: " + next.body());
        }
    }
}

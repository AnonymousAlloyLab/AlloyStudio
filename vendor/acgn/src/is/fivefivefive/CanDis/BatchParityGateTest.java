package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** No solver or corpus run: exercise fatal classification, persistence and cancellation. */
public final class BatchParityGateTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("batch-parity-gate-test-");
        try {
            Path source = root.resolve("pair.als");
            Files.writeString(source, "sig A {}\npred lhs { some A }\npred rhs { no A }\n");
            Path mismatch = Files.createDirectory(root.resolve("distance"));
            BatchParityGate gate = new BatchParityGate(mismatch);
            expectViolation(() -> gate.requireParity(source, "lhs", "rhs",
                    new int[]{1, 0, 0, 1}, new int[]{2, 0, 0, 2}));
            JSONObject report = new JSONObject(Files.readString(mismatch.resolve("parity-incident.json")));
            require(report.getString("reason").equals("DISTANCE_MISMATCH"));
            require(report.getString("sourceSha256").length() == 64);
            require(report.getString("sourceText").equals(Files.readString(source)));
            expectViolation(gate::checkRunning);
            String first = report.toString();
            expectViolation(() -> gate.requireParity(source, "other", "other",
                    new int[]{3, 0, 0, 3}, new int[]{3, 0, 0, 3}));
            require(first.equals(new JSONObject(Files.readString(mismatch.resolve("parity-incident.json"))).toString()));
            try {
                new BatchParityGate(mismatch);
                throw new AssertionError("Existing incident overwritten");
            } catch (IllegalStateException expected) {
                // Existing evidence is protected.
            }
            Path absent = Files.createDirectory(root.resolve("missing-plan"));
            expectViolation(() -> new BatchParityGate(absent).requireParity(source, "lhs", "rhs",
                    new int[]{0, 0, 0, 0}, new int[]{0, 0, 0, 0}));
            require(new JSONObject(Files.readString(absent.resolve("parity-incident.json")))
                    .getString("reason").equals("REPLAY_WITNESS_UNAVAILABLE"));
            Path component = Files.createDirectory(root.resolve("components"));
            expectViolation(() -> new BatchParityGate(component).requireParity(source, "lhs", "rhs",
                    new int[]{2, 0, 1, 1}, new int[]{2, 1, 0, 1}));
            require(new JSONObject(Files.readString(component.resolve("parity-incident.json")))
                    .getString("reason").equals("DISTANCE_MISMATCH"));
            Path diagnostic = Files.createDirectory(root.resolve("diagnostic"));
            BatchParityGate diagnosticGate = new BatchParityGate(diagnostic, BatchParityGate.Mode.DIAGNOSTIC_COMPONENTS);
            diagnosticGate.requireParity(source, "lhs", "rhs", new int[]{1,0,0,1}, new int[]{1,0,0,1});
            diagnosticGate.checkRunning();
            require(!Files.exists(diagnostic.resolve("parity-incident.json")));
            expectViolation(() -> diagnosticGate.requireParity(source, "lhs", "rhs",
                    new int[]{2,0,1,1}, new int[]{2,1,0,1}));
            JSONObject diagnosticIncident = new JSONObject(Files.readString(diagnostic.resolve("parity-incident.json")));
            require(diagnosticIncident.getString("scanMode").equals("DIAGNOSTIC_COMPONENTS"));
            require(diagnosticIncident.getString("certificationStatus").equals("BLOCKED"));
            require(!diagnosticIncident.getBoolean("publicationAllowed"));
            expectViolation(diagnosticGate::checkRunning);
            for (String reason : new String[]{"INCORRECT_ZERO", "INEXACT_SEARCH"}) {
                Path target = Files.createDirectory(root.resolve(reason));
                BatchParityGate safety = new BatchParityGate(target, BatchParityGate.Mode.DIAGNOSTIC_COMPONENTS);
                expectViolation(() -> safety.requireSoundObservation(source,"lhs","rhs",
                        new int[]{0,0,0,0},new int[]{0,0,0,0},reason.equals("INCORRECT_ZERO"),
                        !reason.equals("INEXACT_SEARCH"),null));
                require(new JSONObject(Files.readString(target.resolve("parity-incident.json")))
                        .getString("reason").equals(reason));
                expectViolation(safety::checkRunning);
            }
            Path raced = Files.createDirectory(root.resolve("race"));
            BatchParityGate shared = new BatchParityGate(raced);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger failures = new AtomicInteger();
            Thread[] workers = new Thread[8];
            for (int i = 0; i < workers.length; i++) {
                final String left = "lhs" + i;
                workers[i] = new Thread(() -> {
                    try {
                        start.await();
                        shared.requireParity(source, left, "rhs", new int[]{1, 0, 0, 1}, new int[]{2, 0, 0, 2});
                    } catch (BatchParityGate.Violation expected) {
                        failures.incrementAndGet();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                });
                workers[i].start();
            }
            start.countDown();
            for (Thread worker : workers) worker.join(5000);
            require(failures.get() == 8);
            new JSONObject(Files.readString(raced.resolve("parity-incident.json")));
            Path replay = Files.createDirectory(root.resolve("replay"));
            BatchParityGate replayGate = new BatchParityGate(replay);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger captures = new AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            Thread capturing = new Thread(() -> {
                try {
                    replayGate.requireParity(source, "lhs", "rhs", new int[]{1,0,0,1}, new int[]{2,0,0,2}, () -> {
                        captures.incrementAndGet();
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Replay wait timed out");
                        } catch (InterruptedException error) { throw new IllegalStateException(error); }
                        return new JSONObject().put("nativeOnly", true);
                    });
                    unexpected.set(new AssertionError("Native diagnostics incorrectly granted parity"));
                } catch (BatchParityGate.Violation expected) {
                    // Native replay is evidence only, never a permit to continue.
                } catch (Throwable error) { unexpected.set(error); }
            });
            capturing.start();
            try {
                require(entered.await(5, TimeUnit.SECONDS));
                require(Files.exists(replay.resolve("parity-incident.json")));
                expectViolation(replayGate::checkRunning);
                expectViolation(() -> replayGate.requireParity(source, "later", "rhs",
                        new int[]{1,0,0,1}, new int[]{1,0,0,1}, () -> { captures.incrementAndGet(); return null; }));
            } finally { release.countDown(); capturing.join(5000); }
            require(!capturing.isAlive() && unexpected.get() == null && captures.get() == 1);
            JSONObject nativeEvidence = new JSONObject(Files.readString(replay.resolve("parity-native-replay.json")));
            require(nativeEvidence.getString("captureStatus").equals("CAPTURED"));
            require(nativeEvidence.getJSONObject("diagnostic").getBoolean("nativeOnly"));
            require(!nativeEvidence.getBoolean("complete") && !nativeEvidence.getBoolean("parityAuthority"));
            for (String mode : new String[]{"throws", "null", "source-changed"}) {
                Path failureDir = Files.createDirectory(root.resolve(mode));
                BatchParityGate failed = new BatchParityGate(failureDir);
                String original = Files.readString(source);
                expectViolation(() -> failed.requireParity(source, "lhs", "rhs",
                        new int[]{1,0,0,1}, new int[]{1,0,0,1}, () -> {
                    if (mode.equals("throws")) throw new IllegalArgumentException("Bounded replay refused");
                    if (mode.equals("source-changed")) try { Files.writeString(source, "changed"); }
                        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    return null;
                }));
                JSONObject diagnosticFailure = new JSONObject(Files.readString(failureDir.resolve("parity-native-replay.json")));
                require(diagnosticFailure.getString("captureStatus").equals("FAILED"));
                require(!diagnosticFailure.has("diagnostic"));
                require(new JSONObject(Files.readString(failureDir.resolve("parity-incident.json")))
                        .getString("sourceText").equals(original));
                expectViolation(failed::checkRunning);
                Files.writeString(source, original);
            }
            BatchParityGate unwritable = new BatchParityGate(root.resolve("nonexistent"));
            expectViolation(() -> unwritable.requireParity(source, "lhs", "rhs",
                    new int[]{1, 0, 0, 1}, new int[]{2, 0, 0, 2}));
            expectViolation(unwritable::checkRunning);
            System.out.println("BatchParityGateTest: PASS (fatal mismatch, missing replay, components, first witness, race, persistence failure)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw new AssertionError("Parity gate test failed");
    }

    private static void expectViolation(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Violation swallowed");
        } catch (BatchParityGate.Violation expected) {
            // The caller must not retry a semantic/replay incident.
        }
    }
}

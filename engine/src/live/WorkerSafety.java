package live;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.ErrorFatal;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.core.EGraphNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Worker-only parser ownership and poison boundary; one-shot behavior is retained. */
final class WorkerSafety {
    private static final ThreadLocal<Path> JOB = new ThreadLocal<>();
    private static int parses;
    private static long sourceBytes;
    private WorkerSafety() { }

    static void begin(Path directory) {
        if (JOB.get() != null) throw new IllegalStateException();
        parses = 0;
        sourceBytes = 0;
        JOB.set(directory);
    }

    static int end() {
        // IR compilation already releases its arena in finally. Remove even the
        // empty replacement arena at this sequential request ownership boundary.
        EGraphNode.endGraph();
        JOB.remove();
        return parses;
    }

    static CompModule parse(String source) {
        // The external Alloy parser is a separately bounded boundary: its input
        // is capped upstream, and its size is charged before any parse work.
        is.fivefivefive.CanDis.WorkBudget.charge(source.length());
        Path directory = JOB.get();
        if (directory == null) return CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        // This is exactly CompUtil.parseEverything_fromString's parser path,
        // but supplies an owned file to flushModelToFile: no deleteOnExit entry.
        // Files remain until all parser/source-location users finish the job.
        try {
            sourceBytes += source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (sourceBytes > 67_108_864L) throw new ErrorFatal("Owned parser storage limit reached");
            Path file = directory.resolve("source-" + (++parses) + ".als");
            CompUtil.flushModelToFile(source, file.toFile());
            return CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, file.toString());
        } catch (IOException error) {
            throw new ErrorFatal("Owned parser storage unavailable", error);
        }
    }

    static void rethrowFatal(Throwable error) {
        if (JOB.get() == null) return;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        for (int depth = 0; current != null && depth < 64 && seen.add(current); depth++) {
            // Alloy wraps VM failures in ErrorFatal. An ErrorFatal or any JVM
            // Error is never an ordinary reusable unsupported learner result.
            if (current instanceof Error || current instanceof ErrorFatal)
                throw new PoisonedWorker();
            current = current.getCause();
        }
    }

    private static final class PoisonedWorker extends Error { }
}

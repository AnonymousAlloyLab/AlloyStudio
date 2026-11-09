package is.fivefivefive.ACGN.learn;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import is.fivefivefive.CanDis.augmentation.AlloyEquivalenceValidator.ExecutionOptions;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kodkod.engine.satlab.SATFactory;

/** Run in a fresh JVM: concurrent rewards must not discover unrelated solvers. */
public final class RewarderConcurrencyTest {
    public static void main(String[] args) throws Exception {
        if (!SATFactory.DEFAULT.id().equals("sat4j")
                || SATFactory.DEFAULT.getLibraries().length != 0) {
            throw new AssertionError("Reward solver must remain pure-Java SAT4J");
        }
        AtomicInteger probes = new AtomicInteger();
        SATFactory sentinel = new SATFactory() {
            @Override public String id() { return "000-unrelated-discovery-sentinel"; }
            @Override public String type() { return "test"; }
            @Override public boolean isPresent() {
                probes.incrementAndGet();
                throw new AssertionError("Rewarder probed an unrelated solver");
            }
        };
        SATFactory.extensions.add(sentinel);
        var executor = Executors.newFixedThreadPool(16);
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        ArrayList<Future<Void>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) {
                final boolean temporal = i % 2 == 0;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new AssertionError("Start barrier timed out");
                    }
                    var options = new A4Options();
                    options.solver = SATFactory.DEFAULT;
                    if (!ExecutionOptions.from(options).solverId().equals("sat4j")) {
                        throw new AssertionError("Semantic validation changed solver identity");
                    }
                    var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                            temporal
                            ? "sig A {} var sig Live in A {} pred oracle { always some Live }"
                            : "sig A {} pred oracle { some A }");
                    var pools = Rewarder.instances(module, "oracle", 2);
                    double same = Rewarder.computeReward(module, pools,
                            "oracle", "same", "oracle", 2);
                    double opposite = Rewarder.computeReward(module, pools,
                            "oracle", "opposite", "not oracle[]", 2);
                    if (same != 1.0 || opposite != 0.0) {
                        throw new AssertionError("Reward changed: " + same + ", " + opposite);
                    }
                    return null;
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) throw new AssertionError("Workers not ready");
            start.countDown();
            for (Future<Void> task : tasks) task.get(120, TimeUnit.SECONDS);
            if (probes.get() != 0) throw new AssertionError("Global solver discovery occurred");
        } finally {
            start.countDown();
            executor.shutdownNow();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Reward workers did not terminate");
            }
            SATFactory.extensions.remove(sentinel);
        }
        System.out.println("RewarderConcurrencyTest: 16 workers, 32 reward assertions passed; unrelated solver probes=0");
    }
}

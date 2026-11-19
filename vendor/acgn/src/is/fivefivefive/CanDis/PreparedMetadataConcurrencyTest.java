package is.fivefivefive.CanDis;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.theory.GraphType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import parser.ast.nodes.ModelUnit;

/** Shared endpoint cache publication and serial/concurrent selected-result controls. */
public final class PreparedMetadataConcurrencyTest {
    private static int checks;
    private static final Class<?> METADATA;
    static {
        try { METADATA = Class.forName(CanonicalDistance.class.getName() + "$EGraphMetadata"); }
        catch (ClassNotFoundException error) { throw new ExceptionInInitializerError(error); }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Method method(String name) throws Exception {
        var method = METADATA.getDeclaredMethod(name, EGraphNode.class);
        method.setAccessible(true);
        return method;
    }

    private static Object emptyMetadata() throws Exception {
        var constructor = METADATA.getDeclaredConstructor(List.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(List.of(), List.of());
    }

    private static EGraphNode node(int id, List<EGraphNode> children) {
        var node = new EGraphNode(id, children.isEmpty() ? EGraphNode.Opcode.CONSTANT : EGraphNode.Opcode.NOT,
                children, false, children.size(), false, EGraphNode.Metatype.BOOLEAN);
        node.setExactAlloyType(ExactAlloyType.boolType());
        node.freezeForCertification();
        return node;
    }

    private static <T> List<T> parallel(List<Callable<T>> tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(16);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<T>>();
            for (var task : tasks) futures.add(executor.submit(task));
            var result = new ArrayList<T>();
            for (var future : futures) result.add(future.get(60, TimeUnit.SECONDS));
            return result;
        } finally {
            executor.shutdownNow();
            check(executor.awaitTermination(10, TimeUnit.SECONDS), "workers did not terminate");
        }
    }

    private static void coldCacheStress() throws Exception {
        var children = method("children");
        var size = method("size");
        var type = method("type");
        for (int round = 0; round < 12; round++) {
            Object cache = emptyMetadata();
            var nodes = new ArrayList<EGraphNode>();
            for (int i = 0; i < 2048; i++) nodes.add(node(i * 2 + 1, List.of(node(i * 2, List.of()))));
            var ready = new CountDownLatch(16);
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Callable<Integer>>();
            for (int worker = 0; worker < 16; worker++) {
                int lane = worker;
                tasks.add(() -> {
                    ready.countDown();
                    if (ready.await(10, TimeUnit.SECONDS)) start.countDown();
                    check(start.await(10, TimeUnit.SECONDS), "start barrier");
                    for (int i = lane; i < nodes.size(); i += 16) {
                        EGraphNode current = nodes.get(i);
                        Object selectedChildren = children.invoke(cache, current);
                        check(selectedChildren instanceof List<?> list && list.size() == 1,
                                "lost child metadata during concurrent insertion");
                        check(size.invoke(cache, current).equals(2), "lost subtree size");
                        check(type.invoke(cache, current) == GraphType.BOOL, "lost result type");
                    }
                    return nodes.size() / 16;
                });
            }
            checks += parallel(tasks).stream().mapToInt(Integer::intValue).sum() * 3;
            for (var current : nodes) {
                check(children.invoke(cache, current).equals(current.getChildren()), "cached child identity/order");
                check(size.invoke(cache, current).equals(2), "cached size");
                check(type.invoke(cache, current) == GraphType.BOOL, "cached type");
                checks += 3;
            }
        }
        System.out.println("cold-cache stress checks=" + checks);
    }

    private static final class LockedMap<V> extends IdentityHashMap<EGraphNode, V> {
        private final Object owner;
        LockedMap(Object owner) { this.owner = owner; }
        private void locked() { check(Thread.holdsLock(owner), "cache access outside endpoint monitor"); }
        @Override public V put(EGraphNode key, V value) { locked(); return super.put(key, value); }
        @Override public V get(Object key) { locked(); return super.get(key); }
        @Override public boolean containsKey(Object key) { locked(); return super.containsKey(key); }
        @Override public int size() { locked(); return super.size(); }
    }

    private static void lockContract() throws Exception {
        Object cache = emptyMetadata();
        for (String name : List.of("sizes", "resultTypes", "metricChildren")) {
            Field field = METADATA.getDeclaredField(name);
            field.setAccessible(true);
            field.set(cache, new LockedMap<>(cache));
        }
        var root = node(5000, List.of(node(4999, List.of())));
        for (String name : List.of("children", "size", "type")) {
            method(name).invoke(cache, root);
            method(name).invoke(cache, root);
            checks += 2;
        }
        System.out.println("monitor contract checks=6");
    }

    private static void sharedEndpoints(boolean serialOnly) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                sig A { r: set A }
                pred p0 { some A }
                pred p1 { no A }
                pred p2 { no A or (some x,y:A | x in y.r) }
                pred p3 { no A or (some y,x:A | y in x.r) }
                """);
        var prepared = new ArrayList<Canonical.Prepared>();
        for (String name : List.of("p0", "p1", "p2", "p3")) {
            var visitor = new MASGVisitor(new GlobalVariables(), Set.of(name), module);
            visitor.visit(new ModelUnit(null, module), null);
            prepared.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        var baseline = new ArrayList<JSONObject>();
        var digest = MessageDigest.getInstance("SHA-256");
        for (var a : prepared) for (var b : prepared) {
            var fast = Canonical.evaluateWithTrace(a, b);
            check(fast.distance() == Canonical.distance(a, b), "numeric/trace cost");
            check(fast.replay(fast.json()) == fast.distance(), "fixed replay");
            baseline.add(fast.json());
            digest.update((fast.distance() + ":" + fast.temporalDistance() + ":"
                    + fast.quantifierDistance() + ":" + fast.matrixDistance() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            checks += 2;
        }
        if (!serialOnly) {
            var tasks = new ArrayList<Callable<Integer>>();
            for (int iteration = 0; iteration < 8; iteration++) {
                for (int i = 0; i < baseline.size(); i++) {
                    int index = i;
                    tasks.add(() -> {
                        var trace = Canonical.evaluateWithTrace(prepared.get(index / 4), prepared.get(index % 4));
                        check(trace.json().similar(baseline.get(index)), "concurrent selected trace drift");
                        check(trace.replay(trace.json()) == trace.distance(), "concurrent fixed replay");
                        var changed = trace.json().put("distance", trace.distance() + 1);
                        boolean rejected = false;
                        try { trace.replay(changed); }
                        catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
                        check(rejected, "tampered distance accepted");
                        return 3;
                    });
                }
            }
            checks += parallel(tasks).stream().mapToInt(Integer::intValue).sum();
        }
        System.out.println("serialCostDigest=" + HexFormat.of().formatHex(digest.digest()));
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "all" : args[0];
        if (args.length > 1 || !Set.of("all", "--stress", "--locks", "--serial").contains(mode)) {
            throw new IllegalArgumentException("Expected all, --stress, --locks, or --serial");
        }
        if (mode.equals("--stress") || mode.equals("all")) coldCacheStress();
        if (mode.equals("--locks") || mode.equals("all")) lockContract();
        if (mode.equals("--serial") || mode.equals("all")) sharedEndpoints(mode.equals("--serial"));
        System.out.println("PreparedMetadataConcurrencyTest: " + checks + " checks passed");
    }
}

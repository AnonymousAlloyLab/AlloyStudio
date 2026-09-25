package is.fivefivefive.CanDis.theory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import parser.ast.nodes.ModelUnit;

/** Bounded A2-04 observations. Guard replay is not full certificate admission. */
public final class GuardedJoinChainRegressionTest {
    private static final GraphType A = GraphType.constructor("AlloySig:JoinChainA");
    private static final Comparator<List<Integer>> ORDER = (a, b) -> {
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = Integer.compare(a.get(i), b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    };
    private static final List<String> ROWS = new ArrayList<>();
    private static int checks;

    private GuardedJoinChainRegressionTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: GuardedJoinChainRegressionTest [OUTPUT.tsv]");
        checks = 0;
        ROWS.clear();
        ROWS.add("surface\tfixture\tarities\ttree\trelations\toutput\tproducer\tverifier\tequivalent");
        ReplayGuard replay = new ReplayGuard();
        for (int length = 0; length <= 5; length++) enumerateGuards(replay, length, new ArrayList<>());
        for (int length : List.of(8, 17, 64)) {
            for (int index = 0; index < length; index++) {
                List<Integer> word = new ArrayList<>(Collections.nCopies(length, 2));
                word.set(index, 1);
                guardRow(replay, "long-" + length + "-" + index, word, -1, false);
            }
        }
        for (int arity : List.of(1, 2, 3)) for (int index = 0; index < 5; index++) {
            List<Integer> word = new ArrayList<>(Collections.nCopies(5, 2));
            word.set(index, arity);
            guardRow(replay, "empty-" + arity + "-" + index, word, index, false);
        }
        guardRow(replay, "univ", List.of(1, 2, 2, 1), -1, true);
        int guards = ROWS.size() - 1;
        check(guards == 469, "frozen guard census");
        for (int length = 2; length <= 5; length++) {
            List<String> fixtures = new ArrayList<>(List.of("binary", "left-unary", "right-unary", "wide", "univ"));
            if (length >= 3) fixtures.add("both-unary");
            for (int i = 0; i < length; i++) fixtures.add("empty-" + i);
            for (String fixture : fixtures) for (int seed = 0; seed < 2; seed++)
                finiteCase(replay, length, fixture, seed);
        }
        counterexample(replay);
        int finite = ROWS.size() - 1 - guards;
        check(finite == 460, "frozen finite association census");
        parserCases();
        int parser = ROWS.size() - 1 - guards - finite;
        check(parser == 92, "frozen parser association census");
        if (args.length == 1) Files.writeString(Path.of(args[0]), String.join("\n", ROWS) + "\n", StandardCharsets.UTF_8);
        System.out.println("GuardedJoinChainRegressionTest passed: guards=" + guards + " finite=" + finite
                + " parser=" + parser + " checks=" + checks);
    }

    private static void enumerateGuards(ReplayGuard replay, int remaining, List<Integer> word) throws Exception {
        if (remaining == 0) { guardRow(replay, "word-" + csv(word), word, -1, false); return; }
        for (int a = 1; a <= 3; a++) {
            word.add(a); enumerateGuards(replay, remaining - 1, word); word.remove(word.size() - 1);
        }
    }

    private static boolean oracle(List<Integer> word) {
        return word.size() >= 2 && word.subList(1, word.size() - 1).stream().allMatch(n -> n >= 2);
    }

    private static List<GraphType> types(List<Integer> arities, int empty, boolean univ) {
        List<GraphType> result = new ArrayList<>();
        for (int i = 0; i < arities.size(); i++) result.add(i == empty ? AlloyTypeBridge.emptyRelation(arities.get(i))
                : GraphType.relation(Collections.nCopies(arities.get(i), univ ? GraphType.constructor("AlloySig:univ") : A)));
        return List.copyOf(result);
    }

    private static boolean producer(List<GraphType> types) {
        try { DependentChainTheory.requireSoundFlattening(DependentChainKind.JOIN, types); return true; }
        catch (DependentChainTheory.UnsupportedFlattening e) { return false; }
        catch (IllegalArgumentException e) {
            if (types.size() < 2) return false;
            throw e;
        }
    }

    private static void guardRow(ReplayGuard replay, String fixture, List<Integer> word, int empty, boolean univ) throws Exception {
        List<GraphType> types = types(word, empty, univ);
        boolean p = producer(types), v = replay.accepts(word, empty, univ);
        check(p == oracle(word) && v == oracle(word), "real guards: " + fixture);
        ROWS.add(String.join("\t", "guard", fixture, csv(word), "-", "-", "-", "" + p, "" + v, "-"));
    }

    private record Tree(int index, Tree left, Tree right) {
        static Tree leaf(int i) { return new Tree(i, null, null); }
        static Tree app(Tree l, Tree r) { return new Tree(-1, l, r); }
        @Override public String toString() { return index >= 0 ? "" + index : "(" + left + "," + right + ")"; }
        String alloy(List<String> leaves) { return index >= 0 ? leaves.get(index) : "(" + left.alloy(leaves) + "." + right.alloy(leaves) + ")"; }
        DependentChainInput source(List<DependentChainLeaf> leaves) {
            return index >= 0 ? leaves.get(index) : new DependentChainApplication(DependentChainKind.JOIN, left.source(leaves), right.source(leaves));
        }
        Set<List<Integer>> eval(List<List<List<Integer>>> relations) {
            if (index >= 0) { Set<List<Integer>> result = new TreeSet<>(ORDER); result.addAll(relations.get(index)); return result; }
            return join(left.eval(relations), right.eval(relations));
        }
    }

    private static List<Tree> associations(int start, int length) {
        if (length == 1) return List.of(Tree.leaf(start));
        List<Tree> result = new ArrayList<>();
        for (int split = 1; split < length; split++) for (Tree l : associations(start, split))
            for (Tree r : associations(start + split, length - split)) result.add(Tree.app(l, r));
        return result;
    }

    private static Set<List<Integer>> join(Set<List<Integer>> left, Set<List<Integer>> right) {
        Set<List<Integer>> result = new TreeSet<>(ORDER);
        for (List<Integer> l : left) for (List<Integer> r : right) {
            if (l.isEmpty() || r.isEmpty() || !l.get(l.size() - 1).equals(r.get(0))) continue;
            List<Integer> row = new ArrayList<>(l.subList(0, l.size() - 1));
            row.addAll(r.subList(1, r.size())); result.add(List.copyOf(row));
        }
        return result;
    }

    // Independent n-way witness enumeration: no tree evaluator or pairwise JOIN.
    private static Set<List<Integer>> ordered(List<List<List<Integer>>> relations) {
        Set<List<Integer>> result = new TreeSet<>(ORDER);
        witnesses(relations, new ArrayList<>(), result);
        return result;
    }

    private static void witnesses(List<List<List<Integer>>> relations, List<List<Integer>> chosen, Set<List<Integer>> output) {
        int i = chosen.size();
        if (i < relations.size()) {
            for (List<Integer> row : relations.get(i)) {
                if (i > 0 && !chosen.get(i - 1).get(chosen.get(i - 1).size() - 1).equals(row.get(0))) continue;
                chosen.add(row); witnesses(relations, chosen, output); chosen.remove(chosen.size() - 1);
            }
            return;
        }
        List<Integer> row = new ArrayList<>();
        for (int j = 0; j < chosen.size(); j++) {
            List<Integer> part = chosen.get(j);
            row.addAll(part.subList(j == 0 ? 0 : 1, part.size() - (j + 1 == chosen.size() ? 0 : 1)));
        }
        output.add(List.copyOf(row));
    }

    private static void finiteCase(ReplayGuard replay, int n, String fixture, int seed) throws Exception {
        List<Integer> arities = new ArrayList<>(Collections.nCopies(n, fixture.equals("wide") ? 3 : 2));
        if (fixture.equals("left-unary") || fixture.equals("both-unary")) arities.set(0, 1);
        if (fixture.equals("right-unary") || fixture.equals("both-unary")) arities.set(n - 1, 1);
        if (fixture.equals("both-unary")) arities.set(1, 3);
        int empty = fixture.startsWith("empty-") ? Integer.parseInt(fixture.substring(6)) : -1;
        List<GraphType> types = types(arities, empty, fixture.equals("univ"));
        List<List<List<Integer>>> relations = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<List<Integer>> tuples = new ArrayList<>();
            if (i != empty) for (int bits = 0; bits < (1 << arities.get(i)); bits++) {
                if ((bits + seed + i) % 3 != 0) continue;
                List<Integer> tuple = new ArrayList<>();
                for (int c = arities.get(i) - 1; c >= 0; c--) tuple.add((bits >> c) & 1);
                tuples.add(List.copyOf(tuple));
            }
            relations.add(List.copyOf(tuples));
        }
        boolean p = producer(types), v = replay.accepts(arities, empty, fixture.equals("univ"));
        check(p && v, "admitted finite fixture");
        List<TypedSlot> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) slots.add(TypedSlot.source(types.get(i), 93000 + i));
        TypedSlotContext context = TypedSlotContext.of(slots);
        List<DependentChainLeaf> leaves = slots.stream().map(s -> new DependentChainLeaf(OnePort.slot(context, s))).toList();
        Set<List<Integer>> expected = ordered(relations);
        List<StructuralKey> keys = new ArrayList<>(Collections.nCopies(2, null));
        for (Tree tree : associations(0, n)) {
            Set<List<Integer>> actual = tree.eval(relations);
            check(actual.equals(expected), "finite relational equality: " + fixture + " " + tree);
            DependentChainApplication source = (DependentChainApplication) tree.source(leaves);
            int profileIndex = 0;
            for (SemanticProfile profile : List.of(SemanticProfile.alloyOverflowForbidding(), SemanticProfile.alloyModular())) {
                CertifiedDependentChainConstruction built = TypedENode.constructDependentChainCertified(source, profile);
                CertificateVerifier.verify(built.certificate());
                StructuralKey key = built.node().structuralKey();
                check(keys.get(profileIndex) == null || keys.get(profileIndex).equals(key), "real typed construction reassociation");
                keys.set(profileIndex++, key);
                check(AlloyTypeBridge.relationArity(source.outputType()) == arities.stream().mapToInt(Integer::intValue).sum() - 2 * (n - 1), "exact retained result arity");
            }
            ROWS.add(String.join("\t", "finite", n + ":" + fixture + ":" + seed, csv(arities), tree.toString(),
                    compact(relations), compact(new ArrayList<>(actual)), "" + p, "" + v, "true"));
        }
    }

    private static void counterexample(ReplayGuard replay) throws Exception {
        List<Integer> arities = List.of(2, 1, 2);
        List<List<List<Integer>>> relations = List.of(List.of(List.of(0, 1)), List.of(List.of(1)), List.of(List.of(0, 0)));
        List<Tree> trees = associations(0, 3);
        check(!trees.get(0).eval(relations).equals(trees.get(1).eval(relations)), "retained unary counterexample");
        boolean p = producer(types(arities, -1, false)), v = replay.accepts(arities, -1, false);
        check(!p && !v, "unary counterexample has no flattening license");
        for (Tree tree : trees) ROWS.add(String.join("\t", "counter", "unary", csv(arities), tree.toString(),
                compact(relations), compact(new ArrayList<>(tree.eval(relations))), "" + p, "" + v, "false"));
    }

    private static void parserCases() throws Exception {
        for (String fixture : List.of("binary3", "binary4", "binary5", "left", "right", "both", "empty", "univ")) {
            int n = fixture.equals("binary3") ? 3 : fixture.equals("binary5") ? 5 : 4;
            List<String> leaves = new ArrayList<>();
            for (int i = 0; i < n; i++) leaves.add(i % 2 == 0 ? "r" : "s");
            List<Integer> arities = new ArrayList<>(Collections.nCopies(n, 2));
            if (fixture.equals("left") || fixture.equals("both")) { leaves.set(0, "A"); arities.set(0, 1); }
            if (fixture.equals("right") || fixture.equals("both")) { leaves.set(n - 1, "A"); arities.set(n - 1, 1); }
            if (fixture.equals("both")) { leaves.set(1, "t"); arities.set(1, 3); }
            if (fixture.equals("empty")) leaves.set(1, "(none->none)");
            if (fixture.equals("univ")) leaves.set(1, "(univ->univ)");
            List<Tree> trees = associations(0, n);
            int resultArity = arities.stream().mapToInt(Integer::intValue).sum() - 2 * (n - 1);
            String resultType = String.join("->", Collections.nCopies(resultArity, fixture.equals("univ") ? "univ" : "A"));
            StringBuilder source = new StringBuilder("module guarded_join_chain\nsig A { r, s: set A, t: A->A }\n");
            for (int i = 0; i < trees.size(); i++) source.append("fun F").append(i).append("[]: ").append(resultType)
                    .append(" { ").append(trees.get(i).alloy(leaves)).append(" }\n");
            CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source.toString());
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(new ModelUnit(null, module), null);
            for (SemanticProfile profile : List.of(SemanticProfile.alloyOverflowForbidding(), SemanticProfile.alloyModular())) {
                CanonicalAlloyPipeline.Prepared first = null;
                String mode = profile.equals(SemanticProfile.alloyModular()) ? "MODULAR" : "FORBID";
                for (int i = 0; i < trees.size(); i++) {
                    CanonicalAlloyPipeline.Prepared prepared = CanonicalAlloyPipeline.prepare(visitor.getForest().get(visitor.getForestId("F" + i)), profile);
                    if (first == null) first = prepared;
                    boolean equal = first.equivalentTo(prepared);
                    check(equal, "parser-backed reassociation: " + fixture + " " + trees.get(i));
                    ROWS.add(String.join("\t", "parser", fixture + ":" + mode, csv(arities), trees.get(i).toString(),
                            "-", "-", "-", "-", "" + equal));
                }
            }
        }
    }

    // Reflective construction uses ordinary constructors, never allocation bypass.
    // Only this instance guard is invoked: its null replay ledgers confer no authority.
    private static final class ReplayGuard {
        private final Object replay;
        private final Object join;
        private final Method guard;
        private final Constructor<?> exact;
        private final Constructor<?> key;
        private final Class<?> kind;

        ReplayGuard() throws Exception {
            String owner = "org.acgn.cert.SemanticEvidenceVerifier";
            Class<?> replayClass = Class.forName(owner + "$SemanticReplay");
            Constructor<?> constructor = replayClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            replay = constructor.newInstance(null, null, null, null, false);
            Class<?> chainKind = Class.forName(owner + "$SemanticReplay$ChainKind");
            join = enumValue(chainKind, "JOIN");
            guard = replayClass.getDeclaredMethod("requireSoundDependentFlattening", chainKind, List.class);
            guard.setAccessible(true);
            kind = Class.forName(owner + "$TypeKind");
            Class<?> exactClass = Class.forName(owner + "$ExactType"), keyClass = Class.forName(owner + "$StableKey");
            exact = exactClass.getDeclaredConstructor(String.class, kind, String.class, List.class, keyClass, String.class);
            key = keyClass.getDeclaredConstructor(String.class, List.class, List.class);
            exact.setAccessible(true); key.setAccessible(true);
        }

        private static Object enumValue(Class<?> type, String name) {
            for (Object value : type.getEnumConstants()) if (((Enum<?>) value).name().equals(name)) return value;
            throw new AssertionError("Missing enum " + name);
        }

        private Object type(String tag, String symbol, List<Object> args) throws Exception {
            Object stable = key.newInstance("guard-test", List.of(tag, symbol, "" + args.size()), List.of());
            return exact.newInstance("guard-test", enumValue(kind, tag), symbol, args, stable, symbol);
        }

        boolean accepts(List<Integer> arities, int empty, boolean univ) throws Exception {
            List<Object> types = new ArrayList<>();
            Object column = type("CONSTRUCTOR", univ ? "AlloySig:univ" : "AlloySig:JoinChainA", List.of());
            for (int i = 0; i < arities.size(); i++) types.add(i == empty
                    ? type("CONSTRUCTOR", "AlloyEmptyRelation$arity=" + arities.get(i), List.of())
                    : type("RELATION", "", Collections.nCopies(arities.get(i), column)));
            try { guard.invoke(replay, join, types); return true; }
            catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (!cause.getClass().getName().equals("org.acgn.cert.FormatException")) throw failure;
                return false;
            }
        }
    }

    private static String compact(Object value) { return value.toString().replace(" ", ""); }
    private static String csv(List<Integer> values) { return values.stream().map(Object::toString).collect(Collectors.joining(",")); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
}

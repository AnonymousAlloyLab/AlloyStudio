package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.alloy.SigSymbol;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.egraph.AblationEngine;
import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import is.fivefivefive.CanDis.core.egraph.JavaEgglog;
import is.fivefivefive.CanDis.core.egraph.JavaEgglogDeBruijn;
import is.fivefivefive.CanDis.core.egraph.RawDeBruijnEGraph;
import is.fivefivefive.CanDis.core.egraph.RawEGraph;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;
import is.fivefivefive.CanDis.theory.AlloySemanticProfileFactory;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;

/**
 * P5-15: finite source conformance, not universal Java/Lean refinement or
 * exhaustive implementation coverage. The parent ledger and frozen evidence
 * remain unchanged. Optional TSV export contains only observations from this
 * run; it is not a closure manifest or a Lean replay certificate.
 */
public final class BuiltinIdentityRegressionTest {
    private static final String DECLARATIONS = "sig A, None, Univ, NoneNear, UnivNear {}\n";
    private static final List<String> SCOPES = List.of(
            "for 0 but 0 Int",
            "for 1 but 0 Int, exactly 1 A, exactly 0 None, exactly 0 Univ,"
                    + " exactly 0 NoneNear, exactly 0 UnivNear",
            "for 2 but 0 Int, exactly 2 A, exactly 1 None, exactly 1 Univ,"
                    + " exactly 1 NoneNear, exactly 1 UnivNear");
    private static int checks;
    private static int solverCalls;

    // SAT vectors are in SCOPES order. Null Boolean means the normal form
    // must remain symbolic, even when a particular command fixes its truth.
    private record Fixture(String id, String left, String right, String head,
            String leaf, Boolean folded, boolean equivalent, String leftSat, String rightSat) { }

    private record Prepared(Canonical.Prepared fast, CanonicalAlloyPipeline.Prepared certified) { }
    private record Comparison(boolean fastEquivalent, boolean certifiedEquivalent) { }

    private BuiltinIdentityRegressionTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected at most one TSV output path");
        }
        checks = solverCalls = 0;
        List<Fixture> fixtures = fixtures();
        List<String> rows = new ArrayList<>();
        rows.add(header());
        check(fixtures.stream().filter(Fixture::equivalent).count() == 8, "eight positive fixtures");
        check(fixtures.stream().filter(f -> !f.equivalent()).count() == 4, "four negative controls");
        for (Fixture fixture : fixtures) {
            exercise(fixture, rows);
        }
        check(rows.size() == 37, "36 observed fixture/scope rows plus header");
        if (args.length == 1) {
            Files.write(Path.of(args[0]), rows, StandardCharsets.UTF_8);
        }
        System.out.println("BuiltinIdentityRegressionTest passed: positives=8 negatives=4"
                + " scopes=3 engines=Fast,certified,Raw,RawDeBruijn,JavaEgglog,JavaEgglogDeBruijn,Slotted"
                + " solverCalls=" + solverCalls + " checks=" + checks);
    }

    private static List<Fixture> fixtures() {
        return List.of(
                new Fixture("some-none", "some none", "not (no none)", "UF/SOME", "none",
                        false, true, "000", "000"),
                new Fixture("no-none", "no none", "not (some none)", "UF/NO", "none",
                        true, true, "111", "111"),
                new Fixture("one-none", "one none", "some none", "UF/ONE", "none",
                        false, true, "000", "000"),
                new Fixture("lone-none", "lone none", "no none", "UF/LONE", "none",
                        true, true, "111", "111"),
                new Fixture("none-union", "some (none + A)", "some A", "BE/PLUS", "none",
                        null, true, "011", "011"),
                new Fixture("univ-membership", "A in univ", "no none", "BF/IN", "univ",
                        true, true, "111", "111"),
                new Fixture("some-univ", "some univ", "not (no univ)", "UF/SOME", "univ",
                        null, true, "011", "011"),
                new Fixture("no-univ", "no univ", "not (some univ)", "UF/NO", "univ",
                        null, true, "100", "100"),
                new Fixture("user-None", "some None", "some none", "UF/SOME", "None",
                        null, false, "001", "000"),
                new Fixture("user-Univ", "some Univ", "some univ", "UF/SOME", "Univ",
                        null, false, "001", "011"),
                new Fixture("near-None", "some NoneNear", "some none", "UF/SOME", "NoneNear",
                        null, false, "001", "000"),
                new Fixture("near-Univ", "some UnivNear", "some univ", "UF/SOME", "UnivNear",
                        null, false, "001", "011"));
    }

    private static void exercise(Fixture fixture, List<String> rows) throws Exception {
        String source = "module builtin_identity\n" + DECLARATIONS
                + "pred Target { " + fixture.left() + " }\n"
                + "pred Reference { " + fixture.right() + " }\n"
                + "pred Truth { no none }\npred Falsehood { some none }\n";
        CompModule module = parse(source + String.join("\n",
                SCOPES.stream().map(scope -> "run Target " + scope).toList()));
        ModelUnit model = new ModelUnit(null, module);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(model, null);
        AlloyTerm left = term(model, "Target");
        AlloyTerm right = term(model, "Reference");
        AlloyTerm truth = term(model, "Truth");
        AlloyTerm falsehood = term(model, "Falsehood");
        String operator = actualOperator(left, fixture.head());
        check(operator != null, fixture.id() + ": actual parser operator " + fixture.head());
        SigSymbol sourceSymbol = checkSourceIdentity(fixture, left, graph(visitor, "Target"));

        List<Boolean> baselineResults = new ArrayList<>();
        for (AblationEngine engine : List.of(new RawEGraph(), new RawDeBruijnEGraph(),
                new JavaEgglog(), new JavaEgglogDeBruijn(), new SlottedEGraph())) {
            String context = fixture.id() + ": " + engine.getClass().getSimpleName();
            AblationEngine.Result result = engine.compare(left, right);
            checkComparison(result, fixture.equivalent(), context);
            baselineResults.add(result.equivalent);
            checkComparison(engine.compare(left, truth), Boolean.TRUE.equals(fixture.folded()), context + " truth");
            checkComparison(engine.compare(left, falsehood), Boolean.FALSE.equals(fixture.folded()), context + " false");
        }

        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        boolean witnessedDifference = false;
        check(module.getAllCommands().size() == SCOPES.size(), "one source command per scope");
        for (int scope = 0; scope < SCOPES.size(); scope++) {
            SemanticProfile profile = AlloySemanticProfileFactory.fromExactlyOne(
                    module, List.of(module.getAllCommands().get(scope)), options);
            Prepared target = prepare(visitor, "Target", profile);
            Prepared reference = prepare(visitor, "Reference", profile);
            Prepared trueForm = prepare(visitor, "Truth", profile);
            Prepared falseForm = prepare(visitor, "Falsehood", profile);
            String context = fixture.id() + ": scope " + scope;
            Comparison comparison = compare(target, reference, fixture.equivalent(), context);
            compare(target, trueForm, Boolean.TRUE.equals(fixture.folded()), context + " truth");
            compare(target, falseForm, Boolean.FALSE.equals(fixture.folded()), context + " false");
            checkMatrix(target.fast(), fixture, context);
            String normalizedLeft = CanonicalBacktranslator.formula(target.fast().normalizedForms());
            String normalizedRight = CanonicalBacktranslator.formula(reference.fast().normalizedForms());
            String evaluated = source
                    + "pred NormalizedTarget { " + normalizedLeft + " }\n"
                    + "pred NormalizedReference { " + normalizedRight + " }\n"
                    + "assert TargetPreserved { Target[] iff NormalizedTarget[] }\n"
                    + "assert ReferencePreserved { Reference[] iff NormalizedReference[] }\n"
                    + "assert Pair { Target[] iff Reference[] }\n";
            List<String> commands = List.of("run Target", "run Reference", "run NormalizedTarget",
                    "run NormalizedReference", "check TargetPreserved", "check ReferencePreserved", "check Pair");
            String suffix = " " + SCOPES.get(scope) + "\n";
            CompModule replay = parse(evaluated + String.join("", commands.stream().map(c -> c + suffix).toList()));
            check(replay.getAllCommands().size() == commands.size(), context + ": solver command coverage");
            boolean leftSat = fixture.leftSat().charAt(scope) == '1';
            boolean rightSat = fixture.rightSat().charAt(scope) == '1';
            List<Boolean> expected = List.of(leftSat, rightSat, leftSat, rightSat, false, false, leftSat != rightSat);
            List<Boolean> observed = new ArrayList<>();
            for (int index = 0; index < commands.size(); index++) {
                boolean actual = solve(replay, replay.getAllCommands().get(index), options);
                observed.add(actual);
                check(actual == expected.get(index), context + ": " + commands.get(index)
                        + " expected SAT=" + expected.get(index) + " got " + actual);
                if (index == 6 && actual) {
                    witnessedDifference = true;
                }
            }
            List<Object> fields = new ArrayList<>(List.of(fixture.id(), fixture.left(), fixture.right(),
                    left.toString(), right.toString(), operator, sourceSymbol.getKind().name(),
                    sourceSymbol.getName(), sourceSymbol.getSemanticIdentity(), scope, SCOPES.get(scope),
                    comparison.fastEquivalent(), comparison.certifiedEquivalent()));
            fields.addAll(baselineResults);
            fields.add(normalizedLeft);
            fields.add(normalizedRight);
            fields.addAll(observed);
            rows.add(row(fields));
        }
        check(witnessedDifference == !fixture.equivalent(), fixture.id() + ": actual bounded distinguishing witness");
    }

    private static SigSymbol checkSourceIdentity(Fixture fixture, AlloyTerm left, Multigraph graph) {
        String name = fixture.leaf();
        boolean builtin = name.equals("none") || name.equals("univ");
        AlloyTerm expectedLeaf = AlloyTerm.atom(builtin ? "CONST" : "SIG", name);
        check(contains(left, expectedLeaf), fixture.id() + ": parser adapter retains exact leaf " + expectedLeaf);
        check(!contains(left, AlloyTerm.atom(builtin ? "SIG" : "CONST", name)),
                fixture.id() + ": no alternate adapter leaf kind");
        List<SigSymbol> symbols = graph.getVertices().stream().map(node -> node.getSymbol())
                .filter(SigSymbol.class::isInstance).map(SigSymbol.class::cast)
                .filter(symbol -> symbol.getName().equals(name)).toList();
        check(!symbols.isEmpty(), fixture.id() + ": source MASG signature reached");
        SigSymbol.Kind kind = name.equals("none") ? SigSymbol.Kind.BUILTIN_NONE
                : name.equals("univ") ? SigSymbol.Kind.BUILTIN_UNIV : SigSymbol.Kind.USER;
        String identity = identity(name);
        for (SigSymbol symbol : symbols) {
            check(symbol.getKind() == kind && symbol.getType().equals("Signature")
                    && symbol.getSemanticIdentity().equals(identity), fixture.id() + ": nominal MASG identity");
            check(builtin || symbol.hasParserSignatureAuthority(), fixture.id() + ": user declaration authority");
        }
        return symbols.get(0);
    }

    private static void checkMatrix(Canonical.Prepared prepared, Fixture fixture, String context) {
        List<NormalForm> forms = prepared.normalizedForms();
        check(forms.size() == 1, context + ": one non-temporal phase");
        NormalForm form = forms.get(0);
        check(form.getMatrixEGraph() != null && form.getCertificationMatrixEGraph() != null,
                context + ": both IR matrices reached");
        for (EGraphNode root : List.of(form.getMatrixEGraph(), form.getCertificationMatrixEGraph())) {
            if (fixture.folded() != null) {
                check(root.getOpcode() == Opcode.PREDICATE && root.getChildren().size() == 1,
                        context + ": parameter-free predicate wrapper");
                EGraphNode body = root.getChildren().get(0);
                check(body.getOpcode() == Opcode.CONSTANT
                        && fixture.folded().toString().equals(body.getSourceName())
                        && body.getMetatype() == Metatype.BOOLEAN && body.getChildren().isEmpty(),
                        context + ": actual empty-cardinality Boolean opcode: " + body.getOpcode()
                                + "/" + body.getSourceName() + "/" + body.getMetatype());
            } else if (fixture.head().startsWith("UF/")) {
                List<EGraphNode> leaves = new ArrayList<>();
                collectLeaves(root, fixture.leaf(), leaves);
                check(!leaves.isEmpty(), context + ": symbolic source signature retained");
                for (EGraphNode leaf : leaves) {
                    boolean builtin = fixture.leaf().equals("univ");
                    check(leaf.getOpcode() == Opcode.GLOBALBINDING || leaf.getOpcode() == Opcode.CONSTANT,
                            context + ": signature leaf opcode");
                    check(leaf.getSemanticIdentity().equals(identity(fixture.leaf()))
                            && leaf.getSourceType().equals("Signature")
                            && leaf.getMetatype() == (builtin ? Metatype.SET : Metatype.ATOMIC)
                            && leaf.getChildren().isEmpty(), context + ": retained IR identity and leaf shape");
                }
            }
        }
    }

    private static String identity(String name) {
        return name.equals("none") ? SigSymbol.BUILTIN_NONE_IDENTITY
                : name.equals("univ") ? SigSymbol.BUILTIN_UNIV_IDENTITY : "alloy/signature/" + name;
    }

    private static void collectLeaves(EGraphNode node, String name, List<EGraphNode> result) {
        if (name.equals(node.getSourceName())) {
            result.add(node);
        }
        for (EGraphNode child : node.getChildren()) {
            collectLeaves(child, name, result);
        }
    }

    private static boolean contains(AlloyTerm term, AlloyTerm expected) {
        return term.equals(expected) || term.children().stream().anyMatch(child -> contains(child, expected));
    }

    private static String actualOperator(AlloyTerm term, String head) {
        if (term.head().equals(head)) {
            return term.head();
        }
        for (AlloyTerm child : term.children()) {
            String found = actualOperator(child, head);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void checkComparison(AblationEngine.Result result, boolean expected, String context) {
        check(result.equivalent == expected && (result.distance == 0) == expected, context + ": baseline comparison");
    }

    private static Comparison compare(Prepared left, Prepared right, boolean expected, String context) {
        boolean fast = Canonical.distance(left.fast(), right.fast()) == 0;
        boolean certified = left.certified().equivalentTo(right.certified());
        check(fast == expected, context + ": Fast distance");
        check(certified == expected, context + ": certified equivalence");
        check(left.certified().canonicalObservation().stableForm()
                .equals(right.certified().canonicalObservation().stableForm()) == expected,
                context + ": actual certified observation");
        check((CanonicalAlloyPipeline.distance(left.certified(), right.certified()) == 0) == expected,
                context + ": certified distance");
        return new Comparison(fast, certified);
    }

    private static Prepared prepare(MASGVisitor visitor, String name, SemanticProfile profile) {
        Canonical.Prepared fast = Canonical.prepare(graph(visitor, name), profile);
        return new Prepared(fast, CanonicalAlloyPipeline.prepare(fast));
    }

    private static Multigraph graph(MASGVisitor visitor, String name) {
        Integer id = visitor.getForestId(name);
        check(id != null && visitor.getForest().get(id) != null, "source graph " + name);
        return visitor.getForest().get(id);
    }

    private static AlloyTerm term(ModelUnit model, String name) {
        Predicate predicate = model.getPredDeclList().stream().filter(p -> p.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("Missing parser predicate " + name));
        return AlloyAstTermAdapter.fromAst(predicate.getBody());
    }

    private static CompModule parse(String source) throws Exception {
        return CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
    }

    private static boolean solve(CompModule module, Command command, A4Options options) throws Exception {
        A4Solution solution = TranslateAlloyToKodkod.execute_command(
                A4Reporter.NOP, module.getAllReachableSigs(), command, options);
        solverCalls++;
        check(solution != null, "solver must return an actual result for " + command.label);
        return solution.satisfiable();
    }

    // Fixed schema: one row per fixture/scope, lower-case Java booleans,
    // zero-based scope_index, and literal one-line strings (no escape syntax).
    private static String header() {
        return String.join("\t", "fixture", "source_expr", "reference_expr", "parser_term", "reference_term",
                "parser_operator", "source_kind", "source_name", "semantic_identity", "scope_index", "scope",
                "fast_equivalent", "certified_equivalent", "raw_equivalent", "raw_debruijn_equivalent",
                "java_egglog_equivalent", "java_egglog_debruijn_equivalent", "slotted_equivalent",
                "normalized_source", "normalized_reference", "source_sat", "reference_sat", "normalized_source_sat",
                "normalized_reference_sat", "source_preservation_sat", "reference_preservation_sat", "pair_counterexample_sat");
    }

    private static String row(List<Object> fields) {
        check(fields.size() == header().split("\t", -1).length, "fixed TSV schema width");
        List<String> values = new ArrayList<>();
        for (Object field : fields) {
            String value = field.toString();
            check(!value.contains("\t") && !value.contains("\n") && !value.contains("\r"), "unambiguous TSV field");
            values.add(value);
        }
        return String.join("\t", values);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

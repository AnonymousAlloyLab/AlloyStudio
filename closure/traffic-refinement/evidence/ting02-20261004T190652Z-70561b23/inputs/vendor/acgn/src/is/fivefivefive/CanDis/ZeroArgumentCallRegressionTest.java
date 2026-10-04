package is.fivefivefive.CanDis;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.PredRootSymbol;
import is.fivefivefive.ACGN.alloy.RefSymbol;
import is.fivefivefive.ACGN.alloy.Symbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.structure.ScopeTreeNode;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.theory.CallOccurrenceCertificate;
import parser.ast.nodes.Call;
import parser.ast.nodes.CallExpr;
import parser.ast.nodes.CallFormula;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;

/**
 * P1-10 only: five finite parser -> MASG -> certification-source IR -> artifact
 * fixtures, not a parser refinement or a standalone certificate-verifier run.
 * Optional argument: output TSV, one row per actual parser CALL occurrence.
 * Rows contain observed fields, not expected fixture values. Parser paths are
 * child-index paths; certificate paths are independently observed normalized
 * paths. Neither path is claimed to be a source span or a proof of lineage.
 * Lineage comes from the actual super.visit return and retained occurrence ID.
 * getCertificationMatrixEGraph is a supported IR boundary, not a raw-parser
 * archive: earlier ACI/reflexivity may already have removed calls. Repeated-call
 * survival fixtures therefore use separate predicate bodies. Optimized-matrix
 * counts are reported separately, never as a universal survival obligation.
 * Read-only reflection of callee target fields is part of the observation TCB.
 */
public final class ZeroArgumentCallRegressionTest {
    private static int checks;
    private static int occurrences;
    private static int invalidSources;
    private static int absentFromOptimizedMatrix;

    private record Fixture(String id, String source, Map<String, Integer> expected) { }
    private record Capture(Call parser, AugmentedNode node, Multigraph graph, int visit) { }
    private record Key(String source, String callee, String kind, int arity, String authority) { }

    private ZeroArgumentCallRegressionTest() { }

    private static final class ObservingVisitor extends MASGVisitor {
        private final List<Capture> captures = new ArrayList<>();

        ObservingVisitor(CompModule module) {
            super(new GlobalVariables(), module);
        }

        private AugmentedNode observe(Call parser, ScopeTreeNode scope, AugmentedNode node) {
            Multigraph graph = scope.getAffliation();
            Integer visit = graph.getTimeOfVisitMap().get(node);
            check(visit != null, "returned CALL must have a recorded visit");
            captures.add(new Capture(parser, node, graph, visit));
            return node;
        }

        @Override
        public AugmentedNode visit(CallFormula parser, ScopeTreeNode scope) {
            return observe(parser, scope, super.visit(parser, scope));
        }

        @Override
        public AugmentedNode visit(CallExpr parser, ScopeTreeNode scope) {
            return observe(parser, scope, super.visit(parser, scope));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected at most one TSV output path");
        }
        checks = occurrences = invalidSources = absentFromOptimizedMatrix = 0;
        List<String> rows = new ArrayList<>();
        rows.add(header());
        for (Fixture fixture : fixtures()) {
            exercise(fixture, rows);
        }
        invalidSourceControls();
        if (args.length == 1) {
            Files.write(Path.of(args[0]), rows, StandardCharsets.UTF_8);
        }
        System.out.println("ZeroArgumentCallRegressionTest: fixtures=" + fixtures().size()
                + " occurrences=" + occurrences + " invalidSources=" + invalidSources
                + " absentFromOptimizedMatrix=" + absentFromOptimizedMatrix + " checks=" + checks);
    }

    private static List<Fixture> fixtures() {
        return List.of(
                new Fixture("local-calls", String.join("\n",
                        "module zlocal", "sig A {}", "pred zero { some A }",
                        "fun value: set A { A }", "pred p { zero[] and some value[] }"),
                        Map.of("zlocal/zero", 1, "zlocal/value", 1)),
                new Fixture("ordering", String.join("\n",
                        "module zordering", "open util/ordering[A] as ord", "sig A {}",
                        "pred p { ord/first = ord/last }"),
                        Map.of("util/ordering<A>/first", 1, "util/ordering<A>/last", 1)),
                new Fixture("integer-next", String.join("\n",
                        "module zinteger", "open util/integer", "sig Train { pos: one Int }",
                        "pred p { all t: Train | t.pos.next in Int }"),
                        Map.of("util/integer/next", 1)),
                new Fixture("repeated-local", String.join("\n",
                        "module zrepeat", "sig A {}", "pred zero { some A }",
                        "fun value: set A { A }",
                        "pred p { zero[] }", "pred q { zero[] }",
                        "pred r { some value[] }", "pred s { some value[] }"),
                        Map.of("zrepeat/zero", 2, "zrepeat/value", 2)),
                new Fixture("repeated-import", String.join("\n",
                        "module zimports", "open util/ordering[A] as ord", "sig A {}",
                        "pred p { some ord/first }", "pred q { some ord/first }",
                        "pred r { some ord/last }", "pred s { some ord/last }"),
                        Map.of("util/ordering<A>/first", 2, "util/ordering<A>/last", 2)));
    }

    private static void exercise(Fixture fixture, List<String> rows) throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, fixture.source());
        ModelUnit model = new ModelUnit(null, module);
        Map<Call, String> paths = new IdentityHashMap<>();
        collectParserPaths(model, "model", Collections.newSetFromMap(new IdentityHashMap<>()), paths);
        ObservingVisitor visitor = new ObservingVisitor(module);
        visitor.visit(model, null);
        int expectedCount = fixture.expected().values().stream().mapToInt(Integer::intValue).sum();
        check(paths.size() == expectedCount, fixture.id() + ": parser occurrence coverage");
        check(visitor.captures.size() == paths.size(), "one real visit per parser call");
        MASGVisitor.CallExtractionStats stats = visitor.callExtractionStats();
        check(stats.occurrences() == paths.size() && stats.capturedVisits() == paths.size()
                && stats.validatedVisits() == paths.size() && stats.containingCalls() == 0,
                "all and only the zero calls were captured and validated");
        Set<Call> seenParser = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<AugmentedNode> seenNode = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> seenOccurrences = new HashSet<>();
        Set<Long> seenOwners = new HashSet<>();
        Map<String, Integer> actual = new java.util.TreeMap<>();
        Map<Multigraph, Canonical.Prepared> irByGraph = new IdentityHashMap<>();
        Map<Multigraph, CanonicalAlloyPipeline.Prepared> certifiedByGraph = new IdentityHashMap<>();
        visitor.captures.sort(Comparator.comparingLong(c -> call(c).getOccurrenceId()));
        for (Capture capture : visitor.captures) {
            CallSymbol call = call(capture);
            check(paths.containsKey(capture.parser()) && seenParser.add(capture.parser()),
                    "captured parser object must be unique and present in the original AST");
            check(seenNode.add(capture.node()) && seenOccurrences.add(call.getOccurrenceId())
                    && seenOwners.add(owner(capture.node())),
                    "CALL node, occurrence, and exported owner coordinate must each be unique");
            actual.merge(call.getCallee(), 1, Integer::sum);
            check(capture.parser().getArguments() != null
                    && capture.parser().getArguments().isEmpty(), "actual parser arity zero");
            check(capture.parser().getName().equals(call.getSourceName()), "parser source spelling");
            check(parserKind(capture.parser()).equals(call.getType()), "parser formula/expression kind");
            check(call.getDeclaredArity() == 0 && call.getMaxDownlinks() == 2,
                    "declared zero arity still requires two MASG roles");
            boolean imported = call.getCallee().startsWith("util/");
            check(call.getArityAuthority() == (imported
                    ? CallSymbol.ArityAuthority.TYPECHECKED_IMPORT : CallSymbol.ArityAuthority.DECLARATION),
                    "fixture declaration/import authority");
            check(call.getKind() == (call.getSourceName().equals("zero")
                    ? CallSymbol.Kind.FORMULA : CallSymbol.Kind.EXPRESSION),
                    "fixture predicate/function kind");
            check(capture.graph().getVertices().stream().anyMatch(node -> node == capture.node()),
                    "actual owner graph contains the returned node");
            check(capture.node().getDownlinkMapTOV().size() == 1,
                    "each fresh CALL has exactly one graph/visit entry");
            List<MASGEdge> edges = capture.node().getDownlinksAtTimeOfVisit(capture.graph(), capture.visit());
            check(edges != null && edges.size() == 2, "exactly callee and END retained in MASG");
            for (int i = 0; i < edges.size(); i++) {
                MASGEdge edge = edges.get(i);
                check(edge.getSource() == capture.node(), "edge owner is the captured node, not just equal metadata");
                check(edge.getTimeOfVisit() == capture.visit(), "edge belongs to the captured visit");
                check(edge.getPosition() == i + 1, "visitCall emits positions 1 then 2");
            }
            Key target = targetKey(edges.get(0).getTarget().getSymbol());
            check(target.equals(key(call)), "independently read callee target declaration fields");
            check(call.matchesTarget(edges.get(0).getTarget().getSymbol()), "actual callee matchesTarget result");
            check(!edges.get(0).getTarget().getSymbol().isEndSymbol()
                    && edges.get(1).getTarget().getSymbol().isEndSymbol(), "END is final and unique");
            if (!irByGraph.containsKey(capture.graph())) {
                Canonical.Prepared ir = Canonical.prepare(capture.graph());
                irByGraph.put(capture.graph(), ir);
                certifiedByGraph.put(capture.graph(), CanonicalAlloyPipeline.prepare(ir));
                long graphCount = visitor.captures.stream().filter(c -> c.graph() == capture.graph()).count();
                check(irCalls(ir, true).size() == graphCount,
                        "certification-source IR covers each parser occurrence");
                check(certifiedByGraph.get(capture.graph()).semanticArtifact().callOccurrenceCertificates().size()
                        == graphCount, "no CALL occurrence dropped or duplicated at certificate boundary");
            }
            List<EGraphNode> matches = irCalls(irByGraph.get(capture.graph()), true).stream()
                    .filter(node -> node.getCallOccurrenceId() == call.getOccurrenceId()).toList();
            check(matches.size() == 1, "unique occurrence lookup at certification-source IR boundary");
            EGraphNode ir = matches.get(0);
            check(key(ir).equals(key(call)), "IR retains complete declaration key");
            check(ir.getDeclaredArity() == 0 && ir.getMaxArity() == 0 && ir.getChildren().isEmpty(),
                    "IR deliberately omits both callee edge and END, leaving zero argument children");
            List<CallOccurrenceCertificate> certificates = certifiedByGraph.get(capture.graph())
                    .semanticArtifact().callOccurrenceCertificates().stream()
                    .filter(cert -> cert.occurrenceId() == call.getOccurrenceId()).toList();
            check(certificates.size() == 1, "exact unique occurrence lookup in certified artifact");
            CallOccurrenceCertificate cert = certificates.get(0);
            check(key(cert).equals(key(call)), "certified occurrence retains complete declaration key");
            check(cert.orderedArguments().isEmpty() && cert.sourceEndpoint().ports().isEmpty(),
                    "certified CALL has zero argument endpoints and no END port");
            String operator = "ALLOY/CALL/" + call.getCallee() + "/0/" + call.getType()
                    + "/" + call.getArityAuthority().name();
            check(cert.sourceEndpoint().operator().operator().equals(operator), "actual certified CALL operator");
            check(certifiedByGraph.get(capture.graph()).canonicalObservation().stableForm().contains(operator),
                    "zero CALL operator reaches canonical certified observation");
            long normalizedCount = irCalls(irByGraph.get(capture.graph()), false).stream()
                    .filter(node -> node.getCallOccurrenceId() == call.getOccurrenceId()).count();
            if (normalizedCount == 0) {
                absentFromOptimizedMatrix++;
            }
            rows.add(row(fixture.id(), paths.get(capture.parser()), capture, edges, target, ir, cert, normalizedCount));
            check(edges.equals(capture.node().getDownlinksAtTimeOfVisit(capture.graph(), capture.visit()))
                    && edges.size() == 2 && edges.get(1).getTarget().getSymbol().isEndSymbol(),
                    "IR lowering leaves original MASG END intact");
            occurrences++;
        }
        check(actual.equals(fixture.expected()), fixture.id() + ": exact finite callee multiplicities");
        for (CanonicalAlloyPipeline.Prepared prepared : certifiedByGraph.values()) {
            List<CallOccurrenceCertificate> certs = prepared.semanticArtifact().callOccurrenceCertificates();
            check(certs.stream().map(CallOccurrenceCertificate::sourcePath).distinct().count() == certs.size(),
                    "repeated zero calls keep distinct actual certificate source paths");
        }
    }

    private static void collectParserPaths(Node node, String path, Set<Node> seen, Map<Call, String> paths) {
        if (!seen.add(node)) {
            return;
        }
        if (node instanceof Call call) {
            paths.put(call, path);
        }
        List<Node> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            collectParserPaths(children.get(i), path + "/" + i, seen, paths);
        }
    }

    private static List<EGraphNode> irCalls(Canonical.Prepared prepared, boolean certificationSource) {
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        Set<EGraphNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<EGraphNode> calls = new ArrayList<>();
        for (NormalForm form : prepared.normalizedForms()) {
            EGraphNode root = certificationSource ? form.getCertificationMatrixEGraph() : form.getMatrixEGraph();
            if (root != null) {
                pending.add(root);
            }
        }
        while (!pending.isEmpty()) {
            EGraphNode node = pending.removeFirst();
            if (seen.add(node)) {
                if (node.getOpcode() == Opcode.CALL) {
                    calls.add(node);
                }
                pending.addAll(node.getChildren());
            }
        }
        return calls;
    }

    private static CallSymbol call(Capture c) { return (CallSymbol) c.node().getSymbol(); }
    private static String parserKind(Call c) { return c instanceof CallFormula ? "call/formula" : "call/expression"; }
    private static Key key(CallSymbol c) {
        return new Key(c.getSourceName(), c.getCallee(), c.getType(), c.getDeclaredArity(), c.getArityAuthority().name());
    }
    private static Key key(EGraphNode c) {
        return new Key(c.getSourceName(), c.getSemanticIdentity(), c.getSourceType(), c.getDeclaredArity(), c.getCallArityAuthority());
    }
    private static Key key(CallOccurrenceCertificate c) {
        return new Key(c.sourceName(), c.qualifiedCallee(), c.kind(), c.declaredArity(), c.arityAuthority().name());
    }

    // Both actual target classes expose only matchesCall, so read their stored
    // key fields independently. Never manufacture a target key from the CALL.
    private static Key targetKey(Symbol symbol) throws Exception {
        check(symbol instanceof RefSymbol || symbol instanceof PredRootSymbol, "known callable target representation");
        return new Key(symbol.getName(), (String) field(symbol, "semanticIdentity"),
                "call/" + ((CallSymbol.Kind) field(symbol, "callKind")).name().toLowerCase(java.util.Locale.ROOT),
                (Integer) field(symbol, "declaredArity"),
                ((CallSymbol.ArityAuthority) field(symbol, "arityAuthority")).name());
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static long owner(AugmentedNode node) {
        double value = node.getSemantic();
        check(value >= 0 && value == (long) value, "stable nonnegative integral MASG owner coordinate");
        return (long) value;
    }

    private static void invalidSourceControls() throws Exception {
        for (String body : List.of("unary[]", "missing[]")) {
            boolean rejected = false;
            try {
                CompUtil.parseEverything_fromString(A4Reporter.NOP, String.join("\n",
                        "module zinvalid", "sig A {}", "pred unary[x: A] { some x }", "pred p { " + body + " }"));
            } catch (edu.mit.csail.sdg.alloy4.Err expected) {
                rejected = true;
            }
            check(rejected, "public parser rejects invalid source arity/callee: " + body);
            invalidSources++;
        }
    }

    private static String header() {
        return String.join("\t", "fixture", "parser_path", "graph", "parser_source", "parser_kind", "parser_args",
                "occurrence", "owner", "visit", "source", "callee", "kind", "arity", "authority", "edge_count",
                "e1_owner", "e1_visit", "e1_position", "e1_is_end", "target_source", "target_callee", "target_kind",
                "target_arity", "target_authority", "callee_match", "e2_owner", "e2_visit", "e2_position", "e2_is_end",
                "ir_occurrence", "ir_source", "ir_callee", "ir_kind", "ir_arity", "ir_authority", "ir_args", "ir_max_arity", "ir_end_children",
                "cert_occurrence", "cert_source", "cert_callee", "cert_kind", "cert_arity", "cert_authority", "cert_args",
                "cert_ports", "cert_path", "cert_operator", "normalized_occurrences");
    }

    private static String row(String fixture, String path, Capture c, List<MASGEdge> edges, Key target,
            EGraphNode ir, CallOccurrenceCertificate cert, long normalizedCount) {
        List<String> values = new ArrayList<>();
        add(values, fixture, path, c.graph().getRoot().getSymbol().getName(), c.parser().getName(), parserKind(c.parser()),
                c.parser().getArguments().size(), call(c).getOccurrenceId(), owner(c.node()), c.visit());
        addKey(values, key(call(c)));
        add(values, edges.size());
        MASGEdge first = edges.get(0), end = edges.get(1);
        add(values, owner(first.getSource()), first.getTimeOfVisit(), first.getPosition(), first.getTarget().getSymbol().isEndSymbol());
        addKey(values, target);
        add(values, call(c).matchesTarget(first.getTarget().getSymbol()), owner(end.getSource()), end.getTimeOfVisit(),
                end.getPosition(), end.getTarget().getSymbol().isEndSymbol(), ir.getCallOccurrenceId());
        addKey(values, key(ir));
        add(values, ir.getChildren().size(), ir.getMaxArity(),
                ir.getChildren().stream().filter(node -> node.getOpcode() == Opcode.END).count(), cert.occurrenceId());
        addKey(values, key(cert));
        add(values, cert.orderedArguments().size(), cert.sourceEndpoint().ports().size(), cert.sourcePath(),
                cert.sourceEndpoint().operator().operator(), normalizedCount);
        check(values.size() == header().split("\t", -1).length, "TSV schema width");
        return String.join("\t", values);
    }

    private static void addKey(List<String> values, Key key) {
        add(values, key.source(), key.callee(), key.kind(), key.arity(), key.authority());
    }

    private static void add(List<String> values, Object... fields) {
        for (Object field : fields) {
            String text = String.valueOf(field);
            check(!text.contains("\t") && !text.contains("\n") && !text.contains("\r"), "unambiguous TSV primitive");
            values.add(text);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

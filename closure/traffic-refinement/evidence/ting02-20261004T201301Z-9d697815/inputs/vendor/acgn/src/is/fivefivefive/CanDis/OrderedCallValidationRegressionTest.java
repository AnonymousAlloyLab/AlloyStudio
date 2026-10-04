package is.fivefivefive.CanDis;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
import is.fivefivefive.CanDis.core.CallMetadata;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.theory.CallOccurrenceCertificate;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.InvocationPortLeaf;
import is.fivefivefive.CanDis.theory.OnePort;
import is.fivefivefive.CanDis.theory.TypedEClassRecord;
import parser.ast.nodes.Call;
import parser.ast.nodes.CallExpr;
import parser.ast.nodes.CallFormula;
import parser.ast.nodes.ConstExpr;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.etc.Pair;

/**
 * P1-08/P1-05 finite observations, following ZeroArgumentCallRegressionTest's
 * actual super.visit capture and certification-source (not optimized) matrix.
 * Only integer-literal payload fixtures are decoded here. No universal parser,
 * optimizer-survival, JVM refinement, or standalone verifier claim is made.
 * Optional argument writes deterministic, observed-only occurrence TSV v1.
 */
public final class OrderedCallValidationRegressionTest {
    private static final List<Integer> ARITIES = List.of(0, 1, 2, 3, 5, 8, 16);
    private static int checks, occurrences, rejections, orderControls;
    private record Capture(Call parser, AugmentedNode node, Multigraph graph, int visit) { }
    private record Run(ModelUnit model, ObservingVisitor visitor) { }
    private record TargetKey(String callee, String kind, int arity) { }
    private OrderedCallValidationRegressionTest() { }

    private static final class ObservingVisitor extends MASGVisitor {
        private final List<Capture> captures = new ArrayList<>();
        ObservingVisitor(CompModule module) { super(new GlobalVariables(), module); }
        private AugmentedNode observe(Call parser, ScopeTreeNode scope, AugmentedNode node) {
            Integer visit = scope.getAffliation().getTimeOfVisitMap().get(node);
            check(visit != null, "real returned CALL has recorded visit");
            captures.add(new Capture(parser, node, scope.getAffliation(), visit));
            return node;
        }
        @Override public AugmentedNode visit(CallFormula n, ScopeTreeNode scope) {
            return observe(n, scope, super.visit(n, scope));
        }
        @Override public AugmentedNode visit(CallExpr n, ScopeTreeNode scope) {
            return observe(n, scope, super.visit(n, scope));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Expected at most one TSV output path");
        checks = occurrences = rejections = orderControls = 0;
        List<String> rows = new ArrayList<>();
        rows.add(String.join("\t", "schema", "fixture", "parser_path", "occurrence", "owner", "visit",
                "callee", "kind", "arity", "parser_payloads", "masg_payloads", "ir_payloads", "cert_payloads",
                "positions", "edge_owners", "edge_visits", "edge_targets", "cert_path", "cert_operator",
                "observation_sha256", "target_callee", "target_kind", "target_arity"));
        for (int arity : ARITIES) exercise(arity, rows);
        if (args.length == 1) Files.write(Path.of(args[0]), rows, StandardCharsets.UTF_8);
        System.out.println("OrderedCallValidationRegressionTest: fixtures=" + ARITIES.size()
                + " occurrences=" + occurrences + " rejections=" + rejections
                + " orderControls=" + orderControls + " checks=" + checks);
    }

    private static List<Integer> payloads(int n, int variant) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < n; i++) values.add(variant == 2 ? 2 : 1 + i % 3);
        if (variant == 1) Collections.reverse(values);
        return values;
    }

    private static String source(int n) {
        List<String> parameters = new ArrayList<>();
        for (int i = 0; i < n; i++) parameters.add("x" + i + ": Int");
        String params = String.join(", ", parameters);
        List<String> lines = new ArrayList<>(List.of("module ordered", "sig A {}",
                "pred p[" + params + "] { some Int }", "fun f[" + params + "]: Int { 1 }"));
        for (int v = 0; v < 4; v++) {
            String arguments = csv(payloads(n, v));
            lines.add("pred p" + v + " { p[" + arguments + "] }");
            lines.add("pred f" + v + " { some f[" + arguments + "] }");
        }
        return String.join("\n", lines);
    }

    private static Run parse(int n) throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source(n));
        ModelUnit model = new ModelUnit(null, module);
        ObservingVisitor visitor = new ObservingVisitor(module);
        visitor.visit(model, null);
        return new Run(model, visitor);
    }

    private static void exercise(int arity, List<String> rows) throws Exception {
        check(arity >= 0 && arity <= Integer.MAX_VALUE - 3
                && Math.addExact(arity, 2) == arity + 2 && Math.addExact(arity, 3) == arity + 3,
                "declared finite parser fixture range is inside Java index arithmetic envelope");
        Run run = parse(arity);
        Map<Call, String> paths = new IdentityHashMap<>();
        collectPaths(run.model(), "model", Collections.newSetFromMap(new IdentityHashMap<>()), paths);
        check(paths.size() == 8 && run.visitor().captures.size() == 8, "exact parser/visit occurrence coverage");
        MASGVisitor.CallExtractionStats stats = run.visitor().callExtractionStats();
        check(stats.occurrences() == 8 && stats.capturedVisits() == 8 && stats.validatedVisits() == 8
                && stats.containingCalls() == 0, "all source calls captured and validated");
        Set<Call> parserSeen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> ids = new HashSet<>();
        Set<String> certPaths = new HashSet<>();
        Map<String, String> observations = new java.util.TreeMap<>();
        run.visitor().captures.sort(Comparator.comparingLong(c -> call(c).getOccurrenceId()));
        for (Capture c : run.visitor().captures) {
            CallSymbol call = call(c);
            String name = c.graph().getRoot().getSymbol().getName();
            int variant = Integer.parseInt(name.substring(1));
            List<Integer> parser = c.parser().getArguments().stream().map(a -> {
                check(a instanceof ConstExpr, "fixture parser payload is a real integer literal");
                return Integer.valueOf(((ConstExpr) a).getValue());
            }).toList();
            check(parser.equals(payloads(arity, variant)), "parser matches independently generated source sequence");
            check(paths.containsKey(c.parser()) && parserSeen.add(c.parser()) && ids.add(call.getOccurrenceId()),
                    "one capture per parser object and occurrence ID");
            check(call.getSourceName().equals(c.parser().getName()) && call.getDeclaredArity() == arity
                    && call.getCallee().equals("ordered/" + c.parser().getName())
                    && call.getArityAuthority() == CallSymbol.ArityAuthority.DECLARATION
                    && call.getKind() == (c.parser() instanceof CallFormula
                            ? CallSymbol.Kind.FORMULA : CallSymbol.Kind.EXPRESSION), "full source declaration metadata");
            List<MASGEdge> edges = c.node().getDownlinksAtTimeOfVisit(c.graph(), c.visit());
            check(edges.size() == arity + 2 && c.node().getDownlinkMapTOV().size() == 1,
                    "fresh exact visit includes callee and terminator");
            for (int i = 0; i < edges.size(); i++) {
                MASGEdge edge = edges.get(i);
                check(edge.getSource() == c.node() && edge.getTimeOfVisit() == c.visit()
                        && edge.getPosition() == i + 1, "source insertion order has exact owner/visit/index");
            }
            check(call.matchesTarget(edges.get(0).getTarget().getSymbol())
                    && edges.get(edges.size() - 1).getTarget().getSymbol().isEndSymbol(), "callee and final END");
            TargetKey target = targetKey(edges.get(0).getTarget().getSymbol());
            check(target.equals(new TargetKey(call.getCallee(), call.getType(), call.getDeclaredArity())),
                    "independently read target key matches CALL declaration");
            List<Integer> masg = masgPayloads(edges);
            check(parser.equals(masg), "MASG payload order and multiplicity");
            Canonical.Prepared ir = Canonical.prepare(c.graph());
            List<EGraphNode> calls = irCalls(ir);
            check(calls.size() == 1, "finite fixture CALL survives certification-source IR");
            EGraphNode irCall = calls.get(0);
            check(irCall.getCallOccurrenceId() == call.getOccurrenceId()
                    && irCall.getDeclaredArity() == arity && irCall.getChildren().size() == arity
                    && irCall.getMaxArity() == arity && irCall.getSemanticIdentity().equals(call.getCallee())
                    && irCall.getSourceType().equals(call.getType())
                    && irCall.getSourceName().equals(call.getSourceName())
                    && irCall.getCallArityAuthority().equals(call.getArityAuthority().name()), "IR identity and exact arity");
            List<Integer> lowered = irCall.getChildren().stream().map(child -> {
                check(child.getOpcode() == Opcode.CONSTANT, "literal IR payload, no callee or END child");
                return Integer.valueOf(child.getSourceName());
            }).toList();
            check(parser.equals(lowered), "certification-source IR payload order and multiplicity");
            CanonicalAlloyPipeline.Prepared certified = CanonicalAlloyPipeline.prepare(ir);
            CertifiedSemanticArtifact artifact = certified.semanticArtifact();
            check(artifact.callOccurrenceCertificates().size() == 1, "exact certified occurrence coverage");
            CallOccurrenceCertificate cert = artifact.callOccurrenceCertificates().get(0);
            String operator = "ALLOY/CALL/" + call.getCallee() + "/" + arity + "/" + call.getType()
                    + "/" + call.getArityAuthority().name();
            check(cert.occurrenceId() == call.getOccurrenceId() && cert.declaredArity() == arity
                    && cert.qualifiedCallee().equals(call.getCallee()) && cert.kind().equals(call.getType())
                    && cert.sourceName().equals(call.getSourceName()) && cert.arityAuthority() == call.getArityAuthority()
                    && cert.sourceEndpoint().operator().operator().equals(operator), "certified complete declaration key");
            check(cert.orderedArguments().equals(cert.sourceEndpoint().ports()), "each certified role binds its endpoint");
            List<Integer> certifiedValues = cert.orderedArguments().stream().map(port -> certifiedLiteral(artifact, port)).toList();
            check(parser.equals(certifiedValues), "independently decoded certified payload order and duplicates");
            certificateOrderControls(irCall, cert, certifiedValues);
            check(certPaths.add(name + ":" + cert.sourcePath()), "certificate paths scoped to their actual graph");
            String observation = certified.canonicalObservation().stableForm();
            check(observation.contains(operator), "actual certified observation contains CALL operator");
            observations.put(name, observation);
            addRow(rows, "ordered-call-v1", "arity-" + arity, paths.get(c.parser()), call.getOccurrenceId(),
                    owner(c.node()), c.visit(), call.getCallee(), call.getType(), arity, csv(parser), csv(masg),
                    csv(lowered), csv(certifiedValues), csv(edges.stream().map(MASGEdge::getPosition).toList()),
                    csv(edges.stream().map(e -> owner(e.getSource())).toList()),
                    csv(edges.stream().map(MASGEdge::getTimeOfVisit).toList()),
                    "callee" + (masg.isEmpty() ? "" : "," + csv(masg)) + ",end", cert.sourcePath(), operator,
                    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(observation.getBytes(StandardCharsets.UTF_8))), target.callee(), target.kind(), target.arity());
            check(masg.equals(masgPayloads(edges)) && edges.get(edges.size() - 1).getTarget().getSymbol().isEndSymbol(),
                    "lowering did not mutate original argument roles or END");
            occurrences++;
        }
        for (String kind : List.of("p", "f")) {
            check(observations.get(kind + "0").equals(observations.get(kind + "3")),
                    "equal source payload sequences yield equal canonical observations");
            if (!payloads(arity, 0).equals(payloads(arity, 1))) {
                check(!observations.get(kind + "0").equals(observations.get(kind + "1")),
                        "swapped source payloads remain distinct in certified observations");
                orderControls++;
            }
        }
        malformedControls(run.visitor().captures.get(0));
    }

    private static int certifiedLiteral(CertifiedSemanticArtifact artifact, OnePort port) {
        check(port.leaf() instanceof InvocationPortLeaf, "literal certificate operand is an invocation");
        InvocationPortLeaf leaf = (InvocationPortLeaf) port.leaf();
        TypedEClassRecord record = artifact.classes().get(leaf.invocation().eclass().id());
        check(record != null && record.shapeWitnesses().size() == 1, "unique stored literal shape");
        var node = record.shapeWitnesses().firstKey().node();
        String operator = node.operator().operator();
        String prefix = "ALLOY/CONSTANT/";
        check(node.ports().isEmpty() && operator.startsWith(prefix), "actual nullary integer operator");
        return Integer.parseInt(operator.substring(prefix.length()));
    }

    private static void certificateOrderControls(EGraphNode ir, CallOccurrenceCertificate cert, List<Integer> values) {
        for (int i = 1; i < values.size(); i++) {
            if (values.get(i).equals(values.get(0))) continue;
            for (boolean swap : List.of(false, true)) {
                List<OnePort> wrong = new ArrayList<>(cert.orderedArguments());
                if (swap) Collections.swap(wrong, 0, i);
                else wrong.set(0, wrong.get(i));
                try {
                    CallOccurrenceCertificate.create(CallMetadata.require(ir), cert.sourcePath(), cert.sourceEndpoint(), wrong);
                } catch (IllegalArgumentException expected) {
                    check(expected.getMessage().contains("argument endpoint differs"), "precise same-type payload binding rejection");
                    orderControls++;
                    continue;
                }
                throw new AssertionError("Certified endpoint accepted " + (swap ? "swapped" : "wrong distinct") + " payload");
            }
            return;
        }
    }

    private static List<Integer> masgPayloads(List<MASGEdge> edges) {
        return edges.subList(1, edges.size() - 1).stream().map(e -> {
            check(!e.getTarget().getSymbol().isEndSymbol(), "argument is not END");
            return Integer.valueOf(e.getTarget().getSymbol().getName());
        }).toList();
    }

    private static List<EGraphNode> irCalls(Canonical.Prepared prepared) {
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        Set<EGraphNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<EGraphNode> calls = new ArrayList<>();
        for (NormalForm form : prepared.normalizedForms()) {
            if (form.getCertificationMatrixEGraph() != null) pending.add(form.getCertificationMatrixEGraph());
        }
        while (!pending.isEmpty()) {
            EGraphNode node = pending.removeFirst();
            if (seen.add(node)) {
                if (node.getOpcode() == Opcode.CALL) calls.add(node);
                pending.addAll(node.getChildren());
            }
        }
        return calls;
    }

    private static void collectPaths(Node node, String path, Set<Node> seen, Map<Call, String> paths) {
        if (!seen.add(node)) return;
        if (node instanceof Call call) paths.put(call, path);
        for (int i = 0; i < node.getChildren().size(); i++) collectPaths(node.getChildren().get(i), path + "/" + i, seen, paths);
    }

    // Invoke the real private controls. Decoys use valid same-node buckets both
    // before and after the requested visit; no production dispatch is replaced.
    private static void malformedControls(Capture c) throws Exception {
        Method select = IRAgent.class.getDeclaredMethod("downlinksFor", AugmentedNode.class, int.class, Opcode.class);
        Method complete = MASGVisitor.class.getDeclaredMethod("validateCompletedCallVisit", AugmentedNode.class,
                CallSymbol.class, Multigraph.class, int.class);
        select.setAccessible(true);
        complete.setAccessible(true);
        IRAgent agent = new IRAgent(c.graph());
        var buckets = c.node().getDownlinkMapTOV();
        var saved = new java.util.HashMap<>(buckets);
        Integer maxVisit = c.graph().getTimeOfVisitMap().get(c.node());
        List<MASGEdge> original = new ArrayList<>(c.node().getDownlinksAtTimeOfVisit(c.graph(), c.visit()));
        List<MASGEdge> exact = atVisit(original, 2);
        try {
            buckets.clear();
            for (int visit : List.of(1, 2, 3)) buckets.put(Pair.of(c.graph(), visit), atVisit(original, visit));
            c.graph().getTimeOfVisitMap().put(c.node(), 3);
            for (int visit : List.of(1, 2, 3)) {
                check(((List<?>) invoke(select, agent, c.node(), visit, Opcode.CALL)).size() == original.size(),
                        "decoy/exact bucket independently accepted by actual CALL selector");
                invoke(complete, null, c.node(), call(c), c.graph(), visit);
            }
            rejectBoth("missing bucket", null, c, agent, select, complete);
            rejectBoth("empty bucket", List.of(), c, agent, select, complete);
            damaged("missing callee", exact, e -> e.remove(0), c, agent, select, complete);
            damaged("missing END", exact, e -> e.remove(e.size() - 1), c, agent, select, complete);
            damaged("extra edge", exact, e -> e.add(e.get(0)), c, agent, select, complete);
            damaged("wrong callee", exact, e -> e.set(0, replace(e.get(0), e.get(e.size() - 1).getTarget(), 1, 2, c.node())),
                    c, agent, select, complete);
            damaged("non-END terminator", exact, e -> e.set(e.size() - 1,
                    replace(e.get(e.size() - 1), e.get(0).getTarget(), e.size(), 2, c.node())), c, agent, select, complete);
            for (int i = 0; i < exact.size(); i++) {
                final int index = i;
                damaged("foreign owner role " + i, exact, e -> e.set(index,
                        replace(e.get(index), e.get(index).getTarget(), index + 1, 2, e.get(0).getTarget())), c, agent, select, complete);
                damaged("foreign visit role " + i, exact, e -> e.set(index,
                        replace(e.get(index), e.get(index).getTarget(), index + 1, 3, c.node())), c, agent, select, complete);
                damaged("gap role " + i, exact, e -> e.set(index,
                        replace(e.get(index), e.get(index).getTarget(), e.size() + 1, 2, c.node())), c, agent, select, complete);
                damaged("duplicate role " + i, exact, e -> e.set(index,
                        replace(e.get(index), e.get(index).getTarget(), index == 0 ? 2 : index, 2, c.node())), c, agent, select, complete);
            }
            for (int i = 1; i + 1 < exact.size(); i++) {
                final int index = i;
                damaged("missing argument " + i, exact, e -> e.remove(index), c, agent, select, complete);
                damaged("END argument " + i, exact, e -> e.set(index,
                        replace(e.get(index), e.get(e.size() - 1).getTarget(), index + 1, 2, c.node())), c, agent, select, complete);
            }
            List<MASGEdge> reversed = new ArrayList<>(exact);
            Collections.reverse(reversed);
            buckets.put(Pair.of(c.graph(), 2), reversed);
            check(exact.equals(invoke(select, agent, c.node(), 2, Opcode.CALL)), "selector sorts roles, never payload values");
            invoke(complete, null, c.node(), call(c), c.graph(), 2);
            check(reversed.get(0) == exact.get(exact.size() - 1), "selector does not sort input bucket in place");
            buckets.put(Pair.of(c.graph(), 4), atVisit(original, 4));
            expectRejection(() -> invoke(select, agent, c.node(), 4, Opcode.CALL), "past max visit even with complete bucket");
        } finally {
            buckets.clear();
            buckets.putAll(saved);
            c.graph().getTimeOfVisitMap().put(c.node(), maxVisit);
        }
        check(c.node().getDownlinksAtTimeOfVisit(c.graph(), c.visit()).equals(original), "all temporary graph mutations restored");
    }

    private static MASGEdge replace(MASGEdge ignored, AugmentedNode target, int position, int visit, AugmentedNode owner) {
        return new MASGEdge(owner, target, position, visit);
    }
    private static List<MASGEdge> atVisit(List<MASGEdge> edges, int visit) {
        return edges.stream().map(e -> new MASGEdge(e.getSource(), e.getTarget(), e.getPosition(), visit))
                .collect(Collectors.toCollection(ArrayList::new));
    }
    private static void damaged(String name, List<MASGEdge> exact, Consumer<List<MASGEdge>> mutation,
            Capture c, IRAgent agent, Method select, Method complete) throws Exception {
        List<MASGEdge> edges = new ArrayList<>(exact);
        mutation.accept(edges);
        rejectBoth(name, edges, c, agent, select, complete);
    }
    private static void rejectBoth(String name, List<MASGEdge> edges, Capture c, IRAgent agent,
            Method select, Method complete) throws Exception {
        c.node().getDownlinkMapTOV().put(Pair.of(c.graph(), 2), edges);
        expectRejection(() -> invoke(select, agent, c.node(), 2, Opcode.CALL), "IR " + name);
        expectRejection(() -> invoke(complete, null, c.node(), call(c), c.graph(), 2), "MASG " + name);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void expectRejection(Throwing action, String name) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { rejections++; return; }
        throw new AssertionError("Accepted malformed CALL: " + name);
    }
    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof Exception cause) throw cause;
            if (wrapped.getCause() instanceof Error cause) throw cause;
            throw wrapped;
        }
    }
    private static CallSymbol call(Capture c) { return (CallSymbol) c.node().getSymbol(); }
    private static TargetKey targetKey(Symbol symbol) throws Exception {
        check(symbol instanceof RefSymbol || symbol instanceof PredRootSymbol, "known actual callable target representation");
        return new TargetKey((String) field(symbol, "semanticIdentity"),
                "call/" + ((CallSymbol.Kind) field(symbol, "callKind")).name().toLowerCase(java.util.Locale.ROOT),
                (Integer) field(symbol, "declaredArity"));
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private static long owner(AugmentedNode node) {
        double value = node.getSemantic();
        check(value >= 0 && value == (long) value, "integral nonnegative owner token");
        return (long) value;
    }
    private static String csv(List<?> values) { return values.stream().map(Object::toString).collect(Collectors.joining(",")); }
    private static void addRow(List<String> rows, Object... fields) {
        List<String> values = new ArrayList<>();
        for (Object field : fields) {
            String text = field.toString();
            check(!text.contains("\t") && !text.contains("\n") && !text.contains("\r"), "TSV primitive");
            values.add(text);
        }
        check(values.size() == rows.get(0).split("\t", -1).length, "fixed TSV width");
        rows.add(String.join("\t", values));
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

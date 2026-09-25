package is.fivefivefive.CanDis;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.AlloyLibraryCallableLedger;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.structure.ScopeTreeNode;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.CallMetadata;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.theory.CallOccurrenceCertificate;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.InvocationPortLeaf;
import is.fivefivefive.CanDis.theory.OnePort;
import is.fivefivefive.CanDis.theory.TypedENode;
import is.fivefivefive.CanDis.theory.EClassId;
import org.json.JSONArray;
import parser.ast.nodes.*;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Finite real source observations, not universal Java/parser refinement. */
public final class CallAuthorityTransitionsRegressionTest {
    public static final List<Integer> ARITIES = List.of(0, 1, 2, 3, 5, 8, 16);
    public static final int OCCURRENCES = 91;
    public static final String FIELDS = "schema fixture occurrence owner visit signature_table declaration_groups "
            + "declared_signature parser_tree masg_tree ir_tree cert_tree before first_visit first_after first_accept "
            + "second_visit second_after second_accept max_visit ir_arity ir_policy cert_path observation_sha256";
    private static int checks, rejects;
    private record Capture(Call parser, AugmentedNode node, Multigraph graph, int visit) { }
    private record Signature(String name, String callee, String kind, int arity, String authority, List<Integer> groups) {
        JSONArray json() { return array(callee, kind, arity, authority); }
    }
    private record Run(CompModule module, ModelUnit model, Observer visitor, List<Signature> signatures) { }
    private static final Pattern CALL_OPERATOR = Pattern.compile(
            "ALLOY/CALL/(.+)/(0|[1-9][0-9]*)/(call/(?:formula|expression))/(DECLARATION|TYPECHECKED_IMPORT)");
    private CallAuthorityTransitionsRegressionTest() { }

    private static final class Observer extends MASGVisitor {
        private final List<Capture> captures = new ArrayList<>();
        Observer(CompModule module) { super(new GlobalVariables(), module); }
        private AugmentedNode observe(Call call, ScopeTreeNode scope, AugmentedNode result) {
            Integer visit = scope.getAffliation().getTimeOfVisitMap().get(result);
            check(visit != null, "actual returned CALL has a visit");
            captures.add(new Capture(call, result, scope.getAffliation(), visit));
            return result;
        }
        @Override public AugmentedNode visit(CallExpr call, ScopeTreeNode scope) {
            return observe(call, scope, super.visit(call, scope));
        }
        @Override public AugmentedNode visit(CallFormula call, ScopeTreeNode scope) {
            return observe(call, scope, super.visit(call, scope));
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Expected optional output TSV path");
        if (args.length == 1) Files.deleteIfExists(Path.of(args[0]));
        checks = rejects = 0;
        List<String> rows = new ArrayList<>(List.of(FIELDS.replace(' ', '\t')));
        for (int arity : ARITIES) exercise("arity-" + arity, source(arity), 8, rows);
        exercise("nested", nestedSource(), 30, rows);
        exercise("imported", importedSource(), 5, rows);
        parserControls();
        ledgerControls();
        check(rows.size() == OCCURRENCES + 1, "fixed occurrence census");
        if (args.length == 1) Files.write(Path.of(args[0]), rows, StandardCharsets.UTF_8);
        System.out.println("CallAuthorityTransitionsRegressionTest: occurrences=" + OCCURRENCES
                + " rejections=" + rejects + " checks=" + checks);
    }

    private static String source(int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) names.add("x" + i);
        // Grouped names exercise declaration arity, not just parameter-list length.
        String params = n == 0 ? "" : String.join(",", names) + ": Int";
        List<String> lines = new ArrayList<>(List.of("module authority", "sig A {}",
                "pred p[" + params + "] { some Int }", "fun f[" + params + "]: Int { 1 }"));
        for (int v = 0; v < 4; v++) {
            List<Integer> values = new ArrayList<>();
            for (int i = 0; i < n; i++) values.add(v == 2 ? 2 : 1 + i % 3);
            if (v == 1) Collections.reverse(values);
            String args = values.stream().map(Object::toString).collect(Collectors.joining(","));
            lines.add("pred p" + v + " { p[" + args + "] }");
            lines.add("pred f" + v + " { some f[" + args + "] }");
        }
        return String.join("\n", lines);
    }

    private static String nestedSource() {
        List<String> bodies = List.of("f[f[1]]", "f[1]", "g[1,2]", "g[2,1]", "g[1,1]",
                "g[f[1],f[1]]", "g[f[1],f[2]]", "g[f[2],f[1]]", "f[#g[1,2]]", "f[g[1,2]]",
                "g[#f[1],f[2]]", "g[f[2],#f[1]]", "f[#g[1,2]]");
        List<String> lines = new ArrayList<>(List.of("module authority", "sig A {}",
                "fun f[x: Int]: Int { x }", "fun g[x,y: Int]: Int { x }", "pred p[x,y: Int] { some Int }"));
        for (int i = 0; i < bodies.size(); i++) lines.add("pred n" + i + " { some " + bodies.get(i) + " }");
        lines.add("pred n13 { p[f[1],f[1]] }");
        return String.join("\n", lines);
    }

    private static String importedSource() {
        return String.join("\n", "module authority", "open util/ordering[A] as ord", "sig A {}",
                "pred i0 { ord/lt[ord/first,ord/last] }", "pred i1 { some ord/nexts[ord/first] }");
    }

    private static Run parse(String source) throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        ModelUnit model = new ModelUnit(null, module);
        // Read declarations and the pinned ledger before visiting any CALL.
        List<Signature> signatures = signatures(model);
        Observer visitor = new Observer(module);
        visitor.visit(model, null);
        visitor.captures.sort(Comparator.comparingLong(c -> symbol(c).getOccurrenceId()));
        return new Run(module, model, visitor, signatures);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AlloyLibraryCallableLedger.Signature> ledger() throws Exception {
        Field field = AlloyLibraryCallableLedger.class.getDeclaredField("SIGNATURES");
        field.setAccessible(true);
        return (Map<String, AlloyLibraryCallableLedger.Signature>) field.get(null);
    }

    private static List<Signature> signatures(ModelUnit model) throws Exception {
        List<Signature> result = new ArrayList<>();
        List<PredOrFun> declarations = new ArrayList<>(model.getPredDeclList());
        declarations.addAll(model.getFunDeclList());
        for (PredOrFun declaration : declarations) {
            List<Integer> groups = declaration.getParamList().stream().map(p -> p.getNames().size()).toList();
            result.add(new Signature(declaration.getName(), model.getModuleDecl().getModelName() + "/" + declaration.getName(),
                    declaration instanceof Predicate ? "call/formula" : "call/expression",
                    groups.stream().mapToInt(Integer::intValue).sum(), "DECLARATION", groups));
        }
        for (OpenDecl open : model.getOpenDeclList()) {
            check(Set.of("util/ordering", "util/integer").contains(open.getFileName()),
                    "registered independent import fixture declaration: " + open.getFileName());
            String alias = open.getAlias() == null || open.getAlias().isBlank()
                    ? open.getFileName().substring(open.getFileName().lastIndexOf('/') + 1) : open.getAlias();
            String identity = open.getFileName() + (open.getArguments().isEmpty() ? ""
                    : "<" + String.join(",", open.getArguments()) + ">");
            for (var entry : new TreeMap<>(ledger()).entrySet()) {
                String prefix = open.getFileName() + "/";
                if (!entry.getKey().startsWith(prefix)) continue;
                String member = entry.getKey().substring(prefix.length()).split("/")[0];
                var s = entry.getValue();
                result.add(new Signature(alias + "/" + member, identity + "/" + member,
                        s.kind() == CallSymbol.Kind.FORMULA ? "call/formula" : "call/expression",
                        s.arity(), "TYPECHECKED_IMPORT", List.of()));
            }
        }
        return result.stream().sorted(Comparator.comparing(s -> s.json().toString())).toList();
    }

    private static Signature declared(Run run, Call call) {
        // This finite fixture has no overloaded name. Never construct arity from arguments.
        var matches = run.signatures().stream().filter(s -> s.name().equals(call.getName())).toList();
        check(matches.size() == 1, "unique independently indexed fixture declaration: " + call.getName());
        return matches.get(0);
    }

    private static JSONArray parserTree(Run run, Node node) {
        if (node instanceof ConstExpr constant) return array("atom", Integer.parseInt(constant.getValue()));
        if (node instanceof Call call) return array("call", declared(run, call).json(),
                new JSONArray(call.getArguments().stream().map(a -> parserTree(run, a)).toList()));
        if (node instanceof UnaryExpr unary) {
            if (unary.getOp() == UnaryExpr.UnaryOp.NOOP) return parserTree(run, unary.getSub());
            check(unary.getOp() == UnaryExpr.UnaryOp.CARDINALITY, "registered parser barrier " + unary.getOp());
            return array("barrier", unary.getOp().name(), array(parserTree(run, unary.getSub())));
        }
        throw new AssertionError("Unregistered parser payload: " + node.getClass());
    }

    private static JSONArray masgTree(AugmentedNode node, Multigraph graph, int visit) throws Exception {
        Opcode opcode = (Opcode) invoke(method(IRAgent.class, "opcodeOf", AugmentedNode.class), null, node);
        if (opcode == Opcode.CONSTANT) return array("atom", Integer.parseInt(node.getSymbol().getName()));
        List<MASGEdge> edges = new ArrayList<>(node.getDownlinksAtTimeOfVisit(graph, visit));
        edges.sort(Comparator.comparingInt(MASGEdge::getPosition));
        List<JSONArray> children = new ArrayList<>();
        if (opcode == Opcode.CALL) {
            CallSymbol call = (CallSymbol) node.getSymbol();
            check(edges.size() == call.getDeclaredArity() + 2 && call.matchesTarget(edges.get(0).getTarget().getSymbol())
                    && edges.get(edges.size() - 1).getTarget().getSymbol().isEndSymbol(), "MASG independent callee and END");
            for (int i = 0; i < edges.size(); i++) check(edges.get(i).getSource() == node
                    && edges.get(i).getTimeOfVisit() == visit && edges.get(i).getPosition() == i + 1,
                    "actual CALL role owner/visit/index");
            for (MASGEdge edge : edges.subList(1, edges.size() - 1)) children.add(masgTree(edge.getTarget(), graph, 1));
            return array("call", array(call.getCallee(), call.getType(), call.getDeclaredArity(), call.getArityAuthority().name()),
                    new JSONArray(children));
        }
        check(opcode == Opcode.CARDINALITY, "registered MASG barrier: " + opcode);
        for (MASGEdge edge : edges) if (!edge.getTarget().getSymbol().isEndSymbol()) children.add(masgTree(edge.getTarget(), graph, 1));
        check(children.size() == 1, "unary MASG barrier");
        return array("barrier", opcode.name(), new JSONArray(children));
    }

    private static JSONArray irTree(EGraphNode node) {
        if (node.getOpcode() == Opcode.CONSTANT) return array("atom", Integer.parseInt(node.getSourceName()));
        JSONArray children = new JSONArray(node.getChildren().stream().map(CallAuthorityTransitionsRegressionTest::irTree).toList());
        if (node.getOpcode() == Opcode.CALL) {
            CallMetadata.Validated call = CallMetadata.require(node);
            check(!node.isFlexibleArity() && !node.isOrderInsensitive() && !node.hasFlatLicense(), "actual ordered fixed nonflat IR CALL");
            return array("call", array(call.identity(), call.kind(), call.arity(), call.authority().name()), children);
        }
        check(node.getOpcode() == Opcode.CARDINALITY && children.length() == 1, "registered IR barrier " + node.getOpcode());
        return array("barrier", node.getOpcode().name(), children);
    }

    private static JSONArray certifiedTree(CertifiedSemanticArtifact artifact, TypedENode node, Set<EClassId> active) {
        String operator = node.operator().operator();
        List<JSONArray> children = new ArrayList<>();
        for (var port : node.ports()) {
            check(port instanceof OnePort && ((OnePort) port).leaf() instanceof InvocationPortLeaf, "actual ordered scalar port, not flat container");
            var invocation = ((InvocationPortLeaf) ((OnePort) port).leaf()).invocation();
            EClassId id = invocation.eclass().id();
            check(active.add(id), "acyclic certified fixture payload");
            var record = artifact.classes().get(id);
            check(record != null && record.shapeWitnesses().size() == 1, "unique independently stored certified shape");
            children.add(certifiedTree(artifact, record.shapeWitnesses().firstKey().node(), active));
            active.remove(id);
        }
        var matcher = CALL_OPERATOR.matcher(operator);
        if (matcher.matches()) return array("call", array(matcher.group(1), matcher.group(3),
                Integer.parseInt(matcher.group(2)), matcher.group(4)), new JSONArray(children));
        if (operator.startsWith("ALLOY/CONSTANT/")) {
            check(children.isEmpty(), "nullary certified literal");
            return array("atom", Integer.parseInt(operator.substring("ALLOY/CONSTANT/".length())));
        }
        check(operator.equals("ALLOY/CARDINALITY") && children.size() == 1, "registered certified barrier " + operator);
        return array("barrier", "CARDINALITY", new JSONArray(children));
    }

    private static Map<Long, EGraphNode> irCalls(Canonical.Prepared ir) {
        Map<Long, EGraphNode> result = new TreeMap<>();
        Set<EGraphNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        for (NormalForm form : ir.normalizedForms()) if (form.getCertificationMatrixEGraph() != null) pending.add(form.getCertificationMatrixEGraph());
        while (!pending.isEmpty()) {
            EGraphNode node = pending.removeFirst();
            if (!seen.add(node)) continue;
            if (node.getOpcode() == Opcode.CALL) check(result.put(node.getCallOccurrenceId(), node) == null, "unique finite IR occurrence");
            pending.addAll(node.getChildren());
        }
        return result;
    }

    private static void exercise(String fixture, String source, int count, List<String> rows) throws Exception {
        Run run = parse(source);
        check(run.visitor().captures.size() == count, "frozen fixture occurrence census " + fixture);
        Set<Call> parsedCalls = Collections.newSetFromMap(new IdentityHashMap<>());
        collectCalls(run.model(), parsedCalls, Collections.newSetFromMap(new IdentityHashMap<>()));
        check(parsedCalls.size() == count && run.visitor().captures.stream().allMatch(c -> parsedCalls.remove(c.parser()))
                && parsedCalls.isEmpty(), "independent parser object/returned visit census");
        Map<Multigraph, CanonicalAlloyPipeline.Prepared> artifacts = new IdentityHashMap<>();
        Map<Multigraph, Map<Long, EGraphNode>> irs = new IdentityHashMap<>();
        Map<String, String> observations = new TreeMap<>();
        for (Capture c : run.visitor().captures) {
            if (!artifacts.containsKey(c.graph())) {
                var ir = Canonical.prepare(c.graph());
                irs.put(c.graph(), irCalls(ir));
                for (EGraphNode call : irs.get(c.graph()).values()) metadataControls(call);
                artifacts.put(c.graph(), CanonicalAlloyPipeline.prepare(ir));
                long graphCount = run.visitor().captures.stream().filter(x -> x.graph() == c.graph()).count();
                check(irs.get(c.graph()).size() == graphCount
                        && artifacts.get(c.graph()).semanticArtifact().callOccurrenceCertificates().size() == graphCount,
                        "complete finite certification-source and certified CALL census");
            }
            CallSymbol symbol = symbol(c);
            Signature declaration = declared(run, c.parser());
            check(declaration.json().similar(array(symbol.getCallee(), symbol.getType(), symbol.getDeclaredArity(),
                    symbol.getArityAuthority().name())), "declaration/ledger metadata, not source spelling authority");
            check(declaration.arity() <= Integer.MAX_VALUE - 3 && Math.addExact(declaration.arity(), 3) > 0,
                    "valid Java int arity envelope");
            var pipeline = artifacts.get(c.graph());
            var artifact = pipeline.semanticArtifact();
            EGraphNode ir = irs.get(c.graph()).get(symbol.getOccurrenceId());
            CallOccurrenceCertificate cert = artifact.callOccurrenceCertificates().stream()
                    .filter(x -> x.occurrenceId() == symbol.getOccurrenceId()).findFirst().orElseThrow();
            JSONArray parser = parserTree(run, c.parser()), masg = masgTree(c.node(), c.graph(), c.visit());
            JSONArray lowered = irTree(ir), certified = certifiedTree(artifact, cert.sourceEndpoint(), new HashSet<>());
            check(parser.similar(masg), "actual MASG recursive CALL representation: " + fixture + " " + parser + " != " + masg);
            check(parser.similar(lowered), "actual certification-source recursive CALL representation: " + fixture + " " + parser + " != " + lowered);
            check(parser.similar(certified), "actual certified recursive CALL representation: " + fixture + " " + parser + " != " + certified);
            String observation = pipeline.canonicalObservation().stableForm();
            check(observation.contains(cert.sourceEndpoint().operator().operator()), "CALL operator reaches actual canonical observation");
            observations.put(c.graph().getRoot().getSymbol().getName(), observation);
            List<Object> trace = transition(c);
            certificateControls(ir, cert);
            List<Object> row = new ArrayList<>(List.of("call-authority-v1", fixture, symbol.getOccurrenceId(), owner(c.node()), c.visit(),
                    new JSONArray(run.signatures().stream().map(Signature::json).toList()), new JSONArray(declaration.groups()),
                    declaration.json(), parser, masg, lowered, certified));
            row.addAll(trace);
            row.addAll(List.of(ir.getDeclaredArity(), ir.getSiblingQuotient().name(), cert.sourcePath(), sha256(observation)));
            check(row.size() == FIELDS.split(" ").length, "TSV field census");
            rows.add(row.stream().map(Object::toString).collect(Collectors.joining("\t")));
        }
        if (fixture.equals("nested")) {
            for (String[] pair : List.of(new String[]{"n0", "n1"}, new String[]{"n2", "n3"},
                    new String[]{"n2", "n4"}, new String[]{"n6", "n7"}, new String[]{"n8", "n9"}, new String[]{"n10", "n11"})) {
                check(!observations.get(pair[0]).equals(observations.get(pair[1])), "distinct actual canonical tree observations " + Arrays.toString(pair));
            }
            check(observations.get("n8").equals(observations.get("n12")), "repeated equal barrier tree has equal observation");
            reuseControl(run);
        } else if (fixture.startsWith("arity-")) {
            for (String kind : List.of("p", "f")) check(observations.get(kind + "0").equals(observations.get(kind + "3")),
                    "duplicate-only identity observation control");
        }
    }

    private static List<Object> transition(Capture c) throws Exception {
        Method next = method(IRAgent.class, "nextTov", Map.class, AugmentedNode.class);
        Method select = method(IRAgent.class, "downlinksFor", AugmentedNode.class, int.class, Opcode.class);
        IRAgent agent = new IRAgent(c.graph());
        Map<AugmentedNode, Integer> counters = new IdentityHashMap<>();
        int max = c.graph().getTimeOfVisitMap().get(c.node());
        check(max == 1 && c.visit() == 1, "actual fresh CALL maximum visit");
        int before = counters.getOrDefault(c.node(), 0);
        int first = (Integer) invoke(next, null, counters, c.node());
        int after = counters.get(c.node());
        check(first == 1 && after == 1, "executable initial counter advance");
        Object selected = invoke(select, agent, c.node(), first, Opcode.CALL);
        boolean firstAccept = selected instanceof List<?> && ((List<?>) selected).size() == symbol(c).getDeclaredArity() + 2;
        check(firstAccept, "first actual complete CALL accepted");
        // Advance another owner between uses; its independent counter cannot reset this one.
        AugmentedNode other = c.graph().getRoot();
        check(other != c.node(), "independent interleaved owner");
        check((Integer) invoke(next, null, counters, other) == 1 && counters.get(c.node()) == after, "other-owner transition isolation");
        int second = (Integer) invoke(next, null, counters, c.node());
        int secondAfter = counters.get(c.node());
        check(second == 2 && secondAfter == 2, "executable consumed counter advance");
        reject("referenced more than once", IllegalStateException.class, () -> invoke(select, agent, c.node(), second, Opcode.CALL));
        counters.put(c.node(), Integer.MAX_VALUE - 1);
        check((Integer) invoke(next, null, counters, c.node()) == Integer.MAX_VALUE,
                "last valid signed-int increment, no wraparound premise");
        reject("referenced more than once", IllegalStateException.class,
                () -> invoke(select, agent, c.node(), counters.get(c.node()), Opcode.CALL));
        return List.of(before, first, after, firstAccept, second, secondAfter, false, max);
    }

    private static void metadataControls(EGraphNode ir) throws Exception {
        int arity = ir.getDeclaredArity();
        // Deliberate metadata corruption bypasses the already sealed arena.
        // Restore before any graph/certificate operation; test CallMetadata itself.
        Field field = EGraphNode.class.getDeclaredField("declaredArity");
        field.setAccessible(true);
        try {
            field.setInt(ir, arity + 1);
            reject("child count disagrees with declared arity", IllegalStateException.class, () -> CallMetadata.require(ir));
            if (arity > 0) {
                field.setInt(ir, arity - 1);
                reject("child count disagrees with declared arity", IllegalStateException.class, () -> CallMetadata.require(ir));
            }
        } finally { field.setInt(ir, arity); }
        CallMetadata.require(ir);
    }

    private static void certificateControls(EGraphNode ir, CallOccurrenceCertificate cert) throws Exception {
        List<OnePort> args = cert.orderedArguments();
        if (args.size() < 2) return;
        for (int i = 1; i < args.size(); i++) {
            if (args.get(0).equals(args.get(i))) continue;
            List<OnePort> wrong = new ArrayList<>(args);
            Collections.swap(wrong, 0, i);
            reject("argument endpoint differs", IllegalArgumentException.class,
                    () -> CallOccurrenceCertificate.create(CallMetadata.require(ir), cert.sourcePath(), cert.sourceEndpoint(), wrong));
            wrong.set(0, wrong.get(i));
            reject("argument endpoint differs", IllegalArgumentException.class,
                    () -> CallOccurrenceCertificate.create(CallMetadata.require(ir), cert.sourcePath(), cert.sourceEndpoint(), wrong));
            break;
        }
    }

    private static void reuseControl(Run run) throws Exception {
        Capture parent = run.visitor().captures.stream().filter(c -> c.graph().getRoot().getSymbol().getName().equals("n5")
                && symbol(c).getSourceName().equals("g")).findFirst().orElseThrow();
        List<MASGEdge> edges = parent.node().getDownlinksAtTimeOfVisit(parent.graph(), parent.visit());
        MASGEdge saved = edges.get(2);
        check(edges.get(1).getTarget() != saved.getTarget(), "valid distinct nested source occurrences before control");
        try {
            edges.set(2, new MASGEdge(parent.node(), edges.get(1).getTarget(), 3, 1));
            reject("referenced more than once", IllegalStateException.class, () -> Canonical.prepare(parent.graph()));
        } finally { edges.set(2, saved); }
        Canonical.prepare(parent.graph());
    }

    private static void parserControls() throws Exception {
        for (boolean imported : List.of(false, true)) {
            for (boolean truncate : List.of(false, true)) {
                String source = imported ? importedSource() : source(2);
                CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
                ModelUnit model = new ModelUnit(null, module);
                Set<Call> calls = Collections.newSetFromMap(new IdentityHashMap<>());
                collectCalls(model, calls, Collections.newSetFromMap(new IdentityHashMap<>()));
                Call call = calls.stream().filter(c -> c.getName().equals(imported ? "ord/lt" : "p")).findFirst().orElseThrow();
                List<ExprOrFormula> args = new ArrayList<>(call.getArguments());
                if (truncate) args.remove(args.size() - 1); else args.add(args.get(0));
                call.setArguments(args);
                reject(imported ? "arity disagrees with imported declaration" : "arity disagrees with declaration",
                        IllegalStateException.class, () -> new MASGVisitor(new GlobalVariables(), module).visit(model, null));
            }
        }
    }

    private static void ledgerControls() throws Exception {
        check(ledger().size() == 20, "independent pinned library signature census");
        check(AlloyLibraryCallableLedger.require("util/integer", "max", CallSymbol.Kind.EXPRESSION, 0).arity() == 0
                && AlloyLibraryCallableLedger.require("util/integer", "max", CallSymbol.Kind.EXPRESSION, 1).arity() == 1,
                "independent overload entries, not observed-arity synthesis");
        reject("arity disagrees with imported declaration", IllegalStateException.class,
                () -> AlloyLibraryCallableLedger.require("util/integer", "max", CallSymbol.Kind.EXPRESSION, 2));
        reject("lacks an independently pinned declaration", IllegalStateException.class,
                () -> AlloyLibraryCallableLedger.require("foreign/module", "first", CallSymbol.Kind.EXPRESSION, 0));
        reject("lacks an independently pinned declaration", IllegalStateException.class,
                () -> AlloyLibraryCallableLedger.require("util/ordering", "first", CallSymbol.Kind.FORMULA, 0));
    }

    private static void collectCalls(Node node, Set<Call> calls, Set<Node> seen) {
        if (!seen.add(node)) return;
        if (node instanceof Call call) calls.add(call);
        for (Node child : node.getChildren()) collectCalls(child, calls, seen);
    }
    private interface Attempt { Object run() throws Exception; }
    private static void reject(String boundary, Class<? extends Throwable> type, Attempt attempt) throws Exception {
        try { attempt.run(); }
        catch (Exception error) {
            check(error.getClass() == type && error.getMessage() != null && error.getMessage().contains(boundary),
                    "rejection must occur at relevant boundary: " + boundary + ", got " + error);
            rejects++;
            return;
        }
        throw new AssertionError("Accepted invalid control at " + boundary);
    }
    private static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
        Method result = owner.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }
    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw error;
        }
    }
    private static CallSymbol symbol(Capture capture) { return (CallSymbol) capture.node().getSymbol(); }
    private static long owner(AugmentedNode node) {
        double value = node.getSemantic();
        check(value >= 0 && value == (long) value, "integral observed owner token");
        return (long) value;
    }
    private static JSONArray array(Object... values) { return new JSONArray(Arrays.asList(values)); }
    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}

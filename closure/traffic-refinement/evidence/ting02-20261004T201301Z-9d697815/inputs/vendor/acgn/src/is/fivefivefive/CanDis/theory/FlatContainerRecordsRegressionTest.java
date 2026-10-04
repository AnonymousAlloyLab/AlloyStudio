package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONArray;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** Complete finite records, with acceptance observed only at public verifier calls. */
public final class FlatContainerRecordsRegressionTest {
    private static final List<String> ROWS = new ArrayList<>();
    private static int checks;
    private static List<String> dictionary = List.of();
    private FlatContainerRecordsRegressionTest() { }

    private record Tree(int atom, List<Tree> children) {
        static Tree leaf(int i) { return new Tree(i, List.of()); }
        static Tree app(Tree... children) { return new Tree(-1, List.of(children)); }
        List<Integer> word() {
            return atom >= 0 ? List.of(atom) : children.stream().flatMap(t -> t.word().stream()).toList();
        }
        FlatInput build(InstantiatedOperator op, TypedSlotContext ctx, List<OnePort> atoms) {
            return atom >= 0 ? new FlatLeaf(atoms.get(atom))
                    : new FlatApplication(op, ctx, children.stream().map(t -> t.build(op, ctx, atoms)).toList());
        }
    }
    private static List<Tree> trees() {
        Tree a = Tree.leaf(1), b = Tree.leaf(0);
        return List.of(Tree.app(Tree.app(Tree.app(a,b),a),b),
                Tree.app(a,Tree.app(b,Tree.app(a,b))), Tree.app(Tree.app(a,b),Tree.app(a,b)));
    }
    public static void main(String[] args) throws Exception {
        check(Runtime.version().feature() == 17, "JDK17 required");
        check(args.length <= 1, "usage [observations.tsv]");
        ROWS.clear();
        ROWS.add("case\tfixture\tvariant\tcarrier\tbinding\trecord\taccepted\tstage");
        String old = System.getProperty("acgn.provenance.testOverride");
        try {
            System.setProperty("acgn.provenance.testOverride", "true");
            for (int family = 0; family < 2; family++)
                for (int shape = 0; shape < 3; shape++) fixture(family, shape);
            for (int word = 0; word < 4; word++) fixture(2, word);
            traceCases();
        } finally {
            if (old == null) System.clearProperty("acgn.provenance.testOverride");
            else System.setProperty("acgn.provenance.testOverride", old);
        }
        check(ROWS.size() == 312, "311 frozen records observations");
        if (args.length == 1) Files.write(Path.of(args[0]), ROWS, StandardCharsets.UTF_8);
        System.out.println("FlatContainerRecordsRegressionTest passed: observations=" + (ROWS.size()-1) + " checks=" + checks);
    }

    private static InstantiatedOperator operator(int family) {
        boolean bag = family != 0;
        GraphType type = family == 1 ? GraphType.INT : GraphType.BOOL;
        Opcode code = family == 0 ? Opcode.AND : family == 1 ? Opcode.IPLUS : Opcode.IFF;
        PortSchema schema = bag ? new BagPortSchema(family == 2 ? ArityPolicy.exact(2) : ArityPolicy.nonemptyVariadic(),
                new OnePortSchema(type)) : new SetPortSchema(ArityPolicy.nonemptyVariadic(), new OnePortSchema(type));
        List<ContainerLawCertificate> certs = new ArrayList<>();
        for (var law : ContainerLawCertificate.Law.values()) {
            if (law == ContainerLawCertificate.Law.UNIT || (bag && law == ContainerLawCertificate.Law.IDEMPOTENCY)
                    || (family == 2 && law != ContainerLawCertificate.Law.COMMUTATIVITY)) continue;
            certs.add(AlloyLawRegistry.issue(profile(family), code, "ALLOY/" + code, type, PortPath.at(0), schema, law));
        }
        return OperatorDeclaration.monomorphic("ALLOY/" + code, List.of(schema), type,
                Map.of(PortPath.at(0), ContainerLawDeclaration.certified(schema, certs)), family == 2 ? null : 0).instantiateMonomorphic();
    }
    private static SemanticProfile profile(int family) {
        return family == 1 ? SemanticProfile.alloyModular() : SemanticProfile.alloyOverflowForbidding();
    }
    private static void fixture(int family, int shape) throws Exception {
        String fixture = (family == 0 ? "flat-set-" : family == 1 ? "flat-bag-" : "container-bag-") + shape;
        InstantiatedOperator op = operator(family);
        TypedSlotContext ctx = TypedSlotContext.empty();
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile(family), sink);
        List<OnePort> atoms = new ArrayList<>();
        for (String name : List.of("records-a", "records-b")) {
            InstantiatedOperator leaf = OperatorDeclaration.monomorphic(name, List.of(), op.outputType(), Map.of(), null).instantiateMonomorphic();
            var invocation = graph.insertNode(TypedENode.construct(leaf, ctx, List.of()), graph.coherentWitnessFamily()).returnedInvocation();
            atoms.add(OnePort.invocation(ctx, invocation));
        }
        atoms.sort(Comparator.comparing(OnePort::structuralKey));
        check(atoms.get(0).structuralKey().stableString().compareTo(atoms.get(1).structuralKey().stableString()) < 0,
                "structural and serialized key rankings agree on this exact alphabet");
        List<Integer> word = family == 2 ? List.of(shape / 2, shape % 2) : trees().get(shape).word();
        List<OnePort> inputs = word.stream().map(atoms::get).toList();
        var ledger = ConstructionSourceLedger.builder(profile(family));
        List<FlatConstructionCertificate> flats = new ArrayList<>();
        List<ContainerConstructionCertificate> containers = new ArrayList<>();
        CertifiedInsertionResult root;
        TypedEqualityCertificate certificate;
        ContainerApplicationTrace trace;
        if (family == 2) {
            ledger.recordContainer(op, PortPath.at(0), ctx, inputs);
            var built = TypedENode.constructContainerCertified(op, PortPath.at(0), ctx, inputs, profile(family));
            containers.add(built.certificate()); certificate = built.certificate(); trace = built.certificate().containerTrace();
            root = graph.insertNodeConstructed(built, graph.coherentWitnessFamily());
        } else {
            FlatApplication source = (FlatApplication) trees().get(shape).build(op, ctx, atoms);
            ledger.recordFlat(source);
            var built = TypedENode.flatConstructCertified(source, ignored -> { throw new AssertionError("mixed head"); }, profile(family));
            flats.add(built.certificate()); certificate = built.certificate(); trace = built.certificate().containerTrace();
            root = graph.insertNodeConstructed(built, graph.coherentWitnessFamily());
        }
        var witnesses = graph.coherentWitnessFamily();
        var unfolding = graph.finiteUnfoldingOracle(witnesses, new FiniteUnfoldingBounds(3, 64))
                .enumerate(root.returnedInvocation()).stream().min(Comparator.comparingInt(FiniteUnfoldingTree::height)).orElseThrow();
        Map<String,List<ContainerLawDeclaration>> laws = Map.of(op.operator(), List.of(op.lawForPath(PortPath.at(0))));
        var artifact = new CertifiedSemanticArtifact(root.returnedInvocation(), graph.classes(), witnesses, List.of(unfolding),
                laws, flats, containers, ledger.build(), profile(family));
        var provenance = CertificateProvenance.capture("fixture/" + fixture, fixture.getBytes(StandardCharsets.UTF_8),
                "flat-container-records-v1;" + CertificateTheoryManifest.VERSION);
        check(provenance.testOnly(), "no publication authority");
        var session = new CertificateExportSession(sink, graph, artifact, unfolding.normalizedTermKey(), laws, provenance, "flat-container-records-v1");
        Path file = Files.createTempFile("acgn-flat-records-", ".acgncert");
        byte[] bytes;
        try { session.write(file); bytes = Files.readAllBytes(file); } finally { Files.deleteIfExists(file); }
        Wire.Node bundle = Codec.decode(bytes, Limits.defaults());
        check(find(bundle, "call-occurrence").isEmpty(), "independently empty fixture CALL census");
        Wire.Node record = only(find(bundle, family == 2 ? "container-construction" : "flat-construction"));
        Wire.Node wireTrace = only(find(record, "container-trace"));
        List<String> ids = new ArrayList<>(Collections.nCopies(2, ""));
        for (int i = 0; i < inputs.size(); i++) {
            String id = wireTrace.child(i).scalar(0);
            if (!ids.get(word.get(i)).isEmpty()) check(ids.get(word.get(i)).equals(id), "repeat identity");
            ids.set(word.get(i), id);
        }
        // Unused alphabet identities are not needed to decode this fixture.
        for (int i = 0; i < 2; i++) if (ids.get(i).isEmpty()) ids.set(i, "unused-" + i);
        check(!ids.get(0).equals(ids.get(1)), "distinct equality classes");
        int endpoint = family == 2 ? 5 : 6;
        check(record.scalar(0).equals(certificate.structuralKey().stableString())
                && record.scalar(1).equals(profile(family).fingerprint())
                && record.scalar(3).equals("0/0")
                && record.scalar(endpoint).equals(certificate.leftEndpoint().structuralKey().stableString())
                && record.scalar(endpoint+1).equals(certificate.rightEndpoint().structuralKey().stableString()), "producer endpoint binding");
        List<String> scalars = new ArrayList<>(List.of(record.scalar(2), wireTrace.scalar(1), wireTrace.scalar(0),
                op.structuralKey().stableString(), TheoryKeys.context(ctx).stableString(), trace.schema().structuralKey().stableString()));
        scalars.addAll(record.scalars());
        Wire.Node binding = new Wire.Node("bindings", scalars, List.of(
                new Wire.Node("ids", ids, List.of()), new Wire.Node("keys", atoms.stream().map(a -> a.structuralKey().stableString()).toList(), List.of())));
        PublicReplay verifier = new PublicReplay(bytes, bundle);
        observe(fixture, "original", family == 0 ? 2 : 1, binding, record, verifier.verify(bytes));
        if (shape == 0 && family < 2 || family == 2 && shape == 2) {
            List<String> sites = family == 2 ? List.of("", "0", "0.0", "1", "1.0", "1.2")
                    : List.of("", "0", "0.0", "0.0.0.0", "1", "1.0", "2", "2.0", "2.4");
            for (String site : sites) {
                Wire.Node target = at(record, site);
                for (Map.Entry<String,Wire.Node> mutation : mutations(target).entrySet()) {
                    String label = (site.isEmpty() ? "root" : site) + ":" + mutation.getKey();
                    Wire.Node changed = replace(record, target, mutation.getValue());
                    check(!changed.equals(record), "nonvacuous public-byte mutation " + label);
                    byte[] candidate = Codec.encode(replace(bundle, record, changed));
                    Codec.decode(candidate, Limits.defaults());
                    observe(fixture, label, family == 0 ? 2 : 1, null, changed, verifier.verify(candidate));
                }
            }
        }
    }

    private static Wire.Node at(Wire.Node node, String path) {
        if (path.isEmpty()) return node;
        for (String part : path.split("\\.")) node = node.child(Integer.parseInt(part));
        return node;
    }
    private static Map<String,Wire.Node> mutations(Wire.Node n) {
        Map<String,Wire.Node> out = new LinkedHashMap<>();
        out.put("tag", new Wire.Node("wrong-record-tag", n.scalars(), n.children()));
        for (int i = 0; i < n.scalars().size(); i++) {
            List<String> ss = new ArrayList<>(n.scalars()); ss.remove(i);
            out.put("omit-s" + i, new Wire.Node(n.tag(), ss, n.children()));
            out.put("sub-s" + i, scalar(n, i, "wrong-field"));
        }
        if (!n.scalars().isEmpty()) {
            List<String> ss = new ArrayList<>(n.scalars()); ss.add(0, ss.get(0));
            out.put("duplicate-s", new Wire.Node(n.tag(), ss, n.children()));
        }
        if (n.scalars().size() > 1) {
            List<String> ss = new ArrayList<>(n.scalars()); Collections.swap(ss, 0, ss.size()-1);
            out.put("reorder-s", new Wire.Node(n.tag(), ss, n.children()));
        }
        for (int i = 0; i < n.children().size(); i++) {
            List<Wire.Node> cs = new ArrayList<>(n.children()); cs.remove(i);
            out.put("omit-c" + i, new Wire.Node(n.tag(), n.scalars(), cs));
        }
        if (!n.children().isEmpty()) {
            List<Wire.Node> cs = new ArrayList<>(n.children()); cs.add(0, cs.get(0));
            out.put("duplicate-c", new Wire.Node(n.tag(), n.scalars(), cs));
        }
        if (n.children().size() > 1) {
            List<Wire.Node> cs = new ArrayList<>(n.children()); Collections.reverse(cs);
            out.put("reorder-c", new Wire.Node(n.tag(), n.scalars(), cs));
        }
        return out;
    }
    private static void observe(String fixture, String label, int carrier, Wire.Node binding, Wire.Node record, ReplayResult result) {
        boolean accepted = result.outcome().equals("VERIFIED") && result.code().equals("NONE");
        check(label.equals("original") ? accepted : result.outcome().equals("REJECTED"), fixture + "/" + label + ": " + result);
        String packed = binding == null ? "[]" : bindingJson(binding, record);
        row(fixture, label, carrier, packed, json(record), accepted, result.outcome()+":"+result.code());
    }

    private static void traceCases() {
        var slots = List.of(TypedSlot.source(GraphType.BOOL, 95000), TypedSlot.source(GraphType.BOOL, 95001));
        var ctx = TypedSlotContext.of(slots);
        List<OnePort> atoms = slots.stream().map(s -> OnePort.slot(ctx,s)).sorted(Comparator.comparing(OnePort::structuralKey)).toList();
        List<List<Integer>> words = List.of(List.of(), List.of(0), List.of(1,0,1,0), List.of(0,1), List.of(0,0));
        for (int carrier = 0; carrier < 3; carrier++) for (int wi = 0; wi < words.size(); wi++) {
            OnePortSchema element = new OnePortSchema(GraphType.BOOL);
            ArityPolicy arity = ArityPolicy.atLeast(0);
            PortSchema schema = carrier == 0 ? new SeqPortSchema(arity,element)
                    : carrier == 1 ? new BagPortSchema(arity,element) : new SetPortSchema(arity,element);
            var inputs = words.get(wi).stream().map(atoms::get).toList();
            PortValue output = carrier == 0 ? new SeqPort((SeqPortSchema)schema,ctx,inputs)
                    : carrier == 1 ? new BagPort((BagPortSchema)schema,ctx,inputs) : new SetPort((SetPortSchema)schema,ctx,inputs);
            var trace = ContainerApplicationTrace.of(schema, ctx, inputs, output);
            List<Wire.Node> cs = new ArrayList<>();
            for (var input : trace.inputOccurrences()) cs.add(new Wire.Node("trace-input",List.of(Integer.toString(atoms.indexOf(input))),List.of()));
            for (int i=0;i<trace.outputOccurrences().size();i++) {
                List<String> ss = new ArrayList<>(List.of(Integer.toString(atoms.indexOf(trace.outputOccurrences().get(i)))));
                ss.addAll(trace.outputFibers().get(i).stream().map(Object::toString).toList());
                cs.add(new Wire.Node("trace-output",ss,List.of()));
            }
            Wire.Node record = new Wire.Node("container-trace", List.of("schema","context",Integer.toString(inputs.size()),
                    Integer.toString(trace.outputOccurrences().size()),trace.structuralKey().stableString()),cs);
            Wire.Node binding = new Wire.Node("bindings",List.of("operator","context","schema","operator-key",
                    TheoryKeys.context(ctx).stableString(),schema.structuralKey().stableString()),List.of(
                    new Wire.Node("ids",List.of("0","1"),List.of()),
                    new Wire.Node("keys",atoms.stream().map(a -> a.structuralKey().stableString()).toList(),List.of())));
            String packed = bindingJson(binding, record);
            row("trace-" + carrier + "-" + wi, "original", carrier, packed, json(record), trace != null, "LOCAL_TRACE");
        }
    }
    private static JSONArray data(Wire.Node n) {
        JSONArray cs = new JSONArray(); for (Wire.Node c : n.children()) cs.put(data(c));
        JSONArray ss = new JSONArray();
        for (String s : n.scalars()) {
            if (s.length() >= 64) {
                int index = Collections.binarySearch(dictionary, s);
                check(index >= 0, "complete dictionary for actual wire scalar");
                ss.put(index);
            } else ss.put(s);
        }
        return new JSONArray().put(n.tag()).put(ss).put(cs);
    }
    private static void collect(Wire.Node n, Set<String> values) {
        for (String s : n.scalars()) if (s.length() >= 64) values.add(s);
        for (Wire.Node c : n.children()) collect(c, values);
    }
    private static String bindingJson(Wire.Node b, Wire.Node record) {
        Set<String> values = new TreeSet<>(); collect(b, values); collect(record, values);
        dictionary = List.copyOf(values);
        return new JSONArray().put(data(b)).put(new JSONArray(dictionary)).toString();
    }
    private static String json(Wire.Node n) { return data(n).toString(); }
    private static void row(String fixture,String variant,int carrier,String binding,String record,boolean accepted,String stage) {
        List<String> fields = List.of(Integer.toString(ROWS.size()-1),fixture,variant,Integer.toString(carrier),binding,record,Boolean.toString(accepted),stage);
        for (String field : fields) check(!field.contains("\t") && !field.contains("\n") && !field.contains("\r"), "TSV field");
        ROWS.add(String.join("\t",fields));
    }
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }

    // Only public APIs cross the optional verifier-runtime boundary. Local wire
    // records are immutable decoded data; no verifier state or private fields are mutated.
    private static Class<?> api(String name) {
        try { return Class.forName("org.acgn.cert." + name); }
        catch (ClassNotFoundException error) { throw new IllegalStateException("verifier classes required at runtime", error); }
    }
    private static Object invoke(Object target, Class<?> owner, String name, Class<?>[] types, Object... args) {
        try { return owner.getMethod(name, types).invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static Object get(Object target, String name) { return invoke(target, target.getClass(), name, new Class<?>[0]); }
    private static final class Limits {
        static Object defaults() { return invoke(null, api("Limits"), "defaults", new Class<?>[0]); }
    }
    private static final class Wire {
        private record Node(String tag, List<String> scalars, List<Node> children) {
            Node { scalars = List.copyOf(scalars); children = List.copyOf(children); }
            String scalar(int index) { return scalars.get(index); }
            Node child(int index) { return children.get(index); }
        }
        @SuppressWarnings("unchecked")
        static Node local(Object node) {
            return new Node((String) get(node, "tag"), (List<String>) get(node, "scalars"),
                    ((List<Object>) get(node, "children")).stream().map(Wire::local).toList());
        }
        static Object foreign(Node node) {
            return invoke(null, api("Wire"), "node", new Class<?>[] {String.class, List.class, List.class},
                    node.tag(), node.scalars(), node.children().stream().map(Wire::foreign).toList());
        }
        static String contentId(Node node) { return (String) invoke(null, api("Wire"), "contentId", new Class<?>[] {api("Wire$Node")}, foreign(node)); }
    }
    private static final class Codec {
        static Wire.Node decode(byte[] bytes, Object limits) {
            return Wire.local(invoke(null, api("Codec"), "decode", new Class<?>[] {byte[].class, api("Limits")}, bytes, limits));
        }
        static byte[] encode(Wire.Node node) { return (byte[]) invoke(null, api("Codec"), "encode", new Class<?>[] {api("Wire$Node")}, Wire.foreign(node)); }
    }
    private record ReplayResult(String outcome, String code, String detail) { }
    private static final class PublicReplay {
        private final Object verifier, full, policy;
        PublicReplay(byte[] originalBytes, Wire.Node original) throws ReflectiveOperationException {
            Object bundle = invoke(null, api("Bundle"), "parse", new Class<?>[] {api("Wire$Node")}, Wire.foreign(original));
            Object trusted = invoke(null, api("VerificationPolicy"), "trust", new Class<?>[] {String.class}, get(bundle, "theoryDigest"));
            Object commitment = invoke(null, api("CallOccurrenceCommitment"), "inspect", new Class<?>[] {byte[].class, api("Limits")},
                    originalBytes, Limits.defaults());
            policy = invoke(trusted, api("VerificationPolicy"), "withCallOccurrenceCommitment", new Class<?>[] {api("CallOccurrenceCommitment")}, commitment);
            verifier = api("IndependentVerifier").getConstructor().newInstance();
            full = api("Profile").getField("FULL").get(null);
        }
        ReplayResult verify(byte[] bytes) {
            Object result = invoke(verifier, api("IndependentVerifier"), "verify",
                    new Class<?>[] {byte[].class, api("Profile"), api("VerificationPolicy")}, bytes, full, policy);
            return new ReplayResult(get(result, "outcome").toString(), get(result, "code").toString(), (String) get(result, "detail"));
        }
    }
    private static Wire.Node scalar(Wire.Node node, int index, String value) {
        List<String> scalars = new ArrayList<>(node.scalars()); scalars.set(index, value);
        return new Wire.Node(node.tag(), scalars, node.children());
    }
    private static List<Wire.Node> find(Wire.Node node, String tag) {
        List<Wire.Node> result = new ArrayList<>(); if (node.tag().equals(tag)) result.add(node);
        for (Wire.Node child : node.children()) result.addAll(find(child, tag)); return result;
    }
    private static Wire.Node only(List<Wire.Node> nodes) { check(nodes.size() == 1, "unique wire target, got " + nodes.size()); return nodes.get(0); }
    private static Wire.Node replace(Wire.Node node, Wire.Node old, Wire.Node replacement) {
        if (node == old) return replacement;
        List<Wire.Node> children = node.children().stream().map(c -> replace(c, old, replacement)).toList();
        List<String> scalars = new ArrayList<>(node.scalars());
        if (node.tag().equals("manifest")) scalars.set(1, Wire.contentId(children.get(1)));
        return new Wire.Node(node.tag(), scalars, children);
    }

}

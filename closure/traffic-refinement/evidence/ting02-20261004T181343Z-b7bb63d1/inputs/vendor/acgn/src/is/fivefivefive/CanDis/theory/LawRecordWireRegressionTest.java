package is.fivefivefive.CanDis.theory;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded P3-04: real registry issuance, writer bytes, public standalone verification. */
public final class LawRecordWireRegressionTest {
    private static final List<String> OPS = List.of("AND", "OR", "PLUS", "INTERSECT", "IPLUS", "MUL",
            "EQUALS", "NOT_EQUALS", "IFF", "DISJOINT");
    private static final List<String> RECOMPUTED = List.of("operator", "result", "element", "carrier",
            "policy", "path", "law", "profile", "digest", "parameter", "endpoints");
    private static final List<String> ROWS = new ArrayList<>(List.of(
            "case\tfixture\tprofile\top\ttype\tmutation\tprofileKey\tresultKey\telementKey\tschemaKey"
            + "\tproducer\twriter\tcandidate\tvocabulary\touterFresh\toutcome\tdetail"));
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    // Only public verifier methods/constructors. src-only builds need no verifier dependency.
    private static Class<?> cls(String n) throws Exception { return Class.forName("org.acgn.cert." + n); }
    private static Object api(Object target, Class<?> owner, String name, Class<?>[] types, Object... args)
            throws Exception {
        try { return owner.getMethod(name, types).invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
    private static Object get(Object o, String name) throws Exception {
        return api(o, o.getClass(), name, new Class<?>[0]);
    }
    private record W(String tag, List<String> scalars, List<W> children) {
        W { scalars = List.copyOf(scalars); children = List.copyOf(children); }
        W child(int i) { return children.get(i); }
        String scalar(int i) { return scalars.get(i); }
        W at(int i, W w) { var c = new ArrayList<>(children); c.set(i, w); return new W(tag, scalars, c); }
        W field(int i, String s) { var f = new ArrayList<>(scalars); f.set(i, s); return new W(tag, f, children); }
    }
    @SuppressWarnings("unchecked")
    private static W read(Object node) throws Exception {
        var children = new ArrayList<W>();
        for (Object c : (List<Object>) get(node, "children")) children.add(read(c));
        return new W((String) get(node, "tag"), (List<String>) get(node, "scalars"), children);
    }
    private static Object wire(W n) throws Exception {
        var children = new ArrayList<Object>();
        for (W c : n.children) children.add(wire(c));
        return api(null, cls("Wire"), "node", new Class<?>[]{String.class, List.class, List.class},
                n.tag, n.scalars, children);
    }
    private static W decode(byte[] bytes) throws Exception {
        return read(api(null, cls("Codec"), "decode", new Class<?>[]{byte[].class, cls("Limits")},
                bytes, api(null, cls("Limits"), "defaults", new Class<?>[0])));
    }
    private static byte[] encode(W n) throws Exception {
        return (byte[]) api(null, cls("Codec"), "encode", new Class<?>[]{cls("Wire$Node")}, wire(n));
    }
    private static String contentId(W n) throws Exception {
        return (String) api(null, cls("Wire"), "contentId", new Class<?>[]{cls("Wire$Node")}, wire(n));
    }
    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String packed(List<String> s) { return String.join("|", s.stream().map(LawRecordWireRegressionTest::b64).toList()); }
    private static String table(List<W> nodes) {
        return String.join(";", nodes.stream().map(n -> b64(n.tag) + ":" + n.children.size() + ":" + packed(n.scalars)).toList());
    }
    private static String tree(W n) {
        return key(n.tag, n.scalars, n.children.stream().map(LawRecordWireRegressionTest::tree).toList());
    }
    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
    private static String framed(String... fields) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String frame(String s) { return s.length() + ":" + s; }
    private static String key(String tag, List<String> scalars, List<String> children) {
        return frame(tag) + "[" + scalars.size() + ":" + String.join("", scalars.stream().map(LawRecordWireRegressionTest::frame).toList())
                + "]{" + children.size() + ":" + String.join("", children.stream().map(LawRecordWireRegressionTest::frame).toList()) + "}";
    }
    private static GraphType type(String t) {
        GraphType a = GraphType.constructor("AlloySig:LawA"), b = GraphType.constructor("AlloySig:LawB");
        return switch (t) {
            case "bool" -> GraphType.BOOL;
            case "int" -> GraphType.INT;
            case "rel" -> GraphType.relation(a);
            case "rel2" -> GraphType.relation(a, b);
            case "empty" -> GraphType.constructor("AlloyEmptyRelation$arity=1");
            case "union" -> GraphType.constructor("AlloyRelationUnion", GraphType.relation(a), GraphType.relation(b));
            case "comparable" -> GraphType.constructor("AlloyComparableCarrier", GraphType.relation(a), GraphType.relation(b));
            case "opaque" -> GraphType.constructor("LawOpaque");
            default -> throw new AssertionError(t);
        };
    }
    private static SemanticProfile profile(int p) throws Exception {
        if (p < 2) return p == 0 ? SemanticProfile.alloyOverflowForbidding() : SemanticProfile.alloyModular();
        int width = p == 2 ? 3 : 6;
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {} pred wire { some A } run wire for 3 but " + width + " Int");
        var options = new A4Options(); options.noOverflow = p == 2;
        return AlloySemanticProfileFactory.fromExactlyOne(module, module.getAllCommands(), options);
    }
    private static List<ContainerLawCertificate.Law> laws(String op, int p) {
        if (OPS.indexOf(op) < 4) return List.of(ContainerLawCertificate.Law.ASSOCIATIVITY,
                ContainerLawCertificate.Law.COMMUTATIVITY, ContainerLawCertificate.Law.IDEMPOTENCY);
        if ((op.equals("IPLUS") || op.equals("MUL")) && p % 2 == 1)
            return List.of(ContainerLawCertificate.Law.ASSOCIATIVITY, ContainerLawCertificate.Law.COMMUTATIVITY);
        return List.of(ContainerLawCertificate.Law.COMMUTATIVITY);
    }
    private static String primary(String op) {
        return switch (op) { case "PLUS", "INTERSECT", "DISJOINT" -> "rel"; case "IPLUS", "MUL" -> "int"; default -> "bool"; };
    }
    private static String exactId(GraphType type) throws Exception {
        var children = new ArrayList<W>();
        for (GraphType arg : type.arguments()) children.add(new W("type-ref", List.of(exactId(arg)), List.of()));
        return contentId(new W("exact-type/content", List.of(type.kind().name(), type.symbol() == null ? "" : type.symbol()), children));
    }
    private static String schemaId(String key) throws Exception {
        return "schema/" + contentId(new W("schema-id", List.of(key), List.of()));
    }
    private static List<String> projection(ContainerLawCertificate c) throws Exception {
        var o = c.origin();
        return List.of(c.lawIndex().stableString(), c.authority().name(), c.operatorIdentity(), c.resultType().toString(),
                exactId(c.resultType()), c.schemaPath().toString(), c.law().name(), c.sourceTheoryDigest(),
                schemaId(c.schema().structuralKey().stableString()), c.schema().structuralKey().stableString(),
                c.lawParameter().stableString(), c.leftSourceEndpoint().stableString(), c.rightSourceEndpoint().stableString(),
                o.kind().name(), o.sourceArtifact(), o.declarationId(), Integer.toString(o.ordinal()));
    }
    private record Fixture(int p, String op, String t, SemanticProfile profile, GraphType result, GraphType element,
            PortSchema schema, List<W> producer, W root) {}

    private static Fixture fixture(int p, String name, String t, Path output) throws Exception {
        var profile = profile(p); GraphType element = type(t);
        GraphType result = Set.of("PLUS", "INTERSECT", "IPLUS", "MUL").contains(name) ? element : GraphType.BOOL;
        boolean set = OPS.indexOf(name) < 4, flat = laws(name, p).contains(ContainerLawCertificate.Law.ASSOCIATIVITY);
        ArityPolicy arity = flat || name.equals("DISJOINT") ? ArityPolicy.nonemptyVariadic() : ArityPolicy.exact(2);
        PortSchema schema = set ? new SetPortSchema(arity, new OnePortSchema(element)) : new BagPortSchema(arity, new OnePortSchema(element));
        var certificates = new ArrayList<ContainerLawCertificate>();
        var producer = new ArrayList<W>();
        for (var law : laws(name, p)) {
            var c = AlloyLawRegistry.issue(profile, Opcode.valueOf(name), "ALLOY/" + name, result, PortPath.at(0), schema, law);
            c.verifyLocal(); certificates.add(c); producer.add(new W("law-certificate", projection(c), List.of()));
        }
        producer.sort(Comparator.comparing(n -> n.scalar(0)));
        var declaration = ContainerLawDeclaration.certified(schema, certificates);
        var op = OperatorDeclaration.monomorphic("ALLOY/" + name, List.of(schema), result,
                Map.of(PortPath.at(0), declaration), flat ? 0 : null).instantiateMonomorphic();
        var sink = new RecordingCertificateTraceSink(); var graph = new TypedSlottedPortEGraph(profile, sink);
        var context = TypedSlotContext.empty(); var operands = new ArrayList<OnePort>();
        for (String leafName : List.of("law-wire-a", "law-wire-b")) {
            var leaf = OperatorDeclaration.monomorphic(leafName, List.of(), element, Map.of(), null).instantiateMonomorphic();
            var invocation = graph.insertNode(TypedENode.construct(leaf, context, List.of()), graph.coherentWitnessFamily()).returnedInvocation();
            operands.add(OnePort.invocation(context, invocation));
        }
        var ledger = ConstructionSourceLedger.builder(profile);
        var flats = new ArrayList<FlatConstructionCertificate>(); var containers = new ArrayList<ContainerConstructionCertificate>();
        CertifiedInsertionResult inserted;
        if (flat) {
            var source = new FlatApplication(op, context, operands.stream().map(FlatLeaf::new).toList());
            ledger.recordFlat(source);
            var built = TypedENode.flatConstructCertified(source, ignored -> { throw new AssertionError("unexpected mixed head"); }, profile);
            flats.add(built.certificate());
            inserted = graph.insertNodeConstructed(built, graph.coherentWitnessFamily());
        } else {
            ledger.recordContainer(op, PortPath.at(0), context, operands);
            var built = TypedENode.constructContainerCertified(op, PortPath.at(0), context, operands, profile);
            containers.add(built.certificate()); inserted = graph.insertNodeConstructed(built, graph.coherentWitnessFamily());
        }
        var witnesses = graph.coherentWitnessFamily();
        var unfolding = graph.finiteUnfoldingOracle(witnesses, new FiniteUnfoldingBounds(2, 32))
                .enumerate(inserted.returnedInvocation()).get(0);
        var registry = Map.of(op.operator(), List.of(declaration));
        var artifact = new CertifiedSemanticArtifact(inserted.returnedInvocation(), graph.classes(), witnesses,
                List.of(unfolding), registry, flats, containers, ledger.build(), profile);
        var provenance = CertificateProvenance.capture("fixture/law-record-wire", "law-wire".getBytes(StandardCharsets.UTF_8), "law-record-wire-v1");
        Path file = output.resolve("law.acgncert");
        new CertificateExportSession(sink, graph, artifact, unfolding.normalizedTermKey(), registry, provenance, "law-record-wire-v1").write(file);
        W root = decode(Files.readAllBytes(file));
        check(root.child(1).child(1).child(3).child(0).children.equals(producer), "actual full writer projection " + p + name + t);
        return new Fixture(p, name, t, profile, result, element, schema, producer, root);
    }

    // Coherent counterfeits recompute the inner parameter, index, endpoints and origin.
    private static W recompute(Fixture f, W original, String mutation) throws Exception {
        var s = new ArrayList<>(original.scalars);
        String profile = f.profile.structuralKey().stableString(), result = TheoryKeys.type(f.result).stableString();
        String element = TheoryKeys.type(f.element).stableString(), carrier = f.schema.kind().name();
        String arity = ContainerLawDeclaration.arityPolicy(f.schema).structuralKey().stableString();
        String op = f.op, path = "0/0", law = original.scalar(6), digest = AlloyLawRegistry.SOURCE_THEORY_DIGEST;
        switch (mutation) {
            case "operator" -> op = "IFF";
            case "result" -> { result = TheoryKeys.type(GraphType.INT).stableString(); s.set(3, "Int"); s.set(4, exactId(GraphType.INT)); }
            case "element" -> element = TheoryKeys.type(GraphType.INT).stableString();
            case "carrier" -> carrier = carrier.equals("SET") ? "BAG" : "SET";
            case "policy" -> arity = ArityPolicy.zeroOrMore().structuralKey().stableString();
            case "path" -> path = "0/1";
            case "law" -> law = "UNIT";
            case "profile" -> profile = SemanticProfile.alloyModular().structuralKey().stableString();
            case "digest" -> digest = "0".repeat(64);
            case "parameter", "endpoints" -> { }
            default -> throw new AssertionError(mutation);
        }
        String quotient = carrier.equals("SET") ? "COMMUTATIVE_IDEMPOTENT_SET" : "COMMUTATIVE_BAG";
        String schema = key("schema/" + carrier.toLowerCase(Locale.ROOT), List.of(quotient),
                List.of(arity, key("schema/one", List.of(), List.of(element))));
        String family = switch (law) {
            case "ASSOCIATIVITY" -> "all-legal-outer-nested-arities-and-splice-positions";
            case "COMMUTATIVITY" -> "all-admitted-sibling-permutations";
            case "IDEMPOTENCY" -> "all-admitted-quotient-surjections";
            default -> "exact-empty-fold-deletion";
        };
        String parameter = key("alloy-law-parameter-v1", List.of(op, path, law, family), List.of(profile, result, schema));
        if (mutation.equals("parameter")) parameter = key("wrong-parameter", List.of(), List.of());
        String index = key("container-law-index-v2", List.of("ALLOY_PROFILE_THEORY", "ALLOY/" + op, path, law, digest),
                List.of(profile, result, schema, parameter));
        s.set(0, index); s.set(2, "ALLOY/" + op); s.set(5, path); s.set(6, law); s.set(7, digest);
        s.set(8, schemaId(schema)); s.set(9, schema); s.set(10, parameter);
        s.set(11, key("container-law-source-endpoint", List.of(mutation.equals("endpoints") ? "right" : "left"), List.of(index)));
        s.set(12, key("container-law-source-endpoint", List.of("right"), List.of(index)));
        s.set(14, AlloyLawRegistry.VERSION + "/" + digest);
        s.set(15, "ALLOY/" + op + "@" + path + ":" + law + ":" + sha(parameter));
        s.set(16, Integer.toString(ContainerLawCertificate.Law.valueOf(law).ordinal()));
        return new W(original.tag, s, original.children);
    }

    private static void observe(Fixture f, String mutation) throws Exception {
        W root = f.root, manifest = root.child(1), vocabulary = manifest.child(1), evidence = vocabulary.child(3);
        var records = new ArrayList<>(evidence.child(0).children); W first = records.get(0);
        if (mutation.startsWith("field-")) {
            int i = Integer.parseInt(mutation.substring(6));
            records.set(0, first.field(i, Set.of(0, 9, 10, 11, 12).contains(i)
                    ? key("wrong", List.of(), List.of()) : first.scalar(i) + ":changed"));
            records.sort(Comparator.comparing(n -> n.scalar(0)));
        } else if (mutation.startsWith("omit-field-")) {
            var s = new ArrayList<>(first.scalars); s.remove(Integer.parseInt(mutation.substring(11)));
            records.set(0, new W(first.tag, s, List.of()));
        } else if (mutation.equals("duplicate-field")) {
            var s = new ArrayList<>(first.scalars); s.add(s.get(16)); records.set(0, new W(first.tag, s, List.of()));
        } else if (mutation.equals("order-fields")) {
            var s = new ArrayList<>(first.scalars); Collections.swap(s, 1, 2); records.set(0, new W(first.tag, s, List.of()));
        } else if (mutation.equals("tag")) records.set(0, new W("wrong", first.scalars, List.of()));
        else if (mutation.equals("child")) records.set(0, new W(first.tag, first.scalars, List.of(new W("extra", List.of(), List.of()))));
        else if (mutation.equals("omit-record")) records.remove(0);
        else if (mutation.equals("duplicate-record")) records.add(0, first);
        else if (mutation.equals("order-records")) Collections.reverse(records);
        else if (mutation.startsWith("recompute-")) {
            records.set(0, recompute(f, first, mutation.substring(10))); records.sort(Comparator.comparing(n -> n.scalar(0)));
        } else if (mutation.startsWith("registry-")) {
            String attack = mutation.substring(9);
            // All law rows agree with the changed vocabulary; registry matrix checks must reject first.
            for (int i = 0; i < records.size(); i++) records.set(i, recompute(f, records.get(i), attack));
            records.sort(Comparator.comparing(n -> n.scalar(0)));
            var operators = new ArrayList<>(vocabulary.child(1).children);
            var schemas = new ArrayList<>(vocabulary.child(0).children);
            W container = schemas.stream().filter(s -> s.scalar(0).equals(first.scalar(8))).findFirst().orElseThrow();
            if (attack.equals("carrier")) container = container.field(1, "BAG").field(4, "COMMUTATIVE_BAG");
            if (attack.equals("policy")) container = container.field(3, "AT_LEAST:0");
            if (attack.equals("element")) {
                String oneKey = key("schema/one", List.of(), List.of(TheoryKeys.type(GraphType.INT).stableString()));
                String oneId = schemaId(oneKey);
                schemas.add(new W("schema", List.of(oneId, "ONE", "Int", "FINITE:1", "RIGID"), List.of()));
                container = container.at(0, new W("schema-ref", List.of(oneId), List.of()));
            }
            container = container.field(0, records.get(0).scalar(8));
            W newContainer = container;
            if (schemas.stream().noneMatch(s -> s.scalar(0).equals(newContainer.scalar(0)))) schemas.add(container);
            String extraId = "operator/" + contentId(new W("operator-id", List.of("law-counterfeit/" + attack), List.of()));
            operators.add(new W("operator", List.of(extraId, attack.equals("result") ? "Int" : "Bool",
                    attack.equals("operator") ? "ALLOY/IFF" : "ALLOY/AND", "0/0"),
                    List.of(new W("schema-ref", List.of(container.scalar(0)), List.of()))));
            operators.sort(Comparator.comparing(n -> n.scalar(0)));
            schemas.sort(Comparator.comparing(n -> n.scalar(0)));
            vocabulary = vocabulary.at(1, new W("operators", List.of(), operators));
            vocabulary = vocabulary.at(0, new W("schemas", List.of(), schemas));
            if (attack.equals("result") || attack.equals("element")) {
                var types = new ArrayList<>(evidence.child(4).children);
                types.add(new W("exact-type", List.of(exactId(GraphType.INT), "INT", ""), List.of()));
                types.sort(Comparator.comparing(n -> n.scalar(0)));
                evidence = evidence.at(4, new W("exact-types", List.of(), types));
            }
        }
        evidence = evidence.at(0, new W("law-certificates", List.of(), records));
        vocabulary = vocabulary.at(3, evidence);
        String digest = contentId(vocabulary);
        root = root.at(1, manifest.at(1, vocabulary).field(1, digest));
        byte[] bytes = encode(root); W decoded = decode(bytes);
        boolean fresh = decoded.child(1).scalar(1).equals(contentId(decoded.child(1).child(1)));
        check(fresh, "refreshed outer vocabulary contentId");
        Object bundle = api(null, cls("Bundle"), "parse", new Class<?>[]{cls("Wire$Node")}, wire(decoded));
        Object policy = api(null, cls("VerificationPolicy"), "trust", new Class<?>[]{String.class}, get(bundle, "theoryDigest"));
        String subject = framed("call-occurrence-commitment-v1/subject", "fixture/law-record-wire", sha("law-wire"));
        Object commitment = cls("CallOccurrenceCommitment").getConstructor(String.class, String.class)
                .newInstance(subject, framed("call-occurrence-commitment-v1", subject));
        policy = api(policy, cls("VerificationPolicy"), "withCallOccurrenceCommitment", new Class<?>[]{cls("CallOccurrenceCommitment")}, commitment);
        Object result = api(cls("IndependentVerifier").getConstructor().newInstance(), cls("IndependentVerifier"), "verify",
                new Class<?>[]{byte[].class, cls("Profile"), cls("VerificationPolicy")}, bytes,
                api(null, cls("Profile"), "valueOf", new Class<?>[]{String.class}, "KERNEL"), policy);
        String status = get(result, "outcome").toString(), code = get(result, "code").toString(), detail = (String) get(result, "detail");
        check(status.equals(mutation.equals("base") ? "VERIFIED" : "REJECTED") && !code.equals("INTERNAL_ERROR"),
                f.p + "/" + f.op + "/" + f.t + "/" + mutation + ": " + result);
        if (mutation.startsWith("registry-")) check(code.equals("THEORY_MISMATCH") &&
                (detail.contains("fixed Alloy law matrix") || detail.contains("wrong exact result/element types")),
                "registry admissibility, not earlier graph typing: " + result);
        String vocabularySummary = b64(tree(new W("law-vocabulary", List.of(),
                List.of(vocabulary.child(0), vocabulary.child(1), evidence.child(4)))));
        ROWS.add(String.join("\t", Integer.toString(ROWS.size() - 1), f.p + ":" + f.op + ":" + f.t,
                Integer.toString(f.p), f.op, f.t, mutation, b64(f.profile.structuralKey().stableString()),
                b64(TheoryKeys.type(f.result).stableString()), b64(TheoryKeys.type(f.element).stableString()),
                b64(f.schema.structuralKey().stableString()), table(f.producer), table(f.root.child(1).child(1).child(3).child(0).children),
                table(records), vocabularySummary, Boolean.toString(fresh), status + ":" + code, b64(detail)));
    }

    private static void execute(Path output) throws Exception {
        check("\ud800\udc00".compareTo("\ue000") < 0 && "\ue000".compareTo("\ud800\udc00") > 0,
                "public Java UTF16 supplementary/BMP ordering");
        check("prefix".compareTo("prefix-more") < 0 && "same".compareTo("same") == 0,
                "public Java prefix/equality ordering");
        for (int p = 0; p < 4; p++) for (String op : OPS) {
            Fixture f = fixture(p, op, primary(op), output); observe(f, "base");
            if ((p == 0 && op.equals("AND")) || (p == 1 && op.equals("IPLUS")) || (p == 2 && op.equals("IFF"))) {
                for (int field = 0; field < 17; field++) observe(f, "field-" + field);
                for (String m : List.of("omit-record", "duplicate-record", "duplicate-field", "order-fields", "tag", "child")) observe(f, m);
                if (f.producer.size() > 1) observe(f, "order-records");
            }
            if (p == 0 && op.equals("AND")) {
                for (int field = 0; field < 17; field++) observe(f, "omit-field-" + field);
                for (String m : RECOMPUTED) observe(f, "recompute-" + m);
                for (String m : List.of("operator", "result", "element", "carrier", "policy")) observe(f, "registry-" + m);
            }
        }
        for (String t : List.of("int", "rel2", "empty", "union", "comparable")) observe(fixture(3, "PLUS", t, output), "base");
        for (String t : List.of("int", "rel", "opaque")) observe(fixture(2, "EQUALS", t, output), "base");
        check(ROWS.size() == 153, "frozen 152-case census");
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || Runtime.version().feature() != 17)
            throw new IllegalArgumentException("Usage (JDK17): LawRecordWireRegressionTest [OUTPUT.tsv]");
        Path trace = args.length == 0 ? null : Path.of(args[0]).toAbsolutePath();
        if (trace != null) { Files.createDirectories(trace.getParent()); Files.deleteIfExists(trace); }
        Path fixtures = Files.createTempDirectory("law-record-wire-");
        String prior = System.getProperty("acgn.provenance.testOverride"); System.setProperty("acgn.provenance.testOverride", "true");
        try {
            execute(fixtures);
            if (trace != null) Files.write(trace, ROWS, StandardCharsets.UTF_8);
            System.out.println("LawRecordWireRegressionTest passed: observations=" + (ROWS.size() - 1) + " checks=" + checks);
        } finally {
            if (prior == null) System.clearProperty("acgn.provenance.testOverride"); else System.setProperty("acgn.provenance.testOverride", prior);
            try (var files = Files.walk(fixtures)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
}

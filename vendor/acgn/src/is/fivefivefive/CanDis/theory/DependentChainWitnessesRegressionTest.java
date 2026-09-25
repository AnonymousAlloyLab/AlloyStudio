package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** A2-07/A2-11 finite producer/writer/public-verifier observations, TEST_ONLY. */
public final class DependentChainWitnessesRegressionTest {
    private static final GraphType A = GraphType.constructor("AlloySig:A");
    private static final GraphType B = GraphType.constructor("AlloySig:B");
    private static final GraphType C = GraphType.constructor("AlloySig:C");
    private static final GraphType U = GraphType.constructor("AlloySig:univ");
    private static final List<String> ROWS = new ArrayList<>();
    private static int checks, controls, bundles;
    private static final IndependentVerifier VERIFIER = new IndependentVerifier();

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: DependentChainWitnessesRegressionTest [OUTPUT.tsv]");
        check(Runtime.version().feature() == 17, "JDK 17 required");
        Path output = args.length == 1 ? Path.of(args[0]) : null;
        if (output != null) Files.deleteIfExists(output);
        ROWS.clear(); checks = controls = bundles = 0;
        ROWS.add("surface\tfixture\tcoordinate\tproducer\twriter\tverifier\tstatus");
        leafGrid();
        Path temporary = Files.createTempDirectory("dependent-chain-witness-");
        String previousTestMode = System.getProperty("acgn.provenance.testOverride");
        System.setProperty("acgn.provenance.testOverride", "true");
        try {
            for (DependentChainKind kind : DependentChainKind.values())
                for (String fixture : List.of("exact", "primitive", "int", "univ", "empty", "repeat"))
                    for (int mode = 0; mode < 2; mode++) for (int association = 0; association < 2; association++)
                        chain(temporary, kind, fixture, mode, association);
        } catch (Exception | AssertionError failure) {
            if (output != null) Files.write(output.resolveSibling(output.getFileName() + ".failed.tsv"), ROWS, StandardCharsets.UTF_8);
            throw failure;
        } finally {
            if (previousTestMode == null) System.clearProperty("acgn.provenance.testOverride");
            else System.setProperty("acgn.provenance.testOverride", previousTestMode);
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        check(bundles == 48, "fixed 48-bundle census");
        if (output != null) Files.write(output, ROWS, StandardCharsets.UTF_8);
        System.out.println("DependentChainWitnessesRegressionTest passed: rows=" + (ROWS.size() - 1)
                + " bundles=" + bundles + " controls=" + controls + " checks=" + checks);
    }

    private static GraphType carrier(GraphType c) { return GraphType.constructor("AlloyCarrier", c); }

    private static void leafGrid() {
        List<GraphType> stored = List.of(GraphType.INT, carrier(A), carrier(U),
                GraphType.relation(A), GraphType.relation(A, B), AlloyTypeBridge.emptyRelation(1),
                AlloyTypeBridge.emptyRelation(2), GraphType.BOOL, GraphType.typeVariable("X"),
                GraphType.constructor("AlloyCarrier"), carrier(GraphType.typeVariable("X")),
                GraphType.constructor("AlloyCarrier", GraphType.constructor("A")));
        List<GraphType> views = List.of(GraphType.relation(GraphType.INT), GraphType.relation(A),
                GraphType.relation(U), GraphType.relation(B), GraphType.relation(A, B),
                AlloyTypeBridge.emptyRelation(1), AlloyTypeBridge.emptyRelation(2), GraphType.BOOL);
        for (int i = 0; i < stored.size(); i++) for (int j = 0; j < views.size(); j++) {
            String rule = "REJECTED";
            try { rule = DependentChainTheory.requireLeafTypeProof(stored.get(i), views.get(j)).name(); }
            catch (IllegalArgumentException expected) { /* The grid includes nonrelation views. */ }
            String expected = (i == 0 && j == 0 || i == 1 && j == 1 || i == 2 && j == 2 || i == 11 && j == 1)
                    ? "PRIMITIVE_SET_SINGLETON"
                    : (i == 3 && j == 1 || i == 4 && j == 4 || i == 5 && j == 5 || i == 6 && j == 6)
                    ? "EXACT_RELATION" : "REJECTED";
            check(rule.equals(expected), "independent leaf grid " + i + "/" + j);
            row("leaf-grid", i + ":" + j, "rule", rule,
                    TheoryKeys.type(stored.get(i)).stableString(), TheoryKeys.type(views.get(j)).stableString(), "PRODUCER");
        }
    }

    private static List<GraphType> views(DependentChainKind kind, String fixture) {
        GraphType first = fixture.equals("int") ? GraphType.INT : fixture.equals("univ") ? U : A;
        if (kind == DependentChainKind.ARROW) return List.of(GraphType.relation(first),
                fixture.equals("empty") ? AlloyTypeBridge.emptyRelation(2) : GraphType.relation(B),
                GraphType.relation(C));
        if (fixture.equals("repeat")) return Collections.nCopies(3, GraphType.relation(A, A));
        if (fixture.equals("primitive") || fixture.equals("int"))
            return List.of(GraphType.relation(first), GraphType.relation(first, B), GraphType.relation(B, C));
        return List.of(GraphType.relation(A, first),
                fixture.equals("empty") ? AlloyTypeBridge.emptyRelation(2) : GraphType.relation(first, B),
                GraphType.relation(B, C));
    }

    private static void chain(Path directory, DependentChainKind kind, String fixture, int mode, int association) throws Exception {
        String id = kind + ":" + fixture + ":" + mode + ":" + association;
        SemanticProfile profile = mode == 0 ? SemanticProfile.alloyOverflowForbidding() : SemanticProfile.alloyModular();
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        List<GraphType> viewTypes = views(kind, fixture);
        List<DependentChainLeaf> leaves = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            if (i > 0 && kind == DependentChainKind.JOIN && fixture.equals("repeat")) {
                leaves.add(leaves.get(0));
                continue;
            }
            GraphType stored = i == 0 && fixture.equals("primitive") ? carrier(A)
                    : i == 0 && fixture.equals("int") ? GraphType.INT : viewTypes.get(i);
            TypedENode constant = TypedENode.construct(OperatorDeclaration.monomorphic(
                    "chain-witness-" + (fixture.equals("repeat") && kind == DependentChainKind.JOIN ? 0 : i),
                    List.of(), stored, Map.of(), null).instantiateMonomorphic(), TypedSlotContext.empty(), List.of());
            TypedInvocation invocation = graph.insertNode(constant, graph.coherentWitnessFamily()).returnedInvocation();
            graph.rebuild();
            leaves.add(new DependentChainLeaf(OnePort.invocation(TypedSlotContext.empty(), invocation), viewTypes.get(i)));
        }
        DependentChainApplication source = association == 0
                ? new DependentChainApplication(kind, new DependentChainApplication(kind, leaves.get(0), leaves.get(1)), leaves.get(2))
                : new DependentChainApplication(kind, leaves.get(0), new DependentChainApplication(kind, leaves.get(1), leaves.get(2)));
        // This is explicitly a test occurrence, not parser-origin authority.
        StructuralKey occurrence = StructuralKey.of("alloy-dependent-chain-source-occurrence-v1",
                List.of("fixture/chain-witness/" + id), List.of(
                        StructuralKey.branch("alloy-dependent-chain-typed-source-v1", List.of(source.structuralKey())),
                        StructuralKey.leaf("alloy-dependent-chain-source-content-v1", "TEST_ONLY:" + id)));
        ConstructionSourceLedger.Builder ledger = ConstructionSourceLedger.builder(profile);
        ledger.recordDependentChain(source, occurrence);
        CertifiedDependentChainConstruction construction = TypedENode.constructDependentChainCertified(source, profile, occurrence);
        CertificateVerifier.verify(construction.certificate());
        var root = graph.insertNodeConstructed(construction, graph.coherentWitnessFamily());
        graph.rebuild();
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(family, new FiniteUnfoldingBounds(2, 32))
                .enumerate(root.returnedInvocation()).stream().filter(t -> t.height() == 2).findFirst().orElseThrow();
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(root.returnedInvocation(), graph.classes(),
                family, List.of(unfolding), Map.of(), List.of(), List.of(), List.of(construction.certificate()), ledger.build(), profile);
        check(Boolean.getBoolean("acgn.provenance.testOverride"), "explicit TEST_ONLY provenance flag required");
        CertificateExportSession session = new CertificateExportSession(sink, graph, artifact, unfolding.normalizedTermKey(), Map.of(),
                CertificateProvenance.capture("fixture/chain-witness/" + id, id.getBytes(StandardCharsets.UTF_8),
                        "chain-witness-test-v1;" + CertificateTheoryManifest.VERSION), "chain-witness-test-v1");
        Path path = directory.resolve("bundle.acgncert");
        CertificateBundleWriter.write(session, path);
        byte[] bytes = Files.readAllBytes(path);
        Bundle bundle = Bundle.parse(Codec.decode(bytes, Limits.defaults()));
        check(bundle.root().child(1).child(0).scalars().equals(CertificateTheoryManifest.scalars()), "fixed theory manifest");
        VerificationPolicy policy = VerificationPolicy.trust(bundle.theoryDigest()).emptyCalls(id);
        VerificationResult result = VERIFIER.verify(bytes, Profile.FULL, policy);
        Wire.Node record = bundle.semanticEvidence().child(1).children().stream()
                .filter(n -> n.tag().equals("dependent-chain-construction")).findFirst().orElseThrow();
        check(bundle.semanticEvidence().child(1).children().size() == 1, "one chain record");
        DependentChainCertificate certificate = construction.certificate();
        Wire.Node published = bundle.semanticEvidence().child(4);
        Map<StructuralKey, Wire.Node> publishedTypes = publishedTypes(published);
        Set<StructuralKey> required = typeRequirements(certificate.theoryIndex());
        row("ledger", id, "coverage", StructuralKey.branch("dependent-index-type-requirements", List.copyOf(required)).stableString(),
                StructuralKey.branch("published-exact-types", List.copyOf(publishedTypes.keySet())).stableString(),
                result.outcome().name(), result.code().name());
        row("chain", id, "index", certificate.theoryIndex().stableString(), record.scalar(9), result.outcome().name(), result.code().name());
        row("chain", id, "certificate", certificate.structuralKey().stableString(), record.scalar(0), result.outcome().name(), result.code().name());
        row("chain", id, "source", source.structuralKey().stableString(), record.child(0).scalar(3), result.outcome().name(), result.code().name());
        row("chain", id, "profile", profile.structuralKey().stableString(), bundle.semanticEvidence().scalar(5), result.outcome().name(), result.code().name());
        row("chain", id, "theory", DependentChainTheory.SOURCE_TEXT, record.scalar(7), record.scalar(8), "SHA256");
        for (int i = 0; i < 3; i++) {
            Wire.Node leaf = wireLeaves(record.child(0)).get(i);
            row("leaf", id, Integer.toString(i), leaves.get(i).typeProof().stableString(), leaf.scalar(4), leaf.scalar(2), result.outcome().name());
        }
        check(result.outcome() == Outcome.VERIFIED, "real writer/public FULL verifier " + id + ": " + result);
        check(publishedTypes.keySet().containsAll(required), "complete certified fold ledger " + id);
        // Full coordinate census once per operator/fixture; both profiles and associations have positives.
        if (mode == 0 && association == 0) {
            int typeIndex = 0;
            for (StructuralKey type : required) {
                List<Wire.Node> remaining = new ArrayList<>(published.children());
                check(remaining.remove(publishedTypes.get(type)), "required type present");
                reject(bundle, policy, published, Wire.node(published.tag(), published.scalars(), remaining),
                        id, "ledger/drop" + typeIndex++);
            }
            wireControls(bundle, policy, record, record, "record", id);
            keyControls(bundle, policy, record, certificate.theoryIndex(), "index", id, 9);
            List<Wire.Node> wireLeaves = wireLeaves(record.child(0));
            for (int i = 0; i < 3; i++) {
                Wire.Node leaf = wireLeaves.get(i);
                leafKeyControls(bundle, policy, record, leaf, leaves.get(i).typeProof(), "leaf" + i, id);
                for (DependentChainTheory.LeafTypeRule rule : DependentChainTheory.LeafTypeRule.values())
                    if (!rule.name().equals(leaf.scalar(2))) reject(bundle, policy, record,
                            replace(record, leaf, scalar(leaf, 2, rule.name())), id, "rule" + i + "/" + rule.name());
            }
            reject(bundle, policy, record, scalar(record, 2, kind == DependentChainKind.JOIN ? "ARROW" : "JOIN"), id, "kind/other");
            reject(bundle, policy, record, scalar(record, 1, (mode == 0 ? SemanticProfile.alloyModular() : SemanticProfile.alloyOverflowForbidding()).fingerprint()), id, "profile/other");
            reject(bundle, policy, record, scalar(record, 3, wireLeaves.get(0).scalar(0)), id, "target/other");
            Wire.Node application = record.child(0);
            reject(bundle, policy, record, child(record, 0, child(child(application, 0, application.child(1)), 1, application.child(0))), id, "source/swap");
            for (int i = 0; i < certificate.structuralKey().children().size(); i++) {
                List<StructuralKey> children = new ArrayList<>(certificate.structuralKey().children());
                StructuralKey original = children.get(i);
                children.set(i, StructuralKey.of(original.tag() + "!", original.scalars(), original.children()));
                reject(bundle, policy, record, scalar(record, 0, StructuralKey.of("typed-equality-certificate",
                        List.of("DEPENDENT_CHAIN_NORMALIZATION"), children).stableString()), id, "certificate/detail" + i);
            }
        }
        if (id.equals("JOIN:primitive:0:1")) {
            StructuralKey intermediate = TheoryKeys.type(GraphType.relation(B));
            check(required.contains(intermediate) && !typeRequirements(source.structuralKey()).contains(intermediate),
                    "leftfold-only intermediate absent from original association");
            List<Wire.Node> remaining = new ArrayList<>(published.children());
            check(remaining.remove(publishedTypes.get(intermediate)), "writer registers intermediate relation(B)");
            reject(bundle, policy, published, Wire.node(published.tag(), published.scalars(), remaining), id, "ledger/intermediate");
        }
        bundles++;
    }

    private static Set<StructuralKey> typeRequirements(StructuralKey key) {
        Set<StructuralKey> types = new TreeSet<>(java.util.Comparator.comparing(StructuralKey::stableString));
        if (key.tag().startsWith("type/")) types.add(key);
        if (key.tag().equals("dependent-type-product-v1") || key.tag().equals("dependent-type-case-result-v1"))
            types.add(StructuralKey.branch("type/RELATION", key.children().stream().map(column -> column.children().get(0)).toList()));
        for (StructuralKey child : key.children()) types.addAll(typeRequirements(child));
        return types;
    }

    private static Map<StructuralKey, Wire.Node> publishedTypes(Wire.Node ledger) {
        check(ledger.tag().equals("exact-types") && ledger.scalars().isEmpty(), "exact type ledger shape");
        Map<String, Wire.Node> byId = new TreeMap<>();
        for (Wire.Node type : ledger.children()) {
            check(type.tag().equals("exact-type") && type.scalars().size() == 3, "exact type record shape");
            check(byId.put(type.scalar(0), type) == null, "unique exact type id");
        }
        Map<StructuralKey, Wire.Node> result = new TreeMap<>(java.util.Comparator.comparing(StructuralKey::stableString));
        for (Wire.Node type : ledger.children())
            check(result.put(publishedType(type.scalar(0), byId, new java.util.HashSet<>()), type) == null, "unique published type key");
        return result;
    }

    private static StructuralKey publishedType(String id, Map<String, Wire.Node> records, Set<String> active) {
        check(active.add(id) && records.containsKey(id), "acyclic resolved exact type");
        Wire.Node type = records.get(id);
        List<StructuralKey> arguments = new ArrayList<>();
        for (Wire.Node ref : type.children()) {
            check(ref.tag().equals("type-ref") && ref.scalars().size() == 1 && ref.children().isEmpty(), "exact type reference");
            arguments.add(publishedType(ref.scalar(0), records, active));
        }
        active.remove(id);
        return StructuralKey.of("type/" + type.scalar(1), type.scalar(2).isEmpty() ? List.of() : List.of(type.scalar(2)), arguments);
    }

    private static List<Wire.Node> wireLeaves(Wire.Node input) {
        if (input.tag().equals("dependent-chain-leaf")) return List.of(input);
        List<Wire.Node> result = new ArrayList<>(wireLeaves(input.child(0)));
        result.addAll(wireLeaves(input.child(1))); return result;
    }

    private static void keyControls(Bundle bundle, VerificationPolicy policy, Wire.Node record,
            StructuralKey key, String coordinate, String id, int scalar) {
        visitKey(key, coordinate, (name, changed) -> reject(bundle, policy, record,
                scalar(record, scalar, changed.stableString()), id, name));
    }

    private static void leafKeyControls(Bundle bundle, VerificationPolicy policy, Wire.Node record,
            Wire.Node leaf, StructuralKey proof, String coordinate, String id) {
        visitKey(proof, coordinate, (name, changed) -> reject(bundle, policy, record,
                replace(record, leaf, scalar(leaf, 4, changed.stableString())), id, name));
    }

    private interface KeyControl { void run(String coordinate, StructuralKey changed); }
    private static void visitKey(StructuralKey key, String path, KeyControl emit) {
        emit.run(path + "/tag", StructuralKey.of(key.tag() + "!", key.scalars(), key.children()));
        for (int i = 0; i < key.scalars().size(); i++) {
            List<String> scalars = new ArrayList<>(key.scalars()); scalars.set(i, scalars.get(i) + "!");
            emit.run(path + "/s" + i, StructuralKey.of(key.tag(), scalars, key.children()));
        }
        for (int i = 0; i < key.children().size(); i++) {
            final int at = i;
            List<StructuralKey> removed = new ArrayList<>(key.children()); removed.remove(i);
            emit.run(path + "/drop" + i, StructuralKey.of(key.tag(), key.scalars(), removed));
            visitKey(key.children().get(i), path + "/c" + i, (name, changed) -> {
                List<StructuralKey> children = new ArrayList<>(key.children()); children.set(at, changed);
                emit.run(name, StructuralKey.of(key.tag(), key.scalars(), children));
            });
        }
        for (int i = 0; i + 1 < key.children().size(); i++) if (!key.children().get(i).equals(key.children().get(i + 1))) {
            List<StructuralKey> children = new ArrayList<>(key.children()); Collections.swap(children, i, i + 1);
            emit.run(path + "/swap" + i, StructuralKey.of(key.tag(), key.scalars(), children));
        }
    }

    private static void wireControls(Bundle bundle, VerificationPolicy policy, Wire.Node record,
            Wire.Node node, String coordinate, String id) {
        for (int i = 0; i < node.scalars().size(); i++) {
            // Exact owner is consumed by the source ledger, not just the local theory-index check.
            reject(bundle, policy, record, replace(record, node, scalar(node, i, node.scalar(i) + "!")), id, coordinate + "/s" + i);
        }
        for (int i = 0; i < node.children().size(); i++)
            wireControls(bundle, policy, record, node.child(i), coordinate + "/c" + i, id);
    }

    private static void reject(Bundle bundle, VerificationPolicy policy, Wire.Node record,
            Wire.Node changed, String id, String coordinate) {
        check(!record.equals(changed), "nonvacuous field control " + coordinate);
        Wire.Node root = replace(bundle.root(), record, changed);
        Wire.Node manifest = root.child(1);
        root = child(root, 1, scalar(manifest, 1, Wire.contentId(manifest.child(1))));
        Bundle.parse(root);
        VerificationResult result = VERIFIER.verify(Codec.encode(root), Profile.FULL, policy);
        check(result.outcome() == Outcome.REJECTED && !result.code().name().equals(FailureCode.INTERNAL_ERROR)
                && !result.code().name().equals(FailureCode.RESOURCE_LIMIT),
                "semantic one-field rejection " + id + "/" + coordinate + ": " + result);
        row("control", id, coordinate, "CHANGED", "ENCODED", result.outcome().name(), result.code().name());
        controls++;
    }

    private static Wire.Node replace(Wire.Node root, Wire.Node target, Wire.Node changed) {
        if (root == target) return changed;
        return Wire.node(root.tag(), root.scalars(), root.children().stream().map(n -> replace(n, target, changed)).toList());
    }
    private static Wire.Node scalar(Wire.Node node, int at, String value) {
        List<String> scalars = new ArrayList<>(node.scalars()); scalars.set(at, value);
        return Wire.node(node.tag(), scalars, node.children());
    }
    private static Wire.Node child(Wire.Node node, int at, Wire.Node value) {
        List<Wire.Node> children = new ArrayList<>(node.children()); children.set(at, value);
        return Wire.node(node.tag(), node.scalars(), children);
    }
    private static void row(String surface, String fixture, String coordinate, String producer, String writer, String verifier, String status) {
        ROWS.add(String.join("\t", surface, fixture, coordinate, b64(producer), b64(writer), b64(verifier), status));
    }
    private static String b64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    // Public-API bridge keeps src independently compilable. No private lookup,
    // accessibility override, verifier state mutation, or producer object sharing.
    private static Class<?> api(String name) {
        try { return Class.forName("org.acgn.cert." + name); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("verifier classes required at runtime", e); }
    }
    private static Object invoke(Object receiver, Class<?> owner, String name, Class<?>[] types, Object... args) {
        try { return owner.getMethod(name, types).invoke(receiver, args); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static Object get(Object receiver, String name) { return invoke(receiver, receiver.getClass(), name, new Class<?>[0]); }
    private static Object enumeration(String owner, String name) {
        for (Object value : api(owner).getEnumConstants()) if (((Enum<?>) value).name().equals(name)) return value;
        throw new IllegalStateException("missing verifier enum " + owner + "/" + name);
    }
    private static final class Limits {
        static Object defaults() { return invoke(null, api("Limits"), "defaults", new Class<?>[0]); }
    }
    private static final class Wire {
        private record Node(String tag, List<String> scalars, List<Node> children) {
            String scalar(int i) { return scalars.get(i); }
            Node child(int i) { return children.get(i); }
        }
        static Node node(String tag, List<String> scalars, List<Node> children) { return new Node(tag, List.copyOf(scalars), List.copyOf(children)); }
        @SuppressWarnings("unchecked")
        static Node local(Object node) {
            return new Node((String) get(node, "tag"), (List<String>) get(node, "scalars"),
                    ((List<Object>) get(node, "children")).stream().map(Wire::local).toList());
        }
        static Object foreign(Node node) {
            return invoke(null, api("Wire"), "node", new Class<?>[] {String.class, List.class, List.class},
                    node.tag, node.scalars, node.children.stream().map(Wire::foreign).toList());
        }
        static String contentId(Node node) { return (String) invoke(null, api("Wire"), "contentId", new Class<?>[] {api("Wire$Node")}, foreign(node)); }
    }
    private static final class Codec {
        static Wire.Node decode(byte[] bytes, Object limits) {
            return Wire.local(invoke(null, api("Codec"), "decode", new Class<?>[] {byte[].class, api("Limits")}, bytes, limits));
        }
        static byte[] encode(Wire.Node node) {
            return (byte[]) invoke(null, api("Codec"), "encode", new Class<?>[] {api("Wire$Node")}, Wire.foreign(node));
        }
    }
    private record Bundle(Wire.Node root, String theoryDigest) {
        static Bundle parse(Wire.Node root) {
            Object parsed = invoke(null, api("Bundle"), "parse", new Class<?>[] {api("Wire$Node")}, Wire.foreign(root));
            return new Bundle(root, (String) get(parsed, "theoryDigest"));
        }
        Wire.Node semanticEvidence() { return root.child(1).child(1).child(3); }
    }
    private record VerificationPolicy(Object value) {
        static VerificationPolicy trust(String digest) {
            return new VerificationPolicy(invoke(null, api("VerificationPolicy"), "trust", new Class<?>[] {String.class}, digest));
        }
        VerificationPolicy emptyCalls(String fixture) {
            String subject = framedHash("call-occurrence-commitment-v1/subject", "fixture/chain-witness/" + fixture,
                    sha256(fixture.getBytes(StandardCharsets.UTF_8)));
            String digest = framedHash("call-occurrence-commitment-v1", subject);
            Object commitment = invoke(null, api("CallOccurrenceCommitment"), "parseAssignment",
                    new Class<?>[] {String.class}, subject + "=" + digest);
            return new VerificationPolicy(invoke(value, api("VerificationPolicy"), "withCallOccurrenceCommitment",
                    new Class<?>[] {api("CallOccurrenceCommitment")}, commitment));
        }
    }
    private static String sha256(byte[] value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String framedHash(String... values) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (String value : values) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(encoded.length).array()); bytes.writeBytes(encoded);
        }
        return sha256(bytes.toByteArray());
    }
    private enum Outcome { VERIFIED, REJECTED, UNCHECKABLE }
    private enum Profile { FULL }
    private static final class FailureCode {
        static final String INTERNAL_ERROR = "INTERNAL_ERROR", RESOURCE_LIMIT = "RESOURCE_LIMIT", DIGEST_MISMATCH = "DIGEST_MISMATCH";
    }
    private record Code(String name) { }
    private record VerificationResult(Outcome outcome, Code code, String detail) { }
    private static final class IndependentVerifier {
        VerificationResult verify(byte[] bytes, Profile profile, VerificationPolicy policy) {
            Object verifier;
            try { verifier = api("IndependentVerifier").getConstructor().newInstance(); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            Object result = invoke(verifier, api("IndependentVerifier"), "verify",
                    new Class<?>[] {byte[].class, api("Profile"), api("VerificationPolicy")}, bytes, enumeration("Profile", profile.name()), policy.value);
            return new VerificationResult(Outcome.valueOf(((Enum<?>) get(result, "outcome")).name()),
                    new Code(((Enum<?>) get(result, "code")).name()), (String) get(result, "detail"));
        }
    }
}

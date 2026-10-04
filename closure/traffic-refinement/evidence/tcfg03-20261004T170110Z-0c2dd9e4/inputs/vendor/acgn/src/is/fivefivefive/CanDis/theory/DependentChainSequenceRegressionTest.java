package is.fivefivefive.CanDis.theory;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import parser.ast.nodes.ModelUnit;

/** A2-01/02 finite observations; see chain-notes.md for the exact boundary/TSV. */
public final class DependentChainSequenceRegressionTest {
    private static int checks;
    private static int typedRows;
    private static int pipelineRows;
    private static int certificateRows;
    private static int barrierRows;
    private static int rejectedBundles;
    private static final List<String> rows = new ArrayList<>();

    private DependentChainSequenceRegressionTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: DependentChainSequenceRegressionTest [observations.tsv]");
        checks = typedRows = pipelineRows = certificateRows = barrierRows = rejectedBundles = 0;
        rows.clear();
        rows.add("surface\tprofile\tkind\tfixture\ttree\tsource\toutput\tlength\tcounts\tcarrier\treplay");
        typedCases();
        String previous = System.getProperty("acgn.provenance.testOverride");
        try {
            System.setProperty("acgn.provenance.testOverride", "true");
            pipelineCases();
        } finally {
            if (previous == null) System.clearProperty("acgn.provenance.testOverride");
            else System.setProperty("acgn.provenance.testOverride", previous);
        }
        check(typedRows == 1872, "complete typed word/association/profile matrix");
        check(pipelineRows == 12 && certificateRows == 12 && barrierRows == 4 && rejectedBundles == 24, "complete pipeline/replay matrix");
        check(rows.size() == 1901, "1900 observations plus header");
        if (args.length == 1) Files.writeString(Path.of(args[0]), String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        System.out.println("DependentChainSequenceRegressionTest passed: typed=" + typedRows
                + " pipeline=" + pipelineRows + " certificate=" + certificateRows + " barrier=" + barrierRows
                + " rejectedBundles=" + rejectedBundles + " checks=" + checks);
    }

    private record Tree(int atom, Tree left, Tree right) {
        static Tree leaf(int atom) { return new Tree(atom, null, null); }
        static Tree app(Tree l, Tree r) { return new Tree(-1, l, r); }
        List<Integer> word() {
            if (atom >= 0) return List.of(atom);
            List<Integer> result = new ArrayList<>(left.word());
            result.addAll(right.word());
            return List.copyOf(result);
        }
        DependentChainInput build(DependentChainKind kind, List<DependentChainLeaf> alphabet) {
            return atom >= 0 ? alphabet.get(atom)
                    : new DependentChainApplication(kind, left.build(kind, alphabet), right.build(kind, alphabet));
        }
        @Override public String toString() {
            return atom >= 0 ? Integer.toString(atom) : "(" + left + "," + right + ")";
        }
    }

    private static List<Tree> associations(List<Integer> word) {
        if (word.size() == 1) return List.of(Tree.leaf(word.get(0)));
        List<Tree> result = new ArrayList<>();
        for (int split = 1; split < word.size(); split++) {
            for (Tree l : associations(word.subList(0, split))) {
                for (Tree r : associations(word.subList(split, word.size()))) result.add(Tree.app(l, r));
            }
        }
        return result;
    }

    private static void typedCases() {
        GraphType a = GraphType.constructor("AlloySig:SequenceA");
        GraphType type = GraphType.relation(a, a);
        List<TypedSlot> slots = List.of(TypedSlot.source(type, 91001),
                TypedSlot.source(type, 91002), TypedSlot.source(type, 91003));
        TypedSlotContext context = TypedSlotContext.of(slots);
        List<DependentChainLeaf> alphabet = slots.stream()
                .map(slot -> new DependentChainLeaf(OnePort.slot(context, slot))).toList();
        List<OnePort> ports = alphabet.stream().map(DependentChainLeaf::port).toList();
        check(Set.copyOf(ports).size() == 3 && Set.copyOf(ports.stream().map(OnePort::schema).toList()).size() == 1,
                "same-type distinct complete operand identities");
        for (SemanticProfile profile : List.of(SemanticProfile.alloyOverflowForbidding(), SemanticProfile.alloyModular())) {
            String profileName = profile.equals(SemanticProfile.alloyOverflowForbidding()) ? "FORBID" : "MODULAR";
            for (DependentChainKind kind : List.of(DependentChainKind.JOIN, DependentChainKind.ARROW)) {
                for (int length = 2; length <= 4; length++) {
                    int words = (int) Math.pow(3, length);
                    for (int ordinal = 0; ordinal < words; ordinal++) {
                        List<Integer> word = new ArrayList<>(Collections.nCopies(length, 0));
                        int rest = ordinal;
                        for (int i = length - 1; i >= 0; i--) { word.set(i, rest % 3); rest /= 3; }
                        List<Tree> trees = associations(word);
                        check(trees.size() == (length == 2 ? 1 : length == 3 ? 2 : 5), "Catalan association count");
                        StructuralKey targetKey = null;
                        for (Tree tree : trees) {
                            check(tree.word().equals(word), "independent tree enumerator");
                            DependentChainApplication source = (DependentChainApplication) tree.build(kind, alphabet);
                            List<OnePort> expected = word.stream().map(ports::get).toList();
                            check(source.leaves().equals(expected), "source cached leaves");
                            check(source.leafInputs().stream().map(DependentChainLeaf::port).toList().equals(expected), "recursive source leaves");
                            CertifiedDependentChainConstruction built = TypedENode.constructDependentChainCertified(source, profile);
                            SeqPort seq = assertSequence(built.certificate(), expected);
                            check(targetKey == null || targetKey.equals(built.node().structuralKey()), "all admitted associations have same sequence target");
                            targetKey = built.node().structuralKey();
                            List<Integer> actual = seq.elements().stream().map(ports::indexOf).toList();
                            row("typed", profileName, kind, length + ":" + ordinal, tree.toString(), word, actual, "LOCAL_VERIFIED");
                            typedRows++;
                        }
                    }
                }
                Tree ordered = Tree.app(Tree.leaf(0), Tree.app(Tree.leaf(1), Tree.leaf(0)));
                DependentChainApplication source = (DependentChainApplication) ordered.build(kind, alphabet);
                CertifiedDependentChainConstruction built = TypedENode.constructDependentChainCertified(source, profile);
                SeqPort seq = (SeqPort) built.node().ports().get(0);
                List<PortSchema> retainedSchemas = List.copyOf(built.node().operator().portSchemas());
                GraphType ints = GraphType.relation(GraphType.INT, GraphType.INT);
                TypedSlot intSlot = TypedSlot.source(ints, 92001);
                TypedSlotContext intContext = TypedSlotContext.of(intSlot);
                DependentChainLeaf intLeaf = new DependentChainLeaf(OnePort.slot(intContext, intSlot));
                CertifiedDependentChainConstruction interleaved = TypedENode.constructDependentChainCertified(
                        new DependentChainApplication(kind, intLeaf, intLeaf), profile);
                assertSequence(interleaved.certificate(), List.of(intLeaf.port(), intLeaf.port()));
                check(built.node().operator().portSchemas().equals(retainedSchemas), "different-type interleaving cannot overwrite retained operator schemas");
                check(source.leaves().equals(List.of(ports.get(0), ports.get(1), ports.get(0))), "retained source after interleaving");
                assertSequence(built.certificate(), List.of(ports.get(0), ports.get(1), ports.get(0)));
                List<PortValue> swapped = List.of(ports.get(1), ports.get(0), ports.get(0));
                TypedENode wrong = TypedENode.construct(built.node().operator(), context,
                        List.of(new SeqPort(seq.schema(), context, swapped)));
                check(!wrong.structuralKey().equals(built.node().structuralKey()), "same-type order is observable");
                expectIllegal(() -> DependentChainCertificate.createProduction(source, wrong, profile));
                TypedENode replaced = TypedENode.construct(built.node().operator(), context,
                        List.of(new SeqPort(seq.schema(), context, List.of(ports.get(0), ports.get(1), ports.get(1)))));
                expectIllegal(() -> DependentChainCertificate.createProduction(source, replaced, profile));
                expectIllegal(() -> new SeqPort(seq.schema(), context, List.of(ports.get(0), ports.get(1))));
                expectIllegal(() -> SeqPortSchema.dependent(List.of(ports.get(0).schema())));
                expectIllegal(() -> SeqPortSchema.dependent(List.of()));
                expectImmutable(() -> seq.elements().clear());
                expectImmutable(() -> source.leaves().clear());
            }
        }
    }

    private static SeqPort assertSequence(DependentChainCertificate cert, List<OnePort> expected) {
        CertificateVerifier.verify(cert);
        homogeneous(cert.source(), cert.source().kind());
        check(cert.target().ports().size() == 1 && cert.target().ports().get(0) instanceof SeqPort, "one Seq, never Bag/Set");
        SeqPort seq = (SeqPort) cert.target().ports().get(0);
        check(seq.schema().isDependent() && seq.schema().kind() == PortSchema.Kind.SEQ
                && seq.schema().siblingQuotient() == SiblingQuotient.ORDERED_SEQUENCE, "exact ordered dependent schema");
        check(seq.schema().arityPolicy().equals(ArityPolicy.exact(expected.size())), "exact arity including repeated operands");
        check(seq.elements().equals(expected), "complete ordered operands, not type-only equality");
        check(seq.schema().positionalElementSchemas().size() == expected.size(), "one schema per occurrence");
        for (int i = 0; i < expected.size(); i++) check(seq.schema().schemaAt(i).equals(expected.get(i).schema()), "positional schema");
        ContainerLawDeclaration laws = cert.target().operator().lawForPath(PortPath.at(0));
        check(laws.kind() == ContainerLawDeclaration.Kind.SEQ && !laws.commutative() && !laws.idempotent() && !laws.hasUnit(), "no C/I/unit license");
        return seq;
    }

    private static void pipelineCases() throws Exception {
        // This bridge uses only public standalone APIs and fails if they are absent.
        // Reflection keeps the producer's normal compile independent of verifier classes.
        Replay replay = new Replay();
        for (boolean overflow : List.of(true, false)) {
            String profileName = overflow ? "FORBID" : "MODULAR";
            for (DependentChainKind kind : List.of(DependentChainKind.JOIN, DependentChainKind.ARROW)) {
                String op = kind == DependentChainKind.JOIN ? "." : "->";
                String resultType = kind == DependentChainKind.JOIN ? "A->A" : "A->A->A->A->A->A";
                String source = "module chain_sequence\nsig A { r, s: set A }\n"
                        + "fun Left[]: " + resultType + " { (r " + op + " s) " + op + " r }\n"
                        + "fun Right[]: " + resultType + " { r " + op + " (s " + op + " r) }\n"
                        + "fun Swapped[]: " + resultType + " { (s " + op + " r) " + op + " r }\n"
                        + "fun Barrier[]: A->A->A->A { " + (kind == DependentChainKind.JOIN ? "(r->s).r" : "(r.s)->r") + " }\nrun {} for 3\n";
                CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
                A4Options options = new A4Options();
                options.noOverflow = overflow;
                SemanticProfile profile = AlloySemanticProfileFactory.fromExactlyOne(module, List.of(module.getAllCommands().get(0)), options);
                MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
                visitor.visit(new ModelUnit(null, module), null);
                List<CanonicalAlloyPipeline.Prepared> prepared = new ArrayList<>();
                for (String name : List.of("Left", "Right", "Swapped")) {
                    Integer forest = visitor.getForestId(name);
                    check(forest != null, "parsed source graph");
                    CanonicalAlloyPipeline.Prepared p = CanonicalAlloyPipeline.prepare(visitor.getForest().get(forest), profile);
                    prepared.add(p);
                    List<DependentChainCertificate> certs = p.semanticArtifact().dependentChainConstructions().stream()
                            .filter(c -> c.source().kind() == kind && c.source().leaves().size() == 3).toList();
                    check(certs.size() == 1, "unique supported three-leaf chain from parser pipeline");
                    DependentChainCertificate cert = certs.get(0);
                    List<OnePort> leaves = cert.source().leafInputs().stream().map(DependentChainLeaf::port).toList();
                    check(leaves.get(0).schema().equals(leaves.get(1).schema()), "pipeline same-type operands");
                    check(!leaves.get(0).equals(leaves.get(1)), "pipeline distinct operand identities");
                    check(leaves.get(name.equals("Swapped") ? 1 : 0).equals(leaves.get(2)), "pipeline duplicate occurrence retained");
                    SeqPort seq = assertSequence(cert, leaves);
                    // IDs here are first-occurrence complete port identities, local to this row.
                    List<OnePort> alphabet = leaves.stream().distinct().toList();
                    row("pipeline", profileName, kind, name, sourceShape(cert.source(), alphabet),
                            leaves.stream().map(alphabet::indexOf).toList(), seq.elements().stream().map(alphabet::indexOf).toList(), "LOCAL_VERIFIED");
                    pipelineRows++;
                    certificateCase(replay, profileName, kind, name);
                }
                check(prepared.get(0).equivalentTo(prepared.get(1)), "supported source reassociation");
                check(!prepared.get(0).equivalentTo(prepared.get(2)), "supported pipeline preserves order distinction");
                CanonicalAlloyPipeline.Prepared barrier = CanonicalAlloyPipeline.prepare(
                        visitor.getForest().get(visitor.getForestId("Barrier")), profile);
                List<DependentChainCertificate> outer = barrier.semanticArtifact().dependentChainConstructions().stream()
                        .filter(c -> c.source().kind() == kind).toList();
                check(outer.size() == 1, "unique outer mixed-head chain");
                DependentChainCertificate cert = outer.get(0);
                check(cert.source().left() instanceof DependentChainLeaf && cert.source().right() instanceof DependentChainLeaf,
                        "different head is an opaque leaf, never spliced across the barrier");
                List<OnePort> leaves = cert.source().leaves();
                assertSequence(cert, leaves);
                check(leaves.size() == 2 && !leaves.get(0).equals(leaves.get(1)), "two distinct opaque operands");
                check(barrier.semanticArtifact().dependentChainConstructions().stream().anyMatch(c -> c.source().kind() != kind),
                        "different-head subtree has its own construction");
                row("barrier", profileName, kind, "Barrier", sourceShape(cert.source(), leaves),
                        List.of(0, 1), List.of(0, 1), "LOCAL_VERIFIED");
                barrierRows++;
            }
        }
    }

    private static void homogeneous(DependentChainInput input, DependentChainKind kind) {
        if (input instanceof DependentChainApplication app) {
            check(app.kind() == kind, "admitted model tree has exactly one head family");
            homogeneous(app.left(), kind); homogeneous(app.right(), kind);
        }
    }

    // Fresh graph insertions use the supported public construction/export boundary.
    // They are not exports of the parsed cases, whose retired collision slice is unsupported.
    private static void certificateCase(Replay replay, String profileName,
            DependentChainKind kind, String name) throws Exception {
        SemanticProfile profile = profileName.equals("FORBID")
                ? SemanticProfile.alloyOverflowForbidding() : SemanticProfile.alloyModular();
        GraphType a = GraphType.constructor("AlloySig:SequenceA");
        GraphType type = GraphType.relation(a, a);
        TypedSlotContext context = TypedSlotContext.empty();
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        List<DependentChainLeaf> alphabet = new ArrayList<>();
        for (String identity : List.of("sequence-r", "sequence-s")) {
            InstantiatedOperator operator = OperatorDeclaration.monomorphic(identity, List.of(), type, Map.of(), null).instantiateMonomorphic();
            TypedInvocation invocation = graph.insertNode(TypedENode.construct(operator, context, List.of()),
                    graph.coherentWitnessFamily()).returnedInvocation();
            alphabet.add(new DependentChainLeaf(OnePort.invocation(context, invocation)));
        }
        Tree tree = name.equals("Right") ? Tree.app(Tree.leaf(0), Tree.app(Tree.leaf(1), Tree.leaf(0)))
                : name.equals("Swapped") ? Tree.app(Tree.app(Tree.leaf(1), Tree.leaf(0)), Tree.leaf(0))
                : Tree.app(Tree.app(Tree.leaf(0), Tree.leaf(1)), Tree.leaf(0));
        DependentChainApplication source = (DependentChainApplication) tree.build(kind, alphabet);
        StructuralKey commitment = StructuralKey.of("alloy-dependent-chain-source-occurrence-v1",
                List.of("fixture/chain-sequence/" + name), List.of(
                        StructuralKey.branch("alloy-dependent-chain-typed-source-v1", List.of(source.structuralKey())),
                        StructuralKey.leaf("alloy-dependent-chain-source-content-v1", source.structuralKey().stableString())));
        ConstructionSourceLedger.Builder ledger = ConstructionSourceLedger.builder(profile);
        ledger.recordDependentChain(source, commitment);
        CertifiedDependentChainConstruction built = TypedENode.constructDependentChainCertified(source, profile, commitment);
        List<OnePort> ports = alphabet.stream().map(DependentChainLeaf::port).toList();
        SeqPort sequence = assertSequence(built.certificate(), tree.word().stream().map(ports::get).toList());
        CertifiedInsertionResult root = graph.insertNodeConstructed(built, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(family, new FiniteUnfoldingBounds(2, 32))
                .enumerate(root.returnedInvocation()).stream().filter(t -> t.height() == 2).findFirst().orElseThrow();
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(root.returnedInvocation(), graph.classes(), family,
                List.of(unfolding), Map.of(), List.of(), List.of(), List.of(built.certificate()), ledger.build(), profile);
        CertificateProvenance provenance = CertificateProvenance.capture("fixture/chain-sequence/" + name,
                tree.toString().getBytes(StandardCharsets.UTF_8), "chain-sequence-test-v1;" + CertificateTheoryManifest.VERSION);
        check(provenance.testOnly(), "fixture must not acquire publication authority");
        CertificateExportSession session = new CertificateExportSession(sink, graph, artifact, unfolding.normalizedTermKey(),
                Map.of(), provenance, "chain-sequence-test-v1");
        Path bundle = Files.createTempFile("acgn-chain-", ".acgncert");
        byte[] bytes;
        try { session.write(bundle); bytes = Files.readAllBytes(bundle); }
        finally { Files.deleteIfExists(bundle); }
        replay.pin(bytes);
        String positive = replay.verify(bytes);
        check(positive.equals("VERIFIED:NONE"), "public FULL replay: " + positive + " " + replay.detail);
        replay.checkWire(bytes, kind, 3);
        for (String mutation : List.of("swap", "duplicate-substitution")) {
            String result = replay.verify(replay.mutate(bytes, mutation));
            check(result.equals("REJECTED:THEORY_MISMATCH"), "public " + mutation + " rejects semantically: " + result);
            rejectedBundles++;
        }
        row("certificate", profileName, kind, name, tree.toString(), tree.word(),
                sequence.elements().stream().map(ports::indexOf).toList(), "FULL_VERIFIED");
        certificateRows++;
    }

    private static String sourceShape(DependentChainInput input, List<OnePort> alphabet) {
        if (input instanceof DependentChainLeaf leaf) return Integer.toString(alphabet.indexOf(leaf.port()));
        DependentChainApplication app = (DependentChainApplication) input;
        return "(" + sourceShape(app.left(), alphabet) + "," + sourceShape(app.right(), alphabet) + ")";
    }

    private static void row(String surface, String profile, DependentChainKind kind, String fixture,
            String tree, List<Integer> source, List<Integer> output, String replay) {
        check(source.equals(output), "observed word correspondence");
        List<Integer> counts = new ArrayList<>();
        for (int i = 0; i < 3; i++) counts.add(Collections.frequency(output, i));
        List<String> fields = List.of(surface, profile, kind.name(), fixture, tree, word(source), word(output),
                Integer.toString(output.size()), word(counts), "SEQ", replay);
        for (String f : fields) check(!f.contains("\t") && !f.contains("\n") && !f.contains("\r"), "one-line TSV fields");
        rows.add(String.join("\t", fields));
    }

    private static String word(List<Integer> word) { return word.stream().map(Object::toString).collect(Collectors.joining(",")); }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void expectIllegal(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }
    private static void expectImmutable(Runnable action) {
        checks++;
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected immutable sequence");
    }

    /** Public Wire/Codec/IndependentVerifier only; no private-field access or internal replay calls. */
    private static final class Replay {
        private final Class<?> node = Class.forName("org.acgn.cert.Wire$Node");
        private final Class<?> limitsType = Class.forName("org.acgn.cert.Limits");
        private final Class<?> profileType = Class.forName("org.acgn.cert.Profile");
        private final Class<?> policyType = Class.forName("org.acgn.cert.VerificationPolicy");
        private final Class<?> codec = Class.forName("org.acgn.cert.Codec");
        private final Object limits = limitsType.getMethod("defaults").invoke(null);
        private final Object full = profileType.getField("FULL").get(null);
        private Object policy;
        private String detail;
        private final Object verifier = Class.forName("org.acgn.cert.IndependentVerifier").getConstructor().newInstance();
        Replay() throws Exception { }
        private Object call(Object value, String method) throws Exception { return value.getClass().getMethod(method).invoke(value); }
        @SuppressWarnings("unchecked") private List<Object> children(Object n) throws Exception { return (List<Object>) call(n, "children"); }
        @SuppressWarnings("unchecked") private List<String> scalars(Object n) throws Exception { return (List<String>) call(n, "scalars"); }
        private Object make(Object n, List<String> scalars, List<Object> children) throws Exception {
            return node.getConstructor(String.class, List.class, List.class).newInstance(call(n, "tag"), scalars, children);
        }
        private Object decode(byte[] bytes) throws Exception { return codec.getMethod("decode", byte[].class, limitsType).invoke(null, bytes, limits); }
        void pin(byte[] original) throws Exception {
            Object root = decode(original);
            check(find(root, "call-occurrence").isEmpty(), "this exact test-only source contains no CALLs");
            Object bundle = Class.forName("org.acgn.cert.Bundle").getMethod("parse", node).invoke(null, root);
            policy = policyType.getMethod("trust", String.class).invoke(null, call(bundle, "theoryDigest"));
            // Fixture-only empty-set pin, computed once from the ORIGINAL, never a candidate.
            Class<?> commitmentType = Class.forName("org.acgn.cert.CallOccurrenceCommitment");
            Object commitment = commitmentType.getMethod("inspect", byte[].class, limitsType).invoke(null, original, limits);
            policy = policyType.getMethod("withCallOccurrenceCommitment", commitmentType).invoke(policy, commitment);
        }
        String verify(byte[] bytes) throws Exception {
            check(policy != null, "pin original fixture theory before replaying candidates");
            Method verify = verifier.getClass().getMethod("verify", byte[].class, profileType, policyType);
            Object result = verify.invoke(verifier, bytes, full, policy);
            detail = call(result, "detail").toString();
            return call(result, "outcome") + ":" + call(result, "code");
        }
        private void find(Object n, String tag, List<Object> result) throws Exception {
            if (call(n, "tag").equals(tag)) result.add(n);
            for (Object child : children(n)) find(child, tag, result);
        }
        private List<Object> find(Object root, String tag) throws Exception {
            List<Object> found = new ArrayList<>(); find(root, tag, found); return found;
        }
        private Object replace(Object n, Object old, Object replacement) throws Exception {
            if (n == old) return replacement;
            List<Object> next = new ArrayList<>();
            for (Object child : children(n)) next.add(replace(child, old, replacement));
            List<String> values = new ArrayList<>(scalars(n));
            if (call(n, "tag").equals("manifest")) {
                Object digest = Class.forName("org.acgn.cert.Wire").getMethod("contentId", node).invoke(null, next.get(1));
                values.set(1, digest.toString());
            }
            return make(n, values, next);
        }
        void checkWire(byte[] bytes, DependentChainKind kind, int length) throws Exception {
            Object root = decode(bytes);
            List<Object> candidates = new ArrayList<>();
            for (Object record : find(root, "dependent-chain-construction")) {
                if (scalars(record).get(2).equals(kind.name()) && find(record, "dependent-chain-leaf").size() == length) candidates.add(record);
            }
            check(candidates.size() == 1, "one exported three-leaf chain");
            List<String> sourceIds = new ArrayList<>();
            for (Object leaf : find(candidates.get(0), "dependent-chain-leaf")) sourceIds.add(scalars(leaf).get(0));
            check(sourceIds.size() == length && Set.copyOf(sourceIds).size() == 2, "wire retains duplicate complete term ID");
        }
        byte[] mutate(byte[] bytes, String mutation) throws Exception {
            Object root = decode(bytes);
            List<Object> records = find(root, "dependent-chain-construction");
            Object record = null;
            for (Object candidate : records) if (find(candidate, "dependent-chain-leaf").size() == 3) {
                check(record == null, "unique mutation target"); record = candidate;
            }
            check(record != null, "mutation has actual source target");
            Object source = children(record).get(0);
            Object changed;
            if (mutation.equals("swap")) {
                List<Object> branches = new ArrayList<>(children(source));
                Collections.swap(branches, 0, 1);
                changed = make(source, scalars(source), branches);
            } else {
                List<Object> leaves = find(source, "dependent-chain-leaf");
                Object first = leaves.get(0), second = leaves.get(1);
                check(!scalars(first).get(0).equals(scalars(second).get(0)), "nonvacuous same-type substitution");
                List<String> changedScalars = new ArrayList<>(scalars(second));
                changedScalars.set(0, scalars(first).get(0));
                changed = replace(source, second, make(second, changedScalars, children(second)));
            }
            Object changedRoot = replace(root, source, changed);
            return (byte[]) codec.getMethod("encode", node).invoke(null, changedRoot);
        }
    }
}

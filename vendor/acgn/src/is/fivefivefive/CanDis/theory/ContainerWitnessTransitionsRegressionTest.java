package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.*;
import edu.mit.csail.sdg.translator.A4Options;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import parser.ast.nodes.ModelUnit;

/** Finite P2-20/P2-18 observations, never universal Java/parser authority. */
public final class ContainerWitnessTransitionsRegressionTest {
    private static final List<String> ROWS = new ArrayList<>();
    private static int checks;
    private static final List<String> BOUNDARIES = List.of("missingA", "wrongAHead", "wrongAType",
            "wrongAPath", "wrongASchema", "wrongAProfile", "wrongATheory", "testAuthority",
            "leafType", "leafContext", "empty", "nonflatPath", "wrongTarget", "mixedUnsealed");
    private ContainerWitnessTransitionsRegressionTest() { }

    public static void main(String[] args) throws Exception {
        check(Runtime.version().feature() == 17, "JDK 17 required");
        if (args.length > 1) throw new IllegalArgumentException("Usage: ContainerWitnessTransitionsRegressionTest [observations.tsv]");
        ROWS.clear();
        ROWS.add("case\tsurface\tfamily\tfixture\ttree\tinputs\toutputs\tfibers\tsplices\taccepted\tindexMatches\tcontrols\tstage");
        typedCases();
        traceCases();
        boundaryCases();
        String previous = System.getProperty("acgn.provenance.testOverride");
        try {
            System.setProperty("acgn.provenance.testOverride", "true");
            pipelineCases();
            for (int family : List.of(0, 2, 3)) wireCases(family, -1);
            for (int family : List.of(0, 2)) for (int shape = 0; shape < 5; shape++) wireCases(family, shape);
        } finally {
            if (previous == null) System.clearProperty("acgn.provenance.testOverride");
            else System.setProperty("acgn.provenance.testOverride", previous);
        }
        check(ROWS.size() == 1618, "1617 frozen observations, got " + (ROWS.size() - 1));
        if (args.length == 1) Files.write(Path.of(args[0]), ROWS, StandardCharsets.UTF_8);
        System.out.println("ContainerWitnessTransitionsRegressionTest passed: observations=" + (ROWS.size() - 1) + " checks=" + checks);
    }

    private record Tree(int atom, List<Tree> children) {
        static Tree leaf(int atom) { return new Tree(atom, List.of()); }
        static Tree node(Tree... children) { return new Tree(-1, List.of(children)); }
        List<Integer> word() { return atom >= 0 ? List.of(atom) : children.stream().flatMap(t -> t.word().stream()).toList(); }
        FlatInput build(InstantiatedOperator op, TypedSlotContext context, List<OnePort> alphabet) {
            return atom >= 0 ? new FlatLeaf(alphabet.get(atom)) : new FlatApplication(op, context,
                    children.stream().map(t -> t.build(op, context, alphabet)).toList());
        }
        @Override public String toString() { return atom >= 0 ? Integer.toString(atom) : json(children); }
    }

    private static List<Tree> trees(List<Integer> word) {
        if (word.size() == 1) return List.of(Tree.node(Tree.leaf(word.get(0))));
        return branches(word);
    }
    private static List<Tree> branches(List<Integer> word) {
        if (word.size() == 1) return List.of(Tree.leaf(word.get(0)));
        List<Tree> result = new ArrayList<>();
        for (int split = 1; split < word.size(); split++)
            for (Tree left : branches(word.subList(0, split)))
                for (Tree right : branches(word.subList(split, word.size()))) result.add(Tree.node(left, right));
        return result;
    }
    private static List<Integer> word(int base, int length, int ordinal) {
        List<Integer> result = new ArrayList<>(Collections.nCopies(length, 0));
        for (int i = length - 1; i >= 0; i--) { result.set(i, ordinal % base); ordinal /= base; }
        return result;
    }
    private static SemanticProfile profile(int family) {
        return family == 1 || family == 2 ? SemanticProfile.alloyModular() : SemanticProfile.alloyOverflowForbidding();
    }
    private static GraphType type(int family) { return family == 2 ? GraphType.INT : GraphType.BOOL; }
    private static Opcode opcode(int family) { return family == 2 ? Opcode.IPLUS : family == 3 ? Opcode.IFF : Opcode.AND; }
    private static PortSchema schema(int carrier, boolean empty, GraphType type) {
        ArityPolicy arity = empty ? ArityPolicy.atLeast(0) : ArityPolicy.nonemptyVariadic();
        OnePortSchema element = new OnePortSchema(type);
        return switch (carrier) {
            case 0 -> new SeqPortSchema(arity, element);
            case 1 -> new BagPortSchema(arity, element);
            default -> new SetPortSchema(arity, element);
        };
    }
    private static InstantiatedOperator operator(int family) {
        GraphType result = type(family);
        PortSchema s = family == 3 ? new BagPortSchema(ArityPolicy.exact(2), new OnePortSchema(result))
                : schema(family == 2 ? 1 : 2, false, result);
        List<ContainerLawCertificate> certs = new ArrayList<>();
        for (ContainerLawCertificate.Law law : ContainerLawCertificate.Law.values()) {
            if (law == ContainerLawCertificate.Law.UNIT || (family == 2 && law == ContainerLawCertificate.Law.IDEMPOTENCY)
                    || (family == 3 && law != ContainerLawCertificate.Law.COMMUTATIVITY)) continue;
            certs.add(AlloyLawRegistry.issue(profile(family), opcode(family), "ALLOY/" + opcode(family),
                    result, PortPath.at(0), s, law));
        }
        return OperatorDeclaration.monomorphic("ALLOY/" + opcode(family), List.of(s), result,
                Map.of(PortPath.at(0), ContainerLawDeclaration.certified(s, certs)), family == 3 ? null : 0).instantiateMonomorphic();
    }
    private static List<OnePort> alphabet(GraphType type) {
        List<TypedSlot> slots = List.of(TypedSlot.source(type, 93000), TypedSlot.source(type, 93001), TypedSlot.source(type, 93002));
        TypedSlotContext context = TypedSlotContext.of(slots);
        List<OnePort> result = slots.stream().map(s -> OnePort.slot(context, s))
                .sorted(Comparator.comparing(OnePort::structuralKey)).toList();
        check(new HashSet<>(result).size() == 3, "distinct exact identities");
        for (int i = 1; i < result.size(); i++) check(result.get(i - 1).structuralKey().compareTo(result.get(i).structuralKey()) < 0,
                "independently ranked complete structural keys");
        return result;
    }
    private static CertifiedFlatConstruction construct(FlatApplication source, int family) {
        return TypedENode.flatConstructCertified(source, ignored -> { throw new AssertionError("Unexpected unsealed mixed head"); }, profile(family));
    }
    private static void typedCases() {
        for (int family = 0; family < 3; family++) {
            InstantiatedOperator op = operator(family);
            List<OnePort> alphabet = alphabet(type(family));
            for (int length = 1; length <= 4; length++) {
                for (int ordinal = 0; ordinal < (int) Math.pow(3, length); ordinal++) {
                    List<Tree> trees = trees(word(3, length, ordinal));
                    for (int association = 0; association < trees.size(); association++) {
                        Tree tree = trees.get(association);
                        FlatApplication source = (FlatApplication) tree.build(op, alphabet.get(0).context(), alphabet);
                        FlatConstructionCertificate cert = construct(source, family).certificate();
                        CertificateVerifier.verify(cert);
                        List<OnePort> expected = tree.word().stream().map(alphabet::get).toList();
                        check(cert.containerTrace().inputOccurrences().equals(expected), "ordered recursive occurrences including repeats");
                        int controls = checkFlatIndex(cert);
                        traceRow("flat", family, length + ":" + ordinal + ":" + association, tree.toString(),
                                cert.containerTrace(), alphabet, cert.splices(), controls, "LOCAL_VERIFIED");
                    }
                }
            }
        }
    }

    private static int changedFields(StructuralKey key) {
        int count = 0;
        for (int i = 0; i < key.scalars().size(); i++) {
            List<String> scalars = new ArrayList<>(key.scalars()); scalars.set(i, scalars.get(i) + "!");
            check(!key.equals(StructuralKey.of(key.tag(), scalars, key.children())), "scalar field is exact"); count++;
        }
        for (int i = 0; i < key.children().size(); i++) {
            List<StructuralKey> children = new ArrayList<>(key.children()); children.set(i, StructuralKey.leaf("changed", "field"));
            check(!key.equals(StructuralKey.of(key.tag(), key.scalars(), children)), "child field is exact"); count++;
        }
        return count;
    }
    private static int lawIndex(ContainerLawCertificate c) {
        StructuralKey expected = StructuralKey.of("container-law-index-v2",
                List.of(c.authority().name(), c.operatorIdentity(), c.schemaPath().toString(), c.law().name(), c.sourceTheoryDigest()),
                List.of(c.semanticProfile().structuralKey(), TheoryKeys.type(c.resultType()), c.schema().structuralKey(), c.lawParameter()));
        check(c.lawIndex().equals(expected), "all nine law-index coordinates");
        for (String side : List.of("left", "right")) {
            StructuralKey endpoint = StructuralKey.of("container-law-source-endpoint", List.of(side), List.of(expected));
            check(endpoint.equals(side.equals("left") ? c.leftSourceEndpoint() : c.rightSourceEndpoint()), "exact law source endpoint side/index");
        }
        return changedFields(expected);
    }
    private static int traceIndex(ContainerApplicationTrace trace) {
        List<StructuralKey> children = new ArrayList<>(List.of(trace.schema().structuralKey(), TheoryKeys.context(trace.context())));
        for (PortValue input : trace.inputOccurrences()) children.add(StructuralKey.branch("container-application/input", List.of(input.structuralKey())));
        for (int i = 0; i < trace.outputOccurrences().size(); i++) children.add(StructuralKey.of("container-application/output",
                trace.outputFibers().get(i).stream().map(Object::toString).toList(), List.of(trace.outputOccurrences().get(i).structuralKey())));
        StructuralKey expected = StructuralKey.branch("container-application-trace-v1", children);
        check(expected.equals(trace.structuralKey()), "exact trace schema/context/raw inputs/outputs/fibers key");
        return changedFields(expected);
    }
    private static int checkFlatIndex(FlatConstructionCertificate cert) {
        int controls = traceIndex(cert.containerTrace());
        for (ContainerLawCertificate law : cert.source().operator().lawForPath(PortPath.at(0)).certificates().values()) controls += lawIndex(law);
        check(cert.leftEndpoint().equals(TypedCertificateEndpoint.flatApplication(cert.source(), cert.semanticProfile())), "exact left endpoint");
        check(cert.rightEndpoint().equals(cert.collapsedToSingleton() ? TypedCertificateEndpoint.oneTerm(cert.singletonTarget())
                : TypedCertificateEndpoint.node(cert.target())), "exact right endpoint");
        for (FlatConstructionCertificate.Splice splice : cert.splices()) {
            FlatApplication nested = cert.source();
            FlatApplication parent = nested;
            for (int position : splice.path()) { parent = nested; nested = (FlatApplication) nested.operands().get(position); }
            check(splice.outerArity() == parent.operands().size() && splice.nestedArity() == nested.operands().size()
                    && splice.position() == splice.path().get(splice.path().size() - 1)
                    && splice.nestedSource().equals(nested.structuralKey()), "splice reconstructs exact nested occurrence");
            List<String> coordinates = new ArrayList<>(splice.path().stream().map(Object::toString).toList());
            coordinates.addAll(List.of(Integer.toString(splice.outerArity()), Integer.toString(splice.nestedArity()), Integer.toString(splice.position())));
            StructuralKey expected = StructuralKey.of("associative-splice-v1", coordinates, List.of(nested.structuralKey()));
            check(splice.structuralKey().equals(expected), "exact splice key"); controls += changedFields(expected);
        }
        return controls + 2;
    }

    private static PortValue container(PortSchema s, TypedSlotContext context, List<? extends PortValue> inputs) {
        if (s instanceof SeqPortSchema seq) return new SeqPort(seq, context, inputs);
        if (s instanceof BagPortSchema bag) return new BagPort(bag, context, inputs);
        return new SetPort((SetPortSchema) s, context, inputs);
    }
    private static void traceCases() {
        List<OnePort> alphabet = alphabet(GraphType.BOOL);
        for (int carrier = 0; carrier < 3; carrier++) {
            PortSchema s = schema(carrier, true, GraphType.BOOL);
            for (int length = 0; length <= 4; length++) for (int ordinal = 0; ordinal < 1 << length; ordinal++) {
                List<OnePort> inputs = word(2, length, ordinal).stream().map(alphabet::get).toList();
                ContainerApplicationTrace trace = ContainerApplicationTrace.of(s, alphabet.get(0).context(), inputs,
                        container(s, alphabet.get(0).context(), inputs));
                traceRow("trace", carrier, length + ":" + ordinal, "[]", trace, alphabet, List.of(), traceIndex(trace), "STRUCTURAL_ONLY");
            }
        }
    }
    private static void boundaryCases() {
        for (int family = 0; family < 3; family++) {
            final int f = family;
            InstantiatedOperator op = operator(family);
            List<OnePort> alphabet = alphabet(type(family));
            TypedSlotContext context = alphabet.get(0).context();
            PortSchema s = op.portSchemas().get(0);
            ContainerLawDeclaration laws = op.lawForPath(PortPath.at(0));
            ContainerLawCertificate a = laws.certificates().get(ContainerLawCertificate.Law.ASSOCIATIVITY);
            FlatApplication source = new FlatApplication(op, context, List.of(new FlatLeaf(alphabet.get(0)), new FlatLeaf(alphabet.get(1))));
            for (String label : BOUNDARIES) {
                rejected(() -> {
                    switch (label) {
                        case "missingA" -> {
                            List<ContainerLawCertificate> cs = laws.certificates().values().stream().filter(c -> c.law() != a.law()).toList();
                            OperatorDeclaration.monomorphic(op.operator(), List.of(s), type(f), Map.of(PortPath.at(0), ContainerLawDeclaration.certified(s, cs)), 0);
                        }
                        case "wrongAHead" -> laws.validateEvidenceFor("ALLOY/OR", type(f), PortPath.at(0), s, true);
                        case "wrongAType" -> laws.validateEvidenceFor(op.operator(), GraphType.constructor("wrong"), PortPath.at(0), s, true);
                        case "wrongAPath" -> laws.validateEvidenceFor(op.operator(), type(f), PortPath.at(1), s, true);
                        case "wrongASchema" -> laws.validateEvidenceFor(op.operator(), type(f), PortPath.at(0), schema(0, false, type(f)), true);
                        case "wrongAProfile" -> TypedENode.flatConstructCertified(source, ignored -> { throw new AssertionError(); },
                                f == 0 ? SemanticProfile.alloyModular() : SemanticProfile.alloyOverflowForbidding());
                        case "wrongATheory" -> ContainerLawCertificate.trustedAlloy(s, a.law(), a.origin(), a.semanticProfile(), a.operatorIdentity(),
                                a.resultType(), a.schemaPath(), a.lawParameter(), "changed-theory");
                        case "testAuthority" -> {
                            List<ContainerLawCertificate> cs = laws.certificates().keySet().stream().map(l -> ContainerLawCertificate.testFixture(s, l, a.origin())).toList();
                            ContainerLawDeclaration.certified(s, cs).validateEvidenceFor(op.operator(), type(f), PortPath.at(0), s, true);
                        }
                        case "leafType" -> {
                            TypedSlot wrong = TypedSlot.source(GraphType.constructor("wrong"), 94000);
                            TypedSlotContext sameContext = TypedSlotContext.of(wrong);
                            new FlatApplication(op, sameContext, List.of(new FlatLeaf(OnePort.slot(sameContext, wrong))));
                        }
                        case "leafContext" -> new FlatApplication(op, TypedSlotContext.empty(), source.operands());
                        case "empty" -> new FlatApplication(op, context, List.of());
                        case "nonflatPath" -> TypedENode.constructContainerCertified(operator(3), PortPath.at(1), context, alphabet.subList(0, 2), profile(3));
                        case "wrongTarget" -> FlatConstructionCertificate.createProduction(source,
                                TypedENode.construct(op, context, List.of(container(s, context, List.of(alphabet.get(0), alphabet.get(2))))), profile(f));
                        case "mixedUnsealed" -> {
                            // Same typed carrier, different exact operator instance: local certificate factory must reject it.
                            Opcode other = f == 2 ? Opcode.MUL : Opcode.OR;
                            List<ContainerLawCertificate> cs = laws.certificates().keySet().stream().map(l -> AlloyLawRegistry.issue(profile(f), other,
                                    "ALLOY/" + other, type(f), PortPath.at(0), s, l)).toList();
                            InstantiatedOperator different = OperatorDeclaration.monomorphic("ALLOY/" + other, List.of(s), type(f),
                                    Map.of(PortPath.at(0), ContainerLawDeclaration.certified(s, cs)), 0).instantiateMonomorphic();
                            FlatApplication nested = new FlatApplication(different, context, source.operands());
                            FlatConstructionCertificate.createProduction(new FlatApplication(op, context, List.of(nested, source.operands().get(0))),
                                    TypedENode.construct(op, context, List.of(container(s, context, alphabet.subList(0, 2)))), profile(f));
                        }
                        default -> throw new AssertionError(label);
                    }
                });
                simpleRow("boundary", family, label, false, "LOCAL_REJECTED");
            }
        }
        for (int family = 0; family < 2; family++) for (int carrier = 0; carrier < 3; carrier++) {
            final int f = family, c = carrier;
            rejected(() -> AlloyLawRegistry.issue(profile(f), Opcode.AND, "ALLOY/AND", GraphType.BOOL, PortPath.at(0),
                    schema(c, true, GraphType.BOOL), ContainerLawCertificate.Law.UNIT));
            simpleRow("unit", family, Integer.toString(carrier), false, "REGISTRY_REJECTED");
        }
    }

    private static void pipelineCases() throws Exception {
        String source = "module container_witness\nsig A { r, s, t: set A }\n"
                + "pred Left { some ((r+s)+t) }\npred Right { some (r+(s+t)) }\n"
                + "pred Barrier { some ((r&s)+t) }\nrun {} for 3\n";
        for (int family = 0; family < 2; family++) {
            CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
            A4Options options = new A4Options(); options.noOverflow = family == 0;
            SemanticProfile profile = AlloySemanticProfileFactory.fromExactlyOne(module, List.of(module.getAllCommands().get(0)), options);
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(new ModelUnit(null, module), null);
            List<CanonicalAlloyPipeline.Prepared> prepared = new ArrayList<>();
            for (String name : List.of("Left", "Right", "Barrier")) {
                var p = CanonicalAlloyPipeline.prepare(visitor.getForest().get(visitor.getForestId(name)), profile);
                prepared.add(p);
                List<FlatConstructionCertificate> certs = p.semanticArtifact().flatConstructions();
                check(!certs.isEmpty(), "actual adapter certified flat path");
                for (var cert : certs) { CertificateVerifier.verify(cert); checkFlatIndex(cert); }
                if (name.equals("Barrier")) check(certs.stream().anyMatch(c -> c.source().operator().operator().equals("ALLOY/INTERSECT")),
                        "mixed head has separate certified construction");
                simpleRow("pipeline", family, name, true, "ADAPTER_VERIFIED");
            }
            check(prepared.get(0).equivalentTo(prepared.get(1)), "parser adapter reassociation");
        }
    }

    private static void wireCases(int family, int shape) throws Exception {
        SemanticProfile profile = profile(family);
        InstantiatedOperator op = operator(family);
        TypedSlotContext context = TypedSlotContext.empty();
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        List<OnePort> alphabet = new ArrayList<>();
        for (String name : List.of("container-witness-a", "container-witness-b")) {
            InstantiatedOperator leaf = OperatorDeclaration.monomorphic(name, List.of(), type(family), Map.of(), null).instantiateMonomorphic();
            TypedInvocation invocation = graph.insertNode(TypedENode.construct(leaf, context, List.of()), graph.coherentWitnessFamily()).returnedInvocation();
            alphabet.add(OnePort.invocation(context, invocation));
        }
        alphabet.sort(Comparator.comparing(OnePort::structuralKey));
        ConstructionSourceLedger.Builder ledger = ConstructionSourceLedger.builder(profile);
        List<FlatConstructionCertificate> flat = new ArrayList<>();
        List<ContainerConstructionCertificate> containers = new ArrayList<>();
        CertifiedInsertionResult root;
        if (family == 3) {
            List<OnePort> inputs = List.of(alphabet.get(1), alphabet.get(0));
            ledger.recordContainer(op, PortPath.at(0), context, inputs);
            var built = TypedENode.constructContainerCertified(op, PortPath.at(0), context, inputs, profile);
            containers.add(built.certificate());
            root = graph.insertNodeConstructed(built, graph.coherentWitnessFamily());
        } else {
            Tree tree = shape < 0 ? Tree.node(Tree.leaf(1), Tree.node(Tree.leaf(0), Tree.leaf(1)))
                    : branches(List.of(1, 0, 1, 0)).get(shape);
            FlatApplication source = (FlatApplication) tree.build(op, context, alphabet);
            ledger.recordFlat(source);
            var built = construct(source, family); flat.add(built.certificate());
            root = graph.insertNodeConstructed(built, graph.coherentWitnessFamily());
        }
        CoherentWitnessFamily witnesses = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(witnesses, new FiniteUnfoldingBounds(2, 32))
                .enumerate(root.returnedInvocation()).stream().filter(t -> t.height() == 2).findFirst().orElseThrow();
        Map<String, List<ContainerLawDeclaration>> laws = Map.of(op.operator(), List.of(op.lawForPath(PortPath.at(0))));
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(root.returnedInvocation(), graph.classes(), witnesses,
                List.of(unfolding), laws, flat, containers, ledger.build(), profile);
        String fixture = "container-witness-" + family + "/" + shape;
        String sourceContent = flat.isEmpty() ? fixture : fixture + ":" + flat.get(0).source().structuralKey().stableString();
        CertificateProvenance provenance = CertificateProvenance.capture("fixture/" + fixture,
                sourceContent.getBytes(StandardCharsets.UTF_8), "container-witness-test-v1;" + CertificateTheoryManifest.VERSION);
        check(provenance.testOnly(), "fixture is not publication authority");
        CertificateExportSession session = new CertificateExportSession(sink, graph, artifact, unfolding.normalizedTermKey(), laws, provenance, "container-witness-test-v1");
        Path file = Files.createTempFile("acgn-container-", ".acgncert");
        byte[] bytes;
        try { session.write(file); bytes = Files.readAllBytes(file); } finally { Files.deleteIfExists(file); }
        Wire.Node original = Codec.decode(bytes, Limits.defaults());
        check(find(original, "call-occurrence").isEmpty(), "fixture-only independently empty CALL census");
        PublicReplay replay = new PublicReplay(bytes, original);
        var positive = replay.verify(bytes);
        check(positive.outcome().equals("VERIFIED") && positive.code().equals("NONE"), "FULL original: " + positive);
        if (shape >= 0) {
            Wire.Node record = only(find(original, "flat-construction"));
            Wire.Node ledgerNode = record.child(1);
            check(ledgerNode.children().size() == 2, "four leaves have two distinct nested splice witnesses");
            wireTreeRow(family, shape, "original", record, flat.get(0), alphabet, true, "VERIFIED:NONE");
            List<Wire.Node> reversed = new ArrayList<>(ledgerNode.children());
            Collections.reverse(reversed);
            check(!reversed.equals(ledgerNode.children()), "ledger reversal is nonvacuous");
            Wire.Node changed = new Wire.Node(ledgerNode.tag(), ledgerNode.scalars(), reversed);
            Wire.Node candidate = replace(original, ledgerNode, changed);
            byte[] candidateBytes = Codec.encode(candidate);
            Codec.decode(candidateBytes, Limits.defaults());
            var rejected = replay.verify(candidateBytes);
            check(rejected.outcome().equals("REJECTED") && rejected.code().equals("THEORY_MISMATCH"),
                    "recursive ledger order rejects: " + rejected);
            wireTreeRow(family, shape, "reverseSplices", only(find(candidate, "flat-construction")),
                    flat.get(0), alphabet, false, rejected.outcome() + ":" + rejected.code());
            return;
        }
        simpleRow("wire", family, "original", true, "VERIFIED:NONE");
        List<String> mutations = new ArrayList<>(List.of("fiber", "inputCount", "outputCount", "traceKey", "left", "right", "target"));
        if (family == 3) mutations.add("inputOrder");
        else mutations.addAll(List.of("splicePath", "spliceOuter", "spliceNested", "splicePosition", "spliceSource", "missingSplice"));
        for (String label : mutations) {
            Wire.Node record = only(find(original, family == 3 ? "container-construction" : "flat-construction"));
            Wire.Node trace = only(find(record, "container-trace"));
            Wire.Node target;
            Wire.Node changed;
            if (label.equals("fiber")) {
                target = find(trace, "trace-output").get(0);
                changed = scalar(target, 1, "99");
            } else if (label.equals("inputCount") || label.equals("outputCount") || label.equals("traceKey")) {
                target = trace; int index = label.equals("inputCount") ? 2 : label.equals("outputCount") ? 3 : 4;
                changed = scalar(target, index, index == 4 ? StructuralKey.leaf("changed", "field").stableString() : "99");
            } else if (label.equals("left") || label.equals("right") || label.equals("target")) {
                target = record; int index = (family == 3 ? 4 : 5) + (label.equals("left") ? 1 : label.equals("right") ? 2 : 0);
                changed = scalar(target, index, label.equals("target") ? find(trace, "trace-input").get(0).scalar(0)
                        : StructuralKey.leaf("changed", "field").stableString());
            } else if (label.equals("inputOrder")) {
                target = record.child(0); List<Wire.Node> children = new ArrayList<>(target.children()); Collections.swap(children, 0, 1);
                changed = new Wire.Node(target.tag(), target.scalars(), children);
            } else if (label.equals("missingSplice")) {
                target = record.child(1); changed = new Wire.Node(target.tag(), target.scalars(), List.of());
            } else {
                target = only(find(record, "splice"));
                int index = List.of("splicePath", "spliceOuter", "spliceNested", "splicePosition", "spliceSource").indexOf(label);
                changed = scalar(target, index, index == 4 ? StructuralKey.leaf("changed", "field").stableString() : "99");
            }
            byte[] candidate = Codec.encode(replace(original, target, changed));
            Codec.decode(candidate, Limits.defaults());
            var result = replay.verify(candidate);
            check(result.outcome().equals("REJECTED") && (result.code().equals("THEORY_MISMATCH")
                    || result.code().equals("INVALID_RECORD_SHAPE")), "exact semantic/record control " + label + ": " + result);
            simpleRow("wire", family, label, false, result.outcome() + ":" + result.code());
        }
    }
    private static void wireTreeRow(int family, int shape, String label, Wire.Node record,
            FlatConstructionCertificate certificate, List<OnePort> alphabet, boolean accepted, String stage) {
        checkFlatIndex(certificate);
        Wire.Node trace = only(find(record, "container-trace"));
        List<Integer> expected = branches(List.of(1, 0, 1, 0)).get(shape).word();
        List<Integer> producerInputs = certificate.containerTrace().inputOccurrences().stream().map(alphabet::indexOf).toList();
        check(producerInputs.equals(expected), "four-leaf source repeats and order");
        List<Wire.Node> inputNodes = find(trace, "trace-input");
        check(inputNodes.size() == expected.size(), "exact wire input census");
        Map<String, Integer> identities = new LinkedHashMap<>();
        Map<Integer, String> terms = new LinkedHashMap<>();
        for (int i = 0; i < inputNodes.size(); i++) {
            String term = inputNodes.get(i).scalar(0);
            Integer old = identities.putIfAbsent(term, expected.get(i));
            String prior = terms.putIfAbsent(expected.get(i), term);
            check((old == null || old.equals(expected.get(i))) && (prior == null || prior.equals(term)),
                    "wire complete identities preserve exactly the source equality classes");
        }
        check(identities.size() == 2 && terms.size() == 2, "two distinct wire identities");
        String tree = wireTree(record.child(0), identities);
        check(tree.equals(branches(List.of(1, 0, 1, 0)).get(shape).toString()), "wire source association is exact");
        List<Integer> inputs = inputNodes.stream().map(n -> identities.get(n.scalar(0))).toList();
        List<Wire.Node> outputNodes = find(trace, "trace-output");
        List<Integer> outputs = outputNodes.stream().map(n -> identities.get(n.scalar(0))).toList();
        List<List<Integer>> fibers = outputNodes.stream().map(n -> n.scalars().subList(1, n.scalars().size())
                .stream().map(ContainerWitnessTransitionsRegressionTest::natural).toList()).toList();
        check(outputs.equals(certificate.containerTrace().outputOccurrences().stream().map(alphabet::indexOf).toList())
                && fibers.equals(certificate.containerTrace().outputFibers()), "actual wire outputs and quotient/permutation fibers");
        List<Wire.Node> ledger = record.child(1).children();
        List<List<Integer>> coordinates = new ArrayList<>();
        for (int i = 0; i < ledger.size(); i++) {
            Wire.Node splice = ledger.get(i);
            check(splice.tag().equals("splice") && splice.scalars().size() == 5 && splice.children().isEmpty(), "exact wire splice shape");
            var producer = certificate.splices().get(accepted ? i : ledger.size() - 1 - i);
            check(splice.scalar(4).equals(producer.nestedSource().stableString()), "exact nested source identity, not just arity");
            List<Integer> values = new ArrayList<>(producer.path());
            String path = producer.path().stream().map(Object::toString).collect(Collectors.joining("/"));
            check(splice.scalar(0).equals(path), "exact exported splice path");
            values.addAll(List.of(natural(splice.scalar(1)), natural(splice.scalar(2)), natural(splice.scalar(3))));
            check(values.subList(values.size() - 3, values.size()).equals(List.of(producer.outerArity(), producer.nestedArity(), producer.position())),
                    "exact wire outer/nested arities and position");
            coordinates.add(values);
        }
        row("wireTree", family, shape + ":" + label, tree, json(inputs), json(outputs), json(fibers), json(coordinates), accepted, 1, stage);
    }
    private static int natural(String value) {
        check(value.matches("0|[1-9][0-9]{0,4}"), "canonical bounded wire coordinate");
        return Integer.parseInt(value);
    }
    private static String wireTree(Wire.Node node, Map<String, Integer> identities) {
        if (node.tag().equals("flat-leaf")) {
            check(node.scalars().size() == 1 && node.children().isEmpty() && identities.containsKey(node.scalar(0)), "registered wire leaf");
            return identities.get(node.scalar(0)).toString();
        }
        check(node.tag().equals("flat-application") && natural(node.scalar(2)) == node.children().size(), "wire application arity");
        return node.children().stream().map(child -> wireTree(child, identities)).collect(Collectors.joining(",", "[", "]"));
    }

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
    private static void traceRow(String surface, int family, String fixture, String tree, ContainerApplicationTrace trace,
            List<OnePort> alphabet, List<FlatConstructionCertificate.Splice> splices, int controls, String stage) {
        List<List<Integer>> coordinates = splices.stream().map(s -> {
            List<Integer> values = new ArrayList<>(s.path()); values.addAll(List.of(s.outerArity(), s.nestedArity(), s.position())); return values;
        }).toList();
        row(surface, family, fixture, tree, json(trace.inputOccurrences().stream().map(alphabet::indexOf).toList()),
                json(trace.outputOccurrences().stream().map(alphabet::indexOf).toList()), json(trace.outputFibers()), json(coordinates), true, controls, stage);
    }
    private static void simpleRow(String surface, int family, String fixture, boolean accepted, String stage) {
        row(surface, family, fixture, "[]", "[]", "[]", "[]", "[]", accepted, 1, stage);
    }
    private static void row(String surface, int family, String fixture, String tree, String inputs, String outputs,
            String fibers, String splices, boolean accepted, int controls, String stage) {
        List<String> fields = List.of(Integer.toString(ROWS.size() - 1), surface, Integer.toString(family), fixture, tree,
                inputs, outputs, fibers, splices, Boolean.toString(accepted), "true", Integer.toString(controls), stage);
        for (String field : fields) check(!field.contains("\t") && !field.contains("\n") && !field.contains("\r"), "canonical TSV field");
        ROWS.add(String.join("\t", fields));
    }
    private static String json(List<?> values) { return values.stream().map(Object::toString).collect(Collectors.joining(",", "[", "]")).replace(" ", ""); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Expected supported-path rejection");
    }
}

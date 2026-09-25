package is.fivefivefive.CanDis.theory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.json.JSONWriter;

import is.fivefivefive.CanDis.core.AlloyOperatorPolicy;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/**
 * P2-16 finite observations at the production Boolean K+ construction boundary.
 * These are synthetic typed invocations, not parser-authenticated source terms,
 * source smart-constructor constants, or graph insertions. No Boolean evaluator,
 * normalization oracle, expected output, or replacement trace is used here.
 *
 * Rows are ordered by profile FORBID/MODULAR, head AND/OR, length, lexicographic
 * word, then flat followed by binary associations (in increasing root split
 * order, recursively). The length-two flat and binary rows deliberately overlap;
 * shape distinguishes them. All ordered full binary associations are retained.
 *
 * source is read from the actual requested FlatApplication operands, separately
 * from certificateSource and traceInput. For empty input only, FlatApplication
 * rejects its actual empty operand list before an application or certificate
 * exists: its source is {children:[]}, unavailable encodings/checks are null,
 * and empty output/trace arrays mean no observations (tracePresent is false).
 * output is read from the returned singleton/node, traceOutput and fibers from
 * certificate.containerTrace(). Atom IDs use complete OnePort equality; the
 * atom table retains schema, context, e-class interface, and embedding identity.
 *
 * sourceFlowId names the documentation classes flat/REJECTED_EMPTY,
 * flat/SINGLETON, flat/NODE, binary/SINGLETON, and binary/NODE. sourceFlows maps
 * the individual JSON observation sides to their Java classes and accessors.
 * Empty rows have status EXPECTED_REJECTED, never a true/false interpretation.
 * Run with no arguments for stdout or with one JSON output path.
 */
public final class BooleanConstructionReplayProbe {
    private static final int ALPHABET_SIZE = 3;
    private static final int MAX_LENGTH = 4;
    private static final PortPath PATH = PortPath.at(0);
    private static final List<ContainerLawCertificate.Law> ACI = List.of(
            ContainerLawCertificate.Law.ASSOCIATIVITY,
            ContainerLawCertificate.Law.COMMUTATIVITY,
            ContainerLawCertificate.Law.IDEMPOTENCY);

    private BooleanConstructionReplayProbe() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException(
                    "Usage: BooleanConstructionReplayProbe [output.json]");
        }
        try (BufferedWriter output = args.length == 0
                ? new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))
                : Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8)) {
            writeDocument(new JSONWriter(output));
            output.newLine();
        }
    }

    private static void writeDocument(JSONWriter json) {
        List<TypedSlot> slots = new ArrayList<>();
        for (int id = 0; id < ALPHABET_SIZE; id++) {
            slots.add(TypedSlot.source(GraphType.BOOL, id));
        }
        TypedSlotContext context = TypedSlotContext.of(slots);
        List<OnePort> atoms = new ArrayList<>();
        for (TypedSlot slot : slots) {
            TypedSlotContext exposed = TypedSlotContext.singleton(slot);
            TypedEClassInterface eclass = new TypedEClassInterface(
                    EClassId.of(slot.ordinal().longValueExact()), GraphType.BOOL, exposed);
            atoms.add(OnePort.invocation(context, new TypedInvocation(
                    eclass, TypedEmbedding.inclusion(exposed, context))));
        }
        require(new HashSet<>(atoms).size() == ALPHABET_SIZE,
                "The complete typed atom identities must be distinct");
        List<Fixture> fixtures = new ArrayList<>();
        for (SemanticProfile profile : List.of(
                SemanticProfile.alloyOverflowForbidding(), SemanticProfile.alloyModular())) {
            for (Opcode opcode : List.of(Opcode.AND, Opcode.OR)) {
                fixtures.add(new Fixture(profile, opcode, context, atoms));
            }
        }
        json.object()
                .key("schemaVersion").value(1)
                .key("alphabetSize").value(ALPHABET_SIZE)
                .key("maxLength").value(MAX_LENGTH)
                .key("sourceFlows").object()
                    .key("source").value("FlatApplication.operands / FlatLeaf.port")
                    .key("emptySource").value("FlatApplication.<init>: attempted operands")
                    .key("certificateSource").value("FlatConstructionCertificate.source")
                    .key("sourceEndpointEncoding").value("TypedEqualityCertificate.leftEndpoint")
                    .key("targetEndpointEncoding").value("TypedEqualityCertificate.rightEndpoint")
                    .key("output").value("CertifiedFlatConstruction.singleton / node -> SetPort.elements")
                    .key("traceInput").value("FlatConstructionCertificate.containerTrace -> ContainerApplicationTrace.inputOccurrences")
                    .key("traceOutput").value("FlatConstructionCertificate.containerTrace -> ContainerApplicationTrace.outputOccurrences")
                    .key("fibers").value("FlatConstructionCertificate.containerTrace -> ContainerApplicationTrace.outputFibers")
                    .key("laws").value("InstantiatedOperator.lawForPath -> ContainerLawDeclaration.certificates")
                    .key("premiseLaws").value("FlatConstructionCertificate.premises -> ContainerLawCertificate.law")
                .endObject()
                .key("typeEncoding").value(TheoryKeys.type(GraphType.BOOL).stableString())
                .key("contextEncoding").value(TheoryKeys.context(context).stableString())
                .key("atoms").array();
        for (int id = 0; id < atoms.size(); id++) {
            json.object().key("id").value(id)
                    .key("slotEncoding").value(TheoryKeys.slot(slots.get(id)).stableString())
                    .key("portEncoding").value(atoms.get(id).structuralKey().stableString())
                    .endObject();
        }
        json.endArray().key("fixtures").array();
        for (Fixture fixture : fixtures) {
            fixture.write(json);
        }
        json.endArray().key("rows").array();
        int rowId = 0;
        for (Fixture fixture : fixtures) {
            int wordCount = 1;
            for (int length = 0; length <= MAX_LENGTH; length++) {
                for (int word = 0; word < wordCount; word++) {
                    List<FlatLeaf> leaves = wordLeaves(word, length, atoms);
                    if (length == 0) {
                        writeEmpty(json, rowId++, fixture, leaves);
                        continue;
                    }
                    FlatApplication flat = new FlatApplication(
                            fixture.operator, context, leaves);
                    writeRow(json, rowId++, "flat", fixture, flat);
                    if (length >= 2) {
                        List<FlatInput> associations = binaryAssociations(fixture, leaves);
                        require(associations.size() == (length == 2 ? 1 : length == 3 ? 2 : 5),
                                "Incomplete binary association enumeration");
                        require(new HashSet<>(associations).size() == associations.size(),
                                "Duplicate binary source tree");
                        for (FlatInput association : associations) {
                            writeRow(json, rowId++, "binary", fixture,
                                    (FlatApplication) association);
                        }
                    }
                }
                wordCount *= ALPHABET_SIZE;
            }
        }
        require(rowId == 2356, "Unexpected enumeration size: " + rowId);
        json.endArray().endObject();
    }

    private static List<FlatLeaf> wordLeaves(int word, int length, List<OnePort> atoms) {
        int[] ids = new int[length];
        for (int index = length - 1; index >= 0; index--) {
            ids[index] = word % ALPHABET_SIZE;
            word /= ALPHABET_SIZE;
        }
        List<FlatLeaf> leaves = new ArrayList<>();
        for (int id : ids) {
            leaves.add(new FlatLeaf(atoms.get(id)));
        }
        return leaves;
    }

    private static List<FlatInput> binaryAssociations(Fixture fixture, List<FlatLeaf> leaves) {
        if (leaves.size() == 1) {
            return List.of(leaves.get(0));
        }
        List<FlatInput> result = new ArrayList<>();
        for (int split = 1; split < leaves.size(); split++) {
            for (FlatInput left : binaryAssociations(fixture, leaves.subList(0, split))) {
                for (FlatInput right : binaryAssociations(
                        fixture, leaves.subList(split, leaves.size()))) {
                    result.add(new FlatApplication(
                            fixture.operator, fixture.context, List.of(left, right)));
                }
            }
        }
        return result;
    }

    private static void writeEmpty(
            JSONWriter json, int id, Fixture fixture, List<FlatLeaf> operands) {
        require(operands.isEmpty() && !fixture.schema.arityPolicy().admits(0),
                "Expected an empty request at the K+ boundary");
        IllegalArgumentException rejection;
        try {
            new FlatApplication(fixture.operator, fixture.context, operands);
            throw new IllegalStateException("The K+ source boundary admitted empty operands");
        } catch (IllegalArgumentException failure) {
            require(failure.getClass() == IllegalArgumentException.class
                            && ("Visible flat source arity 0 is not admitted by "
                                    + fixture.schema.arityPolicy()).equals(failure.getMessage()),
                    "Unexpected empty-source failure: " + failure);
            rejection = failure;
        }
        beginRow(json, id, "flat", "REJECTED_EMPTY", fixture);
        json.key("source");
        writeChildren(json, operands, fixture);
        json.key("status").value("EXPECTED_REJECTED")
                .key("rejectionStage").value("FlatApplication.<init>")
                .key("exceptionClass").value(rejection.getClass().getName())
                .key("exceptionMessage").value(rejection.getMessage())
                .key("output").value(List.of())
                .key("traceInput").value(List.of())
                .key("traceOutput").value(List.of())
                .key("fibers").value(List.of())
                .key("certificatePresent").value(false)
                .key("tracePresent").value(false)
                .key("certificateSource").value(null)
                .key("sourceEncoding").value(null)
                .key("sourceEndpointEncoding").value(null)
                .key("targetEndpointEncoding").value(null)
                .key("targetEncoding").value(null)
                .key("traceEncoding").value(null)
                .key("certificateVerified").value(null)
                .key("sourceBound").value(null)
                .key("targetBound").value(null)
                .key("premiseLaws").value(List.of())
                .key("splices").value(List.of())
                .endObject();
    }

    private static void writeRow(
            JSONWriter json, int id, String shape, Fixture fixture, FlatApplication source) {
        TypedCertificateEndpoint expectedSource = TypedCertificateEndpoint.flatApplication(
                source, fixture.profile);
        CertifiedFlatConstruction result = TypedENode.flatConstructCertified(
                source,
                ignored -> {
                    throw new AssertionError("Same-head visible trees must not invoke the sealer");
                },
                fixture.profile);
        FlatConstructionCertificate certificate = result.certificate();
        CertificateVerifier.verify(certificate);
        require(source.equals(certificate.source())
                        && expectedSource.equals(certificate.leftEndpoint())
                        && fixture.profile.equals(certificate.semanticProfile())
                        && PATH.equals(certificate.path()),
                "Certificate is not bound to the requested typed source in row " + id);
        require(result.collapsedToSingleton() == certificate.collapsedToSingleton(),
                "Result and certificate target kinds disagree in row " + id);

        List<? extends PortValue> output;
        TypedCertificateEndpoint expectedTarget;
        StructuralKey targetKey;
        String outcome;
        if (result.collapsedToSingleton()) {
            OnePort singleton = result.singleton();
            fixture.atomId(singleton);
            require(singleton.equals(certificate.singletonTarget()),
                    "Certificate reaches a different singleton in row " + id);
            output = List.of(singleton);
            expectedTarget = TypedCertificateEndpoint.oneTerm(singleton);
            targetKey = singleton.structuralKey();
            outcome = "SINGLETON";
        } else {
            TypedENode node = result.node();
            require(node.equals(certificate.target())
                            && fixture.operator.equals(node.operator())
                            && fixture.context.equals(node.context())
                            && GraphType.BOOL.equals(node.outputType())
                            && node.ports().size() == 1
                            && node.ports().get(0) instanceof SetPort,
                    "Node changed its complete operator, context, or type in row " + id);
            SetPort container = (SetPort) node.ports().get(0);
            require(fixture.schema.equals(container.schema())
                            && fixture.context.equals(container.context())
                            && container.elements().size() > 1,
                    "Malformed stored Boolean carrier in row " + id);
            output = container.elements();
            expectedTarget = TypedCertificateEndpoint.node(node);
            targetKey = node.structuralKey();
            outcome = "NODE";
        }
        require(expectedTarget.equals(certificate.rightEndpoint()),
                "Certificate is not bound to the actual typed target in row " + id);
        ContainerApplicationTrace trace = certificate.containerTrace();
        require(fixture.schema.equals(trace.schema())
                        && fixture.context.equals(trace.context())
                        && output.equals(trace.outputOccurrences()),
                "Certificate trace is not bound to the actual output in row " + id);
        List<String> premiseLaws = new ArrayList<>();
        for (TypedEqualityCertificate premise : certificate.premises()) {
            require(premise instanceof ContainerLawCertificate,
                    "Unexpected construction premise in row " + id);
            ContainerLawCertificate law = (ContainerLawCertificate) premise;
            require(law.law() != ContainerLawCertificate.Law.UNIT
                            && law.equals(fixture.laws.certificates().get(law.law())),
                    "Construction premise is not an exact declared ACI law in row " + id);
            premiseLaws.add(law.law().name());
        }

        beginRow(json, id, shape, outcome, fixture);
        json.key("source");
        writeTree(json, source, fixture);
        json.key("certificateSource");
        writeTree(json, certificate.source(), fixture);
        json.key("output").value(fixture.atomIds(output))
                .key("traceInput").value(fixture.atomIds(trace.inputOccurrences()))
                .key("traceOutput").value(fixture.atomIds(trace.outputOccurrences()))
                .key("fibers").value(trace.outputFibers())
                .key("certificatePresent").value(true)
                .key("tracePresent").value(true)
                .key("sourceEncoding").value(source.structuralKey().stableString())
                .key("sourceEndpointEncoding").value(certificate.leftEndpoint().structuralKey().stableString())
                .key("targetEndpointEncoding").value(certificate.rightEndpoint().structuralKey().stableString())
                .key("targetEncoding").value(targetKey.stableString())
                .key("traceEncoding").value(trace.structuralKey().stableString())
                .key("certificateVerified").value(true)
                .key("sourceBound").value(true)
                .key("targetBound").value(true)
                .key("premiseLaws").value(premiseLaws)
                .key("splices").array();
        for (FlatConstructionCertificate.Splice splice : certificate.splices()) {
            json.object().key("path").value(splice.path())
                    .key("outerArity").value(splice.outerArity())
                    .key("nestedArity").value(splice.nestedArity())
                    .key("position").value(splice.position())
                    .key("nestedSourceEncoding").value(splice.nestedSource().stableString())
                    .endObject();
        }
        json.endArray().endObject();
    }

    private static void beginRow(
            JSONWriter json, int id, String shape, String outcome, Fixture fixture) {
        json.object().key("id").value(id)
                .key("head").value(fixture.opcode.name())
                .key("profile").value(fixture.profile.overflowMode().name())
                .key("shape").value(shape)
                .key("outcome").value(outcome)
                .key("sourceFlowId").value(shape + "/" + outcome)
                .key("fixtureId").value(fixture.id())
                .key("laws").value(fixture.lawNames())
                .key("unitLicense").value(fixture.laws.unitLicense().name())
                .key("hasUnit").value(fixture.laws.hasUnit());
    }

    private static void writeTree(JSONWriter json, FlatInput input, Fixture fixture) {
        require(fixture.context.equals(input.context())
                        && GraphType.BOOL.equals(input.outputType()),
                "Source tree changed its exact context or type");
        if (input instanceof FlatLeaf) {
            json.object().key("leaf").value(fixture.atomId(((FlatLeaf) input).port())).endObject();
            return;
        }
        require(input instanceof FlatApplication, "Unknown source input implementation");
        FlatApplication application = (FlatApplication) input;
        require(fixture.operator.equals(application.operator()),
                "Source tree changed its complete operator instance");
        writeChildren(json, application.operands(), fixture);
    }

    private static void writeChildren(
            JSONWriter json, List<? extends FlatInput> operands, Fixture fixture) {
        json.object().key("children").array();
        for (FlatInput operand : operands) {
            writeTree(json, operand, fixture);
        }
        json.endArray().endObject();
    }

    private static final class Fixture {
        private final SemanticProfile profile;
        private final Opcode opcode;
        private final TypedSlotContext context;
        private final List<OnePort> atoms;
        private final SetPortSchema schema;
        private final ContainerLawDeclaration laws;
        private final InstantiatedOperator operator;
        private final AlloyOperatorPolicy policy;

        private Fixture(
                SemanticProfile profile, Opcode opcode, TypedSlotContext context,
                List<OnePort> atoms) {
            this.profile = profile;
            this.opcode = opcode;
            this.context = context;
            this.atoms = List.copyOf(atoms);
            this.schema = new SetPortSchema(
                    ArityPolicy.nonemptyVariadic(), new OnePortSchema(GraphType.BOOL));
            String head = "ALLOY/" + opcode.name();
            List<ContainerLawCertificate> certificates = new ArrayList<>();
            for (ContainerLawCertificate.Law law : ACI) {
                ContainerLawCertificate certificate = AlloyLawRegistry.issue(
                        profile, opcode, head, GraphType.BOOL, PATH, schema, law);
                CertificateVerifier.verify(certificate);
                require(certificate.authority() == ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY
                                && AlloyLawRegistry.accepts(certificate),
                        "Not a production Alloy law certificate");
                certificates.add(certificate);
            }
            this.laws = ContainerLawDeclaration.certified(schema, certificates);
            this.operator = OperatorDeclaration.monomorphic(
                    head, List.of(schema), GraphType.BOOL, Map.of(PATH, laws), 0)
                    .instantiateMonomorphic();
            this.policy = AlloyOperatorPolicy.forShape(opcode, -1, true, profile);
            require(new ArrayList<>(laws.certificates().keySet()).equals(ACI)
                            && !laws.hasUnit() && laws.unitLicense() == UnitLicense.ABSENT
                            && policy.unitLicense() == UnitLicense.ABSENT
                            && policy.arityPolicy().equals(schema.arityPolicy())
                            && !schema.arityPolicy().admits(0)
                            && operator.lawForPath(PATH).equals(laws),
                    "Production Boolean K+ policy or law inventory changed");
        }

        private String id() {
            return opcode.name() + "/" + profile.overflowMode().name();
        }

        private int atomId(PortValue value) {
            require(value instanceof OnePort
                            && context.equals(value.context())
                            && new OnePortSchema(GraphType.BOOL).equals(value.schema()),
                    "Occurrence is not an exact typed Boolean OnePort in this context");
            int id = atoms.indexOf(value);
            require(id >= 0, "Occurrence is outside the complete typed input alphabet");
            return id;
        }

        private List<Integer> atomIds(List<? extends PortValue> ports) {
            List<Integer> ids = new ArrayList<>();
            for (PortValue port : ports) {
                ids.add(atomId(port));
            }
            return ids;
        }

        private List<String> lawNames() {
            List<String> names = new ArrayList<>();
            for (ContainerLawCertificate certificate : laws.certificates().values()) {
                names.add(certificate.law().name());
            }
            return names;
        }

        private void write(JSONWriter json) {
            json.object().key("id").value(id())
                    .key("head").value(opcode.name())
                    .key("profile").value(profile.overflowMode().name())
                    .key("profileEncoding").value(profile.structuralKey().stableString())
                    .key("operatorEncoding").value(operator.structuralKey().stableString())
                    .key("schemaEncoding").value(schema.structuralKey().stableString())
                    .key("arityPolicyEncoding").value(schema.arityPolicy().structuralKey().stableString())
                    .key("policyUnitLicense").value(policy.unitLicense().name())
                    .key("unitLicense").value(laws.unitLicense().name())
                    .key("hasUnit").value(laws.hasUnit())
                    .key("registryVersion").value(AlloyLawRegistry.VERSION)
                    .key("verifierVersion").value(CertificateVerifier.version())
                    .key("lawInventory").array();
            for (ContainerLawCertificate certificate : laws.certificates().values()) {
                json.object().key("law").value(certificate.law().name())
                        .key("authority").value(certificate.authority().name())
                        .key("sourceTheoryDigest").value(certificate.sourceTheoryDigest())
                        .key("certificateEncoding").value(certificate.structuralKey().stableString())
                        .endObject();
            }
            json.endArray().endObject();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}

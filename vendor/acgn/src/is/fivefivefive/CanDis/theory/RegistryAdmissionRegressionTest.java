package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** Finite P2-19 issue/verify conformance. No new production law or authority. */
public final class RegistryAdmissionRegressionTest {
    private static final Opcode[] OPS = {Opcode.AND, Opcode.OR, Opcode.PLUS, Opcode.INTERSECT,
            Opcode.IPLUS, Opcode.MUL, Opcode.EQUALS, Opcode.NOT_EQUALS, Opcode.IFF, Opcode.DISJOINT, Opcode.CALL};
    private static final GraphType RX = GraphType.relation(GraphType.constructor("AlloySig:RegistryX"));
    private static final GraphType RY = GraphType.relation(GraphType.constructor("AlloySig:RegistryY"));
    private static final GraphType OTHER = GraphType.constructor("RegistryOpaque");
    private static final GraphType[][] PAIRS = {{GraphType.BOOL, GraphType.BOOL},
            {GraphType.INT, GraphType.INT}, {RX, RX}, {RX, RY}, {OTHER, OTHER},
            {GraphType.BOOL, GraphType.INT}, {GraphType.BOOL, RX}, {GraphType.BOOL, null},
            {GraphType.BOOL, OTHER}};
    private static final ArityPolicy[] ARITIES = {ArityPolicy.nonemptyVariadic(),
            ArityPolicy.exact(2), ArityPolicy.zeroOrMore(), ArityPolicy.finite(1, 2)};
    private static int checks, issued, rejections, fieldControls;
    private static final List<String> ROWS = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("Usage: RegistryAdmissionRegressionTest [trace.tsv]");
        checks = issued = rejections = fieldControls = 0;
        ROWS.clear();
        ROWS.add("case\tgroup\tprofile\top\tpair\tcarrier\tarity\tpath\tidentity\tbitwidth\tmodular"
                + "\ttemporal\trewrite\tsignature\tlaw\tissued\tindexMatches\taccepted\tfieldControls\tstage");
        for (int mode = 0; mode < 2; mode++) {
            SemanticProfile profile = mode == 0 ? SemanticProfile.alloyOverflowForbidding() : SemanticProfile.alloyModular();
            for (int op = 0; op < OPS.length; op++) for (int pair = 0; pair < PAIRS.length; pair++)
                for (int carrier = 0; carrier < 3; carrier++) for (int arity = 0; arity < ARITIES.length; arity++)
                    for (ContainerLawCertificate.Law law : ContainerLawCertificate.Law.values())
                        exercise("grid", "compatibility", profile, op, pair, carrier, arity, 0, true, law);
            SemanticProfile custom = new SemanticProfile(profile.bitwidth(), profile.overflowMode(),
                    profile.temporalMode(), profile.rewriteMode(), profile.signatureVersion());
            check(custom.equals(profile) && custom.fingerprint().equals(profile.fingerprint()), "clone really has the same profile key");
            check(!custom.isAdmissibleAlloyProfile(), "profile spelling does not mint authority");
            for (int variant = 0; variant < 3; variant++) {
                exercise("boundary", variant == 0 ? "custom" : "compatibility",
                        variant == 0 ? custom : profile, 0, 0, 2, 0, variant == 1 ? 1 : 0,
                        variant != 2, ContainerLawCertificate.Law.ASSOCIATIVITY);
            }
        }
        sourceProfiles();
        unsupportedOpcodes();
        check(ROWS.size() == 9517, "9504 grid + 6 boundary + 6 source rows");
        if (args.length == 1) Files.write(Path.of(args[0]), ROWS, StandardCharsets.UTF_8);
        System.out.println("RegistryAdmissionRegressionTest passed: rows=" + (ROWS.size() - 1)
                + " issued=" + issued + " rejected=" + rejections + " fieldControls=" + fieldControls + " checks=" + checks);
    }

    private static void sourceProfiles() throws Exception {
        for (int width : new int[] {3, 4, 6}) for (boolean modular : new boolean[] {false, true}) {
            var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                    "sig A {} pred p { some A } run p for 3 but " + width + " Int");
            A4Options options = new A4Options();
            options.noOverflow = !modular;
            SemanticProfile profile = AlloySemanticProfileFactory.fromExactlyOne(module,
                    List.of(module.getAllCommands().get(0)), options);
            check(profile.isSourceCommandBound() && profile.isAuthorizedAlloyProfile(), "real parser command authority");
            exercise("source", "source", profile, 0, 0, 2, 0, 0, true, ContainerLawCertificate.Law.ASSOCIATIVITY);
        }
    }

    private static void unsupportedOpcodes() {
        for (Opcode op : Opcode.values()) {
            if (List.of(OPS).contains(op) && op != Opcode.CALL) continue;
            for (ContainerLawCertificate.Law law : ContainerLawCertificate.Law.values()) {
                boolean rejected = false;
                try {
                    AlloyLawRegistry.issue(SemanticProfile.alloyModular(), op, "ALLOY/" + op,
                            GraphType.BOOL, PortPath.at(0), new SetPortSchema(ArityPolicy.nonemptyVariadic(),
                                    new OnePortSchema(GraphType.BOOL)), law);
                } catch (IllegalStateException expected) { rejected = true; }
                check(rejected, "unlisted opcode cannot acquire registry law: " + op + "/" + law);
            }
        }
    }

    private static PortSchema schema(int pair, int carrier, int arity) {
        PortSchema element = PAIRS[pair][1] == null
                ? new SeqPortSchema(ArityPolicy.nonemptyVariadic(), new OnePortSchema(GraphType.BOOL))
                : new OnePortSchema(PAIRS[pair][1]);
        return switch (carrier) {
            case 0 -> new SeqPortSchema(ARITIES[arity], element);
            case 1 -> new BagPortSchema(ARITIES[arity], element);
            case 2 -> new SetPortSchema(ARITIES[arity], element);
            default -> throw new AssertionError("carrier");
        };
    }

    private static void exercise(String group, String authority, SemanticProfile profile, int op,
            int pair, int carrier, int arity, int path, boolean exactIdentity, ContainerLawCertificate.Law law) throws Exception {
        PortSchema schema = null;
        try { schema = schema(pair, carrier, arity); }
        catch (IllegalArgumentException rejected) {
            check(carrier == 2 && arity == 1, "only the registered Set finite-two schema rejects before registry admission");
        }
        String identity = exactIdentity ? "ALLOY/" + OPS[op] : "OTHER/" + OPS[op];
        ContainerLawCertificate certificate = null;
        try {
            if (schema != null)
            certificate = AlloyLawRegistry.issue(profile, OPS[op], identity, PAIRS[pair][0], PortPath.at(path), schema, law);
        } catch (IllegalStateException rejected) { rejections++; }
        boolean expected = expected(op, pair, carrier, arity, law, profile.overflowMode() == SemanticProfile.OverflowMode.MODULAR)
                && !authority.equals("custom") && exactIdentity && path == 0;
        check((certificate != null) == expected, "independent finite registry table: " + group + "/" + op + "/" + pair);
        boolean indexMatches = false, accepted = false;
        int before = fieldControls;
        if (certificate != null) {
            issued++;
            StructuralKey parameter = parameter(profile, OPS[op], PAIRS[pair][0], PortPath.at(path), schema, law);
            StructuralKey index = StructuralKey.of("container-law-index-v2", List.of("ALLOY_PROFILE_THEORY", identity,
                    PortPath.at(path).toString(), law.name(), AlloyLawRegistry.SOURCE_THEORY_DIGEST),
                    List.of(profile.structuralKey(), TheoryKeys.type(PAIRS[pair][0]), schema.structuralKey(), parameter));
            CertificateOrigin origin = CertificateOrigin.containerLaw(AlloyLawRegistry.VERSION + "/" + AlloyLawRegistry.SOURCE_THEORY_DIGEST,
                    identity + "@" + PortPath.at(path) + ":" + law + ":" + sha256(parameter.stableString()), law.ordinal());
            indexMatches = certificate.lawIndex().equals(index) && certificate.lawParameter().equals(parameter)
                    && certificate.origin().equals(origin) && certificate.semanticProfile().fingerprint().equals(profile.fingerprint())
                    && certificate.leftSourceEndpoint().equals(StructuralKey.of("container-law-source-endpoint", List.of("left"), List.of(index)))
                    && certificate.rightSourceEndpoint().equals(StructuralKey.of("container-law-source-endpoint", List.of("right"), List.of(index)));
            accepted = AlloyLawRegistry.accepts(certificate);
            check(indexMatches && accepted, "exact issued fields and independent acceptance");
            certificate.verifyLocal();
            fieldControls(certificate);
        }
        ROWS.add(String.join("\t", Integer.toString(ROWS.size() - 1), group, authority, Integer.toString(op),
                Integer.toString(pair), Integer.toString(carrier), Integer.toString(arity), Integer.toString(path),
                Boolean.toString(exactIdentity), Integer.toString(profile.bitwidth()), Boolean.toString(profile.overflowMode() == SemanticProfile.OverflowMode.MODULAR),
                b64(profile.temporalMode()), b64(profile.rewriteMode()), b64(profile.signatureVersion()), Integer.toString(law.ordinal()),
                Boolean.toString(certificate != null), Boolean.toString(indexMatches), Boolean.toString(accepted), Integer.toString(fieldControls - before),
                schema == null ? "SCHEMA_REJECTED" : certificate == null ? "REGISTRY_REJECTED" : "ISSUED"));
    }

    // Independent finite table in fixture coordinates, not the production predicate.
    private static boolean expected(int op, int pair, int carrier, int arity, ContainerLawCertificate.Law law, boolean modular) {
        int l = law.ordinal();
        return switch (op) {
            case 0, 1 -> pair == 0 && carrier == 2 && arity == 0 && l < 3;
            case 2, 3 -> (pair == 1 || pair == 2) && carrier == 2 && arity == 0 && l < 3;
            case 4, 5 -> pair == 1 && carrier == 1 && (modular ? arity == 0 && l < 2 : arity == 1 && l == 1);
            case 6, 7 -> (pair == 0 || pair == 5 || pair == 6 || pair == 8) && carrier == 1 && arity == 1 && l == 1;
            case 8 -> pair == 0 && carrier == 1 && arity == 1 && l == 1;
            case 9 -> pair == 6 && carrier == 1 && arity == 0 && l == 1;
            default -> false;
        };
    }

    private static StructuralKey parameter(SemanticProfile profile, Opcode op, GraphType result,
            PortPath path, PortSchema schema, ContainerLawCertificate.Law law) {
        String family = switch (law) {
            case ASSOCIATIVITY -> "all-legal-outer-nested-arities-and-splice-positions";
            case COMMUTATIVITY -> "all-admitted-sibling-permutations";
            case IDEMPOTENCY -> "all-admitted-quotient-surjections";
            case UNIT -> "exact-empty-fold-deletion";
        };
        return StructuralKey.of("alloy-law-parameter-v1", List.of(op.name(), path.toString(), law.name(), family),
                List.of(profile.structuralKey(), TheoryKeys.type(result), schema.structuralKey()));
    }

    private static void fieldControls(ContainerLawCertificate c) {
        for (int mutation = 0; mutation < 9; mutation++) {
            SemanticProfile p = c.semanticProfile();
            String identity = c.operatorIdentity();
            GraphType result = c.resultType();
            PortPath path = c.schemaPath();
            PortSchema schema = c.schema();
            ContainerLawCertificate.Law law = c.law();
            CertificateOrigin origin = c.origin();
            StructuralKey parameter = c.lawParameter();
            String digest = c.sourceTheoryDigest();
            switch (mutation) {
                case 0 -> digest = "different-theory";
                case 1 -> parameter = StructuralKey.leaf("wrong-parameter", "0");
                case 2 -> origin = CertificateOrigin.containerLaw("wrong-theory", "wrong-declaration", 0);
                case 3 -> p = p.overflowMode() == SemanticProfile.OverflowMode.FORBID
                        ? SemanticProfile.alloyModular() : SemanticProfile.alloyOverflowForbidding();
                case 4 -> identity = "ALLOY/CALL";
                case 5 -> result = GraphType.constructor("WrongResult");
                case 6 -> path = PortPath.at(1);
                case 7 -> schema = new SeqPortSchema(ArityPolicy.exact(2), new OnePortSchema(GraphType.INT));
                case 8 -> law = ContainerLawCertificate.Law.UNIT;
                default -> throw new AssertionError();
            }
            boolean rejected = false;
            try { ContainerLawCertificate.trustedAlloy(schema, law, origin, p, identity, result, path, parameter, digest); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "one-field changed certificate rejected: " + mutation);
            fieldControls++;
        }
    }

    private static String b64(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}

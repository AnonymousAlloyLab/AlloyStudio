package is.fivefivefive.CanDis.theory;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Finite P3-03 observations through public writer and standalone verifier APIs. */
public final class SemanticProfileWireRegressionTest {
    private static final List<String> TEXTS = List.of("plain", "[]{}:012", "quote\"slash\\",
            "line\nnext\tcell", "\u03b1\u4e2d", "\ud83d\ude00", "e\u0301", "x\u0000y");
    private static final List<String> ROWS = new ArrayList<>(List.of(
            "case\tsurface\tfixture\tmutation\tauthority\ttestOnly\tbitwidth\toverflow\ttemporal\trewrite\tsignature\tdigest\tproducerKey\tindependentKey\twriter\toutcome\tdetail"));
    private static int checks;

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    // Public API reflection keeps src-only experiment compilation independent
    // of the verifier. No setAccessible, private member, or field writes.
    private static Class<?> cls(String name) throws Exception { return Class.forName("org.acgn.cert." + name); }

    private static Object api(Object target, Class<?> owner, String name, Class<?>[] types, Object... args)
            throws Exception {
        try { return owner.getMethod(name, types).invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    private static Object get(Object target, String name) throws Exception {
        return api(target, target.getClass(), name, new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    private static List<String> scalars(Object node) throws Exception { return (List<String>) get(node, "scalars"); }

    @SuppressWarnings("unchecked")
    private static List<Object> children(Object node) throws Exception { return (List<Object>) get(node, "children"); }

    private static Object node(String tag, List<String> scalars, List<Object> children) throws Exception {
        return api(null, cls("Wire"), "node", new Class<?>[]{String.class, List.class, List.class}, tag, scalars, children);
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String packed(List<String> values) {
        return String.join("|", values.stream().map(SemanticProfileWireRegressionTest::b64).toList());
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String framedDigest(String... values) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String independentKey(List<String> fields) {
        return "16:semantic-profile[5:" + fields.stream().map(s -> s.length() + ":" + s)
                .collect(java.util.stream.Collectors.joining()) + "]{0:}";
    }

    private static List<String> fields(SemanticProfile p) {
        return List.of(Integer.toString(p.bitwidth()), p.overflowMode().name(),
                p.temporalMode(), p.rewriteMode(), p.signatureVersion());
    }

    private static SemanticProfile custom(List<String> f) {
        return new SemanticProfile(Integer.parseInt(f.get(0)), SemanticProfile.OverflowMode.valueOf(f.get(1)),
                f.get(2), f.get(3), f.get(4));
    }

    private static SemanticProfile source(int width, boolean modular) throws Exception {
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {} pred wire { some A } run wire for 3 but " + width + " Int");
        var options = new A4Options();
        options.noOverflow = !modular;
        return AlloySemanticProfileFactory.fromExactlyOne(module, module.getAllCommands(), options);
    }

    private static CertificateExportSession session(SemanticProfile profile, CertificateProvenance provenance) {
        return session(profile, profile, provenance, new RecordingCertificateTraceSink());
    }

    private static CertificateExportSession session(SemanticProfile profile, SemanticProfile graphProfile,
            CertificateProvenance provenance, RecordingCertificateTraceSink sink) {
        // The clone control retains the explicitly supplied admitted graph
        // profile but gives its artifact the equal-spelling custom profile.
        var graph = new TypedSlottedPortEGraph(graphProfile, sink);
        var node = TypedENode.construct(OperatorDeclaration.monomorphic("wire-constant", List.of(),
                GraphType.BOOL, Map.of(), null).instantiateMonomorphic(), TypedSlotContext.empty(), List.of());
        var insertion = graph.insertNode(node, graph.coherentWitnessFamily());
        var family = graph.coherentWitnessFamily();
        var unfolding = graph.finiteUnfoldingOracle(family, new FiniteUnfoldingBounds(1, 8))
                .enumerate(insertion.returnedInvocation()).get(0);
        var artifact = new CertifiedSemanticArtifact(insertion.returnedInvocation(), graph.classes(), family,
                List.of(unfolding), Map.of(), List.of(), List.of(), List.of(), List.of(),
                ConstructionSourceLedger.empty(graphProfile), profile);
        return new CertificateExportSession(sink, graph, artifact, unfolding.normalizedTermKey(), Map.of(),
                provenance, "semantic-profile-wire-v1");
    }

    private static void row(String surface, String fixture, String mutation, String authority, boolean testOnly,
            List<String> f, String digest, String producer, String verifier, String writer,
            String outcome, String detail) {
        ROWS.add(String.join("\t", Integer.toString(ROWS.size() - 1), surface, fixture, mutation, authority,
                Boolean.toString(testOnly), f.get(0), f.get(1), b64(f.get(2)), b64(f.get(3)), b64(f.get(4)),
                digest, b64(producer), b64(verifier), writer, outcome, b64(detail)));
    }

    private static byte[] replaceEvidence(Object root, Object evidence, boolean testOnly) throws Exception {
        var sections = new ArrayList<>(children(root));
        var manifest = sections.get(1);
        var vocabulary = children(manifest).get(1);
        var children = new ArrayList<>(children(vocabulary));
        children.set(3, evidence);
        var changed = node("vocabulary", scalars(vocabulary), children);
        String digest = (String) api(null, cls("Wire"), "contentId", new Class<?>[]{cls("Wire$Node")}, changed);
        sections.set(1, node("manifest", List.of(scalars(manifest).get(0), digest),
                List.of(children(manifest).get(0), changed)));
        if (!testOnly) {
            var metadata = new ArrayList<>(scalars(sections.get(0)));
            // This is a wire-policy control, never a publication provenance claim.
            metadata.set(1, "false");
            metadata.set(6, "PUBLICATION");
            sections.set(0, node("metadata", metadata, List.of()));
        }
        return (byte[]) api(null, cls("Codec"), "encode", new Class<?>[]{cls("Wire$Node")},
                node((String) get(root, "tag"), scalars(root), sections));
    }

    private static void boundary(String fixture, String authority, SemanticProfile profile, Path output,
            CertificateProvenance provenance) throws Exception {
        check(authority.equals("source")
                ? profile.isSourceCommandBound() && profile.isAuthorizedAlloyProfile() && !profile.isFixedCompatibilityProfile()
                : profile.isFixedCompatibilityProfile() && !profile.isSourceCommandBound() && !profile.isAuthorizedAlloyProfile(),
                "observed explicit profile authority");
        Path certificate = output.resolve("profile-" + fixture + ".acgncert");
        session(profile, provenance).write(certificate);
        Object limits = api(null, cls("Limits"), "defaults", new Class<?>[0]);
        Object root = api(null, cls("Codec"), "decode", new Class<?>[]{byte[].class, cls("Limits")},
                Files.readAllBytes(certificate), limits);
        Object bundle = api(null, cls("Bundle"), "parse", new Class<?>[]{cls("Wire$Node")}, root);
        Object evidence = get(bundle, "semanticEvidence");
        List<String> written = scalars(evidence);
        check(written.subList(0, 5).equals(fields(profile)), "actual writer five fields");
        check(written.get(5).equals(profile.fingerprint()), "actual writer digest");
        for (int mutation = 0; mutation < (authority.equals("source") ? 17 : 15); mutation++) {
            List<String> scalars = new ArrayList<>(written);
            String label = "base";
            boolean testOnly = mutation != 14;
            if (mutation >= 1 && mutation <= 10) {
                int field = (mutation - 1) % 5;
                label = (mutation <= 5 ? "stale-" : "rehashed-") + field;
                scalars.set(field, switch (field) {
                    case 0 -> Integer.toString(profile.bitwidth() + 1);
                    case 1 -> profile.overflowMode() == SemanticProfile.OverflowMode.FORBID ? "MODULAR" : "FORBID";
                    default -> scalars.get(field) + ":changed";
                });
                if (mutation > 5) scalars.set(5, sha(independentKey(scalars.subList(0, 5))));
            } else if (mutation == 11) { label = "digest"; scalars.set(5, "0".repeat(64)); }
            else if (mutation == 12) { label = "registry-version"; scalars.set(6, "unknown"); }
            else if (mutation == 13) { label = "registry-digest"; scalars.set(7, "0".repeat(64)); }
            else if (mutation == 14) label = "publication";
            else if (mutation >= 15) {
                label = mutation == 15 ? "version-old" : "version-future";
                String version = mutation == 15 ? "alloy-command-options-v2" : "alloy-command-options-v5";
                String current = AlloySemanticProfileFactory.CONTEXT_VERSION;
                String needle = current.length() + ":" + current;
                String context = scalars.get(2);
                check(context.indexOf(needle) >= 0 && context.indexOf(needle) == context.lastIndexOf(needle),
                        "exact unique source context version coordinate");
                scalars.set(2, context.replace(needle, version.length() + ":" + version));
                scalars.set(5, sha(independentKey(scalars.subList(0, 5))));
            }
            Object changed = node("semantic-evidence", scalars, children(evidence));
            Object policy = api(null, cls("VerificationPolicy"), "trust", new Class<?>[]{String.class}, get(bundle, "theoryDigest"));
            String subject = framedDigest("call-occurrence-commitment-v1/subject",
                    "fixture/semantic-profile-wire", sha("profile-wire"));
            Object commitment = cls("CallOccurrenceCommitment").getConstructor(String.class, String.class)
                    .newInstance(subject, framedDigest("call-occurrence-commitment-v1", subject));
            policy = api(policy, cls("VerificationPolicy"), "withCallOccurrenceCommitment",
                    new Class<?>[]{cls("CallOccurrenceCommitment")}, commitment);
            Object kernel = api(null, cls("Profile"), "valueOf", new Class<?>[]{String.class}, "KERNEL");
            Object publicResult = api(cls("IndependentVerifier").getConstructor().newInstance(), cls("IndependentVerifier"),
                    "verify", new Class<?>[]{byte[].class, cls("Profile"), cls("VerificationPolicy")},
                    replaceEvidence(root, changed, testOnly), kernel, policy);
            String status = get(publicResult, "outcome").toString();
            String publicOutcome = status.equals("VERIFIED") ? "ACCEPT" : get(publicResult, "code").toString();
            check(!status.equals("UNCHECKABLE"), "public verifier completes " + fixture + "/" + label
                    + ": " + publicResult);
            boolean shouldAccept = label.equals("base")
                    || label.equals("rehashed-1") && authority.equals("compatibility");
            check(status.equals(shouldAccept ? "VERIFIED" : "REJECTED"),
                    "exact public acceptance " + fixture + "/" + label + ": " + publicResult);
            if (mutation >= 15) check(publicOutcome.equals("THEORY_MISMATCH") && get(publicResult, "detail")
                    .equals("Source-command semantic context has an unsupported version"), "exact context version rejection");
            List<String> f = scalars.subList(0, 5);
            row("boundary", fixture, label, authority, testOnly, f, scalars.get(5),
                    custom(f).structuralKey().stableString(), independentKey(f), packed(written),
                    publicOutcome, (String) get(publicResult, "detail"));
        }
        SemanticProfile clone = custom(fields(profile));
        check(clone.equals(profile) && clone.fingerprint().equals(profile.fingerprint()), "equal spelling clone");
        check(!clone.isAdmissibleAlloyProfile() && !clone.isAuthorizedAlloyProfile()
                && !clone.isSourceCommandBound() && !clone.isFixedCompatibilityProfile(), "clone has no authority");
        Path rejected = output.resolve("rejected-" + fixture + ".acgncert");
        Files.writeString(rejected, "preserve-existing-output", StandardCharsets.UTF_8);
        try {
            session(clone, profile, provenance, new RecordingCertificateTraceSink()).write(rejected);
            throw new AssertionError("public writer accepted custom spelling clone");
        } catch (IllegalStateException e) {
            check(e.getMessage().equals("Certificate publication requires one parser-owned Alloy source command"),
                    "exact authority rejection");
        }
        check(Files.readString(rejected).equals("preserve-existing-output"), "authority rejection preserves output");
        for (boolean testOnly : List.of(false, true)) {
            try { clone.requireCertificateExportAuthority(testOnly); throw new AssertionError("clone authority"); }
            catch (IllegalStateException e) { check(e.getMessage().contains("parser-owned"), "clone authority stage"); }
        }
        row("clone", fixture, "same-spelling", "custom", true, fields(clone), clone.fingerprint(),
                clone.structuralKey().stableString(), independentKey(fields(clone)), packed(written), "AUTHORITY_REJECT", "");
    }

    private static void execute(Path output) throws Exception {
        for (int width : List.of(0, 4, 30)) for (var overflow : SemanticProfile.OverflowMode.values()) {
            for (int variant = 0; variant < 22; variant++) {
                List<String> f = new ArrayList<>(List.of(Integer.toString(width), overflow.name(), "plain", "plain", "plain"));
                if (variant > 0) f.set(2 + (variant - 1) / 7, TEXTS.get(1 + (variant - 1) % 7));
                SemanticProfile p = custom(f);
                String key = p.structuralKey().stableString(), other = independentKey(f);
                check(key.equals(other), "independent key reconstruction");
                check(sha(key).equals(p.fingerprint()), "real SHA256 computation");
                check(!p.isAdmissibleAlloyProfile() && !p.isSourceCommandBound()
                        && !p.isFixedCompatibilityProfile(), "custom authority");
                row("serialization", width + ":" + overflow + ":" + variant, "base", "custom", false,
                        f, p.fingerprint(), key, other, "-", "UNEXPORTED", "");
            }
        }
        String prior = System.getProperty("acgn.provenance.testOverride");
        System.setProperty("acgn.provenance.testOverride", "true");
        try {
            var provenance = CertificateProvenance.capture("fixture/semantic-profile-wire",
                    "profile-wire".getBytes(StandardCharsets.UTF_8), "semantic-profile-wire-v1");
            for (boolean modular : List.of(false, true)) {
                String mode = modular ? "MODULAR" : "FORBID";
                boundary("fixed:" + mode, "compatibility", modular ? SemanticProfile.alloyModular()
                        : SemanticProfile.alloyOverflowForbidding(), output, provenance);
                for (int width : List.of(3, 4, 6))
                    boundary("source:" + width + ":" + mode, "source", source(width, modular), output, provenance);
            }
        } finally {
            if (prior == null) System.clearProperty("acgn.provenance.testOverride");
            else System.setProperty("acgn.provenance.testOverride", prior);
        }
        check(ROWS.size() == 273, "frozen observation count");
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || Runtime.version().feature() != 17)
            throw new IllegalArgumentException("Usage (JDK17): SemanticProfileWireRegressionTest [OUTPUT.tsv]");
        Path trace = args.length == 0 ? null : Path.of(args[0]).toAbsolutePath();
        if (trace != null) {
            Files.createDirectories(trace.getParent());
            Files.deleteIfExists(trace);
        }
        Path fixtures = Files.createTempDirectory("semantic-profile-wire-");
        try {
            execute(fixtures);
            if (trace != null) Files.write(trace, ROWS, StandardCharsets.UTF_8);
            System.out.println("SemanticProfileWireRegressionTest passed: observations=272 checks=" + checks);
        } finally {
            try (var files = Files.walk(fixtures)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}

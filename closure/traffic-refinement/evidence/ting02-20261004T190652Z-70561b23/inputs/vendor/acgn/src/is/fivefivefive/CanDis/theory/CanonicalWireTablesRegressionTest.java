package is.fivefivefive.CanDis.theory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Finite public-API observations; no private reflection or production-state mutation. */
public final class CanonicalWireTablesRegressionTest {
    private static final String[] SECTIONS = {"contexts", "embeddings", "terms", "proofs",
            "witnesses", "snapshots", "canonical-records", "unfoldings"};
    private static final String[] TAGS = {"context", "embedding", "term", "proof",
            "witness", "snapshot", "canonical-record", "unfolding"};
    private static final int[] POSITIONS = {2, 3, 4, 5, 6, 7, 9, 10};
    private static final String[] MUTATIONS = {"empty", "sorted", "duplicate", "reverse", "separated-duplicate",
            "section-scalars", "record-tag", "missing-id", "empty-id", "stale-scalar", "rehashed-scalar",
            "stale-child", "rehashed-child", "rehashed-tag", "rehashed-child-order"};
    private static final List<String> TEXTS = List.of("", "a", "a:1\u0000b", "\u03b1\u4e2d",
            "\ud83d\ude00", "e\u0301", "\n\t\"\\");
    private static final List<String> ROWS = new ArrayList<>(List.of(
            "case\tsurface\tfixture\tmutation\tinput\toutput\tpreimage\tid\toutcome\texact\torder"));
    private static int checks;

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    // Public API reflection keeps src-only builds independent of the verifier.
    private static Class<?> cls(String name) throws Exception { return Class.forName("org.acgn.cert." + name); }
    private static Object api(Object target, Class<?> owner, String name, Class<?>[] types, Object... args)
            throws Exception {
        try { return owner.getMethod(name, types).invoke(target, args); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }
    private static Object get(Object n, String name) throws Exception {
        return api(n, n.getClass(), name, new Class<?>[0]);
    }
    @SuppressWarnings("unchecked")
    private static List<String> scalars(Object n) throws Exception { return (List<String>) get(n, "scalars"); }
    @SuppressWarnings("unchecked")
    private static List<Object> children(Object n) throws Exception { return (List<Object>) get(n, "children"); }
    private static Object node(String t, List<String> s, List<Object> c) throws Exception {
        return api(null, cls("Wire"), "node", new Class<?>[]{String.class, List.class, List.class}, t, s, c);
    }
    private static Object identified(String t, List<String> s, List<Object> c) throws Exception {
        return api(null, cls("Bundle"), "withContentId", new Class<?>[]{String.class, List.class, List.class}, t, s, c);
    }
    private static byte[] encoded(Object n) throws Exception {
        return (byte[]) api(null, cls("Codec"), "encode", new Class<?>[]{cls("Wire$Node")}, n);
    }
    private static byte[] payload(Object n) throws Exception {
        byte[] bytes = encoded(n);
        return Arrays.copyOfRange(bytes, 18, bytes.length - 32);
    }
    private static Object decode(byte[] bytes) throws Exception {
        Object limits = api(null, cls("Limits"), "defaults", new Class<?>[0]);
        return api(null, cls("Codec"), "decode", new Class<?>[]{byte[].class, cls("Limits")}, bytes, limits);
    }
    private static Object parse(Object n) throws Exception {
        return api(null, cls("Bundle"), "parse", new Class<?>[]{cls("Wire$Node")}, n);
    }
    private static byte[] preimage(Object n) throws Exception {
        return payload(node(get(n, "tag") + "/content", scalars(n).subList(1, scalars(n).size()), children(n)));
    }
    private static byte[] sha(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
    private static String b64(byte[] bytes) { return Base64.getEncoder().encodeToString(bytes); }
    private static String b64(String text) { return b64(text.getBytes(StandardCharsets.UTF_8)); }
    private static String failure(Exception e) throws Exception {
        if (!e.getClass().getName().equals("org.acgn.cert.FormatException")) throw e;
        return get(e, "code").toString();
    }
    private static void row(String surface, String fixture, String mutation, byte[] input, byte[] output,
            byte[] preimage, String id, String outcome, String exact, String order) {
        ROWS.add(String.join("\t", Integer.toString(ROWS.size() - 1), surface, fixture, mutation, b64(input),
                output == null ? "-" : b64(output), preimage == null ? "-" : b64(preimage), id,
                outcome, exact, b64(order)));
    }

    private static byte[] envelope(byte[] payload) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeBytes("ACGNCERT"); out.writeShort(1); out.writeLong(payload.length);
        out.write(payload); out.write(sha(payload)); out.flush();
        return bytes.toByteArray();
    }

    private static CertificateExportSession session(int count) throws Exception {
        var profile = SemanticProfile.alloyOverflowForbidding();
        var sink = new RecordingCertificateTraceSink();
        var graph = new TypedSlottedPortEGraph(profile, sink);
        CertifiedInsertionResult insertion = null;
        for (int i = 0; i < count; i++) {
            var term = TypedENode.construct(OperatorDeclaration.monomorphic("wire-" + i, List.of(),
                    GraphType.BOOL, Map.of(), null).instantiateMonomorphic(), TypedSlotContext.empty(), List.of());
            insertion = graph.insertNode(term, graph.coherentWitnessFamily());
        }
        var family = graph.coherentWitnessFamily();
        var unfolding = graph.finiteUnfoldingOracle(family, new FiniteUnfoldingBounds(1, 8))
                .enumerate(Objects.requireNonNull(insertion).returnedInvocation()).get(0);
        var artifact = new CertifiedSemanticArtifact(insertion.returnedInvocation(), graph.classes(), family,
                List.of(unfolding), Map.of(), List.of(), List.of(), List.of(), List.of(),
                ConstructionSourceLedger.empty(profile), profile);
        var provenance = CertificateProvenance.capture("fixture/canonical-wire-tables",
                "wire-tables".getBytes(StandardCharsets.UTF_8), "canonical-wire-tables-v1");
        return new CertificateExportSession(sink, graph, artifact, unfolding.normalizedTermKey(), Map.of(),
                provenance, "canonical-wire-tables-v1");
    }

    private static Object writer(Path dir, int count) throws Exception {
        Path path = dir.resolve("writer-" + count + ".acgncert");
        session(count).write(path);
        byte[] bytes = Files.readAllBytes(path);
        Object root = decode(bytes), bundle = parse(root);
        check(Arrays.equals(bytes, encoded(root)), "public writer / Codec complete byte roundtrip");
        Object contexts = children(root).get(2);
        check(children(contexts).size() == 1, "one empty context");
        Object context = children(contexts).get(0);
        var snapshots = children(children(root).get(7));
        Object last = snapshots.stream().max(Comparator.comparingLong(n -> {
            try { return Long.parseLong(scalars(n).get(1)); }
            catch (Exception e) { throw new IllegalStateException(e); }
        })).orElseThrow();
        List<String> ids = new ArrayList<>();
        for (Object c : children(children(last).get(0))) ids.add(scalars(c).get(0));
        check(ids.size() == count, "public snapshot class count");
        check(ids.equals(ids.stream().sorted().toList()), "public sortedSection order");
        check(get(bundle, "contexts") instanceof Map<?, ?>, "independent indexedTable executed");
        // Actual producer bytes, not a re-encoding relabeled as producer output:
        byte[] contextPayload = payload(context);
        check(indexOf(bytes, contextPayload) >= 18, "observed context occurs in writer payload");
        int start = indexOf(bytes, contextPayload);
        row("writer", Integer.toString(count), "base", Arrays.copyOfRange(bytes, start, start + contextPayload.length),
                payload(context), preimage(context), scalars(context).get(0), "ACCEPT", "true", String.join("|", ids));
        return root;
    }
    private static int indexOf(byte[] bytes, byte[] needle) {
        outer: for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (bytes[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static Object record(String tag, String text) throws Exception {
        return identified(tag, List.of(text), List.of(node("left", List.of("x"), List.of()),
                node("right", List.of("y"), List.of())));
    }
    private static void tables(Object root) throws Exception {
        for (int table = 0; table < SECTIONS.length; table++) {
            String section = SECTIONS[table], tag = TAGS[table];
            Object a = record(tag, "alpha"), b = record(tag, "beta");
            List<Object> sorted = new ArrayList<>(List.of(a, b));
            sorted.sort(Comparator.comparing(n -> {
                try { return scalars(n).get(0); } catch (Exception e) { throw new IllegalStateException(e); }
            }));
            List<String> mutations = new ArrayList<>(table == 2 || table == 4 ? List.of(MUTATIONS) : List.of("empty", "sorted"));
            if (table == 4) mutations.addAll(List.of("named-order", "named-reverse"));
            for (String mutation : mutations) {
                List<String> sectionScalars = List.of();
                List<Object> records = new ArrayList<>(List.of(a));
                Object changed = a;
                switch (mutation) {
                    case "empty" -> records.clear();
                    case "sorted" -> records = new ArrayList<>(sorted);
                    case "duplicate" -> records = new ArrayList<>(List.of(a, a));
                    case "reverse" -> records = new ArrayList<>(List.of(sorted.get(1), sorted.get(0)));
                    case "separated-duplicate" -> records = new ArrayList<>(List.of(sorted.get(0), sorted.get(1), sorted.get(0)));
                    case "section-scalars" -> sectionScalars = List.of("unexpected");
                    case "record-tag" -> changed = node("wrong", scalars(a), children(a));
                    case "missing-id" -> changed = node(tag, List.of(), children(a));
                    case "empty-id" -> changed = node(tag, List.of("", "alpha"), children(a));
                    case "stale-scalar" -> changed = node(tag, List.of(scalars(a).get(0), "changed"), children(a));
                    case "rehashed-scalar" -> changed = identified(tag, List.of("changed"), children(a));
                    case "stale-child", "rehashed-child" -> {
                        List<Object> cs = List.of(node("left", List.of("changed"), List.of()), children(a).get(1));
                        changed = mutation.startsWith("stale") ? node(tag, scalars(a), cs) : identified(tag, List.of("alpha"), cs);
                    }
                    case "rehashed-tag" -> changed = identified("wrong", List.of("alpha"), children(a));
                    case "rehashed-child-order" -> changed = identified(tag, List.of("alpha"),
                            List.of(children(a).get(1), children(a).get(0)));
                    case "named-order", "named-reverse" -> {
                        records = new ArrayList<>(List.of(node(tag, List.of("\ud800\udc00", "alpha"), List.of()),
                                node(tag, List.of("\ue000", "beta"), List.of())));
                        if (mutation.equals("named-reverse")) Collections.reverse(records);
                    }
                    default -> throw new AssertionError(mutation);
                }
                if (!changed.equals(a)) records = new ArrayList<>(List.of(changed));
                Object candidate = node(section, sectionScalars, records);
                var sections = new ArrayList<>(children(root));
                sections.set(POSITIONS[table], candidate);
                String outcome = "ACCEPT";
                byte[] output = null;
                try {
                    Object decoded = decode(encoded(node((String) get(root, "tag"), scalars(root), sections)));
                    parse(decoded);
                    output = payload(children(decoded).get(POSITIONS[table]));
                } catch (Exception e) { outcome = failure(e); }
                String exact = Boolean.toString(!scalars(changed).isEmpty() &&
                        Arrays.equals(preimage(a), preimage(changed)));
                row("table", section, mutation, payload(candidate), output, null, "-", outcome, exact, "");
            }
        }
    }

    private static void contentVectors() throws Exception {
        for (int i = 0; i < TEXTS.size(); i++) {
            check(Arrays.equals(CertificateBundleWriter.encodeCanonicalUtf8(TEXTS.get(i)),
                    TEXTS.get(i).getBytes(StandardCharsets.UTF_8)), "producer canonical UTF8 scalar");
            Object record = identified("term", List.of(TEXTS.get(i), "tail"),
                    List.of(node("child", List.of("x"), List.of())));
            byte[] preimage = preimage(record);
            String id = (String) api(null, cls("Bundle"), "contentId", new Class<?>[]{cls("Wire$Node")}, record);
            check(id.equals(HexFormat.of().formatHex(sha(preimage))), "independent SHA256 exact preimage");
            check(id.equals(scalars(record).get(0)), "Bundle.withContentId/contentId");
            row("content", Integer.toString(i), "base", payload(record), payload(decode(encoded(record))),
                    preimage, id, "ACCEPT", "true", "");
        }
    }

    private static void grammarVectors() throws Exception {
        byte[] good = payload(node("t", List.of("a"), List.of()));
        var cases = new LinkedHashMap<String, byte[]>();
        cases.put("valid", good);
        cases.put("truncated", Arrays.copyOf(good, good.length - 1));
        cases.put("trailing", Arrays.copyOf(good, good.length + 1));
        for (String label : List.of("negative-tag-length", "negative-scalar-count", "negative-string-length", "negative-child-count")) {
            byte[] changed = good.clone();
            int offset = switch (label) { case "negative-tag-length" -> 0; case "negative-scalar-count" -> 5;
                case "negative-string-length" -> 9; default -> 14; };
            Arrays.fill(changed, offset, offset + 4, (byte) 255);
            cases.put(label, changed);
        }
        for (String hex : List.of("80", "c080", "eda080", "f4908080", "f09f98", "f09f9880")) {
            byte[] text = HexFormat.of().parseHex(hex);
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(1); out.writeByte('t'); out.writeInt(1); out.writeInt(text.length); out.write(text); out.writeInt(0);
            cases.put("utf8-" + hex, bytes.toByteArray());
        }
        for (var entry : cases.entrySet()) {
            String outcome = "ACCEPT"; byte[] output = null;
            try { output = payload(decode(envelope(entry.getValue()))); }
            catch (Exception e) { outcome = failure(e); }
            row("grammar", entry.getKey(), "base", entry.getValue(), output, null, "-", outcome, "-", "");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || Runtime.version().feature() != 17)
            throw new IllegalArgumentException("Usage (JDK17): CanonicalWireTablesRegressionTest [OUTPUT.tsv]");
        Path trace = args.length == 0 ? null : Path.of(args[0]).toAbsolutePath();
        if (trace != null) { Files.createDirectories(trace.getParent()); Files.deleteIfExists(trace); }
        Path dir = Files.createTempDirectory("canonical-wire-tables-");
        String prior = System.getProperty("acgn.provenance.testOverride");
        System.setProperty("acgn.provenance.testOverride", "true");
        try {
            Object root = writer(dir, 1);
            writer(dir, 3);
            tables(root); contentVectors(); grammarVectors();
            check(ROWS.size() == 67, "frozen 66-observation census");
            if (trace != null) Files.write(trace, ROWS, StandardCharsets.UTF_8);
            System.out.println("CanonicalWireTablesRegressionTest passed: observations=66 checks=" + checks);
        } finally {
            if (prior == null) System.clearProperty("acgn.provenance.testOverride");
            else System.setProperty("acgn.provenance.testOverride", prior);
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
}

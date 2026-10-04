package is.fivefivefive.CanDis.theory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Cross-order, worker-count, and fresh-JVM deterministic digest gate. */
public final class TheoryDeterminismTest {
    private static final long SEED = 555_202_608_24L;
    private static final String PROBE_ARGUMENT = "--probe";
    private static int checks;

    private TheoryDeterminismTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && PROBE_ARGUMENT.equals(args[0])) {
            boolean reverse = Integer.parseInt(args[1]) != 0;
            System.out.println(probe(reverse).encoded());
            return;
        }
        testRepeatedAndInsertionOrder();
        testWorkerCounts();
        testFreshJvmReplay();
        System.out.println("TheoryDeterminismTest passed: " + checks
                + " checks; deterministic seed=" + SEED);
    }

    private static void testRepeatedAndInsertionOrder() {
        Probe expected = probe(false);
        for (int repetition = 0; repetition < 12; repetition++) {
            check(expected.equals(probe(false)),
                    "Repeated traces produce identical graph and canonical digests");
        }
        check(expected.equals(probe(true)),
                "Permitted fixed-batch insertion order does not change either digest");
    }

    private static void testWorkerCounts()
            throws InterruptedException, ExecutionException {
        Probe expected = probe(false);
        int logicalWorkers = Math.max(
                1, Math.min(32, Runtime.getRuntime().availableProcessors()));
        for (int workers : new int[]{1, logicalWorkers}) {
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            try {
                List<Callable<Probe>> jobs = new ArrayList<>();
                for (int index = 0; index < 16; index++) {
                    final boolean reverse = (index & 1) != 0;
                    jobs.add(() -> probe(reverse));
                }
                List<Future<Probe>> results = executor.invokeAll(jobs);
                for (Future<Probe> result : results) {
                    check(expected.equals(result.get()),
                            "Independent worker traces reproduce both stable digests");
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void testFreshJvmReplay() throws IOException, InterruptedException {
        Probe expected = probe(false);
        check(expected.equals(runChildProbe(false)),
                "A fresh JVM reproduces the forward trace digests");
        check(expected.equals(runChildProbe(true)),
                "A fresh JVM reproduces the reverse-order trace digests");
    }

    private static Probe runChildProbe(boolean reverse)
            throws IOException, InterruptedException {
        Path java = Paths.get(
                System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                TheoryDeterminismTest.class.getName(),
                PROBE_ARGUMENT,
                reverse ? "1" : "0")
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Determinism child JVM failed with " + exitCode + ": " + output);
        }
        return Probe.parse(output.toString().trim());
    }

    private static Probe probe(boolean reverse) {
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.certifiedFixture();
        FixedRecord first = fixedConstant(10, "determinism/left");
        FixedRecord second = fixedConstant(11, "determinism/right");
        List<FixedRecord> records = reverse
                ? Arrays.asList(second, first)
                : Arrays.asList(first, second);
        for (FixedRecord record : records) {
            graph.admitFixedBatchRecordCertified(
                    record.record,
                    Collections.singletonMap(record.shape, record.equation));
        }

        TypedSlot x = TypedSlot.source(GraphType.BOOL, 7);
        TypedSlot y = TypedSlot.source(GraphType.BOOL, 8);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        BagPortSchema schema = new BagPortSchema(new OnePortSchema(GraphType.BOOL));
        Map<PortPath, ContainerLawDeclaration> laws = new LinkedHashMap<>();
        laws.put(PortPath.at(0), ContainerLawDeclaration.certified(
                schema,
                Arrays.asList(
                        ContainerLawCertificate.testFixture(
                                schema,
                                ContainerLawCertificate.Law.ASSOCIATIVITY,
                                CertificateOrigin.containerLaw(
                                        "determinism", "bag:A", 0)),
                        ContainerLawCertificate.testFixture(
                                schema,
                                ContainerLawCertificate.Law.COMMUTATIVITY,
                                CertificateOrigin.containerLaw(
                                        "determinism", "bag:C", 1)))));
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "determinism/bag",
                Collections.singletonList(schema),
                GraphType.BOOL,
                laws,
                null).instantiateMonomorphic();
        List<PortValue> occurrences = reverse
                ? Arrays.asList(OnePort.slot(context, y), OnePort.slot(context, x))
                : Arrays.asList(OnePort.slot(context, x), OnePort.slot(context, y));
        TypedENode node = TypedENode.construct(
                operator,
                context,
                Collections.singletonList(new BagPort(schema, context, occurrences)));
        CanonicalizationResult canonical = graph.canonicalize(node);
        graph.checkInvariants();
        return new Probe(
                sha256(graph.stateStructuralKey().stableString()),
                sha256(canonical.shape().structuralKey().stableString()));
    }

    private static FixedRecord fixedConstant(long id, String name) {
        TypedSlotContext empty = TypedSlotContext.empty();
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                Collections.emptyList(),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        CanonicalShape shape = CanonicalShape.of(TypedENode.construct(
                operator, empty, Collections.emptyList()));
        ShapeWitness witness = new ShapeWitness(
                empty, empty, empty, TypedRenaming.identity(empty));
        TypedEClassInterface eclass = new TypedEClassInterface(
                EClassId.of(id), GraphType.BOOL, empty);
        TypedEClassRecord record = TypedEClassRecord.of(
                eclass,
                Collections.singletonMap(shape, witness),
                TypedSymmetryGroup.identity(empty));
        InputEquationCertificate equation = new InputEquationCertificate(
                CertificateOrigin.inputEquation(
                        "phase-h-determinism", name, 0),
                TypedCertificateEndpoint.node(shape.node()),
                TypedCertificateEndpoint.eclassWitness(eclass));
        return new FixedRecord(record, shape, equation);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                result.append(String.format("%02x", current & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 digest is unavailable", exception);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FixedRecord {
        private final TypedEClassRecord record;
        private final CanonicalShape shape;
        private final InputEquationCertificate equation;

        private FixedRecord(
                TypedEClassRecord record,
                CanonicalShape shape,
                InputEquationCertificate equation) {
            this.record = record;
            this.shape = shape;
            this.equation = equation;
        }
    }

    private static final class Probe {
        private final String graphDigest;
        private final String canonicalDigest;

        private Probe(String graphDigest, String canonicalDigest) {
            this.graphDigest = graphDigest;
            this.canonicalDigest = canonicalDigest;
        }

        private String encoded() {
            return graphDigest + ":" + canonicalDigest;
        }

        private static Probe parse(String encoded) {
            String[] parts = encoded.split(":", -1);
            if (parts.length != 2 || parts[0].length() != 64 || parts[1].length() != 64) {
                throw new IllegalArgumentException(
                        "Malformed determinism probe output: " + encoded);
            }
            return new Probe(parts[0], parts[1]);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Probe
                    && graphDigest.equals(((Probe) other).graphDigest)
                    && canonicalDigest.equals(((Probe) other).canonicalDigest);
        }

        @Override
        public int hashCode() {
            return 31 * graphDigest.hashCode() + canonicalDigest.hashCode();
        }
    }
}

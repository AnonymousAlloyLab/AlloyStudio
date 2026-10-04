package is.fivefivefive.CanDis.augmentation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;

/** Deterministic persisted audit ledger for adaptive equality evidence. */
public final class EquivalenceAugmentationLedger {
    public static final String SCHEMA_VERSION = "equivalence-augmentation-ledger-v1";

    private EquivalenceAugmentationLedger() {
    }

    public static void write(
            Path output,
            BootstrapTheoryR0 bootstrap,
            long generation,
            List<EquivalenceAugmenter.LocalRecordView> locals,
            List<EquivalenceAugmenter.SchemaRecordView> schemas,
            List<EquivalenceAugmenter.ApplicationRecordView> applications)
            throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bootstrap, "bootstrap");
        Objects.requireNonNull(locals, "locals");
        Objects.requireNonNull(schemas, "schemas");
        Objects.requireNonNull(applications, "applications");
        if (generation < 0) {
            throw new IllegalArgumentException("Theory generation must be nonnegative");
        }

        JSONObject root = new JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("augmenterVersion", EquivalenceAugmenter.VERSION)
                .put("bootstrap", new JSONObject()
                        .put("id", BootstrapTheoryR0.VERSION)
                        .put("digest", bootstrap.digest())
                        .put("components", new JSONArray(bootstrap.components())))
                .put("theoryGeneration", generation);
        JSONArray localArray = new JSONArray();
        locals.stream().sorted(java.util.Comparator.comparing(
                EquivalenceAugmenter.LocalRecordView::id))
                .forEach(record -> localArray.put(localJson(record)));
        JSONArray schemaArray = new JSONArray();
        schemas.stream().sorted(java.util.Comparator.comparing(
                EquivalenceAugmenter.SchemaRecordView::id))
                .forEach(record -> schemaArray.put(schemaJson(record)));
        root.put("instanceLocalEqualities", localArray)
                .put("generalizedSchemas", schemaArray);
        JSONArray applicationArray = new JSONArray();
        applications.stream().sorted(java.util.Comparator.comparing(
                EquivalenceAugmenter.ApplicationRecordView::certificateDigest))
                .forEach(record -> applicationArray.put(applicationJson(record)));
        root.put("schemaApplications", applicationArray);

        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(
                parent == null ? Path.of(".") : parent,
                absolute.getFileName().toString(), ".tmp");
        try {
            Files.writeString(
                    temporary,
                    canonicalJson(root, 0) + "\n",
                    StandardCharsets.UTF_8);
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static JSONObject localJson(
            EquivalenceAugmenter.LocalRecordView record) {
        JSONArray positive = new JSONArray().put(record.sourceCorrespondenceDigest());
        JSONArray negative = new JSONArray();
        if (!record.alloyEvidenceDigest().isBlank()) {
            if (record.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL) {
                positive.put(record.alloyEvidenceDigest());
            } else {
                negative.put(new JSONObject()
                        .put("outcome", record.alloyOutcome())
                        .put("digest", record.alloyEvidenceDigest())
                        .put("detail", record.alloyDetail()));
            }
        }
        return new JSONObject()
                .put("id", record.id())
                .put("state", record.state().name())
                .put("admissionScope", record.scope().name())
                .put("endpointPair", record.endpointPair())
                .put("affectedRegion", record.region())
                .put("originWitnesses", new JSONArray()
                        .put(new JSONObject()
                                .put("side", "left")
                                .put("inputIdentifier", record.leftInputIdentifier())
                                .put("inputSha256", record.leftInputSha256()))
                        .put(new JSONObject()
                                .put("side", "right")
                                .put("inputIdentifier", record.rightInputIdentifier())
                                .put("inputSha256", record.rightInputSha256())))
                .put("observedPositiveDistance", record.observedDistance())
                .put("alloyOutcome", record.alloyOutcome())
                .put("alloyDetail", record.alloyDetail())
                .put("positiveEvidence", positive)
                .put("sourceCorrespondence", new JSONObject()
                        .put("digest", record.sourceCorrespondenceDigest())
                        .put("detail", record.sourceCorrespondenceDetail()))
                .put("semanticContextDigest", record.semanticContextDigest())
                .put("negativeEvidence", negative)
                .put("dependencies", new JSONArray())
                .put("transitions", enumArray(record.transitions()))
                .put("theoryGeneration", record.theoryGeneration());
    }

    private static JSONObject schemaJson(
            EquivalenceAugmenter.SchemaRecordView record) {
        JSONObject negative = new JSONObject();
        record.negativeEvidence().forEach((guard, evidence) ->
                negative.put(guard.name(), new JSONObject()
                        .put("kind", evidence.kind())
                        .put("outcome", evidence.outcome())
                        .put("digest", evidence.digest())
                        .put("detail", evidence.detail())));
        JSONObject guards = new JSONObject();
        record.guards().forEach((guard, value) ->
                guards.put(guard.name(), value));
        return new JSONObject()
                .put("id", record.id())
                .put("state", record.state().name())
                .put("admissionScope", record.scope().name())
                .put("affectedRegion", record.region())
                .put("generalizedSchema", new JSONObject()
                        .put("digest", record.schemaDigest())
                        .put("left", record.leftPattern())
                        .put("right", record.rightPattern())
                        .put("semanticSchemaDigest", record.semanticSchemaDigest()))
                .put("leanObligation", new JSONObject()
                        .put("parameters", record.leanTheoremParameters())
                        .put("statement", record.leanTheoremStatement()))
                .put("guardsDigest", record.guardsDigest())
                .put("guards", guards)
                .put("originWitnesses", new JSONArray(record.originWitnesses()))
                .put("positiveEvidence", new JSONArray(record.positiveEvidence()))
                .put("negativeEvidence", negative)
                .put("leanProof", new JSONObject()
                        .put("source", record.leanProofReference())
                        .put("digest", record.leanProofDigest())
                        .put("executable", record.leanExecutablePath())
                        .put("toolchainSha256", record.leanToolchainSha256())
                        .put("version", record.leanVersion()))
                .put("dependencies", new JSONArray(record.dependencies()))
                .put("orientation", record.orientation().name())
                .put("transitions", enumArray(record.transitions()))
                .put("theoryGeneration", record.theoryGeneration())
                .put("affectedPairCount", record.affectedPairs());
    }

    private static JSONObject applicationJson(
            EquivalenceAugmenter.ApplicationRecordView record) {
        return new JSONObject()
                .put("certificateDigest", record.certificateDigest())
                .put("endpointPair", record.endpointPair())
                .put("schemaId", record.schemaId())
                .put("sourceCorrespondence", new JSONObject()
                        .put("digest", record.sourceCorrespondenceDigest())
                        .put("detail", record.sourceCorrespondenceDetail()))
                .put("semanticContextDigest", record.semanticContextDigest())
                .put("theoryGeneration", record.theoryGeneration());
    }

    private static JSONArray enumArray(
            List<? extends Enum<?>> values) {
        JSONArray result = new JSONArray();
        for (Enum<?> value : values) {
            result.put(value.name());
        }
        return result;
    }

    private static String canonicalJson(Object value, int depth) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>(object.keySet());
            Collections.sort(keys);
            if (keys.isEmpty()) {
                return "{}";
            }
            StringBuilder output = new StringBuilder("{\n");
            for (int index = 0; index < keys.size(); index++) {
                String key = keys.get(index);
                indent(output, depth + 1);
                output.append(JSONObject.quote(key)).append(": ")
                        .append(canonicalJson(object.get(key), depth + 1));
                output.append(index + 1 == keys.size() ? '\n' : ",\n");
            }
            indent(output, depth);
            return output.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() == 0) {
                return "[]";
            }
            StringBuilder output = new StringBuilder("[\n");
            for (int index = 0; index < array.length(); index++) {
                indent(output, depth + 1);
                output.append(canonicalJson(array.get(index), depth + 1));
                output.append(index + 1 == array.length() ? '\n' : ",\n");
            }
            indent(output, depth);
            return output.append(']').toString();
        }
        if (value instanceof String) {
            return JSONObject.quote((String) value);
        }
        if (value instanceof Number) {
            return JSONObject.numberToString((Number) value);
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        throw new IllegalArgumentException(
                "Unsupported canonical JSON value " + value.getClass().getName());
    }

    private static void indent(StringBuilder output, int depth) {
        output.append("  ".repeat(depth));
    }
}

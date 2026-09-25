package is.fivefivefive.CanDis.theory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Proof-heavy construction retained only until a deterministic bundle is written. */
public final class CertificateExportSession {
    private final List<CertificateTraceEvent> events;
    private final CertificateTraceSnapshot finalSnapshot;
    private final CertifiedSemanticArtifact artifact;
    private final StructuralKey canonicalObservation;
    private final Map<String, List<ContainerLawDeclaration>> containerLaws;
    private final CertificateProvenance provenance;
    private final String componentVersions;

    public CertificateExportSession(
            RecordingCertificateTraceSink sink,
            TypedSlottedPortEGraph graph,
            CertifiedSemanticArtifact artifact,
            StructuralKey canonicalObservation,
            Map<String, ? extends List<ContainerLawDeclaration>> containerLaws,
            CertificateProvenance provenance,
            String componentVersions) {
        this.events = Objects.requireNonNull(sink, "sink").events();
        this.finalSnapshot = Objects.requireNonNull(
                graph, "graph").certificateTraceSnapshot();
        this.artifact = Objects.requireNonNull(artifact, "artifact");
        this.canonicalObservation = Objects.requireNonNull(
                canonicalObservation, "canonicalObservation");
        java.util.LinkedHashMap<String, List<ContainerLawDeclaration>> copies =
                new java.util.LinkedHashMap<>();
        for (Map.Entry<String, ? extends List<ContainerLawDeclaration>> entry
                : containerLaws.entrySet()) {
            copies.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        this.containerLaws = java.util.Collections.unmodifiableMap(copies);
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.componentVersions = Objects.requireNonNull(
                componentVersions, "componentVersions");
        if (events.isEmpty()) {
            throw new IllegalStateException(
                    "An exact preparation must retain at least one insertion event");
        }
        if (!events.get(events.size() - 1).after().stateKey().equals(
                finalSnapshot.stateKey())) {
            throw new IllegalStateException(
                    "Final artifact state is not the final retained trace state");
        }
    }

    public List<CertificateTraceEvent> events() {
        return events;
    }

    public CertificateTraceSnapshot finalSnapshot() {
        return finalSnapshot;
    }

    public CertifiedSemanticArtifact artifact() {
        return artifact;
    }

    public StructuralKey canonicalObservation() {
        return canonicalObservation;
    }

    public Map<String, List<ContainerLawDeclaration>> containerLaws() {
        return containerLaws;
    }

    public CertificateProvenance provenance() {
        return provenance;
    }

    public String componentVersions() {
        return componentVersions;
    }

    /** Writes before compact comparison discards the semantic artifact. */
    public CertificateWriteMetrics write(Path output) throws IOException {
        return CertificateBundleWriter.write(this, output);
    }
}

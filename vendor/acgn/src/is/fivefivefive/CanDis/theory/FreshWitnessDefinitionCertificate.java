package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** Definitional extension choosing a fresh class witness to be one effective kernel. */
public final class FreshWitnessDefinitionCertificate extends TypedEqualityCertificate {
    private final TypedENode kernel;
    private final TypedEClassInterface freshClass;
    private final KernelReplayCertificate sourceReplay;

    private FreshWitnessDefinitionCertificate(
            TypedENode kernel,
            TypedEClassInterface freshClass,
            KernelReplayCertificate sourceReplay) {
        super(
                CertificateCategory.WITNESS_DEFINITION,
                TypedCertificateEndpoint.node(kernel),
                TypedCertificateEndpoint.eclassWitness(freshClass),
                Collections.singletonList(sourceReplay),
                java.util.Arrays.asList(
                        kernel.structuralKey(),
                        TheoryKeys.eclass(freshClass),
                        sourceReplay.witnessFamily().structuralKey()));
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.freshClass = Objects.requireNonNull(freshClass, "freshClass");
        this.sourceReplay = Objects.requireNonNull(sourceReplay, "sourceReplay");
        verifyLocal();
    }

    static FreshWitnessDefinitionCertificate create(
            TypedSlottedPortEGraph graph,
            TypedENode kernel,
            TypedEClassInterface freshClass,
            KernelReplayCertificate sourceReplay) {
        Objects.requireNonNull(graph, "graph").requireFreshWitnessDefinition(
                Objects.requireNonNull(freshClass, "freshClass"),
                Objects.requireNonNull(sourceReplay, "sourceReplay"));
        FreshWitnessDefinitionCertificate result =
                new FreshWitnessDefinitionCertificate(
                        Objects.requireNonNull(kernel, "kernel"),
                        freshClass,
                        sourceReplay);
        CertificateVerifier.verify(result);
        return result;
    }

    public TypedENode kernel() {
        return kernel;
    }

    public TypedEClassInterface freshClass() {
        return freshClass;
    }

    public KernelReplayCertificate sourceReplay() {
        return sourceReplay;
    }

    @Override
    void verifyLocal() {
        if (!kernel.context().equals(freshClass.exposedSlots())
                || !kernel.outputType().equals(freshClass.outputType())
                || !sourceReplay.leaderKernel().kernel().equals(kernel)
                || !leftEndpoint().equals(TypedCertificateEndpoint.node(kernel))
                || !rightEndpoint().equals(
                        TypedCertificateEndpoint.eclassWitness(freshClass))) {
            throw new IllegalStateException(
                    "Malformed fresh coherent-witness definition");
        }
    }
}

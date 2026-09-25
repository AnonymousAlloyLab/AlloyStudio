package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.CallMetadata;

/** Provenance-only binding from one parser CALL occurrence to its typed source term. */
public final class CallOccurrenceCertificate {
    private final long occurrenceId;
    private final String sourcePath;
    private final String sourceName;
    private final String qualifiedCallee;
    private final String kind;
    private final int declaredArity;
    private final CallSymbol.ArityAuthority arityAuthority;
    private final TypedENode sourceEndpoint;
    private final List<OnePort> orderedArguments;
    private final StructuralKey structuralKey;

    private CallOccurrenceCertificate(
            CallMetadata.Validated metadata,
            String sourcePath,
            TypedENode sourceEndpoint,
            List<? extends OnePort> orderedArguments) {
        Objects.requireNonNull(metadata, "metadata");
        this.occurrenceId = metadata.occurrenceId();
        this.sourcePath = requireText(sourcePath, "source path");
        this.sourceName = requireText(metadata.sourceName(), "source spelling");
        this.qualifiedCallee = requireText(
                metadata.identity(), "qualified callee identity");
        this.kind = requireText(metadata.kind(), "call kind");
        this.declaredArity = metadata.arity();
        this.arityAuthority = Objects.requireNonNull(
                metadata.authority(), "arityAuthority");
        this.sourceEndpoint = Objects.requireNonNull(sourceEndpoint, "sourceEndpoint");
        Objects.requireNonNull(orderedArguments, "orderedArguments");
        List<OnePort> copied = new ArrayList<>(orderedArguments.size());
        for (OnePort argument : orderedArguments) {
            copied.add(Objects.requireNonNull(argument, "ordered argument"));
        }
        this.orderedArguments = Collections.unmodifiableList(copied);
        requireExactBinding();
        this.structuralKey = buildStructuralKey();
    }

    public static CallOccurrenceCertificate create(
            CallMetadata.Validated metadata,
            String sourcePath,
            TypedENode sourceEndpoint,
            List<? extends OnePort> orderedArguments) {
        return new CallOccurrenceCertificate(
                metadata, sourcePath, sourceEndpoint, orderedArguments);
    }

    private void requireExactBinding() {
        if (occurrenceId < 0L || declaredArity < 0
                || orderedArguments.size() != declaredArity
                || sourceEndpoint.ports().size() != declaredArity) {
            throw new IllegalArgumentException(
                    "CALL occurrence evidence has inconsistent arity");
        }
        String expectedOperator = "ALLOY/CALL/" + qualifiedCallee + "/"
                + declaredArity + "/" + kind + "/" + arityAuthority.name();
        if (!expectedOperator.equals(sourceEndpoint.operator().operator())) {
            throw new IllegalArgumentException(
                    "CALL occurrence evidence names another typed operator");
        }
        for (int index = 0; index < orderedArguments.size(); index++) {
            if (!orderedArguments.get(index).equals(sourceEndpoint.ports().get(index))) {
                throw new IllegalArgumentException(
                        "CALL occurrence argument endpoint differs at role " + index);
            }
        }
    }

    private StructuralKey buildStructuralKey() {
        List<StructuralKey> arguments = new ArrayList<>(orderedArguments.size());
        for (int role = 0; role < orderedArguments.size(); role++) {
            arguments.add(StructuralKey.of(
                    "alloy-call-argument-occurrence-v1",
                    List.of(Integer.toString(role)),
                    List.of(orderedArguments.get(role).structuralKey())));
        }
        return StructuralKey.of(
                "alloy-call-source-occurrence-v1",
                List.of(
                        Long.toString(occurrenceId),
                        sourcePath,
                        sourceName,
                        qualifiedCallee,
                        kind,
                        Integer.toString(declaredArity),
                        arityAuthority.name()),
                List.of(
                        sourceEndpoint.structuralKey(),
                        StructuralKey.branch(
                                "alloy-call-ordered-arguments-v1", arguments)));
    }

    private static String requireText(String value, String label) {
        if (!ExactAlloyType.isAdmittedIdentity(value)) {
            throw new IllegalArgumentException(
                    "CALL " + label + " must be a well-formed visible identity");
        }
        return value;
    }

    public long occurrenceId() {
        return occurrenceId;
    }

    public String sourcePath() {
        return sourcePath;
    }

    public String sourceName() {
        return sourceName;
    }

    public String qualifiedCallee() {
        return qualifiedCallee;
    }

    public String kind() {
        return kind;
    }

    public int declaredArity() {
        return declaredArity;
    }

    public CallSymbol.ArityAuthority arityAuthority() {
        return arityAuthority;
    }

    public TypedENode sourceEndpoint() {
        return sourceEndpoint;
    }

    public List<OnePort> orderedArguments() {
        return orderedArguments;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    /**
     * True only when trusted normalization duplicated one parser occurrence
     * without changing its callee, typed endpoint, or ordered arguments.  The
     * deterministic tree path is intentionally excluded because each clone has
     * a different normalized path while retaining one parser occurrence id.
     */
    boolean sameParserOccurrencePayloadAs(
            CallOccurrenceCertificate other) {
        return other != null
                && occurrenceId == other.occurrenceId
                && declaredArity == other.declaredArity
                && sourceName.equals(other.sourceName)
                && qualifiedCallee.equals(other.qualifiedCallee)
                && kind.equals(other.kind)
                && arityAuthority == other.arityAuthority
                && sourceEndpoint.equals(other.sourceEndpoint)
                && orderedArguments.equals(other.orderedArguments);
    }
}

package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Concrete use of one certified descriptor automorphism at one binder occurrence. */
public final class BinderOccurrenceAutomorphismCertificate
        extends TypedEqualityCertificate {
    private final TypedENode enclosingRoot;
    private final BindBlockPort source;
    private final BindBlockPort target;
    private final List<Integer> sourcePath;
    private final TypedPermutation automorphism;
    private final TypedRenaming occurrencePermutation;

    private BinderOccurrenceAutomorphismCertificate(Build build) {
        super(
                CertificateCategory.BINDER_AUTOMORPHISM,
                TypedCertificateEndpoint.port(build.source),
                TypedCertificateEndpoint.port(build.target),
                Collections.singletonList(build.derivation),
                Arrays.asList(
                        build.enclosingRoot.structuralKey(),
                        build.source.schema().descriptor().structuralKey(),
                        TheoryKeys.context(build.source.context()),
                        TheoryKeys.embedding(build.source.descriptorToOccurrence()),
                        TheoryKeys.embedding(build.automorphism),
                        TheoryKeys.embedding(build.occurrencePermutation),
                        pathKey(build.sourcePath),
                        build.source.structuralKey(),
                        build.target.structuralKey()));
        this.enclosingRoot = build.enclosingRoot;
        this.source = build.source;
        this.target = build.target;
        this.sourcePath = build.sourcePath;
        this.automorphism = build.automorphism;
        this.occurrencePermutation = build.occurrencePermutation;
        verifyLocal();
    }

    static BinderOccurrenceAutomorphismCertificate create(
            TypedENode enclosingRoot,
            BindBlockPort source,
            List<Integer> sourcePath,
            TypedPermutation automorphism) {
        BinderOccurrenceAutomorphismCertificate certificate =
                new BinderOccurrenceAutomorphismCertificate(
                        build(enclosingRoot, source, sourcePath, automorphism));
        CertificateVerifier.verify(certificate);
        return certificate;
    }

    private static Build build(
            TypedENode enclosingRoot,
            BindBlockPort source,
            List<Integer> sourcePath,
            TypedPermutation automorphism) {
        TypedENode checkedRoot = Objects.requireNonNull(
                enclosingRoot, "enclosingRoot");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourcePath, "sourcePath");
        List<Integer> checkedPath = new ArrayList<>(sourcePath.size());
        for (Integer coordinate : sourcePath) {
            if (coordinate == null || coordinate < 0) {
                throw new IllegalArgumentException(
                        "A binder occurrence path must contain nonnegative coordinates");
            }
            checkedPath.add(coordinate);
        }
        if (checkedPath.isEmpty()) {
            throw new IllegalArgumentException(
                    "A binder occurrence path must identify at least one node port");
        }
        checkedPath = Collections.unmodifiableList(checkedPath);
        if (!source.equals(portAt(checkedRoot, checkedPath))) {
            throw new IllegalArgumentException(
                    "A binder occurrence path must locate its source in the enclosing root");
        }

        BinderBlockDescriptor descriptor = source.schema().descriptor();
        TypedPermutation checkedAutomorphism = Objects.requireNonNull(
                automorphism, "automorphism");
        descriptor.automorphisms().requireCertifiedFor(descriptor);
        if (checkedAutomorphism.equals(
                    TypedPermutation.identity(descriptor.boundContext()))) {
            throw new IllegalArgumentException(
                    "Fresh-name alpha allocation is not a descriptor automorphism step");
        }
        TypedEqualityCertificate derivation = descriptor.automorphisms()
                .derivationFor(descriptor, checkedAutomorphism);
        TypedRenaming occurrencePermutation = source.descriptorToOccurrence()
                .inverse()
                .andThen(checkedAutomorphism)
                .andThen(source.descriptorToOccurrence());
        TypedRenaming bodyRenaming = TypedRenaming.identity(source.context())
                .disjointUnion(occurrencePermutation)
                .asRenaming();
        BindBlockPort target = new BindBlockPort(
                source.schema(),
                source.context(),
                source.descriptorToOccurrence(),
                source.body().act(bodyRenaming));
        return new Build(
                checkedRoot,
                source,
                target,
                checkedPath,
                checkedAutomorphism,
                occurrencePermutation,
                derivation);
    }

    private static PortValue portAt(TypedENode root, List<Integer> path) {
        int rootPort = path.get(0);
        if (rootPort >= root.ports().size()) {
            throw new IllegalArgumentException(
                    "A binder occurrence root coordinate is outside the enclosing node");
        }
        PortValue current = root.ports().get(rootPort);
        for (int depth = 1; depth < path.size(); depth++) {
            int coordinate = path.get(depth);
            if (current instanceof BindBlockPort) {
                if (coordinate != 0) {
                    throw new IllegalArgumentException(
                            "A binder block body has only coordinate zero");
                }
                current = ((BindBlockPort) current).body();
            } else if (current instanceof BindPort) {
                if (coordinate != 0) {
                    throw new IllegalArgumentException(
                            "A binder body has only coordinate zero");
                }
                current = ((BindPort) current).body();
            } else if (current instanceof SeqPort) {
                current = elementAt(
                        ((SeqPort) current).elements(), coordinate, "sequence");
            } else if (current instanceof BagPort) {
                current = elementAt(
                        ((BagPort) current).occurrences(), coordinate, "bag");
            } else if (current instanceof SetPort) {
                current = elementAt(
                        ((SetPort) current).elements(), coordinate, "set");
            } else {
                throw new IllegalArgumentException(
                        "A binder occurrence path descends through a leaf port");
            }
        }
        return current;
    }

    private static PortValue elementAt(
            List<? extends PortValue> elements,
            int coordinate,
            String owner) {
        if (coordinate >= elements.size()) {
            throw new IllegalArgumentException(
                    "A binder occurrence coordinate is outside its " + owner);
        }
        return elements.get(coordinate);
    }

    private static StructuralKey pathKey(List<Integer> path) {
        List<String> coordinates = new ArrayList<>(path.size());
        for (Integer coordinate : path) {
            coordinates.add(Integer.toString(coordinate));
        }
        return StructuralKey.of(
                "binder-occurrence-source-path-v1",
                coordinates,
                Collections.emptyList());
    }

    public TypedENode enclosingRoot() { return enclosingRoot; }
    public BindBlockPort source() { return source; }
    public BindBlockPort target() { return target; }
    public List<Integer> sourcePath() { return sourcePath; }
    public TypedPermutation automorphism() { return automorphism; }
    public TypedRenaming occurrencePermutation() { return occurrencePermutation; }

    public boolean appliesTo(
            TypedENode candidateRoot,
            BindBlockPort candidate,
            List<Integer> candidatePath,
            TypedPermutation candidateAutomorphism) {
        return enclosingRoot.equals(candidateRoot)
                && source.equals(candidate)
                && sourcePath.equals(candidatePath)
                && automorphism.equals(candidateAutomorphism);
    }

    @Override
    void verifyLocal() {
        Build rebuilt = build(enclosingRoot, source, sourcePath, automorphism);
        if (!target.equals(rebuilt.target)
                || !occurrencePermutation.equals(rebuilt.occurrencePermutation)
                || !leftEndpoint().equals(TypedCertificateEndpoint.port(source))
                || !rightEndpoint().equals(TypedCertificateEndpoint.port(target))) {
            throw new IllegalStateException(
                    "Malformed binder-occurrence automorphism certificate");
        }
    }

    private static final class Build {
        private final TypedENode enclosingRoot;
        private final BindBlockPort source;
        private final BindBlockPort target;
        private final List<Integer> sourcePath;
        private final TypedPermutation automorphism;
        private final TypedRenaming occurrencePermutation;
        private final TypedEqualityCertificate derivation;

        private Build(
                TypedENode enclosingRoot,
                BindBlockPort source,
                BindBlockPort target,
                List<Integer> sourcePath,
                TypedPermutation automorphism,
                TypedRenaming occurrencePermutation,
                TypedEqualityCertificate derivation) {
            this.enclosingRoot = enclosingRoot;
            this.source = source;
            this.target = target;
            this.sourcePath = sourcePath;
            this.automorphism = automorphism;
            this.occurrencePermutation = occurrencePermutation;
            this.derivation = derivation;
        }
    }
}

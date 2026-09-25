package is.fivefivefive.ACGN.alloy;

import java.util.Collections;
import java.util.Collection;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.parser.CompModule;

/*
 * A signature in the Alloy model as a set. 
 * One of the symbols in the AAME.
 */
public class SigSymbol extends SetSymbol {
    public static final String BUILTIN_NONE_IDENTITY = "alloy/builtin/none";
    public static final String BUILTIN_UNIV_IDENTITY = "alloy/builtin/univ";
    public static final String BUILTIN_INT_IDENTITY = "alloy/builtin/Int";
    public static final String BUILTIN_SEQUENCE_INDEX_IDENTITY =
            "alloy/builtin/seq/Int";

    public enum Kind {
        USER,
        BUILTIN_NONE,
        BUILTIN_UNIV,
        BUILTIN_INT,
        BUILTIN_SEQUENCE_INDEX
    }

    private final String name;
    private final Kind kind;
    private final Sig parserSignature;
    private final CompModule parserModuleAuthority;

    public SigSymbol(String n) {
        this(ExactAlloyType.normalizeColumn(n), Kind.USER, null, null);
    }

    private SigSymbol(String name, Kind kind) {
        this(name, kind, null, null);
    }

    private SigSymbol(
            String name,
            Kind kind,
            Sig parserSignature,
            CompModule parserModuleAuthority) {
        this.name = ExactAlloyType.requireAdmittedIdentity(
                name, "signature name");
        this.kind = java.util.Objects.requireNonNull(kind, "signature kind");
        this.parserSignature = parserSignature;
        this.parserModuleAuthority = parserModuleAuthority;
        if ((parserSignature == null) != (parserModuleAuthority == null)) {
            throw new IllegalArgumentException(
                    "Parser signature and module authority must be supplied together");
        }
        if (parserSignature != null) {
            String parserName = ExactAlloyType.normalizeColumn(parserSignature.label);
            if (kind != Kind.USER || !name.equals(parserName)
                    || parserModuleAuthority.getAllReachableSigs().stream()
                            .noneMatch(candidate -> candidate == parserSignature)) {
                throw new IllegalArgumentException(
                        "Parser signature evidence must name a reachable user signature");
            }
        }
    }

    /** Creates a user-signature symbol carrying live parser declaration authority. */
    public static SigSymbol fromParser(
            Sig signature,
            CompModule sourceModule) {
        Sig checked = java.util.Objects.requireNonNull(signature, "signature");
        CompModule module = java.util.Objects.requireNonNull(
                sourceModule, "sourceModule");
        return new SigSymbol(
                ExactAlloyType.normalizeColumn(checked.label),
                Kind.USER,
                checked,
                module);
    }

    public static SigSymbol builtinNone() {
        return new SigSymbol("none", Kind.BUILTIN_NONE);
    }

    public static SigSymbol builtinUniv() {
        return new SigSymbol("univ", Kind.BUILTIN_UNIV);
    }

    public static SigSymbol builtinInt() {
        return new SigSymbol("Int", Kind.BUILTIN_INT);
    }

    public static SigSymbol builtinSequenceIndex() {
        return new SigSymbol("seq/Int", Kind.BUILTIN_SEQUENCE_INDEX);
    }

    public Kind getKind() {
        return kind;
    }

    public boolean hasParserSignatureAuthority() {
        return parserSignature != null;
    }

    /** True only for a live parser declaration marked {@code var}. */
    public boolean isParserVariableSignature() {
        return parserSignature != null && parserSignature.isVariable != null;
    }

    public boolean authenticatesExactType(ExactAlloyType exactType) {
        return parserModuleAuthority != null
                && exactType != null
                && exactType.isParserAuthenticatedBy(parserModuleAuthority);
    }

    public boolean isParserCertifiedSubsignatureOf(SigSymbol carrier) {
        return carrier != null
                && parserSignature != null
                && carrier.parserSignature != null
                && parserModuleAuthority == carrier.parserModuleAuthority
                && declarationGuaranteesSubset(
                        parserSignature,
                        carrier.parserSignature,
                        Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /**
     * Returns the highest parser-certified abstract signature exactly covered
     * by the supplied full-signature members. Alloy's {@code abstract}
     * constraint is generated only from direct {@code extends} children;
     * subset signatures declared with {@code in} therefore never discharge a
     * cover obligation.
     */
    public static SigSymbol parserCertifiedAbstractCover(
            Collection<SigSymbol> members) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        CompModule module = null;
        Set<Sig> represented = Collections.newSetFromMap(
                new IdentityHashMap<>());
        for (SigSymbol member : members) {
            if (member == null || member.parserSignature == null
                    || member.parserModuleAuthority == null) {
                continue;
            }
            if (module == null) {
                module = member.parserModuleAuthority;
            } else if (module != member.parserModuleAuthority) {
                return null;
            }
            represented.add(member.parserSignature);
        }
        if (module == null || represented.isEmpty()) {
            return null;
        }

        List<Sig.PrimSig> covered = new ArrayList<>();
        for (Sig signature : module.getAllReachableSigs()) {
            if (!(signature instanceof Sig.PrimSig)
                    || signature.builtin
                    || signature.isAbstract == null) {
                continue;
            }
            Sig.PrimSig candidate = (Sig.PrimSig) signature;
            if (abstractSignatureCovered(
                    candidate,
                    represented,
                    module,
                    Collections.newSetFromMap(new IdentityHashMap<>()))) {
                covered.add(candidate);
            }
        }
        if (covered.isEmpty()) {
            return null;
        }
        Sig.PrimSig highest = null;
        for (Sig.PrimSig candidate : covered) {
            boolean coversEveryDerivedCarrier = true;
            for (Sig.PrimSig other : covered) {
                if (!declarationGuaranteesSubset(
                        other,
                        candidate,
                        Collections.newSetFromMap(new IdentityHashMap<>()))) {
                    coversEveryDerivedCarrier = false;
                    break;
                }
            }
            if (coversEveryDerivedCarrier
                    && (highest == null
                            || ExactAlloyType.normalizeColumn(candidate.label)
                                    .compareTo(ExactAlloyType.normalizeColumn(
                                            highest.label)) < 0)) {
                highest = candidate;
            }
        }
        return highest == null ? null : fromParser(highest, module);
    }

    private static boolean abstractSignatureCovered(
            Sig.PrimSig candidate,
            Set<Sig> represented,
            CompModule module,
            Set<Sig> active) {
        if (represented.contains(candidate)) {
            return true;
        }
        if (candidate.isAbstract == null || !active.add(candidate)) {
            return false;
        }
        try {
            List<Sig.PrimSig> directChildren = new ArrayList<>();
            for (Sig reachable : module.getAllReachableSigs()) {
                if (reachable instanceof Sig.PrimSig
                        && !reachable.builtin
                        && ((Sig.PrimSig) reachable).parent == candidate) {
                    directChildren.add((Sig.PrimSig) reachable);
                }
            }
            if (directChildren.isEmpty()) {
                return false;
            }
            for (Sig.PrimSig child : directChildren) {
                if (represented.contains(child)) {
                    continue;
                }
                if (!abstractSignatureCovered(
                        child, represented, module, active)) {
                    return false;
                }
            }
            return true;
        } finally {
            active.remove(candidate);
        }
    }

    /** Exact unary type backed by the same live parser declaration. */
    public ExactAlloyType parserExactType() {
        if (parserSignature == null || parserModuleAuthority == null) {
            throw new IllegalStateException(
                    "A parser exact type requires parser signature authority");
        }
        return ExactAlloyType.fromParser(
                parserSignature.type(), parserModuleAuthority);
    }

    private static boolean declarationGuaranteesSubset(
            Sig candidate,
            Sig carrier,
            Set<Sig> active) {
        if (candidate == carrier) {
            return true;
        }
        if (!active.add(candidate)) {
            return false;
        }
        try {
            if (candidate instanceof Sig.PrimSig) {
                Sig.PrimSig parent = ((Sig.PrimSig) candidate).parent;
                return parent != null
                        && declarationGuaranteesSubset(parent, carrier, active);
            }
            if (candidate instanceof Sig.SubsetSig) {
                Sig.SubsetSig subset = (Sig.SubsetSig) candidate;
                if (subset.parents.isEmpty()) {
                    return false;
                }
                for (Sig parent : subset.parents) {
                    if (!declarationGuaranteesSubset(parent, carrier, active)) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        } finally {
            active.remove(candidate);
        }
    }

    public boolean isSameParserSignatureAs(SigSymbol other) {
        return other != null
                && parserSignature != null
                && parserModuleAuthority == other.parserModuleAuthority
                && parserSignature == other.parserSignature;
    }

    public String getSemanticIdentity() {
        switch (kind) {
            case BUILTIN_NONE:
                return BUILTIN_NONE_IDENTITY;
            case BUILTIN_UNIV:
                return BUILTIN_UNIV_IDENTITY;
            case BUILTIN_INT:
                return BUILTIN_INT_IDENTITY;
            case BUILTIN_SEQUENCE_INDEX:
                return BUILTIN_SEQUENCE_INDEX_IDENTITY;
            case USER:
            default:
                return "alloy/signature/" + name;
        }
    }
    @Override
    public String getName() {
        return name;
    }
    @Override
    public String getType() {
        return "Signature";
    }
    @Override
    public boolean isEndSymbol() {
        return false;
    }
    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, kind);
    }
    @Override
    public boolean equals(Object o) {
        if (o instanceof SigSymbol) {
            SigSymbol other = (SigSymbol) o;
            return this.name.equals(other.getName()) && this.kind == other.kind;
        } else {
            return false;
        }
    }
    @Override
    public int getMaxDownlinks() {
        return 0; // SigSymbol does not have downlinks
    }
    @Override
    public void setMaxDownlinks(int maxDownlinks) {
        // SigSymbol does not have downlinks, so this method does nothing
    }
}

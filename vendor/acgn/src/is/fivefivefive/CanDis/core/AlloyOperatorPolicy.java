package is.fivefivefive.CanDis.core;

import java.util.Objects;

import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.theory.ArityPolicy;
import is.fivefivefive.CanDis.theory.FlatLicense;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.SiblingQuotient;
import is.fivefivefive.CanDis.theory.UnitLicense;

/** Conservative Alloy sibling/flattening policy, indexed by semantic profile. */
public final class AlloyOperatorPolicy {
    private final ArityPolicy arityPolicy;
    private final SiblingQuotient siblingQuotient;
    private final FlatLicense flatLicense;
    private final UnitLicense unitLicense;

    private AlloyOperatorPolicy(
            ArityPolicy arityPolicy,
            SiblingQuotient siblingQuotient,
            FlatLicense flatLicense,
            UnitLicense unitLicense) {
        this.arityPolicy = Objects.requireNonNull(arityPolicy, "arityPolicy");
        this.siblingQuotient = Objects.requireNonNull(siblingQuotient, "siblingQuotient");
        this.flatLicense = Objects.requireNonNull(flatLicense, "flatLicense");
        this.unitLicense = Objects.requireNonNull(unitLicense, "unitLicense");
    }

    public static AlloyOperatorPolicy forShape(
            Opcode opcode,
            int fixedArity,
            boolean variadic,
            SemanticProfile profile) {
        Objects.requireNonNull(opcode, "opcode");
        Objects.requireNonNull(profile, "profile");
        if (isFlatSetOperator(opcode)) {
            return flat(
                    ArityPolicy.nonemptyVariadic(),
                    SiblingQuotient.COMMUTATIVE_IDEMPOTENT_SET);
        }
        if (isIntegerAcOperator(opcode)) {
            if (profile.overflowMode() == SemanticProfile.OverflowMode.MODULAR) {
                return flat(
                        ArityPolicy.nonemptyVariadic(),
                        SiblingQuotient.COMMUTATIVE_BAG);
            }
            return nonflat(ArityPolicy.exact(2), SiblingQuotient.COMMUTATIVE_BAG);
        }
        if (opcode == Opcode.EQUALS || opcode == Opcode.NOT_EQUALS || opcode == Opcode.IFF) {
            return nonflat(ArityPolicy.exact(2), SiblingQuotient.COMMUTATIVE_BAG);
        }
        if (isFixedBinaryTypedChainOperator(opcode)) {
            return nonflat(ArityPolicy.exact(2), SiblingQuotient.ORDERED_SEQUENCE);
        }
        if (opcode == Opcode.CALL) {
            return nonflat(
                    ArityPolicy.exact(requireFixed(fixedArity)),
                    SiblingQuotient.ORDERED_SEQUENCE);
        }
        if (opcode == Opcode.DISJOINT) {
            return nonflat(
                    variadic ? ArityPolicy.nonemptyVariadic()
                            : ArityPolicy.exact(requireFixed(fixedArity)),
                    SiblingQuotient.COMMUTATIVE_BAG);
        }
        if (variadic) {
            return nonflat(
                    ArityPolicy.nonemptyVariadic(),
                    SiblingQuotient.ORDERED_SEQUENCE);
        }
        return nonflat(
                ArityPolicy.exact(requireFixed(fixedArity)),
                SiblingQuotient.ORDERED_SEQUENCE);
    }

    public static AlloyOperatorPolicy defaultForShape(
            Opcode opcode,
            int fixedArity,
            boolean variadic) {
        return forShape(
                opcode, fixedArity, variadic,
                SemanticProfile.alloyOverflowForbidding());
    }

    private static int requireFixed(int fixedArity) {
        if (fixedArity < 0) {
            throw new IllegalArgumentException("A fixed operator requires an exact arity");
        }
        return fixedArity;
    }

    private static AlloyOperatorPolicy flat(
            ArityPolicy policy,
            SiblingQuotient quotient) {
        return new AlloyOperatorPolicy(
                policy, quotient, FlatLicense.atRootPort(0), UnitLicense.ABSENT);
    }

    private static AlloyOperatorPolicy nonflat(
            ArityPolicy policy,
            SiblingQuotient quotient) {
        return new AlloyOperatorPolicy(
                policy, quotient, FlatLicense.none(), UnitLicense.ABSENT);
    }

    public static boolean isFlatSetOperator(Opcode opcode) {
        return opcode == Opcode.AND || opcode == Opcode.OR
                || opcode == Opcode.INTERSECT || opcode == Opcode.PLUS;
    }

    public static boolean isIntegerAcOperator(Opcode opcode) {
        return opcode == Opcode.IPLUS || opcode == Opcode.MUL;
    }

    public static boolean isFixedBinaryTypedChainOperator(Opcode opcode) {
        switch (opcode) {
            case JOIN:
            case ARROW:
            case ANY_ARROW_SOME:
            case ANY_ARROW_ONE:
            case ANY_ARROW_LONE:
            case SOME_ARROW_ANY:
            case SOME_ARROW_SOME:
            case SOME_ARROW_ONE:
            case SOME_ARROW_LONE:
            case ONE_ARROW_ANY:
            case ONE_ARROW_SOME:
            case ONE_ARROW_ONE:
            case ONE_ARROW_LONE:
            case LONE_ARROW_ANY:
            case LONE_ARROW_SOME:
            case LONE_ARROW_ONE:
            case LONE_ARROW_LONE:
            case ISSEQ_ARROW_LONE:
                return true;
            default:
                return false;
        }
    }

    public ArityPolicy arityPolicy() {
        return arityPolicy;
    }

    public SiblingQuotient siblingQuotient() {
        return siblingQuotient;
    }

    public FlatLicense flatLicense() {
        return flatLicense;
    }

    public UnitLicense unitLicense() {
        return unitLicense;
    }

    public boolean isVariadic() {
        return arityPolicy.kind() == ArityPolicy.Kind.AT_LEAST;
    }
}

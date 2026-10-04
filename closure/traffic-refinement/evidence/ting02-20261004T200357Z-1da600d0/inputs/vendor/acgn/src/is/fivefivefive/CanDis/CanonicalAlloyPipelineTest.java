package is.fivefivefive.CanDis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.ast.Attr;
import edu.mit.csail.sdg.ast.Sig.PrimSig;
import edu.mit.csail.sdg.ast.Type;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.alloy.ConstSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.metric.RepairProjection;
import is.fivefivefive.CanDis.metric.RepairView;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.theory.ContainerLawCertificate;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.ContainerConstructionCertificate;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.DependentChainCertificate;
import is.fivefivefive.CanDis.theory.DependentChainKind;
import is.fivefivefive.CanDis.theory.DependentChainTheory;
import is.fivefivefive.CanDis.theory.DependentColumnEvidence;
import is.fivefivefive.CanDis.theory.FlatConstructionCertificate;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.SeqPort;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

/** Fast Alloy-to-exact-engine Phase I conformance checks. */
public final class CanonicalAlloyPipelineTest {
    private static int checks;

    private CanonicalAlloyPipelineTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("candis-phase-i-");
        Path modelPath = directory.resolve("phase_i.als");
        try {
            Files.writeString(modelPath, source(), StandardCharsets.UTF_8);
            CompModule module = AlloyUtil.compileAlloyModule(modelPath.toString());
            check(module != null, "self-contained Alloy fixture must parse");
            check(Arrays.stream(ExactAlloyType.class.getDeclaredMethods())
                            .noneMatch(method -> method.getName().equals(
                                            "relationWithAncestry")
                                    && java.lang.reflect.Modifier.isPublic(
                                            method.getModifiers())),
                    "explicit subtype ancestry must not be a public producer authority");
            check(Arrays.stream(ExactAlloyType.class.getDeclaredConstructors())
                            .noneMatch(constructor -> java.lang.reflect.Modifier.isPublic(
                                    constructor.getModifiers())),
                    "exact Alloy type construction must remain factory controlled");
            check(!new ConstSymbol("iden", false, true)
                            .isBuiltinIdentityRelation(),
                    "public constant metadata must not mint reserved iden authority");
            check(Arrays.stream(DependentColumnEvidence.class.getDeclaredConstructors())
                            .noneMatch(constructor -> java.lang.reflect.Modifier.isPublic(
                                    constructor.getModifiers())),
                    "dependent ancestry evidence must have no public constructor");
            check(Arrays.stream(DependentColumnEvidence.class.getDeclaredMethods())
                            .noneMatch(method -> method.getName().equals(
                                            "fromExactAlloyType")
                                    && java.lang.reflect.Modifier.isPublic(
                                            method.getModifiers())),
                    "parser ancestry extraction must remain package private");
            check(Arrays.stream(
                            IRAgent.TemporalReferenceEvidence.class
                                    .getDeclaredConstructors())
                            .noneMatch(constructor -> java.lang.reflect.Modifier.isPublic(
                                    constructor.getModifiers())),
                    "temporal parser-occurrence evidence must have no public constructor");
            check(Arrays.stream(IRAgent.TemporalReferenceClaim.class
                            .getDeclaredConstructors())
                            .noneMatch(constructor -> java.lang.reflect.Modifier.isPublic(
                                    constructor.getModifiers())),
                    "temporal reference claims must have no public constructor");
            check(Arrays.stream(NormalForm.class.getDeclaredMethods())
                            .filter(method -> method.getName().equals(
                                    "createTemporalReference"))
                            .allMatch(method -> method.getParameterCount() == 1
                                    && method.getParameterTypes()[0]
                                            == IRAgent.TemporalReferenceEvidence.class),
                    "NormalForm must expose no metadata-only temporal authority path");
            checkEmptyRelationArity(module);
            checkCertifiedDistributiveLattices();
            checkCertifiedFullCarrierAbsorption();
            checkCertifiedRelationalFactoring();
            ModelUnit model = new ModelUnit(null, module);
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(model, null);
            checkTemporalReferenceAuthorityIsolation(visitor);
            checkFastRewriteRepairBoundaries(visitor);

            CanonicalAlloyPipeline.Prepared alphaLeft = prepare(visitor, "alphaLeft");
            CanonicalAlloyPipeline.Prepared alphaRight = prepare(visitor, "alphaRight");
            CanonicalAlloyPipeline.Prepared aciLeft = prepare(visitor, "aciLeft");
            CanonicalAlloyPipeline.Prepared aciRight = prepare(visitor, "aciRight");
            CanonicalAlloyPipeline.Prepared andDuplicate =
                    prepare(visitor, "andDuplicate");
            CanonicalAlloyPipeline.Prepared andBare = prepare(visitor, "andBare");
            CanonicalAlloyPipeline.Prepared quotientDuplicate =
                    prepare(visitor, "quotientDuplicate");
            CanonicalAlloyPipeline.Prepared quotientBare =
                    prepare(visitor, "quotientBare");
            CanonicalAlloyPipeline.Prepared orDuplicate =
                    prepare(visitor, "orDuplicate");
            CanonicalAlloyPipeline.Prepared orBare = prepare(visitor, "orBare");
            CanonicalAlloyPipeline.Prepared iffNestedDuplicate =
                    prepare(visitor, "iffNestedDuplicate");
            CanonicalAlloyPipeline.Prepared iffNestedExpanded =
                    prepare(visitor, "iffNestedExpanded");
            CanonicalAlloyPipeline.Prepared unionDuplicate =
                    prepare(visitor, "unionDuplicate");
            CanonicalAlloyPipeline.Prepared unionBare = prepare(visitor, "unionBare");
            CanonicalAlloyPipeline.Prepared intersectDuplicate =
                    prepare(visitor, "intersectDuplicate");
            CanonicalAlloyPipeline.Prepared intersectBare =
                    prepare(visitor, "intersectBare");
            CanonicalAlloyPipeline.Prepared positive = prepare(visitor, "positive");
            CanonicalAlloyPipeline.Prepared negative = prepare(visitor, "negative");
            CanonicalAlloyPipeline.Prepared shadowLeft = prepare(visitor, "shadowLeft");
            CanonicalAlloyPipeline.Prepared shadowRight = prepare(visitor, "shadowRight");
            CanonicalAlloyPipeline.Prepared disjoint = prepare(visitor, "disjointPred");
            CanonicalAlloyPipeline.Prepared nondisjoint = prepare(visitor, "nondisjoint");
            CanonicalAlloyPipeline.Prepared temporalLeft = prepare(visitor, "temporalLeft");
            CanonicalAlloyPipeline.Prepared temporalRight = prepare(visitor, "temporalRight");
            CanonicalAlloyPipeline.Prepared temporalLet = prepare(visitor, "temporalLet");
            CanonicalAlloyPipeline.Prepared temporalLetExpanded =
                    prepare(visitor, "temporalLetExpanded");
            CanonicalAlloyPipeline.Prepared temporalDuplicate =
                    prepare(visitor, "temporalDuplicate");
            CanonicalAlloyPipeline.Prepared temporalBare =
                    prepare(visitor, "temporalBare");
            CanonicalAlloyPipeline.Prepared temporalGuardDuplicate =
                    prepare(visitor, "temporalGuardDuplicate");
            CanonicalAlloyPipeline.Prepared temporalEliminated =
                    prepare(visitor, "temporalEliminated");
            CanonicalAlloyPipeline.Prepared tautology =
                    prepare(visitor, "tautology");
            CanonicalAlloyPipeline.Prepared mixedCarrierLeft = prepare(visitor, "mixedCarrierLeft");
            CanonicalAlloyPipeline.Prepared mixedCarrierRight = prepare(visitor, "mixedCarrierRight");
            CanonicalAlloyPipeline.Prepared heterogeneousOrderLeft =
                    prepare(visitor, "heterogeneousOrderLeft");
            CanonicalAlloyPipeline.Prepared heterogeneousOrderRight =
                    prepare(visitor, "heterogeneousOrderRight");
            CanonicalAlloyPipeline.Prepared domainAciLeft = prepare(visitor, "domainAciLeft");
            CanonicalAlloyPipeline.Prepared domainAciRight = prepare(visitor, "domainAciRight");
            CanonicalAlloyPipeline.Prepared nestedUnionLeft =
                    prepare(visitor, "nestedUnionLeft");
            CanonicalAlloyPipeline.Prepared nestedUnionRight =
                    prepare(visitor, "nestedUnionRight");
            CanonicalAlloyPipeline.Prepared equalityOrderLeft =
                    prepare(visitor, "equalityOrderLeft");
            CanonicalAlloyPipeline.Prepared equalityOrderRight =
                    prepare(visitor, "equalityOrderRight");
            CanonicalAlloyPipeline.Prepared duplicateDisjoint =
                    prepare(visitor, "duplicateDisjoint");
            CanonicalAlloyPipeline.Prepared heterogeneousDisjoint =
                    prepare(visitor, "heterogeneousDisjoint");
            CanonicalAlloyPipeline.Prepared binaryArrowType =
                    prepare(visitor, "binaryArrowType");
            CanonicalAlloyPipeline.Prepared intArrowType =
                    prepare(visitor, "intArrowType");
            CanonicalAlloyPipeline.Prepared reversedArrowType =
                    prepare(visitor, "reversedArrowType");
            CanonicalAlloyPipeline.Prepared binaryJoinType =
                    prepare(visitor, "binaryJoinType");
            CanonicalAlloyPipeline.Prepared arrowAssocLeft =
                    prepare(visitor, "arrowAssocLeft");
            CanonicalAlloyPipeline.Prepared arrowAssocRight =
                    prepare(visitor, "arrowAssocRight");
            CanonicalAlloyPipeline.Prepared joinAssocLeft =
                    prepare(visitor, "joinAssocLeft");
            CanonicalAlloyPipeline.Prepared joinAssocRight =
                    prepare(visitor, "joinAssocRight");
            CanonicalAlloyPipeline.Prepared rightUnivJoinLeft =
                    prepare(visitor, "rightUnivJoinLeft");
            CanonicalAlloyPipeline.Prepared rightUnivJoinRight =
                    prepare(visitor, "rightUnivJoinRight");
            CanonicalAlloyPipeline.Prepared leftUnivJoinLeft =
                    prepare(visitor, "leftUnivJoinLeft");
            CanonicalAlloyPipeline.Prepared leftUnivJoinRight =
                    prepare(visitor, "leftUnivJoinRight");
            CanonicalAlloyPipeline.Prepared subtypeBoundaryJoin =
                    prepare(visitor, "subtypeBoundaryJoin");
            CanonicalAlloyPipeline.Prepared relationFamilyJoin =
                    prepare(visitor, "relationFamilyJoin");
            CanonicalAlloyPipeline.Prepared disjointBoundaryJoin =
                    prepare(visitor, "disjointBoundaryJoin");
            CanonicalAlloyPipeline.Prepared emptyIntersectJoin =
                    prepare(visitor, "emptyIntersectJoin");
            CanonicalAlloyPipeline.Prepared emptyUnionJoin =
                    prepare(visitor, "emptyUnionJoin");
            CanonicalAlloyPipeline.Prepared localGroupingLeft =
                    prepare(visitor, "localGroupingLeft");
            CanonicalAlloyPipeline.Prepared localGroupingRight =
                    prepare(visitor, "localGroupingRight");
            CanonicalAlloyPipeline.Prepared alphaNearLeft = prepare(visitor, "alphaNearLeft");
            CanonicalAlloyPipeline.Prepared alphaNearRight = prepare(visitor, "alphaNearRight");
            CanonicalAlloyPipeline.Prepared aciNearLeft = prepare(visitor, "aciNearLeft");
            CanonicalAlloyPipeline.Prepared aciNearRight = prepare(visitor, "aciNearRight");
            CanonicalAlloyPipeline.Prepared binderAll = prepare(visitor, "binderAll");
            CanonicalAlloyPipeline.Prepared binderSome = prepare(visitor, "binderSome");
            CanonicalAlloyPipeline.Prepared nestedSubtypeLeft =
                    prepare(visitor, "nestedSubtypeLeft");
            CanonicalAlloyPipeline.Prepared nestedSubtypeRight =
                    prepare(visitor, "nestedSubtypeRight");
            CanonicalAlloyPipeline.Prepared redundantDomainGuardLeft =
                    prepare(visitor, "redundantDomainGuardLeft");
            CanonicalAlloyPipeline.Prepared redundantDomainGuardRight =
                    prepare(visitor, "redundantDomainGuardRight");
            CanonicalAlloyPipeline.Prepared witnessedCarrierLeft =
                    prepare(visitor, "witnessedCarrierLeft");
            CanonicalAlloyPipeline.Prepared witnessedCarrierRight =
                    prepare(visitor, "witnessedCarrierRight");
            CanonicalAlloyPipeline.Prepared commutativeBinderLeft =
                    prepare(visitor, "commutativeBinderLeft");
            CanonicalAlloyPipeline.Prepared commutativeBinderRight =
                    prepare(visitor, "commutativeBinderRight");
            CanonicalAlloyPipeline.Prepared localComprehensionLeft =
                    prepare(visitor, "localComprehensionLeft");
            CanonicalAlloyPipeline.Prepared localComprehensionRight =
                    prepare(visitor, "localComprehensionRight");
            CanonicalAlloyPipeline.Prepared localPermutationLeft =
                    prepare(visitor, "localPermutationLeft");
            CanonicalAlloyPipeline.Prepared localPermutationRight =
                    prepare(visitor, "localPermutationRight");
            CanonicalAlloyPipeline.Prepared namedRefFirst =
                    prepare(visitor, "namedRefFirst");
            CanonicalAlloyPipeline.Prepared namedRefLast =
                    prepare(visitor, "namedRefLast");
            CanonicalAlloyPipeline.Prepared temporalBinderTarget =
                    prepare(visitor, "temporalBinderTarget");
            CanonicalAlloyPipeline.Prepared temporalBinderWrongTarget =
                    prepare(visitor, "temporalBinderWrongTarget");
            CanonicalAlloyPipeline.Prepared temporalBinderRenamed =
                    prepare(visitor, "temporalBinderRenamed");
            CanonicalAlloyPipeline.Prepared phaseLocalTemporalBinder =
                    prepare(visitor, "phaseLocalTemporalBinder");
            CanonicalAlloyPipeline.Prepared phaseLocalTemporalBinderRenamed =
                    prepare(visitor, "phaseLocalTemporalBinderRenamed");
            CanonicalAlloyPipeline.Prepared phaseLocalTemporalBinderWrong =
                    prepare(visitor, "phaseLocalTemporalBinderWrong");
            CanonicalAlloyPipeline.Prepared phaseLocalRepeatedReference =
                    prepare(visitor, "phaseLocalRepeatedReference");
            CanonicalAlloyPipeline.Prepared parameterJoinLeft =
                    prepare(visitor, "parameterJoinLeft");
            CanonicalAlloyPipeline.Prepared parameterJoinRight =
                    prepare(visitor, "parameterJoinRight");
            CanonicalAlloyPipeline.Prepared parameterTypeS =
                    prepare(visitor, "parameterTypeS");
            CanonicalAlloyPipeline.Prepared parameterTypeT =
                    prepare(visitor, "parameterTypeT");
            CanonicalAlloyPipeline.Prepared parameterTypeTNo =
                    prepare(visitor, "parameterTypeTNo");
            CanonicalAlloyPipeline.Prepared prenexLoneLeft =
                    prepare(visitor, "prenexLoneLeft");
            CanonicalAlloyPipeline.Prepared prenexLoneRight =
                    prepare(visitor, "prenexLoneRight");
            CanonicalAlloyPipeline.Prepared booleanNeutralLeft =
                    prepare(visitor, "booleanNeutralLeft");
            CanonicalAlloyPipeline.Prepared booleanNeutralRight =
                    prepare(visitor, "booleanNeutralRight");
            CanonicalAlloyPipeline.Prepared plusNoneLeft =
                    prepare(visitor, "plusNoneLeft");
            CanonicalAlloyPipeline.Prepared plusNoneRight =
                    prepare(visitor, "plusNoneRight");
            CanonicalAlloyPipeline.Prepared intersectNoneLeft =
                    prepare(visitor, "intersectNoneLeft");
            CanonicalAlloyPipeline.Prepared intersectNoneRight =
                    prepare(visitor, "intersectNoneRight");
            CanonicalAlloyPipeline.Prepared selfMinusLeft =
                    prepare(visitor, "selfMinusLeft");
            CanonicalAlloyPipeline.Prepared selfMinusRight =
                    prepare(visitor, "selfMinusRight");
            CanonicalAlloyPipeline.Prepared complementLeft =
                    prepare(visitor, "complementLeft");
            CanonicalAlloyPipeline.Prepared complementRight =
                    prepare(visitor, "complementRight");
            CanonicalAlloyPipeline.Prepared contradictionLeft =
                    prepare(visitor, "contradictionLeft");
            CanonicalAlloyPipeline.Prepared contradictionRight =
                    prepare(visitor, "contradictionRight");
            CanonicalAlloyPipeline.Prepared unionIdempotentLeft =
                    prepare(visitor, "unionIdempotentLeft");
            CanonicalAlloyPipeline.Prepared unionIdempotentRight =
                    prepare(visitor, "unionIdempotentRight");
            CanonicalAlloyPipeline.Prepared intersectIdempotentLeft =
                    prepare(visitor, "intersectIdempotentLeft");
            CanonicalAlloyPipeline.Prepared intersectIdempotentRight =
                    prepare(visitor, "intersectIdempotentRight");
            CanonicalAlloyPipeline.Prepared aciComplementLeft =
                    prepare(visitor, "aciComplementLeft");
            CanonicalAlloyPipeline.Prepared aciComplementRight =
                    prepare(visitor, "aciComplementRight");
            CanonicalAlloyPipeline.Prepared aciCommutativeComplementLeft =
                    prepare(visitor, "aciCommutativeComplementLeft");
            CanonicalAlloyPipeline.Prepared aciAssociativeComplementLeft =
                    prepare(visitor, "aciAssociativeComplementLeft");
            CanonicalAlloyPipeline.Prepared aciSelfMinusLeft =
                    prepare(visitor, "aciSelfMinusLeft");
            CanonicalAlloyPipeline.Prepared aciSelfMinusRight =
                    prepare(visitor, "aciSelfMinusRight");
            CanonicalAlloyPipeline.Prepared aciNearMiss =
                    prepare(visitor, "aciNearMiss");
            CanonicalAlloyPipeline.Prepared aciSlotComplementLeft =
                    prepare(visitor, "aciSlotComplementLeft");
            CanonicalAlloyPipeline.Prepared aciSlotComplementRight =
                    prepare(visitor, "aciSlotComplementRight");
            CanonicalAlloyPipeline.Prepared aciSlotNearMiss =
                    prepare(visitor, "aciSlotNearMiss");
            CanonicalAlloyPipeline.Prepared aciSlotNearMissRight =
                    prepare(visitor, "aciSlotNearMissRight");
            CanonicalAlloyPipeline.Prepared integerPlus =
                    prepare(visitor, "integerPlus");
            CanonicalAlloyPipeline.Prepared integerPlusDuplicate =
                    prepare(visitor, "integerPlusDuplicate");
            CanonicalAlloyPipeline.Prepared integerPlusBare =
                    prepare(visitor, "integerPlusBare");
            CanonicalAlloyPipeline.Prepared integerMinus =
                    prepare(visitor, "integerMinus");
            CanonicalAlloyPipeline.Prepared integerArithmeticPlus =
                    prepare(visitor, "integerArithmeticPlus");
            CanonicalAlloyPipeline.Prepared integerArithmeticNearMiss =
                    prepare(visitor, "integerArithmeticNearMiss");
            CanonicalAlloyPipeline.Prepared integerArithmeticNested =
                    prepare(visitor, "integerArithmeticNested");
            CanonicalAlloyPipeline.Prepared intSetUnion =
                    prepare(visitor, "intSetUnion");
            CanonicalAlloyPipeline.Prepared intSetBare =
                    prepare(visitor, "intSetBare");
            CanonicalAlloyPipeline.Prepared intSetDifference =
                    prepare(visitor, "intSetDifference");
            CanonicalAlloyPipeline.Prepared intersectUniv =
                    prepare(visitor, "intersectUniv");
            CanonicalAlloyPipeline.Prepared minusNone =
                    prepare(visitor, "minusNone");
            CanonicalAlloyPipeline.Prepared plusUniv =
                    prepare(visitor, "plusUniv");
            CanonicalAlloyPipeline.Prepared univBare =
                    prepare(visitor, "univBare");
            CanonicalAlloyPipeline.Prepared minusUniv =
                    prepare(visitor, "minusUniv");
            CanonicalAlloyPipeline.Prepared noneMinus =
                    prepare(visitor, "noneMinus");
            CanonicalAlloyPipeline.Prepared heterogeneousIntUnion =
                    prepare(visitor, "heterogeneousIntUnion");
            CanonicalAlloyPipeline.Prepared intUnionIdentity =
                    prepare(visitor, "intUnionIdentity");
            CanonicalAlloyPipeline.Prepared booleanTruth =
                    prepare(visitor, "booleanTruth");
            CanonicalAlloyPipeline.Prepared booleanFalse =
                    prepare(visitor, "booleanFalse");
            CanonicalAlloyPipeline.Prepared emptySubsetUnary =
                    prepare(visitor, "emptySubsetUnary");
            CanonicalAlloyPipeline.Prepared emptyNotSubsetUnary =
                    prepare(visitor, "emptyNotSubsetUnary");
            CanonicalAlloyPipeline.Prepared emptySubsetBinary =
                    prepare(visitor, "emptySubsetBinary");
            CanonicalAlloyPipeline.Prepared emptyNotSubsetBinary =
                    prepare(visitor, "emptyNotSubsetBinary");
            CanonicalAlloyPipeline.Prepared emptyJoin =
                    prepare(visitor, "emptyJoin");
            CanonicalAlloyPipeline.Prepared repeatedNestedUnionLeft =
                    prepare(visitor, "repeatedNestedUnionLeft");
            CanonicalAlloyPipeline.Prepared repeatedNestedUnionRight =
                    prepare(visitor, "repeatedNestedUnionRight");
            CanonicalAlloyPipeline.Prepared distinctNestedUnionLeft =
                    prepare(visitor, "distinctNestedUnionLeft");
            CanonicalAlloyPipeline.Prepared distinctNestedUnionRight =
                    prepare(visitor, "distinctNestedUnionRight");
            CanonicalAlloyPipeline.Prepared distinctNestedUnionNearMiss =
                    prepare(visitor, "distinctNestedUnionNearMiss");
            CanonicalAlloyPipeline.Prepared subtypeWidenedUnionLeft =
                    prepare(visitor, "subtypeWidenedUnionLeft");
            CanonicalAlloyPipeline.Prepared subtypeWidenedUnionRight =
                    prepare(visitor, "subtypeWidenedUnionRight");
            CanonicalAlloyPipeline.Prepared subtypeAbsorptionLeft =
                    prepare(visitor, "subtypeAbsorptionLeft");
            CanonicalAlloyPipeline.Prepared subtypeAbsorptionNested =
                    prepare(visitor, "subtypeAbsorptionNested");
            CanonicalAlloyPipeline.Prepared subtypeAbsorptionRight =
                    prepare(visitor, "subtypeAbsorptionRight");
            CanonicalAlloyPipeline.Prepared subtypeSiblingOnly =
                    prepare(visitor, "subtypeSiblingOnly");
            CanonicalAlloyPipeline.Prepared subsetAbsorptionLeft =
                    prepare(visitor, "subsetAbsorptionLeft");
            CanonicalAlloyPipeline.Prepared nestedSubsetAbsorptionLeft =
                    prepare(visitor, "nestedSubsetAbsorptionLeft");
            CanonicalAlloyPipeline.Prepared nestedSubsetAbsorptionGrouped =
                    prepare(visitor, "nestedSubsetAbsorptionGrouped");
            CanonicalAlloyPipeline.Prepared nestedSubsetAbsorptionRight =
                    prepare(visitor, "nestedSubsetAbsorptionRight");
            CanonicalAlloyPipeline.Prepared nestedSubsetSiblingOnly =
                    prepare(visitor, "nestedSubsetSiblingOnly");
            CanonicalAlloyPipeline.Prepared multiSubsetCommonCarrierLeft =
                    prepare(visitor, "multiSubsetCommonCarrierLeft");
            CanonicalAlloyPipeline.Prepared multiSubsetCommonCarrierRight =
                    prepare(visitor, "multiSubsetCommonCarrierRight");
            CanonicalAlloyPipeline.Prepared multiSubsetSingleBranchLeft =
                    prepare(visitor, "multiSubsetSingleBranchLeft");
            CanonicalAlloyPipeline.Prepared multiSubsetSingleBranchRight =
                    prepare(visitor, "multiSubsetSingleBranchRight");
            CanonicalAlloyPipeline.Prepared typedExpressionNotCarrierLeft =
                    prepare(visitor, "typedExpressionNotCarrierLeft");
            CanonicalAlloyPipeline.Prepared typedExpressionNotCarrierRight =
                    prepare(visitor, "typedExpressionNotCarrierRight");
            CanonicalAlloyPipeline.Prepared typedExpressionWithCarrier =
                    prepare(visitor, "typedExpressionWithCarrier");
            CanonicalAlloyPipeline.Prepared abstractCoverUnion =
                    prepare(visitor, "abstractCoverUnion");
            CanonicalAlloyPipeline.Prepared abstractCoverParent =
                    prepare(visitor, "abstractCoverParent");
            CanonicalAlloyPipeline.Prepared abstractCoverWithSubrelation =
                    prepare(visitor, "abstractCoverWithSubrelation");
            CanonicalAlloyPipeline.Prepared abstractCoverWithUnrelated =
                    prepare(visitor, "abstractCoverWithUnrelated");
            CanonicalAlloyPipeline.Prepared abstractProductRightCoverUnion =
                    prepare(visitor, "abstractProductRightCoverUnion");
            CanonicalAlloyPipeline.Prepared abstractProductRightCoverParent =
                    prepare(visitor, "abstractProductRightCoverParent");
            CanonicalAlloyPipeline.Prepared abstractProductLeftCoverUnion =
                    prepare(visitor, "abstractProductLeftCoverUnion");
            CanonicalAlloyPipeline.Prepared abstractProductLeftCoverParent =
                    prepare(visitor, "abstractProductLeftCoverParent");
            CanonicalAlloyPipeline.Prepared abstractProductDiagonal =
                    prepare(visitor, "abstractProductDiagonal");
            CanonicalAlloyPipeline.Prepared abstractProductFull =
                    prepare(visitor, "abstractProductFull");
            CanonicalAlloyPipeline.Prepared abstractProductWithUnrelated =
                    prepare(visitor, "abstractProductWithUnrelated");
            CanonicalAlloyPipeline.Prepared abstractProductTernaryCoverUnion =
                    prepare(visitor, "abstractProductTernaryCoverUnion");
            CanonicalAlloyPipeline.Prepared abstractProductTernaryCoverParent =
                    prepare(visitor, "abstractProductTernaryCoverParent");
            CanonicalAlloyPipeline.Prepared abstractProductFullGridUnion =
                    prepare(visitor, "abstractProductFullGridUnion");
            CanonicalAlloyPipeline.Prepared abstractProductPartialGridUnion =
                    prepare(visitor, "abstractProductPartialGridUnion");
            CanonicalAlloyPipeline.Prepared abstractProductIntCoverUnion =
                    prepare(visitor, "abstractProductIntCoverUnion");
            CanonicalAlloyPipeline.Prepared abstractProductIntCoverParent =
                    prepare(visitor, "abstractProductIntCoverParent");
            CanonicalAlloyPipeline.Prepared abstractProductIntLeftCoverUnion =
                    prepare(visitor, "abstractProductIntLeftCoverUnion");
            CanonicalAlloyPipeline.Prepared abstractProductIntLeftCoverParent =
                    prepare(visitor, "abstractProductIntLeftCoverParent");
            CanonicalAlloyPipeline.Prepared ordinaryProductRightDistributed =
                    prepare(visitor, "ordinaryProductRightDistributed");
            CanonicalAlloyPipeline.Prepared ordinaryProductRightFactored =
                    prepare(visitor, "ordinaryProductRightFactored");
            CanonicalAlloyPipeline.Prepared ordinaryProductLeftDistributed =
                    prepare(visitor, "ordinaryProductLeftDistributed");
            CanonicalAlloyPipeline.Prepared ordinaryProductLeftFactored =
                    prepare(visitor, "ordinaryProductLeftFactored");
            CanonicalAlloyPipeline.Prepared ordinaryProductTernaryDistributed =
                    prepare(visitor, "ordinaryProductTernaryDistributed");
            CanonicalAlloyPipeline.Prepared ordinaryProductTernaryFactored =
                    prepare(visitor, "ordinaryProductTernaryFactored");
            CanonicalAlloyPipeline.Prepared ordinaryProductFullGrid =
                    prepare(visitor, "ordinaryProductFullGrid");
            CanonicalAlloyPipeline.Prepared ordinaryProductFullFactored =
                    prepare(visitor, "ordinaryProductFullFactored");
            CanonicalAlloyPipeline.Prepared ordinaryProductPartialGrid =
                    prepare(visitor, "ordinaryProductPartialGrid");
            CanonicalAlloyPipeline.Prepared ordinaryProductDiagonal =
                    prepare(visitor, "ordinaryProductDiagonal");
            CanonicalAlloyPipeline.Prepared abstractNestedUnion =
                    prepare(visitor, "abstractNestedUnion");
            CanonicalAlloyPipeline.Prepared abstractNestedInner =
                    prepare(visitor, "abstractNestedInner");
            CanonicalAlloyPipeline.Prepared abstractNestedOuter =
                    prepare(visitor, "abstractNestedOuter");
            CanonicalAlloyPipeline.Prepared abstractSingleChild =
                    prepare(visitor, "abstractSingleChild");
            CanonicalAlloyPipeline.Prepared abstractSingleParent =
                    prepare(visitor, "abstractSingleParent");
            CanonicalAlloyPipeline.Prepared abstractMissingBranch =
                    prepare(visitor, "abstractMissingBranch");
            CanonicalAlloyPipeline.Prepared nonAbstractChildren =
                    prepare(visitor, "nonAbstractChildren");
            CanonicalAlloyPipeline.Prepared nonAbstractParent =
                    prepare(visitor, "nonAbstractParent");
            CanonicalAlloyPipeline.Prepared abstractSubsetOnly =
                    prepare(visitor, "abstractSubsetOnly");
            CanonicalAlloyPipeline.Prepared abstractSubsetParent =
                    prepare(visitor, "abstractSubsetParent");
            CanonicalAlloyPipeline.Prepared enumCoverUnion =
                    prepare(visitor, "enumCoverUnion");
            CanonicalAlloyPipeline.Prepared enumCoverParent =
                    prepare(visitor, "enumCoverParent");
            CanonicalAlloyPipeline.Prepared idenLeft =
                    prepare(visitor, "idenLeft");
            CanonicalAlloyPipeline.Prepared idenRight =
                    prepare(visitor, "idenRight");
            CanonicalAlloyPipeline.Prepared idenBare =
                    prepare(visitor, "idenBare");
            CanonicalAlloyPipeline.Prepared idenMiddle =
                    prepare(visitor, "idenMiddle");
            CanonicalAlloyPipeline.Prepared idenMiddleBare =
                    prepare(visitor, "idenMiddleBare");
            CanonicalAlloyPipeline.Prepared idenAll =
                    prepare(visitor, "idenAll");
            CanonicalAlloyPipeline.Prepared idenAllBare =
                    prepare(visitor, "idenAllBare");
            CanonicalAlloyPipeline.Prepared joinNonIdentity =
                    prepare(visitor, "joinNonIdentity");
            CanonicalAlloyPipeline.Prepared transposeIden =
                    prepare(visitor, "transposeIden");
            CanonicalAlloyPipeline.Prepared closureIden =
                    prepare(visitor, "closureIden");
            CanonicalAlloyPipeline.Prepared rclosureIden =
                    prepare(visitor, "rclosureIden");
            CanonicalAlloyPipeline.Prepared doubleTranspose =
                    prepare(visitor, "doubleTranspose");
            CanonicalAlloyPipeline.Prepared transposeBare =
                    prepare(visitor, "transposeBare");
            CanonicalAlloyPipeline.Prepared transposeArrow =
                    prepare(visitor, "transposeArrow");
            CanonicalAlloyPipeline.Prepared transposeArrowReversed =
                    prepare(visitor, "transposeArrowReversed");
            CanonicalAlloyPipeline.Prepared transposeArrowWrongOrder =
                    prepare(visitor, "transposeArrowWrongOrder");
            CanonicalAlloyPipeline.Prepared transposeUnionLeft =
                    prepare(visitor, "transposeUnionLeft");
            CanonicalAlloyPipeline.Prepared transposeUnionRight =
                    prepare(visitor, "transposeUnionRight");
            CanonicalAlloyPipeline.Prepared transposeIntersectLeft =
                    prepare(visitor, "transposeIntersectLeft");
            CanonicalAlloyPipeline.Prepared transposeIntersectRight =
                    prepare(visitor, "transposeIntersectRight");
            CanonicalAlloyPipeline.Prepared transposeMinusLeft =
                    prepare(visitor, "transposeMinusLeft");
            CanonicalAlloyPipeline.Prepared transposeMinusRight =
                    prepare(visitor, "transposeMinusRight");
            CanonicalAlloyPipeline.Prepared transposeContainerNearMiss =
                    prepare(visitor, "transposeContainerNearMiss");
            CanonicalAlloyPipeline.Prepared transposeProductUnionLeft =
                    prepare(visitor, "transposeProductUnionLeft");
            CanonicalAlloyPipeline.Prepared transposeProductUnionRight =
                    prepare(visitor, "transposeProductUnionRight");
            CanonicalAlloyPipeline.Prepared transposeSlotUnionLeft =
                    prepare(visitor, "transposeSlotUnionLeft");
            CanonicalAlloyPipeline.Prepared transposeSlotUnionRight =
                    prepare(visitor, "transposeSlotUnionRight");
            CanonicalAlloyPipeline.Prepared closureClosure =
                    prepare(visitor, "closureClosure");
            CanonicalAlloyPipeline.Prepared closureBare =
                    prepare(visitor, "closureBare");
            CanonicalAlloyPipeline.Prepared rclosureRclosure =
                    prepare(visitor, "rclosureRclosure");
            CanonicalAlloyPipeline.Prepared rclosureBare =
                    prepare(visitor, "rclosureBare");
            CanonicalAlloyPipeline.Prepared closureRclosure =
                    prepare(visitor, "closureRclosure");
            CanonicalAlloyPipeline.Prepared rclosureClosure =
                    prepare(visitor, "rclosureClosure");
            CanonicalAlloyPipeline.Prepared equalityComplementLeft =
                    prepare(visitor, "equalityComplementLeft");
            CanonicalAlloyPipeline.Prepared equalityComplementRight =
                    prepare(visitor, "equalityComplementRight");
            CanonicalAlloyPipeline.Prepared equalityComplementNearMiss =
                    prepare(visitor, "equalityComplementNearMiss");
            CanonicalAlloyPipeline.Prepared equalitySlotComplementLeft =
                    prepare(visitor, "equalitySlotComplementLeft");
            CanonicalAlloyPipeline.Prepared equalitySlotComplementRight =
                    prepare(visitor, "equalitySlotComplementRight");
            CanonicalAlloyPipeline.Prepared equalitySlotComplementNearMiss =
                    prepare(visitor, "equalitySlotComplementNearMiss");
            CanonicalAlloyPipeline.Prepared nestedIteFormula =
                    prepare(visitor, "nestedIteFormula");
            CanonicalAlloyPipeline.Prepared expandedIteFormula =
                    prepare(visitor, "expandedIteFormula");
            CanonicalAlloyPipeline.Prepared nestedIteExpression =
                    prepare(visitor, "nestedIteExpression");

            SemanticProfile modularProfile = SemanticProfile.alloyModular();
            CanonicalAlloyPipeline.Prepared modularAlpha =
                    prepare(visitor, "alphaLeft", modularProfile);
            CanonicalAlloyPipeline.Prepared modularIntegerPlusDuplicate =
                    prepare(visitor, "integerPlusDuplicate", modularProfile);
            CanonicalAlloyPipeline.Prepared modularIntegerPlusBare =
                    prepare(visitor, "integerPlusBare", modularProfile);
            check(modularAlpha.semanticArtifact().semanticProfile().equals(modularProfile),
                    "Explicit compatibility profile reaches the internal artifact");
            modularAlpha.semanticArtifact().containerLaws().forEach((operator, declarations) ->
                    declarations.forEach(declaration ->
                            declaration.certificates().values().forEach(certificate -> {
                                check(certificate.authority()
                                                == ContainerLawCertificate.Authority
                                                        .ALLOY_PROFILE_THEORY,
                                        "Internal artifacts reject fixture law authority");
                                check(certificate.semanticProfile().equals(modularProfile),
                                        "Every law is indexed by the selected compatibility profile");
                                check(certificate.operatorIdentity().equals(operator),
                                        "Every internal law is indexed by its exact operator");
                            })));

            CanonicalAlloyPipeline.Prepared compactPositive =
                    positive.compactForComparison();
            CanonicalAlloyPipeline.Prepared compactNegative =
                    negative.compactForComparison();
            check(positive.retainsSemanticArtifact(),
                    "ordinary preparation must retain its certified construction artifact");
            check(!compactPositive.retainsSemanticArtifact(),
                    "comparison compaction must release the proof-heavy construction artifact");
            check(compactPositive.digest().equals(positive.digest()),
                    "comparison compaction must preserve the canonical digest");
            check(compactPositive.repairObservationSize() == positive.repairObservationSize(),
                    "comparison compaction must preserve the repair observation size");
            check(compactPositive.semanticProfile().equals(positive.semanticProfile()),
                    "comparison compaction must preserve the semantic profile");
            check(CanonicalAlloyPipeline.distance(compactPositive, compactNegative)
                            == CanonicalAlloyPipeline.distance(positive, negative),
                    "comparison compaction must preserve repair distance");
            CanonicalAlloyPipeline.Prepared compactModularAlpha =
                    modularAlpha.compactForComparison();
            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.distance(alphaLeft, modularAlpha));
            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.distance(
                            alphaLeft.compactForComparison(), compactModularAlpha));
            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.canonicalRepresentativeTreeDistance(
                            alphaLeft, modularAlpha));
            expectThrows(IllegalArgumentException.class, () ->
                    alphaLeft.equivalentTo(modularAlpha));
            boolean compactArtifactRejected = false;
            try {
                compactPositive.semanticArtifact();
            } catch (IllegalStateException expected) {
                compactArtifactRejected = true;
            }
            check(compactArtifactRejected,
                    "compact comparison values must fail closed for certificate replay");

            check(alphaLeft.equivalentTo(alphaRight),
                    "same-descriptor binder permutation must be alpha-equivalent");
            check(CanonicalAlloyPipeline.distance(alphaLeft, alphaRight) == 0,
                    "alpha-equivalent binders must have exact distance zero");
            check(aciLeft.equivalentTo(aciRight),
                    "ACI boolean operands must share the exact canonical key");
            check(CanonicalAlloyPipeline.distance(aciLeft, aciRight) == 0,
                    "ACI-equivalent matrices must have exact distance zero");
            List<CanonicalAlloyPipeline.Prepared> duplicateAci = Arrays.asList(
                    andDuplicate, orDuplicate, unionDuplicate, intersectDuplicate);
            List<CanonicalAlloyPipeline.Prepared> bareAci = Arrays.asList(
                    andBare, orBare, unionBare, intersectBare);
            for (int index = 0; index < duplicateAci.size(); index++) {
                CanonicalAlloyPipeline.Prepared duplicate = duplicateAci.get(index);
                CanonicalAlloyPipeline.Prepared bare = bareAci.get(index);
                int duplicateDistance;
                try {
                    duplicateDistance = CanonicalAlloyPipeline.distance(
                            duplicate, bare);
                } catch (RuntimeException failure) {
                    throw new IllegalStateException(
                            "ACI duplicate distance failed at index " + index,
                            failure);
                }
                check(duplicate.equivalentTo(bare)
                                && duplicateDistance == 0,
                        "ACI duplicate source must smart-construct its bare operand");
                check(duplicate.semanticArtifact().flatConstructions().stream()
                                .anyMatch(FlatConstructionCertificate::collapsedToSingleton),
                        "ACI singleton collapse must retain exact idempotency evidence");
            }
            check(iffNestedDuplicate.equivalentTo(iffNestedExpanded)
                            && CanonicalAlloyPipeline.distance(
                                    iffNestedDuplicate, iffNestedExpanded) == 0,
                    "IFF expansion must retain the pre-saturation occurrence carrier "
                            + "when a nested Set operand adopts its idempotent representative");
            check(quotientDuplicate.equivalentTo(quotientBare),
                    "the certified quotient must equate the contextual duplicate witness");
            check(CanonicalAlloyPipeline.distance(
                            quotientDuplicate, quotientBare) == 0,
                    "ACI set projection must remove operands equal in the certified e-class quotient");
            List<FlatConstructionCertificate> missingSingleton = new ArrayList<>(
                    andDuplicate.semanticArtifact().flatConstructions());
            missingSingleton.removeIf(
                    certificate -> !certificate.collapsedToSingleton());
            check(!missingSingleton.isEmpty(),
                    "duplicate AND must retain a singleton source occurrence");
            missingSingleton.remove(0);
            expectThrows(IllegalArgumentException.class, () ->
                    copyWithFlatConstructions(
                            andDuplicate.semanticArtifact(), missingSingleton));
            List<FlatConstructionCertificate> crossArtifactSingleton = new ArrayList<>(
                    andDuplicate.semanticArtifact().flatConstructions());
            FlatConstructionCertificate unrelatedOrSingleton =
                    orDuplicate.semanticArtifact().flatConstructions().stream()
                            .filter(FlatConstructionCertificate::collapsedToSingleton)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError(
                                    "duplicate OR must retain singleton evidence"));
            crossArtifactSingleton.add(unrelatedOrSingleton);
            expectThrows(IllegalArgumentException.class, () ->
                    copyWithFlatConstructions(
                            andDuplicate.semanticArtifact(), crossArtifactSingleton));
            List<FlatConstructionCertificate> duplicatedSingleton = new ArrayList<>(
                    andDuplicate.semanticArtifact().flatConstructions());
            duplicatedSingleton.add(duplicatedSingleton.stream()
                    .filter(FlatConstructionCertificate::collapsedToSingleton)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "duplicate AND must retain singleton evidence")));
            expectThrows(IllegalArgumentException.class, () ->
                    copyWithFlatConstructions(
                            andDuplicate.semanticArtifact(), duplicatedSingleton));
            check(CanonicalAlloyPipeline.distance(positive, negative) > 0,
                    "semantically opposed atoms must remain distinguishable");
            check(shadowLeft.equivalentTo(shadowRight),
                    "shadowed binders must remain alpha-equivalent without alias capture");
            check(CanonicalAlloyPipeline.distance(disjoint, nondisjoint) > 0,
                    "disjointness classes must remain part of the binder descriptor");
            check(CanonicalAlloyPipeline.distance(temporalLeft, temporalRight) > 0,
                    "different temporal-phase matrices must remain distinguishable");
            check(temporalLet.equivalentTo(temporalLetExpanded)
                            && CanonicalAlloyPipeline.distance(
                                    temporalLet, temporalLetExpanded) == 0,
                    "a lexical LET binding must be beta-substituted before its AFTER phase is split");
            check(!temporalLet.canonicalObservation().stableForm().contains("ALLOY/LET"),
                    "no LET reference may survive into a certified temporal phase");
            check(temporalDuplicate.equivalentTo(temporalBare)
                            && CanonicalAlloyPipeline.distance(
                                    temporalDuplicate, temporalBare) == 0,
                    "a certified duplicate temporal Set operand must collapse with its phase");
            check(temporalDuplicate.repairView().phases().size()
                            == temporalBare.repairView().phases().size(),
                    "a redundant temporal phase must not survive the certified Set quotient");
            check(temporalGuardDuplicate.repairView().phases().size() == 3
                            && temporalGuardDuplicate.repairView().temporalRoot()
                                    .children().size() == 1
                            && temporalGuardDuplicate.repairView().temporalRoot()
                                    .children().get(0).children().size() == 1
                            && "AFTER".equals(temporalGuardDuplicate.repairView()
                                    .temporalRoot().children().get(0).children().get(0).label()),
                    "Set fibers must follow source lineage when prenex guards reorder operands");
            check(temporalEliminated.equivalentTo(tautology)
                            && CanonicalAlloyPipeline.distance(
                                    temporalEliminated, tautology) == 0
                            && temporalEliminated.repairView().phases().size() == 1,
                    "a Boolean tautology may remove an authorized but unreachable temporal child");
            check(mixedCarrierLeft.equivalentTo(mixedCarrierRight),
                    "alpha-equivalence must preserve distinct primitive carrier blocks");
            check(heterogeneousOrderLeft.equivalentTo(heterogeneousOrderRight),
                    "independent heterogeneous binder order must not affect semantic equality");
            check(CanonicalAlloyPipeline.distance(
                            heterogeneousOrderLeft, heterogeneousOrderRight) == 0,
                    "independent heterogeneous binder reordering must retain distance zero");
            check(domainAciLeft.equivalentTo(domainAciRight),
                    "ACI-equivalent guarded binder domains must have certified equality: "
                            + domainAciLeft.digest() + " != " + domainAciRight.digest());
            check(CanonicalAlloyPipeline.distance(domainAciLeft, domainAciRight) == 0,
                    "ACI-equivalent guarded binder domains must retain distance zero");
            check(localGroupingLeft.equivalentTo(localGroupingRight),
                    "equivalent local declaration groupings must have certified equality");
            QuotientRepairDistance.Result localGroupingRepair =
                    QuotientRepairDistance.evaluate(
                            localGroupingLeft.repairView(), localGroupingRight.repairView());
            int localGroupingLegacy = legacyDistance(
                    visitor, "localGroupingLeft", "localGroupingRight");
            check(localGroupingLegacy > 0,
                    "the local-grouping fixture must expose the documented Fast Rewrite ambiguity");
            check(localGroupingRepair.distance() == 0,
                    "equivalent local declaration grouping must lie in the repair zero kernel");
            check(localGroupingRepair.kernelAuthority()
                            == QuotientRepairDistance.KernelAuthority
                                    .CERTIFIED_PROJECTION_PRODUCER_CONSISTENCY,
                    "pipeline repair views must carry certified projection authority");
            check(CanonicalAlloyPipeline.distance(alphaNearLeft, alphaNearRight) == 1,
                    "pairwise binder-orbit alignment must retain one-edit alpha locality");
            check(CanonicalAlloyPipeline.canonicalRepresentativeTreeDistance(
                            alphaNearLeft, alphaNearRight)
                            > CanonicalAlloyPipeline.distance(alphaNearLeft, alphaNearRight),
                    "independent canonical alpha representatives must expose their discontinuity baseline");
            check(CanonicalAlloyPipeline.distance(aciNearLeft, aciNearRight) == 1,
                    "ACI assignment must find the single changed operand");
            check(CanonicalAlloyPipeline.canonicalRepresentativeTreeDistance(
                            aciNearLeft, aciNearRight)
                            > CanonicalAlloyPipeline.distance(aciNearLeft, aciNearRight),
                    "independently sorted ACI representatives must not define repair geometry");
            check(CanonicalAlloyPipeline.distance(binderAll, binderSome) == 1,
                    "one quantifier declaration change must cost one repair");
            QuotientRepairDistance.Result parameterTypeRepair =
                    CanonicalAlloyPipeline.distanceEvaluation(
                            parameterTypeS, parameterTypeT);
            check(parameterTypeRepair.quantifierDistance() == 1
                            && parameterTypeRepair.matrixDistance() == 0
                            && parameterTypeRepair.distance() == 1,
                    "a positional parameter-type edit is explicit and preserves body identity");
            QuotientRepairDistance.Result parameterAndBodyRepair =
                    CanonicalAlloyPipeline.distanceEvaluation(
                            parameterTypeS, parameterTypeTNo);
            check(parameterAndBodyRepair.quantifierDistance() == 1
                            && parameterAndBodyRepair.matrixDistance() == 1
                            && parameterAndBodyRepair.distance() == 2,
                    "a parameter-type edit cannot hide an independent body repair");
            check(prenexLoneLeft.equivalentTo(prenexLoneRight),
                    "safe regrouping around a prenexed LONE binder preserves observation equality");
            check(CanonicalAlloyPipeline.distance(
                            prenexLoneLeft, prenexLoneRight) == 0,
                    "a prenexed LONE coordinate must not retain its obsolete lexical path");
            check(booleanNeutralLeft.equivalentTo(booleanNeutralRight),
                    "Boolean neutral-element elimination must precede the certified snapshot");
            check(CanonicalAlloyPipeline.distance(
                            booleanNeutralLeft, booleanNeutralRight) == 0,
                    "certified and repair observations agree after OR-false elimination");
            check(plusNoneLeft.equivalentTo(plusNoneRight),
                    "relational union-unit elimination must precede the certified snapshot");
            check(CanonicalAlloyPipeline.distance(plusNoneLeft, plusNoneRight) == 0,
                    "certified and repair observations agree after R + none elimination");
            check(intersectNoneLeft.equivalentTo(intersectNoneRight),
                    "relational intersection absorption must precede the certified snapshot");
            check(CanonicalAlloyPipeline.distance(
                            intersectNoneLeft, intersectNoneRight) == 0,
                    "certified and repair observations agree after R & none elimination");
            check(selfMinusLeft.equivalentTo(selfMinusRight),
                    "self-difference elimination must precede the certified snapshot");
            check(CanonicalAlloyPipeline.distance(selfMinusLeft, selfMinusRight) == 0,
                    "certified and repair observations agree after R - R elimination");
            check(complementLeft.equivalentTo(complementRight),
                    "Boolean complement elimination must precede the certified snapshot");
            check(CanonicalAlloyPipeline.distance(complementLeft, complementRight) == 0,
                    "certified and repair observations agree after A or not A elimination");
            check(contradictionLeft.equivalentTo(contradictionRight),
                    "Boolean contradiction elimination must precede the certified snapshot");
            check(CanonicalAlloyPipeline.distance(
                            contradictionLeft, contradictionRight) == 0,
                    "certified and repair observations agree after A and not A elimination");
            check(unionIdempotentLeft.equivalentTo(unionIdempotentRight),
                    "certified ACI union must preserve idempotence");
            check(CanonicalAlloyPipeline.distance(
                            unionIdempotentLeft, unionIdempotentRight) == 0,
                    "repair projection and certified ACI union agree on idempotence");
            check(intersectIdempotentLeft.equivalentTo(intersectIdempotentRight),
                    "certified ACI intersection must preserve idempotence");
            check(CanonicalAlloyPipeline.distance(
                            intersectIdempotentLeft, intersectIdempotentRight) == 0,
                    "repair projection and certified ACI intersection agree on idempotence");
            check(aciComplementLeft.equivalentTo(aciComplementRight),
                    "Boolean complement recognition must consume certified ACI identity");
            check(CanonicalAlloyPipeline.distance(
                            aciComplementLeft, aciComplementRight) == 0,
                    "certified and repair observations agree for an ACI-normalized complement");
            check(aciCommutativeComplementLeft.equivalentTo(aciComplementRight)
                            && CanonicalAlloyPipeline.distance(
                                    aciCommutativeComplementLeft,
                                    aciComplementRight) == 0,
                    "Boolean complement recognition must consume certified commutativity");
            check(aciAssociativeComplementLeft.equivalentTo(aciComplementRight)
                            && CanonicalAlloyPipeline.distance(
                                    aciAssociativeComplementLeft,
                                    aciComplementRight) == 0,
                    "Boolean complement recognition must consume certified associativity");
            check(aciSelfMinusLeft.equivalentTo(aciSelfMinusRight)
                            && CanonicalAlloyPipeline.distance(
                                    aciSelfMinusLeft, aciSelfMinusRight) == 0,
                    "self-difference recognition must consume certified ACI identity");
            check(!aciNearMiss.equivalentTo(aciComplementRight),
                    "different relational operators must not become Boolean complements");
            check(aciSlotComplementLeft.equivalentTo(aciSlotComplementRight)
                            && CanonicalAlloyPipeline.distance(
                                    aciSlotComplementLeft,
                                    aciSlotComplementRight) == 0,
                    "ACI complement comparison must compose bound-slot invocations: "
                            + aciSlotComplementLeft.digest() + " != "
                            + aciSlotComplementRight.digest());
            check(!aciSlotNearMiss.equivalentTo(aciSlotNearMissRight),
                    "ACI singleton comparison must not identify distinct bound slots");
            check(hasOperatorOutput(integerPlus, "ALLOY/PLUS", GraphType.INT),
                    "source + over Int-valued relations must remain relational union");
            check(!hasOperatorOutput(integerPlus, "ALLOY/IPLUS", GraphType.INT),
                    "an Int carrier must not relabel relational + as integer arithmetic");
            check(integerPlusDuplicate.equivalentTo(integerPlusBare),
                    "relational union over singleton Int relations remains idempotent");
            check(hasOperatorOutput(integerMinus, "ALLOY/MINUS", GraphType.INT)
                            && !hasOperatorOutput(
                                    integerMinus, "ALLOY/IMINUS", GraphType.INT),
                    "source - over Int-valued relations must remain set difference");
            check(hasOperatorOutput(
                            integerArithmeticPlus, "ALLOY/IPLUS", GraphType.INT),
                    "fun/add must retain the parser's integer-addition identity");
            check(!hasOperatorOutput(
                            integerArithmeticPlus, "ALLOY/PLUS", GraphType.INT),
                    "integer addition must not receive relational Set policy");
            check(!integerArithmeticPlus.equivalentTo(integerArithmeticNearMiss)
                            && CanonicalAlloyPipeline.distance(
                                    integerArithmeticPlus,
                                    integerArithmeticNearMiss) > 0,
                    "duplicate integer-addition operands must not collapse idempotently");
            check(integerArithmeticNested.semanticArtifact() != null,
                    "nested integer arithmetic must retain every literal occurrence type");
            check(intSetUnion.equivalentTo(intSetBare)
                            && CanonicalAlloyPipeline.distance(
                                    intSetUnion, intSetBare) == 0,
                    "Int + Int must retain relational union idempotence");
            check(intSetDifference.equivalentTo(booleanTruth)
                            && CanonicalAlloyPipeline.distance(
                                    intSetDifference, booleanTruth) == 0,
                    "Int - Int must retain relational self-difference");
            check(intersectUniv.equivalentTo(unionBare)
                            && CanonicalAlloyPipeline.distance(
                                    intersectUniv, unionBare) == 0,
                    "intersection with authenticated univ must retain its operand");
            check(minusNone.equivalentTo(unionBare)
                            && CanonicalAlloyPipeline.distance(
                                    minusNone, unionBare) == 0,
                    "difference by authenticated none must retain its left operand");
            check(plusUniv.equivalentTo(univBare)
                            && CanonicalAlloyPipeline.distance(
                                    plusUniv, univBare) == 0,
                    "union with authenticated univ must collapse to univ");
            check(minusUniv.equivalentTo(booleanTruth)
                            && noneMinus.equivalentTo(booleanTruth)
                            && CanonicalAlloyPipeline.distance(
                                    minusUniv, booleanTruth) == 0
                            && CanonicalAlloyPipeline.distance(
                                    noneMinus, booleanTruth) == 0,
                    "difference by univ and difference from none must be empty");
            check(hasRelationFamilyOperatorOutput(
                            heterogeneousIntUnion, "ALLOY/PLUS")
                            && !hasOperatorOutput(
                                    heterogeneousIntUnion,
                                    "ALLOY/PLUS",
                                    GraphType.INT),
                    "Int + S must retain both alternatives instead of collapsing to Int");
            check(!heterogeneousIntUnion.equivalentTo(intUnionIdentity)
                            && CanonicalAlloyPipeline.distance(
                                    heterogeneousIntUnion,
                                    intUnionIdentity) > 0,
                    "heterogeneous Int union must not certify as the Int-only identity");
            check(repeatedNestedUnionLeft.equivalentTo(repeatedNestedUnionRight)
                            && CanonicalAlloyPipeline.distance(
                                    repeatedNestedUnionLeft,
                                    repeatedNestedUnionRight) == 0,
                    "both repeated occurrences of a nested ACI operand must remain complete");
            check(distinctNestedUnionLeft.equivalentTo(distinctNestedUnionRight),
                    "two distinct nested same-operator occurrences must retain separate visits: "
                            + distinctNestedUnionLeft.digest() + " != "
                            + distinctNestedUnionRight.digest());
            check(!distinctNestedUnionLeft.equivalentTo(distinctNestedUnionNearMiss),
                    "same-operator occurrence tracking must not conflate distinct operands");
            check(subtypeWidenedUnionLeft.equivalentTo(
                            subtypeWidenedUnionRight)
                            && CanonicalAlloyPipeline.distance(
                                    subtypeWidenedUnionLeft,
                                    subtypeWidenedUnionRight) == 0,
                    "relational union associativity must consume authenticated subtype widening");
            check(subtypeAbsorptionLeft.equivalentTo(subtypeAbsorptionRight)
                            && subtypeAbsorptionNested.equivalentTo(
                                    subtypeAbsorptionRight)
                            && CanonicalAlloyPipeline.distance(
                                    subtypeAbsorptionLeft,
                                    subtypeAbsorptionRight) == 0
                            && CanonicalAlloyPipeline.distance(
                                    subtypeAbsorptionNested,
                                    subtypeAbsorptionRight) == 0,
                    "a full parent signature must absorb every parser-certified subtype family");
            check(!subtypeSiblingOnly.equivalentTo(subtypeAbsorptionRight),
                    "siblings without their full parent must not collapse to the parent");
            check(subsetAbsorptionLeft.equivalentTo(unionBare)
                            && CanonicalAlloyPipeline.distance(
                                    subsetAbsorptionLeft, unionBare) == 0,
                    "subset signatures must be absorbed by their authenticated full parent");
            check(nestedSubsetAbsorptionLeft.equivalentTo(
                            nestedSubsetAbsorptionRight)
                            && nestedSubsetAbsorptionGrouped.equivalentTo(
                                    nestedSubsetAbsorptionRight)
                            && CanonicalAlloyPipeline.distance(
                                    nestedSubsetAbsorptionLeft,
                                    nestedSubsetAbsorptionRight) == 0
                            && CanonicalAlloyPipeline.distance(
                                    nestedSubsetAbsorptionGrouped,
                                    nestedSubsetAbsorptionRight) == 0,
                    "the parser declaration DAG must authenticate transitive subset carriers");
            check(!nestedSubsetSiblingOnly.equivalentTo(
                            nestedSubsetAbsorptionRight),
                    "a sibling subset signature must not be absorbed by another subset carrier");
            check(multiSubsetCommonCarrierLeft.equivalentTo(
                            multiSubsetCommonCarrierRight)
                            && CanonicalAlloyPipeline.distance(
                                    multiSubsetCommonCarrierLeft,
                                    multiSubsetCommonCarrierRight) == 0,
                    "all branches of a subset declaration may prove one common carrier");
            check(!multiSubsetSingleBranchLeft.equivalentTo(
                            multiSubsetSingleBranchRight),
                    "one branch of a union-valued subset declaration is not its full carrier");
            check(!typedExpressionNotCarrierLeft.equivalentTo(
                            typedExpressionNotCarrierRight),
                    "an arbitrary parent-typed expression must not act as the full signature");
            check(typedExpressionWithCarrier.equivalentTo(
                            subtypeAbsorptionRight)
                            && CanonicalAlloyPipeline.distance(
                                    typedExpressionWithCarrier,
                            subtypeAbsorptionRight) == 0,
                    "a full parent signature must absorb every typed subrelation in its union region");
            check(abstractCoverUnion.equivalentTo(abstractCoverParent)
                            && abstractCoverWithSubrelation.equivalentTo(
                                    abstractCoverParent)
                            && CanonicalAlloyPipeline.distance(
                                    abstractCoverUnion,
                                    abstractCoverParent) == 0,
                    "all direct extends children must reconstruct their abstract carrier");
            check(!abstractCoverWithUnrelated.equivalentTo(
                            abstractCoverParent)
                            && CanonicalAlloyPipeline.distance(
                                    abstractCoverWithUnrelated,
                                    abstractCoverParent) > 0,
                    "an unrelated union member must block abstract-cover reconstruction");
            check(abstractProductRightCoverUnion.equivalentTo(
                            abstractProductRightCoverParent)
                            && CanonicalAlloyPipeline.distance(
                                    abstractProductRightCoverUnion,
                                    abstractProductRightCoverParent) == 0
                            && abstractProductLeftCoverUnion.equivalentTo(
                                    abstractProductLeftCoverParent)
                            && CanonicalAlloyPipeline.distance(
                                    abstractProductLeftCoverUnion,
                                    abstractProductLeftCoverParent) == 0,
                    "a complete abstract cover must lift through either Cartesian coordinate");
            check(!abstractProductDiagonal.equivalentTo(abstractProductFull),
                    "diagonal products must not invent the missing Cartesian cross terms");
            check(!abstractProductWithUnrelated.equivalentTo(
                            abstractProductRightCoverParent),
                    "an unrelated product alternative must block abstract-cover lifting");
            check(abstractProductTernaryCoverUnion.equivalentTo(
                            abstractProductTernaryCoverParent)
                            && CanonicalAlloyPipeline.distance(
                                    abstractProductTernaryCoverUnion,
                                    abstractProductTernaryCoverParent) == 0,
                    "a complete cover must lift through a variadic ARROW coordinate");
            check(abstractProductFullGridUnion.equivalentTo(
                            abstractProductFull)
                            && CanonicalAlloyPipeline.distance(
                                    abstractProductFullGridUnion,
                                    abstractProductFull) == 0,
                    "complete Cartesian grids must lift covers in multiple coordinates");
            check(!abstractProductPartialGridUnion.equivalentTo(
                            abstractProductFull)
                            && CanonicalAlloyPipeline.distance(
                                    abstractProductPartialGridUnion,
                                    abstractProductFull) > 0,
                    "a partial Cartesian grid must not invent its missing product cell");
            check(abstractProductIntCoverUnion.equivalentTo(
                            abstractProductIntCoverParent)
                            && CanonicalAlloyPipeline.distance(
                                    abstractProductIntCoverUnion,
                                    abstractProductIntCoverParent) == 0,
                    "parser-authenticated Int must remain a unary set factor in products");
            check(abstractProductIntLeftCoverUnion.equivalentTo(
                            abstractProductIntLeftCoverParent)
                            && CanonicalAlloyPipeline.distance(
                                    abstractProductIntLeftCoverUnion,
                                    abstractProductIntLeftCoverParent) == 0,
                    "authenticated Int product evidence must be coordinate symmetric");
            check(ordinaryProductRightDistributed.equivalentTo(
                            ordinaryProductRightFactored)
                            && CanonicalAlloyPipeline.distance(
                                    ordinaryProductRightDistributed,
                                    ordinaryProductRightFactored) == 0
                            && ordinaryProductLeftDistributed.equivalentTo(
                                    ordinaryProductLeftFactored)
                            && CanonicalAlloyPipeline.distance(
                                    ordinaryProductLeftDistributed,
                                    ordinaryProductLeftFactored) == 0,
                    "ordinary Cartesian products must factor unions in either coordinate");
            check(ordinaryProductTernaryDistributed.equivalentTo(
                            ordinaryProductTernaryFactored)
                            && CanonicalAlloyPipeline.distance(
                                    ordinaryProductTernaryDistributed,
                                    ordinaryProductTernaryFactored) == 0,
                    "ordinary product factoring must preserve variadic source order");
            check(ordinaryProductFullGrid.equivalentTo(
                            ordinaryProductFullFactored)
                            && CanonicalAlloyPipeline.distance(
                                    ordinaryProductFullGrid,
                                    ordinaryProductFullFactored) == 0,
                    "a complete ordinary Cartesian grid must factor every coordinate");
            check(!ordinaryProductPartialGrid.equivalentTo(
                            ordinaryProductFullFactored)
                            && CanonicalAlloyPipeline.distance(
                                    ordinaryProductPartialGrid,
                                    ordinaryProductFullFactored) > 0
                            && !ordinaryProductDiagonal.equivalentTo(
                                    ordinaryProductFullFactored),
                    "partial and diagonal product grids must not invent missing cells");
            check(abstractNestedUnion.equivalentTo(abstractNestedInner),
                    "a nested abstract child's complete branches must reconstruct that child");
            check(abstractNestedInner.equivalentTo(abstractNestedOuter),
                    "a singleton abstract extension must reconstruct its parent");
            check(CanonicalAlloyPipeline.distance(
                            abstractNestedUnion,
                            abstractNestedOuter) == 0,
                    "abstract cover normalization must close through singleton abstract ancestors");
            check(abstractSingleChild.equivalentTo(abstractSingleParent),
                    "one direct child of an abstract signature must equal its parent");
            check(!abstractMissingBranch.equivalentTo(abstractCoverParent),
                    "an abstract carrier must retain every direct extension branch");
            check(!nonAbstractChildren.equivalentTo(nonAbstractParent),
                    "children do not cover a non-abstract parent");
            check(!abstractSubsetOnly.equivalentTo(abstractSubsetParent),
                    "an in-subset does not activate Alloy's abstract extends cover");
            check(enumCoverUnion.equivalentTo(enumCoverParent)
                            && CanonicalAlloyPipeline.distance(
                                    enumCoverUnion, enumCoverParent) == 0,
                    "an enum must normalize as the complete union of its atoms");
            check(idenLeft.equivalentTo(idenBare)
                            && idenRight.equivalentTo(idenBare)
                            && CanonicalAlloyPipeline.distance(
                                    idenLeft, idenBare) == 0
                            && CanonicalAlloyPipeline.distance(
                                    idenRight, idenBare) == 0,
                    "Alloy iden must be a two-sided relational JOIN identity");
            check(idenMiddle.equivalentTo(idenMiddleBare)
                            && CanonicalAlloyPipeline.distance(
                                    idenMiddle, idenMiddleBare) == 0,
                    "JOIN identity elimination must work inside a flattened chain");
            check(idenAll.equivalentTo(idenAllBare)
                            && CanonicalAlloyPipeline.distance(
                                    idenAll, idenAllBare) == 0,
                    "a JOIN chain containing only iden must retain one identity");
            check(!joinNonIdentity.equivalentTo(idenBare),
                    "ordinary JOIN operands must not be discarded as identity relations");
            check(transposeIden.equivalentTo(idenAllBare)
                            && closureIden.equivalentTo(idenAllBare)
                            && rclosureIden.equivalentTo(idenAllBare)
                            && CanonicalAlloyPipeline.distance(
                                    transposeIden, idenAllBare) == 0
                            && CanonicalAlloyPipeline.distance(
                                    closureIden, idenAllBare) == 0
                            && CanonicalAlloyPipeline.distance(
                                    rclosureIden, idenAllBare) == 0,
                    "transpose and both closure operators must fix Alloy iden");
            check(doubleTranspose.equivalentTo(transposeBare)
                            && CanonicalAlloyPipeline.distance(
                                    doubleTranspose, transposeBare) == 0,
                    "relational transpose must be involutive");
            check(transposeArrow.equivalentTo(transposeArrowReversed)
                            && CanonicalAlloyPipeline.distance(
                                    transposeArrow, transposeArrowReversed) == 0,
                    "transpose must reverse a binary Cartesian product");
            check(!transposeArrow.equivalentTo(transposeArrowWrongOrder),
                    "transpose must not preserve Cartesian-product operand order");
            check(transposeUnionLeft.equivalentTo(transposeUnionRight)
                            && CanonicalAlloyPipeline.distance(
                                    transposeUnionLeft,
                                    transposeUnionRight) == 0
                            && transposeIntersectLeft.equivalentTo(
                                    transposeIntersectRight)
                            && CanonicalAlloyPipeline.distance(
                                    transposeIntersectLeft,
                                    transposeIntersectRight) == 0
                            && transposeMinusLeft.equivalentTo(
                                    transposeMinusRight)
                            && CanonicalAlloyPipeline.distance(
                                    transposeMinusLeft,
                                    transposeMinusRight) == 0,
                    "transpose must distribute through union, intersection, and difference");
            check(!transposeIntersectLeft.equivalentTo(
                            transposeContainerNearMiss)
                            && !transposeMinusLeft.equivalentTo(
                                    transposeContainerNearMiss),
                    "transpose distribution must preserve its relational container operator");
            check(transposeProductUnionLeft.equivalentTo(
                            transposeProductUnionRight)
                            && CanonicalAlloyPipeline.distance(
                                    transposeProductUnionLeft,
                                    transposeProductUnionRight) == 0,
                    "transpose distribution must compose with Cartesian-product reversal");
            check(transposeSlotUnionLeft.equivalentTo(
                            transposeSlotUnionRight)
                            && CanonicalAlloyPipeline.distance(
                                    transposeSlotUnionLeft,
                                    transposeSlotUnionRight) == 0,
                    "transpose distribution must preserve quantified relation-slot invocations");
            check(closureClosure.equivalentTo(closureBare)
                            && rclosureRclosure.equivalentTo(rclosureBare)
                            && closureRclosure.equivalentTo(rclosureBare)
                            && rclosureClosure.equivalentTo(rclosureBare)
                            && CanonicalAlloyPipeline.distance(
                                    closureClosure, closureBare) == 0
                            && CanonicalAlloyPipeline.distance(
                                    rclosureRclosure, rclosureBare) == 0
                            && CanonicalAlloyPipeline.distance(
                                    closureRclosure, rclosureBare) == 0
                            && CanonicalAlloyPipeline.distance(
                                    rclosureClosure, rclosureBare) == 0,
                    "nested transitive/reflexive closure combinations must reach their fixed point");
            check(!closureBare.equivalentTo(rclosureBare),
                    "transitive closure and reflexive-transitive closure remain distinct");
            check(emptySubsetUnary.equivalentTo(booleanTruth)
                            && emptySubsetBinary.equivalentTo(booleanTruth)
                            && CanonicalAlloyPipeline.distance(
                                    emptySubsetUnary, booleanTruth) == 0
                            && CanonicalAlloyPipeline.distance(
                                    emptySubsetBinary, booleanTruth) == 0,
                    "the empty relation must be a subset at every exact matching arity");
            check(emptyNotSubsetUnary.equivalentTo(booleanFalse)
                            && emptyNotSubsetBinary.equivalentTo(booleanFalse)
                            && emptyJoin.equivalentTo(booleanFalse)
                            && CanonicalAlloyPipeline.distance(
                                    emptyNotSubsetUnary, booleanFalse) == 0
                            && CanonicalAlloyPipeline.distance(
                                    emptyNotSubsetBinary, booleanFalse) == 0
                            && CanonicalAlloyPipeline.distance(
                                    emptyJoin, booleanFalse) == 0,
                    "empty JOIN/ARROW results and negated empty subset must normalize soundly");
            check(equalityComplementLeft.equivalentTo(equalityComplementRight)
                            && CanonicalAlloyPipeline.distance(
                                    equalityComplementLeft,
                                    equalityComplementRight) == 0,
                    "complement recognition must consume certified equality commutativity");
            check(!equalityComplementNearMiss.equivalentTo(equalityComplementRight),
                    "commutative complement comparison must preserve its operand multiset");
            check(equalitySlotComplementLeft.equivalentTo(equalitySlotComplementRight)
                            && CanonicalAlloyPipeline.distance(
                                    equalitySlotComplementLeft,
                                    equalitySlotComplementRight) == 0,
                    "commutative equality complement must compose bound-slot invocations");
            check(!equalitySlotComplementNearMiss.equivalentTo(
                            equalitySlotComplementRight),
                    "commutative equality complement must distinguish bound slots");
            check(nestedIteFormula.equivalentTo(expandedIteFormula)
                            && CanonicalAlloyPipeline.distance(
                                    nestedIteFormula, expandedIteFormula) == 0,
                    "nested formula ITE occurrences must survive branch normalization");
            check(nestedIteExpression.semanticArtifact() != null,
                    "nested expression ITE occurrences must retain all three branches");
            check(modularIntegerPlusDuplicate.equivalentTo(modularIntegerPlusBare),
                    "relational union idempotence is independent of integer arithmetic profile");
            check(nestedSubtypeLeft.equivalentTo(nestedSubtypeRight),
                    "binder permutations must re-normalize ACI operands after acting");
            check(CanonicalAlloyPipeline.distance(
                            nestedSubtypeLeft, nestedSubtypeRight) == 0,
                    "nested and grouped subtype binders must retain distance zero");
            check(redundantDomainGuardLeft.equivalentTo(redundantDomainGuardRight),
                    "ACI projection must preserve certified idempotence of a repeated domain guard");
            check(CanonicalAlloyPipeline.distance(
                            redundantDomainGuardLeft, redundantDomainGuardRight) == 0,
                    "a repeated domain guard under implication rewriting must remain in the zero kernel");
            check(nestedUnionLeft.equivalentTo(nestedUnionRight),
                    "relational union reassociation must remain certified");
            check(nestedUnionLeft.semanticArtifact().flatConstructions().stream()
                            .flatMap(certificate -> certificate.splices().stream())
                            .anyMatch(splice -> splice.outerArity() == 2
                                    && splice.nestedArity() == 2
                                    && (splice.position() == 0 || splice.position() == 1)),
                    "parsed nested union must retain its exact associative splice witness");
            CertifiedSemanticArtifact nestedUnionArtifact =
                    nestedUnionLeft.semanticArtifact();
            List<FlatConstructionCertificate> missingFlat = new ArrayList<>(
                    nestedUnionArtifact.flatConstructions());
            check(!missingFlat.isEmpty(),
                    "nested union fixture must contain concrete flat evidence");
            FlatConstructionCertificate firstFlat = missingFlat.remove(0);
            expectThrows(IllegalArgumentException.class, () ->
                    copyWithFlatConstructions(nestedUnionArtifact, missingFlat));
            List<FlatConstructionCertificate> extraneousFlat = new ArrayList<>(
                    nestedUnionArtifact.flatConstructions());
            extraneousFlat.add(firstFlat);
            expectThrows(IllegalArgumentException.class, () ->
                    copyWithFlatConstructions(nestedUnionArtifact, extraneousFlat));
            check(equalityOrderLeft.equivalentTo(equalityOrderRight),
                    "fixed equality commutativity must remain certified");
            check(equalityOrderLeft.semanticArtifact().containerConstructions().stream()
                            .filter(certificate -> certificate.target().operator().operator()
                                    .equals("ALLOY/EQUALS"))
                            .anyMatch(certificate -> certificate.premises().stream()
                                    .filter(ContainerLawCertificate.class::isInstance)
                                    .map(ContainerLawCertificate.class::cast)
                                    .anyMatch(law -> law.law()
                                            == ContainerLawCertificate.Law.COMMUTATIVITY)),
                    "parsed equality must retain its exact two-occurrence C quotient proof");
            ContainerConstructionCertificate disjointConstruction =
                    duplicateDisjoint.semanticArtifact().containerConstructions().stream()
                            .filter(certificate -> certificate.target().operator().operator()
                                    .equals("ALLOY/DISJOINT"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError(
                                    "parsed disjoint has no concrete container certificate"));
            check(disjointConstruction.containerTrace().inputOccurrences().size() == 3,
                    "disjoint source trace must retain every duplicate occurrence");
            check(disjointConstruction.containerTrace().outputOccurrences().size() == 3,
                    "disjoint bag normalization must not deduplicate operands");
            check(!disjointConstruction.containerTrace().deduplicated(),
                    "disjoint arguments have C but no I evidence");
            check(heterogeneousDisjoint.semanticArtifact().containerConstructions().stream()
                            .anyMatch(certificate -> certificate.operator().operator()
                                    .equals("ALLOY/DISJOINT")),
                    "valid heterogeneous disjoint operands retain concrete C evidence");
            List<ContainerConstructionCertificate> duplicateContainerEvidence =
                    new ArrayList<>(
                            duplicateDisjoint.semanticArtifact().containerConstructions());
            duplicateContainerEvidence.add(disjointConstruction);
            expectThrows(IllegalArgumentException.class, () ->
                    copyWithContainerConstructions(
                            duplicateDisjoint.semanticArtifact(),
                            duplicateContainerEvidence));
            GraphType sigS = GraphType.constructor("AlloySig:S");
            GraphType sigT = GraphType.constructor("AlloySig:T");
            GraphType sigState = GraphType.constructor("AlloySig:State");
            GraphType sigEvent = GraphType.constructor("AlloySig:Event");
            DependentChainCertificate binaryArrow = dependentChain(
                    binaryArrowType, DependentChainKind.ARROW);
            check(binaryArrow.target().outputType().equals(
                            GraphType.relation(Arrays.asList(sigS, sigT))),
                    "dependent ARROW preserves exact binary relation columns");
            check(binaryArrow.operandTypes().equals(List.of(
                            GraphType.relation(sigS), GraphType.relation(sigT))),
                    "dependent ARROW retains independently checked source columns");
            check(binaryArrow.source().leaves().size() == 2
                            && ((SeqPort) binaryArrow.target().ports().get(0))
                                    .schema().isDependent(),
                    "dependent ARROW uses one ordered positional Seq");
            check(binaryArrow.target().operator().containerLaws().values().stream()
                            .noneMatch(laws -> laws.associative()
                                    || laws.commutative()
                                    || laws.idempotent()
                                    || laws.hasUnit()),
                    "dependent ARROW Seq carries no A/C/I/unit quotient license");
            DependentChainCertificate intArrow = dependentChain(
                    intArrowType, DependentChainKind.ARROW);
            check(intArrow.target().outputType().equals(
                            GraphType.relation(GraphType.INT, sigS)),
                    "parser-origin Int receives its unary relation view in ARROW");
            check(intArrow.source().leafInputs().get(0).typeRule()
                            == DependentChainTheory.LeafTypeRule.PRIMITIVE_SET_SINGLETON,
                    "parser-origin Int retains an explicit primitive singleton proof");
            check(hasOutputType(
                            binaryJoinType,
                            GraphType.relation(Arrays.asList(sigEvent, sigState))),
                    "JOIN preserves the exact Event->State result columns");
            check(hasOutputType(
                            binaryJoinType,
                            GraphType.relation(Arrays.asList(sigState, sigEvent, sigState))),
                    "field leaves preserve their exact ternary owner-prefixed type");
            check(!binaryArrowType.equivalentTo(reversedArrowType),
                    "relation column order participates in certified identity");
            check(arrowAssocLeft.equivalentTo(arrowAssocRight)
                            && CanonicalAlloyPipeline.distance(
                                    arrowAssocLeft, arrowAssocRight) == 0,
                    "ARROW reassociation must share one certified ordered-chain target");
            assertReassociatedChain(
                    arrowAssocLeft, arrowAssocRight, DependentChainKind.ARROW);
            check(joinAssocLeft.equivalentTo(joinAssocRight)
                            && CanonicalAlloyPipeline.distance(
                                    joinAssocLeft, joinAssocRight) == 0,
                    "JOIN reassociation must share one certified ordered-chain target");
            assertReassociatedChain(
                    joinAssocLeft, joinAssocRight, DependentChainKind.JOIN);
            check(rightUnivJoinLeft.equivalentTo(rightUnivJoinRight)
                            && CanonicalAlloyPipeline.distance(
                                    rightUnivJoinLeft, rightUnivJoinRight) == 0,
                    "(x.trans).univ and x.(trans.univ) must share one certified target");
            assertReassociatedChain(
                    rightUnivJoinLeft,
                    rightUnivJoinRight,
                    DependentChainKind.JOIN);
            check(leftUnivJoinLeft.equivalentTo(leftUnivJoinRight)
                            && CanonicalAlloyPipeline.distance(
                                    leftUnivJoinLeft, leftUnivJoinRight) == 0,
                    "(univ.trans).x and univ.(trans.x) must share one certified target");
            assertReassociatedChain(
                    leftUnivJoinLeft,
                    leftUnivJoinRight,
                    DependentChainKind.JOIN);
            DependentChainCertificate subtypeCertificate = subtypeBoundaryJoin
                    .semanticArtifact().dependentChainConstructions().stream()
                    .filter(certificate -> certificate.source().kind()
                            == DependentChainKind.JOIN)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "subtype-overlap JOIN lacks a dependent Seq certificate"));
            check(subtypeCertificate.source().boundaryCorrespondence().rule()
                            == is.fivefivefive.CanDis.theory
                                    .DependentBoundaryCorrespondence.Rule
                                            .RIGHT_SUBTYPE_OF_LEFT,
                    "Product.(Component->Position) must carry the explicit "
                            + "Component-subtype-of-Product correspondence");
            check(subtypeCertificate.source().boundaryCorrespondence()
                            .witnessPath().stream().map(GraphType::symbol).toList()
                            .equals(List.of(
                                    "AlloySig:Component", "AlloySig:Product")),
                    "the subtype JOIN witness must retain its exact direct-parent path");
            DependentChainCertificate familyCertificate = dependentChain(
                    relationFamilyJoin, DependentChainKind.JOIN);
            long familyOverlaps = familyCertificate.source()
                    .combinationCases().stream()
                    .filter(proof -> proof.decision()
                            == is.fivefivefive.CanDis.theory.DependentTypeDag
                                    .CombinationDecision.JOIN_OVERLAP)
                    .count();
            long familyDisjoint = familyCertificate.source()
                    .combinationCases().stream()
                    .filter(proof -> proof.decision()
                            == is.fivefivefive.CanDis.theory.DependentTypeDag
                                    .CombinationDecision.JOIN_DISJOINT)
                    .count();
            check(familyCertificate.source().outputTypeDag()
                            .alternatives().size() == 2
                            && familyCertificate.source()
                                    .combinationCases().size() == 4
                            && familyOverlaps == 2
                            && familyDisjoint == 2,
                    "the Alloy adapter retains correlated families and a complete JOIN matrix");
            check(((SeqPort) familyCertificate.target().ports().get(0))
                            .elements().size() == 2,
                    "the relation-family JOIN still uses one ordered two-operand Seq");
            DependentChainCertificate emptyJoinCertificate = dependentChain(
                    disjointBoundaryJoin, DependentChainKind.JOIN);
            check(emptyJoinCertificate.source().outputType()
                            .equals(AlloyTypeBridge.emptyRelation(2))
                            && emptyJoinCertificate.source().outputTypeDag()
                                    .alternatives().isEmpty()
                            && emptyJoinCertificate.source()
                                    .combinationCases().size() == 1
                            && emptyJoinCertificate.source()
                                    .combinationCases().get(0).decision()
                                    == is.fivefivefive.CanDis.theory.DependentTypeDag
                                            .CombinationDecision.JOIN_DISJOINT,
                    "a parser-valid all-disjoint JOIN becomes one certified typed-empty Seq");
            check(((SeqPort) emptyJoinCertificate.target().ports().get(0))
                            .elements().size() == 2,
                    "typed-empty JOIN normalization preserves both source operands");
            DependentChainCertificate emptyIntersectCertificate = dependentChain(
                    emptyIntersectJoin, DependentChainKind.JOIN);
            check(emptyIntersectCertificate.source().leafInputs().get(0)
                            .outputTypeDag().alternatives().isEmpty()
                            && emptyIntersectCertificate.source().leafInputs().get(0)
                                    .outputTypeDag().arity() == 1,
                    "a parser-empty INTERSECT leaf is independently derived as typed empty");
            DependentChainCertificate emptyUnionCertificate = dependentChain(
                    emptyUnionJoin, DependentChainKind.JOIN);
            check(emptyUnionCertificate.source().leafInputs().get(0)
                            .outputTypeDag().alternatives().isEmpty()
                            && emptyUnionCertificate.source().leafInputs().get(0)
                                    .outputTypeDag().arity() == 1,
                    "a parser-empty UNION leaf retains recursively derived empty evidence");
            check(parameterJoinLeft.equivalentTo(parameterJoinRight),
                    "bound-parameter JOIN reassociation must receive symmetric evidence");
            check(CanonicalAlloyPipeline.distance(
                            parameterJoinLeft, parameterJoinRight) == 0,
                    "bound-parameter JOIN reassociation must remain in the zero kernel");
            assertReassociatedChain(
                    parameterJoinLeft,
                    parameterJoinRight,
                    DependentChainKind.JOIN);
            check(witnessedCarrierLeft.equivalentTo(witnessedCarrierRight),
                    "a preceding primitive binder must discharge an unnecessary relativized carrier: "
                            + witnessedCarrierLeft.digest() + " != "
                            + witnessedCarrierRight.digest());
            check(CanonicalAlloyPipeline.distance(
                            witnessedCarrierLeft, witnessedCarrierRight) == 0,
                    "equivalent implication-prenex forms must remain in the certified zero kernel");
            check(matrixBinderCount(
                            visitor, "witnessedCarrierLeft", QuantiVar.Quantifier.SOME, "Person")
                            == 1,
                    "the witnessed existential must retain its primitive Person repair type");
            check(commutativeBinderLeft.equivalentTo(commutativeBinderRight),
                    "binary inequality commutativity must survive a certified binder permutation: "
                            + commutativeBinderLeft.digest() + " != "
                            + commutativeBinderRight.digest());
            check(CanonicalAlloyPipeline.distance(
                            commutativeBinderLeft, commutativeBinderRight) == 0,
                    "commutative inequality and alpha alignment must share the zero kernel");
            check(localComprehensionLeft.equivalentTo(localComprehensionRight),
                    "beta-expanded repeated comprehensions must retain certified equality");
            check(CanonicalAlloyPipeline.distance(
                            localComprehensionLeft, localComprehensionRight) == 0,
                    "certified local comprehension alpha names must not cost matrix edits");
            check(!localPermutationLeft.equivalentTo(localPermutationRight),
                    "comprehension result columns must retain their positional identities");
            check(CanonicalAlloyPipeline.distance(
                            localPermutationLeft, localPermutationRight) > 0,
                    "swapping comprehension columns must remain outside the zero kernel");
            check(!namedRefFirst.equivalentTo(namedRefLast),
                    "distinct non-temporal reference symbols must not share a certified observation");
            check(CanonicalAlloyPipeline.distance(namedRefFirst, namedRefLast) == 1,
                    "changing ordering/first to ordering/last must cost one matrix edit");
            check(CanonicalAlloyPipeline.distance(
                            temporalBinderTarget, temporalBinderWrongTarget) > 0,
                    "an inherited temporal variable must reuse its owner's alpha mapping");
            check(temporalBinderTarget.equivalentTo(temporalBinderRenamed),
                    "a consistent binder permutation across temporal phases must remain certified");
            check(CanonicalAlloyPipeline.distance(
                            temporalBinderTarget, temporalBinderRenamed) == 0,
                    "consistent temporal alpha-renaming must retain repair distance zero");
            check(phaseLocalTemporalBinder.repairView().phases().stream()
                            .filter(phase -> phase.bindings().stream().anyMatch(binding ->
                                    binding.role()
                                            == is.fivefivefive.CanDis.metric.RepairView.BindingRole
                                                    .LOCAL_INHERITED))
                            .count() == 2,
                    "both ONCE and ALWAYS sibling phases must import the same outer local binder");
            check(phaseLocalTemporalBinder.equivalentTo(
                            phaseLocalTemporalBinderRenamed)
                            && CanonicalAlloyPipeline.distance(
                                    phaseLocalTemporalBinder,
                                    phaseLocalTemporalBinderRenamed) == 0,
                    "phase-local temporal imports must preserve alpha equivalence");
            check(!phaseLocalTemporalBinder.equivalentTo(
                            phaseLocalTemporalBinderWrong)
                            && CanonicalAlloyPipeline.distance(
                                    phaseLocalTemporalBinder,
                                    phaseLocalTemporalBinderWrong) > 0,
                    "phase-local rebinding must not erase a changed temporal use");
            check(phaseLocalRepeatedReference.repairView().phases().stream()
                            .filter(phase -> phase.bindings().stream().anyMatch(binding ->
                                    binding.role()
                                            == is.fivefivefive.CanDis.metric.RepairView.BindingRole
                                                    .LOCAL_INHERITED))
                            .count() == 2,
                    "IFF expansion may repeat an exact temporal reference without "
                            + "creating a conflicting phase-local scope");
            checkDuplicateIdDependentChainTransfer();
            checkDependentSourceOccurrenceBinding(Opcode.ARROW);
            checkDependentSourceOccurrenceBinding(Opcode.JOIN);
            checkFrozenProjectionOwnership();
            checkConcurrentProjectionFreeze();
            checkUnaryInteriorJoinDoesNotFlatten();
            checkUncertifiedSourceUnionRejected();
            checkConcreteDependentMismatchRejected();
            check(CanonicalAlloyPipeline.distance(alphaLeft, positive)
                            == CanonicalAlloyPipeline.distance(alphaRight, positive),
                    "repair distance must be invariant under certified alpha equivalence");
            check(CanonicalAlloyPipeline.distance(aciLeft, negative)
                            == CanonicalAlloyPipeline.distance(aciRight, negative),
                    "repair distance must be invariant under certified ACI equivalence");
            checkMetricParity(visitor, "alphaNearLeft", "alphaNearRight");
            checkMetricParity(visitor, "aciNearLeft", "aciNearRight");
            checkMetricParity(visitor, "binderAll", "binderSome");
            checkMetricParity(visitor, "temporalLeft", "temporalRight");
            checkMetricParity(visitor, "unequalAlphaLeft", "unequalAlphaRight");
            checkMetricParity(
                    visitor, "heterogeneousOrderLeft", "heterogeneousOrderRight");
            checkMetricParity(visitor, "domainAciLeft", "domainAciRight");
            checkMetricParity(
                    visitor, "redundantDomainGuardLeft", "redundantDomainGuardRight");
            long scopedMaximumS = matrixBinderCount(
                    visitor, "scopedMaximum", QuantiVar.Quantifier.ALL, "S");
            check(scopedMaximumS == 3,
                    "five sibling universal scopes need only three S coordinates; found "
                            + scopedMaximumS);
            check(matrixBinderCount(visitor, "scopedMaximum", QuantiVar.Quantifier.ALL, "T") == 0
                            && hasLocalQuantifierCarrier(
                                    visitor, "scopedMaximum", Opcode.FORALL, "T"),
                    "the nested differently typed coordinate must remain local and live");
            check(matrixBinderCount(visitor, "nestedAll", QuantiVar.Quantifier.ALL, "S") == 2,
                    "continuously nested universal declarations must not reuse a live coordinate");
            check(matrixBinderCount(visitor, "allUnderOr", QuantiVar.Quantifier.ALL, "S") == 2,
                    "universal scopes in disjunction branches are not reusable lanes");
            check(matrixBinderCount(visitor, "someUnderOr", QuantiVar.Quantifier.SOME, "S") == 1,
                    "existential scopes in disjunction branches must reuse their lane");
            check(matrixBinderCount(visitor, "nestedSomeUnderOr", QuantiVar.Quantifier.SOME, "S") == 2,
                    "nested existential chains in sibling disjunctions use maximum live arity");
            check(matrixBinderCount(visitor, "someUnderAnd", QuantiVar.Quantifier.SOME, "S") == 2,
                    "existential scopes in conjunction branches are not reusable lanes");
            check(matrixBinderCount(visitor, "quantifierBarrier", QuantiVar.Quantifier.ALL, "S") == 1,
                    "a safely lifted outer quantifier must expose the reusable universal frontier");
            check(hasInheritedAlias(visitor, "scopedTemporal", "b"),
                    "a reused slot must retain aliases needed by a temporal child");
            check(alphaLeft.eclassCount() > 0 && alphaLeft.enodeCount() > 0,
                    "exact graph statistics must be populated");
            check(alphaLeft.digest().length() == 64,
                    "canonical digest must be a SHA-256 hex string");
            check(alphaLeft.digest().equals(prepare(visitor, "alphaLeft").digest()),
                    "repeated adaptation must be deterministic");
            NormalForm missingExactType = new NormalForm();
            EGraphNode untypedRelation = new EGraphNode(
                    1_000_001,
                    Opcode.GLOBALBINDING,
                    List.of(),
                    false,
                    0,
                    false,
                    Metatype.SET);
            untypedRelation.setSourceName("S");
            untypedRelation.setSourceType("S");
            missingExactType.addEClass(untypedRelation);
            expectThrows(IllegalStateException.class, () ->
                    TheoryAlloyAdapter.adapt(List.of(missingExactType)));

            NormalForm missingBindingType = new NormalForm();
            missingBindingType.addParam(new QuantiVar(
                    1_000_002, "_q_missing", "missing", null));
            EGraphNode typedBoolean = new EGraphNode(
                    1_000_003,
                    Opcode.CONSTANT,
                    List.of(),
                    false,
                    0,
                    false,
                    Metatype.BOOLEAN);
            typedBoolean.setSourceName("true");
            typedBoolean.setSourceType("bool");
            typedBoolean.setExactAlloyType(ExactAlloyType.boolType());
            missingBindingType.addEClass(typedBoolean);
            expectThrows(IllegalStateException.class, () ->
                    TheoryAlloyAdapter.adapt(List.of(missingBindingType)));

            System.out.println("CanonicalAlloyPipelineTest passed: " + checks + " checks");
        } finally {
            Files.deleteIfExists(modelPath);
            Files.deleteIfExists(directory);
        }
    }

    private static CanonicalAlloyPipeline.Prepared prepare(
            MASGVisitor visitor,
            String predicate) {
        return CanonicalAlloyPipeline.prepare(prepareFast(visitor, predicate));
    }

    private static Canonical.Prepared prepareFast(
            MASGVisitor visitor,
            String predicate) {
        Integer id = visitor.getForestId(predicate);
        check(id != null, "missing MASG predicate " + predicate);
        Multigraph graph = visitor.getForest().get(id);
        check(graph != null, "missing MASG graph " + predicate);
        return Canonical.prepare(graph);
    }

    private static void checkFastRewriteRepairBoundaries(MASGVisitor visitor) {
        Canonical.Prepared fieldLeft = prepareFast(visitor, "fastFieldLeft");
        Canonical.Prepared fieldAlpha = prepareFast(visitor, "fastFieldAlpha");
        Canonical.Prepared fieldWrongOwner = prepareFast(
                visitor, "fastFieldWrongOwner");
        check(Canonical.distance(fieldLeft, fieldAlpha) == 0,
                "Fast Rewrite field identity must preserve alpha-equivalence");
        check(Canonical.distance(fieldLeft, fieldWrongOwner) == 1,
                "Fast Rewrite must distinguish same-spelled fields owned by different signatures");
        check(CanonicalAlloyPipeline.distance(
                        CanonicalAlloyPipeline.prepare(fieldLeft),
                        CanonicalAlloyPipeline.prepare(fieldWrongOwner)) == 2,
                "the field-identity repair must not alter certificate-integrated geometry");

        Canonical.Prepared temporalLeft = prepareFast(
                visitor, "fastTemporalLeft");
        Canonical.Prepared temporalAlpha = prepareFast(
                visitor, "fastTemporalAlpha");
        Canonical.Prepared temporalPermutation = prepareFast(
                visitor, "fastTemporalPermutation");
        Canonical.Prepared temporalWrongEndpoint = prepareFast(
                visitor, "fastTemporalWrongEndpoint");
        check(Canonical.distance(temporalLeft, temporalAlpha) == 0,
                "Fast Rewrite must retain coherent temporal alpha-equivalence");
        check(Canonical.distance(temporalLeft, temporalPermutation) == 0,
                "Fast Rewrite must retain a whole-block permutation across temporal phases");
        check(Canonical.distance(temporalLeft, temporalWrongEndpoint) == 1,
                "Fast Rewrite must reject an independently remapped inherited temporal slot");
        check(CanonicalAlloyPipeline.distance(
                        CanonicalAlloyPipeline.prepare(temporalLeft),
                        CanonicalAlloyPipeline.prepare(temporalWrongEndpoint)) == 1,
                "temporal coherence repair must preserve certificate-integrated geometry");
    }

    private static void checkCertifiedDistributiveLattices()
            throws Exception {
        String source = String.join("\n",
                "module certified_lattices",
                "sig U {}",
                "pred boolAbsorbAnd[p,q:set U] {",
                "  (some p) and ((some p) or (some q))",
                "}",
                "pred boolAbsorbAndExpected[p,q:set U] { some p }",
                "pred boolAbsorbOr[p,q:set U] {",
                "  (some p) or ((some p) and (some q))",
                "}",
                "pred boolAbsorbOrExpected[p,q:set U] { some p }",
                "pred boolAbsorbAndContext[p,q,r,s:set U] {",
                "  (some p) and (some q) and ((some q) or (some r)) and",
                "    (some s) and ((some s) or (some p))",
                "}",
                "pred boolAbsorbAndContextExpected[p,q,r,s:set U] {",
                "  (some p) and (some q) and (some s)",
                "}",
                "pred boolAbsorbOrContext[p,q,r,s:set U] {",
                "  (some p) or (some q) or ((some q) and (some r)) or",
                "    (some s) or ((some s) and (some p))",
                "}",
                "pred boolAbsorbOrContextExpected[p,q,r,s:set U] {",
                "  (some p) or (some q) or (some s)",
                "}",
                "pred boolDistributeAnd[p,q,r:set U] {",
                "  (some p) and ((some q) or (some r))",
                "}",
                "pred boolDistributeAndExpected[p,q,r:set U] {",
                "  ((some p) and (some q)) or ((some p) and (some r))",
                "}",
                "pred boolDistributeOr[p,q,r:set U] {",
                "  (some p) or ((some q) and (some r))",
                "}",
                "pred boolDistributeOrExpected[p,q,r:set U] {",
                "  ((some p) or (some q)) and ((some p) or (some r))",
                "}",
                "pred relationAbsorbIntersect[p,q:set U] {",
                "  some (p & (p + q))",
                "}",
                "pred relationAbsorbIntersectExpected[p,q:set U] { some p }",
                "pred relationAbsorbUnion[p,q:set U] {",
                "  some (p + (p & q))",
                "}",
                "pred relationAbsorbUnionExpected[p,q:set U] { some p }",
                "pred relationAbsorbIntersectContext[p,q,r,s:set U] {",
                "  some (p & q & (q + r) & s & (s + p))",
                "}",
                "pred relationAbsorbIntersectContextExpected[p,q,r,s:set U] {",
                "  some (p & q & s)",
                "}",
                "pred relationAbsorbUnionContext[p,q,r,s:set U] {",
                "  some (p + q + (q & r) + s + (s & p))",
                "}",
                "pred relationAbsorbUnionContextExpected[p,q,r,s:set U] {",
                "  some (p + q + s)",
                "}",
                "pred relationDistributeIntersect[p,q,r:set U] {",
                "  some (p & (q + r))",
                "}",
                "pred relationDistributeIntersectExpected[p,q,r:set U] {",
                "  some ((p & q) + (p & r))",
                "}",
                "pred relationDistributeUnion[p,q,r:set U] {",
                "  some (p + (q & r))",
                "}",
                "pred relationDistributeUnionExpected[p,q,r:set U] {",
                "  some ((p + q) & (p + r))",
                "}",
                "pred productRightSlot[p,q,r:set U] {",
                "  some ((p -> q) + (p -> r))",
                "}",
                "pred productRightSlotExpected[p,q,r:set U] {",
                "  some (p -> (q + r))",
                "}",
                "pred productLeftSlot[p,q,r:set U] {",
                "  some ((p -> r) + (q -> r))",
                "}",
                "pred productLeftSlotExpected[p,q,r:set U] {",
                "  some ((p + q) -> r)",
                "}",
                "pred productFullSlotGrid[p,q:set U] {",
                "  some ((p -> p) + (p -> q) + (q -> p) + (q -> q))",
                "}",
                "pred productFullSlotGridExpected[p,q:set U] {",
                "  some ((p + q) -> (p + q))",
                "}",
                "pred productPartialSlotGrid[p,q:set U] {",
                "  some ((p -> p) + (p -> q) + (q -> p))",
                "}",
                "assert BooleanAbsorbAnd { all p,q:set U |",
                "  boolAbsorbAnd[p,q] iff boolAbsorbAndExpected[p,q] }",
                "assert BooleanAbsorbOr { all p,q:set U |",
                "  boolAbsorbOr[p,q] iff boolAbsorbOrExpected[p,q] }",
                "assert BooleanAbsorbAndContext { all p,q,r,s:set U |",
                "  boolAbsorbAndContext[p,q,r,s] iff",
                "    boolAbsorbAndContextExpected[p,q,r,s] }",
                "assert BooleanAbsorbOrContext { all p,q,r,s:set U |",
                "  boolAbsorbOrContext[p,q,r,s] iff",
                "    boolAbsorbOrContextExpected[p,q,r,s] }",
                "assert BooleanDistributeAnd { all p,q,r:set U |",
                "  boolDistributeAnd[p,q,r] iff boolDistributeAndExpected[p,q,r] }",
                "assert BooleanDistributeOr { all p,q,r:set U |",
                "  boolDistributeOr[p,q,r] iff boolDistributeOrExpected[p,q,r] }",
                "assert RelationAbsorbIntersect { all p,q:set U |",
                "  p & (p + q) = p }",
                "assert RelationAbsorbUnion { all p,q:set U |",
                "  p + (p & q) = p }",
                "assert RelationAbsorbIntersectContext { all p,q,r,s:set U |",
                "  p & q & (q + r) & s = p & q & s }",
                "assert RelationAbsorbUnionContext { all p,q,r,s:set U |",
                "  p + q + (q & r) + s = p + q + s }",
                "assert RelationDistributeIntersect { all p,q,r:set U |",
                "  p & (q + r) = (p & q) + (p & r) }",
                "assert RelationDistributeUnion { all p,q,r:set U |",
                "  p + (q & r) = (p + q) & (p + r) }",
                "assert ProductRightSlot { all p,q,r:set U |",
                "  (p -> q) + (p -> r) = p -> (q + r) }",
                "assert ProductLeftSlot { all p,q,r:set U |",
                "  (p -> r) + (q -> r) = (p + q) -> r }",
                "assert ProductFullSlotGrid { all p,q:set U |",
                "  (p -> p) + (p -> q) + (q -> p) + (q -> q)",
                "    = (p + q) -> (p + q) }",
                "check BooleanAbsorbAnd for 3",
                "check BooleanAbsorbOr for 3",
                "check BooleanAbsorbAndContext for 3",
                "check BooleanAbsorbOrContext for 3",
                "check BooleanDistributeAnd for 3",
                "check BooleanDistributeOr for 3",
                "check RelationAbsorbIntersect for 3",
                "check RelationAbsorbUnion for 3",
                "check RelationAbsorbIntersectContext for 3",
                "check RelationAbsorbUnionContext for 3",
                "check RelationDistributeIntersect for 3",
                "check RelationDistributeUnion for 3",
                "check ProductRightSlot for 3",
                "check ProductLeftSlot for 3",
                "check ProductFullSlotGrid for 3",
                "run { some U and some p,q:set U |",
                "  (p -> p) + (p -> q) + (q -> p)",
                "    != (p + q) -> (p + q) } for 3",
                "");
        CompModule module = CompUtil.parseEverything_fromString(
                edu.mit.csail.sdg.alloy4.A4Reporter.NOP, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        String[][] equivalentPairs = {
                {"boolAbsorbAnd", "boolAbsorbAndExpected"},
                {"boolAbsorbOr", "boolAbsorbOrExpected"},
                {"boolAbsorbAndContext", "boolAbsorbAndContextExpected"},
                {"boolAbsorbOrContext", "boolAbsorbOrContextExpected"},
                {"boolDistributeAnd", "boolDistributeAndExpected"},
                {"boolDistributeOr", "boolDistributeOrExpected"},
                {"relationAbsorbIntersect", "relationAbsorbIntersectExpected"},
                {"relationAbsorbUnion", "relationAbsorbUnionExpected"},
                {"relationAbsorbIntersectContext",
                        "relationAbsorbIntersectContextExpected"},
                {"relationAbsorbUnionContext",
                        "relationAbsorbUnionContextExpected"},
                {"relationDistributeIntersect", "relationDistributeIntersectExpected"},
                {"relationDistributeUnion", "relationDistributeUnionExpected"},
                {"productRightSlot", "productRightSlotExpected"},
                {"productLeftSlot", "productLeftSlotExpected"},
                {"productFullSlotGrid", "productFullSlotGridExpected"}
        };
        for (String[] pair : equivalentPairs) {
            CanonicalAlloyPipeline.Prepared left = prepare(visitor, pair[0]);
            CanonicalAlloyPipeline.Prepared right = prepare(visitor, pair[1]);
            check(left.equivalentTo(right)
                            && CanonicalAlloyPipeline.distance(left, right) == 0,
                    "certified lattice/product normalization failed for "
                            + pair[0]);
        }
        CanonicalAlloyPipeline.Prepared partial = prepare(
                visitor, "productPartialSlotGrid");
        CanonicalAlloyPipeline.Prepared full = prepare(
                visitor, "productFullSlotGridExpected");
        check(!partial.equivalentTo(full)
                        && CanonicalAlloyPipeline.distance(partial, full) > 0,
                "a partial slot-product grid must not synthesize its missing cross term");

        edu.mit.csail.sdg.translator.A4Options options =
                new edu.mit.csail.sdg.translator.A4Options();
        options.solver = edu.mit.csail.sdg.translator.A4Options.SatSolver.SAT4J;
        List<edu.mit.csail.sdg.ast.Command> commands = module.getAllCommands();
        for (int index = 0; index < commands.size(); index++) {
            edu.mit.csail.sdg.translator.A4Solution result =
                    edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
                            .execute_command(
                                    edu.mit.csail.sdg.alloy4.A4Reporter.NOP,
                                    module.getAllReachableSigs(),
                                    commands.get(index),
                                    options);
            boolean expectedSatisfiable = index == commands.size() - 1;
            check(result != null
                            && result.satisfiable() == expectedSatisfiable,
                    "Alloy lattice/product semantic witness failed at command "
                            + index);
        }
    }

    private static void checkCertifiedFullCarrierAbsorption()
            throws Exception {
        String source = String.join("\n",
                "module certified_full_carriers",
                "sig P {}",
                "sig A, B extends P {}",
                "sig X, Y in P {}",
                "sig H { f, g: set P, r: P -> P }",
                "pred parentUnion { some (A + B + P) }",
                "pred parentBare { some P }",
                "pred extendsIntersect { some (A & P) }",
                "pred extendsBare { some A }",
                "pred subsetIntersect { some (X & P) }",
                "pred subsetBare { some X }",
                "pred fieldIntersect { some (H.f & P) }",
                "pred fieldBare { some H.f }",
                "pred productIntersect { some (H.r & (P -> P)) }",
                "pred productBare { some H.r }",
                "pred fieldNearMiss { some (H.f & H.g) }",
                "pred subsetNearMiss { some (X & Y) }",
                "pred productNearMiss { some (H.r & (A -> P)) }",
                "assert ParentUnion { A + B + P = P }",
                "assert ExtendsIntersection { A & P = A }",
                "assert SubsetIntersection { X & P = X }",
                "assert FieldIntersection { H.f & P = H.f }",
                "assert ProductIntersection { H.r & (P -> P) = H.r }",
                "check ParentUnion for 4",
                "check ExtendsIntersection for 4",
                "check SubsetIntersection for 4",
                "check FieldIntersection for 4",
                "check ProductIntersection for 4",
                "run { some A and some B and some X and some H.f and some H.r } for 4",
                "run { some H.f and no (H.f & H.g) } for 4",
                "run { some X and no (X & Y) } for 4",
                "run { some H.r and no (H.r & (A -> P)) } for 4",
                "");
        CompModule module = CompUtil.parseEverything_fromString(
                edu.mit.csail.sdg.alloy4.A4Reporter.NOP, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);

        String[][] equivalentPairs = {
                {"parentUnion", "parentBare"},
                {"extendsIntersect", "extendsBare"},
                {"subsetIntersect", "subsetBare"},
                {"fieldIntersect", "fieldBare"},
                {"productIntersect", "productBare"}
        };
        for (String[] pair : equivalentPairs) {
            CanonicalAlloyPipeline.Prepared left = prepare(visitor, pair[0]);
            CanonicalAlloyPipeline.Prepared right = prepare(visitor, pair[1]);
            check(left.equivalentTo(right)
                            && CanonicalAlloyPipeline.distance(left, right) == 0,
                    "certified full-carrier absorption failed for " + pair[0]);
        }

        CanonicalAlloyPipeline.Prepared field = prepare(visitor, "fieldBare");
        CanonicalAlloyPipeline.Prepared subset = prepare(visitor, "subsetBare");
        CanonicalAlloyPipeline.Prepared product = prepare(visitor, "productBare");
        check(!prepare(visitor, "fieldNearMiss").equivalentTo(field),
                "a same-typed field must not acquire full-carrier authority");
        check(!prepare(visitor, "subsetNearMiss").equivalentTo(subset),
                "a sibling subset signature must not acquire full-carrier authority");
        check(!prepare(visitor, "productNearMiss").equivalentTo(product),
                "a proper sub-product must not acquire full-product authority");

        edu.mit.csail.sdg.translator.A4Options options =
                new edu.mit.csail.sdg.translator.A4Options();
        options.solver = edu.mit.csail.sdg.translator.A4Options.SatSolver.SAT4J;
        List<edu.mit.csail.sdg.ast.Command> commands = module.getAllCommands();
        for (int index = 0; index < commands.size(); index++) {
            edu.mit.csail.sdg.translator.A4Solution result =
                    edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
                            .execute_command(
                                    edu.mit.csail.sdg.alloy4.A4Reporter.NOP,
                                    module.getAllReachableSigs(),
                                    commands.get(index),
                                    options);
            boolean expectedSatisfiable = index >= 5;
            check(result != null
                            && result.satisfiable() == expectedSatisfiable,
                    "Alloy full-carrier semantic witness failed at command "
                            + index);
        }
    }

    private static void checkCertifiedRelationalFactoring()
            throws Exception {
        String source = String.join("\n",
                "module certified_relational_factoring",
                "sig U {}",
                "pred differenceUnionLeft[p,q,r:set U] {",
                "  some ((p - r) + (q - r))",
                "}",
                "pred differenceUnionLeftExpected[p,q,r:set U] {",
                "  some ((p + q) - r)",
                "}",
                "pred differenceIntersectLeft[p,q,r:set U] {",
                "  some ((p - r) & (q - r))",
                "}",
                "pred differenceIntersectLeftExpected[p,q,r:set U] {",
                "  some ((p & q) - r)",
                "}",
                "pred differenceIntersectRight[p,q,r:set U] {",
                "  some ((p - q) & (p - r))",
                "}",
                "pred differenceIntersectRightExpected[p,q,r:set U] {",
                "  some (p - (q + r))",
                "}",
                "pred differenceUnionRight[p,q,r:set U] {",
                "  some ((p - q) + (p - r))",
                "}",
                "pred differenceUnionRightExpected[p,q,r:set U] {",
                "  some (p - (q & r))",
                "}",
                "pred joinUnionRight[r,s,t:U -> U] {",
                "  some (r.s + r.t)",
                "}",
                "pred joinUnionRightExpected[r,s,t:U -> U] {",
                "  some (r.(s + t))",
                "}",
                "pred joinUnionLeft[r,s,t:U -> U] {",
                "  some (r.t + s.t)",
                "}",
                "pred joinUnionLeftExpected[r,s,t:U -> U] {",
                "  some ((r + s).t)",
                "}",
                "pred joinChainUnionRight[r,s,t,u:U -> U] {",
                "  some (r.s.t + r.s.u)",
                "}",
                "pred joinChainUnionRightExpected[r,s,t,u:U -> U] {",
                "  some (r.s.(t + u))",
                "}",
                "pred joinChainUnionMiddle[r,s,t,u:U -> U] {",
                "  some (r.s.t + r.u.t)",
                "}",
                "pred joinChainUnionMiddleExpected[r,s,t,u:U -> U] {",
                "  some (r.(s + u).t)",
                "}",
                "pred joinChainUnionLeft[r,s,t,u:U -> U] {",
                "  some (r.s.t + u.s.t)",
                "}",
                "pred joinChainUnionLeftExpected[r,s,t,u:U -> U] {",
                "  some ((r + u).s.t)",
                "}",
                "pred someUnion[p,q:set U] { some (p + q) }",
                "pred someUnionExpected[p,q:set U] { some p or some q }",
                "pred noUnion[p,q:set U] { no (p + q) }",
                "pred noUnionExpected[p,q:set U] { no p and no q }",
                "pred nestedDifference[p,q,r:set U] {",
                "  some ((p - q) - r)",
                "}",
                "pred nestedDifferenceExpected[p,q,r:set U] {",
                "  some (p - (q + r))",
                "}",
                "pred nestedDifferenceNary[p,q,r,s:set U] {",
                "  some (((p - q) - r) - s)",
                "}",
                "pred nestedDifferenceNaryExpected[p,q,r,s:set U] {",
                "  some (p - (q + r + s))",
                "}",
                "pred rightNestedDifference[p,q,r:set U] {",
                "  some (p - (q - r))",
                "}",
                "pred rightNestedDifferenceExpected[p,q,r:set U] {",
                "  some ((p - q) + (p & r))",
                "}",
                "pred intersectionDifference[p,q,r:set U] {",
                "  some ((p - q) & r)",
                "}",
                "pred intersectionDifferenceExpected[p,q,r:set U] {",
                "  some ((p & r) - q)",
                "}",
                "pred intersectionDifferences[p,q,r,s:set U] {",
                "  some ((p - q) & (r - s))",
                "}",
                "pred intersectionDifferencesExpected[p,q,r,s:set U] {",
                "  some ((p & r) - (q + s))",
                "}",
                "pred overlappingDifference[p,q,r,s:set U] {",
                "  some ((p - q) - (r - s))",
                "}",
                "pred overlappingDifferenceExpected[p,q,r,s:set U] {",
                "  some ((p - (q + r)) + ((p & s) - q))",
                "}",
                "pred intersectionDifferencesNary[p,q,r,s,t,u:set U] {",
                "  some ((p - q) & (r - s) & (t - u))",
                "}",
                "pred intersectionDifferencesNaryExpected[p,q,r,s,t,u:set U] {",
                "  some ((p & r & t) - (q + s + u))",
                "}",
                "pred productDifferenceLeft[p,q,r:set U] {",
                "  some ((p -> r) - (q -> r))",
                "}",
                "pred productDifferenceLeftExpected[p,q,r:set U] {",
                "  some ((p - q) -> r)",
                "}",
                "pred productDifferenceRight[p,q,r:set U] {",
                "  some ((p -> q) - (p -> r))",
                "}",
                "pred productDifferenceRightExpected[p,q,r:set U] {",
                "  some (p -> (q - r))",
                "}",
                "pred productDifferenceMiddle[p,q,r,s:set U] {",
                "  some ((p -> q -> s) - (p -> r -> s))",
                "}",
                "pred productDifferenceMiddleExpected[p,q,r,s:set U] {",
                "  some (p -> (q - r) -> s)",
                "}",
                "pred productDifferenceMultiple[p,q,r,s:set U] {",
                "  some ((p -> r) - (q -> s))",
                "}",
                "pred productDifferenceMultipleUnsound[p,q,r,s:set U] {",
                "  some ((p - q) -> (r - s))",
                "}",
                "pred productIntersectionFixed[p,q,r:set U] {",
                "  some ((p -> r) & (q -> r))",
                "}",
                "pred productIntersectionFixedExpected[p,q,r:set U] {",
                "  some ((p & q) -> r)",
                "}",
                "pred productIntersectionAll[p,q,r,s:set U] {",
                "  some ((p -> r) & (q -> s))",
                "}",
                "pred productIntersectionAllExpected[p,q,r,s:set U] {",
                "  some ((p & q) -> (r & s))",
                "}",
                "pred productIntersectionNary[a,b,c,d,e,f:set U] {",
                "  some ((a -> d) & (b -> e) & (c -> f))",
                "}",
                "pred productIntersectionNaryExpected[a,b,c,d,e,f:set U] {",
                "  some ((a & b & c) -> (d & e & f))",
                "}",
                "pred productIntersectionTernary[a,b,c,d,e,f:set U] {",
                "  some ((a -> c -> e) & (b -> d -> f))",
                "}",
                "pred productIntersectionTernaryExpected[a,b,c,d,e,f:set U] {",
                "  some ((a & b) -> (c & d) -> (e & f))",
                "}",
                "pred productIntersectionResidual[p,q,r,s:set U, t:U->U] {",
                "  some ((p -> r) & (q -> s) & t)",
                "}",
                "pred productIntersectionResidualExpected[p,q,r,s:set U, t:U->U] {",
                "  some (((p & q) -> (r & s)) & t)",
                "}",
                "pred productAciDuplicate[a,b,c:set U] {",
                "  some ((a + a + b) -> c)",
                "}",
                "pred productAciDuplicateExpected[a,b,c:set U] {",
                "  some ((a + b) -> c)",
                "}",
                "pred joinAciUnion[r,s,t,u:U -> U] {",
                "  some ((r + s + t).u)",
                "}",
                "pred joinAciUnionExpected[r,s,t,u:U -> U] {",
                "  some ((t + r + s).u)",
                "}",
                "pred joinAciDuplicate[r,s,u:U -> U] {",
                "  some ((r + r + s).u)",
                "}",
                "pred joinAciDuplicateExpected[r,s,u:U -> U] {",
                "  some ((r + s).u)",
                "}",
                "pred joinAciIntersection[r,s,t,u:U -> U] {",
                "  some ((r & s & t).u)",
                "}",
                "pred joinAciIntersectionExpected[r,s,t,u:U -> U] {",
                "  some ((t & r & s).u)",
                "}",
                "pred transposeJoin[r,s:U->U] { some ~(r.s) }",
                "pred transposeJoinExpected[r,s:U->U] { some ((~s).(~r)) }",
                "pred transposeClosure[r:U->U] { some ~(^r) }",
                "pred transposeClosureExpected[r:U->U] { some ^(~r) }",
                "pred transposeReflexiveClosure[r:U->U] { some ~(*r) }",
                "pred transposeReflexiveClosureExpected[r:U->U] { some *(~r) }",
                "pred transposeDomain[a:set U,r:U->U] { some ~(a<:r) }",
                "pred transposeDomainExpected[a:set U,r:U->U] { some ((~r):>a) }",
                "pred transposeRange[a:set U,r:U->U] { some ~(r:>a) }",
                "pred transposeRangeExpected[a:set U,r:U->U] { some (a<:(~r)) }",
                "pred transposeEmpty { some ~(none->none) }",
                "pred transposeEmptyExpected { some (none->none) }",
                "pred closureEmpty { some ^(none->none) }",
                "pred closureEmptyExpected { some (none->none) }",
                "pred reflexiveClosureEmpty { some *(none->none) }",
                "pred reflexiveClosureEmptyExpected { some iden }",
                "pred reflexiveClosureEmptyNearMiss { some (none->none) }",
                "pred transposeClosureKindNearMiss[r:U->U] { some *(~r) }",
                "pred transposeRestrictionNearMiss[a:set U,r:U->U] { some (a<:(~r)) }",
                "pred domainRelationUnion[a:set U,r,s:U->U] { some (a<:(r+s)) }",
                "pred domainRelationUnionExpected[a:set U,r,s:U->U] { some ((a<:r)+(a<:s)) }",
                "pred domainRelationIntersection[a:set U,r,s:U->U] { some (a<:(r&s)) }",
                "pred domainRelationIntersectionExpected[a:set U,r,s:U->U] { some ((a<:r)&(a<:s)) }",
                "pred domainRelationDifference[a:set U,r,s:U->U] { some (a<:(r-s)) }",
                "pred domainRelationDifferenceExpected[a:set U,r,s:U->U] { some ((a<:r)-(a<:s)) }",
                "pred domainRestrictorUnion[a,b:set U,r:U->U] { some ((a+b)<:r) }",
                "pred domainRestrictorUnionExpected[a,b:set U,r:U->U] { some ((a<:r)+(b<:r)) }",
                "pred domainRestrictorIntersection[a,b:set U,r:U->U] { some ((a&b)<:r) }",
                "pred domainRestrictorIntersectionExpected[a,b:set U,r:U->U] { some ((a<:r)&(b<:r)) }",
                "pred domainRestrictorDifference[a,b:set U,r:U->U] { some ((a-b)<:r) }",
                "pred domainRestrictorDifferenceExpected[a,b:set U,r:U->U] { some ((a<:r)-(b<:r)) }",
                "pred rangeRelationUnion[a:set U,r,s:U->U] { some ((r+s):>a) }",
                "pred rangeRelationUnionExpected[a:set U,r,s:U->U] { some ((r:>a)+(s:>a)) }",
                "pred rangeRelationIntersection[a:set U,r,s:U->U] { some ((r&s):>a) }",
                "pred rangeRelationIntersectionExpected[a:set U,r,s:U->U] { some ((r:>a)&(s:>a)) }",
                "pred rangeRelationDifference[a:set U,r,s:U->U] { some ((r-s):>a) }",
                "pred rangeRelationDifferenceExpected[a:set U,r,s:U->U] { some ((r:>a)-(s:>a)) }",
                "pred rangeRestrictorUnion[a,b:set U,r:U->U] { some (r:>(a+b)) }",
                "pred rangeRestrictorUnionExpected[a,b:set U,r:U->U] { some ((r:>a)+(r:>b)) }",
                "pred rangeRestrictorIntersection[a,b:set U,r:U->U] { some (r:>(a&b)) }",
                "pred rangeRestrictorIntersectionExpected[a,b:set U,r:U->U] { some ((r:>a)&(r:>b)) }",
                "pred rangeRestrictorDifference[a,b:set U,r:U->U] { some (r:>(a-b)) }",
                "pred rangeRestrictorDifferenceExpected[a,b:set U,r:U->U] { some ((r:>a)-(r:>b)) }",
                "pred domainUnivIdentity[r:U->U] { some (univ<:r) }",
                "pred domainUnivIdentityExpected[r:U->U] { some r }",
                "pred rangeUnivIdentity[r:U->U] { some (r:>univ) }",
                "pred rangeUnivIdentityExpected[r:U->U] { some r }",
                "pred domainCarrierIdentity[r:U->U] { some (U<:r) }",
                "pred domainCarrierIdentityExpected[r:U->U] { some r }",
                "pred rangeCarrierIdentity[r:U->U] { some (r:>U) }",
                "pred rangeCarrierIdentityExpected[r:U->U] { some r }",
                "pred domainCarrierBound { all r:U->U | some (U<:r) }",
                "pred domainCarrierBoundExpected { all r:U->U | some r }",
                "pred rangeCarrierBound { all r:U->U | some (r:>U) }",
                "pred rangeCarrierBoundExpected { all r:U->U | some r }",
                "pred domainEmptyRestrictor[r:U->U] { no (none<:r) }",
                "pred domainEmptyRestrictorExpected[r:U->U] { no (r-r) }",
                "pred rangeEmptyRestrictor[r:U->U] { no (r:>none) }",
                "pred rangeEmptyRestrictorExpected[r:U->U] { no (r-r) }",
                "pred domainEmptyRelation[a:set U] { no (a<:(none->none)) }",
                "pred domainEmptyRelationExpected[a:set U] { no (none->none) }",
                "pred rangeEmptyRelation[a:set U] { no ((none->none):>a) }",
                "pred rangeEmptyRelationExpected[a:set U] { no (none->none) }",
                "pred domainVariableNearMiss[a:set U,r:U->U] { some (a<:r) }",
                "pred domainVariableNearMissUnsound[a:set U,r:U->U] { some r }",
                "pred nestedDomain[a,b:set U,r:U->U] { some (a<:(b<:r)) }",
                "pred nestedDomainExpected[a,b:set U,r:U->U] { some ((a&b)<:r) }",
                "pred nestedRange[a,b:set U,r:U->U] { some ((r:>a):>b) }",
                "pred nestedRangeExpected[a,b:set U,r:U->U] { some (r:>(a&b)) }",
                "pred commutingRestrictions[a,b:set U,r:U->U] { some (a<:(r:>b)) }",
                "pred commutingRestrictionsExpected[a,b:set U,r:U->U] { some ((a<:r):>b) }",
                "pred restrictionGrid[a,b:set U,r,s:U->U] {",
                "  some ((a<:r)+(a<:s)+(b<:r)+(b<:s))",
                "}",
                "pred restrictionGridExpected[a,b:set U,r,s:U->U] {",
                "  some ((a+b)<:(r+s))",
                "}",
                "pred transposeJoinWrong[r,s:U->U] { some ((~r).(~s)) }",
                "pred restrictionDiagonal[a,b:set U,r,s:U->U] { some ((a<:r)+(b<:s)) }",
                "pred restrictionDiagonalUnsound[a,b:set U,r,s:U->U] { some ((a+b)<:(r+s)) }",
                "pred restrictionDifferenceMultiple[a,b:set U,r,s:U->U] { some ((a<:r)-(b<:s)) }",
                "pred restrictionDifferenceMultipleUnsound[a,b:set U,r,s:U->U] { some ((a-b)<:(r-s)) }",
                "pred joinOuterDomain[a:set U,r,s:U->U] { some ((a<:r).s) }",
                "pred joinOuterDomainExpected[a:set U,r,s:U->U] { some (a<:(r.s)) }",
                "pred joinOuterRange[a:set U,r,s:U->U] { some (r.(s:>a)) }",
                "pred joinOuterRangeExpected[a:set U,r,s:U->U] { some ((r.s):>a) }",
                "pred joinMiddleRestriction[a:set U,r,s:U->U] { some ((r:>a).s) }",
                "pred joinMiddleRestrictionExpected[a:set U,r,s:U->U] { some (r.(a<:s)) }",
                "pred joinOuterDomainChain[a:set U,r,s,t:U->U] { some ((a<:r).s.t) }",
                "pred joinOuterDomainChainExpected[a:set U,r,s,t:U->U] { some (a<:(r.s.t)) }",
                "pred joinOuterRangeChain[a:set U,r,s,t:U->U] { some (r.s.(t:>a)) }",
                "pred joinOuterRangeChainExpected[a:set U,r,s,t:U->U] { some ((r.s.t):>a) }",
                "pred joinAdjacentRestrictions[a,b:set U,r,s:U->U] { some ((r:>a).(b<:s)) }",
                "pred joinAdjacentRestrictionsExpected[a,b:set U,r,s:U->U] { some (r.((a&b)<:s)) }",
                "pred joinHigherArity[a:set U,r:U->U->U,s:U->U] { some ((a<:r).s) }",
                "pred joinHigherArityExpected[a:set U,r:U->U->U,s:U->U] { some (a<:(r.s)) }",
                "pred joinBoundSlots { all a:set U,r,s:U->U | some ((r:>a).s) }",
                "pred joinBoundSlotsExpected { all a:set U,r,s:U->U | some (r.(a<:s)) }",
                "pred joinWrongOuterSide[a:set U,r,s:U->U] { some ((a<:r).s) }",
                "pred joinWrongOuterSideCandidate[a:set U,r,s:U->U] { some ((r.s):>a) }",
                "pred joinWrongMiddleGuard[a,b:set U,r,s:U->U] { some ((r:>a).s) }",
                "pred joinWrongMiddleGuardCandidate[a,b:set U,r,s:U->U] { some (r.(b<:s)) }",
                "pred joinLeftUnaryBoundary[a,r:set U,s:U->U] { some ((a<:r).s) }",
                "pred joinLeftUnaryBoundaryExpected[a,r:set U,s:U->U] { some (r.(a<:s)) }",
                "pred joinLeftUnaryWrong[a,r:set U,s:U->U] { some (a<:(r.s)) }",
                "pred joinRightUnaryBoundary[a:set U,r:U->U,s:set U] { some (r.(s:>a)) }",
                "pred joinRightUnaryBoundaryExpected[a:set U,r:U->U,s:set U] { some (r.(a<:s)) }",
                "pred joinRightUnaryWrong[a:set U,r:U->U,s:set U] { some ((r.s):>a) }",
                "pred unaryRangeRestriction[a,r:set U] { some (r:>a) }",
                "pred unaryDomainRestriction[a,r:set U] { some (a<:r) }",
                "pred reflexiveSubset[r:set U] { r in r }",
                "pred reflexiveEquality[r:set U] { r = r }",
                "pred reflexiveNotSubset[r:set U] { r not in r }",
                "pred reflexiveInequality[r:set U] { r != r }",
                "pred reflexiveTruth[r:set U] { no none }",
                "pred reflexiveFalse[r:set U] { some none }",
                "pred distinctSubset[r,s:set U] { r in s }",
                "pred subsetUnion[r,s,t:set U] { r in r+s }",
                "pred subsetUnionExpected[r,s,t:set U] { no none }",
                "pred intersectionSubset[r,s,t:set U] { r&s in r }",
                "pred intersectionSubsetExpected[r,s,t:set U] { no none }",
                "pred differenceSubset[r,s,t:set U] { r-s in r }",
                "pred differenceSubsetExpected[r,s,t:set U] { no none }",
                "pred domainRestrictionSubset[a:set U,r:U->U] { (a<:r) in r }",
                "pred domainRestrictionSubsetExpected[a:set U,r:U->U] { no none }",
                "pred rangeRestrictionSubset[a:set U,r:U->U] { (r:>a) in r }",
                "pred rangeRestrictionSubsetExpected[a:set U,r:U->U] { no none }",
                "pred unionSubset[r,s,t:set U] { r+s in t }",
                "pred unionSubsetExpected[r,s,t:set U] { r in t and s in t }",
                "pred unionNotSubset[r,s,t:set U] { r+s not in t }",
                "pred unionNotSubsetExpected[r,s,t:set U] { r not in t or s not in t }",
                "pred subsetIntersection[r,s,t:set U] { r in s&t }",
                "pred subsetIntersectionExpected[r,s,t:set U] { r in s and r in t }",
                "pred notSubsetIntersection[r,s,t:set U] { r not in s&t }",
                "pred notSubsetIntersectionExpected[r,s,t:set U] { r not in s or r not in t }",
                "pred unionSubsetNary[r,s,t,u:set U] { r+s+t in u }",
                "pred unionSubsetNaryExpected[r,s,t,u:set U] { r in u and s in u and t in u }",
                "pred subsetIntersectionNary[r,s,t,u:set U] { r in s&t&u }",
                "pred subsetIntersectionNaryExpected[r,s,t,u:set U] { r in s and r in t and r in u }",
                "pred noDifferenceBridge[r,s:U->U] { no (r-s) }",
                "pred noDifferenceBridgeExpected[r,s:U->U] { r in s }",
                "pred someDifferenceBridge[r,s:U->U] { some (r-s) }",
                "pred someDifferenceBridgeExpected[r,s:U->U] { r not in s }",
                "pred noNestedDifferenceBridge[r,s,t:U->U] { no (r-(s+t)) }",
                "pred noNestedDifferenceBridgeExpected[r,s,t:U->U] { r in s+t }",
                "pred someNestedDifferenceBridge[r,s,t:U->U] { some (r-(s+t)) }",
                "pred someNestedDifferenceBridgeExpected[r,s,t:U->U] { r not in s+t }",
                "pred noRestrictedDifferenceBridge[a:set U,r,s:U->U] { no ((a<:r)-(a<:s)) }",
                "pred noRestrictedDifferenceBridgeExpected[a:set U,r,s:U->U] { (a<:r) in (a<:s) }",
                "pred someRestrictedDifferenceBridge[a:set U,r,s:U->U] { some ((a<:r)-(a<:s)) }",
                "pred someRestrictedDifferenceBridgeExpected[a:set U,r,s:U->U] { (a<:r) not in (a<:s) }",
                "pred noIntersectionDifferenceBridge[r,s,t:U->U] { no (r-(s&t)) }",
                "pred noIntersectionDifferenceBridgeExpected[r,s,t:U->U] { r in s&t }",
                "pred differenceDisjoint[p,q:set U] { no ((p-q)&q) }",
                "pred differenceDisjointExpected[p,q:set U] { no none }",
                "pred differenceRecombine[p,q:set U] { some ((p-q)+(p&q)) }",
                "pred differenceRecombineExpected[p,q:set U] { some p }",
                "pred differenceAbsorb[p,q:set U] { some ((p-q)&(p+q)) }",
                "pred differenceAbsorbExpected[p,q:set U] { some (p-q) }",
                "pred differenceComplement[p,q:set U] { some (p-(p&q)) }",
                "pred differenceComplementExpected[p,q:set U] { some (p-q) }",
                "pred differenceOfDifference[p,q:set U] { some (p-(p-q)) }",
                "pred differenceOfDifferenceExpected[p,q:set U] { some (p&q) }",
                "pred subsetNoneBridge[p:set U] { p in none }",
                "pred subsetNoneBridgeExpected[p:set U] { no p }",
                "pred notSubsetNoneBridge[p:set U] { p not in none }",
                "pred notSubsetNoneBridgeExpected[p:set U] { some p }",
                "pred quantifiedSomeMembership[a,b:set U] { some x:a | x in b }",
                "pred quantifiedSomeMembershipExpected[a,b:set U] { some (a&b) }",
                "pred quantifiedNoMembership[a,b:set U] { no x:a | x in b }",
                "pred quantifiedNoMembershipExpected[a,b:set U] { no (a&b) }",
                "pred quantifiedAllMembership[a,b:set U] { all x:a | x in b }",
                "pred quantifiedAllMembershipExpected[a,b:set U] { a in b }",
                "pred quantifiedSomeNotMembership[a,b:set U] { some x:a | x not in b }",
                "pred quantifiedSomeNotMembershipExpected[a,b:set U] { some (a-b) }",
                "pred quantifiedNoNotMembership[a,b:set U] { no x:a | x not in b }",
                "pred quantifiedNoNotMembershipExpected[a,b:set U] { a in b }",
                "pred quantifiedAllNotMembership[a,b:set U] { all x:a | x not in b }",
                "pred quantifiedAllNotMembershipExpected[a,b:set U] { no (a&b) }",
                "pred quantifiedSetSomeMembership[a,b:set U] { some x:set a | x in b }",
                "pred quantifiedSetSomeMembershipExpected[a,b:set U] { no none }",
                "pred quantifiedSetNoMembership[a,b:set U] { no x:set a | x in b }",
                "pred quantifiedSetNoMembershipExpected[a,b:set U] { some none }",
                "pred quantifiedSetAllMembership[a,b:set U] { all x:set a | x in b }",
                "pred quantifiedSetAllMembershipExpected[a,b:set U] { a in b }",
                "pred quantifiedLoneSomeNotMembership[a,b:set U] { some x:lone a | x not in b }",
                "pred quantifiedLoneSomeNotMembershipExpected[a,b:set U] { some (a-b) }",
                "pred quantifiedLoneNoNotMembership[a,b:set U] { no x:lone a | x not in b }",
                "pred quantifiedLoneNoNotMembershipExpected[a,b:set U] { a in b }",
                "pred quantifiedSetAllNotMembership[a,b:set U] { all x:set a | x not in b }",
                "pred quantifiedSetAllNotMembershipExpected[a,b:set U] { some none }",
                "pred quantifiedSomeCardinalitySomeMembership[a,b:set U] { some x:some a | x in b }",
                "pred quantifiedSomeCardinalitySomeMembershipExpected[a,b:set U] { some (a&b) }",
                "pred quantifiedSomeCardinalityNoMembership[a,b:set U] { no x:some a | x in b }",
                "pred quantifiedSomeCardinalityNoMembershipExpected[a,b:set U] { no (a&b) }",
                "pred quantifiedSomeCardinalityAllMembership[a,b:set U] { all x:some a | x in b }",
                "pred quantifiedSomeCardinalityAllMembershipExpected[a,b:set U] { a in b }",
                "pred quantifiedDisjNearMiss[a,b:set U] { some disj x,y:a | x in b }",
                "pred quantifiedDisjNearMissUnsound[a,b:set U] { some (a&b) }",
                "pred quantifiedSelfDependentNearMiss[a,b:set U] { some x:a | x in b+x }",
                "pred quantifiedSelfDependentNearMissUnsound[a,b:set U] { some (a&b) }",
                "pred quantifiedOneNearMiss[a,b:set U] { one x:a | x in b }",
                "pred quantifiedOneNearMissUnsound[a,b:set U] { some (a&b) }",
                "pred subsetUnionSplit[r,s,t:set U] { r in s+t }",
                "pred subsetUnionSplitUnsound[r,s,t:set U] { r in s or r in t }",
                "pred intersectionSubsetSplit[r,s,t:set U] { r&s in t }",
                "pred intersectionSubsetSplitUnsound[r,s,t:set U] { r in t or s in t }",
                "pred unaryRelationBinding[p:set U] { some p }",
                "pred binaryRelationBinding[p:U -> U] { some p }",
                "pred joinIntersection[r,s,t:U -> U] {",
                "  some (r.(s & t))",
                "}",
                "pred joinIntersectionExpanded[r,s,t:U -> U] {",
                "  some (r.s & r.t)",
                "}",
                "pred someIntersection[p,q:set U] { some (p & q) }",
                "pred someIntersectionExpanded[p,q:set U] { some p and some q }",
                "pred noIntersection[p,q:set U] { no (p & q) }",
                "pred noIntersectionExpanded[p,q:set U] { no p or no q }",
                "assert DifferenceUnionLeft { all p,q,r:set U |",
                "  (p-r) + (q-r) = (p+q)-r }",
                "assert DifferenceIntersectLeft { all p,q,r:set U |",
                "  (p-r) & (q-r) = (p&q)-r }",
                "assert DifferenceIntersectRight { all p,q,r:set U |",
                "  (p-q) & (p-r) = p-(q+r) }",
                "assert DifferenceUnionRight { all p,q,r:set U |",
                "  (p-q) + (p-r) = p-(q&r) }",
                "assert JoinUnionRight { all r,s,t:U -> U |",
                "  r.s + r.t = r.(s+t) }",
                "assert JoinUnionLeft { all r,s,t:U -> U |",
                "  r.t + s.t = (r+s).t }",
                "assert JoinChainUnionRight { all r,s,t,u:U -> U |",
                "  r.s.t + r.s.u = r.s.(t+u) }",
                "assert JoinChainUnionMiddle { all r,s,t,u:U -> U |",
                "  r.s.t + r.u.t = r.(s+u).t }",
                "assert JoinChainUnionLeft { all r,s,t,u:U -> U |",
                "  r.s.t + u.s.t = (r+u).s.t }",
                "assert SomeUnion { all p,q:set U |",
                "  some (p+q) iff (some p or some q) }",
                "assert NoUnion { all p,q:set U |",
                "  no (p+q) iff (no p and no q) }",
                "assert NestedDifferenceChain { all p,q,r,s:set U |",
                "  (((p-q)-r)-s) = p-(q+r+s) }",
                "assert RightNestedDifference { all p,q,r:set U |",
                "  p-(q-r) = (p-q)+(p&r) }",
                "assert IntersectionDifference { all p,q,r:set U |",
                "  (p-q)&r = (p&r)-q }",
                "assert IntersectionDifferences { all p,q,r,s:set U |",
                "  (p-q)&(r-s) = (p&r)-(q+s) }",
                "assert OverlappingDifference { all p,q,r,s:set U |",
                "  (p-q)-(r-s) = (p-(q+r))+((p&s)-q) }",
                "assert IntersectionDifferencesNary {",
                "  all p,q,r,s,t,u:set U |",
                "    (p-q)&(r-s)&(t-u) = (p&r&t)-(q+s+u)",
                "}",
                "assert ProductDifferenceLeft { all p,q,r:set U |",
                "  (p->r)-(q->r) = (p-q)->r }",
                "assert ProductDifferenceRight { all p,q,r:set U |",
                "  (p->q)-(p->r) = p->(q-r) }",
                "assert ProductDifferenceMiddle { all p,q,r,s:set U |",
                "  (p->q->s)-(p->r->s) = p->(q-r)->s }",
                "assert ProductIntersectionFixed { all p,q,r:set U |",
                "  (p->r)&(q->r) = (p&q)->r }",
                "assert ProductIntersectionAll { all p,q,r,s:set U |",
                "  (p->r)&(q->s) = (p&q)->(r&s) }",
                "assert ProductIntersectionNary {",
                "  all a,b,c,d,e,f:set U |",
                "    (a->d)&(b->e)&(c->f) = (a&b&c)->(d&e&f)",
                "}",
                "assert ProductIntersectionTernary {",
                "  all a,b,c,d,e,f:set U |",
                "    (a->c->e)&(b->d->f) = (a&b)->(c&d)->(e&f)",
                "}",
                "assert ProductIntersectionResidual {",
                "  all p,q,r,s:set U, t:U->U |",
                "    (p->r)&(q->s)&t = ((p&q)->(r&s))&t",
                "}",
                "assert ProductAciDuplicate { all a,b,c:set U |",
                "  (a+a+b)->c = (a+b)->c }",
                "assert JoinAciUnion { all r,s,t,u:U->U |",
                "  (r+s+t).u = (t+r+s).u }",
                "assert JoinAciDuplicate { all r,s,u:U->U |",
                "  (r+r+s).u = (r+s).u }",
                "assert JoinAciIntersection { all r,s,t,u:U->U |",
                "  (r&s&t).u = (t&r&s).u }",
                "assert TransposeJoin { all r,s:U->U | ~(r.s)=(~s).(~r) }",
                "assert TransposeClosure { all r:U->U | ~(^r)=^(~r) }",
                "assert TransposeReflexiveClosure { all r:U->U | ~(*r)=*(~r) }",
                "assert TransposeDomain { all a:set U,r:U->U | ~(a<:r)=((~r):>a) }",
                "assert TransposeRange { all a:set U,r:U->U | ~(r:>a)=(a<:(~r)) }",
                "assert TransposeEmpty { ~(none->none)=(none->none) }",
                "assert ClosureEmpty { ^(none->none)=(none->none) }",
                "assert ReflexiveClosureEmpty { *(none->none)=iden }",
                "assert DomainRelationUnion { all a:set U,r,s:U->U | a<:(r+s)=(a<:r)+(a<:s) }",
                "assert DomainRelationIntersection { all a:set U,r,s:U->U | a<:(r&s)=(a<:r)&(a<:s) }",
                "assert DomainRelationDifference { all a:set U,r,s:U->U | a<:(r-s)=(a<:r)-(a<:s) }",
                "assert DomainRestrictorUnion { all a,b:set U,r:U->U | (a+b)<:r=(a<:r)+(b<:r) }",
                "assert DomainRestrictorIntersection { all a,b:set U,r:U->U | (a&b)<:r=(a<:r)&(b<:r) }",
                "assert DomainRestrictorDifference { all a,b:set U,r:U->U | (a-b)<:r=(a<:r)-(b<:r) }",
                "assert RangeRelationUnion { all a:set U,r,s:U->U | (r+s):>a=(r:>a)+(s:>a) }",
                "assert RangeRelationIntersection { all a:set U,r,s:U->U | (r&s):>a=(r:>a)&(s:>a) }",
                "assert RangeRelationDifference { all a:set U,r,s:U->U | (r-s):>a=(r:>a)-(s:>a) }",
                "assert RangeRestrictorUnion { all a,b:set U,r:U->U | r:>(a+b)=(r:>a)+(r:>b) }",
                "assert RangeRestrictorIntersection { all a,b:set U,r:U->U | r:>(a&b)=(r:>a)&(r:>b) }",
                "assert RangeRestrictorDifference { all a,b:set U,r:U->U | r:>(a-b)=(r:>a)-(r:>b) }",
                "assert DomainUnivIdentity { all r:U->U | univ<:r=r }",
                "assert RangeUnivIdentity { all r:U->U | r:>univ=r }",
                "assert DomainCarrierIdentity { all r:U->U | U<:r=r }",
                "assert RangeCarrierIdentity { all r:U->U | r:>U=r }",
                "assert DomainCarrierBound { all r:U->U | (some (U<:r)) iff (some r) }",
                "assert RangeCarrierBound { all r:U->U | (some (r:>U)) iff (some r) }",
                "assert DomainEmptyRestrictor { all r:U->U | no (none<:r) }",
                "assert RangeEmptyRestrictor { all r:U->U | no (r:>none) }",
                "assert DomainEmptyRelation { all a:set U | no (a<:(none->none)) }",
                "assert RangeEmptyRelation { all a:set U | no ((none->none):>a) }",
                "assert NestedDomain { all a,b:set U,r:U->U | a<:(b<:r)=(a&b)<:r }",
                "assert NestedRange { all a,b:set U,r:U->U | (r:>a):>b=r:>(a&b) }",
                "assert CommutingRestrictions { all a,b:set U,r:U->U | a<:(r:>b)=(a<:r):>b }",
                "assert RestrictionGrid { all a,b:set U,r,s:U->U |",
                "  (a<:r)+(a<:s)+(b<:r)+(b<:s)=(a+b)<:(r+s) }",
                "assert JoinOuterDomain { all a:set U,r,s:U->U | (a<:r).s=a<:(r.s) }",
                "assert JoinOuterRange { all a:set U,r,s:U->U | r.(s:>a)=(r.s):>a }",
                "assert JoinMiddleRestriction { all a:set U,r,s:U->U | (r:>a).s=r.(a<:s) }",
                "assert JoinOuterDomainChain { all a:set U,r,s,t:U->U | (a<:r).s.t=a<:(r.s.t) }",
                "assert JoinOuterRangeChain { all a:set U,r,s,t:U->U | r.s.(t:>a)=(r.s.t):>a }",
                "assert JoinAdjacentRestrictions { all a,b:set U,r,s:U->U |",
                "  (r:>a).(b<:s)=r.((a&b)<:s) }",
                "assert JoinHigherArity { all a:set U,r:U->U->U,s:U->U |",
                "  (a<:r).s=a<:(r.s) }",
                "assert JoinLeftUnaryBoundary { all a,r:set U,s:U->U |",
                "  (a<:r).s=r.(a<:s) }",
                "assert JoinRightUnaryBoundary { all a:set U,r:U->U,s:set U |",
                "  r.(s:>a)=r.(a<:s) }",
                "assert UnaryRestrictionSides { all a,r:set U | r:>a=a<:r }",
                "assert ReflexiveSubset { all r:set U | r in r }",
                "assert ReflexiveEquality { all r:set U | r = r }",
                "assert ReflexiveNotSubset { all r:set U | not (r not in r) }",
                "assert ReflexiveInequality { all r:set U | not (r != r) }",
                "assert SubsetUnion { all r,s:set U | r in r+s }",
                "assert IntersectionSubset { all r,s:set U | r&s in r }",
                "assert DifferenceSubset { all r,s:set U | r-s in r }",
                "assert DomainRestrictionSubset { all a:set U,r:U->U | (a<:r) in r }",
                "assert RangeRestrictionSubset { all a:set U,r:U->U | (r:>a) in r }",
                "assert UnionSubset { all r,s,t:set U | (r+s in t) iff (r in t and s in t) }",
                "assert UnionNotSubset { all r,s,t:set U | (r+s not in t) iff (r not in t or s not in t) }",
                "assert SubsetIntersection { all r,s,t:set U | (r in s&t) iff (r in s and r in t) }",
                "assert NotSubsetIntersection { all r,s,t:set U | (r not in s&t) iff (r not in s or r not in t) }",
                "assert UnionSubsetNary { all r,s,t,u:set U | (r+s+t in u) iff (r in u and s in u and t in u) }",
                "assert SubsetIntersectionNary { all r,s,t,u:set U | (r in s&t&u) iff (r in s and r in t and r in u) }",
                "assert NoDifferenceBridge { all r,s:U->U | (no (r-s)) iff r in s }",
                "assert SomeDifferenceBridge { all r,s:U->U | (some (r-s)) iff r not in s }",
                "assert NoNestedDifferenceBridge { all r,s,t:U->U | (no (r-(s+t))) iff r in s+t }",
                "assert SomeNestedDifferenceBridge { all r,s,t:U->U | (some (r-(s+t))) iff r not in s+t }",
                "assert NoRestrictedDifferenceBridge { all a:set U,r,s:U->U | (no ((a<:r)-(a<:s))) iff (a<:r) in (a<:s) }",
                "assert SomeRestrictedDifferenceBridge { all a:set U,r,s:U->U | (some ((a<:r)-(a<:s))) iff (a<:r) not in (a<:s) }",
                "assert NoIntersectionDifferenceBridge { all r,s,t:U->U | (no (r-(s&t))) iff r in s&t }",
                "assert DifferenceDisjoint { all p,q:set U | ((p-q)&q)=none }",
                "assert DifferenceRecombine { all p,q:set U | ((p-q)+(p&q))=p }",
                "assert DifferenceAbsorb { all p,q:set U | ((p-q)&(p+q))=(p-q) }",
                "assert DifferenceComplement { all p,q:set U | p-(p&q)=p-q }",
                "assert DifferenceOfDifference { all p,q:set U | p-(p-q)=p&q }",
                "assert SubsetNoneBridge { all p:set U | (p in none) iff no p }",
                "assert NotSubsetNoneBridge { all p:set U | (p not in none) iff some p }",
                "assert QuantifiedSomeMembership { all a,b:set U | (some x:a | x in b) iff some (a&b) }",
                "assert QuantifiedNoMembership { all a,b:set U | (no x:a | x in b) iff no (a&b) }",
                "assert QuantifiedAllMembership { all a,b:set U | (all x:a | x in b) iff a in b }",
                "assert QuantifiedSomeNotMembership { all a,b:set U | (some x:a | x not in b) iff some (a-b) }",
                "assert QuantifiedNoNotMembership { all a,b:set U | (no x:a | x not in b) iff a in b }",
                "assert QuantifiedAllNotMembership { all a,b:set U | (all x:a | x not in b) iff no (a&b) }",
                "assert QuantifiedSetSomeMembership { all a,b:set U | (some x:set a | x in b) }",
                "assert QuantifiedSetNoMembership { all a,b:set U | not (no x:set a | x in b) }",
                "assert QuantifiedSetAllMembership { all a,b:set U | (all x:set a | x in b) iff a in b }",
                "assert QuantifiedLoneSomeNotMembership { all a,b:set U | (some x:lone a | x not in b) iff some (a-b) }",
                "assert QuantifiedLoneNoNotMembership { all a,b:set U | (no x:lone a | x not in b) iff a in b }",
                "assert QuantifiedSetAllNotMembership { all a,b:set U | not (all x:set a | x not in b) }",
                "assert QuantifiedSomeCardinalitySomeMembership { all a,b:set U | (some x:some a | x in b) iff some (a&b) }",
                "assert QuantifiedSomeCardinalityNoMembership { all a,b:set U | (no x:some a | x in b) iff no (a&b) }",
                "assert QuantifiedSomeCardinalityAllMembership { all a,b:set U | (all x:some a | x in b) iff a in b }",
                "check DifferenceUnionLeft for 3",
                "check DifferenceIntersectLeft for 3",
                "check DifferenceIntersectRight for 3",
                "check DifferenceUnionRight for 3",
                "check JoinUnionRight for 3",
                "check JoinUnionLeft for 3",
                "check JoinChainUnionRight for 3",
                "check JoinChainUnionMiddle for 3",
                "check JoinChainUnionLeft for 3",
                "check SomeUnion for 3",
                "check NoUnion for 3",
                "check NestedDifferenceChain for 3",
                "check RightNestedDifference for 3",
                "check IntersectionDifference for 3",
                "check IntersectionDifferences for 3",
                "check OverlappingDifference for 3",
                "check IntersectionDifferencesNary for 3",
                "check ProductDifferenceLeft for 3",
                "check ProductDifferenceRight for 3",
                "check ProductDifferenceMiddle for 3",
                "check ProductIntersectionFixed for 3",
                "check ProductIntersectionAll for 3",
                "check ProductIntersectionNary for 3",
                "check ProductIntersectionTernary for 3",
                "check ProductIntersectionResidual for 3",
                "check ProductAciDuplicate for 3",
                "check JoinAciUnion for 3",
                "check JoinAciDuplicate for 3",
                "check JoinAciIntersection for 3",
                "check TransposeJoin for 3",
                "check TransposeClosure for 3",
                "check TransposeReflexiveClosure for 3",
                "check TransposeDomain for 3",
                "check TransposeRange for 3",
                "check TransposeEmpty for 3",
                "check ClosureEmpty for 3",
                "check ReflexiveClosureEmpty for 3",
                "check DomainRelationUnion for 3",
                "check DomainRelationIntersection for 3",
                "check DomainRelationDifference for 3",
                "check DomainRestrictorUnion for 3",
                "check DomainRestrictorIntersection for 3",
                "check DomainRestrictorDifference for 3",
                "check RangeRelationUnion for 3",
                "check RangeRelationIntersection for 3",
                "check RangeRelationDifference for 3",
                "check RangeRestrictorUnion for 3",
                "check RangeRestrictorIntersection for 3",
                "check RangeRestrictorDifference for 3",
                "check DomainUnivIdentity for 3",
                "check RangeUnivIdentity for 3",
                "check DomainCarrierIdentity for 3",
                "check RangeCarrierIdentity for 3",
                "check DomainCarrierBound for 3",
                "check RangeCarrierBound for 3",
                "check DomainEmptyRestrictor for 3",
                "check RangeEmptyRestrictor for 3",
                "check DomainEmptyRelation for 3",
                "check RangeEmptyRelation for 3",
                "check NestedDomain for 3",
                "check NestedRange for 3",
                "check CommutingRestrictions for 3",
                "check RestrictionGrid for 3",
                "check JoinOuterDomain for 3",
                "check JoinOuterRange for 3",
                "check JoinMiddleRestriction for 3",
                "check JoinOuterDomainChain for 3",
                "check JoinOuterRangeChain for 3",
                "check JoinAdjacentRestrictions for 3",
                "check JoinHigherArity for 3",
                "check JoinLeftUnaryBoundary for 3",
                "check JoinRightUnaryBoundary for 3",
                "check UnaryRestrictionSides for 3",
                "check ReflexiveSubset for 3",
                "check ReflexiveEquality for 3",
                "check ReflexiveNotSubset for 3",
                "check ReflexiveInequality for 3",
                "check SubsetUnion for 3",
                "check IntersectionSubset for 3",
                "check DifferenceSubset for 3",
                "check DomainRestrictionSubset for 3",
                "check RangeRestrictionSubset for 3",
                "check UnionSubset for 3",
                "check UnionNotSubset for 3",
                "check SubsetIntersection for 3",
                "check NotSubsetIntersection for 3",
                "check UnionSubsetNary for 3",
                "check SubsetIntersectionNary for 3",
                "check NoDifferenceBridge for 3",
                "check SomeDifferenceBridge for 3",
                "check NoNestedDifferenceBridge for 3",
                "check SomeNestedDifferenceBridge for 3",
                "check NoRestrictedDifferenceBridge for 3",
                "check SomeRestrictedDifferenceBridge for 3",
                "check NoIntersectionDifferenceBridge for 3",
                "check DifferenceDisjoint for 3",
                "check DifferenceRecombine for 3",
                "check DifferenceAbsorb for 3",
                "check DifferenceComplement for 3",
                "check DifferenceOfDifference for 3",
                "check SubsetNoneBridge for 3",
                "check NotSubsetNoneBridge for 3",
                "check QuantifiedSomeMembership for 3",
                "check QuantifiedNoMembership for 3",
                "check QuantifiedAllMembership for 3",
                "check QuantifiedSomeNotMembership for 3",
                "check QuantifiedNoNotMembership for 3",
                "check QuantifiedAllNotMembership for 3",
                "run { some r,s,t:U -> U | r.(s&t) != r.s & r.t } for 3",
                "run { some p,q:set U | some p and some q and no (p&q) } for 3",
                "run { some p,q,r:set U | ((p-q)-r) != p-(q-r) } for 3",
                "run { some p,q,r,s:set U |",
                "  ((p->r)-(q->s)) != ((p-q)->(r-s)) } for 3",
                "run { some r,s:U->U | ~(r.s) != (~r).(~s) } for 3",
                "run { some a,b:set U,r,s:U->U |",
                "  (a<:r)+(b<:s) != (a+b)<:(r+s) } for 3",
                "run { some a,b:set U,r,s:U->U |",
                "  (a<:r)-(b<:s) != (a-b)<:(r-s) } for 3",
                "run { some r:U->U | ~(^r) != *(~r) } for 3",
                "run { some a:set U,r:U->U | ~(a<:r) != a<:(~r) } for 3",
                "run { some a:set U,r:U->U | (a<:r) != r } for 3",
                "run { *(none->none) != (none->none) } for 3",
                "run { some a:set U,r,s:U->U | (a<:r).s != (r.s):>a } for 3",
                "run { some a,b:set U,r,s:U->U | (r:>a).s != r.(b<:s) } for 3",
                "run { some a,r:set U,s:U->U | (a<:r).s != a<:(r.s) } for 3",
                "run { some a:set U,r:U->U,s:set U | r.(s:>a) != (r.s):>a } for 3",
                "run { some a:set U,r:U->U | a<:r != r:>a } for 3",
                "run { some r,s:set U | not (r in s) } for 3",
                "run { some r,s,t:set U | r in s+t and not (r in s or r in t) } for 3",
                "run { some r,s,t:set U | r&s in t and not (r in t or s in t) } for 3",
                "run { some a,b:set U | #a=1 and some (a&b) and not (some disj x,y:a | x in b) } for 3",
                "run { some a,b:set U | some a and no (a&b) and (some x:a | x in b+x) } for 3",
                "run { some a,b:set U | #(a&b)=2 and not (one x:a | x in b) } for 3",
                "");
        CompModule module = CompUtil.parseEverything_fromString(
                edu.mit.csail.sdg.alloy4.A4Reporter.NOP, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);

        String[][] equivalentPairs = {
                {"differenceUnionLeft", "differenceUnionLeftExpected"},
                {"differenceIntersectLeft", "differenceIntersectLeftExpected"},
                {"differenceIntersectRight", "differenceIntersectRightExpected"},
                {"differenceUnionRight", "differenceUnionRightExpected"},
                {"joinUnionRight", "joinUnionRightExpected"},
                {"joinUnionLeft", "joinUnionLeftExpected"},
                {"joinChainUnionRight", "joinChainUnionRightExpected"},
                {"joinChainUnionMiddle", "joinChainUnionMiddleExpected"},
                {"joinChainUnionLeft", "joinChainUnionLeftExpected"},
                {"someUnion", "someUnionExpected"},
                {"noUnion", "noUnionExpected"},
                {"nestedDifference", "nestedDifferenceExpected"},
                {"nestedDifferenceNary", "nestedDifferenceNaryExpected"},
                {"rightNestedDifference", "rightNestedDifferenceExpected"},
                {"intersectionDifference", "intersectionDifferenceExpected"},
                {"intersectionDifferences", "intersectionDifferencesExpected"},
                {"overlappingDifference", "overlappingDifferenceExpected"},
                {"intersectionDifferencesNary", "intersectionDifferencesNaryExpected"},
                {"productDifferenceLeft", "productDifferenceLeftExpected"},
                {"productDifferenceRight", "productDifferenceRightExpected"},
                {"productDifferenceMiddle", "productDifferenceMiddleExpected"},
                {"productIntersectionFixed", "productIntersectionFixedExpected"},
                {"productIntersectionAll", "productIntersectionAllExpected"},
                {"productIntersectionNary", "productIntersectionNaryExpected"},
                {"productIntersectionTernary", "productIntersectionTernaryExpected"},
                {"productIntersectionResidual", "productIntersectionResidualExpected"},
                {"productAciDuplicate", "productAciDuplicateExpected"},
                {"joinAciUnion", "joinAciUnionExpected"},
                {"joinAciDuplicate", "joinAciDuplicateExpected"},
                {"joinAciIntersection", "joinAciIntersectionExpected"},
                {"transposeJoin", "transposeJoinExpected"},
                {"transposeClosure", "transposeClosureExpected"},
                {"transposeReflexiveClosure", "transposeReflexiveClosureExpected"},
                {"transposeDomain", "transposeDomainExpected"},
                {"transposeRange", "transposeRangeExpected"},
                {"transposeEmpty", "transposeEmptyExpected"},
                {"closureEmpty", "closureEmptyExpected"},
                {"reflexiveClosureEmpty", "reflexiveClosureEmptyExpected"},
                {"domainRelationUnion", "domainRelationUnionExpected"},
                {"domainRelationIntersection", "domainRelationIntersectionExpected"},
                {"domainRelationDifference", "domainRelationDifferenceExpected"},
                {"domainRestrictorUnion", "domainRestrictorUnionExpected"},
                {"domainRestrictorIntersection", "domainRestrictorIntersectionExpected"},
                {"domainRestrictorDifference", "domainRestrictorDifferenceExpected"},
                {"rangeRelationUnion", "rangeRelationUnionExpected"},
                {"rangeRelationIntersection", "rangeRelationIntersectionExpected"},
                {"rangeRelationDifference", "rangeRelationDifferenceExpected"},
                {"rangeRestrictorUnion", "rangeRestrictorUnionExpected"},
                {"rangeRestrictorIntersection", "rangeRestrictorIntersectionExpected"},
                {"rangeRestrictorDifference", "rangeRestrictorDifferenceExpected"},
                {"domainUnivIdentity", "domainUnivIdentityExpected"},
                {"rangeUnivIdentity", "rangeUnivIdentityExpected"},
                {"domainCarrierIdentity", "domainCarrierIdentityExpected"},
                {"rangeCarrierIdentity", "rangeCarrierIdentityExpected"},
                {"domainCarrierBound", "domainCarrierBoundExpected"},
                {"rangeCarrierBound", "rangeCarrierBoundExpected"},
                {"domainEmptyRestrictor", "domainEmptyRestrictorExpected"},
                {"rangeEmptyRestrictor", "rangeEmptyRestrictorExpected"},
                {"domainEmptyRelation", "domainEmptyRelationExpected"},
                {"rangeEmptyRelation", "rangeEmptyRelationExpected"},
                {"nestedDomain", "nestedDomainExpected"},
                {"nestedRange", "nestedRangeExpected"},
                {"commutingRestrictions", "commutingRestrictionsExpected"},
                {"restrictionGrid", "restrictionGridExpected"},
                {"joinOuterDomain", "joinOuterDomainExpected"},
                {"joinOuterRange", "joinOuterRangeExpected"},
                {"joinMiddleRestriction", "joinMiddleRestrictionExpected"},
                {"joinOuterDomainChain", "joinOuterDomainChainExpected"},
                {"joinOuterRangeChain", "joinOuterRangeChainExpected"},
                {"joinAdjacentRestrictions", "joinAdjacentRestrictionsExpected"},
                {"joinHigherArity", "joinHigherArityExpected"},
                {"joinBoundSlots", "joinBoundSlotsExpected"},
                {"joinLeftUnaryBoundary", "joinLeftUnaryBoundaryExpected"},
                {"joinRightUnaryBoundary", "joinRightUnaryBoundaryExpected"},
                {"unaryRangeRestriction", "unaryDomainRestriction"},
                {"reflexiveSubset", "reflexiveTruth"},
                {"reflexiveEquality", "reflexiveTruth"},
                {"reflexiveNotSubset", "reflexiveFalse"},
                {"reflexiveInequality", "reflexiveFalse"},
                {"subsetUnion", "subsetUnionExpected"},
                {"intersectionSubset", "intersectionSubsetExpected"},
                {"differenceSubset", "differenceSubsetExpected"},
                {"domainRestrictionSubset", "domainRestrictionSubsetExpected"},
                {"rangeRestrictionSubset", "rangeRestrictionSubsetExpected"},
                {"unionSubset", "unionSubsetExpected"},
                {"unionNotSubset", "unionNotSubsetExpected"},
                {"subsetIntersection", "subsetIntersectionExpected"},
                {"notSubsetIntersection", "notSubsetIntersectionExpected"},
                {"unionSubsetNary", "unionSubsetNaryExpected"},
                {"subsetIntersectionNary", "subsetIntersectionNaryExpected"},
                {"noDifferenceBridge", "noDifferenceBridgeExpected"},
                {"someDifferenceBridge", "someDifferenceBridgeExpected"},
                {"noNestedDifferenceBridge", "noNestedDifferenceBridgeExpected"},
                {"someNestedDifferenceBridge", "someNestedDifferenceBridgeExpected"},
                {"noRestrictedDifferenceBridge", "noRestrictedDifferenceBridgeExpected"},
                {"someRestrictedDifferenceBridge", "someRestrictedDifferenceBridgeExpected"},
                {"noIntersectionDifferenceBridge", "noIntersectionDifferenceBridgeExpected"},
                {"differenceDisjoint", "differenceDisjointExpected"},
                {"differenceRecombine", "differenceRecombineExpected"},
                {"differenceAbsorb", "differenceAbsorbExpected"},
                {"differenceComplement", "differenceComplementExpected"},
                {"differenceOfDifference", "differenceOfDifferenceExpected"},
                {"subsetNoneBridge", "subsetNoneBridgeExpected"},
                {"notSubsetNoneBridge", "notSubsetNoneBridgeExpected"},
                {"quantifiedSomeMembership", "quantifiedSomeMembershipExpected"},
                {"quantifiedNoMembership", "quantifiedNoMembershipExpected"},
                {"quantifiedAllMembership", "quantifiedAllMembershipExpected"},
                {"quantifiedSomeNotMembership", "quantifiedSomeNotMembershipExpected"},
                {"quantifiedNoNotMembership", "quantifiedNoNotMembershipExpected"},
                {"quantifiedAllNotMembership", "quantifiedAllNotMembershipExpected"},
                {"quantifiedSetSomeMembership", "quantifiedSetSomeMembershipExpected"},
                {"quantifiedSetNoMembership", "quantifiedSetNoMembershipExpected"},
                {"quantifiedSetAllMembership", "quantifiedSetAllMembershipExpected"},
                {"quantifiedLoneSomeNotMembership", "quantifiedLoneSomeNotMembershipExpected"},
                {"quantifiedLoneNoNotMembership", "quantifiedLoneNoNotMembershipExpected"},
                {"quantifiedSetAllNotMembership", "quantifiedSetAllNotMembershipExpected"},
                {"quantifiedSomeCardinalitySomeMembership", "quantifiedSomeCardinalitySomeMembershipExpected"},
                {"quantifiedSomeCardinalityNoMembership", "quantifiedSomeCardinalityNoMembershipExpected"},
                {"quantifiedSomeCardinalityAllMembership", "quantifiedSomeCardinalityAllMembershipExpected"}
        };
        for (String[] pair : equivalentPairs) {
            CanonicalAlloyPipeline.Prepared left;
            CanonicalAlloyPipeline.Prepared right;
            try {
                left = prepare(visitor, pair[0]);
                right = prepare(visitor, pair[1]);
            } catch (RuntimeException failure) {
                throw new IllegalStateException(
                        "certified relational factoring could not prepare "
                                + pair[0], failure);
            }
            check(left.equivalentTo(right)
                            && CanonicalAlloyPipeline.distance(left, right) == 0,
                    "certified relational factoring failed for " + pair[0]);
        }

        check(!prepare(visitor, "joinIntersection").equivalentTo(
                        prepare(visitor, "joinIntersectionExpanded")),
                "JOIN must not distribute over intersection as an equality");
        check(!prepare(visitor, "someIntersection").equivalentTo(
                        prepare(visitor, "someIntersectionExpanded")),
                "some-intersection must not become conjunction of nonemptiness");
        check(!prepare(visitor, "noIntersection").equivalentTo(
                        prepare(visitor, "noIntersectionExpanded")),
                "no-intersection must not become disjunction of emptiness");
        check(!prepare(visitor, "nestedDifference").equivalentTo(
                        prepare(visitor, "rightNestedDifference")),
                "right-nested difference must not use the left-chain rule");
        check(!prepare(visitor, "productDifferenceMultiple").equivalentTo(
                        prepare(visitor, "productDifferenceMultipleUnsound")),
                "a product difference must not factor multiple coordinates at once");
        check(!prepare(visitor, "transposeJoin").equivalentTo(
                        prepare(visitor, "transposeJoinWrong")),
                "converse must reverse JOIN operand order");
        check(!prepare(visitor, "transposeClosure").equivalentTo(
                        prepare(visitor, "transposeClosureKindNearMiss")),
                "converse-closure commutation must preserve the closure kind");
        check(!prepare(visitor, "transposeDomain").equivalentTo(
                        prepare(visitor, "transposeRestrictionNearMiss")),
                "converse must swap domain restriction to range restriction");
        check(!prepare(visitor, "domainVariableNearMiss").equivalentTo(
                        prepare(visitor, "domainVariableNearMissUnsound")),
                "a relation-valued variable is not a full restriction carrier");
        check(!prepare(visitor, "reflexiveClosureEmpty").equivalentTo(
                        prepare(visitor, "reflexiveClosureEmptyNearMiss")),
                "reflexive closure of empty is iden, not empty");
        check(!prepare(visitor, "restrictionDiagonal").equivalentTo(
                        prepare(visitor, "restrictionDiagonalUnsound")),
                "a diagonal restriction union must not synthesize cross terms");
        check(!prepare(visitor, "restrictionDifferenceMultiple").equivalentTo(
                        prepare(visitor, "restrictionDifferenceMultipleUnsound")),
                "a restriction difference must not factor two changing coordinates");
        check(!prepare(visitor, "joinWrongOuterSide").equivalentTo(
                        prepare(visitor, "joinWrongOuterSideCandidate")),
                "an endpoint restriction must not move to the opposite JOIN endpoint");
        check(!prepare(visitor, "joinWrongMiddleGuard").equivalentTo(
                        prepare(visitor, "joinWrongMiddleGuardCandidate")),
                "an internal JOIN restriction must preserve its guard invocation");
        check(!prepare(visitor, "joinLeftUnaryBoundary").equivalentTo(
                        prepare(visitor, "joinLeftUnaryWrong")),
                "a restricted unary left operand guards the eliminated JOIN boundary");
        check(!prepare(visitor, "joinRightUnaryBoundary").equivalentTo(
                        prepare(visitor, "joinRightUnaryWrong")),
                "a restricted unary right operand guards the eliminated JOIN boundary");
        check(!prepare(visitor, "distinctSubset").equivalentTo(
                        prepare(visitor, "reflexiveTruth")),
                "distinct relation invocations must not use reflexive subset truth");
        check(!prepare(visitor, "subsetUnionSplit").equivalentTo(
                        prepare(visitor, "subsetUnionSplitUnsound")),
                "subset of a union must not become a disjunction of whole-subset tests");
        check(!prepare(visitor, "intersectionSubsetSplit").equivalentTo(
                        prepare(visitor, "intersectionSubsetSplitUnsound")),
                "an intersection subset must not become a disjunction of parent subsets");
        check(!prepare(visitor, "quantifiedDisjNearMiss").equivalentTo(
                        prepare(visitor, "quantifiedDisjNearMissUnsound")),
                "a disjoint multi-binder declaration must remain outside membership elimination");
        check(!prepare(visitor, "quantifiedSelfDependentNearMiss").equivalentTo(
                        prepare(visitor, "quantifiedSelfDependentNearMissUnsound")),
                "a membership RHS that refers to its binder must remain quantified");
        check(!prepare(visitor, "quantifiedOneNearMiss").equivalentTo(
                        prepare(visitor, "quantifiedOneNearMissUnsound")),
                "formula-level ONE is not existential membership");
        CanonicalAlloyPipeline.Prepared unaryBinding =
                prepare(visitor, "unaryRelationBinding");
        CanonicalAlloyPipeline.Prepared binaryBinding =
                prepare(visitor, "binaryRelationBinding");
        check(!unaryBinding.equivalentTo(binaryBinding)
                        && CanonicalAlloyPipeline.distance(
                                unaryBinding, binaryBinding) > 0,
                "binding identity must distinguish unary and binary relation parameters");

        edu.mit.csail.sdg.translator.A4Options options =
                new edu.mit.csail.sdg.translator.A4Options();
        options.solver = edu.mit.csail.sdg.translator.A4Options.SatSolver.SAT4J;
        List<edu.mit.csail.sdg.ast.Command> commands = module.getAllCommands();
        for (int index = 0; index < commands.size(); index++) {
            edu.mit.csail.sdg.translator.A4Solution result =
                    edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
                            .execute_command(
                                    edu.mit.csail.sdg.alloy4.A4Reporter.NOP,
                                    module.getAllReachableSigs(),
                                    commands.get(index),
                                    options);
            boolean expectedSatisfiable = index >= 108;
            check(result != null
                            && result.satisfiable() == expectedSatisfiable,
                    "Alloy relational-factoring witness failed at command "
                            + index);
        }
    }

    private static void checkTemporalReferenceAuthorityIsolation(
            MASGVisitor visitor) throws Exception {
        Canonical.Prepared left = Canonical.prepare(
                visitor.getForest().get(visitor.getForestId("temporalLeft")));
        Canonical.Prepared right = Canonical.prepare(
                visitor.getForest().get(visitor.getForestId("temporalRight")));
        NormalForm leftOwner = left.normalizedForms().get(0);
        NormalForm rightOwner = right.normalizedForms().get(0);
        EGraphNode leftReference = firstOpcode(
                leftOwner.getMatrixEGraph(), Opcode.REF);
        EGraphNode rightReference = firstOpcode(
                rightOwner.getMatrixEGraph(), Opcode.REF);
        check(leftReference != null && rightReference != null,
                "parser-backed temporal predicates must retain owner references");

        java.lang.reflect.Method authorityId = EGraphNode.class
                .getDeclaredMethod("temporalReferenceAuthorityId");
        authorityId.setAccessible(true);
        long leftId = (long) authorityId.invoke(leftReference);
        long rightId = (long) authorityId.invoke(rightReference);
        check(leftId > 0L && rightId > 0L && leftId != rightId,
                "temporal authority ids must be globally unique across owners");

        java.lang.reflect.Field root = NormalForm.class
                .getDeclaredField("matrixEGraphRoot");
        root.setAccessible(true);
        root.set(rightOwner, leftReference);
        expectThrows(IllegalStateException.class,
                rightOwner::requireAdmittedTemporalTree);
    }

    private static EGraphNode firstOpcode(EGraphNode node, Opcode opcode) {
        if (node == null) {
            return null;
        }
        if (node.getOpcode() == opcode) {
            return node;
        }
        for (EGraphNode child : node.getChildren()) {
            EGraphNode found = firstOpcode(child, opcode);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static CanonicalAlloyPipeline.Prepared prepare(
            MASGVisitor visitor,
            String predicate,
            SemanticProfile semanticProfile) {
        Integer id = visitor.getForestId(predicate);
        check(id != null, "missing MASG predicate " + predicate);
        Multigraph graph = visitor.getForest().get(id);
        check(graph != null, "missing MASG graph " + predicate);
        return CanonicalAlloyPipeline.prepare(graph, semanticProfile);
    }

    private static boolean hasOperatorOutput(
            CanonicalAlloyPipeline.Prepared prepared,
            String operator,
            GraphType outputType) {
        return prepared.semanticArtifact().classes().values().stream()
                .flatMap(record -> record.shapeWitnesses().keySet().stream())
                .map(shape -> shape.node())
                .anyMatch(node -> operator.equals(node.operator().operator())
                        && outputType.equals(node.outputType()));
    }

    private static boolean hasOutputType(
            CanonicalAlloyPipeline.Prepared prepared,
            GraphType outputType) {
        return prepared.semanticArtifact().classes().values().stream()
                .flatMap(record -> record.shapeWitnesses().keySet().stream())
                .map(shape -> shape.node())
                .anyMatch(node -> outputType.equals(node.outputType()));
    }

    private static boolean hasRelationFamilyOperatorOutput(
            CanonicalAlloyPipeline.Prepared prepared,
            String operator) {
        return prepared.semanticArtifact().classes().values().stream()
                .flatMap(record -> record.shapeWitnesses().keySet().stream())
                .map(shape -> shape.node())
                .anyMatch(node -> operator.equals(node.operator().operator())
                        && AlloyTypeBridge.isRelationFamily(node.outputType()));
    }

    private static DependentChainCertificate dependentChain(
            CanonicalAlloyPipeline.Prepared prepared,
            DependentChainKind kind) {
        return prepared.semanticArtifact().dependentChainConstructions().stream()
                .filter(certificate -> certificate.source().kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing dependent " + kind + " construction"));
    }

    private static void assertReassociatedChain(
            CanonicalAlloyPipeline.Prepared left,
            CanonicalAlloyPipeline.Prepared right,
            DependentChainKind kind) {
        DependentChainCertificate leftCertificate = dependentChain(left, kind);
        DependentChainCertificate rightCertificate = dependentChain(right, kind);
        check(!leftCertificate.source().structuralKey().equals(
                        rightCertificate.source().structuralKey()),
                kind + " fixtures must expose distinct binary source associations");
        check(leftCertificate.target().structuralKey().equals(
                        rightCertificate.target().structuralKey()),
                kind + " reassociation must preserve one ordered dependent Seq target");
        check(leftCertificate.operandTypes().equals(rightCertificate.operandTypes()),
                kind + " reassociation must preserve every positional type proof");
    }

    private static void checkDuplicateIdDependentChainTransfer() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        EGraphNode first = binaryNode(
                701,
                Opcode.ARROW,
                relationalLeaf(710, "zA", "A"),
                relationalLeaf(711, "zBC", "B", "C"),
                ExactAlloyType.relation(List.of("A", "B", "C")),
                Metatype.SET,
                profile);
        EGraphNode second = binaryNode(
                701,
                Opcode.ARROW,
                relationalLeaf(712, "aAB", "A", "B"),
                relationalLeaf(713, "aC", "C"),
                ExactAlloyType.relation(List.of("A", "B", "C")),
                Metatype.SET,
                profile);
        EGraphNode root = new EGraphNode(
                700, Opcode.AND, new ArrayList<>(), true, -1, true,
                Metatype.BOOLEAN, profile);
        root.setExactAlloyType(ExactAlloyType.boolType());
        root.addChild(unaryFormula(720, Opcode.SOME, first, profile));
        root.addChild(unaryFormula(721, Opcode.SOME, second, profile));

        NormalForm form = new NormalForm();
        form.addEClass(root);
        form.normalize();
        TheoryAlloyAdapter.Result result = TheoryAlloyAdapter.adapt(
                List.of(form), profile);
        check(result.dependentChainSourceCertificates().size() == 2,
                "duplicate parser IDs must retain two dependent source occurrences");
        long distinctLineages = result.dependentChainSourceCertificates().keySet().stream()
                .map(EGraphNode::getSourceOccurrenceLineage)
                .distinct()
                .count();
        check(distinctLineages == 2,
                "dependent source occurrences must transfer through distinct lineages");
        RepairProjection.project(
                result,
                List.of(form));
        check(true,
                "duplicate-ID dependent certificates attach to their positional type proofs");
    }

    private static void checkFrozenProjectionOwnership() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        EGraphNode source = new EGraphNode(
                680, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                Metatype.BOOLEAN, profile);
        source.setSourceName("true");
        source.setExactAlloyType(ExactAlloyType.boolType());
        NormalForm form = new NormalForm();
        form.addEClass(source);

        TheoryAlloyAdapter.Result result = TheoryAlloyAdapter.adapt(
                List.of(form), profile);
        check(form.isFrozenForCertification()
                        && source.isFrozenForCertification(),
                "adaptation must freeze its complete repair projection source");
        expectThrows(IllegalStateException.class,
                () -> source.setSourceName("false"));
        expectThrows(UnsupportedOperationException.class,
                () -> form.getTemporalChildren().add(new NormalForm()));

        NormalForm foreign = new NormalForm();
        EGraphNode foreignSource = new EGraphNode(
                681, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                Metatype.BOOLEAN, profile);
        foreignSource.setSourceName("true");
        foreignSource.setExactAlloyType(ExactAlloyType.boolType());
        foreign.addEClass(foreignSource);
        expectThrows(IllegalArgumentException.class,
                () -> RepairProjection.project(result, List.of(foreign)));
        check(java.util.Arrays.stream(RepairProjection.class.getMethods())
                        .noneMatch(method -> method.getName().equals("project")
                                && method.getParameterCount() == 3),
                "repair projection must expose no caller-supplied authority field");

        RepairView view = RepairProjection.project(result, List.of(form));
        check("true".equals(view.phases().get(0).matrix().payload()),
                "projection must retain the frozen certified source payload");
    }

    private static void checkEmptyRelationArity(CompModule module) throws Exception {
        ExactAlloyType unary = ExactAlloyType.from(
                CompUtil.parseOneExpression_fromString(module, "none").type());
        ExactAlloyType binary = ExactAlloyType.from(
                CompUtil.parseOneExpression_fromString(module, "none -> none").type());
        ExactAlloyType ternary = ExactAlloyType.from(
                CompUtil.parseOneExpression_fromString(
                        module, "(none -> none) -> none").type());
        check(unary.kind() == ExactAlloyType.Kind.EMPTY_RELATION
                        && unary.relationArity() == 1,
                "unary empty relation must retain parser-proved arity");
        check(binary.kind() == ExactAlloyType.Kind.EMPTY_RELATION
                        && binary.relationArity() == 2,
                "binary empty relation must retain parser-proved arity");
        check(ternary.kind() == ExactAlloyType.Kind.EMPTY_RELATION
                        && ternary.relationArity() == 3,
                "ternary empty relation must retain parser-proved arity");
        check(unary.alternatives().isEmpty()
                        && binary.alternatives().isEmpty()
                        && ternary.alternatives().isEmpty(),
                "empty relation conversion must not invent erased signature columns");

        GraphType unaryGraph = AlloyTypeBridge.graphType(unary);
        GraphType binaryGraph = AlloyTypeBridge.graphType(binary);
        GraphType ternaryGraph = AlloyTypeBridge.graphType(ternary);
        check(!unaryGraph.equals(binaryGraph)
                        && !binaryGraph.equals(ternaryGraph)
                        && !unaryGraph.equals(ternaryGraph),
                "empty relation graph identity must distinguish arity");
        check(AlloyTypeBridge.isCommutativeRelationCarrier(binaryGraph),
                "an arity-bearing empty relation remains a relation carrier");
        check(!AlloyTypeBridge.isCommutativeRelationCarrier(
                        GraphType.constructor("AlloyEmptyRelation$arity=02")),
                "noncanonical empty relation arity must reject");

        ExactAlloyType arityless = ExactAlloyType.from(Type.EMPTY);
        check(arityless.kind() == ExactAlloyType.Kind.UNKNOWN,
                "arityless empty parser type must fail closed");
        expectThrows(IllegalStateException.class,
                () -> AlloyTypeBridge.graphType(arityless));

        byte[] serialized = serialize(binary);
        check(binary.equals(deserialize(serialized)),
                "valid exact empty relation must survive serialization");
        byte[] marker = binary.stableString().getBytes(StandardCharsets.UTF_8);
        int markerOffset = indexOf(serialized, marker);
        check(markerOffset >= 0,
                "serialized exact type must contain its stable-key commitment");
        byte[] forged = serialized.clone();
        forged[markerOffset + marker.length - 2] = '3';
        expectThrows(InvalidObjectException.class,
                () -> deserialize(forged));

        Type componentType = CompUtil.parseOneExpression_fromString(
                module, "Component").type();
        ExactAlloyType component = ExactAlloyType.fromParser(
                componentType, module);
        check(component.ancestryAlternatives().equals(List.of(
                        List.of(List.of("Component", "Product", "univ")))),
                "parser-derived subtype ancestry must retain its exact parent path");
        check(component.hasParserAuthenticatedAncestry(),
                "parsed extends hierarchy must carry live parser authority");
        ExactAlloyType parserInt = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(module, "Int").type(),
                module);
        check(parserInt.kind() == ExactAlloyType.Kind.INT
                        && parserInt.hasParserAuthenticatedAncestry(),
                "a parsed exact Int occurrence must retain transient module authority");
        ExactAlloyType parserEmpty = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(module, "none").type(),
                module);
        check(parserEmpty.kind() == ExactAlloyType.Kind.EMPTY_RELATION
                        && parserEmpty.relationArity() == 1
                        && parserEmpty.hasParserAuthenticatedAncestry(),
                "a parsed empty relation must retain transient module authority without inventing columns");
        ExactAlloyType componentIntProduct =
                ExactAlloyType.parserCertifiedCartesianProduct(
                        List.of(component, parserInt));
        check(componentIntProduct.kind() == ExactAlloyType.Kind.RELATION
                        && componentIntProduct.relationArity() == 2
                        && componentIntProduct.alternatives().equals(
                                List.of(List.of("Component", "Int"))),
                "authenticated Int must contribute exactly one unary product column");
        ExactAlloyType componentPosition = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(
                        module, "Component -> Position").type(),
                module);
        ExactAlloyType product = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(module, "Product").type(),
                module);
        ExactAlloyType position = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(module, "Position").type(),
                module);
        ExactAlloyType productPosition = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(
                        module, "Product -> Position").type(),
                module);
        ExactAlloyType domainRestrictedProductPosition =
                ExactAlloyType.parserCertifiedDomainRestriction(
                        component, productPosition);
        check(domainRestrictedProductPosition.sameOccurrenceEvidenceAs(
                        componentPosition),
                "domain restriction must narrow exactly the first correlated column");
        ExactAlloyType rangeRestrictedComponentPosition =
                ExactAlloyType.parserCertifiedRangeRestriction(
                        componentPosition, position);
        check(rangeRestrictedComponentPosition.sameOccurrenceEvidenceAs(
                        componentPosition),
                "range restriction must narrow exactly the final correlated column");
        ExactAlloyType disjointDomainRestriction =
                ExactAlloyType.parserCertifiedDomainRestriction(
                        position, componentPosition);
        check(disjointDomainRestriction.kind()
                        == ExactAlloyType.Kind.EMPTY_RELATION
                        && disjointDomainRestriction.relationArity() == 2
                        && disjointDomainRestriction.hasParserAuthenticatedAncestry(),
                "a disjoint restriction must retain authenticated empty relation arity");
        ExactAlloyType transposedComponentPosition =
                ExactAlloyType.parserCertifiedTranspose(componentPosition);
        check(transposedComponentPosition.alternatives().equals(
                        List.of(List.of("Position", "Component")))
                        && ExactAlloyType.parserCertifiedTranspose(
                                transposedComponentPosition).equals(
                                        componentPosition),
                "parser-certified transpose must reverse correlated columns involutively");
        ExactAlloyType componentPositionUnion =
                ExactAlloyType.parserCertifiedRelationUnion(
                        List.of(component, ExactAlloyType.fromParser(
                                CompUtil.parseOneExpression_fromString(
                                        module, "Position").type(),
                                module)));
        check(componentPositionUnion.kind() == ExactAlloyType.Kind.RELATION
                        && componentPositionUnion.relationArity() == 1
                        && componentPositionUnion.alternatives().containsAll(
                                List.of(List.of("Component"), List.of("Position"))),
                "a derived relation union must retain every correlated operand alternative");
        ExactAlloyType componentWithEmpty =
                ExactAlloyType.parserCertifiedRelationUnion(
                        List.of(component, parserEmpty));
        check(componentWithEmpty.equals(component)
                        && componentWithEmpty.sameOccurrenceEvidenceAs(component),
                "a parser-certified empty relation must be the union identity");
        ExactAlloyType componentProductOverlap =
                ExactAlloyType.parserCertifiedRelationIntersection(
                        List.of(component, product));
        check(componentProductOverlap.equals(component)
                        && componentProductOverlap.sameOccurrenceEvidenceAs(component),
                "intersection type derivation must retain the certified subtype alternative");
        ExactAlloyType componentPositionOverlap =
                ExactAlloyType.parserCertifiedRelationIntersection(
                        List.of(component, position));
        check(componentPositionOverlap.kind()
                        == ExactAlloyType.Kind.EMPTY_RELATION
                        && componentPositionOverlap.relationArity() == 1
                        && componentPositionOverlap.hasParserAuthenticatedAncestry(),
                "intersection of unrelated parser signatures must derive an authenticated empty carrier");
        ExactAlloyType componentEmptyOverlap =
                ExactAlloyType.parserCertifiedRelationIntersection(
                        List.of(component, parserEmpty));
        check(componentEmptyOverlap.kind()
                        == ExactAlloyType.Kind.EMPTY_RELATION
                        && componentEmptyOverlap.sameOccurrenceEvidenceAs(
                                componentPositionOverlap),
                "a parser-certified empty relation must absorb intersection");
        ExactAlloyType componentMinusPosition =
                ExactAlloyType.parserCertifiedRelationDifference(
                        component, position);
        check(componentMinusPosition == component
                        && componentMinusPosition.sameOccurrenceEvidenceAs(component),
                "relation difference must retain its authenticated left static family");
        ExactAlloyType emptyMinusComponent =
                ExactAlloyType.parserCertifiedRelationDifference(
                        parserEmpty, component);
        check(emptyMinusComponent == parserEmpty,
                "empty relational difference must retain its authenticated left arity");
        ExactAlloyType emptyComponentProduct =
                ExactAlloyType.parserCertifiedCartesianProduct(
                        List.of(componentPositionOverlap, component));
        check(emptyComponentProduct.kind()
                        == ExactAlloyType.Kind.EMPTY_RELATION
                        && emptyComponentProduct.relationArity() == 2
                        && emptyComponentProduct.hasParserAuthenticatedAncestry(),
                "an authenticated empty Cartesian factor must produce an authenticated empty product");
        ExactAlloyType intUnion = ExactAlloyType.parserCertifiedRelationUnion(
                List.of(parserInt, parserInt));
        check(intUnion.kind() == ExactAlloyType.Kind.INT
                        && intUnion.hasParserAuthenticatedAncestry(),
                "an all-Int relation union must preserve exact Int overload identity");
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedCartesianProduct(
                        List.of(component, ExactAlloyType.intType())));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationUnion(
                        List.of(component, ExactAlloyType.unaryRelation("Position"))));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationIntersection(
                        List.of(component, ExactAlloyType.unaryRelation("Position"))));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationDifference(
                        component, ExactAlloyType.unaryRelation("Position")));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedDomainRestriction(
                        ExactAlloyType.unaryRelation("Component"),
                        productPosition));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedDomainRestriction(
                        componentPosition, productPosition));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedTranspose(
                        ExactAlloyType.relation(List.of("Left", "Right"))));
        ExactAlloyType heterogeneousInt = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(
                        module, "Int + Component").type(),
                module);
        check(heterogeneousInt.kind() == ExactAlloyType.Kind.RELATION
                        && heterogeneousInt.relationArity() == 1
                        && heterogeneousInt.alternatives().equals(List.of(
                                List.of("Int"), List.of("Component"))),
                "an Int-containing heterogeneous relation must retain every exact alternative");
        check(AlloyTypeBridge.isRelationFamily(
                        AlloyTypeBridge.graphType(heterogeneousInt))
                        && !GraphType.INT.equals(
                                AlloyTypeBridge.graphType(heterogeneousInt)),
                "a heterogeneous Int relation must not receive the exact Int carrier");
        ExactAlloyType heterogeneousProduct = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(
                        module, "Int + Product").type(),
                module);
        check(component.isParserCertifiedRelationSubfamilyOf(product),
                "a parser-authenticated extends edge must prove relation inclusion");
        check(heterogeneousInt.isParserCertifiedRelationSubfamilyOf(
                        heterogeneousProduct),
                "correlated Int/subtype alternatives must widen column-wise");
        check(!heterogeneousProduct.isParserCertifiedRelationSubfamilyOf(
                        heterogeneousInt)
                        && !component.isParserCertifiedRelationSubfamilyOf(position),
                "reverse and unrelated ancestry must remain widening barriers");
        CompModule foreignModule = CompUtil.parseEverything_fromString(
                edu.mit.csail.sdg.alloy4.A4Reporter.NOP,
                "module foreign_type_authority\n"
                        + "sig Product {}\n"
                        + "sig Component extends Product {}\n");
        ExactAlloyType foreignProduct = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(
                        foreignModule, "Product").type(),
                foreignModule);
        ExactAlloyType foreignInt = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(
                        foreignModule, "Int").type(),
                foreignModule);
        check(!component.isParserCertifiedRelationSubfamilyOf(foreignProduct),
                "same-spelled types from another parser module must not prove widening");
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedCartesianProduct(
                        List.of(component, foreignInt)));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedCartesianProduct(
                        List.of(componentPositionOverlap, foreignProduct)));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationUnion(
                        List.of(component, foreignProduct)));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationIntersection(
                        List.of(component, foreignProduct)));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationIntersection(
                        List.of(parserEmpty, foreignProduct)));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationDifference(
                        component, foreignProduct));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedDomainRestriction(
                        foreignProduct, productPosition));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationDifference(
                        component, componentPosition));
        ExactAlloyType componentRoundTrip = (ExactAlloyType) deserialize(
                serialize(component));
        check(componentRoundTrip.ancestryAlternatives().equals(
                        component.ancestryAlternatives()),
                "current exact-type serialization must preserve subtype ancestry");
        check(!componentRoundTrip.hasParserAuthenticatedAncestry(),
                "serialized ancestry must not retain live parser authority");
        check(!componentRoundTrip.isParserCertifiedRelationSubfamilyOf(product),
                "serialized ancestry metadata must not prove a live subtype widening");
        ExactAlloyType componentPositionRoundTrip =
                (ExactAlloyType) deserialize(serialize(componentPosition));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedTranspose(
                        componentPositionRoundTrip));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationUnion(
                        List.of(componentRoundTrip, position)));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationIntersection(
                        List.of(componentRoundTrip, position)));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationDifference(
                        componentRoundTrip, position));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedDomainRestriction(
                        componentRoundTrip, productPosition));
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRangeRestriction(
                        componentPositionRoundTrip, position));
        ExactAlloyType parserEmptyRoundTrip = (ExactAlloyType) deserialize(
                serialize(parserEmpty));
        check(!parserEmptyRoundTrip.hasParserAuthenticatedAncestry(),
                "serialized empty-relation evidence must lose live parser authority");
        expectThrows(IllegalArgumentException.class,
                () -> ExactAlloyType.parserCertifiedRelationIntersection(
                        List.of(parserEmptyRoundTrip, component)));
        expectThrows(IllegalArgumentException.class,
                () -> AlloyTypeBridge.dependentColumns(componentRoundTrip));
        expectThrows(IllegalArgumentException.class,
                () -> AlloyTypeBridge.dependentColumns(
                        ExactAlloyType.from(componentType)));

        PrimSig syntheticParent = new PrimSig("SyntheticParent");
        PrimSig syntheticChild = new PrimSig("SyntheticChild", syntheticParent);
        ExactAlloyType synthetic = ExactAlloyType.from(syntheticChild.type());
        check(!synthetic.hasParserAuthenticatedAncestry(),
                "public PrimSig constructors must not mint parser ancestry authority");
        expectThrows(IllegalArgumentException.class,
                () -> AlloyTypeBridge.dependentColumns(synthetic));

        PrimSig parsedComponent = componentType.iterator().next().get(0);
        PrimSig transplanted = new PrimSig(
                "TransplantedChild",
                syntheticParent,
                parsedComponent.attributes.toArray(new Attr[0]));
        ExactAlloyType transplantedType = ExactAlloyType.fromParser(
                transplanted.type(), module);
        check(!transplantedType.hasParserAuthenticatedAncestry(),
                "a transferable parser Attr must not authorize a foreign PrimSig");
        expectThrows(IllegalArgumentException.class,
                () -> AlloyTypeBridge.dependentColumns(transplantedType));
        check(AlloyTypeBridge.dependentColumns(
                        ExactAlloyType.relation(List.of("SyntheticChild"))).size() == 1,
                "self-only public relation types must remain admissible");
        check(ObjectStreamClass.lookup(ExactAlloyType.class)
                        .getSerialVersionUID() == 3L,
                "exact-type stream version 3 must invalidate ancestry-free caches");

        EGraphNode.beginGraph();
        try {
            SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
            NormalForm unaryForm = emptyRelationForm(760, 1, profile);
            NormalForm binaryForm = emptyRelationForm(761, 2, profile);
            TheoryAlloyAdapter.Result unaryResult = TheoryAlloyAdapter.adapt(
                    List.of(unaryForm), profile);
            TheoryAlloyAdapter.Result binaryResult = TheoryAlloyAdapter.adapt(
                    List.of(binaryForm), profile);
            RepairView unaryView = RepairProjection.project(
                    unaryResult, List.of(unaryForm));
            RepairView binaryView = RepairProjection.project(
                    binaryResult, List.of(binaryForm));
            check(QuotientRepairDistance.distance(unaryView, binaryView) > 0,
                    "repair metric must distinguish cross-arity empty relations");
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static NormalForm emptyRelationForm(
            int id,
            int arity,
            SemanticProfile profile) {
        EGraphNode node = new EGraphNode(
                id, Opcode.CONSTANT, new ArrayList<>(), false, 0, false,
                Metatype.SET, profile);
        node.setSourceName("none");
        node.setExactAlloyType(ExactAlloyType.emptyRelation(arity));
        NormalForm form = new NormalForm();
        form.addEClass(node);
        return form;
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(
                new ByteArrayInputStream(bytes))) {
            return input.readObject();
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    private static void checkConcurrentProjectionFreeze() throws Exception {
        for (int iteration = 0; iteration < 32; iteration++) {
            EGraphNode.beginGraph();
            try {
                SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
                EGraphNode source = new EGraphNode(
                        690 + iteration, Opcode.CONSTANT, new ArrayList<>(),
                        false, 0, false, Metatype.BOOLEAN, profile);
                source.setSourceName("true");
                source.setExactAlloyType(ExactAlloyType.boolType());
                NormalForm form = new NormalForm();
                form.addEClass(source);

                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<TheoryAlloyAdapter.Result> result =
                        new AtomicReference<>();
                AtomicReference<Throwable> adaptationFailure = new AtomicReference<>();
                AtomicReference<Throwable> mutationFailure = new AtomicReference<>();
                Thread adapter = new Thread(() -> {
                    await(start);
                    try {
                        result.set(TheoryAlloyAdapter.adapt(List.of(form), profile));
                    } catch (Throwable failure) {
                        adaptationFailure.set(failure);
                    }
                }, "projection-freeze-adapter");
                Thread mutator = new Thread(() -> {
                    await(start);
                    try {
                        source.setSourceName("false");
                    } catch (Throwable failure) {
                        mutationFailure.set(failure);
                    }
                }, "projection-freeze-mutator");
                adapter.start();
                mutator.start();
                start.countDown();
                adapter.join();
                mutator.join();

                check(adaptationFailure.get() == null && result.get() != null,
                        "concurrent certification must complete without a mixed source");
                Throwable rejectedMutation = mutationFailure.get();
                check(rejectedMutation == null
                                || rejectedMutation instanceof IllegalStateException,
                        "a mutation losing the certification race must fail closed");
                RepairView view = RepairProjection.project(result.get(), List.of(form));
                check(source.getSourceName().equals(
                                view.phases().get(0).matrix().payload()),
                        "concurrent certification and projection must observe one source state");
                check(result.get().canonicalKey().equals(
                                TheoryAlloyAdapter.adapt(List.of(form), profile).canonicalKey()),
                        "a frozen source must reproduce the same certified key");
            } finally {
                EGraphNode.endGraph();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Projection race probe was interrupted", exception);
        }
    }

    private static void checkDependentSourceOccurrenceBinding(Opcode opcode) {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        EGraphNode first;
        EGraphNode second;
        if (opcode == Opcode.ARROW) {
            first = binaryNode(
                    750, opcode,
                    relationalLeaf(751, "firstA", "A"),
                    relationalLeaf(752, "firstB", "B"),
                    ExactAlloyType.relation(List.of("A", "B")),
                    Metatype.SET, profile);
            second = binaryNode(
                    750, opcode,
                    relationalLeaf(753, "secondA", "A"),
                    relationalLeaf(754, "secondB", "B"),
                    ExactAlloyType.relation(List.of("A", "B")),
                    Metatype.SET, profile);
        } else {
            first = binaryNode(
                    760, opcode,
                    relationalLeaf(761, "firstAB", "A", "B"),
                    relationalLeaf(762, "firstBC", "B", "C"),
                    ExactAlloyType.relation(List.of("A", "C")),
                    Metatype.SET, profile);
            second = binaryNode(
                    760, opcode,
                    relationalLeaf(763, "secondAB", "A", "B"),
                    relationalLeaf(764, "secondBC", "B", "C"),
                    ExactAlloyType.relation(List.of("A", "C")),
                    Metatype.SET, profile);
        }
        EGraphNode root = new EGraphNode(
                770, Opcode.AND, new ArrayList<>(), true, -1, true,
                Metatype.BOOLEAN, profile);
        root.setExactAlloyType(ExactAlloyType.boolType());
        root.addChild(unaryFormula(771, Opcode.SOME, first, profile));
        root.addChild(unaryFormula(772, Opcode.SOME, second, profile));

        NormalForm form = new NormalForm();
        form.addEClass(root);
        form.normalize();
        TheoryAlloyAdapter.Result result = TheoryAlloyAdapter.adapt(
                List.of(form), profile);
        List<Map.Entry<EGraphNode, TheoryAlloyAdapter.DependentChainSourceBinding>>
                bindings = new ArrayList<>(
                        result.dependentChainSourceBindings().entrySet());
        check(bindings.size() == 2,
                opcode + " same-typed fixture must retain two occurrence bindings");
        check(!bindings.get(0).getValue().sourceOccurrencePath().equals(
                        bindings.get(1).getValue().sourceOccurrencePath()),
                opcode + " same-typed occurrences need distinct stable paths");
        check(!bindings.get(0).getValue().sourceOccurrenceCommitment().equals(
                        bindings.get(1).getValue().sourceOccurrenceCommitment()),
                opcode + " same-typed occurrences need distinct source commitments");
        expectDependentBindingRejectsCertificateSwap(
                bindings.get(0).getKey(), bindings.get(1).getValue());

        EGraphNode mutatedRoot = bindings.get(0).getKey();
        expectThrows(IllegalStateException.class, () ->
                mutatedRoot.getChildren().get(0).setSourceName(
                        "postCertificationReplacement"));
        RepairProjection.project(result, List.of(form));
    }

    private static void expectDependentBindingRejectsCertificateSwap(
            EGraphNode source,
            TheoryAlloyAdapter.DependentChainSourceBinding wrongBinding) {
        checks++;
        try {
            java.lang.reflect.Constructor<
                    TheoryAlloyAdapter.DependentChainSourceBinding> constructor =
                    TheoryAlloyAdapter.DependentChainSourceBinding.class
                            .getDeclaredConstructor(
                                    EGraphNode.class,
                                    String.class,
                                    DependentChainCertificate.class);
            constructor.setAccessible(true);
            constructor.newInstance(
                    source,
                    wrongBinding.sourceOccurrencePath(),
                    wrongBinding.certificate());
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof IllegalArgumentException
                    || exception.getCause() instanceof IllegalStateException) {
                return;
            }
            throw new AssertionError(
                    "Dependent binding failed for an unrelated reason",
                    exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "Could not exercise dependent binding constructor", exception);
        }
        throw new AssertionError(
                "A same-typed dependent certificate was accepted for another occurrence");
    }

    private static void checkUnaryInteriorJoinDoesNotFlatten() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        EGraphNode leftR = relationalLeaf(780, "R", "X", "X");
        EGraphNode leftS = relationalLeaf(781, "S", "X");
        EGraphNode leftT = relationalLeaf(782, "T", "X", "X");
        EGraphNode left = binaryNode(
                783,
                Opcode.JOIN,
                binaryNode(
                        784,
                        Opcode.JOIN,
                        leftR,
                        leftS,
                        ExactAlloyType.unaryRelation("X"),
                        Metatype.SET,
                        profile),
                leftT,
                ExactAlloyType.unaryRelation("X"),
                Metatype.SET,
                profile);

        EGraphNode rightR = relationalLeaf(785, "R", "X", "X");
        EGraphNode rightS = relationalLeaf(786, "S", "X");
        EGraphNode rightT = relationalLeaf(787, "T", "X", "X");
        EGraphNode right = binaryNode(
                788,
                Opcode.JOIN,
                rightR,
                binaryNode(
                        789,
                        Opcode.JOIN,
                        rightS,
                        rightT,
                        ExactAlloyType.unaryRelation("X"),
                        Metatype.SET,
                        profile),
                ExactAlloyType.unaryRelation("X"),
                Metatype.SET,
                profile);

        NormalForm leftForm = new NormalForm();
        leftForm.addEClass(left);
        leftForm.normalize();
        NormalForm rightForm = new NormalForm();
        rightForm.addEClass(right);
        rightForm.normalize();
        TheoryAlloyAdapter.Result leftResult = TheoryAlloyAdapter.adapt(
                List.of(leftForm), profile);
        TheoryAlloyAdapter.Result rightResult = TheoryAlloyAdapter.adapt(
                List.of(rightForm), profile);
        check(!leftResult.canonicalKey().equals(rightResult.canonicalKey()),
                "JOIN with a unary interior operand must retain source association");
        RepairView leftView = RepairProjection.project(
                leftResult, List.of(leftForm));
        RepairView rightView = RepairProjection.project(
                rightResult, List.of(rightForm));
        check(QuotientRepairDistance.distance(leftView, rightView) > 0,
                "the unary-interior JOIN counterexample must not enter the zero kernel");
    }

    private static EGraphNode relationalLeaf(
            int id,
            String name,
            String... columns) {
        EGraphNode leaf = new EGraphNode(
                id, Opcode.GLOBALBINDING, new ArrayList<>(), false, 0, false,
                Metatype.SET);
        leaf.setSourceName(name);
        leaf.setSourceType(name);
        leaf.setExactAlloyType(ExactAlloyType.relation(Arrays.asList(columns)));
        return leaf;
    }

    private static EGraphNode binaryNode(
            int id,
            Opcode opcode,
            EGraphNode left,
            EGraphNode right,
            ExactAlloyType type,
            Metatype metatype,
            SemanticProfile profile) {
        EGraphNode node = new EGraphNode(
                id, opcode, new ArrayList<>(), false, 2, false, metatype, profile);
        node.setExactAlloyType(type);
        node.addChild(left);
        node.addChild(right);
        return node;
    }

    private static EGraphNode unaryFormula(
            int id,
            Opcode opcode,
            EGraphNode child,
            SemanticProfile profile) {
        EGraphNode node = new EGraphNode(
                id, opcode, new ArrayList<>(), false, 1, false,
                Metatype.BOOLEAN, profile);
        node.setExactAlloyType(ExactAlloyType.boolType());
        node.addChild(child);
        return node;
    }

    private static void checkUncertifiedSourceUnionRejected() {
        EGraphNode.beginGraph();
        try {
            EGraphNode left = relationalLeaf(730, "left", "S");
            EGraphNode right = relationalLeaf(731, "right", "S");
            EGraphNode.union(left.getEClassRef(), right.getEClassRef());
            NormalForm poisoned = new NormalForm();
            poisoned.addEClass(right);
            expectThrows(IllegalStateException.class, () ->
                    TheoryAlloyAdapter.adapt(
                            List.of(poisoned),
                            SemanticProfile.alloyOverflowForbidding()));
        } finally {
            EGraphNode.endGraph();
        }
    }

    private static void checkConcreteDependentMismatchRejected() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        EGraphNode malformed = binaryNode(
                740,
                Opcode.ARROW,
                relationalLeaf(741, "leftA", "A"),
                relationalLeaf(742, "rightB", "B"),
                ExactAlloyType.unaryRelation("X"),
                Metatype.SET,
                profile);
        NormalForm form = new NormalForm();
        form.addEClass(malformed);
        expectThrows(IllegalStateException.class, () ->
                TheoryAlloyAdapter.adapt(List.of(form), profile));
    }

    private static void checkMetricParity(
            MASGVisitor visitor,
            String leftName,
            String rightName) {
        Multigraph leftGraph = visitor.getForest().get(visitor.getForestId(leftName));
        Multigraph rightGraph = visitor.getForest().get(visitor.getForestId(rightName));
        Canonical.Prepared left = Canonical.prepare(leftGraph);
        Canonical.Prepared right = Canonical.prepare(rightGraph);
        CanonicalDistance.DistanceBreakdown expected =
                Canonical.distanceBreakdown(left, right);
        QuotientRepairDistance.Result actual = CanonicalAlloyPipeline.distanceEvaluation(
                CanonicalAlloyPipeline.prepare(left),
                CanonicalAlloyPipeline.prepare(right));
        check(actual.distance() == expected.distance()
                        && actual.temporalDistance() == expected.temporalDistance()
                        && actual.quantifierDistance() == expected.quantifierDistance()
                        && actual.matrixDistance() == expected.matrixDistance(),
                "faithful metric port must preserve every reference metric component for "
                        + leftName + " versus " + rightName
                        + ": expected total/temporal/quantifier/matrix="
                        + expected.distance() + "/" + expected.temporalDistance() + "/"
                        + expected.quantifierDistance() + "/" + expected.matrixDistance()
                        + ", actual=" + actual.distance() + "/" + actual.temporalDistance()
                        + "/" + actual.quantifierDistance() + "/" + actual.matrixDistance()
                        + ", leftIR=" + Canonical.irTemporalFol(left)
                        + ", rightIR=" + Canonical.irTemporalFol(right));
    }

    private static int legacyDistance(
            MASGVisitor visitor,
            String leftName,
            String rightName) {
        Multigraph leftGraph = visitor.getForest().get(visitor.getForestId(leftName));
        Multigraph rightGraph = visitor.getForest().get(visitor.getForestId(rightName));
        return Canonical.distance(
                Canonical.prepare(leftGraph), Canonical.prepare(rightGraph));
    }

    private static long matrixBinderCount(
            MASGVisitor visitor,
            String predicate,
            QuantiVar.Quantifier quantifier,
            String type) {
        Integer id = visitor.getForestId(predicate);
        check(id != null, "missing MASG predicate " + predicate);
        Multigraph graph = visitor.getForest().get(id);
        check(graph != null, "missing MASG graph " + predicate);
        Canonical.Prepared prepared = Canonical.prepare(graph);
        check(!prepared.normalizedForms().isEmpty(),
                "missing normalized form for " + predicate);
        NormalForm root = prepared.normalizedForms().get(0);
        return root.getMatrixQuantiVars().stream()
                .filter(variable -> variable.getQuantifier() == quantifier)
                .filter(variable -> type.equals(variable.getTypeName()))
                .count();
    }

    private static boolean hasLocalQuantifierCarrier(
            MASGVisitor visitor,
            String predicate,
            Opcode quantifier,
            String carrier) {
        Integer id = visitor.getForestId(predicate);
        check(id != null, "missing MASG predicate " + predicate);
        Multigraph graph = visitor.getForest().get(id);
        check(graph != null, "missing MASG graph " + predicate);
        Canonical.Prepared prepared = Canonical.prepare(graph);
        check(!prepared.normalizedForms().isEmpty(),
                "missing normalized form for " + predicate);
        return hasLocalQuantifierCarrier(
                prepared.normalizedForms().get(0).getMatrixEGraph(), quantifier, carrier);
    }

    private static boolean hasLocalQuantifierCarrier(
            EGraphNode node,
            Opcode quantifier,
            String carrier) {
        if (node.getOpcode() == quantifier) {
            for (EGraphNode declaration : node.getChildren()) {
                if ((declaration.getOpcode() == Opcode.GENERICRELDECL
                                || declaration.getOpcode() == Opcode.DISJ
                                || declaration.getOpcode() == Opcode.VAR
                                || declaration.getOpcode() == Opcode.DISJVAR)
                        && !declaration.getChildren().isEmpty()
                        && containsSourceName(declaration.getChildren().get(0), carrier)) {
                    return true;
                }
            }
        }
        for (EGraphNode child : node.getChildren()) {
            if (hasLocalQuantifierCarrier(child, quantifier, carrier)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSourceName(EGraphNode node, String sourceName) {
        if (sourceName.equals(node.getSourceName())) {
            return true;
        }
        for (EGraphNode child : node.getChildren()) {
            if (containsSourceName(child, sourceName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasInheritedAlias(
            MASGVisitor visitor,
            String predicate,
            String alias) {
        Integer id = visitor.getForestId(predicate);
        check(id != null, "missing MASG predicate " + predicate);
        Multigraph graph = visitor.getForest().get(id);
        check(graph != null, "missing MASG graph " + predicate);
        Canonical.Prepared prepared = Canonical.prepare(graph);
        for (NormalForm form : prepared.normalizedForms()) {
            for (QuantiVar variable : form.getInheritedQuantiVars()) {
                if (variable.getOriginalNames().contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static CertifiedSemanticArtifact copyWithFlatConstructions(
            CertifiedSemanticArtifact source,
            List<? extends FlatConstructionCertificate> flatConstructions) {
        return source.withFlatConstructions(flatConstructions);
    }

    private static CertifiedSemanticArtifact copyWithContainerConstructions(
            CertifiedSemanticArtifact source,
            List<? extends ContainerConstructionCertificate> containerConstructions) {
        return source.withContainerConstructions(containerConstructions);
    }

    private static void expectThrows(
            Class<? extends Throwable> expected, ThrowingRunnable operation) {
        checks++;
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (expected.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName() + " but got " + throwable,
                    throwable);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String source() {
        return "module phase_i\n"
                + "open util/ordering[S] as orderingS\n"
                + "open util/integer\n"
                + "sig S { r, q: set S }\n"
                + "sig SequenceOwner { sequenceField: seq S }\n"
                + "sig T {}\n"
                + "sig Protected, Trash in S {}\n"
                + "sig SubsetCarrier, SubsetSibling in S {}\n"
                + "sig SubsetLeaf, SubsetLeafTwo in SubsetCarrier {}\n"
                + "sig MultiParent {}\n"
                + "sig MultiLeft, MultiRight extends MultiParent {}\n"
                + "sig MultiSubset in MultiLeft + MultiRight {}\n"
                + "sig State { trans: Event -> State }\n"
                + "sig Init in State {}\n"
                + "sig Event {}\n"
                + "sig Person { Teaches: set Class }\n"
                + "sig Group {}\n"
                + "sig Class { Groups: Person -> Group }\n"
                + "sig Teacher in Person {}\n"
                + "sig Student in Person {}\n"
                + "sig Product {}\n"
                + "sig Position {}\n"
                + "sig Component extends Product { position: one Position }\n"
                + "sig FamilyParent {}\n"
                + "sig FamilyA, FamilyB extends FamilyParent {}\n"
                + "sig FamilyC {}\n"
                + "sig FamilyHolder { chosen: set FamilyParent }\n"
                + "sig FastProject {}\n"
                + "sig FastOwnerA { sharedProjects: set FastProject }\n"
                + "sig FastOwnerB { sharedProjects: set FastProject }\n"
                + "abstract sig AbstractParent {}\n"
                + "sig AbstractA, AbstractB extends AbstractParent {}\n"
                + "sig AbstractHolder { absChosen: set AbstractParent }\n"
                + "abstract sig AbstractOuter {}\n"
                + "abstract sig AbstractInner extends AbstractOuter {}\n"
                + "sig AbstractLeafA, AbstractLeafB extends AbstractInner {}\n"
                + "abstract sig AbstractSingleParent {}\n"
                + "sig AbstractSingleChild extends AbstractSingleParent {}\n"
                + "sig NonAbstractParent {}\n"
                + "sig NonAbstractA, NonAbstractB extends NonAbstractParent {}\n"
                + "abstract sig AbstractSubsetParent {}\n"
                + "sig AbstractSubsetOnly in AbstractSubsetParent {}\n"
                + "enum EnumCarrier { EnumLeft, EnumRight }\n"
                + "pred alphaLeft { all x, y: S | y in x.r }\n"
                + "pred alphaRight { all a, b: S | a in b.r }\n"
                + "pred aciLeft { (some S and lone S) and one S }\n"
                + "pred aciRight { one S and (lone S and some S) }\n"
                + "pred andDuplicate { (some S) and (some S) }\n"
                + "pred andBare { some S }\n"
                + "pred quotientDuplicate {\n"
                + "  (all f: S | f not in Trash) and no Trash\n"
                + "}\n"
                + "pred quotientBare { no Trash }\n"
                + "pred orDuplicate { (some S) or (some S) }\n"
                + "pred orBare { some S }\n"
                + "pred iffNestedDuplicate {\n"
                + "  (some S and ((no T or no S) and no S)) iff "
                + "((some S and some S) or some T)\n"
                + "}\n"
                + "pred iffNestedExpanded {\n"
                + "  (not (some S and ((no T or no S) and no S)) or "
                + "((some S and some S) or some T)) and\n"
                + "  (not ((some S and some S) or some T) or "
                + "(some S and ((no T or no S) and no S)))\n"
                + "}\n"
                + "pred unionDuplicate { some (S + S) }\n"
                + "pred unionBare { some S }\n"
                + "pred intersectDuplicate { some (S & S) }\n"
                + "pred intersectBare { some S }\n"
                + "pred positive { some S }\n"
                + "pred negative { no S }\n"
                + "pred shadowLeft { all x: S | some x: S | x in S }\n"
                + "pred shadowRight { all a: S | some b: S | b in S }\n"
                + "pred disjointPred { all disj x, y: S | y in x.r }\n"
                + "pred nondisjoint { all x, y: S | y in x.r }\n"
                + "pred temporalLeft { after some S }\n"
                + "pred temporalRight { after no S }\n"
                + "pred temporalLet { always all s: S | let x = s.r | some x implies after some x }\n"
                + "pred temporalLetExpanded { always all s: S | some s.r implies after some s.r }\n"
                + "pred temporalDuplicate { (always some S) and (always some S) }\n"
                + "pred temporalBare { always some S }\n"
                + "pred temporalGuardDuplicate { always all s: S | after s in S or s not in S }\n"
                + "pred temporalEliminated { (some S) or (no S) or (always some S) }\n"
                + "pred fastFieldLeft {\n"
                + "  all p: FastProject | #((FastOwnerA <: sharedProjects).p) = 1\n"
                + "}\n"
                + "pred fastFieldAlpha {\n"
                + "  all q: FastProject | #((FastOwnerA <: sharedProjects).q) = 1\n"
                + "}\n"
                + "pred fastFieldWrongOwner {\n"
                + "  all p: FastProject | #((FastOwnerB <: sharedProjects).p) = 1\n"
                + "}\n"
                + "pred fastTemporalLeft {\n"
                + "  always all x, y: S | y in x.r implies eventually y in Trash\n"
                + "}\n"
                + "pred fastTemporalAlpha {\n"
                + "  always all a, b: S | b in a.r implies eventually b in Trash\n"
                + "}\n"
                + "pred fastTemporalPermutation {\n"
                + "  always all a, b: S | a in b.r implies eventually a in Trash\n"
                + "}\n"
                + "pred fastTemporalWrongEndpoint {\n"
                + "  always all a, b: S | b in a.r implies eventually a in Trash\n"
                + "}\n"
                + "pred tautology { no none }\n"
                + "pred mixedCarrierLeft { all x: S, y: T | x in S and y in T }\n"
                + "pred mixedCarrierRight { all a: S, b: T | a in S and b in T }\n"
                + "pred heterogeneousOrderLeft { all s: S, t: T | s in S and t in T }\n"
                + "pred heterogeneousOrderRight { all t: T, s: S | s in S and t in T }\n"
                + "pred domainAciLeft { always all x: Protected & Trash | x in S }\n"
                + "pred domainAciRight { always all x: Trash & Protected | x in S }\n"
                + "pred nestedUnionLeft { ((S + T) + FamilyC) = S }\n"
                + "pred nestedUnionRight { (S + (T + FamilyC)) = S }\n"
                + "pred equalityOrderLeft { S = Protected }\n"
                + "pred equalityOrderRight { Protected = S }\n"
                + "pred duplicateDisjoint { disj[S, S, Protected] }\n"
                + "pred heterogeneousDisjoint { disj[S, T] }\n"
                + "pred binaryArrowType { some (S -> T) }\n"
                + "pred intArrowType[x: Int, s: S] { some (x -> s) }\n"
                + "pred reversedArrowType { some (T -> S) }\n"
                + "pred binaryJoinType { some (State.trans) }\n"
                + "pred arrowAssocLeft { some ((S -> T) -> Protected) }\n"
                + "pred arrowAssocRight { some (S -> (T -> Protected)) }\n"
                + "pred joinAssocLeft { some ((State.trans).State) }\n"
                + "pred joinAssocRight { some (State.(trans.State)) }\n"
                + "pred rightUnivJoinLeft[x: State] { some ((x.trans).univ) }\n"
                + "pred rightUnivJoinRight[x: State] { some (x.(trans.univ)) }\n"
                + "pred leftUnivJoinLeft[x: State] { some ((univ.trans).x) }\n"
                + "pred leftUnivJoinRight[x: State] { some (univ.(trans.x)) }\n"
                + "pred subtypeBoundaryJoin { all p: Product | some p.position }\n"
                + "pred relationFamilyJoin {\n"
                + "  some (((FamilyA->FamilyA) + (FamilyB->FamilyB))."
                + "((FamilyA->FamilyC) + (FamilyB->FamilyC)))\n"
                + "}\n"
                + "pred disjointBoundaryJoin { no ((S->T).(Event->Group)) }\n"
                + "pred emptyIntersectJoin { no ((S & T).(S->T)) }\n"
                + "pred emptyUnionJoin { no (((S & T) + (Event & Group)).(S->T)) }\n"
                + "pred parameterJoinLeft[x:S] { some ((x.r).r) }\n"
                + "pred parameterJoinRight[x:S] { some (x.(r.r)) }\n"
                + "pred parameterTypeS[x:S] { some x }\n"
                + "pred parameterTypeT[x:T] { some x }\n"
                + "pred parameterTypeTNo[x:T] { no x }\n"
                + "pred prenexLoneLeft {\n"
                + "  all s: S, t: T | lone u: S | s in S and t in T and u in S\n"
                + "}\n"
                + "pred prenexLoneRight {\n"
                + "  all t: T | all s: S | lone u: S | s in S and t in T and u in S\n"
                + "}\n"
                + "pred booleanNeutralLeft { all f: S | f not in Trash }\n"
                + "pred booleanNeutralRight { all f: Trash | f in none }\n"
                + "pred plusNoneLeft { some (S + none) }\n"
                + "pred plusNoneRight { some S }\n"
                + "pred intersectNoneLeft { some (S & none) }\n"
                + "pred intersectNoneRight { some none }\n"
                + "pred selfMinusLeft { some (S - S) }\n"
                + "pred selfMinusRight { some none }\n"
                + "pred complementLeft { (some S) or not (some S) }\n"
                + "pred complementRight { no none }\n"
                + "pred contradictionLeft { (some S) and not (some S) }\n"
                + "pred contradictionRight { some none }\n"
                + "pred unionIdempotentLeft { some (S + S) }\n"
                + "pred unionIdempotentRight { some S }\n"
                + "pred intersectIdempotentLeft { some (S & S) }\n"
                + "pred intersectIdempotentRight { some S }\n"
                + "pred aciComplementLeft { some (S + S) or not (some S) }\n"
                + "pred aciComplementRight { no none }\n"
                + "pred aciCommutativeComplementLeft {\n"
                + "  some (Protected + Trash) or not (some (Trash + Protected))\n"
                + "}\n"
                + "pred aciAssociativeComplementLeft {\n"
                + "  some ((S + Protected) + Trash) or not (some (S + (Protected + Trash)))\n"
                + "}\n"
                + "pred aciSelfMinusLeft { some ((S + S) - S) }\n"
                + "pred aciSelfMinusRight { some none }\n"
                + "pred aciNearMiss {\n"
                + "  some (Protected + Trash) or not (some (Protected & Trash))\n"
                + "}\n"
                + "pred aciSlotComplementLeft[x: S] {\n"
                + "  ((some x.r) and (some x.r)) or no x.r\n"
                + "}\n"
                + "pred aciSlotComplementRight[x: S] { no none }\n"
                + "pred aciSlotNearMiss[x, y: S] {\n"
                + "  ((some x.r) and (some x.r)) or no y.r\n"
                + "}\n"
                + "pred aciSlotNearMissRight[x, y: S] { no none }\n"
                + "pred integerPlus { #S + #T = 1 }\n"
                + "pred integerPlusDuplicate { #S + #S = #S }\n"
                + "pred integerPlusBare { #S = #S }\n"
                + "pred integerMinus { #S - #T = 0 }\n"
                + "pred integerArithmeticPlus { 1 fun/add 1 = 2 }\n"
                + "pred integerArithmeticNearMiss { 1 = 2 }\n"
                + "pred integerArithmeticNested { (1 fun/add 1) fun/add 1 = 3 }\n"
                + "pred intSetUnion { some (Int + Int) }\n"
                + "pred intSetBare { some Int }\n"
                + "pred intSetDifference { no (Int - Int) }\n"
                + "pred intersectUniv { some (S & univ) }\n"
                + "pred minusNone { some (S - none) }\n"
                + "pred plusUniv { some (S + univ) }\n"
                + "pred univBare { some univ }\n"
                + "pred minusUniv { no (S - univ) }\n"
                + "pred noneMinus { no (none - S) }\n"
                + "pred heterogeneousIntUnion { Int + S = Int }\n"
                + "pred intUnionIdentity { Int = Int }\n"
                + "pred booleanTruth { no none }\n"
                + "pred booleanFalse { some none }\n"
                + "pred emptySubsetUnary { none in SubsetCarrier }\n"
                + "pred emptyNotSubsetUnary { none not in SubsetCarrier }\n"
                + "pred emptySubsetBinary { (none -> none) in State.trans }\n"
                + "pred emptyNotSubsetBinary { (none -> none) not in State.trans }\n"
                + "pred emptyJoin { some (none.(State -> State)) }\n"
                + "pred repeatedNestedUnionLeft { some ((S + T) + (S + T)) }\n"
                + "pred repeatedNestedUnionRight { some (S + T) }\n"
                + "pred distinctNestedUnionLeft {\n"
                + "  some ((S + T) + (Product + Position))\n"
                + "}\n"
                + "pred distinctNestedUnionRight { some (S + T + Product + Position) }\n"
                + "pred distinctNestedUnionNearMiss { some (S + T + Product) }\n"
                + "pred subtypeWidenedUnionLeft {\n"
                + "  some ((Int + Component) + Product)\n"
                + "}\n"
                + "pred subtypeWidenedUnionRight {\n"
                + "  some (Int + (Component + Product))\n"
                + "}\n"
                + "pred subtypeAbsorptionLeft {\n"
                + "  some ((FamilyA + FamilyB) + FamilyParent)\n"
                + "}\n"
                + "pred subtypeAbsorptionNested {\n"
                + "  some (FamilyA + (FamilyB + FamilyParent))\n"
                + "}\n"
                + "pred subtypeAbsorptionRight { some FamilyParent }\n"
                + "pred subtypeSiblingOnly { some (FamilyA + FamilyB) }\n"
                + "pred subsetAbsorptionLeft { some (Protected + Trash + S) }\n"
                + "pred nestedSubsetAbsorptionLeft { some (SubsetLeaf + SubsetCarrier) }\n"
                + "pred nestedSubsetAbsorptionGrouped {\n"
                + "  some ((SubsetLeaf + SubsetLeafTwo) + SubsetCarrier)\n"
                + "}\n"
                + "pred nestedSubsetAbsorptionRight { some SubsetCarrier }\n"
                + "pred nestedSubsetSiblingOnly {\n"
                + "  some (SubsetSibling + SubsetCarrier)\n"
                + "}\n"
                + "pred multiSubsetCommonCarrierLeft {\n"
                + "  some (MultiSubset + MultiParent)\n"
                + "}\n"
                + "pred multiSubsetCommonCarrierRight { some MultiParent }\n"
                + "pred multiSubsetSingleBranchLeft {\n"
                + "  some (MultiSubset + MultiLeft)\n"
                + "}\n"
                + "pred multiSubsetSingleBranchRight { some MultiLeft }\n"
                + "pred typedExpressionNotCarrierLeft {\n"
                + "  some (FamilyA + FamilyHolder.chosen)\n"
                + "}\n"
                + "pred typedExpressionNotCarrierRight {\n"
                + "  some FamilyHolder.chosen\n"
                + "}\n"
                + "pred typedExpressionWithCarrier {\n"
                + "  some ((FamilyA + FamilyHolder.chosen) + FamilyParent)\n"
                + "}\n"
                + "pred abstractCoverUnion { some (AbstractA + AbstractB) }\n"
                + "pred abstractCoverParent { some AbstractParent }\n"
                + "pred abstractCoverWithSubrelation {\n"
                + "  some (AbstractA + AbstractB + AbstractHolder.absChosen)\n"
                + "}\n"
                + "pred abstractCoverWithUnrelated {\n"
                + "  some (AbstractA + AbstractB + S)\n"
                + "}\n"
                + "pred abstractProductRightCoverUnion {\n"
                + "  some ((AbstractA -> AbstractA) + (AbstractA -> AbstractB))\n"
                + "}\n"
                + "pred abstractProductRightCoverParent {\n"
                + "  some (AbstractA -> AbstractParent)\n"
                + "}\n"
                + "pred abstractProductLeftCoverUnion {\n"
                + "  some ((AbstractA -> AbstractA) + (AbstractB -> AbstractA))\n"
                + "}\n"
                + "pred abstractProductLeftCoverParent {\n"
                + "  some (AbstractParent -> AbstractA)\n"
                + "}\n"
                + "pred abstractProductDiagonal {\n"
                + "  some ((AbstractA -> AbstractA) + (AbstractB -> AbstractB))\n"
                + "}\n"
                + "pred abstractProductFull {\n"
                + "  some (AbstractParent -> AbstractParent)\n"
                + "}\n"
                + "pred abstractProductWithUnrelated {\n"
                + "  some ((AbstractA -> AbstractA) + (AbstractA -> AbstractB)\n"
                + "    + (AbstractA -> S))\n"
                + "}\n"
                + "pred abstractProductTernaryCoverUnion {\n"
                + "  some ((AbstractA -> AbstractA -> AbstractA)\n"
                + "    + (AbstractA -> AbstractA -> AbstractB))\n"
                + "}\n"
                + "pred abstractProductTernaryCoverParent {\n"
                + "  some (AbstractA -> AbstractA -> AbstractParent)\n"
                + "}\n"
                + "pred abstractProductFullGridUnion {\n"
                + "  some ((AbstractA -> AbstractA) + (AbstractA -> AbstractB)\n"
                + "    + (AbstractB -> AbstractA) + (AbstractB -> AbstractB))\n"
                + "}\n"
                + "pred abstractProductPartialGridUnion {\n"
                + "  some ((AbstractA -> AbstractA) + (AbstractA -> AbstractB)\n"
                + "    + (AbstractB -> AbstractA))\n"
                + "}\n"
                + "pred abstractProductIntCoverUnion {\n"
                + "  some ((AbstractA -> Int) + (AbstractB -> Int))\n"
                + "}\n"
                + "pred abstractProductIntCoverParent {\n"
                + "  some (AbstractParent -> Int)\n"
                + "}\n"
                + "pred abstractProductIntLeftCoverUnion {\n"
                + "  some ((Int -> AbstractA) + (Int -> AbstractB))\n"
                + "}\n"
                + "pred abstractProductIntLeftCoverParent {\n"
                + "  some (Int -> AbstractParent)\n"
                + "}\n"
                + "pred ordinaryProductRightDistributed {\n"
                + "  some ((S -> S) + (S -> T))\n"
                + "}\n"
                + "pred ordinaryProductRightFactored {\n"
                + "  some (S -> (S + T))\n"
                + "}\n"
                + "pred ordinaryProductLeftDistributed {\n"
                + "  some ((S -> T) + (T -> T))\n"
                + "}\n"
                + "pred ordinaryProductLeftFactored {\n"
                + "  some ((S + T) -> T)\n"
                + "}\n"
                + "pred ordinaryProductTernaryDistributed {\n"
                + "  some ((S -> S -> S) + (S -> S -> T))\n"
                + "}\n"
                + "pred ordinaryProductTernaryFactored {\n"
                + "  some (S -> S -> (S + T))\n"
                + "}\n"
                + "pred ordinaryProductFullGrid {\n"
                + "  some ((S -> S) + (S -> T) + (T -> S) + (T -> T))\n"
                + "}\n"
                + "pred ordinaryProductFullFactored {\n"
                + "  some ((S + T) -> (S + T))\n"
                + "}\n"
                + "pred ordinaryProductPartialGrid {\n"
                + "  some ((S -> S) + (S -> T) + (T -> S))\n"
                + "}\n"
                + "pred ordinaryProductDiagonal {\n"
                + "  some ((S -> S) + (T -> T))\n"
                + "}\n"
                + "pred abstractNestedUnion {\n"
                + "  some (AbstractLeafA + AbstractLeafB)\n"
                + "}\n"
                + "pred abstractNestedInner { some AbstractInner }\n"
                + "pred abstractNestedOuter { some AbstractOuter }\n"
                + "pred abstractSingleChild { some AbstractSingleChild }\n"
                + "pred abstractSingleParent { some AbstractSingleParent }\n"
                + "pred abstractMissingBranch { some AbstractA }\n"
                + "pred nonAbstractChildren { some (NonAbstractA + NonAbstractB) }\n"
                + "pred nonAbstractParent { some NonAbstractParent }\n"
                + "pred abstractSubsetOnly { some AbstractSubsetOnly }\n"
                + "pred abstractSubsetParent { some AbstractSubsetParent }\n"
                + "pred enumCoverUnion { some (EnumLeft + EnumRight) }\n"
                + "pred enumCoverParent { some EnumCarrier }\n"
                + "pred idenLeft { some (iden.S) }\n"
                + "pred idenRight { some (S.iden) }\n"
                + "pred idenBare { some S }\n"
                + "pred idenMiddle { some ((S -> S).iden.(S -> S)) }\n"
                + "pred idenMiddleBare { some ((S -> S).(S -> S)) }\n"
                + "pred idenAll { some (iden.iden) }\n"
                + "pred idenAllBare { some iden }\n"
                + "pred joinNonIdentity { some (S.r) }\n"
                + "pred transposeIden { some ~iden }\n"
                + "pred closureIden { some ^iden }\n"
                + "pred rclosureIden { some *iden }\n"
                + "pred doubleTranspose { some ~(~r) }\n"
                + "pred transposeBare { some r }\n"
                + "pred transposeArrow { some ~(S -> T) }\n"
                + "pred transposeArrowReversed { some (T -> S) }\n"
                + "pred transposeArrowWrongOrder { some (S -> T) }\n"
                + "pred transposeUnionLeft { some ~(r + q) }\n"
                + "pred transposeUnionRight { some (~r + ~q) }\n"
                + "pred transposeIntersectLeft { some ~(r & q) }\n"
                + "pred transposeIntersectRight { some (~r & ~q) }\n"
                + "pred transposeMinusLeft { some ~(r - q) }\n"
                + "pred transposeMinusRight { some (~r - ~q) }\n"
                + "pred transposeContainerNearMiss { some (~r + ~q) }\n"
                + "pred transposeProductUnionLeft { some ~((S -> T) + (S -> S)) }\n"
                + "pred transposeProductUnionRight { some ((T -> S) + (S -> S)) }\n"
                + "pred transposeSlotUnionLeft[x: S -> S] { some ~(x + r) }\n"
                + "pred transposeSlotUnionRight[x: S -> S] { some (~x + ~r) }\n"
                + "pred closureClosure { some ^(^r) }\n"
                + "pred closureBare { some ^r }\n"
                + "pred rclosureRclosure { some *(*r) }\n"
                + "pred rclosureBare { some *r }\n"
                + "pred closureRclosure { some ^(*r) }\n"
                + "pred rclosureClosure { some *(^r) }\n"
                + "pred equalityComplementLeft { (S = Protected) or not (Protected = S) }\n"
                + "pred equalityComplementRight { no none }\n"
                + "pred equalityComplementNearMiss { (S = Protected) or not (T = S) }\n"
                + "pred equalitySlotComplementLeft[x, y: S] {\n"
                + "  (x = y) or not (y = x)\n"
                + "}\n"
                + "pred equalitySlotComplementRight[x, y: S] { no none }\n"
                + "pred equalitySlotComplementNearMiss[x, y: S] {\n"
                + "  (x = y) or not (x = x)\n"
                + "}\n"
                + "pred nestedIteFormula {\n"
                + "  (some Protected) implies\n"
                + "    ((some Trash) implies (some Product) else (some Position))\n"
                + "  else ((some Product) implies (some Protected) else (some Trash))\n"
                + "}\n"
                + "pred expandedIteFormula {\n"
                + "  ((some Protected) and\n"
                + "    (((some Trash) and (some Product))\n"
                + "      or ((not some Trash) and (some Position))))\n"
                + "  or ((not some Protected) and\n"
                + "    (((some Product) and (some Protected))\n"
                + "      or ((not some Product) and (some Trash))))\n"
                + "}\n"
                + "pred nestedIteExpression {\n"
                + "  some ((some Protected) implies\n"
                + "    ((some Trash) implies Product else Position)\n"
                + "  else ((some Product) implies Protected else Trash))\n"
                + "}\n"
                + "pred localGroupingLeft {\n"
                + "  let t = { x: State, y: State | some e: Event | x->e->y in trans } |\n"
                + "  all s: State | some i: Init | s in i.^t\n"
                + "}\n"
                + "pred localGroupingRight {\n"
                + "  let t = { x, y: State | some e: Event | x->e->y in trans } |\n"
                + "  all s: State | some i: Init | s in i.^t\n"
                + "}\n"
                + "pred alphaNearLeft { all x,y:S | x in y.r and some x.r and no y.r.r }\n"
                + "pred alphaNearRight { all x,y:S | x in y.r and no x.r and no y.r.r }\n"
                + "pred aciNearLeft { some S and no S.r and one S.r.r and lone S.r.r.r }\n"
                + "pred aciNearRight { some S and no S.r and one S.r.r and some S.r.r.r }\n"
                + "pred binderAll { all x:S | x in x.*r }\n"
                + "pred binderSome { some x:S | x in x.*r }\n"
                + "pred nestedSubtypeLeft {\n"
                + "  all p: Protected | all t: Trash | p in t.*r\n"
                + "}\n"
                + "pred nestedSubtypeRight {\n"
                + "  all t: Trash, p: Protected | p in t.*r\n"
                + "}\n"
                + "pred redundantDomainGuardLeft {\n"
                + "  always all p: Protected |\n"
                + "    p in Protected implies historically p in Protected\n"
                + "}\n"
                + "pred redundantDomainGuardRight {\n"
                + "  always all p: Protected | historically p in Protected\n"
                + "}\n"
                + "pred witnessedCarrierLeft {\n"
                + "  all c: Class |\n"
                + "    (some s: Person, g: Group | c->s->g in Groups)\n"
                + "    implies (some t: Teacher | t->c in Teaches)\n"
                + "}\n"
                + "pred witnessedCarrierRight {\n"
                + "  all c: Class, s: Person, g: Group | some t: Person |\n"
                + "    c->s->g in Groups implies t->c in Teaches and t in Teacher\n"
                + "}\n"
                + "pred commutativeBinderLeft {\n"
                + "  all p, q: Person | p in Teacher and q in Student implies p != q\n"
                + "}\n"
                + "pred commutativeBinderRight {\n"
                + "  no p: Student, q: Teacher | p = q\n"
                + "}\n"
                + "pred localComprehensionLeft {\n"
                + "  all s: State |\n"
                + "    s in Init.^{s1, s2: State | some s1.trans.s2}\n"
                + "    implies some (Init & s.^{s1, s2: State | some s1.trans.s2})\n"
                + "}\n"
                + "pred localComprehensionRight {\n"
                + "  let t = {x: State, y: State | some (x.trans).y} |\n"
                + "  all s: Init.^t | some s.^t & Init\n"
                + "}\n"
                + "pred localPermutationLeft {\n"
                + "  some {x, y: S | x in y.r}\n"
                + "}\n"
                + "pred localPermutationRight {\n"
                + "  some {a, b: S | b in a.r}\n"
                + "}\n"
                + "pred namedRefFirst { orderingS/first in S }\n"
                + "pred namedRefLast { orderingS/last in S }\n"
                + "pred temporalBinderTarget {\n"
                + "  always all x, y: S | x->y in r implies eventually y in S\n"
                + "}\n"
                + "pred temporalBinderWrongTarget {\n"
                + "  always all x, y: S | x->y in r implies eventually x in S\n"
                + "}\n"
                + "pred temporalBinderRenamed {\n"
                + "  always all a, b: S | b->a in r implies eventually a in S\n"
                + "}\n"
                + "pred phaseLocalTemporalBinder {\n"
                + "  no T and (all f: S | once f in Trash implies always f in Trash)\n"
                + "}\n"
                + "pred phaseLocalTemporalBinderRenamed {\n"
                + "  no T and (all g: S | once g in Trash implies always g in Trash)\n"
                + "}\n"
                + "pred phaseLocalTemporalBinderWrong {\n"
                + "  no T and (all f: S | once f in Trash implies always f not in Trash)\n"
                + "}\n"
                + "pred phaseLocalRepeatedReference {\n"
                + "  no T and (all f: S | always f in S iff after f in T)\n"
                + "}\n"
                + "pred unequalAlphaLeft {\n"
                + "  all x0, x1, x2: S | some x0.r and no x1.r and one x2.r\n"
                + "}\n"
                + "pred unequalAlphaRight { all y: S | no y.r }\n"
                + "pred scopedMaximum {\n"
                + "  all a, b, c: S | a in S and b in S and c in S\n"
                + "  all d: S | d in S\n"
                + "  all e, f: S | e in S and f in S\n"
                + "  all g: S | g in S\n"
                + "  all h: S | all i: T | h in S and i in T\n"
                + "}\n"
                + "pred nestedAll { all a: S | all b: S | a in S and b in S }\n"
                + "pred allUnderOr { (all a: S | some (S-a)) or (all b: S | some (S-b)) }\n"
                + "pred someUnderOr { (some a: S | some (S-a)) or (some b: S | some (S-b)) }\n"
                + "pred nestedSomeUnderOr {\n"
                + "  (some a: S | some b: S | a in S and b in S)\n"
                + "  or (some c: S | some d: S | c in S and d in S)\n"
                + "}\n"
                + "pred someUnderAnd { (some a: S | some (S-a)) and (some b: S | some (S-b)) }\n"
                + "pred quantifierBarrier {\n"
                + "  (all x: S | some (S-x))\n"
                + "  and (some y: T | all z: S | y in T and z in S)\n"
                + "}\n"
                + "pred scopedTemporal {\n"
                + "  all a: S | some (S-a)\n"
                + "  all b: S | after some (S-b)\n"
                + "}\n";
    }
}

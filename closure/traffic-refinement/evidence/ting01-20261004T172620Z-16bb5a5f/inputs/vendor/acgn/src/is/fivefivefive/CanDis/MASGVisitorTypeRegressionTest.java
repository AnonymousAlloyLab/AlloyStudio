package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.alloy.FieldRelation;
import is.fivefivefive.ACGN.alloy.SigSymbol;
import is.fivefivefive.ACGN.alloy.VarSymbol;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.QuantiVar;
import parser.ast.nodes.ModelUnit;

public final class MASGVisitorTypeRegressionTest {
    private MASGVisitorTypeRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        String source = String.join("\n",
                "open util/time",
                "sig User { follows: set User }",
                "sig Photo {}",
                "sig File {}",
                "sig Owner { x: set File }",
                "sig A {}",
                "sig B { A: set B }",
                "sig C { dup: set File }",
                "sig D { dup: set File }",
                "sig Protected, Trash in File {}",
                "sig Course {}",
                "sig Instructor { courses: set Course }",
                "sig Lecturer in Instructor {}",
                "pred candidate {",
                "  all x: univ | x in User implies x in User",
                "  all u: User | all f: u - u.follows | f in User",
                "  all y: User | some z: y | z in User",
                "  all n: none | n = n",
                "  all pt: Protected & Trash | pt = Protected",
                "}",
                "pred direct { all pt: Protected & Trash | pt = Protected }",
                "pred signatureShadow { all Trash: File | File in Trash }",
                "pred signatureReference { File in Trash }",
                "pred qualifiedSignature { all Trash: File | File in this/Trash }",
                "pred qualifiedSignatureReference { all t: File | File in Trash }",
                "pred qualifiedField { all x: File | some @x }",
                "pred qualifiedFieldReference { all y: File | some x }",
                "pred collidingSignature { some this/A }",
                "pred fieldC { some C.dup }",
                "pred fieldD { some D.dup }",
                "pred importedSignature { some Time }",
                "pred nestedQuantifierShadow {",
                "  all x: File | (all x: File | no x) and some x",
                "}",
                "pred nestedQuantifierRenamed {",
                "  all outer: File | (all inner: File | no inner) and some outer",
                "}",
                "pred nestedLetShadow {",
                "  let x = File | (let x = none | no x) and some x",
                "}",
                "pred nestedLetRenamed {",
                "  let outer = File | (let inner = none | no inner) and some outer",
                "}",
                "pred letOuterBinding {",
                "  all y: File | let x = y | some y: File | x != y",
                "}",
                "pred letOuterBindingRenamed {",
                "  all outer: File | let x = outer | some inner: File | x != inner",
                "}",
                "pred letCapturedReference {",
                "  all y: File | some y: File | y != y",
                "}",
                "sig A_B {}",
                "pred delimiterCollision {",
                "  all C: A_B, B_C: this/A | no C and no B_C",
                "}",
                "pred delimiterRenamed {",
                "  all left: A_B, right: this/A | no left and no right",
                "}",
                "pred duplicateBinder {",
                "  all x, x, y: File | no x and no y",
                "}",
                "pred duplicateBinderRenamed {",
                "  all unused, x, y: File | no x and no y",
                "}",
                "pred guardedNestedCorrect {",
                "  all t: Lecturer | some c: Course | c in t.courses",
                "}",
                "pred guardedNestedOverconstrained {",
                "  all p: Instructor | some c: Course |",
                "    p in Lecturer implies c in p.courses",
                "}",
                "pred oracle { no none }",
                "assert quantified {",
                "  all p: Photo, u1, u2: User | u1 = u2 implies p = p",
                "}");

        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        checkVisitorLocalSentinels(module, visitor);
        checkIntegerCarrierSetOperators();
        checkAbstractSignatureCovers();
        checkRelationalUnaryIdentities();
        checkRelationalDistributions();

        Multigraph candidate = graph(visitor, "candidate");
        Multigraph direct = graph(visitor, "direct");
        Multigraph signatureShadow = graph(visitor, "signatureShadow");
        Multigraph signatureReference = graph(visitor, "signatureReference");
        Multigraph qualifiedSignature = graph(visitor, "qualifiedSignature");
        Multigraph qualifiedSignatureReference =
                graph(visitor, "qualifiedSignatureReference");
        Multigraph qualifiedField = graph(visitor, "qualifiedField");
        Multigraph qualifiedFieldReference = graph(visitor, "qualifiedFieldReference");
        Multigraph collidingSignature = graph(visitor, "collidingSignature");
        Multigraph fieldC = graph(visitor, "fieldC");
        Multigraph fieldD = graph(visitor, "fieldD");
        Multigraph importedSignature = graph(visitor, "importedSignature");
        Multigraph nestedQuantifierShadow = graph(visitor, "nestedQuantifierShadow");
        Multigraph nestedQuantifierRenamed = graph(visitor, "nestedQuantifierRenamed");
        Multigraph nestedLetShadow = graph(visitor, "nestedLetShadow");
        Multigraph nestedLetRenamed = graph(visitor, "nestedLetRenamed");
        Multigraph letOuterBinding = graph(visitor, "letOuterBinding");
        Multigraph letOuterBindingRenamed = graph(visitor, "letOuterBindingRenamed");
        Multigraph letCapturedReference = graph(visitor, "letCapturedReference");
        Multigraph delimiterCollision = graph(visitor, "delimiterCollision");
        Multigraph delimiterRenamed = graph(visitor, "delimiterRenamed");
        Multigraph duplicateBinder = graph(visitor, "duplicateBinder");
        Multigraph duplicateBinderRenamed = graph(visitor, "duplicateBinderRenamed");
        Multigraph guardedNestedCorrect = graph(visitor, "guardedNestedCorrect");
        Multigraph guardedNestedOverconstrained =
                graph(visitor, "guardedNestedOverconstrained");
        Multigraph oracle = graph(visitor, "oracle");
        if (candidate == null || oracle == null) {
            throw new AssertionError("Expected both predicate graphs");
        }
        int graphSize = Canonical.canonicalFormSize(candidate);
        int graphDistance = Canonical.distance(candidate, oracle);
        Canonical.Prepared preparedCandidate = Canonical.prepare(candidate);
        Canonical.Prepared preparedOracle = Canonical.prepare(oracle);
        if (graphSize != Canonical.canonicalFormSize(preparedCandidate)
                || graphDistance != Canonical.distance(preparedCandidate, preparedOracle)
                || !Canonical.irTemporalFol(candidate).equals(Canonical.irTemporalFol(preparedCandidate))
                || !Canonical.edits(candidate, oracle).equals(Canonical.edits(preparedCandidate, preparedOracle))) {
            throw new AssertionError("Prepared canonical form changed graph-based results");
        }
        long eclasses = Canonical.eclassCount(preparedCandidate);
        long enodes = Canonical.enodeCount(preparedCandidate);
        if (eclasses <= 0 || enodes < eclasses) {
            throw new AssertionError("Prepared canonical form did not retain valid e-graph counts");
        }
        Canonical.Prepared preparedDirect = Canonical.prepare(direct);
        boolean directPrimitiveCarrier = false;
        for (NormalForm form : preparedDirect.normalizedForms()) {
            for (QuantiVar variable : form.getMatrixQuantiVars()) {
                if (variable.getOriginalNames().contains("pt")) {
                    directPrimitiveCarrier = "File".equals(variable.getTypeName())
                            && "File".equals(variable.getCarrierTypeName());
                }
            }
        }
        if (!directPrimitiveCarrier) {
            throw new AssertionError(
                    "A directly liftable guarded binder lost its primitive File carrier");
        }

        Canonical.Prepared preparedSignatureShadow = Canonical.prepare(signatureShadow);
        Canonical.Prepared preparedSignatureReference = Canonical.prepare(signatureReference);
        if (Canonical.distance(preparedSignatureShadow, preparedSignatureReference) == 0) {
            throw new AssertionError(
                    "A local binder named like a signature was resolved as the signature");
        }
        CanonicalAlloyPipeline.Prepared certifiedSignatureShadow =
                CanonicalAlloyPipeline.prepare(signatureShadow);
        CanonicalAlloyPipeline.Prepared certifiedSignatureReference =
                CanonicalAlloyPipeline.prepare(signatureReference);
        if (certifiedSignatureShadow.equivalentTo(certifiedSignatureReference)) {
            throw new AssertionError(
                    "Certified equality erased a local binder that shadows a signature");
        }
        CanonicalAlloyPipeline.Prepared certifiedQualifiedSignature =
                CanonicalAlloyPipeline.prepare(qualifiedSignature);
        CanonicalAlloyPipeline.Prepared certifiedQualifiedSignatureReference =
                CanonicalAlloyPipeline.prepare(qualifiedSignatureReference);
        if (!certifiedQualifiedSignature.equivalentTo(
                certifiedQualifiedSignatureReference)) {
            throw new AssertionError(
                    "An explicitly qualified signature was captured by a local binder");
        }
        if (!CanonicalAlloyPipeline.prepare(qualifiedField).equivalentTo(
                CanonicalAlloyPipeline.prepare(qualifiedFieldReference))) {
            throw new AssertionError(
                    "An explicitly qualified field was captured by a local binder");
        }
        boolean collidingGraphHasSignature = collidingSignature.getVertices().stream()
                .anyMatch(node -> node.getSymbol() instanceof SigSymbol
                        && "A".equals(node.getSymbol().getName()));
        boolean collidingGraphHasField = collidingSignature.getVertices().stream()
                .anyMatch(node -> node.getSymbol() instanceof FieldRelation
                        && "A".equals(node.getSymbol().getName()));
        if (!collidingGraphHasSignature || collidingGraphHasField) {
            throw new AssertionError(
                    "A field spelling collision changed an explicit signature leaf");
        }
        if (CanonicalAlloyPipeline.prepare(fieldC).equivalentTo(
                CanonicalAlloyPipeline.prepare(fieldD))) {
            throw new AssertionError(
                    "Same-named fields from distinct owners lost parser type identity");
        }
        if (importedSignature.getVertices().stream().noneMatch(
                node -> node.getSymbol() instanceof SigSymbol
                        && "time/Time".equals(node.getSymbol().getName()))) {
            throw new AssertionError(
                    "A reachable imported signature was absent from the global namespace");
        }
        if (Canonical.distance(nestedQuantifierShadow, nestedQuantifierRenamed) != 0
                || !CanonicalAlloyPipeline.prepare(nestedQuantifierShadow).equivalentTo(
                        CanonicalAlloyPipeline.prepare(nestedQuantifierRenamed))) {
            throw new AssertionError(
                    "A nested same-spelled quantifier captured the outer occurrence");
        }
        if (Canonical.distance(nestedLetShadow, nestedLetRenamed) != 0
                || !CanonicalAlloyPipeline.prepare(nestedLetShadow).equivalentTo(
                        CanonicalAlloyPipeline.prepare(nestedLetRenamed))) {
            throw new AssertionError(
                    "A nested same-spelled let captured the outer occurrence");
        }
        if (Canonical.distance(letOuterBinding, letOuterBindingRenamed) != 0
                || !CanonicalAlloyPipeline.prepare(letOuterBinding).equivalentTo(
                        CanonicalAlloyPipeline.prepare(letOuterBindingRenamed))) {
            throw new AssertionError(
                    "LET substitution lost alpha-equivalence across lexical renaming");
        }
        if (Canonical.distance(letOuterBinding, letCapturedReference) == 0
                || CanonicalAlloyPipeline.prepare(letOuterBinding).equivalentTo(
                        CanonicalAlloyPipeline.prepare(letCapturedReference))) {
            throw new AssertionError(
                    "LET substitution captured an outer variable under a same-spelled binder");
        }
        Canonical.Prepared preparedGuardedNestedCorrect =
                Canonical.prepare(guardedNestedCorrect);
        Canonical.Prepared preparedGuardedNestedOverconstrained =
                Canonical.prepare(guardedNestedOverconstrained);
        if (Canonical.distance(
                        preparedGuardedNestedCorrect,
                        preparedGuardedNestedOverconstrained) == 0) {
            throw new AssertionError(
                    "An existential crossed a guarded universal over a possibly empty carrier");
        }
        CanonicalAlloyPipeline.Prepared certifiedGuardedNestedCorrect =
                CanonicalAlloyPipeline.prepare(guardedNestedCorrect);
        CanonicalAlloyPipeline.Prepared certifiedGuardedNestedOverconstrained =
                CanonicalAlloyPipeline.prepare(guardedNestedOverconstrained);
        if (certifiedGuardedNestedCorrect.equivalentTo(
                        certifiedGuardedNestedOverconstrained)
                || CanonicalAlloyPipeline.distance(
                        certifiedGuardedNestedCorrect,
                        certifiedGuardedNestedOverconstrained) == 0) {
            throw new AssertionError(
                    "Certified prenexing erased an empty-carrier distinction");
        }
        if (Canonical.distance(delimiterCollision, delimiterRenamed) != 0
                || !CanonicalAlloyPipeline.prepare(delimiterCollision).equivalentTo(
                        CanonicalAlloyPipeline.prepare(delimiterRenamed))) {
            throw new AssertionError(
                    "Presentation-derived variable keys collided within one lexical scope");
        }
        long duplicateBinderSlots = duplicateBinder.getVertices().stream()
                .filter(node -> node.getSymbol() instanceof VarSymbol)
                .map(node -> ((VarSymbol) node.getSymbol()).getHashName())
                .distinct()
                .count();
        boolean duplicateBinderHasX = duplicateBinder.getVertices().stream()
                .anyMatch(node -> node.getSymbol() instanceof VarSymbol
                        && "x".equals(node.getSymbol().getName()));
        boolean duplicateBinderHasY = duplicateBinder.getVertices().stream()
                .anyMatch(node -> node.getSymbol() instanceof VarSymbol
                        && "y".equals(node.getSymbol().getName()));
        int duplicateBinderDistance = Canonical.distance(
                duplicateBinder, duplicateBinderRenamed);
        boolean duplicateBinderEquivalent = CanonicalAlloyPipeline.prepare(
                duplicateBinder).equivalentTo(
                        CanonicalAlloyPipeline.prepare(duplicateBinderRenamed));
        if (duplicateBinderSlots != 2
                || !duplicateBinderHasX
                || !duplicateBinderHasY
                || duplicateBinderDistance != 0
                || !duplicateBinderEquivalent) {
            throw new AssertionError(
                    "Repeated binder spellings reused a lexical slot after scope-map overwrite: "
                            + "slots=" + duplicateBinderSlots
                            + ", distance=" + duplicateBinderDistance
                            + ", equivalent=" + duplicateBinderEquivalent);
        }
        AugmentedNode ownerDeclaration = visitor.getUniqueNode().get(new SigSymbol("Owner"));
        if (ownerDeclaration == null || ownerDeclaration.getMaxDownlinks() != 2) {
            throw new AssertionError(
                    "A preindexed signature retained leaf arity after one field and END were attached");
        }

        boolean matrixContainsPt = false;
        boolean localContainsTypedPt = false;
        for (NormalForm form : preparedCandidate.normalizedForms()) {
            for (QuantiVar variable : form.getMatrixQuantiVars()) {
                matrixContainsPt |= variable.getOriginalNames().contains("pt");
            }
            localContainsTypedPt |= containsTypedLocalBinder(
                    form.getCertificationMatrixEGraph(), "pt", "File");
        }
        if (matrixContainsPt || !localContainsTypedPt) {
            throw new AssertionError(
                    "An unsafe cross-conjunction binder must remain local with exact File type");
        }

        checkBoundedSemantics(preparedCandidate, preparedDirect);
        checkGuardedNestedQuantifierCounterexample();
        checkTemporalSnapshotCarrierRegression();
        System.out.println("MASGVisitorTypeRegressionTest passed");
    }

    private static void checkVisitorLocalSentinels(
            CompModule module,
            MASGVisitor firstVisitor) {
        AugmentedNode firstEnd = firstVisitor.getUniqueNode().get(MASGVisitor.END_SYMBOL);
        AugmentedNode firstShadow = firstVisitor.getUniqueNode().get(MASGVisitor.SHADOW_SYMBOL);
        int firstEndUplinks = firstEnd.getUplinks().size();

        MASGVisitor secondVisitor = new MASGVisitor(new GlobalVariables(), module);
        AugmentedNode secondEnd = secondVisitor.getUniqueNode().get(MASGVisitor.END_SYMBOL);
        AugmentedNode secondShadow = secondVisitor.getUniqueNode().get(MASGVisitor.SHADOW_SYMBOL);
        if (firstEnd == secondEnd || firstShadow == secondShadow) {
            throw new AssertionError("MASG visitors share mutable sentinel nodes");
        }

        secondVisitor.visit(new ModelUnit(null, module), null);
        if (firstEnd.getUplinks().size() != firstEndUplinks) {
            throw new AssertionError("A later MASG visit mutated an earlier visitor's END node");
        }
        if (secondEnd.getUplinks().isEmpty()) {
            throw new AssertionError("The visitor-local END node did not receive graph links");
        }
    }

    private static Multigraph graph(MASGVisitor visitor, String name) {
        Integer id = visitor.getForestId(name);
        if (id == null || visitor.getForest().get(id) == null) {
            throw new AssertionError("Missing predicate graph: " + name);
        }
        return visitor.getForest().get(id);
    }

    private static void checkIntegerCarrierSetOperators() throws Exception {
        String source = String.join("\n",
                "open util/integer",
                "sig Left, Right {}",
                "sig Parent {}",
                "sig Child, Sibling extends Parent {}",
                "sig NestedCarrier in Parent {}",
                "sig NestedLeaf in NestedCarrier {}",
                "sig MultiLeft, MultiRight extends Parent {}",
                "sig MultiSubset in MultiLeft + MultiRight {}",
                "sig Holder { chosen: set Parent, rel: Parent -> Parent }",
                "assert IntUnion { Int + Int = Int }",
                "assert IntDifference { no (Int - Int) }",
                "assert CardinalUnion {",
                "  (#Left = 1 and #Right = 1) implies (#Left + #Right = 1)",
                "}",
                "assert ArithmeticAddition { 1 fun/add 1 = 2 }",
                "assert ArithmeticNotIdempotent { 1 fun/add 1 != 1 }",
                "assert HeterogeneousIntUnion {",
                "  (some Left) implies Int + Left != Int",
                "}",
                "assert SubtypeUnionAssociative {",
                "  ((Int + Child) + Parent) = (Int + (Child + Parent))",
                "}",
                "assert SetConstantIdentities {",
                "  Parent & univ = Parent",
                "  Parent - none = Parent",
                "  Parent + univ = univ",
                "  no (Parent - univ)",
                "  no (none - Parent)",
                "}",
                "assert SubtypeUnionAbsorption {",
                "  Child + Sibling + Parent = Parent",
                "}",
                "assert TypedSubrelationAbsorption {",
                "  (Child + Holder.chosen) + Parent = Parent",
                "}",
                "assert SubtypeIntersectionAbsorption {",
                "  Child & Parent = Child",
                "}",
                "assert TypedSubrelationIntersection {",
                "  Holder.chosen & Parent = Holder.chosen",
                "}",
                "assert ProductCarrierIntersection {",
                "  Holder.rel & (Parent -> Parent) = Holder.rel",
                "}",
                "assert NestedSubsetAbsorption {",
                "  NestedLeaf + NestedCarrier = NestedCarrier",
                "}",
                "assert EmptySubsetUnary { none in NestedCarrier }",
                "assert EmptySubsetBinary {",
                "  (none -> none) in (Parent -> Parent)",
                "}",
                "assert EmptyArrow { no (none -> Parent) }",
                "assert EmptyJoin { no (none.(Parent -> Parent)) }",
                "assert EmptyNotSubset { not (none not in Parent) }",
                "assert MultiSubsetCommonAbsorption {",
                "  MultiSubset + Parent = Parent",
                "}",
                "check IntUnion for 3 but 4 Int",
                "check IntDifference for 3 but 4 Int",
                "check CardinalUnion for 3 but 4 Int",
                "check ArithmeticAddition for 3 but 4 Int",
                "check ArithmeticNotIdempotent for 3 but 4 Int",
                "check HeterogeneousIntUnion for 3 but 4 Int",
                "check SubtypeUnionAssociative for 3 but 4 Int",
                "check SetConstantIdentities for 3 but 4 Int",
                "check SubtypeUnionAbsorption for 3 but 4 Int",
                "check TypedSubrelationAbsorption for 3 but 4 Int",
                "check SubtypeIntersectionAbsorption for 3 but 4 Int",
                "check TypedSubrelationIntersection for 3 but 4 Int",
                "check ProductCarrierIntersection for 3 but 4 Int",
                "check NestedSubsetAbsorption for 3 but 4 Int",
                "check EmptySubsetUnary for 3 but 4 Int",
                "check EmptySubsetBinary for 3 but 4 Int",
                "check EmptyArrow for 3 but 4 Int",
                "check EmptyJoin for 3 but 4 Int",
                "check EmptyNotSubset for 3 but 4 Int",
                "check MultiSubsetCommonAbsorption for 3 but 4 Int",
                "run {",
                "  #Left = 1 and #Right = 1 and #Left + #Right != 2",
                "} for 3 but exactly 1 Left, exactly 1 Right, 4 Int",
                "run { some Child and no Holder.chosen",
                "  and Child + Holder.chosen != Holder.chosen",
                "} for 3 but exactly 1 Child, exactly 1 Holder, 4 Int",
                "run { some (MultiSubset - MultiLeft) } for 3 but 4 Int",
                "");
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        for (int index = 0; index < module.getAllCommands().size(); index++) {
            A4Solution result = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(index),
                    options);
            boolean expectedSatisfiable = index == 20
                    || index == 21
                    || index == 22;
            if (result == null || result.satisfiable() != expectedSatisfiable) {
                throw new AssertionError(
                        "Alloy Int-carrier set semantics failed for command " + index);
            }
        }
    }

    private static void checkAbstractSignatureCovers() throws Exception {
        String source = String.join("\n",
                "abstract sig Parent {}",
                "sig Left, Right extends Parent {}",
                "sig Holder { chosen: set Parent }",
                "abstract sig Outer {}",
                "abstract sig Inner extends Outer {}",
                "sig LeafLeft, LeafRight extends Inner {}",
                "abstract sig SingleParent {}",
                "sig SingleChild extends SingleParent {}",
                "sig PlainParent {}",
                "sig PlainLeft, PlainRight extends PlainParent {}",
                "abstract sig SubsetParent {}",
                "sig SubsetOnly in SubsetParent {}",
                "sig Unrelated {}",
                "assert DirectCover { Parent = Left + Right }",
                "assert NestedCover {",
                "  Inner = LeafLeft + LeafRight",
                "  Outer = Inner",
                "}",
                "assert SingletonCover { SingleParent = SingleChild }",
                "assert CoveredUnionAbsorbsSubrelation {",
                "  Left + Right + Holder.chosen = Parent",
                "}",
                "assert ProductRightCover {",
                "  (Left -> Left) + (Left -> Right) = Left -> Parent",
                "}",
                "assert ProductLeftCover {",
                "  (Left -> Left) + (Right -> Left) = Parent -> Left",
                "}",
                "assert ProductTernaryCover {",
                "  (Left -> Left -> Left) + (Left -> Left -> Right)",
                "    = Left -> Left -> Parent",
                "}",
                "assert ProductFullGridCover {",
                "  (Left -> Left) + (Left -> Right)",
                "    + (Right -> Left) + (Right -> Right) = Parent -> Parent",
                "}",
                "assert ProductIntCover {",
                "  (Left -> Int) + (Right -> Int) = Parent -> Int",
                "}",
                "assert ProductIntLeftCover {",
                "  (Int -> Left) + (Int -> Right) = Int -> Parent",
                "}",
                "check DirectCover for 4",
                "check NestedCover for 4",
                "check SingletonCover for 4",
                "check CoveredUnionAbsorbsSubrelation for 4",
                "check ProductRightCover for 4",
                "check ProductLeftCover for 4",
                "check ProductTernaryCover for 4",
                "check ProductFullGridCover for 4",
                "check ProductIntCover for 4 Int",
                "check ProductIntLeftCover for 4 Int",
                "run { some Right and Left != Parent } for 4",
                "run { some (PlainParent - (PlainLeft + PlainRight)) } for 4",
                "run { some (SubsetParent - SubsetOnly) } for 4",
                "run {",
                "  some Unrelated",
                "  Left + Right + Unrelated != Parent",
                "} for 4",
                "run {",
                "  some Left",
                "  some Right",
                "  (Left -> Left) + (Right -> Right) != Parent -> Parent",
                "} for 4",
                "run {",
                "  some Left",
                "  some Right",
                "  (Left -> Left) + (Left -> Right) + (Right -> Left)",
                "    != Parent -> Parent",
                "} for 4",
                "");
        CompModule module = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        for (int index = 0; index < module.getAllCommands().size(); index++) {
            A4Solution result = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(index),
                    options);
            boolean expectedSatisfiable = index >= 10;
            if (result == null || result.satisfiable() != expectedSatisfiable) {
                throw new AssertionError(
                        "Alloy abstract-cover semantics failed for command " + index);
            }
        }
    }

    private static void checkRelationalUnaryIdentities() throws Exception {
        String source = String.join("\n",
                "sig A {}",
                "sig B {}",
                "sig V { r: set V }",
                "enum E { EA, EB }",
                "assert IdenLeft { iden.A = A }",
                "assert IdenRight { A.iden = A }",
                "assert IdenMiddle {",
                "  (A -> B).iden.(B -> V) = (A -> B).(B -> V)",
                "}",
                "assert IdenAll { iden.iden = iden }",
                "assert DoubleTranspose { ~(~r) = r }",
                "assert TransposeArrow { ~(A -> B) = B -> A }",
                "assert ClosureClosure { ^(^r) = ^r }",
                "assert RClosureRClosure { *(*r) = *r }",
                "assert ClosureRClosure { ^(*r) = *r }",
                "assert RClosureClosure { *(^r) = *r }",
                "assert EnumCover { E = EA + EB }",
                "assert TransposeIden { ~iden = iden }",
                "assert ClosureIden { ^iden = iden }",
                "assert RClosureIden { *iden = iden }",
                "check IdenLeft for 4",
                "check IdenRight for 4",
                "check IdenMiddle for 4",
                "check IdenAll for 4",
                "check DoubleTranspose for 4",
                "check TransposeArrow for 4",
                "check ClosureClosure for 4",
                "check RClosureRClosure for 4",
                "check ClosureRClosure for 4",
                "check RClosureClosure for 4",
                "check EnumCover for 4",
                "check TransposeIden for 4",
                "check ClosureIden for 4",
                "check RClosureIden for 4",
                "run { some A and some B and ~(A -> B) != A -> B }",
                "  for 2 but exactly 1 A, exactly 1 B",
                "run { some V and no r and ^r != *r }",
                "  for 1 but exactly 1 V",
                "run { some V and no r and V.r != V }",
                "  for 1 but exactly 1 V",
                "");
        CompModule module = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        for (int index = 0; index < module.getAllCommands().size(); index++) {
            A4Solution result = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(index),
                    options);
            boolean expectedSatisfiable = index >= 14;
            if (result == null || result.satisfiable() != expectedSatisfiable) {
                throw new AssertionError(
                        "Alloy relational rewrite semantics failed for command "
                                + index);
            }
        }
    }

    private static void checkRelationalDistributions() throws Exception {
        String source = String.join("\n",
                "sig A {}",
                "sig B {}",
                "sig V { r, s: set V }",
                "assert TransposeUnion { ~(r + s) = (~r + ~s) }",
                "assert TransposeIntersection { ~(r & s) = (~r & ~s) }",
                "assert TransposeDifference { ~(r - s) = (~r - ~s) }",
                "assert TransposeProductUnion {",
                "  ~((A -> B) + (A -> A)) = (B -> A) + (A -> A)",
                "}",
                "assert TransposeSlotUnion {",
                "  all x: V -> V | ~(x + r) = (~x + ~r)",
                "}",
                "assert ProductRightDistribution {",
                "  (A -> A) + (A -> B) = A -> (A + B)",
                "}",
                "assert ProductLeftDistribution {",
                "  (A -> B) + (B -> B) = (A + B) -> B",
                "}",
                "assert ProductTernaryDistribution {",
                "  (A -> A -> A) + (A -> A -> B)",
                "    = A -> A -> (A + B)",
                "}",
                "assert ProductFullGrid {",
                "  (A -> A) + (A -> B) + (B -> A) + (B -> B)",
                "    = (A + B) -> (A + B)",
                "}",
                "assert BooleanAbsorbAnd { all p,q:set V |",
                "  ((some p) and ((some p) or (some q))) iff some p",
                "}",
                "assert BooleanAbsorbOr { all p,q:set V |",
                "  ((some p) or ((some p) and (some q))) iff some p",
                "}",
                "assert BooleanDistributeAnd { all p,q,t:set V |",
                "  ((some p) and ((some q) or (some t))) iff",
                "    (((some p) and (some q)) or ((some p) and (some t)))",
                "}",
                "assert BooleanDistributeOr { all p,q,t:set V |",
                "  ((some p) or ((some q) and (some t))) iff",
                "    (((some p) or (some q)) and ((some p) or (some t)))",
                "}",
                "assert RelationAbsorbIntersect { all p,q:set V |",
                "  p & (p + q) = p",
                "}",
                "assert RelationAbsorbUnion { all p,q:set V |",
                "  p + (p & q) = p",
                "}",
                "assert RelationDistributeIntersect { all p,q,t:set V |",
                "  p & (q + t) = (p & q) + (p & t)",
                "}",
                "assert RelationDistributeUnion { all p,q,t:set V |",
                "  p + (q & t) = (p + q) & (p + t)",
                "}",
                "assert ProductRightSlot { all p,q,t:set V |",
                "  (p -> q) + (p -> t) = p -> (q + t)",
                "}",
                "assert ProductLeftSlot { all p,q,t:set V |",
                "  (p -> t) + (q -> t) = (p + q) -> t",
                "}",
                "assert ProductFullSlotGrid { all p,q:set V |",
                "  (p -> p) + (p -> q) + (q -> p) + (q -> q)",
                "    = (p + q) -> (p + q)",
                "}",
                "check TransposeUnion for 3",
                "check TransposeIntersection for 3",
                "check TransposeDifference for 3",
                "check TransposeProductUnion for 3",
                "check TransposeSlotUnion for 3",
                "check ProductRightDistribution for 3",
                "check ProductLeftDistribution for 3",
                "check ProductTernaryDistribution for 3",
                "check ProductFullGrid for 3",
                "check BooleanAbsorbAnd for 3",
                "check BooleanAbsorbOr for 3",
                "check BooleanDistributeAnd for 3",
                "check BooleanDistributeOr for 3",
                "check RelationAbsorbIntersect for 3",
                "check RelationAbsorbUnion for 3",
                "check RelationDistributeIntersect for 3",
                "check RelationDistributeUnion for 3",
                "check ProductRightSlot for 3",
                "check ProductLeftSlot for 3",
                "check ProductFullSlotGrid for 3",
                "run { some A and some B",
                "  and (A -> A) + (A -> B) + (B -> A)",
                "    != (A + B) -> (A + B)",
                "} for 3 but exactly 1 A, exactly 1 B",
                "run { some A and some B",
                "  and (A -> A) + (B -> B) != (A + B) -> (A + B)",
                "} for 3 but exactly 1 A, exactly 1 B",
                "run { ~(r & s) != (~r + ~s) } for 3",
                "run { some r and some s and r != s } for 3",
                "");
        CompModule module = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        for (int index = 0; index < module.getAllCommands().size(); index++) {
            A4Solution result = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(index),
                    options);
            boolean expectedSatisfiable = index >= 20;
            if (result == null || result.satisfiable() != expectedSatisfiable) {
                throw new AssertionError(
                        "Alloy relational distribution semantics failed for command "
                                + index);
            }
        }
    }

    private static boolean containsTypedLocalBinder(
            EGraphNode node,
            String sourceName,
            String primitiveType) {
        if (node == null) {
            return false;
        }
        if (node.getOpcode() == EGraphNode.Opcode.VARIABLE
                && sourceName.equals(node.getSourceName())) {
            ExactAlloyType exact = node.getExactAlloyType();
            if (exact != null
                    && exact.kind() == ExactAlloyType.Kind.RELATION
                    && exact.relationArity() == 1
                    && exact.alternatives().stream().allMatch(
                            columns -> columns.size() == 1
                                    && primitiveType.equals(columns.get(0)))) {
                return true;
            }
        }
        for (EGraphNode child : node.getChildren()) {
            if (containsTypedLocalBinder(child, sourceName, primitiveType)) {
                return true;
            }
        }
        return false;
    }

    private static void checkGuardedNestedQuantifierCounterexample()
            throws Exception {
        String source = String.join("\n",
                "module guarded_nested_quantifier_counterexample",
                "sig Course {}",
                "sig Instructor { courses: set Course }",
                "sig Lecturer in Instructor {}",
                "pred correct {",
                "  all t: Lecturer | some c: Course | c in t.courses",
                "}",
                "pred overconstrained {",
                "  all p: Instructor | some c: Course |",
                "    p in Lecturer implies c in p.courses",
                "}",
                "run { not (correct[] iff overconstrained[]) } for 2 but 0 Int",
                "");
        CompModule module = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        A4Solution result = TranslateAlloyToKodkod.execute_command(
                A4Reporter.NOP,
                module.getAllReachableSigs(),
                module.getAllCommands().get(0),
                options);
        if (result == null || !result.satisfiable()) {
            throw new AssertionError(
                    "Alloy did not reproduce the empty-carrier prenex counterexample");
        }
    }

    private static void checkTemporalSnapshotCarrierRegression()
            throws Exception {
        String source = String.join("\n",
                "module temporal_snapshot_carrier_regression",
                "var sig File {}",
                "var sig Trash in File {}",
                "sig StaticFile {}",
                "sig StaticTrash in StaticFile {}",
                "pred staleAtom { always all t: Trash | after no t }",
                "pred currentMembership {",
                "  always all t: Trash | after no (File & t)",
                "}",
                "pred staticAtom {",
                "  always all t: StaticTrash | after no t",
                "}",
                "pred staticMembership {",
                "  always all t: StaticTrash | after no (StaticFile & t)",
                "}",
                "run { not staleAtom[] and currentMembership[] }",
                "  for 3 but 0 Int, 1..3 steps",
                "");
        CompModule module = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        Multigraph staleAtom = graph(visitor, "staleAtom");
        Multigraph currentMembership = graph(visitor, "currentMembership");
        Multigraph staticAtom = graph(visitor, "staticAtom");
        Multigraph staticMembership = graph(visitor, "staticMembership");
        CanonicalAlloyPipeline.Prepared stale =
                CanonicalAlloyPipeline.prepare(staleAtom);
        CanonicalAlloyPipeline.Prepared current =
                CanonicalAlloyPipeline.prepare(currentMembership);
        if (stale.equivalentTo(current)
                || CanonicalAlloyPipeline.distance(stale, current) == 0) {
            throw new AssertionError(
                    "A temporal snapshot type proved membership in a later mutable carrier");
        }
        CanonicalAlloyPipeline.Prepared staticLeft =
                CanonicalAlloyPipeline.prepare(staticAtom);
        CanonicalAlloyPipeline.Prepared staticRight =
                CanonicalAlloyPipeline.prepare(staticMembership);
        if (!staticLeft.equivalentTo(staticRight)
                || CanonicalAlloyPipeline.distance(staticLeft, staticRight) != 0) {
            throw new AssertionError(
                    "Temporal snapshot guarding disabled a sound static-carrier absorption");
        }
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        A4Solution result = TranslateAlloyToKodkod.execute_command(
                A4Reporter.NOP,
                module.getAllReachableSigs(),
                module.getAllCommands().get(0),
                options);
        if (result == null || !result.satisfiable()) {
            throw new AssertionError(
                    "Alloy did not reproduce the mutable-carrier temporal counterexample");
        }
    }

    private static void checkBoundedSemantics(
            Canonical.Prepared candidate,
            Canonical.Prepared direct) throws Exception {
        String candidateNormalized = CanonicalBacktranslator.formula(
                candidate.normalizedForms());
        String directNormalized = CanonicalBacktranslator.formula(
                direct.normalizedForms());
        String comparison = String.join("\n",
                "module guarded_binder_semantics",
                "sig User { follows: set User }",
                "sig Photo {}",
                "sig File {}",
                "sig Protected, Trash in File {}",
                "pred candidateOriginal {",
                "  all x: univ | x in User implies x in User",
                "  all u: User | all f: u - u.follows | f in User",
                "  all y: User | some z: y | z in User",
                "  all n: none | n = n",
                "  all pt: Protected & Trash | pt = Protected",
                "}",
                "pred candidateNormalized { " + candidateNormalized + " }",
                "pred directOriginal { all pt: Protected & Trash | pt = Protected }",
                "pred directNormalized { " + directNormalized + " }",
                "pred guardedOriginal {",
                "  some File and (all pt: Protected & Trash | pt = pt)",
                "}",
                "pred liftedOverFile {",
                "  all pt: File | some File and",
                "    (pt in (Protected & Trash) implies pt = pt)",
                "}",
                "pred liftedOverUniv {",
                "  all pt: univ | some File and",
                "    (pt in (Protected & Trash) implies pt = pt)",
                "}",
                "assert CandidateZero { candidateOriginal[] iff candidateNormalized[] }",
                "assert CandidateThree { candidateOriginal[] iff candidateNormalized[] }",
                "assert DirectSame { directOriginal[] iff directNormalized[] }",
                "run { not (guardedOriginal[] iff liftedOverFile[]) } for 0 but 0 Int",
                "run { not (guardedOriginal[] iff liftedOverUniv[]) } for 0 but 0 Int",
                "check CandidateZero for 0 but 0 Int",
                "check CandidateThree for 3 but 0 Int",
                "check DirectSame for 3 but 0 Int",
                "");
        Path file = Files.createTempFile("guarded-binder-semantics-", ".als");
        try {
            Files.writeString(file, comparison, StandardCharsets.UTF_8);
            CompModule module = CompUtil.parseEverything_fromFile(
                    A4Reporter.NOP, null, file.toString());
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            for (int index = 0; index < module.getAllCommands().size(); index++) {
                A4Solution result = TranslateAlloyToKodkod.execute_command(
                        A4Reporter.NOP,
                        module.getAllReachableSigs(),
                        module.getAllCommands().get(index),
                        options);
                boolean expectedSatisfiable = index < 2;
                if (result == null || result.satisfiable() != expectedSatisfiable) {
                    throw new AssertionError(
                            "Guarded-binder SAT expectation failed for command " + index);
                }
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}

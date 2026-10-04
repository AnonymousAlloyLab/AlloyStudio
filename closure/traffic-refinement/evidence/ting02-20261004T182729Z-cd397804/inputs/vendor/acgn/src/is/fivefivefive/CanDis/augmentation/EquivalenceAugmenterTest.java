package is.fivefivefive.CanDis.augmentation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;

import org.json.JSONObject;

import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.theory.AlloySemanticProfileFactory;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import parser.ast.nodes.ModelUnit;

/** Executable lifecycle and fail-closed boundary checks for adaptive equality. */
public final class EquivalenceAugmenterTest {
    private static int checks;

    private EquivalenceAugmenterTest() {
    }

    public static void main(String[] args) throws Exception {
        String priorOverride = System.getProperty("acgn.provenance.testOverride");
        Path directory = Files.createTempDirectory("candis-equivalence-augmenter-");
        try {
            System.setProperty("acgn.provenance.testOverride", "true");
            String source = source();
            byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
            CompModule module = CompUtil.parseEverything_fromString(null, source);
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(new ModelUnit(null, module), null);

            CanonicalAlloyPipeline.Prepared a = prepare(visitor, "a", sourceBytes);
            CanonicalAlloyPipeline.Prepared b = prepare(visitor, "b", sourceBytes);
            CanonicalAlloyPipeline.Prepared c = prepare(visitor, "c", sourceBytes);
            CanonicalAlloyPipeline.Prepared d = prepare(visitor, "d", sourceBytes);
            CanonicalAlloyPipeline.Prepared e = prepare(visitor, "e", sourceBytes);
            CanonicalAlloyPipeline.Prepared f = prepare(visitor, "f", sourceBytes);
            CanonicalAlloyPipeline.Prepared falsehood = prepare(
                    visitor, "falsehood", sourceBytes);

            EquivalenceAugmenter augmenter = new EquivalenceAugmenter();
            check(CanonicalAlloyPipeline.distance(a, b) > 0,
                    "consensus witness A must be a positive R0 miss");
            check(CanonicalAlloyPipeline.distance(c, d) > 0,
                    "consensus witness B must be a positive R0 miss");
            check(CanonicalAlloyPipeline.distance(e, f) > 0,
                    "unseen consensus witness must be a positive R0 miss");
            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.observeAugmentedEquality(
                            a,
                            b,
                            augmenter,
                            equivalent(source + "\n", "a", "b")),
                    "source evidence with bytes outside endpoint provenance must fail");
            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.observeAugmentedEquality(
                            a,
                            falsehood,
                            augmenter,
                            counterexample(
                                    source,
                                    "a",
                                    "falsehood",
                                    AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF)),
                    "an equivalence observation must never accept SAT as its target");
            check(augmenter.localRecords().isEmpty(),
                    "failed source correspondence must not create a merge record");

            EquivalenceAugmenter.LocalRecordView first =
                    CanonicalAlloyPipeline.observeAugmentedEquality(
                            a, b, augmenter, equivalent(source, "a", "b"));
            EquivalenceAugmenter.LocalRecordView second =
                    CanonicalAlloyPipeline.observeAugmentedEquality(
                            c, d, augmenter, equivalent(source, "c", "d"));
            check(first.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL
                            && second.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL,
                    "two direct Alloy UNSAT checks must verify exact local equalities: "
                            + first.state() + "/" + first.alloyOutcome() + "/"
                            + first.alloyDetail() + "; " + second.state() + "/"
                            + second.alloyOutcome() + "/" + second.alloyDetail());
            check(first.transitions().equals(List.of(
                            EquivalenceAugmenter.State.OBSERVED,
                            EquivalenceAugmenter.State.CANDIDATE,
                            EquivalenceAugmenter.State.VERIFIED_LOCAL)),
                    "local lifecycle must retain every required state transition");

            EquivalenceAugmenter.Evaluation local =
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            a, b, augmenter, equivalent(source, "a", "b"));
            check(local.distance() == 0 && local.bootstrapDistance() > 0
                            && local.augmentationCertificate().isPresent(),
                    "verified exact equality must merge only in the adaptive overlay");
            check(CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            a, b, augmenter).distance() > 0,
                    "a bounded local equality must not change context-free distance");
            augmenter.verifyCertificate(local.augmentationCertificate().orElseThrow());
            EquivalenceAugmenter.Evaluation reverseLocal =
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            b, a, augmenter, equivalent(source, "b", "a"));
            check(reverseLocal.distance() == 0
                            && reverseLocal.augmentationCertificate().orElseThrow().digest()
                                    .equals(local.augmentationCertificate()
                                            .orElseThrow().digest()),
                    "an unoriented local equality must replay in either endpoint order");

            EquivalenceAugmenter.LocalRecordView falsified =
                    CanonicalAlloyPipeline.observeAugmentedEquality(
                            a,
                            falsehood,
                            augmenter,
                            equivalent(source, "a", "falsehood"));
            check(falsified.state() == EquivalenceAugmenter.State.FALSIFIED,
                    "an Alloy counterexample must permanently falsify that observation");
            check(CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            a, falsehood, augmenter).distance() > 0,
                    "falsified evidence must never merge its endpoints");

            expectThrows(IllegalArgumentException.class, () ->
                    augmenter.proposeSchema(List.of(first.id())),
                    "one witness must never produce a global schema");
            EquivalenceAugmenter.SchemaRecordView candidate = augmenter.proposeSchema(
                    List.of(first.id(), second.id()));
            check(candidate.state() == EquivalenceAugmenter.State.CANDIDATE
                            && candidate.originWitnesses().size() == 2
                            && candidate.orientation()
                                    == EquivalenceAugmenter.Orientation.UNORIENTED,
                    "anti-unification must produce an unoriented two-witness candidate");

            String parameters = candidate.leanTheoremParameters();
            String statement = candidate.leanTheoremStatement();
            check(parameters.equals("(p0 p1 p2 : Prop)"),
                    "consensus correspondence must expose exactly three proposition atoms");
            Path lean = directory.resolve("ConsensusSchema.lean");
            Files.writeString(
                    lean,
                    leanProof(candidate.schemaDigest(), parameters, statement),
                    StandardCharsets.UTF_8);
            String r0 = "R0:" + augmenter.bootstrap().digest();
            expectThrows(IllegalArgumentException.class, () ->
                    augmenter.verifySchema(
                            candidate.id(),
                            new EquivalenceAugmenter.SchemaValidation(
                                    equivalent(source, "schemaLeft", "schemaRight"),
                                    weakeningProbes(source),
                                    new LeanSchemaProofValidator.Request(
                                            lean,
                                            "consensus_schema",
                                            parameters,
                                            statement,
                                            candidate.schemaDigest(),
                                            List.of(),
                                            Duration.ofSeconds(30)),
                                    List.of(r0))),
                    "schema and Lean dependency ledgers must agree exactly");
            check(augmenter.schemaRecords().get(0).state()
                            == EquivalenceAugmenter.State.CANDIDATE,
                    "malformed evidence must not semantically falsify a candidate");
            EnumMap<EquivalenceAugmenter.GuardDimension,
                    EquivalenceAugmenter.WeakeningProbe> forgedTypeProbes =
                    weakeningProbes(source);
            GraphType forged = GraphType.relation(
                    GraphType.constructor("AlloySig:NotTheActualCarrier"));
            forgedTypeProbes.put(
                    EquivalenceAugmenter.GuardDimension.EXACT_TYPE,
                    new EquivalenceAugmenter.WeakeningProbe(
                            counterexample(
                                    source,
                                    "W.p",
                                    "W.p + W.q",
                                    AlloyEquivalenceValidator.ComparisonKind.TERM_EQUALITY),
                            forged,
                            forged));
            EquivalenceAugmenter.SchemaRecordView forgedTypeResult =
                    augmenter.verifySchema(
                            candidate.id(),
                            new EquivalenceAugmenter.SchemaValidation(
                                    equivalent(source, "schemaLeft", "schemaRight"),
                                    forgedTypeProbes,
                                    new LeanSchemaProofValidator.Request(
                                            lean,
                                            "consensus_schema",
                                            parameters,
                                            statement,
                                            candidate.schemaDigest(),
                                            List.of(r0),
                                            Duration.ofSeconds(30)),
                                    List.of(r0)));
            check(forgedTypeResult.state() == EquivalenceAugmenter.State.CANDIDATE
                            && "ERROR".equals(forgedTypeResult.negativeEvidence()
                                    .get(EquivalenceAugmenter.GuardDimension.EXACT_TYPE)
                                    .outcome()),
                    "caller-supplied relation arity must not forge exact-type guard evidence");
            EquivalenceAugmenter.SchemaValidation validation =
                    new EquivalenceAugmenter.SchemaValidation(
                            equivalent(source, "schemaLeft", "schemaRight"),
                            weakeningProbes(source),
                            new LeanSchemaProofValidator.Request(
                                    lean,
                                    "consensus_schema",
                                    parameters,
                                    statement,
                                    candidate.schemaDigest(),
                                    List.of(r0),
                                    Duration.ofSeconds(30)),
                            List.of(r0));
            EquivalenceAugmenter.SchemaRecordView verified = augmenter.verifySchema(
                    candidate.id(), validation);
            check(verified.state() == EquivalenceAugmenter.State.VERIFIED_SCHEMA
                            && verified.negativeEvidence().size()
                                    == EquivalenceAugmenter.GuardDimension.values().length
                            && !verified.leanProofDigest().isBlank(),
                    "schema validation must close Alloy, guard, authority, and Lean evidence: "
                            + verified.state() + ", negative="
                            + verified.negativeEvidence() + ", positive="
                            + verified.positiveEvidence().size() + ", lean="
                            + verified.leanProofReference());
            check(CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            e, f, augmenter, equivalent(source, "e", "f")).distance() > 0,
                    "a proved but unadmitted equality must not affect comparison");

            EquivalenceAugmenter.SchemaRecordView admitted = augmenter.admitSchema(
                    candidate.id());
            check(admitted.state() == EquivalenceAugmenter.State.ADMITTED
                            && admitted.theoryGeneration() == 1
                            && admitted.affectedPairs() >= 2,
                    "admission must advance one generation and reprocess its region");
            check(CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            e, f, augmenter).distance() > 0,
                    "an admitted schema must not apply without source correspondence");
            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            e,
                            f,
                            augmenter,
                            equivalent(source, "e", "falsehood")),
                    "schema application evidence must reconstruct the compared endpoints");
            A4Options foreignContextOptions = new A4Options();
            foreignContextOptions.solver = A4Options.SatSolver.SAT4J;
            AlloyEquivalenceValidator.Request foreignContextApplication =
                    new AlloyEquivalenceValidator.Request(
                            source,
                            "e",
                            "f",
                            "",
                            AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                            AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                            4,
                            Duration.ofSeconds(30),
                            AlloyEquivalenceValidator.SourceCommandContext.from(
                                    0, foreignContextOptions));
            int applicationsBeforeForeignContext =
                    augmenter.applicationRecords().size();
            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            e, f, augmenter, foreignContextApplication),
                    "schema application must authenticate command/options context "
                            + "against the prepared endpoints");
            check(augmenter.applicationRecords().size()
                            == applicationsBeforeForeignContext,
                    "rejected schema application context must not mutate the ledger");
            EquivalenceAugmenter.Evaluation generalized =
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            e, f, augmenter, equivalent(source, "e", "f"));
            check(generalized.distance() == 0
                            && generalized.bootstrapDistance() > 0
                            && generalized.theoryGeneration() == 1
                            && generalized.augmentationCertificate().orElseThrow().scope()
                                    == EquivalenceAugmenter.AdmissionScope.GENERALIZED_SCHEMA,
                    "an unseen matching pair must use the admitted schema generation: "
                            + "distance=" + generalized.distance()
                            + ", bootstrap=" + generalized.bootstrapDistance()
                            + ", generation=" + generalized.theoryGeneration()
                            + ", certificate="
                            + generalized.augmentationCertificate());
            augmenter.verifyCertificate(
                    generalized.augmentationCertificate().orElseThrow());
            EquivalenceAugmenter.Evaluation reverseGeneralized =
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            f, e, augmenter, equivalent(source, "f", "e"));
            check(reverseGeneralized.distance() == 0
                            && reverseGeneralized.augmentationCertificate().orElseThrow()
                                    .digest().equals(generalized.augmentationCertificate()
                                            .orElseThrow().digest()),
                    "an admitted equality must remain unoriented at application time");
            EquivalenceAugmenter.Evaluation scopeThreeGeneralized =
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            e, f, augmenter, equivalentAtScope(source, "e", "f", 3));
            check(scopeThreeGeneralized.distance() == 0
                            && !scopeThreeGeneralized.augmentationCertificate().orElseThrow()
                                    .semanticContextDigest().equals(
                                            generalized.augmentationCertificate().orElseThrow()
                                                    .semanticContextDigest())
                            && augmenter.applicationRecords().size() == 2,
                    "schema cache and application evidence must retain each context");
            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            e,
                            f,
                            augmenter,
                            counterexample(
                                    source,
                                    "e",
                                    "f",
                                    AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF)),
                    "an equality application must not target a counterexample");

            expectThrows(IllegalArgumentException.class, () ->
                    CanonicalAlloyPipeline.augmentedDistanceEvaluation(
                            e.compactForComparison(), f, augmenter),
                    "compact endpoints must not acquire source authority from metadata");

            Path ledger = directory.resolve("equivalence-augmentation-ledger.json");
            augmenter.writeLedger(ledger);
            String ledgerText = Files.readString(ledger, StandardCharsets.UTF_8);
            JSONObject json = new JSONObject(ledgerText);
            check(json.getLong("theoryGeneration") == 1
                            && json.getJSONArray("instanceLocalEqualities").length() == 3
                            && json.getJSONArray("generalizedSchemas").length() == 1
                            && json.getJSONArray("schemaApplications").length() == 2,
                    "persisted ledger must retain local, falsified, and admitted records");
            Path secondLedger = directory.resolve(
                    "equivalence-augmentation-ledger-second.json");
            augmenter.writeLedger(secondLedger);
            check(ledgerText.equals(Files.readString(
                            secondLedger, StandardCharsets.UTF_8)),
                    "repeated ledger writes must be byte-identical");
            check(ledgerText.indexOf("\"augmenterVersion\"")
                            < ledgerText.indexOf("\"bootstrap\"")
                            && ledgerText.indexOf("\"bootstrap\"")
                                    < ledgerText.indexOf("\"generalizedSchemas\""),
                    "ledger object keys must use canonical lexical order");
            JSONObject localJson = json.getJSONArray(
                    "instanceLocalEqualities").getJSONObject(0);
            check(localJson.has("originWitnesses")
                            && localJson.has("positiveEvidence")
                            && localJson.has("negativeEvidence")
                            && localJson.has("semanticContextDigest")
                            && localJson.has("transitions")
                            && localJson.has("theoryGeneration"),
                    "local ledger records must retain lifecycle and evidence fields");
            JSONObject schemaJson = json.getJSONArray(
                    "generalizedSchemas").getJSONObject(0);
            check(schemaJson.has("generalizedSchema")
                            && schemaJson.has("guardsDigest")
                            && schemaJson.getJSONObject("guards").length()
                                    == EquivalenceAugmenter.GuardDimension.values().length
                            && schemaJson.has("positiveEvidence")
                            && schemaJson.has("negativeEvidence")
                            && schemaJson.has("leanProof")
                            && schemaJson.has("dependencies")
                            && schemaJson.has("orientation")
                            && schemaJson.has("theoryGeneration"),
                    "schema ledger records must retain proof, guard, and generation fields");
            JSONObject applicationJson = json.getJSONArray(
                    "schemaApplications").getJSONObject(0);
            check(applicationJson.has("sourceCorrespondence")
                            && applicationJson.has("semanticContextDigest")
                            && applicationJson.has("theoryGeneration"),
                    "application ledger records must retain replay provenance");

            EquivalenceAugmenter inFlight = new EquivalenceAugmenter();
            EquivalenceAugmenter.Evaluation inFlightResult =
                    CanonicalAlloyPipeline.augmentEquivalentInFlight(
                            a, b, inFlight, equivalent(source, "a", "b"));
            check(inFlightResult.distance() == 0
                            && inFlightResult.bootstrapDistance() > 0
                            && inFlight.localRecords().size() == 1,
                    "the in-flight API must persist and locally certify a positive miss");

            checkLeanFailClosed(directory, candidate.schemaDigest(), statement);
            checkPinnedLeanExecutable();
            checkImportedSelectorCannotAliasLocalEndpoint();
            checkOverloadedCallableResolution();
            checkScopeBoundLocalEquality();
            checkSourceCommandBoundValidation();
            checkFollowUpCommandResultIsolation();
            checkSourceCommandFormulaGuard();
            checkSinglePairCannotGeneralize(a, b, source);
            checkScopeAwareAtomKeys();
            checkCompleteAtomIdentity();
            checkQuantifiedAtomIdentity(directory);
            checkBootstrapCoverage(
                    visitor,
                    sourceBytes,
                    "absLeftW",
                    "absRightW",
                    "absLeftX",
                    "absRightX",
                    "absLeftY",
                    "absRightY",
                    "Boolean absorption");
            checkBootstrapCoverage(
                    visitor,
                    sourceBytes,
                    "distLeftW",
                    "distRightW",
                    "distLeftX",
                    "distRightX",
                    "distLeftY",
                    "distRightY",
                    "Boolean distributivity");
            checkGrowthBound(visitor, sourceBytes, source, directory);
        } finally {
            if (priorOverride == null) {
                System.clearProperty("acgn.provenance.testOverride");
            } else {
                System.setProperty("acgn.provenance.testOverride", priorOverride);
            }
            deleteTree(directory);
        }
        System.out.println("EquivalenceAugmenterTest passed: " + checks + " checks");
    }

    private static void checkImportedSelectorCannotAliasLocalEndpoint()
            throws Exception {
        String source = String.join("\n",
                "module endpoint_alias_regression",
                "open util/ordering[A] as ord",
                "sig A {}",
                "fun first: one A { ord/last }",
                "fun second: one A { ord/first }",
                "run { some A } for 3");
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        CompModule module = CompUtil.parseEverything_fromString(null, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        CanonicalAlloyPipeline.Prepared first = prepare(visitor, "first", sourceBytes);
        CanonicalAlloyPipeline.Prepared second = prepare(visitor, "second", sourceBytes);
        check(CanonicalAlloyPipeline.distance(first, second) > 0,
                "the local first/second endpoint collision witness must be an R0 miss");

        AlloyEquivalenceValidator.Request importedEquality =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "ord/first",
                        "second",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.TERM_EQUALITY,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        3,
                        Duration.ofSeconds(30));
        AlloyEquivalenceValidator.Request localDifference =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "this/first",
                        "second",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.TERM_EQUALITY,
                        AlloyEquivalenceValidator.ExpectedOutcome.COUNTEREXAMPLE_REQUIRED,
                        3,
                        Duration.ofSeconds(30));
        GraphType unaryA = GraphType.relation(
                GraphType.constructor("AlloySig:A"));
        AlloyEquivalenceValidator validator = new AlloyEquivalenceValidator();
        AlloyEquivalenceValidator.Evidence importedEvidence =
                validator.validate(importedEquality, unaryA, unaryA);
        AlloyEquivalenceValidator.Evidence localEvidence =
                validator.validate(localDifference, unaryA, unaryA);
        check(importedEvidence.outcome() == AlloyEquivalenceValidator.Outcome.ERROR
                        && importedEvidence.detail().contains("explicit open"),
                "an imported-module equality must remain unauthoritative until its "
                        + "resolved source closure is committed");
        check(localEvidence.outcome() == AlloyEquivalenceValidator.Outcome.ERROR
                        && localEvidence.detail().contains("explicit open"),
                "even root-local endpoints must not acquire semantic authority from "
                        + "an uncommitted opened-module closure");

        expectThrows(IllegalArgumentException.class, () ->
                validator.requireSemanticContext(
                        importedEquality, first.semanticProfile()),
                "an explicit open must fail before adaptive ledger mutation");

        expectThrows(IllegalArgumentException.class, () ->
                SourceEndpointCorrespondence.verify(
                        importedEquality,
                        first,
                        second,
                        AugmentationDigests.sha256(source),
                        AugmentationDigests.sha256(source)),
                "an imported declaration must never fall back to a same-named local forest");
        EquivalenceAugmenter augmenter = new EquivalenceAugmenter();
        expectThrows(IllegalArgumentException.class, () ->
                CanonicalAlloyPipeline.observeAugmentedEquality(
                        first, second, augmenter, importedEquality),
                "imported equality evidence must not certify a local endpoint pair");
        check(augmenter.localRecords().isEmpty(),
                "rejected imported/local correspondence must leave no local record");
    }

    private static void checkOverloadedCallableResolution() throws Exception {
        String source = String.join("\n",
                "module overloaded_callable_regression",
                "sig A {}",
                "pred foo { no A }",
                "pred foo[x: A] { no x }",
                "pred different { some A }",
                "run { some A } for 3");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        CompModule module = CompUtil.parseEverything_fromString(null, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        Integer nullary = visitor.getForestId(
                "foo", is.fivefivefive.ACGN.alloy.CallSymbol.Kind.FORMULA, 0);
        Integer unary = visitor.getForestId(
                "foo", is.fivefivefive.ACGN.alloy.CallSymbol.Kind.FORMULA, 1);
        check(nullary != null && unary != null && !nullary.equals(unary),
                "overloaded declarations must have distinct kind/arity forest identities");
        check(visitor.getForestId("foo").equals(nullary),
                "an unqualified nullary endpoint must resolve to its parser-call arity");

        CanonicalAlloyPipeline.Prepared foo = prepare(visitor, "foo", bytes);
        CanonicalAlloyPipeline.Prepared different = prepare(
                visitor, "different", bytes);
        check(CanonicalAlloyPipeline.distance(foo, different) > 0,
                "overload correspondence witness must be a positive R0 pair");
        EquivalenceAugmenter augmenter = new EquivalenceAugmenter();
        EquivalenceAugmenter.LocalRecordView result = augmenter.observeEquivalent(
                foo, different, equivalent(source, "foo", "different"));
        check(result.state() == EquivalenceAugmenter.State.FALSIFIED,
                "the nullary overload must reach its exact source endpoint and retain "
                        + "the Alloy counterexample");
    }

    private static void checkLeanFailClosed(
            Path directory,
            String schemaDigest,
            String statement) throws Exception {
        Path bad = directory.resolve("BadSchema.lean");
        Files.writeString(
                bad,
                "def acgnSchemaDigest : String := \"" + schemaDigest + "\"\n"
                        + "theorem consensus_schema (p0 p1 p2 : Prop) : "
                        + statement + " := by sorry\n",
                StandardCharsets.UTF_8);
        expectThrows(IllegalArgumentException.class, () ->
                new LeanSchemaProofValidator().verify(
                        new LeanSchemaProofValidator.Request(
                                bad,
                                "consensus_schema",
                                "(p0 p1 p2 : Prop)",
                                statement,
                                schemaDigest,
                                List.of(),
                                Duration.ofSeconds(10))),
                "sorry must fail the independent Lean boundary");

        Path sorryAx = directory.resolve("SorryAxSchema.lean");
        Files.writeString(
                sorryAx,
                "def acgnSchemaDigest : String := \"" + schemaDigest + "\"\n"
                        + "theorem consensus_schema (p0 p1 p2 : Prop) : "
                        + statement + " := by\n"
                        + "  exact sorryAx _ true\n",
                StandardCharsets.UTF_8);
        expectThrows(IllegalArgumentException.class, () ->
                new LeanSchemaProofValidator().verify(
                        new LeanSchemaProofValidator.Request(
                                sorryAx,
                                "consensus_schema",
                                "(p0 p1 p2 : Prop)",
                                statement,
                                schemaDigest,
                                List.of(),
                                Duration.ofSeconds(10))),
                "sorryAx must fail the independent Lean boundary");

        Path implicitSorry = directory.resolve("ImplicitSorrySchema.lean");
        String falseStatement = "(p <-> q)";
        Files.writeString(
                implicitSorry,
                "def acgnSchemaDigest : String := \"" + schemaDigest + "\"\n"
                        + "set_option warningAsError false in\n"
                        + "theorem false_schema (p q : Prop) : "
                        + falseStatement + " := by\n"
                        + "  impossible by\n"
                        + "    intro alleged\n"
                        + "    have contradiction := alleged True False\n"
                        + "    simp at contradiction\n",
                StandardCharsets.UTF_8);
        expectThrows(java.io.IOException.class, () ->
                new LeanSchemaProofValidator().verify(
                        new LeanSchemaProofValidator.Request(
                                implicitSorry,
                                "false_schema",
                                "(p q : Prop)",
                                falseStatement,
                                schemaDigest,
                                List.of(),
                                Duration.ofSeconds(10))),
                "warning-as-error must reject tactics that elaborate to sorryAx");

        Path shadowedAudit = directory.resolve("ShadowedAxiomAuditSchema.lean");
        Files.writeString(
                shadowedAudit,
                "def acgnSchemaDigest : String := \"" + schemaDigest + "\"\n"
                        + "macro \"#print\" \"axioms\" ident : command => "
                        + "`(command| #check True)\n"
                        + "set_option warningAsError false in\n"
                        + "theorem false_schema (p q : Prop) : "
                        + falseStatement + " := by\n"
                        + "  impossible by\n"
                        + "    intro alleged\n"
                        + "    have contradiction := alleged True False\n"
                        + "    simp at contradiction\n",
                StandardCharsets.UTF_8);
        expectThrows(IllegalArgumentException.class, () ->
                new LeanSchemaProofValidator().verify(
                        new LeanSchemaProofValidator.Request(
                                shadowedAudit,
                                "false_schema",
                                "(p q : Prop)",
                                falseStatement,
                                schemaDigest,
                                List.of(),
                                Duration.ofSeconds(10))),
                "proof modules must not redefine the checker-owned axiom audit");

        Path axiom = directory.resolve("AxiomSchema.lean");
        Files.writeString(
                axiom,
                "def acgnSchemaDigest : String := \"" + schemaDigest + "\"\n"
                        + "axiom false_equivalence (p q : Prop) : p <-> q\n"
                        + "theorem consensus_schema (p0 p1 p2 : Prop) : "
                        + statement + " := false_equivalence _ _\n",
                StandardCharsets.UTF_8);
        expectThrows(IllegalArgumentException.class, () ->
                new LeanSchemaProofValidator().verify(
                        new LeanSchemaProofValidator.Request(
                                axiom,
                                "consensus_schema",
                                "(p0 p1 p2 : Prop)",
                                statement,
                                schemaDigest,
                                List.of(),
                                Duration.ofSeconds(10))),
                "producer-declared axioms must fail the independent Lean boundary");

        Path unrelated = directory.resolve("UnrelatedSchema.lean");
        Files.writeString(
                unrelated,
                "def acgnSchemaDigest : String := \"" + schemaDigest + "\"\n"
                        + "theorem consensus_schema (p0 p1 p2 : Prop) : True := by\n"
                        + "  exact True.intro\n",
                StandardCharsets.UTF_8);
        expectThrows(IllegalArgumentException.class, () ->
                new LeanSchemaProofValidator().verify(
                        new LeanSchemaProofValidator.Request(
                                unrelated,
                                "consensus_schema",
                                "(p0 p1 p2 : Prop)",
                                statement,
                                schemaDigest,
                                List.of(),
                                Duration.ofSeconds(10))),
                "a true but unrelated Lean theorem must not certify the schema");

        Path commentedMarker = directory.resolve("CommentedMarker.lean");
        String marker = "def acgnSchemaDigest : String := \""
                + schemaDigest + "\"";
        Files.writeString(
                commentedMarker,
                leanProof(
                        schemaDigest,
                        "(p0 p1 p2 : Prop)",
                        statement).replace(
                                marker,
                                "-- " + marker + "\n"
                                        + "def acgnSchemaDigest : String := \""
                                        + "0".repeat(64) + "\""),
                StandardCharsets.UTF_8);
        expectThrows(IllegalArgumentException.class, () ->
                new LeanSchemaProofValidator().verify(
                        new LeanSchemaProofValidator.Request(
                                commentedMarker,
                                "consensus_schema",
                                "(p0 p1 p2 : Prop)",
                                statement,
                                schemaDigest,
                                List.of(),
                                Duration.ofSeconds(10))),
                "a schema digest present only in a Lean comment must not bind evidence");

        Path stringMarker = directory.resolve("StringMarker.lean");
        Files.writeString(
                stringMarker,
                "def decoy : String := r#\"def acgnSchemaDigest : String := \""
                        + schemaDigest + "\"\"#\n"
                        + "theorem consensus_schema (p0 p1 p2 : Prop) : "
                        + statement + " := by\n"
                        + leanProof(
                                schemaDigest,
                                "(p0 p1 p2 : Prop)",
                                statement).substring(leanProof(
                                        schemaDigest,
                                        "(p0 p1 p2 : Prop)",
                                        statement).indexOf("  constructor")),
                StandardCharsets.UTF_8);
        expectThrows(java.io.IOException.class, () ->
                new LeanSchemaProofValidator().verify(
                        new LeanSchemaProofValidator.Request(
                                stringMarker,
                                "consensus_schema",
                                "(p0 p1 p2 : Prop)",
                                statement,
                                schemaDigest,
                                List.of(),
                                Duration.ofSeconds(10))),
                "schema digest text inside a Lean string must not bind proof evidence");
    }

    private static void checkPinnedLeanExecutable() throws Exception {
        String prior = System.getProperty("acgn.lean");
        try {
            System.setProperty("acgn.lean", "/usr/bin/printf");
            expectThrows(IllegalStateException.class,
                    LeanSchemaProofValidator::new,
                    "a caller-selected non-Lean executable must fail the toolchain pin");
        } finally {
            if (prior == null) {
                System.clearProperty("acgn.lean");
            } else {
                System.setProperty("acgn.lean", prior);
            }
        }
    }

    private static void checkScopeBoundLocalEquality() throws Exception {
        String source = String.join("\n",
                "module scope_witness",
                "sig Z {}",
                "pred scopeSome { some Z }",
                "pred scopeOne { one Z }");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        CompModule module = CompUtil.parseEverything_fromString(null, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        CanonicalAlloyPipeline.Prepared some = prepare(visitor, "scopeSome", bytes);
        CanonicalAlloyPipeline.Prepared one = prepare(visitor, "scopeOne", bytes);
        check(CanonicalAlloyPipeline.distance(some, one) > 0,
                "scope witness must remain a positive R0 miss");

        EquivalenceAugmenter augmenter = new EquivalenceAugmenter();
        AlloyEquivalenceValidator.Request scopeOne = equivalentAtScope(
                source, "scopeSome", "scopeOne", 1);
        EquivalenceAugmenter.LocalRecordView local = augmenter.observeEquivalent(
                some, one, scopeOne);
        check(local.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL,
                "some Z and one Z agree at exact scope one");
        check(augmenter.evaluate(some, one).distance() > 0,
                "scope-one evidence must not authorize a context-free zero");
        check(augmenter.evaluate(some, one, scopeOne).distance() == 0,
                "scope-one evidence may authorize only its exact bounded context");

        AlloyEquivalenceValidator.Request scopeTwo = equivalentAtScope(
                source, "scopeSome", "scopeOne", 2);
        check(augmenter.evaluate(some, one, scopeTwo).distance() > 0,
                "scope-one evidence must not authorize scope two");
        EquivalenceAugmenter.LocalRecordView falsified = augmenter.observeEquivalent(
                some, one, scopeTwo);
        check(falsified.state() == EquivalenceAugmenter.State.FALSIFIED
                        && falsified.alloyOutcome().equals("COUNTEREXAMPLE"),
                "the larger accepted scope must retain its concrete counterexample");
        check(augmenter.evaluate(some, one, scopeTwo).distance() > 0,
                "a scope-two counterexample must keep scope-two distance positive");
    }

    private static void checkSourceCommandBoundValidation() throws Exception {
        String source = String.join("\n",
                "module source_command_context_regression",
                "sig A {}",
                "pred left { some A }",
                "pred right { #A = 1 }",
                "run { some A } for 2 but exactly 2 A, 0 Int");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        CompModule module = CompUtil.parseEverything_fromString(null, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        SemanticProfile profile = AlloySemanticProfileFactory.fromExactlyOne(
                module, List.of(module.getAllCommands().get(0)), options);
        CanonicalAlloyPipeline.Prepared left = CanonicalAlloyPipeline.prepareForVerification(
                visitor.getForest().get(visitor.getForestId("left")),
                profile,
                "source-command-context.als#left",
                bytes);
        CanonicalAlloyPipeline.Prepared right = CanonicalAlloyPipeline.prepareForVerification(
                visitor.getForest().get(visitor.getForestId("right")),
                profile,
                "source-command-context.als#right",
                bytes);
        check(CanonicalAlloyPipeline.distance(left, right) > 0,
                "source-command context witness must be a positive R0 miss");

        EquivalenceAugmenter missingContext = new EquivalenceAugmenter();
        expectThrows(IllegalArgumentException.class, () ->
                missingContext.observeEquivalent(
                        left,
                        right,
                        equivalentAtScope(source, "left", "right", 1)),
                "source-bound endpoints must reject request-only validation scopes");
        check(missingContext.localRecords().isEmpty(),
                "a missing source command context must fail before ledger mutation");

        AlloyEquivalenceValidator.Request exactContext =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "left",
                        "right",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        1,
                        Duration.ofSeconds(30),
                        AlloyEquivalenceValidator.SourceCommandContext.from(0, options));
        AlloyEquivalenceValidator.Evidence evidence =
                new AlloyEquivalenceValidator().validate(
                        exactContext,
                        GraphType.BOOL,
                        GraphType.BOOL,
                        profile);
        check(evidence.outcome() == AlloyEquivalenceValidator.Outcome.COUNTEREXAMPLE
                        && evidence.scope() == 2
                        && evidence.semanticProfileFingerprint().equals(
                                profile.fingerprint()),
                "validation must replay the exact source scope/options, not request scope one");
        EquivalenceAugmenter bounded = new EquivalenceAugmenter();
        EquivalenceAugmenter.LocalRecordView result = bounded.observeEquivalent(
                left, right, exactContext);
        check(result.state() == EquivalenceAugmenter.State.FALSIFIED
                        && bounded.evaluate(left, right, exactContext).distance() > 0,
                "a source-scope counterexample must prevent the false local merge");
    }

    private static void checkFollowUpCommandResultIsolation() throws Exception {
        String source = String.join("\n",
                "pred l { no none }",
                "pred r { some none }",
                "run { some none } for 1 => run { no none } for 1");
        CompModule module = CompUtil.parseEverything_fromString(null, source);
        check(module.getAllCommands().size() == 1
                        && module.getAllCommands().get(0).parent != null,
                "follow-up regression source must retain its executable parent chain");
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        expectThrows(IllegalArgumentException.class, () ->
                AlloySemanticProfileFactory.fromExactlyOne(
                        module, List.of(module.getAllCommands().get(0)), options),
                "a source profile must not erase follow-up command execution semantics");

        AlloyEquivalenceValidator validator = new AlloyEquivalenceValidator();
        AlloyEquivalenceValidator.Request sourceBound =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "l",
                        "r",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        1,
                        Duration.ofSeconds(30),
                        AlloyEquivalenceValidator.SourceCommandContext.from(0, options));
        AlloyEquivalenceValidator.Evidence rejected = validator.validate(
                sourceBound, GraphType.BOOL, GraphType.BOOL);
        check(rejected.outcome() == AlloyEquivalenceValidator.Outcome.ERROR
                        && rejected.detail().contains("Follow-up Alloy command chains"),
                "a parent command must not substitute its SAT result for equality");

        AlloyEquivalenceValidator.Request independent =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "l",
                        "r",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        1,
                        Duration.ofSeconds(30));
        AlloyEquivalenceValidator.Evidence actual = validator.validate(
                independent, GraphType.BOOL, GraphType.BOOL);
        check(actual.outcome() == AlloyEquivalenceValidator.Outcome.COUNTEREXAMPLE,
                "the independently executed l iff r check must retain its counterexample");
    }

    private static void checkSourceCommandFormulaGuard() throws Exception {
        assertSourceCommandFormulaGuard(
                String.join("\n",
                        "sig A {}",
                        "pred left { some A }",
                        "pred right { one A }",
                        "run { one A } for 2"),
                false,
                "run-search-domain");
        assertSourceCommandFormulaGuard(
                String.join("\n",
                        "sig A {}",
                        "pred left { one A }",
                        "pred right { some none }",
                        "check { one A } for 2"),
                true,
                "check-counterexample-domain");
    }

    private static void assertSourceCommandFormulaGuard(
            String source,
            boolean expectedCheck,
            String label) throws Exception {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        CompModule module = CompUtil.parseEverything_fromString(null, source);
        check(module.getAllCommands().size() == 1
                        && module.getAllCommands().get(0).check == expectedCheck
                        && module.getAllCommands().get(0).parent == null,
                label + " must select one independent command of the expected kind");
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        SemanticProfile profile = AlloySemanticProfileFactory.fromExactlyOne(
                module, List.of(module.getAllCommands().get(0)), options);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        CanonicalAlloyPipeline.Prepared left = CanonicalAlloyPipeline.prepareForVerification(
                visitor.getForest().get(visitor.getForestId("left")),
                profile,
                label + ".als#left",
                bytes);
        CanonicalAlloyPipeline.Prepared right = CanonicalAlloyPipeline.prepareForVerification(
                visitor.getForest().get(visitor.getForestId("right")),
                profile,
                label + ".als#right",
                bytes);
        check(CanonicalAlloyPipeline.distance(left, right) > 0,
                label + " must begin as a positive R0 miss");

        AlloyEquivalenceValidator.Request sourceBound =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "left",
                        "right",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        1,
                        Duration.ofSeconds(30),
                        AlloyEquivalenceValidator.SourceCommandContext.from(0, options));
        AlloyEquivalenceValidator validator = new AlloyEquivalenceValidator();
        AlloyEquivalenceValidator.Evidence guarded = validator.validate(
                sourceBound, GraphType.BOOL, GraphType.BOOL, profile);
        check(guarded.outcome()
                        == AlloyEquivalenceValidator.Outcome.NO_COUNTEREXAMPLE
                        && guarded.scope() == 2
                        && guarded.executedFormulaSha256().matches("[0-9a-f]{64}"),
                label + " must validate equality over the selected command search domain");

        AlloyEquivalenceValidator.Request unbound =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "left",
                        "right",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        2,
                        Duration.ofSeconds(30));
        check(validator.validate(unbound, GraphType.BOOL, GraphType.BOOL).outcome()
                        == AlloyEquivalenceValidator.Outcome.COUNTEREXAMPLE,
                label + " equality must remain false outside the command search domain");

        EquivalenceAugmenter augmenter = new EquivalenceAugmenter();
        EquivalenceAugmenter.LocalRecordView local = augmenter.observeEquivalent(
                left, right, sourceBound);
        EquivalenceAugmenter.Evaluation contextual = augmenter.evaluate(
                left, right, sourceBound);
        check(local.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL
                        && contextual.distance() == 0
                        && contextual.bootstrapDistance() > 0,
                label + " must authorize only its exact contextual local equality");
        augmenter.verifyCertificate(
                contextual.augmentationCertificate().orElseThrow());

        AlloyEquivalenceValidator.Request convenienceAlias =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "left",
                        "right",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        2,
                        Duration.ofSeconds(30),
                        AlloyEquivalenceValidator.SourceCommandContext.from(0, options));
        EquivalenceAugmenter.LocalRecordView alias = augmenter.observeEquivalent(
                left, right, convenienceAlias);
        EquivalenceAugmenter.Evaluation aliasEvaluation = augmenter.evaluate(
                left, right, convenienceAlias);
        check(alias.id().equals(local.id())
                        && alias.semanticContextDigest().equals(
                                local.semanticContextDigest())
                        && augmenter.localRecords().size() == 1
                        && aliasEvaluation.augmentationCertificate().orElseThrow().digest()
                        .equals(contextual.augmentationCertificate()
                                        .orElseThrow().digest()),
                label + " must ignore a request scope replaced by the source command");

        AlloyEquivalenceValidator.Request selectorAlias =
                new AlloyEquivalenceValidator.Request(
                        source,
                        "this/left",
                        "right",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        1,
                        Duration.ofSeconds(30),
                        AlloyEquivalenceValidator.SourceCommandContext.from(0, options));
        EquivalenceAugmenter.LocalRecordView resolvedAlias =
                augmenter.observeEquivalent(left, right, selectorAlias);
        EquivalenceAugmenter.Evaluation resolvedAliasEvaluation =
                augmenter.evaluate(left, right, selectorAlias);
        check(resolvedAlias.id().equals(local.id())
                        && resolvedAlias.semanticContextDigest().equals(
                                local.semanticContextDigest())
                        && augmenter.localRecords().size() == 1
                        && resolvedAliasEvaluation.augmentationCertificate()
                                .orElseThrow().digest().equals(
                                        contextual.augmentationCertificate()
                                                .orElseThrow().digest()),
                label + " must normalize parser-equivalent endpoint selectors");
    }

    private static void checkSinglePairCannotGeneralize(
            CanonicalAlloyPipeline.Prepared left,
            CanonicalAlloyPipeline.Prepared right,
            String source) throws Exception {
        EquivalenceAugmenter augmenter = new EquivalenceAugmenter();
        EquivalenceAugmenter.LocalRecordView scopeThree = augmenter.observeEquivalent(
                left, right, equivalentAtScope(source, "a", "b", 3));
        EquivalenceAugmenter.LocalRecordView scopeFour = augmenter.observeEquivalent(
                left, right, equivalentAtScope(source, "a", "b", 4));
        check(scopeThree.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL
                        && scopeFour.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL
                        && !scopeThree.id().equals(scopeFour.id()),
                "distinct validation contexts must retain distinct local observations");
        expectThrows(IllegalArgumentException.class, () -> augmenter.proposeSchema(
                        List.of(scopeThree.id(), scopeFour.id())),
                "two scopes of one endpoint pair must never count as two schema examples");
    }

    private static void checkQuantifiedAtomIdentity(Path directory) throws Exception {
        String source = String.join("\n",
                "module quantified_atom_identity",
                "sig Top {}",
                "sig U {}",
                "sig V {}",
                "sig X {}",
                "sig Y {}",
                "sig M extends Top {}",
                "sig N extends Top {}",
                "one sig W { p, q, r: set Top }",
                "fact { no U and no V and no X and no Y }",
                "pred a { (all x: U | some W.r) and (some W.p and some W.p) }",
                "pred b { (all x: V | some W.r) and some W.p }",
                "pred c { (all x: X | some W.r) and (some W.q and some W.q) }",
                "pred d { (all x: Y | some W.r) and some W.q }",
                "pred e { (all x: M | some W.r) and (some W.p and some W.p) }",
                "pred f { (all x: N | some W.r) and some W.p }");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        CompModule module = CompUtil.parseEverything_fromString(null, source);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(new ModelUnit(null, module), null);
        CanonicalAlloyPipeline.Prepared a = prepare(visitor, "a", bytes);
        CanonicalAlloyPipeline.Prepared b = prepare(visitor, "b", bytes);
        CanonicalAlloyPipeline.Prepared c = prepare(visitor, "c", bytes);
        CanonicalAlloyPipeline.Prepared d = prepare(visitor, "d", bytes);
        CanonicalAlloyPipeline.Prepared e = prepare(visitor, "e", bytes);
        CanonicalAlloyPipeline.Prepared f = prepare(visitor, "f", bytes);
        check(CanonicalAlloyPipeline.distance(a, b) > 0
                        && CanonicalAlloyPipeline.distance(c, d) > 0
                        && CanonicalAlloyPipeline.distance(e, f) > 0,
                "quantified-domain witnesses must begin as positive R0 pairs");

        EquivalenceAugmenter augmenter = new EquivalenceAugmenter();
        var first = augmenter.observeEquivalent(a, b, equivalent(source, "a", "b"));
        var second = augmenter.observeEquivalent(c, d, equivalent(source, "c", "d"));
        check(first.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL
                        && second.state() == EquivalenceAugmenter.State.VERIFIED_LOCAL,
                "empty-domain facts may certify only their exact local pairs");
        var candidate = augmenter.proposeSchema(List.of(first.id(), second.id()));
        check(candidate.leanTheoremParameters().equals("(p0 p1 p2 : Prop)")
                        && candidate.leanTheoremStatement().contains("p1")
                        && candidate.leanTheoremStatement().contains("p2"),
                "quantified domains must remain distinct Lean propositions: "
                        + candidate.leanTheoremStatement());

        Path proof = directory.resolve("QuantifiedDomainCollision.lean");
        Files.writeString(
                proof,
                "def acgnSchemaDigest : String := \"" + candidate.schemaDigest()
                        + "\"\n\n"
                        + "theorem quantified_domain_schema "
                        + candidate.leanTheoremParameters() + " : "
                        + candidate.leanTheoremStatement() + " := by\n"
                        + "  simp\n",
                StandardCharsets.UTF_8);
        String r0 = "R0:" + augmenter.bootstrap().digest();
        expectThrows(java.io.IOException.class, () -> augmenter.verifySchema(
                        candidate.id(),
                        new EquivalenceAugmenter.SchemaValidation(
                                equivalent(source, "a", "b"),
                                quantifiedWeakeningProbes(source),
                                new LeanSchemaProofValidator.Request(
                                        proof,
                                        "quantified_domain_schema",
                                        candidate.leanTheoremParameters(),
                                        candidate.leanTheoremStatement(),
                                        candidate.schemaDigest(),
                                        List.of(r0),
                                        Duration.ofSeconds(30)),
                                List.of(r0))),
                "Lean must reject a schema that equates distinct quantified domains");
        check(augmenter.schemaRecords().get(0).state()
                        == EquivalenceAugmenter.State.CANDIDATE,
                "a rejected quantified schema must remain unadmitted and retryable");
        expectThrows(IllegalArgumentException.class,
                () -> augmenter.admitSchema(candidate.id()),
                "a failed quantified-domain proof must not be admitted");
        check(augmenter.evaluate(e, f, equivalent(source, "e", "f")).distance() > 0,
                "an unseen nonempty-domain pair must remain positive");
        AlloyEquivalenceValidator.Evidence counterexample =
                new AlloyEquivalenceValidator().validate(
                        counterexample(
                                source,
                                "e",
                                "f",
                                AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF),
                        GraphType.BOOL,
                        GraphType.BOOL);
        check(counterexample.validatesExpectedOutcome(),
                "the blocked unseen pair must retain its Alloy counterexample");
    }

    private static void checkScopeAwareAtomKeys() {
        String source = String.join("\n",
                "module scope_aware_atom_keys",
                "sig A {}",
                "sig B {}");
        AlloyBooleanSchema.Pair alpha = AlloyBooleanSchema.analyze(
                new AlloyEquivalenceValidator.Request(
                        source,
                        "some x: A | some x",
                        "some renamed: A | some renamed",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        4,
                        Duration.ofSeconds(30)));
        check(alpha.left().equals(alpha.right()),
                "opaque quantified atoms must preserve alpha-equivalence");
        AlloyBooleanSchema.Pair differentDomain = AlloyBooleanSchema.analyze(
                new AlloyEquivalenceValidator.Request(
                        source,
                        "some x: A | some x",
                        "some x: B | some x",
                        "",
                        AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                        AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                        4,
                        Duration.ofSeconds(30)));
        check(!differentDomain.left().equals(differentDomain.right()),
                "opaque quantified atoms must retain their declaration domains");
    }

    private static void checkCompleteAtomIdentity() {
        String source = String.join("\n",
                "module complete_atom_identity",
                "sig A { r: set A }",
                "pred letA { let x = A | some x }",
                "pred letB { let renamed = A | some renamed }",
                "pred letChanged { let x = A.r | some x }",
                "pred nestedA { all x: A | all y: x.r | y in x.r }",
                "pred nestedB { all u: A | all v: u.r | v in u.r }",
                "pred nestedChanged { all u: A | all v: A | v in u.r }",
                "pred disjA { some disj x, y: A | x != y }",
                "pred disjB { some x, y: A | x != y }",
                "pred multA { some x: set A | some x }",
                "pred multB { some x: one A | some x }",
                "pred callA { nestedA and letA }",
                "pred callB { nestedA and letA }");
        check(atomPairEqual(source, "letA", "letB"),
                "opaque let atoms must preserve alpha-equivalence");
        check(!atomPairEqual(source, "letA", "letChanged"),
                "opaque let atoms must retain changed bound expressions");
        check(atomPairEqual(source, "nestedA", "nestedB"),
                "nested quantified atoms must preserve scoped alpha-equivalence");
        check(!atomPairEqual(source, "nestedA", "nestedChanged"),
                "nested quantified atoms must retain dependent domains");
        check(atomPairEqual(source, "callA", "callB"),
                "opaque atom identity must be stable through nested calls");
        check(!atomPairEqual(source, "disjA", "disjB"),
                "opaque quantified atoms must retain declaration disjointness");
        check(!atomPairEqual(source, "multA", "multB"),
                "opaque quantified atoms must retain declaration multiplicity");
    }

    private static boolean atomPairEqual(
            String source,
            String left,
            String right) {
        AlloyBooleanSchema.Pair pair = AlloyBooleanSchema.analyze(
                new AlloyEquivalenceValidator.Request(
                source,
                left,
                right,
                "",
                AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                4,
                Duration.ofSeconds(30)));
        return pair.left().equals(pair.right());
    }

    private static void checkBootstrapCoverage(
            MASGVisitor visitor,
            byte[] sourceBytes,
            String firstLeftName,
            String firstRightName,
            String secondLeftName,
            String secondRightName,
            String thirdLeftName,
            String thirdRightName,
            String label) {
        check(CanonicalAlloyPipeline.distance(
                                prepare(visitor, firstLeftName, sourceBytes),
                                prepare(visitor, firstRightName, sourceBytes)) == 0
                        && CanonicalAlloyPipeline.distance(
                                prepare(visitor, secondLeftName, sourceBytes),
                                prepare(visitor, secondRightName, sourceBytes)) == 0
                        && CanonicalAlloyPipeline.distance(
                                prepare(visitor, thirdLeftName, sourceBytes),
                                prepare(visitor, thirdRightName, sourceBytes)) == 0,
                label + " is already immutable R0 coverage, not an adaptive miss");
    }

    private static void checkGrowthBound(
            MASGVisitor visitor,
            byte[] sourceBytes,
            String source,
            Path directory) throws Exception {
        EquivalenceAugmenter limited = new EquivalenceAugmenter(
                BootstrapTheoryR0.current(),
                new EquivalenceAugmenter.Limits(
                        20, 10, 2, 2, 1, 10, 20, 50),
                new AlloyEquivalenceValidator(),
                new LeanSchemaProofValidator());
        CanonicalAlloyPipeline.Prepared a = prepare(visitor, "a", sourceBytes);
        CanonicalAlloyPipeline.Prepared b = prepare(visitor, "b", sourceBytes);
        CanonicalAlloyPipeline.Prepared c = prepare(visitor, "c", sourceBytes);
        CanonicalAlloyPipeline.Prepared d = prepare(visitor, "d", sourceBytes);
        var first = limited.observeEquivalent(a, b, equivalent(source, "a", "b"));
        var second = limited.observeEquivalent(c, d, equivalent(source, "c", "d"));
        var candidate = limited.proposeSchema(List.of(first.id(), second.id()));
        String parameters = candidate.leanTheoremParameters();
        String statement = candidate.leanTheoremStatement();
        Path lean = directory.resolve("LimitedConsensus.lean");
        Files.writeString(
                lean, leanProof(candidate.schemaDigest(), parameters, statement),
                StandardCharsets.UTF_8);
        String r0 = "R0:" + limited.bootstrap().digest();
        limited.verifySchema(candidate.id(), new EquivalenceAugmenter.SchemaValidation(
                equivalent(source, "schemaLeft", "schemaRight"),
                weakeningProbes(source),
                new LeanSchemaProofValidator.Request(
                        lean, "consensus_schema", parameters, statement,
                        candidate.schemaDigest(),
                        List.of(r0), Duration.ofSeconds(30)),
                List.of(r0)));
        expectThrows(IllegalStateException.class, () -> limited.admitSchema(candidate.id()),
                "schema admission must stop before uncontrolled affected-region growth");
        check(limited.schemaRecords().get(0).state()
                        == EquivalenceAugmenter.State.VERIFIED_SCHEMA,
                "a growth-budget rejection must not falsify a proved equality");

        EquivalenceAugmenter applicationLimited = new EquivalenceAugmenter(
                BootstrapTheoryR0.current(),
                new EquivalenceAugmenter.Limits(
                        20, 10, 2, 2, 2, 10, 20, 50),
                new AlloyEquivalenceValidator(),
                new LeanSchemaProofValidator());
        var applicationFirst = applicationLimited.observeEquivalent(
                a, b, equivalent(source, "a", "b"));
        var applicationSecond = applicationLimited.observeEquivalent(
                c, d, equivalent(source, "c", "d"));
        var applicationCandidate = applicationLimited.proposeSchema(
                List.of(applicationFirst.id(), applicationSecond.id()));
        Path applicationLean = directory.resolve("ApplicationLimitedConsensus.lean");
        Files.writeString(
                applicationLean,
                leanProof(
                        applicationCandidate.schemaDigest(),
                        applicationCandidate.leanTheoremParameters(),
                        applicationCandidate.leanTheoremStatement()),
                StandardCharsets.UTF_8);
        String applicationR0 = "R0:" + applicationLimited.bootstrap().digest();
        applicationLimited.verifySchema(
                applicationCandidate.id(),
                new EquivalenceAugmenter.SchemaValidation(
                        equivalent(source, "schemaLeft", "schemaRight"),
                        weakeningProbes(source),
                        new LeanSchemaProofValidator.Request(
                                applicationLean,
                                "consensus_schema",
                                applicationCandidate.leanTheoremParameters(),
                                applicationCandidate.leanTheoremStatement(),
                                applicationCandidate.schemaDigest(),
                                List.of(applicationR0),
                                Duration.ofSeconds(30)),
                        List.of(applicationR0)));
        applicationLimited.admitSchema(applicationCandidate.id());
        CanonicalAlloyPipeline.Prepared e = prepare(visitor, "e", sourceBytes);
        CanonicalAlloyPipeline.Prepared f = prepare(visitor, "f", sourceBytes);
        expectThrows(IllegalStateException.class, () -> applicationLimited.evaluate(
                        e, f, equivalent(source, "e", "f")),
                "an unseen schema application must consume the affected-pair budget");
        check(applicationLimited.schemaRecords().get(0).affectedPairs() == 2
                        && applicationLimited.applicationRecords().isEmpty(),
                "rejected growth must not partially mutate schema or application ledgers");
    }

    private static EnumMap<EquivalenceAugmenter.GuardDimension,
            EquivalenceAugmenter.WeakeningProbe> weakeningProbes(String source) {
        return weakeningProbes(source, "AlloySig:U");
    }

    private static EnumMap<EquivalenceAugmenter.GuardDimension,
            EquivalenceAugmenter.WeakeningProbe> weakeningProbes(
                    String source,
                    String carrier) {
        GraphType unary = GraphType.relation(GraphType.constructor(carrier));
        EnumMap<EquivalenceAugmenter.GuardDimension,
                EquivalenceAugmenter.WeakeningProbe> probes =
                new EnumMap<>(EquivalenceAugmenter.GuardDimension.class);
        probes.put(
                EquivalenceAugmenter.GuardDimension.OPERATOR,
                new EquivalenceAugmenter.WeakeningProbe(
                        counterexample(
                                source, "some W.p iff some W.q",
                                "some W.p or some W.q",
                                AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF),
                        GraphType.BOOL,
                        GraphType.BOOL));
        probes.put(
                EquivalenceAugmenter.GuardDimension.EXACT_TYPE,
                new EquivalenceAugmenter.WeakeningProbe(
                        counterexample(
                                source, "W.p", "W.p + W.q",
                                AlloyEquivalenceValidator.ComparisonKind.TERM_EQUALITY),
                        unary,
                        unary));
        probes.put(
                EquivalenceAugmenter.GuardDimension.ARITY,
                new EquivalenceAugmenter.WeakeningProbe(
                        counterexample(
                                source,
                                "some W.p or some W.q",
                                "some W.p or some W.q or some W.r or no W.p",
                                AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF),
                        GraphType.BOOL,
                        GraphType.BOOL));
        return probes;
    }

    private static EnumMap<EquivalenceAugmenter.GuardDimension,
            EquivalenceAugmenter.WeakeningProbe> quantifiedWeakeningProbes(
                    String source) {
        EnumMap<EquivalenceAugmenter.GuardDimension,
                EquivalenceAugmenter.WeakeningProbe> probes =
                weakeningProbes(source, "AlloySig:Top");
        probes.put(
                EquivalenceAugmenter.GuardDimension.ARITY,
                new EquivalenceAugmenter.WeakeningProbe(
                        counterexample(
                                source,
                                "some W.p and some W.q",
                                "some W.p and some W.q and some W.r and no W.p",
                                AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF),
                        GraphType.BOOL,
                        GraphType.BOOL));
        return probes;
    }

    private static AlloyEquivalenceValidator.Request equivalent(
            String source,
            String left,
            String right) {
        return new AlloyEquivalenceValidator.Request(
                source,
                left,
                right,
                "",
                AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                4,
                Duration.ofSeconds(30));
    }

    private static AlloyEquivalenceValidator.Request equivalentAtScope(
            String source,
            String left,
            String right,
            int scope) {
        return new AlloyEquivalenceValidator.Request(
                source,
                left,
                right,
                "",
                AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF,
                AlloyEquivalenceValidator.ExpectedOutcome.NO_COUNTEREXAMPLE,
                scope,
                Duration.ofSeconds(30));
    }

    private static AlloyEquivalenceValidator.Request counterexample(
            String source,
            String left,
            String right,
            AlloyEquivalenceValidator.ComparisonKind kind) {
        return new AlloyEquivalenceValidator.Request(
                source,
                left,
                right,
                "",
                kind,
                AlloyEquivalenceValidator.ExpectedOutcome.COUNTEREXAMPLE_REQUIRED,
                4,
                Duration.ofSeconds(30));
    }

    private static CanonicalAlloyPipeline.Prepared prepare(
            MASGVisitor visitor,
            String predicate,
            byte[] source) {
        Integer id = visitor.getForestId(predicate);
        if (id == null) {
            throw new AssertionError("Missing predicate " + predicate);
        }
        Multigraph graph = visitor.getForest().get(id);
        return CanonicalAlloyPipeline.prepareCompatibilityForVerification(
                graph, "augmentation.als#" + predicate, source);
    }

    private static String leanProof(
            String schemaDigest,
            String parameters,
            String statement) {
        return "def acgnSchemaDigest : String := \"" + schemaDigest + "\"\n\n"
                + "theorem consensus_schema " + parameters + " : "
                + statement + " := by\n"
                + "  constructor\n"
                + "  case mp =>\n"
                + "    intro h\n"
                + "    cases h with\n"
                + "    | inl hpq => exact Or.inl hpq\n"
                + "    | inr hnpr => exact Or.inr (Or.inl hnpr)\n"
                + "  case mpr =>\n"
                + "    intro h\n"
                + "    cases h with\n"
                + "    | inl hpq => exact Or.inl hpq\n"
                + "    | inr hrest =>\n"
                + "      cases hrest with\n"
                + "      | inl hnpr => exact Or.inr hnpr\n"
                + "      | inr hqr =>\n"
                + "        cases Classical.em p1 with\n"
                + "        | inl hp => exact Or.inl (And.intro hp hqr.left)\n"
                + "        | inr hnp => exact Or.inr (And.intro hnp hqr.right)\n";
    }

    private static String source() {
        return String.join("\n",
                "module equivalence_augmenter",
                "sig U {}",
                "one sig W { p, q, r: set U, rel: U -> U }",
                "one sig X { p, q, r: set U }",
                "one sig Y { p, q, r: set U }",
                "pred a {",
                "  (some W.p and some W.q) or",
                "  (no W.p and some W.r) or",
                "  (some W.q and some W.r)",
                "}",
                "pred b {",
                "  (some W.p and some W.q) or (no W.p and some W.r)",
                "}",
                "pred c {",
                "  (some X.p and some X.q) or",
                "  (no X.p and some X.r) or",
                "  (some X.q and some X.r)",
                "}",
                "pred d {",
                "  (some X.p and some X.q) or (no X.p and some X.r)",
                "}",
                "pred e {",
                "  (some Y.p and some Y.q) or",
                "  (no Y.p and some Y.r) or",
                "  (some Y.q and some Y.r)",
                "}",
                "pred f {",
                "  (some Y.p and some Y.q) or (no Y.p and some Y.r)",
                "}",
                "pred schemaLeft {",
                "  (some W.p and some W.q) or",
                "  (no W.p and some W.r) or",
                "  (some W.q and some W.r)",
                "}",
                "pred schemaRight {",
                "  (some W.p and some W.q) or (no W.p and some W.r)",
                "}",
                "pred falsehood { some W.p and no W.p }",
                "pred absLeftW { some W.p or (some W.p and some W.q) }",
                "pred absRightW { some W.p }",
                "pred absLeftX { some X.p or (some X.p and some X.q) }",
                "pred absRightX { some X.p }",
                "pred absLeftY { some Y.p or (some Y.p and some Y.q) }",
                "pred absRightY { some Y.p }",
                "pred distLeftW { some W.p and (some W.q or some W.r) }",
                "pred distRightW {",
                "  (some W.p and some W.q) or (some W.p and some W.r)",
                "}",
                "pred distLeftX { some X.p and (some X.q or some X.r) }",
                "pred distRightX {",
                "  (some X.p and some X.q) or (some X.p and some X.r)",
                "}",
                "pred distLeftY { some Y.p and (some Y.q or some Y.r) }",
                "pred distRightY {",
                "  (some Y.p and some Y.q) or (some Y.p and some Y.r)",
                "}",
                "");
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> expected,
            ThrowingAction action,
            String message) throws Exception {
        checks++;
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError(message + ": wrong failure " + failure, failure);
        }
        throw new AssertionError(message + ": no failure");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}

package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.ErrorType;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.alloy.SigSymbol;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.QuantiVar.Cardinality;
import is.fivefivefive.CanDis.core.egraph.AblationEngine;
import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import is.fivefivefive.CanDis.core.egraph.JavaEgglog;
import is.fivefivefive.CanDis.core.egraph.JavaEgglogDeBruijn;
import is.fivefivefive.CanDis.core.egraph.RawDeBruijnEGraph;
import is.fivefivefive.CanDis.core.egraph.RawEGraph;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;
import is.fivefivefive.CanDis.theory.AlloySemanticProfileFactory;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;
import parser.util.AlloyUtil;

/** Alloy-backed regressions for empty binding domains and relational subset. */
public final class AlloySourceRuleRegressionTest {
    private static int checks;

    private AlloySourceRuleRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("candis-source-rules-");
        Path modelPath = directory.resolve("source_rules.als");
        try {
            Files.writeString(modelPath, source(), StandardCharsets.UTF_8);
            CompModule module = AlloyUtil.compileAlloyModule(modelPath.toString());
            check(module != null, "source-rule fixture must parse");

            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            // Translation support for relation-valued quantified variables is
            // command-specific. The first six commands have fixed direct checks;
            // the higher-order authority ledger below records every mixed result.
            A4Solution instance = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(0),
                    options);
            check(instance != null && !instance.satisfiable(),
                    "Alloy must prove that not (none in none) is unsatisfiable");
            A4Solution compoundEmptyInstance = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(1),
                    options);
            check(compoundEmptyInstance != null
                            && !compoundEmptyInstance.satisfiable(),
                    "Alloy must prove one (none & A) has no admissible binding");
            A4Solution selfDifferenceInstance = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(2),
                    options);
            check(selfDifferenceInstance != null
                            && !selfDifferenceInstance.satisfiable(),
                    "Alloy must prove one (A - A) has no admissible binding");
            A4Solution leftEmptyDifferenceInstance = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(3),
                    options);
            check(leftEmptyDifferenceInstance != null
                            && !leftEmptyDifferenceInstance.satisfiable(),
                    "Alloy must prove one (none - A) has no admissible binding");
            A4Solution emptyUniverseSubset = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(4),
                    options);
            check(emptyUniverseSubset != null && emptyUniverseSubset.satisfiable(),
                    "univ in none is true in the empty zero-scope universe");
            A4Solution emptyUniverseNegation = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    module.getAllReachableSigs(),
                    module.getAllCommands().get(5),
                    options);
            check(emptyUniverseNegation != null
                            && !emptyUniverseNegation.satisfiable(),
                    "not (univ in none) is false in the empty zero-scope universe");
            checkHigherOrderCommandAuthorities(module, options);

            ModelUnit model = new ModelUnit(null, module);
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(model, null);
            checkEmptyUniverseNormalization(module, visitor, options, directory);
            checkUnsafePrenexRegressions(visitor, directory);
            checkAdversarialPrenexRegressions(visitor, directory);
            checkNestedLocalAlphaRegression(directory);
            CanonicalAlloyPipeline.Prepared truth = prepare(visitor, "truth");
            CanonicalAlloyPipeline.Prepared falsehood = prepare(visitor, "falsehood");
            CanonicalAlloyPipeline.Prepared setSome = prepare(visitor, "setSome");
            CanonicalAlloyPipeline.Prepared setAllFalse =
                    prepare(visitor, "setAllFalse");
            CanonicalAlloyPipeline.Prepared loneSome = prepare(visitor, "loneSome");
            CanonicalAlloyPipeline.Prepared oneSome = prepare(visitor, "oneSome");
            CanonicalAlloyPipeline.Prepared noneSubset =
                    prepare(visitor, "noneSubset");
            CanonicalAlloyPipeline.Prepared notNoneSubset =
                    prepare(visitor, "notNoneSubset");
            CanonicalAlloyPipeline.Prepared notUnivSubset =
                    prepare(visitor, "notUnivSubset");
            CanonicalAlloyPipeline.Prepared notNoneUniv =
                    prepare(visitor, "notNoneUniv");
            CanonicalAlloyPipeline.Prepared letSetSome =
                    prepare(visitor, "letSetSome");
            check(prepare(visitor, "unionOnly") != null,
                    "a same-arity exact relation family must retain relational union laws");
            check(!prepare(visitor, "userNoneSome").equivalentTo(falsehood),
                    "a user signature named None must not normalize as Alloy none");
            check(!prepare(visitor, "userUnivSome").equivalentTo(truth),
                    "a user signature named Univ must not normalize as Alloy univ");
            check(!prepare(visitor, "userNamesSubset").equivalentTo(truth)
                            && !prepare(visitor, "userNamesSubset").equivalentTo(falsehood),
                    "membership between user None/Univ signatures must remain guarded");

            check(!setSome.equivalentTo(falsehood),
                    "some x: set none must not collapse as an empty domain");
            check(!setAllFalse.equivalentTo(truth),
                    "all x: set none | false must not collapse to true");
            check(!loneSome.equivalentTo(falsehood),
                    "some x: lone none must retain the empty binding");
            check(!letSetSome.equivalentTo(falsehood),
                    "a let-bound none declaration must retain the empty relation binding");
            check(oneSome.equivalentTo(falsehood),
                    "one none has no admissible binding");
            check(prepare(visitor, "compoundEmpty").equivalentTo(falsehood),
                    "positive exact multiplicity over a proved-empty intersection has no binding");
            check(prepare(visitor, "compoundDifferenceEmpty").equivalentTo(falsehood),
                    "positive exact multiplicity over a self-difference has no binding");
            check(prepare(visitor, "leftEmptyDifference").equivalentTo(falsehood),
                    "positive exact multiplicity over a left-empty difference has no binding");
            check(noneSubset.equivalentTo(truth),
                    "none in none is relational subset truth");
            check(notNoneSubset.equivalentTo(falsehood)
                            && !notUnivSubset.equivalentTo(truth)
                            && !notUnivSubset.equivalentTo(falsehood)
                            && notNoneUniv.equivalentTo(falsehood),
                    "NNF NOT_IN rules require evidence before assuming univ nonempty");
            check(prepare(visitor, "notLoneNone").equivalentTo(falsehood)
                            && prepare(visitor, "notOneNone").equivalentTo(truth),
                    "guarded child rewrites close exposed Boolean negation");
            check(prepare(visitor, "bareParamInNone").equivalentTo(falsehood)
                            && prepare(visitor, "oneParamInNone").equivalentTo(falsehood)
                            && prepare(visitor, "someParamInNone").equivalentTo(falsehood),
                    "nonempty QuantiVar cardinalities discharge x in none");
            check(!prepare(visitor, "setParamInNone").equivalentTo(falsehood)
                            && !prepare(visitor, "loneParamInNone").equivalentTo(falsehood),
                    "set and lone binding tuples do not counterfeit nonemptiness");
            check(CanonicalAlloyPipeline.distance(
                            prepare(visitor, "notLoneNone"), falsehood) == 0
                            && CanonicalAlloyPipeline.distance(
                                    prepare(visitor, "notOneNone"), truth) == 0,
                    "certified observations and repair metric agree after guard closure");

            check(singleBinding(visitor, "setSome").getMatrixQuantiVars().get(0)
                            .getCardinality() == Cardinality.SET,
                    "set none multiplicity remains in the quantifier tuple");
            check(singleBinding(visitor, "loneSome").getMatrixQuantiVars().get(0)
                            .getCardinality() == Cardinality.LONE,
                    "lone none multiplicity remains in the quantifier tuple");
            check("none".equals(singleBinding(visitor, "letSetSome")
                            .getMatrixQuantiVars().get(0).getTypeName()),
                    "a let expression statically equal to none must not invent univ provenance");
            checkGenericLabelCannotMintNonemptiness();
            checkExactlyOfDoesNotMintNonemptiness();
            checkAblationBindingGuards(model);

            System.out.println("AlloySourceRuleRegressionTest passed: "
                    + checks + " checks");
        } finally {
            Files.deleteIfExists(modelPath);
            Files.deleteIfExists(directory);
        }
    }

    private enum SolverAuthority {
        TRANSLATED_SAT,
        TRANSLATED_UNSAT,
        PARSER_TYPE_LOWERING_ONLY
    }

    private record HigherOrderExpectation(
            String commandLabel,
            SolverAuthority authority) {
    }

    private record CommandAuthorityRecord(
            int commandIndex,
            String commandLabel,
            SolverAuthority authority,
            String unsupportedReason) {

        private CommandAuthorityRecord {
            if (commandIndex < 0 || commandLabel == null || commandLabel.isBlank()
                    || authority == null) {
                throw new IllegalArgumentException(
                        "Command authority records require an index, label, and authority");
            }
            if ((authority == SolverAuthority.PARSER_TYPE_LOWERING_ONLY)
                    != (unsupportedReason != null && !unsupportedReason.isBlank())) {
                throw new IllegalArgumentException(
                        "Only parser/type/lowering authority requires an unsupported reason");
            }
        }
    }

    private static void checkHigherOrderCommandAuthorities(
            CompModule module,
            A4Options options) throws Exception {
        int firstCommand = 6;
        List<HigherOrderExpectation> expected = List.of(
                new HigherOrderExpectation("setSome", SolverAuthority.TRANSLATED_SAT),
                new HigherOrderExpectation(
                        "notSetSome", SolverAuthority.PARSER_TYPE_LOWERING_ONLY),
                new HigherOrderExpectation(
                        "setAllFalse", SolverAuthority.PARSER_TYPE_LOWERING_ONLY),
                new HigherOrderExpectation("loneSome", SolverAuthority.TRANSLATED_SAT),
                new HigherOrderExpectation(
                        "notLoneSome", SolverAuthority.PARSER_TYPE_LOWERING_ONLY),
                new HigherOrderExpectation(
                        "loneAllFalse", SolverAuthority.PARSER_TYPE_LOWERING_ONLY),
                new HigherOrderExpectation("bareSome", SolverAuthority.TRANSLATED_UNSAT),
                new HigherOrderExpectation("notBareAll", SolverAuthority.TRANSLATED_UNSAT),
                new HigherOrderExpectation("oneSome", SolverAuthority.TRANSLATED_UNSAT),
                new HigherOrderExpectation("someSome", SolverAuthority.TRANSLATED_UNSAT),
                new HigherOrderExpectation(
                        "notNoneSubset", SolverAuthority.TRANSLATED_UNSAT),
                new HigherOrderExpectation(
                        "compoundEmpty", SolverAuthority.TRANSLATED_UNSAT));
        check(module.getAllCommands().size() == firstCommand + expected.size(),
                "Every higher-order authority fixture must have one command record");

        List<CommandAuthorityRecord> records = new ArrayList<>();
        for (int offset = 0; offset < expected.size(); offset++) {
            int commandIndex = firstCommand + offset;
            HigherOrderExpectation expectation = expected.get(offset);
            Command command = module.getAllCommands().get(commandIndex);
            check(expectation.commandLabel().equals(command.label),
                    "Command authority record must bind parser label "
                            + expectation.commandLabel());
            try {
                A4Solution solution = TranslateAlloyToKodkod.execute_command(
                        A4Reporter.NOP,
                        module.getAllReachableSigs(),
                        command,
                        options);
                check(solution != null,
                        "Translated command must return a solver result for "
                                + expectation.commandLabel());
                SolverAuthority authority = solution.satisfiable()
                        ? SolverAuthority.TRANSLATED_SAT
                        : SolverAuthority.TRANSLATED_UNSAT;
                records.add(new CommandAuthorityRecord(
                        commandIndex,
                        command.label,
                        authority,
                        null));
            } catch (ErrorType unsupported) {
                String message = unsupported.getMessage();
                if (message == null
                        || !message.contains(
                                "higher-order quantification that could not be skolemized")) {
                    throw unsupported;
                }
                records.add(new CommandAuthorityRecord(
                        commandIndex,
                        command.label,
                        SolverAuthority.PARSER_TYPE_LOWERING_ONLY,
                        unsupported.getClass().getName()));
            }
        }

        check(records.size() == expected.size(),
                "Every selected higher-order command emits exactly one authority record");
        for (int offset = 0; offset < records.size(); offset++) {
            CommandAuthorityRecord record = records.get(offset);
            HigherOrderExpectation expectation = expected.get(offset);
            check(record.commandIndex() == firstCommand + offset,
                    "Command authority indices remain contiguous and source ordered");
            check(record.commandLabel().equals(expectation.commandLabel())
                            && record.authority() == expectation.authority(),
                    "Command-specific authority matches Alloy translation for "
                            + expectation.commandLabel());
            System.out.println("SOURCE_RULE_AUTHORITY commandIndex="
                    + record.commandIndex()
                    + " command=" + record.commandLabel()
                    + " authority=" + record.authority()
                    + (record.unsupportedReason() == null
                            ? ""
                            : " reason=" + record.unsupportedReason()));
        }
    }

    private static void checkGenericLabelCannotMintNonemptiness() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        EGraphNode carrier = leaf(
                1, Opcode.GLOBALBINDING, "S", Metatype.SET,
                ExactAlloyType.unaryRelation("S"), profile);
        EGraphNode alleged = node(
                2, Opcode.SOME, Metatype.SET,
                ExactAlloyType.unaryRelation("S"), profile, carrier);
        EGraphNode none = EGraphNode.builtinSetConstant(
                3,
                SigSymbol.builtinNone(),
                ExactAlloyType.unaryRelation("S"),
                profile);
        EGraphNode subset = node(
                4, Opcode.IN, Metatype.BOOLEAN,
                ExactAlloyType.boolType(), profile, alleged, none);
        NormalForm form = new NormalForm();
        form.addEClass(subset);
        form.normalize();
        check(form.getCertificationMatrixEGraph().getOpcode() == Opcode.IN
                        && form.getMatrixEGraph().getOpcode() == Opcode.IN,
                "generic SOME labels cannot mint source nonemptiness evidence");
    }

    private static void checkExactlyOfDoesNotMintNonemptiness() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        ExactAlloyType unaryA = ExactAlloyType.unaryRelation("A");
        EGraphNode none = EGraphNode.builtinSetConstant(
                20, SigSymbol.builtinNone(), unaryA, profile);
        EGraphNode exactDomain = node(
                21, Opcode.EXACTLY, Metatype.SET, unaryA, profile, none);
        EGraphNode variable = variable(22, "x", unaryA, profile);
        EGraphNode quantified = node(
                23, Opcode.EXISTS, Metatype.BOOLEAN,
                ExactAlloyType.boolType(), profile,
                declaration(profile, exactDomain, variable),
                node(24, Opcode.IN, Metatype.BOOLEAN,
                        ExactAlloyType.boolType(), profile, variable, none));
        NormalForm form = new NormalForm();
        form.addEClass(quantified);
        form.normalize();
        check(form.getMatrixQuantiVars().size() == 1
                        && form.getMatrixQuantiVars().get(0).getCardinality()
                                == Cardinality.EXACTLY,
                "exactly-of remains an equality-domain binding, not an empty domain");
        check(form.getMatrixEGraph().getOpcode() != Opcode.CONSTANT,
                "exactly-of does not authorize a nonemptiness fold");

        AlloyTerm syntheticDeclaration = AlloyTerm.node(
                "DECL/ParamDecl/disj=false/var=false",
                AlloyTerm.atom("VAR", "x"),
                AlloyTerm.node("UE/EXACTLY", AlloyTerm.atom("CONST", "none")));
        AlloyTerm guarded = AlloyTerm.node(
                "PREDICATE",
                syntheticDeclaration,
                AlloyTerm.node("BF/IN",
                        AlloyTerm.atom("VAR", "x"),
                        AlloyTerm.atom("CONST", "none")));
        AlloyTerm falsehood = AlloyTerm.node(
                "PREDICATE",
                syntheticDeclaration,
                AlloyTerm.atom("CONST", "false"));
        for (AblationEngine engine : List.of(
                new RawEGraph(),
                new RawDeBruijnEGraph(),
                new JavaEgglog(),
                new JavaEgglogDeBruijn(),
                new SlottedEGraph())) {
            check(!engine.compare(guarded, falsehood).equivalent,
                    engine.getClass().getSimpleName()
                            + " must not treat exactly-of as nonempty evidence");
        }
    }

    private static void checkEmptyUniverseNormalization(
            CompModule module,
            MASGVisitor visitor,
            A4Options options,
            Path directory) throws Exception {
        Command emptyUniverseCommand = module.getAllCommands().get(4);
        SemanticProfile sourceProfile = AlloySemanticProfileFactory.fromExactlyOne(
                module, List.of(emptyUniverseCommand), options);
        Canonical.Prepared normalizedSubset = Canonical.prepare(
                graph(visitor, "univSubset"), sourceProfile);
        CanonicalAlloyPipeline.Prepared subset = CanonicalAlloyPipeline.prepare(
                normalizedSubset);
        CanonicalAlloyPipeline.Prepared truth = CanonicalAlloyPipeline.prepare(
                graph(visitor, "truth"), sourceProfile);
        NormalForm form = normalizedSubset.normalizedForms().get(0);
        check(containsGuardedUniverseEmptiness(form.getMatrixEGraph())
                        && containsGuardedUniverseEmptiness(
                                form.getCertificationMatrixEGraph()),
                "univ has no unconditional nonemptiness witness");
        check(CanonicalAlloyPipeline.distance(subset, truth) > 0,
                "the guarded metric must remain total when an empty universe makes subset true");

        String normalized = CanonicalBacktranslator.formula(
                normalizedSubset.normalizedForms());
        Path normalizedPath = directory.resolve("empty_universe_normalized.als");
        Files.writeString(normalizedPath,
                "module empty_universe_normalized\n"
                        + "sig A {}\n"
                        + "pred target { " + normalized + " }\n"
                        + "run target for 0 but 0 Int\n",
                StandardCharsets.UTF_8);
        CompModule normalizedModule = AlloyUtil.compileAlloyModule(
                normalizedPath.toString());
        A4Solution normalizedInstance = TranslateAlloyToKodkod.execute_command(
                A4Reporter.NOP,
                normalizedModule.getAllReachableSigs(),
                normalizedModule.getAllCommands().get(0),
                options);
        check(normalizedInstance != null && normalizedInstance.satisfiable(),
                "source-authorized bitwidth-0 normalization must preserve empty-universe subset truth");
        Files.deleteIfExists(normalizedPath);
    }

    private static boolean containsMembership(EGraphNode root) {
        return containsMembership(
                root,
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>()));
    }

    private static boolean containsGuardedUniverseEmptiness(EGraphNode root) {
        return containsGuardedUniverseEmptiness(
                root,
                java.util.Collections.newSetFromMap(
                        new java.util.IdentityHashMap<>()));
    }

    private static boolean containsGuardedUniverseEmptiness(
            EGraphNode node,
            java.util.Set<EGraphNode> seen) {
        if (node == null || !seen.add(node)) {
            return false;
        }
        if (node.getOpcode() == Opcode.IN || node.getOpcode() == Opcode.NOT_IN) {
            return true;
        }
        if (node.getOpcode() == Opcode.NO
                && node.getChildren().size() == 1
                && "univ".equals(node.getChildren().get(0).getSourceName())) {
            return true;
        }
        for (EGraphNode child : node.getChildren()) {
            if (containsGuardedUniverseEmptiness(child, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMembership(
            EGraphNode node,
            java.util.Set<EGraphNode> seen) {
        if (node == null || !seen.add(node)) {
            return false;
        }
        if (node.getOpcode() == Opcode.IN || node.getOpcode() == Opcode.NOT_IN) {
            return true;
        }
        for (EGraphNode child : node.getChildren()) {
            if (containsMembership(child, seen)) {
                return true;
            }
        }
        return false;
    }

    private static void checkAblationBindingGuards(ModelUnit model) {
        Map<String, AlloyTerm> terms = new LinkedHashMap<>();
        for (Predicate predicate : model.getPredDeclList()) {
            terms.put(
                    predicate.getName(),
                    AlloyAstTermAdapter.fromPredicate(predicate));
        }
        List<AblationEngine> engines = List.of(
                new RawEGraph(),
                new RawDeBruijnEGraph(),
                new JavaEgglog(),
                new JavaEgglogDeBruijn(),
                new SlottedEGraph());
        for (AblationEngine engine : engines) {
            check(engine.compare(
                            terms.get("bareParamInNone"),
                            terms.get("bareParamFalse")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must consume bare-one binding authority");
            check(engine.compare(
                            terms.get("bareParamNotInNone"),
                            terms.get("bareParamTrue")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must consume bare-one authority for not-in");
            check(engine.compare(
                            terms.get("someParamInNone"),
                            terms.get("someParamFalse")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must consume some binding authority");
            check(engine.compare(
                            terms.get("someParamNotInNone"),
                            terms.get("someParamTrue")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must consume some authority for not-in");
            check(!engine.compare(
                            terms.get("setParamInNone"),
                            terms.get("setParamFalse")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must not treat set as nonempty evidence");
            check(!engine.compare(
                            terms.get("loneParamInNone"),
                            terms.get("loneParamFalse")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must not treat lone as nonempty evidence");
            check(!engine.compare(
                            terms.get("userNoneSome"),
                            terms.get("falsehood")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must keep user signature None distinct from built-in none");
            check(!engine.compare(
                            terms.get("userUnivSome"),
                            terms.get("truth")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must keep user signature Univ distinct from built-in univ");
            check(!engine.compare(
                            terms.get("univSubset"),
                            terms.get("truth")).equivalent
                            && !engine.compare(
                                    terms.get("univSubset"),
                                    terms.get("falsehood")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must not treat univ as unconditional nonemptiness evidence");
            check(engine.compare(
                            terms.get("compoundDifferenceEmpty"),
                            terms.get("falsehood")).equivalent,
                    engine.getClass().getSimpleName()
                            + " must prove a positive binding over A - A impossible");
        }
    }

    private static EGraphNode leaf(
            int id,
            Opcode opcode,
            String name,
            Metatype metatype,
            ExactAlloyType type,
            SemanticProfile profile) {
        EGraphNode result = new EGraphNode(
                id, opcode, new ArrayList<>(), false, 0, false,
                metatype, profile);
        result.setSourceName(name);
        result.setSourceType(type.stableString());
        result.setExactAlloyType(type);
        return result;
    }

    private static void checkUnsafePrenexRegressions(
            MASGVisitor visitor,
            Path directory) throws Exception {
        String universal = normalizedFormula(visitor, "universalBranch");
        String existential = normalizedFormula(visitor, "existentialBranch");
        String lone = normalizedFormula(visitor, "loneBranch");
        Path comparisonPath = directory.resolve("prenex_comparison.als");
        String comparison = "module prenex_comparison\n"
                + "sig A {}\n"
                + "pred universalOriginal { (some none) and (all x: A | no none) }\n"
                + "pred existentialOriginal { (no none) or (some x: A | no none) }\n"
                + "pred loneOriginal { (some none) and (lone x: A | no none) }\n"
                + "pred universalNormalized { " + universal + " }\n"
                + "pred existentialNormalized { " + existential + " }\n"
                + "pred loneNormalized { " + lone + " }\n"
                + "assert UniversalSame { universalOriginal[] iff universalNormalized[] }\n"
                + "assert ExistentialSame { existentialOriginal[] iff existentialNormalized[] }\n"
                + "assert LoneSame { loneOriginal[] iff loneNormalized[] }\n"
                + "check UniversalSame for 0 but 0 Int\n"
                + "check ExistentialSame for 0 but 0 Int\n"
                + "check LoneSame for 3 but 0 Int\n";
        try {
            Files.writeString(comparisonPath, comparison, StandardCharsets.UTF_8);
            CompModule comparisonModule = AlloyUtil.compileAlloyModule(
                    comparisonPath.toString());
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            for (int index = 0; index < comparisonModule.getAllCommands().size(); index++) {
                A4Solution counterexample = TranslateAlloyToKodkod.execute_command(
                        A4Reporter.NOP,
                        comparisonModule.getAllReachableSigs(),
                        comparisonModule.getAllCommands().get(index),
                        options);
                check(counterexample != null && !counterexample.satisfiable(),
                        "unsafe branch quantifier " + index
                                + " must remain source-equivalent after normalization");
            }
        } finally {
            Files.deleteIfExists(comparisonPath);
        }
    }

    private static void checkAdversarialPrenexRegressions(
            MASGVisitor visitor,
            Path directory) throws Exception {
        String setWitness = normalizedFormula(visitor, "setCarrierWitness");
        String loneWitness = normalizedFormula(visitor, "loneCarrierWitness");
        String loneLateEmpty = normalizedFormula(visitor, "loneLateEmpty");
        String notOneLateEmpty = normalizedFormula(visitor, "notOneLateEmpty");
        String relationalIte = normalizedFormula(visitor, "relationalIte");
        String quantifiedRelationalIte = normalizedFormula(
                visitor, "quantifiedRelationalIte");
        String fixedPointLeft = normalizedFormula(visitor, "fixedPointLeft");
        String fixedPointRight = normalizedFormula(visitor, "fixedPointRight");
        String simpleThreeA = normalizedFormula(visitor, "simpleThreeA");
        String simpleThreeB = normalizedFormula(visitor, "simpleThreeB");
        String simpleOrThreeA = normalizedFormula(visitor, "simpleOrThreeA");
        String simpleOrThreeB = normalizedFormula(visitor, "simpleOrThreeB");
        String temporalAciLeft = normalizedFormula(visitor, "temporalAciLeft");
        String temporalAciRight = normalizedFormula(visitor, "temporalAciRight");
        String temporalAciThreeLeft = normalizedFormula(
                visitor, "temporalAciThreeLeft");
        String temporalAciThreeRight = normalizedFormula(
                visitor, "temporalAciThreeRight");
        String unaryNnf = normalizedFormula(visitor, "unaryNnf");
        ParameterizedNormalizedFormula shadowImplication =
                parameterizedNormalizedFormula(visitor, "shadowImplication");
        ParameterizedNormalizedFormula shadowIff =
                parameterizedNormalizedFormula(visitor, "shadowIff");
        ParameterizedNormalizedFormula shadowOuterUse =
                parameterizedNormalizedFormula(visitor, "shadowOuterUse");
        ParameterizedNormalizedFormula shadowSameCarrier =
                parameterizedNormalizedFormula(visitor, "shadowSameCarrier");
        Path comparisonPath = directory.resolve("prenex_adversarial_comparison.als");
        String comparison = "module prenex_adversarial_comparison\n"
                + "sig A {}\n"
                + "sig B {}\n"
                + "sig S { r, s: set S }\n"
                + "pred setWitnessOriginal[x: set A] { "
                + "(some none) and (all y: A | y = y) }\n"
                + "pred loneWitnessOriginal[x: lone A] { "
                + "(some none) and (all y: A | y = y) }\n"
                + "pred setWitnessNormalized { " + setWitness + " }\n"
                + "pred loneWitnessNormalized { " + loneWitness + " }\n"
                + "pred loneLateEmptyOriginal { lone x: A, y: none | no none }\n"
                + "pred loneLateEmptyNormalized { " + loneLateEmpty + " }\n"
                + "pred notOneLateEmptyOriginal { not (one x: A, y: none | no none) }\n"
                + "pred notOneLateEmptyNormalized { " + notOneLateEmpty + " }\n"
                + "pred relationalIteOriginal { (A = A implies A else none) = A }\n"
                + "pred relationalIteNormalized { " + relationalIte + " }\n"
                + "pred quantifiedRelationalIteOriginal { some u:S | "
                + "((all x:S | x=u) implies none else S) = S }\n"
                + "pred quantifiedRelationalIteNormalized { "
                + quantifiedRelationalIte + " }\n"
                + "pred fixedPointOriginal { (all x:S.r | no x.r) and "
                + "(some y:S | some y.r) }\n"
                + "pred simpleThreeOriginal {\n"
                + "  (all x:S | no x.r)\n"
                + "  and (some y:S | some y.r)\n"
                + "  and (all z:S | no z.s)\n"
                + "}\n"
                + "pred simpleOrThreeOriginal {\n"
                + "  (some x:S | some x.r)\n"
                + "  or (all y:S | no y.r)\n"
                + "  or (some z:S | some z.s)\n"
                + "}\n"
                + "pred fixedPointLeftNormalized { " + fixedPointLeft + " }\n"
                + "pred fixedPointRightNormalized { " + fixedPointRight + " }\n"
                + "pred simpleThreeANormalized { " + simpleThreeA + " }\n"
                + "pred simpleThreeBNormalized { " + simpleThreeB + " }\n"
                + "pred simpleOrThreeANormalized { " + simpleOrThreeA + " }\n"
                + "pred simpleOrThreeBNormalized { " + simpleOrThreeB + " }\n"
                + "pred temporalAciLeftNormalized { " + temporalAciLeft + " }\n"
                + "pred temporalAciRightNormalized { " + temporalAciRight + " }\n"
                + "pred temporalAciThreeLeftNormalized { "
                + temporalAciThreeLeft + " }\n"
                + "pred temporalAciThreeRightNormalized { "
                + temporalAciThreeRight + " }\n"
                + "pred unaryNnfOriginal { not (no A) }\n"
                + "pred unaryNnfNormalized { " + unaryNnf + " }\n"
                + "pred shadowImplicationOriginal[x:A] { "
                + "(some x:B | x=x) implies some none }\n"
                + "pred shadowImplicationNormalized["
                + shadowImplication.parameterName + ":A] { "
                + shadowImplication.formula + " }\n"
                + "pred shadowIffOriginal[x:A] { "
                + "(some x:B | x=x) iff some none }\n"
                + "pred shadowIffNormalized[" + shadowIff.parameterName
                + ":A] { " + shadowIff.formula + " }\n"
                + "pred shadowOuterUseOriginal[x:A] { "
                + "(some x:B | x=x) implies x=x }\n"
                + "pred shadowOuterUseNormalized[" + shadowOuterUse.parameterName
                + ":A] { " + shadowOuterUse.formula + " }\n"
                + "pred shadowSameCarrierOriginal[x:A] { "
                + "(some x:A | x=x) implies some none }\n"
                + "pred shadowSameCarrierNormalized[" + shadowSameCarrier.parameterName
                + ":A] { " + shadowSameCarrier.formula + " }\n"
                + "assert SetWitnessSame { setWitnessOriginal[none] iff setWitnessNormalized[] }\n"
                + "assert LoneWitnessSame { loneWitnessOriginal[none] iff loneWitnessNormalized[] }\n"
                + "assert LoneLateEmptySame { loneLateEmptyOriginal[] iff loneLateEmptyNormalized[] }\n"
                + "assert NotOneLateEmptySame { notOneLateEmptyOriginal[] iff notOneLateEmptyNormalized[] }\n"
                + "assert RelationalIteSame { relationalIteOriginal[] iff relationalIteNormalized[] }\n"
                + "assert QuantifiedRelationalIteSame { "
                + "quantifiedRelationalIteOriginal[] iff "
                + "quantifiedRelationalIteNormalized[] }\n"
                + "assert FixedPointLeftSame { fixedPointOriginal[] iff "
                + "fixedPointLeftNormalized[] }\n"
                + "assert FixedPointRightSame { fixedPointOriginal[] iff "
                + "fixedPointRightNormalized[] }\n"
                + "assert FixedPointOrderSame { fixedPointLeftNormalized[] iff "
                + "fixedPointRightNormalized[] }\n"
                + "assert SimpleThreeSame { simpleThreeANormalized[] iff "
                + "simpleThreeBNormalized[] }\n"
                + "assert SimpleOrThreeSame { simpleOrThreeANormalized[] iff "
                + "simpleOrThreeBNormalized[] }\n"
                + "assert SimpleThreeASourceSame { simpleThreeOriginal[] iff "
                + "simpleThreeANormalized[] }\n"
                + "assert SimpleThreeBSourceSame { simpleThreeOriginal[] iff "
                + "simpleThreeBNormalized[] }\n"
                + "assert SimpleOrThreeASourceSame { simpleOrThreeOriginal[] iff "
                + "simpleOrThreeANormalized[] }\n"
                + "assert SimpleOrThreeBSourceSame { simpleOrThreeOriginal[] iff "
                + "simpleOrThreeBNormalized[] }\n"
                + "assert UnaryNnfSame { unaryNnfOriginal[] iff unaryNnfNormalized[] }\n"
                + "assert ShadowImplicationSame { all x:A | "
                + "shadowImplicationOriginal[x] iff shadowImplicationNormalized[x] }\n"
                + "assert ShadowIffSame { all x:A | "
                + "shadowIffOriginal[x] iff shadowIffNormalized[x] }\n"
                + "assert ShadowOuterUseSame { all x:A | "
                + "shadowOuterUseOriginal[x] iff shadowOuterUseNormalized[x] }\n"
                + "assert ShadowSameCarrierSame { all x:A | "
                + "shadowSameCarrierOriginal[x] iff shadowSameCarrierNormalized[x] }\n"
                + "check SetWitnessSame for 2 but 0 Int\n"
                + "check LoneWitnessSame for 2 but 0 Int\n"
                + "check LoneLateEmptySame for 2 but 0 Int\n"
                + "check NotOneLateEmptySame for 2 but 0 Int\n"
                + "check RelationalIteSame for 2 but 0 Int\n"
                + "check QuantifiedRelationalIteSame for 3 but 0 Int\n"
                + "check FixedPointLeftSame for 3 but 0 Int\n"
                + "check FixedPointRightSame for 3 but 0 Int\n"
                + "check FixedPointOrderSame for 3 but 0 Int\n"
                + "check SimpleThreeSame for 3 but 0 Int\n"
                + "check SimpleOrThreeSame for 3 but 0 Int\n"
                + "check SimpleThreeASourceSame for 3 but 0 Int\n"
                + "check SimpleThreeBSourceSame for 3 but 0 Int\n"
                + "check SimpleOrThreeASourceSame for 3 but 0 Int\n"
                + "check SimpleOrThreeBSourceSame for 3 but 0 Int\n"
                + "check UnaryNnfSame for 2 but 0 Int\n"
                + "check ShadowImplicationSame for 3 but 0 Int\n"
                + "check ShadowIffSame for 3 but 0 Int\n"
                + "check ShadowOuterUseSame for 3 but 0 Int\n"
                + "check ShadowSameCarrierSame for 3 but 0 Int\n";
        assertAllCommandsUnsatisfiable(comparisonPath, comparison,
                "adversarial prenex/NNF regression");
        checkTemporalAciSemanticRegression(
                directory,
                temporalAciLeft,
                temporalAciRight,
                temporalAciThreeLeft,
                temporalAciThreeRight);
        check(prepare(visitor, "fixedPointLeft").equivalentTo(
                        prepare(visitor, "fixedPointRight")),
                "conjunction order must not change the certified canonical observation");
        CanonicalAlloyPipeline.Prepared simpleThreeLeft = prepare(
                visitor, "simpleThreeA");
        CanonicalAlloyPipeline.Prepared simpleThreeRight = prepare(
                visitor, "simpleThreeB");
        check(simpleThreeLeft.equivalentTo(simpleThreeRight),
                "maximal AND slot allocation must ignore binary association and order");
        check(CanonicalAlloyPipeline.distance(simpleThreeLeft, simpleThreeRight) == 0,
                "equivalent maximal AND regions must have zero repair distance");
        CanonicalAlloyPipeline.Prepared simpleOrThreeLeft = prepare(
                visitor, "simpleOrThreeA");
        CanonicalAlloyPipeline.Prepared simpleOrThreeRight = prepare(
                visitor, "simpleOrThreeB");
        check(simpleOrThreeLeft.equivalentTo(simpleOrThreeRight),
                "maximal OR slot allocation must ignore binary association and order");
        check(CanonicalAlloyPipeline.distance(
                        simpleOrThreeLeft, simpleOrThreeRight) == 0,
                "equivalent maximal OR regions must have zero repair distance");
        CanonicalAlloyPipeline.Prepared temporalLeft = prepare(
                visitor, "temporalAciLeft");
        CanonicalAlloyPipeline.Prepared temporalRight = prepare(
                visitor, "temporalAciRight");
        check(temporalLeft.equivalentTo(temporalRight),
                "temporal siblings under AND must share one certified ACI observation");
        check(CanonicalAlloyPipeline.distance(temporalLeft, temporalRight) == 0,
                "temporal siblings under AND must use one coherent phase assignment");
        CanonicalAlloyPipeline.Prepared temporalThreeLeft = prepare(
                visitor, "temporalAciThreeLeft");
        CanonicalAlloyPipeline.Prepared temporalThreeRight = prepare(
                visitor, "temporalAciThreeRight");
        check(temporalThreeLeft.equivalentTo(temporalThreeRight),
                "three temporal siblings must share one certified ACI observation");
        check(CanonicalAlloyPipeline.distance(
                        temporalThreeLeft, temporalThreeRight) == 0,
                "three temporal siblings must use one coherent phase assignment");
        check(prepare(visitor, "shadowImplication") != null
                        && prepare(visitor, "shadowIff") != null
                        && prepare(visitor, "shadowOuterUse") != null
                        && prepare(visitor, "shadowSameCarrier") != null,
                "scope-qualified projection must retain shadowed implication/IFF bindings");
    }

    private static void checkTemporalAciSemanticRegression(
            Path directory,
            String temporalAciLeft,
            String temporalAciRight,
            String temporalAciThreeLeft,
            String temporalAciThreeRight) throws Exception {
        Path comparisonPath = directory.resolve("temporal_aci_comparison.als");
        String comparison = "module temporal_aci_comparison\n"
                + "var sig S { var r, s: set S }\n"
                + "pred left { " + temporalAciLeft + " }\n"
                + "pred right { " + temporalAciRight + " }\n"
                + "pred threeLeft { " + temporalAciThreeLeft + " }\n"
                + "pred threeRight { " + temporalAciThreeRight + " }\n"
                + "assert PairSame { left[] iff right[] }\n"
                + "assert TripleSame { threeLeft[] iff threeRight[] }\n"
                + "check PairSame for 3 but 0 Int, 1..4 steps\n"
                + "check TripleSame for 3 but 0 Int, 1..4 steps\n";
        assertAllCommandsUnsatisfiable(
                comparisonPath, comparison, "temporal ACI phase alignment");
    }

    private static void checkNestedLocalAlphaRegression(Path directory) throws Exception {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        ExactAlloyType unaryA = ExactAlloyType.unaryRelation("A");
        EGraphNode aDeclaration = declaration(
                profile,
                global(201, "A", unaryA, profile),
                variable(202, "a", unaryA, profile));
        EGraphNode zDeclaration = declaration(
                profile,
                global(203, "A", unaryA, profile),
                variable(204, "z", unaryA, profile));
        EGraphNode comprehension = node(
                205, Opcode.COMPREHENSION, Metatype.SET, unaryA, profile,
                zDeclaration,
                node(206, Opcode.NOT_EQUALS, Metatype.BOOLEAN,
                        ExactAlloyType.boolType(), profile,
                        variable(207, "z", unaryA, profile),
                        variable(208, "a", unaryA, profile)));
        EGraphNode bDeclaration = declaration(
                profile,
                comprehension,
                variable(209, "b", unaryA, profile));
        EGraphNode quantified = node(
                210, Opcode.FORALL, Metatype.BOOLEAN,
                ExactAlloyType.boolType(), profile,
                aDeclaration,
                bDeclaration,
                node(211, Opcode.EQUALS, Metatype.BOOLEAN,
                        ExactAlloyType.boolType(), profile,
                        variable(212, "b", unaryA, profile),
                        variable(213, "a", unaryA, profile)));
        NormalForm form = new NormalForm();
        form.addEClass(node(
                214, Opcode.AND, Metatype.BOOLEAN,
                ExactAlloyType.boolType(), profile,
                node(215, Opcode.SOME, Metatype.BOOLEAN,
                        ExactAlloyType.boolType(), profile,
                        global(216, "A", unaryA, profile)),
                quantified));
        form.normalize();

        java.util.Set<String> aNames = alphaNames(form.getMatrixEGraph(), "a");
        java.util.Set<String> zNames = alphaNames(form.getMatrixEGraph(), "z");
        check(!aNames.isEmpty() && !zNames.isEmpty()
                        && java.util.Collections.disjoint(aNames, zNames),
                "nested declaration-domain binders must not capture outer alpha identities");

        String normalized = CanonicalBacktranslator.formula(form);
        Path comparisonPath = directory.resolve("nested_local_alpha_comparison.als");
        String comparison = "module nested_local_alpha_comparison\n"
                + "sig A {}\n"
                + "pred original { some A and "
                + "(all a: A, b: {z: A | z != a} | b = a) }\n"
                + "pred normalized { " + normalized + " }\n"
                + "assert Same { original[] iff normalized[] }\n"
                + "check Same for 2 but 0 Int\n";
        assertAllCommandsUnsatisfiable(comparisonPath, comparison,
                "nested local-alpha regression");
    }

    private static void assertAllCommandsUnsatisfiable(
            Path path,
            String source,
            String label) throws Exception {
        try {
            Files.writeString(path, source, StandardCharsets.UTF_8);
            CompModule module = AlloyUtil.compileAlloyModule(path.toString());
            if (module == null) {
                throw new IllegalStateException(
                        label + " generated invalid Alloy at " + path + ":\n" + source);
            }
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            for (int index = 0; index < module.getAllCommands().size(); index++) {
                A4Solution counterexample = TranslateAlloyToKodkod.execute_command(
                        A4Reporter.NOP,
                        module.getAllReachableSigs(),
                        module.getAllCommands().get(index),
                        options);
                check(counterexample != null && !counterexample.satisfiable(),
                        label + " command " + index + " must have no counterexample");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static EGraphNode declaration(
            SemanticProfile profile,
            EGraphNode domain,
            EGraphNode... variables) {
        List<EGraphNode> children = new ArrayList<>();
        children.add(domain);
        children.addAll(List.of(variables));
        return node(
                300 + children.hashCode(), Opcode.GENERICRELDECL,
                Metatype.CONTROL, domain.getExactAlloyType(), profile,
                children.toArray(new EGraphNode[0]));
    }

    private static EGraphNode global(
            int id,
            String name,
            ExactAlloyType type,
            SemanticProfile profile) {
        return leaf(id, Opcode.GLOBALBINDING, name, Metatype.SET, type, profile);
    }

    private static EGraphNode variable(
            int id,
            String name,
            ExactAlloyType type,
            SemanticProfile profile) {
        return leaf(id, Opcode.VARIABLE, name, Metatype.ATOMIC, type, profile);
    }

    private static java.util.Set<String> alphaNames(EGraphNode node, String sourceName) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        collectAlphaNames(node, sourceName, names);
        return names;
    }

    private static void collectAlphaNames(
            EGraphNode node,
            String sourceName,
            java.util.Set<String> names) {
        if (node == null) {
            return;
        }
        if (sourceName.equals(node.getSourceName()) && node.getAlphaName() != null) {
            names.add(node.getAlphaName());
        }
        for (EGraphNode child : node.getChildren()) {
            collectAlphaNames(child, sourceName, names);
        }
    }

    private static String normalizedFormula(
            MASGVisitor visitor,
            String predicate) {
        return CanonicalBacktranslator.formula(
                CanonicalBacktranslator.normalForms(graph(visitor, predicate)));
    }

    private static ParameterizedNormalizedFormula parameterizedNormalizedFormula(
            MASGVisitor visitor,
            String predicate) {
        Canonical.Prepared prepared = Canonical.prepare(graph(visitor, predicate));
        List<NormalForm> forms = prepared.normalizedForms();
        check(!forms.isEmpty(), predicate + " must retain a normalized phase");
        check(forms.get(0).getParams().size() == 1,
                predicate + " must retain exactly one predicate parameter");
        String parameterName = forms.get(0).getParams().get(0).getName();
        for (NormalForm form : forms) {
            check(form.getParams().size() == 1
                            && parameterName.equals(form.getParams().get(0).getName()),
                    predicate + " must retain one stable parameter identity in every phase");
        }
        return new ParameterizedNormalizedFormula(
                parameterName, CanonicalBacktranslator.formula(forms));
    }

    private static final class ParameterizedNormalizedFormula {
        private final String parameterName;
        private final String formula;

        private ParameterizedNormalizedFormula(String parameterName, String formula) {
            this.parameterName = parameterName;
            this.formula = formula;
        }
    }

    private static EGraphNode node(
            int id,
            Opcode opcode,
            Metatype metatype,
            ExactAlloyType type,
            SemanticProfile profile,
            EGraphNode... children) {
        EGraphNode result = new EGraphNode(
                id, opcode, new ArrayList<>(List.of(children)), false,
                children.length, false, metatype, profile);
        result.setSourceType(type.stableString());
        result.setExactAlloyType(type);
        return result;
    }

    private static CanonicalAlloyPipeline.Prepared prepare(
            MASGVisitor visitor,
            String predicate) {
        return CanonicalAlloyPipeline.prepare(graph(visitor, predicate));
    }

    private static NormalForm singleBinding(
            MASGVisitor visitor,
            String predicate) {
        Canonical.Prepared prepared = Canonical.prepare(graph(visitor, predicate));
        check(prepared.normalizedForms().size() == 1,
                predicate + " must have one non-temporal normal form");
        NormalForm form = prepared.normalizedForms().get(0);
        check(form.getMatrixQuantiVars().size() == 1,
                predicate + " must retain one quantified binding");
        return form;
    }

    private static Multigraph graph(MASGVisitor visitor, String predicate) {
        Integer id = visitor.getForestId(predicate);
        check(id != null, "missing predicate " + predicate);
        Multigraph graph = visitor.getForest().get(id);
        check(graph != null, "missing graph " + predicate);
        return graph;
    }

    private static String source() {
        return "module source_rules\n"
                + "sig A {}\n"
                + "sig B {}\n"
                + "sig S { r, s: set S }\n"
                + "sig None {}\n"
                + "sig Univ {}\n"
                + "pred truth { no none }\n"
                + "pred falsehood { some none }\n"
                + "pred userNoneSome { some None }\n"
                + "pred userUnivSome { some Univ }\n"
                + "pred userNamesSubset { Univ in None }\n"
                + "pred setSome { some x: set none | x = none }\n"
                + "pred setAllFalse { all x: set none | some none }\n"
                + "pred loneSome { some x: lone none | x = none }\n"
                + "pred loneAllFalse { all x: lone none | some none }\n"
                + "pred letSetSome { some x: set (let y = none | y) | x = none }\n"
                + "pred unionOnly { some (A + B) }\n"
                + "pred oneSome { some x: one none | no none }\n"
                + "pred someSome { some x: some none | no none }\n"
                + "pred bareSome { some x: none | no none }\n"
                + "pred bareAll { all x: none | some none }\n"
                + "pred notSetSome { not setSome }\n"
                + "pred notLoneSome { not loneSome }\n"
                + "pred notBareAll { not bareAll }\n"
                + "pred noneSubset { none in none }\n"
                + "pred univSubset { univ in none }\n"
                + "pred notNoneSubset { not (none in none) }\n"
                + "pred notUnivSubset { not (univ in none) }\n"
                + "pred notNoneUniv { not (none in univ) }\n"
                + "pred notLoneNone { not (lone none) }\n"
                + "pred notOneNone { not (one none) }\n"
                + "pred bareParamInNone[x: A] { x in none }\n"
                + "pred bareParamNotInNone[x: A] { x not in none }\n"
                + "pred bareParamFalse[x: A] { some none }\n"
                + "pred bareParamTrue[x: A] { no none }\n"
                + "pred oneParamInNone[x: one A] { x in none }\n"
                + "pred someParamInNone[x: some A] { x in none }\n"
                + "pred someParamNotInNone[x: some A] { x not in none }\n"
                + "pred someParamFalse[x: some A] { some none }\n"
                + "pred someParamTrue[x: some A] { no none }\n"
                + "pred setParamInNone[x: set A] { x in none }\n"
                + "pred setParamFalse[x: set A] { some none }\n"
                + "pred loneParamInNone[x: lone A] { x in none }\n"
                + "pred loneParamFalse[x: lone A] { some none }\n"
                + "pred compoundEmpty { some x: one (none & A) | no none }\n"
                + "pred compoundDifferenceEmpty { some x: one (A - A) | no none }\n"
                + "pred leftEmptyDifference { some x: one (none - A) | no none }\n"
                + "pred universalBranch { (some none) and (all x: A | no none) }\n"
                + "pred existentialBranch { (no none) or (some x: A | no none) }\n"
                + "pred loneBranch { (some none) and (lone x: A | no none) }\n"
                + "pred setCarrierWitness[x: set A] { "
                + "(some none) and (all y: A | y = y) }\n"
                + "pred loneCarrierWitness[x: lone A] { "
                + "(some none) and (all y: A | y = y) }\n"
                + "pred loneLateEmpty { lone x: A, y: none | no none }\n"
                + "pred notOneLateEmpty { not (one x: A, y: none | no none) }\n"
                + "pred relationalIte { (A = A implies A else none) = A }\n"
                + "pred quantifiedRelationalIte { some u:S | "
                + "((all x:S | x=u) implies none else S) = S }\n"
                + "pred fixedPointLeft { (all x:S.r | no x.r) and "
                + "(some y:S | some y.r) }\n"
                + "pred fixedPointRight { (some y:S | some y.r) and "
                + "(all x:S.r | no x.r) }\n"
                + "pred simpleThreeA {\n"
                + "  (all x:S | no x.r)\n"
                + "  and (some y:S | some y.r)\n"
                + "  and (all z:S | no z.s)\n"
                + "}\n"
                + "pred simpleThreeB {\n"
                + "  (all z:S | no z.s)\n"
                + "  and (all x:S | no x.r)\n"
                + "  and (some y:S | some y.r)\n"
                + "}\n"
                + "pred simpleOrThreeA {\n"
                + "  (some x:S | some x.r)\n"
                + "  or (all y:S | no y.r)\n"
                + "  or (some z:S | some z.s)\n"
                + "}\n"
                + "pred simpleOrThreeB {\n"
                + "  (some z:S | some z.s)\n"
                + "  or (some x:S | some x.r)\n"
                + "  or (all y:S | no y.r)\n"
                + "}\n"
                + "pred temporalAciLeft {\n"
                + "  (always (all x:S | no x.r))\n"
                + "  and (eventually (some y:S | some y.r))\n"
                + "}\n"
                + "pred temporalAciRight {\n"
                + "  (eventually (some y:S | some y.r))\n"
                + "  and (always (all x:S | no x.r))\n"
                + "}\n"
                + "pred temporalAciThreeLeft {\n"
                + "  (always (all x:S | no x.r))\n"
                + "  and (eventually (some y:S | some y.r))\n"
                + "  and (historically (all z:S | no z.s))\n"
                + "}\n"
                + "pred temporalAciThreeRight {\n"
                + "  (historically (all z:S | no z.s))\n"
                + "  and (always (all x:S | no x.r))\n"
                + "  and (eventually (some y:S | some y.r))\n"
                + "}\n"
                + "pred unaryNnf { not (no A) }\n"
                + "pred shadowImplication[x:A] { "
                + "(some x:B | x=x) implies some none }\n"
                + "pred shadowIff[x:A] { "
                + "(some x:B | x=x) iff some none }\n"
                + "pred shadowOuterUse[x:A] { "
                + "(some x:B | x=x) implies x=x }\n"
                + "pred shadowSameCarrier[x:A] { "
                + "(some x:A | x=x) implies some none }\n"
                + "run { not (none in none) } for 3\n"
                + "run compoundEmpty for 3\n"
                + "run compoundDifferenceEmpty for 3\n"
                + "run leftEmptyDifference for 3\n"
                + "run { univ in none } for 0 but 0 Int\n"
                + "run { not (univ in none) } for 0 but 0 Int\n"
                + "run setSome for 3\n"
                + "run notSetSome for 3\n"
                + "run setAllFalse for 3\n"
                + "run loneSome for 3\n"
                + "run notLoneSome for 3\n"
                + "run loneAllFalse for 3\n"
                + "run bareSome for 3\n"
                + "run notBareAll for 3\n"
                + "run oneSome for 3\n"
                + "run someSome for 3\n"
                + "run notNoneSubset for 3\n"
                + "run compoundEmpty for 3\n";
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

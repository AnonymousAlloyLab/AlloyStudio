package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.translator.A4Options;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.theory.AlloySemanticProfileFactory;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import is.fivefivefive.CanDis.theory.StructuralKey;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

/** Bounded parser-owned command/options profile extraction tests. */
public final class SemanticProfileSourceCommandTest {
    private static int checks;

    private SemanticProfileSourceCommandTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("candis-semantic-profile-");
        try {
            CompModule omitted = module(directory, "omitted", "run {}\n");
            CompModule width4 = module(
                    directory, "width4", "run {} for 3 but 4 Int\n");
            CompModule width5 = module(
                    directory, "width5", "run {} for 3 but 5 Int\n");
            CompModule width0 = module(
                    directory, "width0", "run {} for 3 but 0 Int\n");
            CompModule width31 = module(
                    directory, "width31", "run {} for 3 but 31 Int\n");
            CompModule temporal23 = module(
                    directory, "temporal23", "run {} for 3 but 2..3 steps\n");
            CompModule temporal25 = module(
                    directory, "temporal25", "run {} for 3 but 2..5 steps\n");
            CompModule scope1 = module(
                    directory,
                    "scope1",
                    "sig Scoped {}\nrun {} for 3 but exactly 1 Scoped\n");
            CompModule scope2 = module(
                    directory,
                    "scope2",
                    "sig Scoped {}\nrun {} for 3 but exactly 2 Scoped\n");
            CompModule formulaA = module(
                    directory,
                    "formulaA",
                    "sig Scoped {}\npred target { no Scoped }\nrun target\n");
            CompModule formulaB = module(
                    directory,
                    "formulaB",
                    "sig Scoped {}\npred target { some Scoped }\nrun target\n");
            CompModule followUp = module(
                    directory,
                    "followUp",
                    "run { some none } for 1 => run { no none } for 1\n");

            A4Options modular = options(false, 0);
            A4Options forbid = options(true, 0);
            SemanticProfile source4 = profile(width4, modular);
            SemanticProfile source4Again = profile(width4, modular);

            check(source4.equals(source4Again),
                    "byte-identical parser-owned contexts must be deterministic");
            check(source4.fingerprint().equals(source4Again.fingerprint()),
                    "deterministic contexts must have one profile fingerprint");
            check(source4.isSourceCommandBound() && source4.isAuthorizedAlloyProfile(),
                    "a parser-owned command context must be production-authorized");
            check(source4.bitwidth() == 4
                            && source4.overflowMode()
                                    == SemanticProfile.OverflowMode.MODULAR,
                    "bitwidth and overflow mode must come from command/options");
            check(profile(omitted, modular).bitwidth() == 4,
                    "only Alloy's omitted-width marker defaults to width 4");
            check(source4.rewriteMode().equals(
                            SemanticProfile.PRODUCTION_REWRITE_MODE)
                            && source4.signatureVersion().equals(
                                    SemanticProfile.PRODUCTION_SIGNATURE_VERSION),
                    "profiles must bind current semantic implementation versions");

            check(!source4.equals(profile(width5, modular)),
                    "different command bitwidths must not share a profile");
            check(!source4.equals(profile(width4, forbid)),
                    "different overflow options must not share a profile");
            check(!profile(temporal23, modular).equals(profile(temporal25, modular)),
                    "different temporal bounds must not share a profile");
            check(!profile(scope1, modular).equals(profile(scope2, modular)),
                    "different exact command scopes must not share a profile");
            check(!source4.equals(profile(width4, options(false, 1))),
                    "different execution unroll bounds must not share a profile");
            check(!profile(formulaA, modular).equals(profile(formulaB, modular)),
                    "different selected command formulae must not share a profile");

            SemanticProfile formulaAProfile = profile(formulaA, modular);
            MASGVisitor profileVisitor = new MASGVisitor(
                    new GlobalVariables(), formulaA);
            profileVisitor.visit(new ModelUnit(null, formulaA), null);
            Integer targetId = profileVisitor.getForestId("target");
            Multigraph target = targetId == null
                    ? null : profileVisitor.getForest().get(targetId);
            check(target != null, "source-command profile fixture must retain target");
            CanonicalAlloyPipeline.Prepared prepared =
                    CanonicalAlloyPipeline.prepare(target, formulaAProfile);
            check(prepared.semanticProfile().equals(formulaAProfile)
                            && prepared.semanticArtifact().semanticProfile()
                                    .equals(formulaAProfile),
                    "parser-owned profile must survive normalization and certification");
            expectFailure(() -> CanonicalAlloyPipeline.distance(
                            prepared,
                            CanonicalAlloyPipeline.prepare(
                                    target, profile(formulaB, modular))),
                    "cross-command canonical comparison must reject");

            Set<String> publicOptions = new HashSet<>();
            for (java.lang.reflect.Field field : A4Options.class.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    publicOptions.add(field.getName());
                }
            }
            Set<String> classifiedOptions = new HashSet<>(
                    AlloySemanticProfileFactory.boundOptionFields());
            Set<String> overlap = new HashSet<>(classifiedOptions);
            overlap.retainAll(AlloySemanticProfileFactory.nonsemanticOptionFields());
            classifiedOptions.addAll(
                    AlloySemanticProfileFactory.nonsemanticOptionFields());
            check(overlap.isEmpty(),
                    "bound and intentionally nonsemantic A4Options fields must be disjoint");
            check(classifiedOptions.equals(publicOptions),
                    "every public A4Options field must have an explicit disposition");

            check(profile(width0, modular).bitwidth() == 0
                            && !profile(width0, modular).equals(source4),
                    "explicit Alloy bitwidth zero must be preserved and not alias width 4");
            expectFailure(() -> profile(width31, modular),
                    "bitwidth above Alloy's supported range must reject");
            expectFailure(() -> AlloySemanticProfileFactory.fromExactlyOne(
                            width4, Collections.emptyList(), modular),
                    "missing source-command selection must reject");
            expectFailure(() -> AlloySemanticProfileFactory.fromExactlyOne(
                            width4,
                            List.of(only(width4), only(width4)),
                            modular),
                    "ambiguous source-command selection must reject");
            expectFailure(() -> AlloySemanticProfileFactory.fromExactlyOne(
                            width4, List.of(only(width5)), modular),
                    "a command owned by another parsed module must reject");
            expectFailure(() -> AlloySemanticProfileFactory.fromExactlyOne(
                            width4, List.of(only(width4)), optionsWithoutSolver()),
                    "an invalid execution option state must reject");
            check(only(followUp).parent != null,
                    "follow-up syntax must retain its executable parent command");
            expectFailure(() -> profile(followUp, modular),
                    "a profile must not erase follow-up command execution order");
            expectFailure(() -> AlloySemanticProfileFactory.fromExactlyOne(
                            null, List.of(only(width4)), modular),
                    "null parsed module must reject");
            expectFailure(() -> AlloySemanticProfileFactory.fromExactlyOne(
                            width4, null, modular),
                    "null source-command selection must reject");

            SemanticProfile callerAssertion = new SemanticProfile(
                    source4.bitwidth(),
                    source4.overflowMode(),
                    source4.temporalMode(),
                    source4.rewriteMode(),
                    source4.signatureVersion());
            check(callerAssertion.equals(source4)
                            && !callerAssertion.isSourceCommandBound()
                            && !callerAssertion.isAuthorizedAlloyProfile(),
                    "matching profile text cannot mint parser-derived authority");
            check(!source4.equals(SemanticProfile.alloyModular()),
                    "a fixed compatibility profile cannot impersonate source binding");
            check(!SemanticProfile.alloyModular().isAuthorizedAlloyProfile()
                            && SemanticProfile.alloyModular()
                                    .isAdmissibleAlloyProfile(),
                    "fixed compatibility is internal/test-only, not production authority");
            SemanticProfile.alloyModular().requireCertificateExportAuthority(true);
            source4.requireCertificateExportAuthority(false);
            expectFailure(
                    () -> SemanticProfile.alloyModular()
                            .requireCertificateExportAuthority(false),
                    "fixed compatibility must not authorize publication export");

            StructuralKey wrongTag = StructuralKey.leaf("caller-asserted", "context");
            check(!new SemanticProfile(
                            4,
                            SemanticProfile.OverflowMode.MODULAR,
                            wrongTag.stableString(),
                            SemanticProfile.PRODUCTION_REWRITE_MODE,
                            SemanticProfile.PRODUCTION_SIGNATURE_VERSION)
                            .isAuthorizedAlloyProfile(),
                    "caller-controlled structural keys cannot authorize profiles");

            System.out.println("SemanticProfileSourceCommandTest passed: "
                    + checks + " checks");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static CompModule module(
            Path directory,
            String name,
            String source) throws Exception {
        Path path = directory.resolve(name + ".als");
        Files.writeString(path, source, StandardCharsets.UTF_8);
        CompModule module = AlloyUtil.compileAlloyModule(path.toString());
        check(module != null && module.getAllCommands().size() == 1,
                name + " must parse with exactly one command");
        return module;
    }

    private static Command only(CompModule module) {
        return module.getAllCommands().get(0);
    }

    private static SemanticProfile profile(
            CompModule module,
            A4Options options) {
        return AlloySemanticProfileFactory.fromExactlyOne(
                module, List.of(only(module)), options);
    }

    private static A4Options options(boolean noOverflow, int unrolls) {
        A4Options options = new A4Options();
        options.noOverflow = noOverflow;
        options.unrolls = unrolls;
        options.solver = A4Options.SatSolver.SAT4J;
        return options;
    }

    private static A4Options optionsWithoutSolver() {
        A4Options options = new A4Options();
        options.solver = null;
        return options;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectFailure(Runnable action, String message) {
        checks++;
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException
                | NullPointerException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}

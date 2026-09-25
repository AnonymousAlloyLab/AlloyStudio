package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;
import parser.ast.nodes.QtFormula;
import parser.util.AlloyUtil;

/** Regression for declarations whose domain contains another local declaration. */
public final class DependentDeclarationExtractionRegressionTest {
    private static final String SOURCE_FORMULA =
            "some S and (all a: S, b: {z: S | z != a} | b = a)";
    private static int checks;

    private DependentDeclarationExtractionRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("candis-dependent-decl-");
        Path sourcePath = directory.resolve("dependent_declaration.als");
        Path comparisonPath = directory.resolve("dependent_declaration_equivalence.als");
        try {
            Files.writeString(sourcePath, sourceModule(), StandardCharsets.UTF_8);
            CompModule module = AlloyUtil.compileAlloyModule(sourcePath.toString());
            check(module != null, "dependent-declaration fixture must parse");

            ModelUnit model = new ModelUnit(null, module);
            Predicate predicate = predicate(model, "sourcePredicate");
            QtFormula sourceQuantifier = outerUniversal(predicate);
            check(sourceQuantifier != null,
                    "the parser must retain the source universal quantifier");
            check(sourceQuantifier.getVarDecls().size() == 2,
                    "the parser must retain both source declaration blocks");

            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(model, null);
            Multigraph graph = graph(visitor, "sourcePredicate");
            checkMasgDeclarationOccurrences(graph);

            RawIrEvidence[] raw = new RawIrEvidence[1];
            IRAgent agent = new IRAgent(graph);
            agent.computeNormalForm((stage, active, normalForms) -> {
                if ("temporal-skeleton".equals(stage)) {
                    check(raw[0] == null,
                            "one non-temporal predicate must expose one raw IR skeleton");
                    raw[0] = inspectRawIr(active.getMatrixEGraph());
                }
            });
            check(raw[0] != null, "IR extraction must expose its pre-normalization skeleton");
            check(raw[0].outerDeclarations == 2,
                    "raw IR must retain distinct declarations for a and b");
            check(raw[0].dependentDomainIsComprehension,
                    "b must retain its comprehension domain");
            check(raw[0].innerBinderSurvives,
                    "the comprehension binder z must survive in raw IR");
            check(raw[0].dependentReferenceSurvives,
                    "z != a must retain two distinctly named variable references");

            String normalized = CanonicalBacktranslator.formula(agent.normalForms());
            check(normalized.contains("_l") || normalized.contains("_q"),
                    "normalization must emit explicit alpha-renamed bindings");
            Files.writeString(
                    comparisonPath,
                    comparisonModule(normalized),
                    StandardCharsets.UTF_8);
            CompModule comparison = AlloyUtil.compileAlloyModule(
                    comparisonPath.toString());
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            A4Solution counterexample = TranslateAlloyToKodkod.execute_command(
                    A4Reporter.NOP,
                    comparison.getAllReachableSigs(),
                    comparison.getAllCommands().get(0),
                    options);
            check(counterexample != null && !counterexample.satisfiable(),
                    "normalized backtranslation must equal the source through scope 3");

            System.out.println("DependentDeclarationExtractionRegressionTest passed: "
                    + checks + " checks");
        } finally {
            Files.deleteIfExists(comparisonPath);
            Files.deleteIfExists(sourcePath);
            Files.deleteIfExists(directory);
        }
    }

    private static void checkMasgDeclarationOccurrences(Multigraph graph) {
        AugmentedNode declaration = null;
        for (AugmentedNode node : graph.getVertices()) {
            if (node.getSyntactic() == -127 && (int) node.getSemantic() == 0) {
                check(declaration == null,
                        "one interned generic declaration node is expected in this fixture");
                declaration = node;
            }
        }
        check(declaration != null, "MASG must contain a generic declaration node");
        check(graph.getTimeOfVisitMap().getOrDefault(declaration, 0) == 3,
                "a, b, and z must occupy three declaration occurrences");
        check(declarationNames(declaration, graph, 1).equals(Set.of("a")),
                "declaration occurrence 1 must bind only a");
        check(declarationNames(declaration, graph, 2).equals(Set.of("b")),
                "declaration occurrence 2 must bind only b");
        check(declarationNames(declaration, graph, 3).equals(Set.of("z")),
                "declaration occurrence 3 must bind only z");

        List<MASGEdge> bEdges = declaration.getDownlinksAtTimeOfVisit(graph, 2);
        check(bEdges != null && bEdges.size() == 3,
                "b's occurrence must contain domain, binder, and terminator");
        MASGEdge domain = edgeAt(bEdges, 1);
        check(domain != null
                        && domain.getTarget().getSymbol() != null
                        && !domain.getTarget().getSymbol().isEndSymbol()
                        && domain.getTarget().getSyntactic() != 127,
                "b's position-1 edge must retain a non-leaf dependent domain");

        AugmentedNode aBinder = edgeAt(
                declaration.getDownlinksAtTimeOfVisit(graph, 1), 2).getTarget();
        AugmentedNode zBinder = edgeAt(
                declaration.getDownlinksAtTimeOfVisit(graph, 3), 2).getTarget();
        AugmentedNode dependentComparison = findNode(graph, -5, 2);
        check(dependentComparison != null,
                "MASG must retain the dependent-domain not-equals node");
        List<MASGEdge> comparisonEdges =
                dependentComparison.getDownlinksAtTimeOfVisit(graph, 1);
        check(comparisonEdges != null && comparisonEdges.size() == 2,
                "dependent-domain comparison must retain both operands");
        check(edgeAt(comparisonEdges, 1).getTarget() == zBinder,
                "the left dependent-domain reference must resolve to binder z");
        check(edgeAt(comparisonEdges, 2).getTarget() == aBinder,
                "the right dependent-domain reference must resolve to outer binder a");
    }

    private static AugmentedNode findNode(
            Multigraph graph,
            int syntactic,
            int semantic) {
        AugmentedNode result = null;
        for (AugmentedNode node : graph.getVertices()) {
            if (node.getSyntactic() == syntactic
                    && (int) node.getSemantic() == semantic) {
                check(result == null,
                        "the fixture must contain one matching operator occurrence");
                result = node;
            }
        }
        return result;
    }

    private static Set<String> declarationNames(
            AugmentedNode declaration,
            Multigraph graph,
            int occurrence) {
        Set<String> names = new HashSet<>();
        List<MASGEdge> edges = declaration.getDownlinksAtTimeOfVisit(graph, occurrence);
        if (edges == null) {
            return names;
        }
        for (MASGEdge edge : edges) {
            if (edge.getPosition() > 1
                    && edge.getTarget().getSymbol() != null
                    && edge.getTarget().getSyntactic() == 127) {
                names.add(edge.getTarget().getSymbol().getName());
            }
        }
        return names;
    }

    private static MASGEdge edgeAt(List<MASGEdge> edges, int position) {
        for (MASGEdge edge : edges) {
            if (edge.getPosition() == position) {
                return edge;
            }
        }
        return null;
    }

    private static RawIrEvidence inspectRawIr(EGraphNode root) {
        EGraphNode outer = findOuterUniversal(root);
        check(outer != null, "raw IR must contain the source universal quantifier");
        List<EGraphNode> declarations = new ArrayList<>();
        for (EGraphNode child : outer.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                declarations.add(child);
            }
        }
        EGraphNode bDeclaration = declarationFor(declarations, "b");
        EGraphNode declaredDomain = bDeclaration == null
                || bDeclaration.getChildren().isEmpty()
                        ? null : bDeclaration.getChildren().get(0);
        EGraphNode domain = declaredDomain == null
                ? null : firstWithOpcode(declaredDomain, Opcode.COMPREHENSION);
        boolean comprehension = domain != null;
        EGraphNode zDeclaration = domain == null
                ? null : declarationFor(directDeclarations(domain), "z");
        EGraphNode dependentComparison = domain == null
                ? null : comparisonWithNames(domain, Opcode.NOT_EQUALS, Set.of("a", "z"));
        boolean distinctReferences = dependentComparison != null
                && dependentComparison.getChildren().size() == 2
                && dependentComparison.getChildren().get(0)
                        != dependentComparison.getChildren().get(1)
                && dependentComparison.getChildren().get(0).getSourceOccurrenceLineage()
                        != dependentComparison.getChildren().get(1)
                                .getSourceOccurrenceLineage();
        return new RawIrEvidence(
                declarations.size(),
                comprehension,
                zDeclaration != null,
                distinctReferences);
    }

    private static EGraphNode firstWithOpcode(EGraphNode node, Opcode opcode) {
        if (node.getOpcode() == opcode) {
            return node;
        }
        for (EGraphNode child : node.getChildren()) {
            EGraphNode found = firstWithOpcode(child, opcode);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static EGraphNode findOuterUniversal(EGraphNode node) {
        if (node.getOpcode() == Opcode.FORALL
                && declarationFor(directDeclarations(node), "a") != null
                && declarationFor(directDeclarations(node), "b") != null) {
            return node;
        }
        for (EGraphNode child : node.getChildren()) {
            EGraphNode found = findOuterUniversal(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static List<EGraphNode> directDeclarations(EGraphNode node) {
        List<EGraphNode> result = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                result.add(child);
            }
        }
        return result;
    }

    private static EGraphNode declarationFor(
            List<EGraphNode> declarations,
            String binder) {
        for (EGraphNode declaration : declarations) {
            for (int index = 1; index < declaration.getChildren().size(); index++) {
                EGraphNode child = declaration.getChildren().get(index);
                if (child.getOpcode() == Opcode.VARIABLE
                        && binder.equals(child.getSourceName())) {
                    return declaration;
                }
            }
        }
        return null;
    }

    private static EGraphNode comparisonWithNames(
            EGraphNode node,
            Opcode opcode,
            Set<String> expectedNames) {
        if (node.getOpcode() == opcode) {
            Set<String> names = new HashSet<>();
            for (EGraphNode child : node.getChildren()) {
                if (child.getOpcode() == Opcode.VARIABLE) {
                    names.add(child.getSourceName());
                }
            }
            if (names.equals(expectedNames)) {
                return node;
            }
        }
        for (EGraphNode child : node.getChildren()) {
            EGraphNode found = comparisonWithNames(child, opcode, expectedNames);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static boolean isRelDecl(Opcode opcode) {
        return opcode == Opcode.GENERICRELDECL
                || opcode == Opcode.DISJ
                || opcode == Opcode.VAR
                || opcode == Opcode.DISJVAR;
    }

    private static Predicate predicate(ModelUnit model, String name) {
        for (Predicate predicate : model.getPredDeclList()) {
            if (name.equals(predicate.getName())) {
                return predicate;
            }
        }
        throw new AssertionError("missing parsed predicate " + name);
    }

    private static QtFormula outerUniversal(Node node) {
        if (node instanceof QtFormula
                && ((QtFormula) node).getOp() == QtFormula.Quantifier.ALL
                && ((QtFormula) node).getVarDecls().size() == 2) {
            return (QtFormula) node;
        }
        for (Node child : node.getChildren()) {
            QtFormula result = outerUniversal(child);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static Multigraph graph(MASGVisitor visitor, String predicate) {
        Integer id = visitor.getForestId(predicate);
        check(id != null, "MASG forest must contain " + predicate);
        Multigraph result = visitor.getForest().get(id);
        check(result != null, "MASG graph must exist for " + predicate);
        return result;
    }

    private static String sourceModule() {
        return "module dependent_declaration\n"
                + "sig S {}\n"
                + "pred sourcePredicate { " + SOURCE_FORMULA + " }\n";
    }

    private static String comparisonModule(String normalized) {
        return "module dependent_declaration_equivalence\n"
                + "sig S {}\n"
                + "pred sourcePredicate { " + SOURCE_FORMULA + " }\n"
                + "pred normalizedPredicate { " + normalized + " }\n"
                + "assert Same { sourcePredicate[] iff normalizedPredicate[] }\n"
                + "check Same for 3 but 0 Int\n";
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RawIrEvidence {
        private final int outerDeclarations;
        private final boolean dependentDomainIsComprehension;
        private final boolean innerBinderSurvives;
        private final boolean dependentReferenceSurvives;

        private RawIrEvidence(
                int outerDeclarations,
                boolean dependentDomainIsComprehension,
                boolean innerBinderSurvives,
                boolean dependentReferenceSurvives) {
            this.outerDeclarations = outerDeclarations;
            this.dependentDomainIsComprehension = dependentDomainIsComprehension;
            this.innerBinderSurvives = innerBinderSurvives;
            this.dependentReferenceSurvives = dependentReferenceSurvives;
        }
    }
}

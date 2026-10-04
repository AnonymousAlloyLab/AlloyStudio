package is.fivefivefive.CanDis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.MiddleSymbol;
import is.fivefivefive.ACGN.alloy.RefSymbol;
import is.fivefivefive.ACGN.asg.AugmentedNode;
import is.fivefivefive.ACGN.asg.MASGEdge;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.codegen.Generator;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.metric.RepairProjection;
import is.fivefivefive.CanDis.metric.RepairView;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Call;
import parser.ast.nodes.ExprOrFormula;
import parser.ast.nodes.Node;
import parser.ast.nodes.OpenDecl;
import parser.ast.nodes.VarExpr;

/** Adversarial occurrence, identity, and ordering checks for Alloy CALL lowering. */
public final class CallExtractionRegressionTest {
    private static int checks;

    private CallExtractionRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source());
        ModelUnit parsedModel = new ModelUnit(null, module);
        List<String> parserHOrders = new ArrayList<>();
        for (Call call : findCalls(parsedModel, "h")) {
            parserHOrders.add(parserArgumentOrder(call));
        }
        check(parserHOrders.contains("a,b"), "parser CALL must retain h[a,b] order");
        check(parserHOrders.contains("b,a"), "parser CALL must retain h[b,a] order");
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(parsedModel, null);
        MASGVisitor.CallExtractionStats extractionStats = visitor.callExtractionStats();
        check(extractionStats.occurrences() > 0,
                "CALL instrumentation must count visited parser occurrences");
        check(extractionStats.containingCalls() >= 2,
                "CALL instrumentation must count nested and mixed calls");
        check(extractionStats.capturedVisits() == extractionStats.occurrences(),
                "every parser CALL must be captured before graph registration");
        check(extractionStats.validatedVisits() == extractionStats.occurrences(),
                "every counted parser CALL must have one validated visit");

        Multigraph nestedGraph = graph(visitor, "nested");
        List<AugmentedNode> fCalls = callNodes(nestedGraph, "callregression/f", 1);
        check(fCalls.size() == 2, "nested f[f[a]] must use two occurrence nodes");
        AugmentedNode outerF = fCalls.stream()
                .filter(node -> node.getDownlinksAtTimeOfVisit(nestedGraph, 1).get(1)
                        .getTarget().getSymbol() instanceof CallSymbol)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing outer f occurrence"));
        AugmentedNode innerF = outerF.getDownlinksAtTimeOfVisit(nestedGraph, 1)
                .get(1).getTarget();
        check(outerF != innerF, "nested calls must have explicit target occurrence identity");
        assertCallVisit(nestedGraph, outerF, 1, "f", 1);
        assertCallVisit(nestedGraph, innerF, 1, "f", 1);

        Multigraph mixedGraph = graph(visitor, "mixed");
        assertCallVisit(mixedGraph, onlyCall(mixedGraph, "callregression/f", 1), 1, "f", 1);
        assertCallVisit(mixedGraph, onlyCall(mixedGraph, "callregression/g", 1), 1, "g", 1);
        Multigraph zeroGraph = graph(visitor, "usesZero");
        AugmentedNode zeroCall = onlyCall(zeroGraph, "callregression/zero", 0);
        assertCallVisit(zeroGraph, zeroCall, 1, "zero", 0);
        check(((CallSymbol) zeroCall.getSymbol()).getKind() == CallSymbol.Kind.FORMULA,
                "predicate calls must retain formula-call identity");
        Multigraph orderGraph = graph(visitor, "order");
        List<AugmentedNode> hCalls = callNodes(orderGraph, "callregression/h", 2);
        check(hCalls.size() == 2, "two h calls must retain two independent occurrences");
        check(hCalls.stream().map(node -> ((CallSymbol) node.getSymbol()).getOccurrenceId())
                        .distinct().count() == 2,
                "two source CALLs must retain distinct parser occurrence identities");
        check(hCalls.stream().anyMatch(node -> hasArgumentNames(orderGraph, node, "a", "b")),
                "h[a,b] argument roles were not retained");
        check(hCalls.stream().anyMatch(node -> hasArgumentNames(orderGraph, node, "b", "a")),
                "h[b,a] argument roles were not retained");
        check(hCalls.stream().anyMatch(node -> "h[a, b]".equals(
                        new Generator().toCode(orderGraph, node, 1, null))),
                "MASG serialization changed h[a,b] argument order");
        check(hCalls.stream().anyMatch(node -> "h[b, a]".equals(
                        new Generator().toCode(orderGraph, node, 1, null))),
                "MASG serialization changed h[b,a] argument order");

        Multigraph importedGraph = graph(visitor, "imported");
        AugmentedNode importedFirst = onlyCall(
                importedGraph, "util/ordering<A>/first", 0);
        AugmentedNode importedLast = onlyCall(
                importedGraph, "util/ordering<A>/last", 0);
        check(((CallSymbol) importedFirst.getSymbol()).getArityAuthority()
                        == CallSymbol.ArityAuthority.TYPECHECKED_IMPORT,
                "imported CALL arity must name its typechecked authority");
        check(!((CallSymbol) importedFirst.getSymbol()).getCallee()
                        .startsWith("callregression/"),
                "imported declarations must not be qualified by the client module");
        assertCallVisit(importedGraph, importedFirst, 1, "ord/first", 0);
        assertCallVisit(importedGraph, importedLast, 1, "ord/last", 0);

        CompModule integerModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP,
                String.join("\n",
                        "module integernextregression",
                        "open util/integer",
                        "sig Train { pos: one Int }",
                        "pred usesNext { all t: Train | t.pos.next in Int }"));
        ModelUnit integerModel = new ModelUnit(null, integerModule);
        MASGVisitor integerVisitor = new MASGVisitor(
                new GlobalVariables(), integerModule);
        integerVisitor.visit(integerModel, null);
        Multigraph integerGraph = graph(integerVisitor, "usesNext");
        AugmentedNode integerNext = onlyCall(
                integerGraph, "util/integer/next", 0);
        check(((CallSymbol) integerNext.getSymbol()).getArityAuthority()
                        == CallSymbol.ArityAuthority.TYPECHECKED_IMPORT,
                "util/integer/next must use its independently pinned import declaration");
        assertCallVisit(integerGraph, integerNext, 1, "integer/next", 0);
        String integerNextSerialization = CanonicalAlloyPipeline.prepare(
                Canonical.prepare(integerGraph)).canonicalObservation().stableForm();
        check(integerNextSerialization.contains(
                        "ALLOY/CALL/util/integer/next/0/call/expression/TYPECHECKED_IMPORT"),
                "util/integer/next identity and zero arity must reach certification");

        String distinctImportSource = String.join("\n",
                "module distinctimports",
                "open util/ordering[A] as o1",
                "open util/ordering[B] as o2",
                "sig A {}",
                "sig B {}",
                "pred p { o1/first in A and o2/first in B }");
        CompModule distinctImportModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, distinctImportSource);
        MASGVisitor distinctImportVisitor = new MASGVisitor(
                new GlobalVariables(), distinctImportModule);
        distinctImportVisitor.visit(new ModelUnit(null, distinctImportModule), null);
        Multigraph distinctImportGraph = graph(distinctImportVisitor, "p");
        check(callNodes(distinctImportGraph, "util/ordering<A>/first", 0).size() == 1,
                "the first explicitly aliased module instance must retain its type arguments");
        check(callNodes(distinctImportGraph, "util/ordering<B>/first", 0).size() == 1,
                "the second explicitly aliased module instance must retain its type arguments");

        String regenerated = new Generator().toCode(
                nestedGraph, outerF, 1, null);
        check(countOccurrences(regenerated, "f[") == 2,
                "MASG code generation must retain both nested CALLs: " + regenerated);

        Canonical.Prepared nested = Canonical.prepare(nestedGraph);
        Canonical.Prepared single = Canonical.prepare(graph(visitor, "single"));
        List<EGraphNode> nestedCalls = callNodes(nested.normalizedForms());
        check(nestedCalls.size() == 2, "IR must retain both nested f invocations");
        check(nested.callStats().occurrences() == 2
                        && nested.callStats().containingCalls() == 1,
                "normalized IR CALL instrumentation must retain nested occurrence counts");
        check(nestedCalls.stream().map(EGraphNode::getCallOccurrenceId).distinct().count() == 2,
                "normalization must preserve distinct parser CALL occurrence identities");
        EGraphNode outer = nestedCalls.stream()
                .filter(node -> !node.getChildren().isEmpty()
                        && node.getChildren().get(0).getOpcode() == Opcode.CALL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("nested CALL structure was flattened"));
        check("f".equals(outer.getSourceName()), "IR CALL must carry its exact callee");
        check("callregression/f".equals(outer.getSemanticIdentity()),
                "IR CALL must carry declaration-qualified semantic identity");
        check(outer.getDeclaredArity() == 1 && outer.getMaxArity() == 1,
                "IR CALL must retain exact declared arity");
        check("call/expression".equals(outer.getSourceType()),
                "IR CALL must carry its formula/expression role");
        check(!CanonicalAlloyPipeline.prepare(nested).equivalentTo(
                        CanonicalAlloyPipeline.prepare(single)),
                "f[f[a]] and f[a] must remain distinct at the certified boundary: nested="
                        + Canonical.irTemporalFol(nested) + ", single="
                        + Canonical.irTemporalFol(single));
        String serialized = CanonicalAlloyPipeline.prepare(nested)
                .canonicalObservation().stableForm();
        check(serialized.contains("ALLOY/CALL/callregression/f/1/call/expression"),
                "serialized operator identity must bind callee, arity, and call kind");

        Canonical.Prepared mixed = Canonical.prepare(mixedGraph);
        EGraphNode mixedOuter = callNodes(mixed.normalizedForms()).stream()
                .filter(node -> "callregression/f".equals(node.getSemanticIdentity()))
                .findFirst().orElseThrow();
        check(mixedOuter.getChildren().size() == 1
                        && mixedOuter.getChildren().get(0).getOpcode() == Opcode.CALL
                        && "callregression/g".equals(
                                mixedOuter.getChildren().get(0).getSemanticIdentity()),
                "f[g[a]] must retain its nested callee at the IR boundary");
        check(callNodes(Canonical.prepare(zeroGraph).normalizedForms()).stream()
                        .anyMatch(node -> node.getDeclaredArity() == 0
                                && "callregression/zero".equals(node.getSemanticIdentity())),
                "zero-argument formula CALL must survive normalization");
        List<EGraphNode> hIrCalls = callNodes(Canonical.prepare(orderGraph).normalizedForms());
        check(hIrCalls.stream().filter(node -> "callregression/h".equals(
                        node.getSemanticIdentity())).count() == 2,
                "both multi-argument CALLs must survive the IR boundary");
        check(hIrCalls.stream().anyMatch(node -> hasIrArgumentNames(node, "a", "b")),
                "h[a,b] argument order changed at the IR boundary");
        check(hIrCalls.stream().anyMatch(node -> hasIrArgumentNames(node, "b", "a")),
                "h[b,a] argument order changed at the IR boundary");
        CanonicalAlloyPipeline.Prepared orderPrepared = CanonicalAlloyPipeline.prepare(
                Canonical.prepare(orderGraph));
        String orderSerialization = orderPrepared.canonicalObservation().stableForm();
        check(orderSerialization.contains("ALLOY/CALL/callregression/h/2/call/expression"),
                "multi-argument CALL identity did not reach serialization");
        List<is.fivefivefive.CanDis.theory.CallOccurrenceCertificate> hEvidence =
                orderPrepared.semanticArtifact().callOccurrenceCertificates().stream()
                        .filter(value -> "callregression/h".equals(value.qualifiedCallee()))
                        .toList();
        check(hEvidence.size() == 2
                        && hEvidence.stream().map(
                                is.fivefivefive.CanDis.theory.CallOccurrenceCertificate::occurrenceId)
                                .distinct().count() == 2
                        && hEvidence.stream().map(
                                is.fivefivefive.CanDis.theory.CallOccurrenceCertificate::sourcePath)
                                .distinct().count() == 2,
                "certified CALL provenance must retain distinct occurrence IDs and source paths");
        check(hEvidence.stream().allMatch(value -> value.orderedArguments().size() == 2),
                "certified CALL provenance must retain both ordered argument endpoints");
        String callAbSerialization = CanonicalAlloyPipeline.prepare(
                Canonical.prepare(graph(visitor, "callAB")))
                .canonicalObservation().stableForm();
        String callBaSerialization = CanonicalAlloyPipeline.prepare(
                Canonical.prepare(graph(visitor, "callBA")))
                .canonicalObservation().stableForm();
        check(!callAbSerialization.equals(callBaSerialization),
                "h[a,b] and h[b,a] must remain distinct through certificate serialization");
        String importedSerialization = CanonicalAlloyPipeline.prepare(
                Canonical.prepare(importedGraph)).canonicalObservation().stableForm();
        check(importedSerialization.contains("ALLOY/CALL/util/ordering<A>/first/0/call/expression")
                        && importedSerialization.contains(
                                "ALLOY/CALL/util/ordering<A>/last/0/call/expression"),
                "imported declaration identity did not reach serialization");
        check(importedSerialization.contains("TYPECHECKED_IMPORT"),
                "imported arity authority did not reach certified serialization");
        String zeroSerialization = CanonicalAlloyPipeline.prepare(
                Canonical.prepare(zeroGraph)).canonicalObservation().stableForm();
        check(zeroSerialization.contains(
                        "ALLOY/CALL/callregression/zero/0/call/formula/DECLARATION"),
                "formula-call kind and declaration authority did not reach serialization");

        Command witness = module.getAllCommands().get(0);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        A4Solution solution = TranslateAlloyToKodkod.execute_command(
                A4Reporter.NOP, module.getAllReachableSigs(), witness, options);
        check(solution != null && solution.satisfiable(),
                "Alloy must exhibit the two-atom nested/single semantic separation");

        List<MASGEdge> damaged = outerF.getDownlinksAtTimeOfVisit(nestedGraph, 1);
        MASGEdge removed = damaged.remove(damaged.size() - 1);
        boolean rejected = false;
        try {
            Canonical.prepare(nestedGraph);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("Incomplete CALL occurrence");
        } finally {
            damaged.add(removed);
        }
        check(rejected, "an incomplete CALL visit must fail closed without borrowing another visit");

        MASGEdge duplicatedCallee = damaged.get(0);
        damaged.add(1, new MASGEdge(
                outerF, duplicatedCallee.getTarget(), 2, 1));
        rejected = false;
        try {
            Canonical.prepare(nestedGraph);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("Incomplete CALL occurrence");
        } finally {
            damaged.remove(1);
        }
        check(rejected, "a duplicate CALL callee must fail closed");

        MASGEdge originalArgument = damaged.get(1);
        AugmentedNode localEnd = damaged.get(damaged.size() - 1).getTarget();
        damaged.set(1, new MASGEdge(outerF, localEnd, 2, 1));
        rejected = false;
        try {
            Canonical.prepare(nestedGraph);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("END in an argument role");
        } finally {
            damaged.set(1, originalArgument);
        }
        check(rejected, "a duplicate END in an argument role must fail closed");

        damaged.set(1, new MASGEdge(innerF, originalArgument.getTarget(), 2, 1));
        rejected = false;
        try {
            Canonical.prepare(nestedGraph);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("noncontiguous roles");
        } finally {
            damaged.set(1, originalArgument);
        }
        check(rejected, "a foreign-source CALL edge must fail closed");

        MASGEdge originalCallee = damaged.get(0);
        RefSymbol forgedCallee = new RefSymbol(
                null,
                "f",
                "fraud/f",
                CallSymbol.Kind.EXPRESSION,
                1,
                CallSymbol.ArityAuthority.DECLARATION);
        AugmentedNode forgedCalleeNode = new AugmentedNode(125, -1, forgedCallee);
        forgedCallee.setNode(forgedCalleeNode);
        damaged.set(0, new MASGEdge(outerF, forgedCalleeNode, 1, 1));
        rejected = false;
        try {
            Canonical.prepare(nestedGraph);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("wrong callee");
        }
        check(rejected,
                "same source spelling with another qualified callee identity must fail closed");
        rejected = false;
        try {
            new Generator().toCode(nestedGraph, outerF, 1, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("wrong callee");
        } finally {
            damaged.set(0, originalCallee);
        }
        check(rejected,
                "code generation must reject a same-spelling callee with another declaration key");

        MASGEdge innerArgument = innerF.getDownlinksAtTimeOfVisit(nestedGraph, 1).get(1);
        innerF.getDownlinksAtTimeOfVisit(nestedGraph, 1).set(
                1, new MASGEdge(innerF, innerF, 2, 1));
        rejected = false;
        try {
            Canonical.prepare(nestedGraph);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("referenced more than once");
        } finally {
            innerF.getDownlinksAtTimeOfVisit(nestedGraph, 1).set(1, innerArgument);
        }
        check(rejected, "a CALL occurrence reused as another occurrence must fail closed");

        CompModule malformedModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source());
        ModelUnit malformed = new ModelUnit(null, malformedModule);
        Call fUse = findCall(malformed, "f");
        fUse.setArguments(Collections.emptyList());
        rejected = false;
        try {
            new MASGVisitor(
                    new GlobalVariables(), malformedModule).visit(malformed, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("arity disagrees with declaration");
        }
        check(rejected, "observed CALL arguments must be checked against declaration arity");

        malformedModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source());
        malformed = new ModelUnit(null, malformedModule);
        Call importedUse = findCall(malformed, "ord/first");
        ExprOrFormula borrowedArgument = findCall(malformed, "f").getArguments().get(0);
        importedUse.setArguments(Collections.singletonList(borrowedArgument));
        rejected = false;
        try {
            new MASGVisitor(
                    new GlobalVariables(), malformedModule).visit(malformed, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains(
                    "arity disagrees with imported declaration");
        }
        check(rejected,
                "imported CALL arity must come from the pinned declaration, not child count");

        malformedModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source());
        malformed = new ModelUnit(null, malformedModule);
        findCall(malformed, "ord/first").setName("ord/missing");
        rejected = false;
        try {
            new MASGVisitor(
                    new GlobalVariables(), malformedModule).visit(malformed, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains(
                    "lacks an independently pinned declaration");
        }
        check(rejected, "an opened but unpinned imported member must fail closed");

        malformedModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source());
        malformed = new ModelUnit(null, malformedModule);
        fUse = findCall(malformed, "f");
        fUse.setName("missing");
        rejected = false;
        try {
            new MASGVisitor(
                    new GlobalVariables(), malformedModule).visit(malformed, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("Unresolved call declaration");
        }
        check(rejected, "an unqualified unresolved CALL must fail closed");

        malformedModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source());
        malformed = new ModelUnit(null, malformedModule);
        fUse = findCall(malformed, "f");
        fUse.setName("ghost/f");
        rejected = false;
        try {
            new MASGVisitor(
                    new GlobalVariables(), malformedModule).visit(malformed, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("Unresolved call declaration");
        }
        check(rejected, "a qualified but unopened CALL must fail closed");

        malformedModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source());
        malformed = new ModelUnit(null, malformedModule);
        List<OpenDecl> opens = new ArrayList<>(malformed.getOpenDeclList());
        OpenDecl conflicting = new OpenDecl(malformed);
        conflicting.setFileName("util/ordering");
        conflicting.setAlias("ord");
        conflicting.setArguments(Collections.singletonList("B"));
        opens.add(conflicting);
        malformed.setOpenDeclList(opens);
        rejected = false;
        try {
            new MASGVisitor(
                    new GlobalVariables(), malformedModule).visit(malformed, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("Ambiguous imported module alias");
        }
        check(rejected, "conflicting imported aliases must fail closed");

        malformedModule = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source());
        malformed = new ModelUnit(null, malformedModule);
        malformed.getFunDeclList().get(1).setName("f");
        rejected = false;
        try {
            new MASGVisitor(
                    new GlobalVariables(), malformedModule).visit(malformed, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("Ambiguous callable alias");
        }
        check(rejected, "conflicting local declarations must fail closed");

        String anonymousLeftIdentity = anonymousCallIdentity(
                "sig A {}\nfun f[x: A]: set A { x }\npred p[a: A] { f[a] = a }");
        String anonymousRightIdentity = anonymousCallIdentity(
                "sig A {}\nfun f[x: A]: set A { A - x }\npred p[a: A] { f[a] = a }");
        check(!anonymousLeftIdentity.equals(anonymousRightIdentity),
                "anonymous modules must qualify local calls by stable source identity");

        EGraphNode missingArity = syntheticCall("callregression/missing", "call/formula");
        missingArity.setDeclaredArity(-1);
        NormalForm malformedForm = new NormalForm();
        malformedForm.addEClass(missingArity);
        rejected = false;
        try {
            TheoryAlloyAdapter.adapt(List.of(malformedForm));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("lacks declared arity");
        }
        check(rejected, "the certified adapter must not infer missing CALL arity from children");

        EGraphNode missingOccurrence = syntheticCall(
                "callregression/missing-occurrence", "call/formula");
        missingOccurrence.setCallOccurrenceId(-1L);
        rejected = false;
        try {
            TheoryAlloyAdapter.adapt(List.of(formWith(missingOccurrence)));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("parser occurrence identity");
        }
        check(rejected, "the certified adapter must reject missing CALL occurrence identity");

        EGraphNode missingIdentity = syntheticCall(
                "callregression/missing-identity", "call/formula");
        missingIdentity.setSemanticIdentity(null);
        rejected = false;
        try {
            TheoryAlloyAdapter.adapt(List.of(formWith(missingIdentity)));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("qualified semantic identity");
        }
        check(rejected, "the certified adapter must reject missing CALL identity");

        EGraphNode unqualifiedIdentity = syntheticCall("module/qualified", "call/formula");
        unqualifiedIdentity.setSemanticIdentity("f");
        rejected = false;
        try {
            TheoryAlloyAdapter.adapt(List.of(formWith(unqualifiedIdentity)));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("not qualified");
        }
        check(rejected, "the certified adapter must reject unqualified CALL identity");

        EGraphNode controlIdentity = syntheticCall(
                "callregression/control", "call/formula");
        controlIdentity.setSemanticIdentity("callregression/control\0suffix");
        rejected = false;
        try {
            TheoryAlloyAdapter.adapt(List.of(formWith(controlIdentity)));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("qualified semantic identity");
        }
        check(rejected,
                "the certified adapter must reject embedded controls in CALL identity");

        List<String> forbiddenCallFragments = List.of(
                "\0", "\u200B", "\uE000", "\u0378",
                String.valueOf((char) 0xD800));
        for (String fragment : forbiddenCallFragments) {
            rejected = false;
            try {
                new CallSymbol(
                        CallSymbol.Kind.FORMULA,
                        "f" + fragment,
                        "module/f",
                        0,
                        0,
                        CallSymbol.ArityAuthority.DECLARATION);
            } catch (IllegalArgumentException expected) {
                rejected = expected.getMessage().contains("visible identity");
            }
            check(rejected,
                    "CallSymbol must reject forbidden source-name identity categories");

            rejected = false;
            try {
                new CallSymbol(
                        CallSymbol.Kind.FORMULA,
                        "f",
                        "module/f" + fragment,
                        0,
                        0,
                        CallSymbol.ArityAuthority.DECLARATION);
            } catch (IllegalArgumentException expected) {
                rejected = expected.getMessage().contains("visible identity");
            }
            check(rejected,
                    "CallSymbol must reject forbidden callee identity categories");
        }

        rejected = false;
        try {
            new CallSymbol(
                    CallSymbol.Kind.FORMULA,
                    "f",
                    "f",
                    0,
                    0,
                    CallSymbol.ArityAuthority.DECLARATION);
        } catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("must be qualified");
        }
        check(rejected, "CallSymbol construction must reject unqualified identity");

        EGraphNode invalidKind = syntheticCall("callregression/invalid-kind", "formula");
        rejected = false;
        try {
            TheoryAlloyAdapter.adapt(List.of(formWith(invalidKind)));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("formula/expression kind");
        }
        check(rejected, "the certified adapter must reject invalid CALL kind");

        EGraphNode invalidAuthority = syntheticCall(
                "callregression/invalid-authority", "call/formula");
        invalidAuthority.setCallArityAuthority("OBSERVED_CHILD_COUNT");
        rejected = false;
        try {
            TheoryAlloyAdapter.adapt(List.of(formWith(invalidAuthority)));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("invalid arity authority");
        }
        check(rejected, "the certified adapter must reject unknown CALL authority");

        EGraphNode missingAuthority = syntheticCall(
                "callregression/missing-authority", "call/formula");
        missingAuthority.setCallArityAuthority(null);
        NormalForm missingAuthorityForm = new NormalForm();
        missingAuthorityForm.addEClass(missingAuthority);
        rejected = false;
        try {
            CanonicalDistance.distance(
                    CanonicalDistance.prepare(List.of(missingAuthorityForm)),
                    CanonicalDistance.prepare(List.of(formulaFormFor(
                            "callregression/missing-authority"))));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("arity authority");
        }
        check(rejected, "the fast metric must reject missing CALL authority");

        EGraphNode wrongChildCount = syntheticCall(
                "callregression/wrong-children", "call/formula");
        rejected = false;
        try {
            wrongChildCount.addChild(syntheticVariable("x"));
        } catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("too many children");
        }
        check(rejected, "the e-node boundary must reject CALL child-count corruption");

        EGraphNode projectionCall = syntheticCall(
                "callregression/projection", "call/formula");
        NormalForm projectionForm = formWith(projectionCall);
        TheoryAlloyAdapter.Result projectionEvidence =
                TheoryAlloyAdapter.adapt(List.of(projectionForm));
        rejected = false;
        try {
            projectionCall.setSemanticIdentity(null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("immutable");
        }
        check(rejected, "certification must freeze CALL identity before projection");
        repairView(projectionForm, projectionEvidence);

        EGraphNode backtranslationCall = syntheticCall(
                "callregression/backtranslation", "call/formula");
        backtranslationCall.setSemanticIdentity(null);
        rejected = false;
        try {
            CanonicalBacktranslator.formula(formWith(backtranslationCall));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("qualified semantic identity");
        }
        check(rejected, "backtranslation must reject metadata-incomplete CALLs");

        EGraphNode internalCall = syntheticCall(
                "callregression/internal", "call/formula");
        internalCall.setSemanticIdentity(null);
        EGraphNode internalParent = new EGraphNode(
                9002,
                Opcode.AND,
                new ArrayList<>(List.of(internalCall, syntheticVariable("guard"))),
                true,
                -1,
                true,
                Metatype.BOOLEAN);
        rejected = false;
        try {
            internalParent.saturate();
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("qualified semantic identity");
        }
        check(rejected, "internal canonicalization must reject incomplete CALL identity");

        EGraphNode wrongMaxArity = syntheticCallWithMaxArity(
                "callregression/wrong-max", 2);
        rejected = false;
        try {
            CanonicalDistance.distance(
                    CanonicalDistance.prepare(List.of(formWith(wrongMaxArity))),
                    CanonicalDistance.prepare(List.of(formulaFormFor(
                            "callregression/wrong-max"))));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("ordered fixed arity");
        }
        check(rejected, "the fast metric must require max arity to equal declared arity");

        EGraphNode deletionBypass = new EGraphNode(
                9004, Opcode.CALL, new ArrayList<>(), false, 0, false, Metatype.BOOLEAN);
        rejected = false;
        try {
            CanonicalDistance.prepare(List.of(formWith(deletionBypass)));
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("source spelling");
        }
        check(rejected,
                "metric preparation must reject malformed CALLs before insertion/deletion cost");

        NormalForm formulaForm = new NormalForm();
        formulaForm.addEClass(syntheticCall("callregression/kind", "call/formula"));
        NormalForm expressionForm = new NormalForm();
        expressionForm.addEClass(syntheticCall("callregression/kind", "call/expression"));
        check(!TheoryAlloyAdapter.adapt(List.of(formulaForm)).canonicalKey().equals(
                        TheoryAlloyAdapter.adapt(List.of(expressionForm)).canonicalKey()),
                "formula and expression CALL kinds must have distinct internal keys");

        NormalForm declarationForm = new NormalForm();
        declarationForm.addEClass(syntheticCall(
                "callregression/authority", "call/formula",
                CallSymbol.ArityAuthority.DECLARATION));
        NormalForm importForm = new NormalForm();
        importForm.addEClass(syntheticCall(
                "callregression/authority", "call/formula",
                CallSymbol.ArityAuthority.TYPECHECKED_IMPORT));
        check(CanonicalDistance.distance(
                        CanonicalDistance.prepare(List.of(declarationForm)),
                        CanonicalDistance.prepare(List.of(importForm))) > 0,
                "CALL arity authority must participate in internal structural identity");
        check(!TheoryAlloyAdapter.adapt(List.of(declarationForm)).canonicalKey().equals(
                        TheoryAlloyAdapter.adapt(List.of(importForm)).canonicalKey()),
                "CALL arity authority must participate in certified identity");
        TheoryAlloyAdapter.Result declarationEvidence =
                TheoryAlloyAdapter.adapt(List.of(declarationForm));
        TheoryAlloyAdapter.Result importEvidence =
                TheoryAlloyAdapter.adapt(List.of(importForm));
        RepairView declarationView = repairView(
                declarationForm, declarationEvidence);
        RepairView importView = repairView(importForm, importEvidence);
        check(QuotientRepairDistance.evaluate(declarationView, importView).distance() > 0,
                "CALL authority must participate in the production repair key");

        AugmentedNode genericCall = new AugmentedNode(
                7, 9100, new MiddleSymbol("CALL_EXPR"));
        Multigraph genericGraph = new Multigraph(genericCall, new GlobalVariables());
        genericGraph.addVertex(genericCall);
        rejected = false;
        try {
            new Generator().toCode(genericGraph, genericCall, 1, null);
        } catch (IllegalStateException expected) {
            rejected = expected.getMessage().contains("lacks CallSymbol metadata");
        }
        check(rejected, "the generator must reject generic syntactic CALL nodes");

        System.out.println("CallExtractionRegressionTest passed: " + checks + " checks");
    }

    private static Multigraph graph(MASGVisitor visitor, String callable) {
        Integer id = visitor.getForestId(callable);
        Multigraph graph = id == null ? null : visitor.getForest().get(id);
        if (graph == null) {
            throw new AssertionError("Missing callable graph: " + callable);
        }
        return graph;
    }

    private static List<AugmentedNode> callNodes(
            Multigraph graph, String callee, int arity) {
        List<AugmentedNode> result = new ArrayList<>();
        for (AugmentedNode node : graph.getVertices()) {
            if (node.getSymbol() instanceof CallSymbol) {
                CallSymbol call = (CallSymbol) node.getSymbol();
                if (callee.equals(call.getCallee()) && arity == call.getDeclaredArity()) {
                    result.add(node);
                }
            }
        }
        return result;
    }

    private static AugmentedNode onlyCall(Multigraph graph, String callee, int arity) {
        List<AugmentedNode> calls = callNodes(graph, callee, arity);
        if (calls.size() != 1) {
            throw new AssertionError(
                    "Expected one CALL " + callee + "/" + arity + ", found " + calls.size());
        }
        return calls.get(0);
    }

    private static void assertCallVisit(
            Multigraph graph,
            AugmentedNode node,
            int tov,
            String callee,
            int arity) {
        List<MASGEdge> edges = node.getDownlinksAtTimeOfVisit(graph, tov);
        check(edges != null && edges.size() == arity + 2,
                "CALL visit must contain callee, arguments, and END");
        for (int index = 0; index < edges.size(); index++) {
            check(edges.get(index).getPosition() == index + 1,
                    "CALL roles must be contiguous and ordered");
            check(edges.get(index).getTimeOfVisit() == tov,
                    "CALL edge must remain attached to its captured visit");
        }
        check(callee.equals(edges.get(0).getTarget().getSymbol().getName()),
                "CALL callee role must retain exact identity");
        check(((CallSymbol) node.getSymbol()).matchesTarget(
                        edges.get(0).getTarget().getSymbol()),
                "CALL callee role must retain the complete declaration key");
        check(edges.get(edges.size() - 1).getTarget().getSymbol().isEndSymbol(),
                "CALL terminator must be final");
    }

    private static boolean hasArgumentNames(
            Multigraph graph, AugmentedNode node, String first, String second) {
        List<MASGEdge> edges = node.getDownlinksAtTimeOfVisit(graph, 1);
        return edges != null && edges.size() == 4
                && first.equals(edges.get(1).getTarget().getSymbol().getName())
                && second.equals(edges.get(2).getTarget().getSymbol().getName());
    }

    private static boolean hasIrArgumentNames(
            EGraphNode node, String first, String second) {
        return "callregression/h".equals(node.getSemanticIdentity())
                && node.getChildren().size() == 2
                && first.equals(node.getChildren().get(0).getSourceName())
                && second.equals(node.getChildren().get(1).getSourceName());
    }

    private static List<EGraphNode> callNodes(List<NormalForm> forms) {
        List<EGraphNode> calls = new ArrayList<>();
        Set<EGraphNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        for (NormalForm form : forms) {
            if (form.getMatrixEGraph() != null) {
                pending.add(form.getMatrixEGraph());
            }
        }
        while (!pending.isEmpty()) {
            EGraphNode node = pending.removeFirst();
            if (!seen.add(node)) {
                continue;
            }
            if (node.getOpcode() == Opcode.CALL) {
                calls.add(node);
            }
            pending.addAll(node.getChildren());
        }
        return calls;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(needle, index)) >= 0;
                index += needle.length()) {
            count++;
        }
        return count;
    }

    private static Call findCall(Node root, String name) {
        List<Call> calls = findCalls(root, name);
        if (!calls.isEmpty()) {
            return calls.get(0);
        }
        throw new AssertionError("Missing parser CALL " + name);
    }

    private static List<Call> findCalls(Node root, String name) {
        List<Call> calls = new ArrayList<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (!seen.add(node)) {
                continue;
            }
            if (node instanceof Call && name.equals(((Call) node).getName())) {
                calls.add((Call) node);
            }
            pending.addAll(node.getChildren());
        }
        return calls;
    }

    private static String parserArgumentOrder(Call call) {
        List<String> names = new ArrayList<>();
        for (ExprOrFormula argument : call.getArguments()) {
            names.add(argument instanceof VarExpr
                    ? ((VarExpr) argument).getName()
                    : argument.getClass().getSimpleName());
        }
        return String.join(",", names);
    }

    private static NormalForm formulaFormFor(String identity) {
        NormalForm form = new NormalForm();
        form.addEClass(syntheticCall(identity, "call/formula"));
        return form;
    }

    private static EGraphNode syntheticVariable(String name) {
        EGraphNode node = new EGraphNode(
                9001, Opcode.VARIABLE, new ArrayList<>(), false, 0, false, Metatype.ATOMIC);
        node.setSourceName(name);
        node.setAlphaName(name);
        return node;
    }

    private static EGraphNode syntheticCallWithMaxArity(String identity, int maxArity) {
        EGraphNode node = new EGraphNode(
                9003, Opcode.CALL, new ArrayList<>(), false, maxArity, false, Metatype.BOOLEAN);
        node.setSourceName(identity.substring(identity.lastIndexOf('/') + 1));
        node.setSemanticIdentity(identity);
        node.setSourceType("call/formula");
        node.setCallOccurrenceId(9003L);
        node.setDeclaredArity(0);
        node.setCallArityAuthority(CallSymbol.ArityAuthority.DECLARATION.name());
        return node;
    }

    private static NormalForm formWith(EGraphNode node) {
        NormalForm form = new NormalForm();
        form.addEClass(node);
        return form;
    }

    private static RepairView repairView(
            NormalForm form,
            TheoryAlloyAdapter.Result evidence) {
        return RepairProjection.project(
                evidence,
                List.of(form));
    }

    private static String anonymousCallIdentity(String source) throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(
                A4Reporter.NOP, source);
        ModelUnit model = new ModelUnit(null, module);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(model, null);
        Multigraph graph = graph(visitor, "p");
        for (AugmentedNode node : graph.getVertices()) {
            if (node.getSymbol() instanceof CallSymbol
                    && "f".equals(((CallSymbol) node.getSymbol()).getSourceName())) {
                return ((CallSymbol) node.getSymbol()).getCallee();
            }
        }
        throw new AssertionError("Missing anonymous-module f call");
    }

    private static EGraphNode syntheticCall(String identity, String kind) {
        return syntheticCall(identity, kind, CallSymbol.ArityAuthority.DECLARATION);
    }

    private static EGraphNode syntheticCall(
            String identity,
            String kind,
            CallSymbol.ArityAuthority authority) {
        EGraphNode node = new EGraphNode(
                9000, Opcode.CALL, new ArrayList<>(), false, 0, false, Metatype.BOOLEAN);
        node.setSourceName(identity.substring(identity.lastIndexOf('/') + 1));
        node.setSemanticIdentity(identity);
        node.setSourceType(kind);
        node.setCallOccurrenceId(9000L);
        node.setDeclaredArity(0);
        node.setCallArityAuthority(authority.name());
        return node;
    }

    private static String source() {
        return String.join("\n",
                "module callregression",
                "open util/ordering[A] as ord",
                "sig A { r: one A }",
                "fact cycle { all a: A | a.r != a and all b: A - a | a.r = b }",
                "fun f[x: A]: A { x.r }",
                "fun g[x: A]: A { x }",
                "fun h[x, y: A]: A { x }",
                "pred zero { no none }",
                "pred nested[a: A] { f[f[a]] = a }",
                "pred single[a: A] { f[a] = a }",
                "pred mixed[a: A] { f[g[a]] = a }",
                "pred usesZero { zero[] }",
                "pred order[a, b: A] { h[a, b] = h[b, a] }",
                "pred callAB[a, b: A] { h[a, b] = a }",
                "pred callBA[a, b: A] { h[b, a] = a }",
                "pred imported { ord/first = ord/last }",
                "run { some a: A | nested[a] and not single[a] } for exactly 2 A");
    }
}

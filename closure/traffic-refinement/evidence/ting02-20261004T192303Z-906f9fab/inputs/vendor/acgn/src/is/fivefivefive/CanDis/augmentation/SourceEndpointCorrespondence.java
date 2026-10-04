package is.fivefivefive.CanDis.augmentation;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.ast.ExprCall;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import parser.ast.nodes.ModelUnit;

/**
 * Reconstructs observed endpoints from the exact Alloy bytes named by their
 * provenance. This prevents source-level SAT evidence from being attached to
 * an unrelated prepared graph.
 */
final class SourceEndpointCorrespondence {
    static final String VERSION =
            "adaptive-source-endpoint-correspondence-v4-closed-source-closure";

    record Evidence(
            String modelSourceSha256,
            String leftSelector,
            String rightSelector,
            String leftObservationDigest,
            String rightObservationDigest,
            String detail,
            String digest) {
    }

    private SourceEndpointCorrespondence() {
    }

    static Evidence verify(
            AlloyEquivalenceValidator.Request request,
            CanonicalAlloyPipeline.Prepared left,
            CanonicalAlloyPipeline.Prepared right,
            String leftInputSha256,
            String rightInputSha256) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        byte[] sourceBytes = request.modelSource().getBytes(StandardCharsets.UTF_8);
        String sourceSha256 = AugmentationDigests.sha256(sourceBytes);
        if (!sourceSha256.equals(leftInputSha256)
                || !sourceSha256.equals(rightInputSha256)) {
            throw new IllegalArgumentException(
                    "Adaptive Alloy evidence must use the exact source bytes recorded "
                            + "by both prepared endpoints");
        }

        try {
            CompModule module = CompUtil.parseEverything_fromString(
                    A4Reporter.NOP, request.modelSource());
            AlloyModuleClosureAuthority.requireClosedRoot(module);
            Endpoint leftEndpoint = endpoint(
                    module, request.leftExpression(), "left");
            Endpoint rightEndpoint = endpoint(
                    module, request.rightExpression(), "right");
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(new ModelUnit(null, module), null);

            CanonicalAlloyPipeline.Prepared rebuiltLeft = rebuild(
                    visitor, leftEndpoint, left.semanticProfile());
            CanonicalAlloyPipeline.Prepared rebuiltRight = rebuild(
                    visitor, rightEndpoint, right.semanticProfile());
            requireSameEndpoint("left", left, rebuiltLeft);
            requireSameEndpoint("right", right, rebuiltRight);

            String detail = "exact-source-sha256;parser-resolved-root-local-"
                    + "declaration-identity;production-masg-and-canonical-reconstruction";
            String leftIdentity = String.join("\n",
                    leftEndpoint.declarationIdentity,
                    left.canonicalObservation().digest(),
                    left.semanticProfile().fingerprint());
            String rightIdentity = String.join("\n",
                    rightEndpoint.declarationIdentity,
                    right.canonicalObservation().digest(),
                    right.semanticProfile().fingerprint());
            String endpointPair = leftIdentity.compareTo(rightIdentity) <= 0
                    ? leftIdentity + "\n---\n" + rightIdentity
                    : rightIdentity + "\n---\n" + leftIdentity;
            String digest = AugmentationDigests.sha256(String.join("\n",
                    VERSION,
                    sourceSha256,
                    endpointPair,
                    detail));
            return new Evidence(
                    sourceSha256,
                    leftEndpoint.declarationIdentity,
                    rightEndpoint.declarationIdentity,
                    left.canonicalObservation().digest(),
                    right.canonicalObservation().digest(),
                    detail,
                    digest);
        } catch (edu.mit.csail.sdg.alloy4.Err exception) {
            throw new IllegalArgumentException(
                    "Adaptive endpoint source correspondence could not be parsed",
                    exception);
        }
    }

    private static Endpoint endpoint(
            CompModule module,
            String expressionSource,
            String side) throws edu.mit.csail.sdg.alloy4.Err {
        Expr expression = CompUtil.parseOneExpression_fromString(
                module, expressionSource).deNOP();
        if (!(expression instanceof ExprCall)) {
            throw new IllegalArgumentException(
                    "Adaptive local " + side + " endpoint must be a parser-resolved "
                            + "zero-arity predicate or function call");
        }
        ExprCall call = (ExprCall) expression;
        if (!call.args.isEmpty()) {
            throw new IllegalArgumentException(
                    "Adaptive local endpoint calls with arguments are not yet admitted");
        }
        Func declaration = call.fun;
        if (!module.getAllFunc().contains(declaration)) {
            throw new IllegalArgumentException(
                    "Adaptive local " + side + " endpoint resolves to an imported "
                            + "or non-root declaration: " + declaration.label);
        }
        String label = Objects.toString(declaration.label, "").trim();
        if (label.isEmpty()) {
            throw new IllegalArgumentException(
                    "Adaptive endpoint call has no declaration identity");
        }
        return new Endpoint(
                label,
                selectorCandidates(label),
                declaration.isPred
                        ? CallSymbol.Kind.FORMULA : CallSymbol.Kind.EXPRESSION,
                declaration.count());
    }

    private static CanonicalAlloyPipeline.Prepared rebuild(
            MASGVisitor visitor,
            Endpoint endpoint,
            is.fivefivefive.CanDis.theory.SemanticProfile profile) {
        for (String selector : endpoint.candidates) {
            Integer forestId;
            try {
                forestId = visitor.getForestId(
                        selector, endpoint.kind, endpoint.arity);
            } catch (IllegalStateException notThisAlias) {
                continue;
            }
            if (forestId == null) {
                continue;
            }
            Multigraph graph = visitor.getForest().get(forestId);
            if (graph == null) {
                throw new IllegalStateException(
                        "Parser-resolved endpoint has no MASG graph: " + selector);
            }
            return CanonicalAlloyPipeline.prepare(graph, profile);
        }
        throw new IllegalArgumentException(
                "Parser-resolved endpoint is not a local MASG predicate/function: "
                        + endpoint.declarationIdentity);
    }

    private static void requireSameEndpoint(
            String side,
            CanonicalAlloyPipeline.Prepared claimed,
            CanonicalAlloyPipeline.Prepared rebuilt) {
        try {
            if (CanonicalAlloyPipeline.distance(claimed, rebuilt) != 0
                    || !claimed.canonicalObservation().equivalentTo(
                            rebuilt.canonicalObservation())) {
                throw new IllegalArgumentException(
                        "Adaptive " + side + " prepared endpoint does not correspond "
                                + "to its parser-resolved Alloy declaration");
            }
        } catch (IllegalStateException mismatch) {
            throw new IllegalArgumentException(
                    "Adaptive " + side + " source and prepared endpoint disagree",
                    mismatch);
        }
    }

    private static List<String> selectorCandidates(String declarationLabel) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(declarationLabel);
        candidates.add(stripThis(declarationLabel));
        candidates.removeIf(String::isBlank);
        return List.copyOf(candidates);
    }

    private static String stripThis(String value) {
        return value.startsWith("this/") ? value.substring("this/".length()) : value;
    }

    private static final class Endpoint {
        private final String declarationIdentity;
        private final List<String> candidates;
        private final CallSymbol.Kind kind;
        private final int arity;

        private Endpoint(
                String label,
                List<String> candidates,
                CallSymbol.Kind kind,
                int arity) {
            this.declarationIdentity = label;
            this.candidates = candidates;
            this.kind = kind;
            this.arity = arity;
        }
    }
}

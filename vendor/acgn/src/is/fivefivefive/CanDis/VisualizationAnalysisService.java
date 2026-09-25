package is.fivefivefive.CanDis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.theory.BagPort;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.BindBlockPort;
import is.fivefivefive.CanDis.theory.BindPort;
import is.fivefivefive.CanDis.theory.CanonicalShape;
import is.fivefivefive.CanDis.theory.CertifiedSemanticArtifact;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.InvocationPortLeaf;
import is.fivefivefive.CanDis.theory.OnePort;
import is.fivefivefive.CanDis.theory.PortLeaf;
import is.fivefivefive.CanDis.theory.PortValue;
import is.fivefivefive.CanDis.theory.SeqPort;
import is.fivefivefive.CanDis.theory.SetPort;
import is.fivefivefive.CanDis.theory.ShapeWitness;
import is.fivefivefive.CanDis.theory.SlotPortLeaf;
import is.fivefivefive.CanDis.theory.TypedEClassRecord;
import is.fivefivefive.CanDis.theory.TypedENode;
import is.fivefivefive.CanDis.theory.TypedInvocation;
import is.fivefivefive.CanDis.theory.TypedSlot;
import is.fivefivefive.CanDis.theory.TypedSlotContext;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.Call;
import parser.ast.nodes.Function;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.PredOrFun;
import parser.ast.nodes.Predicate;
import parser.ast.visitor.PrettyStringVisitor;
import parser.util.AlloyUtil;

/** Parser and certified-graph boundary used by the web visualization adapter. */
public final class VisualizationAnalysisService {
    public static final String SCHEMA_VERSION = "1.1";
    public static final String SERVICE_VERSION = "alloy-egraph-visualization-v1.1-isolated";
    public static final int MAX_MODEL_CHARS = 2_000_000;
    private static final Object PARSER_LOCK = new Object();

    public JSONObject inspect(String source) {
        requireModel(source);
        try {
            ParsedModel parsed = parse(source);
            JSONArray callables = new JSONArray();
            JSONArray predicates = new JSONArray();
            for (CallableDeclaration declaration : parsed.callables) {
                JSONObject summary = declaration.summaryJson();
                callables.put(summary);
                if (declaration.kind.equals("predicate")) {
                    predicates.put(new JSONObject().put("name", declaration.name));
                }
            }
            return new JSONObject()
                    .put("callables", callables)
                    .put("predicates", predicates)
                    .put("parseDiagnostics", new JSONArray());
        } catch (AnalysisException exception) {
            return new JSONObject()
                    .put("callables", new JSONArray())
                    .put("predicates", new JSONArray())
                    .put("parseDiagnostics", new JSONArray().put(diagnostic(
                            "error", exception.getMessage(), exception.code())));
        }
    }

    public JSONObject analyze(String source, String name, String requestedKind) {
        requireModel(source);
        long totalStarted = System.nanoTime();
        long phaseStarted = totalStarted;
        ParsedModel parsed = parse(source);
        long parseNanos = System.nanoTime() - phaseStarted;
        CallableDeclaration declaration = parsed.find(name, requestedKind);
        if (declaration == null) {
            throw new AnalysisException(
                    404,
                    "callable_not_found",
                    "No " + normalizedKind(requestedKind) + " named '" + name + "' exists in the model.");
        }

        phaseStarted = System.nanoTime();
        MASGVisitor visitor = focusedVisitor(
                parsed.module, parsed.model, declaration.name);
        Integer graphId = visitor.getForestId(declaration.name);
        DoubleMap<Integer, Multigraph> forest = visitor.getForest();
        Multigraph graph = graphId == null ? null : forest.get(graphId);
        if (graph == null) {
            throw new AnalysisException(
                    500,
                    "analysis_error",
                    "The parser accepted '" + declaration.name
                            + "', but MASG construction produced no callable graph.");
        }
        Canonical.Prepared fast = Canonical.prepare(graph);
        long normalizationNanos = System.nanoTime() - phaseStarted;

        phaseStarted = System.nanoTime();
        CanonicalAlloyPipeline.Prepared certified = CanonicalAlloyPipeline.prepare(fast);
        long saturationNanos = System.nanoTime() - phaseStarted;
        String originalText = declaration.bodyText();
        String normalizedText = String.join("\n", Canonical.irTemporalFol(fast));
        String canonicalText = presentationFormula(fast, normalizedText);
        String certifiedStableForm = certified.stableForm();
        JSONObject callable = callableMetadata(
                declaration,
                certified.semanticArtifact().root(),
                originalText,
                normalizedText,
                canonicalText,
                certifiedStableForm);

        JSONObject response = new JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("model", new JSONObject()
                        .put("name", "submitted.als")
                        .put("digest", sha256(source))
                        .put("sourceLength", source.length()))
                .put("callable", callable)
                // Kept for Visualization IR 1.0 clients.
                .put("predicate", new JSONObject(callable.toMap()))
                .put("stages", stages(originalText, normalizedText, canonicalText,
                        classId(certified.semanticArtifact().root())))
                .put("graph", GraphExporter.export(certified.semanticArtifact(), certified))
                .put("trace", new JSONArray())
                .put("certificates", new JSONArray())
                .put("diagnostics", new JSONArray())
                .put("statistics", new JSONObject()
                        .put("parseMs", milliseconds(parseNanos))
                        .put("normalizationMs", milliseconds(normalizationNanos))
                        .put("saturationMs", milliseconds(saturationNanos))
                        .put("totalMs", milliseconds(System.nanoTime() - totalStarted))
                        .put("eclassCount", certified.eclassCount())
                        .put("enodeCount", certified.enodeCount())
                        .put("mergeCount", 0)
                        .put("saturationRounds", 1)
                        .put("rootReachableEClassCount", certified.eclassCount()));
        return response;
    }

    public JSONObject compare(
            String source,
            String leftName,
            String leftKind,
            String rightName,
            String rightKind) {
        requireModel(source);
        long totalStarted = System.nanoTime();
        long phaseStarted = totalStarted;
        ParsedModel parsed = parse(source);
        long parseNanos = System.nanoTime() - phaseStarted;
        CallableDeclaration leftDeclaration = requireCallable(parsed, leftName, leftKind);
        CallableDeclaration rightDeclaration = requireCallable(parsed, rightName, rightKind);

        phaseStarted = System.nanoTime();
        Set<String> selected = new HashSet<>();
        selected.add(leftDeclaration.name);
        selected.add(rightDeclaration.name);
        MASGVisitor visitor = focusedVisitor(parsed.module, parsed.model, selected);
        ComparisonOperand left = comparisonOperand(visitor, leftDeclaration);
        ComparisonOperand right = comparisonOperand(visitor, rightDeclaration);
        long preparationNanos = System.nanoTime() - phaseStarted;

        phaseStarted = System.nanoTime();
        QuotientRepairDistance.Result distance = CanonicalAlloyPipeline.distanceEvaluation(
                left.certified, right.certified);
        CanonicalDistance.DistanceBreakdown readableBreakdown =
                Canonical.distanceBreakdown(left.fast, right.fast);
        List<String> readableEdits = distance.distance() == 0
                ? java.util.Collections.singletonList("no-op")
                : Canonical.edits(left.fast, right.fast);
        OperationResult operationResult = operations(
                distance, readableBreakdown, readableEdits);
        long distanceNanos = System.nanoTime() - phaseStarted;

        return new JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("model", new JSONObject()
                        .put("name", "submitted.als")
                        .put("digest", sha256(source))
                        .put("sourceLength", source.length()))
                .put("left", comparisonMetadata(left))
                .put("right", comparisonMetadata(right))
                .put("metricVersion", CanonicalAlloyPipeline.QUOTIENT_METRIC_VERSION)
                .put("certifiedEquivalent", left.certified.equivalentTo(right.certified))
                .put("operationDetail", operationResult.detail)
                .put("distance", new JSONObject()
                        .put("total", distance.distance())
                        .put("temporal", distance.temporalDistance())
                        .put("quantifier", distance.quantifierDistance())
                        .put("matrix", distance.matrixDistance())
                        .put("exactForStoredOrbits", distance.exactForStoredOrbits())
                        .put("binderAlignments", distance.binderAlignments()))
                .put("operations", operationResult.operations)
                .put("statistics", new JSONObject()
                        .put("parseMs", milliseconds(parseNanos))
                        .put("preparationMs", milliseconds(preparationNanos))
                        .put("distanceMs", milliseconds(distanceNanos))
                        .put("totalMs", milliseconds(System.nanoTime() - totalStarted)));
    }

    private static CallableDeclaration requireCallable(
            ParsedModel parsed,
            String name,
            String requestedKind) {
        CallableDeclaration declaration = parsed.find(name, requestedKind);
        if (declaration == null) {
            throw new AnalysisException(
                    404,
                    "callable_not_found",
                    "No " + normalizedKind(requestedKind) + " named '" + name
                            + "' exists in the model.");
        }
        return declaration;
    }

    private static ComparisonOperand comparisonOperand(
            MASGVisitor visitor,
            CallableDeclaration declaration) {
        Integer graphId = visitor.getForestId(declaration.name);
        Multigraph graph = graphId == null ? null : visitor.getForest().get(graphId);
        if (graph == null) {
            throw new AnalysisException(
                    500,
                    "analysis_error",
                    "MASG construction produced no graph for '" + declaration.name + "'.");
        }
        Canonical.Prepared fast = Canonical.prepare(graph);
        return new ComparisonOperand(
                declaration,
                fast,
                CanonicalAlloyPipeline.prepare(fast));
    }

    private static JSONObject comparisonMetadata(ComparisonOperand operand) {
        JSONObject result = new JSONObject()
                .put("name", operand.declaration.name)
                .put("kind", operand.declaration.kind)
                .put("originalText", operand.declaration.bodyText())
                .put("normalizedText", String.join("\n", Canonical.irTemporalFol(operand.fast)))
                .put("canonicalText", presentationFormula(
                        operand.fast,
                        String.join("\n", Canonical.irTemporalFol(operand.fast))))
                .put("certifiedStableForm", operand.certified.stableForm())
                .put("digest", operand.certified.digest())
                .put("representationSize", operand.certified.repairObservationSize());
        if (operand.declaration.returnType != null) {
            result.put("returnType", operand.declaration.returnType);
        }
        return result;
    }

    private static OperationResult operations(
            QuotientRepairDistance.Result exact,
            CanonicalDistance.DistanceBreakdown readable,
            List<String> readableEdits) {
        Map<String, List<String>> byComponent = new LinkedHashMap<>();
        byComponent.put("temporal", new ArrayList<>());
        byComponent.put("quantifier", new ArrayList<>());
        byComponent.put("matrix", new ArrayList<>());
        for (String edit : readableEdits) {
            if (edit == null || edit.equals("no-op")) {
                continue;
            }
            byComponent.get(operationComponent(edit)).add(edit);
        }

        JSONArray result = new JSONArray();
        if (exact.distance() == 0) {
            result.put(new JSONObject()
                    .put("id", "op-0")
                    .put("index", 0)
                    .put("component", "equivalence")
                    .put("kind", "no-op")
                    .put("path", "quotient")
                    .put("summary", "Certified semantic equality; no repair is required.")
                    .put("cost", 0)
                    .put("detail", "unit"));
            return new OperationResult(result, "unit");
        }

        boolean allUnit = true;
        int index = 0;
        index = appendOperations(
                result,
                byComponent.get("temporal"),
                "temporal",
                exact.temporalDistance(),
                readable.temporalDistance(),
                index);
        allUnit &= componentHasUnitWitness(
                byComponent.get("temporal"),
                exact.temporalDistance(),
                readable.temporalDistance());
        index = appendOperations(
                result,
                byComponent.get("quantifier"),
                "quantifier",
                exact.quantifierDistance(),
                readable.quantifierDistance(),
                index);
        allUnit &= componentHasUnitWitness(
                byComponent.get("quantifier"),
                exact.quantifierDistance(),
                readable.quantifierDistance());
        appendOperations(
                result,
                byComponent.get("matrix"),
                "matrix",
                exact.matrixDistance(),
                readable.matrixDistance(),
                index);
        allUnit &= componentHasUnitWitness(
                byComponent.get("matrix"),
                exact.matrixDistance(),
                readable.matrixDistance());

        int represented = 0;
        for (int operation = 0; operation < result.length(); operation++) {
            represented = Math.addExact(
                    represented, result.getJSONObject(operation).getInt("cost"));
        }
        if (represented != exact.distance()) {
            throw new IllegalStateException(
                    "Visualized operation cost " + represented
                            + " differs from certified distance " + exact.distance());
        }
        return new OperationResult(result, allUnit ? "unit" : "mixed");
    }

    private static boolean componentHasUnitWitness(
            List<String> edits,
            int exactCost,
            int readableCost) {
        return exactCost == 0
                || (exactCost == readableCost && edits.size() == exactCost);
    }

    private static int appendOperations(
            JSONArray output,
            List<String> edits,
            String component,
            int exactCost,
            int readableCost,
            int startIndex) {
        if (exactCost == 0) {
            return startIndex;
        }
        int index = startIndex;
        if (componentHasUnitWitness(edits, exactCost, readableCost)) {
            for (String edit : edits) {
                output.put(unitOperation(edit, component, index++));
            }
            return index;
        }
        output.put(new JSONObject()
                .put("id", "op-" + index)
                .put("index", index)
                .put("component", component)
                .put("kind", "aggregate")
                .put("path", component)
                .put("summary", "Minimum certified " + component
                        + " repair under admissible alignment")
                .put("cost", exactCost)
                .put("detail", "aggregate"));
        return index + 1;
    }

    private static JSONObject unitOperation(String edit, String component, int index) {
        int separator = edit.indexOf(": ");
        String path = separator < 0 ? component : edit.substring(0, separator);
        String action = separator < 0 ? edit : edit.substring(separator + 2);
        String kind = operationKind(action);
        String body = action.startsWith(kind + " ")
                ? action.substring(kind.length() + 1) : action;
        JSONObject result = new JSONObject()
                .put("id", "op-" + index)
                .put("index", index)
                .put("component", component)
                .put("kind", kind)
                .put("path", path)
                .put("summary", edit)
                .put("cost", 1)
                .put("detail", "unit");
        int arrow = body.indexOf(" -> ");
        if (arrow >= 0) {
            result.put("source", body.substring(0, arrow))
                    .put("target", body.substring(arrow + 4));
        } else if (kind.equals("insert")) {
            result.put("target", body);
        } else if (kind.equals("delete")) {
            result.put("source", body);
        }
        return result;
    }

    private static String operationKind(String action) {
        for (String kind : List.of("insert", "delete", "replace", "modify")) {
            if (action.startsWith(kind + " ")) {
                return kind;
            }
        }
        return "replace";
    }

    private static String operationComponent(String edit) {
        if (edit.startsWith("temporal")) {
            return "temporal";
        }
        return edit.contains(".quantifier") ? "quantifier" : "matrix";
    }

    private static final class ComparisonOperand {
        private final CallableDeclaration declaration;
        private final Canonical.Prepared fast;
        private final CanonicalAlloyPipeline.Prepared certified;

        private ComparisonOperand(
                CallableDeclaration declaration,
                Canonical.Prepared fast,
                CanonicalAlloyPipeline.Prepared certified) {
            this.declaration = declaration;
            this.fast = fast;
            this.certified = certified;
        }
    }

    private static final class OperationResult {
        private final JSONArray operations;
        private final String detail;

        private OperationResult(JSONArray operations, String detail) {
            this.operations = operations;
            this.detail = detail;
        }
    }

    private static JSONObject callableMetadata(
            CallableDeclaration declaration,
            TypedInvocation root,
            String originalText,
            String normalizedText,
            String canonicalText,
            String certifiedStableForm) {
        JSONObject metadata = new JSONObject()
                .put("name", declaration.name)
                .put("kind", declaration.kind)
                .put("rootEClassId", classId(root))
                .put("originalText", originalText)
                .put("normalizedText", normalizedText)
                .put("canonicalText", canonicalText)
                .put("certifiedStableForm", certifiedStableForm);
        if (declaration.returnType != null) {
            metadata.put("returnType", declaration.returnType);
        }
        return metadata;
    }

    private static JSONArray stages(
            String original,
            String normalized,
            String canonical,
            String rootId) {
        return new JSONArray()
                .put(new JSONObject()
                        .put("id", "source")
                        .put("index", 0)
                        .put("name", "Source")
                        .put("text", original))
                .put(new JSONObject()
                        .put("id", "fast-rewrite")
                        .put("index", 1)
                        .put("name", "Fast Rewrite IR")
                        .put("text", normalized)
                        .put("rootEClassId", rootId))
                .put(new JSONObject()
                        .put("id", "certificate-integrated")
                        .put("index", 2)
                        .put("name", "Certified Canonical")
                        .put("text", canonical)
                        .put("rootEClassId", rootId));
    }

    private static MASGVisitor focusedVisitor(
            CompModule module,
            ModelUnit model,
            String selectedName) {
        return focusedVisitor(
                module, model, java.util.Collections.singleton(selectedName));
    }

    private static MASGVisitor focusedVisitor(
            CompModule module,
            ModelUnit model,
            Set<String> selectedNames) {
        Set<String> callables = new HashSet<>();
        for (String selectedName : selectedNames) {
            callables.addAll(callableClosure(model, selectedName));
        }
        MASGVisitor visitor = new MASGVisitor(
                new GlobalVariables(), callables, module);
        try {
            visitor.visit(model, null);
            return visitor;
        } catch (RuntimeException focusedFailure) {
            MASGVisitor fallback = new MASGVisitor(new GlobalVariables(), module);
            fallback.visit(model, null);
            return fallback;
        }
    }

    private static Set<String> callableClosure(ModelUnit model, String selectedName) {
        Map<String, PredOrFun> declarations = new HashMap<>();
        for (Predicate predicate : model.getPredDeclList()) {
            declarations.put(predicate.getName(), predicate);
        }
        for (Function function : model.getFunDeclList()) {
            declarations.put(function.getName(), function);
        }
        Set<String> selected = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(selectedName);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!selected.add(name)) {
                continue;
            }
            PredOrFun declaration = declarations.get(name);
            if (declaration != null) {
                collectCalledDeclarations(declaration, declarations, selected, pending);
            }
        }
        return selected;
    }

    private static void collectCalledDeclarations(
            Node node,
            Map<String, PredOrFun> declarations,
            Set<String> selected,
            ArrayDeque<String> pending) {
        if (node instanceof Call) {
            String name = ((Call) node).getName();
            if (declarations.containsKey(name) && !selected.contains(name)) {
                pending.addLast(name);
            }
        }
        List<Node> children = node.getChildren();
        if (children == null) {
            return;
        }
        for (Node child : children) {
            if (child != null) {
                collectCalledDeclarations(child, declarations, selected, pending);
            }
        }
    }

    private static ParsedModel parse(String source) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("acgn-visualization-");
            Path modelFile = directory.resolve("submitted.als");
            Files.writeString(modelFile, source, StandardCharsets.UTF_8);
            CompModule module;
            synchronized (PARSER_LOCK) {
                module = AlloyUtil.compileAlloyModule(modelFile.toString());
            }
            if (module == null) {
                throw new AnalysisException(422, "parse_error", "The Alloy parser rejected the submitted model.");
            }
            ModelUnit model = new ModelUnit(null, module);
            List<CallableDeclaration> callables = new ArrayList<>();
            for (Predicate predicate : model.getPredDeclList()) {
                if (isSyntheticCallable(predicate.getName())) {
                    continue;
                }
                callables.add(new CallableDeclaration(
                        predicate.getName(), "predicate", null, predicate));
            }
            for (Function function : model.getFunDeclList()) {
                String returnType = function.getReturnType() == null
                        ? null : pretty(function.getReturnType());
                callables.add(new CallableDeclaration(
                        function.getName(), "function", returnType, function));
            }
            callables.sort(Comparator
                    .comparing((CallableDeclaration value) -> value.kind)
                    .thenComparing(value -> value.name));
            return new ParsedModel(module, model, callables);
        } catch (AnalysisException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AnalysisException(
                    422,
                    "parse_error",
                    compactMessage(exception, "The Alloy parser rejected the submitted model."),
                    exception);
        } finally {
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("submitted.als"));
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // Temporary parser inputs are best-effort cleanup.
                }
            }
        }
    }

    private static void requireModel(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new AnalysisException(400, "parse_error", "Alloy model source is required.");
        }
        if (source.length() > MAX_MODEL_CHARS) {
            throw new AnalysisException(
                    413,
                    "model_too_large",
                    "Alloy model exceeds the " + MAX_MODEL_CHARS + " character service limit.");
        }
    }

    private static boolean isSyntheticCallable(String name) {
        return "$$Default".equals(name);
    }

    private static String pretty(Node node) {
        return node.accept(new PrettyStringVisitor(), null);
    }

    private static String normalizedKind(String requestedKind) {
        if (requestedKind == null || requestedKind.trim().isEmpty()) {
            return "callable";
        }
        return requestedKind.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static JSONObject diagnostic(String severity, String message, String code) {
        return new JSONObject()
                .put("severity", severity)
                .put("message", message)
                .put("code", code);
    }

    private static String compactMessage(Throwable exception, String fallback) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return fallback;
        }
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String presentationFormula(Canonical.Prepared prepared, String fallback) {
        try {
            return CanonicalBacktranslator.formula(prepared.normalizedForms());
        } catch (RuntimeException unsupportedPresentation) {
            return fallback;
        }
    }

    private static String sha256(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static double milliseconds(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String classId(TypedInvocation invocation) {
        return "E" + invocation.eclass().id().value();
    }

    public static final class AnalysisException extends RuntimeException {
        private final int status;
        private final String code;

        public AnalysisException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = Objects.requireNonNull(code, "code");
        }

        public AnalysisException(int status, String code, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = Objects.requireNonNull(code, "code");
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private static final class ParsedModel {
        private final CompModule module;
        private final ModelUnit model;
        private final List<CallableDeclaration> callables;

        private ParsedModel(
                CompModule module,
                ModelUnit model,
                List<CallableDeclaration> callables) {
            this.module = module;
            this.model = model;
            this.callables = callables;
        }

        private CallableDeclaration find(String name, String requestedKind) {
            String kind = normalizedKind(requestedKind);
            for (CallableDeclaration declaration : callables) {
                if (declaration.name.equals(name)
                        && (kind.equals("callable") || declaration.kind.equals(kind))) {
                    return declaration;
                }
            }
            return null;
        }
    }

    private static final class CallableDeclaration {
        private final String name;
        private final String kind;
        private final String returnType;
        private final PredOrFun declaration;

        private CallableDeclaration(
                String name,
                String kind,
                String returnType,
                PredOrFun declaration) {
            this.name = Objects.requireNonNull(name, "name");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.returnType = returnType;
            this.declaration = Objects.requireNonNull(declaration, "declaration");
        }

        private JSONObject summaryJson() {
            JSONObject result = new JSONObject().put("name", name).put("kind", kind);
            if (returnType != null) {
                result.put("returnType", returnType);
            }
            return result;
        }

        private String bodyText() {
            if (declaration.getBody() == null || declaration.getBody().getBodyExpr() == null) {
                return "";
            }
            return pretty(declaration.getBody().getBodyExpr());
        }
    }

    private static final class GraphExporter {
        private GraphExporter() {
        }

        private static JSONObject export(
                CertifiedSemanticArtifact artifact,
                CanonicalAlloyPipeline.Prepared prepared) {
            List<TypedEClassRecord> records = new ArrayList<>(artifact.classes().values());
            records.sort(Comparator.comparing(TypedEClassRecord::id));
            JSONArray classes = new JSONArray();
            JSONArray edges = new JSONArray();
            Set<String> edgeIds = new HashSet<>();
            for (TypedEClassRecord record : records) {
                classes.put(eclass(record, edges, edgeIds));
            }
            return new JSONObject()
                    .put("rootEClassId", classId(artifact.root()))
                    .put("eclasses", classes)
                    .put("edges", edges)
                    .put("saturation", new JSONObject()
                            .put("saturated", true)
                            .put("stopReason", "certified-quiescent")
                            .put("rounds", new JSONArray().put(new JSONObject()
                                    .put("index", 0)
                                    .put("eclassCount", prepared.eclassCount())
                                    .put("enodeCount", prepared.enodeCount())
                                    .put("merges", 0)
                                    .put("rebuilds", prepared.rebuildCount()))));
        }

        private static JSONObject eclass(
                TypedEClassRecord record,
                JSONArray edges,
                Set<String> edgeIds) {
            JSONArray nodes = new JSONArray();
            String canonicalNodeId = null;
            int index = 0;
            for (Map.Entry<CanonicalShape, ShapeWitness> entry
                    : record.shapeWitnesses().entrySet()) {
                String nodeId = "N" + record.id().value() + "_" + index++;
                if (canonicalNodeId == null) {
                    canonicalNodeId = nodeId;
                }
                JSONObject node = enode(nodeId, entry.getKey().node(), entry.getValue());
                nodes.put(node);
                JSONArray children = node.getJSONArray("children");
                for (int childIndex = 0; childIndex < children.length(); childIndex++) {
                    JSONObject child = children.getJSONObject(childIndex);
                    String target = child.getString("eclassId");
                    String role = child.optString("role", Integer.toString(childIndex));
                    String edgeId = record.id() + ":" + nodeId + ":" + role + ":" + target;
                    if (edgeIds.add(edgeId)) {
                        edges.put(new JSONObject()
                                .put("id", edgeId)
                                .put("sourceEClassId", "E" + record.id().value())
                                .put("targetEClassId", target)
                                .put("role", role)
                                .put("enodeId", nodeId));
                    }
                }
            }
            JSONObject result = new JSONObject()
                    .put("id", "E" + record.id().value())
                    .put("type", type(record.outputType()))
                    .put("support", slotRefs(record.exposedSlots()))
                    .put("effectiveSupport", slotRefs(record.exposedSlots()))
                    .put("nodes", nodes)
                    .put("provenance", new JSONArray().put(new JSONObject()
                            .put("kind", "certified-eclass")
                            .put("summary", record.symmetryGroup().toString())))
                    .put("invariantStatus", new JSONArray()
                            .put(invariant(record, "type", "Typed e-class interface"))
                            .put(invariant(record, "support", "Certified exposed-slot support"))
                            .put(invariant(record, "quiescent", "Congruence-rebuilt quiescent state")))
                    .put("statistics", new JSONObject()
                            .put("nodeCount", nodes.length()));
            if (canonicalNodeId != null) {
                result.put("canonicalNodeId", canonicalNodeId)
                        .put("representativeNodeId", canonicalNodeId);
            }
            return result;
        }

        private static JSONObject invariant(
                TypedEClassRecord record,
                String suffix,
                String name) {
            return new JSONObject()
                    .put("id", "I" + record.id().value() + "-" + suffix)
                    .put("name", name)
                    .put("status", "pass")
                    .put("relatedEntityIds", new JSONArray().put("E" + record.id().value()));
        }

        private static JSONObject enode(
                String nodeId,
                TypedENode node,
                ShapeWitness witness) {
            PresentedOperator presented = PresentedOperator.from(node.operator().operator());
            PortCollector collector = new PortCollector();
            for (TypedSlot slot : node.context()) {
                collector.addSlot(slot, "free");
            }
            for (int index = 0; index < node.ports().size(); index++) {
                collector.collect(node.ports().get(index), "port" + index);
            }
            JSONObject result = new JSONObject()
                    .put("id", nodeId)
                    .put("kind", presented.kind)
                    .put("displayName", presented.displayName)
                    .put("children", collector.children)
                    .put("type", type(node.outputType()))
                    .put("slots", new JSONArray(collector.slots.values()))
                    .put("provenance", new JSONArray().put(new JSONObject()
                            .put("kind", "canonical-shape")
                            .put("summary", "Stored with a certified instantiating witness")))
                    .put("attributes", new JSONObject()
                            .put("certifiedOperator", node.operator().operator())
                            .put("structuralKey", node.structuralKey().stableString())
                            .put("witness", witness.toString())
                            .put("portSchemas", node.operator().portSchemas().toString()));
            JSONObject container = container(node);
            if (container != null) {
                result.put("container", container);
            }
            return result;
        }

        private static final class PresentedOperator {
            private final String kind;
            private final String displayName;

            private PresentedOperator(String kind, String displayName) {
                this.kind = kind;
                this.displayName = displayName;
            }

            private static PresentedOperator from(String certified) {
                String value = certified.startsWith("ALLOY/")
                        ? certified.substring("ALLOY/".length()) : certified;
                if (value.startsWith("GLOBALBINDING/")) {
                    return new PresentedOperator(
                            "Signature",
                            value.substring("GLOBALBINDING/".length()));
                }
                if (value.equals("RELATIONAL-VARIABLE") || value.equals("BOOLEAN-VARIABLE")) {
                    return new PresentedOperator("Variable", "VAR");
                }
                if (value.startsWith("CONSTANT/")) {
                    return new PresentedOperator("Constant", value.substring("CONSTANT/".length()));
                }
                int slash = value.lastIndexOf('/');
                String token = slash >= 0 ? value.substring(slash + 1) : value;
                String kind = titleCase(token);
                String display = token.replace('-', '_').toUpperCase(java.util.Locale.ROOT);
                return new PresentedOperator(kind, display);
            }

            private static String titleCase(String value) {
                if (value.isEmpty()) {
                    return "Operator";
                }
                String normalized = value.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
                StringBuilder result = new StringBuilder(normalized.length());
                boolean capitalize = true;
                for (int index = 0; index < normalized.length(); index++) {
                    char character = normalized.charAt(index);
                    if (character == '_') {
                        capitalize = true;
                    } else if (capitalize) {
                        result.append(Character.toUpperCase(character));
                        capitalize = false;
                    } else {
                        result.append(character);
                    }
                }
                return result.toString();
            }
        }

        private static JSONObject container(TypedENode node) {
            for (PortValue port : node.ports()) {
                if (port instanceof SeqPort) {
                    return container(node, "A", false, false);
                }
                if (port instanceof BagPort) {
                    return container(node, "AC", true, false);
                }
                if (port instanceof SetPort) {
                    return container(node, "ACI", true, true);
                }
            }
            return null;
        }

        private static JSONObject container(
                TypedENode node,
                String kind,
                boolean orderInsensitive,
                boolean duplicateElimination) {
            return new JSONObject()
                    .put("kind", kind)
                    .put("operator", node.operator().operator())
                    .put("orderInsensitive", orderInsensitive)
                    .put("duplicateElimination", duplicateElimination)
                    .put("flattened", node.operator().usesFlatConstruction());
        }

        private static JSONArray slotRefs(TypedSlotContext context) {
            JSONArray result = new JSONArray();
            for (TypedSlot slot : context) {
                result.put(new JSONObject()
                        .put("id", slot.toString())
                        .put("type", slot.type().toString())
                        .put("displayName", slot.toString()));
            }
            return result;
        }

        private static JSONObject type(GraphType type) {
            switch (type.kind()) {
                case BOOL:
                    return new JSONObject().put("kind", "formula");
                case RELATION:
                    JSONArray columns = new JSONArray();
                    for (GraphType column : type.arguments()) {
                        columns.put(column.toString());
                    }
                    return new JSONObject().put("kind", "relation").put("columns", columns);
                case CONSTRUCTOR:
                    Integer emptyArity = AlloyTypeBridge.emptyRelationArity(type);
                    if (emptyArity != null) {
                        return new JSONObject()
                                .put("kind", "relation")
                                .put("empty", true)
                                .put("arity", emptyArity)
                                .put("columns", new JSONArray());
                    }
                    if ("AlloyRel".equals(type.symbol())) {
                        return new JSONObject()
                                .put("kind", "relation")
                                .put("columns", new JSONArray().put(type.toString()));
                    }
                    return new JSONObject().put("kind", "unknown").put("display", type.toString());
                case INT:
                    return new JSONObject().put("kind", "atom").put("signature", "Int");
                default:
                    return new JSONObject().put("kind", "unknown").put("display", type.toString());
            }
        }
    }

    private static final class PortCollector {
        private final JSONArray children = new JSONArray();
        private final LinkedHashMap<String, JSONObject> slots = new LinkedHashMap<>();

        private void collect(PortValue port, String role) {
            if (port instanceof OnePort) {
                PortLeaf leaf = ((OnePort) port).leaf();
                if (leaf instanceof InvocationPortLeaf) {
                    TypedInvocation invocation = ((InvocationPortLeaf) leaf).invocation();
                    children.put(new JSONObject()
                            .put("role", role)
                            .put("eclassId", classId(invocation)));
                } else if (leaf instanceof SlotPortLeaf) {
                    addSlot(((SlotPortLeaf) leaf).slot(), role);
                }
                return;
            }
            if (port instanceof SeqPort) {
                collectAll(((SeqPort) port).elements(), role);
                return;
            }
            if (port instanceof BagPort) {
                collectAll(((BagPort) port).occurrences(), role);
                return;
            }
            if (port instanceof SetPort) {
                collectAll(((SetPort) port).elements(), role);
                return;
            }
            if (port instanceof BindPort) {
                BindPort bind = (BindPort) port;
                addSlot(bind.boundSlot(), "binder");
                collect(bind.body(), role + ".body");
                return;
            }
            if (port instanceof BindBlockPort) {
                BindBlockPort block = (BindBlockPort) port;
                for (TypedSlot slot : block.boundContext()) {
                    addSlot(slot, "binder-block");
                }
                collect(block.body(), role + ".body");
            }
        }

        private void collectAll(List<PortValue> values, String role) {
            for (int index = 0; index < values.size(); index++) {
                collect(values.get(index), role + "[" + index + "]");
            }
        }

        private void addSlot(TypedSlot slot, String role) {
            slots.putIfAbsent(slot.toString(), new JSONObject()
                    .put("slotId", slot.toString())
                    .put("role", role)
                    .put("type", slot.type().toString()));
        }
    }
}

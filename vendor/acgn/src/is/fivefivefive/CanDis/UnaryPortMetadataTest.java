package is.fivefivefive.CanDis;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.metric.RepairProjection;
import is.fivefivefive.CanDis.metric.RepairView.ContainerKind;

/** Source-independent shape controls and source-bound native replay regressions. */
public final class UnaryPortMetadataTest {
    private static int checks;
    private static final Method PORT = method(RepairProjection.class, "containerKind", EGraphNode.class);
    private static final Method FAST = method(EGraphNode.class, "fastComparisonMatrix", EGraphNode.class);

    private static Method method(Class<?> type, String name, Class<?>... arguments) {
        try {
            Method result = type.getDeclaredMethod(name, arguments);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static EGraphNode relation() {
        EGraphNode result = new EGraphNode(1, Opcode.GLOBALBINDING, List.of(), false, 0, false, Metatype.ATOMIC);
        result.setSourceName("A");
        result.setSourceType("A");
        result.setExactAlloyType(ExactAlloyType.unaryRelation("A"));
        return result;
    }

    private static EGraphNode cardinal(Opcode opcode, boolean flexible, String source, EGraphNode... children) {
        EGraphNode result = new EGraphNode(2, opcode, List.of(children), false,
                flexible ? -1 : 1, flexible, Metatype.BOOLEAN);
        result.setSourceName(source);
        result.setSourceType("MIDDLENODE_" + source);
        result.setExactAlloyType(ExactAlloyType.boolType());
        return result;
    }

    private static ContainerKind port(EGraphNode node) throws Exception {
        return (ContainerKind) PORT.invoke(null, node);
    }

    private static void ineligible(EGraphNode node, String label) throws Exception {
        check(!node.hasUnaryCardinalityOperand(), label + " is not unary cardinality");
        check(port(node) == ContainerKind.SEQUENCE, label + " retains its existing sequence");
    }

    private static void shapeControls() throws Exception {
        for (Opcode opcode : List.of(Opcode.NO, Opcode.SOME, Opcode.ONE, Opcode.LONE)) {
            for (boolean flexible : List.of(false, true)) {
                for (String source : List.of("UNOPF_" + opcode, "UNOPF_NO")) {
                    EGraphNode child = relation();
                    EGraphNode original = cardinal(opcode, flexible, source, child);
                    long occurrence = original.getSourceOccurrenceLineage();
                    check(original.hasUnaryCardinalityOperand(), "typed direct or derived unary cardinality");
                    check(port(original) == ContainerKind.ONE, "legacy unary port must be ONE");
                    EGraphNode fast = (EGraphNode) FAST.invoke(null, original);
                    check(fast.getMaxArity() == 1 && !fast.isFlexibleArity(), "Fast fixed unary metadata");
                    check(fast.getSourceName() == null, "Fast existing source presentation");
                    check(fast.getChildren().get(0).getSourceOccurrenceLineage() == child.getSourceOccurrenceLineage(),
                            "Fast preserves operand occurrence");
                    check(original.getChildren().get(0) == child && original.getSourceOccurrenceLineage() == occurrence,
                            "projection does not replace original source objects");
                    check(original.isFlexibleArity() == flexible && original.getSourceName().equals(source),
                            "projection does not mutate original metadata");
                }
            }
            EGraphNode wrongResult = cardinal(opcode, true, "UNOPF_" + opcode, relation());
            wrongResult.setExactAlloyType(ExactAlloyType.intType());
            ineligible(wrongResult, "wrong result type");
            EGraphNode noResultType = cardinal(opcode, true, "UNOPF_" + opcode, relation());
            noResultType.setExactAlloyType(null);
            ineligible(noResultType, "missing result type");
            EGraphNode wrongSource = cardinal(opcode, true, "UNOPF_" + opcode, relation());
            wrongSource.setSourceType("foreign");
            ineligible(wrongSource, "wrong formula source");
            EGraphNode booleanChild = relation();
            booleanChild.setExactAlloyType(ExactAlloyType.boolType());
            ineligible(cardinal(opcode, true, "UNOPF_" + opcode, booleanChild), "Boolean operand");
            EGraphNode untypedChild = relation();
            untypedChild.setExactAlloyType(null);
            ineligible(cardinal(opcode, true, "UNOPF_" + opcode, untypedChild), "missing operand type");
            ineligible(cardinal(opcode, true, "UNOPF_" + opcode), "empty operands");
            ineligible(cardinal(opcode, true, "UNOPF_" + opcode, relation(), relation()), "multiple operands");
            for (Opcode declaration : List.of(Opcode.GENERICRELDECL, Opcode.DISJ, Opcode.VAR, Opcode.DISJVAR)) {
                EGraphNode group = new EGraphNode(3, declaration, List.of(relation()), false, -1, true, Metatype.CONTROL);
                group.setExactAlloyType(ExactAlloyType.unaryRelation("A"));
                ineligible(cardinal(opcode, true, "QT_FORMULA_" + opcode, group), "declaration group");
            }
        }
        for (Opcode opcode : List.of(Opcode.LIST, Opcode.FORALL, Opcode.EXISTS, Opcode.COMPREHENSION, Opcode.SUM)) {
            ineligible(cardinal(opcode, true, "fixture", relation()), "non-cardinality singleton " + opcode);
        }
        check(port(cardinal(Opcode.AND, true, "BOP_AND", relation())) == ContainerKind.SET, "SET policy stays first");
        check(port(cardinal(Opcode.IFF, true, "BOP_IFF", relation())) == ContainerKind.BAG, "BAG policy stays first");
    }

    private static void naturalControls(JSONObject identity, Path output, JSONArray rows) throws Exception {
        var prepared = LocalOwnerTraceTest.prepare(identity);
        for (int direction = 0; direction < 2; direction++) {
            var left = prepared.get(direction);
            var right = prepared.get(1 - direction);
            var fast = CanonicalRepairTraces.fast(left, right);
            var certified = CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(left), CanonicalAlloyPipeline.prepare(right));
            JSONObject order = new JSONObject();
            QueueProbe.orderedReplay(fast.distance(), certified.distance(),
                    () -> fast.replay(fast.json()), () -> certified.replay(certified.json()), order);
            var result = NativeOperationComparison.compare(left, right, fast, certified);
            String file = identity.getString("id") + "-" + direction + ".json";
            Files.writeString(output.resolve(file), result.evidence().toString(2), StandardOpenOption.CREATE_NEW);
            check(result.status() == NativeOperationComparison.Status.MATCHED,
                    "natural selected actions must match: " + identity.getString("id") + ":" + direction + " " + result.reason());
            JSONObject original = result.evidence().getJSONObject("diagnostic");
            for (String arm : List.of("fast", "certified")) {
                JSONObject bad = new JSONObject(original.toString());
                bad.getJSONObject(arm).put("distance", -1);
                check(NativeOperationComparison.compareCaptured(left, right, fast, certified, bad).status()
                        == NativeOperationComparison.Status.INVALID_REPLAY, "tampered cost rejected for " + arm);
            }
            JSONObject bad = new JSONObject(original.toString());
            boolean temporalOwner = !bad.has("nativeOperationEndpoints");
            if (!temporalOwner) {
                bad.getJSONArray("nativeOperationEndpoints").put(0, "foreign-source");
            } else {
                bad.getJSONObject("fast").getJSONObject("selectedTemporalReferencePayments")
                        .getJSONArray("source").getJSONObject(0).put("ownerPostorder", 999999);
            }
            var rejected = NativeOperationComparison.compareCaptured(left, right, fast, certified, bad);
            if (temporalOwner) {
                check(rejected.status() == NativeOperationComparison.Status.ERROR
                        && rejected.evidence().getString("errorType").equals("java.lang.IllegalArgumentException")
                        && rejected.evidence().getString("error").equals("Selected temporal payment source/owner/correspondence changed"),
                        "existing temporal owner exception retained");
            } else {
                check(rejected.status() == NativeOperationComparison.Status.INVALID_REPLAY, "foreign source endpoint rejected");
            }
            check(NativeOperationComparison.compareCaptured(left, right, fast, certified, original).status()
                    == NativeOperationComparison.Status.MATCHED, "untampered receipt still accepted");
            rows.put(new JSONObject().put("identity", identity).put("direction", direction)
                    .put("distance", fast.distance()).put("status", result.status()).put("replay", order).put("receipt", file));
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        Files.createDirectory(output);
        shapeControls();
        JSONArray rows = new JSONArray();
        for (Object value : new JSONObject(Files.readString(Path.of(args[0]))).getJSONArray("cases")) {
            naturalControls(((JSONObject) value).getJSONObject("identity"), output, rows);
        }
        Files.writeString(output.resolve("summary.json"), new JSONObject().put("checks", checks)
                .put("directions", rows).toString(2), StandardOpenOption.CREATE_NEW);
        System.out.println("UnaryPortMetadataTest: " + checks + " checks passed; " + rows.length() + " directions");
    }
}

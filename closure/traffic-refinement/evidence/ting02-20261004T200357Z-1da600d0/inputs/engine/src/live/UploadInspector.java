package live;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.*;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Private source-backed declaration inventory for authenticated .als uploads. */
public final class UploadInspector {
    private static final int MAX_INPUT = 2_097_152;
    private static final int MAX_SOURCE = 262_144;
    private UploadInspector() { }

    public static void main(String[] args) throws Exception {
        PrintStream wire = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        JSONObject response;
        try {
            byte[] bytes = System.in.readNBytes(MAX_INPUT + 1);
            if (bytes.length > MAX_INPUT) response = rejected("REQUEST_TOO_LARGE");
            else {
                String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                response = inspect(new JSONObject(json));
            }
        } catch (Throwable error) { response = rejected("INVALID_UPLOAD"); }
        wire.println(response);
    }

    public static JSONObject inspect(JSONObject request) {
        try {
            if (!request.keySet().equals(Set.of("source")) || !(request.get("source") instanceof String source)
                    || source.isBlank() || source.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(source)
                    || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE)
                return rejected("INVALID_UPLOAD");
            if (hasModuleMacro(source)) return rejected("UNSUPPORTED_MACRO");
            CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP, source);
            if (!ExerciseValidator.bundledDependencies(module)) return rejected("UNSUPPORTED_EXTERNAL_MODULE");
            List<Declaration> declarations = new ArrayList<>();
            Map<Func, String> names = new IdentityHashMap<>();
            Set<String> usedNames = new TreeSet<>();
            for (Func function : module.getAllFunc()) {
                if (BehaviorFeedback.isSyntheticCommand(function)) continue;
                String name = function.label.substring(function.label.lastIndexOf('/') + 1);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) return rejected("UNSUPPORTED_NAME");
                if (!usedNames.add(name)) return rejected("DUPLICATE_DECLARATION");
                int[] span = range(function.span(), source), body = range(function.getBody().span(), source);
                int keywordStart = span[0];
                if (!source.substring(keywordStart, span[1]).startsWith(function.isPred ? "pred" : "fun")
                        || body[0] < span[0] || body[1] > span[1]
                        || source.charAt(body[0]) != '{' || source.charAt(body[1] - 1) != '}')
                    return rejected("UNSUPPORTED_DECLARATION");
                if (function.isPrivate != null) span[0] = range(function.isPrivate, source)[0];
                if (BehaviorFeedback.cyclic(function, new IdentityHashMap<>())) return rejected("RECURSION");
                declarations.add(new Declaration(function, name, span[0], keywordStart, span[1], body[0] + 1, body[1] - 1));
                names.put(function, name);
            }
            declarations.sort(Comparator.comparingInt(Declaration::start));
            JSONArray rows = new JSONArray();
            int previous = 0;
            for (Declaration declaration : declarations) {
                if (declaration.start < previous) return rejected("OVERLAPPING_DECLARATIONS");
                previous = declaration.end;
                rows.put(new JSONObject().put("name", declaration.name)
                        .put("kind", declaration.function.isPred ? "pred" : "fun")
                        .put("parameterCount", declaration.function.count())
                        .put("startByte", byteOffset(source, declaration.start))
                        .put("keywordStartByte", byteOffset(source, declaration.keywordStart))
                        .put("endByte", byteOffset(source, declaration.end))
                        .put("bodyStartByte", byteOffset(source, declaration.bodyStart))
                        .put("bodyEndByte", byteOffset(source, declaration.bodyEnd))
                        .put("calls", ownCalls(BehaviorFeedback.calls(declaration.function), names)));
            }
            TreeSet<String> facts = new TreeSet<>(), assertions = new TreeSet<>(), commands = new TreeSet<>();
            facts.addAll(ownCalls(BehaviorFeedback.calls(module.getAllReachableFacts()), names));
            for (Sig signature : module.getAllReachableUserDefinedSigs()) {
                for (Expr fact : signature.getFacts()) facts.addAll(ownCalls(BehaviorFeedback.calls(fact), names));
                for (Sig.Field field : signature.getFields())
                    facts.addAll(ownCalls(BehaviorFeedback.calls(field.decl().expr), names));
            }
            for (var assertion : module.getAllAssertions())
                assertions.addAll(ownCalls(BehaviorFeedback.calls(assertion.b), names));
            for (Command command : module.getAllCommands()) {
                commands.addAll(ownCalls(BehaviorFeedback.calls(command.formula), names));
                if (command.nameExpr != null) {
                    commands.addAll(ownCalls(BehaviorFeedback.calls(command.nameExpr), names));
                    // A direct run's formula is inlined by Alloy; its name
                    // expression retains the selected source declaration.
                    if (command.nameExpr instanceof ExprVar variable) {
                        String name = variable.label.startsWith("this/") ? variable.label.substring(5) : variable.label;
                        if (usedNames.contains(name)) commands.add(name);
                    }
                }
            }
            return new JSONObject().put("status", "ok").put("sourceSha256", digest(source))
                    .put("moduleName", module.getModelName()).put("declarations", rows)
                    .put("factCalls", facts).put("assertionCalls", assertions).put("commandCalls", commands);
        } catch (Throwable error) { return rejected("INVALID_MODEL"); }
    }

    private static TreeSet<String> ownCalls(List<Func> functions, Map<Func, String> names) {
        TreeSet<String> result = new TreeSet<>();
        for (Func function : functions) if (names.containsKey(function)) result.add(names.get(function));
        return result;
    }

    private static int[] range(Pos position, String source) {
        int[] result = position.toStartEnd(source);
        if (result == null || result.length != 2 || result[0] < 0 || result[1] <= result[0] || result[1] > source.length())
            throw new IllegalArgumentException();
        return result;
    }

    private static int byteOffset(String source, int position) {
        return source.substring(0, position).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String digest(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static JSONObject rejected(String code) {
        return new JSONObject().put("status", "rejected").put("code", code);
    }

    private static boolean hasModuleMacro(String source) {
        int braces = 0;
        boolean line = false, block = false, string = false, escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index), next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (line) { if (ch == '\n' || ch == '\r') line = false; continue; }
            if (block) { if (ch == '*' && next == '/') { block = false; index++; } continue; }
            if (string) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') string = false;
                continue;
            }
            if (ch == '/' && next == '/' || ch == '-' && next == '-') { line = true; index++; }
            else if (ch == '/' && next == '*') { block = true; index++; }
            else if (ch == '"') string = true;
            else if (ch == '{') braces++;
            else if (ch == '}') braces--;
            else if (Character.isLetter(ch) || ch == '_' || ch == '$') {
                int start = index;
                while (index + 1 < source.length() && (Character.isLetterOrDigit(source.charAt(index + 1))
                        || source.charAt(index + 1) == '_' || source.charAt(index + 1) == '$')) index++;
                if (braces == 0 && source.substring(start, index + 1).equals("let")) return true;
            }
        }
        return false;
    }

    private record Declaration(Func function, String name, int start, int keywordStart, int end,
                               int bodyStart, int bodyEnd) { }
}

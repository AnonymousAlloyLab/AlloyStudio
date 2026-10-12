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
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Private source-backed declaration inventory for authenticated .als uploads. */
public final class UploadInspector {
    private static final int MAX_INPUT = 2_097_152;
    private static final int MAX_SOURCE = 262_144;
    private static final int MAX_VARIANT_GROUPS = 100;
    private static final int MAX_VARIANTS = 256;
    private static final Pattern VARIANT = Pattern.compile(
            "([iI][nN][vV](0|[1-9][0-9]*))[cC](0|[1-9][0-9]*)");
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
            boolean verifyVariants = request.keySet().equals(Set.of("source", "equivalenceScope", "variantGroups"));
            if ((!request.keySet().equals(Set.of("source")) && !verifyVariants)
                    || !(request.get("source") instanceof String source)
                    || source.isBlank() || source.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(source)
                    || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE)
                return rejected("INVALID_UPLOAD");
            int equivalenceScope = 0;
            JSONArray variantGroups = null;
            if (verifyVariants) {
                if (!(request.get("equivalenceScope") instanceof Integer scope) || scope < 1 || scope > 8
                        || !(request.get("variantGroups") instanceof JSONArray groups)
                        || groups.length() > MAX_VARIANT_GROUPS)
                    return rejected("INVALID_UPLOAD");
                equivalenceScope = scope;
                variantGroups = groups;
            }
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
            JSONObject variantEquivalence = null;
            if (verifyVariants) {
                variantEquivalence = variantEquivalence(module, declarations, names, variantGroups, equivalenceScope, source);
                if (variantEquivalence.has("status")) return variantEquivalence;
            }
            JSONArray rows = new JSONArray();
            int previous = 0;
            for (Declaration declaration : declarations) {
                if (declaration.start < previous) return rejected("OVERLAPPING_DECLARATIONS");
                previous = declaration.end;
                JSONArray parameterNames = new JSONArray();
                List<Func> parameterCalls = new ArrayList<>();
                for (Decl parameter : declaration.function.decls) {
                    for (ExprHasName variable : parameter.names) parameterNames.put(variable.label);
                    parameterCalls.addAll(BehaviorFeedback.calls(parameter.expr));
                }
                if (declaration.function.returnDecl != null)
                    parameterCalls.addAll(BehaviorFeedback.calls(declaration.function.returnDecl));
                rows.put(new JSONObject().put("name", declaration.name)
                        .put("kind", declaration.function.isPred ? "pred" : "fun")
                        .put("parameterCount", declaration.function.count())
                        .put("startByte", byteOffset(source, declaration.start))
                        .put("keywordStartByte", byteOffset(source, declaration.keywordStart))
                        .put("endByte", byteOffset(source, declaration.end))
                        .put("bodyStartByte", byteOffset(source, declaration.bodyStart))
                        .put("bodyEndByte", byteOffset(source, declaration.bodyEnd))
                        .put("parameterNames", parameterNames)
                        .put("parameterCalls", ownCalls(parameterCalls, names))
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
            JSONObject result = new JSONObject().put("status", "ok").put("sourceSha256", digest(source))
                    .put("moduleName", module.getModelName()).put("declarations", rows)
                    .put("factCalls", facts).put("assertionCalls", assertions).put("commandCalls", commands);
            if (verifyVariants) result.put("variantEquivalence", variantEquivalence);
            return result;
        } catch (Throwable error) { return rejected("INVALID_MODEL"); }
    }

    /** Compare all marked source predicates in their unchanged original world.
     * The caller's list can neither omit a group nor split or redefine one. */
    private static JSONObject variantEquivalence(CompModule module, List<Declaration> declarations,
            Map<Func, String> names, JSONArray requested, int scope, String source) throws Exception {
        Map<String, List<Func>> groups = new LinkedHashMap<>();
        Map<String, String> spelling = new HashMap<>();
        Set<String> allVariants = new TreeSet<>();
        for (Declaration declaration : declarations) {
            Matcher match = VARIANT.matcher(declaration.name);
            if (!match.matches()) continue;
            if (!declaration.function.isPred
                    || match.group(2).length() > 6 || match.group(3).length() > 6)
                throw new IllegalArgumentException();
            String base = match.group(1);
            String previous = spelling.putIfAbsent(base.toLowerCase(Locale.ROOT), base);
            if (previous != null && !previous.equals(base)) throw new IllegalArgumentException();
            groups.computeIfAbsent(base, ignored -> new ArrayList<>()).add(declaration.function);
            allVariants.add(declaration.name);
            if (groups.size() > MAX_VARIANT_GROUPS || allVariants.size() > MAX_VARIANTS)
                throw new IllegalArgumentException();
        }
        for (List<Func> variants : groups.values()) {
            Func primary = variants.get(0);
            for (Func variant : variants) if (!sameParameters(primary, variant))
                return rejected("PARAMETER_SIGNATURE_MISMATCH");
            variants.sort(Comparator.comparingInt(function -> variantNumber(names.get(function))));
            int previous = -1;
            for (Func function : variants) {
                int current = variantNumber(names.get(function));
                if (current == previous) throw new IllegalArgumentException();
                previous = current;
            }
        }
        Set<String> checkedGroups = new TreeSet<>();
        int suppliedCount = 0;
        for (Object row : requested) {
            if (!(row instanceof JSONArray supplied) || supplied.isEmpty()) throw new IllegalArgumentException();
            Set<String> suppliedNames = new TreeSet<>();
            String base = null;
            for (Object value : supplied) {
                if (!(value instanceof String name) || !allVariants.contains(name)
                        || !suppliedNames.add(name) || ++suppliedCount > MAX_VARIANTS)
                    throw new IllegalArgumentException();
                Matcher match = VARIANT.matcher(name);
                if (!match.matches()) throw new IllegalArgumentException();
                if (base == null) base = match.group(1);
                else if (!base.equals(match.group(1))) throw new IllegalArgumentException();
            }
            if (!checkedGroups.add(base)) throw new IllegalArgumentException();
            Set<String> expectedNames = new TreeSet<>();
            for (Func function : groups.get(base)) expectedNames.add(names.get(function));
            if (!suppliedNames.equals(expectedNames)) throw new IllegalArgumentException();
        }
        if (!checkedGroups.equals(groups.keySet()) || suppliedCount != allVariants.size())
            throw new IllegalArgumentException();

        Expr facts = module.getAllReachableFacts();
        Set<String> strings = new TreeSet<>(BehaviorFeedback.stringConstants(facts));
        // Pin one literal universe for the unchanged original reachable model,
        // including ordinary helper declarations, parameters and return types.
        for (CompModule reachable : module.getAllReachableModules()) {
            for (Func function : reachable.getAllFunc()) {
                strings.addAll(BehaviorFeedback.stringConstants(function.getBody()));
                for (Decl declaration : function.decls)
                    strings.addAll(BehaviorFeedback.stringConstants(declaration.expr));
                if (function.returnDecl != null)
                    strings.addAll(BehaviorFeedback.stringConstants(function.returnDecl));
            }
        }
        for (Sig signature : module.getAllReachableUserDefinedSigs()) {
            for (Expr fact : signature.getFacts()) strings.addAll(BehaviorFeedback.stringConstants(fact));
            for (Sig.Field field : signature.getFields())
                strings.addAll(BehaviorFeedback.stringConstants(field.decl().expr));
        }
        for (String literal : strings)
            facts = facts.and(ExprConstant.Op.STRING.make(Pos.UNKNOWN, literal).in(Sig.STRING));
        if (!ExerciseValidator.solve(module, facts, scope).satisfiable()) return rejected("INCONSISTENT_FACTS");
        for (List<Func> variants : groups.values()) {
            ParameterContext parameters = new ParameterContext(module, variants.get(0), source);
            Expr primary = parameters.oracle();
            for (int index = 1; index < variants.size(); index++) {
                Expr mismatch = facts.and(parameters.mismatch(primary, parameters.call(variants.get(index))));
                if (ExerciseValidator.solve(module, mismatch, scope).satisfiable())
                    return rejected("NOT_EQUIVALENT");
            }
        }
        return new JSONObject().put("groupCount", groups.size()).put("variantCount", allVariants.size())
                .put("scope", scope).put("bitwidth", 5).put("maxSequence", scope)
                .put("minTrace", 1).put("maxTrace", 10).put("solver", "SAT4J")
                .put("moduleFacts", true).put("factsSatisfiable", true).put("equivalence", "bounded");
    }

    /** Headers remain fixed, including grouped/disjoint names and dependent
     * domains. Only predicate names may differ across numbered alternatives.
     * Parsed domains share one original module, so qualified references have
     * stable identities and spellings rather than caller-supplied type claims. */
    private static boolean sameParameters(Func left, Func right) {
        if (left.count() != right.count() || left.decls.size() != right.decls.size()) return false;
        for (int index = 0; index < left.decls.size(); index++) {
            Decl a = left.decls.get(index), b = right.decls.get(index);
            if (a.names.size() != b.names.size()
                    || (a.disjoint != null) != (b.disjoint != null)
                    || (a.disjoint2 != null) != (b.disjoint2 != null)
                    || (a.isVar != null) != (b.isVar != null)
                    || (a.isPrivate != null) != (b.isPrivate != null)
                    || !a.expr.toString().equals(b.expr.toString())
                    || !a.expr.type().toString().equals(b.expr.type().toString())) return false;
            for (int name = 0; name < a.names.size(); name++)
                if (!a.names.get(name).label.equals(b.names.get(name).label)) return false;
        }
        return true;
    }

    private static int variantNumber(String name) {
        Matcher match = VARIANT.matcher(name);
        if (!match.matches()) throw new IllegalArgumentException();
        return Integer.parseInt(match.group(3));
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

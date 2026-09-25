package is.fivefivefive.CanDis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.CallMetadata;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.NormalForm.TemporalOp;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.core.QuantiVar.Cardinality;
import is.fivefivefive.CanDis.core.QuantiVar.Quantifier;

/**
 * Backtranslates normalized canonical forms into Alloy source that can be parsed
 * and run. The output is intentionally canonical: bound variables use the
 * alpha-normalized names stored in the normal form rather than original source
 * spellings.
 */
public final class CanonicalBacktranslator {
    private static final String TRUE_FORMULA = "(none = none)";
    private static final String FALSE_FORMULA = "(none != none)";

    private CanonicalBacktranslator() {
    }

    public static List<NormalForm> normalForms(Multigraph graph) {
        IRAgent agent = new IRAgent(graph);
        agent.computeNormalForm();
        return agent.normalForms();
    }

    public static String predicate(String predicateName, Multigraph graph) {
        return predicate(predicateName, normalForms(graph));
    }

    public static String predicate(String predicateName, List<NormalForm> normalForms) {
        StringBuilder source = new StringBuilder();
        source.append("pred ").append(identifier(predicateName)).append("[] {\n");
        source.append("  ").append(formula(normalForms)).append('\n');
        source.append("}\n");
        return source.toString();
    }

    public static String predicate(String predicateName, NormalForm normalForm) {
        StringBuilder source = new StringBuilder();
        source.append("pred ").append(identifier(predicateName)).append("[] {\n");
        source.append("  ").append(formula(normalForm)).append('\n');
        source.append("}\n");
        return source.toString();
    }

    public static String predicates(String baseName, List<NormalForm> normalForms) {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < normalForms.size(); i++) {
            if (i > 0) {
                source.append('\n');
            }
            source.append(predicate(baseName + "_" + i, normalForms.get(i)));
        }
        return source.toString();
    }

    public static String module(String moduleName, String prelude, String predicateName, Multigraph graph) {
        return module(moduleName, prelude, predicateName, normalForms(graph));
    }

    public static String module(String moduleName, String prelude, String predicateName, List<NormalForm> normalForms) {
        StringBuilder source = new StringBuilder();
        source.append("module ").append(identifier(moduleName)).append("\n\n");
        String bodyPrelude = stripModuleDecl(prelude);
        if (!bodyPrelude.isEmpty()) {
            source.append(bodyPrelude).append("\n\n");
        }
        source.append(predicate(predicateName, normalForms));
        source.append("\nrun ").append(identifier(predicateName)).append(" for 3\n");
        return source.toString();
    }

    public static String formula(List<NormalForm> normalForms) {
        if (normalForms == null || normalForms.isEmpty()) {
            return TRUE_FORMULA;
        }
        return formula(normalForms.get(0), Collections.emptyMap());
    }

    public static String formula(NormalForm normalForm) {
        return formula(normalForm, Collections.emptyMap());
    }

    private static String formula(NormalForm normalForm, Map<String, String> inheritedAliases) {
        if (normalForm == null) {
            return TRUE_FORMULA;
        }
        Map<String, String> aliases = aliasesWithBindings(inheritedAliases, normalForm.getMatrixQuantiVars());
        Map<String, String> temporal = temporalChildrenFormula(
                normalForm.getTemporalChildren(), aliases);
        aliases.putAll(temporal);
        Set<String> referencedTemporal = temporalReferences(normalForm.getMatrixEGraph());
        List<String> parts = new ArrayList<>();
        String matrix = matrixFormula(normalForm.getMatrixEGraph(), aliases);
        if (!isTrue(matrix)) {
            parts.add(matrix);
        }
        for (Map.Entry<String, String> entry : temporal.entrySet()) {
            if (!referencedTemporal.contains(entry.getKey()) && !isTrue(entry.getValue())) {
                parts.add(entry.getValue());
            }
        }
        String body = parts.isEmpty() ? TRUE_FORMULA : parenthesizedJoin(parts, "and");
        return applyQuantifiers(normalForm.getMatrixQuantiVars(), body);
    }

    private static Map<String, String> aliasesWithBindings(Map<String, String> inherited, List<QuantiVar> bindings) {
        Map<String, String> aliases = new HashMap<>(inherited);
        if (bindings != null) {
            for (QuantiVar binding : bindings) {
                if (binding.getName() != null) {
                    for (String alias : binding.getOriginalNames()) {
                        aliases.put(alias, binding.getName());
                    }
                }
            }
        }
        return aliases;
    }

    private static Map<String, String> temporalChildrenFormula(
            List<NormalForm> children,
            Map<String, String> aliases) {
        Map<String, String> parts = new LinkedHashMap<>();
        if (children == null) {
            return parts;
        }
        for (int i = 0; i < children.size(); i++) {
            NormalForm child = children.get(i);
            TemporalOp op = child.getTemporalOp();
            if (i + 1 < children.size() && isLeftBinaryTemporal(op)
                    && matchingRightBinaryTemporal(op) == children.get(i + 1).getTemporalOp()) {
                parts.put("temporal[" + i + ":2]",
                        "(" + formula(child, aliases) + " " + binaryTemporalName(op) + " "
                                + formula(children.get(i + 1), aliases) + ")");
                i++;
            } else {
                parts.put("temporal[" + i + ":1]", temporalFormula(child, aliases));
            }
        }
        return parts;
    }

    private static String temporalFormula(NormalForm normalForm, Map<String, String> aliases) {
        String body = formulaWithoutTemporalSiblings(normalForm, aliases);
        switch (normalForm.getTemporalOp()) {
            case BEFORE:
                return "(before " + body + ")";
            case HISTORICALLY:
                return "(historically " + body + ")";
            case ONCE:
                return "(once " + body + ")";
            case ALWAYS:
                return "(always " + body + ")";
            case EVENTUALLY:
                return "(eventually " + body + ")";
            case AFTER:
                return "(after " + body + ")";
            default:
                return body;
        }
    }

    private static String formulaWithoutTemporalSiblings(NormalForm normalForm, Map<String, String> inheritedAliases) {
        Map<String, String> aliases = aliasesWithBindings(inheritedAliases, normalForm.getMatrixQuantiVars());
        Map<String, String> temporal = temporalChildrenFormula(
                normalForm.getTemporalChildren(), aliases);
        aliases.putAll(temporal);
        Set<String> referencedTemporal = temporalReferences(normalForm.getMatrixEGraph());
        List<String> parts = new ArrayList<>();
        String matrix = matrixFormula(normalForm.getMatrixEGraph(), aliases);
        if (!isTrue(matrix)) {
            parts.add(matrix);
        }
        for (Map.Entry<String, String> entry : temporal.entrySet()) {
            if (!referencedTemporal.contains(entry.getKey()) && !isTrue(entry.getValue())) {
                parts.add(entry.getValue());
            }
        }
        String body = parts.isEmpty() ? TRUE_FORMULA : parenthesizedJoin(parts, "and");
        return applyQuantifiers(normalForm.getMatrixQuantiVars(), body);
    }

    private static String applyQuantifiers(List<QuantiVar> bindings, String body) {
        if (bindings == null || bindings.isEmpty()) {
            return body;
        }
        String result = body;
        for (int i = bindings.size() - 1; i >= 0;) {
            QuantiVar current = bindings.get(i);
            int start = i;
            while (start - 1 >= 0 && sameDeclGroup(bindings.get(start - 1), current)) {
                start--;
            }
            result = quantify(bindings.subList(start, i + 1), result);
            i = start - 1;
        }
        return result;
    }

    private static boolean sameDeclGroup(QuantiVar left, QuantiVar right) {
        return left.getQuantifier() == right.getQuantifier()
                && left.getCardinality() == right.getCardinality()
                && left.getDisjointnessClass() == right.getDisjointnessClass()
                && safeEquals(typeName(left), typeName(right))
                && safeEquals(carrierTypeName(left), carrierTypeName(right));
    }

    private static String quantify(List<QuantiVar> bindings, String body) {
        QuantiVar first = bindings.get(0);
        String decl = decl(bindings);
        switch (first.getQuantifier()) {
            case ALL:
                return "(all " + decl + " | " + body + ")";
            case SOME:
                return "(some " + decl + " | " + body + ")";
            case NO:
                return "(no " + decl + " | " + body + ")";
            case ONE:
                return "(one " + decl + " | " + body + ")";
            case LONE:
                return "(lone " + decl + " | " + body + ")";
            case NOTONE:
                return "(not (one " + decl + " | " + body + "))";
            case NOTLONE:
                return "(not (lone " + decl + " | " + body + "))";
            case SUM:
            case COMPREHENSION:
            default:
                return "(some " + decl + " | " + body + ")";
        }
    }

    private static String decl(List<QuantiVar> bindings) {
        QuantiVar first = bindings.get(0);
        List<String> names = new ArrayList<>();
        for (QuantiVar binding : bindings) {
            names.add(identifier(binding.getName()));
        }
        String disj = first.isDisj() && bindings.size() > 1 ? "disj " : "";
        return disj + String.join(", ", names) + ": "
                + cardinalityPrefix(first.getCardinality()) + alloyType(carrierTypeName(first));
    }

    private static String matrixFormula(EGraphNode node) {
        return matrixFormula(node, Collections.emptyMap());
    }

    private static String matrixFormula(EGraphNode node, Map<String, String> aliases) {
        if (node == null || isBareTemporalMarker(node)) {
            return TRUE_FORMULA;
        }
        if (isQuantifierNode(node)) {
            return quantifierNodeFormula(node, aliases);
        }
        switch (node.getOpcode()) {
            case VARIABLE:
                return variableName(node, aliases);
            case GLOBALBINDING:
                return alloyName(firstNonEmpty(node.getSourceName(), node.getSourceType()));
            case CONSTANT:
                return constantName(node);
            case REF:
                String temporal = aliases.get(node.getSourceName());
                if (temporal == null) {
                    throw new IllegalStateException(
                            "Unresolved temporal normal-form reference " + node.getSourceName());
                }
                return temporal;
            case TEMPORALROOT:
                return formulaFromChildren(node.getChildren(), "and", aliases);
            case NOT:
                return unaryPrefix(node, "not", aliases);
            case SOME:
                return unaryPrefix(node, "some", aliases);
            case NO:
                return unaryPrefix(node, "no", aliases);
            case LONE:
                return unaryPrefix(node, "lone", aliases);
            case ONE:
            case EXACTLY:
                return unaryPrefix(node, "one", aliases);
            case SETOF:
                return unaryPrefix(node, "set", aliases);
            case TRANSPOSE:
                return unarySymbol(node, "~", aliases);
            case RCLOSURE:
                return unarySymbol(node, "*", aliases);
            case CLOSURE:
                return unarySymbol(node, "^", aliases);
            case CARDINALITY:
                return unarySymbol(node, "#", aliases);
            case PRIME:
                return postfixSymbol(node, "'", aliases);
            case CAST2INT:
            case CAST2SIGINT:
                return matrixFormula(firstChild(node), aliases);
            case AND:
                return infix(node, "and", aliases);
            case OR:
                return infix(node, "or", aliases);
            case IMPLIES:
                return infix(node, "=>", aliases);
            case IFF:
                return infix(node, "<=>", aliases);
            case EQUALS:
                return infix(node, "=", aliases);
            case NOT_EQUALS:
                return infix(node, "!=", aliases);
            case IN:
                return infix(node, "in", aliases);
            case NOT_IN:
                return negatedInfix(node, "in", aliases);
            case GT:
                return infix(node, ">", aliases);
            case GTE:
                return infix(node, ">=", aliases);
            case LT:
                return infix(node, "<", aliases);
            case LTE:
                return infix(node, "<=", aliases);
            case NOT_GT:
                return negatedInfix(node, ">", aliases);
            case NOT_GTE:
                return negatedInfix(node, ">=", aliases);
            case NOT_LT:
                return negatedInfix(node, "<", aliases);
            case NOT_LTE:
                return negatedInfix(node, "<=", aliases);
            case JOIN:
                return rightAssociativeInfix(node, ".", aliases);
            case ARROW:
            case ANY_ARROW_SOME:
            case ANY_ARROW_ONE:
            case ANY_ARROW_LONE:
            case SOME_ARROW_ANY:
            case SOME_ARROW_SOME:
            case SOME_ARROW_ONE:
            case SOME_ARROW_LONE:
            case ONE_ARROW_ANY:
            case ONE_ARROW_SOME:
            case ONE_ARROW_ONE:
            case ONE_ARROW_LONE:
            case LONE_ARROW_ANY:
            case LONE_ARROW_SOME:
            case LONE_ARROW_ONE:
            case LONE_ARROW_LONE:
            case ISSEQ_ARROW_LONE:
                return arrowFormula(node, aliases);
            case DOMAIN:
                return infix(node, "<:", aliases);
            case RANGE:
                return infix(node, ":>", aliases);
            case INTERSECT:
                return infix(node, "&", aliases);
            case PLUS:
            case IPLUS:
                return infix(node, "+", aliases);
            case PLUSPLUS:
                return infix(node, "++", aliases);
            case MINUS:
            case IMINUS:
                return infix(node, "-", aliases);
            case MUL:
                return infix(node, "*", aliases);
            case DIV:
                return infix(node, "/", aliases);
            case REM:
                return infix(node, "%", aliases);
            case SHL:
                return infix(node, "<<", aliases);
            case SHA:
                return infix(node, ">>>", aliases);
            case SHR:
                return infix(node, ">>", aliases);
            case PREDICATE:
            case FUNCTION:
                return formulaFromChildren(node.getChildren(), "and", aliases);
            case CALL:
                return callFormula(node, aliases);
            case ITE:
                return iteFormula(node, aliases);
            case DISJOINT:
                return "disj[" + formulaFromChildren(
                        node.getChildren(), ",", aliases) + "]";
            case LIST:
            case DISJOINT_LIST:
            case TOTALORDER_LIST:
                return formulaFromChildren(node.getChildren(), ",", aliases);
            case BEFORE:
            case HISTORICALLY:
            case ONCE:
            case ALWAYS:
            case EVENTUALLY:
            case AFTER:
            case UNTIL:
            case RELEASES:
            case SINCE:
            case TRIGGERED:
                return temporalOpcodeFormula(node, aliases);
            default:
                return callFormula(node, aliases);
        }
    }

    private static String variableName(EGraphNode node, Map<String, String> aliases) {
        String source = firstNonEmpty(node.getSourceName(), node.getAlphaName());
        String alpha = node.getAlphaName();
        if (source != null && aliases.containsKey(source)
                && (alpha == null || alpha.equals(source))) {
            return identifier(aliases.get(source));
        }
        return identifier(firstNonEmpty(alpha, source));
    }

    private static String temporalOpcodeFormula(EGraphNode node, Map<String, String> aliases) {
        if (node.getChildren().isEmpty()) {
            return TRUE_FORMULA;
        }
        switch (node.getOpcode()) {
            case BEFORE:
            case HISTORICALLY:
            case ONCE:
            case ALWAYS:
            case EVENTUALLY:
            case AFTER:
                return "(" + node.getOpcode().name().toLowerCase(Locale.ROOT) + " "
                        + matrixFormula(node.getChildren().get(0), aliases) + ")";
            case UNTIL:
                return infix(node, "until", aliases);
            case RELEASES:
                return infix(node, "releases", aliases);
            case SINCE:
                return infix(node, "since", aliases);
            case TRIGGERED:
                return infix(node, "triggered", aliases);
            default:
                return TRUE_FORMULA;
        }
    }

    private static String infix(EGraphNode node, String operator) {
        return infix(node, operator, Collections.emptyMap());
    }

    private static String infix(EGraphNode node, String operator, Map<String, String> aliases) {
        List<String> parts = childFormulaParts(node, aliases);
        if (parts.isEmpty()) {
            return TRUE_FORMULA;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return parenthesizedJoin(parts, operator);
    }

    private static String rightAssociativeInfix(EGraphNode node, String operator, Map<String, String> aliases) {
        List<String> parts = childFormulaParts(node, aliases);
        if (parts.isEmpty()) {
            return TRUE_FORMULA;
        }
        String result = parts.get(parts.size() - 1);
        for (int i = parts.size() - 2; i >= 0; i--) {
            result = "(" + parts.get(i) + " " + operator + " " + result + ")";
        }
        return result;
    }

    private static String negatedInfix(EGraphNode node, String operator, Map<String, String> aliases) {
        return "(not " + infix(node, operator, aliases) + ")";
    }

    private static String unaryPrefix(EGraphNode node, String operator) {
        return unaryPrefix(node, operator, Collections.emptyMap());
    }

    private static String unaryPrefix(EGraphNode node, String operator, Map<String, String> aliases) {
        EGraphNode child = firstChild(node);
        return child == null ? TRUE_FORMULA : "(" + operator + " " + matrixFormula(child, aliases) + ")";
    }

    private static String unarySymbol(EGraphNode node, String operator) {
        return unarySymbol(node, operator, Collections.emptyMap());
    }

    private static String unarySymbol(EGraphNode node, String operator, Map<String, String> aliases) {
        EGraphNode child = firstChild(node);
        return child == null ? "none" : "(" + operator + matrixFormula(child, aliases) + ")";
    }

    private static String postfixSymbol(EGraphNode node, String operator) {
        return postfixSymbol(node, operator, Collections.emptyMap());
    }

    private static String postfixSymbol(EGraphNode node, String operator, Map<String, String> aliases) {
        EGraphNode child = firstChild(node);
        return child == null ? "none" : "(" + matrixFormula(child, aliases) + operator + ")";
    }

    private static String iteFormula(EGraphNode node, Map<String, String> aliases) {
        List<EGraphNode> children = node.getChildren();
        if (children.size() != 3) {
            return callFormula(node, aliases);
        }
        return "(" + matrixFormula(children.get(0), aliases) + " => " + matrixFormula(children.get(1), aliases)
                + " else " + matrixFormula(children.get(2), aliases) + ")";
    }

    private static String callFormula(EGraphNode node, Map<String, String> aliases) {
        if (node.getOpcode() == Opcode.CALL) {
            CallMetadata.require(node);
        }
        String name = alloyName(firstNonEmpty(node.getSourceName(), node.getAlphaName(), node.getOpcode().name().toLowerCase(Locale.ROOT)));
        List<String> parts = childFormulaParts(node, aliases);
        if (parts.isEmpty()) {
            return name;
        }
        return name + "[" + String.join(", ", parts) + "]";
    }

    private static String arrowFormula(EGraphNode node, Map<String, String> aliases) {
        return infix(node, "->", aliases);
    }

    private static String quantifierNodeFormula(EGraphNode node, Map<String, String> inheritedAliases) {
        Map<String, String> aliases = new HashMap<>(inheritedAliases);
        List<String> declarations = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            if (isRelDecl(child.getOpcode())) {
                String declaration = relDeclFormula(child, aliases);
                if (!declaration.isEmpty()) {
                    declarations.add(declaration);
                }
            } else {
                String body = matrixFormula(child, aliases);
                if (!isTrue(body)) {
                    bodies.add(body);
                }
            }
        }
        if (declarations.isEmpty()) {
            return formulaFromChildren(node.getChildren(), "and", aliases);
        }
        String body = bodies.isEmpty() ? TRUE_FORMULA : parenthesizedJoin(bodies, "and");
        if (node.getOpcode() == Opcode.COMPREHENSION) {
            return "{" + String.join(", ", declarations) + " | " + body + "}";
        }
        return "(" + quantifierKeyword(node.getOpcode()) + " "
                + String.join(", ", declarations) + " | " + body + ")";
    }

    private static String relDeclFormula(EGraphNode relDecl, Map<String, String> aliases) {
        List<EGraphNode> children = relDecl.getChildren();
        if (children.isEmpty()) {
            return "";
        }
        String type = matrixFormula(children.get(0), aliases);
        List<String> variables = new ArrayList<>();
        for (int i = 1; i < children.size(); i++) {
            EGraphNode variable = children.get(i);
            String name = variableName(variable, aliases);
            variables.add(name);
            if (variable.getSourceName() != null) {
                aliases.put(variable.getSourceName(), name);
            }
        }
        if (variables.isEmpty()) {
            return "";
        }
        String disj = isDisjRelDecl(relDecl.getOpcode()) && variables.size() > 1 ? "disj " : "";
        return disj + String.join(", ", variables) + ": " + type;
    }

    private static String quantifierKeyword(Opcode opcode) {
        switch (opcode) {
            case FORALL:
                return "all";
            case NO:
                return "no";
            case ONE:
                return "one";
            case LONE:
                return "lone";
            case SUM:
                return "sum";
            case EXISTS:
            default:
                return "some";
        }
    }

    private static String formulaFromChildren(List<EGraphNode> children, String operator) {
        return formulaFromChildren(children, operator, Collections.emptyMap());
    }

    private static String formulaFromChildren(List<EGraphNode> children, String operator, Map<String, String> aliases) {
        List<String> parts = new ArrayList<>();
        for (EGraphNode child : children) {
            String formula = matrixFormula(child, aliases);
            if (!isTrue(formula)) {
                parts.add(formula);
            }
        }
        if (parts.isEmpty()) {
            return TRUE_FORMULA;
        }
        if (",".equals(operator)) {
            return String.join(", ", parts);
        }
        return parenthesizedJoin(parts, operator);
    }

    private static List<String> childFormulaParts(EGraphNode node) {
        return childFormulaParts(node, Collections.emptyMap());
    }

    private static List<String> childFormulaParts(EGraphNode node, Map<String, String> aliases) {
        List<String> parts = new ArrayList<>();
        for (EGraphNode child : node.getChildren()) {
            if (isBareTemporalMarker(child)) {
                continue;
            }
            String formula = matrixFormula(child, aliases);
            if (!isTrue(formula)) {
                parts.add(formula);
            }
        }
        return parts;
    }

    private static EGraphNode firstChild(EGraphNode node) {
        return node.getChildren().isEmpty() ? null : node.getChildren().get(0);
    }

    private static boolean isBareTemporalMarker(EGraphNode node) {
        if (node == null || !node.getChildren().isEmpty()) {
            return false;
        }
        switch (node.getOpcode()) {
            case BEFORE:
            case HISTORICALLY:
            case ONCE:
            case ALWAYS:
            case EVENTUALLY:
            case AFTER:
            case UNTIL:
            case RELEASES:
            case SINCE:
            case TRIGGERED:
                return true;
            default:
                return false;
        }
    }

    private static Set<String> temporalReferences(EGraphNode root) {
        Set<String> references = new LinkedHashSet<>();
        collectTemporalReferences(root, references);
        return references;
    }

    private static void collectTemporalReferences(EGraphNode node, Set<String> references) {
        if (node == null) {
            return;
        }
        if (node.getOpcode() == Opcode.REF && node.getSourceName() != null) {
            references.add(node.getSourceName());
        }
        for (EGraphNode child : node.getChildren()) {
            collectTemporalReferences(child, references);
        }
    }

    private static boolean isQuantifierNode(EGraphNode node) {
        switch (node.getOpcode()) {
            case FORALL:
            case EXISTS:
            case NO:
            case LONE:
            case ONE:
            case SUM:
            case COMPREHENSION:
                for (EGraphNode child : node.getChildren()) {
                    if (isRelDecl(child.getOpcode())) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    private static boolean isRelDecl(Opcode opcode) {
        return opcode == Opcode.DISJ || opcode == Opcode.VAR || opcode == Opcode.DISJVAR
                || opcode == Opcode.GENERICRELDECL;
    }

    private static boolean isDisjRelDecl(Opcode opcode) {
        return opcode == Opcode.DISJ || opcode == Opcode.DISJVAR;
    }

    private static boolean isLeftBinaryTemporal(TemporalOp op) {
        return op == TemporalOp.UNTILL || op == TemporalOp.RELEASESL
                || op == TemporalOp.SINCEL || op == TemporalOp.TRIGGEREDL;
    }

    private static TemporalOp matchingRightBinaryTemporal(TemporalOp op) {
        switch (op) {
            case UNTILL:
                return TemporalOp.UNTILR;
            case RELEASESL:
                return TemporalOp.RELEASESR;
            case SINCEL:
                return TemporalOp.SINCER;
            case TRIGGEREDL:
                return TemporalOp.TRIGGEREDR;
            default:
                return null;
        }
    }

    private static String binaryTemporalName(TemporalOp op) {
        switch (op) {
            case UNTILL:
                return "until";
            case RELEASESL:
                return "releases";
            case SINCEL:
                return "since";
            case TRIGGEREDL:
                return "triggered";
            default:
                return "until";
        }
    }

    private static String parenthesizedJoin(List<String> parts, String operator) {
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return "(" + String.join(" " + operator + " ", parts) + ")";
    }

    private static String cardinalityPrefix(Cardinality cardinality) {
        switch (cardinality) {
            case SOME:
                return "some ";
            case ONE:
                return "one ";
            case LONE:
                return "lone ";
            case EXACTLY:
                return "one ";
            case SET:
            default:
                return "";
        }
    }

    private static String typeName(QuantiVar qv) {
        String type = qv.getTypeName();
        return type == null || type.isEmpty() ? "univ" : type;
    }

    private static String carrierTypeName(QuantiVar qv) {
        String type = qv.getCarrierTypeName();
        return type == null || type.isEmpty() ? typeName(qv) : type;
    }

    private static String alloyType(String type) {
        return alloyName(type == null || type.isEmpty() ? "univ" : type);
    }

    private static String constantName(EGraphNode node) {
        String name = firstNonEmpty(node.getSourceName(), node.getSourceType(), "true");
        if ("True".equalsIgnoreCase(name)) {
            return TRUE_FORMULA;
        }
        if ("False".equalsIgnoreCase(name)) {
            return FALSE_FORMULA;
        }
        return alloyName(name);
    }

    private static String alloyName(String name) {
        if (name == null || name.isEmpty() || "<unknown>".equals(name)) {
            return "univ";
        }
        String trimmed = unwrapParens(name.trim());
        if (trimmed.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return trimmed;
        }
        return trimmed;
    }

    private static String identifier(String value) {
        if (value == null || value.isEmpty()) {
            return "_canonical";
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9_]", "_");
        if (sanitized.isEmpty() || !Character.isJavaIdentifierStart(sanitized.charAt(0))) {
            sanitized = "_" + sanitized;
        }
        return sanitized;
    }

    private static String unwrapParens(String value) {
        String result = value;
        while (result.length() >= 2 && result.charAt(0) == '(' && result.charAt(result.length() - 1) == ')') {
            result = result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static String stripModuleDecl(String prelude) {
        if (prelude == null) {
            return "";
        }
        String trimmed = prelude.trim();
        if (!trimmed.startsWith("module ")) {
            return trimmed;
        }
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? "" : trimmed.substring(newline + 1).trim();
    }

    private static boolean isTrue(String formula) {
        return formula == null || formula.isEmpty() || "true".equals(formula) || TRUE_FORMULA.equals(formula);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}

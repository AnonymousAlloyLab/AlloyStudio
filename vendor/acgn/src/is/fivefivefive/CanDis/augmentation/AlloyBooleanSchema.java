package is.fivefivefive.CanDis.augmentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Decl;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.ast.ExprBinary;
import edu.mit.csail.sdg.ast.ExprCall;
import edu.mit.csail.sdg.ast.ExprConstant;
import edu.mit.csail.sdg.ast.ExprHasName;
import edu.mit.csail.sdg.ast.ExprITE;
import edu.mit.csail.sdg.ast.ExprLet;
import edu.mit.csail.sdg.ast.ExprList;
import edu.mit.csail.sdg.ast.ExprQt;
import edu.mit.csail.sdg.ast.ExprUnary;
import edu.mit.csail.sdg.ast.ExprVar;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.theory.StructuralKey;

/** Parser-resolved Boolean schema and its mechanically derived Lean obligation. */
final class AlloyBooleanSchema {
    private AlloyBooleanSchema() {
    }

    record Pair(StructuralKey left, StructuralKey right) {
    }

    static Pair analyze(AlloyEquivalenceValidator.Request request) {
        Objects.requireNonNull(request, "request");
        if (request.comparisonKind()
                        != AlloyEquivalenceValidator.ComparisonKind.FORMULA_IFF
                || !request.universalBinder().isEmpty()) {
            throw new IllegalArgumentException(
                    "Automatic Lean correspondence currently admits only closed "
                            + "atemporal formula equalities");
        }
        try {
            CompModule module = CompUtil.parseEverything_fromString(
                    A4Reporter.NOP, request.modelSource());
            Expr left = CompUtil.parseOneExpression_fromString(
                    module, request.leftExpression());
            Expr right = CompUtil.parseOneExpression_fromString(
                    module, request.rightExpression());
            if (!left.type().is_bool || !right.type().is_bool) {
                throw new IllegalArgumentException(
                        "Boolean schema extraction requires two formulas");
            }
            Encoder encoder = new Encoder(AugmentationDigests.sha256(
                    request.modelSource()));
            return new Pair(encoder.formula(left), encoder.formula(right));
        } catch (edu.mit.csail.sdg.alloy4.Err exception) {
            throw new IllegalArgumentException(
                    "Alloy semantic schema extraction failed", exception);
        }
    }

    static final class LeanObligation {
        private final String parameters;
        private final String statement;
        private final String digest;

        private LeanObligation(
                String parameters,
                String statement,
                String digest) {
            this.parameters = parameters;
            this.statement = statement;
            this.digest = digest;
        }

        static LeanObligation from(StructuralAntiUnifier.Proposal proposal) {
            LeanEncoder encoder = new LeanEncoder();
            String left = encoder.formula(proposal.left());
            String right = encoder.formula(proposal.right());
            String parameters = encoder.variables.isEmpty()
                    ? ""
                    : "(" + String.join(" ", encoder.variables.values()) + " : Prop)";
            String statement = "(" + left + ") <-> (" + right + ")";
            String digest = AugmentationDigests.sha256(String.join("\n",
                    "alloy-boolean-lean-correspondence-v1",
                    proposal.digest(),
                    parameters,
                    statement));
            return new LeanObligation(parameters, statement, digest);
        }

        String parameters() {
            return parameters;
        }

        String statement() {
            return statement;
        }

        String digest() {
            return digest;
        }
    }

    private static final class Encoder {
        private final Set<ExprCall> expanding = Collections.newSetFromMap(
                new IdentityHashMap<>());
        private final String sourceDigest;

        private Encoder(String sourceDigest) {
            this.sourceDigest = Objects.requireNonNull(sourceDigest, "sourceDigest");
        }

        StructuralKey formula(Expr source) {
            Expr expression = source.deNOP();
            if (expression instanceof ExprCall) {
                ExprCall call = (ExprCall) expression;
                if (call.fun.isPred && call.args.isEmpty()) {
                    if (!expanding.add(call)) {
                        throw new IllegalArgumentException(
                                "Recursive predicate calls cannot define an adaptive schema");
                    }
                    try {
                        return formula(call.fun.getBody());
                    } finally {
                        expanding.remove(call);
                    }
                }
            }
            if (expression instanceof ExprConstant) {
                ExprConstant constant = (ExprConstant) expression;
                if (constant.op == ExprConstant.Op.TRUE) {
                    return StructuralKey.branch("bool/true", List.of());
                }
                if (constant.op == ExprConstant.Op.FALSE) {
                    return StructuralKey.branch("bool/false", List.of());
                }
            }
            if (expression instanceof ExprUnary) {
                ExprUnary unary = (ExprUnary) expression;
                if (unary.op == ExprUnary.Op.NOT) {
                    return unary("bool/not", formula(unary.sub));
                }
                if (unary.op == ExprUnary.Op.NO) {
                    return unary("bool/not", atom(unary.sub));
                }
                if (unary.op == ExprUnary.Op.SOME) {
                    return atom(unary.sub);
                }
            }
            if (expression instanceof ExprBinary) {
                ExprBinary binary = (ExprBinary) expression;
                String operator = binaryOperator(binary.op);
                if (operator != null) {
                    return StructuralKey.branch(
                            operator,
                            List.of(formula(binary.left), formula(binary.right)));
                }
            }
            if (expression instanceof ExprList) {
                ExprList list = (ExprList) expression;
                String operator = list.op == ExprList.Op.AND
                        ? "bool/and"
                        : list.op == ExprList.Op.OR ? "bool/or" : null;
                if (operator != null && !list.args.isEmpty()) {
                    List<StructuralKey> children = new ArrayList<>();
                    for (Expr argument : list.args) {
                        children.add(formula(argument));
                    }
                    return StructuralKey.branch(operator, children);
                }
            }
            if (expression instanceof ExprITE && expression.type().is_bool) {
                ExprITE ite = (ExprITE) expression;
                StructuralKey condition = formula(ite.cond);
                return StructuralKey.branch(
                        "bool/or",
                        List.of(
                                StructuralKey.branch(
                                        "bool/and",
                                        List.of(condition, formula(ite.left))),
                                StructuralKey.branch(
                                        "bool/and",
                                        List.of(
                                                unary("bool/not", condition),
                                                formula(ite.right)))));
            }
            if (expression.type().is_bool) {
                return atom(expression);
            }
            throw new IllegalArgumentException(
                    "Expression is not a Boolean schema formula: " + expression);
        }

        private StructuralKey atom(Expr expression) {
            StructuralKey identity = new AtomIdentityEncoder(sourceDigest).encode(
                    expression.deNOP());
            return StructuralKey.leaf(
                    "bool/atom",
                    AugmentationDigests.sha256(identity),
                    expression.type().toString());
        }

        private static StructuralKey unary(String operator, StructuralKey child) {
            return StructuralKey.branch(operator, List.of(child));
        }

        private static String binaryOperator(ExprBinary.Op operator) {
            if (operator == ExprBinary.Op.AND) {
                return "bool/and";
            }
            if (operator == ExprBinary.Op.OR) {
                return "bool/or";
            }
            if (operator == ExprBinary.Op.IFF) {
                return "bool/iff";
            }
            if (operator == ExprBinary.Op.IMPLIES) {
                return "bool/implies";
            }
            return null;
        }
    }

    /**
     * Lossless identity for an opaque Alloy proposition. Surface rendering is
     * deliberately excluded because Alloy omits declaration domains in several
     * normalized strings, including quantified expressions.
     */
    private static final class AtomIdentityEncoder {
        private static final String VERSION = "alloy-boolean-atom-ast-v2";

        private final String sourceDigest;
        private final IdentityHashMap<ExprVar, String> boundSlots =
                new IdentityHashMap<>();
        private int nextSlot;

        private AtomIdentityEncoder(String sourceDigest) {
            this.sourceDigest = Objects.requireNonNull(sourceDigest, "sourceDigest");
        }

        private StructuralKey encode(Expr source) {
            return StructuralKey.of(
                    "alloy/atom-root",
                    List.of(VERSION, sourceDigest),
                    List.of(expression(source.deNOP())));
        }

        private StructuralKey expression(Expr source) {
            Expr value = source.deNOP();
            if (value instanceof ExprBinary) {
                ExprBinary binary = (ExprBinary) value;
                return node(
                        "binary",
                        value,
                        List.of(expression(binary.left), expression(binary.right)),
                        binary.op.toString());
            }
            if (value instanceof ExprCall) {
                ExprCall call = (ExprCall) value;
                List<StructuralKey> arguments = new ArrayList<>(call.args.size());
                for (Expr argument : call.args) {
                    arguments.add(expression(argument));
                }
                return node(
                        "call",
                        value,
                        arguments,
                        Objects.toString(call.fun.label, ""),
                        Boolean.toString(call.fun.isPred),
                        Integer.toString(call.fun.count()));
            }
            if (value instanceof ExprConstant) {
                ExprConstant constant = (ExprConstant) value;
                return node(
                        "constant",
                        value,
                        List.of(),
                        constant.op.toString(),
                        Objects.toString(constant.string, ""),
                        Integer.toString(constant.num));
            }
            if (value instanceof ExprITE) {
                ExprITE ite = (ExprITE) value;
                return node(
                        "ite",
                        value,
                        List.of(
                                expression(ite.cond),
                                expression(ite.left),
                                expression(ite.right)));
            }
            if (value instanceof ExprLet) {
                return let((ExprLet) value);
            }
            if (value instanceof ExprList) {
                ExprList list = (ExprList) value;
                List<StructuralKey> arguments = new ArrayList<>(list.args.size());
                for (Expr argument : list.args) {
                    arguments.add(expression(argument));
                }
                return node("list", value, arguments, list.op.toString());
            }
            if (value instanceof ExprQt) {
                return quantified((ExprQt) value);
            }
            if (value instanceof ExprUnary) {
                ExprUnary unary = (ExprUnary) value;
                return node(
                        "unary",
                        value,
                        List.of(expression(unary.sub)),
                        unary.op.toString());
            }
            if (value instanceof ExprVar) {
                ExprVar variable = (ExprVar) value;
                String slot = boundSlots.get(variable);
                return node(
                        slot == null ? "free-var" : "bound-var",
                        value,
                        List.of(),
                        slot == null ? variable.label : slot);
            }
            if (value instanceof Sig.Field) {
                Sig.Field field = (Sig.Field) value;
                return node(
                        "field",
                        value,
                        List.of(),
                        Objects.toString(field.sig.label, ""),
                        Objects.toString(field.label, ""),
                        Boolean.toString(field.defined));
            }
            if (value instanceof Sig) {
                Sig signature = (Sig) value;
                return node(
                        "signature",
                        value,
                        List.of(),
                        Objects.toString(signature.label, ""),
                        Boolean.toString(signature.builtin));
            }
            if (value instanceof ExprHasName) {
                throw unsupported(value);
            }
            throw unsupported(value);
        }

        private StructuralKey let(ExprLet expression) {
            StructuralKey bound = expression(expression.expr);
            String slot = "b" + nextSlot++;
            String prior = boundSlots.put(expression.var, slot);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "A resolved Alloy let variable unexpectedly reuses a binder object");
            }
            try {
                return node(
                        "let",
                        expression,
                        List.of(bound, expression(expression.sub)),
                        slot);
            } finally {
                boundSlots.remove(expression.var);
            }
        }

        private StructuralKey quantified(ExprQt expression) {
            List<StructuralKey> children = new ArrayList<>();
            List<ExprVar> introduced = new ArrayList<>();
            try {
                for (Decl declaration : expression.decls) {
                    StructuralKey domain = expression(declaration.expr);
                    List<String> slots = new ArrayList<>();
                    for (ExprHasName name : declaration.names) {
                        if (!(name instanceof ExprVar)) {
                            throw new IllegalArgumentException(
                                    "A resolved quantified declaration has a non-variable name");
                        }
                        ExprVar variable = (ExprVar) name;
                        String slot = "b" + nextSlot++;
                        if (boundSlots.put(variable, slot) != null) {
                            throw new IllegalArgumentException(
                                    "A resolved Alloy quantifier reuses a binder object");
                        }
                        introduced.add(variable);
                        slots.add(slot);
                    }
                    children.add(StructuralKey.of(
                            "alloy/decl",
                            List.of(
                                    Boolean.toString(declaration.disjoint != null),
                                    Boolean.toString(declaration.disjoint2 != null),
                                    Boolean.toString(declaration.isVar != null),
                                    Integer.toString(declaration.names.size()),
                                    String.join(",", slots)),
                            List.of(domain)));
                }
                children.add(expression(expression.sub));
                return node(
                        "quantified",
                        expression,
                        children,
                        expression.op.toString(),
                        Integer.toString(expression.decls.size()));
            } finally {
                for (ExprVar variable : introduced) {
                    boundSlots.remove(variable);
                }
            }
        }

        private static StructuralKey node(
                String kind,
                Expr expression,
                List<StructuralKey> children,
                String... details) {
            List<String> scalars = new ArrayList<>(details.length + 2);
            scalars.add(expression.type().toString());
            scalars.add(Integer.toString(expression.mult));
            Collections.addAll(scalars, details);
            return StructuralKey.of("alloy/" + kind, scalars, children);
        }

        private static IllegalArgumentException unsupported(Expr expression) {
            return new IllegalArgumentException(
                    "Adaptive Boolean atom identity does not support parser node "
                            + expression.getClass().getName());
        }
    }

    private static final class LeanEncoder {
        private static final String PREFIX = "key/";
        private final Map<String, String> variables = new LinkedHashMap<>();

        String formula(StructuralAntiUnifier.Pattern pattern) {
            if (pattern.isHole()) {
                return variable(pattern.stableForm());
            }
            String symbol = pattern.symbol();
            if (symbol.equals(PREFIX + "bool/true/scalars=0")) {
                return "True";
            }
            if (symbol.equals(PREFIX + "bool/false/scalars=0")) {
                return "False";
            }
            if (symbol.equals(PREFIX + "bool/not/scalars=0")
                    && pattern.children().size() == 1) {
                return "Not (" + formula(pattern.children().get(0)) + ")";
            }
            if (symbol.equals(PREFIX + "bool/and/scalars=0")) {
                return fold("And", pattern.children());
            }
            if (symbol.equals(PREFIX + "bool/or/scalars=0")) {
                return fold("Or", pattern.children());
            }
            if (symbol.equals(PREFIX + "bool/iff/scalars=0")
                    && pattern.children().size() == 2) {
                return "(" + formula(pattern.children().get(0)) + ") <-> ("
                        + formula(pattern.children().get(1)) + ")";
            }
            if (symbol.equals(PREFIX + "bool/implies/scalars=0")
                    && pattern.children().size() == 2) {
                return "(" + formula(pattern.children().get(0)) + ") -> ("
                        + formula(pattern.children().get(1)) + ")";
            }
            return variable(pattern.stableForm());
        }

        private String fold(
                String constructor,
                List<StructuralAntiUnifier.Pattern> children) {
            if (children.isEmpty()) {
                return constructor.equals("And") ? "True" : "False";
            }
            String result = formula(children.get(children.size() - 1));
            for (int index = children.size() - 2; index >= 0; index--) {
                result = constructor + " (" + formula(children.get(index))
                        + ") (" + result + ")";
            }
            return result;
        }

        private String variable(String identity) {
            return variables.computeIfAbsent(
                    identity, ignored -> "p" + variables.size());
        }
    }
}

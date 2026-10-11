package live;

import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.*;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.A4TupleSet;

import java.util.ArrayList;
import java.util.List;

/** One legal argument assignment shared by both compared predicate bodies.
 * No signatures, atoms or model relations are added. Original declaration
 * domains, multiplicities, grouping and dependent bounds are quantified intact.
 */
final class ParameterContext {
    static final int MAX_PARAMETERS = 16;
    private static final String PRIVATE_ARGUMENT = "$lp_argument_";
    final CompModule world;
    final Func target;
    final List<ExprVar> arguments = new ArrayList<>();
    private final List<ExprVar> quantifiedArguments = new ArrayList<>();
    private final List<Decl> declarations;
    private final String declarationSource;

    ParameterContext(CompModule world, Func target, String source) {
        this.world = world;
        this.target = target;
        if (!target.isPred || target.count() > MAX_PARAMETERS) throw new IllegalArgumentException();
        declarations = new ArrayList<>(target.decls);
        List<String> slices = new ArrayList<>();
        for (Decl declaration : declarations) {
            int[] span = declaration.span().toStartEnd(source);
            if (span == null || span.length != 2 || span[0] < 0 || span[1] > source.length()
                    || span[0] >= span[1]) throw new IllegalArgumentException();
            String slice = source.substring(span[0], span[1]);
            if (declaration.expr instanceof ExprUnary unary && unary.op == ExprUnary.Op.EXACTLYOF) {
                // Predicate headers allow x = Domain, whereas quantifier
                // headers do not. Parse under a set domain, then quantify
                // with the exact equality below. Only this private wrapper
                // changes; the uploaded/public header remains byte-exact.
                int[] domain = unary.sub.span().toStartEnd(source);
                if (domain == null || domain.length != 2 || domain[0] < span[0]
                        || domain[1] > span[1] || domain[0] >= domain[1])
                    throw new IllegalArgumentException();
                List<String> names = new ArrayList<>();
                for (ExprHasName name : declaration.names) names.add(name.label);
                slices.add((declaration.disjoint != null ? "disj " : "")
                        + String.join(", ", names) + ": set ("
                        + source.substring(domain[0], domain[1]) + ")");
            } else {
                // Receiver syntax needs an explicit receiver bridge.
                if (!slice.contains(":")) throw new IllegalArgumentException();
                slices.add(slice);
            }
            for (ExprHasName name : declaration.names) {
                if (!(name instanceof ExprVar variable)
                        || !variable.label.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                        || variable.type().arity() < 1 || variable.type().arity() > 8)
                    throw new IllegalArgumentException();
                arguments.add(variable);
                quantifiedArguments.add(ExprVar.make(Pos.UNKNOWN,
                        PRIVATE_ARGUMENT + (arguments.size() - 1), variable.type()));
            }
        }
        declarationSource = String.join(", ", slices);
        if (isParameterized()) {
            for (CompModule module : world.getAllReachableModules()) {
                for (Func function : module.getAllFunc()) {
                    if (privateVariable(function.getBody())) throw new IllegalArgumentException();
                    for (Decl declaration : function.decls)
                        if (privateVariable(declaration.expr)) throw new IllegalArgumentException();
                    if (function.returnDecl != null && privateVariable(function.returnDecl))
                        throw new IllegalArgumentException();
                }
                for (var fact : module.getAllFacts())
                    if (privateVariable(fact.b)) throw new IllegalArgumentException();
                for (Sig signature : module.getAllSigs()) {
                    for (Expr fact : signature.getFacts())
                        if (privateVariable(fact)) throw new IllegalArgumentException();
                    for (Sig.Field field : signature.getFields())
                        if (privateVariable(field.decl().expr)) throw new IllegalArgumentException();
                }
            }
        }
    }

    boolean isParameterized() { return !arguments.isEmpty(); }

    Expr oracle() { return call(target); }

    Expr call(Func function) {
        if (function.count() != arguments.size() || !function.isPred)
            throw new IllegalArgumentException();
        return function.call(arguments.toArray(Expr[]::new));
    }

    /** Parse the body in this signature world, then bind its declared variables
     * to the exact shared arguments. Never parse free parameter names globally.
     */
    Expr parseBody(String body) throws Err {
        if (!isParameterized())
            return CompUtil.parseOneExpression_fromString(world, "{\n" + body + "\n}");
        Expr parsed = CompUtil.parseOneExpression_fromString(world,
                "some " + declarationSource + " | {\n" + body + "\n}").deNOP();
        if (!(parsed instanceof ExprQt quantified) || quantified.op != ExprQt.Op.SOME
                || quantified.count() != arguments.size() || privateVariable(parsed))
            throw new IllegalArgumentException();
        Expr result = quantified.sub;
        for (int index = arguments.size() - 1; index >= 0; index--) {
            if (!quantified.get(index).label.equals(arguments.get(index).label))
                throw new IllegalArgumentException();
            result = ExprLet.make(Pos.UNKNOWN, quantified.get(index), arguments.get(index), result);
        }
        if (!result.errors.isEmpty()) throw new IllegalArgumentException();
        return result;
    }

    /** Nested groups retain dependent domains. Fresh private variable labels
     * make the outer argument skolems unambiguous even when a body shadows names.
     */
    Expr quantify(Expr body) {
        if (!isParameterized()) return body;
        Expr result = body;
        int end = arguments.size();
        for (int group = declarations.size() - 1; group >= 0; group--) {
            Decl original = declarations.get(group);
            int start = end - original.names.size();
            Expr domain = original.expr;
            if (domain instanceof ExprUnary unary && unary.op == ExprUnary.Op.EXACTLYOF) {
                for (int index = end - 1; index >= start; index--)
                    result = arguments.get(index).equal(unary.sub).and(result);
                domain = ExprUnary.Op.SETOF.make(unary.pos, unary.sub);
            }
            for (int index = end - 1; index >= start; index--)
                result = ExprLet.make(Pos.UNKNOWN, arguments.get(index), quantifiedArguments.get(index), result);
            Decl renamed = new Decl(original.isPrivate, original.disjoint, original.disjoint2,
                    original.isVar, quantifiedArguments.subList(start, end), domain);
            result = ExprQt.Op.SOME.make(Pos.UNKNOWN, Pos.UNKNOWN, List.of(renamed), result);
            end = start;
        }
        if (!result.errors.isEmpty()) throw new IllegalArgumentException();
        return result;
    }

    Expr mismatch(Expr left, Expr right) { return quantify(left.iff(right).not()); }

    /** Close a new student's expression with this particular sample's argument
     * assignment. Existentially choosing a new assignment would corrupt reward.
     */
    Expr freeze(Expr body, A4Solution solution) {
        List<ExprVar> skolems = bindings(solution);
        Expr result = body;
        for (int index = arguments.size() - 1; index >= 0; index--)
            result = ExprLet.make(Pos.UNKNOWN, arguments.get(index), skolems.get(index), result);
        if (!result.errors.isEmpty()) throw new IllegalArgumentException();
        return result;
    }

    List<A4TupleSet> values(A4Solution solution) throws Err {
        List<A4TupleSet> values = new ArrayList<>();
        for (ExprVar skolem : bindings(solution)) {
            Object value = solution.eval(skolem);
            if (!(value instanceof A4TupleSet tuples)) throw new IllegalArgumentException();
            values.add(tuples);
        }
        return values;
    }

    private List<ExprVar> bindings(A4Solution solution) {
        List<ExprVar> result = new ArrayList<>();
        for (ExprVar argument : quantifiedArguments) {
            ExprVar found = null;
            for (ExprVar skolem : solution.getAllSkolems()) {
                // These are the two fixed commands owned by the live engine
                // and import validator. Match complete labels, never suffixes.
                if (!skolem.label.equals(argument.label)
                        && !skolem.label.equals("$private-import-validation_" + argument.label)) continue;
                if (found != null || skolem.type().arity() != argument.type().arity())
                    throw new IllegalArgumentException();
                found = skolem;
            }
            if (found == null) throw new IllegalArgumentException("Argument assignment unavailable");
            result.add(found);
        }
        return result;
    }

    private static boolean privateVariable(Expr expression) {
        if (expression instanceof ExprVar variable) return variable.label.startsWith(PRIVATE_ARGUMENT);
        if (expression instanceof ExprCall call) {
            for (Expr argument : call.args) if (privateVariable(argument)) return true;
        } else if (expression instanceof ExprUnary unary) return privateVariable(unary.sub);
        else if (expression instanceof ExprBinary binary)
            return privateVariable(binary.left) || privateVariable(binary.right);
        else if (expression instanceof ExprList list) {
            for (Expr argument : list.args) if (privateVariable(argument)) return true;
        } else if (expression instanceof ExprITE conditional)
            return privateVariable(conditional.cond) || privateVariable(conditional.left)
                    || privateVariable(conditional.right);
        else if (expression instanceof ExprLet binding)
            return privateVariable(binding.var) || privateVariable(binding.expr) || privateVariable(binding.sub);
        else if (expression instanceof ExprQt quantified) {
            for (Decl declaration : quantified.decls) {
                for (ExprHasName variable : declaration.names)
                    if (privateVariable(variable)) return true;
                if (privateVariable(declaration.expr)) return true;
            }
            return privateVariable(quantified.sub);
        }
        return false;
    }
}

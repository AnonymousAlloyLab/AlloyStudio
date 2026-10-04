package is.fivefivefive.CanDis.ablation;

import java.util.List;

import is.fivefivefive.CanDis.core.egraph.AblationEngine;
import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import is.fivefivefive.CanDis.core.egraph.DeBruijnVariables;
import is.fivefivefive.CanDis.core.egraph.JavaEgglog;
import is.fivefivefive.CanDis.core.egraph.JavaEgglogDeBruijn;
import is.fivefivefive.CanDis.core.egraph.RawDeBruijnEGraph;
import is.fivefivefive.CanDis.core.egraph.RawEGraph;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;

/** Fast executable regression tests for the e-graph ablation engines. */
public final class EGraphAblationTest {
    private EGraphAblationTest() {
    }

    public static void main(String[] args) {
        AlloyTerm a = AlloyTerm.atom("VAR", "a");
        AlloyTerm b = AlloyTerm.atom("VAR", "b");
        AlloyTerm andAB = AlloyTerm.node("BF/AND", a, b);
        AlloyTerm andBA = AlloyTerm.node("BF/AND", b, a);

        AblationEngine.Result rawOrder = new RawEGraph().compare(andAB, andBA);
        check(rawOrder.equivalent,
                "fixed-arity e-graph must saturate commutativity");
        AblationEngine.Result eggOrder = new JavaEgglog().compare(andAB, andBA);
        check(eggOrder.equivalent && eggOrder.distance == 0,
                "egglog core must saturate commutativity");

        AlloyTerm joinAB = AlloyTerm.node("BE/JOIN", a, b);
        AlloyTerm joinBA = AlloyTerm.node("BE/JOIN", b, a);
        check(!new RawEGraph().compare(joinAB, joinBA).equivalent,
                "associative sequence operators must retain operand order");

        AlloyTerm pA = AlloyTerm.node("P", a);
        AlloyTerm pB = AlloyTerm.node("P", b);
        check(new RawEGraph().compare(pA, pB).distance == 1,
                "an atom replacement must cost one edit");

        AlloyTerm notAnd = AlloyTerm.node("UF/NOT", andAB);
        AlloyTerm deMorgan = AlloyTerm.node("BF/OR",
                AlloyTerm.node("UF/NOT", a), AlloyTerm.node("UF/NOT", b));
        checkAllEquivalent("De Morgan", notAnd, deMorgan);

        AlloyTerm carrier = AlloyTerm.atom("SIG", "S");
        AlloyTerm noCarrier = AlloyTerm.node("UF/NO", carrier);
        AlloyTerm someCarrier = AlloyTerm.node("UF/SOME", carrier);
        AlloyTerm contradictoryBranch = AlloyTerm.node("BF/AND", a,
                AlloyTerm.node("BF/AND", noCarrier, someCarrier));
        AlloyTerm otherBranch = AlloyTerm.node("BF/AND", b, a);
        checkAllEquivalent("De Morgan after AC and atomic-dual normalization",
                AlloyTerm.node("UF/NOT",
                        AlloyTerm.node("BF/AND", contradictoryBranch, otherBranch)),
                AlloyTerm.node("BF/OR",
                        AlloyTerm.node("UF/NOT", otherBranch),
                        AlloyTerm.node("UF/NOT", contradictoryBranch)));
        AlloyTerm call = AlloyTerm.atom("CALL/CallFormula", "p");
        AlloyTerm otherCarrier = AlloyTerm.atom("SIG", "T");
        AlloyTerm relationChoice = AlloyTerm.node("BF/OR",
                AlloyTerm.node("BF/IN", carrier,
                        AlloyTerm.node("BE/PLUS", carrier, otherCarrier)),
                AlloyTerm.node("UF/SOME", AlloyTerm.atom("FIELD", "r")));
        AlloyTerm leftFlattened = AlloyTerm.node("BF/AND", List.of(
                call, noCarrier, someCarrier, someCarrier, relationChoice, noCarrier));
        AlloyTerm rightFirstGroup = AlloyTerm.node("BF/AND", relationChoice, noCarrier);
        AlloyTerm rightSecondGroup = AlloyTerm.node("BF/AND", List.of(
                call, noCarrier, someCarrier, someCarrier));
        checkAllEquivalent("De Morgan over parser-flattened AC groups",
                AlloyTerm.node("UF/NOT", leftFlattened),
                AlloyTerm.node("BF/OR",
                        AlloyTerm.node("UF/NOT", rightFirstGroup),
                        AlloyTerm.node("UF/NOT", rightSecondGroup)));

        AlloyTerm implication = AlloyTerm.node("BF/IMPLIES", a, b);
        AlloyTerm disjunction = AlloyTerm.node("BF/OR", AlloyTerm.node("UF/NOT", a), b);
        checkAllEquivalent("implication elimination", implication, disjunction);
        AblationEngine.Result implicationResult = new JavaEgglog().compare(implication, disjunction);
        check(implicationResult.equivalent && implicationResult.distance == 0,
                "egglog core must eliminate implication");
        AlloyTerm nearDisjunction = AlloyTerm.node("BOOL/OR",
                AlloyTerm.node("BOOL/NOT", a), AlloyTerm.atom("VAR", "c"));
        check(new JavaEgglog().compare(implication, nearDisjunction).distance == 1,
                "egglog distance must minimize over retained rewrite roots");

        AlloyTerm duplicateAnd = AlloyTerm.node("BF/AND", a, a);
        AlloyTerm duplicateUnion = AlloyTerm.node("BE/PLUS", a, a);
        AlloyTerm duplicateIntersection = AlloyTerm.node("BE/INTERSECT", a, a);
        checkAllEquivalent("boolean conjunction idempotence", duplicateAnd, a);
        checkAllEquivalent("relational union idempotence", duplicateUnion, a);
        checkAllEquivalent("relational intersection idempotence", duplicateIntersection, a);
        checkAllEquivalent("nested ACI flattening",
                AlloyTerm.node("BF/AND", AlloyTerm.node("BF/AND", a, b), a),
                AlloyTerm.node("BF/AND", b, a));
        checkAllDistinct("JOIN without a dependent typed-chain certificate",
                AlloyTerm.node("BE/JOIN", AlloyTerm.node("BE/JOIN", a, b),
                        AlloyTerm.atom("VAR", "c")),
                AlloyTerm.node("BE/JOIN", a,
                        AlloyTerm.node("BE/JOIN", b, AlloyTerm.atom("VAR", "c"))));
        check(!new JavaEgglog().compare(AlloyTerm.node("BE/IPLUS", a, a), a).equivalent,
                "AC bag operators must retain duplicate operands");

        AlloyTerm trueTerm = AlloyTerm.atom("CONST", "true");
        AlloyTerm falseTerm = AlloyTerm.atom("CONST", "false");
        AlloyTerm noneTerm = AlloyTerm.atom("CONST", "none");
        AlloyTerm univTerm = AlloyTerm.atom("CONST", "univ");
        checkAllEquivalent("and true identity", AlloyTerm.node("BF/AND", a, trueTerm), a);
        checkAllEquivalent("or false identity", AlloyTerm.node("BF/OR", a, falseTerm), a);
        checkAllEquivalent("and false annihilator", AlloyTerm.node("BF/AND", a, falseTerm), falseTerm);
        checkAllEquivalent("or true annihilator", AlloyTerm.node("BF/OR", a, trueTerm), trueTerm);
        checkAllEquivalent("boolean excluded middle",
                AlloyTerm.node("BF/OR", a, AlloyTerm.node("UF/NOT", a)), trueTerm);
        checkAllDistinct("unproved membership in none",
                AlloyTerm.node("BF/IN", a, noneTerm), falseTerm);
        checkAllEquivalent("none is a subset of none",
                AlloyTerm.node("BF/IN", noneTerm, noneTerm), trueTerm);
        checkAllDistinct("synthetic unary label is not nonempty evidence",
                AlloyTerm.node(
                        "BF/IN", AlloyTerm.node("UE/ONE", a), noneTerm),
                falseTerm);
        checkAllEquivalent("membership in univ",
                AlloyTerm.node("BF/IN", a, univTerm), trueTerm);
        checkAllEquivalent("intersection with none",
                AlloyTerm.node("BE/INTERSECT", a, noneTerm), noneTerm);
        checkAllEquivalent("union with none",
                AlloyTerm.node("BE/PLUS", a, noneTerm), a);

        AlloyTerm parsedNone = AlloyTerm.node(
                "UE/NOOP", AlloyTerm.atom("CONST", "none"));
        AlloyTerm parsedCarrier = AlloyTerm.node(
                "UE/NOOP", AlloyTerm.atom("SIG", "A"));
        AlloyTerm boundX = AlloyTerm.atom("VAR", "x");
        AlloyTerm falseBody = AlloyTerm.node("UF/SOME", parsedNone);
        AlloyTerm trueBody = AlloyTerm.node("UF/NO", parsedNone);
        checkAllEquivalent(
                "bare-one parameter authority",
                predicateWithDomain(
                        "x",
                        parsedCarrier,
                        AlloyTerm.node("BF/IN", boundX, parsedNone)),
                predicateWithDomain("x", parsedCarrier, falseBody));
        checkAllEquivalent(
                "bare-one parameter authority under not-in",
                predicateWithDomain(
                        "x",
                        parsedCarrier,
                        AlloyTerm.node("BF/NOT_IN", boundX, parsedNone)),
                predicateWithDomain("x", parsedCarrier, trueBody));
        AlloyTerm someCarrierDomain = AlloyTerm.node("UE/SOME", parsedCarrier);
        checkAllEquivalent(
                "some parameter authority",
                predicateWithDomain(
                        "x",
                        someCarrierDomain,
                        AlloyTerm.node("BF/IN", boundX, parsedNone)),
                predicateWithDomain("x", someCarrierDomain, falseBody));
        checkAllEquivalent(
                "some parameter authority under not-in",
                predicateWithDomain(
                        "x",
                        someCarrierDomain,
                        AlloyTerm.node("BF/NOT_IN", boundX, parsedNone)),
                predicateWithDomain("x", someCarrierDomain, trueBody));
        for (String multiplicity : List.of("UE/SETOF", "UE/LONE")) {
            AlloyTerm nullableDomain = AlloyTerm.node(multiplicity, parsedCarrier);
            checkAllDistinct(
                    multiplicity + " parameter does not prove nonemptiness",
                    predicateWithDomain(
                            "x",
                            nullableDomain,
                            AlloyTerm.node("BF/IN", boundX, parsedNone)),
                    predicateWithDomain("x", nullableDomain, falseBody));
        }
        AlloyTerm shadowedSet = quantifiedWithDomain(
                "QF/ALL",
                "x",
                AlloyTerm.node("UE/SETOF", parsedCarrier),
                AlloyTerm.node("BF/IN", boundX, parsedNone));
        AlloyTerm shadowedFalse = quantifiedWithDomain(
                "QF/ALL",
                "x",
                AlloyTerm.node("UE/SETOF", parsedCarrier),
                falseBody);
        checkAllDistinct(
                "inner nullable binding shadows an outer positive parameter",
                predicateWithDomain("x", parsedCarrier, shadowedSet),
                predicateWithDomain("x", parsedCarrier, shadowedFalse));

        AlloyTerm iff = AlloyTerm.node("BF/IFF", a, b);
        AlloyTerm iffExpansion = AlloyTerm.node("BF/AND",
                AlloyTerm.node("BF/OR", AlloyTerm.node("UF/NOT", a), b),
                AlloyTerm.node("BF/OR", AlloyTerm.node("UF/NOT", b), a));
        checkAllEquivalent("iff elimination", iff, iffExpansion);
        AlloyTerm ite = AlloyTerm.node("ITE/FORMULA", a, b, falseTerm);
        AlloyTerm iteExpansion = AlloyTerm.node("BF/OR",
                AlloyTerm.node("BF/AND", a, b),
                AlloyTerm.node("BF/AND", AlloyTerm.node("UF/NOT", a), falseTerm));
        checkAllEquivalent("formula ITE elimination", ite, iteExpansion);

        AlloyTerm allX = quantified("QF/ALL", "x", "S",
                AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")));
        AlloyTerm someNotX = quantified("QF/SOME", "x", "S",
                AlloyTerm.node("UF/NOT", AlloyTerm.node("P", AlloyTerm.atom("VAR", "x"))));
        checkAllEquivalent("negated universal quantifier", AlloyTerm.node("UF/NOT", allX), someNotX);
        AlloyTerm noX = quantified("QF/NO", "x", "S",
                AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")));
        checkAllEquivalent("no quantifier expansion", noX, quantified("QF/ALL", "x", "S",
                AlloyTerm.node("UF/NOT", AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")))));
        AlloyTerm oneX = quantified("QF/ONE", "x", "S",
                AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")));
        AlloyTerm notOneX = quantified("QF/NOTONE", "x", "S",
                AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")));
        checkAllEquivalent("negated one quantifier", AlloyTerm.node("UF/NOT", oneX), notOneX);
        AlloyTerm loneX = quantified("QF/LONE", "x", "S",
                AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")));
        AlloyTerm notLoneX = quantified("QF/NOTLONE", "x", "S",
                AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")));
        checkAllEquivalent("negated lone quantifier", AlloyTerm.node("UF/NOT", loneX), notLoneX);
        checkAllEquivalent("empty existential domain",
                quantifiedWithDomain("QF/SOME", "x", noneTerm,
                        AlloyTerm.node("P", AlloyTerm.atom("VAR", "x"))),
                falseTerm);
        checkAllEquivalent("empty universal domain",
                quantifiedWithDomain("QF/ALL", "x", noneTerm,
                        AlloyTerm.node("P", AlloyTerm.atom("VAR", "x"))),
                trueTerm);
        checkAllDistinct("set none admits its empty binding",
                quantifiedWithDomain(
                        "QF/SOME",
                        "x",
                        AlloyTerm.node("UE/SETOF", noneTerm),
                        trueTerm),
                falseTerm);
        checkAllDistinct("lone none admits its empty binding",
                quantifiedWithDomain(
                        "QF/ALL",
                        "x",
                        AlloyTerm.node("UE/LONE", noneTerm),
                        falseTerm),
                trueTerm);
        checkAllEquivalent("one none has no admissible binding",
                quantifiedWithDomain(
                        "QF/SOME",
                        "x",
                        AlloyTerm.node("UE/ONE", noneTerm),
                        trueTerm),
                falseTerm);
        checkAllEquivalent("false existential body",
                quantified("QF/SOME", "x", "S", falseTerm), falseTerm);
        checkAllEquivalent("true universal body",
                quantified("QF/ALL", "x", "S", trueTerm), trueTerm);
        checkAllEquivalent("false no body",
                quantified("QF/NO", "x", "S", falseTerm), trueTerm);
        checkAllEquivalent("false one body",
                quantified("QF/ONE", "x", "S", falseTerm), falseTerm);
        checkAllEquivalent("false lone body",
                quantified("QF/LONE", "x", "S", falseTerm), trueTerm);

        AlloyTerm someP = quantified("QF/SOME", "x", "S",
                AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")));
        AlloyTerm prenexSome = quantified("QF/SOME", "x", "S",
                AlloyTerm.node("BF/AND", AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")), b));
        checkAllEquivalent("safe existential prenex", AlloyTerm.node("BF/AND", someP, b), prenexSome);
        AlloyTerm prenexAll = quantified("QF/ALL", "x", "S",
                AlloyTerm.node("BF/OR", AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")), b));
        checkAllEquivalent("safe universal prenex", AlloyTerm.node("BF/OR", allX, b), prenexAll);

        checkAllEquivalent("future temporal dual",
                AlloyTerm.node("UF/NOT", AlloyTerm.node("UF/ALWAYS", a)),
                AlloyTerm.node("UF/EVENTUALLY", AlloyTerm.node("UF/NOT", a)));
        checkAllEquivalent("binary temporal dual",
                AlloyTerm.node("UF/NOT", AlloyTerm.node("BF/UNTIL", a, b)),
                AlloyTerm.node("BF/RELEASES",
                        AlloyTerm.node("UF/NOT", a), AlloyTerm.node("UF/NOT", b)));

        AlloyTerm alphaLeft = predicate("x", AlloyTerm.node("P", AlloyTerm.atom("VAR", "x")));
        AlloyTerm alphaRight = predicate("renamed", AlloyTerm.node("P", AlloyTerm.atom("VAR", "renamed")));
        check(!new JavaEgglog().compare(alphaLeft, alphaRight).equivalent,
                "ordinary e-graph must retain literal identifiers");
        check(DeBruijnVariables.encode(alphaLeft).equals(DeBruijnVariables.encode(alphaRight)),
                "De Bruijn terms must erase bound identifier spelling");
        check(new RawDeBruijnEGraph().compare(alphaLeft, alphaRight).equivalent,
                "fixed-arity De Bruijn e-graph must preserve alpha-equivalence");
        check(new JavaEgglogDeBruijn().compare(alphaLeft, alphaRight).equivalent,
                "egglog De Bruijn e-graph must preserve alpha-equivalence");
        AblationEngine.Result alphaResult = new SlottedEGraph().compare(alphaLeft, alphaRight);
        check(alphaResult.equivalent && alphaResult.distance == 0,
                "slotted e-graph must preserve alpha-equivalence");

        AlloyTerm permutationLeft = quantified(
                "QF/ALL", List.of("x", "y"), "User",
                AlloyTerm.node("F", AlloyTerm.atom("VAR", "x"), AlloyTerm.atom("VAR", "y")));
        AlloyTerm permutationRight = quantified(
                "QF/ALL", List.of("x", "y"), "User",
                AlloyTerm.node("F", AlloyTerm.atom("VAR", "y"), AlloyTerm.atom("VAR", "x")));
        check(new SlottedEGraph().compare(permutationLeft, permutationRight).equivalent,
                "same-declaration formula-quantifier slots must form a permutation group");

        AlloyTerm aliased = quantified(
                "QF/ALL", List.of("x", "y"), "User",
                AlloyTerm.node("F", AlloyTerm.atom("VAR", "x"), AlloyTerm.atom("VAR", "x")));
        AblationEngine.Result aliasResult = new SlottedEGraph().compare(aliased, permutationLeft);
        check(!aliasResult.equivalent && aliasResult.distance > 0,
                "slot shapes must preserve aliasing patterns");

        AlloyTerm shadowed = quantified("QF/ALL", "x", "Outer",
                quantified("QF/SOME", "x", "Inner",
                        AlloyTerm.node("P", AlloyTerm.atom("VAR", "x"))));
        AlloyTerm shadowRenamed = quantified("QF/ALL", "outer", "Outer",
                quantified("QF/SOME", "inner", "Inner",
                        AlloyTerm.node("P", AlloyTerm.atom("VAR", "inner"))));
        AlloyTerm capturesOuter = quantified("QF/ALL", "outer", "Outer",
                quantified("QF/SOME", "inner", "Inner",
                        AlloyTerm.node("P", AlloyTerm.atom("VAR", "outer"))));
        check(new SlottedEGraph().compare(shadowed, shadowRenamed).equivalent,
                "lexically shadowed slots must remain alpha-equivalent");
        check(!new SlottedEGraph().compare(shadowed, capturesOuter).equivalent,
                "inner and outer slots must remain distinct under shadowing");
        check(new RawDeBruijnEGraph().compare(shadowed, shadowRenamed).equivalent,
                "De Bruijn indices must preserve alpha-equivalence under shadowing");
        check(!new RawDeBruijnEGraph().compare(shadowed, capturesOuter).equivalent,
                "De Bruijn index zero and index one must remain distinct");

        AlloyTerm freeLeft = predicate("x", AlloyTerm.node("F",
                AlloyTerm.atom("VAR", "x"), AlloyTerm.atom("VAR", "freeA")));
        AlloyTerm freeRight = predicate("renamed", AlloyTerm.node("F",
                AlloyTerm.atom("VAR", "renamed"), AlloyTerm.atom("VAR", "freeB")));
        check(!new JavaEgglogDeBruijn().compare(freeLeft, freeRight).equivalent,
                "De Bruijn encoding must retain free-variable names");
        check(!new JavaEgglogDeBruijn().compare(permutationLeft, permutationRight).equivalent,
                "plain De Bruijn encoding must not add declaration permutation groups");

        AlloyTerm dependentLeft = dependentQuantifier("x", "y");
        AlloyTerm dependentRight = dependentQuantifier("outer", "inner");
        AlloyTerm dependentEncoded = DeBruijnVariables.encode(dependentLeft);
        check(dependentEncoded.equals(DeBruijnVariables.encode(dependentRight)),
                "dependent declaration domains must be alpha-equivalent");
        check(dependentEncoded.toString().contains("F(VAR(@db:1),VAR(@db:0))"),
                "nested binders must use nearest-binder De Bruijn indices");

        AlloyTerm captureUnsafeLet = quantified("QF/ALL", "y", "S",
                AlloyTerm.node("LetExpr",
                        AlloyTerm.atom("VAR", "x"),
                        AlloyTerm.atom("VAR", "y"),
                        AlloyTerm.node("Body", quantified("QF/SOME", "y", "S",
                                AlloyTerm.node("BF/NOT_EQUALS",
                                        AlloyTerm.atom("VAR", "x"), AlloyTerm.atom("VAR", "y"))))));
        AlloyTerm capturedResult = quantified("QF/ALL", "y", "S",
                quantified("QF/SOME", "y", "S",
                        AlloyTerm.node("BF/NOT_EQUALS",
                                AlloyTerm.atom("VAR", "y"), AlloyTerm.atom("VAR", "y"))));
        check(!new JavaEgglog().compare(captureUnsafeLet, capturedResult).equivalent,
                "egglog beta reduction must avoid capture by shadowing quantifiers");
        check(!new JavaEgglogDeBruijn().compare(captureUnsafeLet, capturedResult).equivalent,
                "De Bruijn egglog beta reduction must avoid capture by shadowing quantifiers");

        AlloyTerm simpleLet = AlloyTerm.node("LetExpr",
                AlloyTerm.atom("VAR", "x"), a,
                AlloyTerm.node("Body", AlloyTerm.node("P", AlloyTerm.atom("VAR", "x"))));
        check(new JavaEgglog().compare(simpleLet, AlloyTerm.node("P", a)).equivalent,
                "capture-safe beta reduction must still eliminate ordinary lets");
        check(new JavaEgglogDeBruijn().compare(simpleLet, AlloyTerm.node("P", a)).equivalent,
                "De Bruijn egglog must retain ordinary beta equivalence");

        AlloyTerm comprehensionLeft = comprehension(
                AlloyTerm.node("BF/IN", AlloyTerm.atom("VAR", "y"),
                        AlloyTerm.node("BE/JOIN", AlloyTerm.atom("VAR", "x"), AlloyTerm.atom("FIELD", "r"))));
        AlloyTerm comprehensionRight = comprehension(
                AlloyTerm.node("BF/IN", AlloyTerm.atom("VAR", "x"),
                        AlloyTerm.node("BE/JOIN", AlloyTerm.atom("VAR", "y"), AlloyTerm.atom("FIELD", "r"))));
        check(!new SlottedEGraph().compare(comprehensionLeft, comprehensionRight).equivalent,
                "comprehension columns are ordered and must not form a permutation group");

        AblationEngine.Result redundant = new SlottedEGraph().compare(
                AlloyTerm.node("BF/AND", a, falseTerm), falseTerm);
        check(redundant.equivalent, "slotted saturation must eliminate false conjunctions");
        check(redundant.stats.redundantSlots > 0,
                "slot-redundancy elimination must be observable in the statistics");

        System.out.println("EGraph ablation tests passed.");
    }

    private static void checkAllEquivalent(String rule, AlloyTerm left, AlloyTerm right) {
        check(new RawEGraph().compare(left, right).equivalent,
                "fixed-arity e-graph must apply " + rule);
        check(new RawDeBruijnEGraph().compare(left, right).equivalent,
                "fixed-arity De Bruijn e-graph must apply " + rule);
        check(new JavaEgglog().compare(left, right).equivalent,
                "egglog core must apply " + rule);
        check(new JavaEgglogDeBruijn().compare(left, right).equivalent,
                "egglog De Bruijn core must apply " + rule);
        check(new SlottedEGraph().compare(left, right).equivalent,
                "slotted e-graph must apply " + rule);
    }

    private static void checkAllDistinct(String rule, AlloyTerm left, AlloyTerm right) {
        check(!new RawEGraph().compare(left, right).equivalent,
                "fixed-arity e-graph must preserve " + rule);
        check(!new RawDeBruijnEGraph().compare(left, right).equivalent,
                "fixed-arity De Bruijn e-graph must preserve " + rule);
        check(!new JavaEgglog().compare(left, right).equivalent,
                "egglog core must preserve " + rule);
        check(!new JavaEgglogDeBruijn().compare(left, right).equivalent,
                "egglog De Bruijn core must preserve " + rule);
        check(!new SlottedEGraph().compare(left, right).equivalent,
                "slotted e-graph must preserve " + rule);
    }

    private static AlloyTerm predicate(String variable, AlloyTerm body) {
        return predicate(List.of(variable), body);
    }

    private static AlloyTerm predicate(List<String> variables, AlloyTerm body) {
        List<AlloyTerm> declarationChildren = new java.util.ArrayList<>();
        for (String variable : variables) {
            declarationChildren.add(AlloyTerm.atom("VAR", variable));
        }
        declarationChildren.add(AlloyTerm.atom("SIG", "User"));
        AlloyTerm declaration = AlloyTerm.node(
                "DECL/ParamDecl/disj=false/var=false", declarationChildren);
        return AlloyTerm.node("PREDICATE", declaration, AlloyTerm.node("Body", body));
    }

    private static AlloyTerm predicateWithDomain(
            String variable,
            AlloyTerm domain,
            AlloyTerm body) {
        AlloyTerm declaration = AlloyTerm.node(
                "DECL/ParamDecl/disj=false/var=false",
                AlloyTerm.atom("VAR", variable),
                domain);
        return AlloyTerm.node(
                "PREDICATE", declaration, AlloyTerm.node("Body", body));
    }

    private static AlloyTerm quantified(String head, String variable, String domain, AlloyTerm body) {
        return quantified(head, List.of(variable), domain, body);
    }

    private static AlloyTerm quantifiedWithDomain(
            String head, String variable, AlloyTerm domain, AlloyTerm body) {
        AlloyTerm declaration = AlloyTerm.node(
                "DECL/VarDecl/disj=false/var=false",
                AlloyTerm.atom("VAR", variable), domain);
        return AlloyTerm.node(head, declaration, AlloyTerm.node("Body", body));
    }

    private static AlloyTerm quantified(String head, List<String> variables, String domain, AlloyTerm body) {
        List<AlloyTerm> declarationChildren = new java.util.ArrayList<>();
        for (String variable : variables) {
            declarationChildren.add(AlloyTerm.atom("VAR", variable));
        }
        declarationChildren.add(AlloyTerm.atom("SIG", domain));
        AlloyTerm declaration = AlloyTerm.node(
                "DECL/VarDecl/disj=false/var=false",
                declarationChildren);
        return AlloyTerm.node(head, declaration, AlloyTerm.node("Body", body));
    }

    private static AlloyTerm comprehension(AlloyTerm body) {
        AlloyTerm declaration = AlloyTerm.node(
                "DECL/VarDecl/disj=false/var=false",
                AlloyTerm.atom("VAR", "x"), AlloyTerm.atom("VAR", "y"), AlloyTerm.atom("SIG", "S"));
        return AlloyTerm.node("QE/COMPREHENSION", declaration, AlloyTerm.node("Body", body));
    }

    private static AlloyTerm dependentQuantifier(String outer, String inner) {
        AlloyTerm outerDeclaration = AlloyTerm.node(
                "DECL/VarDecl/disj=false/var=false",
                AlloyTerm.atom("VAR", outer), AlloyTerm.atom("SIG", "S"));
        AlloyTerm innerDeclaration = AlloyTerm.node(
                "DECL/VarDecl/disj=false/var=false",
                AlloyTerm.atom("VAR", inner),
                AlloyTerm.node("BE/JOIN",
                        AlloyTerm.atom("VAR", outer), AlloyTerm.atom("FIELD", "r")));
        return AlloyTerm.node("QF/ALL", outerDeclaration, innerDeclaration,
                AlloyTerm.node("Body", AlloyTerm.node("F",
                        AlloyTerm.atom("VAR", outer), AlloyTerm.atom("VAR", inner))));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

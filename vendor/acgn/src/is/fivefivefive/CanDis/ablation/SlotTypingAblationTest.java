package is.fivefivefive.CanDis.ablation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.LetExpr;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;

/** Bounded correspondence regressions for the matched slot-color treatment. */
public final class SlotTypingAblationTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        AlloyTerm a = AlloyTerm.typedVariable("x", "A");
        AlloyTerm b = AlloyTerm.typedVariable("x", "B");
        check(!a.equals(b) && a.compareTo(b) != 0, "type participates in term identity/order");
        check(a.renameVariable("y").slotType().equals("A"), "renaming retains type");
        check(a.eraseSlotTypes().equals(b.eraseSlotTypes()), "erasure negative control");
        check(new SlottedEGraph().compare(a, b).equivalent, "untyped slots ignore colors");
        check(!new SlottedEGraph(true).compare(a, b).equivalent, "typed slot shapes differ");
        check(new SlottedEGraph(true).compare(a, b).distance == 1, "fallback distance retains color");
        check(new SlottedEGraph(true).compare(a, a.renameVariable("y")).equivalent,
                "same-type alpha mapping remains available");
        boolean rejected = false;
        try {
            new SlottedEGraph(true).compare(AlloyTerm.atom("VAR", "x"), a);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        check(rejected, "typed engine rejects missing types");
        AlloyTerm typedLet = AlloyTerm.node("LetExpr", a, a.renameVariable("z"),
                AlloyTerm.node("UF/SOME", a));
        AlloyTerm beta = SlottedEGraph.normalForm(typedLet);
        check(allSlotsTyped(beta), "beta reduction retains type metadata");
        check(new SlottedEGraph(true).compare(typedLet,
                AlloyTerm.node("UF/SOME", a.renameVariable("z"))).equivalent, "typed beta reduction");
        AlloyTerm mixed = AlloyTerm.node("BE/ARROW", a, b.renameVariable("y"));
        AlloyTerm swapped = AlloyTerm.node("BE/ARROW", b, a.renameVariable("y"));
        check(new SlottedEGraph().compare(mixed, swapped).equivalent, "erased mixed-type alignment control");
        check(!new SlottedEGraph(true).compare(mixed, swapped).equivalent,
                "ordered cross-type mapping is inadmissible");
        check(new SlottedEGraph(true).compare(mixed,
                AlloyTerm.node("BE/ARROW", a.renameVariable("u"), b.renameVariable("v"))).equivalent,
                "ordered same-type mapping remains admissible");

        List<Predicate> predicates = new ArrayList<>();
        collect(new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {} sig B {}\n"
                + "pred p { all x,y: A | x in y }\n"
                + "pred q { all u,v: A | v in u }\n"
                + "pred r { all x,y: B | x in y }\n"
                + "pred s { all x: A | some x: B | some x }\n"
                + "pred t { all u: A | some v: B | some v }\n"
                + "pred f[x:A] { some x }\n"
                + "pred g[x:B] { some x }\n")), predicates);
        AlloyTerm p = term(predicates, "p"), q = term(predicates, "q"), r = term(predicates, "r");
        check(allSlotsTyped(p), "parser provides types to declaration and usage occurrences");
        check(new SlottedEGraph(true).compare(p, q).equivalent, "typed declaration permutation");
        check(!new SlottedEGraph(true).compare(p, r).equivalent, "different declared domains retained");
        check(new SlottedEGraph(true).compare(term(predicates, "s"), term(predicates, "t")).equivalent,
                "scope-aware typed alpha renaming under shadowing");
        AlloyTerm f = AlloyAstTermAdapter.fromAst(find(predicates, "f").getBody(), true);
        AlloyTerm g = AlloyAstTermAdapter.fromAst(find(predicates, "g").getBody(), true);
        check(new SlottedEGraph().compare(f, g).equivalent, "parsed body type-erasure control");
        check(!new SlottedEGraph(true).compare(f, g).equivalent, "parsed body type distinction");
        for (Predicate predicate : predicates) {
            check(AlloyAstTermAdapter.fromPredicate(predicate).equals(
                    AlloyAstTermAdapter.fromPredicate(predicate, true).eraseSlotTypes()),
                    "treatment erasure reproduces old AST adapter: " + predicate.getName());
        }
        check(new SlottedEGraph(true).compare(AlloyTerm.node("BF/AND", f, f), f).equivalent,
                "typed ACI duplicate elimination");
        checkEightBindingPermutation();
        checkSlotCensus();
        checkWrappedBindingCensus();
        System.out.println("SlotTypingAblationTest: " + checks + " checks passed");
    }

    private static void checkWrappedBindingCensus() throws Exception {
        List<Predicate> predicates = new ArrayList<>();
        collect(new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {} sig B {}\n"
                + "pred binary { let r = A->A | some r and r in A->A }\n"
                + "pred mixed { let r = A->B | some r }\n"
                + "pred nested { let r = A->A | let r = r | some r }\n"
                + "pred transposed[r:A->A] { let s = ~r | some s }\n"
                + "pred comprehension { let r = {x,y:A | x in y} | some r }\n")), predicates);
        String unary = "RELATION[1:A]", binary = "RELATION[1:A1:A]";
        check(SlotCensus.fromPredicate(find(predicates, "binary")).equals(
                new SlotCensus(1, Map.of(binary, 1L))),
                "NOOP-wrapped binary let binder counted once with exact type, not per usage");
        check(SlotCensus.fromPredicate(find(predicates, "mixed")).equals(
                new SlotCensus(1, Map.of("RELATION[1:A1:B]", 1L))),
                "heterogeneous let binder retains both type columns");
        check(SlotCensus.fromPredicate(find(predicates, "nested")).equals(
                new SlotCensus(2, Map.of(binary, 2L))),
                "shadowed relation let bindings count separately");
        check(SlotCensus.fromPredicate(find(predicates, "comprehension")).equals(
                new SlotCensus(3, Map.of(binary, 1L, unary, 2L))),
                "wrapped binder and comprehension domain bindings count once each");
        // Exercise binder admission with real parsed non-binding expressions, without mutating ASTs.
        var count = SlotCensus.class.getDeclaredMethod("count", Node.class, Map.class);
        count.setAccessible(true);
        for (String name : List.of("binary", "transposed")) {
            Map<String, Long> counts = new java.util.TreeMap<>();
            boolean rejected = false;
            try {
                count.invoke(null, firstLet(find(predicates, name)).getBound(), counts);
            } catch (java.lang.reflect.InvocationTargetException expected) {
                rejected = expected.getCause() instanceof IllegalArgumentException;
            }
            check(rejected && counts.isEmpty(), "non-variable expression rejected without counting: " + name);
        }
    }

    private static LetExpr firstLet(Node node) {
        if (node instanceof LetExpr let) return let;
        if (node.getChildren() != null) {
            for (Node child : node.getChildren()) {
                if (child == null) continue;
                LetExpr found = firstLet(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void checkSlotCensus() throws Exception {
        List<Predicate> predicates = new ArrayList<>();
        collect(new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {} sig B {}\n"
                + "pred p[a:A] { all x,y:A | (some x:B | some x) and (let z=a | z in A) }\n"
                + "pred q { all x:A | once some x implies always some x }\n"
                + "pred r { some {x:A | some x} }\n"
                + "pred empty { some A }\n")), predicates);
        SlotCensus census = SlotCensus.fromPredicate(find(predicates, "p"));
        check(census.total() == 5, "parameters, unused, shadowed, and let binders counted once");
        check(census.byType().size() == 2 && census.byType().containsValue(4L)
                && census.byType().containsValue(1L), "source slots partitioned by exact type");
        check(SlotCensus.fromPredicate(find(predicates, "q")).total() == 1,
                "sibling temporal usages do not duplicate source binder");
        check(SlotCensus.fromPredicate(find(predicates, "r")).total() == 1, "comprehension binder counted");
        check(SlotCensus.fromPredicate(find(predicates, "empty")).total() == 0, "zero slots is measured zero");
        for (boolean typed : List.of(false, true)) {
            AlloyTerm p = AlloyAstTermAdapter.fromPredicate(find(predicates, "p"), typed);
            var stats = new SlottedEGraph(typed).compare(p, p).stats;
            check(stats.slotsByType.values().stream().mapToLong(Long::longValue).sum() == stats.slots,
                    "interface type histogram partitions slot total");
            check(typed || stats.slotsByType.keySet().equals(java.util.Set.of("<erased>")),
                    "erased slots are not reported as univ");
        }
    }

    private static void checkEightBindingPermutation() throws Exception {
        List<Predicate> predicates = new ArrayList<>();
        collect(new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {} one sig Store { r: A->A->A->A->A->A->A->A }\n"
                + "pred p { all a,b,c,d,e,f,g,h:A | a->b->c->d->e->f->g->h in Store.r }\n"
                + "pred q { all a,b,c,d,e,f,g,h:A | h->g->f->e->d->c->b->a in Store.r }\n")), predicates);
        AlloyTerm left = term(predicates, "p"), right = term(predicates, "q");
        check(!left.equals(right), "eight-binding witness is structurally distinct");
        check(SlottedEGraph.alphaRepresentative(left).equals(SlottedEGraph.alphaRepresentative(right)),
                "typed eight-binding reversal beyond the former 720-candidate prefix");
        check(SlottedEGraph.alphaRepresentative(left.eraseSlotTypes()).equals(
                        SlottedEGraph.alphaRepresentative(right.eraseSlotTypes())),
                "untyped eight-binding reversal beyond the former 720-candidate prefix");
    }

    private static boolean allSlotsTyped(AlloyTerm term) {
        return (!term.isVariable() || !term.slotType().isEmpty())
                && term.children().stream().allMatch(SlotTypingAblationTest::allSlotsTyped);
    }

    private static void collect(Node node, List<Predicate> predicates) {
        if (node instanceof Predicate) {
            predicates.add((Predicate) node);
            return;
        }
        if (node.getChildren() != null) {
            for (Node child : node.getChildren()) {
                if (child != null) collect(child, predicates);
            }
        }
    }

    private static Predicate find(List<Predicate> predicates, String name) {
        return predicates.stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }

    private static AlloyTerm term(List<Predicate> predicates, String name) {
        return AlloyAstTermAdapter.fromPredicate(find(predicates, name), true);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

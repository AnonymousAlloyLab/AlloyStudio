package is.fivefivefive.CanDis.core.egraph;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import parser.ast.nodes.ModelUnit;

/** Exhaustive finite checks of the generator, separate from source semantic controls. */
public final class SlotPermutationSearchTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        AlloyTerm mixed = declaration("A", "A", "A", "A", "B", "B", "B", "B");
        verify(mixed, 576, true);
        verify(mixed, 576, false);
        verify(mixed.eraseSlotTypes(), 40_320, false);
        verify(declaration("A", "A", "B", "B", "C", "C", "D", "D"), 16, true);
        verify(declaration("A", "B", "C", "D", "E", "F", "G", "H"), 1, true);
        verify(declaration("A", "A", "A", "A", "A", "A", "A", "A"), 40_320, false);
        var model = new ModelUnit(null, CompUtil.parseEverything_fromString(A4Reporter.NOP,
                "sig A {} sig B {} pred p { all a,b,c,d:A, e,f,g,h:B | some a and some e }\n"
                + "pred q { all x:A | some y:B | x != y }\n"
                + "pred r { all disj a,b:A, disj c,d:A | a != c }\n"));
        for (var predicate : model.getPredDeclList()) {
            long expected = switch (predicate.getName()) { case "p" -> 576; case "r" -> 4; default -> 1; };
            verify(AlloyAstTermAdapter.fromPredicate(predicate, true), expected, false);
            verify(AlloyAstTermAdapter.fromPredicate(predicate, false),
                    predicate.getName().equals("p") ? 40_320 : expected, false);
        }
        System.out.println("SlotPermutationSearchTest: " + checks + " checks passed");
    }

    private static AlloyTerm declaration(String... types) {
        List<AlloyTerm> slots = new ArrayList<>();
        for (int i = 0; i < types.length; i++) slots.add(AlloyTerm.typedVariable("s" + i, types[i]));
        slots.add(AlloyTerm.atom("SIG", "Domain"));
        return AlloyTerm.node("QF/ALL", AlloyTerm.node("DECL/RelDecl/disj=false/var=false", slots),
                AlloyTerm.atom("CONST", "true"));
    }

    private static void verify(AlloyTerm input, long expected, boolean uncapped) {
        Map<String, String> types = new HashMap<>();
        collectTypes(input, types);
        Map<String, String> targetTypes = new HashMap<>();
        Set<Map<String, String>> observed = new HashSet<>();
        long count = SlotPermutationSearch.forEach(input, uncapped, mapping -> {
            check(new HashSet<>(mapping.values()).size() == mapping.size(), "candidate is injective");
            for (var entry : mapping.entrySet()) {
                String type = types.get(entry.getKey());
                String targetType = targetTypes.putIfAbsent(entry.getValue(), type);
                check(targetType == null || targetType.equals(type), "no cross-type candidate generated");
            }
            check(observed.add(Map.copyOf(mapping)), "no duplicate candidate");
        });
        check(count == expected && observed.size() == expected, "exact product-of-factorials coverage");
        System.out.println("permutation candidates=" + count + " uncapped=" + uncapped);
    }

    private static void collectTypes(AlloyTerm input, Map<String, String> types) {
        if (input.isVariable()) types.put(input.atom(), input.slotType());
        for (AlloyTerm child : input.children()) collectTypes(child, types);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}

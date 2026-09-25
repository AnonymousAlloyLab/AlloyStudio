package is.fivefivefive.CanDis.augmentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import is.fivefivefive.CanDis.theory.StructuralKey;

/** Least-general structural anti-unification over deterministic theory keys. */
public final class StructuralAntiUnifier {
    private StructuralAntiUnifier() {
    }

    public record Equation(
            String witnessId,
            StructuralKey left,
            StructuralKey right) {
        public Equation {
            witnessId = requireText(witnessId, "witnessId");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    public static final class Pattern {
        private final String symbol;
        private final String hole;
        private final List<Pattern> children;
        private final String stableForm;

        private Pattern(String symbol, String hole, List<? extends Pattern> children) {
            this.symbol = symbol;
            this.hole = hole;
            this.children = List.copyOf(children);
            if ((symbol == null) == (hole == null)) {
                throw new IllegalArgumentException(
                        "A pattern must be exactly one of a node or a hole");
            }
            if (hole != null && !this.children.isEmpty()) {
                throw new IllegalArgumentException("A pattern hole cannot have children");
            }
            StringBuilder encoded = new StringBuilder();
            if (hole != null) {
                encoded.append('?').append(hole.length()).append(':').append(hole);
            } else {
                encoded.append('N').append(symbol.length()).append(':').append(symbol)
                        .append('[').append(this.children.size()).append(':');
                for (Pattern child : this.children) {
                    String nested = child.stableForm;
                    encoded.append(nested.length()).append(':').append(nested);
                }
                encoded.append(']');
            }
            this.stableForm = encoded.toString();
        }

        static Pattern node(String symbol, List<? extends Pattern> children) {
            return new Pattern(requireText(symbol, "symbol"), null, children);
        }

        static Pattern hole(String name) {
            return new Pattern(null, requireText(name, "hole"), List.of());
        }

        public boolean isHole() {
            return hole != null;
        }

        public String hole() {
            if (hole == null) {
                throw new IllegalStateException("This pattern is not a hole");
            }
            return hole;
        }

        public String symbol() {
            if (symbol == null) {
                throw new IllegalStateException("A hole has no node symbol");
            }
            return symbol;
        }

        public List<Pattern> children() {
            return children;
        }

        public String stableForm() {
            return stableForm;
        }

        public int holeCount() {
            Set<String> holes = new LinkedHashSet<>();
            collectHoles(holes);
            return holes.size();
        }

        private void collectHoles(Set<String> output) {
            if (hole != null) {
                output.add(hole);
                return;
            }
            for (Pattern child : children) {
                child.collectHoles(output);
            }
        }

        boolean match(Term term, Map<String, Term> bindings) {
            if (hole != null) {
                Term prior = bindings.putIfAbsent(hole, term);
                return prior == null || prior.equals(term);
            }
            if (!symbol.equals(term.symbol) || children.size() != term.children.size()) {
                return false;
            }
            for (int index = 0; index < children.size(); index++) {
                if (!children.get(index).match(term.children.get(index), bindings)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pattern
                    && stableForm.equals(((Pattern) other).stableForm);
        }

        @Override
        public int hashCode() {
            return stableForm.hashCode();
        }

        @Override
        public String toString() {
            return stableForm;
        }
    }

    public record Proposal(
            Pattern left,
            Pattern right,
            List<String> witnessIds,
            List<String> redundantWitnessIds,
            int holeCount,
            String digest) {
        public Proposal {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            witnessIds = List.copyOf(witnessIds);
            redundantWitnessIds = List.copyOf(redundantWitnessIds);
            digest = requireText(digest, "digest");
            if (witnessIds.size() < 2) {
                throw new IllegalArgumentException(
                        "A generalized schema requires at least two witnesses");
            }
            if (left.isHole() || right.isHole()) {
                throw new IllegalArgumentException(
                        "A generalized equation cannot erase an entire endpoint");
            }
            if (left.equals(right)) {
                throw new IllegalArgumentException(
                        "A generalized equation must not be reflexive by construction");
            }
            if (holeCount <= 0) {
                throw new IllegalArgumentException(
                        "A reusable schema must generalize at least one coordinate");
            }
        }

        public boolean matches(StructuralKey concreteLeft, StructuralKey concreteRight) {
            Map<String, Term> bindings = new LinkedHashMap<>();
            return left.match(Term.from(concreteLeft), bindings)
                    && right.match(Term.from(concreteRight), bindings);
        }

        public boolean matchesEitherDirection(
                StructuralKey concreteLeft,
                StructuralKey concreteRight) {
            return matches(concreteLeft, concreteRight)
                    || matches(concreteRight, concreteLeft);
        }
    }

    public static Proposal propose(List<? extends Equation> equations) {
        Objects.requireNonNull(equations, "equations");
        if (equations.size() < 2) {
            throw new IllegalArgumentException(
                    "Anti-unification cannot infer a schema from one example");
        }
        List<Equation> checked = new ArrayList<>(equations.size());
        Set<String> identifiers = new LinkedHashSet<>();
        for (Equation equation : equations) {
            Equation value = Objects.requireNonNull(equation, "equation");
            if (!identifiers.add(value.witnessId())) {
                throw new IllegalArgumentException("Duplicate witness id " + value.witnessId());
            }
            checked.add(value);
        }
        checked.sort(java.util.Comparator.comparing(Equation::witnessId));
        PatternPair target = antiUnify(checked);
        List<Equation> minimal = new ArrayList<>(checked);
        List<String> redundant = new ArrayList<>();
        boolean changed;
        do {
            changed = false;
            for (int index = 0; index < minimal.size() && minimal.size() > 2; index++) {
                List<Equation> attempt = new ArrayList<>(minimal);
                Equation removed = attempt.remove(index);
                if (antiUnify(attempt).equals(target)) {
                    minimal = attempt;
                    redundant.add(removed.witnessId());
                    changed = true;
                    break;
                }
            }
        } while (changed);

        PatternPair minimized = antiUnify(minimal);
        List<String> witnessIds = minimal.stream().map(Equation::witnessId).toList();
        Collections.sort(redundant);
        int holes = distinctHoles(minimized.left, minimized.right);
        String stable = "anti-unifier-v1\n"
                + minimized.left.stableForm() + "\n"
                + minimized.right.stableForm() + "\n"
                + String.join("\n", witnessIds);
        return new Proposal(
                minimized.left,
                minimized.right,
                witnessIds,
                redundant,
                holes,
                AugmentationDigests.sha256(stable));
    }

    private static int distinctHoles(Pattern left, Pattern right) {
        Set<String> holes = new LinkedHashSet<>();
        left.collectHoles(holes);
        right.collectHoles(holes);
        return holes.size();
    }

    private static PatternPair antiUnify(List<Equation> equations) {
        AntiUnification state = new AntiUnification();
        List<Term> left = new ArrayList<>(equations.size());
        List<Term> right = new ArrayList<>(equations.size());
        for (Equation equation : equations) {
            left.add(Term.from(equation.left()));
            right.add(Term.from(equation.right()));
        }
        return new PatternPair(state.unify(left), state.unify(right));
    }

    private record PatternPair(Pattern left, Pattern right) {
    }

    private static final class AntiUnification {
        private final Map<List<Term>, String> holes = new LinkedHashMap<>();

        Pattern unify(List<Term> terms) {
            if (terms.isEmpty()) {
                throw new IllegalArgumentException("Anti-unification needs concrete terms");
            }
            Term first = terms.get(0);
            if (terms.stream().allMatch(first::equals)) {
                return exact(first);
            }
            boolean sameHead = terms.stream().allMatch(term ->
                    first.symbol.equals(term.symbol)
                            && first.children.size() == term.children.size());
            if (!sameHead) {
                return hole(terms);
            }
            List<Pattern> children = new ArrayList<>(first.children.size());
            for (int index = 0; index < first.children.size(); index++) {
                List<Term> column = new ArrayList<>(terms.size());
                for (Term term : terms) {
                    column.add(term.children.get(index));
                }
                children.add(unify(column));
            }
            return Pattern.node(first.symbol, children);
        }

        private Pattern exact(Term term) {
            List<Pattern> children = new ArrayList<>(term.children.size());
            for (Term child : term.children) {
                children.add(exact(child));
            }
            return Pattern.node(term.symbol, children);
        }

        private Pattern hole(List<Term> terms) {
            List<Term> key = List.copyOf(terms);
            String name = holes.computeIfAbsent(key, ignored -> "h" + holes.size());
            return Pattern.hole(name);
        }
    }

    private static final class Term {
        private final String symbol;
        private final List<Term> children;

        private Term(String symbol, List<? extends Term> children) {
            this.symbol = requireText(symbol, "term symbol");
            this.children = List.copyOf(children);
        }

        static Term from(StructuralKey key) {
            List<Term> children = new ArrayList<>(
                    key.scalars().size() + key.children().size());
            for (int index = 0; index < key.scalars().size(); index++) {
                children.add(new Term(
                        "scalar/" + index + "/" + key.scalars().get(index),
                        List.of()));
            }
            for (StructuralKey child : key.children()) {
                children.add(from(child));
            }
            return new Term(
                    "key/" + key.tag() + "/scalars=" + key.scalars().size(),
                    children);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Term
                    && symbol.equals(((Term) other).symbol)
                    && children.equals(((Term) other).children);
        }

        @Override
        public int hashCode() {
            return Objects.hash(symbol, children);
        }
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}

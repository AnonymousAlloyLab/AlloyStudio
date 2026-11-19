package is.fivefivefive.CanDis.core;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source-linked dependent read, invocation and partial-transport controls. */
public final class LocalBinderTransportTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }
    private static void frozen(Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, message);
    }
    private record Published(EGraphNode node, boolean insideAlternative) { }
    public static void publicationControls(List<NormalForm> forms, boolean requireNested) {
        int before = checks;
        var prepared = CanonicalDistance.prepare(forms);
        var pending = new ArrayDeque<Published>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<EGraphNode, Boolean>());
        for (var form : prepared.comparisonForms()) pending.add(new Published(form.getMatrixEGraph(), false));
        int orbits = 0, nested = 0, bodyMutations = 0;
        while (!pending.isEmpty()) {
            var entry = pending.remove();
            var node = entry.node();
            if (!seen.add(node)) continue;
            var alternatives = prepared.fastAlternatives(node);
            if (!alternatives.isEmpty()) {
                orbits++;
                if (entry.insideAlternative()) nested++;
                check(alternatives.size() >= 2, "primary and nonidentity members retained");
                for (var member : alternatives) {
                    frozen(() -> member.setChildren(List.of()), "published root cannot change children");
                    frozen(() -> member.setExactAlloyType(null), "published root cannot change type");
                    var declared = slots(member);
                    var body = new ArrayDeque<EGraphNode>();
                    for (var child : member.getChildren())
                        if (!LocalBinderTransport.declaration(child.getOpcode().name())) body.add(child);
                    while (!body.isEmpty()) {
                        var read = body.remove();
                        if (read.getOpcode() == EGraphNode.Opcode.VARIABLE) {
                            var replacement = declared.stream().filter(s -> !s.getAlphaName().equals(read.getAlphaName())
                                    && Objects.equals(s.getExactAlloyType(), read.getExactAlloyType())).findFirst();
                            if (replacement.isPresent()) {
                                String original = read.getAlphaName();
                                frozen(() -> read.setAlphaName(replacement.get().getAlphaName()),
                                        "published same-typed body substitution rejected without rechecking");
                                check(Objects.equals(original, read.getAlphaName()), "rejected mutation leaves read unchanged");
                                bodyMutations++;
                            }
                        }
                        body.addAll(read.getChildren());
                    }
                    pending.add(new Published(member, true));
                }
            }
            if (entry.insideAlternative()) {
                check(node.isFrozenForCertification(), "published descendant is frozen");
                frozen(() -> node.setChildren(List.of()), "published descendant mutation rejected");
            }
            for (var child : node.getChildren()) pending.add(new Published(child, entry.insideAlternative()));
        }
        check(orbits > 0 && bodyMutations > 0, "public API exercised actual orbits and body reads");
        check(!requireNested || nested > 0, "independently registered nested alternatives exercised");
        System.out.println("LocalBinderTransportTest publication: " + (checks-before)
                + " checks passed; orbits=" + orbits + ", nested=" + nested);
    }
    private static Field field(String name) throws Exception {
        Field field = EGraphNode.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static EGraphNode.EClassRef withSlots(EGraphNode.EClassRef ref, Map<String,String> slots) throws Exception {
        var constructor = EGraphNode.EClassRef.class.getDeclaredConstructor(EGraphNode.EClass.class, Map.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ref.getEClass(), slots);
    }
    private static List<EGraphNode> slots(EGraphNode n) {
        List<EGraphNode> slots = new ArrayList<>();
        for (var child : n.getChildren()) if (LocalBinderTransport.declaration(child.getOpcode().name()))
            slots.addAll(child.getChildren().subList(1, child.getChildren().size()));
        return slots;
    }
    private record Term(String operator, String variable, String type, List<Term> children) { }
    private static final LocalBinderTransport.View<Term> VIEW = new LocalBinderTransport.View<>() {
        public String operator(Term n) { return n.operator; }
        public String variable(Term n) { return n.variable; }
        public Object type(Term n) { return n.type; }
        public List<Term> children(Term n) { return n.children; }
        public boolean sameHead(Term a, Term b) { return a.operator.equals(b.operator) && Objects.equals(a.type,b.type); }
    };
    private static Term term(String op, Term... children) { return new Term(op,null,"U",List.of(children)); }
    private static Term variable(String name) { return new Term("VARIABLE",name,"U",List.of()); }
    private static Term rename(Term n, Map<String,String> names) {
        return new Term(n.operator,LocalBinderTransport.rename(names,n.variable),n.type,
                n.children.stream().map(c->rename(c,names)).toList());
    }
    private static void structuralControls() {
        var a=variable("a"); var b=variable("b"); var c=variable("c"); var free=variable("free");
        var domain=term("GLOBALBINDING"); var group=term("DISJ",domain,a,b);
        var dependent=term("GENERICRELDECL",term("MINUS",term("JOIN",a,domain),a),c);
        var body=term("AND",term("EQUALS",b,c),term("EQUALS",free,a));
        var source=term("FORALL",group,dependent,body);
        var names=Map.of("a","b","b","a","c","c"); var target=rename(source,names);
        LocalBinderTransport.requireRenamed(source,target,names,VIEW); checks++;
        LocalBinderTransport.requireRenamed(target,source,names,VIEW); checks++;
        rejects(()->LocalBinderTransport.requireRenamed(source,term("FORALL",target.children.get(0),dependent,rename(body,names)),names,VIEW),"untransported dependent domain");
        rejects(()->LocalBinderTransport.requireRenamed(source,term("FORALL",target.children.get(0),target.children.get(1),body),names,VIEW),"untransported body");
        var lost=term("GENERICRELDECL",term("MINUS",term("JOIN",b,domain)),c);
        rejects(()->LocalBinderTransport.requireRenamed(source,term("FORALL",target.children.get(0),lost,rename(body,names)),names,VIEW),"duplicate read occurrence removed");
        var wrongFree=rename(target,Map.of("free","b"));
        rejects(()->LocalBinderTransport.requireRenamed(source,wrongFree,names,VIEW),"foreign read captured");
        var early=term("FORALL",term("DISJ",a,a,b),dependent,body);
        rejects(()->LocalBinderTransport.requireRenamed(early,rename(early,names),names,VIEW),"domain reads slot before its declaration");
        var nested=term("FORALL",group,dependent,term("EXISTS",term("GENERICRELDECL",domain,a),body));
        rejects(()->LocalBinderTransport.requireRenamed(nested,rename(nested,names),names,VIEW),"nested binder captures root slot");
        var otherType=new Term("VARIABLE","b","V",List.of());
        var typed=term("FORALL",term("DISJ",domain,a,otherType),dependent,body);
        rejects(()->LocalBinderTransport.requireRenamed(typed,rename(typed,names),names,VIEW),"bijection across different exact types");
        var nestedGood=term("FORALL",group,dependent,term("EXISTS",term("GENERICRELDECL",a,variable("d")),body));
        LocalBinderTransport.requireRenamed(nestedGood,rename(nestedGood,names),names,VIEW); checks++;
        var three=Map.of("a","b","b","c","c","a");
        LocalBinderTransport.requireRenamed(source,rename(source,three),three,VIEW); checks++;
    }
    public static void run(NormalForm source) throws Exception {
        structuralControls();
        EGraphNode root = EGraphNode.fastComparisonMatrix(source.getMatrixEGraph());
        var queue = new ArrayDeque<EGraphNode>(); queue.add(root);
        EGraphNode owner = null;
        while (!queue.isEmpty()) {
            var node = queue.remove();
            if (slots(node).size() == 3) { owner = node; break; }
            queue.addAll(node.getChildren());
        }
        check(owner != null, "source-linked three-slot binder");
        final EGraphNode binder = owner;
        var slots = slots(binder);
        var names = new LinkedHashMap<String, String>();
        for (int i = 0; i < 3; i++) names.put(slots.get(i).getAlphaName(), slots.get(i < 2 ? 1-i : i).getAlphaName());
        var candidate = EGraphNode.comparisonRenameSubtree(binder, names, new int[]{10000});
        LocalBinderTransport.requireRenamed(binder, candidate, names, EGraphNode.BINDER_TRANSPORT);
        check(FastBinderOrbit.build(source, root).get(binder).size() == 2, "whole-dependent transport admitted");
        var bindings = new ArrayList<QuantiVar>();
        for (var slot : slots) bindings.add(source.getLocalQuantiVars().stream()
                .filter(q -> q.getName().equals(slot.comparisonVariableSource().getAlphaName())).findFirst().orElseThrow());
        var oldGate = FastBinderOrbit.class.getDeclaredMethod("preservesDependencies", List.class, int[].class);
        oldGate.setAccessible(true);
        check(!(boolean)oldGate.invoke(null, bindings, new int[]{1,0,2}), "exact fixed-frame gate rejects source witness");

        var invalid = new LinkedHashMap<>(names); invalid.put(slots.get(1).getAlphaName(), slots.get(1).getAlphaName());
        rejects(() -> LocalBinderTransport.requireRenamed(binder,candidate,invalid,EGraphNode.BINDER_TRANSPORT), "nonbijection");
        var alpha = field("alphaName");
        var read = slots(candidate).get(0);
        Object original = alpha.get(read);
        try {
            alpha.set(read, slots.get(0).getAlphaName());
            rejects(() -> LocalBinderTransport.requireRenamed(binder,candidate,names,EGraphNode.BINDER_TRANSPORT), "partial declaration rename");
        } finally { alpha.set(read, original); }
        var type = field("exactAlloyType"); original = type.get(read);
        try {
            type.set(read, null);
            rejects(() -> LocalBinderTransport.requireRenamed(binder,candidate,names,EGraphNode.BINDER_TRANSPORT), "changed type");
        } finally { type.set(read, original); }
        var children = field("childClasses");
        var declaration = binder.getChildren().get(0);
        Object references = children.get(declaration);
        try {
            var changed = new ArrayList<>(declaration.getChildClasses());
            changed.set(1,withSlots(changed.get(1),names));
            children.set(declaration,changed);
            rejects(() -> LocalBinderTransport.requireRenamed(binder,candidate,names,EGraphNode.BINDER_TRANSPORT), "stale same-typed invocation seal");
        } finally { children.set(declaration,references); }
        references = children.get(candidate);
        try {
            var changed = new ArrayList<>(candidate.getChildClasses());
            int body = changed.size()-1;
            changed.set(body,withSlots(changed.get(body),names));
            children.set(candidate,changed);
            rejects(() -> LocalBinderTransport.requireRenamed(binder,candidate,names,EGraphNode.BINDER_TRANSPORT), "same-typed body map does not commute");
        } finally { children.set(candidate,references); }
        LocalBinderTransport.requireRenamed(binder,candidate,names,EGraphNode.BINDER_TRANSPORT);
        check(LocalBinderTransport.moveInvocation(Map.of("a","b","c","a"),Map.of("a","b","b","a","c","c"))
                .equals(Map.of("b","a","c","b")), "invocation conjugation, including fixed slot read");
        System.out.println("LocalBinderTransportTest: " + checks + " checks passed");
    }
}

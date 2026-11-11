package is.fivefivefive.ACGN.repair;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.ast.*;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.theory.AlloySemanticProfileFactory;
import is.fivefivefive.CanDis.theory.CertificateTheoryManifest;
import is.fivefivefive.CanDis.theory.SemanticProfile;
import parser.ast.nodes.ModelUnit;

/** Owns the fixed model context. Full module source is not a policy observation. */
public final class RepairTask {
    public static final String VERSION = "source-occurrence-repair-v1";
    public static final int MAX_SOURCE_CHARS = 100_000;
    static final String VIRTUAL_FILE = Path.of(System.getProperty("java.io.tmpdir"),
            "acgn-repair-virtual.als").toAbsolutePath().toString();
    public static final String THEORY = CertificateTheoryManifest.VERSION + ";"
            + CanonicalAlloyPipeline.PIPELINE_VERSION;

    private final String target;
    private final int commandIndex;
    private final Set<String> forbidden;
    private final String before;
    private final String after;
    private final String originalBody;
    private final String id;
    private final SemanticProfile profile;
    private final boolean feedbackTemporal;

    public static final class UnsupportedInput extends IllegalArgumentException {
        public UnsupportedInput(String message) { super(message); }
    }

    private RepairTask(String source, String target, int commandIndex, Set<String> forbidden) {
        this(source, target, commandIndex, forbidden, false);
    }

    private RepairTask(String source, String target, int commandIndex, Set<String> forbidden,
                       boolean feedbackTemporal) {
        this.feedbackTemporal = feedbackTemporal;
        this.target = qualify(target);
        this.commandIndex = commandIndex;
        Set<String> excluded = new HashSet<>();
        for (String name : Objects.requireNonNull(forbidden, "forbidden")) excluded.add(qualify(name));
        if (excluded.contains(this.target)) throw new IllegalArgumentException("Target is excluded");
        this.forbidden = Set.copyOf(excluded);
        Parsed parsed = parse(source);
        int[] range = parsed.target.getBody().span().toStartEnd(source);
        if (range == null || range[1] - range[0] < 2
                || source.charAt(range[0]) != '{' || source.charAt(range[1] - 1) != '}') {
            throw new UnsupportedInput("Predicate body requires an explicit braced source span");
        }
        this.before = source.substring(0, range[0] + 1);
        this.after = source.substring(range[1] - 1);
        this.originalBody = source.substring(range[0] + 1, range[1] - 1);
        this.profile = parsed.profile;
        this.id = digest(VERSION, source, this.target, Integer.toString(commandIndex),
                String.join("\n", excluded.stream().sorted().toList()), profile.fingerprint(), THEORY);
    }

    public static RepairTask fromSource(String source, String target, int commandIndex,
                                        Set<String> forbiddenCallables) {
        return new RepairTask(Objects.requireNonNull(source, "source"), target,
                commandIndex, forbiddenCallables);
    }

    public static RepairTask fromFile(Path path, String target, int commandIndex,
                                      Set<String> forbiddenCallables) throws java.io.IOException {
        return fromSource(Files.readString(path, StandardCharsets.UTF_8), target,
                commandIndex, forbiddenCallables);
    }

    public String id() { return id; }

    /** Opt-in for phase-aware feedback; historical experiments retain their scope. */
    public static RepairTask forFeedback(String source, String target, int commandIndex,
                                         Set<String> forbiddenCallables) {
        return new RepairTask(Objects.requireNonNull(source, "source"), target, commandIndex,
                forbiddenCallables, true);
    }
    public String target() { return target; }
    public String originalBody() { return originalBody; }
    public SemanticProfile semanticProfile() { return profile; }
    int bodyStart() { return before.length(); }

    EvaluatorContext evaluatorContext(String oracleName) {
        String oracleLabel = qualify(oracleName);
        if (!forbidden.contains(oracleLabel)) {
            throw new UnsupportedInput("Evaluation oracle must be excluded from repair dependencies");
        }
        Parsed parsed = parse(source(originalBody));
        Command command = parsed.module.getAllCommands().get(commandIndex);
        if (command.check) throw new UnsupportedInput("RL-2 requires an independent run command");
        for (Func function : parsed.module.getAllFunc()) {
            if (function.label.equals(oracleLabel) && function.isPred && function.count() == 0) {
                requireIndependent(function.getBody(), parsed.target.getBody().span(), 0);
                scan(function.getBody(), Set.of(target), new HashSet<>(), 0);
                return new EvaluatorContext(parsed.module, command, function.getBody());
            }
        }
        throw new UnsupportedInput("Select a local parameterless oracle predicate");
    }

    String source(String body) {
        Objects.requireNonNull(body, "body");
        if ((long) before.length() + body.length() + after.length() > MAX_SOURCE_CHARS) {
            throw new UnsupportedInput("Candidate exceeds source-size bound");
        }
        return before + body + after;
    }

    Candidate prepare(String body) {
        String source = source(body);
        Parsed parsed = parse(source);
        if (!profile.equals(parsed.profile)) {
            throw new UnsupportedInput("Candidate changed the source-command semantic profile");
        }
        int[] range = parsed.target.getBody().span().toStartEnd(source);
        if (range == null || range[0] + 1 != before.length()
                || range[1] - 1 != before.length() + body.length()) {
            throw new UnsupportedInput("Candidate escaped the fixed predicate body boundary");
        }
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), parsed.module);
        visitor.visit(new ModelUnit(null, parsed.module), null);
        Integer graphId = visitor.getForestId(target.substring("this/".length()));
        if (graphId == null) throw new IllegalStateException("Selected predicate graph is missing");
        CanonicalAlloyPipeline.Prepared prepared = CanonicalAlloyPipeline.prepare(
                visitor.getForest().get(graphId), parsed.profile).compactForComparison();
        return new Candidate(body, digest(source), parsed.target.getBody(), prepared);
    }

    private Parsed parse(String source) {
        if (source.length() > MAX_SOURCE_CHARS) throw new UnsupportedInput("Source-size bound exceeded");
        CompModule module = CompUtil.parseEverything_fromFile(A4Reporter.NOP,
                new HashMap<>(Map.of(VIRTUAL_FILE, source)), VIRTUAL_FILE);
        for (CompModule.Open open : module.getOpens()) {
            if (!("util/integer".equals(open.filename) && open.pos == null)) {
                throw new UnsupportedInput("Explicit imports are not editable in repair slice v1");
            }
        }
        Func selected = null;
        for (Func function : module.getAllFunc()) {
            if (target.equals(function.label)) selected = function;
            scan(function.getBody(), Set.of(), new HashSet<>(), 0);
        }
        if (selected == null || !selected.isPred || selected.count() != 0) {
            throw new UnsupportedInput("Select exactly one local parameterless predicate: " + target);
        }
        if (commandIndex < 0 || commandIndex >= module.getAllCommands().size()) {
            throw new UnsupportedInput("Select an existing source command by index");
        }
        Command command = module.getAllCommands().get(commandIndex);
        requireIndependent(command.formula, selected.getBody().span(), 0);
        scan(command.formula, Set.of(target), new HashSet<>(), 0);
        requireIndependent(module.getAllReachableFacts(), selected.getBody().span(), 0);
        scan(module.getAllReachableFacts(), Set.of(target), new HashSet<>(), 0);
        for (Sig sig : module.getAllSigs()) {
            if (!feedbackTemporal && sig.isVariable != null) throw new UnsupportedInput("Mutable signatures require temporal support");
            for (Sig.Field field : sig.getFields()) {
                if (!feedbackTemporal && field.isVariable != null) throw new UnsupportedInput("Mutable fields require temporal support");
                scan(field.decl().expr, Set.of(target), new HashSet<>(), 0);
            }
            for (Expr fact : sig.getFacts()) scan(fact, Set.of(target), new HashSet<>(), 0);
        }
        scan(selected.getBody(), forbidden, new HashSet<>(), 0);
        A4Options options = new A4Options();
        options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        SemanticProfile selectedProfile = AlloySemanticProfileFactory.fromExactlyOne(
                module, List.of(command), options);
        return new Parsed(module, selected, selectedProfile);
    }

    private static void requireIndependent(Expr expression, Pos editable, int depth) {
        if (depth > 128) throw new UnsupportedInput("Context depth exceeds 128");
        Pos position = expression.span();
        if (position != null && editable.sameFile(position) && editable.contains(position)) {
            throw new UnsupportedInput("Selected command/facts inline the editable predicate");
        }
        for (Expr child : children(expression)) requireIndependent(child, editable, depth + 1);
    }

    private void scan(Expr expression, Set<String> forbidden, Set<Func> active, int depth) {
        if (depth > 128) throw new UnsupportedInput("Expression/call depth exceeds 128");
        if (!feedbackTemporal && expression instanceof ExprUnary unary && Set.of(ExprUnary.Op.AFTER,
                ExprUnary.Op.ALWAYS, ExprUnary.Op.EVENTUALLY, ExprUnary.Op.BEFORE,
                ExprUnary.Op.HISTORICALLY, ExprUnary.Op.ONCE, ExprUnary.Op.PRIME).contains(unary.op)) {
            throw new UnsupportedInput("Temporal expressions require a later repair milestone");
        }
        if (!feedbackTemporal && expression instanceof ExprBinary binary && Set.of(ExprBinary.Op.UNTIL,
                ExprBinary.Op.RELEASES, ExprBinary.Op.SINCE, ExprBinary.Op.TRIGGERED).contains(binary.op)) {
            throw new UnsupportedInput("Temporal expressions require a later repair milestone");
        }
        if (expression instanceof ExprCall call) {
            if (forbidden.contains(call.fun.label)) {
                throw new UnsupportedInput("Disallowed callable dependency: " + call.fun.label);
            }
            if (!active.add(call.fun)) throw new UnsupportedInput("Recursive calls are outside this repair slice");
            for (Decl decl : call.fun.decls) scan(decl.expr, forbidden, active, depth + 1);
            scan(call.fun.getBody(), forbidden, active, depth + 1);
            active.remove(call.fun);
        }
        for (Expr child : children(expression)) scan(child, forbidden, active, depth + 1);
    }

    static List<Expr> children(Expr expression) {
        if (expression instanceof ExprUnary u) return List.of(u.sub);
        if (expression instanceof ExprBinary b) return List.of(b.left, b.right);
        if (expression instanceof ExprList l) return l.args;
        if (expression instanceof ExprCall c) return c.args;
        if (expression instanceof ExprITE i) return List.of(i.cond, i.left, i.right);
        if (expression instanceof ExprLet l) return List.of(l.expr, l.sub);
        if (expression instanceof ExprQt q) {
            List<Expr> result = new ArrayList<>();
            for (Decl decl : q.decls) result.add(decl.expr);
            result.add(q.sub);
            return result;
        }
        if (expression instanceof ExprVar || expression instanceof ExprConstant
                || expression instanceof Sig || expression instanceof Sig.Field) return List.of();
        throw new UnsupportedInput("Unsupported expression node: " + expression.getClass().getSimpleName());
    }

    static String qualify(String name) {
        Objects.requireNonNull(name, "name");
        String local = name.startsWith("this/") ? name.substring(5) : name;
        if (!local.matches("[A-Za-z_][A-Za-z0-9_']*")) {
            throw new UnsupportedInput("Select a local callable identifier");
        }
        return "this/" + local;
    }

    static String digest(String... parts) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sha.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Parsed(CompModule module, Func target, SemanticProfile profile) { }
    record EvaluatorContext(CompModule module, Command command, Expr oracle) { }
    record Candidate(String body, String hash, Expr expression,
                     CanonicalAlloyPipeline.Prepared prepared) { }
}

package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Version;
import edu.mit.csail.sdg.ast.*;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;

/** Validates the synthetic inputs without executing a rewriting arm. */
public final class SlotBindingDatasetValidator {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3) {
            throw new IllegalArgumentException("Usage: ROOT REPORT [PROBE_FAMILY]");
        }
        Path root = Path.of(args[0]);
        JSONObject manifest = new JSONObject(Files.readString(root.resolve("manifest.json")));
        JSONArray rows = new JSONArray();
        Set<String> operators = new TreeSet<>();
        int failures = 0;
        JSONArray input = args.length == 3 ? manifest.getJSONArray("reducedProbes") : manifest.getJSONArray("pairs");
        for (int i = 0; i < input.length(); i++) {
            JSONObject entry = input.getJSONObject(i);
            if (args.length == 3 && !entry.getString("family").equals(args[2])) {
                continue;
            }
            JSONObject row = new JSONObject().put("path", entry.getString("path"));
            try {
                Path file = root.resolve(entry.getString("path"));
                require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(file))).equals(entry.getString("sha256")), "source hash mismatch");
                CompModule module = CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, file.toString());
                Func left = function(module, entry.getString("predicate"));
                Func right = function(module, entry.getString("reference"));
                int expected = entry.getInt("outerBindingsPerPredicate");
                for (Func fn : List.of(left, right)) {
                    List<ExprVar> outer = new ArrayList<>();
                    Expr body = fn.getBody().deNOP();
                    while (body instanceof ExprQt && (((ExprQt) body).op == ExprQt.Op.ALL
                            || ((ExprQt) body).op == ExprQt.Op.SOME)) {
                        ExprQt qt = (ExprQt) body;
                        for (Decl decl : qt.decls) {
                            for (ExprHasName name : decl.names) {
                                outer.add((ExprVar) name);
                            }
                        }
                        body = qt.sub.deNOP();
                    }
                    require(outer.size() == expected, "outer binder count mismatch");
                    Scan scan = new Scan();
                    body.accept(scan);
                    require(scan.used.containsAll(outer), "unused outer binder");
                    Set<String> types = new HashSet<>();
                    Map<String, Integer> histogram = new HashMap<>();
                    for (ExprVar v : outer) types.add(v.type().toString());
                    for (ExprVar v : outer) histogram.merge(v.type().toString(), 1, Integer::sum);
                    require(types.size() == entry.getInt("outerTypeCount"), "outer type count mismatch");
                    if (manifest.getString("schema").equals("slot-typing-grid-v1")) {
                        require(expected >= 2 && expected <= 10, "grid binder bound");
                        require(entry.getInt("totalBindingsPerPredicate") == expected, "grid total metadata mismatch");
                        require(scan.declared == 0, "grid body has additional binders");
                        List<Integer> actualCounts = histogram.values().stream().sorted().toList();
                        JSONObject stated = entry.getJSONObject("outerTypeHistogram");
                        List<Integer> statedCounts = stated.keySet().stream().map(stated::getInt).sorted().toList();
                        require(actualCounts.equals(statedCounts), "grid histogram mismatch");
                        require(actualCounts.get(actualCounts.size() - 1) - actualCounts.get(0) <= 1,
                                "grid type allocation is not balanced");
                    }
                    fn.getBody().accept(scan);
                    operators.addAll(scan.operators);
                }
                require(!left.getBody().toString().equals(right.getBody().toString()), "identical native AST rendering");
                if (Boolean.getBoolean("acgn.validateAdapter")) {
                    ModelUnit model = new ModelUnit(null, module);
                    Predicate p = model.getPredDeclList().stream().filter(x -> x.getName().equals(entry.getString("predicate")))
                            .findFirst().orElseThrow();
                    Predicate q = model.getPredDeclList().stream().filter(x -> x.getName().equals(entry.getString("reference")))
                            .findFirst().orElseThrow();
                    require(!DatasetConventions.sameRawAst(p.getBody(), q.getBody()), "AST-identical pair would be skipped");
                }
                row.put("parsed", true).put("outerBindings", expected).put("astDifferent", true);
                if (args.length == 3) {
                    if (entry.getBoolean("solverCheck")) {
                        A4Options options = new A4Options();
                        options.noOverflow = true;
                        Command command = module.getAllCommands().stream().filter(c -> c.check).findFirst().orElseThrow();
                        boolean sat = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,
                                module.getAllReachableSigs(), command, options).satisfiable();
                        row.put("counterexample", sat).put("solverCheck", "bounded");
                        require(sat != entry.getBoolean("expectedEquivalent"), "solver result disagrees with label");
                    } else {
                        row.put("solverCheck", "not attempted: second-order domain; schema-only semantic expectation");
                    }
                }
                row.put("success", true);
            } catch (Exception | StackOverflowError failure) {
                row.put("success", false).put("error", failure.toString());
                failures++;
                System.err.println(entry.getString("path") + ": " + failure);
            }
            rows.put(row);
        }
        require(rows.length() == (args.length == 3 ? 1 : manifest.getInt("targetPairs")), "unexpected validation population");
        require(manifest.getInt("targetPairs") > 0
                && manifest.getInt("targetPredicates") == 2 * manifest.getInt("targetPairs"), "invalid population metadata");
        JSONObject report = new JSONObject().put("schema", manifest.getString("schema"))
                .put("datasetHash", manifest.getString("filesSha256"))
                .put("alloyVersion", Version.version()).put("alloyBuild", Version.buildNumber())
                .put("repositoryAstAdapterChecked", Boolean.getBoolean("acgn.validateAdapter"))
                .put("checkedPairs", rows.length()).put("failures", failures)
                .put("observedNativeOperators", new JSONArray(operators)).put("rows", rows);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, report.toString(2) + "\n");
        System.out.println("Alloy " + Version.version() + ": " + rows.length() + " pairs; failures=" + failures);
        if (failures != 0) throw new IllegalStateException("Dataset validation failed; see " + output);
    }

    private static Func function(CompModule module, String name) {
        for (Func fn : module.getAllFunc()) {
            if (fn.label.equals("this/" + name)) return fn;
        }
        throw new IllegalStateException("Missing predicate " + name);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static final class Scan extends VisitReturn<Void> {
        final Set<ExprVar> used = new HashSet<>();
        final Set<String> operators = new TreeSet<>();
        int declared;
        public Void visit(ExprBinary x) throws edu.mit.csail.sdg.alloy4.Err {
            operators.add("binary:" + x.op.name()); x.left.accept(this); x.right.accept(this); return null;
        }
        public Void visit(ExprList x) throws edu.mit.csail.sdg.alloy4.Err {
            operators.add("list:" + x.op.name()); for (Expr a : x.args) a.accept(this); return null;
        }
        public Void visit(ExprCall x) throws edu.mit.csail.sdg.alloy4.Err {
            operators.add("call"); for (Expr a : x.args) a.accept(this); return null;
        }
        public Void visit(ExprConstant x) { operators.add("constant:" + x.op.name()); return null; }
        public Void visit(ExprITE x) throws edu.mit.csail.sdg.alloy4.Err {
            operators.add("ite"); x.cond.accept(this); x.left.accept(this); x.right.accept(this); return null;
        }
        public Void visit(ExprLet x) throws edu.mit.csail.sdg.alloy4.Err {
            declared++;
            operators.add("let"); x.expr.accept(this); x.sub.accept(this); return null;
        }
        public Void visit(ExprQt x) throws edu.mit.csail.sdg.alloy4.Err {
            operators.add("quantifier:" + x.op.name());
            for (Decl d : x.decls) declared += d.names.size();
            for (Decl d : x.decls) d.expr.accept(this);
            x.sub.accept(this); return null;
        }
        public Void visit(ExprUnary x) throws edu.mit.csail.sdg.alloy4.Err {
            operators.add("unary:" + x.op.name()); x.sub.accept(this); return null;
        }
        public Void visit(ExprVar x) { used.add(x); return null; }
        public Void visit(Sig x) { return null; }
        public Void visit(Sig.Field x) { return null; }
        public Void visit(Func x) throws edu.mit.csail.sdg.alloy4.Err { x.getBody().accept(this); return null; }
        public Void visit(Assert x) throws edu.mit.csail.sdg.alloy4.Err { x.expr.accept(this); return null; }
        public Void visit(edu.mit.csail.sdg.parser.Macro x) throws edu.mit.csail.sdg.alloy4.Err {
            x.body.accept(this); return null;
        }
    }
}

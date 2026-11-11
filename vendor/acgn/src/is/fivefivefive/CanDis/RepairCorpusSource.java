package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;

/** Metadata-only preparation of derived scope-3 repair tasks; never edits the corpus. */
public final class RepairCorpusSource {
    private static JSONArray ast(Node node) {
        JSONArray result = new JSONArray().put(DatasetConventions.rawAstLabel(node));
        for (Node child : DatasetConventions.rawAstChildren(node)) result.put(ast(child));
        return result;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("source output preferredPredicate");
        Path source = Path.of(args[0]).toRealPath();
        Path output = Path.of(args[1]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Use a fresh derived source");
        byte[] original = Files.readAllBytes(source);
        String text = new String(original, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(original, text.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Source is not valid UTF-8");
        }
        CompModule module = CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, source.toString());
        Map<String, Predicate> predicates = new LinkedHashMap<>();
        for (Predicate predicate : new ModelUnit(null, module).getPredDeclList()) {
            predicates.put(predicate.getName(), predicate);
        }
        String[] names = DatasetConventions.findPredicatePairNames(args[2], predicates);
        if (names == null) throw new IllegalArgumentException("No student/oracle pair");
        Predicate left = predicates.get(names[0]), right = predicates.get(names[1]);
        String student = ast(left.getBody()).toString();
        String oracle = ast(right.getBody()).toString();
        if (student.equals(oracle)) throw new IllegalArgumentException("AST_IDENTICAL");
        String derived = text + "\n// Independent bounded RL evaluation profile.\nrun {} for 3\n";
        Files.writeString(output, derived, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        CompModule reparsed = CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, output.toString());
        int index = module.getAllCommands().size();
        if (reparsed.getAllCommands().size() != index + 1 || reparsed.getAllCommands().get(index).check) {
            throw new IllegalStateException("Independent command append mismatch");
        }
        System.out.println(new JSONObject().put("target", names[0]).put("oracle", names[1])
                .put("command", index).put("originalHash", sha(original))
                .put("derivedHash", sha(derived.getBytes(StandardCharsets.UTF_8)))
                .put("studentAstHash", sha(student.getBytes(StandardCharsets.UTF_8)))
                .put("oracleAstHash", sha(oracle.getBytes(StandardCharsets.UTF_8)))
                .put("profile", "appended-independent-run-scope-3").toString());
    }
}

package is.fivefivefive.CanDis;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONObject;
import parser.ast.nodes.ModelUnit;

/** One synthetic predicate per process; run only through the deadline supervisor. */
public final class SlotTypingSyntheticWorker {
    public static void main(String[] args) throws Exception {
        if (args.length != 4 || !(args[2].equals("typed") || args[2].equals("untyped"))) {
            throw new IllegalArgumentException("source predicate typed|untyped result.json");
        }
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[3]);
        JSONObject result = new JSONObject().put("schema", "slot-synthetic-v1")
                .put("source", source.toString()).put("predicate", args[1]).put("arm", args[2]);
        long started = System.nanoTime();
        try {
            System.err.println("SLOT_PHASE parse");
            var module = CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, source.toString());
            var model = new ModelUnit(null, module);
            var matches = model.getPredDeclList().stream()
                    .filter(p -> p.getName().equals(args[1])).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous/missing predicate: " + args[1]);
            long parsed = System.nanoTime();
            System.err.println("SLOT_PHASE adapt");
            var input = AlloyAstTermAdapter.fromPredicate(matches.get(0), args[2].equals("typed"));
            long adapted = System.nanoTime();
            System.err.println("SLOT_PHASE representation");
            var representation = new SlottedEGraph(args[2].equals("typed")).representUnbounded(input);
            long constructed = System.nanoTime();
            System.err.println("SLOT_PHASE serialization");
            byte[] text = representation.frontier().toString().getBytes(StandardCharsets.UTF_8);
            Path representationFile = output.resolveSibling(output.getFileName() + ".representation.txt");
            Files.write(representationFile, text);
            var stats = representation.stats();
            result.put("status", "SUCCESS").put("parseNanos", parsed - started)
                    .put("adaptNanos", adapted - parsed).put("representationNanos", constructed - adapted)
                    .put("engineNanos", constructed - parsed)
                    .put("totalNanos", System.nanoTime() - started)
                    .put("representationFile", representationFile.getFileName().toString())
                    .put("representationSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text)))
                    .put("eclasses", stats.eclasses).put("enodes", stats.enodes)
                    .put("rewriteApplications", stats.rewriteApplications).put("iterations", stats.iterations)
                    .put("rebuilds", stats.rebuilds).put("slotMappings", stats.slotMappings);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            result.put("status", error instanceof OutOfMemoryError ? "OOM"
                    : error instanceof StackOverflowError ? "STACK_OVERFLOW" : "ERROR")
                    .put("error", error.getClass().getName() + ": " + error.getMessage());
        }
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.writeString(temporary, result.toString(2) + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
        if (!result.getString("status").equals("SUCCESS")) System.exit(1);
    }
}

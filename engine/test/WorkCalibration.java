package live;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Calibration driver for the AP01-C01 work budget (scripts/calibrate_work_budget.py).
 *
 * Runs each JSONL request in one warm JVM with a caller-chosen fuel budget and
 * records work units, retained allocations, wall time and the public status.
 * It writes no request or response content, only identifiers and counters.
 */
public final class WorkCalibration {
    public static void main(String[] args) throws Exception {
        boolean baseline = args[2].equals("baseline");
        long fuel = baseline ? 0 : Long.parseLong(args[2]);
        PrintStream console = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));  // framework console noise
        try (BufferedReader in = Files.newBufferedReader(Path.of(args[0]));
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1])))) {
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject row = new JSONObject(line);
                long started = System.nanoTime();
                JSONObject result = baseline ? LiveFeedback.evaluate(row.getJSONObject("request"))
                        : LiveFeedback.evaluate(row.getJSONObject("request"), fuel);
                double seconds = (System.nanoTime() - started) / 1e9;
                String code = result.has("diagnostics") && !result.getJSONArray("diagnostics").isEmpty()
                        ? result.getJSONArray("diagnostics").getJSONObject(0).optString("code", "") : "";
                out.println(new JSONObject().put("id", row.getString("id")).put("kind", row.getString("kind"))
                        .put("metric", row.getString("metric")).put("bytes", row.getInt("bytes"))
                        .put("pool", row.getInt("pool")).put("units", baseline ? -1 : LiveFeedback.lastWorkUnits())
                        .put("allocated", baseline ? -1 : LiveFeedback.lastAllocated()).put("seconds", seconds)
                        .put("status", result.getString("status")).put("code", code)
                        .put("requestSha256", sha256(row.getJSONObject("request").toString()))
                        .put("responseSha256", sha256(result.toString())));
                out.flush();
            }
        } finally {
            System.setOut(console);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}

package benchmark;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import live.LiveFeedback;

/** Calls the unmodified production engine; JSONL is a benchmark transport only. */
public final class LiveWorker {
    public static void main(String[] args) throws Exception {
        PrintStream wire = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            long start = System.nanoTime();
            JSONObject response;
            try {
                if (line.getBytes(StandardCharsets.UTF_8).length + 1 > 1_048_576) {
                    response = new JSONObject().put("status", "invalid_request")
                        .put("benchmark_error", "REQUEST_TOO_LARGE");
                } else response = LiveFeedback.evaluate(new JSONObject(line));
            } catch (Throwable error) {
                response = new JSONObject().put("status", "engine_error")
                    .put("benchmark_error", error.getClass().getSimpleName());
            }
            response.put("benchmark_engine_seconds", (System.nanoTime() - start) / 1e9);
            wire.println(response.toString());
            wire.flush();
        }
    }
}

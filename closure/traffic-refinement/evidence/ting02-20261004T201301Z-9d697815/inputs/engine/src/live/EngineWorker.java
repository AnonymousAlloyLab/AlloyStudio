package live;

import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

/** Private, sequential framed dispatcher. Never listens on a network socket. */
public final class EngineWorker {
    private static final int REQUEST_LIMIT = 1_048_576;
    private static final int RESPONSE_LIMIT = 4_194_304;
    private static final Set<String> FIELDS = Set.of("protocol", "incarnation", "ticket", "context", "kind", "request");
    private EngineWorker() { }

    private static void send(DataOutputStream wire, JSONObject value) throws Exception {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > RESPONSE_LIMIT) throw new IllegalArgumentException();
        wire.writeInt(bytes.length); wire.write(bytes); wire.flush();
    }

    public static void main(String[] args) {
        // Nothing from parser/solver code owns the protocol descriptor.
        DataOutputStream wire = new DataOutputStream(new FileOutputStream(FileDescriptor.out));
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        try {
            if (args.length != 2 || !args[0].matches("[1-9][0-9]{0,18}")
                    || !(args[1].equals("feedback") || args[1].equals("behavior"))) return;
            String incarnation = args[0], lane = args[1];
            Path directory = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            send(wire, new JSONObject().put("protocol", 1).put("incarnation", incarnation)
                    .put("ticket", 0).put("context", "").put("kind", "ready")
                    .put("result", new JSONObject().put("status", "ready")));
            DataInputStream input = new DataInputStream(System.in);
            long lastTicket = 0;
            while (true) {
                int length;
                try { length = input.readInt(); } catch (EOFException done) { return; }
                if (length < 2 || length > REQUEST_LIMIT) return;
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) return;
                String encoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                WorkerJson.check(encoded);
                JSONObject frame = new JSONObject(encoded);
                if (!frame.keySet().equals(FIELDS) || !(frame.get("protocol") instanceof Integer)
                        || frame.getInt("protocol") != 1 || !incarnation.equals(frame.get("incarnation"))
                        || !lane.equals(frame.get("kind")) || !(frame.get("ticket") instanceof Number)
                        || !(frame.get("context") instanceof String)
                        || !frame.getString("context").matches("[0-9a-f]{64}")) return;
                Object ticketValue = frame.get("ticket");
                if (!(ticketValue instanceof Integer || ticketValue instanceof Long)) return;
                long ticket = frame.getLong("ticket");
                if (ticket <= lastTicket) return;
                lastTicket = ticket;
                Path job = Files.createDirectory(directory.resolve("job-" + ticket));
                JSONObject result;
                int parses;
                WorkerSafety.begin(job);
                try {
                    JSONObject request = frame.getJSONObject("request");
                    result = lane.equals("feedback") ? LiveFeedback.evaluate(request) : BehaviorFeedback.evaluate(request);
                } finally {
                    parses = WorkerSafety.end();
                }
                // No retained parsed-reference cache. Each request owns every
                // mutable parser/graph/solver object and releases its disk inputs.
                try (var files = Files.walk(job)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
                }
                send(wire, new JSONObject().put("protocol", 1).put("incarnation", incarnation)
                        .put("ticket", ticket).put("context", frame.getString("context")).put("kind", lane)
                        .put("result", result).put("parseUnits", parses));
            }
        } catch (Throwable poisonedOrMalformed) {
            // Close the process without a reusable result. The owner retires and
            // reaps it; diagnostics and private input never leave on stderr.
            System.exit(70);
        }
    }
}

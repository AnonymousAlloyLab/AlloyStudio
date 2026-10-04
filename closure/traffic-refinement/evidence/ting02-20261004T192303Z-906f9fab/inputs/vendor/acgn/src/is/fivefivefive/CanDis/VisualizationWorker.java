package is.fivefivefive.CanDis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONObject;

/** Single-request process used by {@link VisualizationProcessRunner}. */
public final class VisualizationWorker {
    private VisualizationWorker() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("VisualizationWorker requires request and response paths");
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        JSONObject envelope;
        try {
            JSONObject command = new JSONObject(Files.readString(input, StandardCharsets.UTF_8));
            JSONObject request = command.getJSONObject("request");
            JSONObject body = execute(command.getString("operation"), request);
            envelope = new JSONObject().put("status", 200).put("body", body);
        } catch (VisualizationAnalysisService.AnalysisException exception) {
            envelope = error(exception.status(), exception.code(), exception.getMessage());
        } catch (Throwable exception) {
            envelope = error(500, "internal_error",
                    "The isolated visualization worker could not complete the request.");
            exception.printStackTrace(System.err);
        }
        Files.writeString(output, envelope.toString(), StandardCharsets.UTF_8);
    }

    private static JSONObject execute(String operation, JSONObject request) throws InterruptedException {
        VisualizationAnalysisService service = new VisualizationAnalysisService();
        switch (operation) {
            case "inspect":
                return service.inspect(request.optString("model", ""));
            case "analyze": {
                CallableValue callable = callable(request, "callable");
                return service.analyze(request.optString("model", ""), callable.name, callable.kind);
            }
            case "compare": {
                CallableValue left = callable(request, "leftCallable");
                CallableValue right = callable(request, "rightCallable");
                return service.compare(request.optString("model", ""),
                        left.name, left.kind, right.name, right.kind);
            }
            case "test-sleep":
                Thread.sleep(request.optLong("milliseconds", 0L));
                return new JSONObject().put("status", "slept");
            default:
                throw new VisualizationAnalysisService.AnalysisException(
                        400, "invalid_operation", "Unknown visualization worker operation.");
        }
    }

    private static CallableValue callable(JSONObject request, String field) {
        JSONObject value = request.optJSONObject(field);
        if (value == null && field.equals("callable")) {
            return new CallableValue(request.optString("predicate", ""), "predicate");
        }
        if (value == null) {
            throw new VisualizationAnalysisService.AnalysisException(
                    400, "callable_required", field + " must identify a predicate or function.");
        }
        return new CallableValue(value.optString("name", ""), value.optString("kind", "callable"));
    }

    private static JSONObject error(int status, String code, String message) {
        return new JSONObject()
                .put("status", status)
                .put("body", new JSONObject().put("code", code).put("message", message));
    }

    private static final class CallableValue {
        private final String name;
        private final String kind;

        private CallableValue(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }
}

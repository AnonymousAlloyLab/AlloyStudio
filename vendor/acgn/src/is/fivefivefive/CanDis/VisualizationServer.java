package is.fivefivefive.CanDis;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Minimal JSON/HTTP boundary for the Alloy e-graph explorer. */
public final class VisualizationServer {
    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_REQUEST_BYTES = VisualizationAnalysisService.MAX_MODEL_CHARS * 4 + 65_536;
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private VisualizationServer() {
    }

    public static void main(String[] args) throws IOException {
        Configuration configuration = Configuration.parse(args);
        VisualizationProcessRunner runner = new VisualizationProcessRunner(
                configuration.workers,
                configuration.timeoutSeconds * 1_000L,
                configuration.workerHeap);
        HttpServer server = HttpServer.create(
                new InetSocketAddress(configuration.bindAddress, configuration.port), 32);
        // Analysis slots wait on child processes. Keep control threads free for health and cancellation.
        ExecutorService executor = Executors.newFixedThreadPool(configuration.workers + 2);
        server.setExecutor(executor);
        server.createContext("/api/v1/health", new Endpoint(configuration, exchange -> {
            requireMethod(exchange, "GET");
            return new Response(200, new JSONObject()
                    .put("status", "ok")
                    .put("version", VisualizationAnalysisService.SERVICE_VERSION)
                    .put("visualizationSchemaVersion", VisualizationAnalysisService.SCHEMA_VERSION)
                    .put("serviceVersion", VisualizationAnalysisService.SERVICE_VERSION)
                    .put("schemaVersion", VisualizationAnalysisService.SCHEMA_VERSION)
                    .put("activeJobs", runner.activeJobCount())
                    .put("maxJobs", configuration.workers)
                    .put("timeoutSeconds", configuration.timeoutSeconds));
        }));
        server.createContext("/api/v1/model/inspect", new Endpoint(configuration, exchange -> {
            requireMethod(exchange, "POST");
            JSONObject request = requestJson(exchange);
            return response(runner.execute(requestId(request), "inspect", request));
        }));
        server.createContext("/api/v1/egraph/analyze", new Endpoint(configuration, exchange -> {
            requireMethod(exchange, "POST");
            JSONObject request = requestJson(exchange);
            CallableRequest.from(request);
            return response(runner.execute(requestId(request), "analyze", request));
        }));
        server.createContext("/api/v1/egraph/compare", new Endpoint(configuration, exchange -> {
            requireMethod(exchange, "POST");
            JSONObject request = requestJson(exchange);
            CallableRequest.from(request, "leftCallable");
            CallableRequest.from(request, "rightCallable");
            return response(runner.execute(requestId(request), "compare", request));
        }));
        server.createContext("/api/v1/jobs/cancel", new Endpoint(configuration, exchange -> {
            requireMethod(exchange, "POST");
            JSONObject request = requestJson(exchange);
            String id = requiredRequestId(request);
            VisualizationProcessRunner.TerminalCause cause = cancellationCause(request);
            boolean cancelled = runner.cancel(id, cause);
            return new Response(200, new JSONObject()
                    .put("requestId", id)
                    .put("cause", request.getString("cause"))
                    .put("cancelled", cancelled)
                    .put("status", cancelled ? "cancelling" : "not-running"));
        }));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            runner.close();
            server.stop(1);
            executor.shutdownNow();
        }, "visualization-server-shutdown"));
        server.start();
        System.out.printf(
                Locale.ROOT,
                "Alloy e-graph visualization API listening at http://%s:%d/api/v1/%n",
                configuration.bindAddress,
                configuration.port);
    }

    private static Response response(VisualizationProcessRunner.ExecutionResult result) {
        return new Response(result.status(), result.body());
    }

    private static String requestId(JSONObject request) {
        String value = request.optString("requestId", "").trim();
        return value.isEmpty() ? UUID.randomUUID().toString() : validateRequestId(value);
    }

    private static String requiredRequestId(JSONObject request) {
        String value = request.optString("requestId", "").trim();
        if (value.isEmpty()) {
            throw new HttpFailure(400, "request_id_required", "requestId is required.");
        }
        return validateRequestId(value);
    }

    static VisualizationProcessRunner.TerminalCause cancellationCause(
            JSONObject request) {
        Object raw = request.opt("cause");
        if (!(raw instanceof String)) {
            throw new HttpFailure(
                    400, "cancellation_cause_required",
                    "cause must be 'cancelled' or 'timeout'.");
        }
        return switch ((String) raw) {
            case "cancelled" -> VisualizationProcessRunner.TerminalCause.CANCELLED;
            case "timeout" -> VisualizationProcessRunner.TerminalCause.TIMED_OUT;
            default -> throw new HttpFailure(
                    400, "invalid_cancellation_cause",
                    "cause must be 'cancelled' or 'timeout'.");
        };
    }

    private static String validateRequestId(String value) {
        if (!REQUEST_ID.matcher(value).matches()) {
            throw new HttpFailure(
                    400,
                    "invalid_request_id",
                    "requestId must contain 1-128 letters, digits, dots, colons, underscores, or hyphens.");
        }
        return value;
    }

    private static JSONObject requestJson(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null
                && !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new HttpFailure(415, "unsupported_media_type", "Content-Type must be application/json.");
        }
        byte[] bytes = readBounded(exchange.getRequestBody());
        if (bytes.length == 0) {
            throw new HttpFailure(400, "invalid_request", "A JSON request body is required.");
        }
        try {
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (JSONException exception) {
            throw new HttpFailure(400, "invalid_request", "The request body is not valid JSON.");
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_REQUEST_BYTES) {
                throw new HttpFailure(413, "request_too_large", "The request body exceeds the service limit.");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void requireMethod(HttpExchange exchange, String expected) {
        if (!expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", expected + ", OPTIONS");
            throw new HttpFailure(405, "method_not_allowed", "Use " + expected + " for this endpoint.");
        }
    }

    private interface EndpointBody {
        Response handle(HttpExchange exchange) throws IOException;
    }

    private static final class Endpoint implements HttpHandler {
        private final Configuration configuration;
        private final EndpointBody body;

        private Endpoint(Configuration configuration, EndpointBody body) {
            this.configuration = configuration;
            this.body = body;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            applyCommonHeaders(exchange.getResponseHeaders(), configuration.allowOrigin);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }
            Response response;
            try {
                response = body.handle(exchange);
            } catch (VisualizationAnalysisService.AnalysisException exception) {
                response = error(exception.status(), exception.code(), exception.getMessage());
            } catch (HttpFailure exception) {
                response = error(exception.status, exception.code, exception.getMessage());
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
                response = error(500, "internal_error", "The visualization service could not complete the request.");
            }
            writeJson(exchange, response);
        }
    }

    private static void applyCommonHeaders(Headers headers, String allowOrigin) {
        headers.set("Access-Control-Allow-Origin", allowOrigin);
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
    }

    private static void writeJson(HttpExchange exchange, Response response) throws IOException {
        byte[] payload = response.body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(response.status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private static Response error(int status, String code, String message) {
        return new Response(status, new JSONObject()
                .put("code", code)
                .put("message", message));
    }

    private static final class Response {
        private final int status;
        private final JSONObject body;

        private Response(int status, JSONObject body) {
            this.status = status;
            this.body = Objects.requireNonNull(body, "body");
        }
    }

    private static final class CallableRequest {
        private final String name;
        private final String kind;

        private CallableRequest(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }

        private static CallableRequest from(JSONObject request) {
            JSONObject callable = request.optJSONObject("callable");
            String name = callable == null
                    ? request.optString("predicate", "")
                    : callable.optString("name", "");
            String kind = callable == null
                    ? "predicate"
                    : callable.optString("kind", "callable");
            if (name.trim().isEmpty()) {
                throw new HttpFailure(
                        400,
                        "callable_required",
                        "Select a predicate or function to analyze.");
            }
            if (!kind.equals("predicate") && !kind.equals("function") && !kind.equals("callable")) {
                throw new HttpFailure(
                        400,
                        "invalid_callable_kind",
                        "Callable kind must be predicate or function.");
            }
            return new CallableRequest(name, kind);
        }

        private static CallableRequest from(JSONObject request, String field) {
            JSONObject callable = request.optJSONObject(field);
            if (callable == null) {
                throw new HttpFailure(
                        400,
                        "callable_required",
                        field + " must identify a predicate or function.");
            }
            JSONObject wrapper = new JSONObject().put("callable", callable);
            return from(wrapper);
        }
    }

    static final class HttpFailure extends RuntimeException {
        private final int status;
        private final String code;

        private HttpFailure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    private static final class Configuration {
        private final String bindAddress;
        private final int port;
        private final String allowOrigin;
        private final int workers;
        private final int timeoutSeconds;
        private final String workerHeap;

        private Configuration(
                String bindAddress,
                int port,
                String allowOrigin,
                int workers,
                int timeoutSeconds,
                String workerHeap) {
            this.bindAddress = bindAddress;
            this.port = port;
            this.allowOrigin = allowOrigin;
            this.workers = workers;
            this.timeoutSeconds = timeoutSeconds;
            this.workerHeap = workerHeap;
        }

        private static Configuration parse(String[] args) {
            String bind = "127.0.0.1";
            int port = DEFAULT_PORT;
            String origin = "http://localhost:5173";
            int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
            int timeoutSeconds = 120;
            String workerHeap = "1g";
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if ("--bind".equals(argument)) {
                    bind = value(args, ++index, argument);
                } else if ("--port".equals(argument)) {
                    port = positiveInt(value(args, ++index, argument), argument);
                } else if ("--allow-origin".equals(argument)) {
                    origin = value(args, ++index, argument);
                } else if ("--workers".equals(argument)) {
                    workers = positiveInt(value(args, ++index, argument), argument);
                } else if ("--timeout-seconds".equals(argument)) {
                    timeoutSeconds = positiveInt(value(args, ++index, argument), argument);
                } else if ("--worker-heap".equals(argument)) {
                    workerHeap = heapSize(value(args, ++index, argument), argument);
                } else if ("--help".equals(argument) || "-h".equals(argument)) {
                    System.out.println("Usage: VisualizationServer [--bind HOST] [--port PORT]"
                            + " [--allow-origin ORIGIN] [--workers N]"
                            + " [--timeout-seconds N] [--worker-heap SIZE]");
                    System.exit(0);
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + argument);
                }
            }
            return new Configuration(bind, port, origin, workers, timeoutSeconds, workerHeap);
        }

        private static String value(String[] args, int index, String flag) {
            if (index >= args.length || args[index].trim().isEmpty()) {
                throw new IllegalArgumentException(flag + " requires a value");
            }
            return args[index];
        }

        private static int positiveInt(String value, String flag) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed > 0 && parsed <= 65_535) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // Report a uniform command-line error below.
            }
            throw new IllegalArgumentException(flag + " requires a positive integer up to 65535");
        }

        private static String heapSize(String value, String flag) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.matches("[1-9][0-9]*[kmg]")) {
                return normalized;
            }
            throw new IllegalArgumentException(flag + " requires a JVM heap size such as 512m or 2g");
        }
    }
}

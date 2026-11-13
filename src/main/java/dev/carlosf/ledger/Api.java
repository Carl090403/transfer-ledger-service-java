package dev.carlosf.ledger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.carlosf.ledger.Domain.BusinessRuleException;
import dev.carlosf.ledger.Domain.NotFoundException;
import dev.carlosf.ledger.Domain.ValidationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** HTTP layer (JDK only): routing, JSON parsing, error mapping and one structured log line per request. */
public class Api {
    static final int MAX_BODY = 8 * 1024;
    private static final Pattern KEY_RE = Pattern.compile("^[A-Za-z0-9-]{8,64}$");
    private static final Pattern BALANCE = Pattern.compile("/accounts/([A-Z]{3}-\\d{6})/balance");
    private static final Pattern TRANSFER = Pattern.compile("/transfers/([0-9a-f-]{36})");
    private static final Logger LOG = Logger.getLogger("ledger");

    private final Ledger ledger;
    private final HttpServer server;
    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    public Api(Ledger ledger, int port) throws IOException {
        this.ledger = ledger;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(pool);
        server.createContext("/", this::dispatch);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        pool.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private static final class MethodNotAllowed extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private void dispatch(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        String requestId = ex.getRequestHeaders().getFirst("X-Request-Id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        int status;
        Object payload;
        try {
            Object[] r = route(ex);
            status = (Integer) r[0];
            payload = r[1];
        } catch (ValidationException e) {
            status = 400;
            payload = map("error", "validation_failed", "problems", e.problems());
        } catch (NotFoundException e) {
            status = 404;
            payload = map("error", "not_found", "message", e.getMessage());
        } catch (BusinessRuleException e) {
            status = 422;
            payload = map("error", "rule_violation", "message", e.getMessage());
        } catch (MethodNotAllowed e) {
            status = 405;
            payload = map("error", "method_not_allowed", "message", ex.getRequestMethod() + " is not supported");
        } catch (RuntimeException e) { // never leak internals (stack traces) to the client
            LOG.log(Level.SEVERE, "unhandled error request_id=" + requestId, e);
            status = 500;
            payload = map("error", "internal_error", "request_id", requestId);
        }
        byte[] body = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.getResponseHeaders().set("X-Request-Id", requestId);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
        double ms = Math.round((System.nanoTime() - started) / 10_000.0) / 100.0;
        LOG.info(Json.write(map("request_id", requestId, "method", ex.getRequestMethod(),
                "path", ex.getRequestURI().getRawPath(), "status", status, "ms", ms)));
    }

    private Object[] route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getRawPath(); // the query string is ignored for routing
        if (!method.equals("GET") && !method.equals("POST")) throw new MethodNotAllowed();
        if (method.equals("GET") && path.equals("/health")) return result(200, map("status", "ok"));
        Matcher m = BALANCE.matcher(path);
        if (method.equals("GET") && m.matches()) {
            long cents = ledger.balance(m.group(1));
            return result(200, map("account", m.group(1), "balance", Domain.formatCents(cents)));
        }
        m = TRANSFER.matcher(path);
        if (method.equals("GET") && m.matches()) return result(200, ledger.getTransfer(m.group(1)).toJson());
        if (method.equals("POST") && path.equals("/transfers")) {
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            if (key == null || !KEY_RE.matcher(key).matches()) {
                throw new ValidationException(List.of("Idempotency-Key header (8-64 chars) is required"));
            }
            Ledger.Result r = ledger.transfer(Domain.validateTransfer(readJson(ex)), key);
            return result(r.created() ? 201 : 200, r.transfer().toJson());
        }
        throw new NotFoundException("route not found");
    }

    private static Object readJson(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.contains("application/json")) {
            throw new ValidationException(List.of("Content-Type must be application/json"));
        }
        byte[] body;
        try (InputStream in = ex.getRequestBody()) {
            body = in.readNBytes(MAX_BODY + 1); // bounded read: never buffers more than the limit
        }
        if (body.length > MAX_BODY) throw new ValidationException(List.of("body too large"));
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.isBlank()) return null;
        try {
            return Json.parse(text);
        } catch (Json.ParseException e) {
            throw new ValidationException(List.of("malformed JSON"));
        }
    }

    private static Object[] result(int status, Object payload) {
        return new Object[] {status, payload};
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return m;
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%n");
        Ledger demo = new Ledger();
        demo.openAccount("CHQ-000001", "Demo A", 500_00);
        demo.openAccount("SAV-000002", "Demo B", 0);
        Api api = new Api(demo, 8080);
        api.start();
        System.out.println("Listening on http://127.0.0.1:8080");
    }
}

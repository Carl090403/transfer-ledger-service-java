package dev.carlosf.ledger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class ApiTest {
    private static final String BODY = "{\"source\":\"CHQ-000001\",\"destination\":\"SAV-000002\",\"amount\":\"25.00\"}";
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    private Api api;

    @BeforeClass
    public static void quietLogs() {
        Logger.getLogger("ledger").setLevel(Level.WARNING);
    }

    @Before
    public void start() throws Exception {
        Ledger ledger = new Ledger();
        ledger.openAccount("CHQ-000001", "Demo A", 500_00);
        ledger.openAccount("SAV-000002", "Demo B", 0);
        api = new Api(ledger, 0);
        api.start();
    }

    @After
    public void stop() {
        api.stop();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + api.port() + path);
    }

    private HttpResponse<String> post(String key, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/transfers")).POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) b.header("Idempotency-Key", key);
        if (contentType != null) b.header("Content-Type", contentType);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void healthEchoesRequestIdAndDisablesCaching() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(uri("/health")).header("X-Request-Id", "trace-123").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("trace-123", r.headers().firstValue("X-Request-Id").orElse(""));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void createThenReplayThenFetch() throws Exception {
        HttpResponse<String> created = post("demo-00000001", "application/json", BODY);
        assertEquals(201, created.statusCode());
        HttpResponse<String> replay = post("demo-00000001", "application/json", BODY);
        assertEquals(200, replay.statusCode());
        assertEquals(created.body(), replay.body());
        String id = (String) ((Map<String, Object>) Json.parse(created.body())).get("id");
        assertEquals(created.body(), get("/transfers/" + id).body());
        assertTrue(get("/accounts/SAV-000002/balance").body().contains("\"25.00\""));
    }

    @Test
    public void missingOrShortIdempotencyKeyIs400() throws Exception {
        assertEquals(400, post(null, "application/json", BODY).statusCode());
        assertEquals(400, post("short", "application/json", BODY).statusCode());
    }

    @Test
    public void validationErrorsListEveryProblem() throws Exception {
        HttpResponse<String> r = post("demo-00000002", "application/json", "{\"source\":\"x\",\"destination\":\"y\",\"amount\":12.5}");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("source must look like ABC-123456"));
        assertTrue(r.body().contains("amount must be a decimal string"));
    }

    @Test
    public void wrongContentTypeMalformedJsonAndOversizedBodyAre400() throws Exception {
        assertEquals(400, post("demo-00000003", "text/plain", BODY).statusCode());
        assertEquals(400, post("demo-00000004", "application/json", "{not json").statusCode());
        String huge = "{\"memo\":\"" + "x".repeat(Api.MAX_BODY) + "\"}";
        HttpResponse<String> r = post("demo-00000005", "application/json", huge);
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("body too large"));
    }

    @Test
    public void ruleViolationsAre422() throws Exception {
        String tooMuch = BODY.replace("25.00", "999.00");
        assertEquals(422, post("demo-00000006", "application/json", tooMuch).statusCode());
        post("demo-00000007", "application/json", BODY);
        assertEquals(422, post("demo-00000007", "application/json", BODY.replace("25.00", "26.00")).statusCode());
    }

    @Test
    public void unknownRoutesAndAccountsAre404WithoutLeakingInternals() throws Exception {
        HttpResponse<String> r = get("/nope");
        assertEquals(404, r.statusCode());
        assertFalse(r.body().contains("Exception"));
        assertEquals(404, get("/accounts/ZZZ-000000/balance").statusCode());
        assertEquals(404, get("/transfers/00000000-0000-0000-0000-000000000000").statusCode());
    }

    @Test
    public void queryStringIsIgnoredForRouting() throws Exception {
        assertEquals(200, get("/health?probe=1").statusCode());
    }

    @Test
    public void unsupportedMethodIsJson405() throws Exception {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(uri("/transfers")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
        assertTrue(r.body().contains("method_not_allowed"));
    }
}

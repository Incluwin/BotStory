package server.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the loopback admin HTTP server: auth behaviour and the /runtime/uptime
 * endpoint (which hits only static state, so it works without a full Server.init()).
 *
 * Endpoints that depend on Server.getInstance() (online, rates, shops, etc.) are covered by
 * compilation + manual testing — standing up a full Cosmic in a unit test is out of scope.
 */
class AdminControlPortTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private AdminControlPort port;
    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        port = new AdminControlPort(0, "test-token-abc");  // port 0 = ephemeral
        port.startServer();
        baseUrl = "http://127.0.0.1:" + port.getPort();
        client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    @AfterEach
    void tearDown() {
        if (port != null) port.stop();
    }

    @Test
    void missingAuthHeaderReturns401() throws Exception {
        HttpResponse<String> resp = get("/runtime/uptime", null);
        assertEquals(401, resp.statusCode());
        assertTrue(resp.body().contains("unauthorized"), resp.body());
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        HttpResponse<String> resp = get("/runtime/uptime", "Bearer nope");
        assertEquals(401, resp.statusCode());
    }

    @Test
    void emptyTokenInstanceReturns503() throws Exception {
        AdminControlPort p = new AdminControlPort(0, "");
        try {
            p.startServer();
            HttpClient c = HttpClient.newHttpClient();
            HttpResponse<String> resp = c.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + p.getPort() + "/runtime/uptime"))
                            .header("Authorization", "Bearer anything")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, resp.statusCode());
            assertTrue(resp.body().contains("auth_disabled"));
        } finally {
            p.stop();
        }
    }

    @Test
    void validTokenOnUptimeReturns200WithJson() throws Exception {
        HttpResponse<String> resp = get("/runtime/uptime", "Bearer test-token-abc");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("application/json"));
        String body = resp.body();
        assertTrue(body.contains("\"millis\""), body);
        assertTrue(body.contains("\"started_at\""), body);
    }

    @Test
    void unknownRuntimePathReturnsNotFoundJson() throws Exception {
        HttpResponse<String> resp = get("/runtime/does-not-exist", "Bearer test-token-abc");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("not_found"), resp.body());
    }

    @Test
    void postMethodRejectedAs405() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/runtime/uptime"))
                        .header("Authorization", "Bearer test-token-abc")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(405, resp.statusCode());
    }

    private HttpResponse<String> get(String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}

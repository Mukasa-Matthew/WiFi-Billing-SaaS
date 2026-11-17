package com.wifibilling.school;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Drives the real HTTP client against a stand-in identity server, so the mapping of identity's
 * status codes to portal errors is tested end to end rather than mocked away.
 */
class IdentityClientErrorMappingTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private HttpServer server;

    private IdentityClient answering(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new IdentityClient("http://127.0.0.1:" + server.getAddress().getPort(), SECRET);
    }

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void wrongPinReachesTheStudentWithAttemptsLeft() throws Exception {
        IdentityClient client = answering(401, "{\"status\":401,\"code\":\"WRONG_PIN\",\"detail\":\"That PIN is incorrect.\",\"attemptsLeft\":4}");
        IdentityException error = catchThrowableOfType(IdentityException.class, () -> client.verify(11, "DCN047/2347/2026/2027", "1234", null));
        assertThat(error.status()).isEqualTo(401);
        assertThat(error.code()).isEqualTo("WRONG_PIN");
        assertThat(error.getMessage()).isEqualTo("That PIN is incorrect.");
        assertThat(error.body().path("attemptsLeft").asInt()).isEqualTo(4);
    }

    @Test
    void expiredPinChangeTokenIsNotReportedAsAnOutage() throws Exception {
        IdentityClient client = answering(401, "{\"status\":401,\"code\":\"PIN_CHANGE_EXPIRED\",\"detail\":\"Sign in again to change your PIN.\"}");
        IdentityException error = catchThrowableOfType(IdentityException.class, () -> client.verify(11, "S1", "1234", null));
        assertThat(error.code()).isEqualTo("PIN_CHANGE_EXPIRED");
    }

    @Test
    void rejectedSignatureIsAnOutage() throws Exception {
        IdentityClient client = answering(401, "{\"status\":401,\"code\":\"INVALID_SIGNATURE\",\"detail\":\"The platform request signature is missing, invalid, expired or replayed.\"}");
        IdentityException error = catchThrowableOfType(IdentityException.class, () -> client.verify(11, "S1", "1234", null));
        assertThat(error.code()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(error.status()).isEqualTo(503);
    }

    @Test
    void unexplained401IsAnOutage() throws Exception {
        IdentityClient client = answering(401, "");
        IdentityException error = catchThrowableOfType(IdentityException.class, () -> client.verify(11, "S1", "1234", null));
        assertThat(error.code()).isEqualTo("IDENTITY_UNAVAILABLE");
    }

    @Test
    void serverErrorIsAnOutage() throws Exception {
        IdentityClient client = answering(500, "{\"code\":\"INTERNAL\"}");
        IdentityException error = catchThrowableOfType(IdentityException.class, () -> client.verify(11, "S1", "1234", null));
        assertThat(error.code()).isEqualTo("IDENTITY_UNAVAILABLE");
    }

    @Test
    void lockedAccountKeepsItsCountdown() throws Exception {
        IdentityClient client = answering(423, "{\"status\":423,\"code\":\"LOCKED\",\"detail\":\"Sign-in is temporarily locked.\",\"secondsRemaining\":600}");
        IdentityException error = catchThrowableOfType(IdentityException.class, () -> client.verify(11, "S1", "1234", null));
        assertThat(error.code()).isEqualTo("LOCKED");
        assertThat(error.body().path("secondsRemaining").asInt()).isEqualTo(600);
    }
}

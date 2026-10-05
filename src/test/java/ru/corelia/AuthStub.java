package ru.corelia;

import static ru.corelia.support.Json.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Минимальный независимый OIDC issuer для system tests. */
final class AuthStub implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final KeyPair keyPair;

    AuthStub() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(executor);
        server.createContext("/certs", this::certs);
        server.start();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String token(String login) {
        return token(object(
                "iss", base() + "/realm",
                "sub", "user-" + login,
                "preferred_username", login,
                "name", "Тестовый пользователь",
                "email", login + "@test.local",
                "exp", Instant.now().getEpochSecond() + 600,
                "aud", List.of("corelia-web"),
                "realm_access", object("roles", List.of("document_operator"))));
    }

    private String token(JsonNode claims) {
        try {
            var encoder = Base64.getUrlEncoder().withoutPadding();
            String input = encoder.encodeToString(write(object("alg", "RS256", "kid", "test-key"))
                            .getBytes(StandardCharsets.UTF_8))
                    + "."
                    + encoder.encodeToString(write(claims).getBytes(StandardCharsets.UTF_8));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + encoder.encodeToString(signature.sign());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private void certs(HttpExchange exchange) throws IOException {
        var key = (RSAPublicKey) keyPair.getPublic();
        byte[] response = write(object("keys", List.of(object(
                        "kid", "test-key",
                        "kty", "RSA",
                        "n", unsigned(key.getModulus().toByteArray()),
                        "e", unsigned(key.getPublicExponent().toByteArray())))))
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static String unsigned(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.length > 1 && value[0] == 0 ? java.util.Arrays.copyOfRange(value, 1, value.length) : value);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}

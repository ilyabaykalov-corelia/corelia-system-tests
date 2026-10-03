package ru.corelia;

import static org.junit.jupiter.api.Assertions.*;

import static ru.corelia.support.Json.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Проверяет собранные образы с независимым тестовым OIDC issuer. Отдельный Compose-проект всегда удаляется. */
class DockerSmokeTest {
    private final Path root = Path.of("..").toAbsolutePath().normalize();
    private final Path overlay = root.resolve("corelia-system-tests/target/docker-smoke.yaml");
    private final Path log = root.resolve("corelia-system-tests/target/docker-smoke.log");
    private final HttpClient client = HttpClient.newHttpClient();
    private String base;
    private String token;
    private Path customerPackage = root.resolve("../sber-npf-corelia-config").normalize();

    @Test
    void runsNativeV3ScenarioWithoutExternalProviderCalls() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("dockerSmoke"));
        Files.deleteIfExists(log);
        try (var auth = new AuthStub()) {
            customerPackage = root.resolve("corelia-system-tests/src/test/resources/customers/customer-v3");
            prepareOverlay(auth);
            try {
                try {
                    command("up", "--no-build", "--wait", "--wait-timeout", "240");
                } catch (AssertionError failure) {
                    try {
                        command("logs", "--no-color");
                    } catch (AssertionError logFailure) {
                        failure.addSuppressed(logFailure);
                    }
                    throw failure;
                }
                String address = command("port", "corelia-gateway", "7170").lines()
                        .filter(line -> line.matches("[^\\s:]+:\\d+"))
                        .reduce((first, second) -> second)
                        .orElseThrow(() -> new IllegalStateException("Docker Compose не вернул адрес gateway"));
                base = "http://" + address;
                assertEquals(
                        "corelia-gateway", text(call("GET", "/api/core/v1/health", null, 200), "service"));
                token = auth.token("operator");
                assertFalse(token.isEmpty());
                assertEquals("operator", text(call("GET", "/api/core/v1/auth/me", null, 200), "login"));
                assertEquals(
                        0,
                        number(
                                call(
                                        "POST",
                        "/api/core/v1/documents/V3_CONTRACT/search",
                                        object(),
                                        200),
                                "total",
                                0));
                String creationRequestId = UUID.randomUUID().toString();
                JsonNode created =
                        call(
                                "POST",
                                "/api/core/v1/documents/V3_CONTRACT",
                                object(
                                        "requestId", creationRequestId,
                                        "attributes", object("number", "DOCKER-1", "amount", 1)),
                                201);
                JsonNode repeatedCreation =
                        call(
                                "POST",
                                "/api/core/v1/documents/V3_CONTRACT",
                                object(
                                        "requestId", creationRequestId,
                                        "attributes", object("number", "DOCKER-1", "amount", 1)),
                                201);
                assertEquals(text(created, "id"), text(repeatedCreation, "id"));
                assertFalse(text(created, "processInstanceId").isEmpty());
                String attachmentPath = "/api/core/v1/documents/V3_CONTRACT/" + text(created, "id") + "/attachments";
                command("kill", "corelia-attachment-service");
                call(
                        "POST",
                        attachmentPath,
                        object("requestId", UUID.randomUUID().toString(), "attachments", List.of(object(
                                "fileName", "interrupted.pdf", "contentType", "application/pdf", "contentBase64", "dGVzdA=="))),
                502);
                command("start", "corelia-attachment-service");
                command("up", "--no-build", "--wait", "--wait-timeout", "240");
                command("stop", "seaweedfs");
                call(
                        "POST",
                        attachmentPath,
                        object("requestId", UUID.randomUUID().toString(), "attachments", List.of(object(
                                "fileName", "storage-unavailable.pdf", "contentType", "application/pdf", "contentBase64", "dGVzdA=="))),
                        500);
                command("start", "seaweedfs");
                command("up", "--no-build", "--wait", "--wait-timeout", "240");
                var files =
                        eventuallyCall(
                                "POST",
                                attachmentPath,
                                object(
                                        "requestId", UUID.randomUUID().toString(),
                                        "attachments",
                                        List.of(
                                                object(
                                                        "fileName", "test.pdf",
                                                        "contentType", "application/pdf",
                                                        "contentBase64",
                                                        "dGVzdA=="))),
                                201);
                String id = text(files.get(0), "id");
                assertFalse(id.isEmpty());
                JsonNode currentAttachments = call(
                        "GET",
                        "/api/core/v1/documents/V3_CONTRACT/" + text(created, "id") + "/attachments",
                        null,
                        200);
                assertEquals(id, text(currentAttachments.get(0), "id"));
                assertArrayEquals("test".getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes("/api/core/v1/attachments/" + id));
                JsonNode workflow = call("GET", "/api/core/v1/documents/V3_CONTRACT/" + text(created, "id") + "/workflow", null, 200);
                String taskId = text(workflow.path("task"), "id");
                assertFalse(taskId.isEmpty());
                call("POST", "/api/core/v1/tasks/" + taskId + "/start", object(), 200);
                JsonNode card = call("GET", "/api/core/v1/documents/V3_CONTRACT/" + text(created, "id"), null, 200);
                String documentPath = "/api/core/v1/documents/V3_CONTRACT/" + text(created, "id");
                JsonNode recoveryPatch = object(
                        "attributes", object("number", "DOCKER-RECOVERED", "amount", 2),
                        "expectedVersion", number(card, "version", 0),
                        "changeToken", text(card, "changeToken"),
                        "requestId", UUID.randomUUID().toString());
                command("kill", "corelia-data-service");
                assertTrue(Set.of(502, 504).contains(status("PATCH", documentPath, recoveryPatch)));
                command("start", "corelia-data-service");
                command("up", "--no-build", "--wait", "--wait-timeout", "240");
                assertEquals(
                        "DOCKER-RECOVERED",
                        text(eventuallyCall("PATCH", documentPath, recoveryPatch, 200).path("attributes"), "number"));
                card = call("GET", documentPath, null, 200);
                JsonNode concurrentCard = card;
                var concurrentEdits = List.of("DOCKER-2", "DOCKER-3").stream().map(requestedNumber -> {
                    JsonNode body = object("attributes", object("number", requestedNumber, "amount", 2),
                            "expectedVersion", number(concurrentCard, "version", 0),
                            "changeToken", text(concurrentCard, "changeToken"), "requestId", UUID.randomUUID().toString());
                    return client.sendAsync(HttpRequest.newBuilder(URI.create(base + documentPath))
                            .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .method("PATCH", HttpRequest.BodyPublishers.ofString(write(body)))
                            .build(), HttpResponse.BodyHandlers.ofString());
                }).toList();
                assertEquals(List.of(200, 409), concurrentEdits.stream()
                        .map(response -> response.join().statusCode()).sorted().toList());
                assertFalse(list(call("GET", "/api/core/v1/documents/V3_CONTRACT/" + text(created, "id") + "/history", null, 200).path("items")).isEmpty());
                JsonNode replacement = call(
                        "PUT",
                        "/api/core/v1/attachments/" + id,
                        object(
                                "requestId", UUID.randomUUID().toString(),
                                "attachments",
                                List.of(
                                        object(
                                                "fileName", "test-v2.pdf",
                                                "contentType", "application/pdf",
                                                "contentBase64", "dGVzdC0y"))),
                        200);
                String replacementId = text(replacement, "id");
                assertFalse(replacementId.isEmpty());
                assertArrayEquals(
                        "test-2".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        bytes("/api/core/v1/attachments/" + replacementId));
                JsonNode completion = object("actionCode", "approve", "requestId", UUID.randomUUID().toString());
                JsonNode completed = call("POST", "/api/core/v1/tasks/" + taskId + "/action", completion, 200);
                assertEquals(completed, call("POST", "/api/core/v1/tasks/" + taskId + "/action", completion, 200));
                assertTrue(
                        eventuallyCall(
                                        "GET",
                                        "/api/core/v1/documents/V3_CONTRACT/" + text(created, "id") + "/workflow",
                                        null,
                                        200)
                                .path("task")
                                .isNull());
                assertEquals(1, number(call("POST", "/api/core/v1/documents/V3_CONTRACT/search", object(), 200), "total", 0));
                Path backup = root.resolve("corelia-system-tests/target/backup-smoke");
                recreateDirectory(backup);
                script("scripts/backup.sh", backup.toString());
                JsonNode afterBackup = call(
                        "POST",
                        "/api/core/v1/documents/V3_CONTRACT",
                        object("requestId", UUID.randomUUID().toString(), "attributes", object("number", "AFTER-BACKUP", "amount", 3)),
                        201);
                script("scripts/restore.sh", backup.toString(), "--confirm");
                assertEquals("V3_CONTRACT", text(eventuallyCall("GET", documentPath, null, 200), "typeCode"));
                assertArrayEquals(
                        "test-2".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        bytes("/api/core/v1/attachments/" + replacementId));
                call("GET", "/api/core/v1/documents/V3_CONTRACT/" + text(afterBackup, "id"), null, 404);
                command("restart", "postgres");
                command("up", "--no-build", "--wait", "--wait-timeout", "240");
                assertEquals(
                        "V3_CONTRACT",
                        text(call("GET", documentPath, null, 200), "typeCode"));
                assertArrayEquals(
                        "test-2".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        bytes("/api/core/v1/attachments/" + replacementId));
                command("restart", "corelia-gateway", "corelia-document-service", "corelia-workflow-service", "corelia-attachment-service", "corelia-data-service");
                command("up", "--no-build", "--wait", "--wait-timeout", "240");
                address = command("port", "corelia-gateway", "7170").lines()
                        .filter(line -> line.matches("[^\\s:]+:\\d+"))
                        .reduce((first, second) -> second)
                        .orElseThrow(() -> new IllegalStateException("Docker Compose не вернул адрес gateway после перезапуска"));
                base = "http://" + address;
                assertEquals("corelia-gateway", text(call("GET", "/api/core/v1/health", null, 200), "service"));
                assertTrue(List.of("DOCKER-2", "DOCKER-3").contains(text(
                        call("GET", "/api/core/v1/documents/V3_CONTRACT/" + text(created, "id"), null, 200)
                                .path("attributes"),
                        "number")));
                assertArrayEquals(
                        "test-2".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        bytes("/api/core/v1/attachments/" + replacementId));
            } catch (AssertionError failure) {
                try {
                    command("logs", "--no-color");
                } catch (AssertionError logFailure) {
                    failure.addSuppressed(logFailure);
                }
                throw failure;
            } finally {
                command("down", "--volumes", "--remove-orphans");
            }
        }
    }

    private void prepareOverlay(AuthStub auth) throws Exception {
        String dockerBase = auth.base().replace("127.0.0.1", "host.docker.internal");
            StringBuilder yaml = new StringBuilder("""
                    services:
                      postgres:
                        container_name: corelia-smoke-postgres
                      seaweedfs:
                        container_name: corelia-smoke-seaweedfs
                        ports: !override []
                      keycloak:
                        container_name: corelia-smoke-keycloak
                        ports: !override []
                      corelia-data-service:
                        container_name: corelia-smoke-data-service
                        image: corelia-smoke/data-service:test
                    """);
            yaml.append("    environment:\n")
                    .append("      CORELIA_AUTH_ISSUER: ")
                    .append(auth.base())
                    .append("/realm\n")
                    .append("      CORELIA_AUTH_JWKS_URL: ")
                    .append(dockerBase)
                    .append("/certs\n");
            for (String service :
                    List.of(
                            "gateway",
                            "document-service",
                            "workflow-service",
                            "attachment-service")) {
                yaml.append("  corelia-")
                        .append(service)
                        .append(":\n    container_name: corelia-smoke-").append(service)
                        .append("\n    image: corelia-smoke/").append(service).append(":test\n    environment:\n")
                        .append("      CORELIA_AUTH_ISSUER: ")
                        .append(auth.base())
                        .append("/realm\n")
                        .append("      CORELIA_AUTH_JWKS_URL: ")
                        .append(dockerBase)
                        .append("/certs\n")
                        .append(
                                "      CORELIA_PROVIDER_DOCUMENTS: native-data\n"
                                    + "      CORELIA_PROVIDER_DOCUMENT_VERSIONS: native-data\n"
                                    + "      CORELIA_PROVIDER_DOCUMENT_TYPES: native-data\n"
                                    + "      CORELIA_PROVIDER_ATTACHMENTS: native-data\n"
                                    + "      CORELIA_DOCUMENT_PROVIDER_WORKFLOW: flowable\n"
                                    + "      CORELIA_DOCUMENT_PROVIDER_TASKS: flowable\n"
                                    + "      CORELIA_PROVIDER_WORKFLOW: flowable\n"
                                    + "      CORELIA_PROVIDER_TASKS: flowable\n"
                                    + "      CORELIA_FLOWABLE_DATABASE_SCHEMA_UPDATE: 'true'\n"
                                    + "      REDIS_URL: ''\n");
                String capabilities = switch (service) {
                    case "gateway" -> "permissions";
                    case "document-service" -> "documents,document-versions,document-types,attachments,permissions";
                    case "workflow-service" -> "documents,workflow,tasks,permissions";
                    case "attachment-service" -> "attachments,binary-storage,permissions";
                    default -> throw new IllegalArgumentException("Неизвестный сервис: " + service);
                };
                yaml.append("      CORELIA_PROVIDER_CAPABILITIES: ").append(capabilities).append("\n");
                if (service.equals("attachment-service"))
                    yaml.append("      CORELIA_PROVIDER_BINARY_STORAGE: s3\n");
                if (service.equals("gateway"))
                    yaml.append("    ports: !override [\"127.0.0.1:0:7170\"]\n");
                String providers = switch (service) {
                    case "gateway" -> "corelia-provider-native-permissions";
                    case "document-service" -> "corelia-provider-native-data,corelia-provider-native-permissions";
                    case "workflow-service" -> "corelia-provider-flowable,corelia-provider-native-data,corelia-provider-native-permissions";
                    case "attachment-service" -> "corelia-provider-s3,corelia-provider-native-data,corelia-provider-native-permissions";
                    default -> throw new IllegalArgumentException("Неизвестный сервис: " + service);
                };
                yaml.append("    build:\n      args:\n        PROVIDER_MODULES: ").append(providers).append("\n");
            }
            Files.writeString(overlay, yaml);
    }

    private static void recreateDirectory(Path directory) throws Exception {
        if (Files.exists(directory)) try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
        Files.createDirectories(directory);
    }

    private JsonNode call(String method, String path, JsonNode body, int expected)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response =
                client.send(
                        request.method(
                                        method,
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofString(write(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body());
        return parse(response.body());
    }

    private int status(String method, String path, JsonNode body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(
                        request.method(method, HttpRequest.BodyPublishers.ofString(write(body))).build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private JsonNode eventuallyCall(String method, String path, JsonNode body, int expected)
            throws Exception {
        AssertionError failure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                return call(method, path, body, expected);
            } catch (AssertionError currentFailure) {
                failure = currentFailure;
                TimeUnit.SECONDS.sleep(1);
            } catch (java.io.IOException ignored) {
                TimeUnit.SECONDS.sleep(1);
            }
        }
        throw failure == null ? new AssertionError("Сервис не стал доступен за отведённое время") : failure;
    }

    private byte[] bytes(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60));
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private String command(String... arguments) throws Exception {
        List<String> command =
                new ArrayList<>(
                        List.of(
                                "docker",
                                "compose",
                                "--project-name",
                                "corelia-smoke",
                                "-f",
                                "compose.yaml",
                                "-f",
                                overlay.toString()));
        command.addAll(List.of(arguments));
        var builder =
                new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("CORELIA_CUSTOMER_CONFIG", customerPackage.toString());
        builder.environment().put("CORELIA_INTERNAL_NETWORK_SUBNET", "172.31.0.0/16");
        for (String field : List.of("u", "g")) {
            var id = new ProcessBuilder("id", "-" + field).start();
            String value = new String(id.getInputStream().readAllBytes()).trim();
            id.waitFor();
            builder.environment().put(field.equals("u") ? "CORELIA_UID" : "CORELIA_GID", value);
        }
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process process = builder.start();
        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        String commandOutput = Files.readString(log);
        assertTrue(finished, "Команда Docker Compose превысила лимит 15 минут: " + String.join(" ", command));
        assertEquals(0, process.exitValue(), commandOutput);
        return commandOutput;
    }

    private void script(String path, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(root.resolve(path).toString());
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
        builder.environment().put("COMPOSE_PROJECT_NAME", "corelia-smoke");
        builder.environment().put("COMPOSE_FILE", "compose.yaml" + java.io.File.pathSeparator + overlay);
        builder.environment().put("CORELIA_CUSTOMER_CONFIG", customerPackage.toString());
        builder.environment().put("CORELIA_INTERNAL_NETWORK_SUBNET", "172.31.0.0/16");
        for (String field : List.of("u", "g")) {
            var id = new ProcessBuilder("id", "-" + field).start();
            String value = new String(id.getInputStream().readAllBytes()).trim();
            id.waitFor();
            builder.environment().put(field.equals("u") ? "CORELIA_UID" : "CORELIA_GID", value);
        }
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process process = builder.start();
        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        assertTrue(finished, "Скрипт превысил лимит 15 минут: " + String.join(" ", command));
        assertEquals(0, process.exitValue(), Files.readString(log));
    }
}

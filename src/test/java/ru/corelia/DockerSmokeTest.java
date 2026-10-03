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

/** Проверяет собранные образы на имитации платформы. Отдельный Compose-проект всегда удаляется. */
class DockerSmokeTest {
    private final Path root = Path.of("..").toAbsolutePath().normalize();
    private final Path overlay = root.resolve("corelia-system-tests/target/docker-smoke.yaml");
    private final Path log = root.resolve("corelia-system-tests/target/docker-smoke.log");
    private final HttpClient client = HttpClient.newHttpClient();
    private String base;
    private String token;
    private Path customerPackage = root.resolve("../sber-npf-corelia-config").normalize();

    @Test
    void runsNativeV3ScenarioWithoutPlatformVCalls() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("dockerSmoke"));
        Files.deleteIfExists(log);
        try (var platform = new PlatformStub()) {
            customerPackage = root.resolve("corelia-system-tests/src/test/resources/customers/customer-v3");
            prepareOverlay(platform);
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
                token = platform.token("operator");
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
                call("PATCH", documentPath, recoveryPatch, 504);
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
                call("POST", "/api/core/v1/tasks/" + taskId + "/action", object("actionCode", "approve"), 200);
                assertEquals(1, number(call("POST", "/api/core/v1/documents/V3_CONTRACT/search", object(), 200), "total", 0));
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
                assertTrue(platform.calls.stream().noneMatch(value -> value.path().startsWith("/graphql")
                        || value.path().startsWith("/bpmx") || value.path().startsWith("/bpmu") || value.path().startsWith("/dam")));
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

    /** Startup/catalog proof only: fixture model/transactions still need real Platform V acceptance. */
    @org.junit.jupiter.api.Disabled("V2 Platform V compatibility scenario удалён из PHASE 11 native suite")
    @Test
    void sameImagesLoadTwoExternalCatalogsWithoutRebuilding() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("dockerSmoke"));
        Set<String> imageIds = null;
        for (String customer : List.of("customer-a", "customer-b")) {
            customerPackage = prepareCatalogFixture(customer);
            token = null;
            try (var platform = new PlatformStub()) {
                prepareOverlay(platform);
                try {
                    command("up", "--no-build", "--wait", "--wait-timeout", "240");
                    Set<String> currentImages = new TreeSet<>(command("images", "--quiet").lines().filter(line -> !line.isBlank()).toList());
                    assertEquals(4, currentImages.size());
                    if (imageIds == null) imageIds = currentImages;
                    else assertEquals(imageIds, currentImages, "Customer switch must not rebuild or replace images");
                    base = "http://" + command("port", "corelia-gateway", "7170").trim();
                    token = platform.token("operator");
                    var catalog = call("GET", "/api/core/v1/document-types", null, 200);
                    assertEquals(customer.equals("customer-a") ? 2 : 5, number(catalog, "total", 0));
                    for (JsonNode type : list(catalog.path("items"))) {
                        assertTrue(text(type, "name").startsWith(customer));
                        assertTrue(type.path("attachments").isObject());
                        assertFalse(type.has("storage"));
                        String code = text(type, "code");
                        assertEquals(code, text(call("GET", "/api/core/v1/document-types/" + code, null, 200), "code"));
                        // Signed default platform role is not granted fixture-specific creation permission.
                        call("POST", "/api/core/v1/documents/" + code, object("attributes", object()), 403);
                    }
                    assertTrue(platform.calls.stream().noneMatch(call -> call.path().startsWith("/bpmx")));
                } finally { command("down", "--remove-orphans"); }
            }
        }
    }

    private Path prepareCatalogFixture(String customer) throws Exception {
        Path fixture = root.resolve("corelia-system-tests/src/test/resources/customers/" + customer);
        Path output = root.resolve("corelia-system-tests/target/docker-catalog-" + customer);
        recreateDirectory(output);
        copyDirectory(fixture.resolve("data-model"), output.resolve("data-model"));
        copyDirectory(fixture.resolve("ui"), output.resolve("ui"));
        copyDirectory(fixture.resolve("permissions"), output.resolve("permissions"));
        copyDirectory(fixture.resolve("operations"), output.resolve("operations"));
        copyDirectory(fixture.resolve("graphql"), output.resolve("graphql"));
        // Общие операции adapter-а добавляются к внешнему каталогу без legacy поля configuration.operations.
        Path migration = root.resolve("../sber-npf-corelia-config");
        for (String name : List.of("searchDocument", "searchDocumentVersion", "searchDocumentCommand", "searchAttachment", "initializeDocumentVersion", "commitDocumentNoChange", "commitDocumentFileUpload", "commitDocumentFileReplace", "commitDocumentFileDelete", "searchDocumentProcessSettings", "refDocumentTypeListGet")) {
            Path operation = migration.resolve("operations").resolve(name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT) + ".json");
            Path copiedOperation = output.resolve("operations").resolve(operation.getFileName());
            Files.copy(operation, copiedOperation, StandardCopyOption.REPLACE_EXISTING);
            String graphql = text(parse(Files.readString(operation)), "file");
            Path source = operation.getParent().resolve(graphql).normalize();
            Path target = copiedOperation.getParent().resolve(graphql).normalize();
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        var config = copy(parse(Files.readString(fixture.resolve("configuration.json"))));
        Files.writeString(output.resolve("configuration.json"), write(config));
        return output;
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path relative = source.relativize(file), destination = target.resolve(relative);
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void recreateDirectory(Path directory) throws Exception {
        if (Files.exists(directory)) try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
        Files.createDirectories(directory);
    }

    private void prepareOverlay(PlatformStub platform) throws Exception {
            String dockerBase = platform.base().replace("127.0.0.1", "host.docker.internal");
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
                    .append(platform.base())
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
                        .append("      PLATFORM_V_KEYCLOAK_BASE_URL: ")
                        .append(dockerBase)
                        .append("/realm\n")
                        .append("      PLATFORM_V_KEYCLOAK_ISSUER: ")
                        .append(platform.base())
                        .append("/realm\n")
                        .append("      CORELIA_AUTH_ISSUER: ")
                        .append(platform.base())
                        .append("/realm\n")
                        .append("      CORELIA_AUTH_JWKS_URL: ")
                        .append(dockerBase)
                        .append("/certs\n")
                        .append("      PLATFORM_V_DATASPACE_GRAPHQL_URL: ")
                        .append(dockerBase)
                        .append("/graphql\n")
                        .append("      PLATFORM_V_BPMX_BASE_URL: ")
                        .append(dockerBase)
                        .append("/bpmx\n")
                        .append("      PLATFORM_V_TASK_LIST_BASE_URL: ")
                        .append(dockerBase)
                        .append("/bpmu\n")
                        .append("      PLATFORM_V_FILE_STORAGE_BASE_URL: ")
                        .append(dockerBase)
                        .append("/dam\n")
                        .append(
                                "      PLATFORM_V_TENANT: tenant-test\n"
                                    + "      PLATFORM_V_APP_INSTANCE_ID: app-test\n"
                                    + "      CORELIA_PROVIDER_DOCUMENTS: native-data\n"
                                    + "      CORELIA_PROVIDER_DOCUMENT_VERSIONS: native-data\n"
                                    + "      CORELIA_PROVIDER_DOCUMENT_TYPES: native-data\n"
                                    + "      CORELIA_PROVIDER_ATTACHMENTS: native-data\n"
                                    + "      CORELIA_DOCUMENT_PROVIDER_WORKFLOW: flowable\n"
                                    + "      CORELIA_DOCUMENT_PROVIDER_TASKS: flowable\n"
                                    + "      CORELIA_PROVIDER_WORKFLOW: flowable\n"
                                    + "      CORELIA_PROVIDER_TASKS: flowable\n"
                                    + "      CORELIA_FLOWABLE_DATABASE_SCHEMA_UPDATE: 'true'\n"
                                    + "      CORELIA_WORKFLOW_LEGACY_PLATFORM_V_ENABLED: 'false'\n"
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

    private JsonNode eventuallyCall(String method, String path, JsonNode body, int expected)
            throws Exception {
        AssertionError failure = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                return call(method, path, body, expected);
            } catch (AssertionError currentFailure) {
                failure = currentFailure;
                TimeUnit.SECONDS.sleep(1);
            }
        }
        throw failure;
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
}

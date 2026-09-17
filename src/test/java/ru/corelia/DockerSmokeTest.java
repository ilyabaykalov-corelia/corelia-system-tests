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
    void runsFiveContainersAgainstPlatformStub() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("dockerSmoke"));
        try (var platform = new PlatformStub()) {
            prepareOverlay(platform, root.resolve("../sber-npf-platform-v/ac.json").normalize());
            try {
                command("up", "--no-build", "--wait", "--wait-timeout", "240");
                String address = command("port", "corelia-gateway", "7170").trim();
                base = "http://" + address;
                assertEquals(
                        "corelia-gateway", text(call("GET", "/api/core/v1/health", null, 200), "service"));
                token =
                        text(
                                call(
                                        "POST",
                                        "/api/core/v1/auth/login",
                                        object("username", "operator", "password", "secret"),
                                        200),
                                "accessToken");
                assertFalse(token.isEmpty());
                assertEquals("operator", text(call("GET", "/api/core/v1/auth/me", null, 200), "login"));
                assertEquals(
                        1,
                        number(
                                call(
                                        "POST",
                                        "/api/core/v1/documents/PDS_CONTRACT/search",
                                        object(),
                                        200),
                                "total",
                                0));
                assertEquals("doc-1", text(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null, 200), "id"));
                JsonNode created =
                        call(
                                "POST",
                                "/api/core/v1/documents/PDS_CONTRACT",
                                object(
                                        "attributes",
                                        object(
                                                "contractDate",
                                                "2026-09-08",
                                                "contractNumber",
                                                "DOCKER-1",
                                                "snils",
                                                "123-456-789 00")),
                                201);
                assertEquals("process-1", text(created, "processInstanceId"));
                platform.returnFollowUp = true;
                call("POST", "/api/core/v1/tasks/task-1/action", object("actionCode", "IN_WORK"), 200);
                var files =
                        call(
                                "POST",
                                "/api/core/v1/documents/PDS_CONTRACT/" + text(created, "id") + "/attachments",
                                object(
                                        "requestId", UUID.randomUUID().toString(),
                                        "attachments",
                                        List.of(
                                                object(
                                                        "fileName",
                                                        "test.txt",
                                                        "contentType",
                                                        "text/plain",
                                                        "contentBase64",
                                                        "dGVzdA=="))),
                                201);
                String id = text(files.get(0), "id");
                assertFalse(id.isEmpty());
                call("DELETE", "/api/core/v1/attachments/" + id + "?requestId=" + UUID.randomUUID(), null, 200);
                call("POST", "/api/core/v1/tasks/task-2/action", object("actionCode", "APPROVED"), 200);
            } finally {
                command("down", "--remove-orphans");
            }
        }
    }

    /** Startup/catalog proof only: fixture model/transactions still need real Platform V acceptance. */
    @Test
    void sameImagesLoadTwoExternalCatalogsWithoutRebuilding() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("dockerSmoke"));
        Set<String> imageIds = null;
        for (String customer : List.of("customer-a", "customer-b")) {
            customerPackage = prepareCatalogFixture(customer);
            token = null;
            try (var platform = new PlatformStub()) {
                prepareOverlay(platform, customerPackage.resolve("platform-v-ac.json"));
                try {
                    command("up", "--no-build", "--wait", "--wait-timeout", "240");
                    Set<String> currentImages = new TreeSet<>(command("images", "--quiet").lines().filter(line -> !line.isBlank()).toList());
                    assertEquals(5, currentImages.size());
                    if (imageIds == null) imageIds = currentImages;
                    else assertEquals(imageIds, currentImages, "Customer switch must not rebuild or replace images");
                    base = "http://" + command("port", "corelia-gateway", "7170").trim();
                    token = text(call("POST", "/api/core/v1/auth/login", object("username", "operator", "password", "secret"), 200), "accessToken");
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
        Files.createDirectories(output.resolve("graphql"));
        var config = copy(parse(Files.readString(fixture.resolve("configuration.json"))));
        var operations = copy(config.path("operations"));
        try (var files = Files.list(fixture.resolve("graphql"))) {
            for (Path file : files.toList()) Files.copy(file, output.resolve("graphql").resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        // Common adapter startup operations are supplied as static texts, not executed by this catalog-only test.
        Path migration = root.resolve("../sber-npf-corelia-config");
        var common = parse(Files.readString(migration.resolve("configuration.json"))).path("operations");
        for (String name : List.of("searchDocument", "searchDocumentVersion", "searchDocumentCommand", "searchAttachment", "initializeDocumentVersion", "commitDocumentNoChange", "commitDocumentFileUpload", "commitDocumentFileReplace", "commitDocumentFileDelete", "searchDocumentProcessSettings", "refDocumentTypeListGet")) {
            operations.set(name, common.path(name));
            Path file = Path.of(text(common.path(name), "file"));
            Files.copy(migration.resolve(file), output.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        config.set("operations", operations);
        Files.writeString(output.resolve("configuration.json"), write(config));
        Files.copy(fixture.resolve("platform-v-ac.json"), output.resolve("platform-v-ac.json"), StandardCopyOption.REPLACE_EXISTING);
        return output;
    }

    private void prepareOverlay(PlatformStub platform, Path accessControl) throws Exception {
            String dockerBase = platform.base().replace("127.0.0.1", "host.docker.internal");
            StringBuilder yaml = new StringBuilder("services:\n");
            for (String service :
                    List.of(
                            "gateway",
                            "auth",
                            "document-service",
                            "workflow-service",
                            "attachment-service")) {
                yaml.append("  corelia-")
                        .append(service)
                        .append(":\n    container_name: corelia-smoke-").append(service).append("\n    volumes:\n      - ").append(accessControl).append(":/etc/corelia/platform-v-ac.json:ro\n    environment:\n      CORELIA_PLATFORM_V_AC_PATH: /etc/corelia/platform-v-ac.json\n")
                        .append("      PLATFORM_V_KEYCLOAK_BASE_URL: ")
                        .append(dockerBase)
                        .append("/realm\n")
                        .append("      PLATFORM_V_KEYCLOAK_ISSUER: ")
                        .append(platform.base())
                        .append("/realm\n")
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
                                    + "      REDIS_URL: ''\n");
                if (service.equals("gateway"))
                    yaml.append("    ports: !override [\"127.0.0.1:0:7170\"]\n");
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
        for (String field : List.of("u", "g")) {
            var id = new ProcessBuilder("id", "-" + field).start();
            String value = new String(id.getInputStream().readAllBytes()).trim();
            id.waitFor();
            builder.environment().put(field.equals("u") ? "CORELIA_UID" : "CORELIA_GID", value);
        }
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        Files.writeString(log, output, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        assertEquals(0, exit, output);
        return output;
    }
}

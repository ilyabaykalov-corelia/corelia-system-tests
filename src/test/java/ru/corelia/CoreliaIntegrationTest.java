package ru.corelia;

import static org.junit.jupiter.api.Assertions.*;

import static ru.corelia.support.Json.*;

import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import ru.corelia.cache.TaskCache;

import tools.jackson.databind.JsonNode;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Проверяет REST-контракты через настоящий Tomcat, JWKS, DataSpace, BPMX, BPMU и DAM. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreliaIntegrationTest {
    private PlatformStub platform;
    private WebServerApplicationContext app;
    private final HttpClient http = HttpClient.newHttpClient();
    private String base;
    private String token;

    private final List<org.springframework.context.ConfigurableApplicationContext> contexts =
            new ArrayList<>();
    private final Map<String, String> addresses = new HashMap<>();
    private final java.nio.file.Path root =
            java.nio.file.Path.of("..").toAbsolutePath().normalize();

    @BeforeAll
    void start() throws Exception {
        platform = new PlatformStub();
        startService(
                ru.corelia.workflow.WorkflowApplication.class,
                "corelia-workflow-service",
                "workflow");
        startService(
                ru.corelia.documents.DocumentApplication.class,
                "corelia-document-service",
                "document");
        startService(
                ru.corelia.attachments.AttachmentApplication.class,
                "corelia-attachment-service",
                "attachment");
        startService(ru.corelia.identity.AuthApplication.class, "corelia-auth", "auth");
        app =
                startService(
                        ru.corelia.gateway.GatewayApplication.class, "corelia-gateway", "gateway");
        base = addresses.get("gateway");
    }

    private WebServerApplicationContext startService(
            Class<?> application, String service, String target) throws Exception {
        String cert = root.resolve(".local/certs/" + service).toString();
        String password =
                java.nio.file.Files.readString(root.resolve(".local/secrets/corelia.tls.password"))
                        .trim();
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "--spring.config.name=corelia-test",
                                "--server.port=0",
                                "--spring.main.banner-mode=off",
                                "--spring.threads.virtual.enabled=true",
                                "--corelia.service=" + service,
                                "--corelia.internal.key-store=" + cert + "/identity.p12",
                                "--corelia.internal.trust-store=" + cert + "/trust.p12",
                                "--corelia.tls.password=" + password,
                                "--PLATFORM_V_KEYCLOAK_BASE_URL=" + platform.base() + "/realm",
                                "--PLATFORM_V_DATASPACE_GRAPHQL_URL="
                                        + platform.base()
                                        + "/graphql",
                                "--PLATFORM_V_BPMX_BASE_URL=" + platform.base() + "/bpmx",
                                "--PLATFORM_V_TASK_LIST_BASE_URL=" + platform.base() + "/bpmu",
                                "--PLATFORM_V_FILE_STORAGE_BASE_URL=" + platform.base() + "/dam",
                                "--PLATFORM_V_TENANT=tenant-test",
                                "--PLATFORM_V_APP_INSTANCE_ID=app-test",
                                "--REDIS_URL=",
                                "--MAX_BODY_SIZE_MB=1",
                                "--logging.level.root=WARN"));
        if (!target.equals("gateway"))
            args.addAll(
                    List.of(
                            "--server.ssl.enabled=true",
                            "--server.ssl.key-store=file:" + cert + "/identity.p12",
                            "--server.ssl.key-store-password=" + password,
                            "--server.ssl.key-store-type=PKCS12",
                            "--server.ssl.trust-store=file:" + cert + "/trust.p12",
                            "--server.ssl.trust-store-password=" + password,
                            "--server.ssl.trust-store-type=PKCS12",
                            "--server.ssl.client-auth=need"));
        addresses.forEach(
                (name, address) -> args.add("--corelia.services." + name + "=" + address));
        var context =
                (WebServerApplicationContext)
                        new SpringApplicationBuilder(application).run(args.toArray(String[]::new));
        contexts.add((org.springframework.context.ConfigurableApplicationContext) context);
        addresses.put(
                target,
                (target.equals("gateway") ? "http" : "https")
                        + "://localhost:"
                        + context.getWebServer().getPort());
        return context;
    }

    @BeforeEach
    void reset() {
        platform.reset();
        app.getBean(TaskCache.class).invalidate();
        token = platform.token("operator");
    }

    @AfterAll
    void stop() {
        for (var context : contexts.reversed()) context.close();
        if (platform != null) platform.close();
    }

    private HttpResponse<String> call(String method, String path, JsonNode payload)
            throws Exception {
        if (method.equals("PATCH") && payload != null && !payload.has("requestId") && payload.path("attributes").isObject()
                && !payload.path("attributes").path("snils").isNull()) {
            JsonNode card = ok(raw("GET", path, null, token), 200);
            payload = copy(payload).put("requestId", UUID.randomUUID().toString())
                .put("expectedVersion", number(card, "version", 1)).put("changeToken", text(card, "changeToken"));
        }
        if (method.equals("DELETE") && path.contains("/attachments/") && !path.contains("?")) path += "?requestId=" + UUID.randomUUID();
        return raw(method, path, payload == null ? null : write(payload), token);
    }

    private HttpResponse<String> raw(String method, String path, String body, String token)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.header("Content-Type", "application/json");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        return response.body().isEmpty() ? object() : parse(response.body());
    }

    private JsonNode validDocument() {
        return object(
                "attributes",
                object(
                        "contractDate", "2026-09-07",
                        "contractNumber", "NEW-1",
                        "snils", "123-456-789 00"));
    }

    private JsonNode upload(String name, String text) {
        return object(
                "requestId", UUID.randomUUID().toString(),
                "attachments",
                List.of(
                        object(
                                "fileName",
                                name,
                                "contentType",
                                "text/plain",
                                "contentBase64",
                                Base64.getEncoder()
                                        .encodeToString(text.getBytes(StandardCharsets.UTF_8)))));
    }

    @Test
    void healthAndPreflightArePublic() throws Exception {
        JsonNode health = ok(raw("GET", "/api/core/v1/health", null, null), 200);
        assertEquals("corelia-gateway", text(health, "service"));
        assertEquals(1, health.path("schemaVersion").asInt());
        ok(raw("GET", "/api/core/v1/health", null, null), 200);
        var preflight = raw("OPTIONS", "/api/core/v1/documents/PDS_CONTRACT", null, null);
        assertEquals(204, preflight.statusCode());
        assertEquals(
                "*", preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
    }

    @Test
    void protectedRoutesRejectMissingToken() throws Exception {
        assertEquals(
                "Не передан Keycloak access token",
                text(ok(raw("GET", "/api/core/v1/auth/me", null, null), 401), "message"));
        assertTrue(platform.calls.isEmpty());
    }

    @Test
    void signedTokenReturnsProfileAndJwksIsCached() throws Exception {
        JsonNode user = ok(call("GET", "/api/core/v1/auth/me", null), 200);
        assertEquals("operator", text(user, "login"));
        assertEquals("Иван Иванов", text(user, "fullName"));
        int calls = platform.jwksCalls.get();
        assertEquals(user, ok(call("GET", "/api/core/v1/auth/me", null), 200));
        assertEquals(calls, platform.jwksCalls.get());
    }

    @Test
    void testerDoesNotReceiveRolesAbsentFromToken() {
        var claims = copy(platform.claims("tester"));
        claims.set("realm_access", object("roles", List.of("user")));
        claims.set("resource_access", object());
        var auth =
                app.getBean(ru.corelia.auth.JwtVerifier.class)
                        .authenticate("Bearer " + platform.token(claims, platform.kid, "RS256"));
        assertEquals(List.of("user"), auth.roles());
    }

    @Test
    void rejectsMalformedUnsignedAndForgedTokens() throws Exception {
        for (String bad :
                List.of(
                        "invalid",
                        "a.b.c",
                        platform.token(platform.claims("operator"), platform.kid, "none"))) {
            ok(raw("GET", "/api/core/v1/auth/me", null, bad), 401);
        }
        String[] parts = token.split("\\.");
        String forged =
                parts[0]
                        + "."
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        write(platform.claims("admin"))
                                                .getBytes(StandardCharsets.UTF_8))
                        + "."
                        + parts[2];
        ok(raw("GET", "/api/core/v1/auth/me", null, forged), 401);
    }

    @Test
    void checksIssuerExpirationAndNotBefore() throws Exception {
        var wrong = platform.claims("operator").put("iss", "https://other.test");
        ok(raw("GET", "/api/core/v1/auth/me", null, platform.token(wrong, platform.kid, "RS256")), 401);
        var expired = platform.claims("operator").put("exp", Instant.now().getEpochSecond() - 31);
        ok(raw("GET", "/api/core/v1/auth/me", null, platform.token(expired, platform.kid, "RS256")), 401);
        var future = platform.claims("operator").put("nbf", Instant.now().getEpochSecond() + 120);
        ok(raw("GET", "/api/core/v1/auth/me", null, platform.token(future, platform.kid, "RS256")), 401);
        var missing = platform.claims("operator");
        missing.remove("exp");
        ok(raw("GET", "/api/core/v1/auth/me", null, platform.token(missing, platform.kid, "RS256")), 401);
        var skew = platform.claims("operator").put("exp", Instant.now().getEpochSecond() - 10);
        ok(raw("GET", "/api/core/v1/auth/me", null, platform.token(skew, platform.kid, "RS256")), 200);
    }

    @Test
    void refreshesJwksWhenKeyRotates() throws Exception {
        ok(call("GET", "/api/core/v1/auth/me", null), 200);
        platform.kid = "rotated-" + UUID.randomUUID();
        int before = platform.jwksCalls.get();
        ok(raw("GET", "/api/core/v1/auth/me", null, platform.token("operator")), 200);
        // После ротации каждый из двух сервисов независимо обновляет ключи.
        assertEquals(before + 2, platform.jwksCalls.get());
        ok(
                raw(
                        "GET",
                        "/api/core/v1/auth/me",
                        null,
                        platform.token(platform.claims("operator"), "unknown", "RS256")),
                401);
    }

    @Test
    void loginRefreshLogoutPreserveSessionContract() throws Exception {
        long now = System.currentTimeMillis();
        JsonNode session =
                ok(
                        raw(
                                "POST",
                                "/api/core/v1/auth/login",
                                write(object("username", " оператор ", "password", "пароль &+")),
                                null),
                        200);
        assertEquals("refresh-test", text(session, "refreshToken"));
        assertTrue(number(session, "expiresAt", 0) >= now + 300000);
        var form =
                platform.calls.stream()
                        .filter(call -> call.path().endsWith("/token"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(form.body().contains("grant_type=password"));
        assertTrue(form.body().contains("client_id=PlatformAuth-Proxy"));
        assertNull(form.authorization());
        ok(
                raw(
                        "POST",
                        "/api/core/v1/auth/refresh",
                        write(object("refreshToken", "refresh-test")),
                        null),
                200);
        ok(
                raw(
                        "POST",
                        "/api/core/v1/auth/logout",
                        write(object("refreshToken", "refresh-test")),
                        null),
                204);
        ok(raw("POST", "/api/core/v1/auth/logout", "{}", null), 204);
    }

    @Test
    void validatesJsonAndBodyLimit() throws Exception {
        ok(raw("POST", "/api/core/v1/auth/login", "{bad", null), 400);
        ok(raw("POST", "/api/core/v1/auth/login", "{}", null), 400);
        ok(raw("POST", "/api/core/v1/auth/refresh", "{}", null), 400);
        ok(
                raw(
                        "POST",
                        "/api/core/v1/auth/login",
                        "{\"username\":\"" + "x".repeat(1024 * 1024) + "\"}",
                        null),
                413);
        ok(raw("POST", "/api/core/v1/auth/login", "null", null), 400);
    }

    @Test
    void unknownRoutePreserves404Contract() throws Exception {
        assertEquals("Маршрут не найден", text(ok(call("GET", "/unknown", null), 404), "message"));
    }

    @Test
    void searchFiltersAndMapsDocuments() throws Exception {
        JsonNode result =
                ok(
                        call(
                                "POST",
                                "/api/core/v1/documents/PDS_CONTRACT/search",
                                object(
                                        "query",
                                        "pds",
                                        "dateFrom",
                                        "01.09.2026",
                                        "status",
                                        "В работе")),
                        200);
        assertEquals(1, result.path("total").asInt());
        assertEquals("doc-1", text(result.path("items").get(0), "id"));
        assertEquals(
                0,
                ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT/search", object("dateTo", "2026-08-31")), 200)
                        .path("total")
                        .asInt());
        assertEquals(
                0,
                ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT/search", object("offset", 5)), 200)
                        .path("items")
                        .size());
        assertEquals(1, ok(call("GET", "/api/core/v1/document-types", null), 200).path("total").asInt());
        assertTrue(
                platform.calls.stream()
                        .filter(call -> call.path().equals("/graphql"))
                        .allMatch(call -> ("Bearer " + token).equals(call.authorization())));
    }

    @Test
    void documentDetailsUsePlatformActionsAndTaskHeaders() throws Exception {
        JsonNode result = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertEquals(3, result.path("workflow").path("availableActions").size());
        assertEquals("APPROVED", text(result.path("workflow").path("availableActions").get(0), "code"));
        assertEquals("operator", text(result.path("workflow").path("executor"), "login"));
        var taskCall =
                platform.calls.stream()
                        .filter(call -> call.path().endsWith("tasks:search"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("operator", taskCall.username());
        assertEquals("document_operator,approver", taskCall.roles());
        assertTrue(
                platform.calls.stream()
                        .anyMatch(
                                call ->
                                        call.path().endsWith("tasks:search")
                                                && call.query().contains("scope=EXECUTOR")));
        assertTrue(
                platform.calls.stream()
                        .anyMatch(
                                call ->
                                        call.path().endsWith("tasks:search")
                                                && call.query().contains("scope=MANAGER")));
        assertEquals("doc-1", text(taskCall.json().path("attributes").path("documentId"), "value"));
    }

    @Test
    void terminalDocumentHasNoActionsAndNoTaskCalls() throws Exception {
        platform.document.put("approvalStatus", "APPROVED");
        JsonNode result = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertTrue(result.path("workflow").path("availableActions").isEmpty());
        assertTrue(result.path("workflow").path("executor").isNull());
        assertFalse(platform.calls.stream().anyMatch(call -> call.path().endsWith("tasks:search")));
    }

    @Test
    void queuedDocumentHasExpectedExecutor() throws Exception {
        platform.document.put("approvalStatus", "ON_APPROVAL");
        platform.task.put("status", "NEW").putNull("assignee").put("executorRole", "approver");
        JsonNode result = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertEquals("approver", text(result.path("workflow").path("executor"), "role"));
        assertEquals("NEW", text(result.path("workflow").path("executor"), "taskStatus"));
    }

    @Test
    void createsOnlyThroughConfiguredApplicationProcess() throws Exception {
        JsonNode created = ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT", validDocument()), 201);
        assertEquals(platform.processCreatedId, text(created, "id"));
        assertEquals("FROM-PLATFORM", text(created.path("attributes"), "contractNumber"));
        assertEquals("process-1", text(created, "processInstanceId"));
        var start =
                platform.calls.stream()
                        .filter(call -> call.path().contains("/processes/"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "/bpmx/tenant-test/v7/apps/app-test/processes/Process_test:start", start.path());
        assertEquals("includeVariables=true", start.query());
        assertEquals("operator", text(start.json().path("payload"), "createdBy"));
        assertEquals(platform.processCreatedId, text(start.json(), "businessKey"));
        assertFalse(
                platform.calls.stream()
                        .filter(call -> call.path().equals("/graphql"))
                        .anyMatch(call -> text(call.json(), "query").startsWith("mutation")
                            && !text(call.json(), "query").startsWith("mutation commitDocument")
                            && !text(call.json(), "query").startsWith("mutation initializeDocumentVersion")));
    }

    @Test
    void rejectsMissingProcessAndPlatformIncident() throws Exception {
        platform.noProcess = true;
        ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT", validDocument()), 400);
        assertFalse(platform.calls.stream().anyMatch(call -> call.path().contains("/processes/")));
        platform.noProcess = false;
        platform.processIncident = true;
        assertTrue(
                text(ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT", validDocument()), 502), "message")
                        .contains("упал на create"));
    }

    @Test
    void updatesUsingModelIdAndSupportsDocumentFields() throws Exception {
        JsonNode result =
                ok(
                        call(
                                "PATCH",
                                "/api/core/v1/documents/PDS_CONTRACT/doc-1",
                                object(
                                        "attributes",
                                        object(
                                                "contractDate", "2026-09-02",
                                                "contractNumber", "CHANGED",
                                                "snils", "123-456-789 00"))),
                        200);
        assertEquals("CHANGED", text(result.path("attributes"), "contractNumber"));
        var mutation =
                platform.calls.stream()
                        .filter(
                                call ->
                                        call.path().equals("/graphql")
                                                && text(call.json(), "query")
                                                        .startsWith("mutation commitDocument"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("model-1", text(mutation.json().path("variables").path("document"), "id"));
        assertFalse(mutation.json().path("variables").path("document").has("approvalStatus"));
    }

    @Test
    void validationDoesNotCallPlatform() throws Exception {
        ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT", object()), 400);
        ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT", object("attributes", object("contractDate", "2026-09-07", "contractNumber", "NEW-1", "snils", "bad"))), 400);
        ok(
                call(
                        "POST",
                        "/api/core/v1/documents/PDS_CONTRACT",
                        object("attributes", object("contractDate", "2026-09-07", "contractNumber", "x".repeat(65), "snils", "123-456-789 00"))),
                400);
        ok(
                call(
                        "POST",
                        "/api/core/v1/documents/PDS_CONTRACT",
                        object("attributes", object("contractDate", "07.09.2026", "contractNumber", "NEW-1", "snils", "123-456-789 00"))),
                400);
        assertFalse(platform.calls.stream().anyMatch(call -> call.path().equals("/graphql")));
    }

    @Test
    void mapsUpstreamErrorsWithoutSuccessfulFallback() throws Exception {
        platform.graphqlStatus = 403;
        ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 403);
        platform.graphqlStatus = 500;
        ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 502);
        platform.graphqlStatus = 0;
        platform.graphqlErrors = true;
        assertEquals(
                "DataSpace GraphQL: Ошибка GraphQL",
                text(ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 502), "message"));
    }

    @Test
    void attachmentLifecyclePreservesVersionsAndDamProtocol() throws Exception {
        JsonNode uploaded =
                ok(
                                call(
                                        "POST",
                                        "/api/core/v1/documents/PDS_CONTRACT/doc-1/attachments",
                                        upload("документ.txt", "первая версия")),
                                201)
                        .get(0);
        String first = text(uploaded, "id");
        assertEquals(1, uploaded.path("version").asInt());
        assertFalse(uploaded.has("storageReference"));
        var dam =
                platform.calls.stream()
                        .filter(call -> call.path().endsWith("/upload/files/"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(dam.body().contains("name=\"size\""));
        assertTrue(dam.body().contains("documents/doc-1/uploads/" + first + "/"));
        assertTrue(dam.body().contains("документ.txt"));
        assertTrue(dam.body().contains("первая версия"));
        assertEquals("Bearer " + token, dam.authorization());
        JsonNode replaced =
                ok(
                        call(
                                "PUT",
                                "/api/core/v1/attachments/" + first,
                                upload("новый.txt", "вторая версия")),
                        200);
        assertEquals(2, replaced.path("version").asInt());
        assertEquals(first, text(replaced, "logicalAttachmentId"));
        String next = text(replaced, "id");
        JsonNode versions = ok(call("GET", "/api/core/v1/attachments/" + next + "/versions", null), 200);
        assertEquals(first, text(versions.get(0), "id"));
        assertFalse(versions.get(0).path("current").asBoolean());
        JsonNode current = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1/attachments", null), 200);
        assertEquals(1, current.size());
        assertEquals(next, text(current.get(0), "id"));
        var download = call("GET", "/api/core/v1/attachments/" + next, null);
        assertEquals(200, download.statusCode());
        assertEquals("содержимое", download.body());
        assertTrue(
                download.headers()
                        .firstValue("Content-Disposition")
                        .orElseThrow()
                        .contains("filename*=UTF-8''"));
        assertTrue(
                ok(call("DELETE", "/api/core/v1/attachments/" + next, null), 200)
                        .path("deleted")
                        .asBoolean());
        assertTrue(platform.attachments.values().stream().noneMatch(item -> item.path("current").asBoolean()));
    }

    @Test
    void attachmentAndDocumentNotFoundAre404() throws Exception {
        ok(call("GET", "/api/core/v1/attachments/missing", null), 404);
        ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/missing", null), 404);
        ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT/doc-1/attachments", object()), 400);
    }

    @Test
    void queuesSummaryAndGenericSearchKeepTaskFields() throws Exception {
        JsonNode mine = ok(call("POST", "/api/core/v1/tasks/search", object("queue", "MY")), 200);
        assertEquals(1, mine.path("total").asInt());
        assertTrue(mine.path("items").get(0).has("completions"));
        assertEquals(3, mine.path("items").get(0).path("availableActions").size());
        JsonNode summary = ok(call("GET", "/api/core/v1/tasks/summary", null), 200);
        assertEquals(1, summary.path("my").asInt());
        assertEquals(0, summary.path("available").asInt());
        JsonNode generic = ok(call("POST", "/api/core/v1/tasks/search", object()), 200);
        assertEquals(1, generic.path("total").asInt());
        platform.task.put("status", "NEW").putNull("assignee");
        JsonNode available =
                ok(call("POST", "/api/core/v1/tasks/search", object("queue", "AVAILABLE")), 200);
        assertEquals(1, available.path("total").asInt());
    }

    @Test
    void hiddenDocumentsDoNotLeakThroughSharedCache() throws Exception {
        ok(call("GET", "/api/core/v1/tasks/summary", null), 200);
        platform.document.put("documentId", "different-doc");
        token = platform.token("other");
        JsonNode result = ok(call("GET", "/api/core/v1/tasks/summary", null), 200);
        assertEquals(0, result.path("my").asInt());
        assertEquals(0, result.path("available").asInt());
    }

    @Test
    void startAndCompleteSendSystemCommands() throws Exception {
        platform.task.put("status", "NEW");
        JsonNode started = ok(call("POST", "/api/core/v1/tasks/task-1/start", object()), 200);
        assertEquals("task-1", text(started.path("operationResults").get(0), "userTaskId"));
        ok(call("POST", "/api/core/v1/tasks/task-1/action", object("actionCode", "APPROVED")), 200);
        var complete =
                platform.calls.stream()
                        .filter(call -> call.path().endsWith("usertasks:complete"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("operator", text(complete.json(), "clientLogin"));
        assertEquals("APPROVED", text(complete.json().path("parameters"), "approvalStatus"));
    }

    @Test
    void taskActionAndDocumentApprovalUseOnlyPlatformOptionParameters() throws Exception {
        JsonNode result =
                ok(
                        call(
                                "POST",
                                "/api/core/v1/tasks/task-1/action",
                                object("actionCode", "APPROVED")),
                        200);
        JsonNode document = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertEquals("APPROVED", text(document, "status"));
        platform.reset();
        app.getBean(TaskCache.class).invalidate();
        result =
                ok(
                        call(
                                "POST",
                                "/api/core/v1/tasks/task-1/action",
                                object("parameters", object("approvalStatus", "REJECTED"))),
                        200);
        document = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertEquals("REJECTED", text(document, "status"));
        assertFalse(
                platform.calls.stream()
                        .filter(call -> call.path().equals("/graphql"))
                        .anyMatch(call -> text(call.json(), "query").startsWith("mutation")
                            && !text(call.json(), "query").startsWith("mutation commitDocument")
                            && !text(call.json(), "query").startsWith("mutation initializeDocumentVersion")));
    }

    @Test
    void rejectsUnknownActionsAndFailedOperations() throws Exception {
        ok(call("POST", "/api/core/v1/tasks/task-1/action", object("actionCode", "ILLEGAL")), 400);
        assertFalse(
                platform.calls.stream()
                        .anyMatch(call -> call.path().endsWith("usertasks:complete")));
        platform.failedOperation = true;
        ok(call("POST", "/api/core/v1/tasks/task-1/start", object()), 502);
        ok(call("POST", "/api/core/v1/tasks/task-1/action", object("actionCode", "APPROVED")), 502);
        assertEquals("IN_WORK", text(platform.document, "approvalStatus"));
    }

    @Test
    void taskDetailFallsBackToBpmu() throws Exception {
        platform.task.remove("formType");
        platform.task.remove("completions");
        platform.fallbackDetails = true;
        JsonNode result = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertTrue(result.path("workflow").path("availableActions").isEmpty());
        assertTrue(
                platform.calls.stream()
                        .anyMatch(call -> call.path().contains("/system/v6/usertasks/")));
        assertTrue(
                platform.calls.stream()
                        .anyMatch(call -> call.path().contains("/system/v1/user-tasks/")));
    }

    @Test
    void takingInWorkStartsFollowUpTaskAndPassesAssignee() throws Exception {
        platform.task.put("status", "NEW").putNull("assignee");
        platform.returnFollowUp = true;
        JsonNode result =
                ok(
                        call("POST", "/api/core/v1/tasks/task-1/action", object("actionCode", "IN_WORK")),
                        200);
        var complete =
                platform.calls.stream()
                        .filter(call -> call.path().endsWith("usertasks:complete"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("operator", text(complete.json().path("parameters"), "assignee"));
        assertEquals(
                2,
                platform.calls.stream()
                        .filter(call -> call.path().endsWith("usertasks:start"))
                        .count());
    }

    @Test
    void chunkedBodiesAreAlsoLimited() throws Exception {
        byte[] bytes =
                ("{\"username\":\"" + "x".repeat(1024 * 1024) + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
        var request =
                HttpRequest.newBuilder(URI.create(base + "/api/core/v1/auth/login"))
                        .POST(
                                HttpRequest.BodyPublishers.ofInputStream(
                                        () -> new java.io.ByteArrayInputStream(bytes)))
                        .header("Content-Type", "application/json")
                        .build();
        assertEquals(413, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void emptyRegistryCreatesDocumentAndCompletesWorkflowWithAttachmentVersions() throws Exception {
        platform.document = null;
        platform.noTask = true;
        assertEquals(0, number(ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT/search", object()), 200), "total", -1));
        JsonNode created = ok(call("POST", "/api/core/v1/documents/PDS_CONTRACT", validDocument()), 201);
        String id = text(created, "id");
        String path = "/api/core/v1/documents/PDS_CONTRACT/" + id;
        assertEquals("CREATED", text(ok(call("GET", path, null), 200), "status"));
        JsonNode attachment = ok(call("POST", path + "/attachments", upload("file.txt", "v1")), 201).get(0);
        platform.document.put("approvalStatus", "IN_WORK"); platform.noTask = false;
        platform.task.put("status", "STARTED").put("assignee", "operator");
        JsonNode replacement = ok(call("PUT", "/api/core/v1/attachments/" + text(attachment, "id"), upload("file.txt", "v2")), 200);
        assertEquals(2, number(replacement, "version", 0));
        for (String status : List.of("IN_WORK", "ON_APPROVAL", "NEEDS_REVISION", "ON_APPROVAL", "APPROVED")) {
            platform.noTask = false;
            platform.task.put("status", "NEW");
            platform.task.set("completions", object("options", List.of(
                    object("label", status, "result", object("approvalStatus", status)))));
            JsonNode updated = ok(call("POST", "/api/core/v1/tasks/" + id + "/action", object("approvalStatus", status)), 200);
            assertEquals(status, text(updated, "approvalStatus"));
            assertEquals(id, text(updated, "id"));
            assertEquals(1, updated.path("attachments").size());
        }
        assertEquals(text(attachment, "id"), text(ok(call("GET", "/api/core/v1/attachments/" + text(replacement, "id") + "/versions", null), 200).get(0), "id"));
    }

    @Test
    void stableReactCardCanCompleteByDocumentId() throws Exception {
        JsonNode card = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertEquals(card.path("workflow").path("availableActions"), card.path("availableActions"));
        assertEquals("operator", text(card.path("executor"), "login"));
        JsonNode updated = ok(call("POST", "/api/core/v1/tasks/doc-1/action",
                object("approvalStatus", "APPROVED")), 200);
        assertEquals("doc-1", text(updated, "id"));
        assertEquals("PDS_CONTRACT", text(updated, "documentTypeId"));
        assertEquals("PDS-001", text(updated, "contractNumber"));
        assertEquals("APPROVED", text(updated, "approvalStatus"));
        assertTrue(updated.path("availableActions").isEmpty());
        assertTrue(updated.path("executor").isNull());
        assertTrue(platform.calls.stream().anyMatch(c -> c.path().endsWith("usertasks:complete")
                && c.json().path("userTaskIds").get(0).asString().equals("task-1")));
    }

    @Test
    void legacyRoutesAreAbsent() throws Exception {
        for (String path : List.of("/api/documents", "/api/tasks", "/api/auth/me", "/api/pds-contracts"))
            ok(call("GET", path, null), 404);
    }

    @Test
    void documentContractWorksWithoutExternalConfiguration() throws Exception {
        JsonNode catalog = ok(call("GET", "/api/core/v1/document-types", null), 200);
        assertEquals(1, number(catalog, "total", 0));
        assertEquals("PDS_CONTRACT", text(catalog.path("items").get(0), "code"));
        JsonNode document = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertTrue(document.path("attributes").has("contractNumber"));
        ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1/versions", null), 200);
        ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1/versions/1", null), 200);
        JsonNode updated =
                ok(
                        call(
                                "PATCH",
                                "/api/core/v1/documents/PDS_CONTRACT/doc-1",
                                object("attributes", object("contractNumber", "ПДС-42"))),
                        200);
        assertEquals("ПДС-42", text(updated.path("attributes"), "contractNumber"));
        ok(
                call(
                        "PATCH",
                        "/api/core/v1/documents/PDS_CONTRACT/doc-1",
                        object("attributes", object("snils", null))),
                400);
        ok(
                call(
                        "POST",
                        "/api/core/v1/documents/PDS_CONTRACT",
                        object("attributes", object("unknown", true))),
                400);
        ok(call("GET", "/api/core/v1/documents/UNKNOWN/1", null), 400);
        ok(call("POST", "/api/core/v1/documents/UNKNOWN", object("attributes", object())), 400);
        JsonNode health = ok(call("GET", "/api/core/v1/health", null), 200);
        assertFalse(health.has("profile"));
    }

    @Test
    void modernTakeInWorkUpdatesCardAndStartsOperatorTask() throws Exception {
        platform.document.put("approvalStatus", "CREATED");
        platform.task.put("status", "NEW").putNull("assignee");
        platform.returnFollowUp = true;

        ok(
                call(
                        "POST",
                        "/api/core/v1/tasks/task-1/action",
                        object("actionCode", "IN_WORK")),
                200);
        JsonNode document = ok(call("GET", "/api/core/v1/documents/PDS_CONTRACT/doc-1", null), 200);
        assertEquals("IN_WORK", text(document, "status"));
        assertEquals("operator", text(document.path("workflow").path("executor"), "login"));
        assertEquals("document_operator", text(document.path("workflow").path("executor"), "role"));
    }

    private JsonNode versionPatch(JsonNode card, String number) {
        return object("attributes", object("contractNumber", number), "expectedVersion", number(card, "version", 1),
                "changeToken", text(card, "changeToken"), "requestId", UUID.randomUUID().toString());
    }

    @Test
    void documentVersionsFreezeAttachmentsAndPreserveTheRunningProcess() throws Exception {
        String path = "/api/core/v1/documents/PDS_CONTRACT/doc-1";
        JsonNode originalTask = platform.task.deepCopy();
        JsonNode a1 = ok(call("POST", path + "/attachments", upload("file.txt", "one")), 201).get(0);
        JsonNode a2 = ok(call("PUT", "/api/core/v1/attachments/" + text(a1, "id"), upload("file.txt", "two")), 200);
        JsonNode a3 = ok(call("PUT", "/api/core/v1/attachments/" + text(a2, "id"), upload("file.txt", "three")), 200);
        JsonNode first = ok(call("GET", path, null), 200);
        JsonNode patch = versionPatch(first, "VERSION-2");
        JsonNode second = ok(call("PATCH", path, patch), 200);
        assertEquals(2, number(second, "version", 0));
        assertEquals(second, ok(call("PATCH", path, patch), 200), "Retry returns the committed response");
        JsonNode a4 = ok(call("PUT", "/api/core/v1/attachments/" + text(a3, "id"), upload("file.txt", "four")), 200);
        JsonNode v1 = ok(call("GET", path + "/versions/1", null), 200);
        JsonNode v2 = ok(call("GET", path + "/versions/2", null), 200);
        assertEquals("PDS-001", text(v1.path("attributes"), "contractNumber"));
        assertEquals("VERSION-2", text(v2.path("attributes"), "contractNumber"));
        assertEquals(text(a3, "id"), text(v1.path("attachments").get(0), "id"));
        assertEquals(text(a4, "id"), text(v2.path("attachments").get(0), "id"));
        assertEquals(2, ok(call("GET", "/api/core/v1/attachments/" + text(a3, "id") + "/versions", null), 200).size());
        assertEquals(originalTask, platform.task);
        assertEquals("model-1", text(platform.document, "id"));
        assertFalse(platform.calls.stream().anyMatch(c -> c.method().equals("POST") && (c.path().endsWith(":start") || c.path().endsWith("usertasks:complete"))));
        ok(call("DELETE", "/api/core/v1/attachments/" + text(a4, "id"), null), 200);
        assertTrue(ok(call("GET", path, null), 200).path("attachments").isEmpty());
        assertEquals(text(a3, "id"), text(ok(call("GET", path + "/versions/1", null), 200).path("attachments").get(0), "id"));
        assertEquals(200, call("GET", "/api/core/v1/attachments/" + text(a3, "id"), null).statusCode());
        assertEquals(4, platform.attachments.size());
        ok(call("POST", "/api/core/v1/tasks/doc-1/action", object("approvalStatus", "APPROVED")), 200);
        assertEquals("APPROVED", text(platform.document, "approvalStatus"));
        assertEquals(2, number(platform.document, "version", 0));
    }

    @Test
    void versionCommandsHandleNoOpConflictAndReusedRequestId() throws Exception {
        String path = "/api/core/v1/documents/PDS_CONTRACT/doc-1";
        JsonNode card = ok(call("GET", path, null), 200);
        JsonNode unchanged = ok(call("PATCH", path, versionPatch(card, " PDS-001 ")), 200);
        assertEquals(1, number(unchanged, "version", 0));
        JsonNode patch = versionPatch(unchanged, "NEXT");
        ok(call("PATCH", path, patch), 200);
        ok(call("PATCH", path, versionPatch(unchanged, "STALE")), 409);
        var reused = copy(patch); reused.set("attributes", object("contractNumber", "DIFFERENT"));
        ok(call("PATCH", path, reused), 409);
        assertEquals(2, platform.documentVersions.size());
        ok(raw("PATCH", path, write(object("attributes", object("contractNumber", "NO-KEY"))), token), 400);
    }

    @Test
    void failedAndLostCommitResponsesDoNotLoseOrDuplicateVersions() throws Exception {
        String path = "/api/core/v1/documents/PDS_CONTRACT/doc-1";
        JsonNode card = ok(call("GET", path, null), 200);
        JsonNode patch = versionPatch(card, "AFTER-RETRY");
        platform.failVersionCommit = true;
        ok(call("PATCH", path, patch), 502);
        assertEquals(1, platform.documentVersions.size());
        assertEquals("PDS-001", text(platform.document, "contractNumber"));
        platform.failVersionCommit = false; platform.loseVersionResponse = true;
        JsonNode saved = ok(call("PATCH", path, patch), 200);
        assertEquals(saved, ok(call("PATCH", path, patch), 200));
        assertEquals(2, platform.documentVersions.size());
    }

    @Test
    void concurrentEditorsCannotBothCommitTheSameBaseVersion() throws Exception {
        String path = "/api/core/v1/documents/PDS_CONTRACT/doc-1";
        JsonNode card = ok(call("GET", path, null), 200);
        var requests = List.of(versionPatch(card, "FIRST"), versionPatch(card, "SECOND"));
        var futures = requests.stream().map(body -> http.sendAsync(HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
            .method("PATCH", HttpRequest.BodyPublishers.ofString(write(body))).build(), HttpResponse.BodyHandlers.ofString())).toList();
        var statuses = futures.stream().map(f -> f.join().statusCode()).sorted().toList();
        assertEquals(List.of(200, 409), statuses);
        assertEquals(2, platform.documentVersions.size());
    }

    @Test
    void attachmentRetryIsIdempotentAndInvalidatesAnOpenEditor() throws Exception {
        String path = "/api/core/v1/documents/PDS_CONTRACT/doc-1";
        JsonNode card = ok(call("GET", path, null), 200);
        JsonNode upload = upload("file.txt", "same bytes");
        JsonNode uploaded = ok(call("POST", path + "/attachments", upload), 201);
        assertEquals(uploaded, ok(call("POST", path + "/attachments", upload), 201));
        assertEquals(1, platform.attachments.size());
        ok(call("PATCH", path, versionPatch(card, "STALE-AFTER-FILE")), 409);
        assertEquals(1, number(platform.document, "version", 0));
        platform.document.put("approvalStatus", "APPROVED");
        JsonNode current = ok(call("GET", path, null), 200);
        ok(call("PATCH", path, versionPatch(current, "FORBIDDEN")), 409);
        assertEquals(1, platform.documentVersions.size());
    }

    private ru.corelia.transport.ServiceClient peer(String service) throws Exception {
        var env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty(
                "corelia.internal.key-store",
                root.resolve(".local/certs/" + service + "/identity.p12").toString());
        env.setProperty(
                "corelia.internal.trust-store",
                root.resolve(".local/certs/" + service + "/trust.p12").toString());
        env.setProperty(
                "corelia.tls.password",
                java.nio.file.Files.readString(root.resolve(".local/secrets/corelia.tls.password"))
                        .trim());
        addresses.forEach((name, address) -> env.setProperty("corelia.services." + name, address));
        return new ru.corelia.transport.ServiceClient(new ru.corelia.config.CoreliaConfig(env));
    }

    @Test
    void internalApiRequiresBothAuthorizedCertificateAndUserToken() throws Exception {
        var gateway = peer("corelia-gateway");
        assertEquals(
                "ok",
                text(gateway.call("document", "/internal/v1/health", "GET", null, null), "status"));
        assertEquals(
                401,
                assertThrows(
                                ru.corelia.http.ApiException.class,
                                () ->
                                        gateway.call(
                                                "document",
                                                "/internal/v1/document-types",
                                                "GET",
                                                null,
                                                null))
                        .status());
        var user = app.getBean(ru.corelia.auth.JwtVerifier.class).authenticate("Bearer " + token);
        assertEquals(
                403,
                assertThrows(
                                ru.corelia.http.ApiException.class,
                                () ->
                                        peer("corelia-auth")
                                                .call(
                                                        "document",
                                                        "/internal/v1/document-types",
                                                        "GET",
                                                        null,
                                                        user))
                        .status());
        assertEquals(
                403,
                assertThrows(
                                ru.corelia.http.ApiException.class,
                                () ->
                                        peer("corelia-document-service")
                                                .call(
                                                        "workflow",
                                                        "/internal/v1/tasks/search",
                                                        "POST",
                                                        object(),
                                                        user))
                        .status());
        assertEquals(
                403,
                assertThrows(
                                ru.corelia.http.ApiException.class,
                                () ->
                                        peer("corelia-attachment-service")
                                                .call(
                                                        "document",
                                                        "/internal/v1/documents/PDS_CONTRACT",
                                                        "POST",
                                                        object(),
                                                        user))
                        .status());
        ok(call("GET", "/internal/v1/health", null), 404);
    }

    @Test
    void internalTlsRejectsConnectionWithoutClientCertificate() throws Exception {
        var store = java.security.KeyStore.getInstance("PKCS12");
        try (var input =
                java.nio.file.Files.newInputStream(
                        root.resolve(".local/certs/corelia-gateway/trust.p12"))) {
            store.load(
                    input,
                    java.nio.file.Files.readString(
                                    root.resolve(".local/secrets/corelia.tls.password"))
                            .trim()
                            .toCharArray());
        }
        var trust =
                javax.net.ssl.TrustManagerFactory.getInstance(
                        javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(null, trust.getTrustManagers(), null);
        try (var client = HttpClient.newBuilder().sslContext(ssl).build()) {
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            client.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            addresses.get("document")
                                                                    + "/internal/v1/health"))
                                            .build(),
                                    HttpResponse.BodyHandlers.discarding()));
        }
    }

    @Test
    void platformRejectsUnregisteredOperationName() throws Exception {
        var response =
                http.send(
                        HttpRequest.newBuilder(URI.create(platform.base() + "/graphql"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                write(
                                                        object(
                                                                "query",
                                                                "query searchDocumentType {"
                                                                    + " searchDocumentType { elems"
                                                                    + " { id name } count } }"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(
                text(parse(response.body()).path("errors").get(0), "message")
                        .contains("is not in the list of allowed requests"));
    }

    @Test
    void platformRejectsChangedBodyOfRegisteredOperation() throws Exception {
        var response =
                http.send(
                        HttpRequest.newBuilder(URI.create(platform.base() + "/graphql"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                write(
                                                        object(
                                                                "query",
                                                                "query searchPdsContract {"
                                                                    + " searchPdsContract { elems {"
                                                                    + " id } count } }"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(
                text(parse(response.body()).path("errors").get(0), "message")
                        .contains("body does not match"));
    }
}

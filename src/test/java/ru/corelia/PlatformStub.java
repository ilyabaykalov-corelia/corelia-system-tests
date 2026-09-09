package ru.corelia;

import static ru.corelia.support.Json.*;

import com.sun.net.httpserver.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Локальная платформа для проверки реального HTTP-обмена без доступа к стенду. */
final class PlatformStub implements AutoCloseable {
    record Call(
            String method,
            String path,
            String query,
            String authorization,
            String username,
            String roles,
            byte[] bytes) {
        JsonNode json() {
            return bytes.length == 0 ? object() : MAPPER.readTree(bytes);
        }

        String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final Map<String, String> allowedQueries = new java.util.HashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final KeyPair keyPair;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Map<String, ObjectNode> attachments = new ConcurrentHashMap<>();
    final AtomicInteger jwksCalls = new AtomicInteger();
    volatile String kid = "key-1";
    volatile ObjectNode document;
    volatile ObjectNode task;
    volatile int graphqlStatus;
    volatile boolean graphqlErrors;
    volatile boolean processIncident;
    volatile boolean noProcess;
    volatile boolean noTask;
    volatile boolean fallbackDetails;
    volatile boolean failedOperation;
    volatile boolean returnFollowUp;
    volatile String processCreatedId;
    volatile byte[] download = "содержимое".getBytes(StandardCharsets.UTF_8);

    PlatformStub() throws Exception {
        try (var input =
                PlatformStub.class.getResourceAsStream("/platform-v/allowed-requests.json")) {
            for (JsonNode entry : list(MAPPER.readTree(input)))
                allowedQueries.put(text(entry, "name"), text(entry, "body"));
        }
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        reset();
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        calls.clear();
        attachments.clear();
        graphqlStatus = 0;
        graphqlErrors = false;
        processIncident = false;
        noProcess = false;
        noTask = false;
        fallbackDetails = false;
        failedOperation = false;
        returnFollowUp = false;
        processCreatedId = null;
        document =
                object(
                        "id",
                        "model-1",
                        "documentId",
                        "doc-1",
                        "documentType",
                        object("id", "PDS_CONTRACT", "name", "Договор ПДС"),
                        "contractDate",
                        "2026-09-01",
                        "contractNumber",
                        "PDS-001",
                        "snils",
                        "123-456-789 00",
                        "approvalStatus",
                        "IN_WORK",
                        "createdBy",
                        "operator",
                        "createdAt",
                        "2026-09-01T10:00:00Z");
        task =
                object(
                        "id",
                        "task-1",
                        "title",
                        "Работа оператора с договором",
                        "status",
                        "STARTED",
                        "assignee",
                        "operator",
                        "executorRole",
                        "document_operator",
                        "attributes",
                        object("documentId", object("value", "doc-1"), "contractNumber", "PDS-001"),
                        "formType",
                        "COMPLETIONS",
                        "completions",
                        object(
                                "options",
                                List.of(
                                        object(
                                                "label",
                                                "Согласовать",
                                                "result",
                                                object("approvalStatus", "APPROVED")),
                                        object(
                                                "label",
                                                "Отказать",
                                                "result",
                                                object("approvalStatus", "REJECTED")),
                                        object(
                                                "label",
                                                "Взять в работу",
                                                "result",
                                                object("approvalStatus", "IN_WORK")))));
    }

    ObjectNode claims(String login) {
        return object(
                "iss",
                base() + "/realm",
                "sub",
                "user-" + login,
                "preferred_username",
                login,
                "name",
                "Иван Иванов",
                "email",
                login + "@test.local",
                "exp",
                Instant.now().getEpochSecond() + 600,
                "realm_access",
                object("roles", List.of("document_operator")),
                "resource_access",
                object("bpm", object("roles", List.of("approver", "document_operator"))));
    }

    String token(String login) {
        return token(claims(login), kid, "RS256");
    }

    String token(JsonNode claims, String kid, String alg) {
        try {
            var encoder = Base64.getUrlEncoder().withoutPadding();
            String input =
                    encoder.encodeToString(
                                    write(object("alg", alg, "kid", kid))
                                            .getBytes(StandardCharsets.UTF_8))
                            + "."
                            + encoder.encodeToString(
                                    write(claims).getBytes(StandardCharsets.UTF_8));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + encoder.encodeToString(signature.sign());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        Call call =
                new Call(
                        exchange.getRequestMethod(),
                        path,
                        exchange.getRequestURI().getRawQuery(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("X-Username"),
                        exchange.getRequestHeaders().getFirst("X-Roles"),
                        bytes);
        calls.add(call);
        try {
            if (path.endsWith("/certs")) {
                jwksCalls.incrementAndGet();
                var key = (RSAPublicKey) keyPair.getPublic();
                json(
                        exchange,
                        200,
                        object(
                                "keys",
                                List.of(
                                        object(
                                                "kid",
                                                kid,
                                                "kty",
                                                "RSA",
                                                "n",
                                                unsigned(key.getModulus().toByteArray()),
                                                "e",
                                                unsigned(key.getPublicExponent().toByteArray())))));
            } else if (path.endsWith("/token")) {
                json(
                        exchange,
                        200,
                        object(
                                "access_token",
                                token("operator"),
                                "refresh_token",
                                "refresh-test",
                                "expires_in",
                                300,
                                "refresh_expires_in",
                                600,
                                "token_type",
                                "Bearer",
                                "scope",
                                "openid roles"));
            } else if (path.endsWith("/logout")) {
                exchange.sendResponseHeaders(204, -1);
            } else if (path.equals("/graphql")) {
                graphql(exchange, call.json());
            } else if (path.contains("/processes/")) {
                processCreatedId = text(call.json().path("payload"), "documentId");
                json(
                        exchange,
                        200,
                        object(
                                "id",
                                "process-1",
                                "isIncident",
                                processIncident,
                                "globalVariables",
                                object(
                                        "id",
                                        object("value", "model-new"),
                                        "documentId",
                                        object("value", processCreatedId),
                                        "contractNumber",
                                        object("value", "FROM-PLATFORM")),
                                "currentActivities",
                                processIncident
                                        ? List.of(
                                                object(
                                                        "isIncident",
                                                        true,
                                                        "definitionId",
                                                        "create",
                                                        "error",
                                                        "Ошибка создания"))
                                        : List.of()));
            } else if (path.endsWith("tasks:search")) {
                String status = text(call.json().path("status"), "value");
                boolean visible =
                        !noTask && (status.isEmpty() || status.equals(text(task, "status")));
                json(
                        exchange,
                        200,
                        object(
                                "items",
                                visible ? List.of(task) : List.of(),
                                "count",
                                visible ? 1 : 0));
            } else if (path.endsWith("usertasks:start")) {
                task.put("status", "STARTED").put("assignee", text(call.json(), "clientLogin"));
                operation(exchange, text(call.json().path("userTaskIds").get(0)));
            } else if (path.endsWith("usertasks:complete")) {
                if (!failedOperation) {
                    document.put(
                            "approvalStatus",
                            text(call.json().path("parameters"), "approvalStatus"));
                    noTask = !returnFollowUp;
                    if (returnFollowUp) {
                        task =
                                copy(task)
                                        .put("id", "task-2")
                                        .put("status", "NEW")
                                        .putNull("assignee");
                    }
                }
                operation(exchange, text(call.json().path("userTaskIds").get(0)));
            } else if (path.contains("/usertasks/") || path.contains("/user-tasks/")) {
                if (noTask || fallbackDetails && path.contains("/usertasks/"))
                    json(exchange, 404, object("message", "Не найдено"));
                else json(exchange, 200, task);
            } else if (path.endsWith("/upload/files/")) {
                json(exchange, 200, object("uploaded", true));
            } else if (path.contains("/download/")) {
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, download.length);
                exchange.getResponseBody().write(download);
            } else if (path.equals("/redirect")) {
                exchange.getResponseHeaders().add("Location", base() + "/login");
                exchange.sendResponseHeaders(302, -1);
            } else if (path.equals("/forbidden")) {
                json(exchange, 403, object("message", "Нет доступа"));
            } else json(exchange, 404, object("message", "Маршрут имитации не найден"));
        } catch (RuntimeException error) {
            json(exchange, 500, object("message", "Ошибка имитации: " + error));
        } finally {
            exchange.close();
        }
    }

    private void operation(HttpExchange exchange, String taskId) throws IOException {
        json(
                exchange,
                200,
                object(
                        "operationResults",
                        List.of(
                                object(
                                        "userTaskId",
                                        taskId,
                                        "responseType",
                                        failedOperation ? "DENIED" : "SUCCESS"))));
    }

    private void graphql(HttpExchange exchange, JsonNode body) throws IOException {
        if (graphqlStatus != 0) {
            json(exchange, graphqlStatus, object("message", "Ошибка DataSpace"));
            return;
        }
        if (graphqlErrors) {
            json(exchange, 200, object("errors", List.of(object("message", "Ошибка GraphQL"))));
            return;
        }
        String query = text(body, "query");
        // Имитируем проверку имени и полного тела, которую выполняет DataSpace до исполнения.
        var operation =
                java.util.regex.Pattern.compile("^(?:query|mutation)\\s+([_A-Za-z][_0-9A-Za-z]*)")
                        .matcher(query);
        String name = operation.find() ? operation.group(1) : "";
        String registered = allowedQueries.get(name);
        if (registered == null || !registered.equals(query)) {
            json(
                    exchange,
                    200,
                    object(
                            "errors",
                            List.of(
                                    object(
                                            "message",
                                            registered == null
                                                    ? "Access denied. Request with name "
                                                            + name
                                                            + " is not in the list of allowed"
                                                            + " requests."
                                                    : "Access denied. Request body does not match"
                                                            + " registered request "
                                                            + name))));
            return;
        }
        JsonNode variables = body.path("variables");
        JsonNode result;
        if (query.startsWith("query searchPdsContract"))
            result = object("searchPdsContract", object("elems", List.of(document), "count", 1));
        else if (query.startsWith("query documentStates"))
            result =
                    object(
                            "getStatesPdsContract",
                            object(
                                    "elems",
                                    List.of(
                                            copy(document)
                                                    .put("sysHistNumber", 1)
                                                    .put("sysHistoryTime", "2026-09-01T10:00:00Z")),
                                    "count",
                                    1));
        else if (query.startsWith("query searchDocumentProcessSettings"))
            result =
                    object(
                            "searchDocumentProcessSettings",
                            object(
                                    "elems",
                                    noProcess
                                            ? List.of()
                                            : List.of(
                                                    object(
                                                            "id",
                                                            "settings-1",
                                                            "enabled",
                                                            true,
                                                            "documentType",
                                                            object("id", "PDS_CONTRACT"),
                                                            "processId",
                                                            "Process_test")),
                                    "count",
                                    noProcess ? 0 : 1));
        else if (query.startsWith("query refDocumentTypeListGet"))
            result =
                    object(
                            "searchDocumentType",
                            object(
                                    "elems",
                                    List.of(object("id", "PDS_CONTRACT", "name", "Договор ПДС")),
                                    "count",
                                    1));
        else if (query.startsWith("mutation updatePdsContract")) {
            variables
                    .path("input")
                    .properties()
                    .forEach(
                            entry -> {
                                if (!Set.of("id", "documentType").contains(entry.getKey()))
                                    document.set(entry.getKey(), entry.getValue());
                            });
            result =
                    object(
                            "packet",
                            object(
                                    "updatePdsContract",
                                    object(
                                            "id",
                                            "model-1",
                                            "approvalStatus",
                                            text(document, "approvalStatus"))));
        } else if (query.startsWith("query searchAttachment"))
            result =
                    object(
                            "searchAttachment",
                            object(
                                    "elems",
                                    new ArrayList<>(attachments.values()),
                                    "count",
                                    attachments.size()));
        else if (query.startsWith("query attachmentComposition"))
            result = object("searchAttachmentChange", object("elems", List.of(), "count", 0));
        else if (query.startsWith("mutation createAttachment")
                || query.startsWith("mutation replaceAttachmentVersion")) {
            String previous = text(variables, "currentAttachmentId");
            if (!previous.isEmpty()) attachments.get(previous).put("current", false);
            ObjectNode created = copy(variables.path("input"));
            String id = "model-" + text(created, "attachmentId");
            created.put("id", id);
            attachments.put(id, created);
            result = object("packet", object("createAttachment", created));
        } else if (query.startsWith("mutation deleteAttachment")) {
            attachments.remove(text(variables, "id"));
            result = object("packet", object("deleteAttachment", text(variables, "id")));
        } else throw new IllegalStateException("Неизвестный GraphQL-запрос: " + query);
        json(exchange, 200, object("data", result));
    }

    private static String unsigned(byte[] value) {
        if (value[0] == 0) value = Arrays.copyOfRange(value, 1, value.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}

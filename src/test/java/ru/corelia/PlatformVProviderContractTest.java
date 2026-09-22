package ru.corelia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.corelia.support.Json.object;
import static ru.corelia.support.Json.write;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import ru.corelia.auth.AuthContext;
import ru.corelia.cache.UserCache;
import ru.corelia.configuration.ConfigurationLoader;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.observability.CoreliaObservability;
import ru.corelia.observability.TraceContextPropagation;
import ru.corelia.platformv.BpmClient;
import ru.corelia.platformv.DataSpaceClient;
import ru.corelia.platformv.FileStorageClient;
import ru.corelia.platformv.PlatformAttachmentCatalog;
import ru.corelia.platformv.PlatformBinaryStorage;
import ru.corelia.platformv.PlatformDocumentStore;
import ru.corelia.platformv.PlatformDocumentTypeProvider;
import ru.corelia.platformv.PlatformDocumentVersionStore;
import ru.corelia.platformv.PlatformHttp;
import ru.corelia.platformv.PlatformTaskProvider;
import ru.corelia.platformv.PlatformVConfig;
import ru.corelia.platformv.PlatformVDocumentBindings;
import ru.corelia.platformv.PlatformVOperationCatalog;
import ru.corelia.platformv.PlatformVPermissionChecker;
import ru.corelia.platformv.PlatformWorkflowProvider;
import ru.corelia.http.ApiException;
import ru.corelia.provider.tck.ProviderContractTest;
import ru.corelia.provider.tck.ProviderFixture;
import ru.corelia.provider.model.DocumentMutation;
import ru.corelia.provider.model.DocumentSnapshot;
import ru.corelia.provider.model.DocumentVersion;
import ru.corelia.provider.model.WorkflowTask;
import ru.corelia.support.ParallelCalls;

/** Запускает reusable SPI TCK через реальные Platform V adapters и локальный HTTP stub. */
class PlatformVProviderContractTest extends ProviderContractTest {
    private static PlatformStub platform;
    private static Fixture fixture;

    @BeforeAll static void startPlatform() throws Exception { platform = new PlatformStub(); fixture = new Fixture(platform); }
    @AfterAll static void stopPlatform() { if (platform != null) platform.close(); }
    @Override protected ProviderFixture fixture() { platform.reset(); platform.download = "data".getBytes(java.nio.charset.StandardCharsets.UTF_8); return fixture; }

    @Test void initializesFirstVersionWhenDataSpaceChangeTokenIsNull() {
        platform.reset();
        var document = new DocumentSnapshot("doc-1", "PDS_CONTRACT", "DRAFT", 0,
                Map.of("contractNumber", object("value", "PDS-001")), "user", Instant.EPOCH, null);
        var initial = new DocumentVersion("version-0", "doc-1", 0, 1,
                Map.of("contractNumber", object("value", "PDS-001")), "DRAFT", Instant.EPOCH, "user", null, List.of());
        var first = new DocumentVersion("", "doc-1", 1, 1,
                Map.of("contractNumber", object("value", "PDS-001")), "DRAFT", Instant.EPOCH, "user", null, List.of());
        fixture.seed(document, initial);

        assertNull(fixture.documents().get("PDS_CONTRACT", "doc-1", fixture.allowedAuth()).changeToken());
        fixture.versions().commit(new DocumentMutation("doc-1", "PDS_CONTRACT", 0, null,
                Map.of("contractNumber", object("value", "PDS-001")), first, null, null, null,
                "initialize-null-token", "hash", object("version", 1)), fixture.allowedAuth());

        assertEquals(1, fixture.versions().state("PDS_CONTRACT", "doc-1", fixture.allowedAuth()).document().currentVersion());
        var request = platform.calls.stream().filter(call -> call.path().equals("/graphql"))
                .filter(call -> call.json().path("query").asString().startsWith("mutation initializeDocumentVersion")).findFirst().orElseThrow();
        assertTrue(request.json().path("variables").path("compare").path("changeToken").isNull());
    }

    @Test void rejectsExistingDocumentWithStaleChangeToken() {
        var error = assertThrows(ApiException.class, () -> fixture.versions().commit(new DocumentMutation("doc-1", "PDS_CONTRACT", 1, "stale-token",
                Map.of("contractNumber", object("value", "PDS-002")), new DocumentVersion("version-2", "doc-1", 2, 1,
                Map.of("contractNumber", object("value", "PDS-002")), "DRAFT", Instant.EPOCH, "user", null, List.of()),
                new DocumentVersion("version-1", "doc-1", 1, 1, Map.of("contractNumber", object("value", "PDS-001")),
                        "DRAFT", Instant.EPOCH, "user", null, List.of()), null, null, "stale-token", "hash", object()), fixture.allowedAuth()));
        assertEquals(409, error.status());
    }

    private static final class Fixture implements ProviderFixture {
        private final PlatformDocumentStore documents;
        private final PlatformDocumentVersionStore versions;
        private final PlatformDocumentTypeProvider types;
        private final PlatformBinaryStorage storage;
        private final PlatformAttachmentCatalog attachments;
        private final PlatformWorkflowProvider workflows;
        private final PlatformTaskProvider tasks;
        private final PlatformVPermissionChecker permissions;
        private final AuthContext allowed = new AuthContext("Bearer test", "id", "operator", "Operator", "", List.of("document_operator"), "operator");
        private final AuthContext denied = new AuthContext("Bearer denied", "denied", "denied", "Denied", "", List.of(), "denied");

        Fixture(PlatformStub platform) throws Exception {
            Path root = Path.of("..").toRealPath();
            var loaded = new ConfigurationLoader().load(root.resolve("../sber-npf-corelia-config"), "0.1.0");
            var catalog = new DocumentTypeCatalog(loaded);
            Environment environment = mock(Environment.class);
            Map<String, String> values = Map.of(
                    "PLATFORM_V_DATASPACE_GRAPHQL_URL", platform.base() + "/graphql",
                    "PLATFORM_V_BPMX_BASE_URL", platform.base() + "/bpmx",
                    "PLATFORM_V_TASK_LIST_BASE_URL", platform.base() + "/bpmu",
                    "PLATFORM_V_FILE_STORAGE_BASE_URL", platform.base() + "/dam",
                    "PLATFORM_V_TENANT", "tenant-test", "PLATFORM_V_APP_INSTANCE_ID", "app-test");
            when(environment.getProperty(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> values.getOrDefault(call.getArgument(0), call.getArgument(1)));
            var config = new PlatformVConfig(environment);
            CoreliaObservability observability = mock(CoreliaObservability.class);
            when(observability.observe(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
            var http = new PlatformHttp(observability, mock(TraceContextPropagation.class));
            var data = new DataSpaceClient(config, http, new PlatformVOperationCatalog(loaded));
            var bindings = new PlatformVDocumentBindings(loaded, new PlatformVOperationCatalog(loaded));
            documents = new PlatformDocumentStore(catalog, data, bindings);
            versions = new PlatformDocumentVersionStore(data, documents, catalog, bindings);
            types = new PlatformDocumentTypeProvider(data);
            storage = new PlatformBinaryStorage(new FileStorageClient(config, http));
            attachments = new PlatformAttachmentCatalog(data);
            var bpm = new BpmClient(config, http);
            workflows = new PlatformWorkflowProvider(bpm, data, config, catalog, bindings);
            tasks = new PlatformTaskProvider(bpm, new ParallelCalls(), catalog, new UserCache(), config, data);
            permissions = PlatformVPermissionChecker.fromText(Files.readString(root.resolve("../sber-npf-platform-v/ac.json")), loaded);
        }
        public Data data() { return new Data("PDS_CONTRACT", "doc-1", "token-1", "task-1", "contractNumber", "DocumentVersion:commit"); }
        public ru.corelia.provider.DocumentStore documents() { return documents; }
        public ru.corelia.provider.DocumentVersionStore versions() { return versions; }
        public ru.corelia.provider.DocumentTypeProvider documentTypes() { return types; }
        public ru.corelia.provider.BinaryStorage storage() { return storage; }
        public ru.corelia.provider.AttachmentCatalog attachments() { return attachments; }
        public ru.corelia.provider.WorkflowProvider workflows() { return workflows; }
        public ru.corelia.provider.TaskProvider tasks() { return tasks; }
        public ru.corelia.provider.PermissionProvider permissions() { return permissions; }
        public AuthContext allowedAuth() { return allowed; }
        public AuthContext deniedAuth() { return denied; }
        public void seed(DocumentSnapshot document, DocumentVersion version) {
            platform.document.put("version", document.currentVersion()).put("changeToken", document.changeToken());
            platform.details().put("status", "IN_WORK").put("contractNumber", document.attributes().get("contractNumber").path("value").asString());
            if (version.number() > 0) platform.documentVersions.put("version-" + version.number(), object("id", "version-" + version.number(), "documentId", document.id(), "version", version.number(),
                    "schemaVersion", 1, "attributes", write(object("contractNumber", document.attributes().get("contractNumber"))),
                    "attachments", "[]", "createdBy", "user", "createdAt", Instant.EPOCH.toString(), "status", "IN_WORK"));
        }
        public void seed(WorkflowTask task) {
            platform.task.put("id", task.id()).put("status", "NEW");
            platform.task.set("attributes", object("documentId", object("value", task.documentId()), "documentType", object("value", task.documentType())));
        }
    }
}

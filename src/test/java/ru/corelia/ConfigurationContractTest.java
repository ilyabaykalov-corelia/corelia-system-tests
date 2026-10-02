package ru.corelia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.corelia.support.Json.*;
import org.junit.jupiter.api.Test;
import ru.corelia.configuration.*;
import ru.corelia.providernativepermissions.NativePermissionProvider;
import ru.corelia.platformv.PlatformVDocumentBindings;
import ru.corelia.platformv.PlatformVOperationCatalog;
import ru.corelia.platformv.DocumentProjection;
import ru.corelia.documents.*;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.transport.ServiceClient;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.model.DocumentSearchRequest;
import ru.corelia.provider.model.DocumentSearchResult;
import ru.corelia.provider.model.DocumentSnapshot;
import java.nio.file.Path;
import java.util.*;

/** Независимые реестры документов и разные mapping для тестовых заказчиков. */
class ConfigurationContractTest {
    private ConfigurationLoader.LoadedConfiguration load(String customer) {
        return new ConfigurationLoader().load(Path.of("src/test/resources/customers", customer), "0.1.0");
    }
    @Test void nativePermissionsAndConfiguredAssignmentAreBothRequired() throws Exception {
        var config = load("customer-a");
        var permissions = new NativePermissionProvider(config);
        var editor = new AuthContext("token", "id", "alice", "Alice", "", List.of("fixture_editor"), "alice");
        var stranger = new AuthContext("token", "id", "alice", "Alice", "", List.of("document_operator"), "alice");
        assertDoesNotThrow(() -> permissions.require("Fixture:create", editor));
        assertEquals(403, assertThrows(ApiException.class, () -> permissions.require("Fixture:create", stranger)).status());
        assertThrows(ConfigurationException.class, () -> permissions.require("missing", editor));
        var services = mock(ServiceClient.class);
        when(services.call(eq("workflow"), anyString(), eq("GET"), isNull(), eq(editor)))
            .thenReturn(object("executor", object("login", "alice", "role", "fixture_editor")));
        var policy = new ConfiguredDocumentPolicy(services, new DocumentTypeCatalog(config), "CONTRACT_X", permissions);
        var document = object("documentId", "doc", "status", "OPEN");
        assertDoesNotThrow(() -> policy.authorize(document, "edit", editor));
        assertEquals(403, assertThrows(ApiException.class, () -> policy.authorize(document, "edit", stranger)).status());
        when(services.call(eq("workflow"), anyString(), eq("GET"), isNull(), eq(editor)))
            .thenReturn(object("executor", object("login", "someone_else", "role", "fixture_editor")));
        assertEquals(403, assertThrows(ApiException.class, () -> policy.authorize(document, "edit", editor)).status());
        document.put("status", "CLOSED");
        assertEquals(409, assertThrows(ApiException.class, () -> policy.authorize(document, "edit", editor)).status());
    }
    @Test void v3PackageProvidesNativeDataWorkflowPermissionAndUiContracts() {
        var loaded = load("customer-v3");
        var type = loaded.documentTypes().require("V3_CONTRACT");
        assertDoesNotThrow(() -> type.validate(object("number", "C-1", "amount", 1), false));
        assertEquals("number", type.ui().path("createForm").path("fields").get(0).asString());
        assertEquals("Contract number", type.ui().path("table").path("columns").get(0).path("label").asString());
        assertEquals("application/pdf", type.attachments().path("allowedMimeTypes").get(0).asString());
        assertEquals("v3_contract_process", loaded.providerBindings().get("V3_CONTRACT")
                .path("workflow").path("flowable").path("definitionKey").asString());
        var permissions = new NativePermissionProvider(loaded);
        var editor = new AuthContext("token", "id", "editor", "Editor", "", List.of("v3_editor"), "editor");
        assertDoesNotThrow(() -> permissions.require("document:V3_CONTRACT:create", editor));
    }
    @Test void deniedCreationDoesNotStageFilesOrStartProcesses() throws Exception {
        var config = load("customer-a");
        var permissions = new NativePermissionProvider(config);
        var services = mock(ServiceClient.class);
        var repository = mock(DocumentStore.class);
        var versions = mock(DocumentVersionService.class);
        var versionRepository = mock(DocumentVersionStore.class);
        var documentService = new DocumentService(permissions, new DocumentTypeCatalog(config), repository, services, versions, versionRepository);
        var unauthorized = new AuthContext("token", "id", "alice", "Alice", "", List.of(), "alice");
        assertEquals(403, assertThrows(ApiException.class, () -> documentService.create("CONTRACT_X", object(), unauthorized)).status());
        verifyNoInteractions(services, repository, versions, versionRepository);
    }
    @Test void sberSchemaAcceptsValidDateAndInsuranceNumber() {
        var types = new DocumentTypeCatalog(new ConfigurationLoader().load(Path.of("../../sber-npf-corelia-config"), "0.1.0"));
        assertDoesNotThrow(() -> types.validate("PDS_CONTRACT", object("contractDate", "2026-09-16", "contractNumber", "ПДС-1", "snils", "123-456-789 00"), false));
        assertThrows(ApiException.class, () -> types.validate("PDS_CONTRACT", object("contractDate", "2026-02-30", "contractNumber", "ПДС-1", "snils", "123-456-789 00"), false));
    }
    @Test void twoCustomersDoNotShareTypesSchemasOrMutableState() {
        var a = new DocumentTypeCatalog(load("customer-a"));
        var b = new DocumentTypeCatalog(load("customer-b"));
        assertEquals(2, a.types().size()); assertEquals(5, b.types().size());
        assertThrows(ApiException.class, () -> a.requireType("CONTRACT_G"));
        assertDoesNotThrow(() -> b.requireType("CONTRACT_G"));
        assertDoesNotThrow(() -> a.validate("CONTRACT_X", object("title", "one", "value", true), false));
        assertThrows(ApiException.class, () -> b.validate("CONTRACT_X", object("title", "one", "value", true), false));
        assertDoesNotThrow(() -> b.validate("CONTRACT_X", object("title", "one", "value", 123.5), false));
        assertFalse(a.publicDefinition("CONTRACT_X").has("storage"));
        assertFalse(a.publicDefinition("CONTRACT_X").has("workflow"));
    }
    @Test void adaptersUseConfiguredProjectionsAndAtomicUpdateOperation() {
        for (String customer : List.of("customer-a", "customer-b")) {
            var configuration = load(customer);
            var types = new DocumentTypeCatalog(configuration);
            var bindings = new PlatformVDocumentBindings(configuration, new PlatformVOperationCatalog(configuration));
            for (var type : types.types()) {
                var definition = types.definition(type);
                var storage = bindings.storage(type);
                var mapping = storage.path("fields");
                var row = object("id", "model-1", "documentId", "public-1", "version", 1,
                    "documentType", object("id", type), "changeToken", "before");
                var details = object("id", "details-1", "status", "OPEN", "caption", "one");
                details.set(text(mapping, "value"), definition.schema().definition().path("properties").path("value").path("type").asString().equals("boolean") ? parse("true") : parse("100.5"));
                row.set(text(storage, "details"), details);
                var projected = DocumentProjection.document(row, types, bindings);
                assertEquals("one", text(projected.path("attributes"), "title"));
                assertFalse(projected.has(text(storage, "details")));
                var policy = new ConfiguredDocumentPolicy(mock(ServiceClient.class), types, type, mock(ru.corelia.provider.PermissionProvider.class));
                assertEquals(definition.schemaVersion(), policy.schemaVersion());
                assertThrows(ApiException.class, () -> policy.validateSnapshot(object("title", "missing value")));
                int maximum = definition.attachments().path("maxCount").asInt();
                assertDoesNotThrow(() -> policy.validateAttachmentCount(maximum));
                assertThrows(ApiException.class, () -> policy.validateAttachmentCount(maximum + 1));
            }
        }
    }
    @Test void workflowUsesConfiguredProcessAndExternalFieldNames() {
        var types = new DocumentTypeCatalog(load("customer-a"));
        var auth = new AuthContext("token", "id", "operator", "Operator", "", List.of(), "operator");
        var provider = mock(ru.corelia.provider.WorkflowProvider.class);
        var versions = mock(ru.corelia.provider.DocumentVersionStore.class);
        var tasks = mock(ru.corelia.provider.TaskProvider.class);
        when(tasks.findByDocument("public-1", auth)).thenReturn(List.of());
        var service = new ru.corelia.workflow.WorkflowService(types, mock(DocumentStore.class), versions, provider, tasks);
        when(provider.start(any(), eq(auth))).thenReturn(new ru.corelia.provider.model.ProcessInstance("instance", "public-1", ""));
        service.create(object("typeCode", "CONTRACT_X", "documentId", "public-1", "attributes", object("title", "sample", "value", true)), auth);
        var body = org.mockito.ArgumentCaptor.forClass(ru.corelia.provider.model.WorkflowContext.class);
        verify(provider).start(body.capture(), eq(auth));
        assertEquals("sample", text(body.getValue().attributes().get("title")));
        assertFalse(body.getValue().attributes().containsKey("contractNumber"));
    }

    @Test void searchUsesConfiguredFieldsAndNumericSort() {
        var loaded = load("customer-a");
        var definition = (tools.jackson.databind.node.ObjectNode) loaded.documentTypes().require("CONTRACT_Y").definition();
        ((tools.jackson.databind.node.ObjectNode) definition.path("ui")).putArray("sortFields").add("value");
        var configured = new ConfigurationLoader.LoadedConfiguration(new DocumentTypeRegistry(List.of(
            new DocumentTypeDefinition(definition))), loaded.providerBindings(), loaded.packageRoot());
        var types = new DocumentTypeCatalog(configured);
        var repository = mock(DocumentStore.class);
        var auth = mock(AuthContext.class);
        when(repository.search(any(DocumentSearchRequest.class), eq(auth))).thenReturn(new DocumentSearchResult(List.of(
            new DocumentSnapshot("a", "CONTRACT_Y", "OPEN", 1, Map.of("title", parse("\"alpha\""), "value", parse("2")), "", null, ""),
            new DocumentSnapshot("b", "CONTRACT_Y", "OPEN", 1, Map.of("title", parse("\"beta\""), "value", parse("10")), "", null, "")), 2));
        var service = new DocumentService(mock(ru.corelia.provider.PermissionProvider.class), types, repository, mock(ServiceClient.class), mock(DocumentVersionService.class), mock(DocumentVersionStore.class));
        assertEquals("b", text(service.search("CONTRACT_Y", object(), auth).path("items").get(0), "id"));
        assertEquals(0, number(service.search("CONTRACT_Y", object("query", "10"), auth), "total", -1));
        assertEquals(1, number(service.search("CONTRACT_Y", object("query", "alpha"), auth), "total", -1));
        assertThrows(ApiException.class, () -> service.search("CONTRACT_Y", object("dateFrom", "2026-01-01"), auth));
    }

}

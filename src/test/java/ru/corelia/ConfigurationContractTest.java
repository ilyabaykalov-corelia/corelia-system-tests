package ru.corelia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.corelia.support.Json.*;
import org.junit.jupiter.api.Test;
import ru.corelia.configuration.*;
import ru.corelia.integration.*;
import ru.corelia.documents.*;
import ru.corelia.auth.AuthContext;
import ru.corelia.http.ApiException;
import ru.corelia.transport.ServiceClient;
import java.nio.file.Path;
import java.util.*;

/** Same product classes with independent, dissimilar customer registries and nonidentity mappings. */
class ConfigurationContractTest {
    private ConfigurationLoader.LoadedConfiguration load(String customer) {
        return new ConfigurationLoader().load(Path.of("src/test/resources/customers", customer), "0.1.0");
    }
    @Test void platformPermissionsAndConfiguredAssignmentAreBothRequired() throws Exception {
        var config = load("customer-a");
        String source = java.nio.file.Files.readString(Path.of("src/test/resources/customers/customer-a/platform-v-ac.json"));
        var permissions = PlatformVPermissionChecker.fromText(source, config);
        var editor = new AuthContext("token", "id", "alice", "Alice", "", List.of("fixture_editor"), "alice");
        var stranger = new AuthContext("token", "id", "alice", "Alice", "", List.of("document_operator"), "alice");
        assertDoesNotThrow(() -> permissions.require("Fixture:create", editor));
        assertEquals(403, assertThrows(ApiException.class, () -> permissions.require("Fixture:create", stranger)).status());
        assertThrows(ConfigurationException.class, () -> PlatformVPermissionChecker.fromText(source.replace("Fixture:edit", "Fixture:other"), config));
        assertThrows(ConfigurationException.class, () -> PlatformVPermissionChecker.fromText(source.replace("fixture_editor", "other_role"), config));
        assertThrows(ConfigurationException.class, () -> PlatformVPermissionChecker.fromText("{invalid", config));
        var services = mock(ServiceClient.class);
        when(services.call(eq("workflow"), anyString(), eq("GET"), isNull(), eq(editor)))
            .thenReturn(object("executor", object("login", "alice", "role", "fixture_editor")));
        var policy = new ConfiguredDocumentPolicy(services, new DocumentTypes(config), "CONTRACT_X", permissions);
        var document = object("documentId", "doc", "status", "OPEN");
        assertDoesNotThrow(() -> policy.authorize(document, "edit", editor));
        assertEquals(403, assertThrows(ApiException.class, () -> policy.authorize(document, "edit", stranger)).status());
        when(services.call(eq("workflow"), anyString(), eq("GET"), isNull(), eq(editor)))
            .thenReturn(object("executor", object("login", "someone_else", "role", "fixture_editor")));
        assertEquals(403, assertThrows(ApiException.class, () -> policy.authorize(document, "edit", editor)).status());
        document.put("status", "CLOSED");
        assertEquals(409, assertThrows(ApiException.class, () -> policy.authorize(document, "edit", editor)).status());
    }
    @Test void deniedCreationDoesNotStageFilesOrStartProcesses() throws Exception {
        var config = load("customer-a");
        var permissions = PlatformVPermissionChecker.fromText(java.nio.file.Files.readString(Path.of("src/test/resources/customers/customer-a/platform-v-ac.json")), config);
        var services = mock(ServiceClient.class);
        var repository = mock(DocumentRepository.class);
        var versions = mock(DocumentVersionService.class);
        var versionRepository = mock(DocumentVersionRepository.class);
        var documentService = new DocumentService(permissions, new DocumentTypes(config), repository, services, versions, versionRepository);
        var unauthorized = new AuthContext("token", "id", "alice", "Alice", "", List.of(), "alice");
        assertEquals(403, assertThrows(ApiException.class, () -> documentService.create("CONTRACT_X", object(), unauthorized)).status());
        verifyNoInteractions(services, repository, versions, versionRepository);
    }
    @Test void twoCustomersDoNotShareTypesSchemasOrMutableState() {
        var a = new DocumentTypes(load("customer-a"));
        var b = new DocumentTypes(load("customer-b"));
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
            var types = new DocumentTypes(load(customer));
            for (var type : types.types()) {
                var definition = types.definition(type);
                var mapping = definition.storage().path("fields");
                var row = object("id", "model-1", "documentId", "public-1", "version", 1,
                    "documentType", object("id", type), "changeToken", "before");
                var details = object("id", "details-1", "status", "OPEN", "caption", "one");
                details.set(text(mapping, "value"), definition.schema().definition().path("properties").path("value").path("type").asString().equals("boolean") ? parse("true") : parse("100.5"));
                row.set(text(definition.storage(), "details"), details);
                var projected = DocumentProjection.document(row, types);
                assertEquals("one", text(projected.path("attributes"), "title"));
                assertFalse(projected.has(text(definition.storage(), "details")));
                var data = mock(DataSpaceClient.class);
                var auth = mock(AuthContext.class);
                var repository = new PlatformDocumentVersionRepository(types, data);
                var attrs = copy(projected.path("attributes")).put("title", "two");
                repository.commit(projected, attrs, 2, object("id", "version-2"), object("id", "version-1"), null, null,
                    "key", "hash", object("changeToken", "after"), auth);
                var variables = org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
                verify(data).query(eq(text(definition.storage().path("operations"), "update")), variables.capture(), eq(auth));
                assertEquals("two", text(variables.getValue().path("details"), "caption"));
                assertEquals("one", text(variables.getValue().path("detailsCompare"), "caption"));
                assertEquals("before", text(variables.getValue().path("compare"), "changeToken"));
                assertEquals("after", text(variables.getValue().path("document"), "changeToken"));
                assertFalse(variables.getValue().path("details").has("title"));
                var policy = new ConfiguredDocumentPolicy(mock(ServiceClient.class), types, type, mock(ru.corelia.auth.PermissionChecker.class));
                assertEquals(definition.schemaVersion(), policy.schemaVersion());
                assertThrows(ApiException.class, () -> policy.validateSnapshot(object("title", "missing value")));
                int maximum = definition.attachments().path("maxCount").asInt();
                assertDoesNotThrow(() -> policy.validateAttachmentCount(maximum));
                assertThrows(ApiException.class, () -> policy.validateAttachmentCount(maximum + 1));
            }
        }
    }
    @Test void workflowUsesConfiguredProcessAndExternalFieldNames() {
        var types = new DocumentTypes(load("customer-a"));
        var bpm = mock(BpmClient.class);
        var data = mock(DataSpaceClient.class);
        var environment = new org.springframework.mock.env.MockEnvironment()
            .withProperty("PLATFORM_V_TENANT", "test").withProperty("PLATFORM_V_APP_INSTANCE_ID", "test");
        var service = new ru.corelia.workflow.WorkflowService(types, bpm, data,
            mock(ru.corelia.workflow.TaskGateway.class), mock(ru.corelia.workflow.TaskPresentation.class),
            new ru.corelia.config.CoreliaConfig(environment));
        var auth = new AuthContext("token", "id", "operator", "Operator", "", List.of(), "operator");
        when(bpm.process(anyString(), any(), eq(auth))).thenReturn(object("id", "instance"));
        service.create(object("typeCode", "CONTRACT_X", "documentId", "public-1", "attributes", object("title", "sample", "value", true)), auth);
        var body = org.mockito.ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
        verify(bpm).process(eq("/processes/process0:start"), body.capture(), eq(auth));
        assertEquals("sample", text(body.getValue().path("externalIds"), "title"));
        assertFalse(body.getValue().path("externalIds").has("contractNumber"));
        verifyNoInteractions(data);
    }

    @Test void searchUsesConfiguredFieldsAndNumericSort() {
        var loaded = load("customer-a");
        var definition = (tools.jackson.databind.node.ObjectNode) loaded.documentTypes().require("CONTRACT_Y").definition();
        ((tools.jackson.databind.node.ObjectNode) definition.path("ui")).putArray("sortFields").add("value");
        var configured = new ConfigurationLoader.LoadedConfiguration(new DocumentTypeRegistry(List.of(
            new DocumentTypeDefinition(definition, loaded.operations().keySet()))), loaded.operations());
        var types = new DocumentTypes(configured);
        var repository = mock(DocumentRepository.class);
        var auth = mock(AuthContext.class);
        when(repository.all("CONTRACT_Y", auth)).thenReturn(List.of(
            object("id", "a", "attributes", object("title", "alpha", "value", 2)),
            object("id", "b", "attributes", object("title", "beta", "value", 10))));
        var service = new DocumentService(mock(ru.corelia.auth.PermissionChecker.class), types, repository, mock(ServiceClient.class), mock(DocumentVersionService.class), mock(DocumentVersionRepository.class));
        assertEquals("b", text(service.search("CONTRACT_Y", object(), auth).path("items").get(0), "id"));
        assertEquals(0, number(service.search("CONTRACT_Y", object("query", "10"), auth), "total", -1));
        assertEquals(1, number(service.search("CONTRACT_Y", object("query", "alpha"), auth), "total", -1));
        assertThrows(ApiException.class, () -> service.search("CONTRACT_Y", object("dateFrom", "2026-01-01"), auth));
    }

}

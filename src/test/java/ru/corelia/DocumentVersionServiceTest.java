package ru.corelia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.corelia.support.Json.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.corelia.auth.AuthContext;
import ru.corelia.configuration.DocumentTypeCatalog;
import ru.corelia.documents.*;
import ru.corelia.provider.DocumentStore;
import ru.corelia.provider.DocumentVersionStore;
import ru.corelia.provider.model.*;
import tools.jackson.databind.JsonNode;

/** Разные реквизиты проходят один алгоритм версионирования через canonical SPI. */
class DocumentVersionServiceTest {
    @Test
    void historyReturnsRecordedFileEventWithoutCreatingDocumentVersion() {
        var versions = mock(DocumentVersionStore.class); var documents = mock(DocumentStore.class); var policy = mock(DocumentPolicy.class); var auth = mock(AuthContext.class); var types = mock(DocumentTypeCatalog.class);
        JsonNode recorded = object("id", "1:ATTACHMENT_REPLACED:file-1:2", "timestamp", "2026-09-15T10:00:00Z", "userLogin", "operator", "action", "ATTACHMENT_REPLACED", "documentVersion", 1,
                "attachment", object("attachmentId", "file-1", "oldFileName", "test.pdf", "oldVersion", 1, "newFileName", "print_form.pdf", "newVersion", 2));
        when(versions.history("doc-1", auth)).thenReturn(List.of(recorded));
        JsonNode result = new DocumentVersionService(versions, documents, List.of(policy), types).history("PDS_CONTRACT", "doc-1", auth);
        assertEquals(1, result.path("items").size());
        assertEquals("ATTACHMENT_REPLACED", text(result.path("items").get(0), "action"));
        assertEquals(2, number(result.path("items").get(0).path("attachment"), "newVersion", 0));
        verifyNoInteractions(documents);
    }

    @Test
    void historyGroupsAttributeChangesAndKeepsInitialAttachmentSeparate() {
        var versions = mock(DocumentVersionStore.class); var documents = mock(DocumentStore.class); var policy = mock(DocumentPolicy.class); var auth = mock(AuthContext.class); var types = mock(DocumentTypeCatalog.class);
        when(policy.type()).thenReturn("PDS_CONTRACT");
        when(types.publicDefinition("PDS_CONTRACT")).thenReturn(object("schema", object("properties", object(
                "contractNumber", object("title", "Номер договора"), "contractDate", object("title", "Дата договора")))));
        var document = new DocumentSnapshot("doc-1", "PDS_CONTRACT", "IN_WORK", 2, Map.of(), "creator", Instant.EPOCH, "token");
        var file = new AttachmentMetadata("file-1", "file-1", "doc-1", "test.pdf", "application/pdf", 1, 1, true, Instant.EPOCH, new StorageReference("storage-1"));
        var first = new DocumentVersion("v1", "doc-1", 1, 1, Map.of("contractNumber", parse("\"123\""), "contractDate", parse("\"2026-09-01\"")), "", Instant.parse("2026-09-01T10:00:00Z"), "creator", null, List.of(file));
        var second = new DocumentVersion("v2", "doc-1", 2, 1, Map.of("contractNumber", parse("\"456\""), "contractDate", parse("\"2026-09-15\"")), "", Instant.parse("2026-09-02T10:00:00Z"), "operator", null, List.of(file));
        when(versions.state("PDS_CONTRACT", "doc-1", auth)).thenReturn(new DocumentVersionState(document, second, List.of(first, second), List.of(file)));
        var result = new DocumentVersionService(versions, documents, List.of(policy), types).history("PDS_CONTRACT", "doc-1", auth);
        assertEquals(3, result.path("items").size());
        var attributes = result.path("items").get(0);
        assertEquals("ATTRIBUTES_CHANGED", text(attributes, "action"));
        assertEquals(2, attributes.path("changes").size());
        assertEquals(2, number(attributes, "documentVersion", 0));
        assertTrue(list(result.path("items")).stream().anyMatch(item -> "ATTACHMENT_ADDED".equals(text(item, "action"))));
        assertTrue(list(result.path("items")).stream().anyMatch(item -> "DOCUMENT_CREATED".equals(text(item, "action"))));
    }

    @Test
    void snapshotsUseThePolicySchemaAndPreserveNestedAttributesOfAnotherType() {
        var versions = mock(DocumentVersionStore.class); var documents = mock(DocumentStore.class); var policy = mock(DocumentPolicy.class); var auth = mock(AuthContext.class); var types = mock(DocumentTypeCatalog.class);
        when(auth.login()).thenReturn("operator"); when(policy.type()).thenReturn("APPLICATION"); when(policy.schemaVersion()).thenReturn(3); when(policy.validate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(types.name("APPLICATION")).thenReturn("Заявление"); when(types.label("APPLICATION", "OPEN")).thenReturn("Открыто"); when(types.tone("APPLICATION", "OPEN")).thenReturn("info");
        var attributes = Map.<String, JsonNode>of("subject", parse("\"Исходное заявление\""), "applicant", object("name", "Иван"));
        var snapshot = new DocumentSnapshot("application-1", "APPLICATION", "OPEN", 1, Map.of(), "", null, "token-1");
        var initial = new DocumentVersion("snapshot-1", "application-1", 1, 3, attributes, "", Instant.now(), "operator", null, List.of());
        var state = new AtomicReference<>(new DocumentVersionState(snapshot, initial, List.of(initial), List.of()));
        when(documents.get("APPLICATION", "application-1", auth)).thenReturn(snapshot);
        when(versions.state("APPLICATION", "application-1", auth)).thenAnswer(invocation -> state.get());
        doAnswer(invocation -> {
            DocumentMutation mutation = invocation.getArgument(0); assertEquals(3, mutation.createdVersion().schemaVersion());
            assertEquals("Иван", text(mutation.createdVersion().attributes().get("applicant"), "name"));
            var changed = new DocumentSnapshot("application-1", "APPLICATION", "OPEN", 2, Map.of(), "", null, text(mutation.response(), "changeToken"));
            state.set(new DocumentVersionState(changed, mutation.createdVersion(), List.of(initial, mutation.createdVersion()), List.of())); return null;
        }).when(versions).commit(any(), eq(auth));
        var service = new DocumentVersionService(versions, documents, List.of(policy), types);
        JsonNode updated = service.update("APPLICATION", "application-1", object("requestId", UUID.randomUUID().toString(), "expectedVersion", 1, "changeToken", "token-1", "attributes", object("subject", "Изменённое заявление")), auth);
        assertEquals(2, number(updated, "version", 0)); assertEquals("Иван", text(updated.path("attributes").path("applicant"), "name")); assertEquals("Изменённое заявление", text(updated.path("attributes"), "subject"));
        JsonNode previous = service.get("APPLICATION", "application-1", 1, auth); assertEquals("Исходное заявление", text(previous.path("attributes"), "subject")); assertEquals("Открыто", text(previous, "statusLabel")); assertEquals("info", text(previous, "statusTone")); verify(policy).authorize(any(), eq("attributes"), eq(auth));
    }
}

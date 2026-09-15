package ru.corelia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static ru.corelia.support.Json.*;

import org.junit.jupiter.api.Test;
import ru.corelia.auth.AuthContext;
import ru.corelia.documents.*;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Другой состав атрибутов проходит тот же алгоритм без отдельной таблицы версий. */
class DocumentVersionServiceTest {
    @Test
    void snapshotsUseThePolicySchemaAndPreserveNestedAttributesOfAnotherType() {
        var repository = mock(DocumentVersionRepository.class);
        var documents = mock(DocumentRepository.class);
        var policy = mock(DocumentPolicy.class);
        var auth = mock(AuthContext.class);
        when(auth.login()).thenReturn("operator");
        when(policy.type()).thenReturn("APPLICATION");
        when(policy.schemaVersion()).thenReturn(3);
        when(policy.validate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var attributes = object("subject", "Исходное заявление", "applicant", object("name", "Иван"));
        var state = new AtomicReference<>(object("id", "root-application", "documentId", "application-1",
                "version", 1, "changeToken", "token-1", "attributes", attributes));
        List<JsonNode> snapshots = new ArrayList<>();
        snapshots.add(object("id", "snapshot-1", "document", "root-application", "documentId", "application-1",
                "version", 1, "schemaVersion", 3, "attributes", write(attributes), "attachments", "[]"));
        when(repository.document(eq("APPLICATION"), eq("application-1"), eq(auth))).thenAnswer(i -> state.get());
        when(repository.versions("application-1", auth)).thenAnswer(i -> List.copyOf(snapshots));
        when(documents.get("APPLICATION", "application-1", auth)).thenReturn(object("id", "application-1", "typeCode", "APPLICATION", "status", "OPEN"));
        doAnswer(invocation -> {
            JsonNode created = invocation.getArgument(3);
            assertEquals(3, number(created, "schemaVersion", 0));
            assertEquals("root-application", text(created, "document"));
            assertFalse(created.has("subject"));
            snapshots.add(copy(created).put("id", "snapshot-2"));
            JsonNode response = invocation.getArgument(9);
            state.set(copy(state.get()).put("version", 2).put("changeToken", text(response, "changeToken")));
            return null;
        }).when(repository).commit(any(), any(), anyInt(), any(), any(), isNull(), isNull(), anyString(), anyString(), any(), eq(auth));
        var service = new DocumentVersionService(repository, documents, List.of(policy));
        JsonNode updated = service.update("APPLICATION", "application-1", object("requestId", UUID.randomUUID().toString(),
                "expectedVersion", 1, "changeToken", "token-1", "attributes", object("subject", "Изменённое заявление")), auth);
        assertEquals(2, number(updated, "version", 0));
        assertEquals("Иван", text(updated.path("attributes").path("applicant"), "name"));
        assertEquals("Изменённое заявление", text(updated.path("attributes"), "subject"));
        JsonNode previous = service.get("APPLICATION", "application-1", 1, auth);
        assertEquals("Исходное заявление", text(previous.path("attributes"), "subject"));
        verify(policy).authorize(any(), eq("attributes"), eq(auth));
    }
}

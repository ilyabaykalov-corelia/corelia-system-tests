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

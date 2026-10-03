package ru.corelia;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Не допускает возврат provider-specific деталей в нейтральные и business-модули. */
class ArchitectureBoundaryTest {
    private static final List<String> GENERIC_MODULES = List.of(
            "corelia-common", "corelia-configuration", "corelia-document-service",
            "corelia-workflow-service", "corelia-attachment-service", "corelia-gateway");
    private static final List<String> FORBIDDEN = List.of(
            "Platform V", "DataSpace", "BPMX", "BPMU", "BpmClient", "FileStorageClient",
            "platform-v-dam", "GraphQL", "multiaggregate", "appInstanceId", "/system/v6/usertasks");

    @Test void genericProductionSourceDoesNotKnowRemovedProviderProtocol() throws IOException {
        Path root = Path.of("..").toRealPath();
        for (String module : GENERIC_MODULES) {
            Path source = root.resolve(module).resolve("src/main");
            if (!Files.exists(source)) continue;
            try (var files = Files.walk(source)) {
                for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(file);
                    for (String forbidden : FORBIDDEN)
                        assertFalse(text.contains(forbidden), () -> module + " раскрывает provider detail '" + forbidden + "' в " + source.relativize(file));
                }
            }
        }
    }

    @Test void buildDoesNotContainRemovedProviderModule() throws IOException {
        Path root = Path.of("..").toRealPath();
        assertFalse(Files.readString(root.resolve("pom.xml")).contains("corelia-platform-v"));
        assertFalse(Files.exists(root.resolve("corelia-platform-v")));
    }
}

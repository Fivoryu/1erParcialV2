package com.diagramador.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.command.ValidationState;
import com.diagramador.domain.ClassifierKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DomainProjectEmitterNameCollisionTest {

    private final DomainModelIRMapper mapper = new DomainModelIRMapper();
    private final DomainProjectEmitter emitter = new DomainProjectEmitter();

    @TempDir
    Path tempDir;

    @Test
    void compilesAllGeneratedJavaForAnnotationNameCollisionsAndRemainsDeterministic() throws IOException {
        DomainModelIR ir = mapper.map(snapshot());
        assertThat(ir.hasBlockingDiagnostics()).isFalse();

        Map<String, String> first = emitter.emit("name-collision", ir);
        Map<String, String> second = emitter.emit("name-collision", ir);
        assertThat(new ArrayList<>(second.entrySet())).containsExactlyElementsOf(new ArrayList<>(first.entrySet()));
        assertThat(first.keySet()).anyMatch(path -> path.endsWith("/domain/Service.java"));
        assertThat(first.keySet()).anyMatch(path -> path.endsWith("/domain/Transactional.java"));
        assertThat(first.keySet()).anyMatch(path -> path.endsWith("/domain/Customer.java"));

        Path sourceRoot = tempDir.resolve("sources");
        Path classesRoot = tempDir.resolve("classes");
        Files.createDirectories(classesRoot);
        List<Path> javaSources = new ArrayList<>();
        for (Map.Entry<String, String> artifact : first.entrySet()) {
            if (!artifact.getKey().endsWith(".java")) {
                continue;
            }
            Path output = sourceRoot.resolve(artifact.getKey()).normalize();
            assertThat(output.startsWith(sourceRoot)).isTrue();
            Files.createDirectories(output.getParent());
            Files.writeString(output, artifact.getValue(), StandardCharsets.UTF_8);
            javaSources.add(output);
        }
        assertThat(javaSources).isNotEmpty();

        String testClasspath = System.getProperty("surefire.test.class.path");
        assertThat(testClasspath).isNotBlank();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("The regression must run with a JDK compiler").isNotNull();

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean compiled;
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(javaSources);
            compiled = Boolean.TRUE.equals(compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-classpath", testClasspath, "-d", classesRoot.toString(), "-proc:none"),
                    null,
                    compilationUnits).call());
        }

        String diagnosticText = diagnostics.getDiagnostics().stream()
                .map(diagnostic -> diagnostic.getKind() + " "
                        + (diagnostic.getSource() == null ? "<unknown>" : diagnostic.getSource().getName())
                        + ":" + diagnostic.getLineNumber() + " "
                        + diagnostic.getMessage(Locale.ROOT))
                .collect(Collectors.joining(System.lineSeparator()));
        assertThat(compiled).as("Generated Java compiler diagnostics:\n" + diagnosticText).isTrue();
    }

    private static InputSnapshot snapshot() {
        String projection = "name-collision-fixture-v1";
        return new InputSnapshot(
                "name-collision-fixture",
                new UUID(0, 100),
                new UUID(0, 101),
                1,
                "a".repeat(64),
                ValidationState.VALID,
                InputSnapshotFactory.sha256(projection),
                List.of(
                        new InputSnapshot.ClassifierElement(new UUID(0, 1), null, ClassifierKind.CLASS, "Service", false),
                        new InputSnapshot.ClassifierElement(new UUID(0, 2), null, ClassifierKind.CLASS, "Transactional", false),
                        new InputSnapshot.ClassifierElement(new UUID(0, 3), null, ClassifierKind.CLASS, "Customer", false)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                projection);
    }
}

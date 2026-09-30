package com.diagramador;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BaselineConsistencyTest {

    @Test
    void baselineMatchesPomDockerfileAndComposeDeclarations() throws IOException {
        Path root = repositoryRoot();
        byte[] baselineBytes = Files.readAllBytes(root.resolve("backend/src/main/resources/baseline.properties"));
        byte[] pomBytes = Files.readAllBytes(root.resolve("backend/pom.xml"));
        byte[] dockerfileBytes = Files.readAllBytes(root.resolve("backend/Dockerfile"));
        byte[] composeBytes = Files.readAllBytes(root.resolve("docker-compose.yml"));

        String baseline = new String(baselineBytes, StandardCharsets.UTF_8);
        String pom = new String(pomBytes, StandardCharsets.UTF_8);
        String dockerfile = new String(dockerfileBytes, StandardCharsets.UTF_8);
        String compose = new String(composeBytes, StandardCharsets.UTF_8);

        String javaVersion = baselineValue(baseline, "java.version");
        String springBootVersion = baselineValue(baseline, "spring.boot.version");
        String postgresImage = baselineValue(baseline, "postgres.image");
        String runtimeBaseImage = baselineValue(baseline, "runtime.base.image");

        assertThat(javaVersion).as("baseline and Maven java.version").isEqualTo(pomProperty(pom, "java.version"));
        assertThat(springBootVersion).as("baseline and Spring Boot parent version")
                .isEqualTo(springBootParentVersion(pom));
        assertThat(javaVersion).as("baseline and Dockerfile JAVA_VERSION")
                .isEqualTo(dockerfileArg(dockerfile, "JAVA_VERSION"));
        assertThat(springBootVersion).as("baseline and Dockerfile SPRING_BOOT_VERSION")
                .isEqualTo(dockerfileArg(dockerfile, "SPRING_BOOT_VERSION"));
        assertThat(postgresImage).as("baseline and Dockerfile POSTGRES_IMAGE")
                .isEqualTo(dockerfileArg(dockerfile, "POSTGRES_IMAGE"));
        assertThat(runtimeBaseImage).as("baseline and Dockerfile RUNTIME_BASE_IMAGE")
                .isEqualTo(dockerfileArg(dockerfile, "RUNTIME_BASE_IMAGE"));
        assertThat(dockerfile).containsPattern("(?m)^FROM \\$\\{RUNTIME_BASE_IMAGE}$");
        assertThat(dockerfile).contains("COPY target/*.jar /app/app.jar");

        assertThat(javaVersion).as("baseline and Compose JAVA_VERSION")
                .isEqualTo(composeBuildArg(compose, "JAVA_VERSION"));
        assertThat(springBootVersion).as("baseline and Compose SPRING_BOOT_VERSION")
                .isEqualTo(composeBuildArg(compose, "SPRING_BOOT_VERSION"));
        assertThat(postgresImage).as("baseline and Compose PostgreSQL image anchor")
                .isEqualTo(composePostgresImage(compose));
        assertThat(runtimeBaseImage).as("baseline and Compose RUNTIME_BASE_IMAGE")
                .isEqualTo(composeBuildArg(compose, "RUNTIME_BASE_IMAGE"));
        assertThat(composeBuildArg(compose, "POSTGRES_IMAGE")).isEqualTo("*postgres-image");
        assertThat(compose).containsPattern("(?m)^\\s{4}image:\\s*\\*postgres-image\\s*$");
        assertThat(compose).containsPattern("(?m)^\\s{8}POSTGRES_IMAGE:\\s*\\*postgres-image\\s*$");
        assertThat(compose).containsPattern("(?m)^name:\\s*uml-diagrammer\\s*$");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("backend/pom.xml"))
                    && Files.isRegularFile(current.resolve("docker-compose.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from the test working directory");
    }

    private static String baselineValue(String baseline, String key) {
        return capture(baseline, "(?m)^" + Pattern.quote(key) + "=([^\\r\\n]+)$", key);
    }

    private static String pomProperty(String pom, String property) {
        return capture(pom, "(?s)<properties>.*?<" + Pattern.quote(property)
                + ">(.*?)</" + Pattern.quote(property) + ">.*?</properties>", "pom " + property).trim();
    }

    private static String springBootParentVersion(String pom) {
        return capture(pom, "(?s)<parent>\\s*<groupId>org\\.springframework\\.boot</groupId>\\s*"
                + "<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)</version>"
                + ".*?</parent>", "Spring Boot parent version").trim();
    }

    private static String dockerfileArg(String dockerfile, String name) {
        return capture(dockerfile, "(?m)^ARG\\s+" + Pattern.quote(name) + "=([^\\s]+)\\s*$",
                "Dockerfile ARG " + name).trim();
    }

    private static String composeBuildArg(String compose, String name) {
        return capture(compose, "(?m)^\\s{8}" + Pattern.quote(name) + ":\\s*[\"']?([^\\s\"']+)[\"']?\\s*$",
                "Compose build arg " + name).trim();
    }

    private static String composePostgresImage(String compose) {
        return capture(compose, "(?m)^x-postgres-image:\\s*&postgres-image\\s+([^\\s]+)\\s*$",
                "Compose PostgreSQL image anchor").trim();
    }

    private static String capture(String source, String expression, String description) {
        Matcher matcher = Pattern.compile(expression).matcher(source);
        assertThat(matcher.find()).as("must declare " + description).isTrue();
        String value = matcher.group(1);
        assertThat(matcher.find()).as("must declare " + description + " only once").isFalse();
        return value;
    }
}

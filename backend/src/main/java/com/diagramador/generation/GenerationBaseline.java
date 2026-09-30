package com.diagramador.generation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;

/** The single version baseline shared by runtime emission and consistency checks. */
public record GenerationBaseline(
        String javaVersion,
        String springBootVersion,
        String postgresImage,
        String runtimeBaseImage) {

    private static final String RESOURCE_NAME = "/baseline.properties";

    public GenerationBaseline {
        javaVersion = required(javaVersion, "java.version");
        springBootVersion = required(springBootVersion, "spring.boot.version");
        postgresImage = required(postgresImage, "postgres.image");
        runtimeBaseImage = required(runtimeBaseImage, "runtime.base.image");
    }

    public static GenerationBaseline load() {
        try (InputStream stream = GenerationBaseline.class.getResourceAsStream(RESOURCE_NAME)) {
            if (stream == null) {
                throw new IllegalStateException("Classpath resource " + RESOURCE_NAME + " is missing");
            }
            Properties values = new Properties();
            values.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return new GenerationBaseline(values.getProperty("java.version"),
                    values.getProperty("spring.boot.version"), values.getProperty("postgres.image"),
                    values.getProperty("runtime.base.image"));
        } catch (IOException exception) {
            throw new IllegalStateException("Classpath resource " + RESOURCE_NAME + " could not be read", exception);
        }
    }

    private static String required(String value, String key) {
        String normalized = Objects.requireNonNull(value, key).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Baseline property " + key + " must not be blank");
        }
        return normalized;
    }
}

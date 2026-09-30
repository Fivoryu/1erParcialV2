package com.diagramador.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.generation.GenerationArtifactBundleBuilder.ArtifactDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The persisted artifact manifest must be comparable without depending on either order.
 *
 * <p>This is the regression that a deployment run against Debian-based PostgreSQL found: the same
 * artifact set came back from the database in word-collation order (`docker-compose.yml`,
 * `Dockerfile`, `.env.example`, `.gitignore`, …) while the emitter's manifest is in Java path order
 * (`.env.example`, `.gitignore`, `Dockerfile`, …). A positional comparison accepted the run on one
 * PostgreSQL build and rejected it on another. These tests need no database: they exercise the
 * comparison itself with both orders.
 */
class GenerationArtifactManifestTest {

    private static final List<ArtifactDescriptor> EMITTED_IN_PATH_ORDER = List.of(
            descriptor(".env.example"),
            descriptor(".gitignore"),
            descriptor("Dockerfile"),
            descriptor("README.md"),
            descriptor("docker-compose.yml"),
            descriptor("openapi.json"),
            descriptor("pom.xml"),
            descriptor("provenance.json"));

    /** What PostgreSQL returned under an `en_US.utf8` collation for that same artifact set. */
    private static final List<ArtifactDescriptor> DATABASE_WORD_ORDER = List.of(
            descriptor("docker-compose.yml"),
            descriptor("Dockerfile"),
            descriptor(".env.example"),
            descriptor(".gitignore"),
            descriptor("openapi.json"),
            descriptor("pom.xml"),
            descriptor("provenance.json"),
            descriptor("README.md"));

    private static ArtifactDescriptor descriptor(String path) {
        return new ArtifactDescriptor(path, "configuration", "a".repeat(64), path.length());
    }

    @Test
    void matchesTheSameRowsInBothOrdersBecauseTheCollationOrdersPathsDifferently() {
        // Premise first: the two orders are genuinely different, so the old positional comparison failed.
        assertThat(DATABASE_WORD_ORDER).isNotEqualTo(EMITTED_IN_PATH_ORDER);
        assertThat(DATABASE_WORD_ORDER).containsExactlyInAnyOrderElementsOf(EMITTED_IN_PATH_ORDER);

        assertThat(GenerationArtifactService.manifestsMatch(EMITTED_IN_PATH_ORDER, DATABASE_WORD_ORDER)).isTrue();
        assertThat(GenerationArtifactService.manifestsMatch(DATABASE_WORD_ORDER, EMITTED_IN_PATH_ORDER)).isTrue();
    }

    @Test
    void matchesAnyPermutationOfTheSameRows() {
        List<ArtifactDescriptor> reversed = new ArrayList<>(EMITTED_IN_PATH_ORDER);
        Collections.reverse(reversed);
        List<ArtifactDescriptor> rotated = new ArrayList<>(EMITTED_IN_PATH_ORDER);
        Collections.rotate(rotated, 3);

        assertThat(reversed).isNotEqualTo(EMITTED_IN_PATH_ORDER);
        assertThat(GenerationArtifactService.manifestsMatch(EMITTED_IN_PATH_ORDER, reversed)).isTrue();
        assertThat(GenerationArtifactService.manifestsMatch(EMITTED_IN_PATH_ORDER, rotated)).isTrue();
    }

    @Test
    void rejectsAMissingRowAnExtraRowAChangedHashADifferentSizeAndADuplicatePath() {
        List<ArtifactDescriptor> missing = EMITTED_IN_PATH_ORDER.subList(0, EMITTED_IN_PATH_ORDER.size() - 1);
        List<ArtifactDescriptor> extra = new ArrayList<>(EMITTED_IN_PATH_ORDER);
        extra.add(descriptor("src/main/resources/extra.yml"));
        List<ArtifactDescriptor> changedHash = replace(0,
                new ArtifactDescriptor(".env.example", "configuration", "b".repeat(64), ".env.example".length()));
        List<ArtifactDescriptor> differentSize = replace(0,
                new ArtifactDescriptor(".env.example", "configuration", "a".repeat(64), 9_999));
        List<ArtifactDescriptor> duplicate = new ArrayList<>(EMITTED_IN_PATH_ORDER);
        duplicate.add(EMITTED_IN_PATH_ORDER.getFirst());

        assertThat(GenerationArtifactService.manifestsMatch(EMITTED_IN_PATH_ORDER, missing)).isFalse();
        assertThat(GenerationArtifactService.manifestsMatch(EMITTED_IN_PATH_ORDER, extra)).isFalse();
        assertThat(GenerationArtifactService.manifestsMatch(EMITTED_IN_PATH_ORDER, changedHash)).isFalse();
        assertThat(GenerationArtifactService.manifestsMatch(EMITTED_IN_PATH_ORDER, differentSize)).isFalse();
        assertThat(GenerationArtifactService.manifestsMatch(EMITTED_IN_PATH_ORDER, duplicate)).isFalse();
    }

    private static List<ArtifactDescriptor> replace(int index, ArtifactDescriptor replacement) {
        List<ArtifactDescriptor> copy = new ArrayList<>(EMITTED_IN_PATH_ORDER);
        copy.set(index, replacement);
        return copy;
    }
}

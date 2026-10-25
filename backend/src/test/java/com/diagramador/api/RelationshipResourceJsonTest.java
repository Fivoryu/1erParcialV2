package com.diagramador.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource.RelationshipResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelationshipResourceJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void aRelationshipWithoutAStereotypeSerialisesExactlyAsItAlwaysDid() {
        RelationshipResource plain = new RelationshipResource(UUID.randomUUID(), "ASSOCIATION", "owns", List.of(),
                List.of());

        JsonNode json = objectMapper.valueToTree(plain);

        assertThat(json.has("stereotype")).isFalse();
        assertThat(plain.stereotype()).isNull();
    }

    @Test
    void aStereotypedDependencySerialisesItsStereotype() {
        RelationshipResource dependency = new RelationshipResource(UUID.randomUUID(), "DEPENDENCY", "needs", "USE",
                List.of(), List.of());

        assertThat(objectMapper.valueToTree(dependency).path("stereotype").asText()).isEqualTo("USE");
    }

    @Test
    void aSnapshotWrittenBeforeStereotypesExistReadsBackWithoutOne() throws Exception {
        UUID id = UUID.randomUUID();
        String legacy = "{\"id\":\"" + id + "\",\"kind\":\"DEPENDENCY\",\"name\":\"needs\",\"ends\":[],\"constraints\":[]}";

        RelationshipResource read = objectMapper.readValue(legacy, RelationshipResource.class);

        assertThat(read.id()).isEqualTo(id);
        assertThat(read.stereotype()).isNull();
    }

    @Test
    void aStereotypeSurvivesAWriteAndARead() throws Exception {
        RelationshipResource dependency = new RelationshipResource(UUID.randomUUID(), "DEPENDENCY", null, "INCLUDE",
                List.of(), List.of());

        RelationshipResource read = objectMapper.readValue(objectMapper.writeValueAsString(dependency),
                RelationshipResource.class);

        assertThat(read).isEqualTo(dependency);
    }
}

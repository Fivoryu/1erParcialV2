package com.diagramador.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.RelationshipEndResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.MultiplicityKind;
import com.diagramador.domain.NavigationKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The assistant has to see the stereotype of a dependency, or it cannot tell «use» from «import» when asked. */
class DiagramContextRendererStereotypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DiagramContextRenderer renderer = new DiagramContextRenderer(objectMapper);

    @Test
    void aStereotypedDependencyCarriesItsStereotypeInTheContext() throws Exception {
        JsonNode relationship = render("DEPENDENCY", "USE").path("relationships").get(0);

        assertThat(relationship.path("kind").asText()).isEqualTo("DEPENDENCY");
        assertThat(relationship.path("stereotype").asText()).isEqualTo("USE");
        assertThat(relationship.path("source").asText()).isEqualTo("Order");
        assertThat(relationship.path("target").asText()).isEqualTo("Customer");
    }

    @Test
    void aRelationshipWithoutAStereotypeKeepsTheContextItHadBefore() throws Exception {
        JsonNode relationship = render("ASSOCIATION", null).path("relationships").get(0);

        assertThat(relationship.has("stereotype")).isFalse();
        assertThat(relationship.fieldNames()).toIterable().containsExactly("kind", "source", "target");
    }

    @Test
    void anAssociationClassCarriesItsNameInTheContext() throws Exception {
        JsonNode relationship = render("ASSOCIATION", null, true).path("relationships").get(0);

        assertThat(relationship.path("associationClass").asText()).isEqualTo("Invoice");
        assertThat(render("ASSOCIATION", null, false).path("relationships").get(0).has("associationClass")).isFalse();
    }

    private JsonNode render(String kind, String stereotype) throws Exception {
        return render(kind, stereotype, false);
    }

    private JsonNode render(String kind, String stereotype, boolean withAssociationClass) throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        ClassifierResource order = new ClassifierResource(orderId, "Order", ClassifierKind.CLASS, false, null,
                List.of(), List.of(), List.of());
        ClassifierResource customer = new ClassifierResource(customerId, "Customer", ClassifierKind.CLASS, false,
                null, List.of(), List.of(), List.of());
        UUID invoiceId = UUID.randomUUID();
        ClassifierResource invoice = new ClassifierResource(invoiceId, "Invoice", ClassifierKind.CLASS, false,
                null, List.of(), List.of(), List.of());
        RelationshipResource relationship = new RelationshipResource(UUID.randomUUID(), kind, null, stereotype,
                withAssociationClass ? invoiceId : null,
                List.of(new RelationshipEndResource(UUID.randomUUID(), orderId, null, MultiplicityKind.EXACTLY_ONE,
                                NavigationKind.UNSPECIFIED, "none", (short) 1),
                        new RelationshipEndResource(UUID.randomUUID(), customerId, null,
                                MultiplicityKind.EXACTLY_ONE, NavigationKind.UNSPECIFIED, "none", (short) 2)),
                List.of());
        ModelResource model = new ModelResource(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                List.of(), List.of(order, customer, invoice), List.of(relationship));
        return objectMapper.readTree(renderer.renderDiagramOnly(model));
    }
}

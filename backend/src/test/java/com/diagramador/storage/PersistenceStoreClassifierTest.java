package com.diagramador.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.ClassifierKind;
import com.diagramador.domain.PersistenceStore;
import com.diagramador.storage.PersistenceStoreClassifier.ClassifierFacts;
import com.diagramador.storage.PersistenceStoreClassifier.CompositionLink;
import com.diagramador.storage.PersistenceStoreClassifier.Decision;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersistenceStoreClassifierTest {

    private static final UUID ORDER = id(1);
    private static final UUID LINE = id(2);
    private static final UUID TAGGED = id(3);
    private static final UUID STATUS = id(4);

    @Test
    void aClassWithoutSignalsDefaultsToSql() {
        Map<UUID, Decision> decisions = PersistenceStoreClassifier.classify(
                List.of(facts(ORDER, ClassifierKind.CLASS, null, false)), List.of(), Map.of(ORDER, "Order"));

        assertThat(decisions.get(ORDER).store()).isEqualTo(PersistenceStore.SQL);
        assertThat(decisions.get(ORDER).override()).isFalse();
        assertThat(decisions.get(ORDER).reason()).isEqualTo("No document signal, so it stays relational.");
    }

    @Test
    void thePartOfACompositionBecomesADocumentAndNamesItsWhole() {
        Map<UUID, Decision> decisions = PersistenceStoreClassifier.classify(
                List.of(facts(ORDER, ClassifierKind.CLASS, null, false),
                        facts(LINE, ClassifierKind.CLASS, null, false)),
                List.of(new CompositionLink(ORDER, LINE)), Map.of(ORDER, "Order", LINE, "OrderLine"));

        assertThat(decisions.get(ORDER).store()).isEqualTo(PersistenceStore.SQL);
        assertThat(decisions.get(LINE).store()).isEqualTo(PersistenceStore.DOCUMENT);
        assertThat(decisions.get(LINE).reason()).isEqualTo("Part of a composition owned by Order.");
    }

    @Test
    void aListAttributeMakesTheClassADocument() {
        Map<UUID, Decision> decisions = PersistenceStoreClassifier.classify(
                List.of(facts(TAGGED, ClassifierKind.CLASS, null, true)), List.of(), Map.of(TAGGED, "Post"));

        assertThat(decisions.get(TAGGED).store()).isEqualTo(PersistenceStore.DOCUMENT);
        assertThat(decisions.get(TAGGED).reason()).isEqualTo("Has list attributes, which a document stores natively.");
    }

    @Test
    void aManualOverrideWinsOverEverySignalAndIsFlagged() {
        Map<UUID, Decision> decisions = PersistenceStoreClassifier.classify(
                List.of(facts(LINE, ClassifierKind.CLASS, PersistenceStore.SQL, true)),
                List.of(new CompositionLink(ORDER, LINE)), Map.of(ORDER, "Order", LINE, "OrderLine"));

        assertThat(decisions.get(LINE).store()).isEqualTo(PersistenceStore.SQL);
        assertThat(decisions.get(LINE).override()).isTrue();
        assertThat(decisions.get(LINE).reason()).isEqualTo("Set manually.");
    }

    @Test
    void onlyClassesAreClassified() {
        Map<UUID, Decision> decisions = PersistenceStoreClassifier.classify(
                List.of(facts(STATUS, ClassifierKind.ENUMERATION, null, false),
                        facts(ORDER, ClassifierKind.ABSTRACT_CLASS, null, false)),
                List.of(), Map.of(STATUS, "Status", ORDER, "Order"));

        assertThat(decisions).containsOnlyKeys(ORDER);
    }

    private static ClassifierFacts facts(UUID id, ClassifierKind kind, PersistenceStore override,
            boolean hasListAttribute) {
        return new ClassifierFacts(id, kind, override, hasListAttribute);
    }

    private static UUID id(int value) {
        return new UUID(0L, value);
    }
}

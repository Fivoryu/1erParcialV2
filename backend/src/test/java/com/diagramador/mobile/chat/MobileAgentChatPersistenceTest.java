package com.diagramador.mobile.chat;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import jakarta.persistence.EntityManager;

/** Uses a new disposable database; never points at an existing platform database. */
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MobileAgentChatPersistenceTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired AgentConversationRepository conversations;
    @Autowired AgentMessageRepository messages;
    @Autowired EntityManager em;
    @Test void newMigrationMapsAndBoundedQueriesRunOnPostgres() {
        UUID id=UUID.randomUUID();
        assertThat(conversations.history(id,id,id,null,PageRequest.of(0,51))).isEmpty();
        assertThat(conversations.history(id,id,id,id,PageRequest.of(0,51))).isEmpty();
        assertThat(conversations.lockById(id)).isEmpty();
        assertThat(messages.findByConversationIdAndSequenceGreaterThanOrderBySequenceAsc(id,0,PageRequest.of(0,51))).isEmpty();
        em.createNativeQuery("select 1 from pg_advisory_xact_lock(?1)").setParameter(1,42L).getSingleResult();
        assertThat(((Number)em.createNativeQuery("select count(*) from pg_constraint where conrelid='diagrammer.agent_message'::regclass and contype='u'").getSingleResult()).intValue()).isEqualTo(1);
    }
}

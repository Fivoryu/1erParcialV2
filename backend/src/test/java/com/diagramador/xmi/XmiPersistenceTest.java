package com.diagramador.xmi;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramador.domain.ExchangeDirection;
import com.diagramador.domain.ExchangeStatus;
import com.diagramador.domain.Project;
import com.diagramador.domain.SeverityKind;
import com.diagramador.domain.UmlModel;
import com.diagramador.domain.UserAccount;
import com.diagramador.domain.XmiExchange;
import com.diagramador.domain.XmiLossItem;
import com.diagramador.persistence.ProjectRepository;
import com.diagramador.persistence.UmlModelRepository;
import com.diagramador.persistence.UserAccountRepository;
import com.diagramador.persistence.XmiExchangeRepository;
import com.diagramador.persistence.XmiLossItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
class XmiPersistenceTest {

    private static final UUID SEED_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private final UserAccountRepository userAccountRepository;
    private final ProjectRepository projectRepository;
    private final UmlModelRepository modelRepository;
    private final XmiExchangeRepository exchangeRepository;
    private final XmiLossItemRepository lossItemRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    XmiPersistenceTest(UserAccountRepository userAccountRepository, ProjectRepository projectRepository,
            UmlModelRepository modelRepository, XmiExchangeRepository exchangeRepository,
            XmiLossItemRepository lossItemRepository) {
        this.userAccountRepository = userAccountRepository;
        this.projectRepository = projectRepository;
        this.modelRepository = modelRepository;
        this.exchangeRepository = exchangeRepository;
        this.lossItemRepository = lossItemRepository;
    }

    @Test
    @Transactional
    void persistsExchangesAndLossItemsWithNativeEnumsAndCascades() {
        UserAccount owner = userAccountRepository.findById(SEED_OWNER_ID).orElseThrow();
        Project project = projectRepository.save(Project.create(owner, "XMI persistence test", "test"));
        UmlModel model = modelRepository.save(UmlModel.create(project, "XMI persistence model"));

        XmiExchange exchange = XmiExchange.create(model, ExchangeDirection.IMPORT, "EA 15.0.1514 / XMI 2.1",
                3L, "a".repeat(64), "fixture.xmi");
        exchange.setStatus(ExchangeStatus.PARTIAL);
        exchange.addLossItem(SeverityKind.WARNING, "Model/elements", "Unsupported extension data was ignored.");
        exchange.addLossItem(SeverityKind.ERROR, "Model/relationships", "A relationship could not be resolved.");

        XmiExchange saved = exchangeRepository.saveAndFlush(exchange);
        UUID exchangeId = saved.getId();
        entityManager.clear();

        XmiExchange loaded = exchangeRepository.findById(exchangeId).orElseThrow();
        assertThat(loaded.getDirection()).isEqualTo(ExchangeDirection.IMPORT);
        assertThat(loaded.getStatus()).isEqualTo(ExchangeStatus.PARTIAL);
        assertThat(loaded.getLossItems()).extracting(XmiLossItem::getSeverity)
                .containsExactlyInAnyOrder(SeverityKind.WARNING, SeverityKind.ERROR);
        assertThat(lossItemRepository.findByExchangeId(exchangeId)).hasSize(2);

        exchangeRepository.delete(loaded);
        exchangeRepository.flush();
        assertThat(lossItemRepository.findByExchangeId(exchangeId)).isEmpty();
    }
}

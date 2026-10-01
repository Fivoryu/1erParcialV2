package com.diagramador.persistence;

import com.diagramador.domain.XmiExchange;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface XmiExchangeRepository extends JpaRepository<XmiExchange, UUID> {
}

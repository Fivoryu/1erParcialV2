package com.diagramador.persistence;

import com.diagramador.domain.XmiLossItem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface XmiLossItemRepository extends JpaRepository<XmiLossItem, UUID> {

    List<XmiLossItem> findByExchangeId(UUID exchangeId);
}

package com.diagramador.persistence;

import com.diagramador.domain.OperationParameter;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationParameterRepository extends JpaRepository<OperationParameter, UUID> {

    List<OperationParameter> findByOperationId(UUID operationId);
}

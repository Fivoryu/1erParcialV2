package com.diagramador.persistence;

import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.ProjectMemberId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMemberId> {

    List<ProjectMember> findByProjectId(UUID projectId);

    List<ProjectMember> findByUserId(UUID userId);

    Optional<ProjectMember> findByProjectIdAndUserId(UUID projectId, UUID userId);

    @Query(value = "select exists(select 1 from diagrammer.user_account where user_id = :userId and is_platform_admin)",
            nativeQuery = true)
    boolean isPlatformAdmin(@Param("userId") UUID userId);
}

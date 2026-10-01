package com.diagramador.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Invitation row of {@code diagrammer.invitation}.
 */
@Entity
@Table(name = "invitation", schema = "diagrammer")
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "invitation_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invited_by_user_id", nullable = false)
    private UserAccount invitedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invitee_user_id")
    private UserAccount invitee;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false)
    private ProjectMemberRole role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private InvitationStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Invitation() {
    }

    public static Invitation create(Project project, UserAccount invitedBy, UserAccount invitee,
            ProjectMemberRole role) {
        Invitation invitation = new Invitation();
        invitation.project = project;
        invitation.invitedBy = invitedBy;
        invitation.invitee = invitee;
        invitation.role = role;
        invitation.status = InvitationStatus.PENDING;
        OffsetDateTime now = OffsetDateTime.now();
        invitation.createdAt = now;
        invitation.updatedAt = now;
        return invitation;
    }

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public UserAccount getInvitedBy() {
        return invitedBy;
    }

    public UserAccount getInvitee() {
        return invitee;
    }

    public ProjectMemberRole getRole() {
        return role;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public void setStatus(InvitationStatus status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

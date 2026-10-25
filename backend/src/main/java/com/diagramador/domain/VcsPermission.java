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
 * Row of {@code diagrammer.vcs_permission}: one granted action for one user on one diagram.
 *
 * <p>The table only stores grants; absence of a row is a denial. The project role does not
 * disappear when fine permissions exist: a grant adds, a revoke removes only the grant.
 */
@Entity
@Table(name = "vcs_permission", schema = "diagrammer")
public class VcsPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "permission_id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private UmlModel model;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "action", nullable = false)
    private VcsAction action;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by_user_id")
    private UserAccount grantedByUser;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected VcsPermission() {
    }

    public static VcsPermission create(UmlModel model, UserAccount user, VcsAction action, UserAccount grantedByUser) {
        VcsPermission permission = new VcsPermission();
        permission.model = model;
        permission.user = user;
        permission.action = action;
        permission.grantedByUser = grantedByUser;
        return permission;
    }

    public UUID getId() {
        return id;
    }

    public UmlModel getModel() {
        return model;
    }

    public UserAccount getUser() {
        return user;
    }

    public VcsAction getAction() {
        return action;
    }

    public UserAccount getGrantedByUser() {
        return grantedByUser;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}

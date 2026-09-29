package com.diagramador.command;

public enum AuthorizationClassification {
    AUTHORIZED_ONLINE,
    VERIFIED_PRE_REVOCATION,
    VERIFIED_POST_REVOCATION,
    AMBIGUOUS,
    NOT_APPLICABLE
}

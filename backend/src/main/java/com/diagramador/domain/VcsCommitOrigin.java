package com.diagramador.domain;

/** Commit origin, mirroring {@link com.diagramador.command.CommandOrigin} for authorship display. */
public enum VcsCommitOrigin {
    MANUAL,
    AI,
    VOICE,
    IMPORT,
    MERGE
}

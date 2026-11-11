package com.diagramador.xmi;

/**
 * A closed choice applied to the parsed file before it is submitted: rename or skip a classifier or rename the
 * diagram ({@code subject} is the id the issue reported), or keep one of several diagrams.
 */
public record XmiResolution(String subject, Action action, String newName) {

    public enum Action {
        RENAME,
        SKIP,
        KEEP_VIEW
    }
}

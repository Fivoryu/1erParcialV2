package com.diagramador.context;

/**
 * Tells the model who wrote a message. Display names are chosen by users, so they are sanitized before they
 * reach a prompt: no control characters, no square brackets (they delimit the label) and a bounded length.
 */
public final class SpeakerLabel {
    static final int MAX_LENGTH = 60;

    private SpeakerLabel() { }

    /** The label for a person, or null when neither a display name nor a username is usable. */
    public static String label(String displayName, String username) {
        String label = clean(displayName);
        if (label.isEmpty()) {
            label = clean(username);
        }
        return label.isEmpty() ? null : label;
    }

    /** Prefixes a human message with its author, e.g. {@code [Carlos]: hola}. */
    public static String attribute(String label, String body) {
        return label == null ? body : "[" + label + "]: " + body;
    }

    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '[') {
                out.append('(');
            } else if (c == ']') {
                out.append(')');
            } else if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        String collapsed = out.toString().replaceAll(" {2,}", " ").trim();
        return collapsed.length() > MAX_LENGTH ? collapsed.substring(0, MAX_LENGTH).trim() : collapsed;
    }
}

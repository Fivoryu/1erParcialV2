package com.diagramador.generation;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Application-level named check that prevents credentials from being persisted as an opaque target reference. */
final class ConnectionConfigRefValidator {

    static final String CHECK_NAME = "chk_generation_target_connection_config_ref";

    private static final Pattern URI_USER_INFO = Pattern.compile(
            "(?i)\\b[a-z][a-z0-9+.-]*://[^\\s/@]+@[^\\s]+");
    private static final Pattern CREDENTIAL_PAIR = Pattern.compile(
            "(?i)(?:^|[?&;,\\s{])['\"]?(password|passwd|pwd|token|access[_-]?token|refresh[_-]?token"
                    + "|secret|client[_-]?secret|api[_-]?key|private[_-]?key|username|user)['\"]?"
                    + "\\s*[:=]\\s*['\"]?([^'\"&;,\\s}]+)");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?:\\$\\{[A-Za-z_][A-Za-z0-9_]*}|\\{\\{[A-Za-z_][A-Za-z0-9_]*}}|<[^<>]+>)");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----");

    private ConnectionConfigRefValidator() {
    }

    static void validate(String connectionConfigRef) {
        if (looksCredentialLike(connectionConfigRef)) {
            throw new IllegalArgumentException(CHECK_NAME
                    + ": connection_config_ref must be an opaque external locator, not an embedded credential,"
                    + " credential-bearing connection string, bearer token, or private key");
        }
    }

    static boolean looksCredentialLike(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (URI_USER_INFO.matcher(value).find()
                || BEARER_TOKEN.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find()) {
            return true;
        }
        Matcher pair = CREDENTIAL_PAIR.matcher(value);
        while (pair.find()) {
            String credentialValue = pair.group(2);
            if (!PLACEHOLDER.matcher(credentialValue).matches()) {
                return true;
            }
        }
        return false;
    }

    static boolean hasSecretFieldName(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password") || normalized.equals("passwd") || normalized.equals("pwd")
                || normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("credential") || normalized.contains("privatekey")
                || normalized.contains("connectionstring") || normalized.contains("connectionurl")
                || normalized.contains("connectionconfigref") || normalized.contains("vaultref");
    }
}

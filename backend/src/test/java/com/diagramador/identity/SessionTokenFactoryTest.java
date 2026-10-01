package com.diagramador.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SessionTokenFactoryTest {

    private final SessionTokenFactory tokenFactory = new SessionTokenFactory();

    @Test
    void tokensAreUniqueAndUrlSafe() {
        Set<String> tokens = new HashSet<>();
        for (int index = 0; index < 100; index++) {
            String token = tokenFactory.createToken();
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
            tokens.add(token);
        }

        assertThat(tokens).hasSize(100);
    }

    @Test
    void sha256HexIsStableAndLowercase() {
        String firstHash = tokenFactory.sha256Hex("opaque token");
        String secondHash = tokenFactory.sha256Hex("opaque token");

        assertThat(firstHash).isEqualTo(secondHash);
        assertThat(firstHash).hasSize(64).matches(Pattern.compile("[0-9a-f]{64}"));
    }
}

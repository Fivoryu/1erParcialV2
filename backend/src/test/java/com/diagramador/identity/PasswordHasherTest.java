package com.diagramador.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void hashesUseIndependentSaltsAndVerifyTheRawPassword() {
        String raw = "correct horse battery staple";
        String firstHash = passwordHasher.hash(raw);
        String secondHash = passwordHasher.hash(raw);

        assertThat(firstHash).isNotEqualTo(secondHash);
        assertThat(passwordHasher.matches(raw, firstHash)).isTrue();
        assertThat(passwordHasher.matches("incorrect password", firstHash)).isFalse();
    }

    @Test
    void seedOwnerCredentialIsAValidBcryptHash() {
        assertThat(passwordHasher.matches("seed-owner-local",
                "$2a$10$4Bru3384rPJobK6U42fAnes76sFVZ40iyeSvBeDc1SeVpXjoQHocK")).isTrue();
    }
}

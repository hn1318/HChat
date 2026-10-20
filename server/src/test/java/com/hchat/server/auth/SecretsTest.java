package com.hchat.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretsTest {

    @Test
    void hashesDeterministically() {
        assertThat(Secrets.sha256Hex("dev-secret-hchat"))
                .isEqualTo("b54f67ac82a63ae50f7e4295e6eca3d412c9f7b8f9e55f28e1960a3bd5930fec");
    }

    @Test
    void matchesOnlyCorrectSecret() {
        String hash = Secrets.sha256Hex("s3cr3t-value");
        assertThat(Secrets.matches("s3cr3t-value", hash)).isTrue();
        assertThat(Secrets.matches("s3cr3t-valuE", hash)).isFalse();
        assertThat(Secrets.matches(null, hash)).isFalse();
        assertThat(Secrets.matches("s3cr3t-value", null)).isFalse();
    }

    @Test
    void generatesHighEntropySecrets() {
        String a = Secrets.randomSecret();
        String b = Secrets.randomSecret();
        assertThat(a).hasSize(64).isNotEqualTo(b);
        assertThat(Secrets.randomWebhookSecret()).startsWith("whsec_");
    }
}

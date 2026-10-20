package com.hchat.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hchat.server.common.ApiException;
import com.hchat.server.config.HChatProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Token 签发与校验的单元测试。 */
class JwtServiceTest {

    private JwtService newService(long ttlSeconds, String secret) {
        HChatProperties props = new HChatProperties();
        props.getJwt().setSecret(secret);
        props.getJwt().setTtlSeconds(ttlSeconds);
        return new JwtService(props);
    }

    @Test
    void issuesAndParsesToken() {
        JwtService service = newService(3600, "unit-test-secret-key-for-hchat");
        JwtService.IssuedToken issued = service.issue(42L, "app_a", "tenant_a", "ext_1", List.of("chat", "ai"));

        ChatPrincipal principal = service.parse(issued.token());

        assertThat(principal.userId()).isEqualTo(42L);
        assertThat(principal.appId()).isEqualTo("app_a");
        assertThat(principal.tenantId()).isEqualTo("tenant_a");
        assertThat(principal.externalUserId()).isEqualTo("ext_1");
        assertThat(principal.hasScope("chat")).isTrue();
        assertThat(principal.hasScope("admin")).isFalse();
        assertThat(issued.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtService service = newService(0, "unit-test-secret-key-for-hchat");
        String token = service.issue(1L, "app_a", "tenant_a", "ext", List.of("chat")).token();
        Thread.sleep(1100);
        assertThatThrownBy(() -> service.parse(token))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无效");
    }

    @Test
    void rejectsTamperedToken() {
        JwtService service = newService(3600, "unit-test-secret-key-for-hchat");
        String token = service.issue(1L, "app_a", "tenant_a", "ext", List.of("chat")).token();
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsTokenSignedByAnotherSecret() {
        String token = newService(3600, "secret-one-secret-one-secret-one").issue(
                1L, "app_a", "tenant_a", "ext", List.of("chat")).token();
        JwtService other = newService(3600, "secret-two-secret-two-secret-two");

        assertThatThrownBy(() -> other.parse(token)).isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsShortOrLongSecrets() {
        // 密钥由 SHA-256 派生，长度不影响 HS256 合法性
        assertThat(newService(60, "a").issue(1L, "app", "tenant", "ext", List.of("chat")).token()).isNotBlank();
        assertThat(newService(60, "x".repeat(200)).issue(1L, "app", "tenant", "ext", List.of("chat")).token())
                .isNotBlank();
    }
}

package com.hchat.server.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * MySQL 8 + Flyway 集成测试（需要 Docker，无 Docker 时自动跳过）。
 *
 * <p>运行方式：{@code mvn verify -Pit}
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MySqlMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("hchat")
            .withUsername("hchat")
            .withPassword("hchat");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Test
    void migrationsCreateAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
        assertThat(tables).contains(
                "tenants", "apps", "external_users", "conversations", "conversation_members",
                "messages", "read_receipts", "ai_agents", "ai_runs", "files",
                "webhook_endpoints", "webhook_deliveries", "audit_logs", "flyway_schema_history");
        Integer seeded = jdbc.queryForObject("SELECT COUNT(*) FROM apps WHERE id = 'app_demo'", Integer.class);
        assertThat(seeded).isEqualTo(1);
    }

    @Test
    void chatFlowWorksOnMysql() throws Exception {
        String buyer = devToken("mysql_buyer", "买家");
        String conversationId = createConversation(buyer, "mysql-" + System.nanoTime());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(buyer);
        ResponseEntity<JsonNode> sent = rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages", HttpMethod.POST,
                new HttpEntity<>("{\"type\":\"text\",\"content\":{\"text\":\"MySQL 你好\"}}", headers),
                JsonNode.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().path("content").path("text").asText()).isEqualTo("MySQL 你好");

        ResponseEntity<JsonNode> history = rest.exchange(
                "/api/v1/conversations/" + conversationId + "/messages?limit=10", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(history.getBody().path("items")).hasSize(1);
    }

    private String devToken(String externalUserId, String displayName) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"appId\":\"app_demo\",\"externalUserId\":\"" + externalUserId
                + "\",\"displayName\":\"" + displayName + "\"}";
        String json = rest.exchange("/api/v1/auth/dev-token", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody();
        return mapper.readTree(json).path("token").asText();
    }

    private String createConversation(String token, String dedupeKey) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        String body = """
                {"type":"c2c","title":"MySQL 测试","dedupeKey":"%s",
                 "members":[{"externalUserId":"mysql_seller","role":"seller"}]}
                """.formatted(dedupeKey);
        String json = rest.exchange("/api/v1/conversations", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getBody();
        return mapper.readTree(json).path("id").asText();
    }
}

-- =====================================================================
-- HChat 初始 schema
-- 目标：MySQL 8 与 H2(MySQL 模式) 均可执行，便于本地/CI 零依赖跑通
-- 约定：
--   * 主键为雪花 ID（BIGINT），对外序列化为字符串
--   * 所有业务表带 tenant_id / app_id，查询必须带上二者
--   * *_json 列存放 JSON 文本，由应用层序列化
--   * 时间列为 TIMESTAMP(3)，应用内统一按 UTC 处理
-- =====================================================================

CREATE TABLE tenants (
    id         VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE apps (
    id               VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    app_secret_hash  VARCHAR(128) NOT NULL,
    allowed_origins  TEXT,
    config_json      TEXT,
    status           VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at       TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_apps_tenant ON apps (tenant_id);

CREATE TABLE external_users (
    id               BIGINT       NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    app_id           VARCHAR(64)  NOT NULL,
    external_user_id VARCHAR(128) NOT NULL,
    display_name     VARCHAR(128),
    avatar_url       VARCHAR(512),
    metadata_json    TEXT,
    created_at       TIMESTAMP(3) NOT NULL,
    updated_at       TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_external_users_app_user ON external_users (app_id, external_user_id);

CREATE TABLE conversations (
    id                   BIGINT       NOT NULL,
    tenant_id            VARCHAR(64)  NOT NULL,
    app_id               VARCHAR(64)  NOT NULL,
    type                 VARCHAR(16)  NOT NULL,
    title                VARCHAR(256),
    created_by           BIGINT       NOT NULL,
    metadata_json        TEXT,
    dedupe_key           VARCHAR(128),
    last_message_id      BIGINT,
    last_message_at      TIMESTAMP(3),
    last_message_preview VARCHAR(512),
    created_at           TIMESTAMP(3) NOT NULL,
    updated_at           TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_conversations_app_last ON conversations (app_id, last_message_at);
CREATE INDEX idx_conversations_tenant_app ON conversations (tenant_id, app_id);
-- 同一 app 内 dedupe_key 唯一：业务方可用「商品ID+订单ID」避免重复建会话
CREATE UNIQUE INDEX ux_conversations_dedupe ON conversations (app_id, dedupe_key);

CREATE TABLE conversation_members (
    id                   BIGINT       NOT NULL,
    tenant_id            VARCHAR(64)  NOT NULL,
    app_id               VARCHAR(64)  NOT NULL,
    conversation_id      BIGINT       NOT NULL,
    user_id              BIGINT       NOT NULL,
    role                 VARCHAR(16)  NOT NULL DEFAULT 'member',
    joined_at            TIMESTAMP(3) NOT NULL,
    last_read_message_id BIGINT,
    unread_count         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_members_conv_user ON conversation_members (conversation_id, user_id);
CREATE INDEX idx_members_user ON conversation_members (app_id, user_id);

CREATE TABLE messages (
    id              BIGINT       NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    app_id          VARCHAR(64)  NOT NULL,
    conversation_id BIGINT       NOT NULL,
    sender_type     VARCHAR(16)  NOT NULL,
    sender_id       BIGINT,
    type            VARCHAR(16)  NOT NULL,
    content_json    TEXT         NOT NULL,
    client_msg_id   VARCHAR(64),
    status          VARCHAR(16)  NOT NULL DEFAULT 'sent',
    created_at      TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_messages_conv_client ON messages (conversation_id, client_msg_id);
CREATE INDEX idx_messages_conv_id ON messages (conversation_id, id);

CREATE TABLE read_receipts (
    id              BIGINT       NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    app_id          VARCHAR(64)  NOT NULL,
    conversation_id BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    message_id      BIGINT       NOT NULL,
    read_at         TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_read_receipts_conv_user ON read_receipts (conversation_id, user_id);

CREATE TABLE ai_agents (
    id            BIGINT       NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    app_id        VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    provider      VARCHAR(64)  NOT NULL,
    model         VARCHAR(128),
    system_prompt TEXT,
    config_json   TEXT,
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMP(3) NOT NULL,
    updated_at    TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_ai_agents_app ON ai_agents (app_id);

CREATE TABLE ai_runs (
    id              BIGINT        NOT NULL,
    tenant_id       VARCHAR(64)   NOT NULL,
    app_id          VARCHAR(64)   NOT NULL,
    conversation_id BIGINT        NOT NULL,
    message_id      BIGINT,
    agent_id        BIGINT,
    status          VARCHAR(16)   NOT NULL,
    usage_json      TEXT,
    error           VARCHAR(1024),
    created_at      TIMESTAMP(3)  NOT NULL,
    finished_at     TIMESTAMP(3),
    PRIMARY KEY (id)
);
CREATE INDEX idx_ai_runs_conv ON ai_runs (conversation_id);

CREATE TABLE files (
    id          BIGINT        NOT NULL,
    tenant_id   VARCHAR(64)   NOT NULL,
    app_id      VARCHAR(64)   NOT NULL,
    storage_key VARCHAR(512)  NOT NULL,
    url         VARCHAR(1024) NOT NULL,
    file_name   VARCHAR(256),
    mime_type   VARCHAR(128),
    file_size   BIGINT        NOT NULL DEFAULT 0,
    uploader_id BIGINT,
    created_at  TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_files_app ON files (app_id);

CREATE TABLE webhook_endpoints (
    id          BIGINT       NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    app_id      VARCHAR(64)  NOT NULL,
    url         VARCHAR(1024) NOT NULL,
    secret      VARCHAR(128) NOT NULL,
    events_json TEXT,
    status      VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_webhook_endpoints_app ON webhook_endpoints (app_id);

CREATE TABLE webhook_deliveries (
    id           BIGINT       NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    app_id       VARCHAR(64)  NOT NULL,
    endpoint_id  BIGINT       NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    payload_json TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   VARCHAR(1024),
    created_at   TIMESTAMP(3) NOT NULL,
    updated_at   TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_webhook_deliveries_endpoint ON webhook_deliveries (endpoint_id, created_at);

CREATE TABLE audit_logs (
    id            BIGINT       NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    app_id        VARCHAR(64)  NOT NULL,
    actor_id      BIGINT,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(64),
    target_id     VARCHAR(128),
    metadata_json TEXT,
    created_at    TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);
CREATE INDEX idx_audit_logs_app_created ON audit_logs (app_id, created_at);

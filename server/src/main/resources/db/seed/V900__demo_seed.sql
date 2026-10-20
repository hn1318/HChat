-- 演示数据：一个默认租户 + 一个 demo app + 两个内置智能体
-- 仅当 SEED_DEMO=true（dev / compose 演示）时执行

INSERT INTO tenants (id, name, status, created_at)
VALUES ('default', 'Default Tenant', 'active', TIMESTAMP '2026-01-01 00:00:00');

-- appSecret 明文为 dev-secret-hchat（仅演示用，生产请用服务端随机生成的密钥）
INSERT INTO apps (id, tenant_id, name, app_secret_hash, allowed_origins, config_json, status, created_at)
VALUES ('app_demo', 'default', 'HChat Demo', 'b54f67ac82a63ae50f7e4295e6eca3d412c9f7b8f9e55f28e1960a3bd5930fec',
        'http://localhost:5173,http://localhost:5174,http://localhost:8081,http://localhost:8000',
        '{"description":"本地演示应用"}', 'active', TIMESTAMP '2026-01-01 00:00:00');

-- provider / model 留空表示跟随服务端 AI_PROVIDER / AI_MODEL 配置
INSERT INTO ai_agents (id, tenant_id, app_id, name, provider, model, system_prompt, config_json, status,
                       created_at, updated_at)
VALUES (1000000000000001, 'default', 'app_demo', '通用助手', '', '',
        '你是 HChat 的内置助手，回答简洁、准确、有帮助。涉及交易、价格等内容时不要编造，必要时提示用户自行核实。',
        '{}', 'active', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00');

INSERT INTO ai_agents (id, tenant_id, app_id, name, provider, model, system_prompt, config_json, status,
                       created_at, updated_at)
VALUES (1000000000000002, 'default', 'app_demo', '聊天总结助手', '', '',
        '你是聊天记录分析助手。请用 3 条以内的要点总结对话的关键信息（诉求、价格、时间、待办），不要臆造内容。',
        '{}', 'active', TIMESTAMP '2026-01-01 00:00:00', TIMESTAMP '2026-01-01 00:00:00');

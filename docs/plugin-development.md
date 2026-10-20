# 插件开发

HChat 的外部依赖全部通过接口抽象，替换实现不需要改业务代码。

| 接口 | 默认实现 | 替换方式 |
| --- | --- | --- |
| `AIProvider` | `MockAiProvider`、`OpenAiCompatibleProvider` | 实现接口 + 注册为 Spring Bean |
| `StorageProvider` | `LocalStorageProvider`、`S3StorageProvider` | 实现接口，`STORAGE_DRIVER` 选择 |
| `NotificationProvider` | `WebhookNotificationProvider` | 实现接口 + 注册为 Bean |
| `WsBroadcaster` | `InMemoryWsBroadcaster`、`RedisWsBroadcaster` | `REDIS_ENABLED` 切换 |

代码位置：`server/src/main/java/com/hchat/server/plugin/**`。

## 1. AI Provider

```java
public interface AIProvider {
    String name();                                              // 与 ai_agents.provider 对应
    AiChatResult chat(AiChatInput input);                       // 一次性回答
    AiChatResult stream(AiChatInput input, Consumer<String> onDelta);  // 流式（同步阻塞）
}
```

最小实现：

```java
package com.example.hchat;

import com.hchat.server.plugin.ai.*;
import com.hchat.server.plugin.ai.AiModels.*;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class MyVendorProvider implements AIProvider {

    @Override
    public String name() {
        return "my-vendor";
    }

    @Override
    public AiChatResult chat(AiChatInput input) {
        String answer = callMyVendor(input);          // 你的 SDK / HTTP 调用
        return new AiChatResult(answer, Map.of("provider", "my-vendor"));
    }

    @Override
    public AiChatResult stream(AiChatInput input, Consumer<String> onDelta) {
        StringBuilder buffer = new StringBuilder();
        for (String delta : streamFromMyVendor(input)) {
            buffer.append(delta);
            onDelta.accept(delta);                    // 每拿到一片就回调
        }
        return new AiChatResult(buffer.toString(), Map.of());
    }
}
```

要点：

- `stream` 是**阻塞方法**，服务端会把它放到 `taskExecutor` 线程池执行，不要在内部再起线程池
- 抛 `ApiException(ErrorCode.AI_PROVIDER_ERROR, "...")` 会被转成 SSE `error` 事件并落库 `ai_runs.error`
- 用量信息放进 `AiChatResult.usage`，会写入 `ai_runs.usage_json` 并随 `ai.done` 返回

启用：

```yaml
hchat:
  ai:
    provider: my-vendor
```

或给某个智能体单独指定：`POST /api/v1/ai/agents {"name":"…","provider":"my-vendor"}`。

## 2. Storage Provider

```java
public interface StorageProvider {
    record StoredObject(String key, String url, long size) {}
    String name();
    StoredObject put(String key, byte[] data, String contentType);
    byte[] get(String key);
    void delete(String key);
}
```

实现示例（阿里云 OSS / 兼容 S3 的其它服务同理）：

```java
@Component
@ConditionalOnProperty(name = "hchat.storage.driver", havingValue = "oss")
public class OssStorageProvider implements StorageProvider {

    @Override
    public String name() { return "oss"; }

    @Override
    public StoredObject put(String key, byte[] data, String contentType) {
        ossClient.putObject(bucket, key, new ByteArrayInputStream(data));
        return new StoredObject(key, publicBaseUrl + "/" + key, data.length);
    }

    @Override
    public byte[] get(String key) { /* ... */ }

    @Override
    public void delete(String key) { /* ... */ }
}
```

约定：

- `key` 由服务端生成，格式 `{tenantId}/{appId}/yyyy/MM/{uuid}{ext}`
- 返回的 `url` 必须能被前端直接访问（公有读或 CDN 域名）
- 大文件建议直接返回预签名 URL，避免应用进程转发

启用：`STORAGE_DRIVER=oss`。

## 3. Notification Provider

```java
public interface NotificationProvider {
    String name();
    void send(NotificationEvent event);   // record(tenantId, appId, type, payload)
}
```

首期事件：`message.created`、`conversation.created`、`ai.completed`、`notification.offline`。

```java
@Component
public class SmsNotificationProvider implements NotificationProvider {

    @Override
    public String name() { return "sms"; }

    @Override
    public void send(NotificationEvent event) {
        if (!"notification.offline".equals(event.type())) return;
        String phone = (String) event.payload().get("phone");
        smsClient.send(phone, "你有一条新消息");
    }
}
```

注意：`NotificationService` 会遍历所有 provider，单个抛异常只记录日志，不影响主流程。耗时的发送请自己控制超时。

## 4. 广播实现（水平扩展）

```java
public interface WsBroadcaster {
    void sendToUsers(Collection<Long> userIds, WsEvent event);
}
```

- 单机：`InMemoryWsBroadcaster`（默认）
- 多实例：`RedisWsBroadcaster`，事件发到 `hchat:ws:events` 频道，各实例只投递给本地持有的连接
- 自定义（如 Kafka）：实现接口并注册 Bean，同时用 `@ConditionalOnProperty` 排除默认实现

## 5. 前端 SDK 扩展

`core-sdk` 的 `client.on(...)` 支持自定义事件透传：服务端新增 WS 事件时，在 `handleFrame` 的 `switch` 里补一个分支即可；类型定义在 `packages/shared-types/src/index.ts`。

## 6. 测试建议

- Provider 单测不要打真实网络，注入可 mock 的 HTTP 客户端
- 存储 Provider 至少覆盖：写入 → 读取 → 删除、key 目录穿越
- 通知 Provider 至少覆盖：不订阅的事件不投递、异常不影响其他 provider

现有参考：`server/src/test/java/com/hchat/server/service/WebhookServiceTest.java`（本地起 HttpServer 真实验签 + 重试）。

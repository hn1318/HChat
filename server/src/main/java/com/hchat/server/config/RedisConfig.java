package com.hchat.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hchat.server.ws.RedisWsBroadcaster;
import com.hchat.server.ws.WsSender;
import com.hchat.server.ws.WsSessionRegistry;
import io.lettuce.core.ClientOptions;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 可选支持：只用 {@code REDIS_URL} 一个变量即可接入，
 * 用于多实例下的在线状态与 WS 事件广播（Pub/Sub）。
 */
@Configuration
@ConditionalOnProperty(name = "hchat.redis.enabled", havingValue = "true")
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    static RedisStandaloneConfiguration parseUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("REDIS_ENABLED=true 时必须配置 REDIS_URL");
        }
        URI uri = URI.create(url.trim());
        String host = uri.getHost() == null ? "localhost" : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            String[] parts = userInfo.split(":", 2);
            if (parts.length == 2) {
                if (!parts[0].isBlank()) {
                    config.setUsername(parts[0]);
                }
                config.setPassword(RedisPassword.of(parts[1]));
            } else {
                config.setPassword(RedisPassword.of(parts[0]));
            }
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            try {
                config.setDatabase(Integer.parseInt(path.substring(1)));
            } catch (NumberFormatException ignored) {
                log.warn("REDIS_URL 中的 db 不是数字，已忽略: {}", path);
            }
        }
        return config;
    }

    @Bean
    public RedisConnectionFactory redisConnectionFactory(HChatProperties props) {
        RedisStandaloneConfiguration standalone = parseUrl(props.getRedis().getUrl());
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder().autoReconnect(true).build())
                .build();
        return new LettuceConnectionFactory(standalone, client);
    }

    @Bean
    public RedisWsBroadcaster redisWsBroadcaster(StringRedisTemplate redisTemplate,
                                                 WsSessionRegistry registry,
                                                 WsSender sender,
                                                 ObjectMapper objectMapper) {
        return new RedisWsBroadcaster(redisTemplate, registry, sender, objectMapper);
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                      RedisWsBroadcaster listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(RedisWsBroadcaster.CHANNEL));
        return container;
    }
}

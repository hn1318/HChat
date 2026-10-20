package com.hchat.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * HChat 服务端入口。
 *
 * <p>模块化单体：REST + WebSocket + SSE 共用一个进程，外部依赖（AI / 存储 / 通知 / Redis）
 * 全部通过接口插件化，缺省配置下无任何外部依赖即可启动。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class HChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(HChatApplication.class, args);
    }
}

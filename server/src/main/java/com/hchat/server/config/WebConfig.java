package com.hchat.server.config;

import com.hchat.server.plugin.storage.LocalStorageProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源：本地存储模式下把 STORAGE_LOCAL_DIR 挂到 {@code /files/**}。
 * 生产建议由 Nginx / S3 直接对外提供，避免应用进程承担大流量下载。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ObjectProvider<LocalStorageProvider> localStorage;

    public WebConfig(ObjectProvider<LocalStorageProvider> localStorage) {
        this.localStorage = localStorage;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        LocalStorageProvider local = localStorage.getIfAvailable();
        if (local == null) {
            return;
        }
        String location = local.root().toUri().toString();
        registry.addResourceHandler("/files/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}

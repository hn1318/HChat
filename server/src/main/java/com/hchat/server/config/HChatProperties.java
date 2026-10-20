package com.hchat.server.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 统一配置。环境变量采用扁平命名（见 .env.example），内部用层级属性，便于测试覆盖。
 */
@ConfigurationProperties(prefix = "hchat")
public class HChatProperties {

    /** 单租户模式下的默认租户 ID。 */
    private String defaultTenantId = "default";

    /** 对外可访问的服务地址，用于拼装文件 URL、Webhook 等。 */
    private String publicBaseUrl = "http://localhost:8080";

    /** 开发模式：允许使用 /api/v1/auth/dev-token 等仅限本地的便捷能力。 */
    private boolean devMode = false;

    /** 允许跨域的来源，逗号分隔；为空表示只允许 apps.allowed_origins。 */
    private List<String> corsOrigins = new ArrayList<>(List.of("http://localhost:5173"));

    private final Jwt jwt = new Jwt();
    private final Storage storage = new Storage();
    private final Ai ai = new Ai();
    private final Limits limits = new Limits();
    private final Webhook webhook = new Webhook();
    private final Redis redis = new Redis();

    public String getDefaultTenantId() {
        return defaultTenantId;
    }

    public void setDefaultTenantId(String defaultTenantId) {
        this.defaultTenantId = defaultTenantId;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public boolean isDevMode() {
        return devMode;
    }

    public void setDevMode(boolean devMode) {
        this.devMode = devMode;
    }

    public List<String> getCorsOrigins() {
        return corsOrigins;
    }

    public void setCorsOrigins(List<String> corsOrigins) {
        this.corsOrigins = corsOrigins;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Storage getStorage() {
        return storage;
    }

    public Ai getAi() {
        return ai;
    }

    public Limits getLimits() {
        return limits;
    }

    public Webhook getWebhook() {
        return webhook;
    }

    public Redis getRedis() {
        return redis;
    }

    public static class Jwt {
        /** HS256 密钥，至少 32 字节。 */
        private String secret = "hchat-dev-secret-please-change-me-32bytes";
        /** 短期 Token 有效期（秒）。 */
        private long ttlSeconds = 3600;
        private String issuer = "hchat";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }
    }

    public static class Storage {
        /** local | s3 */
        private String driver = "local";
        private String localDir = "./.data/uploads";
        private String s3Endpoint = "";
        private String s3Bucket = "hchat";
        private String s3AccessKey = "";
        private String s3SecretKey = "";
        private String s3Region = "us-east-1";
        private boolean s3PathStyle = true;

        public String getDriver() {
            return driver;
        }

        public void setDriver(String driver) {
            this.driver = driver;
        }

        public String getLocalDir() {
            return localDir;
        }

        public void setLocalDir(String localDir) {
            this.localDir = localDir;
        }

        public String getS3Endpoint() {
            return s3Endpoint;
        }

        public void setS3Endpoint(String s3Endpoint) {
            this.s3Endpoint = s3Endpoint;
        }

        public String getS3Bucket() {
            return s3Bucket;
        }

        public void setS3Bucket(String s3Bucket) {
            this.s3Bucket = s3Bucket;
        }

        public String getS3AccessKey() {
            return s3AccessKey;
        }

        public void setS3AccessKey(String s3AccessKey) {
            this.s3AccessKey = s3AccessKey;
        }

        public String getS3SecretKey() {
            return s3SecretKey;
        }

        public void setS3SecretKey(String s3SecretKey) {
            this.s3SecretKey = s3SecretKey;
        }

        public String getS3Region() {
            return s3Region;
        }

        public void setS3Region(String s3Region) {
            this.s3Region = s3Region;
        }

        public boolean isS3PathStyle() {
            return s3PathStyle;
        }

        public void setS3PathStyle(boolean s3PathStyle) {
            this.s3PathStyle = s3PathStyle;
        }
    }

    public static class Ai {
        /** mock | openai-compatible */
        private String provider = "mock";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private double temperature = 0.7;
        private int maxTokens = 1024;
        private int timeoutSeconds = 120;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Limits {
        private boolean enabled = true;
        /** 每 IP 每秒请求数。 */
        private int ipRps = 60;
        /** 每用户每秒请求数。 */
        private int userRps = 30;
        /** 每 app 每秒请求数。 */
        private int appRps = 300;
        /** 突发桶容量倍数。 */
        private int burst = 2;
        /** 单条消息最大字符数。 */
        private int maxMessageLength = 4000;
        /** 撤回窗口（秒）。 */
        private int recallWindowSeconds = 120;
        /** 默认分页大小。 */
        private int defaultPageSize = 20;
        private int maxPageSize = 100;
        /** 单个文件大小上限（字节）。 */
        private long maxFileBytes = 20L * 1024 * 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getIpRps() {
            return ipRps;
        }

        public void setIpRps(int ipRps) {
            this.ipRps = ipRps;
        }

        public int getUserRps() {
            return userRps;
        }

        public void setUserRps(int userRps) {
            this.userRps = userRps;
        }

        public int getAppRps() {
            return appRps;
        }

        public void setAppRps(int appRps) {
            this.appRps = appRps;
        }

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }

        public int getMaxMessageLength() {
            return maxMessageLength;
        }

        public void setMaxMessageLength(int maxMessageLength) {
            this.maxMessageLength = maxMessageLength;
        }

        public int getRecallWindowSeconds() {
            return recallWindowSeconds;
        }

        public void setRecallWindowSeconds(int recallWindowSeconds) {
            this.recallWindowSeconds = recallWindowSeconds;
        }

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public void setDefaultPageSize(int defaultPageSize) {
            this.defaultPageSize = defaultPageSize;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }

        public void setMaxPageSize(int maxPageSize) {
            this.maxPageSize = maxPageSize;
        }

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }
    }

    public static class Webhook {
        private int timeoutSeconds = 10;
        private int maxAttempts = 5;
        private long initialBackoffMs = 1000;
        private int maxAgeSeconds = 300;

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getInitialBackoffMs() {
            return initialBackoffMs;
        }

        public void setInitialBackoffMs(long initialBackoffMs) {
            this.initialBackoffMs = initialBackoffMs;
        }

        public int getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        public void setMaxAgeSeconds(int maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }

    public static class Redis {
        /** 是否启用 Redis（在线状态、WS 广播、限流共享）。 */
        private boolean enabled = false;
        /** redis://[:password@]host:port[/db] */
        private String url = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }
}

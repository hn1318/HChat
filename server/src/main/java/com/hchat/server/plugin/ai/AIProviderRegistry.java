package com.hchat.server.plugin.ai;

import com.hchat.server.common.ApiException;
import com.hchat.server.common.ErrorCode;
import com.hchat.server.config.HChatProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** AI Provider 注册表：按名字选择实现，未指定时用 AI_PROVIDER 配置的默认值。 */
@Component
public class AIProviderRegistry {

    private final Map<String, AIProvider> providers = new LinkedHashMap<>();
    private final HChatProperties props;

    public AIProviderRegistry(List<AIProvider> available, HChatProperties props) {
        this.props = props;
        available.forEach(p -> providers.put(p.name(), p));
    }

    public AIProvider resolve(String requested) {
        String name = (requested == null || requested.isBlank()) ? props.getAi().getProvider() : requested;
        AIProvider provider = providers.get(name);
        if (provider == null) {
            throw new ApiException(ErrorCode.AI_PROVIDER_DISABLED, "未知的 AI provider: " + name);
        }
        return provider;
    }

    public List<String> available() {
        return List.copyOf(providers.keySet());
    }
}

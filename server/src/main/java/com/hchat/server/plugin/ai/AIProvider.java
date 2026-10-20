package com.hchat.server.plugin.ai;

import com.hchat.server.plugin.ai.AiModels.AiChatInput;
import com.hchat.server.plugin.ai.AiModels.AiChatResult;
import java.util.function.Consumer;

/**
 * AI Provider 插件接口。默认实现是 OpenAI 兼容协议，可替换为任意厂商。
 *
 * <p>{@link #stream} 为同步阻塞方法，调用方负责放到工作线程上执行。
 */
public interface AIProvider {

    String name();

    AiChatResult chat(AiChatInput input);

    AiChatResult stream(AiChatInput input, Consumer<String> onDelta);
}

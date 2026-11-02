package com.demo.cs.agent;

/**
 * 智能客服 Agent 接口（AI Services 注解风格）。
 *
 * <p>★★ 这是 LangChain4j 最核心的用法，值得逐行理解 ★★
 * 你只声明了一个 Java 接口，没有任何实现类——LangChain4j 在运行时用动态代理
 * 把每次方法调用翻译成一次"带工具的 LLM 对话"：
 *
 * 1. @SystemMessage：注入系统提示词（Agent 的人格/职责/规则），从 classpath 加载，
 *    便于版本化管理（Prompt 也是代码资产，改动要走评审）；
 * 2. @MemoryId：方法参数标记为"会话 ID"，框架据此把对话历史（ChatMemory）
 *    自动拼进每次请求——多会话隔离就是靠这个 ID；
 * 3. @UserMessage：{{it}} 占位符替换为用户输入；
 * 4. 配合 AgentConfig 里注册的 Tools（工单 CRUD）与 ToolProvider（MCP 知识库），
 *    LLM 会在需要时自主发起 Function Calling，框架负责执行工具并把结果喂回模型，
 *    直到模型产出最终回答。</p>
 *
 * <p>这就是"Agent 接口层"：对上层（Controller）来说它只是一个普通 Service，
 * 对下层来说它编排了 LLM + 记忆 + 两套工具。</p>
 */
public interface CustomerServiceAgent {

    /**
     * 处理一轮客服对话。
     *
     * @param sessionId 会话 ID（同一 ID 的多轮对话共享记忆，不同 ID 互相隔离）
     * @param message   用户本轮输入
     * @return 助手回复
     */
    @dev.langchain4j.service.SystemMessage(fromResource = "/prompts/customer-service-system.txt")
    String chat(@dev.langchain4j.service.MemoryId String sessionId,
                @dev.langchain4j.service.UserMessage("{{it}}") String message);
}

package com.demo.cs.config;

import com.demo.cs.agent.CustomerServiceAgent;
import com.demo.cs.memory.RedisChatMemoryStore;
import com.demo.cs.tools.TicketTools;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Agent 装配中心：把 LLM + 记忆 + 本地工具 + MCP 远程工具组装成一个 Agent Bean。
 *
 * <p>装配一张"Agent 配方表"：
 * ┌────────────────────────────────────────────────────────┐
 * │ ChatModel        决策大脑（Ollama/DeepSeek/通义 可切换） │
 * │ ChatMemory       会话记忆（Redis 持久化 + 内存降级）     │
 * │ TicketTools      本地工具（工单查询/创建，Tool Calling） │
 * │ McpToolProvider  远程工具（知识库，经 MCP 协议）          │
 * └────────────────────────────────────────────────────────┘
 * AiServices 就是 LangChain4j 的 Controller：把上述四样粘合成
 * CustomerServiceAgent 接口的动态代理实现。</p>
 */
@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    private static final String OLLAMA_DEFAULT_URL = "http://localhost:11434";
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1";
    private static final String DASHSCOPE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    // ==================== 1. 对话模型 ====================

    @Bean
    public ChatModel chatModel(AppProperties props) {
        AppProperties.Llm c = props.getLlm();
        log.info("装配对话模型: provider={}, model={}", c.getProvider(), c.getModel());
        switch (c.getProvider().toLowerCase()) {
            case "ollama" -> {
                return OllamaChatModel.builder()
                        .baseUrl(blank(c.getBaseUrl(), OLLAMA_DEFAULT_URL))
                        .modelName(c.getModel())
                        .temperature(c.getTemperature())
                        .timeout(Duration.ofSeconds(c.getTimeoutSeconds()))
                        .build();
            }
            case "deepseek" -> {
                // 旧模型名 deepseek-chat / deepseek-reasoner 已于 2026-07-24 停用，
                // 现行名称是 deepseek-flash（1M 上下文、支持 Tool Calls、价格最低）
                return openAiCompatible(c, blank(c.getBaseUrl(), DEEPSEEK_URL),
                        blank(c.getModel(), "deepseek-flash"));
            }
            case "qwen" -> {
                return openAiCompatible(c, blank(c.getBaseUrl(), DASHSCOPE_URL),
                        blank(c.getModel(), "qwen-plus"));
            }
            default -> throw new IllegalArgumentException(
                    "不支持的 llm.provider: " + c.getProvider() + "（可选 ollama / deepseek / qwen）");
        }
    }

    private OpenAiChatModel openAiCompatible(AppProperties.Llm c, String baseUrl, String model) {
        String apiKey = (c.getApiKey() != null && !c.getApiKey().isBlank())
                ? c.getApiKey()
                : firstNonBlank(System.getenv("DEEPSEEK_API_KEY"), System.getenv("DASHSCOPE_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("provider=" + c.getProvider() + " 需要 API Key（环境变量或 yml）");
        }
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(c.getTemperature())
                .maxTokens(c.getMaxTokens())
                .timeout(Duration.ofSeconds(c.getTimeoutSeconds()))
                .build();
    }

    // ==================== 2. Agent 装配 ====================

    /**
     * 用 AiServices 组装 Agent 动态代理。
     * MCP 工具是可选的：app.mcp.enabled=false 或 MCP Server 未启动时，
     * Agent 依然可用（只是没有知识库能力）——工具可以"缺位"，Agent 不能"宕机"。
     */
    @Bean
    public CustomerServiceAgent customerServiceAgent(ChatModel chatModel,
                                                     RedisChatMemoryStore memoryStore,
                                                     TicketTools ticketTools,
                                                     AppProperties props) {
        // 每个会话独立一个滑动窗口记忆（多会话隔离的关键：
        // chatMemoryProvider 按 memoryId 创建/复用记忆，记忆内容持久化在 Redis）
        var builder = AiServices.builder(CustomerServiceAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(props.getMemory().getMaxMessages())
                        .chatMemoryStore(memoryStore)
                        .build())
                .tools(ticketTools) // 本地工具：工单查询/创建
                // 工具出错时的显式策略（LangChain4j 1.23 起要求显式声明，默认值未来会变）：
                //  ① 参数错误：把异常文本回给 LLM，让它自己改参数重试——这是 Function Calling
                //     最常见的失败模式（模型把 "E1002" 写成 "1002"），重试比直接失败体验好；
                //  ② 执行错误：只回一句通用话术，不把堆栈/DB 地址/SQL 泄露给模型和会话记忆。
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.sendExceptionMessageToLlm())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler
                        .sendGenericMessageToLlmUnlessVisibleToLlm("工单服务暂时不可用，请稍后再试"));

        // 远程工具：通过 MCP 协议接入知识库（可选）
        if (props.getMcp().isEnabled()) {
            McpToolProvider mcpProvider = buildMcpProvider(props);
            if (mcpProvider != null) {
                builder.toolProvider(mcpProvider);
                log.info("已接入 MCP 知识库工具: {}", props.getMcp().getUrl());
            }
        }
        return builder.build();
    }

    /**
     * 构建 MCP 客户端与工具提供者。
     *
     * <p>McpToolProvider 与 @Tool 的关系：两者都是"给 LLM 提供工具"，
     * 区别是 @Tool 是本进程的 Java 方法，McpToolProvider 背后是【另一个进程/服务】——
     * 框架会先向 MCP Server 调 tools/list 拿到远程工具说明书，转交给 LLM；
     * LLM 决定调用时再经 MCP 协议转发 tools/call。failIfOneServerFails(false)
     * 表示单个工具失败不拖垮整轮对话（降级思维再次出现）。</p>
     *
     * <p><b>传输方式：Streamable HTTP</b>（MCP 2025-03-26 规范引入，已在主流客户端/服务端普及）。
     * 与旧的 HTTP+SSE 双端点方案相比，它只用一个端点（这里是 /mcp）：
     * 客户端 POST JSON-RPC，服务端可以用普通 JSON 直接应答，也可以升级成 SSE 流分块推送；
     * 会话状态通过 Mcp-Session-Id 头传递，不再需要长连接维持"消息回传通道"。
     * 因此不再需要心跳保活，也不会因为 SSE 长连接空闲超时而反复重连。</p>
     */
    private McpToolProvider buildMcpProvider(AppProperties props) {
        try {
            // 只需一个 URL：没有 sse-url / message-endpoint 的配对关系了
            McpTransport transport = new StreamableHttpMcpTransport.Builder()
                    .url(props.getMcp().getUrl())
                    // 单次 HTTP 交互的超时（含 initialize 与 tools/call），
                    // 不是"长连接读超时"，所以不需要为了保活而放大
                    .timeout(Duration.ofSeconds(props.getMcp().getTimeoutSeconds()))
                    .logRequests(true)   // 打印 MCP 协议报文，学习期强烈建议开着
                    .logResponses(true)
                    .build();
            // 注意：McpClient 是接口，实际实现是 DefaultMcpClient（Builder 在其内部）
            McpClient mcpClient = new DefaultMcpClient.Builder()
                    .transport(transport)
                    .clientName("cs-agent")
                    .clientVersion("1.0.0")
                    .build();
            return McpToolProvider.builder()
                    .mcpClients(List.of(mcpClient))
                    .failIfOneServerFails(false)  // 单个 MCP Server 故障不拖垮整轮对话
                    .build();
        } catch (Exception e) {
            // MCP Server 没启动时给出清晰的启动指引而不是让应用崩溃
            log.warn("MCP Server 连接初始化失败（{}），Agent 将不包含知识库工具。"
                    + "请先启动 kb-mcp-server 或将 app.mcp.enabled 设为 false", e.getMessage());
            return null;
        }
    }

    private String blank(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }
}

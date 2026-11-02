# 智能客服 Agent（Spring Boot + LangChain4j + Redis + MCP）

> 转型学习项目二：基于 **AI Services 注解风格**实现 Agent 接口层，整合 **ChatMemory（Redis 持久化、多会话隔离）**、**Tool Calling（工单查询/创建）** 与 **RAG 知识库（经 MCP 协议远程接入）**。配套项目：`E:\Workspaces\kb-mcp-server`（把知识库封装为 MCP Server）。

---

## 一、这个项目能帮你学会什么

| 简历能力点 | 对应源码位置 |
|---|---|
| AI Services 注解式 Agent 接口 | `agent/CustomerServiceAgent`（★必读） |
| Agent 装配（LLM+记忆+工具粘合） | `config/AgentConfig`（★必读） |
| ChatMemory + Redis 持久化 + 多会话隔离 | `memory/RedisChatMemoryStore` |
| Tool Calling（Function Calling） | `tools/TicketTools`（每个注解都有原理注释） |
| MCP 客户端接入 | `AgentConfig.buildMcpProvider` |
| Prompt 工程（系统提示词版本化） | `resources/prompts/customer-service-system.txt` |
| 工具参数防御与降级思维 | `TicketTools.createTicket`、`RedisChatMemoryStore` |

**Agent vs 工作流**（面试高频）：本项目里 LLM 自己决定"查知识库？查工单？建工单？"——这是 Agent；项目一的 RAG 问答链路固定不变——那是工作流。两者都要会写，并说得出取舍。

## 二、架构一览

```
用户 ──HTTP──► ChatController ──► CustomerServiceAgent(AI Services 动态代理)
                                        │
        ┌───────────────────────────────┼──────────────────────────┐
        ▼                    ▼                      ▼
   ChatModel(决策)      ChatMemory(记忆)         工具层
   Ollama/DeepSeek/    Redis 持久化             ├─ TicketTools（本地，Tool Calling）
   通义千问             多会话隔离               │    queryTicket / queryTicketsByUser /
   (Function Calling    TTL 7天                 │    searchTickets / createTicket
    循环的发起者)        Redis挂→内存降级         └─ McpToolProvider（远程，MCP 协议）
                                                  └─► kb-mcp-server:8082（知识库检索）
工单数据：H2 文件库 ./data/csagent（预置 24 条工单，h2-console 可查）
```

## 三、项目结构（每个包的作用）

```
cs-agent/
├── pom.xml                        # Spring Boot 3.5 + LangChain4j 1.0.1 + Redis + H2
├── docker-compose.yml             # 可选：一键拉起 Redis（不启动也能跑，自动降级）
└── src/main/
    ├── resources/
    │   ├── application.yml        # 全部配置（模型/记忆/MCP 开关）
    │   ├── prompts/customer-service-system.txt  # 系统提示词（Prompt 资产版本化）
    │   └── static/index.html      # 聊天测试页面
    └── java/com/demo/cs/
        ├── CsAgentApplication.java
        ├── agent/
        │   └── CustomerServiceAgent.java  # ★ AI Services 注解式接口（无实现类，动态代理）
        ├── config/
        │   ├── AppProperties.java         # yml → 强类型配置
        │   ├── AgentConfig.java           # ★ AiServices 装配 + MCP 客户端
        │   └── TicketSeedRunner.java      # 启动预置 24 条测试工单（幂等）
        ├── memory/
        │   └── RedisChatMemoryStore.java  # ★ Redis 持久化记忆 + 内存降级
        ├── ticket/
        │   ├── Ticket.java / TicketRepository.java   # 工单实体与 JPA 仓库
        ├── tools/
        │   └── TicketTools.java           # ★ Tool Calling 工具集（@Tool 注解）
        └── web/
            └── ChatController.java        # 对话接口 + 记忆调试接口
```

## 四、需要你准备的事项

1. **JDK 17+** 与 **Maven 3.9+**（或 VSCode + Extension Pack for Java）。
2. **大模型（三选一，同项目一）**：默认 Ollama（`ollama pull qwen2.5:7b`）；或配置 DeepSeek/Qwen API Key（改 `app.llm.provider` + 环境变量）。**本项目不需要 Embedding 模型**（知识库检索在 MCP Server 侧用关键词检索）。
3. **可选**：Docker（拉起 Redis）；启动 kb-mcp-server（不启动则 Agent 没有知识库能力，其余功能正常）。

## 五、快速开始（推荐启动顺序）

```bash
# 0.（可选但推荐）先启动 MCP Server，让 Agent 拿到知识库工具
cd E:\Workspaces\kb-mcp-server && mvn spring-boot:run
# 看到 "Tomcat started on port 8082" 即成功

# 1.（可选）启动 Redis，体验记忆持久化
cd E:\Workspaces\cs-agent && docker compose up -d

# 2. 启动 Agent
cd E:\Workspaces\cs-agent && mvn spring-boot:run
# 看到 "已接入 MCP 知识库工具" 与 "预置测试工单 24 条" 即成功

# 3. 打开聊天页
#    http://localhost:8081
```

### 云端模式（DeepSeek，不用装 Ollama）

项目内置了 `application-cloud.yml`，激活 profile 即可切到 DeepSeek：

```bash
# 1) 设置 Key（PowerShell，永久生效用 setx）
$env:DEEPSEEK_API_KEY="sk-你的key"
# 2) 用 cloud profile 启动
java -jar target\cs-agent-1.0.0.jar --spring.profiles.active=cloud
# 或 mvn spring-boot:run -Dspring-boot.run.profiles=cloud
```

启动日志出现 `装配对话模型: provider=deepseek, model=deepseek-flash` 即为生效。
`deepseek-chat` / `deepseek-reasoner` 这两个旧模型名已于 2026-07-24 停用，本项目默认已改用 `deepseek-flash`。

### 建议的验证对话（直接在页面里试）

1. `报销需要在几天内提交？` → 观察日志：LLM 先调 MCP 工具 `search_knowledge_base`，回答带来源。
2. `帮我查一下我报的打印机工单` → 回答工单号 TCK-1001 与状态（说明它学会了先问工号或用关键词搜索）。
3. `帮我提交一个工单：我的显示器坏了，工号 E1002，比较急` → 调 `createTicket`，回复里带新工单号；到 `http://localhost:8081/h2-console`（JDBC URL 填 `jdbc:h2:file:./data/csagent;MODE=MySQL`，用户名 sa）查 TICKET 表验证。
4. `我刚才问了什么？` → 依赖 ChatMemory 的多轮记忆。
5. 重启应用后再问 `我刚才问了什么？` → 记忆还在（Redis 持久化生效）；没启动 Redis 时这一步记忆会丢（降级为内存）。
6. 新开浏览器标签页问同样的问题 → 新会话不知道上一个标签聊过什么（多会话隔离）。

### 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | body `{sessionId, message}` → `{reply, costMs}` |
| GET | `/api/chat/history/{sessionId}` | 查看会话记忆（调试用） |
| DELETE | `/api/chat/{sessionId}` | 清空会话记忆 |
| GET | `/api/chat/sessions` | 全部会话 ID（验证持久化） |
| GET | `/api/tickets?userId=E1001` | 直接查工单（对照工具结果） |

## 六、建议的源码阅读顺序

1. `agent/CustomerServiceAgent` —— 10 行接口看懂 AI Services 注解风格
2. `tools/TicketTools` —— Tool Calling 原理注释 + 参数防御
3. `config/AgentConfig` —— 记忆/工具/MCP 怎么粘合成一个 Agent
4. `memory/RedisChatMemoryStore` —— 持久化 + 降级（生产思维）
5. `resources/prompts/customer-service-system.txt` —— 提示词即资产

打开 `application.yml` 把 `logging.level.dev.langchain4j` 改成 `DEBUG` 再跑一轮对话，可以在控制台看到完整的 Function Calling 报文（LLM 返回的 ToolExecutionRequest 原文）——这是理解 Agent 最直观的方式。

## 七、编译验证结论与已知限制

- **编译验证**：已用 Maven 3.9.9 + JDK 26（`--release 17`）完成 `mvn clean compile` 与 `mvn package` 全量验证，BUILD SUCCESS；产出 `target/cs-agent-1.0.0.jar` 可直接 `java -jar` 运行。
- LangChain4j `1.0.1`（BOM，其中 langchain4j-mcp 为 `1.0.1-beta6`）+ Spring Boot `3.5.3`。本版本实际 API 已核实并在代码注释中说明：`McpClient` 是接口（用 `new DefaultMcpClient.Builder()` 构建）、`ChatMemoryStore` 位于 `dev.langchain4j.store.memory.chat` 包且只有 getMessages/updateMessages/deleteMessages 三个方法。
- 若后续升级 LangChain4j 大版本，`HttpMcpTransport` / `McpToolProvider` 构造 API 可能变化，按新版 Javadoc 调整 `AgentConfig.buildMcpProvider` 即可。
- 工单号用内存自增，重启后从 1000 继续，可能与历史数据冲突——生产应换数据库序列/发号器（作为学习讨论点保留）。
- H2 文件库同进程并发写入有限制，多实例部署需换 MySQL/PostgreSQL。

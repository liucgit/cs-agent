# 智能客服 Agent

基于 **LangChain4j AI Services** 的对话式客服服务：一个接口方法对外提供多轮对话能力，内部由大模型自主决定「查知识库 / 查工单 / 建工单」，并携带 Redis 持久化的会话记忆。

- 工具分两类：本地工单工具（`@Tool`，直接操作 H2）与远程知识库工具（经 **MCP 协议**调用 `kb-mcp-server`）。
- 记忆可持久化：Redis 存会话历史，TTL 7 天；Redis 不可用时自动降级为进程内存，服务不中断。
- 无 key 也能跑：默认走本地 Ollama；也可切 DeepSeek / 通义千问。

---

## 一、项目概览

| 项 | 说明 |
|---|---|
| 职责 | 面向员工的客服对话服务：回答制度问题、查询与创建工单 |
| 对外形态 | HTTP 服务 + 内置聊天页面 |
| 端口 | 8081 |
| 运行依赖 | JDK 17+、Maven 3.9+；Redis（可选）；`kb-mcp-server`（可选） |
| 数据存储 | H2 文件库 `./data/csagent`（预置 24 条工单） |
| 代码规模 | `src/main/java` 共 11 个类 |

**核心实现索引**

| 功能 | 代码位置 |
|---|---|
| Agent 接口（无实现类，运行期动态代理） | `agent/CustomerServiceAgent` |
| Agent 装配（模型 + 记忆 + 工具 + MCP） | `config/AgentConfig` |
| 会话记忆持久化与降级 | `memory/RedisChatMemoryStore` |
| 工单工具（4 个） | `tools/TicketTools` |
| 系统提示词 | `resources/prompts/customer-service-system.txt` |
| 对话接口与记忆调试接口 | `web/ChatController` |

---

## 二、技术栈

| 组件 | 版本 | 作用 |
|---|---|---|
| Spring Boot | 3.5.3 | Web、JPA、配置管理 |
| LangChain4j | 1.23.0（BOM，其中 `langchain4j-mcp` 1.23.0-beta33） | AI Services、Tool Calling、MCP 客户端 |
| Redis | Spring Data Redis | 会话记忆持久化（可选） |
| H2 | Boot 内置 | 工单存储（文件模式，重启不丢） |
| 前端 | 原生 HTML/JS | 内置聊天测试页（`static/index.html`） |

---

## 三、架构

```
浏览器 ──HTTP──► ChatController ──► CustomerServiceAgent（AI Services 动态代理）
                                          │
        ┌─────────────────────────────────┼──────────────────────────────┐
        ▼                                 ▼                              ▼
  ChatModel（决策）                 ChatMemory（记忆）                工具层
  Ollama / DeepSeek / 通义          Redis 持久化 + TTL 7 天      ├─ TicketTools（本地）
  发起 Function Calling 循环         滑动窗口 20 条消息           │    4 个 @Tool 方法
                                     Redis 不可用 → 内存降级      └─ McpToolProvider（远程）
                                                                       └─► kb-mcp-server:8082 /mcp
工单数据：H2 文件库 ./data/csagent（预置 24 条，h2-console 可查）
```

一次对话的处理过程：

1. `ChatController` 收到 `{sessionId, message}`，调用 `CustomerServiceAgent.chat(sessionId, message)`。
2. 框架按 `sessionId` 取出该会话历史，与系统提示词、用户消息一起组装成请求。
3. 模型返回 `tool_calls` 时，框架执行对应工具（本地或远程 MCP），把结果回填给模型，循环直到模型给出最终回答。
4. 本轮问答写回 `ChatMemory`（Redis），返回 `{reply, costMs}`。

---

## 四、目录结构

```
cs-agent/
├── pom.xml                        # Boot 3.5.3 + LangChain4j 1.23.0 + Redis + H2
├── docker-compose.yml             # 可选：一键拉起 Redis
└── src/main/
    ├── resources/
    │   ├── application.yml        # 模型 / 记忆 / MCP 全部配置
    │   ├── application-cloud.yml  # cloud profile：DeepSeek 对话模型
    │   ├── prompts/customer-service-system.txt   # 系统提示词
    │   └── static/index.html      # 聊天测试页面
    └── java/com/demo/cs/
        ├── CsAgentApplication.java
        ├── agent/CustomerServiceAgent.java        # AI Services 接口
        ├── config/
        │   ├── AppProperties.java                 # yml → 强类型配置
        │   ├── AgentConfig.java                   # AiServices 装配 + MCP 客户端
        │   └── TicketSeedRunner.java              # 启动预置 24 条工单（幂等）
        ├── memory/RedisChatMemoryStore.java       # Redis 记忆 + 内存降级
        ├── ticket/                                # 工单实体与 JPA 仓库
        ├── tools/TicketTools.java                 # 工单工具集
        └── web/
            ├── ChatController.java                # 对话与记忆调试接口
            └── GlobalExceptionHandler.java
```

---

## 五、Agent 装配

`CustomerServiceAgent` 只有一个方法，没有实现类：

```java
public interface CustomerServiceAgent {
    @SystemMessage(fromResource = "/prompts/customer-service-system.txt")
    String chat(@MemoryId String sessionId, @UserMessage("{{it}}") String message);
}
```

三个注解分别承担：系统提示词注入（从 classpath 加载，便于版本化管理）、会话 ID 绑定（框架据此隔离多会话记忆）、用户输入占位。运行时 LangChain4j 用动态代理把方法调用翻译成一次「带工具的模型对话」。

`AgentConfig` 负责把四样东西装配成一个 Agent：

| 组成 | 实现 |
|---|---|
| 对话模型 | 按 `app.llm.provider` 装配 `OpenAiChatModel`（DeepSeek / 通义）或 `OllamaChatModel` |
| 记忆 | `MessageWindowChatMemory`（窗口 20 条）+ 自定义 `ChatMemoryStore`（Redis） |
| 本地工具 | `TicketTools` 的 4 个 `@Tool` 方法 |
| 远程工具 | `McpToolProvider`，内部用 `StreamableHttpMcpTransport` 连 `app.mcp.url` |

工具异常也在此声明，避免异常直接冒到用户：

- `toolArgumentsErrorHandler` → 参数错误时把原因回给模型，让它自行纠正参数。
- `toolExecutionErrorHandler` → 执行失败时回一句「工单服务暂时不可用」，模型据此改口而不是崩掉整轮对话。

---

## 六、会话记忆

`RedisChatMemoryStore` 实现了 LangChain4j 的 `ChatMemoryStore`：

| 设计点 | 做法 | 好处 |
|---|---|---|
| 存储键 | `csagent:memory:{sessionId}` | 一个会话一份历史，天然隔离，也便于按前缀列出全部会话 |
| 过期 | TTL 7 天（`app.memory.ttl-days`），每次写入刷新 | 会话不会无限堆积，Redis 内存可控 |
| 窗口 | 保留最近 20 条消息（`app.memory.max-messages`） | 控制 token 成本，避免长会话稀释上下文 |
| 降级 | Redis 操作抛异常时写入进程内 `Map`，并打 WARN | 缓存型依赖不应让主服务起不来 |
| 序列化 | JSON 数组存 `ChatMessage` | 可直接在 Redis 里查看会话内容，便于排查 |

每次更新以「整份覆盖 + 重设 TTL」的方式写入，同一会话在 Redis 中始终只有一份最新快照；读取失败返回空列表，让对话从零开始而不是抛错。

---

## 七、工具层

### 本地工具（`TicketTools`）

| 工具 | 用途 | 设计考虑 |
|---|---|---|
| `queryTicket` | 按工单号查详情 | 描述里明确「记不清工单号时改用 queryTicketsByUser」，减少模型乱传参 |
| `queryTicketsByUser` | 按工号查该员工全部工单 | 覆盖「我的报修到哪一步了」这类没有单号的问法 |
| `searchTickets` | 按关键词搜标题/描述 | 用户既没工号也没单号时的兜底路径 |
| `createTicket` | 创建工单 | 入参做必填校验；工号缺失时返回提示而非抛异常，让模型追问用户 |

工具返回结构化的短文本（工单号、状态、优先级、标题），而不是实体对象——模型读文本更稳，也避免把内部字段全部暴露出去。

### 远程工具（MCP）

`kb-mcp-server` 通过 MCP 协议提供 `search_knowledge_base` 与 `get_document` 两个工具，`cs-agent` 侧不需要写任何适配代码：

- 传输：`StreamableHttpMcpTransport`，单端点 `http://localhost:8082/mcp`，超时 60s。
- 工具发现：客户端懒加载，启动时不一定立刻发 `tools/list`，首次对话需要知识库时才建立会话。
- 开关：`app.mcp.enabled=false` 时完全不接知识库，Agent 只保留工单能力。

知识库放在独立进程，是为了让检索能力被多个调用方复用（其他 Agent、Cursor、Claude Desktop 都能接同一个 Server），同时把知识库的迭代与客服服务的发布解耦。

---

## 八、模型接入

三选一，改 `application.yml` 的 `app.llm.provider`：

| 方案 | 配置 | 说明 |
|---|---|---|
| Ollama（默认） | `provider: ollama`、`model: qwen2.5:7b` | 本地推理，免费；需先 `ollama pull qwen2.5:7b` |
| DeepSeek | `provider: deepseek`、`model: deepseek-flash` | OpenAI 兼容协议；读环境变量 `DEEPSEEK_API_KEY` |
| 通义千问 | `provider: qwen` | 读环境变量 `DASHSCOPE_API_KEY` |

云端模式已内置 `application-cloud.yml`，无需改动主配置：

```bash
$env:DEEPSEEK_API_KEY="sk-你的key"          # PowerShell；永久生效用 setx
java -jar target\cs-agent-1.0.0.jar --spring.profiles.active=cloud
# 或 mvn spring-boot:run -Dspring-boot.run.profiles=cloud
```

启动日志出现 `装配对话模型: provider=deepseek, model=deepseek-flash` 即生效。

---

## 九、运行与验证

### 启动顺序

```bash
# 0.（可选）知识库 MCP Server，让 Agent 拿到知识库工具
cd E:\Workspaces\kb-mcp-server && mvn spring-boot:run     # 端口 8082

# 1.（可选）Redis，体验记忆持久化
cd E:\Workspaces\cs-agent && docker compose up -d

# 2. 启动 Agent
cd E:\Workspaces\cs-agent && mvn spring-boot:run          # 端口 8081
# 成功标志：
#   装配对话模型: provider=ollama, model=qwen2.5:7b
#   已接入 MCP 知识库工具: http://localhost:8082/mcp
#   预置测试工单 24 条（覆盖 6 名员工 × 4 状态 × 4 优先级）

# 3. 打开聊天页面
#    http://localhost:8081
```

MCP Server 未启动时，日志只打印一条 WARN（`MCP Server 连接初始化失败...Agent 将不包含知识库工具`），服务照常启动，工单类对话不受影响。

### 验证用例

| 输入 | 期望行为 |
|---|---|
| `报销需要在几天内提交？` | 调用 MCP 工具 `search_knowledge_base`，回答带知识来源 |
| `帮我查一下我报的打印机工单` | 先追问工号或用 `searchTickets` 兜底，返回 TCK-1001 及状态 |
| `帮我提交一个工单：显示器坏了，工号 E1002，比较急` | 调用 `createTicket`，返回新工单号；可在 h2-console 的 TICKET 表核对 |
| `我刚才问了什么？` | 依据 `ChatMemory` 正确复述上一轮 |
| 重启应用后再问同上 | Redis 模式下记忆仍在；未启 Redis 时记忆丢失（内存降级） |
| 新开浏览器标签再问 | 新 `sessionId` 不知道旧会话内容，验证多会话隔离 |

h2-console：`http://localhost:8081/h2-console`，JDBC URL `jdbc:h2:file:./data/csagent;MODE=MySQL`，用户名 `sa`，空密码。

### 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/chat` | body `{sessionId, message}` → `{reply, costMs}` |
| GET | `/api/chat/history/{sessionId}` | 查看会话记忆（调试用） |
| DELETE | `/api/chat/{sessionId}` | 清空会话记忆 |
| GET | `/api/chat/sessions` | 列出全部会话 ID（验证持久化） |
| GET | `/api/tickets?userId=E1001` | 直接查工单（对照工具结果） |

排查工具调用时，把 `logging.level.dev.langchain4j` 调成 `DEBUG`，控制台会打印完整的 `ToolExecutionRequest` 与工具返回，能直接看到模型选了哪个工具、传了什么参数。

---

## 十、设计取舍

| 决策 | 理由 |
|---|---|
| 用 AI Services 接口封装 Agent | 工具调用循环、消息拼装、记忆读写由框架负责；业务代码只保留「一个方法 + 提示词 + 工具」，Agent 行为调整以改提示词为主 |
| 知识库走 MCP，不做进程内调用 | 检索能力被多个客户端复用，知识库迭代不触发客服服务重新发布 |
| 记忆存 Redis 而非纯内存 | 客服会话常跨设备、跨重启，内存记忆会让「上下文」随机消失；Redis 不可用时仍能降级 |
| 工单存 H2 文件库 | 单机零部署即可跑通完整链路（建单 → 查单）；JPA 抽象使换 MySQL 只改连接配置 |
| 系统提示词放独立资源文件 | 提示词属于需要评审、需要回溯的资产，放在 `.txt` 里比硬编码进 Java 更可控 |
| 工具错误处理器显式配置 | 工具失败时把可读信息回给模型，让对话继续，而不是把异常抛给用户 |

---

## 十一、已知限制

- **工单号用内存自增**，重启后从 1000 继续，可能与历史数据冲突；生产应改用数据库序列或独立发号器。
- **H2 并发写入能力有限**，多实例部署需换 MySQL / PostgreSQL，并把 `ddl-auto` 从 `update` 改为 `validate` + 迁移脚本。
- **记忆窗口固定 20 条**，长会话会丢弃早期上下文；需要「长期记忆」时应引入摘要压缩或向量召回。
- **Redis 降级后的进程内存储不是线程安全的**（普通 `Map`）：Redis 长时间不可用且并发较高时，记忆可能出现覆盖。生产可换成 `ConcurrentHashMap` 或干脆让服务快速失败。
- **MCP 未做鉴权**，仅适合本机或内网联调；对外暴露需在 MCP 层接入 OAuth 2.1。
- 未接入流式输出（SSE / WebSocket），首字延迟取决于模型；接入流式只需把接口返回值改为 `TokenStream`。

---

## 十二、项目组合中的位置

| 项目 | 角色 | 端口 |
|---|---|---|
| `kb-rag-system` | RAG 引擎：文档入库、混合检索、引用溯源、效果评测 | 8080 |
| `cs-agent` | 智能客服 Agent：对话、记忆、工具调用（本项目） | 8081 |
| `kb-mcp-server` | 知识库 MCP Server：把检索能力标准化对外发布 | 8082 |

三者的关系：`cs-agent` 是唯一同时持有「模型决策」与「工具执行」的项目；知识库能力通过对端 `kb-mcp-server` 获得，与 `kb-rag-system` 无代码耦合。任一项目可单独启动，缺失依赖时功能降级而不是启动失败。

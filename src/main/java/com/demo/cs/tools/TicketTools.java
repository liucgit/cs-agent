package com.demo.cs.tools;

import com.demo.cs.ticket.Ticket;
import com.demo.cs.ticket.TicketRepository;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工单工具集（Tool Calling / Function Calling 的"工具侧"实现）。
 *
 * <p>★★ Tool Calling 的工作原理（面试必考）★★
 * 1. 启动时：LangChain4j 扫描本类所有 @Tool 方法，生成"工具说明书"（名称/描述/参数 JSON Schema）；
 * 2. 对话时：说明书随请求发给 LLM → LLM 判断需要哪个工具 → 返回一个
 *    ToolExecutionRequest（JSON：工具名 + 参数）；
 * 3. 框架自动调用对应 Java 方法（就是下面这些普通方法），把返回字符串作为
 *    ToolExecutionResultMessage 追加进对话；
 * 4. LLM 拿到工具结果，继续推理，直到生成面向用户的最终回答。
 * 注意：工具方法就是你熟悉的 Spring Bean 方法，没有任何黑魔法。</p>
 *
 * <p>工程要点：
 * - @Tool 的 description 写给 LLM 看，要像写 API 文档一样认真——
 *   LLM 什么时候调、怎么填参数全靠它；
 * - 工具返回 String（喂回模型的文本），要精炼，别把大对象整个 toString；
 * - 创建工单属于"改变外部世界"的操作：工具内部要做参数校验（最小信任 LLM）。</p>
 */
@Component
public class TicketTools {

    private static final Logger log = LoggerFactory.getLogger(TicketTools.class);

    private final TicketRepository repository;
    /** 工单号自增序列（学习项目用内存自增；生产应换成发号器/数据库序列） */
    private final AtomicLong seq = new AtomicLong(1000);

    public TicketTools(TicketRepository repository) {
        this.repository = repository;
    }

    /**
     * 按工单号查询工单详情。
     */
    @Tool("根据工单号查询工单详情，包括状态、优先级、标题和描述。工单号格式如 TCK-1001。用户记不清完整工单号时不要用这个工具，改用 queryTicketsByUser。")
    public String queryTicket(@P("工单号，例如 TCK-1001") String ticketId) {
        log.info("[工具调用] queryTicket: {}", ticketId);
        return repository.findById(ticketId == null ? "" : ticketId.trim().toUpperCase())
                .map(Ticket::toLine)
                .orElse("未找到工单 " + ticketId + "，请确认工单号是否正确。");
    }

    /**
     * 按员工工号查询工单列表。
     */
    @Tool("根据员工工号查询该员工的全部工单列表。当用户想看自己的工单/报修进度但没给工单号时使用。")
    public String queryTicketsByUser(@P("员工工号，例如 E1001") String userId) {
        log.info("[工具调用] queryTicketsByUser: {}", userId);
        List<Ticket> tickets = repository.findByUserIdOrderByCreatedAtDesc(userId == null ? "" : userId.trim().toUpperCase());
        if (tickets.isEmpty()) {
            return "工号 " + userId + " 名下没有工单。";
        }
        StringBuilder sb = new StringBuilder("共 " + tickets.size() + " 个工单：\n");
        tickets.forEach(t -> sb.append(t.toLine()).append('\n'));
        return sb.toString();
    }

    /**
     * 关键词搜索工单（跨员工）。
     */
    @Tool("按关键词搜索工单（匹配标题或描述），用于用户提供不了工号和工单号、但记得问题内容时。")
    public String searchTickets(@P("搜索关键词，例如 打印机") String keyword) {
        log.info("[工具调用] searchTickets: {}", keyword);
        List<Ticket> tickets = repository.searchByKeyword(keyword == null ? "" : keyword.trim());
        if (tickets.isEmpty()) {
            return "没有找到与「" + keyword + "」相关的工单。";
        }
        StringBuilder sb = new StringBuilder("共 " + tickets.size() + " 个相关工单：\n");
        tickets.stream().limit(10).forEach(t -> sb.append(t.toLine()).append('\n'));
        return sb.toString();
    }

    /**
     * 创建工单。
     *
     * <p>这是"写操作"工具：参数在工具内做二次校验（不信任 LLM 填的参数），
     * 并生成工单号回执给用户。</p>
     */
    @Tool("为员工创建新工单（报修/投诉/咨询等）。调用前必须先向用户确认问题描述；用户未提供工号时先询问工号，不要编造。")
    public String createTicket(@P("员工工号，例如 E1001") String userId,
                               @P("工单标题，10字以内概括问题") String title,
                               @P("问题描述，尽量包含现象、影响、发生时间") String description,
                               @P("优先级: LOW / MEDIUM / HIGH / URGENT，默认 MEDIUM") String priority) {
        log.info("[工具调用] createTicket: user={} title={}", userId, title);

        // ---- 参数防御性校验（LLM 可能填错，工具侧是最后防线）----
        if (userId == null || userId.isBlank() || !userId.trim().toUpperCase().matches("E\\d{3,6}")) {
            return "创建失败：需要有效的员工工号（格式如 E1001），请向用户确认后再创建。";
        }
        if (title == null || title.isBlank() || description == null || description.isBlank()) {
            return "创建失败：标题和描述不能为空，请补充后再创建。";
        }
        String pr = (priority == null || priority.isBlank()) ? "MEDIUM" : priority.trim().toUpperCase();
        if (!List.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(pr)) {
            pr = "MEDIUM";
        }

        String id = "TCK-" + seq.incrementAndGet();
        LocalDateTime now = LocalDateTime.now();
        Ticket ticket = new Ticket(id, userId.trim().toUpperCase(),
                title.trim(), description.trim(), "OPEN", pr, now, now);
        repository.save(ticket);
        return "工单创建成功。工单号: " + id + "，状态: OPEN，优先级: " + pr + "。请把工单号告知用户。";
    }
}

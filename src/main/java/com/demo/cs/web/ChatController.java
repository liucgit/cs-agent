package com.demo.cs.web;

import com.demo.cs.agent.CustomerServiceAgent;
import com.demo.cs.memory.RedisChatMemoryStore;
import com.demo.cs.ticket.Ticket;
import com.demo.cs.ticket.TicketRepository;
import dev.langchain4j.data.message.ChatMessage;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对话与调试接口。
 *
 * <p>注意 Controller 有多"薄"：一行 agent.chat(...) 就是完整的一轮 Agent 执行
 * （含记忆加载、工具调用循环、回复生成）。复杂度都在装配层，这是好设计。</p>
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final CustomerServiceAgent agent;
    private final RedisChatMemoryStore memoryStore;
    private final TicketRepository ticketRepository;

    public ChatController(CustomerServiceAgent agent,
                          RedisChatMemoryStore memoryStore,
                          TicketRepository ticketRepository) {
        this.agent = agent;
        this.memoryStore = memoryStore;
        this.ticketRepository = ticketRepository;
    }

    /** 对话请求体 */
    public record ChatRequest(String sessionId, String message) {
    }

    /** 发送一条消息（主入口）。sessionId 由前端生成（如 UUID），多会话互不串扰 */
    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody ChatRequest request) {
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        long start = System.currentTimeMillis();
        String reply = agent.chat(request.sessionId(), request.message());
        return Map.of(
                "reply", reply,
                "costMs", String.valueOf(System.currentTimeMillis() - start)
        );
    }

    /** 查看某会话的记忆（调试多会话隔离/记忆持久化的利器） */
    @GetMapping("/chat/history/{sessionId}")
    public List<Map<String, String>> history(@PathVariable String sessionId) {
        List<ChatMessage> messages = memoryStore.getMessages(sessionId);
        return messages.stream()
                .map(m -> Map.of("type", m.type().name(), "text", m.toString()))
                .toList();
    }

    /** 清空某会话的记忆 */
    @DeleteMapping("/chat/{sessionId}")
    public Map<String, String> clear(@PathVariable String sessionId) {
        memoryStore.deleteMessages(sessionId);
        return Map.of("status", "cleared", "sessionId", sessionId);
    }

    /** 查看全部会话 ID（验证 Redis 持久化：重启应用后这里还有数据） */
    @GetMapping("/chat/sessions")
    public List<String> sessions() {
        return memoryStore.listSessionIds();
    }

    /** 查看某员工工单（跳过 Agent 直接查库，用于对照验证工具的正确性） */
    @GetMapping("/tickets")
    public List<Ticket> tickets(@RequestParam String userId) {
        return ticketRepository.findByUserIdOrderByCreatedAtDesc(userId.toUpperCase());
    }
}

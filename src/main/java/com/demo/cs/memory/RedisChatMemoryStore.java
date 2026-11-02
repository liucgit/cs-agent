package com.demo.cs.memory;

import com.demo.cs.config.AppProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 Redis 的会话记忆持久化（实现 LangChain4j 的 ChatMemoryStore 接口）。
 *
 * <p>为什么记忆要用 Redis？
 * - Agent 重启后对话不能丢（用户体验）；
 * - 多实例部署时，用户会话可能被负载均衡到任意实例，记忆必须放共享存储；
 * - key 设计：csagent:memory:{sessionId}，一个会话一个 Redis List/字符串，
 *   便于 TTL 过期与多会话隔离。</p>
 *
 * <p>序列化方案：LangChain4j 自带的 ChatMessageSerializer 能把
 * UserMessage/AiMessage/ToolExecutionResultMessage 双向 JSON 化，
 * 保证"工具调用记录"这类结构化消息也能完整还原。</p>
 *
 * <p>★★ 工程亮点（C4 降级路径）★★：Redis 连不上时自动降级为进程内 Map 存储，
 * Agent 功能不中断，只打 WARN 日志。生产里这叫"有损降级"：
 * 可用性优先，重启丢记忆的代价远小于整个客服不可用。</p>
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    /** Redis key 前缀（多会话隔离的命名空间） */
    private static final String KEY_PREFIX = "csagent:memory:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    /** 降级用的进程内备份存储（Redis 故障时的兜底） */
    private final Map<String, List<ChatMessage>> fallback = new HashMap<>();
    /** 标记 Redis 是否已故障（避免每次操作都刷屏打日志） */
    private volatile boolean redisDown = false;

    public RedisChatMemoryStore(StringRedisTemplate redis, AppProperties props) {
        this.redis = redis;
        this.ttl = Duration.ofDays(props.getMemory().getTtlDays());
    }

    /**
     * 列出全部会话 ID（管理接口用）：扫描 Redis 前缀，降级时扫内存 Map。
     * 注意：1.0.1 的 ChatMemoryStore 接口不含此方法，作为本类的扩展方法提供。
     */
    public List<String> listSessionIds() {
        try {
            Set<String> keys = redis.keys(KEY_PREFIX + "*");
            redisDown = false;
            return keys.stream().map(k -> k.substring(KEY_PREFIX.length())).toList();
        } catch (Exception e) {
            redisDown = true;
            log.warn("Redis 不可用，getIds 降级为内存: {}", e.getMessage());
            return new ArrayList<>(fallback.keySet());
        }
    }

    /** 读取某个会话的全部消息（每次对话轮次都会调用） */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + memoryId);
            redisDown = false;
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            // 反序列化：JSON 字符串 -> List<ChatMessage>（含工具调用消息）
            return new ArrayList<>(ChatMessageDeserializer.messagesFromJson(json));
        } catch (Exception e) {
            redisDown = true;
            log.warn("Redis 不可用，getMessages 降级为内存: {}", e.getMessage());
            return new ArrayList<>(fallback.getOrDefault(String.valueOf(memoryId), List.of()));
        }
    }

    /** 覆盖式写入某个会话的全部消息（LangChain4j 的语义：每次整窗替换） */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = KEY_PREFIX + memoryId;
        // messagesToJson：消息列表 -> 单个 JSON 字符串
        String json = ChatMessageSerializer.messagesToJson(messages);
        try {
            // 写入 + 设置 TTL（7 天不活跃的会话记忆自动过期，防止 Redis 无限膨胀）
            redis.opsForValue().set(key, json, ttl);
            redisDown = false;
        } catch (Exception e) {
            redisDown = true;
            log.warn("Redis 不可用，updateMessages 降级为内存: {}", e.getMessage());
        } finally {
            // 无论 Redis 是否成功，都同步维护内存兜底副本（内存量小：每会话最多 20 条）
            fallback.put(String.valueOf(memoryId), new ArrayList<>(messages));
        }
    }

    /** 删除某个会话的记忆（用户点"清空会话"时调用） */
    @Override
    public void deleteMessages(Object memoryId) {
        fallback.remove(String.valueOf(memoryId));
        try {
            redis.delete(KEY_PREFIX + memoryId);
            redisDown = false;
        } catch (Exception e) {
            redisDown = true;
            log.warn("Redis 不可用，deleteMessages 降级为内存: {}", e.getMessage());
        }
    }
}

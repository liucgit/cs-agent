package com.demo.cs.ticket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 工单号发号器（Redis INCR 方案）。
 *
 * <p>为什么不用进程内 AtomicLong？
 * - 重启即重置：Redis 里已有 TCK-1005，应用重启后内存计数器回到初始值，新单立刻撞主键；
 * - 多实例必撞：两个副本各有一个内存计数器，各自发号必然重复；
 * Redis INCR 是跨进程的原子自增（单命令原子性），天然解决这两点。</p>
 *
 * <p>序列水位保证：Redis key 不存在时（首次启动 / Redis 被清空），
 * 用「数据库中已有最大工单号」初始化计数器，保证新号一定大于所有存量数据。
 * INCR 对不存在的 key 从 0 开始自增，若不初始化会发出 TCK-1 这类与旧数据规则脱节的号。</p>
 *
 * <p>★★ 降级路径（与 RedisChatMemoryStore 同一模式）★★：
 * Redis 连不上时降级为进程内 AtomicLong 兜底，建单功能不中断；
 * 代价是降级期间多实例可能撞号、重启丢水位——可用性优先，
 * Redis 恢复后自动回到 Redis 发号（key 会被重新初始化为当时库内最大号）。</p>
 */
@Component
public class TicketIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(TicketIdGenerator.class);

    /** Redis 计数器 key（命名空间与 csagent:memory: 保持同风格） */
    private static final String SEQ_KEY = "csagent:ticket:seq";

    /** 序列起始水位（与种子数据 TCK-1001 起步对齐） */
    private static final long INITIAL_SEQ = 1000;

    private final StringRedisTemplate redis;
    private final TicketRepository repository;
    /** Redis 故障时的进程内兜底计数器 */
    private final AtomicLong fallback = new AtomicLong(INITIAL_SEQ);

    public TicketIdGenerator(StringRedisTemplate redis, TicketRepository repository) {
        this.redis = redis;
        this.repository = repository;
    }

    /**
     * 生成下一个工单号，格式与既有约定一致：TCK-xxxx。
     */
    public String nextId() {
        try {
            // key 不存在（首次启动 / Redis 被清空）时，以库内最大工单号为水位初始化。
            // setIfAbsent 保证多实例并发初始化时只有一个生效，且值相同（都读同一个库），无竞态风险。
            if (Boolean.FALSE.equals(redis.hasKey(SEQ_KEY))) {
                redis.opsForValue().setIfAbsent(SEQ_KEY, String.valueOf(maxTicketNo()));
            }
            Long n = redis.opsForValue().increment(SEQ_KEY);
            if (n == null) {
                throw new IllegalStateException("Redis INCR 返回 null");
            }
            return "TCK-" + n;
        } catch (Exception e) {
            log.warn("Redis 发号失败，降级为进程内自增: {}", e.getMessage());
            return "TCK-" + nextFallback();
        }
    }

    /** 降级发号：以「库内最大号」与当前兜底计数器的较大者为基准，避免重启后与存量数据撞号 */
    private long nextFallback() {
        long floor = Math.max(maxTicketNo(), INITIAL_SEQ);
        fallback.accumulateAndGet(floor, Math::max);
        return fallback.incrementAndGet();
    }

    /**
     * 当前库内最大工单号（数值部分）。
     * 注意不能直接用字符串 max：TCK-9999 与 TCK-10000 字典序会反（"9" > "1"），必须解析成数字比较。
     * DB 也查不到时回退 INITIAL_SEQ，保证本方法永不抛异常（它会被降级路径调用）。
     */
    private long maxTicketNo() {
        try {
            return repository.findAllTicketIds().stream()
                    .mapToLong(TicketIdGenerator::parseTicketNo)
                    .max()
                    .orElse(INITIAL_SEQ);
        } catch (Exception e) {
            log.warn("查询存量工单号失败，回退初始水位 {}: {}", INITIAL_SEQ, e.getMessage());
            return INITIAL_SEQ;
        }
    }

    /** 解析 "TCK-1001" 中的数字部分；非标准格式按 0 处理（不参与水位计算） */
    private static long parseTicketNo(String id) {
        if (id == null || !id.startsWith("TCK-")) {
            return 0;
        }
        try {
            return Long.parseLong(id.substring("TCK-".length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

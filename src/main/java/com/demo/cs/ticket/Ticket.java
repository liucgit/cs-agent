package com.demo.cs.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 工单实体（Agent 的 Tool Calling 会直接读写这张表）。
 *
 * <p>特意没用 Lombok：转型学习阶段建议手写 getter/setter，
 * 看清楚 JPA 实体的每个组成部分。</p>
 */
@Entity
@Table(name = "ticket")
public class Ticket {

    /** 工单号（业务主键，格式 TCK-1001，也是给 LLM 看的"人话主键"） */
    @Id
    @Column(length = 20)
    private String id;

    /** 员工工号（如 E1001），按人查工单的依据 */
    @Column(nullable = false, length = 20)
    private String userId;

    /** 工单标题 */
    @Column(nullable = false, length = 100)
    private String title;

    /** 问题描述 */
    @Column(nullable = false, length = 1000)
    private String description;

    /** 状态：OPEN(待处理) / PROCESSING(处理中) / RESOLVED(已解决) / CLOSED(已关闭) */
    @Column(nullable = false, length = 20)
    private String status;

    /** 优先级：LOW / MEDIUM / HIGH / URGENT */
    @Column(nullable = false, length = 10)
    private String priority;

    /** 创建时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 最近更新时间 */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Ticket() {
    }

    public Ticket(String id, String userId, String title, String description,
                  String status, String priority, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.title = title;
        this.description = description;
        this.status = status;
        this.priority = priority;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /** 给 LLM 看的单行摘要（工具返回值，控制 token 占用） */
    public String toLine() {
        return id + " | " + status + " | " + priority + " | " + title + " | " + description;
    }
}

package com.demo.cs.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 工单仓库（Spring Data JPA 标准用法，你熟悉的部分）。
 * Agent 的工具方法会调用这里的查询——Tool Calling 的"工具"本质上就是普通业务代码。
 */
public interface TicketRepository extends JpaRepository<Ticket, String> {

    List<Ticket> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Ticket> findByUserIdAndStatus(String userId, String status);

    /** 简单关键词检索（标题/描述模糊匹配），供 Agent 的 queryTickets 工具使用 */
    @Query("select t from Ticket t where lower(t.title) like lower(concat('%', :keyword, '%')) " +
           "or lower(t.description) like lower(concat('%', :keyword, '%')) order by t.createdAt desc")
    List<Ticket> searchByKeyword(@Param("keyword") String keyword);
}

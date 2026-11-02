package com.demo.cs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 智能客服 Agent 启动类。
 *
 * <p>一个对话能体现的 Agent 全景（建议配 README 的使用示例一起看）：
 * 用户："帮我查一下上次报的打印机坏了的工单，再看看报销几天内要提交"
 * LLM 决策：① 调本地工具 queryTickets(关键词匹配工单) → ② 通过 MCP 调远程工具
 * search_knowledge_base(查知识库) → ③ 综合两者组织回答。整个过程由
 * Function Calling 循环驱动，你写的只是"工具"和"提示词"，控制流交给框架。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CsAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CsAgentApplication.class, args);
    }
}

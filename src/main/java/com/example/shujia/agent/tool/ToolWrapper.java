package com.example.shujia.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolWrapper {
    private static final Logger log = LoggerFactory.getLogger(ToolWrapper.class);
    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    private final Map<String, AgentTool> tools = new HashMap<>();

    @Autowired
    public ToolWrapper(List<AgentTool> toolList) {
        for (AgentTool tool : toolList) {
            this.tools.put(tool.getName(), tool);
            log.info("注册工具：{}-{}", tool.getName(), tool.getdescription());
        }
    }

    private String executeWithAudit(String toolName, Map<String, Object> arguments) throws Exception {
        AgentTool tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("未找到工具：" + toolName);
        }
        long start = System.nanoTime();
        auditLog.info("执行工具：{}，参数：{}", toolName, arguments);
        try {
            String result = tool.execute(arguments);
            long cost = System.nanoTime() - start;
            auditLog.info("工具执行完成：{}，耗时：{}纳秒", toolName, cost);
            return result;
        } catch (Exception e) {
            auditLog.error("工具执行失败：{}，耗时：{}纳秒", toolName, System.nanoTime() - start, e);
            throw e;
        }
    }

    @Tool(description = "查询当前系统时间。当用户询问现在几点、今天日期时使用。")
    public String queryCurrentTime() throws Exception {
        return executeWithAudit("query_current_time", Map.of());
    }

    @Tool(description = "计算数学表达式，例如 (100+200)*0.8")
    public String calculator(String expression) throws Exception {
        return executeWithAudit("calculator", Map.of("expression", expression));
    }

    @Tool(description = "根据订单号查询订单状态和物流信息")
    public String orderQuery(String orderId) throws Exception {
        return executeWithAudit("OrderQueryTool", Map.of("orderId", orderId));
    }
}

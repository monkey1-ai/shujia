package com.example.shujia.agent.tool.impl;

import com.example.shujia.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Component
public class TimeQueryTool implements AgentTool {
    private static final Logger log= LoggerFactory.getLogger(TimeQueryTool.class);

    @Override
    public String getName() {
        return "query_current_time";
    }

    @Override
    public String getdescription() {
        return "查询当前系统时间。当用户询问现在几点、今天日期时使用。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        return schema;
    }


    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return "当前时间是：" + now;
    }
}

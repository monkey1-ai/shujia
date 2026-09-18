package com.example.shujia.agent.tool.impl;

import com.example.shujia.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.HashMap;
import java.util.Map;

@Component
public class CalculatorTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

    @Override
    public String getName() {
        return "calculator";
    }

    @Override
    public String getdescription() {
        return "计算器工具，用于计算数学表达式";

    }
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> expression = new HashMap<>();
        expression.put("type", "string");
        expression.put("description", "数学表达式，例如 (100+200)*0.8");
        properties.put("expression", expression);
        schema.put("properties", properties);
        schema.put("required", new String[]{"expression"});
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String expression = (String) arguments.get("expression");

        // 安全校验：只允许数字和运算符，防止注入
        if (expression == null || !expression.matches("[0-9+\\-*/().\\s]+")) {
            throw new IllegalArgumentException("表达式包含非法字符，仅支持数字和 + - * / ( )");
        }

        log.info("【工具调用】计算表达式: {}", expression);
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript");
        Object result = engine.eval(expression);
        return "计算结果：" + expression + " = " + result;
    }

    @Override
    public String getPermissionLevel() {
        return AgentTool.super.getPermissionLevel();
    }
}
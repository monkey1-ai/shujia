package com.example.shujia.agent.tool.impl;

import com.example.shujia.agent.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class OrderQueryTool implements AgentTool {
    private static final Logger log= LoggerFactory.getLogger(OrderQueryTool.class);

    @Override
    public String getName() {
        return "OrderQueryTool";
    }

    @Override
    public String getdescription() {
        return "根据订单号查询订单状态和物流信息。当用户询问订单进度、物流状态时使用此工具。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String,Object> schema=new HashMap<>();
        schema.put("type","object");
        Map<String,Object> properties=new HashMap<>();
        Map<String,Object> orderId=new HashMap<>();
        orderId.put("type","string");
        orderId.put("description","订单号");
        properties.put("orderId",orderId);
        schema.put("properties",properties);
        schema.put("required",new String[]{"orderId"});
        return schema;

    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String orderId=(String)arguments.get("orderId");
        if(orderId==null){
            throw new Exception("orderId is null");
        }
        log.info("orderId is {}",orderId);
        return switch (orderId){
            case "123456"->"订单已发货，预计明天送达";
            case "789012"->"订单已签收";
            default->"订单不存在";
        };

    }
}

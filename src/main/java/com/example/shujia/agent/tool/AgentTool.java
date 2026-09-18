package com.example.shujia.agent.tool;

import java.util.Map;
public interface AgentTool{
    String getName();
    String getdescription();

    Map<String,Object> getParameterSchema();
    String execute(Map<String,Object> arguments)throws Exception;
    default String getPermissionLevel(){
        return "user";
    }
}


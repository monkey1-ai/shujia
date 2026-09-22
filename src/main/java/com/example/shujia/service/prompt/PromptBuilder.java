package com.example.shujia.service.prompt;

import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {
    public String build(String context, String question, String history) {
        return """
        当用户问到你是谁时可以不参考文献。
        参考信息：
        ---
        %s
        ---
        用户问题：%s
        历史记录：%s
        要求：
        1. 必须基于参考信息回答，禁止编造。
        2. 如果需要工具，返回上面的 JSON 格式。
        3. 你可以根据自己本身的知识解答，碰见需要调取文档的东西再调取文档内容。
        4. 回答要简洁具体。
        """.formatted(context, question, history);
    }

}

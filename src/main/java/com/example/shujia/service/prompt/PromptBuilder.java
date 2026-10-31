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
                             1. 只把参考信息当作事实依据，不要整段引用，用自己的话重新组织。
                             2. 如果参考信息里的原句不超过 10 个字并且是专有名词（人名、型号、数值），可以保留；其余内容必须改写。
                             
                             4. 回答控制在 3-5 句，不要罗列。
                             5. 不要回答用户问题以外的内容。
                             6. 不要直接说参考信息如何如何，输出要根据自己的语言，要自然。
                             文档以外的可以回答，但是文档中有的一定要答。
        """.formatted(context, question, history);
    }
}

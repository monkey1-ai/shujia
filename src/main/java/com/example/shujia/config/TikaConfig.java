package com.example.shujia.config;

import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class TikaConfig {
    @Bean
    public Parser tikaParser() {
        // 1. 使用 AutoDetectParser（比默认的更加鲁棒）
        return new AutoDetectParser();
    }

    @Bean
    public ParseContext tikaParseContext(Parser parser) {
        ParseContext context = new ParseContext();
        // 2. 关键：把解析器注入上下文，防止嵌套文件无限递归（设定最大嵌套深度）
        context.set(Parser.class, parser);
        return context;
    }
}

package com.example.shujia.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端首页转发：Vite 打包产物落在 static/index.html，
 * Spring Boot 默认不把 index.html 作为根路径欢迎页，这里显式转发一次。
 */
@Controller
public class IndexController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}

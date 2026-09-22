package com.example.shujia.client.butternController;

import com.example.shujia.service.ConversationMemoryService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cleanMemory")
public class cleanMemoryController {
    private final ConversationMemoryService conversationMemoryService;

    public cleanMemoryController(ConversationMemoryService conversationMemoryService) {
        this.conversationMemoryService = conversationMemoryService;
    }

    @PostMapping("/clean")
    public String cleanMemory(@RequestParam String sessionId) {
        conversationMemoryService.clear(sessionId);
        return "success";
    }
}
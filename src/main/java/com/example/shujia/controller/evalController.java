package com.example.shujia.controller;

import com.example.shujia.dto.response.Result;
import com.example.shujia.service.EvalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/eval")
public class evalController {
    @Autowired
    private EvalService evalService;

    @GetMapping("/run")
    public Result<EvalService.EvalResult> runEval(@RequestParam(defaultValue = "3") int topK) {
        EvalService.EvalResult result = evalService.runEvaluation(topK);
        return Result.success(result);
    }
}

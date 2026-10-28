package com.example.shujia.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QueryRequest {
    @NotBlank(message = "查询内容不能为空")
    private String question;
    @Min(value = 1, message = "topk不能小于1")
    @Max(value = 10, message = "topk不能大于10")
    private Integer topk=3;
    private String sessionId;
    private String messageId;
}

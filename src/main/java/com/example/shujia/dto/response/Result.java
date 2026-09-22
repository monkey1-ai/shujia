package com.example.shujia.dto.response;

import com.example.shujia.util.TraceIdUtil;
import lombok.Data;

@Data
public class Result<T> {
    private Integer code;
    private String message;
    private String traceId;
    private T data;

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("success");
        result.setData(data);
        result.setTraceId(TraceIdUtil.get());
        return result;
    }

    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setTraceId(TraceIdUtil.get());
        return result;
    }
}

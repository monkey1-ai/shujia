package com.example.shujia.exception;
import com.example.shujia.dto.response.Result;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log= LoggerFactory
            .getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex){
        String msg= Objects.requireNonNull(ex.
                        getBindingResult().
                        getFieldError()).
                getDefaultMessage();
        log.warn("参数校验失败：{}" , msg);
        return Result.error(400,msg);
    }
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception ex){
        log.warn("系统异常：{}" , ex.getMessage());
        return Result.error(500,ex.getMessage());
    }
}

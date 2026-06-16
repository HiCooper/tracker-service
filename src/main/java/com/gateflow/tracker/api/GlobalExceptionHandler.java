package com.gateflow.tracker.api;

import com.gateflow.tracker.api.dto.EventResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理:把校验/解析/未捕获异常映射为稳定的 {@link EventResponse} 形状,
 * 避免向客户端泄漏堆栈,并返回正确的 HTTP 状态码。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<EventResponse> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.badRequest().body(EventResponse.builder()
                .code(HttpStatus.BAD_REQUEST.value()).message(msg).build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<EventResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(EventResponse.builder()
                .code(HttpStatus.BAD_REQUEST.value()).message("malformed request body").build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<EventResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(EventResponse.builder()
                        .code(HttpStatus.INTERNAL_SERVER_ERROR.value())
                        .message("internal error").build());
    }
}

package com.demo.cs.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 全局异常处理：把异常翻译成对前端友好的 JSON 错误响应。
 *
 * <p>工程规范：接口层不要把异常堆栈直接抛给前端；对外只给笼统信息，
 * 完整堆栈留在服务端日志里，避免泄露内部实现细节。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 静态资源不存在（典型场景：浏览器自动请求 /favicon.ico 标签页图标）。
     * 正常的 404，静默返回，不打 ERROR 堆栈污染日志。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> staticResourceNotFound(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "资源不存在: " + e.getResourcePath()));
    }

    /** 其余未知异常：记录完整堆栈，对外只给笼统信息 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> serverError(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "系统异常: " + e.getMessage()));
    }
}

package com.lab.sipim.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> statusFailure(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",error.getReason()==null?"请求未完成":error.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidArgument(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error",
                error.getMessage() == null ? "invalid request" : error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(HttpServletRequest request) {
        String uri = (request != null && request.getRequestURI() != null) ? request.getRequestURI() : "";
        if (uri.contains("/login") || uri.contains("/register")) {
            return ResponseEntity.badRequest().body(Map.of("error", LoginController.MISSING));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "malformed request body"));
    }
}

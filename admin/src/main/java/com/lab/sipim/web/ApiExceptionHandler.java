package com.lab.sipim.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

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

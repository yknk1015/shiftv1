package com.example.shiftv1.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

import org.springframework.beans.factory.annotation.Autowired;

@ControllerAdvice
public class WebErrorAdvice {

    private static final Logger logger = LoggerFactory.getLogger(WebErrorAdvice.class);
    @Autowired(required = false)
    private com.example.shiftv1.common.error.ErrorLogBuffer errorLogBuffer;

    @ExceptionHandler(Exception.class)
    public Object handleAnyException(HttpServletRequest request, Exception ex) {
        String uri = request.getRequestURI();
        logger.error("Unhandled exception on {}", uri, ex);
        try { if (errorLogBuffer != null) errorLogBuffer.addError("Unhandled exception on " + uri, ex); } catch (Exception ignore) {}
        if (uri != null && uri.startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.failure("内部エラーが発生しました。時間をおいて再度お試しください。"));
        }
        ModelAndView mav = new ModelAndView("error");
        mav.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        mav.addObject("message", "内部エラーが発生しました。ダッシュボードへ戻ってやり直してください。");
        return mav;
    }
}

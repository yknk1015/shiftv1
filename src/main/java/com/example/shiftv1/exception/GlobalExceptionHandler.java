package com.example.shiftv1.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        ErrorResponse errorResponse = new ErrorResponse(
                "バリデーションエラー",
                "入力データに問題があります",
                errors,
                LocalDateTime.now()
        );
        
        logger.warn("バリデーションエラーが発生しました: {}", errors);
        return ResponseEntity.badRequest().body(errorResponse);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateException(IllegalStateException ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                "ビジネスロジックエラー",
                ex.getMessage(),
                null,
                LocalDateTime.now()
        );
        
        logger.error("ビジネスロジックエラーが発生しました: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                "引数エラー",
                ex.getMessage(),
                null,
                LocalDateTime.now()
        );
        
        logger.warn("引数エラーが発生しました: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(errorResponse);
    }

    @ExceptionHandler(ScheduleGenerationException.class)
    public ResponseEntity<ErrorResponse> handleScheduleGenerationException(ScheduleGenerationException ex, WebRequest request) {
        logger.error("Schedule generation error: {}", ex.getMessage(), ex);
        
        ErrorResponse errorResponse = new ErrorResponse(
                "SCHEDULE_GENERATION_ERROR",
                ex.getMessage(),
                null,
                LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException ex, WebRequest request) {
        logger.warn("Constraint violation: {}", ex.getMessage());
        
        String message = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining(", "));
        
        ErrorResponse errorResponse = new ErrorResponse(
                "CONSTRAINT_VIOLATION",
                message,
                null,
                LocalDateTime.now()
        );
        return ResponseEntity.badRequest().body(errorResponse);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException ex, WebRequest request) {
        logger.warn("Business error: {}", ex.getMessage());
        
        ErrorResponse errorResponse = new ErrorResponse(
                ex.getErrorCode(),
                ex.getMessage(),
                null,
                LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                "内部サーバーエラー",
                "予期しないエラーが発生しました",
                null,
                LocalDateTime.now()
        );
        
        logger.error("予期しないエラーが発生しました", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    public record ErrorResponse(
            String error,
            String message,
            Map<String, String> details,
            LocalDateTime timestamp
    ) {}
}

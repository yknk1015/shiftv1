package com.example.shiftv1.common;

import java.util.Collections;
import java.util.Map;

/**
 * 共通APIレスポンスラッパー。
 * <p>
 * フロントエンドでは {@code success} フラグと {@code data} フィールドを前提とした
 * ハンドリングを行っているため、全エンドポイントで統一して利用する。
 */
public record ApiResponse<T>(boolean success, String message, T data, Map<String, Object> meta) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, null, data, Collections.emptyMap());
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data, Collections.emptyMap());
    }

    public static <T> ApiResponse<T> success(String message, T data, Map<String, Object> meta) {
        return new ApiResponse<>(true, message, data, meta == null ? Collections.emptyMap() : Map.copyOf(meta));
    }

    public static <T> ApiResponse<T> failure(String message) {
        return new ApiResponse<>(false, message, null, Collections.emptyMap());
    }
}

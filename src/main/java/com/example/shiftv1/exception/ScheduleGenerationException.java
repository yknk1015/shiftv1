package com.example.shiftv1.exception;

public class ScheduleGenerationException extends RuntimeException {

    private final String errorCode;
    private final Object[] parameters;

    public ScheduleGenerationException(String message) {
        super(message);
        this.errorCode = "SCHEDULE_GENERATION_ERROR";
        this.parameters = new Object[0];
    }

    public ScheduleGenerationException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "SCHEDULE_GENERATION_ERROR";
        this.parameters = new Object[0];
    }

    public ScheduleGenerationException(String errorCode, String message, Object... parameters) {
        super(message);
        this.errorCode = errorCode;
        this.parameters = parameters;
    }

    public ScheduleGenerationException(String errorCode, String message, Throwable cause, Object... parameters) {
        super(message, cause);
        this.errorCode = errorCode;
        this.parameters = parameters;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object[] getParameters() {
        return parameters;
    }
}

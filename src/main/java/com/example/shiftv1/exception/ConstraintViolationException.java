package com.example.shiftv1.exception;

public class ConstraintViolationException extends RuntimeException {

    private final String constraintType;
    private final Object constraintValue;

    public ConstraintViolationException(String message) {
        super(message);
        this.constraintType = "GENERAL";
        this.constraintValue = null;
    }

    public ConstraintViolationException(String message, String constraintType, Object constraintValue) {
        super(message);
        this.constraintType = constraintType;
        this.constraintValue = constraintValue;
    }

    public ConstraintViolationException(String message, Throwable cause) {
        super(message, cause);
        this.constraintType = "GENERAL";
        this.constraintValue = null;
    }

    public ConstraintViolationException(String message, String constraintType, Object constraintValue, Throwable cause) {
        super(message, cause);
        this.constraintType = constraintType;
        this.constraintValue = constraintValue;
    }

    public String getConstraintType() {
        return constraintType;
    }

    public Object getConstraintValue() {
        return constraintValue;
    }
}

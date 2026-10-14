package com.ysh.studiotool.executor;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

public class BaseResult implements Serializable {

    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    protected final int EMPTY_DEFAULT = -2026;

    private String state;
    private String message;
    private String error;

    public BaseResult(String state, String message, String error) {
        this.state = state;
        this.message = message;
        this.error = error;
    }

    public static BaseResult success(String message, String error) {
        return new BaseResult(SUCCESS, message, error);
    }

    public static BaseResult failed(String message, String error) {
        return new BaseResult(FAILED, message, error);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return SUCCESS.equals(state);
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}

package com.ysh.studiotool.executor;

public class ExecutionResult extends BaseResult {

    private long durationMs = EMPTY_DEFAULT;
    private int exitCode = EMPTY_DEFAULT;

    public ExecutionResult(String success, String message, String error, long durationMs, int exitCode) {
        super(success, message, error);
        this.durationMs = durationMs;
        this.exitCode = exitCode;
    }

    public static ExecutionResult success(String message, long durationMs, int exitCode) {
        return new ExecutionResult(BaseResult.SUCCESS, message, null, durationMs, exitCode);
    }

    public static ExecutionResult error(String error, long durationMs) {
        return new ExecutionResult(BaseResult.FAILED, null, error, durationMs, -1);
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setExitCode(int exitCode) {
        this.exitCode = exitCode;
    }
}

package com.entertainment.tester;

public class ApiResponse {
    public final int statusCode;
    public final String body;
    public final String error;

    public ApiResponse(int statusCode, String body, String error) {
        this.statusCode = statusCode;
        this.body = body;
        this.error = error;
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
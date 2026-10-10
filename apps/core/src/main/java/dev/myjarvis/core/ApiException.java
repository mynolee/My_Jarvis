package dev.myjarvis.core;

public final class ApiException extends RuntimeException {
    final int status; final String code;
    ApiException(int status, String code) { super(code); this.status=status; this.code=code; }
    static ApiException bad() { return new ApiException(400,"INVALID_REQUEST"); }
    static ApiException missing() { return new ApiException(404,"NOT_FOUND"); }
}

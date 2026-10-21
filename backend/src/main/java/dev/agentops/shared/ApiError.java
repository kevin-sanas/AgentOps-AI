package dev.agentops.shared;

public final class ApiError extends RuntimeException {
    private final int status;
    private final String code;
    public ApiError(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static ApiError badRequest(String code) { return new ApiError(400, code); }
    public static ApiError notFound() { return new ApiError(404, "RESOURCE_NOT_FOUND"); }
}

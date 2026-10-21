package dev.agentops.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class Problems {
    private Problems() {}
    public static ProblemDetail detail(int status, String code, HttpServletRequest request) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(HttpStatus.valueOf(status).getReasonPhrase());
        detail.setDetail("Request could not be completed.");
        detail.setProperty("code", code);
        detail.setProperty("traceId", RequestIds.of(request));
        return detail;
    }
    public static void write(HttpServletRequest request, HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        // Only generated UUIDs and server-owned codes are interpolated here.
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + HttpStatus.valueOf(status).getReasonPhrase()
            + "\",\"status\":" + status + ",\"code\":\"" + code + "\",\"traceId\":\"" + RequestIds.of(request) + "\"}");
    }
}

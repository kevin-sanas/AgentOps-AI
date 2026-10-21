package dev.agentops.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIds extends OncePerRequestFilter {
    public static final String ATTRIBUTE = "agentops.requestId";
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Request-Id", id);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
    public static String of(HttpServletRequest request) { return (String) request.getAttribute(ATTRIBUTE); }
}

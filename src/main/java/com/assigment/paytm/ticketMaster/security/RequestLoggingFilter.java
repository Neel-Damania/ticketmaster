package com.assigment.paytm.ticketMaster.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String suppliedId = request.getHeader(REQUEST_ID_HEADER);
        String requestId = suppliedId != null && SAFE_REQUEST_ID.matcher(suppliedId).matches()
            ? suppliedId
            : UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("request_id", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            Object userAttribute = request.getAttribute("user");
            if (userAttribute instanceof AuthenticatedUser user) {
                MDC.put("user_id", user.userId());
            }
            Object reason = request.getAttribute("reason");
            var event = log.atInfo()
                .addKeyValue("method", request.getMethod())
                .addKeyValue("path", request.getRequestURI())
                .addKeyValue("status", response.getStatus());
            if (reason instanceof String reasonValue) {
                event.addKeyValue("reason", reasonValue);
            }
            event.log("http_request");
            MDC.remove("request_id");
            MDC.remove("user_id");
        }
    }
}

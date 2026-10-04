package com.assigment.paytm.ticketMaster.security;

import com.assigment.paytm.ticketMaster.service.AuthTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final AuthTokenService authTokenService;

    public JwtAuthenticationFilter(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/auth/token") || path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            writeError(response, 401, "unauthorized", "Missing or invalid token");
            return;
        }

        try {
            String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
            AuthenticatedUser user = authTokenService.parseToken(token);
            request.setAttribute("user", user);
            filterChain.doFilter(request, response);
        } catch (RuntimeException ex) {
            writeError(response, 401, "unauthorized", "Missing or invalid token");
        }
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}

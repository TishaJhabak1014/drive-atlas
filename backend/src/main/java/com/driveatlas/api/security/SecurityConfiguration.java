package com.driveatlas.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        var csrfHandler = new CsrfTokenRequestAttributeHandler();

        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/index.html", "/privacy.html", "/assets/**", "/favicon.ico",
                                "/actuator/health/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                .oauth2Login(login -> login.defaultSuccessUrl("/", true))
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                        (request, response, exception) -> writeProblem(
                                response, objectMapper, HttpStatus.UNAUTHORIZED, "Authentication required"),
                        new AntPathRequestMatcher("/api/**"))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                writeProblem(response, objectMapper, HttpStatus.FORBIDDEN, "Request is not permitted");
                            } else {
                                response.sendError(HttpStatus.FORBIDDEN.value());
                            }
                        }))
                .logout(logout -> logout.logoutSuccessHandler(
                        (request, response, authentication) -> response.setStatus(HttpStatus.NO_CONTENT.value())))
                .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.csrf.CsrfFilter.class)
                .build();
    }

    private static void writeProblem(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            HttpStatus status,
            String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}

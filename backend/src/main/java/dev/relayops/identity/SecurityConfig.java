package dev.relayops.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService users(JdbcTemplate jdbc) {
        return username -> jdbc.query("SELECT username, password_hash, enabled FROM user_account WHERE username = ?",
                (rs, row) -> User.withUsername(rs.getString("username")).password(rs.getString("password_hash"))
                        .roles("USER").disabled(!rs.getBoolean("enabled")).build(), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"请求未获授权\"}");
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        // Keep Spring's session CSRF repository and XOR token handler. The client fetches /auth/csrf.
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/auth/csrf", "/auth/login").permitAll()
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginProcessingUrl("/auth/login")
                        .successHandler((req, res, authentication) -> res.setStatus(204))
                        .failureHandler((req, res, exception) -> error(res, 401, "UNAUTHENTICATED")))
                .logout(logout -> logout.logoutUrl("/auth/logout")
                        .logoutSuccessHandler((req, res, authentication) -> res.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> error(res, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((req, res, ex) -> error(res, 403, "FORBIDDEN")))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                            throws ServletException, IOException {
                        var session = req.getSession(false);
                        if (session != null && System.currentTimeMillis() - session.getCreationTime() > 8 * 60 * 60 * 1000L) {
                            session.invalidate();
                            error(res, 401, "SESSION_EXPIRED");
                            return;
                        }
                        if (req.getServletPath().equals("/auth/login") && req.getMethod().equals("POST")) {
                            String password = req.getParameter("password");
                            String username = req.getParameter("username");
                            if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                                    || username == null || username.length() > 80) {
                                error(res, 401, "UNAUTHENTICATED");
                                return;
                            }
                        }
                        chain.doFilter(req, res);
                    }
                }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

package dev.agentops.access;

import dev.agentops.identity.SessionService;
import dev.agentops.shared.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    // No framework-generated user or password. Authentication uses opaque sessions only.
    @Bean UserDetailsService noBasicUsers() { return username -> { throw new UsernameNotFoundException("Unavailable"); }; }
    @Bean SecurityFilterChain security(HttpSecurity http, SessionService sessions, SecuritySettings settings) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> Problems.write(req,res,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((req,res,ex) -> Problems.write(req,res,403,"FORBIDDEN")))
            .authorizeHttpRequests(a -> a.requestMatchers("/api/v1/auth/login", "/actuator/health").permitAll()
                .requestMatchers("/internal/**").hasRole("INTERNAL")
                .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
            .addFilterBefore(new BearerFilter(sessions, settings), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    private static final class BearerFilter extends OncePerRequestFilter {
        private final SessionService sessions;
        private final byte[] internalToken;
        private BearerFilter(SessionService sessions, SecuritySettings settings) {
            this.sessions = sessions; this.internalToken = settings.internalToken().getBytes(StandardCharsets.UTF_8);
        }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            String header = req.getHeader("Authorization");
            String token = header != null && header.startsWith("Bearer ") && header.length() <= 1024 ? header.substring(7) : null;
            if (req.getServletPath().startsWith("/internal/")) {
                if (token != null && MessageDigest.isEqual(internalToken, token.getBytes(StandardCharsets.UTF_8))) {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-service", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))));
                }
            } else if (token != null) {
                try {
                    Caller caller = sessions.authenticate(token);
                    if (caller != null) SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(caller, null,
                        caller.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
                } catch (org.springframework.dao.DataAccessException e) {
                    Problems.write(req, res, 503, "DEPENDENCY_UNAVAILABLE"); return;
                }
            }
            chain.doFilter(req, res);
        }
    }
}

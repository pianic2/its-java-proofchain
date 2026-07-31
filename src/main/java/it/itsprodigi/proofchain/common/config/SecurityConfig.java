package it.itsprodigi.proofchain.common.config;

import it.itsprodigi.proofchain.auth.application.JwtTokenService;
import it.itsprodigi.proofchain.auth.logging.AuthEventLogger;
import it.itsprodigi.proofchain.auth.security.JwtAuthenticationFilter;
import it.itsprodigi.proofchain.auth.security.ProblemAccessDeniedHandler;
import it.itsprodigi.proofchain.auth.security.ProblemAuthenticationEntryPoint;
import it.itsprodigi.proofchain.auth.security.SecurityProblemWriter;
import it.itsprodigi.proofchain.operator.persistence.OperatorRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /** Browser preflight results may be cached for this long once an explicit allowlist is configured. */
    private static final Duration CORS_MAX_AGE = Duration.ofMinutes(30);

    /**
     * The container orchestrator must read health before any operator exists, so exactly these three probes are
     * unauthenticated. They are enumerated one by one instead of {@code /actuator/**} so that any endpoint added later
     * is authenticated by default rather than published by accident. The responses carry a bare status: detail and
     * component rendering are switched off in {@code application.yml}, and no other endpoint is exposed at all.
     */
    public static final String[] PUBLIC_HEALTH_PROBES = {
        "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
    };

    /**
     * Cross-origin policy. With the frozen empty allowlist the source resolves to {@code null} for every request, so no
     * CORS header is written and browsers deny the exchange. Only explicit, non-wildcard origins can ever be returned,
     * and credentials are never allowed because the API authenticates with a bearer token.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        return request -> {
            if (properties.deniesEveryOrigin()) {
                return null;
            }
            CorsConfiguration configuration = new CorsConfiguration();
            configuration.setAllowedOrigins(properties.allowedOrigins());
            configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "OPTIONS"));
            configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            configuration.setAllowCredentials(false);
            configuration.setMaxAge(CORS_MAX_AGE);
            return configuration;
        };
    }

    @Bean
    ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(SecurityProblemWriter writer) {
        return new ProblemAuthenticationEntryPoint(writer);
    }

    @Bean
    ProblemAccessDeniedHandler problemAccessDeniedHandler(SecurityProblemWriter writer) {
        return new ProblemAccessDeniedHandler(writer);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtTokenService tokens,
            OperatorRepository operators,
            SecurityProblemWriter writer,
            ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler deniedHandler,
            AuthEventLogger authEventLogger,
            CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        JwtAuthenticationFilter jwt = new JwtAuthenticationFilter(tokens, operators, writer, authEventLogger);
        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .rememberMe(remember -> remember.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .securityContext(context -> context.requireExplicitSave(true)
                        .securityContextRepository(
                                new org.springframework.security.web.context.NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        errors -> errors.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                        .permitAll()
                        .requestMatchers("/api/v1/auth/login", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(PUBLIC_HEALTH_PROBES)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(jwt, AnonymousAuthenticationFilter.class)
                .build();
    }
}

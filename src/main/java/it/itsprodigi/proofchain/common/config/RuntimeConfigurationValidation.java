package it.itsprodigi.proofchain.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers the strongly typed configuration bindings that must be validated before the application accepts traffic.
 *
 * <p>Every binding below is eager, so an invalid value stops the context instead of surfacing later as a runtime
 * surprise. Nothing here degrades to a default when a required value is absent.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    CorsProperties.class,
    MultipartLimitsProperties.class,
    HttpRequestLimitsProperties.class,
    DatabaseTimeoutProperties.class,
    GracefulShutdownProperties.class
})
public class RuntimeConfigurationValidation {

    /**
     * Datasource credentials are only mandatory when a runtime profile is active. Automated tests provide their own
     * container-backed coordinates and therefore do not activate this binding.
     */
    @Configuration(proxyBeanMethods = false)
    @Profile({"local", "container"})
    @EnableConfigurationProperties(RuntimeDatasourceProperties.class)
    public static class RuntimeDatasourceValidation {}
}

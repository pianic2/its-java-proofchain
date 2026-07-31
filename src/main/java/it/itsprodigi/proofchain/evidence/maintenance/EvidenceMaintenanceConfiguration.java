package it.itsprodigi.proofchain.evidence.maintenance;

import it.itsprodigi.proofchain.evidence.storage.EvidenceStorageProperties;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The entire bean set of the offline maintenance context.
 *
 * <p>This is deliberately not the application context. It imports exactly one auto-configuration — the data source —
 * and declares exactly two beans. There is no servlet container, no security filter chain, no controller, no OpenAPI
 * document, no actuator endpoint, no JPA factory, no Flyway migration and no evidence write path, so the offline report
 * has no HTTP surface to expose and no component that could mutate evidence even if it were asked to.
 *
 * <p>The {@link Profile} guard is the second lock. The class sits inside the component-scanned package tree, so a
 * normal application start does evaluate it — and skips it, because {@code maintenance} is never one of the three
 * runtime profiles. Only {@link EvidenceMaintenanceCommand}, which activates that profile itself, can bring it up.
 */
@Configuration(proxyBeanMethods = false)
@Profile(EvidenceMaintenanceCommand.MAINTENANCE_PROFILE)
@ImportAutoConfiguration(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties({EvidenceStorageProperties.class, OrphanFileReportProperties.class})
public class EvidenceMaintenanceConfiguration {

    @Bean
    EvidenceStorageKeyCatalog evidenceStorageKeyCatalog(DataSource dataSource) {
        return new JdbcEvidenceStorageKeyCatalog(dataSource);
    }

    @Bean
    OrphanFileReportService orphanFileReportService(
            EvidenceStorageProperties properties, EvidenceStorageKeyCatalog catalog) {
        return new OrphanFileReportService(properties, catalog);
    }
}

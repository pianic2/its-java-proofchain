package it.itsprodigi.proofchain.evidence.storage;

import it.itsprodigi.proofchain.evidence.application.EvidenceStoragePort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvidenceStorageProperties.class)
public class EvidenceStorageConfiguration {

    @Bean
    EvidenceStoragePort evidenceStorage(EvidenceStorageProperties properties) {
        return new FileSystemEvidenceStorage(properties);
    }

    /**
     * The bean name is contractual: the health registry derives the contributor name {@code evidenceStorage} from it,
     * and the readiness group declared in {@code application.yml} refers to exactly that name.
     */
    @Bean
    EvidenceStorageHealthIndicator evidenceStorageHealthIndicator(EvidenceStorageProperties properties) {
        return new EvidenceStorageHealthIndicator(properties);
    }
}

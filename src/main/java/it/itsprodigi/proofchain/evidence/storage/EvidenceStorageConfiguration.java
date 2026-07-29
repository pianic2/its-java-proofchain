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
}

package it.itsprodigi.proofchain.evidence.maintenance;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The activation switch and the optional destination of the offline orphan report.
 *
 * <p>The switch defaults to {@code false} and is bound only inside the maintenance context, so a normal application
 * start neither reads it nor exposes it. The destination is optional: with no destination the report goes to standard
 * output, which is the mode that touches no filesystem at all.
 */
@ConfigurationProperties(prefix = OrphanFileReportProperties.PREFIX)
public record OrphanFileReportProperties(boolean enabled, Path output) {

    public static final String PREFIX = "proofchain.maintenance.orphan-report";
}

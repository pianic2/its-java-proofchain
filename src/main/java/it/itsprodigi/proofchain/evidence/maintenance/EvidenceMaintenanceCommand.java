package it.itsprodigi.proofchain.evidence.maintenance;

import it.itsprodigi.proofchain.evidence.storage.EvidenceStorageProperties;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;

/**
 * The offline evidence-storage maintenance command.
 *
 * <p>The command is unreachable over HTTP by construction rather than by authorization. It has no controller, no
 * handler mapping and no actuator endpoint; it is a {@code main}-time branch that starts a second, minimal Spring
 * context in {@link WebApplicationType#NONE} mode. A running server therefore has no code path — authenticated,
 * anonymous or administrative — that can reach it, and the command in turn never opens a port.
 *
 * <p>Three independent conditions must all hold before anything is scanned: the process must be started from the
 * command line with the explicit {@code --proofchain.maintenance.orphan-report.enabled=true} argument, the
 * {@code maintenance} profile must be active — the command activates it itself and refuses to continue otherwise — and
 * the resulting context must not be a web context.
 *
 * <p>The command writes nothing except its own report document, and only to a destination outside the evidence storage
 * root, only with {@code CREATE_NEW} semantics so an existing file is never overwritten. With no destination
 * configured the report goes to standard output and the command touches no file at all.
 */
public final class EvidenceMaintenanceCommand {

    /** The profile the command activates for its own context; no runtime deployment ever activates it. */
    public static final String MAINTENANCE_PROFILE = "maintenance";

    /** The property that must be explicitly enabled, in full, on the command line. */
    public static final String ENABLED_PROPERTY = OrphanFileReportProperties.PREFIX + ".enabled";

    /** The scan completed and found nothing to investigate. */
    public static final int EXIT_CLEAN = 0;

    /** The command could not run to completion; no report was produced. */
    public static final int EXIT_FAILED = 1;

    /** The scan completed and produced at least one finding. */
    public static final int EXIT_FINDINGS = 2;

    private static final String ENABLED_ARGUMENT = "--" + ENABLED_PROPERTY + "=true";

    private static final Map<String, Object> FORCED_DEFAULTS = Map.of(
            "spring.flyway.enabled", "false",
            "spring.jpa.hibernate.ddl-auto", "none",
            "spring.datasource.hikari.read-only", "true",
            "spring.datasource.hikari.maximum-pool-size", "2",
            "spring.datasource.hikari.pool-name", "proofchain-maintenance",
            "spring.main.web-application-type", "none");

    private EvidenceMaintenanceCommand() {}

    /**
     * Whether this process was started as the maintenance command. Only the exact, fully spelled enabling argument
     * counts: no environment variable, no configuration file and no partial match can divert a normal start.
     */
    public static boolean isRequested(String[] arguments) {
        if (arguments == null) {
            return false;
        }
        for (String argument : arguments) {
            if (ENABLED_ARGUMENT.equals(argument)) {
                return true;
            }
        }
        return false;
    }

    /** Runs the command and returns the process exit code. */
    public static int execute(String[] arguments, PrintStream out, PrintStream error) {
        Objects.requireNonNull(out, "out must not be null");
        Objects.requireNonNull(error, "error must not be null");
        if (!isRequested(arguments)) {
            error.println("The evidence orphan report requires " + ENABLED_ARGUMENT);
            return EXIT_FAILED;
        }
        try (ConfigurableApplicationContext context = start(arguments)) {
            if (context instanceof WebApplicationContext) {
                error.println("The evidence orphan report refuses to run inside a web context");
                return EXIT_FAILED;
            }
            EvidenceStorageProperties storage = context.getBean(EvidenceStorageProperties.class);
            OrphanFileReportProperties report = context.getBean(OrphanFileReportProperties.class);
            if (!report.enabled()) {
                error.println("The evidence orphan report is not enabled");
                return EXIT_FAILED;
            }
            OrphanFileReport result =
                    context.getBean(OrphanFileReportService.class).scan();
            publish(result.toJson(), report.output(), storage.root(), out);
            return result.findings().isEmpty() ? EXIT_CLEAN : EXIT_FINDINGS;
        } catch (OrphanFileReportException exception) {
            error.println("The evidence orphan report failed: " + exception.getMessage());
            return EXIT_FAILED;
        } catch (RuntimeException exception) {
            // Only the failure type is printed: a startup failure message can carry a JDBC URL or a credential.
            error.println(
                    "The evidence orphan report failed: " + exception.getClass().getSimpleName());
            return EXIT_FAILED;
        }
    }

    private static ConfigurableApplicationContext start(String[] arguments) {
        return new SpringApplicationBuilder(EvidenceMaintenanceConfiguration.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .registerShutdownHook(false)
                .profiles(MAINTENANCE_PROFILE)
                .properties(FORCED_DEFAULTS)
                .run(arguments);
    }

    private static void publish(String rendered, Path output, Path storageRoot, PrintStream out) {
        if (output == null) {
            out.print(rendered);
            return;
        }
        Path destination = output.toAbsolutePath().normalize();
        if (destination.startsWith(storageRoot.toAbsolutePath().normalize())) {
            throw new OrphanFileReportException("The report destination must stay outside the evidence storage root");
        }
        try {
            Files.writeString(destination, rendered, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException exception) {
            throw new OrphanFileReportException("The report destination already exists and is never overwritten");
        } catch (IOException exception) {
            throw new OrphanFileReportException("The report destination could not be written");
        }
    }
}

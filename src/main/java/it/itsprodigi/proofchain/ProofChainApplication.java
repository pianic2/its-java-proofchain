package it.itsprodigi.proofchain;

import it.itsprodigi.proofchain.evidence.maintenance.EvidenceMaintenanceCommand;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProofChainApplication {

    public static void main(String[] args) {
        // The offline maintenance command is a start-time branch, never a running feature: it starts its own minimal
        // non-web context and returns an exit code. The server below is therefore never started with it, and the
        // command has no HTTP surface to be reached through.
        if (EvidenceMaintenanceCommand.isRequested(args)) {
            System.exit(EvidenceMaintenanceCommand.execute(args, System.out, System.err));
        }
        SpringApplication.run(ProofChainApplication.class, args);
    }
}

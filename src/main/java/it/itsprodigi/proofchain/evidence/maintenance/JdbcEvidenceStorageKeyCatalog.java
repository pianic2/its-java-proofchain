package it.itsprodigi.proofchain.evidence.maintenance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The single database access path of the offline report.
 *
 * <p>Three independent properties make a write impossible here. The connection is switched to read-only before the
 * statement is prepared, so PostgreSQL itself refuses any {@code INSERT}, {@code UPDATE} or {@code DELETE} issued on
 * it. The only statement is a {@code SELECT} of two columns. And the surrounding {@link EvidenceStorageKeyCatalog}
 * contract offers no write operation to call.
 */
public final class JdbcEvidenceStorageKeyCatalog implements EvidenceStorageKeyCatalog {

    private static final String QUERY = "SELECT id, storage_key FROM digital_evidence ORDER BY id";

    private final DataSource dataSource;

    public JdbcEvidenceStorageKeyCatalog(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public List<EvidenceStorageKeyEntry> storageKeys() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(QUERY);
                    ResultSet rows = statement.executeQuery()) {
                List<EvidenceStorageKeyEntry> entries = new ArrayList<>();
                while (rows.next()) {
                    entries.add(new EvidenceStorageKeyEntry(
                            UUID.fromString(rows.getString(1)), Objects.requireNonNullElse(rows.getString(2), "")));
                }
                return List.copyOf(entries);
            }
        } catch (SQLException | IllegalArgumentException exception) {
            throw new OrphanFileReportException("The evidence storage keys could not be read");
        }
    }
}

package it.itsprodigi.proofchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Repository-wide documentation link audit.
 *
 * <p>Every relative Markdown link in the README, the contributing guide, the technical documentation and the migration
 * guide must resolve to a file that exists, and every in-document anchor must match an actual heading. This keeps the
 * ADR index, the documentation home and the feature guides from drifting apart after a rename, and it is what makes a
 * newly added ADR number verifiable from a test rather than from a manual review.
 */
class DocumentationLinkAuditTest {

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]]*]\\(([^)\\s]+)\\)");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9 \\-]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Test
    void everyRelativeDocumentationLinkAndAnchorResolves() throws IOException {
        List<Path> documents = documents();
        assertThat(documents)
                .as("the audit must actually find the documentation set")
                .hasSizeGreaterThanOrEqualTo(10);

        List<String> broken = new ArrayList<>();
        for (Path document : documents) {
            String content = Files.readString(document, StandardCharsets.UTF_8);
            Matcher matcher = MARKDOWN_LINK.matcher(content);
            while (matcher.find()) {
                String target = matcher.group(1);
                if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")) {
                    continue;
                }
                int hash = target.indexOf('#');
                String filePart = hash < 0 ? target : target.substring(0, hash);
                String anchor = hash < 0 ? "" : target.substring(hash + 1);
                Path directory = document.toAbsolutePath().getParent();
                Path resolved = filePart.isEmpty()
                        ? document
                        : directory.resolve(filePart).normalize();
                // A link may target a file or, like the payload package link, a directory.
                if (!Files.exists(resolved)) {
                    broken.add("%s -> %s (missing target)".formatted(document, target));
                    continue;
                }
                if (!anchor.isEmpty()
                        && resolved.getFileName().toString().endsWith(".md")
                        && !headingSlugs(resolved).contains(anchor)) {
                    broken.add("%s -> %s (missing anchor)".formatted(document, target));
                }
            }
        }
        assertThat(broken).as("broken documentation links").isEmpty();
    }

    @Test
    void theArchitectureDecisionIndexListsEveryAcceptedRecordExactlyOnce() throws IOException {
        Path adrDirectory = Path.of("docs", "adr");
        List<String> records;
        try (Stream<Path> files = Files.list(adrDirectory)) {
            records = files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("ADR-") && name.endsWith(".md"))
                    .sorted()
                    .toList();
        }
        assertThat(records).as("the repository must contain accepted ADRs").isNotEmpty();

        String index = Files.readString(adrDirectory.resolve("README.md"), StandardCharsets.UTF_8);
        for (String record : records) {
            assertThat(index.split(Pattern.quote("./" + record), -1).length - 1)
                    .as("ADR %s must be listed exactly once in the index", record)
                    .isEqualTo(1);
        }

        // ADR numbers are dense and unique, so the next free number is always records.size() + 1.
        for (int position = 0; position < records.size(); position++) {
            assertThat(records.get(position))
                    .as("ADR numbering must be dense and gapless")
                    .startsWith("ADR-%03d-".formatted(position + 1));
        }
    }

    private static List<Path> documents() throws IOException {
        List<Path> documents = new ArrayList<>();
        documents.add(Path.of("README.md"));
        documents.add(Path.of("CONTRIBUTING.md"));
        documents.add(Path.of("src", "main", "resources", "db", "migration", "README.md"));
        try (Stream<Path> files = Files.walk(Path.of("docs"))) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(documents::add);
        }
        return documents.stream().filter(Files::isRegularFile).toList();
    }

    private static Set<String> headingSlugs(Path document) throws IOException {
        Set<String> slugs = new HashSet<>();
        for (String line : Files.readAllLines(document, StandardCharsets.UTF_8)) {
            if (!line.startsWith("#")) {
                continue;
            }
            String heading = line.replaceFirst("^#+", "").strip().toLowerCase(Locale.ROOT);
            slugs.add(WHITESPACE
                    .matcher(NON_SLUG.matcher(heading).replaceAll("").strip())
                    .replaceAll("-"));
        }
        return slugs;
    }
}

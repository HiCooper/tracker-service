package com.gateflow.tracker.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class ClickHouseMigrationRunner implements ApplicationRunner {

    private static final String MIGRATION_LOCATION = "classpath:db/migration/*.sql";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^V(\\d+)__.*\\.sql$");

    private final DataSource dataSource;

    public ClickHouseMigrationRunner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureVersionTable();
            List<String> pending = findPendingMigrations();
            if (pending.isEmpty()) {
                log.info("No pending ClickHouse migrations found");
                return;
            }
            for (String file : pending) {
                executeMigration(file);
            }
            log.info("ClickHouse migrations completed: {} files executed", pending.size());
        } catch (Exception e) {
            log.error("ClickHouse migration failed, service will continue startup", e);
        }
    }

    private void ensureVersionTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS _schema_version (
                version     Int32,
                filename    String,
                checksum    String,
                executed_at DateTime DEFAULT now()
            ) ENGINE = MergeTree()
            ORDER BY version
            SETTINGS index_granularity = 8192
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
        }
    }

    private List<String> findPendingMigrations() throws Exception {
        List<String> executed = getExecutedVersions();
        List<String> pending = new ArrayList<>();

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(MIGRATION_LOCATION);

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) continue;

            Matcher m = VERSION_PATTERN.matcher(filename);
            if (!m.matches()) continue;

            int version = Integer.parseInt(m.group(1));
            if (!executed.contains(filename)) {
                pending.add(filename);
            } else {
                log.debug("Migration already executed: {}", filename);
            }
        }

        pending.sort((a, b) -> {
            int v1 = extractVersion(a);
            int v2 = extractVersion(b);
            return Integer.compare(v1, v2);
        });

        return pending;
    }

    private List<String> getExecutedVersions() throws SQLException {
        List<String> versions = new ArrayList<>();
        String sql = "SELECT filename FROM _schema_version ORDER BY version";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                versions.add(rs.getString("filename"));
            }
        }
        return versions;
    }

    private void executeMigration(String filename) throws Exception {
        log.info("Executing ClickHouse migration: {}", filename);

        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource resource = resolver.getResource("classpath:db/migration/" + filename);
        String content = new String(resource.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        List<String> statements = splitStatements(content);

        try (Connection conn = dataSource.getConnection()) {
            for (String sql : statements) {
                String trimmed = sql.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
                try (PreparedStatement stmt = conn.prepareStatement(trimmed)) {
                    stmt.execute();
                } catch (SQLException e) {
                    if (e.getMessage() != null && e.getMessage().contains("ALREADY_EXISTS")) {
                        log.debug("Skipping (already exists): {}", e.getMessage());
                    } else {
                        throw e;
                    }
                }
            }
        }

        recordMigration(filename);
        log.info("Migration executed successfully: {}", filename);
    }

    private void recordMigration(String filename) throws SQLException {
        String sql = "INSERT INTO _schema_version (version, filename, checksum) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, extractVersion(filename));
            stmt.setString(2, filename);
            stmt.setString(3, "");
            stmt.execute();
        }
    }

    private List<String> splitStatements(String content) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleLineComment = false;

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("--")) continue;
            if (trimmed.isEmpty() && current.isEmpty()) continue;

            current.append(line).append("\n");

            if (trimmed.endsWith(";")) {
                String stmt = current.toString().trim();
                if (stmt.endsWith(";")) {
                    stmt = stmt.substring(0, stmt.length() - 1);
                }
                statements.add(stmt);
                current = new StringBuilder();
            }
        }

        // leftover without trailing semicolon
        if (!current.isEmpty()) {
            String stmt = current.toString().trim();
            if (!stmt.isEmpty()) {
                statements.add(stmt);
            }
        }

        return statements;
    }

    private int extractVersion(String filename) {
        Matcher m = VERSION_PATTERN.matcher(filename);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        throw new IllegalArgumentException("Invalid migration filename: " + filename);
    }
}

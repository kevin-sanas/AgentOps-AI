package dev.agentops;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

final class PostgresFixture {
    private PostgresFixture() {}
    static EmbeddedPostgres start() {
        try {
            Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
            Path data = Path.of("target", "tmp", "postgres-" + UUID.randomUUID()).toAbsolutePath();
            return EmbeddedPostgres.builder().setDataDirectory(data).setCleanDataDirectory(true).setPort(0).start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
}

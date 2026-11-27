package vangogh.storage;

import io.vavr.control.Either;
import io.vavr.control.Try;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import vangogh.error.FetchError;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

import static vangogh.db.Tables.USER;

/**
 * The SQLite file, its one connection, and the schema bootstrap.
 *
 * <p>SQLite's JDBC connection is not thread-safe, so <em>every</em> access —
 * reads included — goes through {@link #read} / {@link #write}, both of which
 * take the same lock. A single-user desktop app does not notice; if read
 * parallelism is ever needed, a read pool can be added behind this seam without
 * changing any repository.
 *
 * <p>The schema is applied once, gated by {@code PRAGMA user_version}. DDL in
 * SQLite is transactional, so the schema, the seeded {@code local} user and the
 * version bump commit together: a failure leaves the file untouched and the
 * next open retries.
 */
public final class Database implements AutoCloseable {

    static final long LOCAL_USER_ID = 1L;
    private static final int SCHEMA_VERSION = 1;
    private static final String SCHEMA_RESOURCE = "/db/schema.sql";

    private final Connection connection;
    private final DSLContext dsl;
    private final ReentrantLock lock = new ReentrantLock();

    private Database(Connection connection) {
        this.connection = connection;
        this.dsl = DSL.using(connection, SQLDialect.SQLITE);
    }

    /** The app's database: {@code ~/.van-gogh/vangogh.db}. */
    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".van-gogh", "vangogh.db");
    }

    public static Database open() {
        return open(defaultPath());
    }

    /** Opens (creating if needed) the database at {@code file}. */
    public static Database open(Path file) {
        Path target = file.toAbsolutePath();
        Connection connection = null;
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + target);
            Database database = new Database(connection);
            database.bootstrap();
            return database;
        } catch (Exception error) {
            closeQuietly(connection);
            throw new IllegalStateException(
                    "cannot open database " + target + ": " + error.getMessage(), error);
        }
    }

    /** Reads through the shared connection, serialized with writers. */
    public <T> T read(Function<? super DSLContext, ? extends T> work) {
        lock.lock();
        try {
            return work.apply(dsl);
        } finally {
            lock.unlock();
        }
    }

    /** Runs {@code work} in one transaction and hands it a transaction-bound context. */
    public <T> T write(Function<? super DSLContext, ? extends T> work) {
        lock.lock();
        try {
            return dsl.transactionResult(configuration ->
                    work.apply(DSL.using(configuration)));
        } finally {
            lock.unlock();
        }
    }

    /** Maps a throwing repository body onto the shared error vocabulary. */
    static <T> Either<FetchError, T> attempt(String op, Supplier<T> body) {
        return Try.of(body::get)
                .toEither()
                .mapLeft(error -> (FetchError) new FetchError.Storage(op, error));
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (Exception ignored) {
            // Closing is best-effort; the process is ending or the file is gone.
        } finally {
            lock.unlock();
        }
    }

    // ------------------------------------------------------------- bootstrap

    private void bootstrap() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }

        int version = userVersion();
        if (version > SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "database schema is version " + version + ", this app supports "
                            + SCHEMA_VERSION);
        }
        if (version < SCHEMA_VERSION) {
            applySchema();
        }
    }

    private void applySchema() throws Exception {
        connection.setAutoCommit(false);
        try {
            executeScript(readSchema());
            dsl.insertInto(USER)
                    .set(USER.ID, (int) LOCAL_USER_ID)
                    .set(USER.NAME, "local")
                    .set(USER.CREATED_AT, (int) Instant.now().getEpochSecond())
                    .onConflict(USER.ID)
                    .doNothing()
                    .execute();
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = " + SCHEMA_VERSION);
            }
            connection.commit();
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private int userVersion() throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private void executeScript(String script) throws Exception {
        String withoutComments = script.lines()
                .map(String::strip)
                .filter(line -> !line.startsWith("--"))
                .reduce("", (left, right) -> left + right + "\n");
        try (Statement statement = connection.createStatement()) {
            for (String sql : withoutComments.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }

    private static String readSchema() throws IOException {
        try (InputStream stream = Database.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IOException("missing classpath resource " + SCHEMA_RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // Already failing to open; nothing useful to do.
            }
        }
    }
}

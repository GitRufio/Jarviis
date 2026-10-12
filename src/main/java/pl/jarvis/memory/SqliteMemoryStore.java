package pl.jarvis.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SqliteMemoryStore implements MemoryStore {
    private final Connection connection;

    public SqliteMemoryStore(Path databasePath) {
        try {
            Files.createDirectories(databasePath.toAbsolutePath().getParent());
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS memory (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            type TEXT NOT NULL,
                            content TEXT NOT NULL,
                            importance INTEGER NOT NULL,
                            created_at TEXT NOT NULL,
                            updated_at TEXT NOT NULL
                        )
                        """);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot initialize SQLite memory", exception);
        }
    }

    @Override
    public synchronized void save(String type, String content, int importance) {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO memory(type, content, importance, created_at, updated_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, type);
            statement.setString(2, content);
            statement.setInt(3, importance);
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot save memory", exception);
        }
    }

    @Override
    public synchronized List<MemoryEntry> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<MemoryEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, type, content, importance, created_at, updated_at FROM memory ORDER BY id DESC LIMIT ?")) {
            statement.setInt(1, safeLimit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    entries.add(new MemoryEntry(result.getLong("id"), result.getString("type"),
                            result.getString("content"), result.getInt("importance"),
                            Instant.parse(result.getString("created_at")), Instant.parse(result.getString("updated_at"))));
                }
            }
            return List.copyOf(entries);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot read memory", exception);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot close SQLite memory", exception);
        }
    }
}

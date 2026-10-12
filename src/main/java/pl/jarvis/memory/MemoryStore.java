package pl.jarvis.memory;

import java.util.List;

public interface MemoryStore extends AutoCloseable {
    void save(String type, String content, int importance);
    List<MemoryEntry> recent(int limit);
    @Override default void close() { }
}

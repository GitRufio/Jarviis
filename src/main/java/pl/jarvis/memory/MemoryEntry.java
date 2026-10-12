package pl.jarvis.memory;

import java.time.Instant;

public record MemoryEntry(long id, String type, String content, int importance, Instant createdAt, Instant updatedAt) { }

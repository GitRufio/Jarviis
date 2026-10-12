package pl.jarvis.recovery;

public final class RecoveryManager {
    private final int maxRetries;

    public RecoveryManager(int maxRetries) {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be non-negative");
        this.maxRetries = maxRetries;
    }

    public boolean mayRetry(int attempts) {
        return attempts < maxRetries;
    }
}

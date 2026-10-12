package pl.jarvis.tools;

public final class ToolPermissionException extends ToolException {
    private final boolean confirmationRequired;

    public ToolPermissionException(String message, boolean confirmationRequired) {
        super(message);
        this.confirmationRequired = confirmationRequired;
    }

    public boolean confirmationRequired() { return confirmationRequired; }
}

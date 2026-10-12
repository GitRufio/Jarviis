package pl.jarvis.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FileToolTest {
    @Test
    void rejectsPathTraversalOutsideWorkspace() throws Exception {
        Path root = Files.createTempDirectory("jarvis-file-tool");
        FileTool tool = new FileTool(root);
        assertThrows(ToolException.class, () -> tool.execute(Map.of("action", "read", "path", "../outside")));
    }

    @Test
    void rejectsSymbolicLinksThatPointOutsideWorkspace() throws Exception {
        Path root = Files.createTempDirectory("jarvis-file-tool");
        Path outside = Files.createTempFile("jarvis-outside", ".txt");
        Files.createSymbolicLink(root.resolve("outside-link"), outside);
        FileTool tool = new FileTool(root);

        assertThrows(ToolException.class,
                () -> tool.execute(Map.of("action", "read", "path", "outside-link")));
    }
}

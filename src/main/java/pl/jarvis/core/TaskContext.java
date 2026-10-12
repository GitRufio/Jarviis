package pl.jarvis.core;

import pl.jarvis.hardware.HardwareProfile;
import pl.jarvis.models.ModelManager;
import pl.jarvis.tools.ToolManager;

public record TaskContext(
        Task task,
        HardwareProfile hardware,
        ModelManager models,
        ToolManager tools
) { }

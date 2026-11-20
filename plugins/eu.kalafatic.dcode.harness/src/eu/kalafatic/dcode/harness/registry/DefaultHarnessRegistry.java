package eu.kalafatic.dcode.harness.registry;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Default harness registry.
 *
 * <p>The DCode native harness is always present and integrated. External CLI harnesses
 * (OpenCode, Claude Code, Aider) are reported with their true availability — detected
 * on PATH — and {@code integrated=false} until a backend execution adapter exists.
 * The UI must only offer task execution through usable harnesses and never silently
 * substitute one harness for another.</p>
 */
public final class DefaultHarnessRegistry implements HarnessRegistry {

    private final List<HarnessDescriptor> descriptors = List.of(
            new HarnessDescriptor("dcode", "DCode",
                    "Native DCode coding harness (tool-calling agent loop)",
                    true, true, List.of("tools", "streaming", "sessions", "review")),
            new HarnessDescriptor("opencode", "OpenCode",
                    "OpenCode CLI coding agent (external; not integrated into this build)",
                    false, cliOnPath("opencode"),
                    List.of("cli")),
            new HarnessDescriptor("claude", "Claude Code",
                    "Anthropic Claude Code CLI (external; not integrated into this build)",
                    false, cliOnPath("claude"),
                    List.of("cli")),
            new HarnessDescriptor("aider", "Aider",
                    "Aider AI pair-programming CLI (external; not integrated into this build)",
                    false, cliOnPath("aider"),
                    List.of("cli"))
    );

    @Override
    public List<HarnessDescriptor> harnesses() {
        return descriptors;
    }

    @Override
    public Optional<HarnessDescriptor> find(String id) {
        return descriptors.stream().filter(descriptor -> descriptor.id().equals(id))
                .findFirst();
    }

    /** Detects an executable on PATH (honest availability reporting). */
    private static boolean cliOnPath(String name) {
        ProcessBuilder builder = new ProcessBuilder("which", name);
        try {
            Process process = builder.start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}

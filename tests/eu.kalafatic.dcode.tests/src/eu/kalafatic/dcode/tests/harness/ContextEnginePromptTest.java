package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.harness.context.ContextEngine;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent system prompt must expose the exact tool argument names: agents decide
 * what to call from the prompt alone, and wrong argument names cost tool round-trips
 * (observed live with {@code old_text} instead of {@code oldText}).
 */
class ContextEnginePromptTest {

    @Test
    void systemPromptListsToolArgumentNames() {
        ContextEngine engine = new ContextEngine(null);
        Workspace workspace = new Workspace("ws-1", "test-ws", WorkspaceMode.LOCAL,
                "/tmp/test-ws", List.of(), Instant.now());
        Session session = Session.builder().id("s-1").title("test")
                .workspaceId(workspace.id()).state(SessionState.CREATED).build();

        ToolDefinition edit = new ToolDefinition("edit_file",
                "Replace an exact text block inside a workspace file.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"path\":{\"type\":\"string\"},"
                        + "\"oldText\":{\"type\":\"string\"},"
                        + "\"newText\":{\"type\":\"string\"}}}",
                false, ToolScope.WORKSPACE);

        String prompt = engine.buildSystemPrompt(workspace, session, "test task",
                List.of(edit));

        assertTrue(prompt.contains("edit_file"), "tool name must appear");
        assertTrue(prompt.contains("args: path, oldText, newText"),
                "exact argument names must be advertised; prompt was:\n" + prompt);
    }

    @Test
    void toolWithoutParametersGetsNoArgsSuffix() {
        ContextEngine engine = new ContextEngine(null);
        Workspace workspace = new Workspace("ws-1", "test-ws", WorkspaceMode.LOCAL,
                "/tmp/test-ws", List.of(), Instant.now());
        Session session = Session.builder().id("s-1").title("test")
                .workspaceId(workspace.id()).state(SessionState.CREATED).build();

        ToolDefinition listing = new ToolDefinition("list_files",
                "List files and directories in the workspace (bounded).",
                "{\"type\":\"object\",\"properties\":{}}",
                true, ToolScope.WORKSPACE);

        String prompt = engine.buildSystemPrompt(workspace, session, "test task",
                List.of(listing));
        assertTrue(prompt.contains("list_files"));
        assertTrue(!prompt.contains("args:"), "no-parameter tools must not advertise args");
    }
}

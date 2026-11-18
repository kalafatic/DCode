package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;

import java.util.Map;

/**
 * Base class for the Java/PDE tools: shared JSON helpers, repository resolution and
 * access-policy enforcement.
 */
@SuppressWarnings("restriction")
public abstract class AbstractJavaTool implements Tool {

    protected final JavaWorkspaceManager workspaceManager;
    protected final ObjectMapper json = new ObjectMapper();

    protected AbstractJavaTool(JavaWorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    protected static String arg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    protected RepositoryRef repository(ToolContext context, String name) {
        if (name == null || name.isBlank()) {
            if (context.workspace().repositories().size() == 1) {
                return context.workspace().repositories().get(0);
            }
            throw new ToolingException("Argument 'repository' is required (workspace has "
                    + context.workspace().repositories().size() + " repositories)");
        }
        for (RepositoryRef repository : context.workspace().repositories()) {
            if (repository.name().equals(name)) {
                return repository;
            }
        }
        throw new ToolingException("Unknown repository: " + name);
    }

    /** Rejects mutating operations against read-only repositories. */
    protected void requireWritable(ToolContext context, RepositoryRef repository) {
        if (repository.isReadOnly()) {
            throw new ToolingException("Repository '" + repository.name()
                    + "' is READ_ONLY; mutating Java operations are not permitted");
        }
    }

    /** Resolves a module handle for {repository, module} arguments. */
    protected JavaWorkspaceManager.ProjectHandle module(ToolContext context,
                                                        Map<String, Object> arguments) {
        RepositoryRef repository = repository(context, arg(arguments, "repository"));
        return workspaceManager.projectForModule(context.workspace(), repository,
                arg(arguments, "module"));
    }

    protected ObjectNode object() {
        return json.createObjectNode();
    }

    protected ArrayNode array() {
        return json.createArrayNode();
    }

    /** Wraps a ToolingException into a structured tool result. */
    protected eu.kalafatic.dcode.harness.tool.ToolResult failure(ToolingException e) {
        ObjectNode node = object();
        node.put("error", e.getMessage() != null ? e.getMessage() : "Java tooling failure");
        node.put("success", false);
        return eu.kalafatic.dcode.harness.tool.ToolResult.ok(node.toString());
    }
}

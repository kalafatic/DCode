package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;

import java.util.List;
import java.util.Map;

/** Implementation search for a type or interface method. */
@SuppressWarnings("restriction")
public final class JavaFindImplementationsTool extends AbstractJavaTool {

    public JavaFindImplementationsTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_find_implementations",
                "Find all implementors of an interface type (or a method of it).",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"},"
                        + "\"module\":{\"type\":\"string\"},"
                        + "\"kind\":{\"type\":\"string\",\"description\":\"type or method\"},"
                        + "\"name\":{\"type\":\"string\",\"description\":\"fully qualified type name, or 'Type#method'\"}},"
                        + "\"required\":[\"repository\",\"kind\",\"name\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            var handle = module(context, arguments);
            String kind = arg(arguments, "kind");
            String name = arg(arguments, "name");
            if (kind == null || name == null) {
                throw new ToolingException("Arguments 'kind' and 'name' are required");
            }
            IJavaElement element = JavaFindReferencesTool.resolveElement(handle, kind, name);
            List<SearchMatch> matches = JavaFindReferencesTool.search(element,
                    IJavaSearchConstants.IMPLEMENTORS);
            ObjectNode root = object();
            root.put("target", name);
            root.put("implementorCount", matches.size());
            ArrayNode implementors = root.putArray("implementors");
            for (SearchMatch match : matches) {
                ObjectNode node = implementors.addObject();
                node.put("resource", match.getResource() != null
                        && match.getResource().getLocation() != null
                        ? match.getResource().getLocation().toOSString() : "");
                node.put("offset", match.getOffset());
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        } catch (Exception e) {
            return failure(new ToolingException("Implementor search failed: "
                    + e.getMessage(), e));
        }
    }
}

package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;

import java.util.Map;

/** Type hierarchy: all supertypes and subtypes of a type. */
@SuppressWarnings("restriction")
public final class JavaTypeHierarchyTool extends AbstractJavaTool {

    public JavaTypeHierarchyTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_type_hierarchy",
                "Report the supertype and subtype hierarchies of a type.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"},"
                        + "\"type\":{\"type\":\"string\",\"description\":\"fully qualified type name\"}},"
                        + "\"required\":[\"repository\",\"type\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            var handle = module(context, arguments);
            String typeName = arg(arguments, "type");
            if (typeName == null) {
                throw new ToolingException("Argument 'type' is required");
            }
            IType type = workspaceManager.findType(handle, typeName);
            ITypeHierarchy hierarchy = type.newTypeHierarchy(
                    new org.eclipse.core.runtime.NullProgressMonitor());
            ObjectNode root = object();
            root.put("type", type.getFullyQualifiedName('.'));
            ArrayNode supertypes = root.putArray("supertypes");
            for (IType supertype : hierarchy.getAllSuperclasses(type)) {
                supertypes.add(supertype.getFullyQualifiedName('.'));
            }
            ArrayNode subtypes = root.putArray("subtypes");
            for (IType subtype : hierarchy.getAllSubtypes(type)) {
                subtypes.add(subtype.getFullyQualifiedName('.'));
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        } catch (Exception e) {
            return failure(new ToolingException("Hierarchy computation failed: "
                    + e.getMessage(), e));
        }
    }
}

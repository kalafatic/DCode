package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;

import java.util.Map;

/** Type resolution and structural inspection of one type. */
@SuppressWarnings("restriction")
public final class JavaResolveTypeTool extends AbstractJavaTool {

    public JavaResolveTypeTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_resolve_type",
                "Resolve a type by (fully qualified) name in a repository project and report "
                        + "its kind, superclass, interfaces, source location and methods.",
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
            ObjectNode root = object();
            root.put("name", type.getElementName());
            root.put("qualifiedName", type.getFullyQualifiedName('.'));
            root.put("kind", type.isInterface() ? "interface"
                    : type.isEnum() ? "enum" : type.isAnnotation() ? "annotation" : "class");
            root.put("isInterface", type.isInterface());
            root.put("visibility", Flags.toString(type.getFlags()));
            root.put("sourceFile", type.getResource() != null
                    ? type.getResource().getLocation() != null
                    ? type.getResource().getLocation().toOSString() : "" : "");
            String superclass = "";
            if (!type.isInterface()) {
                String superclassName = type.getSuperclassName();
                if (superclassName != null) {
                    String[][] resolved = type.resolveType(superclassName);
                    superclass = resolved != null && resolved.length > 0
                            ? String.join(".", resolved[0]) : superclassName;
                } else {
                    // no explicit extends clause: implicit java.lang.Object
                    superclass = "java.lang.Object";
                }
            }
            root.put("superclass", superclass);
            ArrayNode interfaces = root.putArray("interfaces");
            for (String interfaceName : type.getSuperInterfaceNames()) {
                String[][] resolved = type.resolveType(interfaceName);
                interfaces.add(resolved != null && resolved.length > 0
                        ? String.join(".", resolved[0]) : interfaceName);
            }
            ArrayNode methods = root.putArray("methods");
            for (IMethod method : type.getMethods()) {
                ObjectNode node = methods.addObject();
                node.put("name", method.getElementName());
                node.put("signature", method.getSignature());
                node.put("returnType", method.getReturnType());
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        } catch (Exception e) {
            return failure(new ToolingException("Type resolution failed: " + e.getMessage(), e));
        }
    }
}

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
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Call hierarchy: callers of a method, computed with the JDT search engine over the
 * workspace scope (correct across sibling modules, unlike the project-scoped corext
 * helper).
 */
public final class JavaCallHierarchyTool extends AbstractJavaTool {

    public JavaCallHierarchyTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_call_hierarchy",
                "Report the callers of a method in a Java source file.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"workspace-relative path of the file declaring the method\"},"
                        + "\"method\":{\"type\":\"string\",\"description\":\"method name\"}},"
                        + "\"required\":[\"path\",\"method\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            String pathArg = arg(arguments, "path");
            String methodName = arg(arguments, "method");
            if (pathArg == null || methodName == null) {
                throw new ToolingException("Arguments 'path' and 'method' are required");
            }
            var handle = workspaceManager.projectForPath(context.workspace(), pathArg);
            var unit = workspaceManager.compilationUnit(handle,
                    context.workspaceRoot().toString(), pathArg);
            IMethod target = null;
            for (IType type : unit.getAllTypes()) {
                for (IMethod method : type.getMethods()) {
                    if (method.getElementName().equals(methodName)) {
                        target = method;
                        break;
                    }
                }
                if (target != null) {
                    break;
                }
            }
            if (target == null) {
                throw new ToolingException("Method '" + methodName + "' not found in " + pathArg);
            }
            List<SearchMatch> matches = JavaFindReferencesTool.search(target,
                    IJavaSearchConstants.REFERENCES);
            ObjectNode root = object();
            root.put("method", target.getDeclaringType().getFullyQualifiedName('.')
                    + "#" + target.getElementName());
            ArrayNode callers = root.putArray("callers");
            List<String> seen = new ArrayList<>();
            for (SearchMatch match : matches) {
                Object matchElement = match.getElement();
                IMethod caller = matchElement instanceof IJavaElement javaElement
                        ? enclosingMethod(javaElement) : null;
                if (caller == null || caller.equals(target)) {
                    continue;
                }
                String resource = caller.getResource() != null
                        && caller.getResource().getLocation() != null
                        ? caller.getResource().getLocation().toOSString() : "";
                String key = resource + "#" + caller.getElementName();
                if (seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                ObjectNode node = callers.addObject();
                node.put("method", caller.getDeclaringType().getFullyQualifiedName('.')
                        + "#" + caller.getElementName());
                node.put("resource", resource);
                node.put("offset", match.getOffset());
            }
            root.put("callerCount", callers.size());
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        } catch (Exception e) {
            return failure(new ToolingException("Call hierarchy failed: " + e.getMessage(), e));
        }
    }

    private static IMethod enclosingMethod(IJavaElement element) {
        IJavaElement current = element;
        while (current != null) {
            if (current instanceof IMethod method) {
                return method;
            }
            current = current.getParent();
        }
        return null;
    }
}

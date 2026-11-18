package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.JavaProjectKind;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;

import java.util.Map;

/** Java project discovery and classpath structure of a repository. */
@SuppressWarnings("restriction")
public final class JavaProjectStructureTool extends AbstractJavaTool {

    public JavaProjectStructureTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_project_structure",
                "Discover Java projects (Maven modules, Eclipse projects, OSGi bundles) in "
                        + "a repository and report their structure and classpath.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"}},"
                        + "\"required\":[\"repository\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            var repository = repository(context, arg(arguments, "repository"));
            var handles = workspaceManager.scanRepository(context.workspace(), repository);
            ObjectNode root = object();
            root.put("repository", repository.name());
            root.put("projectCount", handles.size());
            ArrayNode projects = root.putArray("projects");
            for (JavaWorkspaceManager.ProjectHandle handle : handles) {
                ObjectNode project = projects.addObject();
                project.put("name", handle.project().getName());
                project.put("moduleDir", handle.moduleDir());
                project.put("kind", handle.kind().name());
                ArrayNode sources = project.putArray("sourceDirectories");
                for (var source : workspaceManager.sourceDirectories(handle)) {
                    sources.add(source.toString());
                }
                ArrayNode classpath = project.putArray("classpathEntries");
                int count = 0;
                for (var entry : workspaceManager.resolvedClasspath(handle)) {
                    if (entry.getEntryKind() == org.eclipse.jdt.core.IClasspathEntry.CPE_LIBRARY
                            || entry.getEntryKind() == org.eclipse.jdt.core.IClasspathEntry.CPE_SOURCE) {
                        if (count++ < 50) {
                            classpath.add(entry.getPath() != null
                                    ? entry.getPath().toOSString() : "");
                        }
                    }
                }
                project.put("classpathEntryCount", count);
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        }
    }
}

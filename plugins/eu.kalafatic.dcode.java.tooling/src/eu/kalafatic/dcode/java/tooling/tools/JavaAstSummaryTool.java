package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.AstHelper;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** AST summary of one Java source file with resolved bindings. */
@SuppressWarnings("restriction")
public final class JavaAstSummaryTool extends AbstractJavaTool {

    public JavaAstSummaryTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_ast_summary",
                "Parse a Java source file with type resolution and report its types, "
                        + "methods, fields, imports and method calls.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"workspace-relative path to the .java file\"}},"
                        + "\"required\":[\"path\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            String pathArg = arg(arguments, "path");
            if (pathArg == null) {
                throw new ToolingException("Argument 'path' is required");
            }
            var handle = workspaceManager.projectForPath(context.workspace(), pathArg);
            Path file = eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                    context.workspaceRoot(), pathArg);
            List<Path> sourceDirectories = workspaceManager.sourceDirectories(handle);
            String classpath = workspaceManager.classpathString(handle);
            var unit = AstHelper.parse(file, sourceDirectories, classpath);

            ObjectNode root = object();
            root.put("file", pathArg);
            root.put("package", unit.getPackage() != null
                    ? unit.getPackage().getName().getFullyQualifiedName() : "");

            ArrayNode types = root.putArray("types");
            for (AstHelper.TypeInfo type : AstHelper.collectTypes(unit)) {
                ObjectNode node = types.addObject();
                node.put("name", type.name());
                node.put("qualifiedName", type.qualifiedName());
                node.put("kind", type.kind());
                node.put("superclass", type.superclass());
                ArrayNode interfaces = node.putArray("interfaces");
                type.interfaces().forEach(interfaces::add);
                node.put("line", type.line());
            }

            ArrayNode methods = root.putArray("methods");
            for (AstHelper.MethodInfo method : AstHelper.collectMethods(unit)) {
                ObjectNode node = methods.addObject();
                node.put("name", method.name());
                node.put("returnType", method.returnType());
                node.put("static", method.isStatic());
                node.put("line", method.line());
                ArrayNode parameters = node.putArray("parameterTypes");
                method.parameterTypes().forEach(parameters::add);
            }

            ArrayNode fields = root.putArray("fields");
            for (AstHelper.FieldInfo field : AstHelper.collectFields(unit)) {
                ObjectNode node = fields.addObject();
                node.put("name", field.name());
                node.put("type", field.type());
                node.put("static", field.isStatic());
                node.put("line", field.line());
            }

            ArrayNode imports = root.putArray("imports");
            for (AstHelper.ImportInfo info : AstHelper.analyzeImports(unit)) {
                ObjectNode node = imports.addObject();
                node.put("name", info.name());
                node.put("onDemand", info.onDemand());
                node.put("resolved", info.resolved());
                node.put("usages", info.usages());
            }

            ArrayNode calls = root.putArray("calls");
            for (AstHelper.CallSite call : AstHelper.collectCalls(unit)) {
                ObjectNode node = calls.addObject();
                node.put("callee", call.callee());
                node.put("line", call.line());
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        }
    }
}

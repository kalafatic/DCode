package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;
import eu.kalafatic.dcode.java.tooling.pde.BundleManifest;
import eu.kalafatic.dcode.java.tooling.pde.PluginXmlInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** PDE/OSGi bundle metadata inspection (manifest + plugin.xml). */
@SuppressWarnings("restriction")
public final class PdeInspectBundleTool extends AbstractJavaTool {

    public PdeInspectBundleTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("pde_inspect_bundle",
                "Inspect an OSGi/Eclipse plugin bundle: manifest headers, imported and "
                        + "exported packages, extension points and extensions.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"workspace-relative path to the bundle directory (contains META-INF/MANIFEST.MF)\"}},"
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
            Path bundleDir = eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                    context.workspaceRoot(), pathArg);
            Path manifestFile = bundleDir.resolve("META-INF/MANIFEST.MF");
            if (!Files.isRegularFile(manifestFile)) {
                throw new ToolingException("No META-INF/MANIFEST.MF in " + pathArg);
            }
            BundleManifest manifest = new BundleManifest(manifestFile);
            ObjectNode root = object();
            root.put("bundleDir", pathArg);
            root.put("symbolicName", manifest.symbolicName());
            root.put("version", manifest.bundleVersion());
            root.put("activator", manifest.bundleActivator());
            ArrayNode requireBundle = root.putArray("requireBundle");
            manifest.requireBundle().forEach(requireBundle::add);
            ArrayNode importPackage = root.putArray("importPackage");
            for (BundleManifest.PackageClause clause : manifest.importPackage()) {
                ObjectNode node = importPackage.addObject();
                node.put("name", clause.name());
                node.put("version", clause.attributes().getOrDefault("version", ""));
            }
            ArrayNode exportPackage = root.putArray("exportPackage");
            for (BundleManifest.PackageClause clause : manifest.exportPackage()) {
                ObjectNode node = exportPackage.addObject();
                node.put("name", clause.name());
                node.put("version", clause.attributes().getOrDefault("version", ""));
            }
            ArrayNode classpath = root.putArray("bundleClassPath");
            manifest.bundleClassPath().forEach(classpath::add);
            ObjectNode headers = root.putObject("headers");
            manifest.allHeaders().forEach(headers::put);

            Path pluginXml = bundleDir.resolve("plugin.xml");
            if (Files.isRegularFile(pluginXml)) {
                PluginXmlInfo info = new PluginXmlInfo(pluginXml);
                ArrayNode points = root.putArray("extensionPoints");
                for (PluginXmlInfo.ExtensionPoint point : info.extensionPoints()) {
                    ObjectNode node = points.addObject();
                    node.put("id", point.id());
                    node.put("name", point.name());
                    node.put("schema", point.schema());
                }
                ArrayNode extensions = root.putArray("extensions");
                for (PluginXmlInfo.Extension extension : info.extensions()) {
                    ObjectNode node = extensions.addObject();
                    node.put("point", extension.point());
                    node.put("id", extension.id());
                    node.put("name", extension.name());
                }
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        }
    }
}

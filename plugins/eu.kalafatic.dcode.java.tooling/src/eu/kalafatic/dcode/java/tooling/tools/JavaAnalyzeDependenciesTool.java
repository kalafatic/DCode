package eu.kalafatic.dcode.java.tooling.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Dependency analysis of a Maven module or OSGi bundle. */
@SuppressWarnings("restriction")
public final class JavaAnalyzeDependenciesTool extends AbstractJavaTool {

    public JavaAnalyzeDependenciesTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_analyze_dependencies",
                "Analyze the dependencies of a Java module: Maven dependencies, reactor "
                        + "modules and (for OSGi bundles) manifest imports/exports.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"},"
                        + "\"module\":{\"type\":\"string\",\"description\":\"module dir relative to the repository, optional\"}},"
                        + "\"required\":[\"repository\"]}",
                true, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            var repository = repository(context, arg(arguments, "repository"));
            Path repoDir = Path.of(repository.localPath());
            Path moduleDir = repoDir;
            String moduleArg = arg(arguments, "module");
            if (moduleArg != null && !moduleArg.isBlank()) {
                moduleDir = repoDir.resolve(moduleArg).normalize();
            }
            if (!Files.isDirectory(moduleDir)) {
                throw new ToolingException("No such module directory: " + moduleDir);
            }
            ObjectNode root = object();
            root.put("repository", repository.name());
            root.put("module", moduleDir.toString());

            Path pom = moduleDir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                analyzePom(root, pom);
                ArrayNode reactor = root.putArray("reactorModules");
                try (var stream = Files.walk(repoDir, 5)) {
                    for (Path candidate : stream.filter(p -> p.getFileName() != null
                            && p.getFileName().toString().equals("pom.xml")).toList()) {
                        if (!candidate.normalize().equals(pom.normalize())) {
                            reactor.add(repoDir.relativize(candidate.getParent()).toString());
                        }
                    }
                }
            }
            Path manifest = moduleDir.resolve("META-INF/MANIFEST.MF");
            if (Files.isRegularFile(manifest)) {
                var bundle = new eu.kalafatic.dcode.java.tooling.pde.BundleManifest(manifest);
                ObjectNode osgi = root.putObject("osgi");
                osgi.put("symbolicName", bundle.symbolicName());
                osgi.put("version", bundle.bundleVersion());
                ArrayNode requireBundle = osgi.putArray("requireBundle");
                bundle.requireBundle().forEach(requireBundle::add);
                ArrayNode importPackage = osgi.putArray("importPackage");
                bundle.importPackage().forEach(c -> importPackage.add(c.name()));
                ArrayNode exportPackage = osgi.putArray("exportPackage");
                bundle.exportPackage().forEach(c -> exportPackage.add(c.name()));
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        } catch (Exception e) {
            return failure(new ToolingException("Dependency analysis failed: "
                    + e.getMessage(), e));
        }
    }

    private void analyzePom(ObjectNode root, Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            var document = factory.newDocumentBuilder().parse(pom.toFile());
            Element project = document.getDocumentElement();
            ObjectNode maven = root.putObject("maven");
            maven.put("groupId", childText(project, "groupId"));
            maven.put("artifactId", childText(project, "artifactId"));
            maven.put("version", childText(project, "version"));
            maven.put("packaging", childText(project, "packaging"));
            ArrayNode dependencies = maven.putArray("dependencies");
            NodeList dependencyNodes = document.getElementsByTagName("dependency");
            for (int i = 0; i < dependencyNodes.getLength(); i++) {
                Element dependency = (Element) dependencyNodes.item(i);
                if (!"dependency".equals(dependency.getTagName())) {
                    continue;
                }
                ObjectNode node = dependencies.addObject();
                node.put("groupId", childText(dependency, "groupId"));
                node.put("artifactId", childText(dependency, "artifactId"));
                node.put("version", childText(dependency, "version"));
                node.put("scope", childText(dependency, "scope"));
            }
            ArrayNode modules = maven.putArray("modules");
            NodeList moduleNodes = document.getElementsByTagName("module");
            for (int i = 0; i < moduleNodes.getLength(); i++) {
                if ("module".equals(moduleNodes.item(i).getNodeName())) {
                    modules.add(moduleNodes.item(i).getTextContent().trim());
                }
            }
        } catch (Exception e) {
            throw new ToolingException("Cannot parse pom.xml at " + pom + ": "
                    + e.getMessage(), e);
        }
    }

    private static String childText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() == 0) {
            return "";
        }
        return children.item(0).getTextContent().trim();
    }
}

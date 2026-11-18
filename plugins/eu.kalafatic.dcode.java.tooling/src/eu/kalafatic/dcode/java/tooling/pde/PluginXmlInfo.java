package eu.kalafatic.dcode.java.tooling.pde;

import eu.kalafatic.dcode.java.tooling.jdt.ToolingException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured inspection of a plugin.xml (extension points and extensions). Plain JDK
 * DOM parsing; no PDE runtime required.
 */
@SuppressWarnings("restriction")
public final class PluginXmlInfo {

    /** One extension point definition. */
    public record ExtensionPoint(String id, String name, String schema) {
    }

    /** One extension registration. */
    public record Extension(String point, String id, String name) {
    }

    private final List<ExtensionPoint> extensionPoints = new ArrayList<>();
    private final List<Extension> extensions = new ArrayList<>();

    public PluginXmlInfo(Path pluginXmlFile) {
        if (!java.nio.file.Files.isRegularFile(pluginXmlFile)) {
            return; // no plugin.xml: an extension-less bundle
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            var document = factory.newDocumentBuilder().parse(pluginXmlFile.toFile());
            Element plugin = document.getDocumentElement();
            NodeList points = plugin.getElementsByTagName("extension-point");
            for (int i = 0; i < points.getLength(); i++) {
                Element point = (Element) points.item(i);
                if (!"extension-point".equals(point.getTagName())) {
                    continue;
                }
                extensionPoints.add(new ExtensionPoint(
                        point.getAttribute("id"), point.getAttribute("name"),
                        point.getAttribute("schema")));
            }
            NodeList extensionNodes = plugin.getElementsByTagName("extension");
            for (int i = 0; i < extensionNodes.getLength(); i++) {
                Element extension = (Element) extensionNodes.item(i);
                if (!"extension".equals(extension.getTagName())) {
                    continue;
                }
                extensions.add(new Extension(extension.getAttribute("point"),
                        extension.getAttribute("id"), extension.getAttribute("name")));
            }
        } catch (Exception e) {
            throw new ToolingException("Cannot parse " + pluginXmlFile + ": "
                    + e.getMessage(), e);
        }
    }

    public List<ExtensionPoint> extensionPoints() {
        return extensionPoints;
    }

    public List<Extension> extensions() {
        return extensions;
    }
}

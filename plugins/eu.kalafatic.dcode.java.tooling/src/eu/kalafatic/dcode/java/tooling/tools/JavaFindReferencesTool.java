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
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reference search for a type or method (real JDT search engine). */
@SuppressWarnings("restriction")
public final class JavaFindReferencesTool extends AbstractJavaTool {

    public JavaFindReferencesTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_find_references",
                "Find all references to a type or method in a repository project.",
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
            IJavaElement element = resolveElement(handle, kind, name);
            List<SearchMatch> matches = search(element, IJavaSearchConstants.REFERENCES);
            ObjectNode root = object();
            root.put("target", name);
            root.put("referenceCount", matches.size());
            ArrayNode references = root.putArray("references");
            for (SearchMatch match : matches) {
                ObjectNode node = references.addObject();
                node.put("resource", match.getResource() != null
                        && match.getResource().getLocation() != null
                        ? match.getResource().getLocation().toOSString() : "");
                node.put("offset", match.getOffset());
                node.put("length", match.getLength());
                node.put("accuracy", match.getAccuracy() == SearchMatch.A_ACCURATE
                        ? "ACCURATE" : "INACCURATE");
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        } catch (Exception e) {
            return failure(new ToolingException("Reference search failed: "
                    + e.getMessage(), e));
        }
    }

    static IJavaElement resolveElement(JavaWorkspaceManager.ProjectHandle handle,
                                       String kind, String name) throws JavaModelException {
        if ("method".equals(kind)) {
            int hash = name.lastIndexOf('#');
            if (hash <= 0) {
                throw new ToolingException("Method name must have the form 'Type#method'");
            }
            IType type = handle.javaProject().findType(name.substring(0, hash));
            if (type == null) {
                throw new ToolingException("Type not found: " + name.substring(0, hash));
            }
            for (IMethod method : type.getMethods()) {
                if (method.getElementName().equals(name.substring(hash + 1))) {
                    return method;
                }
            }
            throw new ToolingException("Method not found: " + name);
        }
        IType type = handle.javaProject().findType(name);
        if (type == null) {
            throw new ToolingException("Type not found: " + name);
        }
        return type;
    }

    static List<SearchMatch> search(IJavaElement element, int limitTo)
            throws org.eclipse.core.runtime.CoreException {
        SearchPattern pattern = SearchPattern.createPattern(element, limitTo);
        // Workspace scope: references across sibling modules/repositories are found.
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        List<SearchMatch> matches = new ArrayList<>();
        new SearchEngine().search(pattern, new SearchParticipant[]{
                        SearchEngine.getDefaultSearchParticipant()}, scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        matches.add(match);
                    }
                }, new NullProgressMonitor());
        return matches;
    }
}

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
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameFieldProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeProcessor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

import java.util.Map;

/**
 * Safe rename refactoring for types, methods and fields (JDT corext processors, headless
 * LTK execution). Verifies repository writability; after the change the project index is
 * rebuilt and diagnostics are re-run so the result reports the post-rename state.
 */
@SuppressWarnings("restriction")
public final class JavaRenameTool extends AbstractJavaTool {

    public JavaRenameTool(JavaWorkspaceManager workspaceManager) {
        super(workspaceManager);
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("java_rename",
                "Rename a Java type, method or field with reference updating and verify "
                        + "the result with compiler diagnostics.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\","
                        + "\"description\":\"workspace-relative path of the declaring file\"},"
                        + "\"kind\":{\"type\":\"string\",\"description\":\"type, method or field\"},"
                        + "\"name\":{\"type\":\"string\"},\"newName\":{\"type\":\"string\"}},"
                        + "\"required\":[\"path\",\"kind\",\"name\",\"newName\"]}",
                false, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        try {
            String pathArg = arg(arguments, "path");
            String kind = arg(arguments, "kind");
            String name = arg(arguments, "name");
            String newName = arg(arguments, "newName");
            if (pathArg == null || kind == null || name == null || newName == null) {
                throw new ToolingException("Arguments 'path', 'kind', 'name' and 'newName' "
                        + "are required");
            }
            var repository = workspaceManager.repositoryForPath(context.workspace(), pathArg);
            requireWritable(context, repository);
            var handle = workspaceManager.projectForPath(context.workspace(), pathArg);
            var unit = workspaceManager.compilationUnit(handle, context.workspaceRoot().toString(), pathArg);

            IJavaRenameProcessor processor = switch (kind) {
                case "type" -> {
                    IType type = findType(unit, name);
                    yield new TypeRename(type);
                }
                case "method" -> {
                    IMethod method = findMethod(unit, name);
                    yield new MethodRename(method);
                }
                case "field" -> {
                    IField field = findField(unit, name);
                    yield new FieldRename(field);
                }
                default -> throw new ToolingException("Unknown kind '" + kind
                        + "' (expected type, method or field)");
            };
            processor.prepare(newName);
            Change change = processor.createChange();
            if (change == null) {
                throw new ToolingException("No changes computed by the refactoring");
            }
            change.perform(new NullProgressMonitor());
            workspaceManager.rebuildIndex(handle);
            var problems = workspaceManager.diagnostics(handle);
            long errors = problems.stream().filter(p -> p.severity().equals("ERROR")).count();

            ObjectNode root = object();
            root.put("renamed", kind + " " + name + " -> " + newName);
            root.put("file", pathArg);
            root.put("changePerformed", true);
            root.put("errorCount", errors);
            ArrayNode diagnostics = root.putArray("diagnostics");
            for (var problem : problems) {
                if (problem.severity().equals("ERROR")) {
                    ObjectNode node = diagnostics.addObject();
                    node.put("severity", problem.severity());
                    node.put("message", problem.message());
                    node.put("file", problem.file());
                    node.put("line", problem.line());
                }
            }
            return ToolResult.ok(root.toString());
        } catch (ToolingException e) {
            return failure(e);
        } catch (Exception e) {
            return failure(new ToolingException("Rename failed: " + e.getMessage(), e));
        }
    }

    private IType findType(org.eclipse.jdt.core.ICompilationUnit unit, String name)
            throws org.eclipse.jdt.core.JavaModelException {
        for (IType type : unit.getAllTypes()) {
            if (type.getElementName().equals(name)) {
                return type;
            }
        }
        throw new ToolingException("Type '" + name + "' not found in " + unit.getElementName());
    }

    private IMethod findMethod(org.eclipse.jdt.core.ICompilationUnit unit, String name)
            throws org.eclipse.jdt.core.JavaModelException {
        for (IType type : unit.getAllTypes()) {
            for (IMethod method : type.getMethods()) {
                if (method.getElementName().equals(name)) {
                    return method;
                }
            }
        }
        throw new ToolingException("Method '" + name + "' not found in " + unit.getElementName());
    }

    private IField findField(org.eclipse.jdt.core.ICompilationUnit unit, String name)
            throws org.eclipse.jdt.core.JavaModelException {
        for (IType type : unit.getAllTypes()) {
            for (IField field : type.getFields()) {
                if (field.getElementName().equals(name)) {
                    return field;
                }
            }
        }
        throw new ToolingException("Field '" + name + "' not found in " + unit.getElementName());
    }

    private interface IJavaRenameProcessor {
        void prepare(String newName);

        Change createChange() throws Exception;
    }

    private static final class TypeRename implements IJavaRenameProcessor {
        private final RenameTypeProcessor processor;

        TypeRename(IType type) {
            processor = new RenameTypeProcessor(type);
            processor.setUpdateReferences(true);
        }

        @Override
        public void prepare(String newName) {
            processor.setNewElementName(newName);
        }

        @Override
        public Change createChange() throws Exception {
            RefactoringStatus status = processor.checkInitialConditions(new NullProgressMonitor());
            if (status.hasError()) {
                throw new ToolingException("Rename preconditions failed: "
                        + status.getMessageMatchingSeverity(RefactoringStatus.ERROR));
            }
            // The final-conditions check initializes the change manager in the
            // corext processors; createChange assumes it has run.
            org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext
                    conditions = new org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext();
            conditions.add(new org.eclipse.ltk.core.refactoring.participants.ResourceChangeChecker());
            conditions.add(new org.eclipse.ltk.core.refactoring.participants.ValidateEditChecker(null));
            status = processor.checkFinalConditions(new NullProgressMonitor(), conditions);
            if (status.hasError()) {
                throw new ToolingException("Rename conditions failed: "
                        + status.getMessageMatchingSeverity(RefactoringStatus.ERROR));
            }
            return processor.createChange(new NullProgressMonitor());
        }
    }

    private static final class MethodRename implements IJavaRenameProcessor {
        private final RenameMethodProcessorAccess processor;

        MethodRename(IMethod method) {
            processor = new RenameMethodProcessorAccess(method);
            processor.setUpdateReferences(true);
        }

        @Override
        public void prepare(String newName) {
            processor.setNewElementName(newName);
        }

        @Override
        public Change createChange() throws Exception {
            RefactoringStatus status = processor.checkInitialConditions(new NullProgressMonitor());
            if (status.hasError()) {
                throw new ToolingException("Rename preconditions failed: "
                        + status.getMessageMatchingSeverity(RefactoringStatus.ERROR));
            }
            // The final-conditions check initializes the change manager in the
            // corext processors; createChange assumes it has run.
            org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext
                    conditions = new org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext();
            conditions.add(new org.eclipse.ltk.core.refactoring.participants.ResourceChangeChecker());
            conditions.add(new org.eclipse.ltk.core.refactoring.participants.ValidateEditChecker(null));
            status = processor.checkFinalConditions(new NullProgressMonitor(), conditions);
            if (status.hasError()) {
                throw new ToolingException("Rename conditions failed: "
                        + status.getMessageMatchingSeverity(RefactoringStatus.ERROR));
            }
            return processor.createChange(new NullProgressMonitor());
        }
    }

    private static final class FieldRename implements IJavaRenameProcessor {
        private final RenameFieldProcessorAccess processor;

        FieldRename(IField field) {
            processor = new RenameFieldProcessorAccess(field);
            processor.setUpdateReferences(true);
        }

        @Override
        public void prepare(String newName) {
            processor.setNewElementName(newName);
        }

        @Override
        public Change createChange() throws Exception {
            RefactoringStatus status = processor.checkInitialConditions(new NullProgressMonitor());
            if (status.hasError()) {
                throw new ToolingException("Rename preconditions failed: "
                        + status.getMessageMatchingSeverity(RefactoringStatus.ERROR));
            }
            // The final-conditions check initializes the change manager in the
            // corext processors; createChange assumes it has run.
            org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext
                    conditions = new org.eclipse.ltk.core.refactoring.participants.CheckConditionsContext();
            conditions.add(new org.eclipse.ltk.core.refactoring.participants.ResourceChangeChecker());
            conditions.add(new org.eclipse.ltk.core.refactoring.participants.ValidateEditChecker(null));
            status = processor.checkFinalConditions(new NullProgressMonitor(), conditions);
            if (status.hasError()) {
                throw new ToolingException("Rename conditions failed: "
                        + status.getMessageMatchingSeverity(RefactoringStatus.ERROR));
            }
            return processor.createChange(new NullProgressMonitor());
        }
    }

    /** Protected-constructor access for the internal rename processors. */
    private static final class RenameMethodProcessorAccess extends RenameMethodProcessor {
        RenameMethodProcessorAccess(IMethod method) {
            super(method);
        }

        @Override
        public String getDelegateUpdatingTitle(boolean add) {
            return null; // delegate updating is a UI-only feature; headless rename does not use it
        }
    }

    /** Protected-constructor access for the internal rename processors. */
    private static final class RenameFieldProcessorAccess extends RenameFieldProcessor {
        RenameFieldProcessorAccess(IField field) {
            super(field);
        }
    }
}

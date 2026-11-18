package eu.kalafatic.dcode.java.tooling.jdt;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AST parsing helpers with type resolution (bindings), using the batch-compiler
 * environment (source directories + resolved classpath) — no workspace dependency.
 */
@SuppressWarnings("restriction")
public final class AstHelper {

    private AstHelper() {
    }

    /** Parses one Java source file with bindings resolved against the environment. */
    public static CompilationUnit parse(Path sourceFile, List<Path> sourceDirectories,
                                        String classpath) {
        try {
            String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setStatementsRecovery(true);
            String[] sourcepath = sourceDirectories.stream()
                    .map(Path::toString).toArray(String[]::new);
            String[] classpathEntries = classpath == null || classpath.isBlank()
                    ? new String[0] : classpath.split(java.io.File.pathSeparator);
            parser.setEnvironment(classpathEntries, sourcepath, null, true);
            parser.setUnitName(sourceFile.getFileName().toString());
            parser.setSource(source.toCharArray());
            return (CompilationUnit) parser.createAST(null);
        } catch (Exception e) {
            throw new ToolingException("AST parsing failed for " + sourceFile + ": "
                    + e.getMessage(), e);
        }
    }

    /** One structured type declaration. */
    public record TypeInfo(String name, String qualifiedName, String kind,
                           String superclass, List<String> interfaces, int line) {
    }

    /** One structured method declaration. */
    public record MethodInfo(String name, String signature, String returnType,
                             List<String> parameterTypes, boolean isStatic,
                             int line) {
    }

    /** One structured field declaration. */
    public record FieldInfo(String name, String type, boolean isStatic, int line) {
    }

    /** One import statement with usage analysis. */
    public record ImportInfo(String name, boolean onDemand, boolean resolved, int usages) {
    }

    /** Collects the structured declarations of a compilation unit. */
    public static List<TypeInfo> collectTypes(CompilationUnit unit) {
        List<TypeInfo> result = new ArrayList<>();
        unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                ITypeBinding binding = node.resolveBinding();
                String qualifiedName = binding != null
                        ? binding.getQualifiedName() : node.getName().getIdentifier();
                String superclass = binding != null && binding.getSuperclass() != null
                        ? binding.getSuperclass().getQualifiedName() : "";
                List<String> interfaces = new ArrayList<>();
                if (binding != null) {
                    for (ITypeBinding intf : binding.getInterfaces()) {
                        interfaces.add(intf.getQualifiedName());
                    }
                }
                result.add(new TypeInfo(node.getName().getIdentifier(), qualifiedName,
                        binding != null ? bindingTypeKind(binding) : "unknown",
                        superclass, interfaces, lineOf(unit, node)));
                return true;
            }
        });
        return result;
    }

    /** Collects the methods of a compilation unit. */
    public static List<MethodInfo> collectMethods(CompilationUnit unit) {
        List<MethodInfo> result = new ArrayList<>();
        unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                List<String> parameters = new ArrayList<>();
                if (binding != null) {
                    for (ITypeBinding parameter : binding.getParameterTypes()) {
                        parameters.add(parameter.getQualifiedName());
                    }
                }
                result.add(new MethodInfo(node.getName().getIdentifier(),
                        binding != null ? binding.getKey() : node.getName().getIdentifier(),
                        binding != null && binding.getReturnType() != null
                                ? binding.getReturnType().getQualifiedName() : "",
                        parameters,
                        Modifier.isStatic(node.getModifiers()),
                        lineOf(unit, node)));
                return true;
            }
        });
        return result;
    }

    /** Collects the fields of a compilation unit. */
    public static List<FieldInfo> collectFields(CompilationUnit unit) {
        List<FieldInfo> result = new ArrayList<>();
        unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.FieldDeclaration node) {
                ITypeBinding typeBinding = node.getType().resolveBinding();
                String type = typeBinding != null ? typeBinding.getQualifiedName() : "";
                for (Object fragment : node.fragments()) {
                    if (fragment instanceof org.eclipse.jdt.core.dom.VariableDeclarationFragment var) {
                        result.add(new FieldInfo(var.getName().getIdentifier(), type,
                                Modifier.isStatic(node.getModifiers()), lineOf(unit, node)));
                    }
                }
                return true;
            }
        });
        return result;
    }

    /** Import usage analysis: resolution status and reference count per import. */
    public static List<ImportInfo> analyzeImports(CompilationUnit unit) {
        Map<String, Integer> usageCount = new java.util.HashMap<>();
        unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding instanceof ITypeBinding typeBinding) {
                    String fq = typeBinding.getQualifiedName();
                    usageCount.merge(fq, 1, Integer::sum);
                }
                return true;
            }
        });
        List<ImportInfo> result = new ArrayList<>();
        for (Object declaration : unit.imports()) {
            if (declaration instanceof ImportDeclaration importDeclaration) {
                String name = importDeclaration.getName().getFullyQualifiedName();
                boolean onDemand = importDeclaration.isOnDemand();
                IBinding binding = importDeclaration.resolveBinding();
                int usages = 0;
                if (binding instanceof ITypeBinding typeBinding) {
                    usages = usageCount.getOrDefault(typeBinding.getQualifiedName(), 0);
                }
                result.add(new ImportInfo(name, onDemand, binding != null, usages));
            }
        }
        return result;
    }

    /** Collects method-call bindings of a compilation unit (callee types+names). */
    public static List<CallSite> collectCalls(CompilationUnit unit) {
        List<CallSite> result = new ArrayList<>();
        unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.MethodInvocation node) {
                IMethodBinding binding = node.resolveMethodBinding();
                String callee = binding != null
                        ? (binding.getDeclaringClass() != null
                        ? binding.getDeclaringClass().getQualifiedName() + "#"
                        + binding.getName() : binding.getName())
                        : node.getName().getIdentifier();
                result.add(new CallSite(callee, lineOf(unit, node),
                        binding != null ? binding.getKey() : null));
                return true;
            }
        });
        return result;
    }

    /** One method-call site. */
    public record CallSite(String callee, int line, String bindingKey) {
    }

    private static String bindingTypeKind(ITypeBinding binding) {
        if (binding.isInterface()) {
            return "interface";
        }
        if (binding.isEnum()) {
            return "enum";
        }
        if (binding.isAnnotation()) {
            return "annotation";
        }
        if (binding.isRecord()) {
            return "record";
        }
        return "class";
    }

    private static int lineOf(CompilationUnit unit, org.eclipse.jdt.core.dom.ASTNode node) {
        int position = node.getStartPosition();
        return position >= 0 ? unit.getLineNumber(position) : -1;
    }
}

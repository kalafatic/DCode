package eu.kalafatic.dcode.tests.javatoo;

import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import eu.kalafatic.dcode.repositories.service.InMemoryWorkspaceService;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import eu.kalafatic.dcode.repositories.workspace.RepositoryWorkspaceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Shared fixture builder: real Java test projects on disk (Maven-style modules, an
 * Eclipse .classpath project and OSGi bundles), plus the DCode service stack to execute
 * tools against them.
 */
public final class JavaToolingFixtures {

    public final Path root;
    public final JavaWorkspaceManager workspaceManager;
    public final Workspace workspace;
    public final InMemoryWorkspaceService workspaceService;
    public final Session session;
    public final ToolContext context;

    public JavaToolingFixtures() throws Exception {
        root = Files.createTempDirectory("dcode-java-tooling-test");

        buildMavenRepo();
        buildEclipseProject();
        buildOsgiBundles();

        JGitEngine gitEngine = new JGitEngine();
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        workspaceService = new InMemoryWorkspaceService(bus);
        var ws = workspaceService.createWorkspace("java-fixtures", WorkspaceMode.LOCAL,
                root.toString());
        ws = workspaceService.addRepository(ws.id(), repo("javarepo",
                root.resolve("javarepo"), RepositoryAccessPolicy.READ_WRITE));
        ws = workspaceService.addRepository(ws.id(), repo("eclipserepo",
                root.resolve("eclipserepo"), RepositoryAccessPolicy.READ_WRITE));
        ws = workspaceService.addRepository(ws.id(), repo("osgirepo",
                root.resolve("osgirepo"), RepositoryAccessPolicy.READ_WRITE));
        this.workspace = ws;

        RepositoryWorkspaceManager workspaceManager0 = new RepositoryWorkspaceManager(gitEngine);
        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager0::repositoryDir);

        workspaceManager = new JavaWorkspaceManager();
        session = Session.builder().id("java-session").title("java tools")
                .workspaceId(workspace.id()).state(SessionState.RUNNING).build();
        context = new ToolContext(session, workspace, root, operations, null,
                () -> false, null);
    }

    /** Workspace with the same repositories but one marked READ_ONLY. */
    public ToolContext readOnlyContext(String repositoryName) {
        Workspace updated = workspace;
        List<RepositoryRef> repositories = new java.util.ArrayList<>();
        for (RepositoryRef repository : workspace.repositories()) {
            repositories.add(repository.name().equals(repositoryName)
                    ? new RepositoryRef(repository.id(), repository.name(),
                    repository.localPath(), repository.remoteUrl(), repository.branch(),
                    RepositoryAccessPolicy.READ_ONLY, repository.addedAt())
                    : repository);
        }
        updated = updated.withRepositories(repositories);
        return new ToolContext(session, updated, root, context.repositories(),
                context.gitAccounts(), () -> false, null);
    }

    private RepositoryRef repo(String name, Path dir, RepositoryAccessPolicy policy) {
        return new RepositoryRef(name, name, dir.toString(), null, "master", policy,
                Instant.now());
    }

    // ---- fixtures ----

    private void buildMavenRepo() throws IOException {
        Path repo = root.resolve("javarepo");

        // module core: interface + implementation
        Path core = repo.resolve("core");
        write(core.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>com.example</groupId><artifactId>core</artifactId>"
                        + "<version>1.0.0</version><packaging>jar</packaging></project>");
        write(core.resolve("src/main/java/com/example/core/Greeting.java"),
                "package com.example.core;\n"
                        + "public interface Greeting {\n"
                        + "    String greet(String name);\n"
                        + "}\n");
        write(core.resolve("src/main/java/com/example/core/GreetingImpl.java"),
                "package com.example.core;\n"
                        + "public class GreetingImpl implements Greeting {\n"
                        + "    private final String prefix;\n"
                        + "    public GreetingImpl(String prefix) {\n"
                        + "        this.prefix = prefix;\n"
                        + "    }\n"
                        + "    @Override\n"
                        + "    public String greet(String name) {\n"
                        + "        return prefix + \", \" + name + \"!\";\n"
                        + "    }\n"
                        + "}\n");
        // compile core so the app module can resolve Greeting via target/classes
        compileWithJavac(core, List.of(
                core.resolve("src/main/java/com/example/core/Greeting.java"),
                core.resolve("src/main/java/com/example/core/GreetingImpl.java")));

        // module app: uses core + has one broken file and one caller
        Path app = repo.resolve("app");
        write(app.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>com.example</groupId><artifactId>app</artifactId>"
                        + "<version>1.0.0</version><packaging>jar</packaging>"
                        + "<dependencies><dependency><groupId>com.example</groupId>"
                        + "<artifactId>core</artifactId><version>1.0.0</version>"
                        + "</dependency></dependencies></project>");
        write(app.resolve("src/main/java/com/example/app/App.java"),
                "package com.example.app;\n"
                        + "import com.example.core.Greeting;\n"
                        + "import com.example.core.GreetingImpl;\n"
                        + "public class App {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        Greeting greeting = new GreetingImpl(\"Hello\");\n"
                        + "        System.out.println(greeting.greet(\"World\"));\n"
                        + "    }\n"
                        + "}\n");
        write(app.resolve("src/main/java/com/example/app/Broken.java"),
                "package com.example.app;\n"
                        + "import com.nonexistent.dep.MissingType;\n"
                        + "public class Broken {\n"
                        + "    private MissingType field;\n"
                        + "}\n");
        // module invalid: syntax error
        Path invalid = repo.resolve("invalid");
        write(invalid.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>com.example</groupId><artifactId>invalid</artifactId>"
                        + "<version>1.0.0</version><packaging>jar</packaging></project>");
        write(invalid.resolve("src/main/java/com/example/invalid/SyntaxBroken.java"),
                "package com.example.invalid;\n"
                        + "public class SyntaxBroken {\n"
                        + "    public void broken( {\n" // missing parenthesis
                        + "    }\n"
                        + "}\n");
    }

    private void buildEclipseProject() throws IOException {
        Path repo = root.resolve("eclipserepo");
        Path project = repo.resolve("sample");
        write(project.resolve(".project"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<projectDescription>\n"
                        + "  <name>sample</name>\n"
                        + "  <buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name>"
                        + "<arguments/></buildCommand></buildSpec>\n"
                        + "  <natures><nature>org.eclipse.jdt.core.javanature</nature></natures>\n"
                        + "</projectDescription>\n");
        write(project.resolve(".classpath"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<classpath>\n"
                        + "  <classpathentry kind=\"src\" path=\"src\"/>\n"
                        + "  <classpathentry kind=\"con\" path=\"org.eclipse.jdt.launching.JRE_CONTAINER\"/>\n"
                        + "  <classpathentry kind=\"output\" path=\"bin\"/>\n"
                        + "</classpath>\n");
        write(project.resolve("src/example/Sample.java"),
                "package example;\n"
                        + "public class Sample {\n"
                        + "    public int add(int a, int b) {\n"
                        + "        return a + b;\n"
                        + "    }\n"
                        + "}\n");
    }

    private void buildOsgiBundles() throws IOException {
        Path repo = root.resolve("osgirepo");

        Path api = repo.resolve("com.example.api");
        write(api.resolve("META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\n"
                        + "Bundle-ManifestVersion: 2\n"
                        + "Bundle-SymbolicName: com.example.api;singleton:=true\n"
                        + "Bundle-Version: 1.0.0.qualifier\n"
                        + "Export-Package: com.example.api;version=\"1.0.0\"\n");
        write(api.resolve("src/com/example/api/Api.java"),
                "package com.example.api;\n"
                        + "public interface Api {\n"
                        + "    String describe();\n"
                        + "}\n");
        write(api.resolve("plugin.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<plugin>\n"
                        + "  <extension-point id=\"providers\" name=\"Providers\" schema=\"schema/providers.exsd\"/>\n"
                        + "</plugin>\n");

        Path impl = repo.resolve("com.example.impl");
        write(impl.resolve("META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\n"
                        + "Bundle-ManifestVersion: 2\n"
                        + "Bundle-SymbolicName: com.example.impl\n"
                        + "Bundle-Version: 1.0.0.qualifier\n"
                        + "Require-Bundle: com.example.api;bundle-version=\"1.0.0\"\n"
                        + "Import-Package: com.example.api;version=\"1.0.0\"\n");
        write(impl.resolve("plugin.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<plugin>\n"
                        + "  <extension point=\"com.example.api.providers\" id=\"default\" name=\"Default Provider\"/>\n"
                        + "</plugin>\n");

        Path broken = repo.resolve("com.example.broken");
        write(broken.resolve("META-INF/MANIFEST.MF"),
                "Manifest-Version: 1.0\n"
                        + "Bundle-ManifestVersion: 2\n"
                        + "Bundle-SymbolicName: com.example.broken\n"
                        + "Bundle-Version: 1.0.0.qualifier\n"
                        + "Import-Package: com.nonexistent.pkg\n");
    }

    private static void compileWithJavac(Path module, List<Path> sources) throws IOException {
        Path out = module.resolve("target/classes");
        Files.createDirectories(out);
        List<String> command = new java.util.ArrayList<>(List.of(
                System.getProperty("java.home") + "/bin/javac",
                "-d", out.toString()));
        sources.forEach(source -> command.add(source.toString()));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("javac failed: " + new String(output));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("javac interrupted", e);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}

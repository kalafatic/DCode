package eu.kalafatic.dcode.repositories.internal;

import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.service.ServiceAvailabilityGate;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.repositories.git.GitEngine;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import eu.kalafatic.dcode.repositories.service.InMemoryWorkspaceService;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import eu.kalafatic.dcode.repositories.workspace.RepositoryWorkspaceManager;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Registers the repository engine services in OSGi (family convention: manual wiring).
 * Startup retries through a service-availability gate; bundle start order is irrelevant.
 */
public final class RepositoriesActivator implements BundleActivator {

    private BundleContext context;
    private ServiceRegistration<WorkspaceService> workspaceRegistration;
    private ServiceRegistration<GitEngine> gitEngineRegistration;
    private ServiceRegistration<RepositoryOperations> operationsRegistration;
    private ServiceAvailabilityGate gate;

    @Override
    public void start(BundleContext context) {
        this.context = context;
        gate = new ServiceAvailabilityGate(context, new Class<?>[]{
                EventBus.class, DCodeLifecycle.class}, this::wire);
        gate.runIfAvailable();
    }

    private void wire() {
        EventBus eventBus = context.getService(context.getServiceReference(EventBus.class));
        DCodeLifecycle lifecycle = context.getService(
                context.getServiceReference(DCodeLifecycle.class));

        GitEngine gitEngine = new JGitEngine();
        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        InMemoryWorkspaceService workspaceService = new InMemoryWorkspaceService(eventBus);
        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);

        // Register the in-memory registry only when no durable implementation exists;
        // the persistence bundle registers the SQLite-backed service with a higher
        // ranking and consumers resolve it dynamically.
        if (context.getServiceReference(WorkspaceService.class) == null) {
            workspaceRegistration = context.registerService(WorkspaceService.class,
                    workspaceService, null);
        }
        gitEngineRegistration = context.registerService(GitEngine.class, gitEngine, null);
        operationsRegistration = context.registerService(RepositoryOperations.class,
                operations, null);

        lifecycle.registerAction(LifecycleStage.REPOSITORIES, "repositories-services",
                new DCodeLifecycle.LifecycleAction() {
                    @Override
                    public void start() {
                        DCodeLog.info("repositories", "Repository engine started");
                    }

                    @Override
                    public void stop() {
                        DCodeLog.info("repositories", "Repository engine stopped");
                    }
                });
        try {
            lifecycle.startThrough(LifecycleStage.REPOSITORIES);
        } catch (Exception e) {
            DCodeLog.error("repositories", "Failed to advance lifecycle to REPOSITORIES", e);
        }
    }

    @Override
    public void stop(BundleContext context) {
        if (workspaceRegistration != null) {
            workspaceRegistration.unregister();
        }
        if (gitEngineRegistration != null) {
            gitEngineRegistration.unregister();
        }
        if (operationsRegistration != null) {
            operationsRegistration.unregister();
        }
        if (gate != null) {
            gate.close();
        }
        workspaceRegistration = null;
        gitEngineRegistration = null;
        operationsRegistration = null;
    }
}

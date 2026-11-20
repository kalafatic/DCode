package eu.kalafatic.dcode.server.internal;

import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistry;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.port.DCodePorts;
import eu.kalafatic.dcode.core.port.PortAllocator;
import eu.kalafatic.dcode.core.service.DynamicServiceLookup;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.ServiceAvailabilityGate;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.tool.ToolRegistry;
import eu.kalafatic.dcode.server.DCodeApi;
import eu.kalafatic.dcode.server.DCodeHttpServer;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

import java.util.function.Supplier;

/**
 * Starts the NanoHTTPD API server at the SERVER lifecycle stage. Startup is retried
 * through a service-availability gate: bundle start order is irrelevant.
 */
public final class ServerActivator implements BundleActivator {

    private DCodeHttpServer server;
    private DynamicServiceLookup services;
    private ServiceAvailabilityGate gate;
    private BundleContext context;

    @Override
    public void start(BundleContext context) {
        this.context = context;
        gate = new ServiceAvailabilityGate(context, new Class<?>[]{
                EventBus.class, DCodeLifecycle.class, ConfigurationService.class,
                ProviderRegistry.class, RuntimeRegistry.class, ToolRegistry.class,
                SessionExecutor.class},
                this::wire);
        gate.runIfAvailable();
    }

    private void wire() {
        EventBus eventBus = context.getService(context.getServiceReference(EventBus.class));
        DCodeLifecycle lifecycle = context.getService(
                context.getServiceReference(DCodeLifecycle.class));
        ConfigurationService configuration = context.getService(
                context.getServiceReference(ConfigurationService.class));
        ProviderRegistry providers = context.getService(
                context.getServiceReference(ProviderRegistry.class));
        RuntimeRegistry runtimes = context.getService(
                context.getServiceReference(RuntimeRegistry.class));
        ToolRegistry tools = context.getService(context.getServiceReference(ToolRegistry.class));
        SessionExecutor executor = context.getService(
                context.getServiceReference(SessionExecutor.class));

        services = new DynamicServiceLookup(context);

        Supplier<SessionService> sessionSupplier = () -> services.get(SessionService.class);
        Supplier<WorkspaceService> workspaceSupplier = () -> services.get(WorkspaceService.class);
        Supplier<ReviewService> reviewSupplier = () -> services.get(ReviewService.class);

        DCodeApi api = new DCodeApi(sessionSupplier, workspaceSupplier, reviewSupplier,
                providers, runtimes, tools, executor, lifecycle, configuration, eventBus,
                () -> services.get(eu.kalafatic.dcode.harness.tool.ToolEngine.class),
                () -> services.get(eu.kalafatic.dcode.repositories.service.RepositoryOperations.class),
                () -> services.get(eu.kalafatic.dcode.config.git.GitAccountManager.class),
                () -> services.get(eu.kalafatic.dcode.harness.registry.HarnessRegistry.class),
                () -> services.get(eu.kalafatic.dcode.config.agent.AgentRegistry.class));

        lifecycle.registerAction(LifecycleStage.SERVER, "http-server",
                new DCodeLifecycle.LifecycleAction() {
                    @Override
                    public void start() throws Exception {
                        int configuredPort = configuration.getConfiguration().httpPort();
                        int port = configuredPort > 0 ? configuredPort
                                : PortAllocator.allocate(DCodePorts.HTTP,
                                DCodePorts.HTTP_FALLBACK_RANGE);
                        String bindAddress = configuration.getConfiguration().bindAddress();
                        server = new DCodeHttpServer(bindAddress, port, api,
                                new eu.kalafatic.dcode.server.StaticContent.BundleEntry(
                                        context.getBundle()));
                        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                        // published for ops tooling and runtime-verification tests
                        System.setProperty("dcode.server.port", String.valueOf(port));
                        api.setHttpPortActual(port, bindAddress);
                        DCodeLog.info("server", "DCode HTTP API listening on "
                                + bindAddress + ":" + port + " ("
                                + (configuredPort > 0 ? "configured" : "allocated") + ")");
                    }

                    @Override
                    public void stop() {
                        if (server != null) {
                            server.stop();
                            server = null;
                            DCodeLog.info("server", "DCode HTTP API stopped");
                        }
                    }
                });
        try {
            lifecycle.startThrough(LifecycleStage.SERVER);
        } catch (Exception e) {
            DCodeLog.error("server", "Failed to advance lifecycle to SERVER", e);
        }
    }

    @Override
    public void stop(BundleContext context) {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (services != null) {
            services.close();
        }
        if (gate != null) {
            gate.close();
        }
    }
}

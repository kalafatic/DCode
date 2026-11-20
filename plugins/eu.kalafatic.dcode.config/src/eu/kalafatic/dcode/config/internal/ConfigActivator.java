package eu.kalafatic.dcode.config.internal;

import eu.kalafatic.dcode.config.credentials.ConfigurationCredentialVault;
import eu.kalafatic.dcode.config.credentials.CredentialVault;
import eu.kalafatic.dcode.config.git.GitAccountManager;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.config.routing.ModelRouter;
import eu.kalafatic.dcode.config.runtime.RuntimeManager;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistry;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.service.ServiceAvailabilityGate;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Registers the configuration services in OSGi (manual wiring, family convention).
 *
 * <p>The authoritative {@link RuntimeManager} is registered under both the
 * {@link RuntimeRegistry} (management) and {@link ProviderRegistry} (harness) contracts:
 * one registry, two views. ENR (family default inference engine) and the local Ollama
 * instance are seeded as the default runtime registrations when no persisted
 * registrations exist; users can add, edit, disable and remove runtimes at will.
 * Startup retries through a service-availability gate so bundle start order is
 * irrelevant.</p>
 */
public final class ConfigActivator implements BundleActivator {

    private BundleContext context;
    private ServiceRegistration<RuntimeRegistry> runtimeRegistryRegistration;
    private ServiceRegistration<ProviderRegistry> providerRegistryRegistration;
    private ServiceRegistration<ModelRouter> modelRouterRegistration;
    private ServiceRegistration<CredentialVault> vaultRegistration;
    private ServiceRegistration<GitAccountManager> gitAccountRegistration;
    private ServiceRegistration<eu.kalafatic.dcode.config.agent.AgentRegistry>
            agentRegistryRegistration;
    private ServiceAvailabilityGate gate;
    private eu.kalafatic.dcode.core.service.DynamicServiceLookup services;

    @Override
    public void start(BundleContext context) {
        this.context = context;
        gate = new ServiceAvailabilityGate(context, new Class<?>[]{
                ConfigurationService.class, DCodeLifecycle.class}, this::wire);
        gate.runIfAvailable();
    }

    private void wire() {
        DCodeLifecycle lifecycle = context.getService(
                context.getServiceReference(DCodeLifecycle.class));

        // Resolve the configuration service at call time: the durable encrypted
        // implementation registers later with a higher ranking and must win.
        services = new eu.kalafatic.dcode.core.service.DynamicServiceLookup(context);
        ConfigurationService configurationService = new DeferredConfigurationService(services);

        DefaultModelRouter modelRouter = new DefaultModelRouter();
        CredentialVault vault = new ConfigurationCredentialVault(configurationService);
        GitAccountManager gitAccounts = new GitAccountManager(vault);
        RuntimeManager runtimeManager = RuntimeManager.create(configurationService,
                vault, modelRouter);
        var agentRegistry = new eu.kalafatic.dcode.config.agent.DefaultAgentRegistry(
                new eu.kalafatic.dcode.config.agent.JsonFileAgentStore(
                        configurationService.getConfiguration().dataDir()));

        runtimeRegistryRegistration = context.registerService(RuntimeRegistry.class,
                runtimeManager, null);
        providerRegistryRegistration = context.registerService(ProviderRegistry.class,
                runtimeManager, null);
        modelRouterRegistration = context.registerService(ModelRouter.class,
                modelRouter, null);
        vaultRegistration = context.registerService(CredentialVault.class, vault, null);
        gitAccountRegistration = context.registerService(GitAccountManager.class,
                gitAccounts, null);
        agentRegistryRegistration = context.registerService(
                eu.kalafatic.dcode.config.agent.AgentRegistry.class,
                agentRegistry, null);

        lifecycle.registerAction(LifecycleStage.PROVIDERS, "provider-services",
                new DCodeLifecycle.LifecycleAction() {
                    @Override
                    public void start() {
                        DCodeLog.info("config", "Runtime registry started ("
                                + runtimeManager.runtimes().size() + " runtime(s))");
                    }

                    @Override
                    public void stop() {
                        DCodeLog.info("config", "Runtime registry stopped");
                    }
                });
        try {
            lifecycle.startThrough(LifecycleStage.PROVIDERS);
        } catch (Exception e) {
            DCodeLog.error("config", "Failed to advance lifecycle to PROVIDERS", e);
        }
    }

    @Override
    public void stop(BundleContext context) {
        if (runtimeRegistryRegistration != null) {
            runtimeRegistryRegistration.unregister();
        }
        if (providerRegistryRegistration != null) {
            providerRegistryRegistration.unregister();
        }
        if (modelRouterRegistration != null) {
            modelRouterRegistration.unregister();
        }
        if (vaultRegistration != null) {
            vaultRegistration.unregister();
        }
        if (gitAccountRegistration != null) {
            gitAccountRegistration.unregister();
        }
        if (agentRegistryRegistration != null) {
            agentRegistryRegistration.unregister();
        }
        if (services != null) {
            services.close();
        }
        if (gate != null) {
            gate.close();
        }
        runtimeRegistryRegistration = null;
        providerRegistryRegistration = null;
        modelRouterRegistration = null;
        vaultRegistration = null;
        gitAccountRegistration = null;
    }
}

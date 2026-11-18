package eu.kalafatic.dcode.config.internal;

import eu.kalafatic.dcode.config.credentials.ConfigurationCredentialVault;
import eu.kalafatic.dcode.config.credentials.CredentialVault;
import eu.kalafatic.dcode.config.git.GitAccountManager;
import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.provider.EnrProvider;
import eu.kalafatic.dcode.config.provider.OllamaProvider;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.config.routing.ModelRouter;
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
 * ENR (family default inference engine) is registered first, followed by the local
 * Ollama provider as the alternative. Startup retries through a service-availability
 * gate so bundle start order is irrelevant.
 */
public final class ConfigActivator implements BundleActivator {

    private BundleContext context;
    private ServiceRegistration<ProviderRegistry> providerRegistryRegistration;
    private ServiceRegistration<ModelRouter> modelRouterRegistration;
    private ServiceRegistration<CredentialVault> vaultRegistration;
    private ServiceRegistration<GitAccountManager> gitAccountRegistration;
    private ServiceAvailabilityGate gate;

    @Override
    public void start(BundleContext context) {
        this.context = context;
        gate = new ServiceAvailabilityGate(context, new Class<?>[]{
                ConfigurationService.class, DCodeLifecycle.class}, this::wire);
        gate.runIfAvailable();
    }

    private void wire() {
        ConfigurationService configurationService = context.getService(
                context.getServiceReference(ConfigurationService.class));
        DCodeLifecycle lifecycle = context.getService(
                context.getServiceReference(DCodeLifecycle.class));

        DefaultProviderRegistry providerRegistry = new DefaultProviderRegistry();
        providerRegistry.register(EnrProvider.localDefault());
        providerRegistry.register(OllamaProvider.localDefault());
        DefaultModelRouter modelRouter = new DefaultModelRouter();
        CredentialVault vault = new ConfigurationCredentialVault(configurationService);
        GitAccountManager gitAccounts = new GitAccountManager(vault);

        providerRegistryRegistration = context.registerService(ProviderRegistry.class,
                providerRegistry, null);
        modelRouterRegistration = context.registerService(ModelRouter.class, modelRouter, null);
        vaultRegistration = context.registerService(CredentialVault.class, vault, null);
        gitAccountRegistration = context.registerService(GitAccountManager.class, gitAccounts, null);

        lifecycle.registerAction(LifecycleStage.PROVIDERS, "provider-services",
                new DCodeLifecycle.LifecycleAction() {
                    @Override
                    public void start() {
                        DCodeLog.info("config", "Provider registry started ("
                                + providerRegistry.providers().size() + " provider(s))");
                    }

                    @Override
                    public void stop() {
                        DCodeLog.info("config", "Provider registry stopped");
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
        if (gate != null) {
            gate.close();
        }
        providerRegistryRegistration = null;
        modelRouterRegistration = null;
        vaultRegistration = null;
        gitAccountRegistration = null;
    }
}

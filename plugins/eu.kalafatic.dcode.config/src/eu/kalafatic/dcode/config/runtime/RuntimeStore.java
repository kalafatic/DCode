package eu.kalafatic.dcode.config.runtime;

/**
 * Durable store of runtime registrations. Implementations must never persist secrets
 * (bearer tokens and API keys stay in the credential vault).
 */
public interface RuntimeStore {

    RuntimeStoreData load();

    void save(RuntimeStoreData data);
}

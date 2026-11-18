package eu.kalafatic.dcode.config.git;

import eu.kalafatic.dcode.config.credentials.CredentialVault;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central Git account management: commit identity and remote tokens.
 *
 * <p>Tokens are stored exclusively in the credential vault and are never returned by any
 * listing method. Shared provider definitions and credentials stay separate: an account
 * references a vault key, not a token.</p>
 */
public final class GitAccountManager {

    /** Account view without secrets. */
    public record GitAccount(
            String id,
            String name,
            String userName,
            String userEmail,
            String tokenVaultKey,
            Instant createdAt
    ) {
    }

    /** Commit identity resolved from an account. */
    public record CommitIdentity(String userName, String userEmail) {
    }

    private final CredentialVault vault;
    private final ConcurrentHashMap<String, GitAccount> accounts = new ConcurrentHashMap<>();

    public GitAccountManager(CredentialVault vault) {
        this.vault = Objects.requireNonNull(vault, "vault");
    }

    public GitAccount addAccount(String name, String userName, String userEmail) {
        Objects.requireNonNull(name, "name");
        String id = UUID.randomUUID().toString();
        GitAccount account = new GitAccount(id, name,
                userName == null ? "" : userName,
                userEmail == null ? "" : userEmail,
                "git." + id + ".token", Instant.now());
        accounts.put(id, account);
        return account;
    }

    public Optional<GitAccount> find(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    public List<GitAccount> listAccounts() {
        return List.copyOf(accounts.values());
    }

    public void setToken(String accountId, String token) {
        GitAccount account = require(accountId);
        vault.put(account.tokenVaultKey(), token);
    }

    public Optional<String> token(String accountId) {
        return vault.get(require(accountId).tokenVaultKey());
    }

    public boolean hasToken(String accountId) {
        return vault.has(require(accountId).tokenVaultKey());
    }

    public CommitIdentity commitIdentity(String accountId) {
        GitAccount account = require(accountId);
        if (account.userName().isBlank()) {
            throw new IllegalStateException("Git account '" + account.name()
                    + "' has no commit identity configured");
        }
        return new CommitIdentity(account.userName(), account.userEmail());
    }

    private GitAccount require(String accountId) {
        GitAccount account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Unknown Git account: " + accountId);
        }
        return account;
    }
}

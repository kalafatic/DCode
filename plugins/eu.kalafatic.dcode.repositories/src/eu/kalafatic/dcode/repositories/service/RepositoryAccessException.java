package eu.kalafatic.dcode.repositories.service;

import eu.kalafatic.dcode.core.model.RepositoryRef;

/** Thrown when an operation violates the repository access policy. */
public class RepositoryAccessException extends RuntimeException {

    public RepositoryAccessException(String message) {
        super(message);
    }
}

package eu.kalafatic.dcode.config.routing;

/** Resolved model selection: a provider/model pair. */
public record ModelSelection(
        String providerId,
        String modelId
) {
}

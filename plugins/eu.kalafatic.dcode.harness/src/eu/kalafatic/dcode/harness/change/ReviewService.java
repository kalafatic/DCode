package eu.kalafatic.dcode.harness.change;

/**
 * Backward-compatible alias kept for API stability during the WP5.7 refactoring. The
 * authoritative contract is {@link eu.kalafatic.dcode.core.service.ReviewService}.
 *
 * @deprecated use {@link eu.kalafatic.dcode.core.service.ReviewService}
 */
@Deprecated
public interface ReviewService extends eu.kalafatic.dcode.core.service.ReviewService {
}

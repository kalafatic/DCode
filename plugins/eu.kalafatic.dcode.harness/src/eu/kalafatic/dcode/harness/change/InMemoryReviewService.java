package eu.kalafatic.dcode.harness.change;

import eu.kalafatic.dcode.core.service.ReviewService;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory review service fallback. The persistence bundle provides the durable
 * SQLite-backed implementation (higher OSGi service ranking).
 */
public final class InMemoryReviewService implements ReviewService {

    private final ConcurrentHashMap<String, Review> reviews = new ConcurrentHashMap<>();

    @Override
    public Review createReview(String sessionId, String changeSetId) {
        Review review = new Review(UUID.randomUUID().toString(),
                Objects.requireNonNull(sessionId, "sessionId"),
                Objects.requireNonNull(changeSetId, "changeSetId"),
                Status.PENDING, null, null, Instant.now(), null);
        reviews.put(review.id(), review);
        return review;
    }

    @Override
    public Optional<Review> find(String reviewId) {
        return Optional.ofNullable(reviews.get(reviewId));
    }

    @Override
    public List<Review> reviewsOf(String sessionId) {
        return reviews.values().stream()
                .filter(r -> r.sessionId().equals(sessionId))
                .toList();
    }

    @Override
    public Review approve(String reviewId, String approverId, String comment) {
        return decide(reviewId, Status.APPROVED, approverId, comment);
    }

    @Override
    public Review reject(String reviewId, String approverId, String comment) {
        return decide(reviewId, Status.REJECTED, approverId, comment);
    }

    private Review decide(String reviewId, Status status, String approverId, String comment) {
        return reviews.compute(reviewId, (id, current) -> {
            if (current == null) {
                throw new IllegalArgumentException("Unknown review: " + reviewId);
            }
            if (current.status() != Status.PENDING) {
                throw new IllegalStateException("Review already decided: " + reviewId);
            }
            return new Review(current.id(), current.sessionId(), current.changeSetId(),
                    status, approverId, comment, current.createdAt(), Instant.now());
        });
    }
}

package eu.kalafatic.dcode.core.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Review service contract: human/agent review gate over produced change sets.
 *
 * <p>Approval decisions are recorded with the approver identity and never fabricated.
 * The harness bundle provides an in-memory implementation; the persistence bundle
 * provides the durable SQLite implementation.</p>
 */
public interface ReviewService {

    /** Review status. */
    enum Status {
        PENDING,
        APPROVED,
        REJECTED
    }

    /** One review record. */
    record Review(
            String id,
            String sessionId,
            String changeSetId,
            Status status,
            String approverId,
            String comment,
            Instant createdAt,
            Instant decidedAt
    ) {
    }

    Review createReview(String sessionId, String changeSetId);

    Optional<Review> find(String reviewId);

    List<Review> reviewsOf(String sessionId);

    Review approve(String reviewId, String approverId, String comment);

    Review reject(String reviewId, String approverId, String comment);
}

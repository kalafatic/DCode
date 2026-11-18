package eu.kalafatic.dcode.persistence.service;

import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed review service: reviews survive restarts together with sessions.
 */
public final class SqliteReviewService implements ReviewService {

    private final SqliteDatabaseManager database;

    public SqliteReviewService(SqliteDatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Review createReview(String sessionId, String changeSetId) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(changeSetId, "changeSetId");
        Review review = new Review(UUID.randomUUID().toString(), sessionId, changeSetId,
                Status.PENDING, null, null, Instant.now(), null);
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO reviews(id, session_id, change_set_id, status, approver_id,"
                            + " comment, created_at, decided_at) VALUES (?,?,?,?,?,?,?,?)")) {
                statement.setString(1, review.id());
                statement.setString(2, review.sessionId());
                statement.setString(3, review.changeSetId());
                statement.setString(4, review.status().name());
                statement.setString(5, null);
                statement.setString(6, null);
                statement.setString(7, review.createdAt().toString());
                statement.setString(8, null);
                statement.executeUpdate();
            }
        });
        return review;
    }

    @Override
    public Optional<Review> find(String reviewId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM reviews WHERE id = ?")) {
                statement.setString(1, reviewId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapReview(rs)) : Optional.<Review>empty();
                }
            }
        });
    }

    @Override
    public List<Review> reviewsOf(String sessionId) {
        return database.query(connection -> {
            List<Review> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM reviews WHERE session_id = ? ORDER BY created_at ASC")) {
                statement.setString(1, sessionId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapReview(rs));
                    }
                }
            }
            return result;
        });
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
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT status FROM reviews WHERE id = ?")) {
                statement.setString(1, reviewId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Unknown review: " + reviewId);
                    }
                    if (!Status.PENDING.name().equals(rs.getString(1))) {
                        throw new IllegalStateException("Review already decided: " + reviewId);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE reviews SET status = ?, approver_id = ?, comment = ?, decided_at = ?"
                            + " WHERE id = ?")) {
                statement.setString(1, status.name());
                statement.setString(2, approverId);
                statement.setString(3, comment);
                statement.setString(4, Instant.now().toString());
                statement.setString(5, reviewId);
                statement.executeUpdate();
            }
        });
        return find(reviewId).orElseThrow();
    }

    private static Review mapReview(ResultSet rs) throws java.sql.SQLException {
        return new Review(rs.getString("id"), rs.getString("session_id"),
                rs.getString("change_set_id"), Status.valueOf(rs.getString("status")),
                rs.getString("approver_id"), rs.getString("comment"),
                Instant.parse(rs.getString("created_at")),
                rs.getString("decided_at") != null
                        ? Instant.parse(rs.getString("decided_at")) : null);
    }
}

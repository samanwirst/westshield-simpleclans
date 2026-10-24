package com.westshield.tab;

import com.djrapitops.plan.query.QueryService;
import java.util.UUID;

/** Read-only Plan queries. All methods run on the adapter's asynchronous worker. */
public final class PlanHistory {
    private static final String SQL = """
        SELECT MIN(i.registered) AS first_seen,
               COALESCE(SUM(CASE WHEN s.session_end > s.session_start
                   THEN s.session_end - s.session_start ELSE 0 END), 0) AS online_ms
        FROM plan_users u
        JOIN plan_user_info i ON i.user_id = u.id
        JOIN plan_servers v ON v.id = i.server_id
        LEFT JOIN plan_sessions s ON s.user_id = u.id AND s.server_id = i.server_id
        WHERE u.uuid = ? AND v.uuid = ?
        """;
    public record History(long firstSeen, long onlineMillis, long sampledAt) { }

    public History read(UUID player) {
        QueryService service = QueryService.getInstance();
        UUID server = service.getServerUUID().orElseThrow(() -> new IllegalStateException("Plan server identity unavailable"));
        // Plan stores finished sessions in SQL; its cache owns the current session.
        return service.query(SQL, statement -> {
            statement.setString(1, player.toString());
            statement.setString(2, server.toString());
            long first;
            long saved;
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Plan history unavailable");
                first = rows.getLong("first_seen");
                saved = rows.getLong("online_ms");
            }
            long active = service.getCommonQueries().fetchCurrentSessionPlaytime(player);
            return new History(first, Math.addExact(saved, Math.max(0, active)), System.currentTimeMillis());
        });
    }
}

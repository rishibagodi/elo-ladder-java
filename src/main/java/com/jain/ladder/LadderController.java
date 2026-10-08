package com.jain.ladder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class LadderController {

    private static final String LADDER_KEY = "ladder";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    @Autowired
    public LadderController(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        boolean pg = false, rd = false;
        try { jdbc.queryForObject("SELECT 1", Integer.class); pg = true; } catch (Exception ignored) { }
        try { redis.getConnectionFactory().getConnection().ping(); rd = true; } catch (Exception ignored) { }
        out.put("postgres", pg);
        out.put("redis", rd);
        return (pg && rd) ? ResponseEntity.ok(out) : ResponseEntity.status(503).body(out);
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of("service", "elo-ladder",
                "endpoints", List.of("GET /health", "GET /players", "POST /players",
                        "GET /ladder", "POST /matches"));
    }

    @GetMapping("/players")
    public Map<String, Object> players() {
        return Map.of("players", jdbc.queryForList(
                "SELECT id, name, rating, games_played FROM players ORDER BY rating DESC, name"));
    }

    @PostMapping("/players")
    public ResponseEntity<?> addPlayer(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        Integer id = jdbc.queryForObject(
                "INSERT INTO players (name, rating, games_played) VALUES (?, ?, 0) RETURNING id",
                Integer.class, name, Elo.STARTING_RATING);
        redis.opsForZSet().add(LADDER_KEY, name, Elo.STARTING_RATING);
        return ResponseEntity.status(201).body(
                Map.of("id", id, "name", name, "rating", Elo.STARTING_RATING));
    }

    /** Report a result. scoreA: 1 = A won, 0.5 = draw, 0 = B won. */
    @PostMapping("/matches")
    public ResponseEntity<?> report(@RequestBody Map<String, Object> body) {
        Integer idA = asInt(body.get("playerA"));
        Integer idB = asInt(body.get("playerB"));
        Double score = body.get("scoreA") == null ? null
                : Double.valueOf(body.get("scoreA").toString());

        if (idA == null || idB == null || score == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "playerA, playerB and scoreA are required"));
        }
        if (idA.equals(idB)) {
            return ResponseEntity.badRequest().body(Map.of("error", "a player cannot play themselves"));
        }

        Map<String, Object> a = one(idA), b = one(idB);
        if (a == null || b == null) {
            return ResponseEntity.status(404).body(Map.of("error", "no such player"));
        }

        Elo.Player pa = new Elo.Player(asInt(a.get("rating")), asInt(a.get("games_played")));
        Elo.Player pb = new Elo.Player(asInt(b.get("rating")), asInt(b.get("games_played")));

        Elo.Result r;
        try {
            r = Elo.play(pa, pb, score);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        jdbc.update("UPDATE players SET rating = ?, games_played = games_played + 1 WHERE id = ?",
                r.ratingA(), idA);
        jdbc.update("UPDATE players SET rating = ?, games_played = games_played + 1 WHERE id = ?",
                r.ratingB(), idB);
        jdbc.update("INSERT INTO matches (player_a, player_b, score_a, delta_a, delta_b)"
                + " VALUES (?,?,?,?,?)", idA, idB, score, r.deltaA(), r.deltaB());

        redis.opsForZSet().add(LADDER_KEY, (String) a.get("name"), r.ratingA());
        redis.opsForZSet().add(LADDER_KEY, (String) b.get("name"), r.ratingB());

        return ResponseEntity.ok(Map.of(
                "playerA", Map.of("name", a.get("name"), "rating", r.ratingA(), "delta", r.deltaA()),
                "playerB", Map.of("name", b.get("name"), "rating", r.ratingB(), "delta", r.deltaB())));
    }

    /** The ladder, straight out of a Redis sorted set - one call however many players. */
    @GetMapping("/ladder")
    public Map<String, Object> ladder() {
        warmLadderIfEmpty();
        var tuples = redis.opsForZSet().reverseRangeWithScores(LADDER_KEY, 0, 49);
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        if (tuples != null) {
            for (var t : tuples) {
                rows.add(Map.of("rank", rank++, "name", t.getValue(),
                        "rating", t.getScore() == null ? 0 : t.getScore().intValue()));
            }
        }
        return Map.of("source", "redis-sorted-set", "ladder", rows);
    }

    /**
     * Rebuild the Redis ladder from Postgres when the cache is cold.
     *
     * Redis is a CACHE, not the source of truth. On a fresh start - or after
     * Redis is flushed or restarted - the sorted set is empty while Postgres
     * still holds every player. Without this, the ladder would silently show
     * only the players who happened to play since the last restart.
     */
    private void warmLadderIfEmpty() {
        Long size = redis.opsForZSet().size(LADDER_KEY);
        if (size != null && size > 0) {
            return;
        }
        for (var row : jdbc.queryForList("SELECT name, rating FROM players")) {
            redis.opsForZSet().add(LADDER_KEY, (String) row.get("name"),
                    asInt(row.get("rating")));
        }
    }

    private Map<String, Object> one(int id) {
        var rows = jdbc.queryForList(
                "SELECT id, name, rating, games_played FROM players WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        return o instanceof Number n ? n.intValue() : Integer.valueOf(o.toString());
    }
}

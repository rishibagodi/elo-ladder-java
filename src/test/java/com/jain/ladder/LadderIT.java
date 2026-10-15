package com.jain.ladder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Integration tests: the real app against a real Postgres and Redis.
 *
 * Connection details come from the same environment variables the app always
 * uses (DATABASE_URL, REDIS_HOST, ...). In CircleCI they point at localhost,
 * because service containers share the job's network; in docker-compose the
 * same app uses the service names db and cache. Nothing here is hard-coded.
 *
 * Every test creates its own uniquely named players, so tests never depend on
 * each other or on what an earlier run left in the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({ "rawtypes", "unchecked" })
class LadderIT {

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private StringRedisTemplate redis;

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private int createPlayer(String name) {
        ResponseEntity<Map> r = http.postForEntity("/players", Map.of("name", name), Map.class);
        assertEquals(201, r.getStatusCode().value());
        return ((Number) r.getBody().get("id")).intValue();
    }

    private Map playerById(int id) {
        List<Map> players = (List<Map>) http.getForEntity("/players", Map.class).getBody().get("players");
        return players.stream()
                .filter(p -> ((Number) p.get("id")).intValue() == id)
                .findFirst().orElse(null);
    }

    @Test
    void healthReportsBothBackendsUp() {
        ResponseEntity<Map> r = http.getForEntity("/health", Map.class);
        assertEquals(200, r.getStatusCode().value());
        assertEquals(true, r.getBody().get("postgres"));
        assertEquals(true, r.getBody().get("redis"));
    }

    @Test
    void createReadUpdateDeletePlayer() {
        String name = unique("crud");
        int id = createPlayer(name);

        Map created = playerById(id);
        assertEquals(name, created.get("name"));
        assertEquals(1200, ((Number) created.get("rating")).intValue());

        String renamed = name + "-renamed";
        ResponseEntity<Map> put = http.exchange("/players/" + id, HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", renamed)), Map.class);
        assertEquals(200, put.getStatusCode().value());
        assertEquals(renamed, playerById(id).get("name"));

        ResponseEntity<Void> del = http.exchange("/players/" + id, HttpMethod.DELETE, null, Void.class);
        assertEquals(204, del.getStatusCode().value());
        assertEquals(null, playerById(id));
    }

    @Test
    void duplicateNameIsAConflict() {
        String name = unique("dup");
        createPlayer(name);
        ResponseEntity<Map> again = http.postForEntity("/players", Map.of("name", name), Map.class);
        assertEquals(409, again.getStatusCode().value());
    }

    @Test
    void emptyNameIsRejected() {
        ResponseEntity<Map> r = http.postForEntity("/players", Map.of("name", "  "), Map.class);
        assertEquals(400, r.getStatusCode().value());
    }

    @Test
    void unknownPlayerIsNotFound() {
        ResponseEntity<Map> r = http.exchange("/players/999999", HttpMethod.PUT,
                new HttpEntity<>(Map.of("name", unique("ghost"))), Map.class);
        assertEquals(404, r.getStatusCode().value());
    }

    @Test
    void matchUpdatesPostgresAndRedis() {
        String nameA = unique("a");
        String nameB = unique("b");
        int a = createPlayer(nameA);
        int b = createPlayer(nameB);

        // two brand-new 1200 players, both K = 40: winner +20, loser -20
        ResponseEntity<Map> r = http.postForEntity("/matches",
                Map.of("playerA", a, "playerB", b, "scoreA", 1), Map.class);
        assertEquals(200, r.getStatusCode().value());

        Map rowA = playerById(a);
        assertEquals(1220, ((Number) rowA.get("rating")).intValue());
        assertEquals(1, ((Number) rowA.get("games_played")).intValue());
        assertEquals(1180, ((Number) playerById(b).get("rating")).intValue());

        Double cached = redis.opsForZSet().score("ladder", nameA);
        assertEquals(1220.0, cached);
    }

    @Test
    void playerWithMatchesCannotBeDeleted() {
        int a = createPlayer(unique("keep-a"));
        int b = createPlayer(unique("keep-b"));
        http.postForEntity("/matches", Map.of("playerA", a, "playerB", b, "scoreA", 0.5), Map.class);

        ResponseEntity<Map> del = http.exchange("/players/" + a, HttpMethod.DELETE, null, Map.class);
        assertEquals(409, del.getStatusCode().value());
    }

    @Test
    void invalidMatchesAreRejected() {
        int a = createPlayer(unique("solo"));
        ResponseEntity<Map> self = http.postForEntity("/matches",
                Map.of("playerA", a, "playerB", a, "scoreA", 1), Map.class);
        assertEquals(400, self.getStatusCode().value());

        ResponseEntity<Map> ghost = http.postForEntity("/matches",
                Map.of("playerA", a, "playerB", 999999, "scoreA", 1), Map.class);
        assertEquals(404, ghost.getStatusCode().value());
    }

    @Test
    void ladderIsRebuiltFromPostgresWhenRedisIsEmpty() {
        redis.delete("ladder"); // simulate a Redis restart

        ResponseEntity<Map> r = http.getForEntity("/ladder", Map.class);
        assertEquals(200, r.getStatusCode().value());
        List<Map> ladder = (List<Map>) r.getBody().get("ladder");
        assertTrue(ladder.stream().anyMatch(row -> "Anand".equals(row.get("name"))),
                "seed player missing after cache rebuild");
    }
}
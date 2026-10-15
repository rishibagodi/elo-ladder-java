package com.jain.ladder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure ELO maths. No Spring, no database, no network:
 * these run in milliseconds and are what the 70% coverage gate measures.
 */
class EloTest {

    private static final double EPS = 1e-9;

    // Seed players, so the numbers match what the running app shows.
    private static final Elo.Player ANAND = new Elo.Player(2450, 310); // master, K = 10
    private static final Elo.Player BHAVNA = new Elo.Player(1875, 142); // established, K = 20
    private static final Elo.Player CHETAN = new Elo.Player(1640, 88); // established, K = 20
    private static final Elo.Player FARAH = new Elo.Player(1200, 0); // brand new, K = 40

    // ---------- expected() ----------

    @Test
    @DisplayName("equal ratings give each player a 50% chance")
    void equalRatingsAreEvens() {
        assertEquals(0.5, Elo.expected(1500, 1500), EPS);
    }

    @Test
    @DisplayName("property: expected(a,b) + expected(b,a) == 1 for any pair")
    void expectedIsSymmetric() {
        int[][] pairs = { { 1200, 1200 }, { 1200, 1600 }, { 2450, 1200 }, { 1000, 3000 }, { 1875, 1640 } };
        for (int[] p : pairs) {
            double sum = Elo.expected(p[0], p[1]) + Elo.expected(p[1], p[0]);
            assertEquals(1.0, sum, EPS, "pair " + p[0] + " vs " + p[1]);
        }
    }

    @Test
    @DisplayName("a 400-point gap makes the stronger player a 10-to-1 favourite")
    void fourHundredPointGap() {
        assertEquals(10.0 / 11.0, Elo.expected(1600, 1200), EPS);
    }

    @Test
    @DisplayName("the higher-rated player is always the favourite")
    void higherRatedIsFavourite() {
        assertTrue(Elo.expected(1601, 1600) > 0.5);
        assertTrue(Elo.expected(1600, 1601) < 0.5);
    }

    // ---------- kFactor() ----------

    @Test
    @DisplayName("provisional players (fewer than 30 games) get K = 40")
    void provisionalK() {
        assertEquals(40, Elo.kFactor(1200, 0));
        assertEquals(40, Elo.kFactor(1200, 29)); // boundary: last provisional game
    }

    @Test
    @DisplayName("provisional status wins even for a high rating")
    void provisionalOverridesRating() {
        assertEquals(40, Elo.kFactor(2500, 10));
    }

    @Test
    @DisplayName("established players below 2400 get K = 20")
    void establishedK() {
        assertEquals(20, Elo.kFactor(1200, 30)); // boundary: first non-provisional game
        assertEquals(20, Elo.kFactor(2399, 30)); // boundary: just below master
    }

    @Test
    @DisplayName("masters (2400+) get K = 10")
    void masterK() {
        assertEquals(10, Elo.kFactor(2400, 30)); // boundary: exactly master
        assertEquals(10, Elo.kFactor(2800, 500));
    }

    // ---------- newRating() ----------

    @Test
    @DisplayName("against an equal opponent with K = 20: win +10, draw 0, loss -10")
    void newRatingAgainstEqual() {
        assertEquals(1210, Elo.newRating(1200, 1200, 1.0, 20));
        assertEquals(1200, Elo.newRating(1200, 1200, 0.5, 20));
        assertEquals(1190, Elo.newRating(1200, 1200, 0.0, 20));
    }

    // ---------- play() ----------

    @Test
    @DisplayName("property: with the same K, one player's gain is exactly the other's loss")
    void sameKConservesRating() {
        for (double score : new double[] { 1.0, 0.5, 0.0 }) {
            Elo.Result r = Elo.play(BHAVNA, CHETAN, score);
            assertEquals(0, r.deltaA() + r.deltaB(), "score " + score);
        }
    }

    @Test
    @DisplayName("the K-factor asymmetry: a new player beating a master moves a lot, the master little")
    void upsetIsAsymmetric() {
        Elo.Result r = Elo.play(FARAH, ANAND, 1.0);
        assertEquals(40, r.deltaA());
        assertEquals(-10, r.deltaB());
        assertEquals(1240, r.ratingA());
        assertEquals(2440, r.ratingB());
    }

    @Test
    @DisplayName("anti-farming: a master gains nothing from beating a beginner, however often")
    void masterCannotFarmBeginners() {
        Elo.Player anand = ANAND;
        for (int game = 0; game < 10; game++) {
            Elo.Result r = Elo.play(anand, FARAH, 1.0);
            assertEquals(0, r.deltaA(), "game " + game);
            anand = new Elo.Player(r.ratingA(), anand.gamesPlayed() + 1);
        }
        assertEquals(2450, anand.rating());
    }

    @Test
    @DisplayName("a draw still costs the favourite rating")
    void drawCostsFavourite() {
        Elo.Result r = Elo.play(BHAVNA, CHETAN, 0.5);
        assertEquals(-6, r.deltaA());
        assertEquals(6, r.deltaB());
    }

    @Test
    @DisplayName("new ratings equal old rating plus delta")
    void resultIsConsistent() {
        Elo.Result r = Elo.play(CHETAN, FARAH, 0.0);
        assertEquals(CHETAN.rating() + r.deltaA(), r.ratingA());
        assertEquals(FARAH.rating() + r.deltaB(), r.ratingB());
    }

    @Test
    @DisplayName("scores other than 1, 0.5 and 0 are rejected")
    void invalidScoreRejected() {
        for (double bad : new double[] { 0.7, -1.0, 2.0 }) {
            assertThrows(IllegalArgumentException.class, () -> Elo.play(BHAVNA, CHETAN, bad),
                    "score " + bad);
        }
    }

    @Test
    @DisplayName("everyone starts at 1200 and is provisional for 30 games")
    void constants() {
        assertEquals(1200, Elo.STARTING_RATING);
        assertEquals(30, Elo.PROVISIONAL_GAMES);
    }
}
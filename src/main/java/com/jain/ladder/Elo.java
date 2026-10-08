package com.jain.ladder;

/**
 * Pure ELO rating maths.
 *
 * No database, no Spring, no HTTP. Every method here is a plain function
 * over numbers, which makes this the file to unit test.
 *
 * Two properties are worth testing explicitly because they must ALWAYS hold:
 *   1. expected(a,b) + expected(b,a) == 1.0
 *   2. when both players have the same K-factor, the points one gains equal
 *      the points the other loses - the ladder never invents or destroys rating
 */
public final class Elo {

    /** Everyone starts here. */
    public static final int STARTING_RATING = 1200;

    /** Games needed before a player stops being provisional. */
    public static final int PROVISIONAL_GAMES = 30;

    private Elo() { }

    /**
     * Probability that player A beats player B, from the rating gap alone.
     * A 400-point gap means roughly a 10-to-1 favourite.
     */
    public static double expected(int ratingA, int ratingB) {
        return 1.0 / (1.0 + Math.pow(10.0, (ratingB - ratingA) / 400.0));
    }

    /**
     * How much a single game can move a rating.
     *
     * New players move fast so they reach their real level quickly;
     * strong, established players move slowly so the top of the ladder
     * is stable. Getting this asymmetry wrong is what makes a ladder
     * gameable - see the brief.
     */
    public static int kFactor(int rating, int gamesPlayed) {
        if (gamesPlayed < PROVISIONAL_GAMES) {
            return 40;
        }
        return rating >= 2400 ? 10 : 20;
    }

    /**
     * New rating after one game.
     *
     * @param score 1.0 for a win, 0.5 for a draw, 0.0 for a loss
     */
    public static int newRating(int rating, int opponentRating, double score, int k) {
        double delta = k * (score - expected(rating, opponentRating));
        return (int) Math.round(rating + delta);
    }

    /** Both players' new ratings after a single game. */
    public static Result play(Player a, Player b, double scoreForA) {
        if (scoreForA != 0.0 && scoreForA != 0.5 && scoreForA != 1.0) {
            throw new IllegalArgumentException("score must be 1, 0.5 or 0");
        }
        int ka = kFactor(a.rating(), a.gamesPlayed());
        int kb = kFactor(b.rating(), b.gamesPlayed());
        int newA = newRating(a.rating(), b.rating(), scoreForA, ka);
        int newB = newRating(b.rating(), a.rating(), 1.0 - scoreForA, kb);
        return new Result(newA, newB, newA - a.rating(), newB - b.rating());
    }

    /** A player, as far as the maths is concerned. */
    public record Player(int rating, int gamesPlayed) { }

    /** The outcome of one game. */
    public record Result(int ratingA, int ratingB, int deltaA, int deltaB) { }
}

package com.example.bbhelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plant die Zugfolge für drei Teile. Bitboard (Bit = Zeile*8 + Spalte), Beam-Search über alle
 * Reihenfolgen. Bewertet wird nicht nur, wie viele Linien fallen, sondern vor allem, wie viele der
 * üblichen Formen danach noch auf das Brett passen (Überlebenschance für die nächsten Teile).
 */
public final class Solver {

    public static final class Move {
        public final int piece, r, c;
        public final List<int[]> cells;
        Move(int piece, int r, int c, List<int[]> cells) {
            this.piece = piece; this.r = r; this.c = c; this.cells = cells;
        }
    }

    public static final class Result {
        public final List<Move> moves;
        public final int lines;
        Result(List<Move> moves, int lines) { this.moves = moves; this.lines = lines; }
    }

    // Gewichte (öffentlich, damit sie sich in Simulationen einstellen lassen)
    public static int BEAM = 40;
    public static double W_LINE = 20.0, W_FILL = 1.5, W_ISO = 6.0, W_DEAD = 0.0, W_TRANS = 1.2, W_SURV = 0.0;
    public static double W_MC = 70.0;    // Gewicht der Wahrscheinlichkeit, dass ein zufälliger nächster Satz noch passt
    public static int K_FINAL = 40;      // so viele Endstellungen werden per Simulation geprüft
    public static int NODE_BUDGET = 4000;

    private static final long[] ROW = new long[8];
    private static final long[] COL = new long[8];

    private static final String[][] BASE = {
        {"#"}, {"##"}, {"###"}, {"####"}, {"#####"},
        {"##", "##"}, {"###", "###"}, {"###", "###", "###"},
        {"#.", "##"}, {"#.", "#.", "##"}, {"###", ".#."}, {".##", "##."}, {"#..", "#..", "###"}
    };

    private static final int NT = 48;
    private static final long[][][] TRIPLES;
    private static final long[][] SHAPE_MASKS;
    private static final double[] SHAPE_WEIGHT;

    static {
        for (int i = 0; i < 8; i++) {
            ROW[i] = 0xFFL << (8 * i);
            COL[i] = 0x0101010101010101L << i;
        }
        List<long[]> masks = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] base : BASE) {
            List<int[]> cells = new ArrayList<>();
            for (int r = 0; r < base.length; r++)
                for (int c = 0; c < base[r].length(); c++)
                    if (base[r].charAt(c) == '#') cells.add(new int[]{r, c});
            for (int sym = 0; sym < 8; sym++) {
                List<int[]> t = new ArrayList<>();
                for (int[] p : cells) {
                    int r = p[0], c = p[1];
                    if ((sym & 4) != 0) c = -c;
                    for (int k = 0; k < (sym & 3); k++) { int nr = c; int nc = -r; r = nr; c = nc; }
                    t.add(new int[]{r, c});
                }
                normalize(t);
                if (seen.add(key(t))) {
                    masks.add(placementMasks(t));
                    weights.add((double) t.size());
                }
            }
        }
        SHAPE_MASKS = masks.toArray(new long[0][]);
        SHAPE_WEIGHT = new double[weights.size()];
        for (int i = 0; i < SHAPE_WEIGHT.length; i++) SHAPE_WEIGHT[i] = weights.get(i);

        // feste Stichprobe möglicher nächster Sätze (nur Teile ab 3 Feldern, also eher die schweren)
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < SHAPE_WEIGHT.length; i++) if (SHAPE_WEIGHT[i] >= 3) pool.add(i);
        java.util.Random rnd = new java.util.Random(12345);
        TRIPLES = new long[NT][3][];
        for (int t = 0; t < NT; t++)
            for (int k = 0; k < 3; k++) TRIPLES[t][k] = SHAPE_MASKS[pool.get(rnd.nextInt(pool.size()))];
    }

    private static long clearLines(long nb) {
        long clear = 0;
        for (int i = 0; i < 8; i++) {
            if ((nb & ROW[i]) == ROW[i]) clear |= ROW[i];
            if ((nb & COL[i]) == COL[i]) clear |= COL[i];
        }
        return nb & ~clear;
    }

    private static boolean canPlaceAll(long b, long[][] ps, int used, int[] budget) {
        if (used == 7) return true;
        for (int i = 0; i < 3; i++) {
            if ((used & (1 << i)) != 0) continue;
            for (long m : ps[i]) {
                if ((m & b) != 0) continue;
                if (canPlaceAll(clearLines(b | m), ps, used | (1 << i), budget)) return true;
                if (--budget[0] <= 0) return true;   // sehr viele Möglichkeiten: optimistisch
            }
        }
        return false;
    }

    /** Anteil der Stichproben-Sätze, die sich von dieser Stellung aus noch komplett setzen lassen. */
    private static double survivalRate(long b) {
        int ok = 0;
        for (int t = 0; t < NT; t++) {
            int[] budget = {NODE_BUDGET};
            if (canPlaceAll(b, TRIPLES[t], 0, budget)) ok++;
        }
        return ok / (double) NT;
    }

    /** Anzahl der unterschiedlichen Standardformen (für Simulationen). */
    public static List<List<int[]>> standardShapes() {
        List<List<int[]>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] base : BASE) {
            List<int[]> cells = new ArrayList<>();
            for (int r = 0; r < base.length; r++)
                for (int c = 0; c < base[r].length(); c++)
                    if (base[r].charAt(c) == '#') cells.add(new int[]{r, c});
            for (int sym = 0; sym < 8; sym++) {
                List<int[]> t = new ArrayList<>();
                for (int[] p : cells) {
                    int r = p[0], c = p[1];
                    if ((sym & 4) != 0) c = -c;
                    for (int k = 0; k < (sym & 3); k++) { int nr = c; int nc = -r; r = nr; c = nc; }
                    t.add(new int[]{r, c});
                }
                normalize(t);
                if (seen.add(key(t))) out.add(t);
            }
        }
        return out;
    }

    private static void normalize(List<int[]> cells) {
        int mr = Integer.MAX_VALUE, mc = Integer.MAX_VALUE;
        for (int[] p : cells) { mr = Math.min(mr, p[0]); mc = Math.min(mc, p[1]); }
        for (int[] p : cells) { p[0] -= mr; p[1] -= mc; }
        Collections.sort(cells, new Comparator<int[]>() {
            @Override public int compare(int[] a, int[] b) { return a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]; }
        });
    }

    private static String key(List<int[]> cells) {
        StringBuilder sb = new StringBuilder();
        for (int[] p : cells) sb.append(p[0]).append(',').append(p[1]).append(';');
        return sb.toString();
    }

    private static long[] placementMasks(List<int[]> cells) {
        int maxR = 0, maxC = 0;
        for (int[] p : cells) { maxR = Math.max(maxR, p[0]); maxC = Math.max(maxC, p[1]); }
        List<Long> out = new ArrayList<>();
        for (int r0 = 0; r0 + maxR < 8; r0++)
            for (int c0 = 0; c0 + maxC < 8; c0++) {
                long m = 0;
                for (int[] p : cells) m |= 1L << ((r0 + p[0]) * 8 + c0 + p[1]);
                out.add(m);
            }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static double survivability(long b) {
        double s = 0;
        for (int i = 0; i < SHAPE_MASKS.length; i++) {
            int cnt = 0;
            for (long m : SHAPE_MASKS[i]) {
                if ((m & b) == 0) { cnt++; if (cnt >= 2) break; }
            }
            if (cnt >= 2) s += SHAPE_WEIGHT[i]; else if (cnt == 1) s += SHAPE_WEIGHT[i] * 0.5;
        }
        return s;
    }

    private static double eval(long b, int lines, int unplaced, boolean full) {
        double s = W_LINE * lines - 1e6 * unplaced - W_FILL * Long.bitCount(b);
        long e = ~b;
        int trans = 0;
        for (int i = 0; i < 64; i++) {
            int r = i >> 3, q = i & 7;
            boolean fi = ((e >>> i) & 1L) != 0;
            if (fi) {
                int n = 0;
                if (r > 0 && ((e >>> (i - 8)) & 1L) != 0) n++;
                if (r < 7 && ((e >>> (i + 8)) & 1L) != 0) n++;
                if (q > 0 && ((e >>> (i - 1)) & 1L) != 0) n++;
                if (q < 7 && ((e >>> (i + 1)) & 1L) != 0) n++;
                if (n == 0) s -= W_ISO; else if (n == 1) s -= W_DEAD;
            }
            if (q < 7 && fi != (((e >>> (i + 1)) & 1L) != 0)) trans++;
            if (r < 7 && fi != (((e >>> (i + 8)) & 1L) != 0)) trans++;
        }
        s -= W_TRANS * trans;
        if (full) s += W_SURV * survivability(b);
        return s;
    }

    private static final class State {
        final long b; final int lines; final int[] order; final int[] pos; double score;
        State(long b, int lines, int[] order, int[] pos) { this.b = b; this.lines = lines; this.order = order; this.pos = pos; }
    }

    private static int[] append(int[] a, int v) {
        int[] r = new int[a.length + 1];
        System.arraycopy(a, 0, r, 0, a.length);
        r[a.length] = v;
        return r;
    }

    private static void permutations(List<Integer> items, List<Integer> cur, List<int[]> out) {
        if (cur.size() == items.size()) {
            int[] a = new int[cur.size()];
            for (int i = 0; i < a.length; i++) a[i] = cur.get(i);
            out.add(a);
            return;
        }
        for (Integer it : items) {
            if (cur.contains(it)) continue;
            cur.add(it);
            permutations(items, cur, out);
            cur.remove(cur.size() - 1);
        }
    }

    public static Result solve(boolean[] board, List<List<int[]>> pieces) {
        long b0 = 0;
        for (int i = 0; i < 64; i++) if (board[i]) b0 |= 1L << i;

        List<Integer> idx = new ArrayList<>();
        List<List<int[]>> norm = new ArrayList<>();
        long[][] masks = new long[pieces.size()][];
        int[][] where = new int[pieces.size()][];
        for (int p = 0; p < pieces.size(); p++) {
            List<int[]> cells = new ArrayList<>();
            for (int[] c : pieces.get(p)) cells.add(new int[]{c[0], c[1]});
            norm.add(cells);
            if (cells.isEmpty()) continue;
            normalize(cells);
            idx.add(p);
            int maxR = 0, maxC = 0;
            for (int[] c : cells) { maxR = Math.max(maxR, c[0]); maxC = Math.max(maxC, c[1]); }
            List<Long> ms = new ArrayList<>();
            List<Integer> ps = new ArrayList<>();
            for (int r0 = 0; r0 + maxR < 8; r0++)
                for (int c0 = 0; c0 + maxC < 8; c0++) {
                    long m = 0;
                    for (int[] c : cells) m |= 1L << ((r0 + c[0]) * 8 + c0 + c[1]);
                    ms.add(m); ps.add(r0 * 8 + c0);
                }
            masks[p] = new long[ms.size()];
            where[p] = new int[ms.size()];
            for (int i = 0; i < ms.size(); i++) { masks[p][i] = ms.get(i); where[p][i] = ps.get(i); }
        }
        if (idx.isEmpty()) return null;
        final int n = idx.size();

        List<int[]> perms = new ArrayList<>();
        permutations(idx, new ArrayList<Integer>(), perms);

        State bestPartial = null;
        double bestPartialScore = Double.NEGATIVE_INFINITY;
        java.util.HashMap<Long, State> finals = new java.util.HashMap<>();
        for (int[] perm : perms) {
            List<State> beam = new ArrayList<>();
            beam.add(new State(b0, 0, new int[0], new int[0]));
            for (int k = 0; k < n; k++) {
                int pi = perm[k];
                List<State> next = new ArrayList<>();
                for (State st : beam) {
                    boolean any = false;
                    for (int j = 0; j < masks[pi].length; j++) {
                        long m = masks[pi][j];
                        if ((m & st.b) != 0) continue;
                        any = true;
                        long nb = st.b | m;
                        long clear = 0;
                        int cl = 0;
                        for (int i = 0; i < 8; i++) {
                            if ((nb & ROW[i]) == ROW[i]) { clear |= ROW[i]; cl++; }
                            if ((nb & COL[i]) == COL[i]) { clear |= COL[i]; cl++; }
                        }
                        nb &= ~clear;
                        next.add(new State(nb, st.lines + cl, append(st.order, pi), append(st.pos, where[pi][j])));
                    }
                    if (!any) {
                        double sc = eval(st.b, st.lines, n - k, false);
                        if (sc > bestPartialScore) { bestPartialScore = sc; bestPartial = st; }
                    }
                }
                for (State st : next) st.score = eval(st.b, st.lines, 0, false);
                if (k == n - 1) {
                    for (State st : next) {
                        State old = finals.get(st.b);
                        if (old == null || st.score > old.score) finals.put(st.b, st);
                    }
                } else {
                    Collections.sort(next, new Comparator<State>() {
                        @Override public int compare(State a, State b) { return Double.compare(b.score, a.score); }
                    });
                    if (next.size() > BEAM) next = new ArrayList<>(next.subList(0, BEAM));
                }
                beam = next;
            }
        }
        State best = null;
        if (!finals.isEmpty()) {
            List<State> top = new ArrayList<>(finals.values());
            Collections.sort(top, new Comparator<State>() {
                @Override public int compare(State a, State b) { return Double.compare(b.score, a.score); }
            });
            if (top.size() > K_FINAL) top = new ArrayList<>(top.subList(0, K_FINAL));
            double bs = Double.NEGATIVE_INFINITY;
            for (State st : top) {
                double sc = st.score + W_MC * survivalRate(st.b);
                if (sc > bs) { bs = sc; best = st; }
            }
        } else {
            best = bestPartial;
        }
        if (best == null) return null;
        List<Move> moves = new ArrayList<>();
        for (int t = 0; t < best.order.length; t++) {
            int pi = best.order[t];
            moves.add(new Move(pi, best.pos[t] >> 3, best.pos[t] & 7, norm.get(pi)));
        }
        return new Result(moves, best.lines);
    }
}

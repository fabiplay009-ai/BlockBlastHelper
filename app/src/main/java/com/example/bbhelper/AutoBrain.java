package com.example.bbhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lernen und Entscheidungen des Automatik-Modus, ohne Android-Abhängigkeiten (am PC mit dem
 * Simulator in sim/ testbar). Bildschirmkoordinaten in Pixeln, Strecken/Fehler in Brettfeldern.
 */
public final class AutoBrain {

    // ---- Gelerntes (wird gespeichert) ----

    /** Landefehler je Richtung: (Zugstrecke, Fehler), die letzten 12 Züge. */
    public final ArrayList<float[]> learnX = new ArrayList<>(), learnY = new ArrayList<>();
    /** Je Teilform ("ReihenxSpalten"): Lage der Teil-Mitte zum Finger kurz nach dem Greifen (Felder). */
    public final HashMap<String, float[]> calOff = new HashMap<>();
    /** Gemessene Verstärkungen (kx, ky) der Zugbewegung, die letzten 8. Benutzt wird der Median. */
    public final ArrayList<float[]> kHist = new ArrayList<>();

    // ---- Zustand des laufenden Zugs ----
    public float usedTravelX, usedTravelY, usedCorrX, usedCorrY;
    public String lastKey = "";
    public boolean lastFast;
    public int misses;
    /** Wiederholung desselben Zugs (Teil sprang zurück): Ziel probeweise etwas versetzen, um das Einrasten zu finden. */
    public int retry;
    static final float[][] EXPLORE = {{0, 0}, {0, -0.45f}, {0, 0.45f}, {-0.45f, 0}, {0.45f, 0}};

    public static String key(int rows, int cols) { return rows + "x" + cols; }

    /** Verstärkung (kx, ky): Median der bisherigen Messungen, null = noch nie gemessen. */
    public float[] calK() {
        if (kHist.isEmpty()) return null;
        return new float[]{median(kHist, 0), median(kHist, 1)};
    }

    private static float median(List<float[]> l, int k) {
        float[] v = new float[l.size()];
        for (int i = 0; i < v.length; i++) v[i] = l.get(i)[k];
        Arrays.sort(v);
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    /**
     * Neue Messung der Verstärkung. Weicht sie stark von den bisherigen ab (mehr als 20 %), war es
     * vermutlich eine Fehlmessung: sie wird nicht übernommen und es gilt der bisherige Median.
     * @return die zu benutzende Verstärkung
     */
    public float[] addK(float kx, float ky, boolean kyMeasured) {
        float[] k = calK();
        if (k != null && kHist.size() >= 2) {
            boolean bad = Math.abs(kx - k[0]) > 0.2f * k[0] || (kyMeasured && Math.abs(ky - k[1]) > 0.2f * k[1]);
            if (bad) return k;
        }
        kHist.add(new float[]{kx, kyMeasured ? ky : (k != null ? k[1] : kx)});
        while (kHist.size() > 8) kHist.remove(0);
        return calK();
    }

    /** Geschätzter Landefehler (Felder) bei dieser Zugstrecke: Gerade durch die gespeicherten Züge. */
    public static float predict(List<float[]> l, float travel) {
        if (l.isEmpty()) return 0f;
        int n = l.size();
        double mt = 0, me = 0;
        for (float[] p : l) { mt += p[0]; me += p[1]; }
        mt /= n; me /= n;
        double sxx = 0, sxy = 0;
        for (float[] p : l) { sxx += (p[0] - mt) * (p[0] - mt); sxy += (p[0] - mt) * (p[1] - me); }
        double b = n >= 4 && sxx / n > 0.5 ? Math.max(-0.5, Math.min(0.5, sxy / sxx)) : 0.0;
        return (float) Math.max(-3, Math.min(3, me + b * (travel - mt)));
    }

    /**
     * Fingerziel, damit die Teil-Mitte über dem Ziel (rawX, rawY) liegt: das Teil war bei Finger (fx0, fy0)
     * an (px, py) und bewegt sich um k mal die Fingerbewegung. Mit gelernter Feinkorrektur.
     * @return {fx, fy} oder null, wenn das außerhalb des Bildschirms läge
     */
    public float[] fingerFor(float rawX, float rawY, float px, float py, float kx, float ky,
                             float fx0, float fy0, float cs, int W, int H) {
        usedTravelX = (rawX - px) / cs; usedTravelY = (rawY - py) / cs;
        float[] ex = EXPLORE[Math.min(retry, EXPLORE.length - 1)];
        usedCorrX = -predict(learnX, usedTravelX) + ex[0]; usedCorrY = -predict(learnY, usedTravelY) + ex[1];
        float tx = rawX + usedCorrX * cs, ty = rawY + usedCorrY * cs;
        float fx = fx0 + (tx - px) / kx, fy = fy0 + (ty - py) / ky;
        if (fx < 2 || fx > W - 2 || fy < 2 || fy > H - 2) return null;
        return new float[]{fx, fy};
    }

    /** Das Teil wurde nicht gesetzt (liegt wieder in der Ablage): diese Form beim nächsten Mal neu vermessen. */
    public void notPlaced() {
        if (lastFast) calOff.remove(lastKey);
    }

    /**
     * Wo ist das Teil wirklich gelandet? Vergleicht das Brett vor und nach dem Zug.
     * @return {dr, dc} Abweichung in Feldern, oder null, wenn die Landung nicht erkennbar ist
     *         (z. B. Reihen schon abgeräumt). Bei Erfolg wird gelernt.
     */
    public int[] checkLanding(int[] before, int[] after, List<int[]> cells, int r, int c) {
        ArrayList<Integer> fresh = new ArrayList<>();
        for (int i = 0; i < 64; i++) if (after[i] == Vision.FULL && before[i] == Vision.EMPTY) fresh.add(i);
        if (fresh.size() != cells.size()) return null;
        int r0 = 99, c0 = 99;
        for (int i : fresh) { r0 = Math.min(r0, i / 8); c0 = Math.min(c0, i % 8); }
        boolean[] shape = new boolean[64];
        for (int i : fresh) shape[(i / 8 - r0) * 8 + (i % 8 - c0)] = true;
        for (int[] x : cells) if (x[0] * 8 + x[1] >= 64 || !shape[x[0] * 8 + x[1]]) return null;
        int dr = r0 - r, dc = c0 - c;
        // tatsächlicher Fehler = Landestelle minus Zielpunkt, auf den gezielt wurde. Mehr als ein Feld
        // daneben ist ein Ausreißer (z. B. Fehlmessung) und wird nicht gelernt – sonst zieht ein einzelner
        // Fehlwurf die nächsten Züge mit.
        if (Math.abs(dr) <= 1 && Math.abs(dc) <= 1) {
            addSample(learnX, usedTravelX, dc - usedCorrX);
            addSample(learnY, usedTravelY, dr - usedCorrY);
        }
        if (dr == 0 && dc == 0) misses = 0;
        else {
            misses++;
            // diese Form beim nächsten Mal neu ausmessen statt mit womöglich falschen Werten weiterzumachen
            if (lastFast) calOff.remove(lastKey);
        }
        return new int[]{dr, dc};
    }

    private static void addSample(ArrayList<float[]> l, float travel, float err) {
        l.add(new float[]{travel, err});
        while (l.size() > 12) l.remove(0);
    }

    // ---- Speichern / Laden ----

    public String save() {
        StringBuilder sb = new StringBuilder();
        sb.append(list(learnX)).append('#').append(list(learnY)).append('#').append(list(kHist)).append('#');
        boolean first = true;
        for (Map.Entry<String, float[]> e : calOff.entrySet()) {
            if (!first) sb.append(';');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue()[0]).append(':').append(e.getValue()[1]);
        }
        return sb.toString();
    }

    private static String list(List<float[]> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(l.get(i)[0]).append(':').append(l.get(i)[1]);
        }
        return sb.toString();
    }

    public void load(String s) {
        learnX.clear(); learnY.clear(); kHist.clear(); calOff.clear();
        if (s == null || s.isEmpty()) return;
        try {
            String[] p = s.split("#", -1);
            parse(p[0], learnX); parse(p[1], learnY); parse(p[2], kHist);
            if (p.length > 3) for (String e : p[3].split(";")) {
                if (!e.contains("=")) continue;
                String[] kv = e.split("="), v = kv[1].split(":");
                calOff.put(kv[0], new float[]{Float.parseFloat(v[0]), Float.parseFloat(v[1])});
            }
        } catch (Exception e) {
            learnX.clear(); learnY.clear(); kHist.clear(); calOff.clear();
        }
    }

    private static void parse(String s, List<float[]> l) {
        for (String e : s.split(";")) {
            if (!e.contains(":")) continue;
            String[] v = e.split(":");
            l.add(new float[]{Float.parseFloat(v[0]), Float.parseFloat(v[1])});
        }
    }

    // ---------- Ausmessen beim Ziehen ----------

    /**
     * Ablauf des Ausmessens für einen Zug. Die App (bzw. der Simulator) hält den Finger, liefert nach
     * jedem kurzen Halteschritt eine Messung ({@link #onMeasure}) und führt die zurückgegebene Aktion aus.
     *  Phase 1: Teil greifen, halten bis es ruhig schwebt -> Lage P0 bei Finger F0. Ragt es ins Brett,
     *           wird der Finger tiefer gesetzt (über dem Brett stören Schatten, Aufleuchten, gleiche Farben).
     *  Phase 2: Finger seitwärts (und nur nach unten, wenn Platz) -> Lage P1 -> Verstärkung k.
     *  Dann: Finger so hinbewegen, dass die Teil-Mitte über dem Ziel liegt, loslassen.
     */
    public final class Plan {
        public static final int CONTINUE = 0, MOVE = 1, RELEASE = 2, FAIL = 3;

        final int W, H, rows, cols;
        final float cs, sx, sy, rawX, rawY, top, boardBot, bot;
        public final String key;

        public int phase = 1, ticks = 0, lowered = 0, shifted = 0;
        public float f0x, f0y, dxF, dyF;
        float prevBottom = Float.NaN, prevF0y;      // Lage vor dem letzten Tiefersetzen (zum Schätzen der Verstärkung)
        Vision.Box last, p0;
        final ArrayList<Vision.Box> good = new ArrayList<>();
        public String info = "";

        // Ergebnis von onMeasure
        public float ax, ay;
        public String msg;

        public Plan(int W, int H, float sx, float sy, int rows, int cols, int r, int c) {
            this.W = W; this.H = H; this.rows = rows; this.cols = cols;
            cs = Vision.cell(W);
            this.sx = sx; this.sy = sy; f0x = sx; f0y = sy;
            rawX = Vision.BX * W + (c + cols / 2f) * cs;
            rawY = Vision.BY * H + (r + rows / 2f) * cs;
            top = Vision.BY * H;
            boardBot = Vision.BY * H + 8 * cs;
            bot = Vision.floatBottom(H);
            key = key(rows, cols);
        }

        /** Schnellweg, wenn die Form schon vermessen ist: Fingerziel oder null (dann ausmessen). */
        public float[] fastTarget() {
            float[] k = calK();
            float[] off = calOff.get(key);
            if (k == null || off == null) return null;
            return fingerFor(rawX, rawY, sx + off[0] * cs, sy + off[1] * cs, k[0], k[1], sx, sy, cs, W, H);
        }

        private void newPhase(int p) { phase = p; ticks = 0; last = null; good.clear(); }

        private int move(float x, float y) { ax = x; ay = y; return MOVE; }

        private int fail(String m) { msg = m; return FAIL; }

        /** Eine Messung auswerten (raw = gefundene Fläche oder null). */
        public int onMeasure(Vision.Box raw) {
            ticks++;
            boolean below = raw != null && raw.top >= boardBot - 0.15f * cs;       // ganz unter dem Brett
            boolean inside = raw != null && raw.top > top + 8 && raw.bottom < bot - 8;
            Vision.Box p = raw != null && below && inside && Vision.plausible(raw, rows, cols, W) ? raw : null;
            if (raw == null) info = "nichts gefunden";
            else if (!inside) info = "Fläche stößt an den Rand des Suchbereichs";
            else if (!below) info = "Teil ragt ins Brett";
            else info = String.format("Fläche %.2f x %.2f Felder (erwartet %d x %d), passend: %d",
                    raw.width() / cs, raw.height() / cs, cols, rows, good.size() + (p != null ? 1 : 0));
            if (lowered + shifted > 0) info += ", Finger " + (lowered + shifted) + "x nachgesetzt";
            // Phase 1: klebt das Teil am Bildschirmrand, hat das Spiel es dorthin geschoben (es würde sonst
            // hinausragen) – die gemessene Lage passt dann nicht zur Fingerbewegung. Finger erst zur Mitte rücken.
            if (phase == 1 && raw != null && shifted < 4) {
                boolean atLeft = raw.left < 0.25f * cs, atRight = raw.right > W - 0.25f * cs;
                if (atLeft != atRight) {
                    shifted++;
                    f0x = Math.max(cs, Math.min(W - cs, f0x + (atLeft ? 1 : -1) * cs));
                    newPhase(1);
                    return move(f0x, f0y);
                }
            }
            // Phase 1: Lage korrigieren, bis das Teil ganz unter dem Brett schwebt. Über gleichfarbigen Brett-Blöcken
            // ist das Teil unsichtbar – endet die Fläche oben an der Brettkante, ragt es also vermutlich hinein.
            if (phase == 1 && p == null && raw != null && ticks >= 3 && lowered < 6) {
                float fullH = rows * cs;
                boolean touchBottom = raw.bottom >= bot - 8;
                boolean hiddenTop = raw.top < boardBot + 0.3f * cs;
                float shift = 0;                     // gewünschte Verschiebung des Teils in px (+ = nach unten)
                if (hiddenTop && !touchBottom) shift = (boardBot + 0.15f * cs) - (raw.bottom - fullH);
                else if (touchBottom && !hiddenTop) shift = (bot - 0.25f * cs) - (raw.top + fullH);
                if (Math.abs(shift) > 0.05f * cs) {
                    // Verstärkung: bekannt, sonst aus dem letzten Tiefersetzen, sonst vorsichtig geschätzt
                    float k = 2f;
                    float[] ck = calK();
                    if (ck != null) k = ck[1];
                    else if (!Float.isNaN(prevBottom) && f0y != prevF0y) {
                        float m = (raw.bottom - prevBottom) / (f0y - prevF0y);
                        if (m > 0.5f && m < 5f) k = m;
                    }
                    if (!touchBottom) { prevBottom = raw.bottom; prevF0y = f0y; }
                    lowered++;
                    f0y = Math.max(sy - 3 * cs, Math.min(H * 0.97f, f0y + shift / k));
                    newPhase(1);
                    return move(f0x, f0y);
                }
            }
            if (p != null) {
                Vision.Box l = last;
                good.add(p);
                float tol = cs * 0.15f;
                if (l != null && Math.abs(p.cx() - l.cx()) <= tol && Math.abs(p.cy() - l.cy()) <= tol
                        && Math.abs(p.width() - l.width()) <= tol)
                    return result(new Vision.Box((p.left + l.left) / 2, (p.top + l.top) / 2, (p.right + l.right) / 2, (p.bottom + l.bottom) / 2));
            }
            last = p;
            if (ticks >= 15) {
                if (good.size() >= 3) {
                    List<Vision.Box> n = good.subList(Math.max(0, good.size() - 4), good.size());
                    float a = 0, b = 0, c = 0, d = 0;
                    for (Vision.Box q : n) { a += q.left; b += q.top; c += q.right; d += q.bottom; }
                    int k = n.size();
                    return result(new Vision.Box(a / k, b / k, c / k, d / k));
                }
                return fail("gezogenes Teil nicht gefunden (" + phase + ") – " + info);
            }
            return CONTINUE;
        }

        private int result(Vision.Box r) {
            if (phase == 1) {
                p0 = r;
                // Testbewegung: seitwärts zur Mitte; nach unten nur, wenn unter dem Teil Platz ist.
                // Nie nach oben – über dem Brett ist keine saubere Messung möglich.
                dxF = (f0x < W / 2f ? 0.8f : -0.8f) * cs;
                float roomDown = bot - r.bottom;
                dyF = roomDown > 0.6f * cs ? Math.min(roomDown / 4f, 0.6f * cs) : 0f;
                newPhase(2);
                return move(f0x + dxF, f0y + dyF);
            }
            Vision.Box q0 = p0;
            // Teil muss bei beiden Messungen gleich groß sein, sonst war es teilweise verdeckt
            boolean sameSize = Math.abs(r.width() - q0.width()) <= 0.2f * cs && Math.abs(r.height() - q0.height()) <= 0.2f * cs;
            float kx = (r.cx() - q0.cx()) / dxF;
            float ky = dyF != 0f ? (r.cy() - q0.cy()) / dyF : Float.NaN;
            float[] k;
            if (!sameSize || kx < 0.5f || kx > 5f || (dyF != 0f && (ky < 0.5f || ky > 5f))) {
                k = calK();
                if (k == null) return fail(String.format("Zugverhalten nicht messbar (k=%.2f/%.2f)", kx, ky));
            } else {
                k = addK(kx, dyF != 0f ? ky : kx, dyF != 0f);
            }
            float[] t = fingerFor(rawX, rawY, q0.cx(), q0.cy(), k[0], k[1], f0x, f0y, cs, W, H);
            if (t == null) return fail("Ziel außerhalb des Bildschirms");
            // Lage der Teil-Mitte, wenn der Finger noch an der Ablage wäre (S), relativ zu S merken.
            // Beim nächsten Teil dieser Form geht es dann ohne Ausmessen.
            float psx = q0.cx() - k[0] * (f0x - sx), psy = q0.cy() - k[1] * (f0y - sy);
            calOff.put(key, new float[]{(psx - sx) / cs, (psy - sy) / cs});
            ax = t[0]; ay = t[1];
            return RELEASE;
        }
    }
}

package com.example.bbhelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bildauswertung des Helfers ohne Android-Abhängigkeiten, damit sie sich am PC mit dem Simulator
 * (Ordner sim/) genau so testen lässt, wie sie auf dem Handy läuft.
 */
public final class Vision {
    private Vision() {}

    public static final int EMPTY = 0, FULL = 1, UNSURE = 2;

    // ---- Kalibrierung (Anteile von Bildschirmbreite W bzw. -höhe H), gemessen am Pixel 8a ----
    public static final float BX = 0.0574f;  // Brett: linker Rand (x W)
    public static final float BY = 0.2417f;  // Brett: oberer Rand (x H)
    public static final float BS = 0.885f;   // Brett: Breite (x W)
    public static final float TY0 = 0.68f;   // Ablage der Teile: oben (x H), hoch genug für 5er-Stangen
    public static final float TY1 = 0.90f;   // Ablage der Teile: unten (x H)
    public static final float TS = 0.45f;    // Feldgröße in der Ablage relativ zum Brettfeld
    public static final int MIND_BOARD = 70; // Brettfeld belegt, wenn Farbabstand zum leeren Feld größer ist
    public static final int MIND_EMPTY = 35; // Brettfeld sicher leer, wenn Farbabstand kleiner ist (dazwischen = unsicher)
    public static final int MIND = 50;       // Teil erkannt, wenn Farbabstand zum Ablage-Hintergrund größer ist
    public static final int NEED = 8;        // Ablagefeld belegt, wenn mind. so viele von 25 Stichproben "Teil" sind
                                             // (leere Felder: 0, volle: 16-25 – auch bei dunkel schattierten Blöcken)

    /** Ein Bildschirmbild im RGBA-Format (so wie es der ImageReader liefert). */
    public static final class Frame {
        public final byte[] buf;
        public final int stride, w, h;

        public Frame(byte[] buf, int stride, int w, int h) {
            this.buf = buf; this.stride = stride; this.w = w; this.h = h;
        }

        int off(int x, int y) {
            return Math.max(0, Math.min(h - 1, y)) * stride + Math.max(0, Math.min(w - 1, x)) * 4;
        }

        /** Farbe an (x, y), Koordinaten werden auf das Bild begrenzt. */
        public int[] rgb(int x, int y) {
            int o = off(x, y);
            if (o + 2 >= buf.length) return new int[]{0, 0, 0};
            return new int[]{buf[o] & 255, buf[o + 1] & 255, buf[o + 2] & 255};
        }
    }

    /** Rechteck in Bildschirm-Pixeln. */
    public static final class Box {
        public final float left, top, right, bottom;

        public Box(float left, float top, float right, float bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }

        public float width() { return right - left; }
        public float height() { return bottom - top; }
        public float cx() { return (left + right) / 2; }
        public float cy() { return (top + bottom) / 2; }
    }

    public static float cell(int w) { return BS * w / 8; }

    static double dist(int[] a, int[] b) {
        int dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    // ---------- Brett ----------

    /**
     * Brett: je Feld 9 Stichproben aus der Feldmitte. Leer = Farbe der dunkelsten Felder.
     * Ein Feld ist nur dann LEER bzw. BELEGT, wenn die klare Mehrheit der Stichproben das sagt,
     * sonst UNSICHER (z. B. Animation, Effekt, halb verdeckt).
     */
    public static int[] readCells(Frame f) {
        float bx = BX * f.w, by = BY * f.h, cs = cell(f.w);
        int[][][] smp = new int[64][9][];
        int[][] avg = new int[64][3];
        for (int i = 0; i < 64; i++) {
            for (int k = 0; k < 9; k++) {
                int dx = k % 3 - 1, dy = k / 3 - 1;
                smp[i][k] = f.rgb((int) (bx + (i % 8 + .5f + dx * .2f) * cs), (int) (by + (i / 8 + .5f + dy * .2f) * cs));
                for (int c = 0; c < 3; c++) avg[i][c] += smp[i][k][c];
            }
            for (int c = 0; c < 3; c++) avg[i][c] /= 9;
        }
        int[] darkest = avg[0];
        for (int[] a : avg) if (a[0] * 3 + a[1] * 6 + a[2] < darkest[0] * 3 + darkest[1] * 6 + darkest[2]) darkest = a;
        int[] acc = new int[3];
        int cnt = 0;
        for (int[] a : avg) if (dist(a, darkest) < 40) { cnt++; for (int c = 0; c < 3; c++) acc[c] += a[c]; }
        int[] emptyCol = {acc[0] / cnt, acc[1] / cnt, acc[2] / cnt};
        int[] out = new int[64];
        for (int i = 0; i < 64; i++) {
            int full = 0, empty = 0;
            for (int[] p : smp[i]) {
                double d = dist(p, emptyCol);
                if (d > MIND_BOARD) full++;
                if (d < MIND_EMPTY) empty++;
            }
            out[i] = full >= 7 ? FULL : empty >= 7 ? EMPTY : UNSURE;
        }
        return out;
    }

    // ---------- Ablage ----------

    /** Hängen die Felder zusammen? Auch über Eck: es gibt diagonale Teile. */
    static boolean connected(List<int[]> cells) {
        if (cells.isEmpty()) return false;
        boolean[] seen = new boolean[cells.size()];
        ArrayList<Integer> stack = new ArrayList<>();
        stack.add(0); seen[0] = true;
        int n = 0;
        while (!stack.isEmpty()) {
            int i = stack.remove(stack.size() - 1);
            n++;
            for (int j = 0; j < cells.size(); j++) {
                if (!seen[j] && Math.max(Math.abs(cells.get(i)[0] - cells.get(j)[0]), Math.abs(cells.get(i)[1] - cells.get(j)[1])) == 1) {
                    seen[j] = true; stack.add(j);
                }
            }
        }
        return n == cells.size();
    }

    /**
     * Ablage: Teil = Pixel, der sich deutlich vom Hintergrund des Feldes unterscheidet.
     * Unplausible Formen (zusammengerutscht, mitten in der Animation) zählen als "leer".
     * [boxes] bekommt je Fach die Lage des Teils (oder null).
     */
    public static List<List<int[]>> readTray(Frame f, Box[] boxes) {
        int W = f.w, H = f.h;
        float cs = cell(W);
        float t = cs * TS;                                   // erwartete Feldgröße in der Ablage (px)
        int er = Math.max(2, Math.round(6f * W / 1080f));    // Mindestdicke: dünne Rahmenlinien zählen nicht
        List<List<int[]>> out = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            boxes[s] = null;
            int x0 = W * s / 3, x1 = W * (s + 1) / 3;
            int y0 = (int) (TY0 * H), y1 = (int) (TY1 * H);
            // Hintergrund = Median aller Farbwerte im Feld (Teile sind nur ein kleiner Teil der Fläche)
            ArrayList<Integer> rs = new ArrayList<>(), gs = new ArrayList<>(), bs = new ArrayList<>();
            for (int y = y0 + 10; y < y1 - 30; y += 8)
                for (int x = x0 + 10; x < x1 - 10; x += 8) {
                    int[] p = f.rgb(x, y); rs.add(p[0]); gs.add(p[1]); bs.add(p[2]);
                }
            rs.sort(null); gs.sort(null); bs.sort(null);
            final int[] bg = {rs.get(rs.size() / 2), gs.get(gs.size() / 2), bs.get(bs.size() / 2)};
            int mnX = Integer.MAX_VALUE, mnY = Integer.MAX_VALUE, mxX = -1, mxY = -1;
            for (int y = y0 + 10; y < y1 - 30; y += 4)
                for (int x = x0 + 10; x < x1 - 10; x += 4)
                    if (on(f, bg, x, y) && on(f, bg, x - er, y) && on(f, bg, x + er, y) && on(f, bg, x, y - er) && on(f, bg, x, y + er)) {
                        mnX = Math.min(mnX, x); mxX = Math.max(mxX, x); mnY = Math.min(mnY, y); mxY = Math.max(mxY, y);
                    }
            ArrayList<int[]> cells = new ArrayList<>();
            if (mxX >= 0) {
                float left = mnX - er - 2, top = mnY - er - 2;
                float w = mxX - mnX + 2 * er + 4, ph = mxY - mnY + 2 * er + 4;
                float fw = w / t, fh = ph / t;
                boxes[s] = new Box(left, top, left + w, top + ph);
                if (Math.abs(fw - Math.round(fw)) <= 0.3f && Math.abs(fh - Math.round(fh)) <= 0.3f) {
                    int cols = Math.max(1, Math.min(5, Math.round(fw)));
                    int rows = Math.max(1, Math.min(5, Math.round(fh)));
                    float rw = w / cols / t, rh = ph / rows / t;
                    // Feldgröße muss zur Ablage passen (sonst: Teil noch mitten in der Animation)
                    if (rw >= 0.8f && rw <= 1.3f && rh >= 0.8f && rh <= 1.3f) {
                        for (int r = 0; r < rows; r++)
                            for (int c = 0; c < cols; c++) {
                                float cx = left + (c + .5f) * w / cols, cy = top + (r + .5f) * ph / rows;
                                int n = 0;
                                for (int dx = -2; dx <= 2; dx++)
                                    for (int dy = -2; dy <= 2; dy++)
                                        if (on(f, bg, (int) (cx + dx * .15f * t), (int) (cy + dy * .15f * t))) n++;
                                if (n >= NEED) cells.add(new int[]{r, c});
                            }
                        if (!connected(cells)) cells.clear();
                    }
                }
            }
            out.add(cells);
        }
        return out;
    }

    private static boolean on(Frame f, int[] bg, int x, int y) { return dist(f.rgb(x, y), bg) > MIND; }

    /** Kurzform einer Ablage-Lesung, z. B. "00,01,11|...|..." (zum Vergleichen). */
    public static String signature(List<List<int[]>> tray) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < tray.size(); s++) {
            if (s > 0) sb.append('|');
            List<int[]> cells = tray.get(s);
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(cells.get(i)[0]).append(cells.get(i)[1]);
            }
        }
        return sb.toString();
    }

    // ---------- gezogenes Teil ----------

    /** Unterer Rand des Suchbereichs: knapp über der Werbung (der Hinweis-Balken ist unverändert und fällt weg). */
    public static float floatBottom(int h) { return h * 0.925f; }

    /**
     * Farbe des Teils in der Ablage (im Bild vor dem Greifen): die häufigste Farbe unter den Pixeln, die sich
     * vom Hintergrund abheben – also die große Blockfläche, nicht die hellen/dunklen Kanten. (Früher: nur sehr
     * auffällige Pixel; im Holz-Design fiel die Blockfläche dadurch heraus und es blieb die Kantenfarbe übrig.)
     */
    public static int[] pieceColor(Frame f, Box box) {
        int W = f.w, H = f.h;
        float cs = cell(W);
        int bgTop = (int) (BY * H + 8 * cs + 6), bot = (int) (TY1 * H - 34);
        ArrayList<int[]> bgs = new ArrayList<>();
        for (int y = bgTop; y < bot; y += 16) for (int x = 4; x < W - 4; x += 16) bgs.add(f.rgb(x, y));
        if (bgs.isEmpty()) return null;
        int[] bg = median(bgs);
        ArrayList<int[]> ps = new ArrayList<>();
        int[] hist = new int[16 * 16 * 16];
        for (int y = (int) box.top; y < box.bottom; y += 2)
            for (int x = (int) box.left; x < box.right; x += 2) {
                int[] c = f.rgb(x, y);
                if (dist(c, bg) > MIND) { ps.add(c); hist[(c[0] >> 4) * 256 + (c[1] >> 4) * 16 + (c[2] >> 4)]++; }
            }
        if (ps.size() < 10) return null;
        int best = 0;
        for (int i = 1; i < hist.length; i++) if (hist[i] > hist[best]) best = i;
        // Median der Pixel in der Nachbarschaft der häufigsten Farbe
        int br = (best >> 8) * 16 + 8, bgr = ((best >> 4) & 15) * 16 + 8, bb = (best & 15) * 16 + 8;
        ArrayList<int[]> core = new ArrayList<>();
        for (int[] c : ps) if (dist(c, new int[]{br, bgr, bb}) < 28) core.add(c);
        return median(core.isEmpty() ? ps : core);
    }

    private static int[] median(List<int[]> l) {
        int[] out = new int[3];
        for (int k = 0; k < 3; k++) {
            int[] v = new int[l.size()];
            for (int i = 0; i < v.length; i++) v[i] = l.get(i)[k];
            Arrays.sort(v);
            out[k] = v[v.length / 2];
        }
        return out;
    }

    /**
     * Sucht das gerade gezogene (schwebende) Teil zwischen oberem Brettrand und Werbung.
     * Teil-Pixel = weicht vom Ablage-Hintergrund ab, hat die Farbe des Teils [pc] (der dunklere Schatten an
     * der Landestelle zählt so nicht) UND hat sich seit dem Greifen [ref] verändert; so fallen Brett, andere
     * Teile und Hinweis-Balken von selbst weg. null = nichts gefunden. Ob die Fläche an den Rand des
     * Suchbereichs stößt, prüft der Aufrufer. Schnell, weil ohne Speicher-Anlegen pro Pixel.
     */
    public static Box findFloating(Frame f, Frame ref, int[] pc) {
        int W = f.w, H = f.h;
        float cs = cell(W);
        int top = (int) (BY * H + 4), bot = (int) floatBottom(H);
        int bgTop = (int) (BY * H + 8 * cs + 6);              // Hintergrundfarbe nur unter dem Brett bestimmen
        byte[] b = f.buf;
        byte[] rf = ref != null && ref.buf.length == b.length ? ref.buf : null;
        ArrayList<int[]> bgs = new ArrayList<>();
        for (int y = bgTop; y < bot; y += 12) for (int x = 4; x < W - 4; x += 12) bgs.add(f.rgb(x, y));
        if (bgs.isEmpty()) return null;
        int[] bg = median(bgs);
        int er = Math.max(2, Math.round(6f * W / 1080f));
        int boardBot = (int) (BY * H + 8 * cs);
        int mnX = Integer.MAX_VALUE, mnY = Integer.MAX_VALUE, mxX = -1, mxY = -1;
        for (int y = top; y < bot; y += 5)
            for (int x = 4; x < W - 4; x += 5)
                if (fl(f, rf, bg, pc, boardBot, x, y) && fl(f, rf, bg, pc, boardBot, x - er, y) && fl(f, rf, bg, pc, boardBot, x + er, y)
                        && fl(f, rf, bg, pc, boardBot, x, y - er) && fl(f, rf, bg, pc, boardBot, x, y + er)) {
                    mnX = Math.min(mnX, x); mxX = Math.max(mxX, x); mnY = Math.min(mnY, y); mxY = Math.max(mxY, y);
                }
        if (mxX < 0) return null;
        return new Box(mnX, mnY, mxX, mxY);
    }

    private static boolean fl(Frame f, byte[] rf, int[] bg, int[] pc, int boardBot, int x, int y) {
        int o = f.off(x, y);
        byte[] b = f.buf;
        if (o + 2 >= b.length) return false;
        int r = b[o] & 255, g = b[o + 1] & 255, bl = b[o + 2] & 255;
        int dr = r - bg[0], dg = g - bg[1], db = bl - bg[2];
        if (dr * dr + dg * dg + db * db <= MIND * MIND) return false;
        if (pc != null) {
            int pr = r - pc[0], pg = g - pc[1], pb = bl - pc[2];
            if (pr * pr + pg * pg + pb * pb > 60 * 60) return false;
        }
        if (rf == null) return true;
        int rr = rf[o] & 255, rg = rf[o + 1] & 255, rb = rf[o + 2] & 255;
        // Auf dem Brett: war hier vorher schon ein Block in Teilfarbe, zählt das Pixel nicht – sonst halten
        // noch laufende Animationen gleichfarbiger Brett-Blöcke als "gezogenes Teil" her.
        if (pc != null && y < boardBot) {
            int qr = rr - pc[0], qg = rg - pc[1], qb = rb - pc[2];
            if (qr * qr + qg * qg + qb * qb <= 60 * 60) return false;
        }
        int e1 = r - rr, e2 = g - rg, e3 = bl - rb;
        return e1 * e1 + e2 * e2 + e3 * e3 > 30 * 30;
    }

    /** Passt die gefundene Fläche zur Form des Teils (ungefähr Brett-Feldgröße)? */
    public static boolean plausible(Box r, int rows, int cols, int w) {
        float cs = cell(w);
        float fw = r.width() / cols / cs, fh = r.height() / rows / cs;
        return fw >= 0.45f && fw <= 1.4f && fh >= 0.45f && fh <= 1.4f && Math.abs(fw - fh) < 0.25f;
    }
}

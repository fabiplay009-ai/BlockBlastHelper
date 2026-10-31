import com.example.bbhelper.AutoBrain;
import com.example.bbhelper.Solver;
import com.example.bbhelper.Vision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Simulator für den Automatik-Modus: erzeugt Spielbilder (Holz-Design wie auf den Screenshots) und
 * spielt mit dem echten Code der App (Vision, AutoBrain, Solver). Nachgebildet werden:
 *  - einfliegende neue Teile (nacheinander von rechts, wachsend)
 *  - gezogenes Teil: schwebt über dem Finger, bewegt sich um k mal die Fingerbewegung, ruckelt nach,
 *    bleibt im Bildschirm; Schatten an der Landestelle, Aufleuchten voller Reihen
 *  - Einrasten mit (unbekanntem) Versatz, Zurückspringen bei ungültiger Stelle, Abräum-Animation
 *  - wechselnde Werbung, Hinweis-Rahmen der App im Bild
 * Der Ablauf (Abfrage, Ruhezeit, Ziehen) ist dem der App (HelperService) nachgebaut.
 *
 * Starten (aus dem Projektordner):
 *   javac -d /tmp/sim app/src/main/java/com/example/bbhelper/{Vision,AutoBrain,Solver}.java sim/Sim.java
 *   java -cp /tmp/sim Sim
 */
public class Sim {
    static final int W = 1080, H = 2400, ST = W * 4;
    static final float CS = Vision.cell(W), BXP = Vision.BX * W, BYP = Vision.BY * H;
    static final float SLOT_Y = 0.7575f * H, T = CS * Vision.TS;

    // Holz-Design (Farben aus den Screenshots)
    static final int[] BG = {181, 129, 58}, CELL = {74, 45, 25}, GRID = {52, 30, 16}, FRAMEC = {222, 154, 74};
    static final int[] BLOCK = {214, 100, 8}, LIGHT = {240, 140, 40}, DARK = {160, 60, 0};
    static final int[] GHOST = {120, 62, 18}, GLOW = {255, 170, 80};
    static final int[][] HINT = {{47, 123, 245}, {15, 167, 122}, {232, 137, 12}};
    static final int[][] ADS = {{250, 250, 250}, {20, 20, 20}, {240, 150, 20}, {120, 230, 40}};

    static final List<List<int[]>> SHAPES = Solver.standardShapes();
    static byte[] bgBuf;
    static boolean TRACE = false;

    // ------------------------------------------------------------------ Spiel

    static final class Game {
        final Random rnd;
        final float kx, ky, biasX, biasY, lift = 1.2f * CS;
        boolean[] board = new boolean[64];
        long[] clearing = new long[64];
        List<int[]>[] tray = new List[3];
        long[] flyStart = new long[3];
        long now = 0, spawnAt = 0;
        // Finger / gezogenes Teil
        boolean down;
        float fx, fy, downFx, downFy, pDownX, pDownY, dispX, dispY, scale;
        int drag = -1;
        // zurückspringendes Teil
        int ret = -1; long retStart; float retX, retY;
        // Hinweis der App (liegt als Overlay im Bild)
        List<int[]> hint; int hintR, hintC, hintSlot = -1;
        // Ergebnis des letzten Loslassens
        int lastR, lastC; boolean lastPlaced, lastDrop;
        int gameOvers = 0;
        boolean texts;                 // Combo-Schriftzüge über der Ablage einblenden
        boolean glitch;                // gezogenes Teil ruckelt zufällig (Hänger im Spiel)
        long textUntil = 0; int textSeed;

        Game(long seed, float kx, float ky, float biasX, float biasY) {
            rnd = new Random(seed); this.kx = kx; this.ky = ky; this.biasX = biasX; this.biasY = biasY;
            spawnAt = 100;
        }

        static float slotX(int s) { return W * (2 * s + 1) / 6f; }

        static int rows(List<int[]> p) { int m = 0; for (int[] c : p) m = Math.max(m, c[0]); return m + 1; }
        static int cols(List<int[]> p) { int m = 0; for (int[] c : p) m = Math.max(m, c[1]); return m + 1; }

        boolean fits(List<int[]> p, int r, int c, boolean[] b) {
            for (int[] x : p) {
                int rr = r + x[0], cc = c + x[1];
                if (rr < 0 || rr > 7 || cc < 0 || cc > 7 || b[rr * 8 + cc]) return false;
            }
            return true;
        }

        boolean fitsAnywhere(List<int[]> p) {
            for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) if (fits(p, r, c, board)) return true;
            return false;
        }

        /** Einrasten wie im Spiel: aus der angezeigten Lage des Teils (mit Versatz), {r, c}. */
        int[] snap() {
            List<int[]> p = tray[drag];
            float left = dispX - cols(p) * CS / 2, top = dispY - rows(p) * CS / 2;
            return new int[]{Math.round((top - BYP) / CS + biasY), Math.round((left - BXP) / CS + biasX)};
        }

        void setFinger(boolean d, float x, float y) {
            if (d && !down) touchDown(x, y);
            if (!d && down) touchUp();
            down = d; fx = x; fy = y;
        }

        void touchDown(float x, float y) {
            for (int s = 0; s < 3; s++) {
                if (tray[s] == null || now < flyStart[s] + 350 || s == ret) continue;
                if (Math.abs(x - slotX(s)) < W / 6f && y > Vision.TY0 * H && y < Vision.TY1 * H) {
                    drag = s; downFx = x; downFy = y;
                    pDownX = x; pDownY = y - lift - rows(tray[s]) * CS / 2;
                    dispX = slotX(s); dispY = SLOT_Y; scale = Vision.TS;
                    return;
                }
            }
        }

        void touchUp() {
            if (drag < 0) return;
            int[] rc = snap();
            List<int[]> p = tray[drag];
            lastDrop = true; lastR = rc[0]; lastC = rc[1];
            if (fits(p, rc[0], rc[1], board)) {
                lastPlaced = true;
                for (int[] x : p) board[(rc[0] + x[0]) * 8 + rc[1] + x[1]] = true;
                tray[drag] = null;
                int lines = 0;
                for (int i = 0; i < 8; i++) {
                    boolean row = true, col = true;
                    for (int j = 0; j < 8; j++) { row &= board[i * 8 + j]; col &= board[j * 8 + i]; }
                    if (row) lines++;
                    if (col) lines++;
                    for (int j = 0; j < 8; j++) {
                        if (row) clearing[i * 8 + j] = now + 1;
                        if (col) clearing[j * 8 + i] = now + 1;
                    }
                }
                if (texts && lines > 0) { textUntil = now + 1100; textSeed = rnd.nextInt(1000); }
            } else {
                lastPlaced = false;
                ret = drag; retStart = now; retX = dispX; retY = dispY;
            }
            drag = -1;
        }

        /** Spielzeit um ms weiterlaufen lassen (16-ms-Schritte, Finger bleibt wo er ist). */
        void run(long ms) {
            long end = now + ms;
            while (now < end) { now += Math.min(16, end - now); step(); }
        }

        void step() {
            if (drag >= 0) {
                List<int[]> p = tray[drag];
                float tx = pDownX + kx * (fx - downFx), ty = pDownY + ky * (fy - downFy);
                float hw = cols(p) * CS / 2, hh = rows(p) * CS / 2;
                tx = Math.max(hw, Math.min(W - hw, tx)); ty = Math.max(hh, Math.min(H - hh, ty));   // bleibt im Bild
                dispX += (tx - dispX) * 0.45f; dispY += (ty - dispY) * 0.45f;                     // ruckelt nach
                scale += (1 - scale) * 0.35f;
            }
            if (ret >= 0 && now - retStart > 250) ret = -1;
            for (int i = 0; i < 64; i++) if (clearing[i] > 0 && now - clearing[i] > 350) { board[i] = false; clearing[i] = 0; }
            boolean anyClear = false;
            for (long c : clearing) anyClear |= c > 0;
            boolean empty = tray[0] == null && tray[1] == null && tray[2] == null;
            if (empty && drag < 0 && !anyClear && spawnAt == 0) spawnAt = now + 300;
            if (spawnAt > 0 && now >= spawnAt) {
                for (int s = 0; s < 3; s++) {
                    tray[s] = SHAPES.get(rnd.nextInt(SHAPES.size()));
                    flyStart[s] = now + s * 120;                 // nacheinander von rechts
                }
                spawnAt = 0;
            }
            // Game Over: keins der (fertig eingeflogenen) Teile passt mehr -> neues Spiel
            if (!empty && drag < 0 && ret < 0 && !anyClear && spawnAt == 0) {
                boolean ready = true, any = false;
                for (int s = 0; s < 3; s++) if (tray[s] != null) { ready &= now > flyStart[s] + 400; any |= fitsAnywhere(tray[s]); }
                if (ready && !any) {
                    gameOvers++;
                    board = new boolean[64]; clearing = new long[64];
                    tray = new List[3]; spawnAt = now + 300;
                }
            }
        }

        // ---------------------------------------------------------- Bild

        byte[] render() {
            byte[] b = bgBuf.clone();
            // Brett
            fill(b, BXP - 14, BYP - 14, 8 * CS + 28, 8 * CS + 28, FRAMEC);
            fill(b, BXP, BYP, 8 * CS, 8 * CS, GRID);
            int[] ghostRc = null; boolean[] glow = new boolean[64];
            if (drag >= 0) {
                int[] rc = snap();
                if (fits(tray[drag], rc[0], rc[1], board)) {
                    ghostRc = rc;
                    boolean[] nb = board.clone();
                    for (int[] x : tray[drag]) nb[(rc[0] + x[0]) * 8 + rc[1] + x[1]] = true;
                    for (int i = 0; i < 8; i++) {
                        boolean row = true, col = true;
                        for (int j = 0; j < 8; j++) { row &= nb[i * 8 + j]; col &= nb[j * 8 + i]; }
                        for (int j = 0; j < 8; j++) { if (row) glow[i * 8 + j] = true; if (col) glow[j * 8 + i] = true; }
                    }
                }
            }
            for (int i = 0; i < 64; i++) {
                float x = BXP + (i % 8) * CS, y = BYP + (i / 8) * CS;
                fill(b, x + 2, y + 2, CS - 4, CS - 4, CELL);
                if (board[i]) {
                    if (clearing[i] > 0) block(b, x, y, CS, mix(BLOCK, GLOW, 0.6f));
                    else if (glow[i]) { block(b, x, y, CS, mix(BLOCK, GLOW, 0.5f)); }
                    else block(b, x, y, CS, BLOCK);
                }
            }
            if (ghostRc != null)
                for (int[] x : tray[drag]) fill(b, BXP + (ghostRc[1] + x[1]) * CS + 4, BYP + (ghostRc[0] + x[0]) * CS + 4, CS - 8, CS - 8, GHOST);
            // Hinweis der App (Overlay): Rahmen + Balken unter dem Fach
            if (hint != null) {
                float st = Math.max(6f, CS * 0.08f), in = st / 2 + 2;
                for (int[] x : hint) {
                    float l = BXP + (hintC + x[1]) * CS + in - st / 2, t = BYP + (hintR + x[0]) * CS + in - st / 2, w = CS - 2 * in + st;
                    fill(b, l, t, w, st, HINT[hintSlot]); fill(b, l, t + w - st, w, st, HINT[hintSlot]);
                    fill(b, l, t, st, w, HINT[hintSlot]); fill(b, l + w - st, t, st, w, HINT[hintSlot]);
                }
                fill(b, slotX(hintSlot) - CS * .7f, Vision.TY1 * H - 26, CS * 1.4f, 14, HINT[hintSlot]);
            }
            // Ablage
            for (int s = 0; s < 3; s++) {
                if (tray[s] == null || s == drag) continue;
                float cx = slotX(s), cy = SLOT_Y, sc = 1;
                if (s == ret) {
                    float a = Math.min(1, (now - retStart) / 250f);
                    cx = retX + (cx - retX) * a; cy = retY + (cy - retY) * a; sc = 1 / Vision.TS + (1 - 1 / Vision.TS) * a;
                } else if (now < flyStart[s]) continue;
                else if (now < flyStart[s] + 350) {
                    float a = (now - flyStart[s]) / 350f;
                    cx = W + 200 + (cx - W - 200) * a; sc = 0.5f + 0.5f * a;
                }
                piece(b, tray[s], cx, cy, T * sc);
            }
            if (drag >= 0) {
                float jx = 0, jy = 0;
                if (glitch && rnd.nextFloat() < 0.2f) { jx = (rnd.nextFloat() - 0.5f) * CS; jy = (rnd.nextFloat() - 0.5f) * CS; }
                piece(b, tray[drag], dispX + jx, dispY + jy, CS * scale);
            }
            // Combo-Schriftzug (z. B. "GREAT!") quer über der Ablage, gelb mit dunklem Rand
            if (now < textUntil) {
                Random tr = new Random(textSeed);
                float x = 230 + tr.nextInt(80), y = 1690 + tr.nextInt(120);
                for (int l = 0; l < 6; l++, x += 100) {
                    fill(b, x - 4, y - 4, 22, 128, new int[]{90, 40, 10}); fill(b, x, y, 14, 120, new int[]{255, 225, 80});
                    fill(b, x - 4, y - 4, 74, 22, new int[]{90, 40, 10}); fill(b, x, y, 66, 14, new int[]{255, 225, 80});
                    if (tr.nextBoolean()) { fill(b, x - 4, y + 52, 74, 22, new int[]{90, 40, 10}); fill(b, x, y + 56, 66, 14, new int[]{255, 225, 80}); }
                }
            }
            // Werbung (wechselt alle 2 s, mit laufendem Streifen)
            int[] ad = ADS[(int) (now / 2000) % ADS.length];
            fill(b, 120, 2232, 840, 132, ad);
            fill(b, 120 + (now / 7) % 700, 2250, 60, 96, mix(ad, new int[]{255, 60, 60}, 0.7f));
            return b;
        }

        static void piece(byte[] b, List<int[]> p, float cx, float cy, float cell) {
            float l = cx - cols(p) * cell / 2, t = cy - rows(p) * cell / 2;
            for (int[] x : p) fill(b, l + x[1] * cell + 4, t + x[0] * cell + 4, cell, cell, new int[]{140, 98, 40});  // Schatten
            for (int[] x : p) block(b, l + x[1] * cell, t + x[0] * cell, cell, BLOCK);
        }
    }

    static int[] mix(int[] a, int[] b, float f) {
        return new int[]{(int) (a[0] + (b[0] - a[0]) * f), (int) (a[1] + (b[1] - a[1]) * f), (int) (a[2] + (b[2] - a[2]) * f)};
    }

    /** Block mit Lichtkante oben/links und Schattenkante unten/rechts. */
    static void block(byte[] b, float x, float y, float s, int[] col) {
        float e = Math.max(2, s * 0.09f);
        int[] light = mix(col, LIGHT, 0.8f), dark = mix(col, DARK, 0.9f);
        fill(b, x + 1, y + 1, s - 2, s - 2, dark);
        fill(b, x + 1, y + 1, s - 2 - e, s - 2 - e, light);
        fill(b, x + 1 + e, y + 1 + e, s - 2 - 2 * e, s - 2 - 2 * e, col);
    }

    static void fill(byte[] b, float x, float y, float w, float h, int[] c) {
        int x0 = Math.max(0, Math.round(x)), y0 = Math.max(0, Math.round(y));
        int x1 = Math.min(W, Math.round(x + w)), y1 = Math.min(H, Math.round(y + h));
        for (int yy = y0; yy < y1; yy++) {
            int o = yy * ST + x0 * 4;
            for (int xx = x0; xx < x1; xx++, o += 4) { b[o] = (byte) c[0]; b[o + 1] = (byte) c[1]; b[o + 2] = (byte) c[2]; b[o + 3] = (byte) 255; }
        }
    }

    static void makeBackground() {
        bgBuf = new byte[ST * H];
        Random r = new Random(7);
        int[] grain = new int[H];
        for (int y = 0; y < H; y++) grain[y] = r.nextInt(13) - 6;            // Holzmaserung (Querstreifen)
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int n = grain[y] + ((x * 31 + y * 17) % 7) - 3;
            int o = y * ST + x * 4;
            bgBuf[o] = (byte) (BG[0] + n); bgBuf[o + 1] = (byte) (BG[1] + n); bgBuf[o + 2] = (byte) (BG[2] + n); bgBuf[o + 3] = (byte) 255;
        }
    }

    // ------------------------------------------------------------------ Ablauf wie in der App

    static final class Driver {
        final Game g;
        final AutoBrain brain = new AutoBrain();
        String lastKey = "", analyzedKey = null, analyzedBoard = "";
        long stableSince = 0, dragAt = 0;
        int analyzedCount = 0, tryIdx = 0;
        Solver.Move planned; int[] plannedBoard; int plannedCount;
        List<List<int[]>> lastPieces;
        boolean landingDone;
        // Statistik
        int moves, correct, misplaced, returned, fails, wrongReads, recomputes, offscreen;
        final TreeMap<String, Integer> reasons = new TreeMap<>();

        Driver(Game g) { this.g = g; }

        Vision.Frame frame() { return new Vision.Frame(g.render(), ST, W, H); }

        void run(long until) {
            while (g.now < until) { tick(); g.run(200); }
        }

        static String norm(List<int[]> p) {
            if (p == null || p.isEmpty()) return "";
            int mr = 99, mc = 99;
            for (int[] c : p) { mr = Math.min(mr, c[0]); mc = Math.min(mc, c[1]); }
            ArrayList<String> l = new ArrayList<>();
            for (int[] c : p) l.add((c[0] - mr) + "" + (c[1] - mc));
            l.sort(null);
            return String.join(",", l);
        }

        void tick() {
            Vision.Frame f = frame();
            Vision.Box[] boxes = new Vision.Box[3];
            List<List<int[]>> tray = Vision.readTray(f, boxes);
            int count = 0; for (List<int[]> t : tray) if (!t.isEmpty()) count++;
            int[] cells = Vision.readCells(f);
            StringBuilder bs = new StringBuilder(); for (int c : cells) bs.append(c);
            String board = bs.toString(), key = board + "/" + Vision.signature(tray);
            if (!key.equals(lastKey)) { lastKey = key; stableSince = g.now; }
            long stableMs = g.now - stableSince;
            if (key.equals(analyzedKey)) {
                if (dragAt > 0 && g.now - dragAt > 3000) dragFailed();
                return;
            }
            if (count == 0) {
                g.hint = null;
                if (dragAt > 0 && stableMs >= 1050) learn(cells, count);
                return;
            }
            if (count < analyzedCount && board.equals(analyzedBoard)) return;
            long need = count < analyzedCount ? 1050 : 700;
            if (stableMs < need) return;
            if (dragAt > 0) learn(cells, count);
            analyzedKey = key; analyzedBoard = board; analyzedCount = count;
            show(tray, cells);
            lastPieces = tray;
            // Stimmt die Lesung der Ablage mit dem Spiel überein?
            for (int s = 0; s < 3; s++)
                if (!tray.get(s).isEmpty() && !norm(tray.get(s)).equals(norm(g.tray[s]))) { wrongReads++; reason("Ablage falsch gelesen"); break; }
            if (planned != null) {
                plannedBoard = cells; plannedCount = count;
                g.run(250);
                doDrag();
            }
        }

        boolean fits(int[] cells, Solver.Move m) {
            for (int[] x : m.cells) {
                int r = m.r + x[0], c = m.c + x[1];
                if (r < 0 || r > 7 || c < 0 || c > 7 || cells[r * 8 + c] != Vision.EMPTY) return false;
            }
            return true;
        }

        void show(List<List<int[]>> pieces, int[] cells) {
            boolean[] b1 = new boolean[64], b2 = new boolean[64];
            for (int i = 0; i < 64; i++) { b1[i] = cells[i] == Vision.FULL; b2[i] = cells[i] != Vision.EMPTY; }
            Solver.Result res = Solver.solve(b1, pieces);
            if (res != null && !res.moves.isEmpty() && !fits(cells, res.moves.get(0))) res = Solver.solve(b2, pieces);
            Solver.Move m = res != null && !res.moves.isEmpty() ? res.moves.get(0) : null;
            planned = m != null && fits(cells, m) ? m : null;
            if (planned != null) { g.hint = planned.cells; g.hintR = planned.r; g.hintC = planned.c; g.hintSlot = planned.piece; }
            else g.hint = null;
        }

        void reason(String r) { reasons.merge(r, 1, Integer::sum); }

        // Finger-Gesten (wie AutoService: down / move / hold / up)
        void down(float x, float y, long ms) { g.setFinger(true, Math.round(x), Math.round(y)); g.run(ms); }
        void move(float x, float y, long ms) {
            float x0 = g.fx, y0 = g.fy; int n = (int) Math.max(1, ms / 16);
            for (int i = 1; i <= n; i++) { g.setFinger(true, x0 + (x - x0) * i / n, y0 + (y - y0) * i / n); g.run(ms / n); }
        }
        void up() { g.setFinger(false, g.fx, g.fy); g.run(16); }

        void doDrag() {
            Solver.Move m = planned;
            Vision.Frame f = frame();
            Vision.Box[] boxes = new Vision.Box[3];
            List<List<int[]>> now = Vision.readTray(f, boxes);
            if (!Vision.signature(Arrays.asList(now.get(m.piece))).equals(Vision.signature(Arrays.asList(lastPieces.get(m.piece))))) {
                recomputes++; analyzedKey = null; stableSince = g.now; return;
            }
            Vision.Box box = boxes[m.piece];
            Vision.Frame ref = new Vision.Frame(f.buf.clone(), ST, W, H);
            int[] pc = Vision.pieceColor(ref, box);
            int rows = 0, cols = 0;
            for (int[] x : m.cells) { rows = Math.max(rows, x[0] + 1); cols = Math.max(cols, x[1] + 1); }
            AutoBrain.Plan plan = brain.new Plan(W, H, box.cx(), box.cy(), rows, cols, m.r, m.c);
            brain.lastKey = plan.key; landingDone = false; moves++; brain.retry = tryIdx;
            g.lastDrop = false;
            float[] t = plan.fastTarget();
            if (t != null) {
                brain.lastFast = true;
                down(box.cx(), box.cy(), 150); g.run(150);
                release(m, t[0], t[1]);
                return;
            }
            brain.lastFast = false;
            down(box.cx(), box.cy(), 150);
            while (true) {
                g.run(90);
                Vision.Box raw = Vision.findFloating(frame(), ref, pc);
                int a = plan.onMeasure(raw);
                if (TRACE) System.out.printf("  t=%d Phase %d Finger %.0f,%.0f Teil(echt) %.0f,%.0f -> %s | %s%n", g.now, plan.phase, g.fx, g.fy, g.dispX, g.dispY,
                        raw == null ? "-" : String.format("%.0f,%.0f-%.0f,%.0f", raw.left, raw.top, raw.right, raw.bottom), plan.info);
                if (a == AutoBrain.Plan.MOVE) move(plan.ax, plan.ay, 200);
                else if (a == AutoBrain.Plan.RELEASE) { release(m, plan.ax, plan.ay); return; }
                else if (a == AutoBrain.Plan.FAIL) {
                    up(); fails++; reason("Pause: " + plan.msg.replaceAll("[0-9]+[.,][0-9]+", "#"));
                    dragAt = 0; analyzedKey = null; stableSince = g.now;   // Nutzer tippt "weiter"
                    g.run(1500);
                    return;
                }
            }
        }

        void release(Solver.Move m, float fx, float fy) {
            if (fy < BYP - 2 * CS || fy > H - 10) { offscreen++; reason("Finger-Ziel weit über dem Brett/außerhalb"); }
            move(fx, fy, 350); g.run(250); up();
            dragAt = g.now;
            if (g.lastDrop) {
                if (!g.lastPlaced) { returned++; reason("Teil zurückgesprungen"); }
                else if (g.lastR == m.r && g.lastC == m.c) correct++;
                else { misplaced++; reason("daneben gesetzt"); }
            }
            g.run(150); landing();
            g.run(200); landing();
        }

        void landing() {
            if (landingDone) return;
            int[] cells = Vision.readCells(frame());
            if (brain.checkLanding(plannedBoard, cells, planned.cells, planned.r, planned.c) != null) landingDone = true;
        }

        void dragFailed() {
            dragAt = 0;
            brain.notPlaced();
            tryIdx++;
            if (tryIdx >= 5) { tryIdx = 0; fails++; reason("Pause: Teil ließ sich nicht setzen"); analyzedKey = null; return; }
            g.run(400);
            doDrag();
        }

        void learn(int[] cells, int count) {
            dragAt = 0;
            if (count >= plannedCount) return;
            tryIdx = 0;
            if (!landingDone && planned != null && brain.checkLanding(plannedBoard, cells, planned.cells, planned.r, planned.c) != null) landingDone = true;
        }
    }

    // ------------------------------------------------------------------ Szenarien

    public static void main(String[] args) {
        makeBackground();
        long minutes = args.length > 0 ? Long.parseLong(args[0]) : 6;
        float[][] scen = {          // kx, ky, Versatz beim Einrasten x/y (Felder)
            {2.0f, 2.3f, 0.0f, 0.0f},
            {2.0f, 2.3f, 0.0f, 0.35f},
            {2.3f, 2.3f, 0.3f, -0.3f},
            {1.0f, 1.0f, 0.0f, 0.45f},
            {2.6f, 2.0f, -0.4f, 0.4f},
            {2.0f, 2.3f, 0.0f, 0.35f, 1},   // mit Combo-Schriftzügen über der Ablage
            {2.0f, 2.3f, 0.0f, 0.35f, 2},   // mit Rucklern des gezogenen Teils
        };
        int sum = 0, sumOk = 0;
        for (int i = 0; i < scen.length; i++) {
            float[] s = scen[i];
            Game g = new Game(1000 + i, s[0], s[1], s[2], s[3]);
            g.texts = s.length > 4 && s[4] == 1; g.glitch = s.length > 4 && s[4] == 2;
            Driver d = new Driver(g);
            d.run(minutes * 60_000);
            sum += d.moves; sumOk += d.correct;
            System.out.printf("k=%.1f/%.1f Versatz=%+.2f/%+.2f%s | Züge %d, richtig %d (%.1f %%), daneben %d, zurück %d, Pausen %d, Ablage falsch %d, neu gerechnet %d, Game Over %d, Ø %.1f s/Zug%n",
                s[0], s[1], s[2], s[3], g.texts ? " +Schrift" : g.glitch ? " +Ruckler" : "", d.moves, d.correct, 100.0 * d.correct / Math.max(1, d.moves), d.misplaced, d.returned,
                d.fails, d.wrongReads, d.recomputes, g.gameOvers, minutes * 60.0 / Math.max(1, d.moves));
            for (Map.Entry<String, Integer> e : d.reasons.entrySet()) System.out.println("    " + e.getValue() + "x " + e.getKey());
        }
        double rate = 100.0 * sumOk / Math.max(1, sum);
        System.out.printf("GESAMT: %d Züge, %.1f %% richtig%n", sum, rate);
        if (rate < 95) { System.out.println("FEHLER: Trefferquote unter 95 %"); System.exit(1); }
    }
}

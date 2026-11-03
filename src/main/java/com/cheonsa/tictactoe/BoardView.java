package com.cheonsa.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything you see: the board, symbols, ghost previews, cursors, win line, banner, buttons.
 * Board space: (0,0) is the centre of the 3x3 grid, 1 cell = 1 block, u = right, v = up.
 */
final class BoardView {

    enum Button { LEFT, RIGHT }

    // ---- layout (blocks) ----
    static final float PANEL_L = -1.9f, PANEL_R = 1.9f, PANEL_B = -2.35f, PANEL_T = 2.5f;
    static final float STATUS_V = 2.12f;
    static final float BTN_V = -1.95f, BTN_U = 0.95f, BTN_W = 1.7f, BTN_H = 0.45f; // W/H = click area
    static final float BTN_SCALE = 0.85f, BTN_HOVER_SCALE = 1.1f;

    // ---- depth: small steps towards the viewer so layers never z-fight ----
    static final float Z_PANEL = 0f, Z_GRID = 0.01f, Z_SYMBOL = 0.02f, Z_LINE = 0.03f,
            Z_BANNER = 0.04f, Z_BTN_TEXT = 0.06f, Z_CURSOR = 0.08f;

    // ---- colours (ARGB) ----
    static final int PANEL = 0xFF141821, GRID = 0xFFC8D0DC;
    static final int X_COLOR = 0xFFFF5A5F, O_COLOR = 0xFF4DABF7;
    static final int GHOST_ALPHA = 0x55;
    static final int WIN_LINE = 0xFFFFD43B;
    static final int BANNER_BG = 0xD0000000;
    // button text: normal, hovered
    static final int AGAIN = 0xFF51CF66, AGAIN_HOVER = 0xFFB2F2BB;
    static final int LEAVE = 0xFFFF6B6B, LEAVE_HOVER = 0xFFFFC9C9;
    static final int DRAW = 0xFFADB5BD, DRAW_HOVER = 0xFFFFFFFF;
    static final int COIN = 0xFFE8B923;

    // ---- coin flip ----
    static final int COIN_TICKS = 50;
    static final float COIN_TURNS = 4, COIN_HEIGHT = 0.8f;
    static final float COIN_Z = 1.0f;   // in front of the panel, so the spinning coin never clips into it
    static final float COIN_SCALE = 6f; // the 8px head glyph becomes ~1.2 blocks
    /** One face of the coin, centred on the coin's middle and a hair in front of it. */
    private static final Matrix4f COIN_FACE = new Matrix4f()
            .translate(-0.5f * Geo.TP * COIN_SCALE, -(Geo.BG_H / 2f) * Geo.TP * COIN_SCALE, 0.01f)
            .scale(COIN_SCALE, COIN_SCALE, 1f);

    private final Plugin plugin;
    private final Location origin;
    private final Vector up = new Vector(0, 1, 0);
    private final Vector right;
    private final Vector normal;
    private final List<BukkitTask> tasks = new ArrayList<>();

    private final Shape panel, grid;
    private final Label status;
    private final Shape ghostX, ghostO, cursorX, cursorO;
    private final Shape[] symbols = new Shape[9];
    private TextDisplay winLine;
    private Label banner, txtL, txtR, coinFront, coinBack;
    private final Interaction hitbox;
    private final Player viewer;
    private final Label turnText;
    private String textL, textR;
    private int colL, hoverL, colR, hoverR;
    private boolean hovL, hovR;

    /** Spawns a board 4.5 blocks in front of {@code viewer}'s eyes, facing them, visible only to them. */
    BoardView(Plugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        Location eye = viewer.getEyeLocation();
        Vector look = eye.getDirection().setY(0);
        if (look.lengthSquared() < 1e-6) look = new Vector(0, 0, 1);
        look.normalize();
        this.right = look.clone().crossProduct(up).normalize(); // viewer's right-hand side
        this.normal = look.clone().multiply(-1);                 // board faces the viewer
        this.origin = eye.clone().add(look.clone().multiply(4.5)).add(0, -0.25, 0);
        this.origin.setDirection(normal);
        this.origin.setPitch(0);

        panel = new Shape(this, List.of(Part.rect(0, (PANEL_B + PANEL_T) / 2f,
                PANEL_R - PANEL_L, PANEL_T - PANEL_B)), PANEL, Z_PANEL);
        panel.place(0, 0, 1, 0);

        float len = 2.9f, th = 0.05f;
        grid = new Shape(this, List.of(
                Part.rect(-0.5f, 0, th, len), Part.rect(0.5f, 0, th, len),
                Part.rect(0, 0.5f, len, th), Part.rect(0, -0.5f, len, th)), GRID, Z_GRID);
        grid.place(0, 0, 1, 0);

        status = new Label(this, Component.empty(), 0, Z_GRID);
        status.place(0, STATUS_V, 0.9f, 0, 0);

        ghostX = new Shape(this, xParts(), ghost(X_COLOR), Z_SYMBOL);
        ghostO = new Shape(this, oParts(), ghost(O_COLOR), Z_SYMBOL);
        cursorX = new Shape(this, cursorParts(), X_COLOR, Z_CURSOR);
        cursorO = new Shape(this, cursorParts(), O_COLOR, Z_CURSOR + 0.005f);

        // Invisible box over the panel. Spectators can't left-click air, but clicking an entity
        // makes them try to spectate it, which the plugin cancels and treats as a click.
        hitbox = own(origin.getWorld().spawn(origin.clone().add(0, PANEL_B, 0), Interaction.class, h -> {
            h.setVisibleByDefault(false);
            h.setInteractionWidth(PANEL_R - PANEL_L);
            h.setInteractionHeight(PANEL_T - PANEL_B);
            h.setResponsive(false);
            h.setPersistent(false);
        }));

        turnText = new Label(this, Component.empty(), BANNER_BG, Z_GRID);
    }

    // ------------------------------------------------------------------ shapes

    private static List<Part> xParts() {
        float r = 0.3f, th = 0.12f;
        return List.of(Part.seg(-r, -r, r, r, th), Part.seg(-r, r, r, -r, th));
    }

    /** A ring built from 8 short strokes (an octagon), since rectangles are all we have. */
    private static List<Part> oParts() {
        float r = 0.3f, th = 0.11f;
        float side = (float) (2 * r * Math.tan(Math.PI / 8)) + th * 0.42f;
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double phi = i * Math.PI / 4;
            parts.add(new Part((float) (r * Math.cos(phi)), (float) (r * Math.sin(phi)),
                    (float) (phi + Math.PI / 2), side, th));
        }
        return parts;
    }

    /** Classic arrow pointer outline. The tip, at (0,0), is the exact point the player aims at. */
    private static List<Part> cursorParts() {
        float[][] pts = {{0, 0}, {0, -0.32f}, {0.08f, -0.25f}, {0.14f, -0.37f},
                {0.19f, -0.35f}, {0.13f, -0.23f}, {0.23f, -0.23f}, {0, 0}};
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < pts.length - 1; i++) {
            parts.add(Part.seg(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], 0.035f));
        }
        return parts;
    }

    private static int ghost(int argb) {
        return (GHOST_ALPHA << 24) | (argb & 0xFFFFFF);
    }

    static int symbolColor(int sym) {
        return sym == 1 ? X_COLOR : O_COLOR;
    }

    // ------------------------------------------------------------------ input

    /** Where the player's crosshair meets the board, as {u, v}, or null if not on the board. */
    float[] hit(Player p) {
        if (!p.getWorld().equals(origin.getWorld())) return null;
        Location eye = p.getEyeLocation();
        Vector pos = eye.toVector(), dir = eye.getDirection();
        double denom = dir.dot(normal);
        if (denom > -1e-4) return null; // looking away from (or at the back of) the board
        double t = origin.toVector().subtract(pos).dot(normal) / denom;
        if (t < 0 || t > 24) return null;
        Vector local = pos.add(dir.multiply(t)).subtract(origin.toVector());
        float u = (float) local.dot(right), v = (float) local.dot(up);
        if (u < PANEL_L || u > PANEL_R || v < PANEL_B || v > PANEL_T) return null;
        return new float[]{u, v};
    }

    static int cellAt(float u, float v) {
        int col = (int) Math.floor(u + 1.5f), row = (int) Math.floor(1.5f - v);
        if (col < 0 || col > 2 || row < 0 || row > 2) return -1;
        return row * 3 + col;
    }

    static float cellU(int cell) { return -1f + cell % 3; }
    static float cellV(int cell) { return 1f - cell / 3; }

    Button buttonAt(float u, float v) {
        if (txtL == null) return null;
        if (Math.abs(v - BTN_V) > BTN_H / 2) return null;
        if (Math.abs(u + BTN_U) < BTN_W / 2) return Button.LEFT;
        if (Math.abs(u - BTN_U) < BTN_W / 2) return Button.RIGHT;
        return null;
    }

    // ------------------------------------------------------------------ drawing

    void setStatus(Component c) {
        status.text(c);
    }

    /** Text just above the board. */
    void setTurnText(Component c) {
        turnText.text(c);
        turnText.place(0, PANEL_T + 0.25f, 0.7f, 0, 0);
    }

    void hideTurnText() {
        turnText.place(0, PANEL_T + 0.25f, 0f, 0, 0);
    }

    /**
     * Tosses a coin with {@code front}'s head on one side and {@code back}'s on the other,
     * spinning end over end, landing after {@link #COIN_TICKS} with the winner facing the viewer.
     */
    void flipCoin(Player front, Player back, boolean frontWins) {
        coinFront = coinFace(front);
        coinBack = coinFace(back);
        float total = (float) (2 * Math.PI * (COIN_TURNS + (frontWins ? 0 : 0.5f)));
        int[] t = {0};
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (t[0] > COIN_TICKS) return;
            float p = t[0] / (float) COIN_TICKS;
            // Constant spin: each tick turns ~30°, small enough that the client's interpolation stays smooth.
            placeCoin(COIN_HEIGHT * 4 * p * (1 - p), total * p, t[0] == 0 ? 0 : 1);
            t[0]++;
        }, 0L, 1L));
    }

    private Label coinFace(Player p) {
        return new Label(this, Component.object(ObjectContents.playerHead(p)), COIN, Z_BANNER);
    }

    private void placeCoin(float v, float angle, int interp) {
        Matrix4f coin = new Matrix4f().translate(0, v, COIN_Z).rotateX(angle);
        coinFront.matrix(new Matrix4f(coin).mul(COIN_FACE), interp);
        // The back face is flipped about the same axis, so it reads upright once the coin has turned over.
        coinBack.matrix(new Matrix4f(coin).rotateX((float) Math.PI).mul(COIN_FACE), interp);
    }

    void moveCursor(int sym, float u, float v) {
        // 2-tick interpolation makes the pointer glide instead of jumping tick to tick.
        (sym == 1 ? cursorX : cursorO).place(u, v, 1f, 2);
    }

    void hideCursor(int sym) {
        (sym == 1 ? cursorX : cursorO).hide();
    }

    void hideCursors() {
        cursorX.hide();
        cursorO.hide();
    }

    void showGhost(int sym, int cell) {
        (sym == 1 ? ghostO : ghostX).hide();
        (sym == 1 ? ghostX : ghostO).place(cellU(cell), cellV(cell), 1f, 0);
    }

    void hideGhosts() {
        ghostX.hide();
        ghostO.hide();
    }

    /** Places a symbol with a small "pop" (scale 0 → 1). */
    void placeSymbol(int cell, int sym) {
        Shape s = new Shape(this, sym == 1 ? xParts() : oParts(), symbolColor(sym), Z_SYMBOL + 0.002f);
        float u = cellU(cell), v = cellV(cell);
        s.place(u, v, 0f, 0);
        symbols[cell] = s;
        later(2, () -> s.place(u, v, 1f, 5));
    }

    /** Draws a line through the winning cells, growing from the first cell to the last. */
    void drawWinLine(int from, int to) {
        float u1 = cellU(from), v1 = cellV(from), u2 = cellU(to), v2 = cellV(to);
        float angle = (float) Math.atan2(v2 - v1, u2 - u1);
        float ext = 0.42f;
        float sx = u1 - (float) Math.cos(angle) * ext, sy = v1 - (float) Math.sin(angle) * ext;
        float len = (float) Math.hypot(u2 - u1, v2 - v1) + ext * 2;
        float th = 0.13f;
        winLine = spawnRect(WIN_LINE, Geo.line(sx, sy, Z_LINE, angle, 0f, th));
        TextDisplay line = winLine;
        later(2, () -> {
            if (!line.isValid()) return;
            line.setInterpolationDelay(0);
            line.setInterpolationDuration(14);
            line.setTransformation(Geo.line(sx, sy, Z_LINE, angle, len, th));
        });
    }

    /** "DRAW!" spins and pops in at the centre, overshoots, settles, then keeps wobbling. */
    void showDrawBanner() {
        Component text = Component.text("DRAW!", TextColor.color(0xFFD43B), TextDecoration.BOLD);
        banner = new Label(this, text, BANNER_BG, Z_BANNER);
        Label b = banner;
        float u = 0f, v = 0.05f, s = 3.2f;
        b.place(u, v, 0f, -0.9f, 0);
        later(2, () -> b.place(u, v, s * 1.3f, 0.12f, 7));
        later(9, () -> b.place(u, v, s, 0f, 5));
        boolean[] flip = {false};
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            flip[0] = !flip[0];
            b.place(u, v, s * (flip[0] ? 1.06f : 0.97f), flip[0] ? 0.07f : -0.07f, 14);
        }, 15L, 14L));
    }

    /** Two text buttons along the bottom edge; replaces any buttons already shown. */
    void showButtons(String left, int lc, int lh, String right, int rc, int rh) {
        hideButtons();
        textL = left; colL = lc; hoverL = lh;
        textR = right; colR = rc; hoverR = rh;
        Label ta = txtL = new Label(this, btnText(left, lc), 0, Z_BTN_TEXT);
        Label tr = txtR = new Label(this, btnText(right, rc), 0, Z_BTN_TEXT);
        later(2, () -> {
            ta.place(-BTN_U, BTN_V, hovL ? BTN_HOVER_SCALE : BTN_SCALE, 0, 5);
            tr.place(BTN_U, BTN_V, hovR ? BTN_HOVER_SCALE : BTN_SCALE, 0, 5);
        });
    }

    private static Component btnText(String s, int argb) {
        return Component.text(s, TextColor.color(argb & 0xFFFFFF), TextDecoration.BOLD);
    }

    /** Hovered text grows and changes colour; only sends updates when the hover state flips. */
    void setButtonHover(boolean left, boolean right) {
        if (txtL == null) return;
        if (left != hovL) {
            hovL = left;
            txtL.text(btnText(textL, left ? hoverL : colL));
            txtL.place(-BTN_U, BTN_V, left ? BTN_HOVER_SCALE : BTN_SCALE, 0, 3);
        }
        if (right != hovR) {
            hovR = right;
            txtR.text(btnText(textR, right ? hoverR : colR));
            txtR.place(BTN_U, BTN_V, right ? BTN_HOVER_SCALE : BTN_SCALE, 0, 3);
        }
    }

    void hideButtons() {
        if (txtL != null) txtL.remove();
        if (txtR != null) txtR.remove();
        txtL = txtR = null;
        hovL = hovR = false;
    }

    /** Clears everything from the last round, keeping the board itself. */
    void clearRound() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        for (int i = 0; i < 9; i++) {
            if (symbols[i] != null) symbols[i].remove();
            symbols[i] = null;
        }
        if (winLine != null) winLine.remove();
        winLine = null;
        if (banner != null) banner.remove();
        banner = null;
        if (coinFront != null) coinFront.remove();
        if (coinBack != null) coinBack.remove();
        coinFront = coinBack = null;
        hideTurnText();
        hideButtons();
        hideGhosts();
        hideCursors();
    }

    void destroy() {
        clearRound();
        panel.remove();
        grid.remove();
        status.remove();
        ghostX.remove();
        ghostO.remove();
        cursorX.remove();
        cursorO.remove();
        turnText.remove();
        hitbox.remove();
    }

    /** Runs a task later; it's cancelled automatically when the round is cleared. */
    void later(long ticks, Runnable r) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, r, ticks));
    }

    // ------------------------------------------------------------------ entities

    /** Hidden from everyone by default (set before spawning, so no packet leaks); shown to the viewer only. */
    private <T extends Entity> T own(T e) {
        viewer.showEntity(plugin, e);
        return e;
    }

    TextDisplay spawnRect(int argb, Transformation t) {
        return own(origin.getWorld().spawn(origin, TextDisplay.class, d -> {
            base(d);
            d.text(Component.text(" "));
            d.setBackgroundColor(color(argb));
            d.setTransformation(t);
        }));
    }

    TextDisplay spawnText(Component text, int bgArgb, Transformation t) {
        return own(origin.getWorld().spawn(origin, TextDisplay.class, d -> {
            base(d);
            d.text(text);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(color(bgArgb)); // 0 = no background
            d.setTransformation(t);
        }));
    }

    private static void base(TextDisplay d) {
        d.setVisibleByDefault(false);
        d.setBillboard(Display.Billboard.FIXED);
        d.setDefaultBackground(false);
        d.setShadowed(false);
        d.setSeeThrough(false);
        d.setBrightness(new Display.Brightness(15, 15)); // full bright, day or night
        d.setLineWidth(1000);
        d.setPersistent(false);                          // never saved to the world
        d.setDisplayWidth(8f);                           // culling box covers the whole board
        d.setDisplayHeight(8f);
        d.setViewRange(1f);
    }

    static Color color(int argb) {
        return Color.fromARGB(argb >>> 24, (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }
}

package com.cheonsa.tictactoe;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
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
    // The server can't see client FOV, so everyone is assumed to play at FOV 70, and the board
    // is placed at the distance where it just fills the screen height.
    static final int FOV = 70;
    static final float FLY_FOV = 1.1f;   // spectators always fly, which widens the FOV by 10% (FOV effects slider at 100%)
    static final float FILL = 0.95f;     // fraction of the screen height the panel covers
    static final float PANEL_CY = (PANEL_T + PANEL_B) / 2; // the eye looks straight at the panel's centre
    /** Distance at which the panel fills {@link #FILL} of the screen height. */
    static final double DISTANCE = (PANEL_T - PANEL_B) / 2 / FILL / Math.tan(Math.toRadians(FOV * FLY_FOV / 2));
    static final float BTN_V = -1.95f, BTN_U = 0.95f, BTN_W = 1.7f, BTN_H = 0.45f; // W/H = click area
    static final float BTN_SCALE = 0.85f, BTN_HOVER_SCALE = 1.1f;

    // ---- depth: small steps towards the viewer so layers never z-fight ----
    static final float Z_PANEL = 0f, Z_GRID = 0.01f, Z_SYMBOL = 0.02f, Z_LINE = 0.03f,
            Z_BANNER = 0.04f, Z_BTN_TEXT = 0.06f, Z_CURSOR = 0.08f;

    // ---- colours (ARGB) ----
    static final float BACKDROP_Z = -0.05f;  // the plank wall sits just behind the panel
    static final int MAX_FOV = 110;          // the wall is big enough for this FOV...
    static final float MAX_ASPECT = 21f / 9; // ...on screens up to this wide
    static final int PANEL = 0xFF141821, GRID = 0xFFC8D0DC;
    static final int X_COLOR = 0xFFFF5A5F, O_COLOR = 0xFF4DABF7;
    static final int GHOST_ALPHA = 0x55;
    static final int O_SIDES = 24;
    static final int WIN_LINE = 0xFFFFD43B;
    static final int BANNER_BG = 0xD0000000;
    // button text: normal, hovered
    static final int AGAIN = 0xFF51CF66, AGAIN_HOVER = 0xFFB2F2BB;
    static final int LEAVE = 0xFFFF6B6B, LEAVE_HOVER = 0xFFFFC9C9;
    static final int DRAW = 0xFFADB5BD, DRAW_HOVER = 0xFFFFFFFF;
    static final int COIN = 0xFFE8B923; // the winner's card rim
    // pixel-art coin: gold edge and outer band, pale ring, black core behind the head, dark seams between edge faces
    static final int COIN_RIM = 0xFFD0B04A, COIN_RING = 0xFFFFD88A, COIN_CORE = 0xFF0E0E10, COIN_SEAM = 0xFF6B5418;
    static final int CARD = 0xFF1E2430, CARD_WIN = 0xFF6B5320;
    static final int CURSOR_EDGE = 0xFF05060A, CURSOR_SHINE = 0xFFFFFFFF;

    // ---- cursor: a pixel arrow, tip at (0,0) ----
    static final float CURSOR_PX = 0.03f;
    private static final int[] ARROW = {1, 2, 3, 4, 5, 6, 7, 8, 4, 3}; // outline width per row
    private static final int[] ARROW_FILL = {0, 0, 0, 1, 2, 3, 4, 2, 1, 0}; // colour pixels per row, right of the white column

    // ---- coin flip ----
    static final int COIN_HOLD = 8, COIN_TICKS = 32; // sits still for COIN_HOLD, lands at COIN_TICKS
    static final float COIN_TURNS = 3, COIN_START_V = -0.5f, COIN_BIG = 2f;
    static final float COIN_START_SCALE = 0.75f, COIN_SPIN_SCALE = 1.3f;
    static final float COIN_WOBBLE = 0.35f; // radians the spin axis rocks either side of vertical
    // a card per player (head + name) either side of the coin
    static final float CARD_U = 1.2f, CARD_V = 0.1f, CARD_W = 1.2f, CARD_H = 1.3f, CARD_HEAD = 3f;
    static final float CARD_RIM = 0.04f;   // gold border the winner's card gets
    static final float CARD_LEAN = 0.17f;  // winner's card shears into a parallelogram, top leaning right
    static final int CARD_GOLD_EARLY = 4;  // ticks before the coin lands that the winner's card lights up
    static final float COIN_Z = 1.0f;   // in front of the panel, so the spinning coin never clips into it
    static final float COIN_R = 0.32f, COIN_THICK = 0.06f; // ~0.64 blocks across
    static final float COIN_SCALE = 1.75f; // the 8px head glyph becomes 0.35 blocks, inside the core
    static final float COIN_PX = 0.025f;   // pixel size of the ring's and core's stepped outlines
    static final float COIN_CORE_R = 0.266f; // a pixel of black past the head's corners
    private static final double COIN_SIDE = Math.PI / 6; // the disc is a 12-gon
    private static final float COIN_APO = (float) (COIN_R * Math.cos(COIN_SIDE / 2)),
            COIN_EDGE = (float) (2 * COIN_R * Math.sin(COIN_SIDE / 2));
    private static final float COIN_RING_R = COIN_APO - 0.003f; // just inside the 12-gon, a sliver of gold band shows all round
    // nudge for the head glyph, in its own pixels (+ = right/up): the font draws it a little up and right of where Geo.text expects
    static final float COIN_HEAD_DX = -0.5f, COIN_HEAD_DY = -0.5f;
    /** The head on one face, centred on the coin's middle and a hair in front of that face. */
    private static final Matrix4f COIN_FACE = new Matrix4f()
            .translate((-0.5f + COIN_HEAD_DX) * Geo.TP * COIN_SCALE, (-(Geo.BG_H / 2f) + COIN_HEAD_DY) * Geo.TP * COIN_SCALE,
                    COIN_THICK / 2 + 0.005f)
            .scale(COIN_SCALE, COIN_SCALE, 1f);
    // Face layers step towards the viewer so they never z-fight; the head sits in front of them all.
    private static final List<Matrix4f> COIN_DISCS = coinFaces(coinGon(), COIN_THICK / 2),
            COIN_RINGS = coinFaces(pixelDisc(COIN_RING_R), COIN_THICK / 2 + 0.002f),
            COIN_CORES = coinFaces(pixelDisc(COIN_CORE_R), COIN_THICK / 2 + 0.0035f),
            COIN_RIMS = coinEdge(0, COIN_APO, COIN_EDGE * 1.05f), // slight overlap closes the corners
            COIN_SEAMS = coinEdge(0.5f, COIN_R + 0.002f, 0.008f); // a thin dark line on each corner

    private final Plugin plugin;
    private final Location origin;
    private final ItemDisplay camera; // the player spectates and rides this, see the constructor
    private final Vector eyePos, normal, right, up = new Vector(0, 1, 0);
    private final float camYaw; // the camera looks level (pitch 0) along this yaw
    private float cu, cv;            // cursor, in board space
    private boolean jumpHeld;
    private final List<BukkitTask> tasks = new ArrayList<>();

    private final Shape panel, grid;
    private final Label status;
    private final Shape ghostX, ghostO;
    private final Shape[] cursorX, cursorO; // outline, white edge, colour fill
    private final Shape[] symbols = new Shape[9];
    private TextDisplay winLine;
    private Label banner, txtL, txtR;
    private final List<Label> coin = new ArrayList<>();
    private final List<Matrix4f> coinAt = new ArrayList<>(); // each coin label's pose in coin space
    private final List<Shape> cards = new ArrayList<>();     // rim, fill; front player's card first
    private final List<Label> cardText = new ArrayList<>();  // head, name; front player's first
    private final Interaction hitbox;
    private final List<BlockDisplay> backdrop = new ArrayList<>();
    private final Player viewer;
    private final Label turnText;
    private String textL, textR;
    private int colL, hoverL, colR, hoverR;
    private boolean hovL, hovR;

    /** Spawns a board {@link #distance} blocks in front of {@code viewer}'s eyes, facing them, visible only to them. */
    BoardView(Plugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        Location eye = viewer.getEyeLocation();
        Vector look = eye.getDirection().setY(0);
        if (look.lengthSquared() < 1e-6) look = new Vector(0, 0, 1);
        look.normalize();
        double dist = DISTANCE;
        this.eyePos = eye.toVector();
        this.normal = look.clone().multiply(-1);                 // board faces the viewer
        this.right = look.clone().crossProduct(up).normalize(); // viewer's right-hand side
        this.origin = eye.clone().add(look.clone().multiply(dist)).add(0, -PANEL_CY, 0);
        this.origin.setDirection(normal);
        this.origin.setPitch(0);

        // The player spectates an invisible entity at their eyes, looking straight at the panel's
        // centre, so the view is locked. They also ride it: passengers send their head rotation
        // every tick even when the camera is elsewhere, so the mouse still steers the cursor.
        Location cam = eye.clone().setDirection(look);
        camYaw = cam.getYaw();
        // An empty item display has no model, so unlike an invisible armor stand (which spectators
        // still see as a ghost) there's nothing to render. Eye height 0, so the camera sits exactly at cam.
        camera = own(origin.getWorld().spawn(cam, ItemDisplay.class, a -> {
            a.setVisibleByDefault(false);
            a.setPersistent(false);
        }));
        viewer.setSpectatorTarget(camera); // before riding: spectating teleports, which would dismount
        if (!camera.addPassenger(viewer)) plugin.getLogger().warning("Couldn't seat " + viewer.getName() + "; mouse cursor won't move.");

        // A cherry-plank wall just behind the panel hides the world, so the board reads as a
        // full-screen menu. One block per tile, sized to fill the screen up to FOV 110 on 21:9.
        double halfH = (dist - BACKDROP_Z) * Math.tan(Math.toRadians(MAX_FOV * FLY_FOV / 2));
        int ny = (int) Math.ceil(halfH), nx = (int) Math.ceil(halfH * MAX_ASPECT);
        BlockData planks = Material.CHERRY_PLANKS.createBlockData();
        for (int i = -nx; i < nx; i++) for (int j = -ny; j < ny; j++) tile(planks, i, PANEL_CY + j);

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
        cursorX = cursor(X_COLOR, Z_CURSOR);
        cursorO = cursor(O_COLOR, Z_CURSOR + 0.005f);

        // Invisible box over the panel. Spectators can't left-click air, but clicking an entity
        // makes them try to spectate it, which the plugin cancels and treats as a click.
        hitbox = own(origin.getWorld().spawn(origin.clone().add(0, PANEL_B, 0), Interaction.class, h -> {
            h.setVisibleByDefault(false);
            h.setInteractionWidth(PANEL_R - PANEL_L);
            h.setInteractionHeight(PANEL_T - PANEL_B);
            h.setResponsive(false);
            h.setPersistent(false);
        }));

        turnText = new Label(this, Component.empty(), 0, Z_GRID); // 0 = no background
    }

    /** One 1x1 backdrop tile with its lower-left corner at (u, v), a paper-thin slab whose front face is at BACKDROP_Z. */
    private void tile(BlockData block, float u, float v) {
        backdrop.add(own(origin.getWorld().spawn(origin, BlockDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setBlock(block);
            d.setBrightness(new Display.Brightness(15, 15)); // full bright, day or night
            d.setTransformationMatrix(new Matrix4f().translate(u, v, BACKDROP_Z - 0.01f).scale(1, 1, 0.01f));
        })));
    }

    // ------------------------------------------------------------------ shapes

    private static List<Part> xParts() {
        float r = 0.3f, th = 0.12f;
        return List.of(Part.seg(-r, -r, r, r, th), Part.seg(-r, r, r, -r, th));
    }

    /** A ring built from O_SIDES short strokes, since rectangles are all we have; at 24 it reads as a circle. */
    private static List<Part> oParts() {
        float r = 0.3f, th = 0.11f;
        // Long enough that neighbouring strokes meet at their outer corners, so the rim has no notches.
        float side = (float) ((2 * r + th) * Math.tan(Math.PI / O_SIDES));
        List<Part> parts = new ArrayList<>();
        for (int i = 0; i < O_SIDES; i++) {
            double phi = i * 2 * Math.PI / O_SIDES;
            parts.add(new Part((float) (r * Math.cos(phi)), (float) (r * Math.sin(phi)),
                    (float) (phi + Math.PI / 2), side, th));
        }
        return parts;
    }

    /**
     * Pixel arrow pointer: a dark silhouette, then a white left edge and a colour fill on top.
     * The tip, at (0,0), is the exact point the player aims at.
     */
    private Shape[] cursor(int color, float z) {
        List<Part> edge = new ArrayList<>(), fill = new ArrayList<>();
        for (int r = 0; r < ARROW.length; r++) {
            edge.add(px(0, r, ARROW[r], 1));
            if (ARROW_FILL[r] > 0) fill.add(px(2, r, ARROW_FILL[r], 1));
        }
        return new Shape[]{
                new Shape(this, edge, CURSOR_EDGE, z),
                new Shape(this, List.of(px(1, 2, 1, 7)), CURSOR_SHINE, z + 0.002f),
                new Shape(this, fill, color, z + 0.002f)};
    }

    /** A w x h block of cursor pixels whose top-left pixel is (col, row), rows going down. */
    private static Part px(int col, int row, int w, int h) {
        return Part.rect(CURSOR_PX * (col + w / 2f), -CURSOR_PX * (row + h / 2f), CURSOR_PX * w, CURSOR_PX * h);
    }

    /**
     * The symbol faded into the panel, pre-blended and opaque: translucent strokes would double
     * up where they overlap (the O's joints, the X's centre) and show two shades.
     */
    private static int ghost(int argb) {
        int c = 0xFF000000;
        for (int s = 0; s <= 16; s += 8) {
            int fg = argb >> s & 0xFF, bg = PANEL >> s & 0xFF;
            c |= (bg + (fg - bg) * GHOST_ALPHA / 255) << s;
        }
        return c;
    }

    static int symbolColor(int sym) {
        return sym == 1 ? X_COLOR : O_COLOR;
    }

    // ------------------------------------------------------------------ input

    /** The cursor as last drawn, as {u, v}. Clicks use this, so they land exactly where it's shown. */
    float[] cursor() {
        return new float[]{cu, cv};
    }

    /**
     * Moves the cursor to where the player's (invisible) head direction meets the board, clamped
     * to the panel. The camera doesn't turn, but the head does, so this follows the mouse.
     * Called once per tick.
     */
    void aim() {
        // Each tick the server copies the camera's rotation onto a spectating player
        // (ServerPlayer.tick → absSnapTo), wiping the mouse rotation the client sent.
        // A match means we're reading that copy: keep the last real position.
        Location l = viewer.getLocation();
        if (Math.abs(Location.normalizeYaw(l.getYaw() - camYaw)) < 1e-3f && Math.abs(l.getPitch()) < 1e-3f) return;
        Vector dir = l.getDirection();
        double denom = dir.dot(normal);
        if (denom < -1e-4) { // facing the board; otherwise keep the last position
            double t = origin.toVector().subtract(eyePos).dot(normal) / denom;
            Vector local = eyePos.clone().add(dir.multiply(t)).subtract(origin.toVector());
            // ponytail: the head can turn past the edge, then must turn back before the cursor
            // moves again; snap the head rotation back if that feels bad.
            cu = (float) Math.clamp(local.dot(right), PANEL_L, PANEL_R);
            cv = (float) Math.clamp(local.dot(up), PANEL_B, PANEL_T);
        }
    }

    /** True on the tick Space is pressed, which counts as a click. */
    boolean jumped() {
        boolean jump = viewer.getCurrentInput().isJump();
        boolean click = jump && !jumpHeld;
        jumpHeld = jump;
        return click;
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
     * between the two players' cards. It sits small and still below them for {@link #COIN_HOLD},
     * then spins on a wobbling axis as it rises and grows, slows down and lands after
     * {@link #COIN_TICKS} with the winner facing the viewer. Just before that the winner's card
     * turns gold and leans, then slides in behind the coin while the coin grows.
     */
    void flipCoin(Component frontHead, String frontName, Component backHead, String backName, boolean frontWins) {
        grid.hide(); // the cards sit where the grid is; clearRound brings it back
        card(frontHead, frontName, -CARD_U);
        card(backHead, backName, CARD_U);
        for (Matrix4f m : COIN_DISCS) coinPart(Component.text(" "), COIN_RIM, m);
        for (Matrix4f m : COIN_RINGS) coinPart(Component.text(" "), COIN_RING, m);
        for (Matrix4f m : COIN_CORES) coinPart(Component.text(" "), COIN_CORE, m);
        for (Matrix4f m : COIN_RIMS) coinPart(Component.text(" "), COIN_RIM, m);
        for (Matrix4f m : COIN_SEAMS) coinPart(Component.text(" "), COIN_SEAM, m);
        coinPart(frontHead, 0, COIN_FACE);
        // The back head is turned about the spin axis, so it reads the right way round once the coin has turned over.
        coinPart(backHead, 0, new Matrix4f().rotateY((float) Math.PI).mul(COIN_FACE));
        float total = (float) (2 * Math.PI * (COIN_TURNS + (frontWins ? 0 : 0.5f)));
        int[] t = {0};
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (t[0] > COIN_TICKS) return;
            float p = Math.max(0, t[0] - COIN_HOLD) / (float) (COIN_TICKS - COIN_HOLD);
            float ease = 1 - (1 - p) * (1 - p);
            // Ease-out spin: fastest at the start, ~90°/tick, still under the 180° the client can interpolate.
            // The axis rocks side to side and settles upright for the landing.
            float tilt = COIN_WOBBLE * (1 - p) * (float) Math.sin(3 * Math.PI * p);
            placeCoin(COIN_START_V + (CARD_V - COIN_START_V) * ease, tilt, total * ease,
                    COIN_START_SCALE + (COIN_SPIN_SCALE - COIN_START_SCALE) * ease, t[0] == 0 ? 0 : 1);
            int winner = frontWins ? 0 : 1;
            if (t[0] == COIN_TICKS - CARD_GOLD_EARLY) goldCard(winner);
            if (t[0] == COIN_TICKS) land(winner, total);
            t[0]++;
        }, 0L, 1L));
    }

    private void card(Component head, String name, float u) {
        int i = cards.size() / 2;
        cards.add(new Shape(this, List.of(Part.rect(0, 0, CARD_W + 2 * CARD_RIM, CARD_H + 2 * CARD_RIM)), CARD, Z_GRID - 0.005f));
        cards.add(new Shape(this, List.of(Part.rect(0, 0, CARD_W, CARD_H)), CARD, Z_GRID));
        cardText.add(new Label(this, head, 0, Z_SYMBOL));
        cardText.add(new Label(this, Component.text(name, NamedTextColor.WHITE), 0, Z_SYMBOL));
        poseCard(i, u, 0, 1, 1, 0);
    }

    /** Card {@code i} centred at (u, CARD_V), sheared by {@code lean}, scaled by k; its head and name by k * textK. */
    private void poseCard(int i, float u, float lean, float k, float textK, int interp) {
        Matrix4f m = new Matrix4f().translate(u, CARD_V, 0).mul(new Matrix4f().m10(lean)).scale(k, k, 1);
        cards.get(2 * i).matrix(m, interp);
        cards.get(2 * i + 1).matrix(m, interp);
        cardText.get(2 * i).matrix(new Matrix4f(m).mul(Geo.mat(Geo.text(0, 0.15f, Z_SYMBOL, CARD_HEAD * textK, 0))), interp);
        cardText.get(2 * i + 1).matrix(new Matrix4f(m).mul(Geo.mat(Geo.text(0, -0.4f, Z_SYMBOL, 0.45f * textK, 0))), interp);
    }

    private static float cardU(int i) {
        return i == 0 ? -CARD_U : CARD_U;
    }

    /** Just before the coin lands: the winner's card gets a gold rim and fill and leans over. */
    private void goldCard(int winner) {
        cards.get(2 * winner).color(COIN);
        cards.get(2 * winner + 1).color(CARD_WIN);
        poseCard(winner, cardU(winner), CARD_LEAN, 1, 1, CARD_GOLD_EARLY);
    }

    /** The winner's card slides in behind the coin, its head shrinking; the loser's slides inward and fades. */
    private void land(int winner, float angle) {
        poseCard(winner, 0, CARD_LEAN, 1.2f, 0, 8);
        poseCard(1 - winner, cardU(1 - winner) * 0.6f, 0, 0.001f, 0, 8);
        placeCoin(CARD_V, 0, angle, COIN_BIG, 8);
    }

    private void coinPart(Component c, int bg, Matrix4f at) {
        coin.add(new Label(this, c, bg, 0));
        coinAt.add(at);
    }

    private void placeCoin(float v, float tilt, float angle, float scale, int interp) {
        Matrix4f m = new Matrix4f().translate(0, v, COIN_Z).rotateZ(tilt).rotateY(angle).scale(scale);
        for (int i = 0; i < coin.size(); i++) coin.get(i).matrix(new Matrix4f(m).mul(coinAt.get(i)), interp);
    }

    /** Both faces of one layer: the rects {@code z} in front of the coin's middle, and mirrored out the back. */
    private static List<Matrix4f> coinFaces(List<Part> parts, float z) {
        List<Matrix4f> l = new ArrayList<>();
        for (Part p : parts) {
            Matrix4f r = rect(p);
            l.add(new Matrix4f().translate(0, 0, z).mul(r));
            l.add(new Matrix4f().translate(0, 0, -z).rotateY((float) Math.PI).mul(r)); // faces out the back
        }
        return l;
    }

    /** A 12-gon is the union of 6 rects, each spanning a pair of opposite sides. */
    private static List<Part> coinGon() {
        List<Part> l = new ArrayList<>();
        for (int k = 0; k < 6; k++) l.add(new Part(0, 0, (float) (k * COIN_SIDE), 2 * COIN_APO, COIN_EDGE));
        return l;
    }

    /**
     * A pixel-art disc on a {@link #COIN_PX} grid, every corner inside radius {@code r}: the union of
     * centred rects, one per step of its stair-stepped outline.
     */
    private static List<Part> pixelDisc(float r) {
        float q = r / COIN_PX;
        int n = (int) q;
        List<Part> l = new ArrayList<>();
        for (int h = 1; h <= n; h++) { // h = half-height in pixels, w = the half-width that row allows
            int w = (int) Math.sqrt(q * q - h * h);
            int next = h < n ? (int) Math.sqrt(q * q - (h + 1) * (h + 1)) : -1;
            if (w > 0 && next < w) l.add(Part.rect(0, 0, 2 * w * COIN_PX, 2 * h * COIN_PX));
        }
        return l;
    }

    /** Upright rects round the edge, facing outwards: {@code w} wide at radius {@code r}, {@code phase} sides round from the first side. */
    private static List<Matrix4f> coinEdge(float phase, float r, float w) {
        Matrix4f rect = rect(Part.rect(0, 0, COIN_THICK, w));
        List<Matrix4f> l = new ArrayList<>();
        for (int k = 0; k < 12; k++) {
            l.add(new Matrix4f().rotateZ((float) ((k + phase) * COIN_SIDE)).translate(r, 0, 0)
                    .rotateY((float) Math.PI / 2).mul(rect));
        }
        return l;
    }

    private static Matrix4f rect(Part p) {
        return Geo.mat(p.at(0, 0, 0, 1));
    }

    void moveCursor(int sym, float u, float v) {
        // 2-tick interpolation makes the pointer glide instead of jumping tick to tick.
        for (Shape s : sym == 1 ? cursorX : cursorO) s.place(u, v, 1f, 2);
    }

    void hideCursors() {
        for (Shape s : cursorX) s.hide();
        for (Shape s : cursorO) s.hide();
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
        int[] tick = {0};
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> { // pulse, one cycle per 40 ticks
            if (!line.isValid()) return;
            float t = 0.5f - 0.5f * (float) Math.cos(++tick[0] * Math.PI / 20);
            line.setBackgroundColor(color(0xFF000000 | TextColor.lerp(t, SHINE_HI, SHINE_LO).value()));
        }, 1L, 1L));
    }

    private static final TextColor SHINE_HI = TextColor.color(0xFFF3BF), SHINE_LO = TextColor.color(0xFF922B);

    /** "DRAW!" spins and pops in at the centre, overshoots, settles, then keeps wobbling. */
    void showDrawBanner() {
        banner = new Label(this, shimmer(0f), 0, Z_BANNER); // 0 = no background
        banner.shadow();
        Label b = banner;
        int[] tick = {0};
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin,
                () -> b.text(shimmer(++tick[0] / 40f)), 1L, 1L)); // one sweep per 40 ticks
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

    /** "DRAW!" in a gold/orange gradient that loops along the letters as phase goes 0 -> 1. */
    private static Component shimmer(float phase) {
        String s = "DRAW!";
        Component c = Component.empty().decorate(TextDecoration.BOLD);
        for (int i = 0; i < s.length(); i++) {
            float t = (i / (float) s.length() - phase) % 1f;
            if (t < 0) t += 1f;
            t = Math.abs(2f * t - 1f); // triangle wave, so the loop has no seam
            c = c.append(Component.text(s.charAt(i), TextColor.lerp(t, SHINE_HI, SHINE_LO)));
        }
        return c;
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
        coin.forEach(Label::remove);
        coin.clear();
        coinAt.clear();
        cards.forEach(Shape::remove);
        cards.clear();
        cardText.forEach(Label::remove);
        cardText.clear();
        grid.place(0, 0, 1, 4);
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
        for (Shape s : cursorX) s.remove();
        for (Shape s : cursorO) s.remove();
        turnText.remove();
        hitbox.remove();
        backdrop.forEach(BlockDisplay::remove);
        camera.remove();
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

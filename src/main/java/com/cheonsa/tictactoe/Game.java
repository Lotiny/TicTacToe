package com.cheonsa.tictactoe;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** One match between two players, or a player (X) and the bot (O). Symbol 1 = X, 2 = O. */
final class Game {

    enum State { PLAYING, ANIMATING, ENDED }

    private static final int[][] LINES = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8},
            {0, 3, 6}, {1, 4, 7}, {2, 5, 8},
            {0, 4, 8}, {2, 4, 6}};

    private static final int TURN_TICKS = 15 * 20;
    static final String BOT_NAME = "Bot";
    // Vanilla's built-in Steve skin, so the bot's head needs no lookup.
    private static final PlayerHeadObjectContents BOT_HEAD =
            ObjectContents.playerHead().texture(Key.key("entity/player/wide/steve")).build();
    private static final int BOT_THINK = 20;          // ticks the bot "thinks" before moving
    private static final double BOT_MISTAKE = 0.25;   // chance of a random move; 0 = unbeatable
    private static final NamespacedKey REACH = NamespacedKey.fromString("tictactoe:reach");
    private static final NamespacedKey SAVED_MODE = NamespacedKey.fromString("tictactoe:saved_mode");
    private static final NamespacedKey SAVED_FLY = NamespacedKey.fromString("tictactoe:saved_fly");
    private static final NamespacedKey SAVED_LOC = NamespacedKey.fromString("tictactoe:saved_loc");

    private final TicTacToePlugin plugin;
    final Player x, o;
    private final BoardView[] views = new BoardView[2]; // one per player: [0] X's, [1] O's
    private final int[] board = new int[9];
    private final Set<UUID> again = new HashSet<>();
    private final Set<UUID> drawOffers = new HashSet<>();
    private final Set<UUID> resignArmed = new HashSet<>();
    private int turn;
    private int turnLeft; // ticks until a random move is made for the current player
    private int starter = 1;
    private State state;
    private Component result = Component.empty();
    private boolean over;

    /** {@code o == null} plays against the bot. */
    Game(TicTacToePlugin plugin, Player x, Player o) {
        this.plugin = plugin;
        this.x = x;
        this.o = o;
        freeze(x, true, 16);
        views[0] = new BoardView(plugin, x); // after the teleport: boards spawn at the player's eyes
        if (o != null) {
            freeze(o, true, 48);
            // Name tags render through walls, so hide the players from each other outright.
            x.hidePlayer(plugin, o);
            o.hidePlayer(plugin, x);
            views[1] = new BoardView(plugin, o);
        }
        flipCoin();
    }

    private boolean bot() { return o == null; }

    /** Null for the bot. */
    Player player(int sym) { return sym == 1 ? x : o; }

    private String name(int sym) { return sym == 2 && bot() ? BOT_NAME : player(sym).getName(); }

    private Component head(int sym) {
        return Component.object(sym == 2 && bot() ? BOT_HEAD : ObjectContents.playerHead(player(sym)));
    }

    private void actionBar(int sym, Component c) {
        Player p = player(sym);
        if (p != null) p.sendActionBar(c);
    }

    int symbolOf(Player p) { return p.getUniqueId().equals(x.getUniqueId()) ? 1 : 2; }

    /** The board in front of {@code p}, the only one they can see and click. */
    BoardView view(Player p) { return views[symbolOf(p) - 1]; }

    /** Both boards mirror the same game, so every visual change goes to both (just one vs the bot). */
    private void all(Consumer<BoardView> f) {
        f.accept(views[0]);
        if (views[1] != null) f.accept(views[1]);
    }

    /** Game timers live on X's board; they're cancelled with it on clearRound/destroy. */
    private void later(long ticks, Runnable r) { views[0].later(ticks, r); }

    // ------------------------------------------------------------------ flow

    /** Opening coin toss: whoever's head lands face up moves first. Later rounds alternate. */
    private void flipCoin() {
        int first = ThreadLocalRandom.current().nextInt(2) + 1;
        state = State.ANIMATING;
        result = Component.text("Flipping a coin...", NamedTextColor.GOLD);
        updateStatus();
        all(v -> v.flipCoin(head(1), name(1), head(2), name(2), first == 1));
        both(Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f);
        later(BoardView.COIN_TICKS, () -> {
            result = symbol(first).append(Component.text(" " + name(first) + " goes first!", NamedTextColor.WHITE));
            updateStatus();
            both(Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f);
        });
        later(BoardView.COIN_TICKS + 30, () -> {
            starter = first;
            startRound();
        });
    }

    private void startRound() {
        Arrays.fill(board, 0);
        again.clear();
        drawOffers.clear();
        resignArmed.clear();
        all(BoardView::clearRound);
        all(v -> v.showButtons("OFFER DRAW", BoardView.DRAW, BoardView.DRAW_HOVER,
                "RESIGN", BoardView.LEAVE, BoardView.LEAVE_HOVER));
        turn = starter;
        starter = 3 - starter; // the other player starts next round
        state = State.PLAYING;
        newTurn();
        both(Sound.BLOCK_NOTE_BLOCK_CHIME, 1.2f);
    }

    /** Called every tick: moves both cursors, shows the ghost preview and button hover. */
    void tick() {
        if (!x.isOnline() || (o != null && !o.isOnline())) {
            end("Your opponent left.");
            return;
        }
        if (state == State.PLAYING) {
            if (bot() && turn == 2 && TURN_TICKS - --turnLeft >= BOT_THINK) place(botMove());
            else if (--turnLeft <= 0) timeUp();
            else if (turnLeft % 20 == 0) updateTurnText();
        }
        if (state == State.ANIMATING) return;
        // Each player aims at their own board and sees only their own cursor, ghost and hover there.
        boolean ghost = false;
        List<Player> jumped = new ArrayList<>();
        for (int s = 1; s <= 2; s++) {
            final int sym = s;
            BoardView mine = views[sym - 1];
            if (mine == null) continue; // the bot has no board
            if (mine.jumped()) jumped.add(player(sym));
            mine.aim();
            float[] h = mine.cursor();
            mine.moveCursor(sym, h[0], h[1]);
            BoardView.Button b = mine.buttonAt(h[0], h[1]);
            mine.setButtonHover(b == BoardView.Button.LEFT, b == BoardView.Button.RIGHT);
            if (state == State.PLAYING && sym == turn) {
                int cell = BoardView.cellAt(h[0], h[1]);
                if (cell >= 0 && board[cell] == 0) {
                    mine.showGhost(sym, cell);
                    ghost = true;
                }
            }
        }
        if (!ghost) all(BoardView::hideGhosts);
        // Clicks run after the loop: one can end the game and destroy the boards.
        for (Player p : jumped) if (!over) click(p);
    }

    void click(Player p) {
        BoardView view = view(p);
        float[] h = view.cursor();
        if (state == State.PLAYING) {
            BoardView.Button btn = view.buttonAt(h[0], h[1]);
            if (btn == BoardView.Button.LEFT) {
                offerDraw(p);
                return;
            }
            if (btn == BoardView.Button.RIGHT) {
                resign(p);
                return;
            }
            if (symbolOf(p) != turn) return; // their label already says "OPPONENT'S TURN"
            int cell = BoardView.cellAt(h[0], h[1]);
            if (cell < 0 || board[cell] != 0) return;
            place(cell);
        } else if (state == State.ENDED) {
            BoardView.Button b = view.buttonAt(h[0], h[1]);
            if (b == BoardView.Button.RIGHT) {
                end(p.getName() + " left the game.");
            } else if (b == BoardView.Button.LEFT && again.add(p.getUniqueId())) {
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                if (bot() || again.size() == 2) { // the bot is always up for a rematch
                    startRound();
                } else {
                    updateStatus();
                    actionBar(3 - symbolOf(p), Component.text(p.getName() + " wants a rematch!", NamedTextColor.GREEN));
                }
            }
        }
    }

    /** A draw needs both players to press OFFER DRAW; any move cancels a pending offer. */
    private void offerDraw(Player p) {
        if (bot()) {
            p.sendActionBar(Component.text(BOT_NAME + " declines the draw.", NamedTextColor.YELLOW));
            return;
        }
        if (!drawOffers.add(p.getUniqueId())) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
        if (drawOffers.size() == 2) {
            finishDraw(Component.text("Draw agreed!", NamedTextColor.YELLOW));
            return;
        }
        updateStatus();
        actionBar(3 - symbolOf(p), Component.text(
                p.getName() + " offers a draw. Press OFFER DRAW to accept.", NamedTextColor.YELLOW));
    }

    /** First press arms RESIGN for 3 seconds; a second press within that window resigns. */
    private void resign(Player p) {
        UUID id = p.getUniqueId();
        if (resignArmed.add(id)) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 0.6f);
            p.sendActionBar(Component.text("Press RESIGN again to confirm", NamedTextColor.RED));
            later(60, () -> resignArmed.remove(id));
            return;
        }
        int winner = 3 - symbolOf(p);
        finish(symbol(winner).append(Component.text(" " + name(winner) + " wins! ("
                + p.getName() + " resigned)", NamedTextColor.WHITE)));
        both(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
        // No PLAY AGAIN / LEAVE after a resign: show the result for 2s, then send both players back.
        later(40, () -> end(p.getName() + " resigned. " + name(winner) + " wins!"));
    }

    /** Freezes the round and shows {@code result} until the end buttons appear. */
    private void finish(Component result) {
        state = State.ANIMATING;
        this.result = result;
        all(BoardView::hideCursors);
        all(BoardView::hideGhosts);
        all(BoardView::hideButtons);
        all(BoardView::hideTurnText);
        updateStatus();
    }

    private void finishDraw(Component result) {
        finish(result);
        later(6, () -> {
            all(BoardView::showDrawBanner);
            both(Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f);
        });
        later(26, this::showEnd);
    }

    private void place(int cell) {
        board[cell] = turn;
        drawOffers.clear();
        all(BoardView::hideGhosts);
        all(v -> v.placeSymbol(cell, turn));
        both(Sound.BLOCK_NOTE_BLOCK_HAT, turn == 1 ? 1.4f : 1.0f);

        int[] line = findWin();
        if (line != null) {
            int winner = turn;
            finish(symbol(winner).append(Component.text(" " + name(winner) + " wins!", NamedTextColor.WHITE)));
            later(6, () -> {
                all(v -> v.drawWinLine(line[0], line[2]));
                both(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f);
            });
            later(30, this::showEnd);
        } else if (isFull()) {
            finishDraw(Component.text("It's a draw!", NamedTextColor.YELLOW));
        } else {
            turn = 3 - turn;
            newTurn();
        }
    }

    private void newTurn() {
        turnLeft = TURN_TICKS;
        updateTurnText();
        updateStatus();
    }

    /** Out of time: play a random empty square for the current player. */
    private void timeUp() {
        List<Integer> empty = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (board[i] == 0) empty.add(i);
        actionBar(turn, Component.text("Time's up! A random move was made.", NamedTextColor.RED));
        place(empty.get(ThreadLocalRandom.current().nextInt(empty.size())));
    }

    /** Perfect play (minimax), except it blunders into a random square BOT_MISTAKE of the time so it can be beaten. */
    private int botMove() {
        List<Integer> empty = new ArrayList<>();
        for (int i = 0; i < 9; i++) if (board[i] == 0) empty.add(i);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() < BOT_MISTAKE) return empty.get(r.nextInt(empty.size()));
        int best = empty.get(0), bestScore = Integer.MIN_VALUE;
        for (int c : empty) {
            board[c] = turn;
            int s = -negamax(3 - turn);
            board[c] = 0;
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return best;
    }

    /** Score for {@code sym} to move: +10-ish if they win (sooner is better), -10-ish if they lose, 0 for a draw. */
    private int negamax(int sym) {
        if (findWin() != null) return -10; // the previous move (by the other side) just won
        if (isFull()) return 0;
        int best = Integer.MIN_VALUE;
        for (int c = 0; c < 9; c++) {
            if (board[c] != 0) continue;
            board[c] = sym;
            best = Math.max(best, -negamax(3 - sym));
            board[c] = 0;
        }
        return best - Integer.signum(best); // a win later is worth less than a win now
    }

    private void updateTurnText() {
        int secs = (turnLeft + 19) / 20;
        for (int sym = 1; sym <= 2; sym++) {
            boolean mine = sym == turn;
            if (views[sym - 1] != null) views[sym - 1].setTurnText(Component.text(mine ? "YOUR TURN" : "OPPONENT'S TURN",
                            mine ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD)
                    .append(Component.text("  " + secs + "s", secs <= 5 ? NamedTextColor.RED : NamedTextColor.WHITE)));
        }
    }

    private void showEnd() {
        state = State.ENDED;
        all(v -> v.showButtons("PLAY AGAIN", BoardView.AGAIN, BoardView.AGAIN_HOVER,
                "LEAVE", BoardView.LEAVE, BoardView.LEAVE_HOVER));
        updateStatus();
    }

    void end(String reason) {
        if (over) return;
        over = true;
        plugin.remove(this); // first, so the plugin stops pinning the camera to the board
        all(BoardView::destroy); // before freeze(false): removing the camera seat dismounts, so the teleport back works
        freeze(x, false, 0);
        Component msg = Component.text("[Tic-Tac-Toe] ", NamedTextColor.GOLD).append(Component.text(reason, NamedTextColor.WHITE));
        if (x.isOnline()) x.sendMessage(msg);
        if (o == null) return;
        freeze(o, false, 0);
        x.showPlayer(plugin, o);
        o.showPlayer(plugin, x);
        if (o.isOnline()) o.sendMessage(msg);
    }

    // ------------------------------------------------------------------ helpers

    private int[] findWin() {
        for (int[] l : LINES) {
            if (board[l[0]] != 0 && board[l[0]] == board[l[1]] && board[l[1]] == board[l[2]]) return l;
        }
        return null;
    }

    private boolean isFull() {
        for (int c : board) if (c == 0) return false;
        return true;
    }

    private void updateStatus() {
        Component c = switch (state) {
            case PLAYING -> symbol(turn).append(Component.text(" " + name(turn) + "'s turn", NamedTextColor.WHITE))
                    .append(drawOffers.isEmpty() ? Component.empty()
                            : Component.text("   (draw offered)", NamedTextColor.YELLOW));
            case ANIMATING -> result;
            case ENDED -> result.append(Component.text("   Again? ", NamedTextColor.GRAY))
                    .append(vote(1)).append(Component.text(" ")).append(vote(2));
        };
        all(v -> v.setStatus(c));
        if (state == State.PLAYING) {
            actionBar(turn, Component.text("Your turn: move the mouse, click or press Space", NamedTextColor.GREEN));
        }
    }

    private static Component symbol(int sym) {
        return Component.text(sym == 1 ? "X" : "O",
                TextColor.color(BoardView.symbolColor(sym) & 0xFFFFFF), TextDecoration.BOLD);
    }

    private Component vote(int sym) {
        boolean yes = player(sym) == null || again.contains(player(sym).getUniqueId());
        return symbol(sym).append(Component.text(yes ? "✔" : "…", yes ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
    }

    /**
     * Spectator mode with zero fly speed: the player can only look around, and spectators
     * see no hand, hotbar, XP bar or hearts. Clicks arrive as "spectate entity" attempts on the
     * board's hitbox (see BoardView), so entity reach is raised to cover the board.
     * The player is lifted {@code lift} blocks above the build limit, into empty sky, so no
     * terrain pokes through the board's backdrop. The old game mode and location are saved on
     * the player so a crash mid-game is undone on rejoin.
     */
    private static void freeze(Player p, boolean on, int lift) {
        AttributeInstance reach = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (reach != null) {
            reach.removeModifier(REACH);
            if (on) reach.addTransientModifier(new AttributeModifier(REACH, 24, AttributeModifier.Operation.ADD_NUMBER));
        }
        if (!on) {
            restore(p);
            return;
        }
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(SAVED_MODE)) { // never overwrite a save that hasn't been restored yet
            pdc.set(SAVED_MODE, PersistentDataType.STRING, p.getGameMode().name());
            pdc.set(SAVED_FLY, PersistentDataType.FLOAT, p.getFlySpeed());
            Location l = p.getLocation();
            pdc.set(SAVED_LOC, PersistentDataType.STRING, l.getWorld().getUID() + " " + l.getX() + " " + l.getY()
                    + " " + l.getZ() + " " + l.getYaw() + " " + l.getPitch());
        }
        p.setGameMode(GameMode.SPECTATOR);
        p.setFlySpeed(0f);
        Location sky = p.getLocation();
        sky.setY(sky.getWorld().getMaxHeight() + lift);
        sky.setPitch(0);
        p.teleport(sky);
    }

    /** Puts back the game mode and fly speed saved by {@link #freeze}; no-op if nothing is saved. */
    static void restore(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String mode = pdc.get(SAVED_MODE, PersistentDataType.STRING);
        if (mode == null) return;
        Float fly = pdc.get(SAVED_FLY, PersistentDataType.FLOAT);
        p.setGameMode(GameMode.valueOf(mode));
        p.setFlySpeed(fly == null ? 0.1f : fly);
        String loc = pdc.get(SAVED_LOC, PersistentDataType.STRING);
        if (loc != null) {
            String[] s = loc.split(" ");
            World w = Bukkit.getWorld(UUID.fromString(s[0]));
            if (w != null) p.teleport(new Location(w, Double.parseDouble(s[1]), Double.parseDouble(s[2]),
                    Double.parseDouble(s[3]), Float.parseFloat(s[4]), Float.parseFloat(s[5])));
        }
        pdc.remove(SAVED_MODE);
        pdc.remove(SAVED_FLY);
        pdc.remove(SAVED_LOC);
    }

    private void both(Sound sound, float pitch) {
        for (Player p : new Player[]{x, o}) {
            if (p != null && p.isOnline()) p.playSound(p.getLocation(), sound, 1f, pitch);
        }
    }
}

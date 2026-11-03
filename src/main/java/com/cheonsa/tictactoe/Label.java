package com.cheonsa.tictactoe;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

/** A single text display showing real (crisp) text, optionally with a background. */
final class Label {
    private final TextDisplay d;
    private final float z;

    Label(BoardView view, Component text, int bgArgb, float z) {
        this.z = z;
        this.d = view.spawnText(text, bgArgb, Geo.text(0, 0, z, 0, 0));
    }

    void place(float cx, float cy, float scale, float angle, int interp) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformation(Geo.text(cx, cy, z, scale, angle));
    }

    /** Full 3D placement, for things that leave the board plane (the coin). */
    void matrix(Matrix4f m, int interp) {
        if (!d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(interp);
        d.setTransformationMatrix(m);
    }

    void text(Component c) {
        if (d.isValid()) d.text(c);
    }

    void remove() {
        d.remove();
    }
}

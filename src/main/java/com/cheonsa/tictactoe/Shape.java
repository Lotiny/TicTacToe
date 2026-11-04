package com.cheonsa.tictactoe;

import org.bukkit.Color;
import org.bukkit.entity.TextDisplay;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** A group of rectangles (one text display each) that moves, scales and recolours together. */
final class Shape {
    private final List<Part> parts;
    private final List<TextDisplay> ents = new ArrayList<>();
    private final float z;
    private float px = Float.NaN, py = Float.NaN, k = Float.NaN;
    private int argb;

    Shape(BoardView view, List<Part> parts, int argb, float z) {
        this.parts = parts;
        this.z = z;
        this.argb = argb;
        for (Part p : parts) ents.add(view.spawnRect(argb, p.at(0, 0, z, 0)));
    }

    /**
     * Move the shape to (px, py) at scale k. The client animates the change smoothly
     * over {@code interp} ticks (0 = instant).
     */
    void place(float px, float py, float k, int interp) {
        if (px == this.px && py == this.py && k == this.k) return; // nothing changed → no packets
        this.px = px;
        this.py = py;
        this.k = k;
        for (int i = 0; i < ents.size(); i++) {
            TextDisplay d = ents.get(i);
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(interp);
            d.setTransformation(parts.get(i).at(px, py, z, k));
        }
    }

    /** Full matrix placement (e.g. sheared); {@code m} maps the shape's own coordinates to the board. */
    void matrix(Matrix4f m, int interp) {
        px = py = k = Float.NaN; // the next place() must not be skipped
        for (int i = 0; i < ents.size(); i++) {
            TextDisplay d = ents.get(i);
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(interp);
            d.setTransformationMatrix(new Matrix4f(m).mul(Geo.mat(parts.get(i).at(0, 0, z, 1))));
        }
    }

    void hide() {
        place(Float.isNaN(px) ? 0 : px, Float.isNaN(py) ? 0 : py, 0f, 0);
    }

    void color(int argb) {
        if (argb == this.argb) return;
        this.argb = argb;
        Color c = BoardView.color(argb);
        for (TextDisplay d : ents) if (d.isValid()) d.setBackgroundColor(c);
    }

    void remove() {
        for (TextDisplay d : ents) d.remove();
        ents.clear();
    }
}

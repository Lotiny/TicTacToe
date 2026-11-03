package com.cheonsa.tictactoe;

import org.bukkit.util.Transformation;

/** One rectangle of a shape, in the shape's own coordinates (centre, angle, length, thickness). */
record Part(float cx, float cy, float angle, float len, float th) {

    static Part rect(float cx, float cy, float w, float h) {
        return new Part(cx, cy, 0f, w, h);
    }

    /** A stroke between two points, extended by half its thickness at both ends so joints close. */
    static Part seg(float x1, float y1, float x2, float y2, float th) {
        float dx = x2 - x1, dy = y2 - y1;
        return new Part((x1 + x2) / 2f, (y1 + y2) / 2f,
                (float) Math.atan2(dy, dx), (float) Math.hypot(dx, dy) + th, th);
    }

    /** Transformation for this part when its shape sits at (px, py), scaled by k (0 = hidden). */
    Transformation at(float px, float py, float z, float k) {
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        float sx = px + k * (cx - c * len / 2f);
        float sy = py + k * (cy - s * len / 2f);
        return Geo.line(sx, sy, z, angle, k * len, k * th);
    }
}

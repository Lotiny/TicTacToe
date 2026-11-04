package com.cheonsa.tictactoe;

import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Maths for drawing with text displays.
 *
 * Every shape on the board is a text display showing a single space " ", so only its
 * background rectangle is visible. In vanilla that background is BG_W x BG_H "text
 * pixels" (1 text pixel = 0.025 blocks), starting BG_LEFT pixels left of the entity and
 * going up from it. With scale, rotation and translation we turn it into any rectangle.
 *
 * All coordinates are in board space: u = right, v = up, z = towards the viewer (blocks).
 *
 * If shapes look slightly off on your Minecraft version, these constants are the only
 * thing to adjust.
 */
final class Geo {
    static final float TP = 0.025f;
    static final float BG_W = 5f;
    static final float BG_H = 10f;
    static final float BG_LEFT = 2f;
    static final float EPS = 0.0001f;

    private Geo() {}

    /**
     * A rectangle that starts at (sx, sy), runs along {@code angle} for {@code len} blocks,
     * and is {@code th} thick, centred on that axis. Translation and scale are both linear
     * in len, so interpolating len makes the line "draw" itself from its start point.
     */
    static Transformation line(float sx, float sy, float z, float angle, float len, float th) {
        len = Math.max(len, EPS);
        th = Math.max(th, EPS);
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        float ox = len * BG_LEFT / BG_W, oy = -th / 2f;
        return new Transformation(
                new Vector3f(sx + ox * c - oy * s, sy + ox * s + oy * c, z),
                new AxisAngle4f(angle, 0, 0, 1),
                new Vector3f(len / (BG_W * TP), th / (BG_H * TP), 1f),
                new AxisAngle4f());
    }

    static Matrix4f mat(Transformation t) {
        return new Matrix4f().translate(t.getTranslation()).rotate(t.getLeftRotation()).scale(t.getScale());
    }

    /** A text label centred on (cx, cy), scaled and rotated around its centre. */
    static Transformation text(float cx, float cy, float z, float scale, float angle) {
        scale = Math.max(scale, EPS);
        float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
        // The middle of one centred line sits about (+0.5, BG_H/2) text pixels from the anchor.
        float ox = -0.5f * TP * scale, oy = -(BG_H / 2f) * TP * scale;
        return new Transformation(
                new Vector3f(cx + ox * c - oy * s, cy + ox * s + oy * c, z),
                new AxisAngle4f(angle, 0, 0, 1),
                new Vector3f(scale, scale, 1f),
                new AxisAngle4f());
    }
}

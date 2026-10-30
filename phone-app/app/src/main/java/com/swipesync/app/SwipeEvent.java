package com.swipesync.app;

/** 手表端滑动事件 */
public class SwipeEvent {
    public static final String UP = "up";
    public static final String DOWN = "down";
    public static final String LEFT = "left";
    public static final String RIGHT = "right";

    public final String dir;   // up / down / left / right
    public final boolean continuous; // true=连续滑动模式
    public final long seq;

    public SwipeEvent(String dir, boolean continuous, long seq) {
        this.dir = dir;
        this.continuous = continuous;
        this.seq = seq;
    }

    public static boolean isValidDir(String d) {
        return UP.equals(d) || DOWN.equals(d) || LEFT.equals(d) || RIGHT.equals(d);
    }

    @Override
    public String toString() {
        return "SwipeEvent{" + dir + (continuous ? " cont" : " disc") + " #" + seq + "}";
    }
}

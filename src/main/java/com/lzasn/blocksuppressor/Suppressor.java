package com.lzasn.blocksuppressor;

import java.util.UUID;

/**
 * 一个抑制器 = 一个被铁砧重命名过的容器（箱子、木桶、熔炉……只要是容器方块）。
 *
 * <p>容器本身是"身份"，接在旁边的比较器决定"抑制哪里"：
 * 比较器指向的方块成为抑制域中心。没接比较器时它是"待机"状态，
 * 仍然算抑制器、仍然顶着悬浮标签，但不会抑制任何方块。</p>
 */
public final class Suppressor {

    private final UUID worldId;
    private final String worldName;

    private final int x;
    private final int y;
    private final int z;

    private volatile int radius;

    private volatile boolean armed;
    private volatile int centerX;
    private volatile int centerY;
    private volatile int centerZ;
    private volatile int comparatorX;
    private volatile int comparatorY;
    private volatile int comparatorZ;

    private volatile UUID hologramId;

    public Suppressor(UUID worldId, String worldName, int x, int y, int z, int radius) {
        this.worldId = worldId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
    }

    // ---- 容器身份 ----

    public UUID worldId() {
        return worldId;
    }

    public String worldName() {
        return worldName;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public int radius() {
        return radius;
    }

    public void radius(int radius) {
        this.radius = radius;
    }

    public boolean isContainerAt(UUID otherWorld, int bx, int by, int bz) {
        return worldId.equals(otherWorld) && x == bx && y == by && z == bz;
    }

    // ---- 抑制域 ----

    public boolean armed() {
        return armed;
    }

    public int centerX() {
        return centerX;
    }

    public int centerY() {
        return centerY;
    }

    public int centerZ() {
        return centerZ;
    }

    public int comparatorX() {
        return comparatorX;
    }

    public int comparatorY() {
        return comparatorY;
    }

    public int comparatorZ() {
        return comparatorZ;
    }

    public boolean isComparatorAt(UUID otherWorld, int bx, int by, int bz) {
        return armed && worldId.equals(otherWorld)
                && comparatorX == bx && comparatorY == by && comparatorZ == bz;
    }

    /** 该坐标是否落在抑制域内。 */
    public boolean contains(UUID otherWorld, int bx, int by, int bz) {
        return armed && worldId.equals(otherWorld)
                && Math.abs(bx - centerX) <= radius
                && Math.abs(by - centerY) <= radius
                && Math.abs(bz - centerZ) <= radius;
    }

    public void arm(int comparatorX, int comparatorY, int comparatorZ,
                    int centerX, int centerY, int centerZ) {
        this.comparatorX = comparatorX;
        this.comparatorY = comparatorY;
        this.comparatorZ = comparatorZ;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.armed = true;
    }

    public void disarm() {
        this.armed = false;
    }

    // ---- 悬浮标签 ----

    public UUID hologramId() {
        return hologramId;
    }

    public void hologramId(UUID hologramId) {
        this.hologramId = hologramId;
    }

    // ---- 展示 ----

    public String containerText() {
        return x + ", " + y + ", " + z;
    }

    public String centerText() {
        return centerX + ", " + centerY + ", " + centerZ;
    }
}

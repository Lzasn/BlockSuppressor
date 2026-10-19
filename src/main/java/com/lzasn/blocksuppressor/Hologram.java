package com.lzasn.blocksuppressor;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 抑制器容器上方的常驻悬浮标签（TextDisplay 实体）。
 *
 * <p>Folia：生成在容器所在 region 的线程上；移除走 EntityScheduler，
 * 不跨线程碰实体。实体 non-persistent，区块卸载时随之丢弃，
 * 区块重新加载时由扫描重新生成。</p>
 */
final class Hologram {

    private static final String TAG = "blocksuppressor_hologram";

    private Hologram() {
    }

    static UUID spawn(BlockSuppressorPlugin plugin, World world, int x, int y, int z) {
        Location location = new Location(world, x + 0.5D, y + plugin.hologramHeight(), z + 0.5D);
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            return null;
        }
        Component label = Component.text(plugin.hologramText(), NamedTextColor.AQUA);
        TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
            entity.text(label);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setLineWidth(200);
            entity.setSeeThrough(false);
            entity.setShadowed(true);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setGlowing(false);
            entity.addScoreboardTag(TAG);
        });
        return display == null ? null : display.getUniqueId();
    }

    static boolean exists(UUID id) {
        if (id == null) {
            return false;
        }
        try {
            Entity entity = Bukkit.getEntity(id);
            return entity != null && entity.isValid();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    static void remove(BlockSuppressorPlugin plugin, UUID id) {
        if (id == null) {
            return;
        }
        Entity entity;
        try {
            entity = Bukkit.getEntity(id);
        } catch (RuntimeException exception) {
            return;
        }
        if (entity == null) {
            return;
        }
        entity.getScheduler().run(plugin, task -> entity.remove(), null);
    }
}

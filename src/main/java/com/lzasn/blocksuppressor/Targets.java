package com.lzasn.blocksuppressor;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.EnderChest;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * "这个方块算不算抑制器"的统一判定。
 *
 * <p>箱子/陷阱箱：方块实体里的 CustomName 就是铁砧改的名字，直接比对。</p>
 *
 * <p>末影箱：原版的 {@code EnderChestBlockEntity} 没实现 Nameable，铁砧改的名字
 * 放下就丢了（没有任何字段能存）。所以我们在它的方块实体 PDC 里写一个标记，
 * 由 {@code BlockPlaceEvent} 时读取物品名写入，效果等价于"改名"。</p>
 */
final class Targets {

    private Targets() {
    }

    static boolean isSuppressor(BlockSuppressorPlugin plugin, BlockState state) {
        if (state == null || !plugin.allowedContainers().contains(state.getType())) {
            return false;
        }
        if (state instanceof EnderChest enderChest) {
            return enderChest.getPersistentDataContainer()
                    .has(plugin.suppressorKey(), PersistentDataType.BYTE);
        }
        if (state instanceof Container container) {
            return Names.matches(plugin, container);
        }
        return false;
    }

    /** 手上这件物品（块状物）是不是一个"改过名的抑制器"。 */
    static boolean isNamedSuppressorItem(BlockSuppressorPlugin plugin, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        if (!plugin.allowedContainers().contains(item.getType())) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && Names.matches(plugin, meta.customName());
    }

    static void markEnderChest(BlockSuppressorPlugin plugin, Block block) {
        BlockState state = block.getState();
        if (!(state instanceof EnderChest enderChest)) {
            return;
        }
        enderChest.getPersistentDataContainer()
                .set(plugin.suppressorKey(), PersistentDataType.BYTE, (byte) 1);
        enderChest.update(true, false);
    }

    static void unmarkEnderChest(BlockSuppressorPlugin plugin, Block block) {
        BlockState state = block.getState();
        if (!(state instanceof EnderChest enderChest)) {
            return;
        }
        enderChest.getPersistentDataContainer().remove(plugin.suppressorKey());
        enderChest.update(true, false);
    }
}

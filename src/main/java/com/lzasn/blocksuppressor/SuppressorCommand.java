package com.lzasn.blocksuppressor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /suppressor list | remove | reload */
public final class SuppressorCommand implements CommandExecutor, TabCompleter {

    private final BlockSuppressorPlugin plugin;
    private final SuppressorManager manager;

    public SuppressorCommand(BlockSuppressorPlugin plugin, SuppressorManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blocksuppressor.admin")) {
            sender.sendMessage("§c你没有使用该命令的权限。");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "remove" -> remove(sender);
            case "reload" -> {
                plugin.applyConfig();
                sender.sendMessage("§a[抑制器] 配置已重载：半径 " + plugin.zoneRadius()
                        + "，接受的名字 " + plugin.suppressorNames());
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§e§l方块抑制器");
        sender.sendMessage("§f/" + label + " list §7— 查看已登记的抑制器");
        sender.sendMessage("§f/" + label + " remove §7— 移除离你最近的一个（8 格内）");
        sender.sendMessage("§f/" + label + " reload §7— 重载配置");
        sender.sendMessage("§7用法：铁砧把容器重命名为「方块抑制器」或「Block Suppressor」，");
        sender.sendMessage("§7放下后接一个背离它、指向目标的比较器。");
    }

    private void list(CommandSender sender) {
        List<Suppressor> all = manager.all();
        if (all.isEmpty()) {
            sender.sendMessage("§7当前没有已登记的抑制器。把容器在铁砧里重命名为「方块抑制器」或「Block Suppressor」即可。");
            return;
        }
        sender.sendMessage("§e已登记抑制器 " + all.size() + " 个：");
        for (Suppressor suppressor : all) {
            if (suppressor.armed()) {
                sender.sendMessage("§8- §f" + suppressor.worldName()
                        + " §7容器 §e" + suppressor.containerText()
                        + " §a已接比较器 §7中心 §e" + suppressor.centerText()
                        + " §7半径 §e" + suppressor.radius());
            } else {
                sender.sendMessage("§8- §f" + suppressor.worldName()
                        + " §7容器 §e" + suppressor.containerText() + " §7待机（未接比较器）");
            }
        }
    }

    private void remove(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令需要玩家执行。");
            return;
        }
        Location location = player.getLocation();
        Suppressor suppressor = manager.nearest(location, 8.0D);
        if (suppressor == null) {
            sender.sendMessage("§c8 格内没有已登记的抑制器。");
            return;
        }
        manager.unregister(suppressor);

        World world = Bukkit.getWorld(suppressor.worldId());
        if (world == null || !world.isChunkLoaded(suppressor.x() >> 4, suppressor.z() >> 4)) {
            sender.sendMessage("§e已移除登记，但容器所在区块未加载，名字没清除——重新加载后它还会变成抑制器。");
            return;
        }
        Bukkit.getRegionScheduler().run(plugin,
                new Location(world, suppressor.x() + 0.5D, suppressor.y(), suppressor.z() + 0.5D),
                task -> {
                    Block block = world.getBlockAt(suppressor.x(), suppressor.y(), suppressor.z());
                    if (block.getType() == Material.ENDER_CHEST) {
                        Targets.unmarkEnderChest(plugin, block);
                        return;
                    }
                    BlockState state = block.getState();
                    if (state instanceof Container container) {
                        container.customName(null);
                        container.update(true, false);
                    }
                });
        sender.sendMessage("§a已移除抑制器并清除标记：§e" + suppressor.containerText());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String sub : new String[]{"list", "remove", "reload"}) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(sub);
                }
            }
            return options;
        }
        return List.of();
    }
}

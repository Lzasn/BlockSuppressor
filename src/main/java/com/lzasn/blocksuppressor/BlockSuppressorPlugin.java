package com.lzasn.blocksuppressor;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * BlockSuppressor —— 用「铁砧重命名过的容器 + 比较器」复刻原版更新抑制器。
 *
 * <p>容器重命名为 {@code 方块抑制器} 或 {@code Block Suppressor}（忽略大小写 / 空格）后即成为抑制器；
 * 旁边接一个背离容器指向目标的比较器，比较器指向的方块成为抑制域中心。
 * 域内方块不再接收方块更新（BlockPhysicsEvent 被取消），因此拆掉下界门框架时门不会碎（切门）。</p>
 *
 * <p>本插件按 Folia 的 region 模型编写：不使用 BukkitScheduler，所有延迟任务走
 * {@link org.bukkit.Bukkit#getRegionScheduler()}，共享数据全部为并发容器。</p>
 */
public final class BlockSuppressorPlugin extends JavaPlugin {

    private static final Set<Material> DEFAULT_CONTAINERS =
            Set.of(Material.CHEST, Material.TRAPPED_CHEST, Material.ENDER_CHEST);

    private SuppressorManager manager;

    /** 归一化后的合法名字集合。 */
    private volatile Set<String> suppressorNames = Set.of();

    /** 可以当抑制器的容器方块类型。 */
    private volatile Set<Material> allowedContainers = DEFAULT_CONTAINERS;

    /** 末影箱用的方块实体标记键（它存不了名字）。 */
    private NamespacedKey suppressorKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        suppressorKey = new NamespacedKey(this, "suppressor");
        loadSettings();

        manager = new SuppressorManager(this);
        getServer().getPluginManager().registerEvents(new SuppressorListener(this, manager), this);

        PluginCommand command = getCommand("suppressor");
        if (command != null) {
            SuppressorCommand executor = new SuppressorCommand(this, manager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().warning("plugin.yml 中没有 suppressor 命令定义");
        }

        getLogger().info("BlockSuppressor 已启用（Folia region 调度器），当前已登记抑制器：" + manager.size());
    }

    @Override
    public void onDisable() {
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
    }

    /** 重载配置：名字 / 允许的容器类型；半径在下一次接上比较器时生效。 */
    public void applyConfig() {
        reloadConfig();
        loadSettings();
    }

    private void loadSettings() {
        Set<String> names = new HashSet<>();
        List<String> configured = getConfig().getStringList("suppressor-names");
        for (String raw : configured) {
            String normalized = Names.normalize(raw);
            if (!normalized.isEmpty()) {
                names.add(normalized);
            }
        }
        if (names.isEmpty()) {
            names.add(Names.normalize("方块抑制器"));
            names.add(Names.normalize("Block Suppressor"));
        }
        this.suppressorNames = Set.copyOf(names);

        Set<Material> containers = new HashSet<>();
        for (String raw : getConfig().getStringList("allowed-containers")) {
            try {
                containers.add(Material.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                getLogger().warning("allowed-containers 里有无效的方块名：" + raw);
            }
        }
        if (containers.isEmpty()) {
            containers.addAll(DEFAULT_CONTAINERS);
        }
        this.allowedContainers = Set.copyOf(containers);
    }

    public Set<String> suppressorNames() {
        return suppressorNames;
    }

    public Set<Material> allowedContainers() {
        return allowedContainers;
    }

    public NamespacedKey suppressorKey() {
        return suppressorKey;
    }

    /** 抑制域半径，受 max-radius 限制。 */
    public int zoneRadius() {
        int radius = getConfig().getInt("radius", 1);
        int max = Math.max(0, getConfig().getInt("max-radius", 3));
        return Math.max(0, Math.min(radius, max));
    }

    /** 试图打开抑制器容器时，物品栏上方显示的红字。 */
    public String lockedMessage() {
        return getConfig().getString("locked-message", "该容器已被锁定为方块抑制器，无法打开");
    }

    // ---- 悬浮标签 ----

    public boolean hologramEnabled() {
        return getConfig().getBoolean("hologram.enabled", true);
    }

    public String hologramText() {
        return getConfig().getString("hologram.text", "Block Suppressor");
    }

    public double hologramHeight() {
        return getConfig().getDouble("hologram.height", 1.45D);
    }
}

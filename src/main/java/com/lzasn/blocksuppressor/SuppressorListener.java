package com.lzasn.blocksuppressor;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 抑制器相关的全部事件。
 *
 * <p>Folia 注意：事件处理函数在拥有该方块/区块的 region 线程上执行，所以可以直接读写
 * 当前方块；延迟任务通过 RegionScheduler 投递到目标位置所属的 region。</p>
 */
public final class SuppressorListener implements Listener {

    private static final BlockFace[] HORIZONTAL = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST
    };

    private final BlockSuppressorPlugin plugin;
    private final SuppressorManager manager;

    public SuppressorListener(BlockSuppressorPlugin plugin, SuppressorManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ------------------------------------------------------------------
    // 抑制器旁边的箱子：放下后强制拆成两个单箱
    // ------------------------------------------------------------------

    /**
     * 原版两个相邻箱子会合并成大箱子。这里不拦放置，而是在放下之后把两边都强制改回
     * {@link Chest.Type#SINGLE}：{@code ChestType} 是方块状态属性，合并判定要求邻居不是
     * SINGLE，所以两边都置成 SINGLE 之后不会再被形状更新合并回去，各自是独立的 27 格。
     */
    private void splitChestIfNeeded(World world, Block placed) {
        Material type = placed.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST) {
            return;
        }
        boolean placedIsSuppressor = Targets.isSuppressor(plugin, placed.getState(false));
        for (BlockFace face : HORIZONTAL) {
            Block neighbor = placed.getRelative(face);
            if (neighbor.getType() != type) {
                continue;
            }
            if (!loaded(world, neighbor.getX(), neighbor.getZ())) {
                continue;
            }
            if (placedIsSuppressor || Targets.isSuppressor(plugin, neighbor.getState(false))) {
                forceSingle(placed);
                forceSingle(neighbor);
            }
        }
    }

    private void forceSingle(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Chest chest && chest.getType() != Chest.Type.SINGLE) {
            chest.setType(Chest.Type.SINGLE);
            block.setBlockData(chest, false);
        }
    }

    // ------------------------------------------------------------------
    // 放置：重命名容器 = 抑制器；比较器决定抑制哪一格
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block placed = event.getBlockPlaced();
        World world = placed.getWorld();
        int x = placed.getX();
        int y = placed.getY();
        int z = placed.getZ();
        // 末影箱放下后名字会丢，所以要在事件里先记下"手上这件是不是改过名的"
        boolean namedItem = Targets.isNamedSuppressorItem(plugin, event.getItemInHand());

        // 下个 tick 再判断：此时方块一定已经真正写进世界
        scheduleAt(world, x, y, z, () -> handlePlace(world, x, y, z, namedItem));
    }

    private void handlePlace(World world, int x, int y, int z, boolean namedItem) {
        Block block = world.getBlockAt(x, y, z);

        // 抑制器旁边的箱子：放下后直接拆成两个单箱，不拦放置
        splitChestIfNeeded(world, block);

        if (block.getType() == Material.COMPARATOR) {
            BlockFace facing = ((Comparator) block.getBlockData()).getFacing();
            Block container = block.getRelative(facing.getOppositeFace());
            if (!loaded(world, container.getX(), container.getZ())) {
                return;
            }
            if (!Targets.isSuppressor(plugin, container.getState(false))) {
                return;
            }
            Suppressor suppressor = manager.register(world, container.getX(), container.getY(), container.getZ());
            manager.arm(suppressor, block);
            return;
        }

        BlockState state = block.getState(false);
        // 末影箱的方块实体没有 Nameable，名字存不住：把"改过名"落成 PDC 标记
        if (state.getType() == Material.ENDER_CHEST && namedItem
                && plugin.allowedContainers().contains(Material.ENDER_CHEST)) {
            Targets.markEnderChest(plugin, block);
            state = block.getState(false);
        }
        if (!Targets.isSuppressor(plugin, state)) {
            return;
        }
        Suppressor suppressor = manager.register(world, x, y, z);
        manager.resync(suppressor, world);
    }

    // ------------------------------------------------------------------
    // 破坏：拆容器 = 注销；拆比较器 = 回到待机
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (manager.size() == 0) {
            return;
        }
        Block block = event.getBlock();
        UUID worldId = block.getWorld().getUID();

        if (block.getType() == Material.COMPARATOR) {
            Suppressor suppressor = manager.byComparator(worldId, block.getX(), block.getY(), block.getZ());
            if (suppressor != null) {
                manager.disarm(suppressor);
                plugin.getLogger().info("抑制器回到待机（比较器被拆）：容器 " + suppressor.containerText());
            }
            return;
        }

        Suppressor suppressor = manager.byContainer(worldId, block.getX(), block.getY(), block.getZ());
        if (suppressor != null) {
            manager.unregister(suppressor);
            plugin.getLogger().info("抑制器已注销（容器被拆）：" + suppressor.containerText());
        }
    }

    // ------------------------------------------------------------------
    // 右键旋转比较器：重新解析指向
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.COMPARATOR) {
            return;
        }
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        scheduleAt(world, x, y, z, () -> resyncComparator(world, x, y, z));
    }

    private void resyncComparator(World world, int x, int y, int z) {
        Block comparator = world.getBlockAt(x, y, z);
        if (comparator.getType() != Material.COMPARATOR) {
            return;
        }
        UUID worldId = world.getUID();
        Suppressor owner = manager.byComparator(worldId, x, y, z);

        BlockFace facing = ((Comparator) comparator.getBlockData()).getFacing();
        Block container = comparator.getRelative(facing.getOppositeFace());
        if (loaded(world, container.getX(), container.getZ())
                && Targets.isSuppressor(plugin, container.getState(false))) {
            Suppressor suppressor = manager.register(world, container.getX(), container.getY(), container.getZ());
            manager.arm(suppressor, comparator);
            return;
        }
        if (owner != null) {
            manager.disarm(owner);
        }
    }

    // ------------------------------------------------------------------
    // 锁定：抑制器容器打不开，也不跟漏斗/漏斗矿车交换物品
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (manager.size() == 0) {
            return;
        }
        if (!manager.isLockedInventory(event.getInventory())) {
            return;
        }
        event.setCancelled(true);
        if (event.getPlayer() instanceof Player player) {
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (manager.size() == 0) {
            return;
        }
        if (manager.isLockedInventory(event.getSource())
                || manager.isLockedInventory(event.getDestination())) {
            event.setCancelled(true);
        }
    }

    private void deny(Player player) {
        player.sendActionBar(Component.text(plugin.lockedMessage(), NamedTextColor.RED));
    }

    /**
     * 末影箱不能用 InventoryOpenEvent 拦：它打开的是玩家自己的末影物品栏，
     * 从 Inventory 反查不到是哪一格方块。只能在右键这一步拦，
     * 但手里拿着方块（比如比较器）时放行，否则没法在它旁边搭红石。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnderChestInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || manager.size() == 0) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENDER_CHEST) {
            return;
        }
        if (manager.byContainer(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()) == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && item.getType() != Material.AIR && item.getType().isBlock()) {
            return;
        }
        event.setCancelled(true);
        deny(event.getPlayer());
    }

    // ------------------------------------------------------------------
    // 核心：取消抑制域内的方块物理事件
    // ------------------------------------------------------------------

    /**
     * Paper 在 {@code Level#notifyAndUpdatePhysics} 里抛出这个事件；
     * 取消它，紧接着的 updateNeighbourShapes / updateIndirectNeighbourShapes 就会被整段跳过。
     * 下界门方块正是靠形状更新（updateShape）才会碎裂，所以拆框架时门会保留。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (manager.isSuppressed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * 展示框 / 画这类挂载实体不吃方块更新：它们每 tick 自己检查 {@code survives()}，
     * 支撑方块没了就把自己拆掉（{@code HangingBreakEvent} 的 PHYSICS / OBSTRUCTION）。
     * 所以抑制域内要把这两个原因的事件取消掉，它们才会跟着"悬空"。
     * 玩家自己打掉的（ENTITY）不拦。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (manager.size() == 0) {
            return;
        }
        HangingBreakEvent.RemoveCause cause = event.getCause();
        if (cause != HangingBreakEvent.RemoveCause.PHYSICS
                && cause != HangingBreakEvent.RemoveCause.OBSTRUCTION) {
            return;
        }
        Hanging hanging = event.getEntity();
        if (manager.isSuppressedAt(hanging.getLocation())) {
            event.setCancelled(true);
            return;
        }
        // 展示框本身在抑制域外、但它钉的那面墙在域内时也保住
        BlockFace facing = hanging.getFacing();
        Block support = hanging.getLocation().getBlock()
                .getRelative(facing.getOppositeFace());
        if (manager.isSuppressedAt(support.getLocation())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // 区块：世界即真相，加载时补登记 / 卸载时丢记录
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        manager.handleChunkLoad(event.getWorld(), event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        manager.handleChunkUnload(event.getWorld(), event.getChunk());
    }

    // ------------------------------------------------------------------

    /** 在目标位置所属的 region 上下个 tick 执行。 */
    private void scheduleAt(World world, int x, int y, int z, Runnable action) {
        Bukkit.getRegionScheduler().runDelayed(plugin, new Location(world, x, y, z), task -> action.run(), 1L);
    }

    /** Folia 下不能顺手加载区块：跨到未加载区块的读取一律跳过。 */    private static boolean loaded(World world, int x, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4);
    }
}

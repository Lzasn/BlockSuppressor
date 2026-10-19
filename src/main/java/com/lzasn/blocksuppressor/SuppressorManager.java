package com.lzasn.blocksuppressor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.type.Comparator;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * 抑制器注册表。
 *
 * <p>"真相"在世界里：容器是否被重命名、旁边有没有比较器，都直接从方块读。
 * 所以没有 YAML 存档——区块加载时重扫，区块卸载时把记录丢掉。</p>
 */
public final class SuppressorManager {

    private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH,
            BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private final BlockSuppressorPlugin plugin;

    /** 所有抑制器（被重命名的容器）。 */
    private final Set<Suppressor> suppressors = ConcurrentHashMap.newKeySet();

    /** worldId -> (center 所在 chunkKey -> 已接比较器的抑制器)。 */
    private final Map<UUID, Map<Long, List<Suppressor>>> zoneIndex = new ConcurrentHashMap<>();

    public SuppressorManager(BlockSuppressorPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public int size() {
        return suppressors.size();
    }

    public List<Suppressor> all() {
        return new ArrayList<>(suppressors);
    }

    public Suppressor byContainer(UUID worldId, int x, int y, int z) {
        for (Suppressor suppressor : suppressors) {
            if (suppressor.isContainerAt(worldId, x, y, z)) {
                return suppressor;
            }
        }
        return null;
    }

    public Suppressor byComparator(UUID worldId, int x, int y, int z) {
        for (Suppressor suppressor : suppressors) {
            if (suppressor.isComparatorAt(worldId, x, y, z)) {
                return suppressor;
            }
        }
        return null;
    }

    /** 高频路径：每次方块物理事件都会走这里。 */
    public boolean isSuppressed(Block block) {
        return isSuppressedAt(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    /** 实体位置（展示框、画之类）是否落在抑制域里。 */
    public boolean isSuppressedAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        return isSuppressedAt(location.getWorld(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public boolean isSuppressedAt(World world, int x, int y, int z) {
        UUID worldId = world.getUID();
        Map<Long, List<Suppressor>> worldIndex = zoneIndex.get(worldId);
        if (worldIndex == null || worldIndex.isEmpty()) {
            return false;
        }
        List<Suppressor> candidates = worldIndex.get(chunkKey(x >> 4, z >> 4));
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (Suppressor suppressor : candidates) {
            if (suppressor.contains(worldId, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public Suppressor nearest(Location location, double maxDistance) {
        UUID worldId = location.getWorld().getUID();
        Suppressor best = null;
        double bestDistance = maxDistance * maxDistance;
        for (Suppressor suppressor : suppressors) {
            if (!suppressor.worldId().equals(worldId)) {
                continue;
            }
            double dx = suppressor.x() + 0.5D - location.getX();
            double dy = suppressor.y() + 0.5D - location.getY();
            double dz = suppressor.z() + 0.5D - location.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = suppressor;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 增删
    // ------------------------------------------------------------------

    public Suppressor register(World world, int x, int y, int z) {
        Suppressor existing = byContainer(world.getUID(), x, y, z);
        if (existing != null) {
            return existing;
        }
        Suppressor suppressor = new Suppressor(world.getUID(), world.getName(), x, y, z, plugin.zoneRadius());
        suppressors.add(suppressor);
        return suppressor;
    }

    public void unregister(Suppressor suppressor) {
        if (!suppressors.remove(suppressor)) {
            return;
        }
        dismissHologram(suppressor);
        disarm(suppressor);
    }

    // ------------------------------------------------------------------
    // 抑制域：容器旁边有没有比较器
    // ------------------------------------------------------------------

    /**
     * 重新解析容器旁边、背离容器指向外面的比较器。
     *
     * @return 是否处于已接比较器的状态
     */
    public boolean resync(Suppressor suppressor, World world) {
        if (!world.isChunkLoaded(suppressor.x() >> 4, suppressor.z() >> 4)) {
            return suppressor.armed();
        }
        Block container = world.getBlockAt(suppressor.x(), suppressor.y(), suppressor.z());
        for (BlockFace face : FACES) {
            Block neighbor = container.getRelative(face);
            if (!world.isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)) {
                continue;
            }
            if (neighbor.getType() != Material.COMPARATOR) {
                continue;
            }
            BlockFace facing = ((Comparator) neighbor.getBlockData()).getFacing();
            Block readFrom = neighbor.getRelative(facing.getOppositeFace());
            if (readFrom.getX() == suppressor.x()
                    && readFrom.getY() == suppressor.y()
                    && readFrom.getZ() == suppressor.z()) {
                arm(suppressor, neighbor);
                return true;
            }
        }
        disarm(suppressor);
        return false;
    }

    public void arm(Suppressor suppressor, Block comparator) {
        BlockFace facing = ((Comparator) comparator.getBlockData()).getFacing();
        Block target = comparator.getRelative(facing);
        if (suppressor.armed()) {
            unindex(suppressor);
        }
        suppressor.radius(plugin.zoneRadius());
        suppressor.arm(comparator.getX(), comparator.getY(), comparator.getZ(),
                target.getX(), target.getY(), target.getZ());
        index(suppressor);
        // 只有接上比较器（真正开始抑制）才显示悬浮标签
        ensureHologram(suppressor);
    }

    public void disarm(Suppressor suppressor) {
        if (!suppressor.armed()) {
            return;
        }
        unindex(suppressor);
        suppressor.disarm();
        dismissHologram(suppressor);
    }

    // ------------------------------------------------------------------
    // 区块加载 / 卸载：世界即真相
    // ------------------------------------------------------------------

    public void handleChunkLoad(World world, Chunk chunk) {
        UUID worldId = world.getUID();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        // 1) 本区块里已登记的抑制器：容器还在、名字还对就复检比较器
        for (Suppressor suppressor : new ArrayList<>(suppressors)) {
            if (!suppressor.worldId().equals(worldId)) {
                continue;
            }
            if ((suppressor.x() >> 4) != chunkX || (suppressor.z() >> 4) != chunkZ) {
                continue;
            }
            if (!stillValid(world, suppressor)) {
                unregister(suppressor);
                continue;
            }
            resync(suppressor, world);
        }

        // 2) 扫描本区块的抑制器容器，补登记（插件后装 / 区块刚加载）
        for (BlockState state : chunk.getTileEntities(false)) {
            if (!Targets.isSuppressor(plugin, state)) {
                continue;
            }
            if (byContainer(worldId, state.getX(), state.getY(), state.getZ()) != null) {
                continue;
            }
            Suppressor suppressor = register(world, state.getX(), state.getY(), state.getZ());
            resync(suppressor, world);
        }
    }

    public void handleChunkUnload(World world, Chunk chunk) {
        UUID worldId = world.getUID();
        for (Suppressor suppressor : new ArrayList<>(suppressors)) {
            if (!suppressor.worldId().equals(worldId)) {
                continue;
            }
            if ((suppressor.x() >> 4) != chunk.getX() || (suppressor.z() >> 4) != chunk.getZ()) {
                continue;
            }
            // 实体随区块一起丢弃，不需要（也不能）单独 remove
            suppressors.remove(suppressor);
            if (suppressor.armed()) {
                unindex(suppressor);
            }
            suppressor.hologramId(null);
        }
    }

    public boolean stillValid(World world, Suppressor suppressor) {
        if (!world.isChunkLoaded(suppressor.x() >> 4, suppressor.z() >> 4)) {
            return true;
        }
        return Targets.isSuppressor(plugin,
                world.getBlockAt(suppressor.x(), suppressor.y(), suppressor.z()).getState(false));
    }

    // ------------------------------------------------------------------
    // 悬浮标签
    // ------------------------------------------------------------------

    private void spawnHologram(Suppressor suppressor) {
        if (!plugin.hologramEnabled()) {
            return;
        }
        World world = Bukkit.getWorld(suppressor.worldId());
        if (world == null) {
            return;
        }
        try {
            suppressor.hologramId(Hologram.spawn(plugin, world, suppressor.x(), suppressor.y(), suppressor.z()));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("悬浮标签生成失败（" + suppressor.containerText() + "）：" + exception.getMessage());
        }
    }

    private void ensureHologram(Suppressor suppressor) {
        if (!plugin.hologramEnabled()) {
            return;
        }
        if (Hologram.exists(suppressor.hologramId())) {
            return;
        }
        spawnHologram(suppressor);
    }

    private void dismissHologram(Suppressor suppressor) {
        UUID id = suppressor.hologramId();
        suppressor.hologramId(null);
        Hologram.remove(plugin, id);
    }

    // ------------------------------------------------------------------
    // 锁定：抑制器容器不能打开，也不能和漏斗交换物品
    // ------------------------------------------------------------------

    public boolean isSuppressorAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        return byContainer(location.getWorld().getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()) != null;
    }

    /**
     * 该物品栏是否属于某个抑制器容器。
     *
     * <p>大箱子要特殊处理：{@code getLocation()} 只给左半边，
     * 所以还要看 {@link DoubleChest} 的左右两个方块。</p>
     */
    public boolean isLockedInventory(Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        if (isSuppressorAt(inventory.getLocation())) {
            return true;
        }
        // 只有大箱子需要额外看两半：它的 getLocation() 只给左半边
        if (!(inventory instanceof DoubleChestInventory)) {
            return false;
        }
        try {
            if (inventory.getHolder() instanceof DoubleChest doubleChest) {
                return isSuppressorHolder(doubleChest.getLeftSide())
                        || isSuppressorHolder(doubleChest.getRightSide());
            }
        } catch (RuntimeException ignored) {
            // 跨 region 读不到就当作没锁，宁可放过也不要误伤
        }
        return false;
    }

    private boolean isSuppressorHolder(InventoryHolder holder) {
        if (holder instanceof BlockState state) {
            return byContainer(state.getWorld().getUID(), state.getX(), state.getY(), state.getZ()) != null;
        }
        return holder != null && isSuppressorAt(holder.getInventory().getLocation());
    }

    // ------------------------------------------------------------------
    // 抑制域索引
    // ------------------------------------------------------------------

    private void index(Suppressor suppressor) {
        Map<Long, List<Suppressor>> worldIndex =
                zoneIndex.computeIfAbsent(suppressor.worldId(), key -> new ConcurrentHashMap<>());
        int minX = suppressor.centerX() - suppressor.radius();
        int maxX = suppressor.centerX() + suppressor.radius();
        int minZ = suppressor.centerZ() - suppressor.radius();
        int maxZ = suppressor.centerZ() + suppressor.radius();
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                worldIndex.computeIfAbsent(chunkKey(cx, cz), key -> new CopyOnWriteArrayList<>()).add(suppressor);
            }
        }
    }

    private void unindex(Suppressor suppressor) {
        Map<Long, List<Suppressor>> worldIndex = zoneIndex.get(suppressor.worldId());
        if (worldIndex == null) {
            return;
        }
        int minX = suppressor.centerX() - suppressor.radius();
        int maxX = suppressor.centerX() + suppressor.radius();
        int minZ = suppressor.centerZ() - suppressor.radius();
        int maxZ = suppressor.centerZ() + suppressor.radius();
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                List<Suppressor> list = worldIndex.get(chunkKey(cx, cz));
                if (list != null) {
                    list.remove(suppressor);
                }
            }
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}

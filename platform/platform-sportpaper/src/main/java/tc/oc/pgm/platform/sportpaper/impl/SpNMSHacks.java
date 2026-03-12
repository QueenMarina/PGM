package tc.oc.pgm.platform.sportpaper.impl;

import static tc.oc.pgm.util.nms.Packets.ENTITIES;
import static tc.oc.pgm.util.platform.Supports.Variant.SPORTPAPER;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.server.v1_8_R3.BlockPosition;
import net.minecraft.server.v1_8_R3.ChunkSection;
import net.minecraft.server.v1_8_R3.DataWatcher;
import net.minecraft.server.v1_8_R3.EntityArrow;
import net.minecraft.server.v1_8_R3.EntityFireball;
import net.minecraft.server.v1_8_R3.EntityFireworks;
import net.minecraft.server.v1_8_R3.IBlockData;
import net.minecraft.server.v1_8_R3.IDataManager;
import net.minecraft.server.v1_8_R3.NBTTagCompound;
import net.minecraft.server.v1_8_R3.PacketPlayOutEntityMetadata;
import net.minecraft.server.v1_8_R3.PacketPlayOutSpawnEntity;
import net.minecraft.server.v1_8_R3.ServerNBTManager;
import net.minecraft.server.v1_8_R3.WorldData;
import net.minecraft.server.v1_8_R3.WorldServer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.v1_8_R3.CraftChunk;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftFireball;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftFirework;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftItem;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_8_R3.util.CraftMagicNumbers;
import org.bukkit.craftbukkit.v1_8_R3.inventory.CraftItemStack;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import tc.oc.pgm.platform.sportpaper.packets.SpPacket;
import tc.oc.pgm.util.chunk.NullChunkGenerator;
import tc.oc.pgm.util.material.BlockMaterialData;
import tc.oc.pgm.util.material.Materials;
import tc.oc.pgm.util.nms.NMSHacks;
import tc.oc.pgm.util.platform.Supports;
import tc.oc.pgm.util.reflect.ReflectionUtils;
import tc.oc.pgm.util.skin.Skin;

@Supports(SPORTPAPER)
public class SpNMSHacks implements NMSHacks {
  @Override
  public void skipFireworksLaunch(Firework firework) {
    EntityFireworks entityFirework = ((CraftFirework) firework).getHandle();
    entityFirework.expectedLifespan = 2;
    entityFirework.ticksFlown = 2;
    ENTITIES
        .entityMetadataPacket(firework.getEntityId(), firework, false)
        .sendToViewers(firework, false);
  }

  @Override
  public boolean isCraftItemArrowEntity(PlayerPickupItemEvent event) {
    return ((CraftItem) event.getItem()).getHandle() instanceof EntityArrow;
  }

  @Override
  public void freezeEntity(Entity entity) {
    net.minecraft.server.v1_8_R3.Entity nmsEntity = ((CraftEntity) entity).getHandle();
    NBTTagCompound tag = new NBTTagCompound();
    nmsEntity.c(tag); // save to tag
    tag.setBoolean("NoAI", true);
    tag.setBoolean("NoGravity", true);
    nmsEntity.f(tag); // load from tag
  }

  @Override
  public void setFireballDirection(Fireball entity, Vector direction) {
    EntityFireball fireball = ((CraftFireball) entity).getHandle();
    fireball.dirX = direction.getX() * 0.1D;
    fireball.dirY = direction.getY() * 0.1D;
    fireball.dirZ = direction.getZ() * 0.1D;
  }

  @Override
  public long getMonotonicTime(World world) {
    return ((CraftWorld) world).getHandle().getTime();
  }

  @Override
  public void scheduleFluidTick(Block block) {
    if (block == null) return;
    var type = block.getType();
    if (!(type == Material.WATER
        || type == Materials.STILL_WATER
        || type == Material.LAVA
        || type == Materials.STILL_LAVA)) {
      return;
    }

    WorldServer world = ((CraftWorld) block.getWorld()).getHandle();
    net.minecraft.server.v1_8_R3.Block nmsBlock = CraftMagicNumbers.getBlock(type);
    if (nmsBlock == null) return;

    BlockPosition pos = new BlockPosition(block.getX(), block.getY(), block.getZ());

    // Best-effort: wake both the tick scheduler and neighbor/physics propagation.
    world.applyPhysics(pos, nmsBlock);
    world.update(pos, nmsBlock);
    world.notify(pos);
    world.a(pos, nmsBlock, 1);
  }

  @Override
  public void resumeServer() {
    if (Bukkit.getServer().isSuspended()) Bukkit.getServer().setSuspended(false);
  }

  @Override
  public Inventory createFakeInventory(Player viewer, Inventory realInventory) {
    if (realInventory.hasCustomName()) {
      //noinspection deprecation
      return realInventory instanceof DoubleChestInventory
          ? Bukkit.createInventory(viewer, realInventory.getSize(), realInventory.getName())
          : Bukkit.createInventory(viewer, realInventory.getType(), realInventory.getName());
    } else {
      return realInventory instanceof DoubleChestInventory
          ? Bukkit.createInventory(viewer, realInventory.getSize())
          : Bukkit.createInventory(viewer, realInventory.getType());
    }
  }

  @Override
  public List<Block> getBlocks(Chunk bukkitChunk, Material material) {
    CraftChunk craftChunk = (CraftChunk) bukkitChunk;
    List<Block> blocks = new ArrayList<>();

    net.minecraft.server.v1_8_R3.Block nmsBlock = CraftMagicNumbers.getBlock(material);
    net.minecraft.server.v1_8_R3.Chunk chunk = craftChunk.getHandle();

    for (ChunkSection section : chunk.getSections()) {
      if (section == null || section.a()) continue; // ChunkSection.a() -> true if section is empty

      char[] blockIds = section.getIdArray();
      for (int i = 0; i < blockIds.length; i++) {
        // This does a lookup in the block registry, but does not create any objects, so should be
        // pretty efficient
        IBlockData blockData = net.minecraft.server.v1_8_R3.Block.d.a(blockIds[i]);
        if (blockData != null && blockData.getBlock() == nmsBlock) {
          blocks.add(
              bukkitChunk.getBlock(i & 0xf, section.getYPosition() | (i >> 8), (i >> 4) & 0xf));
        }
      }
    }

    return blocks;
  }

  @Override
  public void setSkullMetaOwner(SkullMeta meta, String name, UUID uuid, Skin skin) {
    meta.setOwner(name, uuid, new org.bukkit.Skin(skin.getData(), skin.getSignature()));
  }

  @Override
  public World createWorld(String worldName, World.Environment env, boolean terrain, long seed) {
    WorldCreator creator = new WorldCreator(worldName);

    IDataManager sdm =
        new ServerNBTManager(Bukkit.getServer().getWorldContainer(), worldName, true);
    WorldData worldData = sdm.getWorldData();
    if (worldData != null) {
      creator
          .generateStructures(worldData.shouldGenerateMapFeatures())
          .generatorSettings(worldData.getGeneratorOptions())
          .seed(worldData.getSeed())
          .type(org.bukkit.WorldType.getByName(worldData.getType().name()));
    }

    return Bukkit.getServer()
        .createWorld(creator
            .environment(env)
            .generator(terrain ? null : NullChunkGenerator.INSTANCE)
            .seed(terrain ? seed : creator.seed()));
  }

  @Override
  public boolean canMineBlock(BlockMaterialData blockMaterial, Player player) {
    ItemStack tool = player.getItemInHand();

    net.minecraft.server.v1_8_R3.Block nmsBlock =
        CraftMagicNumbers.getBlock(blockMaterial.getItemType());
    net.minecraft.server.v1_8_R3.Item nmsTool =
        tool == null ? null : CraftMagicNumbers.getItem(tool.getType());

    return nmsBlock != null
        && (nmsBlock.getMaterial().isAlwaysDestroyable()
            || (nmsTool != null && nmsTool.canDestroySpecialBlock(nmsBlock)));
  }

  @Override
  public void resetDimension(World world) {
    var nmsWorld = ((CraftWorld) world).getHandle();
    try {
      nmsWorld.dimension = 11;
    } catch (IllegalAccessError e) {

      Field worldServerField = ReflectionUtils.getField(CraftWorld.class, "world");
      Field dimensionField = ReflectionUtils.getField(WorldServer.class, "dimension");
      Field modifiersField = ReflectionUtils.getField(Field.class, "modifiers");

      try {
        modifiersField.setInt(dimensionField, dimensionField.getModifiers() & ~Modifier.FINAL);
        dimensionField.set(worldServerField.get(world), 11);
      } catch (IllegalAccessException ex) {
        // No-op, newer version of Java have disabled modifying final fields
      }
    }
  }

  @Override
  public void cleanupWorld(World world) {
    var nmsWorld = ((CraftWorld) world).getHandle();
    nmsWorld.craftingManager.lastCraftView = null;
    world.setKeepSpawnInMemory(false);
  }

  @Override
  public void cleanupPlayer(Player player) {
    var nmsPlayer = ((CraftPlayer) player).getHandle();
    nmsPlayer.killer = null;
    nmsPlayer.p(null); // Resets who last hit the entityLiving
  }

  @Override
  public double getTPS() {
    return Bukkit.getServer().spigot().getTPS()[0];
  }

  @Override
  public void postToMainThread(Plugin plugin, boolean priority, Runnable task) {
    Bukkit.getServer().postToMainThread(plugin, priority, task);
  }

  @Override
  public int getMaxWorldSize(World world) {
    return ((CraftWorld) world).getHandle().getWorldBorder().l();
  }

  @Override
  public int allocateEntityId() {
    return Bukkit.allocateEntityId();
  }

  @Override
  public int[] spawnClientSideItemBurst(
      Player viewer, Location center, ItemStack item, int count, double radius, double speed) {
    if (viewer == null || center == null || item == null) return new int[0];
    if (count <= 0) return new int[0];

    // Object type id for item entities in 1.8 spawn packets.
    final int ITEM_ENTITY_TYPE = 2;

    int[] ids = new int[count];
    final ThreadLocalRandom rnd = ThreadLocalRandom.current();

    // Metadata index for the carried item stack on EntityItem.
    final int ITEMSTACK_WATCHER = 10;
    final var nmsStack = CraftItemStack.asNMSCopy(item);

    for (int i = 0; i < count; i++) {
      int entityId = allocateEntityId();
      ids[i] = entityId;

      double angle = rnd.nextDouble(0, Math.PI * 2);
      double r = rnd.nextDouble(0, Math.max(0.01, radius));
      double dx = Math.cos(angle) * r;
      double dz = Math.sin(angle) * r;

      double x = center.getX() + dx;
      double y = center.getY() + rnd.nextDouble(0.2, 1.2);
      double z = center.getZ() + dz;

      double vx = dx * 0.35 + rnd.nextDouble(-0.03, 0.03);
      double vz = dz * 0.35 + rnd.nextDouble(-0.03, 0.03);
      double vy = rnd.nextDouble(speed * 0.6, speed);

      PacketPlayOutSpawnEntity spawn =
          new PacketPlayOutSpawnEntity(
              entityId,
              x,
              y,
              z,
              (int) (vx * 8000),
              (int) (vy * 8000),
              (int) (vz * 8000),
              0,
              0,
              ITEM_ENTITY_TYPE,
              1);

      DataWatcher watcher = new DataWatcher(null);
      watcher.a(ITEMSTACK_WATCHER, nmsStack);
      PacketPlayOutEntityMetadata meta = new PacketPlayOutEntityMetadata(entityId, watcher, true);

      new SpPacket<>(spawn).send(viewer);
      new SpPacket<>(meta).send(viewer);
    }

    return ids;
  }
}

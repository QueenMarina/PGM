package tc.oc.pgm.util.nms;

import java.util.List;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import tc.oc.pgm.util.material.BlockMaterialData;
import tc.oc.pgm.util.platform.Platform;
import tc.oc.pgm.util.skin.Skin;

public interface NMSHacks {
  NMSHacks NMS_HACKS = Platform.get(NMSHacks.class);

  void skipFireworksLaunch(Firework firework);

  boolean isCraftItemArrowEntity(PlayerPickupItemEvent item);

  void freezeEntity(Entity entity);

  void setFireballDirection(Fireball entity, Vector direction);

  long getMonotonicTime(World world);

  void resumeServer();

  Inventory createFakeInventory(Player viewer, Inventory realInventory);

  List<Block> getBlocks(Chunk bukkitChunk, Material material);

  void setSkullMetaOwner(SkullMeta meta, String name, UUID uuid, Skin skin);

  World createWorld(String worldName, World.Environment env, boolean terrain, long seed);

  boolean canMineBlock(BlockMaterialData blockMaterial, Player player);

  void resetDimension(World world);

  void cleanupWorld(World world);

  void cleanupPlayer(Player player);

  double getTPS();

  void postToMainThread(Plugin plugin, boolean priority, Runnable task);

  int getMaxWorldSize(World world);

  /**
   * (Best-effort) nudge for the server's fluid tick scheduler to re-run updates at/around the given
   * block position.
   *
   * <p>This is useful when a plugin cancels flow/physics events and wants fluids to resume
   * immediately after the cancellation stops.
   */
  void scheduleFluidTick(Block block);

  int allocateEntityId();

  /**
   * Spawn a short-lived burst of item entities that are only visible to {@code viewer}.
   *
   * <p>This is used for cosmetic "reward" effects; items are client-side only and therefore
   * unpickable. The caller is responsible for destroying them (e.g. after ~20 ticks).
   *
   * @return the spawned client-side entity IDs (may be empty on unsupported platforms)
   */
  default int[] spawnClientSideItemBurst(
      Player viewer, Location center, ItemStack item, int count, double radius, double speed) {
    return new int[0];
  }
}

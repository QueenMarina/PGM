package tc.oc.pgm.pause;

import static tc.oc.pgm.util.Assert.assertNotNull;
import static tc.oc.pgm.util.nms.NMSHacks.NMS_HACKS;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchModule;
import tc.oc.pgm.api.match.MatchScope;
import tc.oc.pgm.api.party.Competitor;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.events.PlayerJoinMatchEvent;
import tc.oc.pgm.util.nms.PlayerUtils;
import tc.oc.pgm.util.bukkit.Sounds;
import tc.oc.pgm.util.material.Materials;

/**
 * A match-level pause that freezes match time (tick/clock) and cancels the most important gameplay
 * updates (damage, player movement/dis-mount, fluid/physics updates).
 *
 * <p>This cannot fully stop Minecraft world ticks. Instead it tries to approximate "tick freeze"
 * for gameplay-affecting systems within the match world.
 */
public class PauseMatchModule implements MatchModule, Listener {

  private static final int RESUME_COUNTDOWN_SECONDS = 5;
  private static final int REQUEST_EXPIRY_SECONDS = 15;

  private final Match match;
  private final Set<UUID> frozenByPause = new HashSet<>();
  private final Map<Player, FlightState> flightByPause = new WeakHashMap<>();
  private final Set<BlockPos> physicsWake = new HashSet<>();
  private final Set<ChunkPos> fluidWakeChunks = new HashSet<>();
  private final Map<Player, EffectsState> effectsByPause = new WeakHashMap<>();

  private final Map<String, TimedRequest> pauseRequests = new HashMap<>();
  private final Map<String, TimedRequest> resumeRequests = new HashMap<>();
  private BukkitTask resumeCountdownTask;

  private boolean paused;

  public PauseMatchModule(Match match) {
    this.match = assertNotNull(match);
  }

  @Override
  public void load() {
    // Pausing is only meaningful in-match, but listeners can safely exist once the world is loaded.
    match.addListener(this, MatchScope.LOADED);
  }

  @Override
  public void disable() {
    // Ensure a clean shutdown.
    resume();
  }

  public boolean isPaused() {
    return paused;
  }

  public void pause() {
    if (paused) return;
    paused = true;

    match.getClock().pause();
    physicsWake.clear();
    fluidWakeChunks.clear();
    clearPauseRequests();
    clearResumeRequests();
    cancelResumeCountdown();

    for (MatchPlayer player : match.getPlayers()) {
      enableFlightWhilePaused(player.getBukkit());
      snapshotEffectsWhilePaused(player.getBukkit());
      if (!player.isFrozen()) {
        player.setFrozen(true);
        frozenByPause.add(player.getId());
      }
    }

    // Some effects (e.g., eating a golden apple) can be applied slightly after the command
    // executes in the same tick; refresh once next tick to avoid snapshotting "too early".
    Bukkit.getScheduler().runTask(PGM.get(), () -> {
      if (!paused) return;
      for (MatchPlayer player : match.getPlayers()) {
        refreshEffectsSnapshotWhilePaused(player.getBukkit());
      }
    });
  }

  public void resume() {
    if (!paused) return;
    paused = false;

    match.getClock().resume();
    clearPauseRequests();
    clearResumeRequests();
    cancelResumeCountdown();

    for (UUID playerId : frozenByPause) {
      MatchPlayer player = match.getPlayer(playerId);
      if (player != null && player.isFrozen()) {
        player.setFrozen(false);
      }
    }
    frozenByPause.clear();

    restoreFlightAfterResume();
    restoreEffectsAfterResume();
    wakeWorldPhysics();
  }

  public boolean requestPause(Competitor competitor) {
    if (paused) return false;
    if (competitor == null) return false;

    final String competitorId = competitor.getId();
    if (pauseRequests.containsKey(competitorId)) return false;

    // Pause requests expire so teams can't "bank" consent indefinitely.
    final BukkitTask expiryTask =
        Bukkit.getScheduler()
            .runTaskLater(
                PGM.get(), () -> expirePauseRequest(competitorId), REQUEST_EXPIRY_SECONDS * 20L);
    pauseRequests.put(competitorId, new TimedRequest(competitor.getName(), expiryTask));

    Set<String> required = activeCompetitorIds();
    if (!required.isEmpty() && pauseRequests.keySet().containsAll(required)) {
      pause();
      match.sendMessage(Component.text("Match paused."));
      return true;
    }

    int needed = Math.max(1, required.size());
    match.sendMessage(
        Component.text()
            .append(competitor.getName())
            .append(
                Component.text(
                    " requested a pause. Requests are valid for "
                        + REQUEST_EXPIRY_SECONDS
                        + " seconds and will expire if not accepted. "))
            .append(Component.text("(" + pauseRequests.size() + "/" + needed + ")"))
            .build());
    return false;
  }

  public boolean requestResume(Competitor competitor) {
    if (!paused) return false;
    if (competitor == null) return false;
    if (resumeCountdownTask != null) return false;

    final String competitorId = competitor.getId();
    if (resumeRequests.containsKey(competitorId)) return false;

    final BukkitTask expiryTask =
        Bukkit.getScheduler()
            .runTaskLater(
                PGM.get(), () -> expireResumeRequest(competitorId), REQUEST_EXPIRY_SECONDS * 20L);
    resumeRequests.put(competitorId, new TimedRequest(competitor.getName(), expiryTask));

    Set<String> required = activeCompetitorIds();
    if (!required.isEmpty() && resumeRequests.keySet().containsAll(required)) {
      clearResumeRequests(); // prevent expiry messages during countdown
      startResumeCountdown(
          Component.text(
              "Both teams agreed. Resuming in " + RESUME_COUNTDOWN_SECONDS + " seconds..."));
      return true;
    }

    int needed = Math.max(1, required.size());
    match.sendMessage(
        Component.text()
            .append(competitor.getName())
            .append(
                Component.text(
                    " requested to resume. Requests are valid for "
                        + REQUEST_EXPIRY_SECONDS
                        + " seconds and will expire if not accepted. "))
            .append(Component.text("(" + resumeRequests.size() + "/" + needed + ")"))
            .build());
    return false;
  }

  public void startResumeCountdown(Component initialMessage) {
    if (resumeCountdownTask != null) return;

    if (initialMessage != null) {
      match.sendMessage(initialMessage);
      match.playSound(Sounds.MATCH_COUNTDOWN);
    }

    final int[] seconds = new int[] {RESUME_COUNTDOWN_SECONDS};
    resumeCountdownTask = Bukkit.getScheduler().runTaskTimer(PGM.get(), () -> {
      if (!paused) {
        cancelResumeCountdown();
        return;
      }

      if (seconds[0] <= 0) {
        cancelResumeCountdown();
        match.playSound(Sounds.MATCH_START);
        resume();
        return;
      }

      match.sendMessage(Component.text("Resuming in " + seconds[0] + "..."));
      match.playSound(Sounds.MATCH_COUNTDOWN);
      seconds[0]--;
    }, 0L, 20L);
  }

  private void cancelResumeCountdown() {
    if (resumeCountdownTask != null) {
      resumeCountdownTask.cancel();
      resumeCountdownTask = null;
    }
  }

  public boolean hasPauseRequest(Competitor competitor) {
    return competitor != null && pauseRequests.containsKey(competitor.getId());
  }

  public boolean hasResumeRequest(Competitor competitor) {
    return competitor != null && resumeRequests.containsKey(competitor.getId());
  }

  public boolean isResumeCountdownRunning() {
    return resumeCountdownTask != null;
  }

  private void expirePauseRequest(String competitorId) {
    final TimedRequest req = pauseRequests.remove(competitorId);
    if (req == null) return;
    if (paused) return;

    match.sendMessage(
        Component.text()
            .append(req.requesterName)
            .append(
                Component.text(
                    " pause request expired (not accepted within "
                        + REQUEST_EXPIRY_SECONDS
                        + " seconds)."))
            .build());
  }

  private void expireResumeRequest(String competitorId) {
    final TimedRequest req = resumeRequests.remove(competitorId);
    if (req == null) return;
    if (!paused) return;
    if (resumeCountdownTask != null) return;

    match.sendMessage(
        Component.text()
            .append(req.requesterName)
            .append(
                Component.text(
                    " resume request expired (not accepted within "
                        + REQUEST_EXPIRY_SECONDS
                        + " seconds)."))
            .build());
  }

  private void clearPauseRequests() {
    for (TimedRequest req : pauseRequests.values()) {
      if (req.expiryTask != null) req.expiryTask.cancel();
    }
    pauseRequests.clear();
  }

  private void clearResumeRequests() {
    for (TimedRequest req : resumeRequests.values()) {
      if (req.expiryTask != null) req.expiryTask.cancel();
    }
    resumeRequests.clear();
  }

  private static final class TimedRequest {
    private final Component requesterName;
    private final BukkitTask expiryTask;

    private TimedRequest(Component requesterName, BukkitTask expiryTask) {
      this.requesterName = requesterName;
      this.expiryTask = expiryTask;
    }
  }

  private Set<String> activeCompetitorIds() {
    Set<String> ids = new HashSet<>();
    for (MatchPlayer player : match.getParticipants()) {
      Competitor c = player.getCompetitor();
      if (c != null) ids.add(c.getId());
    }
    return ids;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onJoin(final PlayerJoinMatchEvent event) {
    if (!paused) return;
    if (event.getMatch() != match) return;

    MatchPlayer player = event.getPlayer();
    enableFlightWhilePaused(player.getBukkit());
    snapshotEffectsWhilePaused(player.getBukkit());
    if (!player.isFrozen()) {
      player.setFrozen(true);
      frozenByPause.add(player.getId());
    }
  }

  private void enableFlightWhilePaused(Player bukkit) {
    if (!paused || bukkit == null) return;
    if (flightByPause.containsKey(bukkit)) return;

    // Avoid the common Spigot/Paper kick: "Flying is not enabled on this server" while frozen.
    flightByPause.put(
        bukkit, new FlightState(bukkit.getAllowFlight(), bukkit.isFlying(), bukkit.getFallDistance()));
    bukkit.setAllowFlight(true);
    bukkit.setFlying(false);
  }

  private void restoreFlightAfterResume() {
    for (Map.Entry<Player, FlightState> entry : flightByPause.entrySet()) {
      Player player = entry.getKey();
      if (player == null) continue;

      FlightState state = entry.getValue();
      player.setFallDistance(state.fallDistance);
      player.setFlying(state.flying);
      player.setAllowFlight(state.allowFlight);
    }
    flightByPause.clear();
  }

  private record FlightState(boolean allowFlight, boolean flying, float fallDistance) {}

  private record EffectsState(Map<PotionEffectType, PotionEffect> effects, double absorption) {}

  private void snapshotEffectsWhilePaused(Player bukkit) {
    if (!paused || bukkit == null) return;
    if (effectsByPause.containsKey(bukkit)) return;

    // Preserve effect durations and absorption hearts across pause.
    effectsByPause.put(bukkit, snapshotCurrentEffectsState(bukkit));
  }

  private void refreshEffectsSnapshotWhilePaused(Player bukkit) {
    if (!paused || bukkit == null) return;
    effectsByPause.put(bukkit, snapshotCurrentEffectsState(bukkit));
  }

  private EffectsState snapshotCurrentEffectsState(Player bukkit) {
    Map<PotionEffectType, PotionEffect> effects = new HashMap<>();
    for (PotionEffect effect : new ArrayList<>(bukkit.getActivePotionEffects())) {
      PotionEffectType type = effect.getType();
      if (type != null) effects.put(type, effect);
    }
    return new EffectsState(effects, PlayerUtils.PLAYER_UTILS.getAbsorption(bukkit));
  }

  private void restoreEffectsAfterResume() {
    for (Map.Entry<Player, EffectsState> entry : effectsByPause.entrySet()) {
      Player player = entry.getKey();
      if (player == null) continue;

      EffectsState state = entry.getValue();

      // Do not blanket-clear current effects; that can wipe effects gained right before pausing
      // if we snapshotted too early. Instead, only "restore up" effects we captured.
      for (PotionEffect effect : state.effects.values()) {
        PotionEffectType type = effect.getType();
        if (type == null) continue;

        PotionEffect current = getCurrentEffect(player, type);
        if (current == null
            || current.getAmplifier() != effect.getAmplifier()
            || current.getDuration() < effect.getDuration()) {
          player.addPotionEffect(effect, true);
        }
      }

      // Re-applying Absorption effects can refill hearts; preserve the exact amount they had when
      // paused (including 0 if it was already depleted).
      PlayerUtils.PLAYER_UTILS.setAbsorption(player, state.absorption);
    }
    effectsByPause.clear();
  }

  private PotionEffect getCurrentEffect(Player player, PotionEffectType type) {
    for (PotionEffect effect : player.getActivePotionEffects()) {
      if (type.equals(effect.getType())) return effect;
    }
    return null;
  }

  private record BlockPos(int x, int y, int z) {
    private static BlockPos of(Block block) {
      return new BlockPos(block.getX(), block.getY(), block.getZ());
    }
  }

  private record ChunkPos(int x, int z) {
    private static ChunkPos of(Block block) {
      return new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
    }
  }

  private boolean isMatchWorld(World world) {
    return world != null && world.equals(match.getWorld());
  }

  private boolean isPausedMatchPlayer(Player bukkit) {
    if (!paused || bukkit == null || !isMatchWorld(bukkit.getWorld())) return false;
    return match.getPlayer(bukkit) != null;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDamage(final EntityDamageEvent event) {
    if (!paused) return;
    if (event.getEntity() instanceof Player player && isPausedMatchPlayer(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onHunger(final FoodLevelChangeEvent event) {
    if (!paused) return;
    if (event.getEntity() instanceof Player player && isPausedMatchPlayer(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onRegainHealth(final EntityRegainHealthEvent event) {
    if (!paused) return;
    if (event.getEntity() instanceof Player player && isPausedMatchPlayer(player)) {
      // Prevent regen (and other heals) from ticking while paused.
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onVehicleEnter(final VehicleEnterEvent event) {
    if (!paused) return;
    if (event.getEntered() instanceof Player player && isPausedMatchPlayer(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onVehicleExit(final VehicleExitEvent event) {
    if (!paused) return;
    if (event.getExited() instanceof Player player && isPausedMatchPlayer(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onSneak(final PlayerToggleSneakEvent event) {
    if (!isPausedMatchPlayer(event.getPlayer())) return;
    // Avoid dismounting from the freeze entity while paused.
    if (event.isSneaking()) event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteractEntity(final PlayerInteractEntityEvent event) {
    if (!isPausedMatchPlayer(event.getPlayer())) return;
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteract(final PlayerInteractEvent event) {
    if (!isPausedMatchPlayer(event.getPlayer())) return;
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteractAtEntity(final PlayerInteractAtEntityEvent event) {
    if (!isPausedMatchPlayer(event.getPlayer())) return;
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBreak(final BlockBreakEvent event) {
    if (!isPausedMatchPlayer(event.getPlayer())) return;
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockPlace(final BlockPlaceEvent event) {
    if (!isPausedMatchPlayer(event.getPlayer())) return;
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onFluidFlow(final BlockFromToEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    physicsWake.add(BlockPos.of(event.getToBlock()));
    fluidWakeChunks.add(ChunkPos.of(event.getBlock()));
    fluidWakeChunks.add(ChunkPos.of(event.getToBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPhysics(final BlockPhysicsEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onSpread(final BlockSpreadEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onForm(final BlockFormEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onFade(final BlockFadeEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onGrow(final BlockGrowEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBurn(final BlockBurnEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onIgnite(final BlockIgniteEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPistonExtend(final BlockPistonExtendEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPistonRetract(final BlockPistonRetractEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onRedstone(final BlockRedstoneEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    physicsWake.add(BlockPos.of(event.getBlock()));
    event.setNewCurrent(event.getOldCurrent());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onExplode(final EntityExplodeEvent event) {
    if (!paused || !isMatchWorld(event.getLocation().getWorld())) return;
    event.blockList().clear();
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
    if (!paused || !isMatchWorld(event.getBlock().getWorld())) return;
    Entity entity = event.getEntity();
    if (entity instanceof FallingBlock) {
      physicsWake.add(BlockPos.of(event.getBlock()));
      event.setCancelled(true);
    }
  }

  private void wakeWorldPhysics() {
    if (physicsWake.isEmpty() && fluidWakeChunks.isEmpty()) return;

    // If we cancel physics/fluid events while paused, some updates never get re-scheduled.
    // Nudging affected blocks on resume approximates the "neighbor update" that would restart flow.
    if (!physicsWake.isEmpty()) {
      Bukkit.getScheduler().runTaskLater(PGM.get(), () -> wakeWorldPhysicsOnce(physicsWake), 1L);
      Bukkit.getScheduler().runTaskLater(PGM.get(), () -> wakeWorldPhysicsOnce(physicsWake), 10L);
      Bukkit.getScheduler().runTaskLater(PGM.get(), physicsWake::clear, 11L);
    }

    // Fluids rely heavily on scheduled ticks; cancelling flow can leave them "dormant" until a
    // neighbor update happens. Re-schedule fluid ticks for any chunks where flow was cancelled.
    if (!fluidWakeChunks.isEmpty()) {
      Bukkit.getScheduler().runTaskLater(PGM.get(), () -> kickFluidTicks(fluidWakeChunks), 1L);
      Bukkit.getScheduler().runTaskLater(PGM.get(), () -> kickFluidTicks(fluidWakeChunks), 10L);
      Bukkit.getScheduler().runTaskLater(PGM.get(), fluidWakeChunks::clear, 11L);
    }
  }

  private void wakeWorldPhysicsOnce(Set<BlockPos> positions) {
    if (paused || positions.isEmpty()) return;

    World world = match.getWorld();
    for (BlockPos pos : positions) {
      Block block = world.getBlockAt(pos.x, pos.y, pos.z);
      block.getState().update(true, true);

      // Liquids often need a neighbor "poke" to restart after we cancelled their scheduled flow.
      if (isLiquid(block.getType())) {
        NMS_HACKS.scheduleFluidTick(block);
        for (BlockFace face :
            new BlockFace[] {
              BlockFace.DOWN,
              BlockFace.UP,
              BlockFace.NORTH,
              BlockFace.SOUTH,
              BlockFace.EAST,
              BlockFace.WEST
            }) {
          Block neighbor = block.getRelative(face);
          neighbor.getState().update(true, true);
          NMS_HACKS.scheduleFluidTick(neighbor);
        }
      }
    }
  }

  private void kickFluidTicks(Set<ChunkPos> chunks) {
    if (paused || chunks.isEmpty()) return;

    World world = match.getWorld();
    for (ChunkPos pos : chunks) {
      if (!world.isChunkLoaded(pos.x, pos.z)) continue;
      Chunk chunk = world.getChunkAt(pos.x, pos.z);

      kickFluidTicksInChunk(chunk, Material.WATER);
      kickFluidTicksInChunk(chunk, Materials.STILL_WATER);
      kickFluidTicksInChunk(chunk, Material.LAVA);
      kickFluidTicksInChunk(chunk, Materials.STILL_LAVA);
    }
  }

  private void kickFluidTicksInChunk(Chunk chunk, Material material) {
    if (material == null) return;
    for (Block block : NMS_HACKS.getBlocks(chunk, material)) {
      NMS_HACKS.scheduleFluidTick(block);
    }
  }

  private boolean isLiquid(Material material) {
    return material == Material.WATER
        || material == Materials.STILL_WATER
        || material == Material.LAVA
        || material == Materials.STILL_LAVA;
  }
}

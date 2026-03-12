package tc.oc.pgm.raindrops;

import static net.kyori.adventure.text.Component.text;
import static tc.oc.pgm.util.nms.NMSHacks.NMS_HACKS;
import static tc.oc.pgm.util.nms.Packets.ENTITIES;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchModule;
import tc.oc.pgm.api.match.MatchScope;
import tc.oc.pgm.api.match.event.MatchFinishEvent;
import tc.oc.pgm.api.match.event.MatchStartEvent;
import tc.oc.pgm.api.party.Competitor;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.player.MatchPlayerState;
import tc.oc.pgm.api.player.ParticipantState;
import tc.oc.pgm.core.Core;
import tc.oc.pgm.core.CoreBlockBreakEvent;
import tc.oc.pgm.destroyable.DestroyableDestroyedEvent;
import tc.oc.pgm.destroyable.DestroyableHealthChange;
import tc.oc.pgm.destroyable.DestroyableHealthChangeEvent;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.flag.Flag;
import tc.oc.pgm.flag.event.FlagCaptureEvent;
import tc.oc.pgm.goals.Contribution;
import tc.oc.pgm.goals.events.GoalCompleteEvent;
import tc.oc.pgm.goals.events.GoalTouchEvent;
import tc.oc.pgm.api.player.event.MatchPlayerDeathEvent;
import tc.oc.pgm.wool.MonumentWool;
import tc.oc.pgm.wool.PlayerWoolPlaceEvent;

/**
 * Awards raindrops for common match actions, and stores balances globally.
 *
 * <p>Messages are sent only to the awarded player.
 */
@ListenerScope(MatchScope.LOADED)
public final class RaindropsMatchModule implements MatchModule, Listener {

  private static final int KILL = 5;
  private static final int ASSIST = 2;
  private static final int CORE_MONUMENT_HIT = 5;
  private static final int CORE_MONUMENT_DESTROYED = 25;
  private static final int OBJECTIVE_CAPTURE = 15;
  private static final int OBJECTIVE_CAPTURE_ASSIST = 7;
  private static final int MATCH_WIN = 20;

  // Periodic participation awards
  private static final int PARTICIPATION_MINUTES = 5;
  private static final int PARTICIPATION_MIN = 1;
  private static final int PARTICIPATION_MAX = 3;

  private static final int EFFECT_ITEMS = 8;
  private static final int EFFECT_LIFETIME_TICKS = 20;
  private static final double EFFECT_RADIUS = 0.65;
  private static final double EFFECT_SPEED = 0.35;
  private static final ItemStack EFFECT_STACK = new ItemStack(Material.GHAST_TEAR);

  private final Match match;
  private BukkitTask participationTask;
  private final Map<UUID, Set<String>> oneTimeAwarded = new HashMap<>();

  public RaindropsMatchModule(Match match) {
    this.match = match;
  }

  @Override
  public void disable() {
    cancelParticipationTask();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onMatchStart(MatchStartEvent event) {
    startParticipationTask();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onMatchFinish(MatchFinishEvent event) {
    cancelParticipationTask();

    // Team win reward
    Set<String> winnerIds = new HashSet<>();
    for (Competitor winner : event.getWinners()) {
      winnerIds.add(winner.getId());
    }
    if (winnerIds.isEmpty()) return;
    for (MatchPlayer player : match.getParticipants()) {
      Competitor c = player.getCompetitor();
      if (c != null && winnerIds.contains(c.getId())) {
        award(player, MATCH_WIN, "match win");
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDeath(MatchPlayerDeathEvent event) {
    if (event.isPredicted()) return;
    if (!event.isChallengeKill()) return;

    final Location victimLoc = safeLocation(event.getVictim());

    ParticipantState killer = event.getKiller();
    if (killer != null) {
      MatchPlayer mp = killer.getPlayer().orElse(null);
      if (mp != null) awardNextTick(mp, KILL, "kill", victimLoc);
    }

    if (event.isChallengeAssist()) {
      ParticipantState assister = event.getAssister();
      if (assister != null) {
        MatchPlayer mp = assister.getPlayer().orElse(null);
        if (mp != null) awardNextTick(mp, ASSIST, "assist", victimLoc);
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCoreHit(CoreBlockBreakEvent event) {
    MatchPlayer mp = event.getPlayer().getPlayer().orElse(null);
    // One-time per core per player per match: first damage only.
    if (mp != null && shouldAwardOneTime(mp.getId(), "core:" + event.getCore().getId()))
      award(mp, CORE_MONUMENT_HIT, "core hit");
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDestroyableHit(DestroyableHealthChangeEvent event) {
    DestroyableHealthChange change = event.getChange();
    if (change == null || change.getHealthChange() >= 0) return;

    ParticipantState cause = change.getPlayerCause();
    if (cause == null) return;
    MatchPlayer mp = cause.getPlayer().orElse(null);
    // One-time per monument per player per match: first damage only.
    if (mp != null && shouldAwardOneTime(mp.getId(), "destroyable:" + event.getDestroyable().getId()))
      award(mp, CORE_MONUMENT_HIT, "monument hit");
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onDestroyableDestroyed(DestroyableDestroyedEvent event) {
    Set<MatchPlayerState> credited = new HashSet<>();
    event
        .getDestroyable()
        .getContributions()
        .forEach(
            contrib -> {
              MatchPlayerState ps = contrib.getPlayerState();
              if (ps != null) credited.add(ps);
            });

    for (MatchPlayerState ps : credited) {
      MatchPlayer mp = ps.getPlayer().orElse(null);
      if (mp != null) award(mp, CORE_MONUMENT_DESTROYED, "monument destroyed");
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onCoreDestroyed(GoalCompleteEvent event) {
    if (!(event.getGoal() instanceof Core)) return;
    // Core completion is "bad" for the owner, so treat it as destroyed for attackers.
    if (event.isGood()) return;

    Set<MatchPlayerState> credited = new HashSet<>();
    for (Contribution c : event.getContributions()) {
      credited.add(c.getPlayerState());
    }
    for (MatchPlayerState ps : credited) {
      MatchPlayer mp = ps.getPlayer().orElse(null);
      if (mp != null) award(mp, CORE_MONUMENT_DESTROYED, "core destroyed");
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onWoolCapture(PlayerWoolPlaceEvent event) {
    ParticipantState placer = event.getPlayer();
    if (placer == null) return;
    MatchPlayer placerMp = placer.getPlayer().orElse(null);
    if (placerMp == null) return;

    award(placerMp, OBJECTIVE_CAPTURE, "capture");
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onFlagCapture(FlagCaptureEvent event) {
    MatchPlayer carrier = event.getCarrier();
    if (carrier == null) return;
    award(carrier, OBJECTIVE_CAPTURE, "capture");
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onObjectiveTouch(GoalTouchEvent event) {
    if (!match.isRunning()) return;
    if (!event.isFirstForPlayerLife()) return;
    ParticipantState toucher = event.getPlayer();
    if (toucher == null) return;

    MatchPlayer mp = toucher.getPlayer().orElse(null);
    if (mp == null) return;

    if (event.getGoal() instanceof Flag) {
      awardNextTick(mp, OBJECTIVE_CAPTURE_ASSIST, "flag pickup", safeLocation(mp));
    } else if (event.getGoal() instanceof MonumentWool) {
      awardNextTick(mp, OBJECTIVE_CAPTURE_ASSIST, "wool touch", safeLocation(mp));
    }
  }

  private void startParticipationTask() {
    cancelParticipationTask();
    participationTask =
        Bukkit.getScheduler()
            .runTaskTimer(
                tc.oc.pgm.api.PGM.get(),
                () -> {
                  if (!match.isRunning()) return;
                  if (match.getClock().isPaused()) return;
                  for (MatchPlayer player : match.getParticipants()) {
                    int amount =
                        ThreadLocalRandom.current()
                            .nextInt(PARTICIPATION_MIN, PARTICIPATION_MAX + 1);
                    // Participation rewards are intentionally less flashy.
                    award(player, amount, "participation", null);
                  }
                },
                20L * 60L * PARTICIPATION_MINUTES,
                20L * 60L * PARTICIPATION_MINUTES);
  }

  private void cancelParticipationTask() {
    if (participationTask != null) {
      participationTask.cancel();
      participationTask = null;
    }
  }

  private void award(MatchPlayer player, int amount, String reason) {
    award(player, amount, reason, safeLocation(player));
  }

  private void award(MatchPlayer player, int amount, String reason, Location effectLocation) {
    if (amount <= 0) return;
    RaindropsService.get().add(player.getId(), amount);
    player.sendMessage(formatMessage(amount, reason));
    playEffect(player, effectLocation);
  }

  private void awardNextTick(MatchPlayer player, int amount, String reason, Location effectLocation) {
    Bukkit.getScheduler()
        .runTask(tc.oc.pgm.api.PGM.get(), () -> award(player, amount, reason, effectLocation));
  }

  private boolean shouldAwardOneTime(UUID playerId, String key) {
    Set<String> set = oneTimeAwarded.computeIfAbsent(playerId, k -> new HashSet<>());
    return set.add(key);
  }

  private static Location safeLocation(MatchPlayer player) {
    if (player == null) return null;
    var bukkit = player.getBukkit();
    if (bukkit == null) return null;
    return bukkit.getLocation().clone();
  }

  private void playEffect(MatchPlayer awarded, Location location) {
    if (location == null) return;
    if (!RaindropsService.get().isEffectsEnabled(awarded.getId())) return;
    var viewer = awarded.getBukkit();
    if (viewer == null || !viewer.isOnline()) return;

    int[] ids =
        NMS_HACKS.spawnClientSideItemBurst(
            viewer, location, EFFECT_STACK, EFFECT_ITEMS, EFFECT_RADIUS, EFFECT_SPEED);
    if (ids.length == 0) return;

    Bukkit.getScheduler()
        .runTaskLater(
            tc.oc.pgm.api.PGM.get(),
            () -> {
              if (viewer.isOnline()) ENTITIES.destroyEntitiesPacket(ids).send(viewer);
            },
            EFFECT_LIFETIME_TICKS);
  }

  private static Component formatMessage(int amount, String reason) {
    return Component.text()
        .append(text("+" + amount, NamedTextColor.GREEN))
        .append(text(" ", NamedTextColor.GRAY))
        .append(text("raindrops", NamedTextColor.AQUA))
        .append(text(" ", NamedTextColor.GRAY))
        .append(text("|", NamedTextColor.DARK_GRAY))
        .append(text(" ", NamedTextColor.GRAY))
        .append(text(titleCase(reason), NamedTextColor.GRAY))
        .build();
  }

  private static String titleCase(String s) {
    if (s == null) return "";
    String in = s.trim();
    if (in.isEmpty()) return "";
    String[] parts = in.split("\\s+");
    StringBuilder out = new StringBuilder(in.length());
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) out.append(' ');
      String p = parts[i];
      if (p.isEmpty()) continue;
      int cp = p.codePointAt(0);
      out.appendCodePoint(Character.toUpperCase(cp));
      out.append(p.substring(Character.charCount(cp)).toLowerCase(Locale.ROOT));
    }
    return out.toString();
  }
}

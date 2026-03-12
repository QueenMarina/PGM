package tc.oc.pgm.hotbar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchModule;
import tc.oc.pgm.api.match.MatchScope;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.spawns.events.ParticipantKitApplyEvent;
import tc.oc.pgm.util.material.Materials;

/**
 * Persist and re-apply per-player hotbar layouts for each map.
 *
 * <p>After kits are applied on spawn/respawn, this module reorders the hotbar based on the saved
 * layout for the current map. On death, it records the current hotbar order (restricted to items
 * that existed in the spawn kit hotbar) and persists it for next respawns and future plays of the
 * map.
 */
@ListenerScope(MatchScope.LOADED)
public final class HotbarLayoutMatchModule implements MatchModule, Listener {

  private final Match match;
  private final String mapId;

  // Per-player baseline snapshot of hotbar items immediately after kits are applied.
  // Used to ignore non-kit items when persisting layouts.
  private final Map<UUID, List<ItemStack>> baselineHotbar = new HashMap<>();

  public HotbarLayoutMatchModule(Match match) {
    this.match = match;
    this.mapId = match.getMap().getId();
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onParticipantKitApply(ParticipantKitApplyEvent event) {
    MatchPlayer mp = event.getPlayer();
    Player player = mp.getBukkit();
    if (player == null) return;
    if (!HotbarLayoutService.get().isGlobalEnabled()
        || !HotbarLayoutService.get().isPreserveEnabled(mp.getId())) {
      baselineHotbar.remove(mp.getId());
      return;
    }

    PlayerInventory inv = player.getInventory();
    Map<Integer, ItemStack> saved = HotbarLayoutService.get().getLayout(mapId, mp.getId());
    if (!saved.isEmpty()) applyLayout(inv, saved);

    // Capture a baseline snapshot after all kit application handlers have run.
    baselineHotbar.put(mp.getId(), snapshotHotbar(inv));
  }

  // Must run before DeathTracker wraps PlayerDeathEvent into MatchPlayerDeathEvent, because match
  // death handling transitions to Dead state and clears inventories.
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onVanillaDeath(PlayerDeathEvent event) {
    MatchPlayer victim = match.getParticipant(event.getEntity());
    if (victim == null) return;
    Player player = victim.getBukkit();
    if (player == null) return;
    if (!HotbarLayoutService.get().isGlobalEnabled()
        || !HotbarLayoutService.get().isPreserveEnabled(victim.getId())) return;

    List<ItemStack> baseline = baselineHotbar.get(victim.getId());
    if (baseline == null || baseline.isEmpty()) return;

    PlayerInventory inv = player.getInventory();
    Map<Integer, ItemStack> found = captureLayout(inv, baseline);
    if (found.isEmpty()) return;

    // Keep any previously stored preferences for baseline kit items that weren't present in the
    // hotbar at time of death (e.g. a player dropped an item but still wants their layout).
    Map<Integer, ItemStack> prev = HotbarLayoutService.get().getLayout(mapId, victim.getId());
    Map<Integer, ItemStack> merged = new HashMap<>(found);
    if (!prev.isEmpty()) {
      for (Map.Entry<Integer, ItemStack> e : prev.entrySet()) {
        int slot = e.getKey();
        if (merged.containsKey(slot)) continue;
        ItemStack key = e.getValue();
        if (!matchesAny(baseline, key)) continue;
        if (matchesAny(merged.values(), key)) continue;
        merged.put(slot, key);
      }
    }

    HotbarLayoutService.get().setLayout(mapId, victim.getId(), merged);
  }

  private static void applyLayout(PlayerInventory inv, Map<Integer, ItemStack> layout) {
    ItemStack[] current = new ItemStack[9];
    for (int i = 0; i < 9; i++) current[i] = inv.getItem(i);

    boolean[] used = new boolean[9];
    ItemStack[] desired = new ItemStack[9];

    for (int target = 0; target < 9; target++) {
      ItemStack wanted = layout.get(target);
      if (wanted == null) continue;
      int found = -1;
      for (int i = 0; i < 9; i++) {
        if (used[i]) continue;
        if (current[i] == null || current[i].getType() == Material.AIR) continue;
        if (matchesForLayout(current[i], wanted)) {
          found = i;
          break;
        }
      }
      if (found != -1) {
        desired[target] = current[found];
        used[found] = true;
      }
    }

    int next = 0;
    for (int i = 0; i < 9; i++) {
      if (used[i]) continue;
      while (next < 9 && desired[next] != null) next++;
      if (next >= 9) break;
      desired[next] = current[i];
    }

    for (int i = 0; i < 9; i++) inv.setItem(i, desired[i]);
  }

  private static List<ItemStack> snapshotHotbar(PlayerInventory inv) {
    List<ItemStack> out = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      ItemStack it = inv.getItem(i);
      if (it == null || it.getType() == Material.AIR) continue;
      out.add(stripAmount(it));
    }
    return out;
  }

  private static Map<Integer, ItemStack> captureLayout(PlayerInventory inv, List<ItemStack> baseline) {
    List<ItemStack> remaining = new ArrayList<>(baseline);
    Map<Integer, ItemStack> out = new HashMap<>();

    for (int slot = 0; slot < 9; slot++) {
      ItemStack it = inv.getItem(slot);
      if (it == null || it.getType() == Material.AIR) continue;

      int idx = findSimilarIndex(remaining, it);
      if (idx == -1) continue;
      remaining.remove(idx);
      out.put(slot, stripAmount(it));
    }

    return out;
  }

  private static int findSimilarIndex(List<ItemStack> list, ItemStack query) {
    for (int i = 0; i < list.size(); i++) {
      if (matchesForLayout(list.get(i), query)) return i;
    }
    return -1;
  }

  private static boolean matchesForLayout(ItemStack a, ItemStack b) {
    if (a == null || b == null) return false;
    if (a.getType() == Material.AIR || b.getType() == Material.AIR) return false;
    boolean skipDurability = a.getType().getMaxDurability() > 0;
    return Materials.itemsSimilarMaterial(a, b, skipDurability) && Materials.itemsSimilarMeta(a, b);
  }

  private static ItemStack stripAmount(ItemStack it) {
    ItemStack key = it.clone();
    key.setAmount(1);
    return key;
  }

  private static boolean matchesAny(Iterable<ItemStack> items, ItemStack query) {
    if (query == null) return false;
    for (ItemStack it : items) {
      if (matchesForLayout(it, query)) return true;
    }
    return false;
  }
}

package tc.oc.pgm.hotbar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import tc.oc.pgm.api.PGM;

/**
 * Persistent per-player, per-map hotbar layout preferences.
 *
 * <p>Stored in {@code hotbar-layouts.yml} under {@code layouts.<mapId>.<uuid>.<slot>}.
 */
public final class HotbarLayoutService {

  private static volatile HotbarLayoutService INSTANCE;

  public static HotbarLayoutService init(PGM plugin) {
    HotbarLayoutService service = new HotbarLayoutService(plugin);
    service.load();
    service.ensureFileExists();
    INSTANCE = service;
    return service;
  }

  public static HotbarLayoutService get() {
    HotbarLayoutService s = INSTANCE;
    if (s == null) throw new IllegalStateException("HotbarLayoutService not initialized");
    return s;
  }

  private final PGM plugin;
  private final File file;

  // mapId -> player uuid -> slot -> ItemStack (amount stripped to 1)
  private final Map<String, Map<UUID, Map<Integer, ItemStack>>> layouts = new HashMap<>();
  // Default is enabled; only store explicit opt-outs.
  private final Set<UUID> preserveDisabled = new HashSet<>();
  // Global gate. Default is disabled.
  private boolean globalEnabled;

  private boolean dirty;
  private BukkitTask saveTask;

  private HotbarLayoutService(PGM plugin) {
    this.plugin = plugin;
    this.file = new File(plugin.getDataFolder(), "hotbar-layouts.yml");
  }

  private void ensureFileExists() {
    if (file.exists()) return;
    file.getParentFile().mkdirs();
    try {
      // Create an empty file so server owners can easily find it.
      new YamlConfiguration().save(file);
    } catch (IOException ignored) {
      // no-op
    }
  }

  public synchronized Map<Integer, ItemStack> getLayout(String mapId, UUID playerId) {
    if (mapId == null || playerId == null) return Collections.emptyMap();
    Map<UUID, Map<Integer, ItemStack>> byPlayer = layouts.get(mapId);
    if (byPlayer == null) return Collections.emptyMap();
    Map<Integer, ItemStack> layout = byPlayer.get(playerId);
    if (layout == null || layout.isEmpty()) return Collections.emptyMap();
    return new HashMap<>(layout);
  }

  public synchronized void setLayout(String mapId, UUID playerId, Map<Integer, ItemStack> layout) {
    if (mapId == null || playerId == null || layout == null) return;

    Map<UUID, Map<Integer, ItemStack>> byPlayer =
        layouts.computeIfAbsent(mapId, k -> new HashMap<>());
    Map<Integer, ItemStack> next = new HashMap<>();

    for (Map.Entry<Integer, ItemStack> e : layout.entrySet()) {
      Integer slot = e.getKey();
      ItemStack item = e.getValue();
      if (slot == null || slot < 0 || slot > 8) continue;
      if (item == null) continue;
      ItemStack key = item.clone();
      key.setAmount(1);
      next.put(slot, key);
    }

    byPlayer.put(playerId, next);
    markDirty();
  }

  public synchronized boolean isGlobalEnabled() {
    return globalEnabled;
  }

  public synchronized void setGlobalEnabled(boolean enabled) {
    if (globalEnabled == enabled) return;
    globalEnabled = enabled;
    markDirty();
  }

  public synchronized boolean isPreserveEnabled(UUID playerId) {
    return playerId != null && !preserveDisabled.contains(playerId);
  }

  public synchronized void setPreserveEnabled(UUID playerId, boolean enabled) {
    if (playerId == null) return;
    boolean changed = enabled ? preserveDisabled.remove(playerId) : preserveDisabled.add(playerId);
    if (changed) markDirty();
  }

  private void markDirty() {
    dirty = true;
    if (saveTask != null) return;
    saveTask =
        Bukkit.getScheduler()
            .runTaskLaterAsynchronously(plugin, this::flushIfDirty, 20L * 5);
  }

  private void flushIfDirty() {
    synchronized (this) {
      saveTask = null;
      if (!dirty) return;
      dirty = false;
    }
    save();
  }

  private synchronized void load() {
    layouts.clear();
    preserveDisabled.clear();
    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

    globalEnabled = cfg.getBoolean("settings.globalEnabled", false);

    List<String> disabled = cfg.getStringList("settings.preserveDisabled");
    for (String s : disabled) {
      try {
        preserveDisabled.add(UUID.fromString(s));
      } catch (IllegalArgumentException ignored) {
        // ignore malformed keys
      }
    }

    ConfigurationSection root = cfg.getConfigurationSection("layouts");
    if (root == null) return;

    for (String mapId : root.getKeys(false)) {
      ConfigurationSection mapSec = root.getConfigurationSection(mapId);
      if (mapSec == null) continue;

      Map<UUID, Map<Integer, ItemStack>> byPlayer = new HashMap<>();
      for (String uuidStr : mapSec.getKeys(false)) {
        UUID uuid;
        try {
          uuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException ignored) {
          continue;
        }

        ConfigurationSection playerSec = mapSec.getConfigurationSection(uuidStr);
        if (playerSec == null) continue;

        Map<Integer, ItemStack> layout = new HashMap<>();
        for (String slotKey : playerSec.getKeys(false)) {
          int slot;
          try {
            slot = Integer.parseInt(slotKey);
          } catch (NumberFormatException ignored) {
            continue;
          }
          if (slot < 0 || slot > 8) continue;

          Object raw = playerSec.get(slotKey);
          if (raw instanceof ItemStack stack) {
            ItemStack key = stack.clone();
            key.setAmount(1);
            layout.put(slot, key);
          }
        }

        if (!layout.isEmpty()) byPlayer.put(uuid, layout);
      }

      if (!byPlayer.isEmpty()) layouts.put(mapId, byPlayer);
    }
  }

  private synchronized void save() {
    YamlConfiguration cfg = new YamlConfiguration();
    cfg.set("settings.globalEnabled", globalEnabled);
    if (!preserveDisabled.isEmpty()) {
      List<String> out = new ArrayList<>(preserveDisabled.size());
      for (UUID uuid : preserveDisabled) out.add(uuid.toString());
      Collections.sort(out);
      cfg.set("settings.preserveDisabled", out);
    }
    for (Map.Entry<String, Map<UUID, Map<Integer, ItemStack>>> mapEntry : layouts.entrySet()) {
      String mapId = mapEntry.getKey();
      for (Map.Entry<UUID, Map<Integer, ItemStack>> playerEntry : mapEntry.getValue().entrySet()) {
        String base = "layouts." + mapId + "." + playerEntry.getKey();
        for (Map.Entry<Integer, ItemStack> slotEntry : playerEntry.getValue().entrySet()) {
          cfg.set(base + "." + slotEntry.getKey(), slotEntry.getValue());
        }
      }
    }

    file.getParentFile().mkdirs();
    try {
      cfg.save(file);
    } catch (IOException e) {
      Bukkit.getLogger().warning("Failed to save hotbar-layouts.yml: " + e.getMessage());
    }
  }
}

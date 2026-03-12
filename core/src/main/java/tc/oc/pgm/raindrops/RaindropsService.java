package tc.oc.pgm.raindrops;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import tc.oc.pgm.api.PGM;

/** Simple persistent raindrops balance store backed by {@code raindrops.yml}. */
public final class RaindropsService {

  private static volatile RaindropsService INSTANCE;

  public static RaindropsService init(PGM plugin) {
    RaindropsService service = new RaindropsService(plugin);
    service.load();
    INSTANCE = service;
    return service;
  }

  public static RaindropsService get() {
    RaindropsService s = INSTANCE;
    if (s == null) throw new IllegalStateException("RaindropsService not initialized");
    return s;
  }

  private final PGM plugin;
  private final File file;
  private final Map<UUID, Long> balances = new HashMap<>();
  // Default is "enabled"; only store explicit opt-outs.
  private final Set<UUID> effectsDisabled = new HashSet<>();

  private boolean dirty;
  private BukkitTask saveTask;

  private RaindropsService(PGM plugin) {
    this.plugin = plugin;
    this.file = new File(plugin.getDataFolder(), "raindrops.yml");
  }

  public synchronized long getBalance(UUID uuid) {
    return balances.getOrDefault(uuid, 0L);
  }

  public synchronized long add(UUID uuid, long amount) {
    if (amount == 0) return getBalance(uuid);
    long next = Math.max(0L, getBalance(uuid) + amount);
    balances.put(uuid, next);
    markDirty();
    return next;
  }

  public synchronized boolean isEffectsEnabled(UUID uuid) {
    return uuid != null && !effectsDisabled.contains(uuid);
  }

  public synchronized void setEffectsEnabled(UUID uuid, boolean enabled) {
    if (uuid == null) return;
    boolean changed = enabled ? effectsDisabled.remove(uuid) : effectsDisabled.add(uuid);
    if (changed) markDirty();
  }

  private void markDirty() {
    dirty = true;
    if (saveTask != null) return;
    // Debounce writes: coalesce bursts of awards.
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
    balances.clear();
    effectsDisabled.clear();
    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
    ConfigurationSection sec = cfg.getConfigurationSection("balances");
    if (sec != null) {
      for (String key : sec.getKeys(false)) {
        try {
          UUID uuid = UUID.fromString(key);
          long value = sec.getLong(key, 0L);
          if (value > 0) balances.put(uuid, value);
        } catch (IllegalArgumentException ignored) {
          // ignore malformed keys
        }
      }
    }

    List<String> disabled = cfg.getStringList("effects.disabled");
    for (String key : disabled) {
      try {
        UUID uuid = UUID.fromString(key);
        effectsDisabled.add(uuid);
      } catch (IllegalArgumentException ignored) {
        // ignore malformed keys
      }
    }
  }

  private synchronized void save() {
    YamlConfiguration cfg = new YamlConfiguration();
    for (Map.Entry<UUID, Long> e : balances.entrySet()) {
      cfg.set("balances." + e.getKey(), e.getValue());
    }
    if (!effectsDisabled.isEmpty()) {
      List<String> out = new ArrayList<>(effectsDisabled.size());
      for (UUID uuid : effectsDisabled) out.add(uuid.toString());
      Collections.sort(out);
      cfg.set("effects.disabled", out);
    }
    file.getParentFile().mkdirs();
    try {
      cfg.save(file);
    } catch (IOException e) {
      Bukkit.getLogger().warning("Failed to save raindrops.yml: " + e.getMessage());
    }
  }
}

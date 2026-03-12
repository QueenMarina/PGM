package tc.oc.pgm.namedecorations;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import tc.oc.pgm.api.PGM;

/**
 * Stores custom ranks and player assignments in {@code ranks.yml}.
 *
 * <p>Decorator strings may include legacy color codes using {@code &} (e.g. {@code &a✦}).
 */
public final class CustomRankService {

  private static volatile CustomRankService INSTANCE;

  public static CustomRankService init(PGM plugin) {
    CustomRankService service = new CustomRankService(plugin);
    service.load();
    INSTANCE = service;
    return service;
  }

  public static CustomRankService get() {
    CustomRankService s = INSTANCE;
    if (s == null) throw new IllegalStateException("CustomRankService not initialized");
    return s;
  }

  private final File file;
  private final Map<String, String> ranks = new LinkedHashMap<>();
  private final Map<UUID, LinkedHashSet<String>> playerRanks = new HashMap<>();

  private CustomRankService(PGM plugin) {
    this.file = new File(plugin.getDataFolder(), "ranks.yml");
  }

  public synchronized void load() {
    ranks.clear();
    playerRanks.clear();

    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
    ConfigurationSection rankSec = cfg.getConfigurationSection("ranks");
    if (rankSec != null) {
      for (String key : rankSec.getKeys(false)) {
        String name = normalize(key);
        String deco = rankSec.getString(key + ".decorator", "");
        if (!name.isEmpty()) ranks.put(name, deco == null ? "" : deco);
      }
    }

    ConfigurationSection playerSec = cfg.getConfigurationSection("players");
    if (playerSec != null) {
      for (String uuidKey : playerSec.getKeys(false)) {
        UUID uuid;
        try {
          uuid = UUID.fromString(uuidKey);
        } catch (IllegalArgumentException ignored) {
          continue;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String r : playerSec.getStringList(uuidKey)) {
          String name = normalize(r);
          if (ranks.containsKey(name)) set.add(name);
        }
        if (!set.isEmpty()) playerRanks.put(uuid, set);
      }
    }
  }

  private synchronized void save() {
    YamlConfiguration cfg = new YamlConfiguration();

    for (Map.Entry<String, String> e : ranks.entrySet()) {
      cfg.set("ranks." + e.getKey() + ".decorator", e.getValue());
    }
    for (Map.Entry<UUID, LinkedHashSet<String>> e : playerRanks.entrySet()) {
      cfg.set("players." + e.getKey().toString(), e.getValue().stream().toList());
    }

    file.getParentFile().mkdirs();
    try {
      cfg.save(file);
    } catch (IOException e) {
      // Best-effort; commands will still work in-memory.
    }
  }

  public synchronized Map<String, String> listRanks() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(ranks));
  }

  public synchronized boolean addRank(String name, String decorator) {
    String key = normalize(name);
    if (key.isEmpty() || ranks.containsKey(key)) return false;
    ranks.put(key, decorator == null ? "" : decorator);
    save();
    return true;
  }

  /** @return UUIDs of players affected by the deletion */
  public synchronized Set<UUID> deleteRank(String name) {
    String key = normalize(name);
    if (!ranks.containsKey(key)) return Collections.emptySet();
    ranks.remove(key);

    Set<UUID> affected = new LinkedHashSet<>();
    playerRanks.forEach((uuid, set) -> {
      if (set.remove(key)) affected.add(uuid);
    });
    playerRanks.entrySet().removeIf(e -> e.getValue().isEmpty());

    save();
    return affected;
  }

  public synchronized boolean rankExists(String name) {
    return ranks.containsKey(normalize(name));
  }

  public synchronized String getDecorator(String rankName) {
    return ranks.getOrDefault(normalize(rankName), "");
  }

  public synchronized Set<String> getRanks(UUID uuid) {
    LinkedHashSet<String> set = playerRanks.get(uuid);
    return set == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(set));
  }

  public synchronized boolean giveRank(UUID uuid, String rankName) {
    String key = normalize(rankName);
    if (!ranks.containsKey(key)) return false;
    playerRanks.computeIfAbsent(uuid, k -> new LinkedHashSet<>()).add(key);
    save();
    return true;
  }

  public synchronized boolean removeRank(UUID uuid, String rankName) {
    String key = normalize(rankName);
    LinkedHashSet<String> set = playerRanks.get(uuid);
    if (set == null) return false;
    boolean removed = set.remove(key);
    if (set.isEmpty()) playerRanks.remove(uuid);
    if (removed) save();
    return removed;
  }

  private static String normalize(String name) {
    if (name == null) return "";
    return name.trim().toLowerCase(Locale.ROOT);
  }
}


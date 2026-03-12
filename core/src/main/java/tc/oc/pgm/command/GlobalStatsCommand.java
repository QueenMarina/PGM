package tc.oc.pgm.command;

import static net.kyori.adventure.text.Component.text;
import static tc.oc.pgm.util.text.TextException.playerOnly;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.raindrops.RaindropsService;
import tc.oc.pgm.util.Audience;

public final class GlobalStatsCommand {

  @Command("globalstats [player]")
  @CommandDescription("Show global (historical) stats from match-stats.csv")
  public void globalstats(Audience audience, CommandSender sender, @Argument("player") String query) {
    final UUID targetId;
    final String queryName;

    if (query == null || query.isEmpty()) {
      if (!(sender instanceof Player)) throw playerOnly();
      targetId = ((Player) sender).getUniqueId();
      queryName = ((Player) sender).getName();
    } else {
      UUID parsed = tryParseUuid(query);
      if (parsed != null) {
        targetId = parsed;
        queryName = query;
      } else {
        Player online = Bukkit.getPlayerExact(query);
        if (online != null) {
          targetId = online.getUniqueId();
          queryName = online.getName();
        } else {
          targetId = null;
          queryName = query;
        }
      }
    }

    Bukkit.getScheduler()
        .runTaskAsynchronously(
            PGM.get(),
            () -> {
              File csv = new File(PGM.get().getDataFolder(), "match-stats.csv");
              if (!csv.exists() || csv.length() == 0) {
                Bukkit.getScheduler()
                    .runTask(
                        PGM.get(),
                        () ->
                            audience.sendWarning(
                                text("No global stats file found yet.", NamedTextColor.RED)));
                return;
              }

              try {
                GlobalResult result = readAndAggregate(csv, targetId, queryName);
                if (result == null || result.agg == null || result.agg.matches == 0) {
                  Bukkit.getScheduler()
                      .runTask(
                          PGM.get(),
                          () ->
                              audience.sendWarning(
                                  text("No global stats found for " + queryName + ".", NamedTextColor.RED)));
                  return;
                }

                long raindrops = RaindropsService.get().getBalance(result.uuid);
                Component[] lines = result.agg.render(queryName, raindrops);
                Bukkit.getScheduler().runTask(PGM.get(), () -> {
                  for (Component line : lines) audience.sendMessage(line);
                });
              } catch (IOException e) {
                Bukkit.getScheduler()
                    .runTask(
                        PGM.get(),
                        () ->
                            audience.sendWarning(
                                text("Failed to read global stats file.", NamedTextColor.RED)));
              }
            });
  }

  private static GlobalResult readAndAggregate(File csv, UUID targetId, String queryName)
      throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(csv.toPath(), StandardCharsets.UTF_8)) {
      String headerLine = reader.readLine();
      if (headerLine == null) return null;

      List<String> header = parseCsvLine(headerLine);
      Map<String, Integer> idx = new HashMap<>();
      for (int i = 0; i < header.size(); i++) idx.put(header.get(i), i);

      if (!idx.containsKey("player_uuid") || !idx.containsKey("player_name")) return null;

      if (targetId != null) {
        GlobalAgg agg = new GlobalAgg();
        String line;
        while ((line = reader.readLine()) != null) {
          List<String> cols = parseCsvLine(line);
          if (cols.isEmpty()) continue;
          UUID rowId = tryParseUuid(get(cols, idx, "player_uuid"));
          if (rowId == null || !rowId.equals(targetId)) continue;
          agg.add(cols, idx);
        }
        return new GlobalResult(targetId, agg);
      }

      // Query by username: aggregate per UUID, then pick the UUID with the most recent timestamp.
      Map<UUID, GlobalAgg> byId = new HashMap<>();
      Map<UUID, Instant> latest = new HashMap<>();
      String line;
      while ((line = reader.readLine()) != null) {
        List<String> cols = parseCsvLine(line);
        if (cols.isEmpty()) continue;
        String rowName = get(cols, idx, "player_name");
        if (rowName == null || !rowName.equalsIgnoreCase(queryName)) continue;

        UUID rowId = tryParseUuid(get(cols, idx, "player_uuid"));
        if (rowId == null) continue;

        byId.computeIfAbsent(rowId, k -> new GlobalAgg()).add(cols, idx);
        Instant endedAt = tryParseInstant(get(cols, idx, "ended_at"));
        if (endedAt != null) {
          Instant prev = latest.get(rowId);
          if (prev == null || endedAt.isAfter(prev)) latest.put(rowId, endedAt);
        }
      }

      UUID best = null;
      Instant bestTime = null;
      for (Map.Entry<UUID, Instant> e : latest.entrySet()) {
        if (bestTime == null || e.getValue().isAfter(bestTime)) {
          bestTime = e.getValue();
          best = e.getKey();
        }
      }
      return best == null ? null : new GlobalResult(best, byId.get(best));
    }
  }

  private static final class GlobalResult {
    private final UUID uuid;
    private final GlobalAgg agg;

    private GlobalResult(UUID uuid, GlobalAgg agg) {
      this.uuid = uuid;
      this.agg = agg;
    }
  }

  private static final class GlobalAgg {
    long matches;
    double timePlayedSeconds;

    long kills;
    long deaths;
    long assists;
    long killstreakMax;

    long longestBowShot;
    double damageDone;
    double damageTaken;
    double bowDamage;
    double bowDamageTaken;
    long shotsTaken;
    long shotsHit;

    long destroyablePiecesBroken;
    long monumentsDestroyed;
    long flagsCaptured;
    long flagPickups;
    long coresLeaked;
    long woolsCaptured;
    long woolsTouched;

    double longestFlagHoldSeconds;

    double sumMatchKd;

    void add(List<String> cols, Map<String, Integer> idx) {
      matches++;

      double k = getDouble(cols, idx, "kills");
      double d = getDouble(cols, idx, "deaths");
      sumMatchKd += k / Math.max(1d, d);

      timePlayedSeconds += getDouble(cols, idx, "time_played_seconds");
      kills += (long) k;
      deaths += (long) d;
      assists += (long) getDouble(cols, idx, "assists");
      killstreakMax = Math.max(killstreakMax, (long) getDouble(cols, idx, "killstreak_max"));

      longestBowShot = Math.max(longestBowShot, (long) getDouble(cols, idx, "longest_bow_shot_blocks"));
      damageDone += getDouble(cols, idx, "damage_done");
      damageTaken += getDouble(cols, idx, "damage_taken");
      bowDamage += getDouble(cols, idx, "bow_damage");
      bowDamageTaken += getDouble(cols, idx, "bow_damage_taken");
      shotsTaken += (long) getDouble(cols, idx, "shots_taken");
      shotsHit += (long) getDouble(cols, idx, "shots_hit");

      destroyablePiecesBroken += (long) getDouble(cols, idx, "destroyable_pieces_broken");
      monumentsDestroyed += (long) getDouble(cols, idx, "monuments_destroyed");
      flagsCaptured += (long) getDouble(cols, idx, "flags_captured");
      flagPickups += (long) getDouble(cols, idx, "flag_pickups");
      coresLeaked += (long) getDouble(cols, idx, "cores_leaked");
      woolsCaptured += (long) getDouble(cols, idx, "wools_captured");
      woolsTouched += (long) getDouble(cols, idx, "wools_touched");

      longestFlagHoldSeconds =
          Math.max(longestFlagHoldSeconds, getDouble(cols, idx, "longest_flag_hold_seconds"));
    }

    Component[] render(String displayName, long raindrops) {
      double overallKd = kills / Math.max(1d, (double) deaths);
      double acc = shotsTaken <= 0 ? Double.NaN : (shotsHit / (double) shotsTaken) * 100d;

      List<Component> out = new ArrayList<>();

      out.add(text("Global Stats", NamedTextColor.GOLD, TextDecoration.BOLD)
          .append(text(" for ", NamedTextColor.GRAY))
          .append(text(displayName, NamedTextColor.YELLOW)));

      out.add(text("Matches: ", NamedTextColor.GRAY)
          .append(text(matches, NamedTextColor.AQUA))
          .append(text("  Time played: ", NamedTextColor.GRAY))
          .append(text(formatDurationSeconds(timePlayedSeconds), NamedTextColor.AQUA)));

      out.add(text("Raindrops: ", NamedTextColor.GRAY)
          .append(text(raindrops, NamedTextColor.AQUA)));

      out.add(text("K/D/A: ", NamedTextColor.GRAY)
          .append(text(kills, NamedTextColor.GREEN))
          .append(text("/", NamedTextColor.DARK_GRAY))
          .append(text(deaths, NamedTextColor.RED))
          .append(text("/", NamedTextColor.DARK_GRAY))
          .append(text(assists, NamedTextColor.LIGHT_PURPLE))
          .append(text("  KD: ", NamedTextColor.GRAY))
          .append(text(fmt2(overallKd), NamedTextColor.AQUA)));

      out.add(text("Best killstreak: ", NamedTextColor.GRAY)
          .append(text(killstreakMax, NamedTextColor.AQUA)));

      out.add(text("Damage: ", NamedTextColor.GRAY)
          .append(text(fmt2(damageDone), NamedTextColor.GREEN))
          .append(text(" dealt", NamedTextColor.GRAY))
          .append(text("  ", NamedTextColor.GRAY))
          .append(text(fmt2(damageTaken), NamedTextColor.RED))
          .append(text(" taken", NamedTextColor.GRAY)));

      out.add(text("Bow: ", NamedTextColor.GRAY)
          .append(text(longestBowShot + " blocks", NamedTextColor.AQUA))
          .append(text(" longest shot", NamedTextColor.GRAY))
          .append(text("  Accuracy: ", NamedTextColor.GRAY))
          .append(text(Double.isNaN(acc) ? "N/A" : (fmt2(acc) + "%"), NamedTextColor.AQUA)));

      out.add(text("Objectives", NamedTextColor.GOLD, TextDecoration.BOLD));
      out.add(text("Wools: ", NamedTextColor.GRAY)
          .append(text(woolsTouched, NamedTextColor.AQUA))
          .append(text(" picked", NamedTextColor.GRAY))
          .append(text(" / ", NamedTextColor.DARK_GRAY))
          .append(text(woolsCaptured, NamedTextColor.AQUA))
          .append(text(" placed", NamedTextColor.GRAY)));
      out.add(text("Monuments: ", NamedTextColor.GRAY)
          .append(text(monumentsDestroyed, NamedTextColor.AQUA)));
      out.add(text("Flags: ", NamedTextColor.GRAY)
          .append(text(flagsCaptured, NamedTextColor.AQUA))
          .append(text(" captured", NamedTextColor.GRAY))
          .append(text(" / ", NamedTextColor.DARK_GRAY))
          .append(text(flagPickups, NamedTextColor.AQUA))
          .append(text(" picked", NamedTextColor.GRAY)));
      out.add(text("Longest flag hold: ", NamedTextColor.GRAY)
          .append(text(formatDurationSeconds(longestFlagHoldSeconds), NamedTextColor.AQUA)));

      return out.toArray(new Component[0]);
    }
  }

  private static String formatDurationSeconds(double seconds) {
    if (seconds <= 0) return "0s";
    Duration d = Duration.ofMillis((long) (seconds * 1000d));
    long h = d.toHours();
    long m = d.minusHours(h).toMinutes();
    long s = d.minusHours(h).minusMinutes(m).getSeconds();
    if (h > 0) return h + "h " + m + "m";
    if (m > 0) return m + "m " + s + "s";
    return s + "s";
  }

  private static String fmt2(double v) {
    return String.format(Locale.ROOT, "%.2f", v);
  }

  private static double getDouble(List<String> cols, Map<String, Integer> idx, String key) {
    String s = get(cols, idx, key);
    if (s == null || s.isEmpty()) return 0d;
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      return 0d;
    }
  }

  private static String get(List<String> cols, Map<String, Integer> idx, String key) {
    Integer i = idx.get(key);
    if (i == null || i < 0 || i >= cols.size()) return null;
    return cols.get(i);
  }

  private static UUID tryParseUuid(String s) {
    if (s == null) return null;
    try {
      return UUID.fromString(s.trim());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static Instant tryParseInstant(String s) {
    if (s == null || s.isEmpty()) return null;
    try {
      return Instant.parse(s);
    } catch (Exception e) {
      return null;
    }
  }

  // Minimal RFC4180-style parser supporting quoted fields and escaped quotes.
  private static List<String> parseCsvLine(String line) {
    List<String> out = new ArrayList<>();
    if (line == null) return out;
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          cur.append(c);
        }
      } else {
        if (c == ',') {
          out.add(cur.toString());
          cur.setLength(0);
        } else if (c == '"') {
          inQuotes = true;
        } else {
          cur.append(c);
        }
      }
    }
    out.add(cur.toString());
    return out;
  }
}

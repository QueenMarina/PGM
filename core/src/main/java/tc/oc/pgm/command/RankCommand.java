package tc.oc.pgm.command;

import static net.kyori.adventure.text.Component.text;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.api.event.NameDecorationChangeEvent;
import tc.oc.pgm.namedecorations.CustomRankService;
import tc.oc.pgm.util.Audience;

public final class RankCommand {

  @Command("rank list")
  @CommandDescription("List custom ranks")
  @Permission(Permissions.RANK)
  public void list(Audience audience, CommandSender sender) {
    Map<String, String> ranks = CustomRankService.get().listRanks();
    if (ranks.isEmpty()) {
      audience.sendMessage(text("No custom ranks defined.", NamedTextColor.GRAY));
      return;
    }

    audience.sendMessage(text("Custom ranks:", NamedTextColor.GOLD));
    for (Map.Entry<String, String> e : ranks.entrySet()) {
      audience.sendMessage(
          text(" - ", NamedTextColor.DARK_GRAY)
              .append(text(e.getKey(), NamedTextColor.YELLOW))
              .append(text(" : ", NamedTextColor.DARK_GRAY))
              .append(text(e.getValue(), NamedTextColor.GRAY)));
    }
  }

  @Command("rank add <rank> <decorator>")
  @CommandDescription("Add a custom rank")
  @Permission(Permissions.RANK)
  public void add(
      Audience audience,
      CommandSender sender,
      @Argument("rank") String rank,
      @Argument("decorator") String decorator) {
    if (rank == null || rank.trim().isEmpty()) {
      audience.sendWarning(text("Rank name cannot be empty.", NamedTextColor.RED));
      return;
    }
    if (decorator == null) decorator = "";

    boolean ok = CustomRankService.get().addRank(rank, decorator);
    if (!ok) {
      audience.sendWarning(text("Rank already exists: " + rank, NamedTextColor.RED));
      return;
    }
    audience.sendMessage(text("Added rank " + rank + ".", NamedTextColor.GREEN));
  }

  @Command("rank delete <rank>")
  @CommandDescription("Delete a custom rank")
  @Permission(Permissions.RANK)
  public void delete(Audience audience, CommandSender sender, @Argument("rank") String rank) {
    if (!CustomRankService.get().rankExists(rank)) {
      audience.sendWarning(text("Unknown rank: " + rank, NamedTextColor.RED));
      return;
    }

    Set<UUID> affected = CustomRankService.get().deleteRank(rank);

    // Refresh any online players that had this rank.
    for (UUID uuid : affected) {
      Player p = Bukkit.getPlayer(uuid);
      if (p != null) {
        PGM.get().getServer().getPluginManager().callEvent(new NameDecorationChangeEvent(uuid));
      }
    }

    audience.sendMessage(
        text("Deleted rank " + rank + ".", NamedTextColor.GREEN)
            .append(text(" (" + affected.size() + " players affected)", NamedTextColor.GRAY)));
  }

  @Command("rank give <player> <rank>")
  @CommandDescription("Give a player a custom rank")
  @Permission(Permissions.RANK)
  public void give(
      Audience audience,
      CommandSender sender,
      @Argument("player") OfflinePlayer player,
      @Argument("rank") String rank) {
    if (!CustomRankService.get().rankExists(rank)) {
      audience.sendWarning(text("Unknown rank: " + rank, NamedTextColor.RED));
      return;
    }

    UUID uuid = player.getUniqueId();
    boolean ok = CustomRankService.get().giveRank(uuid, rank);
    if (!ok) {
      audience.sendWarning(text("Could not give rank " + rank + ".", NamedTextColor.RED));
      return;
    }

    PGM.get().getServer().getPluginManager().callEvent(new NameDecorationChangeEvent(uuid));
    audience.sendMessage(
        text("Gave ", NamedTextColor.GREEN)
            .append(text(player.getName() == null ? uuid.toString() : player.getName(), NamedTextColor.YELLOW))
            .append(text(" rank ", NamedTextColor.GREEN))
            .append(text(rank, NamedTextColor.YELLOW))
            .append(text(".", NamedTextColor.GREEN)));
  }

  @Command("rank remove <player> <rank>")
  @CommandDescription("Remove a custom rank from a player")
  @Permission(Permissions.RANK)
  public void remove(
      Audience audience,
      CommandSender sender,
      @Argument("player") OfflinePlayer player,
      @Argument("rank") String rank) {
    UUID uuid = player.getUniqueId();
    boolean ok = CustomRankService.get().removeRank(uuid, rank);
    if (!ok) {
      audience.sendWarning(text("That player does not have rank " + rank + ".", NamedTextColor.RED));
      return;
    }

    PGM.get().getServer().getPluginManager().callEvent(new NameDecorationChangeEvent(uuid));
    audience.sendMessage(
        text("Removed ", NamedTextColor.GREEN)
            .append(text(player.getName() == null ? uuid.toString() : player.getName(), NamedTextColor.YELLOW))
            .append(text(" rank ", NamedTextColor.GREEN))
            .append(text(rank, NamedTextColor.YELLOW))
            .append(text(".", NamedTextColor.GREEN)));
  }
}

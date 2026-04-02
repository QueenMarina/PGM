package tc.oc.pgm.command;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.Component.translatable;
import static tc.oc.pgm.util.text.TextException.exception;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.setting.SettingKey;
import tc.oc.pgm.api.setting.SettingValue;
import tc.oc.pgm.stats.StatsMatchModule;
import tc.oc.pgm.teams.TeamMatchModule;
import tc.oc.pgm.util.Audience;
import tc.oc.pgm.util.text.TextFormatter;

public final class StatsCommand {

  @Command("stats toggle <state>")
  @CommandDescription("Enable or disable saving match stats to CSV")
  @Permission(Permissions.STATS_TOGGLE)
  public void toggleCsvSaving(
      Audience audience, CommandSender sender, @Argument("state") String state) {
    final boolean enabled;
    if (state.equalsIgnoreCase("on") || state.equalsIgnoreCase("true")) {
      enabled = true;
    } else if (state.equalsIgnoreCase("off") || state.equalsIgnoreCase("false")) {
      enabled = false;
    } else {
      audience.sendWarning(text("Usage: /stats toggle on|off", NamedTextColor.RED));
      return;
    }

    StatsMatchModule.setCsvSavingEnabled(enabled);
    audience.sendMessage(
        text("Match stats CSV saving: ", NamedTextColor.GRAY)
            .append(text(enabled ? "ON" : "OFF", enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
  }

  @Command("stats")
  @CommandDescription("Show your stats for the match")
  public void stats(
      Audience audience,
      CommandSender sender,
      MatchPlayer player,
      Match match,
      StatsMatchModule stats) {
    if (match.isFinished()
        && PGM.get().getConfiguration().showVerboseStats()
        && match.hasModule(TeamMatchModule.class)) { // Should not try to trigger on FFA
      stats.openStatsMenu(player);
    } else if (player.getSettings().getValue(SettingKey.STATS).equals(SettingValue.STATS_ON)) {
      audience.sendMessage(TextFormatter.horizontalLineHeading(
          sender,
          translatable("match.stats.you", NamedTextColor.DARK_GREEN),
          NamedTextColor.WHITE));
      audience.sendMessage(stats.getBasicStatsMessage(player.getId()));
    } else {
      throw exception("match.stats.disabled");
    }
  }
}

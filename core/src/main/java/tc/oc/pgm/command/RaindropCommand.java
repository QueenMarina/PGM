package tc.oc.pgm.command;

import static net.kyori.adventure.text.Component.text;
import static tc.oc.pgm.util.text.TextException.playerOnly;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import tc.oc.pgm.raindrops.RaindropsService;
import tc.oc.pgm.util.Audience;

public final class RaindropCommand {

  @Command("raindrop effects <state>")
  @Command("raindrops effects <state>")
  @CommandDescription("Enable/disable raindrop burst effects")
  public void effects(
      Audience audience, CommandSender sender, @Argument("state") String state) {
    if (!(sender instanceof Player player)) throw playerOnly();

    boolean enabled;
    if (state == null) {
      audience.sendWarning(text("Usage: /raindrop effects on|off", NamedTextColor.RED));
      return;
    } else if (state.equalsIgnoreCase("on") || state.equalsIgnoreCase("enable")
        || state.equalsIgnoreCase("enabled") || state.equalsIgnoreCase("true")) {
      enabled = true;
    } else if (state.equalsIgnoreCase("off") || state.equalsIgnoreCase("disable")
        || state.equalsIgnoreCase("disabled") || state.equalsIgnoreCase("false")) {
      enabled = false;
    } else {
      audience.sendWarning(text("Usage: /raindrop effects on|off", NamedTextColor.RED));
      return;
    }

    RaindropsService.get().setEffectsEnabled(player.getUniqueId(), enabled);
    audience.sendMessage(
        text("Raindrop effects: ", NamedTextColor.GRAY)
            .append(text(enabled ? "ON" : "OFF", enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
  }
}


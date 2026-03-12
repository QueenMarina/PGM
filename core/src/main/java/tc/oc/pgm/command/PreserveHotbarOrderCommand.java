package tc.oc.pgm.command;

import static net.kyori.adventure.text.Component.text;
import static tc.oc.pgm.util.text.TextException.playerOnly;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.hotbar.HotbarLayoutService;
import tc.oc.pgm.util.Audience;

public final class PreserveHotbarOrderCommand {

  @Command("preservehotbarorder enable")
  @CommandDescription("Enable preserving hotbar order globally (server-wide)")
  @Permission(Permissions.PRESERVE_HOTBAR_ORDER)
  public void enableGlobal(Audience audience, CommandSender sender) {
    HotbarLayoutService.get().setGlobalEnabled(true);
    audience.sendMessage(
        text("Preserve hotbar order (global): ", NamedTextColor.GRAY)
            .append(text("ENABLED", NamedTextColor.GREEN)));
  }

  @Command("preservehotbarorder disable")
  @CommandDescription("Disable preserving hotbar order globally (server-wide)")
  @Permission(Permissions.PRESERVE_HOTBAR_ORDER)
  public void disableGlobal(Audience audience, CommandSender sender) {
    HotbarLayoutService.get().setGlobalEnabled(false);
    audience.sendMessage(
        text("Preserve hotbar order (global): ", NamedTextColor.GRAY)
            .append(text("DISABLED", NamedTextColor.RED)));
  }

  @Command("preservehotbarorder <state>")
  @CommandDescription("Enable/disable preserving your hotbar order on respawn (per map)")
  public void preserveHotbarOrder(
      Audience audience, CommandSender sender, @Argument("state") String state) {
    if (!(sender instanceof Player player)) throw playerOnly();

    boolean enabled;
    if (state == null) {
      audience.sendWarning(text("Usage: /preservehotbarorder on|off", NamedTextColor.RED));
      return;
    } else if (state.equalsIgnoreCase("on") || state.equalsIgnoreCase("true")) {
      enabled = true;
    } else if (state.equalsIgnoreCase("off") || state.equalsIgnoreCase("false")) {
      enabled = false;
    } else {
      audience.sendWarning(text("Usage: /preservehotbarorder on|off", NamedTextColor.RED));
      return;
    }

    HotbarLayoutService.get().setPreserveEnabled(player.getUniqueId(), enabled);
    audience.sendMessage(
        text("Preserve hotbar order: ", NamedTextColor.GRAY)
            .append(
                text(
                    enabled ? "ON" : "OFF",
                    enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
  }
}

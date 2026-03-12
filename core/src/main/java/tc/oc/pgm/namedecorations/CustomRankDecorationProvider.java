package tc.oc.pgm.namedecorations;

import static net.kyori.adventure.text.Component.text;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import tc.oc.pgm.util.named.NameDecorationProvider;

/** Adds custom rank decorators as a prefix. */
public final class CustomRankDecorationProvider implements NameDecorationProvider {

  private final CustomRankService ranks;

  public CustomRankDecorationProvider(CustomRankService ranks) {
    this.ranks = ranks;
  }

  @Override
  public String getPrefix(UUID uuid) {
    StringBuilder sb = new StringBuilder();
    for (String rank : ranks.getRanks(uuid)) {
      String deco = decorate(ranks.getDecorator(rank));
      if (!deco.isEmpty()) sb.append(ChatColor.translateAlternateColorCodes('&', deco));
    }
    return sb.toString();
  }

  @Override
  public String getSuffix(UUID uuid) {
    return "";
  }

  @Override
  public Component getPrefixComponent(UUID uuid) {
    Component out = text("");
    for (String rank : ranks.getRanks(uuid)) {
      String deco = decorate(ranks.getDecorator(rank));
      if (deco.isEmpty()) continue;
      out = out.append(deserializeLegacy(deco));
    }
    return out;
  }

  private static Component deserializeLegacy(String deco) {
    // Allow either '&' or '§' style codes.
    if (deco.indexOf('§') >= 0) {
      return LegacyComponentSerializer.legacySection().deserialize(deco);
    }
    return LegacyComponentSerializer.legacyAmpersand().deserialize(deco);
  }

  private static String decorate(String deco) {
    if (deco == null) return "";
    String s = deco;
    if (s.isEmpty()) return "";
    return s;
  }
}

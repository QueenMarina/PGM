package tc.oc.pgm.namedecorations;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import tc.oc.pgm.util.named.NameDecorationProvider;

/** Combines two decoration providers by concatenating their prefixes/suffixes. */
public final class CombinedDecorationProvider implements NameDecorationProvider {

  private final NameDecorationProvider first;
  private final NameDecorationProvider second;

  public CombinedDecorationProvider(NameDecorationProvider first, NameDecorationProvider second) {
    this.first = first == null ? NameDecorationProvider.DEFAULT : first;
    this.second = second == null ? NameDecorationProvider.DEFAULT : second;
  }

  @Override
  public String getPrefix(UUID uuid) {
    return first.getPrefix(uuid) + second.getPrefix(uuid);
  }

  @Override
  public String getSuffix(UUID uuid) {
    return first.getSuffix(uuid) + second.getSuffix(uuid);
  }

  @Override
  public TextColor getColor(UUID uuid) {
    TextColor c = second.getColor(uuid);
    return c == null ? (first.getColor(uuid) == null ? NamedTextColor.WHITE : first.getColor(uuid)) : c;
  }

  @Override
  public Component getPrefixComponent(UUID uuid) {
    return Component.text().append(first.getPrefixComponent(uuid)).append(second.getPrefixComponent(uuid)).build();
  }

  @Override
  public Component getSuffixComponent(UUID uuid) {
    return Component.text().append(first.getSuffixComponent(uuid)).append(second.getSuffixComponent(uuid)).build();
  }
}


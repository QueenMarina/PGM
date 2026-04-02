# Fork Additions

This fork adds several server-facing features on top of upstream PGM.

## Match Stats

- Players can view their match stats with `/stats`.
- Players can also look at stats for other players.
- Staff can turn match stat saving on or off.
- Added commands:
  `/stats`
  `/stats toggle <on|off>`

## Match Pause and Resume

- Teams can request a pause during a match.
- Teams can also agree to resume after a pause.
- Staff can force a pause or resume when needed.
- Added commands:
  `/pause`
  `/resume`
  `/pause force`
  `/resume force`

## Hotbar Order Saving

- Players can keep their preferred hotbar order on respawn.
- Saved layouts can carry over when playing the same map again.
- The feature can be toggled globally, and players can also toggle it for themselves.
- Added commands:
  `/preservehotbarorder enable`
  `/preservehotbarorder disable`
  `/preservehotbarorder <on|off>`

## Raindrops

- Players earn raindrops for gameplay actions and objective play.
- A personal visual effect can be turned on or off with `/raindrop effects <on|off>`.
- Added commands:
  `/raindrop effects <on|off>`
  `/raindrops effects <on|off>`

## Custom Ranks

- Staff can create custom ranks.
- Custom ranks can be assigned to players.
- Players with custom ranks show those decorations in-game.
- Added commands:
  `/rank list`
  `/rank add <rank> <decorator>`
  `/rank delete <rank>`
  `/rank give <player> <rank>`
  `/rank remove <player> <rank>`

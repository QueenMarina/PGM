package tc.oc.pgm.command;

import static net.kyori.adventure.text.Component.translatable;
import static tc.oc.pgm.util.player.PlayerComponent.player;
import static tc.oc.pgm.util.text.TextException.exception;
import static tc.oc.pgm.util.text.TextException.playerOnly;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.party.Competitor;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.channels.ChatManager;
import tc.oc.pgm.pause.PauseMatchModule;
import tc.oc.pgm.util.Audience;
import tc.oc.pgm.util.named.NameStyle;

public final class PauseCommand {

  @Command("pause")
  @CommandDescription("Pause the match")
  public void pause(
      Audience audience, CommandSender sender, Match match, PauseMatchModule pauseModule) {
    if (!match.isRunning()) {
      throw exception("admin.pause.matchNotRunning");
    }
    if (pauseModule.isPaused()) {
      throw exception("admin.pause.alreadyPaused");
    }

    // If an admin is actively playing on a team, default to the request flow
    // (so they don't accidentally force-pause mid-game). Use /pause force instead.
    MatchPlayer senderPlayer = null;
    if (sender instanceof Player) {
      senderPlayer = match.getPlayer((Player) sender);
    }
    if (sender.hasPermission(Permissions.PAUSE)
        && (senderPlayer == null || senderPlayer.getCompetitor() == null)) {
      pauseModule.pause();
      audience.sendMessage(translatable("admin.pause.paused"));
      ChatManager.broadcastAdminMessage(
          translatable("admin.pause.announce", player(sender, NameStyle.FANCY)));
      return;
    }

    // Players (including admins on a team) request a pause; both competitors must agree.
    if (!(sender instanceof Player)) throw playerOnly();
    final MatchPlayer mp = senderPlayer != null ? senderPlayer : match.getPlayer((Player) sender);
    if (mp == null) throw exception("pause.request.notInMatch");

    final Competitor competitor = mp.getCompetitor();
    if (competitor == null) throw exception("match.notOnTeam");
    if (pauseModule.hasPauseRequest(competitor)) throw exception("pause.request.alreadyRequested");

    pauseModule.requestPause(competitor);
  }

  @Command("resume")
  @CommandDescription("Resume the match")
  public void resume(
      Audience audience, CommandSender sender, Match match, PauseMatchModule pauseModule) {
    if (!match.isRunning()) {
      throw exception("admin.pause.matchNotRunning");
    }
    if (!pauseModule.isPaused()) {
      throw exception("admin.pause.notPaused");
    }

    // Players (including admins on a team) request resume; both competitors must agree, then a
    // countdown begins. Admins can bypass agreement via /resume force.
    if (!(sender instanceof Player)) throw playerOnly();
    final MatchPlayer mp = match.getPlayer((Player) sender);
    if (mp == null) throw exception("pause.request.notInMatch");

    final Competitor competitor = mp.getCompetitor();
    if (competitor == null) throw exception("match.notOnTeam");
    if (pauseModule.isResumeCountdownRunning()) throw exception("pause.resume.countdownRunning");
    if (pauseModule.hasResumeRequest(competitor)) throw exception("pause.resume.alreadyRequested");

    pauseModule.requestResume(competitor);
  }

  @Command("pause force")
  @CommandDescription("Pause the match immediately, bypassing team agreement")
  @Permission(Permissions.PAUSE)
  public void pauseForce(
      Audience audience, CommandSender sender, Match match, PauseMatchModule pauseModule) {
    if (!match.isRunning()) {
      throw exception("admin.pause.matchNotRunning");
    }
    if (pauseModule.isPaused()) {
      throw exception("admin.pause.alreadyPaused");
    }

    pauseModule.pause();
    audience.sendMessage(translatable("admin.pause.paused"));
    ChatManager.broadcastAdminMessage(
        translatable("admin.pause.announce", player(sender, NameStyle.FANCY)));
  }

  @Command("resume force")
  @CommandDescription("Resume the match immediately, bypassing any countdown")
  @Permission(Permissions.PAUSE)
  public void resumeForce(
      Audience audience, CommandSender sender, Match match, PauseMatchModule pauseModule) {
    if (!match.isRunning()) {
      throw exception("admin.pause.matchNotRunning");
    }
    if (!pauseModule.isPaused()) {
      throw exception("admin.pause.notPaused");
    }

    pauseModule.resume();
    audience.sendMessage(translatable("admin.pause.resumed"));
    ChatManager.broadcastAdminMessage(
        translatable("admin.pause.resumeAnnounce", player(sender, NameStyle.FANCY)));
  }
}

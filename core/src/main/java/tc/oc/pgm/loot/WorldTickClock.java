package tc.oc.pgm.loot;

import static tc.oc.pgm.util.nms.NMSHacks.NMS_HACKS;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.time.Tick;
import tc.oc.pgm.util.collection.InstantMap;

/**
 * Quantizes time to the ticks of the given World. Guaranteed to return the same time over the
 * duration of any tick.
 *
 * <p>Does not support anything that requires a {@link ZoneId}, should only be used to fetch
 * instants. Only implements {@link Clock} to fit into {@link InstantMap}
 */
public class WorldTickClock extends Clock {

  private final Match match;
  private Tick tick;

  private long tickOffset;
  private Duration pausedDuration = Duration.ZERO;
  private boolean paused;
  private long pausedRawTick;
  private Instant pausedAt;
  private Tick pausedTick;

  public WorldTickClock(Match match) {
    this.match = match;
  }

  @Override
  public ZoneId getZone() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Instant instant() {
    return this.now().instant;
  }

  public Tick getTick() {
    return this.now();
  }

  public boolean isPaused() {
    return paused;
  }

  /** Freeze match time. World time keeps advancing, but {@link #getTick()} will not. */
  public void pause() {
    if (paused) return;

    // Capture current virtual tick/instant and raw tick so we can offset away time spent paused.
    this.pausedTick = now();
    this.pausedRawTick = rawTick();
    this.pausedAt = Instant.now();
    this.paused = true;
  }

  /** Resume match time after {@link #pause()}. */
  public void resume() {
    if (!paused) return;

    long nowRaw = rawTick();
    this.tickOffset += (nowRaw - this.pausedRawTick);
    this.pausedDuration = this.pausedDuration.plus(Duration.between(this.pausedAt, Instant.now()));

    this.paused = false;
    this.pausedAt = null;
    this.pausedTick = null;
    // Force a fresh Tick instance next call.
    this.tick = null;
  }

  private Tick now() {
    if (paused) {
      // pausedTick is set in pause(), but be defensive in case pause() was never called.
      if (this.pausedTick == null) {
        this.pausedTick = new Tick(rawTick() - tickOffset, Instant.now().minus(pausedDuration));
      }
      return this.pausedTick;
    }

    long rawTick = rawTick();
    long virtualTick = rawTick - tickOffset;
    if (this.tick == null || virtualTick != this.tick.tick) {
      this.tick = new Tick(virtualTick, Instant.now().minus(pausedDuration));
    }
    return this.tick;
  }

  private long rawTick() {
    return NMS_HACKS.getMonotonicTime(match.getWorld());
  }
}

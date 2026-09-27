package pzopt;

import zombie.iso.IsoDirections;

/**
 * The seven values one {@code PropertyContainer.initSurface()} walk derives, accumulated off the container.
 *
 * <p>Stock's lazy init sets its done flag at the TOP of the block and only then fills the six surface values from the
 * property entries, so a second thread entering the guard mid-walk returns at once and reads fields still at their
 * reset defaults — a square answering "no sloped surface" while it has one. With the zombie entity updates on the
 * frame workers that second thread exists: the walk is reached from {@code updateFalling ->
 * getHeightAboveFloor -> hasSlopedSurface} on every worker at once, and from the game thread and the chunk streamer
 * besides.
 *
 * <p>So the walk accumulates here and the container publishes once at the end, the done flag last (and the flag is
 * volatile, so the order is a happens-before and not merely a program order). Two threads may both do the work; that
 * is fine, it is idempotent and they compute the same values from the same entries. The flag bits live here too:
 * stock OR-ed them into the field from inside the per-entry handler, a read-modify-write that loses a bit when two
 * walks overlap, and as one accumulated byte they land in the same single store as the done flag.
 *
 * <p>One instance per thread, reused: the surface walk allocates nothing ({@code propertySurfaceNoAlloc} exists
 * because C1 allocated one capturing lambda per call and chunk loading made that 30 % of a drive's allocation).
 * Re-entrancy cannot alias the scratch — everything the walk calls between {@link #scratch()} and the publish is
 * integer parsing, a direction lookup and a clamp, none of which reaches a property container.
 */
public final class SurfaceInit {

   public byte surface;
   public short stackReplaceTileOffset;
   public byte itemHeight;
   public IsoDirections slopedSurfaceDirection;
   public byte slopedSurfaceHeightMin;
   public byte slopedSurfaceHeightMax;
   /** The surface flag bits the entries set (offset / table / table top); the valid bit is added at the publish. */
   public byte flags;

   private static final ThreadLocal<SurfaceInit> SCRATCH = ThreadLocal.withInitial(SurfaceInit::new);

   private SurfaceInit() {
   }

   /** This thread's accumulator, at the defaults stock's init resets the fields to before its walk. */
   public static SurfaceInit scratch() {
      SurfaceInit s = SCRATCH.get();
      s.surface = 0;
      s.stackReplaceTileOffset = 0;
      s.itemHeight = 0;
      s.slopedSurfaceDirection = null;
      s.slopedSurfaceHeightMin = 0;
      s.slopedSurfaceHeightMax = 0;
      s.flags = 0;
      return s;
   }
}

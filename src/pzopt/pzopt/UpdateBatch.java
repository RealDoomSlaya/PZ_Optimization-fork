package pzopt;

import zombie.UpdateSchedulerSimulationLevel;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.iso.IsoMovingObject;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * The simulation bucket's own update loop on the frame workers ({@code entityUpdateParallel}).
 *
 * <p>Stock {@code MovingObjectUpdateSchedulerUpdateBucket.update(int)} walks one of the bucket's sub-lists and, per
 * entity, calls {@code setCurrentSimulationLevel(level)}, {@code preupdate()}, {@code frameStep()} and
 * {@code update()} — in that order, four calls, nothing else. This batch runs that same sequence per entity on the
 * {@link FrameBatch} workers instead of one entity after another on the game thread. The scheduler's other batches
 * ({@link AnimBatch}, {@link ActionEval}, {@link SeparateBatch}) all sit around the simulation; this one is the
 * simulation, which is why it ships off.
 *
 * <p><b>One bucket at a time, never across two.</b> The bucket sets {@code GameTime.perObjectMultiplier} to its own
 * frame mod for the whole of its loop and back to 1 afterwards. That field is a single value on the {@code GameTime}
 * singleton, so it is only constant — and therefore only correct — while one bucket's entities are in flight. Two
 * buckets batched at once would each publish their own multiplier and every entity would read whichever landed last.
 *
 * <p>What stays on the game thread, in the bucket before the batch is filled: the {@code IsoDeadBody} entries (they
 * go to the cell's remove set, shared and order-dependent) and the reused-zombie debug branch. Everything the batch
 * did not take runs inline exactly as before, and an entity that throws on a worker is reported once and turns the
 * batching off for the rest of the session — the bucket then walks stock's loop from the next frame on.
 */
public final class UpdateBatch {
   private UpdateBatch() {
   }

   private static IsoMovingObject[] queue = new IsoMovingObject[4096];
   private static int count;
   private static volatile boolean failed;
   private static volatile boolean inFlight; // set/cleared by run() around FrameBatch.run: the entity batch is on the workers now

   /**
    * True only on a {@link FrameBatch.Worker} while this batch's entities are in flight — the one predicate every
    * thread-safety guard of {@code entityUpdateParallel} keys on (the ForwardDirection throw, the LuaEventManager
    * suppression, the PathFindBehavior2 clone and catch). Two conditions, both load-bearing:
    *
    * <ul>
    *   <li>{@code inFlight}: the scheduler's other batches ({@code AnimBatch}, {@code ActionEval},
    *       {@code LightingBatch}, {@code SeparateBatch}) run on the same workers but never call Lua or the guarded
    *       entity paths by design, so a guard must not fire for them; {@code FrameBatch} runs one batch at a time
    *       (run() joins any pending async batch first), so while this flag is up the only tasks on the workers are
    *       this batch's entities.
    *   <li>the {@code instanceof} check: the game thread works the batch too, and an entity it updates must behave
    *       exactly as with the key off — Lua allowed, the zero-length throw kept, the live path list read.
    * </ul>
    *
    * <p>Cheap on the game-thread fast path: with the key off the volatile is always false and the thread check is
    * never reached.
    */
   public static boolean onWorkerNow() {
      return inFlight && Thread.currentThread() instanceof FrameBatch.Worker;
   }

   public static long frames, batched, maxBatch, workNanos, waitNanos;

   /** True while a bucket should hand its scheduled entities over instead of walking them itself. */
   public static boolean enabled() {
      return Config.ENTITY_UPDATE_PARALLEL && !failed && Config.effectiveWorkers() > 1
            && !GameClient.client && !GameServer.server && Overrides.enabled();
   }

   /**
    * Whether an entity of this type may update on a worker at all. ActionEval's rule: players and animals stay
    * inline. The player is the one that matters — {@code IsoPlayer.update()} runs Lua (input, timed actions, the
    * {@code OnPlayer*} events), nothing here guards against Lua re-entry from a worker, and the scheduler puts
    * every player in the FULL bucket, so a batch that did not exclude them would hand them to a worker on the
    * first frame. Animals follow the same rule as in ActionEval rather than being assumed safe.
    */
   public static boolean batchableType(Class<?> type) {
      return !IsoPlayer.class.isAssignableFrom(type) && !IsoAnimal.class.isAssignableFrom(type);
   }

   /** Whether this entity may update on a worker: its type, plus the per-zombie cases that read another entity. */
   public static boolean batchable(IsoMovingObject entity) {
      if (!batchableType(entity.getClass())) {
         return false;
      }

      // Grapple and a reanimated player read the other character while updating; ActionEval excludes the same three.
      if (entity instanceof IsoZombie zombie) {
         return !zombie.isBeingGrappled() && !zombie.isGrappling() && zombie.getReanimatedPlayer() == null;
      }

      return true;
   }

   /** True once an entity threw on a worker: the session is back on stock's loop. */
   public static boolean hasFailed() {
      return failed;
   }

   /** How many entities the current bucket has handed over and not yet run. */
   public static int pending() {
      return count;
   }

   /** Game thread, from the bucket's loop: this entity updates this frame and can run on a worker. */
   public static void add(IsoMovingObject entity) {
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
      }

      queue[count++] = entity;
   }

   /**
    * Game thread, at the end of one bucket's collection: run the collected entities' update sequence on the
    * workers and drain the queue. Returns false when there was nothing to do.
    */
   public static boolean run(UpdateSchedulerSimulationLevel level) {
      int n = count;
      if (n == 0) {
         return false;
      }

      frames++;
      batched += n;
      if (n > maxBatch) {
         maxBatch = n;
      }

      long w0 = FrameBatch.workNanos;
      long q0 = FrameBatch.waitNanos;
      Throwable t;
      inFlight = true; // onWorkerNow(): the workers are running this batch's entities from here to the finally
      try {
         t = FrameBatch.run(n, i -> {
            IsoMovingObject entity = queue[i];
            entity.setCurrentSimulationLevel(level);
            entity.preupdate();
            entity.frameStep();
            entity.update();
         });
      } finally {
         inFlight = false;
      }
      workNanos += FrameBatch.workNanos - w0;
      waitNanos += FrameBatch.waitNanos - q0;
      if (t != null) {
         if (!failed) {
            failed = true; // the bucket walks stock's loop from the next frame on
            // The full stack trace, once: the live frame-10 "Index -1 out of bounds for length 2" arrived with
            // no call site because only t.toString() was logged, and the next unknown race must not.
            Log.warn("entityUpdateParallel: an entity update failed on a worker, batching off: " + stackOf(t));
         } else {
            Log.warn("entityUpdateParallel: an entity update failed on a worker, batching off: " + t);
         }
      }

      java.util.Arrays.fill(queue, 0, n, null);
      count = 0;
      return true;
   }

   /** Game thread: drop whatever was collected (a bucket that never reached run()). */
   public static void clear() {
      java.util.Arrays.fill(queue, 0, count, null);
      count = 0;
   }

   /** One throwable, rendered with its full stack trace and causes, for the one-shot logs. */
   private static String stackOf(Throwable t) {
      java.io.StringWriter sw = new java.io.StringWriter();
      t.printStackTrace(new java.io.PrintWriter(sw));
      return sw.toString().trim();
   }

   private static final java.util.concurrent.atomic.AtomicLong luaSuppressed = new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong pathfindRaceSkipped = new java.util.concurrent.atomic.AtomicLong();

   /**
    * A worker mid-batch reached {@code LuaEventManager.triggerEvent} and the override's guard turned the dispatch
    * into a no-op (vanilla's main-thread path writes the shared static argument slots {@code a1..a8}/
    * {@code a1index..a8index}; its off-thread path takes the EventMap monitor and queues into a shared pool — a
    * worker must enter neither). Counted, and the first occurrence dumps the current stack so the log says WHICH
    * event from WHERE; the count is on {@link #describe}.
    */
   public static void onLuaSuppressed() {
      if (luaSuppressed.incrementAndGet() == 1L) {
         Log.warn("entityUpdateParallel: first Lua event suppressed on a worker (dispatch site follows): "
               + stackOf(new Throwable("suppressed Lua dispatch on " + Thread.currentThread().getName())));
      }
   }

   /** How many Lua event dispatches the worker guard has suppressed this session. */
   public static long getLuaSuppressedCount() {
      return luaSuppressed.get();
   }

   /**
    * A worker mid-batch hit the PathFindBehavior2 race (the async pathfinder's list write or the position-derived
    * IllegalStateException) and the override returned {@code BehaviorResult.Working} for this frame instead of
    * letting the throw latch the batch off; the character retries next frame. Count only — the interesting stack
    * is already known and documented; the count is on {@link #describe}.
    */
   public static void onPathfindRaceSkipped() {
      pathfindRaceSkipped.incrementAndGet();
   }

   /** How many PathFindBehavior2 updates a worker skipped to Working on the race this session. */
   public static long getPathfindRaceSkippedCount() {
      return pathfindRaceSkipped.get();
   }

   public static String describe() {
      return "update batch: frames=" + frames + " batched=" + batched + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L)
            + " luaSuppressed=" + luaSuppressed.get() + " pathfindRaceSkipped=" + pathfindRaceSkipped.get()
            + (failed ? " FAILED" : "");
   }
}

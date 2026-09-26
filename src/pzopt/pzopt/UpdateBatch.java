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

   // ── position snapshot (the PZMulticore IsoMovingObjectPatcher layer, pzopt-shaped) ──────────────────────────
   //
   // The first live A/B's residue was 20 zero-length ForwardDirection throws on the GAME thread: a worker read
   // another entity's x/y/z while a second worker was writing them, and the torn value surfaced a frame later.
   // So for the window of one batch, a cross-entity getX()/getY()/getZ() answers from these arrays — the position
   // frozen on the game thread just before dispatch — while a self-read (the entity the current task is updating,
   // CURRENT) stays live. Only the queued entities are snapshotted: everything else (players, animals, grappled
   // zombies) ran inline before run() and is motionless while the batch is in flight.
   //
   // Validity is the per-entity frame stamp against snapshotFrame — no clear pass after the join, a stale index
   // from an earlier batch simply stops matching. Happens-before: the arrays and stamps are written on the game
   // thread BEFORE the volatile write to inFlight; every reader volatile-reads inFlight first (frozen()).
   private static volatile float[] snapX = new float[4096];
   private static volatile float[] snapY = new float[4096];
   private static volatile float[] snapZ = new float[4096];
   private static volatile long snapshotFrame;
   private static final ThreadLocal<IsoMovingObject> CURRENT = new ThreadLocal<>();

   // setForwardDirectionFromIsoDirection's scratch vector. The jar's method writes the direction into the STATIC
   // IsoGameCharacter.tempVector2_2 and reads it back, and getVectorFromDirection ZEROES the vector before the
   // switch assigns it — so with zombies on the workers, any character reading between another's zeroing and its
   // assignment got an exact (0,0) and threw "Forward Direction cannot be zero length vector" (the whole
   // 37-exception Louisville residue: WalkTowardState, ThumpState, ClimbOverFenceState — the last from
   // setDir(IsoDirections.N), a constant, which is what ruled the states' own math out). Between throws the same
   // race silently handed a walker another zombie's direction. Per-thread vector, same idiom as VehicleCull.near.
   private static final ThreadLocal<zombie.iso.Vector2> DIR_SCRATCH = ThreadLocal.withInitial(zombie.iso.Vector2::new);

   /** This thread's direction scratch vector, for the IsoGameCharacter override. */
   public static zombie.iso.Vector2 dirScratch() {
      return DIR_SCRATCH.get();
   }

   /**
    * True when this entity's position must be read from the snapshot: a batch is in flight, the entity is in it
    * (its stamp matches this batch), and the caller is not the task updating it. Called by the IsoMovingObject
    * override's getX/getY/getZ — with the key off the volatile is always false and nothing else is read.
    */
   public static boolean frozen(IsoMovingObject entity) {
      return inFlight && entity.pzoptSnapshotFrame == snapshotFrame && entity != CURRENT.get();
   }

   /** The frozen X of an entity {@link #frozen} said yes for. */
   public static float frozenX(IsoMovingObject entity) {
      return snapX[entity.pzoptSnapshotIndex];
   }

   /** The frozen Y of an entity {@link #frozen} said yes for. */
   public static float frozenY(IsoMovingObject entity) {
      return snapY[entity.pzoptSnapshotIndex];
   }

   /** The frozen Z of an entity {@link #frozen} said yes for. */
   public static float frozenZ(IsoMovingObject entity) {
      return snapZ[entity.pzoptSnapshotIndex];
   }

   // ── setMovingSquare deferral ────────────────────────────────────────────────────────────────────────────────
   //
   // setMovingSquare mutates the target square's shared MovingObjects ArrayList; two workers landing entities on
   // one square is a plain list race. A call made from inside a batched entity's update is latched on the entity
   // (last call wins, like the serial loop's end state) and replayed by the game thread right after the join. The
   // PZMulticore reference skips the call outright; a pzopt batch can replay on the game thread, so nothing goes
   // stale. The latched entity need not be in the queue — an update may set a square on another entity.
   private static final java.util.concurrent.ConcurrentLinkedQueue<IsoMovingObject> deferredSquares =
         new java.util.concurrent.ConcurrentLinkedQueue<>();
   private static long movingSquareDeferred;

   /**
    * Called by the IsoMovingObject override at the top of setMovingSquare. True = the call was latched (the
    * caller returns without touching the square lists); false = no batch window, run the stock body.
    */
   public static boolean deferMovingSquare(IsoMovingObject entity, zombie.iso.IsoGridSquare square) {
      if (!inFlight || CURRENT.get() == null) {
         return false;
      }

      if (entity.pzoptDeferredSquareFrame != snapshotFrame) {
         entity.pzoptDeferredSquareFrame = snapshotFrame;
         deferredSquares.add(entity); // two racing stamp reads may add twice; the replay's stamp check drops the twin
      }

      entity.pzoptDeferredSquare = square;
      return true;
   }

   // ── Lua capture-and-replay (entityUpdateLuaReplay) ─────────────────────────────────────────────────────────
   //
   // xD3I's AnimParallel pattern applied to the update batch: a worker mid-batch reaching triggerEvent appends
   // (event, args…) to its task's capture list instead of dropping the dispatch; after the join the game thread
   // walks the tasks in queue order — stock's serial event order — and fans each record back through the real
   // triggerEvent overloads, still inside the bucket window so handlers run under the bucket's
   // perObjectMultiplier exactly as stock's inline dispatch did (AnimParallel dispatches under useMultiplier for
   // the same reason: ThumpState counts strikes over it). With the key off the guard counts and drops, as before.
   private static java.util.ArrayList<Object[]>[] luaCaptures = newLuaCaptureArray(4096);
   private static final ThreadLocal<java.util.ArrayList<Object[]>> LUA_CAPTURE = new ThreadLocal<>();
   private static long luaCaptured, luaReplayed;

   @SuppressWarnings("unchecked")
   private static java.util.ArrayList<Object[]>[] newLuaCaptureArray(int size) {
      return new java.util.ArrayList[size];
   }

   /**
    * A worker mid-batch reached a {@code triggerEvent} overload (the override calls this from behind its
    * {@link #onWorkerNow} guard): capture the dispatch for the game thread's replay, or count-and-drop when
    * {@code entityUpdateLuaReplay} is off. The varargs array only ever allocates on this already-guarded path.
    */
   public static void captureLuaEvent(String event, Object... params) {
      java.util.ArrayList<Object[]> list = LUA_CAPTURE.get();
      if (list == null || !Config.ENTITY_UPDATE_LUA_REPLAY) {
         onLuaSuppressed();
         return;
      }

      Object[] record = new Object[params.length + 1];
      record[0] = event;
      System.arraycopy(params, 0, record, 1, params.length);
      list.add(record);
   }

   /** Game thread, after the join: every task's captured events through the real dispatch, in queue order. */
   private static void replayLuaEvents(int n) {
      for (int i = 0; i < n; i++) {
         java.util.ArrayList<Object[]> list = luaCaptures[i];
         if (list == null || list.isEmpty()) {
            continue;
         }

         luaCaptured += list.size();
         for (int j = 0; j < list.size(); j++) {
            Object[] r = list.get(j);
            String e = (String) r[0];
            switch (r.length) {
               case 1 -> zombie.Lua.LuaEventManager.triggerEvent(e);
               case 2 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1]);
               case 3 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2]);
               case 4 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3]);
               case 5 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4]);
               case 6 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4], r[5]);
               case 7 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4], r[5], r[6]);
               case 8 -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4], r[5], r[6], r[7]);
               default -> zombie.Lua.LuaEventManager.triggerEvent(e, r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8]);
            }

            luaReplayed++;
         }

         list.clear();
      }
   }

   /** How many worker-fired Lua events the capture has taken this session. */
   public static long getLuaCapturedCount() {
      return luaCaptured;
   }

   /** How many captured Lua events the game thread has replayed this session. */
   public static long getLuaReplayedCount() {
      return luaReplayed;
   }

   /** Game thread, after the join: apply the last latched square per entity through the real setMovingSquare. */
   private static void applyDeferredSquares() {
      IsoMovingObject entity;
      while ((entity = deferredSquares.poll()) != null) {
         if (entity.pzoptDeferredSquareFrame != snapshotFrame) {
            continue; // a duplicate queue entry: this entity was already applied
         }

         entity.pzoptDeferredSquareFrame = 0L;
         zombie.iso.IsoGridSquare square = entity.pzoptDeferredSquare;
         entity.pzoptDeferredSquare = null;
         entity.setMovingSquare(square); // inFlight is false again, so this runs the stock body
         movingSquareDeferred++;
      }
   }

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

   /**
    * True while this thread is running one of the batch's entity tasks — a worker OR the game thread working
    * the batch. The pathfind guard's predicate: every PathFindState escape of the live runs (4 in
    * lou-replay-clean, 1 in lou-fwd-scratch) was the game-thread participant reading the live path list while
    * the pathfind thread or another worker's group-leader pathToLocation mutated it — mid-batch the participant
    * faces the same writers a worker does, so it snapshots and skips like one. Outside a batch CURRENT is never
    * set and vanilla behaviour is untouched (deferMovingSquare has used this same condition from the start).
    */
   public static boolean onBatchTaskNow() {
      return inFlight && CURRENT.get() != null;
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
    *
    * <p>Vehicles and physics objects stay inline too, found by the first hour-long live session (2026-09-26):
    * an active vehicle's {@code update()} reaches Lua part scripts ({@code updateParts} →
    * {@code VehicleParts.callLuaVoid}) — the wrong-thread guard errored 26 times on the workers and the 27th
    * corrupted the Kahlua VM stack ("Index -4 out of bounds for length 1000"), latching batching off. The bench
    * route is on foot, so no active vehicle ever updated mid-batch before real play did it. IsoPhysicsObject
    * steps native physics and was never audited — same rule.
    */
   public static boolean batchableType(Class<?> type) {
      return !IsoPlayer.class.isAssignableFrom(type) && !IsoAnimal.class.isAssignableFrom(type)
            && !zombie.vehicles.BaseVehicle.class.isAssignableFrom(type)
            && !zombie.iso.IsoPhysicsObject.class.isAssignableFrom(type);
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

      // Freeze the queued entities' positions BEFORE the volatile write to inFlight below: that ordered pair is
      // the happens-before that lets workers read the arrays and stamps without a lock.
      snapshotFrame++;
      if (snapX.length < n) {
         int size = Math.max(n, snapX.length * 2);
         snapX = new float[size];
         snapY = new float[size];
         snapZ = new float[size];
      }
      float[] sx = snapX;
      float[] sy = snapY;
      float[] sz = snapZ;
      long frame = snapshotFrame;
      for (int i = 0; i < n; i++) {
         IsoMovingObject entity = queue[i];
         sx[i] = entity.getX(); // still live here: inFlight is false until the write below
         sy[i] = entity.getY();
         sz[i] = entity.getZ();
         entity.pzoptSnapshotIndex = i;
         entity.pzoptSnapshotFrame = frame;
      }

      boolean luaReplay = Config.ENTITY_UPDATE_LUA_REPLAY;
      if (luaReplay && luaCaptures.length < n) {
         luaCaptures = java.util.Arrays.copyOf(luaCaptures, Math.max(n, luaCaptures.length * 2));
      }

      Throwable t;
      inFlight = true; // onWorkerNow() and frozen(): the workers are running this batch's entities from here to the finally
      try {
         t = FrameBatch.run(n, i -> {
            IsoMovingObject entity = queue[i];
            CURRENT.set(entity); // frozen(): this task's entity reads itself live, everyone else frozen
            java.util.ArrayList<Object[]> capture = null;
            if (luaReplay) {
               capture = luaCaptures[i];
               if (capture == null) {
                  capture = new java.util.ArrayList<>();
                  luaCaptures[i] = capture; // published to the game thread by the join
               }
               LUA_CAPTURE.set(capture); // captureLuaEvent appends here for this task
            }
            try {
               entity.setCurrentSimulationLevel(level);
               entity.preupdate();
               entity.frameStep();
               entity.update();
            } finally {
               CURRENT.set(null); // a pooled worker must not carry the reference into the next task
               if (capture != null) {
                  LUA_CAPTURE.set(null);
               }
            }
         });
      } finally {
         inFlight = false;
      }

      // The window's latched setMovingSquare calls, applied in one place on the game thread — also after a
      // failed batch, so a partially updated frame still lands its tile updates instead of leaking them.
      applyDeferredSquares();

      // The window's captured Lua dispatches, in queue order — stock's serial event order — while the bucket's
      // perObjectMultiplier is still set, so handlers see the same time scale as stock's inline dispatch. Also
      // after a failed batch: events fired before the throw did fire in stock's semantics too.
      if (luaReplay) {
         replayLuaEvents(n);
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
            + " luaCaptured=" + luaCaptured + " luaReplayed=" + luaReplayed
            + " luaSuppressed=" + luaSuppressed.get() + " pathfindRaceSkipped=" + pathfindRaceSkipped.get()
            + " movingSquareDeferred=" + movingSquareDeferred
            + (failed ? " FAILED" : "");
   }
}

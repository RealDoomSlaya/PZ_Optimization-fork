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

   // The per-task perObjectMultiplier override: NaN = not inside a batch task (read the live field). A
   // float[1] holder instead of ThreadLocal<Float> so the per-task set/clear never boxes. Written by the
   // batch runner from the value captured at dispatch; read by pom() at the five consumer sites so a task
   // keeps its bucket's multiplier even after the game thread has moved on to the next bucket's write.
   private static final ThreadLocal<float[]> POM = ThreadLocal.withInitial(() -> new float[]{Float.NaN});

   /** The perObjectMultiplier this thread should see: the dispatch-time capture inside a batch task, the live field otherwise. */
   public static float pom(zombie.GameTime gameTime) {
      float v = POM.get()[0];
      return v == v ? v : gameTime.perObjectMultiplier; // v==v is false only for the NaN sentinel
   }

   // IsoGameCharacter's static tempo/tempo2 scratch vectors, per thread (the dirScratch disease's siblings:
   // faceThisObject fills the static tempo and hands it to DirectionFromVector, so two zombies facing
   // anything concurrently share one vector — run lou-pipe-off caught ZombieEatBodyState throwing the
   // zero-length exception through exactly that path). Worker-reachable users read these; the debug/render/
   // death-path users keep the statics.
   private static final ThreadLocal<zombie.iso.Vector2> TEMPO = ThreadLocal.withInitial(zombie.iso.Vector2::new);
   private static final ThreadLocal<zombie.iso.Vector2> TEMPO2 = ThreadLocal.withInitial(zombie.iso.Vector2::new);

   /** This thread's tempo scratch vector, for the IsoGameCharacter override. */
   public static zombie.iso.Vector2 tempoScratch() {
      return TEMPO.get();
   }

   /** This thread's tempo2 scratch vector, for the IsoGameCharacter override. */
   public static zombie.iso.Vector2 tempo2Scratch() {
      return TEMPO2.get();
   }

   // WalkTowardState's singleton temp/worldPos scratch, per thread (the State instance is shared by every
   // walking zombie; its fields are the same disease as the statics above).
   private static final ThreadLocal<zombie.iso.Vector2> WALK = ThreadLocal.withInitial(zombie.iso.Vector2::new);
   private static final ThreadLocal<org.joml.Vector3f> WALK3 = ThreadLocal.withInitial(org.joml.Vector3f::new);

   /** This thread's WalkTowardState temp scratch, for that override. */
   public static zombie.iso.Vector2 walkScratch() {
      return WALK.get();
   }

   /** This thread's WalkTowardState worldPos scratch, for that override. */
   public static org.joml.Vector3f walkScratch3() {
      return WALK3.get();
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

   // ── Deferred emitter ticks (emitterDefer) ──────────────────────────────────────────────────────────────
   //
   // The same per-task slot shape as the Lua capture: a batched zombie reaching updateEmitter queues ITSELF
   // on its task's slot instead of ticking FMOD on the worker (the ticks serialized on the emitter monitors,
   // against each other and against the inline player's combat — and the prone branch writes a STATIC bone
   // scratch). joinPending runs the queued ticks on the game thread in queue order — stock's serial order —
   // under the flight's captured multiplier. The tick still lands in the same frame, before anything renders.
   private static java.util.ArrayList<zombie.characters.IsoGameCharacter>[] emitterCaptures = newEmitterCaptureArray(4096);
   private static final ThreadLocal<java.util.ArrayList<zombie.characters.IsoGameCharacter>> EMITTER_CAPTURE = new ThreadLocal<>();
   private static long emitterDeferred, emitterDrained;

   @SuppressWarnings("unchecked")
   private static java.util.ArrayList<zombie.characters.IsoGameCharacter>[] newEmitterCaptureArray(int size) {
      return new java.util.ArrayList[size];
   }

   /**
    * A batch task reached {@code updateEmitter}: queue the character for the join's drain and return true.
    * Off a batch task (or with {@code emitterDefer} off at dispatch) the slot is unset and the caller runs
    * the stock body in place — the inline path never defers.
    */
   public static boolean deferEmitter(zombie.characters.IsoGameCharacter character) {
      java.util.ArrayList<zombie.characters.IsoGameCharacter> list = EMITTER_CAPTURE.get();
      if (list == null) {
         return false;
      }

      list.add(character);
      emitterDeferred++;
      return true;
   }

   /**
    * Game thread, after the join: every task's queued emitter ticks through the real updateEmitter, in queue
    * order. {@code flightPomArr} is set on the combined path — that flight spans every bucket of the frame, so each
    * tick must land under its own entity's time scale — and null on the sync path, where the flight's single
    * {@code flightPom} covers every task, as it always did.
    */
   private static void drainEmitters(int n) {
      float[] h = POM.get(); // joinPending set it to flightPom already; this is the same holder array
      final float[] poms = flightPomArr;
      for (int i = 0; i < n; i++) {
         java.util.ArrayList<zombie.characters.IsoGameCharacter> list = emitterCaptures[i];
         if (list == null || list.isEmpty()) {
            continue;
         }

         h[0] = poms != null ? poms[i] : flightPom; // spec 3.5: stock's per-bucket multiplier per entity
         for (int j = 0; j < list.size(); j++) {
            list.get(j).updateEmitter(); // EMITTER_CAPTURE is unset on the game thread, so the stock body runs
            emitterDrained++;
         }

         list.clear();
      }
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

   /**
    * Game thread, after the join: every task's captured events through the real dispatch, in queue order, each
    * under its own entity's multiplier (the combined path's {@code flightPomArr} — see {@link #drainEmitters}).
    */
   private static void replayLuaEvents(int n) {
      float[] h = POM.get();
      final float[] poms = flightPomArr;
      for (int i = 0; i < n; i++) {
         java.util.ArrayList<Object[]> list = luaCaptures[i];
         if (list == null || list.isEmpty()) {
            continue;
         }

         h[0] = poms != null ? poms[i] : flightPom; // spec 3.5: the handlers of this entity's events see its time scale
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

   // ── the pipeline's one airborne batch (entityUpdatePipeline): dispatched without a join so the game
   // thread can collect the NEXT bucket while these tasks run; joined before the next dispatch (and after the
   // last bucket, from the scheduler override), so at most one batch is ever in flight — FrameBatch's own
   // one-batch rule, kept. The flight owns its entity array (double-buffered with the collection queue), its
   // dispatch-time perObjectMultiplier (replay runs under it) and its Lua capture count.
   private static IsoMovingObject[] spareQueue;
   private static IsoMovingObject[] flightArr;
   private static int flightN;
   private static float flightPom;
   private static boolean flightLuaReplay;
   private static boolean flightPending;
   private static volatile Throwable flightFailure;
   private static int snapAppend; // inline entities stamped into the in-flight snapshot append here

   /** True while a dispatched batch has not been joined yet. */
   public static boolean hasPendingBatch() {
      return flightPending;
   }

   // ── combined dispatch (spec 2026-09-27): the whole frame's batchables in one workers-only flight ──
   //
   // The per-bucket pipeline overlapped bucket N's flight with bucket N+1's collection, and the overlap window
   // was measurably empty: the dominant batch is FULL's and the buckets after it are trivial, so `async helped`
   // was ~100 % of `batched` — the game thread reached the join before the workers woke and did all the work
   // itself. On a combined frame every bucket only QUEUES: batchables here with their bucket's multiplier and
   // simulation level stamped at queue time, everything else into the inline queue. After the last bucket the
   // scheduler dispatches the whole frame at once and runs the inline entities while the flight is airborne —
   // that game-thread work is the runway — then joins at its tail, before updateZombieVocals().
   private static boolean combinedFrame; // latched once per frame (scheduler.update entry); never read from the wall clock mid-frame
   private static float[] queuePom = new float[4096];   // per-entity: its bucket's perObjectMultiplier at add()
   private static int[] queueLevel = new int[4096];     // per-entity: its bucket's simulationLevel ordinal
   private static float[] sparePom;                     // double-buffered with the flight, like spareQueue
   private static int[] spareLevel;
   private static float[] flightPomArr;                 // the airborne batch's per-task multipliers (replay/drain read these)
   private static int[] flightLevelArr;
   private static IsoMovingObject[] inlineQueue = new IsoMovingObject[1024];
   private static float[] inlinePom = new float[1024];
   private static int[] inlineLevel = new int[1024];
   private static int inlineCount;
   private static long nestedJoins, combinedFrames, inlineQueued;

   // values() allocates a fresh array per call, and the combined runner decodes a level per task — thousands of
   // tasks a frame. The enum is immutable, so one copy for the session.
   private static final UpdateSchedulerSimulationLevel[] LEVELS = UpdateSchedulerSimulationLevel.values();

   /**
    * Scheduler entry, once per frame: latch whether this frame runs the combined shape. Reading the wall
    * clock (devPipelineAlternate) once here means a window flip can never produce a mixed frame (spec 3.1).
    * The test seam takes the value directly.
    */
   public static void latchFrame(boolean combined) {
      combinedFrame = combined && enabled();
      if (combinedFrame) {
         clear();
         inlineCount = 0;
         combinedFrames++;
      }
   }

   /** The production latch: enabled() and pipelineOn() read once, at the scheduler's frame entry. */
   public static boolean latchFrameFromConfig() {
      latchFrame(pipelineOn());
      return combinedFrame;
   }

   /** True while this frame's buckets must queue instead of executing (spec 3.2). */
   public static boolean combinedFrame() {
      return combinedFrame;
   }

   /** Combined add: the entity plus its bucket's multiplier (the live global — the bucket just set it) and level. */
   public static void add(IsoMovingObject entity, int simulationLevelOrdinal) {
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
         queuePom = java.util.Arrays.copyOf(queuePom, count * 2);
         queueLevel = java.util.Arrays.copyOf(queueLevel, count * 2);
      }
      if (queuePom.length < queue.length) { // the sync-path add() grew queue alone in an earlier frame
         queuePom = java.util.Arrays.copyOf(queuePom, queue.length);
         queueLevel = java.util.Arrays.copyOf(queueLevel, queue.length);
      }

      queuePom[count] = zombie.GameTime.getInstance().perObjectMultiplier;
      queueLevel[count] = simulationLevelOrdinal;
      queue[count++] = entity;
   }

   /** Combined mode: an inline (non-batchable) entity, deferred to runInlinePhase under the flight (spec 3.4). */
   public static void queueInline(IsoMovingObject entity, int simulationLevelOrdinal) {
      if (inlineCount == inlineQueue.length) {
         inlineQueue = java.util.Arrays.copyOf(inlineQueue, inlineCount * 2);
         inlinePom = java.util.Arrays.copyOf(inlinePom, inlineCount * 2);
         inlineLevel = java.util.Arrays.copyOf(inlineLevel, inlineCount * 2);
      }

      inlinePom[inlineCount] = zombie.GameTime.getInstance().perObjectMultiplier;
      inlineLevel[inlineCount] = simulationLevelOrdinal;
      inlineQueue[inlineCount++] = entity;
      inlineQueued++;
   }

   /** The nested-batch guard's landing (spec 3.4): full bookkeeping, counted, dev-logged once. */
   public static void nestedJoin() {
      nestedJoins++;
      if (nestedJoins == 1L && Config.DEV) {
         Log.info("combined dispatch: nested FrameBatch use landed the flight first\n" + stackOf(new Throwable("caller")));
      }

      joinPending();
   }

   /** How many times a nested batch user landed the combined flight this session. */
   public static long getNestedJoins() {
      return nestedJoins;
   }

   /** Test seam: undo the failure latch between CombinedDispatchTest sections. */
   public static void resetFailedForTest() {
      failed = false;
   }

   private static long altWindow = Long.MIN_VALUE; // devPipelineAlternate: last logged window index

   /**
    * Whether this bucket dispatch uses the pipeline. Plain ENTITY_UPDATE_PIPELINE normally; with
    * devPipelineAlternate=N the answer flips every N seconds inside the run — same zombies, same spawn,
    * same thermals for both modes — and each flip is logged with its epoch so the frame log splits into
    * paired windows (drop the first window of each pair as warm-up when analysing).
    */
   public static boolean pipelineOn() {
      int alt = Config.DEV_PIPELINE_ALTERNATE;
      if (alt <= 0) {
         return Config.ENTITY_UPDATE_PIPELINE;
      }
      long now = System.currentTimeMillis();
      long window = now / (alt * 1000L);
      boolean on = (window & 1L) == 0L;
      if (window != altWindow) {
         altWindow = window;
         Log.info("pipeline-alt: window " + (on ? "on" : "off") + " @" + now);
      }
      return on;
   }

   /**
    * Game thread, at the end of one bucket's collection: run the collected entities' update sequence on the
    * workers and drain the queue. Returns false when there was nothing to do. The synchronous shape —
    * dispatch, then join — used when the pipeline key is off and by the JVM tests.
    */
   public static boolean run(UpdateSchedulerSimulationLevel level) {
      boolean dispatched = dispatchAsync(level);
      joinPending();
      return dispatched;
   }

   /**
    * Game thread: dispatch the collected entities to the workers WITHOUT joining (entityUpdatePipeline). The
    * caller keeps the game thread busy with the next bucket's collection and calls {@link #joinPending}
    * before the next dispatch. Any batch still airborne is joined here first, so two can never overlap.
    */
   public static boolean dispatchAsync(UpdateSchedulerSimulationLevel level) {
      joinPending();
      int n = count;
      if (n == 0) {
         return false;
      }

      frames++;
      batched += n;
      if (n > maxBatch) {
         maxBatch = n;
      }

      // Freeze the queued entities' positions BEFORE the volatile write to inFlight below: that ordered pair is
      // the happens-before that lets workers read the arrays and stamps without a lock. INLINE_SLACK reserves
      // room for the next bucket's inline entities (players, vehicles, animals) to stamp their post-update
      // positions in while this batch flies — the arrays never grow mid-flight.
      snapshotFrame++;
      if (snapX.length < n + INLINE_SLACK) {
         int size = Math.max(n + INLINE_SLACK, snapX.length * 2);
         snapX = new float[size];
         snapY = new float[size];
         snapZ = new float[size];
      }
      float[] sx = snapX;
      float[] sy = snapY;
      float[] sz = snapZ;
      long frame = snapshotFrame;
      final IsoMovingObject[] q = queue; // the flight owns THIS array; the next collection gets the spare
      for (int i = 0; i < n; i++) {
         IsoMovingObject entity = q[i];
         sx[i] = entity.getX(); // still live here: inFlight is false until the write below
         sy[i] = entity.getY();
         sz[i] = entity.getZ();
         entity.pzoptSnapshotIndex = i;
         entity.pzoptSnapshotFrame = frame;
      }
      snapAppend = n;

      boolean luaReplay = Config.ENTITY_UPDATE_LUA_REPLAY;
      if (luaReplay && luaCaptures.length < n) {
         luaCaptures = java.util.Arrays.copyOf(luaCaptures, Math.max(n, luaCaptures.length * 2));
      }
      final boolean flightDefer = Config.EMITTER_DEFER;
      if (flightDefer && emitterCaptures.length < n) {
         emitterCaptures = java.util.Arrays.copyOf(emitterCaptures, Math.max(n, emitterCaptures.length * 2));
      }

      // The bucket's frame mod, captured at dispatch: the game thread moves on to the next bucket — and
      // rewrites the global perObjectMultiplier — while these tasks still run, so every task reads THIS value
      // through pom() instead of the live field (the five consumer sites are the GameTime getters,
      // FrameDelay.update and IsoZombie.allowsInvisibleAnimationSkips; PerObjectMultiplierTest).
      final float pomAtDispatch = zombie.GameTime.getInstance().perObjectMultiplier;
      final boolean flightReplay = luaReplay;

      flightFailure = null;
      inFlight = true; // onWorkerNow() and frozen(): the workers are running this batch's entities from here to joinPending
      FrameBatch.runAsync(n, i -> {
         IsoMovingObject entity = q[i];
         CURRENT.set(entity); // frozen(): this task's entity reads itself live, everyone else frozen
         POM.get()[0] = pomAtDispatch; // pom(): this task reads the dispatch-time multiplier
         java.util.ArrayList<Object[]> capture = null;
         if (flightReplay) {
            capture = luaCaptures[i];
            if (capture == null) {
               capture = new java.util.ArrayList<>();
               luaCaptures[i] = capture; // published to the game thread by the join
            }
            LUA_CAPTURE.set(capture); // captureLuaEvent appends here for this task
         }
         java.util.ArrayList<zombie.characters.IsoGameCharacter> emitterSlot = null;
         if (flightDefer) {
            emitterSlot = emitterCaptures[i];
            if (emitterSlot == null) {
               emitterSlot = new java.util.ArrayList<>();
               emitterCaptures[i] = emitterSlot; // published to the game thread by the join
            }
            EMITTER_CAPTURE.set(emitterSlot); // deferEmitter queues here for this task
         }
         try {
            entity.setCurrentSimulationLevel(level);
            entity.preupdate();
            entity.frameStep();
            entity.update();
         } finally {
            CURRENT.set(null); // a pooled worker must not carry the reference into the next task
            POM.get()[0] = Float.NaN; // pom() follows the live field again off-task
            if (capture != null) {
               LUA_CAPTURE.set(null);
            }
            if (emitterSlot != null) {
               EMITTER_CAPTURE.set(null);
            }
         }
      }, fx -> flightFailure = fx);

      // Hand the owned array to the flight and swap the spare in for the next bucket's collection: the
      // workers iterate q while add() fills a different array, so the two never race.
      flightArr = q;
      flightN = n;
      flightPom = pomAtDispatch;
      flightLuaReplay = flightReplay;
      flightPending = true;
      queue = spareQueue != null ? spareQueue : new IsoMovingObject[q.length];
      spareQueue = null;
      count = 0;
      return true;
   }

   /**
    * Combined dispatch (spec 3.3): the whole frame's batchables in one workers-only flight. Each task runs
    * under ITS entity's simulation level and multiplier (queueLevel/queuePom, stamped at add) instead of one
    * level and one multiplier for the flight, because this batch spans every bucket of the frame. The snapshot
    * is sized n + inlineCount + 64 — the walk finished before this call, so the inline count is exact rather
    * than the pipeline's fixed INLINE_SLACK guess.
    */
   public static boolean dispatchCombined() {
      joinPending();
      int n = count;
      if (n == 0) {
         return false;
      }

      frames++;
      batched += n;
      if (n > maxBatch) {
         maxBatch = n;
      }

      snapshotFrame++;
      int need = n + inlineCount + 64;
      if (snapX.length < need) {
         int size = Math.max(need, snapX.length * 2);
         snapX = new float[size];
         snapY = new float[size];
         snapZ = new float[size];
      }
      float[] sx = snapX;
      float[] sy = snapY;
      float[] sz = snapZ;
      long frame = snapshotFrame;
      final IsoMovingObject[] q = queue; // the flight owns THESE three arrays; the next frame collects into the spares
      final float[] poms = queuePom;
      final int[] levels = queueLevel;
      for (int i = 0; i < n; i++) {
         IsoMovingObject entity = q[i];
         sx[i] = entity.getX(); // still live here: inFlight is false until the write below
         sy[i] = entity.getY();
         sz[i] = entity.getZ();
         entity.pzoptSnapshotIndex = i;
         entity.pzoptSnapshotFrame = frame;
      }
      snapAppend = n;

      boolean luaReplay = Config.ENTITY_UPDATE_LUA_REPLAY;
      if (luaReplay && luaCaptures.length < n) {
         luaCaptures = java.util.Arrays.copyOf(luaCaptures, Math.max(n, luaCaptures.length * 2));
      }
      final boolean flightDefer = Config.EMITTER_DEFER;
      if (flightDefer && emitterCaptures.length < n) {
         emitterCaptures = java.util.Arrays.copyOf(emitterCaptures, Math.max(n, emitterCaptures.length * 2));
      }
      final boolean flightReplay = luaReplay;

      flightFailure = null;
      inFlight = true; // onWorkerNow() and frozen(): the workers are running this batch's entities from here to joinPending
      FrameBatch.runAsync(n, i -> {
         IsoMovingObject entity = q[i];
         CURRENT.set(entity); // frozen(): this task's entity reads itself live, everyone else frozen
         POM.get()[0] = poms[i]; // spec 3.3: per-task, not per-flight — this flight spans every bucket of the frame
         java.util.ArrayList<Object[]> capture = null;
         if (flightReplay) {
            capture = luaCaptures[i];
            if (capture == null) {
               capture = new java.util.ArrayList<>();
               luaCaptures[i] = capture; // published to the game thread by the join
            }
            LUA_CAPTURE.set(capture); // captureLuaEvent appends here for this task
         }
         java.util.ArrayList<zombie.characters.IsoGameCharacter> emitterSlot = null;
         if (flightDefer) {
            emitterSlot = emitterCaptures[i];
            if (emitterSlot == null) {
               emitterSlot = new java.util.ArrayList<>();
               emitterCaptures[i] = emitterSlot; // published to the game thread by the join
            }
            EMITTER_CAPTURE.set(emitterSlot); // deferEmitter queues here for this task
         }
         try {
            entity.setCurrentSimulationLevel(LEVELS[levels[i]]);
            entity.preupdate();
            entity.frameStep();
            entity.update();
         } finally {
            CURRENT.set(null); // a pooled worker must not carry the reference into the next task
            POM.get()[0] = Float.NaN; // pom() follows the live field again off-task
            if (capture != null) {
               LUA_CAPTURE.set(null);
            }
            if (emitterSlot != null) {
               EMITTER_CAPTURE.set(null);
            }
         }
      }, fx -> flightFailure = fx);

      flightArr = q;
      flightN = n;
      flightPomArr = poms;
      flightLevelArr = levels;
      flightPom = Float.NaN; // scalar unused on the combined path: the drains read flightPomArr per task
      flightLuaReplay = flightReplay;
      flightPending = true;
      // Swap the spares in for the next frame's collection, keeping the invariant the stamped add() relies on:
      // queuePom / queueLevel are never shorter than queue (a sync-path dispatchAsync recycles the entity array
      // alone, so the three can arrive here with different lengths).
      queue = spareQueue != null ? spareQueue : new IsoMovingObject[q.length];
      queuePom = sparePom != null && sparePom.length >= queue.length ? sparePom : new float[queue.length];
      queueLevel = spareLevel != null && spareLevel.length >= queue.length ? spareLevel : new int[queue.length];
      spareQueue = null;
      sparePom = null;
      spareLevel = null;
      count = 0;
      return true;
   }

   /**
    * Game thread, after dispatchCombined (spec 3.4): the frame's inline entities under the flight. Per entry
    * the stock four calls under its bucket's multiplier (the live global — pom() on the game thread resolves
    * through it, the POM holder stays NaN here by construction), stampInline publishing its post-update
    * position into the airborne snapshot. Global back to 1.0 after, stock's guarantee.
    */
   public static void runInlinePhase() {
      zombie.GameTime gt = zombie.GameTime.getInstance();
      for (int i = 0; i < inlineCount; i++) {
         IsoMovingObject entity = inlineQueue[i];
         gt.perObjectMultiplier = inlinePom[i];
         entity.setCurrentSimulationLevel(LEVELS[inlineLevel[i]]);
         entity.preupdate();
         entity.frameStep();
         entity.update();
         stampInline(entity);
      }

      gt.perObjectMultiplier = 1.0F;
      java.util.Arrays.fill(inlineQueue, 0, inlineCount, null);
      inlineCount = 0;
   }

   /**
    * Game thread: wait for the airborne batch, then land its window — deferred tile updates, the Lua replay
    * under ITS dispatch-time multiplier (the game thread's global may already be the next bucket's), the
    * failure latch. No-op without a pending batch.
    */
   public static void joinPending() {
      if (!flightPending) {
         return;
      }

      long j0 = System.nanoTime();
      FrameBatch.join(); // runs the completion above on this thread: flightFailure is set past here
      waitNanos += System.nanoTime() - j0; // the game thread's cost of this batch IS the join wait: with the
      // pipeline it did the next bucket's collection instead of task work, so work ms stays ~0 by design
      inFlight = false;
      int n = flightN;

      // The window's latched setMovingSquare calls, applied in one place on the game thread — also after a
      // failed batch, so a partially updated frame still lands its tile updates instead of leaking them.
      applyDeferredSquares();

      // The window's deferred emitter ticks, then its captured Lua dispatches, both in queue order — stock's
      // serial order — under the multiplier the batch dispatched with, so the ticks and the handlers see the
      // same time scale as stock's inline run. Also after a failed batch: what deferred before the throw
      // would have run in stock's semantics too.
      float[] h = POM.get();
      h[0] = flightPom; // NaN on the combined path: pom() falls through to the live global until a walker sets a task's own
      try {
         drainEmitters(n); // before the replay: a handler reading a zombie's sound state sees the ticked emitter
         if (flightLuaReplay) {
            replayLuaEvents(n);
         }
      } finally {
         h[0] = Float.NaN;
      }
      Throwable t = flightFailure;
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

      java.util.Arrays.fill(flightArr, 0, n, null);
      spareQueue = flightArr; // the next dispatch collects into it
      flightArr = null;
      sparePom = flightPomArr; // symmetric with the entity array: the combined path recycles all three buffers
      spareLevel = flightLevelArr; // (both null after a sync-path flight, which never took them)
      flightPomArr = null;
      flightLevelArr = null;
      flightPending = false;
   }

   /** Room reserved past the queued block for inline entities stamped mid-flight (players, vehicles, animals). */
   private static final int INLINE_SLACK = 512;

   /**
    * Game thread, from the bucket's loop, right after an inline (non-batchable) entity finished its four
    * calls while a batch is airborne: stamp its post-update position into the in-flight snapshot so the
    * workers read a stable value — exactly what they saw when inline entities all ran before the dispatch.
    * Bounded by INLINE_SLACK; past it the entity just stays live (the pre-snapshot behaviour).
    */
   public static void stampInline(IsoMovingObject entity) {
      if (!flightPending || snapAppend >= snapX.length) {
         return;
      }
      int i = snapAppend++;
      snapX[i] = entity.getX();
      snapY[i] = entity.getY();
      snapZ[i] = entity.getZ();
      entity.pzoptSnapshotIndex = i;
      entity.pzoptSnapshotFrame = snapshotFrame; // published by the plain writes: a torn read just means one more live read
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
            + " emitterDeferred=" + emitterDeferred + " emitterDrained=" + emitterDrained
            + " luaSuppressed=" + luaSuppressed.get() + " pathfindRaceSkipped=" + pathfindRaceSkipped.get()
            + " movingSquareDeferred=" + movingSquareDeferred
            + " combinedFrames=" + combinedFrames + " inlineQueued=" + inlineQueued
            + " nestedJoins=" + nestedJoins + " preClaimed=" + FrameBatch.lastPreClaimed()
            + (failed ? " FAILED" : "");
   }
}

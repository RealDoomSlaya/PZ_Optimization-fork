package pzopt;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * {@code pzopt.UpdateBatch.onWorkerNow()}: the one predicate every thread-safety guard of {@code entityUpdateParallel}
 * keys on, and the first-failure stack trace.
 *
 * <p>The contract, pinned here because five guards (ForwardDirection, LuaEventManager, PathFindBehavior2 twice, and
 * whatever comes next) all call it:
 * <ul>
 *   <li>false on the game thread, always — including while a batch is in flight, because the game thread works the
 *       batch too and an entity it updates must behave exactly as with the key off;
 *   <li>true on a {@code FrameBatch} worker only while an {@code UpdateBatch} run is in flight;
 *   <li>false on the same workers when any other batch runs on them ({@code AnimBatch} / {@code ActionEval} /
 *       {@code LightingBatch} share the pool but never call Lua by design — their behaviour must not change).
 * </ul>
 *
 * <p>Also pinned: the first entity failure logs the throwable's full stack trace (the live
 * {@code ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 2} at frame 10 arrived with no call site
 * because only {@code t.toString()} was logged), and a repeat failure logs the one-line summary.
 */
public class WorkerNowTest {

   /** A real IsoMovingObject; {@code super(false)} skips the cell registration, as in UpdateBatchTest. */
   static final class Probe extends IsoMovingObject {
      final int index;
      final boolean throwOnUpdate;
      volatile boolean sawWorkerNow;
      volatile boolean ranOnWorker;
      volatile boolean ran;

      Probe(int index, boolean throwOnUpdate) {
         super(false);
         this.index = index;
         this.throwOnUpdate = throwOnUpdate;
      }

      @Override
      public void setCurrentSimulationLevel(UpdateSchedulerSimulationLevel level) {
      }

      @Override
      public void preupdate() {
      }

      @Override
      public void frameStep() {
      }

      @Override
      public void update() {
         this.sawWorkerNow = UpdateBatch.onWorkerNow();
         this.ranOnWorker = Thread.currentThread() instanceof FrameBatch.Worker;
         this.ran = true;
         // Nudge the batch across threads: without a little work the calling thread drains the whole queue
         // alone (the workers park between batches), same trick as UpdateBatchTest.
         if ((this.index & 511) == 0) {
            try {
               Thread.sleep(1);
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
            }
         }
         if (this.throwOnUpdate) {
            throw new IllegalStateException("probe update failed for the stack-trace pin");
         }
      }
   }

   public static void main(String[] args) throws Exception {
      UpdateSchedulerSimulationLevel level = UpdateSchedulerSimulationLevel.FULL;

      // ── never on the game thread outside a batch ──
      Check.check(!UpdateBatch.onWorkerNow(), "onWorkerNow() is false on the game thread outside a batch");

      // ── true on a worker exactly while an UpdateBatch run is in flight, false on the game thread inside it ──
      int n = 5000;
      Probe[] probes = new Probe[n];
      for (int i = 0; i < n; i++) {
         probes[i] = new Probe(i, false);
         UpdateBatch.add(probes[i]);
      }
      UpdateBatch.run(level);
      Check.check(!UpdateBatch.onWorkerNow(), "onWorkerNow() is false on the game thread right after a batch");

      int onWorker = 0;
      int onGameThread = 0;
      for (Probe p : probes) {
         Check.check(p.ran, "probe " + p.index + " ran");
         if (p.ranOnWorker) {
            onWorker++;
            Check.check(p.sawWorkerNow, "probe " + p.index + " on a worker mid-batch sees onWorkerNow() true");
         } else {
            onGameThread++;
            Check.check(!p.sawWorkerNow,
                  "probe " + p.index + " updated by the game thread mid-batch sees onWorkerNow() false:"
                        + " a game-thread entity must behave exactly as with the key off");
         }
      }
      Check.check(onWorker > 0, "the batch put entities on the workers: " + onWorker);
      Check.check(onGameThread > 0, "the game thread worked the batch too: " + onGameThread);

      // ── the other batches share the same workers and must not trip the guards ──
      AtomicInteger tripped = new AtomicInteger();
      AtomicInteger workerTasks = new AtomicInteger();
      Throwable t = FrameBatch.run(64, i -> {
         if (Thread.currentThread() instanceof FrameBatch.Worker) {
            workerTasks.incrementAndGet();
         }
         if (UpdateBatch.onWorkerNow()) {
            tripped.incrementAndGet();
         }
         Thread.sleep(1);
      });
      Check.check(t == null, "the plain FrameBatch batch ran clean");
      Check.check(workerTasks.get() > 0, "the plain batch used the workers: " + workerTasks.get());
      Check.check(tripped.get() == 0,
            "onWorkerNow() stays false during a non-entity batch on the same workers (AnimBatch/ActionEval/"
                  + "LightingBatch must be unaffected), tripped " + tripped.get() + " times");

      // ── the first failure arrives with its stack trace, a repeat with the one-line summary ──
      Check.check(!UpdateBatch.hasFailed(), "no failure latched yet");
      String first = captureFailingRun(level);
      Check.check(first.contains("batching off"), "the first failure is reported: " + first);
      Check.check(first.contains("at pzopt.WorkerNowTest"),
            "the first failure logs the throwable's full stack trace (the frame-10 AIOOBE had no call site), got: "
                  + first);
      Check.check(UpdateBatch.hasFailed(), "the failure latched");

      String repeat = captureFailingRun(level);
      Check.check(repeat.contains("batching off"), "a repeat failure still gets the one-line summary: " + repeat);
      Check.check(!repeat.contains("\tat "),
            "a repeat failure logs one line, not another stack trace, got: " + repeat);

      System.out.println("WorkerNowTest ok");
   }

   /** Runs a batch with one throwing probe under a captured System.out and returns what was logged. */
   private static String captureFailingRun(UpdateSchedulerSimulationLevel level) {
      for (int i = 0; i < 64; i++) {
         UpdateBatch.add(new Probe(i, i == 13));
      }
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      PrintStream old = System.out;
      System.setOut(new PrintStream(buf, true));
      try {
         UpdateBatch.run(level);
      } finally {
         System.setOut(old);
      }
      return buf.toString();
   }
}

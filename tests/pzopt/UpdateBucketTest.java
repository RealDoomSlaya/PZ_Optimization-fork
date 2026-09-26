package pzopt;

import java.util.HashSet;
import java.util.Set;
import zombie.MovingObjectUpdateSchedulerUpdateBucket;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * The real simulation bucket, driven both ways: {@code entityUpdateParallel} off must behave exactly like stock,
 * and on must produce the same per-entity result off the game thread.
 *
 * <p>Run twice by the harness — once with {@code -Dpzopt.entityUpdateParallel=false} and once with {@code true} —
 * and it asserts against whichever mode it finds itself in, so neither run can pass vacuously: the serial run fails
 * if work escapes to a worker, the parallel run fails if it never does.
 *
 * <p>The parity that matters is the call sequence. Stock's loop does exactly
 * {@code setCurrentSimulationLevel}, {@code preupdate}, {@code frameStep}, {@code update} per entity; the batch has
 * to do the same four, once each, whichever thread runs them.
 */
public class UpdateBucketTest {

   static final class Probe extends IsoMovingObject {
      final StringBuilder calls = new StringBuilder();
      volatile String thread;
      private final int index;

      Probe(int index) {
         super(false);
         this.index = index;
      }

      @Override
      public void setCurrentSimulationLevel(UpdateSchedulerSimulationLevel level) {
         record("S");
      }

      @Override
      public void preupdate() {
         record("P");
      }

      @Override
      public void frameStep() {
         record("F");
      }

      @Override
      public void update() {
         record("U");
         this.thread = Thread.currentThread().getName();
         if ((this.index & 255) == 0) {
            try {
               Thread.sleep(1); // give the parked workers time to wake; see FrameBatchTest
            } catch (InterruptedException e) {
               Thread.currentThread().interrupt();
            }
         }
      }

      private void record(String step) {
         synchronized (this.calls) {
            this.calls.append(step);
         }
      }

      String sequence() {
         synchronized (this.calls) {
            return this.calls.toString();
         }
      }
   }

   public static void main(String[] args) {
      // UpdateBatch.enabled() reads GameClient.client / GameServer.server, as ActionEval does. Touching those
      // classes runs ServerOptions' initializer, which draws from Rand — already seeded long before the scheduler
      // runs in the game, but not in a bare JVM. Seed it here rather than weaken the production guard.
      zombie.core.random.RandStandard.INSTANCE.init();

      boolean batching = UpdateBatch.enabled();
      System.out.println("UpdateBucketTest: entityUpdateParallel=" + Config.ENTITY_UPDATE_PARALLEL
            + " overrides=" + Overrides.enabled() + " workers=" + Config.effectiveWorkers()
            + " -> batching=" + batching);

      MovingObjectUpdateSchedulerUpdateBucket bucket =
            new MovingObjectUpdateSchedulerUpdateBucket(UpdateSchedulerSimulationLevel.FULL);

      int n = 2000;
      Probe[] probes = new Probe[n];
      for (int i = 0; i < n; i++) {
         probes[i] = new Probe(i);
         bucket.add(probes[i]);
      }

      // FULL has a frame mod of 1, so frame 0 holds every entity that was added.
      Check.check(bucket.getBucket(0).size() == n,
            "the bucket holds every entity at frame 0: " + bucket.getBucket(0).size());

      bucket.update(0);

      Set<String> threads = new HashSet<>();
      for (int i = 0; i < n; i++) {
         Check.check("SPFU".equals(probes[i].sequence()),
               "entity " + i + " ran setCurrentSimulationLevel, preupdate, frameStep, update once and in that"
                     + " order, ran \"" + probes[i].sequence() + "\"");
         threads.add(probes[i].thread);
      }

      if (batching) {
         Check.check(threads.size() > 1,
               "with entityUpdateParallel the bucket spreads its entities over the workers, used: " + threads);
      } else {
         Check.check(threads.size() == 1 && threads.contains(Thread.currentThread().getName()),
               "without the key every entity stays on the calling thread, used: " + threads);
      }

      // The multiplier the bucket published for its loop must be handed back, batched or not: the scheduler runs
      // the next bucket straight after this one, and stock leaves it at 1.
      Check.check(zombie.GameTime.getInstance().perObjectMultiplier == 1.0F,
            "perObjectMultiplier is restored to 1 after the bucket, was "
                  + zombie.GameTime.getInstance().perObjectMultiplier);

      Check.check(UpdateBatch.pending() == 0, "the batch queue is drained after the bucket");

      System.out.println("UpdateBucketTest ok (" + (batching ? "parallel" : "serial") + ")");
   }
}

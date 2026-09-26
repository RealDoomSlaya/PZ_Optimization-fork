package pzopt;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * entityUpdateParallel's position snapshot: while a batch is in flight, a cross-entity {@code getX()/getY()/getZ()}
 * returns the position frozen at the start of the batch, a self-read stays live, and everything is live again the
 * moment {@code run()} returns.
 *
 * <p>Why: the measured residue of the first live A/B was 20 zero-length ForwardDirection throws on the GAME thread —
 * a worker mid-update read another entity's position while a second worker was writing it, and the torn value
 * surfaced a frame later. PZMulticore's v1.4 fix (IsoMovingObjectPatcher) freezes cross-entity reads for the window;
 * this is that layer, pzopt-shaped: the snapshot covers exactly the queued entities (nothing else moves during the
 * window — players and animals ran inline before {@code run()}), validity is a per-entity frame stamp (no clear
 * pass), and the happens-before is the {@code inFlight} volatile write after population.
 *
 * <p>Determinism: entity A moves itself and signals; entity B waits for the signal before reading A. Under live
 * reads B must see the moved position, under frozen reads the pre-batch one — no scheduling luck involved. The
 * await has a timeout so a broken batch fails instead of hanging; FrameBatch always has the calling thread plus at
 * least one worker, so A's task cannot starve behind B's wait.
 */
public class PositionSnapshotTest {

   /** A real IsoMovingObject whose update() is the test's script. {@code super(false)} skips cell registration. */
   static final class Mover extends IsoMovingObject {
      Runnable onUpdate;

      Mover(float x, float y, float z) {
         super(false);
         this.setX(x);
         this.setY(y);
         this.setZ(z);
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
         if (this.onUpdate != null) {
            this.onUpdate.run();
         }
      }
   }

   public static void main(String[] args) throws Exception {
      zombie.core.random.RandStandard.INSTANCE.init();
      UpdateSchedulerSimulationLevel level = UpdateSchedulerSimulationLevel.FULL;

      // ── a cross-entity read mid-batch is the frozen pre-batch position; a self-read is live ──
      Mover a = new Mover(10f, 20f, 3f);
      Mover b = new Mover(50f, 60f, 1f);
      CountDownLatch aMoved = new CountDownLatch(1);
      float[] seenOfA = new float[3];
      boolean[] selfLive = {false};

      a.onUpdate = () -> {
         a.setX(a.getX() + 5f);
         a.setY(a.getY() + 5f);
         a.setZ(a.getZ() + 5f);
         // The entity mid-update must see its own writes: its movement math depends on it.
         selfLive[0] = a.getX() == 15f && a.getY() == 25f && a.getZ() == 8f;
         aMoved.countDown();
      };
      b.onUpdate = () -> {
         try {
            if (!aMoved.await(5, TimeUnit.SECONDS)) {
               seenOfA[0] = Float.NaN; // never happened: fails the frozen check below with a visible value
               return;
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
         }
         seenOfA[0] = a.getX();
         seenOfA[1] = a.getY();
         seenOfA[2] = a.getZ();
      };

      UpdateBatch.clear();
      UpdateBatch.add(a);
      UpdateBatch.add(b);
      Check.check(UpdateBatch.run(level), "the batch ran");
      Check.check(selfLive[0], "an entity mid-update reads its own moved position live");
      Check.check(seenOfA[0] == 10f && seenOfA[1] == 20f && seenOfA[2] == 3f,
            "a cross-entity read mid-batch is the frozen pre-batch position, saw "
                  + seenOfA[0] + "/" + seenOfA[1] + "/" + seenOfA[2] + " wanted 10/20/3");

      // ── the moment run() returns, reads are live again ──
      Check.check(a.getX() == 15f && a.getY() == 25f && a.getZ() == 8f,
            "after the join the moved position is the live one: " + a.getX() + "/" + a.getY() + "/" + a.getZ());

      // ── an entity from an earlier batch is stale: its old slot now holds someone else's data ──
      // a is not queued this time; its old index 0 belongs to b in this batch. A staleness bug would serve
      // b's position (50) for a; the frame stamp must route a to its live value (15).
      float[] seenStale = {Float.NaN};
      b.onUpdate = () -> seenStale[0] = a.getX();
      UpdateBatch.clear();
      UpdateBatch.add(b);
      Check.check(UpdateBatch.run(level), "the second batch ran");
      Check.check(seenStale[0] == 15f,
            "a stale snapshot index from an earlier batch reads live, saw " + seenStale[0] + " wanted 15");

      // ── an entity never batched reads live during a window ──
      Mover c = new Mover(70f, 80f, 2f);
      float[] seenOfC = {Float.NaN};
      b.onUpdate = () -> seenOfC[0] = c.getX();
      UpdateBatch.clear();
      UpdateBatch.add(b);
      Check.check(UpdateBatch.run(level), "the third batch ran");
      Check.check(seenOfC[0] == 70f, "a never-batched entity reads live during the window: " + seenOfC[0]);

      System.out.println("PositionSnapshotTest ok");
   }
}

package pzopt;

import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;

/**
 * entityUpdateParallel's tile-occupancy deferral: {@code IsoMovingObject.setMovingSquare} mutates the target
 * square's shared {@code MovingObjects} ArrayList — two workers landing entities on the same square is a plain
 * list race. During the batch window a call from a batched entity's update is latched instead of applied, and the
 * game thread replays the last latched square per entity right after the join, so the end state per frame is
 * byte-for-byte what stock's serial loop would have left.
 *
 * <p>This deliberately goes one step past the PZMulticore reference, which skips the call outright: there the
 * whole update ran in the deferred window every frame and the only other writer, {@code postupdate()}'s
 * {@code setMovingSquare(this.current)}, does not exist as a game-thread re-apply for its dispatcher — a pzopt
 * batch CAN replay on the game thread, so it does, and nothing goes stale.
 */
public class MovingSquareDeferralTest {

   static final class Mover extends IsoMovingObject {
      Runnable onUpdate;

      Mover() {
         super(false);
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
      IsoGridSquare sq1 = new IsoGridSquare(null, null, 100, 100, 0);
      IsoGridSquare sq2 = new IsoGridSquare(null, null, 101, 100, 0);

      // ── outside a batch the call is stock: applied immediately ──
      Mover outside = new Mover();
      outside.setMovingSquare(sq1);
      Check.check(outside.getMovingSquare() == sq1, "outside a batch setMovingSquare applies immediately");
      Check.check(sq1.getMovingObjects().contains(outside), "outside a batch the square list is updated");
      outside.setMovingSquare(null);
      Check.check(!sq1.getMovingObjects().contains(outside), "clearing outside a batch removes from the list");

      // ── during the window the call is latched, the shared list untouched; the join applies it ──
      Mover m = new Mover();
      boolean[] deferred = {false, false};
      m.onUpdate = () -> {
         m.setMovingSquare(sq1);
         deferred[0] = m.getMovingSquare() != sq1;            // still the old value on the worker
         deferred[1] = !sq1.getMovingObjects().contains(m);   // the shared list was not touched mid-window
      };
      UpdateBatch.clear();
      UpdateBatch.add(m);
      Check.check(UpdateBatch.run(level), "the batch ran");
      Check.check(deferred[0], "setMovingSquare mid-batch is deferred: movingSq unchanged on the worker");
      Check.check(deferred[1], "the square's shared MovingObjects list is untouched during the window");
      Check.check(m.getMovingSquare() == sq1, "after the join the deferred square is applied");
      Check.check(sq1.getMovingObjects().contains(m), "after the join the entity is in the square's list");

      // ── two calls in one update: last one wins, and the end state matches stock's serial outcome ──
      Mover twice = new Mover();
      twice.onUpdate = () -> {
         twice.setMovingSquare(sq1);
         twice.setMovingSquare(sq2);
      };
      UpdateBatch.clear();
      UpdateBatch.add(twice);
      Check.check(UpdateBatch.run(level), "the two-call batch ran");
      Check.check(twice.getMovingSquare() == sq2, "the last square set mid-update is the one applied");
      Check.check(!sq1.getMovingObjects().contains(twice), "the intermediate square never holds the entity");
      Check.check(sq2.getMovingObjects().contains(twice), "the final square holds the entity");

      // ── a batched update touching ANOTHER entity's square is deferred too (the callee need not be queued) ──
      Mover toucher = new Mover();
      Mover bystander = new Mover();
      boolean[] bystanderDeferred = {false};
      toucher.onUpdate = () -> {
         bystander.setMovingSquare(sq1);
         bystanderDeferred[0] = bystander.getMovingSquare() != sq1;
      };
      UpdateBatch.clear();
      UpdateBatch.add(toucher);
      Check.check(UpdateBatch.run(level), "the bystander batch ran");
      Check.check(bystanderDeferred[0], "a non-queued entity written from a batched update is deferred too");
      Check.check(bystander.getMovingSquare() == sq1, "and its deferred square is applied after the join");
      Check.check(sq1.getMovingObjects().contains(bystander), "and it lands in the square's list after the join");

      // ── after the window everything is stock again ──
      Mover after = new Mover();
      after.setMovingSquare(sq2);
      Check.check(after.getMovingSquare() == sq2, "a fresh call after the batch applies immediately again");

      System.out.println("MovingSquareDeferralTest ok");
   }
}

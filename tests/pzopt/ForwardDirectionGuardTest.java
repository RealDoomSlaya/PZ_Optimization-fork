package pzopt;

import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * The zero-length {@code setForwardDirection(float, float)} throw, guarded on the workers
 * ({@code entityUpdateParallel}; the IsoGameCharacter override).
 *
 * <p>The live 4,220-zombie run died with {@code IllegalStateException: Forward Direction cannot be zero length
 * vector} out of {@code WalkTowardState.execute} on a {@code pzopt-frame-} worker: torn position reads make the
 * walk delta zero-length, vanilla treats that as a programming error and throws, and one throw latches the whole
 * batch off. On a worker the degenerate case is expected — the character keeps its previous direction and the
 * next frame recomputes — so the override returns silently there. Ported from PZMulticore's
 * ForwardDirectionPatcher, but guarded on {@code onWorkerNow()} instead of unconditional: pzopt's output-parity
 * discipline keeps the game-thread path throwing exactly like vanilla, with the key off and on.
 *
 * <p>Vanilla's mutation order is preserved either way: the method writes {@code forwardDirection}, normalizes and
 * sets the iso direction BEFORE the zero-length check, and PZMulticore's patch (ATHROW replaced by POP+RETURN)
 * kept those writes too — the guard changes only whether the throw happens.
 */
public class ForwardDirectionGuardTest {

   /** A real IsoGameCharacter; cell null and x=y=z=0 skips the cell registration (as in ItemVisualsScratchTest). */
   static final class Chr extends zombie.characters.IsoGameCharacter {
      Chr() {
         super(null, 0.0F, 0.0F, 0.0F);
      }
   }

   /** A batchable probe: its update() drives the zero-length call on whatever thread the batch put it on. */
   static final class Probe extends IsoMovingObject {
      final int index;
      final Chr chr = new Chr();
      volatile boolean ranOnWorker;
      volatile boolean threw;
      volatile boolean ran;

      Probe(int index) {
         super(false);
         this.index = index;
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
         this.ranOnWorker = Thread.currentThread() instanceof FrameBatch.Worker;
         try {
            this.chr.setForwardDirection(0.0F, 0.0F);
            this.threw = false;
         } catch (IllegalStateException e) {
            this.threw = true;
         }
         this.ran = true;
         // spread the batch across threads (the workers park between batches; same trick as UpdateBatchTest)
         try {
            Thread.sleep(1);
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
   }

   public static void main(String[] args) throws Exception {
      // IsoGameCharacter's constructor draws its patience from Rand; seeded long before this runs in the game.
      zombie.core.random.RandStandard.INSTANCE.init();
      zombie.core.Core.soundDisabled = true;

      // ── the game thread outside a batch still throws: vanilla behaviour pinned byte for byte ──
      Chr serial = new Chr();
      boolean threwSerial = false;
      try {
         serial.setForwardDirection(0.0F, 0.0F);
      } catch (IllegalStateException e) {
         threwSerial = true;
         Check.check(e.getMessage() != null && e.getMessage().contains("zero length"),
               "vanilla's message survives: " + e.getMessage());
      }
      Check.check(threwSerial, "a zero-length setForwardDirection on the game thread still throws (vanilla path)");

      // ── a non-zero direction still lands, so the method is otherwise untouched ──
      serial.setForwardDirection(0.0F, 1.0F);

      // ── on a worker mid-batch the same call returns silently instead of killing the batch ──
      int n = 192;
      Probe[] probes = new Probe[n];
      for (int i = 0; i < n; i++) {
         probes[i] = new Probe(i);
         UpdateBatch.add(probes[i]);
      }
      UpdateBatch.run(UpdateSchedulerSimulationLevel.FULL);

      int onWorker = 0;
      int onGameThread = 0;
      for (Probe p : probes) {
         Check.check(p.ran, "probe " + p.index + " ran");
         if (p.ranOnWorker) {
            onWorker++;
            Check.check(!p.threw,
                  "probe " + p.index + " on a worker mid-batch: the zero-length call returns silently"
                        + " (a torn position read is expected there, not a programming error)");
         } else {
            onGameThread++;
            Check.check(p.threw,
                  "probe " + p.index + " updated by the game thread mid-batch still gets vanilla's throw");
         }
      }
      Check.check(onWorker > 0, "the batch put probes on the workers: " + onWorker);
      Check.check(onGameThread > 0, "the game thread worked the batch too: " + onGameThread);
      Check.check(!UpdateBatch.hasFailed(), "no probe leaked the IllegalStateException into the batch");

      System.out.println("ForwardDirectionGuardTest ok");
   }
}

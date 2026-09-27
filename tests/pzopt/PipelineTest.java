package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import zombie.GameTime;
import zombie.Lua.LuaEventManager;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * A bucket's batch flies while the game thread collects the next one ({@code entityUpdatePipeline}).
 *
 * <p>{@code dispatchAsync} sends the collected entities to the workers and returns; the caller overlaps the
 * flight with the next bucket's collection and calls {@code joinPending} before the next dispatch (the
 * scheduler override joins after the last bucket, so postupdate and the render never see an airborne batch).
 * The landing owns everything the old synchronous tail did — deferred tile updates, the Lua replay in queue
 * order, the failure latch — and the replay runs under the batch's dispatch-time multiplier through the same
 * {@code pom()} holder the tasks used, because the game thread's global field already belongs to the next
 * bucket by then.
 *
 * <p>The discriminating assertions: after {@code dispatchAsync} returns and every task has finished on the
 * workers, NOTHING has replayed yet (the synchronous shape would have replayed before returning), and the
 * captured events land exactly at {@code joinPending}. The bytecode half pins the wiring: the bucket
 * override's update() dispatches async and joins the previous flight, and the scheduler override's update()
 * holds the final join.
 */
public class PipelineTest {

   static final AtomicInteger ran = new AtomicInteger();

   static final class Probe extends IsoMovingObject {
      final GameTime gt;
      volatile float sawPom = Float.NaN;

      Probe(GameTime gt) {
         super(false);
         this.gt = gt;
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
         this.sawPom = UpdateBatch.pom(this.gt);
         LuaEventManager.triggerEvent("PzoptPipelineProbe"); // env-null-safe 0-arg overload, captured for replay
         ran.incrementAndGet();
         try {
            Thread.sleep(1); // spread the batch across threads, as in UpdateBatchTest
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
   }

   public static void main(String[] args) throws Exception {
      Check.check(Config.ENTITY_UPDATE_PIPELINE, "entityUpdatePipeline defaults to on");

      GameTime gt = GameTime.getInstance();
      long captured0 = UpdateBatch.getLuaCapturedCount();
      long replayed0 = UpdateBatch.getLuaReplayedCount();

      // ── dispatch: returns with the batch airborne; the game thread is free ──
      int n = 96;
      Probe[] probes = new Probe[n];
      UpdateBatch.clear();
      for (int i = 0; i < n; i++) {
         probes[i] = new Probe(gt);
         UpdateBatch.add(probes[i]);
      }
      gt.perObjectMultiplier = 4.0F; // the bucket's entry write
      Check.check(UpdateBatch.dispatchAsync(UpdateSchedulerSimulationLevel.FULL), "the batch dispatched");
      Check.check(UpdateBatch.hasPendingBatch(), "the batch is airborne after dispatchAsync returns");

      // the next bucket's write lands while the flight runs — tasks must keep seeing 4 through pom()
      gt.perObjectMultiplier = 8.0F;

      // "collection work": wait until every task has actually finished on the workers
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (ran.get() < n && System.nanoTime() < deadline) {
         Thread.onSpinWait();
      }
      Check.check(ran.get() == n, "every task finished on the workers while the game thread was free: " + ran.get());

      // ── the discriminator: all work done, and STILL nothing has landed ──
      Check.check(UpdateBatch.hasPendingBatch(), "the batch has not been joined yet");
      Check.check(UpdateBatch.getLuaReplayedCount() == replayed0,
            "no Lua replay before joinPending (the synchronous shape would have replayed already)");
      Check.check(UpdateBatch.getLuaCapturedCount() == captured0,
            "the capture tally is part of the landing too — both counters move only at joinPending");

      // the next bucket can collect into the swapped-in queue while the flight is still pending
      UpdateBatch.clear();
      Check.check(UpdateBatch.pending() == 0, "the next collection starts empty in its own buffer");

      // ── the landing ──
      UpdateBatch.joinPending();
      Check.check(!UpdateBatch.hasPendingBatch(), "joined");
      Check.check(UpdateBatch.getLuaReplayedCount() - replayed0 == n,
            "the captured events replayed exactly at the join: " + (UpdateBatch.getLuaReplayedCount() - replayed0));
      for (Probe p : probes) {
         Check.check(p.sawPom == 4.0F,
               "a task read the dispatch-time multiplier (4) despite the next bucket's write (8), got " + p.sawPom);
      }
      Check.check(UpdateBatch.pom(gt) == 8.0F, "after the join pom() follows the live field");
      UpdateBatch.joinPending(); // a second join is a no-op
      Check.check(!UpdateBatch.hasFailed(), "nothing failed");
      gt.perObjectMultiplier = 1.0F;

      // ── bytecode: the bucket dispatches async and joins the previous; the scheduler holds the final join ──
      MethodModel bucket = method(loose("zombie.MovingObjectUpdateSchedulerUpdateBucket"), "update", "(I)V");
      Check.check(invokes(bucket, "dispatchAsync"), "the bucket's update() dispatches without joining");
      Check.check(invokes(bucket, "joinPending"), "the bucket's update() lands the PREVIOUS bucket's flight");
      Check.check(invokes(bucket, "stampInline"), "inline entities stamp their post-update position mid-flight");
      MethodModel sched = method(loose("zombie.MovingObjectUpdateScheduler"), "update", "()V");
      Check.check(invokes(sched, "joinPending"), "the scheduler's update() holds the final join after the last bucket");

      System.out.println("PipelineTest ok");
   }

   private static MethodModel method(ClassModel model, String name, String desc) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals(name) && m.methodTypeSymbol().descriptorString().equals(desc)) {
            return m;
         }
      }
      throw new AssertionError("FAILED: " + name + desc + " not found");
   }

   private static boolean invokes(MethodModel m, String name) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof InvokeInstruction inv
               && inv.owner().name().stringValue().equals("pzopt/UpdateBatch")
               && inv.name().stringValue().equals(name)) {
            found[0] = true;
         }
      });
      return found[0];
   }

   private static ClassModel loose(String className) throws Exception {
      String rel = className.replace('.', '/') + ".class";
      for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
         if (part.endsWith(".jar")) {
            continue;
         }
         Path p = Path.of(part).resolve(rel);
         if (Files.isRegularFile(p)) {
            return ClassFile.of().parse(Files.readAllBytes(p));
         }
      }
      throw new AssertionError("FAILED: " + rel + " is not in a directory on the test classpath");
   }
}

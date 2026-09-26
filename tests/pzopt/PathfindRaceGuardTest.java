package pzopt;

import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.ai.astar.AStarPathFinder;
import zombie.iso.IsoMovingObject;
import zombie.pathfind.PathFindBehavior2;

/**
 * {@code zombie.pathfind.PathFindBehavior2.update()} survives the async-pathfinding race on a frame worker
 * ({@code entityUpdateParallel}; the PathFindBehavior2 override, ported from PZMulticore's
 * PathFindBehavior2Patcher — both of its layers).
 *
 * <p>PZ's pathfinder delivers results into {@code Path.nodes} from its own thread while the owner's state
 * machine reads the list inside {@code update()}: the list shrinks between the size check and the {@code get},
 * or a torn position read collapses a direction to zero length. Two layers, like the patcher:
 * <ol>
 *   <li>on a batch task — a worker OR the game thread working the batch ({@code onBatchTaskNow()}) —
 *       {@code update()} iterates a frozen clone of {@code this.path.nodes} taken once at entry (outside a
 *       batch the local aliases the live list, so serial behaviour is bit-identical);
 *   <li>a batch task's {@code IndexOutOfBoundsException} / {@code IllegalStateException} out of the body
 *       returns {@code BehaviorResult.Working} and counts via {@code UpdateBatch.onPathfindRaceSkipped()} —
 *       the character just retries next frame. Outside a batch the throw escapes, vanilla parity.
 * </ol>
 *
 * <p>Both layers originally covered the workers only, keeping vanilla behaviour on the game-thread
 * participant. The live runs said otherwise: every PathFindState escape of lou-replay-clean (4) and
 * lou-fwd-scratch (1) bottomed out in {@code FrameBatch.run} — the game thread working the batch, where the
 * concurrent writers (the pathfind thread's delivery, a group leader's member {@code pathToLocation} on
 * another worker) are exactly as live as on a worker. {@code deferMovingSquare} already treated the
 * participant as a batch task; the pathfind layers now match it.
 *
 * <p>The bytecode pin: the jar's {@code update()} reads the {@code Path.nodes} field many times; the built
 * override's reads it at most once (the entry snapshot) so no read in the body can see the live list. The
 * runtime side drives the real {@code update()} on a real character whose finder reports {@code found} over an
 * empty path — the body then indexes past the list's end, which is exactly the race's failure shape.
 */
public class PathfindRaceGuardTest {

   /** Drives pfb.update() from whatever thread the batch put this probe on. */
   static final class Probe extends IsoMovingObject {
      final int index;
      final zombie.characters.IsoGameCharacter chr;
      volatile boolean ranOnWorker;
      volatile Object result;
      volatile Throwable thrown;
      volatile boolean ran;

      Probe(int index) {
         super(false);
         this.index = index;
         this.chr = new Chr();
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
         PathFindBehavior2 pfb = this.chr.getPathFindBehavior2();
         this.chr.getFinder().progress = AStarPathFinder.PathFindProgress.found;
         try {
            this.result = pfb.update();
         } catch (IndexOutOfBoundsException e) {
            this.thrown = e;
         }
         this.ran = true;
         try {
            Thread.sleep(1); // spread the batch across threads, as in UpdateBatchTest
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
   }

   static final class Chr extends zombie.characters.IsoGameCharacter {
      Chr() {
         super(null, 0.0F, 0.0F, 0.0F);
      }
   }

   public static void main(String[] args) throws Exception {
      zombie.core.random.RandStandard.INSTANCE.init();
      zombie.core.Core.soundDisabled = true;

      // ── bytecode pin: the body reads the snapshot, not the live list ──
      int jarReads = nodesReadsInUpdate(jarClass("zombie/pathfind/PathFindBehavior2.class"));
      Check.check(jarReads > 1, "the jar's update() reads Path.nodes directly many times (so the pin below is"
            + " testing our edit), found " + jarReads);

      ClassModel override = java.lang.classfile.ClassFile.of()
            .parse(Files.readAllBytes(looseClass("zombie.pathfind.PathFindBehavior2")));
      int ourReads = nodesReadsInUpdate(override);
      Check.check(ourReads <= 1,
            "the override's update() reads Path.nodes at most once — the entry snapshot — so no read in the"
                  + " body can see the live list; found " + ourReads);

      // ── game thread OUTSIDE a batch: the failure shape still throws (vanilla parity, key on or off) ──
      Chr serial = new Chr();
      serial.getFinder().progress = AStarPathFinder.PathFindProgress.found;
      boolean threw = false;
      try {
         serial.getPathFindBehavior2().update();
      } catch (IndexOutOfBoundsException e) {
         threw = true;
      }
      Check.check(threw, "outside a batch an out-of-range path read still throws, exactly like vanilla");
      Check.check(UpdateBatch.getPathfindRaceSkippedCount() == 0,
            "the outside-batch throw was not counted as a skip");

      // ── on any batch task — worker or the game thread working the batch: Working, counted, no throw ──
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
         } else {
            onGameThread++;
         }
         Check.check(p.thrown == null,
               "probe " + p.index + (p.ranOnWorker ? " on a worker" : " on the game thread mid-batch")
                     + ": the race does not escape update(), threw " + p.thrown);
         Check.check(p.result == PathFindBehavior2.BehaviorResult.Working,
               "probe " + p.index + " gets BehaviorResult.Working (retry next frame), got " + p.result);
      }
      Check.check(onWorker > 0, "the batch put probes on the workers: " + onWorker);
      Check.check(onGameThread > 0, "the game thread worked the batch too: " + onGameThread);
      Check.check(!UpdateBatch.hasFailed(), "no probe leaked the race exception into the batch");

      long skipped = UpdateBatch.getPathfindRaceSkippedCount();
      Check.check(skipped == n,
            "every batch task's skip was counted, exactly once each: " + n + " probes, counted " + skipped);
      Check.check(UpdateBatch.describe().contains("pathfindRaceSkipped=" + skipped),
            "describe() folds the skip count in: " + UpdateBatch.describe());

      System.out.println("PathfindRaceGuardTest ok");
   }

   /** How many times update()'s code reads the Path.nodes field. */
   private static int nodesReadsInUpdate(ClassModel model) {
      for (MethodModel m : model.methods()) {
         if (!m.methodName().stringValue().equals("update")
               || !m.methodTypeSymbol().descriptorString()
                     .equals("()Lzombie/pathfind/PathFindBehavior2$BehaviorResult;")) {
            continue;
         }
         int reads = 0;
         for (var element : m.code().orElseThrow()) {
            if (element instanceof FieldInstruction fi
                  && fi.owner().name().stringValue().equals("zombie/pathfind/Path")
                  && fi.name().stringValue().equals("nodes")) {
               reads++;
            }
         }
         return reads;
      }
      throw new AssertionError("FAILED: update()Lzombie/pathfind/PathFindBehavior2$BehaviorResult; not found"
            + " — TIS restructured the method this override edits");
   }

   private static ClassModel jarClass(String entry) throws Exception {
      for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
         if (!part.endsWith("projectzomboid.jar")) {
            continue;
         }
         try (ZipFile zip = new ZipFile(part)) {
            var e = zip.getEntry(entry);
            Check.check(e != null, "the game jar still has " + entry);
            try (var in = zip.getInputStream(e)) {
               return java.lang.classfile.ClassFile.of().parse(in.readAllBytes());
            }
         }
      }
      throw new AssertionError("FAILED: projectzomboid.jar is not on the test classpath");
   }

   private static Path looseClass(String className) {
      String rel = className.replace('.', '/') + ".class";
      for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
         if (part.endsWith(".jar")) {
            continue;
         }
         Path p = Path.of(part).resolve(rel);
         if (Files.isRegularFile(p)) {
            return p;
         }
      }
      throw new AssertionError("FAILED: " + rel + " is not in a directory on the test classpath"
            + " (the PathFindBehavior2 override is not built)");
   }
}

package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.ai.astar.AStarPathFinder;
import zombie.iso.IsoMovingObject;
import zombie.pathfind.PathFindBehavior2;
import zombie.pathfind.PathFindBehavior2.PointOnPath;

/**
 * {@code zombie.pathfind.PathFindBehavior2}'s four static scratch objects are per-thread, so two batch tasks
 * inside {@code update()} no longer derive each other's path state ({@code entityUpdateParallel}).
 *
 * <p>The first port of this override read the race wrong, and this test read it wrong with it. The story was
 * "PZ's pathfinder delivers into {@code Path.nodes} from its own thread while a worker reads the list", and the
 * answers were a defensive {@code nodes.clone()} at the top of {@code update()} plus a catch that swallowed
 * {@code IndexOutOfBounds}/{@code IllegalState} into {@code BehaviorResult.Working}. The live counter then read
 * 77-958 skips a route, which is what sent this back for another look.
 *
 * <p>Delivery is not off-thread. Both pathfinders (the Java {@code PolygonalMap2} thread and the native
 * {@code PathfindNativeThread}) compute into the REQUEST's own {@code Path} and hand the request to a
 * {@code requestToMain} queue; the only code that copies a result into a character's path is
 * {@code PathFindBehavior2.Succeeded}, and the only callers of that are {@code PolygonalMap2.updateMain} and
 * {@code PathfindNative.updateMain} — both on the game thread, both from {@code IngameState.UpdateStuff}, which
 * {@code updateInternal} reaches only AFTER {@code IsoWorld.update()} has returned. The flight opens and lands
 * inside that call ({@code MovingObjectUpdateScheduler.update}'s finally joins it), so no delivery can overlap a
 * batch task. And {@code PathFindBehavior2.path} is a private field, so nothing outside the class can touch the
 * list at all. The clone was guarding a writer that does not exist in the window.
 *
 * <p>What does race is the class's own scratch: {@code tempVector2}, {@code tempVector2_2},
 * {@code tempVector3f_1} and {@code pointOnPath} are four STATIC mutable objects shared by every thread running
 * these methods. The worst is {@code pointOnPath}: {@code update()} calls {@code closestPointOnPath(…, this.path,
 * pointOnPath)} and reads {@code pointOnPath.pathIndex} back on the very next line, so a second task landing
 * between those two statements hands this character an index derived from ITS path. Too large and the following
 * {@code nodes.get} throws {@code IndexOutOfBounds} — the counted case. In range and the character silently walks
 * the wrong segment of its own path — the uncounted case, and the one that mattered. The vectors are the same
 * disease: {@code getDeferredMovement(tempVector2_2)} and then {@code moveUnmodded(tempVector2_2.x, …)} thirty
 * lines later moves this character by whatever another one wrote in between, and the {@code getLengthSquared() > 0}
 * guard in front of {@code setForwardDirection} can be true when the read a line later is (0,0).
 *
 * <p>So the fix is the repo's own static-scratch idiom, the one {@code dirScratch}/{@code tempoScratch}/
 * {@code walkScratch} already apply to {@code IsoGameCharacter} and {@code WalkTowardState}: every method a batch
 * task can reach takes its scratch from a per-thread holder on {@link UpdateBatch}. Identical single-threaded (one
 * thread sees one object, written and read inside one method, exactly as before), and no cross-thread write left.
 * The clone comes out, and the catch stops swallowing: it counts, logs the first one loudly and rethrows, so a
 * nonzero {@code pathfindRaceEscaped} in a route log now means this reading is wrong and carries the stack to
 * prove it.
 *
 * <p>The pins are of four kinds. The jar pins record the facts the fix rests on — where delivery happens, that
 * the path field is private, that the four statics are still what stock reads — so a game update that moves any
 * of them fails the build instead of silently un-fixing this. The override pins say the worker-reachable methods
 * read none of the four and route through the per-thread holders, while {@code render()} deliberately keeps the
 * statics (game-thread debug only, and nothing else writes them any more). The derived-index invariant is
 * one-sided: through a per-thread holder a task can only read back the index it just wrote itself, so it cannot
 * fail for a timing reason, and the control beside it drives the identical hammer through one shared instance to
 * show the hammer does detect the race. The catch case drives the real {@code update()} on real characters
 * through the batch and asserts the throw now escapes on every thread, counted once each.
 */
public class PathfindRaceGuardTest {

   /** The four static scratch objects of the class, and the per-thread holder that replaces each. */
   private static final String[][] SCRATCH = {
      {"tempVector2", "pathScratch"},
      {"tempVector2_2", "pathScratch2"},
      {"tempVector3f_1", "pathScratch3"},
      {"pointOnPath", "pathPointScratch"},
   };

   private static final String PFB = "zombie/pathfind/PathFindBehavior2.class";
   private static final String UPDATE_DESC = "()Lzombie/pathfind/PathFindBehavior2$BehaviorResult;";

   /**
    * Every method a batch task can reach that touches the scratch, with the statics it touches. {@code update}
    * is the entity's own state machine; {@code moveToPoint} is reached from it through
    * {@code updateWhileRunningPathfind} and from the walk states; {@code moveToDir} from the lunge and bumped
    * states; {@code checkCrawlingTransition} from {@code update} itself; {@code pathToCharacter} from
    * {@code WalkTowardState}, which every chasing zombie runs.
    */
   private static final String[][] REACHABLE = {
      {"update", UPDATE_DESC, "tempVector2", "tempVector2_2", "tempVector3f_1", "pointOnPath"},
      {"moveToPoint", "(FFF)V", "tempVector2", "tempVector2_2"},
      {"moveToDir", "(Lzombie/iso/IsoMovingObject;F)V", "tempVector2", "tempVector2_2"},
      {"checkCrawlingTransition", "(Lzombie/pathfind/PathNode;Lzombie/pathfind/PathNode;F)V", "pointOnPath"},
      {"pathToCharacter", "(Lzombie/characters/IsoGameCharacter;)V", "tempVector3f_1"},
   };

   public static void main(String[] args) throws Exception {
      zombie.core.random.RandStandard.INSTANCE.init();
      zombie.core.Core.soundDisabled = true;

      deliveryIsOnTheGameThread();
      theListHasNoOutsideWriter();
      jarScratchShape();
      overrideScratchShape();
      renderKeepsTheStatics();
      theCloneIsGone();
      scratchIsPerThread();
      derivedIndexInvariant();
      theCatchRethrows();

      System.out.println("PathfindRaceGuardTest ok");
   }

   // ── the writer: Succeeded, from updateMain, on the game thread, after the flight has landed ─────────────────

   private static void deliveryIsOnTheGameThread() throws Exception {
      for (String[] c : new String[][]{
            {"zombie/pathfind/PolygonalMap2.class", "PolygonalMap2"},
            {"zombie/pathfind/nativeCode/PathfindNative.class", "PathfindNative"}}) {
         Set<String> callers = methodsInvoking(jarClass(c[0]), "Succeeded");
         Check.check(callers.equals(Set.of("updateMain")),
               c[1] + " copies a finished path into a character only from updateMain — the game thread's own"
                     + " frame step, never a pathfind thread; callers are " + callers);
      }

      // The pathfind threads' own loops stay clear of it: they fill the request's Path and queue the request.
      Check.check(!methodsInvoking(jarClass("zombie/pathfind/PolygonalMap2.class"), "Succeeded")
                  .contains("updateThread"),
            "PolygonalMap2's own thread loop never delivers a path itself");
      Check.check(!methodsInvoking(jarClass("zombie/pathfind/nativeCode/PathfindNativeThread.class"), "Succeeded")
                  .contains("run"),
            "the native pathfind thread's run loop never delivers a path itself");

      // And the game-thread ordering the whole reading rests on: the scheduler's flight opens and lands inside
      // IsoWorld.update(), which updateInternal calls BEFORE UpdateStuff(), where the two updateMain calls live.
      ClassModel ingame = jarClass("zombie/gameStates/IngameState.class");
      List<String> order = invokeOrder(method(ingame, "updateInternal"));
      int world = order.indexOf("zombie/iso/IsoWorld.update");
      int stuff = order.indexOf("zombie/gameStates/IngameState.UpdateStuff");
      Check.check(world >= 0 && stuff >= 0 && world < stuff,
            "IngameState.updateInternal runs IsoWorld.update() before UpdateStuff(), so the entity flight has"
                  + " landed before any path is delivered; IsoWorld.update at " + world + ", UpdateStuff at "
                  + stuff);
      Set<String> stuffCalls = invokeSet(method(ingame, "UpdateStuff"));
      Check.check(stuffCalls.contains("zombie/pathfind/PolygonalMap2.updateMain")
                  && stuffCalls.contains("zombie/pathfind/nativeCode/PathfindNative.updateMain"),
            "UpdateStuff is where both updateMain calls live");
      // …and the chain that puts the flight inside that call: IsoWorld.update -> updateWorld -> IsoCell.update,
      // and the cell's object pass is what runs the scheduler.
      ClassModel isoWorld = jarClass("zombie/iso/IsoWorld.class");
      Check.check(!methodsInvokingOwned(isoWorld, "zombie/iso/IsoWorld", "updateWorld").isEmpty(),
            "IsoWorld.update() reaches updateWorld inside the class");
      Check.check(invokeSet(method(isoWorld, "updateWorld")).contains("zombie/iso/IsoCell.update"),
            "IsoWorld.updateWorld runs the cell's update");
      Set<String> cellDrivers = methodsInvokingOwned(jarClass("zombie/iso/IsoCell.class"),
            "zombie/MovingObjectUpdateScheduler", "update");
      Check.check(!cellDrivers.isEmpty(),
            "the cell's object pass drives the entity scheduler, and so the flight, from inside IsoWorld.update()"
                  + " — methods found: " + cellDrivers);
   }

   private static void theListHasNoOutsideWriter() throws Exception {
      Check.check(isPrivate(jarClass(PFB), "path"),
            "PathFindBehavior2.path is private, so only this class can mutate the list a batch task reads"
                  + " (if it opens up, re-read every writer before trusting the clone's removal)");
   }

   // ── the four statics: stock's, and the override's replacements ───────────────────────────────────────────────

   private static void jarScratchShape() throws Exception {
      ClassModel jar = jarClass(PFB);
      for (String[] f : SCRATCH) {
         Check.check(isStatic(jar, f[0]),
               "the jar still holds " + f[0] + " as one static scratch object shared by every thread"
                     + " (if the developers made it per-instance, drop our layer)");
      }
      for (String[] r : REACHABLE) {
         MethodModel m = method(jar, r[0], r[1]);
         for (int i = 2; i < r.length; i++) {
            Check.check(readsField(m, r[i]),
                  "the jar's " + r[0] + " reads the static " + r[i] + " (the hazard is stock's; the fix is ours)");
         }
      }
      Check.check(invokeOrder(method(jar, "update", UPDATE_DESC))
                  .contains("zombie/pathfind/PathFindBehavior2.closestPointOnPath"),
            "the jar's update() still derives its path index through closestPointOnPath");
   }

   private static void overrideScratchShape() throws Exception {
      ClassModel ours = overrideClass("zombie.pathfind.PathFindBehavior2");
      for (String[] r : REACHABLE) {
         MethodModel m = method(ours, r[0], r[1]);
         for (int i = 2; i < r.length; i++) {
            Check.check(!readsField(m, r[i]), r[0] + " no longer reads the shared static " + r[i]);
            Check.check(invokes(m, "pzopt/UpdateBatch", holderFor(r[i])),
                  r[0] + " takes its " + r[i] + " from UpdateBatch." + holderFor(r[i]) + "()");
         }
      }
   }

   /**
    * {@code render()} is debug-gated and game-thread only, and once no batch task reads the statics nothing else
    * writes them — so it keeps them and stays byte-identical to the jar, the same call the IsoGameCharacter
    * override made for its debug and death-path users.
    */
   private static void renderKeepsTheStatics() throws Exception {
      MethodModel m = method(overrideClass("zombie.pathfind.PathFindBehavior2"), "render", "()V");
      Check.check(readsField(m, "pointOnPath") && readsField(m, "tempVector2"),
            "render() deliberately still uses the statics (game-thread debug only, no other writer left)");
      Check.check(!invokes(m, "pzopt/UpdateBatch", "pathPointScratch"),
            "render() was not converted, so it carries no marker and the audit keeps comparing it");
   }

   private static void theCloneIsGone() throws Exception {
      MethodModel m = method(overrideClass("zombie.pathfind.PathFindBehavior2"), "update", UPDATE_DESC);
      Check.check(!invokes(m, "java/util/ArrayList", "clone"),
            "update() no longer clones Path.nodes: delivery is on the game thread outside the flight and the"
                  + " field is private, so there is no concurrent list writer to snapshot against");
   }

   // ── the holders themselves ──────────────────────────────────────────────────────────────────────────────────

   private static void scratchIsPerThread() throws Exception {
      Object[] mine = holders();
      Object[] again = holders();
      for (int i = 0; i < mine.length; i++) {
         Check.check(mine[i] != null && mine[i] == again[i], "holder " + i + " is stable on one thread");
      }
      for (int i = 0; i < mine.length; i++) {
         for (int j = i + 1; j < mine.length; j++) {
            Check.check(mine[i] != mine[j], "holder " + i + " and holder " + j + " are different objects");
         }
      }

      AtomicReference<Object[]> other = new AtomicReference<>();
      Thread t = new Thread(() -> other.set(holders()));
      t.start();
      t.join();
      for (int i = 0; i < mine.length; i++) {
         Check.check(other.get()[i] != mine[i], "holder " + i + " is a different object on another thread");
      }
   }

   private static Object[] holders() {
      return new Object[]{UpdateBatch.pathScratch(), UpdateBatch.pathScratch2(),
            UpdateBatch.pathScratch3(), UpdateBatch.pathPointScratch()};
   }

   // ── the derived index: always an index into the reader's OWN path ────────────────────────────────────────────

   /** A bare mover for closestPointOnPath; not an IsoZombie, so the ghost branch stays out of it. */
   static final class Mover extends IsoMovingObject {
      Mover() {
         super(false);
      }
   }

   private static final Field POP_INDEX = popField("pathIndex");

   private static Field popField(String name) {
      try {
         Field f = PointOnPath.class.getDeclaredField(name);
         f.setAccessible(true);
         return f;
      } catch (NoSuchFieldException e) {
         throw new AssertionError("FAILED: PointOnPath has no " + name + " field any more");
      }
   }

   /**
    * A straight path of {@code n} nodes, three tiles away from where the mover will stand. The offset matters:
    * {@code closestPointOnPath} reaches {@code IsoCell.getGridSquare} only when the projected point is within one
    * tile of the mover, and a bare JVM has no cell — three tiles keeps the whole walk arithmetic, which is the
    * part that writes the shared point.
    */
   private static zombie.pathfind.Path straightPath(int n) {
      zombie.pathfind.Path p = new zombie.pathfind.Path();
      for (int i = 0; i < n; i++) {
         p.addNode(10.0f + i, 20.0f, 0.0f);
      }
      return p;
   }

   /** Where a mover stands for {@code straightPath(n)}'s last segment to be the closest one. */
   private static float moverX(int n) {
      return 10.0f + n - 1.5f;
   }

   private static void derivedIndexInvariant() throws Exception {
      // The walk itself: each path resolves to its own last segment, so a torn read shows up as another index.
      for (int n : new int[]{2, 15, 28, 41}) {
         PointOnPath pop = new PointOnPath();
         PathFindBehavior2.closestPointOnPath(moverX(n), 23.0f, 0.0f, new Mover(), straightPath(n), pop);
         Check.check(POP_INDEX.getInt(pop) == n - 2,
               "a " + n + "-node path derives index " + (n - 2) + ", got " + POP_INDEX.getInt(pop)
                     + " (the hammer below tells the paths apart by exactly this)");
      }

      // The control: one shared point, as the jar's static is. The hammer must see the race, or it is proving
      // nothing about the invariant below.
      long shared = hammer(new PointOnPath());
      Check.check(shared > 0,
            "the control saw the race through a single shared PointOnPath (if this ever reads 0 the hammer has"
                  + " stopped exercising the window and the invariant below is vacuous)");

      // The invariant: through the per-thread holder a task can only read back the index it just wrote itself.
      long perThread = hammer(null);
      Check.check(perThread == 0,
            "no batch-shaped reader derived an index outside its own path through UpdateBatch.pathPointScratch(),"
                  + " violations " + perThread + " against the control's " + shared);
   }

   /**
    * Four threads, four path lengths, each deriving its own last-segment index and reading it straight back —
    * the two statements {@code update()} runs back to back. {@code shared != null} passes one point to all four
    * (stock's static); null gives each the per-thread holder.
    */
   private static long hammer(PointOnPath shared) throws Exception {
      final int rounds = 60_000;
      AtomicLong violations = new AtomicLong();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      Thread[] ts = new Thread[4];
      for (int t = 0; t < ts.length; t++) {
         final int n = 2 + t * 13; // 2, 15, 28, 41 nodes: wanted index 0, 13, 26, 39
         ts[t] = new Thread(() -> {
            try {
               zombie.pathfind.Path p = straightPath(n);
               Mover m = new Mover();
               float x = moverX(n);
               for (int r = 0; r < rounds; r++) {
                  PointOnPath pop = shared != null ? shared : UpdateBatch.pathPointScratch();
                  PathFindBehavior2.closestPointOnPath(x, 23.0f, 0.0f, m, p, pop);
                  if (POP_INDEX.getInt(pop) != n - 2) {
                     violations.incrementAndGet();
                  }
               }
            } catch (Throwable e) {
               failure.compareAndSet(null, e);
            }
         }, "pathfind-hammer-" + t);
         ts[t].setDaemon(true);
      }
      for (Thread t : ts) {
         t.start();
      }
      for (Thread t : ts) {
         t.join();
      }
      if (failure.get() != null) {
         throw new AssertionError("FAILED: the hammer threw", failure.get());
      }
      return violations.get();
   }

   // ── the catch: counts, logs, and rethrows on every thread ────────────────────────────────────────────────────

   /** Drives the real pfb.update() from whatever thread the batch put this probe on. */
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
            this.thrown = e; // caught HERE, so the batch's own failure latch never sees it
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

   /**
    * An empty path with the finder reporting {@code found} indexes past the list's end — vanilla's own throw
    * shape, and the one thing left that can reach the catch. It must escape identically on and off a batch now;
    * only the counting differs, and only a batch task counts.
    */
   private static void theCatchRethrows() throws Exception {
      long before = UpdateBatch.getPathfindRaceEscapedCount();
      Check.check(before == 0, "nothing counted an escape before this case, count is " + before);

      Chr serial = new Chr();
      serial.getFinder().progress = AStarPathFinder.PathFindProgress.found;
      boolean threw = false;
      try {
         serial.getPathFindBehavior2().update();
      } catch (IndexOutOfBoundsException e) {
         threw = true;
      }
      Check.check(threw, "off a batch the out-of-range read throws, exactly like vanilla");
      Check.check(UpdateBatch.getPathfindRaceEscapedCount() == 0,
            "an off-batch throw is vanilla's own, not a race, and is not counted");

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
         Check.check(p.thrown != null,
               "probe " + p.index + (p.ranOnWorker ? " on a worker" : " on the game thread mid-batch")
                     + ": the throw escapes update() instead of turning into Working, result was " + p.result);
      }
      Check.check(onWorker > 0, "the batch put probes on the workers: " + onWorker);
      Check.check(onGameThread > 0, "the game thread worked the batch too: " + onGameThread);

      long escaped = UpdateBatch.getPathfindRaceEscapedCount();
      Check.check(escaped == n,
            "every batch task's escape was counted, exactly once each: " + n + " probes, counted " + escaped);
      Check.check(UpdateBatch.describe().contains("pathfindRaceEscaped=" + escaped),
            "describe() folds the escape count in: " + UpdateBatch.describe());
      Check.check(!UpdateBatch.hasFailed(),
            "the probes caught their own throws, so the batch itself did not latch off");
   }

   // ── bytecode helpers ────────────────────────────────────────────────────────────────────────────────────────

   private static String holderFor(String staticField) {
      for (String[] s : SCRATCH) {
         if (s[0].equals(staticField)) {
            return s[1];
         }
      }
      throw new AssertionError("FAILED: no holder mapped for " + staticField);
   }

   /** The names of the methods of {@code model} that invoke something called {@code callee}. */
   private static Set<String> methodsInvoking(ClassModel model, String callee) {
      Set<String> out = new LinkedHashSet<>();
      for (MethodModel m : model.methods()) {
         if (m.code().isEmpty()) {
            continue;
         }
         for (var element : m.code().orElseThrow()) {
            if (element instanceof InvokeInstruction ii && ii.name().stringValue().equals(callee)) {
               out.add(m.methodName().stringValue());
            }
         }
      }
      return out;
   }

   /** The names of the methods of {@code model} that invoke {@code owner.name}. */
   private static Set<String> methodsInvokingOwned(ClassModel model, String owner, String name) {
      Set<String> out = new LinkedHashSet<>();
      for (MethodModel m : model.methods()) {
         if (m.code().isEmpty()) {
            continue;
         }
         if (invokes(m, owner, name)) {
            out.add(m.methodName().stringValue());
         }
      }
      return out;
   }

   /** Every {@code owner.name} the method invokes, in bytecode order. */
   private static List<String> invokeOrder(MethodModel m) {
      List<String> out = new ArrayList<>();
      for (var element : m.code().orElseThrow()) {
         if (element instanceof InvokeInstruction ii) {
            out.add(ii.owner().name().stringValue() + "." + ii.name().stringValue());
         }
      }
      return out;
   }

   private static Set<String> invokeSet(MethodModel m) {
      return new LinkedHashSet<>(invokeOrder(m));
   }

   private static boolean readsField(MethodModel m, String name) {
      for (var element : m.code().orElseThrow()) {
         if (element instanceof FieldInstruction f && f.name().stringValue().equals(name)) {
            return true;
         }
      }
      return false;
   }

   private static boolean invokes(MethodModel m, String owner, String name) {
      for (var element : m.code().orElseThrow()) {
         if (element instanceof InvokeInstruction ii
               && ii.owner().name().stringValue().equals(owner)
               && ii.name().stringValue().equals(name)) {
            return true;
         }
      }
      return false;
   }

   private static boolean isStatic(ClassModel model, String field) {
      for (var f : model.fields()) {
         if (f.fieldName().stringValue().equals(field)) {
            return f.flags().has(AccessFlag.STATIC);
         }
      }
      throw new AssertionError("FAILED: field " + field + " not found — the developers renamed the scratch");
   }

   private static boolean isPrivate(ClassModel model, String field) {
      for (var f : model.fields()) {
         if (f.fieldName().stringValue().equals(field)) {
            return f.flags().has(AccessFlag.PRIVATE);
         }
      }
      throw new AssertionError("FAILED: field " + field + " not found");
   }

   private static MethodModel method(ClassModel model, String name, String desc) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals(name)
               && m.methodTypeSymbol().descriptorString().equals(desc)) {
            return m;
         }
      }
      throw new AssertionError("FAILED: " + name + desc + " not found in "
            + model.thisClass().name().stringValue()
            + " — the developers restructured a method this override edits");
   }

   private static MethodModel method(ClassModel model, String name) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals(name) && m.code().isPresent()) {
            return m;
         }
      }
      throw new AssertionError("FAILED: " + model.thisClass().name().stringValue() + "." + name + " not found");
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
               return ClassFile.of().parse(in.readAllBytes());
            }
         }
      }
      throw new AssertionError("FAILED: projectzomboid.jar is not on the test classpath");
   }

   private static ClassModel overrideClass(String className) throws Exception {
      return ClassFile.of().parse(Files.readAllBytes(looseClass(className)));
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

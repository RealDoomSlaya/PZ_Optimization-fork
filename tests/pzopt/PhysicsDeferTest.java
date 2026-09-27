package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * The two native-physics escapes of a batched zombie land at the join ({@code physicsDefer}; the
 * IsoGameCharacter and IsoZombie overrides' guards + UpdateBatch's per-task slots).
 *
 * <p>Why: with {@code entityUpdateParallel} on, shooting zombies crashed the process in both live runs — a
 * SIGSEGV inside {@code libPZBullet64}'s {@code btHashedOverlappingPairCache::removeOverlappingPair}, on a
 * {@code pzopt-frame-3} worker, under {@code IsoGameCharacter.updateInternal} →
 * {@code releaseBallisticsTarget} → {@code BallisticsTarget.removeFromWorld}. Bullet is not thread-safe and a
 * native crash is not catchable by the batch's failure latch, so the only fix is to keep the calls off the
 * workers. {@code BallisticsTarget} also hands the native a PUBLIC STATIC {@code float[] boneTransformData}
 * that {@code initialize()} may replace mid-fill, which no amount of locking on our side repairs. The
 * ragdoll-vehicle contact test is the same hazard by a rarer route (it needs a ragdolling zombie touching a
 * car) and passes the static {@code vehicleRagdollBodyDynamicsParams} to the native besides.
 *
 * <p>No runtime hammer: both paths need a constructible character and a live Bullet world (the
 * ZombieGroupGuardTest constraint). Structural pins plus the two live-callable refusals:
 * <ul>
 *   <li>the jar still routes both escapes where we hooked them — {@code IsoGameCharacter.updateInternal}
 *       calls {@code updateBallisticsTarget}, {@code IsoZombie.updateInternal} calls
 *       {@code RagdollController.vehicleCollision} — so a TIS rework fails the build instead of silently
 *       un-deferring the fix;
 *   <li>the jar's {@code BallisticsTarget.update} still reaches {@code Bullet} and still feeds it the static
 *       bone buffer (if either goes, re-read the override);
 *   <li>the overrides route through {@code UpdateBatch.deferBallistics} / {@code deferRagdollVehicle};
 *   <li>{@code joinPending} drains both, before the Lua replay (a handler must not read a stale hitbox), and
 *       each drain calls the real work through the override's own entry point;
 *   <li>off a batch task both helpers refuse — the game thread and the inline path never defer;
 *   <li>the key defaults on: the crash fix is not opt-in.
 * </ul>
 */
public class PhysicsDeferTest {

   public static void main(String[] args) throws Exception {
      // ── the jar: both escapes still where the overrides hook them ──
      MethodModel jarChar = named(jarClass("zombie/characters/IsoGameCharacter.class"), "updateInternal", "()V");
      Check.check(invokes(jarChar, "zombie/characters/IsoGameCharacter", "updateBallisticsTarget"),
            "the jar's IsoGameCharacter.updateInternal still calls updateBallisticsTarget (the hitbox escape we defer)");
      MethodModel jarZombie = named(jarClass("zombie/characters/IsoZombie.class"), "updateInternal", "()V");
      Check.check(invokes(jarZombie, "zombie/core/physics/RagdollController", "vehicleCollision"),
            "the jar's IsoZombie.updateInternal still calls RagdollController.vehicleCollision (the ragdoll escape we defer)");

      // ── the jar: the hitbox update is still a native call over a shared static buffer ──
      ClassModel jarTarget = jarClass("zombie/core/physics/BallisticsTarget.class");
      MethodModel jarUpdate = named(jarTarget, "update", "()Z");
      Check.check(invokesOwner(jarUpdate, "zombie/core/physics/Bullet"),
            "the jar's BallisticsTarget.update still calls into Bullet (else re-read what physicsDefer is for)");
      Check.check(readsField(named(jarTarget, "getBoneTransforms", "()V"), "boneTransformData")
                  && readsField(named(jarTarget, "updateSkeleton", "()V"), "boneTransformData"),
            "the jar still fills the static boneTransformData buffer and hands it to the native");

      // ── the overrides: a batch task's calls defer ──
      ClassModel ourChar = ClassFile.of().parse(Files.readAllBytes(looseClass("zombie.characters.IsoGameCharacter")));
      Check.check(invokes(named(ourChar, "updateBallisticsTarget", "()V"), "pzopt/UpdateBatch", "deferBallistics"),
            "the override's updateBallisticsTarget routes batch-task calls through UpdateBatch.deferBallistics");
      ClassModel ourZombie = ClassFile.of().parse(Files.readAllBytes(looseClass("zombie.characters.IsoZombie")));
      Check.check(invokes(named(ourZombie, "updateInternal", "()V"), "pzopt/UpdateBatch", "deferRagdollVehicle"),
            "the override's IsoZombie.updateInternal routes batch-task calls through UpdateBatch.deferRagdollVehicle");

      // ── UpdateBatch: the join drains both, ahead of the Lua replay ──
      ClassModel ub = ClassFile.of().parse(Files.readAllBytes(looseClass("pzopt.UpdateBatch")));
      MethodModel join = named(ub, "joinPending", "()V");
      Check.check(invokes(join, "pzopt/UpdateBatch", "drainRagdolls"), "joinPending drains the deferred ragdoll contacts");
      Check.check(invokes(join, "pzopt/UpdateBatch", "drainBallistics"), "joinPending drains the deferred hitbox updates");
      List<String> order = calls(join, "drainRagdolls", "drainBallistics", "replayLuaEvents");
      Check.check(order.indexOf("drainRagdolls") < order.indexOf("replayLuaEvents")
                  && order.indexOf("drainBallistics") < order.indexOf("replayLuaEvents"),
            "both physics drains run before the Lua replay (a handler must not observe a stale hitbox)");

      // ── the drains run the real work, through the overrides' own entry points ──
      Check.check(invokes(named(ub, "drainBallistics", "(I)V"),
                  "zombie/characters/IsoGameCharacter", "pzoptUpdateBallisticsTarget"),
            "drainBallistics runs the real hitbox update on the game thread");
      Check.check(invokes(named(ourChar, "pzoptUpdateBallisticsTarget", "()V"),
                  "zombie/characters/IsoGameCharacter", "updateBallisticsTarget"),
            "the override's drain entry point calls the stock updateBallisticsTarget (re-reading the field)");
      Check.check(invokes(named(ub, "drainRagdolls", "(I)V"), "zombie/characters/IsoZombie", "pzoptVehicleCollision"),
            "drainRagdolls runs the real vehicle-contact test on the game thread");
      Check.check(invokes(named(ourZombie, "pzoptVehicleCollision", "(Lzombie/vehicles/BaseVehicle;)V"),
                  "zombie/core/physics/RagdollController", "vehicleCollision"),
            "the override's drain entry point calls the stock RagdollController.vehicleCollision");

      // ── live: off a batch task both refuse, so the game thread and the inline path always run in place ──
      Check.check(!UpdateBatch.deferBallistics(null), "deferBallistics refuses off a batch task");
      Check.check(!UpdateBatch.deferRagdollVehicle(null, null), "deferRagdollVehicle refuses off a batch task");

      // ── the fix is not opt-in ──
      Check.check(Config.PHYSICS_DEFER, "physicsDefer defaults on");

      System.out.println("PhysicsDeferTest ok");
   }

   private static MethodModel named(ClassModel model, String name, String desc) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals(name) && m.methodTypeSymbol().descriptorString().equals(desc)) {
            return m;
         }
      }
      throw new AssertionError("FAILED: " + name + desc + " not found");
   }

   private static boolean readsField(MethodModel m, String fieldName) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof FieldInstruction f && f.name().stringValue().equals(fieldName)) {
            found[0] = true;
         }
      });
      return found[0];
   }

   private static boolean invokes(MethodModel m, String owner, String name) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof InvokeInstruction inv
               && inv.owner().name().stringValue().equals(owner)
               && inv.name().stringValue().equals(name)) {
            found[0] = true;
         }
      });
      return found[0];
   }

   private static boolean invokesOwner(MethodModel m, String owner) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof InvokeInstruction inv && inv.owner().name().stringValue().equals(owner)) {
            found[0] = true;
         }
      });
      return found[0];
   }

   /** The named calls of this method in bytecode order, so a pin can say which runs first. */
   private static List<String> calls(MethodModel m, String... names) {
      List<String> wanted = List.of(names);
      List<String> seen = new ArrayList<>();
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof InvokeInstruction inv && wanted.contains(inv.name().stringValue())) {
            seen.add(inv.name().stringValue());
         }
      });
      for (String n : names) {
         Check.check(seen.contains(n), "joinPending calls " + n);
      }
      return seen;
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
      throw new AssertionError("FAILED: " + rel + " is not on the test classpath — is the build current?");
   }
}

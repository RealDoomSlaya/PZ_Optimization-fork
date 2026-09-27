package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * A batched zombie's emitter tick lands at the join ({@code emitterDefer}; the IsoGameCharacter override's
 * updateEmitter guard + UpdateBatch's per-task slots).
 *
 * <p>Why: the pipeline's overlap window held nothing (lou-pipe-alt measured a wash) — the flight's dear part
 * is the zombies' FMOD work serializing on the emitter monitors (the EmitterLockTest locks), against other
 * workers and against the inline player's combat. Deferring updateEmitter off the workers shortens the
 * flight, the game thread ticks the deferred emitters at joinPending in queue order (stock's serial order)
 * under the flight's captured multiplier, and the emitter monitors go back to uncontended. It also takes a
 * worker off {@code CombatManager.getBoneWorldPos}'s STATIC {@code tempVectorBonePos} (the prone-zombie
 * branch of updateEmitter) — the scratch-family disease again, gone with the thread instead of converted.
 *
 * <p>No runtime hammer: updateEmitter needs a constructible IsoGameCharacter (the ZombieGroupGuardTest
 * constraint). Structural pins plus one live-callable check:
 * <ul>
 *   <li>the jar's updateEmitter invokes nothing of pzopt's and still reaches the static tempVectorBonePos
 *       (if TIS reworks either, re-read the override);
 *   <li>the override's updateEmitter routes through {@code UpdateBatch.deferEmitter};
 *   <li>{@code joinPending} drains via {@code drainEmitters}, which calls the real updateEmitter;
 *   <li>off a batch task, {@code deferEmitter} refuses (returns false) — the inline path never defers.
 * </ul>
 */
public class EmitterDeferTest {

   public static void main(String[] args) throws Exception {
      // ── the jar: no pzopt routing, the static bone scratch still there ──
      MethodModel jarM = named(jarClass("zombie/characters/IsoGameCharacter.class"), "updateEmitter", "()V");
      Check.check(!invokesOwner(jarM, "pzopt/UpdateBatch"),
            "the jar's updateEmitter has no pzopt routing (if TIS adds one, re-read the override)");
      Check.check(readsField(jarM, "tempVectorBonePos"),
            "the jar's updateEmitter still uses the static tempVectorBonePos scratch (else re-read the override)");

      // ── the override: batch-task calls defer ──
      ClassModel ours = ClassFile.of().parse(Files.readAllBytes(looseClass("zombie.characters.IsoGameCharacter")));
      MethodModel ourM = named(ours, "updateEmitter", "()V");
      Check.check(invokes(ourM, "pzopt/UpdateBatch", "deferEmitter"),
            "the override's updateEmitter routes batch-task calls through UpdateBatch.deferEmitter");

      // ── UpdateBatch: the join drains, the drain runs the real tick ──
      ClassModel ub = ClassFile.of().parse(Files.readAllBytes(looseClass("pzopt.UpdateBatch")));
      Check.check(invokes(named(ub, "joinPending", "()V"), "pzopt/UpdateBatch", "drainEmitters"),
            "joinPending drains the deferred emitters");
      MethodModel drain = named(ub, "drainEmitters", "(I)V");
      Check.check(invokes(drain, "zombie/characters/IsoGameCharacter", "updateEmitter"),
            "drainEmitters runs the real updateEmitter on the game thread");

      // ── live: off a batch task the defer refuses, so the inline path always ticks in place ──
      Check.check(!UpdateBatch.deferEmitter(null), "deferEmitter refuses off a batch task");

      System.out.println("EmitterDeferTest ok");
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

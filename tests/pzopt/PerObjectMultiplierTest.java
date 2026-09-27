package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import zombie.GameTime;
import zombie.UpdateSchedulerSimulationLevel;
import zombie.iso.IsoMovingObject;

/**
 * A batch task reads the multiplier its bucket dispatched with, not the live field
 * ({@code entityUpdateParallel}; the GameTime / FrameDelay / IsoZombie edits behind the bucket pipeline).
 *
 * <p>{@code GameTime.perObjectMultiplier} is written by the bucket loop (its frame mod at entry, 1 at exit)
 * and consumed, jar-wide, at exactly five sites: the {@code getMultiplier} / {@code getUnmoddedMultiplier} /
 * {@code getTrueMultiplier} getters and two raw field reads ({@code IsoZombie.allowsInvisibleAnimationSkips},
 * {@code FrameDelay.update}). With the batch dispatched asynchronously, the game thread moves on to the next
 * bucket — and writes the global field — while the previous bucket's tasks still run, so every one of the
 * five sites now reads {@code pzopt.UpdateBatch.pom(gameTime)}: the value captured at the batch's dispatch
 * while this thread runs one of its tasks, the live field otherwise. Outside a batch every path is
 * bit-identical to vanilla (the helper returns the field).
 *
 * <p>The runtime half drives a real batch: the field is 3 at dispatch, every task then writes 7 into the
 * field and must still read 3 (worker AND game-thread participant — both run tasks); after the join the
 * helper follows the field again. The bytecode half pins the routing: the five sites in the built overrides
 * invoke {@code pom} and no longer read the field raw, and the jar's copies still read it raw (a TIS rework
 * would be seen), with no pzopt call.
 */
public class PerObjectMultiplierTest {

   static final class Probe extends IsoMovingObject {
      final GameTime gt;
      volatile float sawPom = Float.NaN;
      volatile boolean ran;

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
         this.gt.perObjectMultiplier = 7.0F; // any task racing the field must not leak into another's read
         this.sawPom = UpdateBatch.pom(this.gt);
         this.ran = true;
         try {
            Thread.sleep(1); // spread the batch across threads, as in UpdateBatchTest
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      }
   }

   public static void main(String[] args) throws Exception {
      // ── runtime: dispatch-time capture beats the live field, inside tasks only ──
      GameTime gt = new GameTime();
      gt.perObjectMultiplier = 3.0F;
      Check.check(UpdateBatch.pom(gt) == 3.0F, "outside a batch pom() is the live field");

      int n = 128;
      Probe[] probes = new Probe[n];
      UpdateBatch.clear();
      for (int i = 0; i < n; i++) {
         probes[i] = new Probe(gt);
         UpdateBatch.add(probes[i]);
      }
      GameTime.instance.perObjectMultiplier = 3.0F; // what the bucket's entry write left for this dispatch
      UpdateBatch.run(UpdateSchedulerSimulationLevel.FULL);

      for (Probe p : probes) {
         Check.check(p.ran, "probe ran");
         Check.check(p.sawPom == 3.0F,
               "a task reads the dispatch-time multiplier (3), not the racing field, got " + p.sawPom);
      }
      Check.check(UpdateBatch.pom(gt) == 7.0F, "after the join pom() follows the live field again");

      // ── bytecode: the five sites route through pom(), the jar's copies read the field raw ──
      String[][] sites = {
            {"zombie/GameTime.class", "zombie.GameTime", "getMultiplier", "()F"},
            {"zombie/GameTime.class", "zombie.GameTime", "getUnmoddedMultiplier", "()F"},
            {"zombie/GameTime.class", "zombie.GameTime", "getTrueMultiplier", "()F"},
            {"zombie/util/FrameDelay.class", "zombie.util.FrameDelay", "update", "()Z"},
            {"zombie/characters/IsoZombie.class", "zombie.characters.IsoZombie", "allowsInvisibleAnimationSkips", "()Z"},
      };
      for (String[] s : sites) {
         MethodModel jar = method(jarClass(s[0]), s[2], s[3]);
         Check.check(readsPom(jar), "the jar's " + s[1] + "." + s[2] + " still reads perObjectMultiplier raw"
               + " (if TIS reworked it, re-read the overrides against the new jar)");
         Check.check(!invokesPom(jar), "the jar's " + s[1] + "." + s[2] + " has no pzopt call");

         MethodModel ours = method(ClassFile.of().parse(Files.readAllBytes(looseClass(s[1]))), s[2], s[3]);
         Check.check(invokesPom(ours), s[1] + "." + s[2] + " routes through UpdateBatch.pom()");
         Check.check(!readsPom(ours), s[1] + "." + s[2] + " no longer reads perObjectMultiplier raw");
      }

      System.out.println("PerObjectMultiplierTest ok");
   }

   private static MethodModel method(ClassModel model, String name, String desc) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals(name) && m.methodTypeSymbol().descriptorString().equals(desc)) {
            return m;
         }
      }
      throw new AssertionError("FAILED: " + name + desc + " not found");
   }

   private static boolean readsPom(MethodModel m) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof FieldInstruction f && f.name().stringValue().equals("perObjectMultiplier")) {
            found[0] = true;
         }
      });
      return found[0];
   }

   private static boolean invokesPom(MethodModel m) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof InvokeInstruction inv
               && inv.owner().name().stringValue().equals("pzopt/UpdateBatch")
               && inv.name().stringValue().equals("pom")) {
            found[0] = true;
         }
      });
      return found[0];
   }

   /** The jar's copy of a class, parsed without running it (as in ZombieGroupGuardTest). */
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
      throw new AssertionError("FAILED: " + rel + " is not in a directory on the test classpath"
            + " — are the GameTime/FrameDelay overrides built?");
   }
}

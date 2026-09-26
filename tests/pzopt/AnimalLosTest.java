package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/**
 * IsoAnimal.updateLOS skips the no-effect far-zombie calls exactly ({@code animalLosFast}; the IsoAnimal
 * override).
 *
 * <p>Vanilla walks the WHOLE cell object list once per animal per frame and calls
 * {@code BaseAnimalBehavior.spotted(zombie, false, dist)} for every zombie in the cell — in a Louisville
 * horde that is animals x ~4,000 objects, and it is why xD3I's farm profile had updateLOS at 4% of the game
 * thread. Read from the pinned jar: for a zombie farther than 10 tiles (squares non-null) the call's whole
 * observable effect is two pieces of bookkeeping — {@code parent.spottedChr = null} and one
 * {@code lastAlerted} decrement-with-clamp. No stress, no flee, no alert ({@code spotted} can only become
 * true for a zombie within 10), and no {@code Rand} draw, so the shared RNG stream is untouched either way.
 *
 * <p>The fast path skips those calls (zombie, current square non-null, squared distance &gt; 101 — the 1.0
 * margin over 10^2 means float-sqrt rounding at the boundary can never make vanilla's {@code dist <= 10}
 * true for a skipped zombie) and replays the bookkeeping exactly: before the next executed call and once
 * after the loop, {@code AnimalLos.decay} applies the same N sequential subtract-then-clamp steps N vanilla
 * calls would have (float subtraction is not associative, so it is a loop, not one multiply), and
 * {@code spottedChr} is nulled once (each executed call re-nulls it at entry anyway).
 *
 * <p>A runtime drive of the real updateLOS needs a constructible IsoAnimal with a populated cell, which a
 * bare JVM does not have (the constructor chain pulls AnimalDefinitions through the game's script engine) —
 * same constraint as ZombieGroupGuardTest, so the evidence is the decay unit test plus bytecode pins: the
 * jar's updateLOS calls spotted and no pzopt code (the fast path is ours), and the override's updateLOS
 * still calls spotted (the near path is intact) and routes the skip accounting through pzopt.AnimalLos.
 */
public class AnimalLosTest {

   public static void main(String[] args) throws Exception {
      // ── the decay replay is bit-exact against N vanilla decrement-with-clamp steps ──
      float[] starts = {0.0f, 0.001f, 0.5f, 1.0f, 16.6f, 100.0f, 12345.678f, Float.MIN_NORMAL};
      float[] mults = {0.0f, 0.8f, 1.0f, 1.6f, 30.0f};
      int[] counts = {0, 1, 2, 3, 7, 100, 4096};
      for (float start : starts) {
         for (float mult : mults) {
            for (int n : counts) {
               float vanilla = start;
               for (int i = 0; i < n; i++) {
                  // the exact shape of BaseAnimalBehavior.spotted's bookkeeping, once per call
                  if (vanilla > 0.0f) {
                     vanilla -= mult;
                  }
                  if (vanilla < 0.0f) {
                     vanilla = 0.0f;
                  }
               }
               float ours = AnimalLos.decay(start, mult, n);
               Check.check(Float.floatToIntBits(ours) == Float.floatToIntBits(vanilla),
                     "decay(" + start + ", " + mult + ", " + n + ") is bit-exact: vanilla=" + vanilla
                           + " ours=" + ours);
            }
         }
      }

      // ── the jar: updateLOS calls spotted and holds no pzopt hook (the fast path below is ours) ──
      MethodModel jarMethod = method(jarClass("zombie/characters/animals/IsoAnimal.class"));
      Check.check(invokes(jarMethod, "zombie/characters/animals/behavior/BaseAnimalBehavior", "spotted"),
            "the jar's updateLOS still fans out through BaseAnimalBehavior.spotted"
                  + " (if TIS restructured it, re-read the override against the new jar)");
      Check.check(!invokesOwnerPrefix(jarMethod, "pzopt/"),
            "the jar's updateLOS has no pzopt call — the fast path is our edit");

      // ── the override: the near path intact, the skip accounting routed through AnimalLos ──
      MethodModel ours = method(ClassFile.of()
            .parse(Files.readAllBytes(looseClass("zombie.characters.animals.IsoAnimal"))));
      Check.check(invokes(ours, "zombie/characters/animals/behavior/BaseAnimalBehavior", "spotted"),
            "the override's updateLOS still calls spotted for near zombies and players");
      Check.check(invokes(ours, "pzopt/AnimalLos", "decay"),
            "the override's updateLOS replays skipped bookkeeping through AnimalLos.decay");

      System.out.println("AnimalLosTest ok");
   }

   private static MethodModel method(ClassModel model) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals("updateLOS")
               && m.methodTypeSymbol().descriptorString().equals("()V")) {
            return m;
         }
      }
      throw new AssertionError("FAILED: updateLOS()V not found");
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

   private static boolean invokesOwnerPrefix(MethodModel m, String ownerPrefix) {
      boolean[] found = {false};
      m.code().orElseThrow().forEach(el -> {
         if (el instanceof InvokeInstruction inv
               && inv.owner().name().stringValue().startsWith(ownerPrefix)) {
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
            + " — is the IsoAnimal override built?");
   }
}

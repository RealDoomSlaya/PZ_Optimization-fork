package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.MonitorInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * ZombieGroupManager's shared group list is locked for the batch workers ({@code entityUpdateParallel};
 * the ZombieGroupManager override).
 *
 * <p>The live failure (run {@code lou-replay-on}, frame 928): a worker died with {@code NullPointerException:
 * "idealSizeFactor" because "group" is null} in {@code findNearestGroup}, latching the batch off for the rest of
 * the session. Root cause read from the pinned jar: {@code groups} is a plain ArrayList; {@code findNearestGroup}
 * iterates it AND removes empties ({@code groups.remove(i--)}), {@code update(IsoZombie)} adds groups, removes
 * members and reads other groups' leaders, and every batched zombie's {@code updateInternal} calls
 * {@code update()} — concurrent iterate/add/remove on one ArrayList, a torn slot read, the NPE. The same method
 * family also shares the manager's {@code tempVec2}/{@code tempVec3} scratch fields between workers. Fix, in the
 * repo's own idiom (the {@code lccMain} and {@code soundList} locks): every group-touching section runs under
 * {@code synchronized (this.groups)} — the remove at update()'s entry, everything past the tick gate, the whole
 * of {@code findNearestGroup}, {@code preupdate()}'s sweep and {@code Reset()}. Uncontended on the stock path
 * (workers exist only while a batch is in flight); the tick gate keeps the join block off 29 frames in 30.
 *
 * <p>A runtime hammer is not possible in a bare JVM: {@code findNearestGroup}'s first statements read
 * {@code SandboxOptions.instance}, whose class initializer runs Lua through the game filesystem (verified —
 * {@code ExceptionInInitializerError} out of {@code ZomboidFileSystem}). So, like PathfindRaceGuardTest's
 * clone-under-race semantics, the evidence is structural plus the live run: this test pins in bytecode that the
 * jar's methods hold NO monitor (proving the lock is ours, and that a game update adding its own would be seen)
 * and that the override's group-touching methods each hold one, with the method set otherwise identical.
 */
public class ZombieGroupGuardTest {

   private static final String CLASS = "zombie/ai/ZombieGroupManager.class";
   private static final Set<String> GUARDED = Set.of("update", "findNearestGroup", "preupdate", "Reset");

   public static void main(String[] args) throws Exception {
      // ── the jar: same methods, no monitors — the lock below is pzopt's, not TIS's ──
      ClassModel jar = jarClass(CLASS);
      Set<String> jarMethods = methodNames(jar);
      for (MethodModel m : jar.methods()) {
         Check.check(!holdsMonitor(m), "the jar's " + m.methodName().stringValue()
               + " takes no monitor (if TIS adds its own lock, re-read the override against the new jar)");
      }

      // ── the override: every group-touching method under the lock, nothing else changed shape ──
      ClassModel override = ClassFile.of().parse(Files.readAllBytes(looseClass("zombie.ai.ZombieGroupManager")));
      Check.check(methodNames(override).equals(jarMethods),
            "the override carries exactly the jar's methods; jar=" + jarMethods + " ours=" + methodNames(override));

      for (String name : GUARDED) {
         boolean found = false;
         for (MethodModel m : override.methods()) {
            if (m.methodName().stringValue().equals(name) && holdsMonitor(m)) {
               found = true;
            }
         }
         Check.check(found, name + " runs its group-list work under synchronized (this.groups)");
      }

      for (MethodModel m : override.methods()) {
         if (!GUARDED.contains(m.methodName().stringValue())) {
            Check.check(!holdsMonitor(m), "no stray monitor in " + m.methodName().stringValue());
         }
      }

      System.out.println("ZombieGroupGuardTest ok");
   }

   private static boolean holdsMonitor(MethodModel m) {
      if (m.code().isEmpty()) {
         return false;
      }
      boolean[] found = {false};
      m.code().get().forEach(el -> {
         if (el instanceof MonitorInstruction) {
            found[0] = true;
         }
      });
      return found[0];
   }

   private static Set<String> methodNames(ClassModel model) {
      Set<String> out = new HashSet<>();
      for (MethodModel m : model.methods()) {
         out.add(m.methodName().stringValue() + m.methodTypeSymbol().descriptorString());
      }
      return out;
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
      throw new AssertionError("FAILED: " + rel + " is not in a directory on the test classpath"
            + " — is the ZombieGroupManager override built?");
   }
}

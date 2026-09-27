package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * The statistics map is locked for the batch ({@code entityUpdateParallel}; the StatisticsManager override).
 *
 * <p>The first live modded session (2026-09-27, 322 mods): at frame 1 a worker's zombie update ran
 * {@code IsoZombie.updateMovementStatistics} → {@code StatisticsManager.incrementStatistic}, whose
 * {@code computeIfAbsent} raced another worker's on the singleton's plain HashMap —
 * {@code ConcurrentModificationException}, and the failure latch turned batching off for the whole
 * session (the session then ran serial at 29 fps, correct but unbatched). The bench saves never see it:
 * the statistics path only ticks with a consumer installed (the live save's daily-statistics mod).
 * Every map-touching method of the manager now holds its own monitor — the ZombieGroupManager /
 * FMODSoundEmitter idiom, uncontended on the serial path. The {@code Statistic} increment itself
 * (a read-modify-write) rides the same lock, so worker-vs-worker increments stop losing updates too.
 *
 * <p>No runtime hammer: {@code incrementStatistic} calls into {@code AchievementManager}, whose
 * initialisation runs platform/achievement state a bare JVM does not have (the ZombieGroupGuardTest
 * constraint). The pin is structural, like EmitterLockTest: the jar's methods carry no
 * ACC_SYNCHRONIZED (the lock is ours; a TIS-added lock means re-read the override), the override's
 * listed surface all does, the lambdas and {@code getInstance} stay lock-free (they run under the
 * caller's monitor or touch no shared state), and the method set is otherwise identical.
 */
public class StatisticsLockTest {

   private static final String CLASS = "zombie/statistics/StatisticsManager.class";
   private static final Set<String> LOCKED = Set.of(
         "incrementStatistic", "setStatistic", "getStatistic", "getStatistics", "getAllStatisticsDebug",
         "load", "save");

   public static void main(String[] args) throws Exception {
      ClassModel jar = jarClass(CLASS);
      for (MethodModel m : jar.methods()) {
         Check.check(!m.flags().has(AccessFlag.SYNCHRONIZED),
               "the jar's " + m.methodName().stringValue() + " takes no lock (if TIS adds one, re-read the override)");
      }

      ClassModel ours = ClassFile.of().parse(Files.readAllBytes(looseClass("zombie.statistics.StatisticsManager")));
      Check.check(methodNames(ours).containsAll(methodNames(jar)),
            "the override carries at least the jar's methods");
      int locked = 0;
      for (MethodModel m : ours.methods()) {
         String name = m.methodName().stringValue();
         if (LOCKED.contains(name)) {
            Check.check(m.flags().has(AccessFlag.SYNCHRONIZED),
                  name + m.methodTypeSymbol().descriptorString() + " holds the manager lock");
            locked++;
         } else {
            Check.check(!m.flags().has(AccessFlag.SYNCHRONIZED), "no stray lock on " + name);
         }
      }
      Check.check(locked == 7, "all 7 map-touching methods are locked, found " + locked);

      System.out.println("StatisticsLockTest ok");
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
      throw new AssertionError("FAILED: " + rel + " is not on the test classpath — is the StatisticsManager override built?");
   }
}

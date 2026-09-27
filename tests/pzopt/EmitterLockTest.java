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
 * A character's FMOD emitter is locked for the batch ({@code entityUpdateParallel}; the FMODSoundEmitter
 * override).
 *
 * <p>Run lou-pipe-clean2: with the pipeline overlapping bucket k's flight with bucket k+1's collection, the
 * inline player fought zombies of the airborne bucket — its combat wrote into a zombie's emitter
 * ({@code playSound} → the sound lists and the slot BitSet) while that zombie's worker task ticked the same
 * emitter. {@code BitSet.clear(-1)} latched batching off at f:15, but the race had already corrupted the
 * emitter's sound list, and at f:366 the SERIAL path ticked the same emitter into
 * {@code FMOD_Studio_GetPlaybackState(NULL)} — a native-argument NPE on the game thread, fatal. Every
 * list-touching entry point of the emitter (tick, the playSound family, stop/volume/3D/parameter, the
 * isPlaying readers) now holds the emitter's own monitor — the ZombieGroupManager idiom, uncontended on the
 * serial path.
 *
 * <p>No runtime hammer: an FMODSoundEmitter needs the native FMOD system. The pin is structural, like
 * ZombieGroupGuardTest: the jar's methods carry no ACC_SYNCHRONIZED (the lock is ours; a TIS-added lock
 * would be seen here), the override's listed surface all does, and the method set is otherwise identical.
 */
public class EmitterLockTest {

   private static final String CLASS = "fmod/fmod/FMODSoundEmitter.class";
   private static final Set<String> LOCKED = Set.of(
         "stopSound", "stopSoundLocal", "setVolume", "setVolumeAll", "stopAll", "playSound", "playSoundImpl",
         "playSoundLooped", "playSoundLoopedImpl", "set3D", "tick", "hasSoundsToStart", "isPlaying",
         "setParameterValue");

   public static void main(String[] args) throws Exception {
      ClassModel jar = jarClass(CLASS);
      for (MethodModel m : jar.methods()) {
         Check.check(!m.flags().has(AccessFlag.SYNCHRONIZED),
               "the jar's " + m.methodName().stringValue() + " takes no lock (if TIS adds one, re-read the override)");
      }

      ClassModel ours = ClassFile.of().parse(Files.readAllBytes(looseClass("fmod.fmod.FMODSoundEmitter")));
      Check.check(methodNames(ours).containsAll(methodNames(jar)),
            "the override carries at least the jar's methods (it adds emitterIdleSkip helpers of its own)");
      int locked = 0;
      for (MethodModel m : ours.methods()) {
         String name = m.methodName().stringValue();
         if (LOCKED.contains(name)) {
            Check.check(m.flags().has(AccessFlag.SYNCHRONIZED),
                  name + m.methodTypeSymbol().descriptorString() + " holds the emitter lock");
            locked++;
         } else {
            Check.check(!m.flags().has(AccessFlag.SYNCHRONIZED), "no stray lock on " + name);
         }
      }
      Check.check(locked == 23, "all 23 list-touching entry points are locked, found " + locked);

      System.out.println("EmitterLockTest ok");
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
      throw new AssertionError("FAILED: " + rel + " is not on the test classpath — is the override built?");
   }
}

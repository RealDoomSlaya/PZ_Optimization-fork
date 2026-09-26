package pzopt;

import java.io.IOException;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.zip.ZipFile;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;

/**
 * The {@code ItemVisuals} scratch buffers a {@code pzopt.FrameBatch} worker reaches are per thread
 * ({@code entityUpdateParallel}).
 *
 * <p>Two of the jar's scratch buffers are reachable from a batched entity's {@code update()}, and both are one
 * static {@code ItemVisuals} shared by every character:
 *
 * <ul>
 *   <li>{@code IsoGameCharacter.tempItemVisuals}, e.g.
 *       {@code IsoZombie.update} -> {@code IsoGameCharacter.updateInternal} -> {@code StateMachine.update} ->
 *       {@code ZombieEatBodyState.execute} -> {@code IsoGameCharacter.addBlood} (a zombie eating a corpse
 *       splatters blood on itself; {@code addBlood} hands the buffer on to
 *       {@code BloodClothingType.addBlood} inside a loop of up to 28 splats);
 *   <li>{@code ParameterShoeType.tempItemVisuals}, via
 *       {@code IsoZombie.updateInternal} -> {@code IsoGameCharacter.updateEmitter} ->
 *       {@code FMODParameterList.update} -> {@code FMODParameter.update} -> {@code calculateCurrentValue} ->
 *       {@code getShoeType}. This one crashed a live 4,220-zombie batch at frame 53 with
 *       {@code NullPointerException: Cannot invoke "ItemVisual.getScriptItem()" because "itemVisual" is null}:
 *       {@code getItemVisuals(buffer)} clears and refills the buffer, so one worker's refill shortened the list
 *       another worker was part way through walking by index.
 * </ul>
 *
 * <p>Both are pure per-call scratch — filled by {@code getItemVisuals(buffer)} and read inside the one method,
 * never carrying anything between calls — so a {@code ThreadLocal.withInitial(ItemVisuals::new)} is what the game
 * thread already saw (one buffer, reused) and is applied unconditionally rather than behind the key.
 *
 * <p>The test drives the real methods on real {@code IsoGameCharacter} instances from several threads at once, held
 * inside {@code getItemVisuals} on a barrier so every thread has filled its buffer before any of them reads it
 * back. Shared buffer: every thread is handed the same object and reads the last filler's contents. Per thread:
 * each is handed its own and reads back what it wrote. It also pins the jar's side of the fact — that the fields
 * really are {@code static ItemVisuals} in the shipped class — so a TIS change fails the build instead of quietly
 * making the override pointless.
 */
public class ItemVisualsScratchTest {

   private static final int THREADS = 8;

   /** A real IsoGameCharacter. Cell null and x=y=z=0 skips the cell registration, as IsoMovingObject(false) does. */
   static class Chr extends zombie.characters.IsoGameCharacter {
      final int fill;
      private final CyclicBarrier barrier;
      volatile ItemVisuals handed;
      volatile int handedSizeAfterFill;

      Chr(int fill, CyclicBarrier barrier) {
         super(null, 0.0F, 0.0F, 0.0F);
         this.fill = fill;
         this.barrier = barrier;
      }

      /**
       * Stands in for the worn-item walk. Vanilla's {@code getItemVisuals(buffer)} clears the buffer and refills it
       * from this character's worn items; that clear is what makes a shared buffer a data race, so the stand-in
       * clears and refills too — with this character's own number of visuals, so a buffer that arrives at the read
       * holding a different count can only have come from another thread.
       */
      @Override
      public void getItemVisuals(ItemVisuals itemVisuals) {
         this.handed = itemVisuals;
         itemVisuals.clear();
         for (int i = 0; i < this.fill; i++) {
            itemVisuals.add(new ItemVisual());
         }

         this.handedSizeAfterFill = itemVisuals.size();
         if (this.barrier != null) {
            try {
               this.barrier.await(); // every thread has filled before any of them reads back
            } catch (Exception e) {
               throw new RuntimeException(e);
            }
         }
      }
   }

   public static void main(String[] args) throws Exception {
      // IsoGameCharacter's constructor draws its patience from Rand, and enabled() reads GameClient/GameServer,
      // whose initializers draw from Rand too; seeded long before the scheduler runs in the game, not in a bare JVM.
      zombie.core.random.RandStandard.INSTANCE.init();
      zombie.core.Core.soundDisabled = true; // a DummyCharacterSoundEmitter instead of FMOD

      pinVanillaStaticField("zombie/characters/IsoGameCharacter.class", "tempItemVisuals");
      pinVanillaStaticField("zombie/audio/parameters/ParameterShoeType.class", "tempItemVisuals");

      // IsoGameCharacter keeps the jar's field declared so anything compiled against the shipped class still
      // links (scripts/build.sh's signature check), and every user takes a local of the same name off the
      // thread-local, which shadows it. A method that missed the local would compile and silently read the shared
      // buffer again, so the override's own bytecode is checked: nothing but the class initializer may touch it.
      pinNoUseOfSharedBuffer("zombie.characters.IsoGameCharacter", "tempItemVisuals");
      pinNoUseOfSharedBuffer("zombie.characters.IsoZombie", "tempItemVisuals");

      // ── IsoGameCharacter's own buffer, through a real public method that uses it ──────────────
      // hasDirtyClothing is the shortest unguarded user of tempItemVisuals: getItemVisuals(tempItemVisuals)
      // then a walk of the filled buffer. addBlood, addDirt, addHole, getBodyPartClothingDefense,
      // playWeaponHitArmourSound, updateDisguisedState and the worn-item modifiers all do the same thing.
      run("IsoGameCharacter.hasDirtyClothing", chr -> chr.hasDirtyClothing(0));

      // ── ParameterShoeType's buffer, through the method the live crash came out of ─────────────
      run("ParameterShoeType.calculateCurrentValue", chr -> {
         zombie.audio.parameters.ParameterShoeType p = new zombie.audio.parameters.ParameterShoeType(chr);
         p.calculateCurrentValue();
      });

      System.out.println("ItemVisualsScratchTest ok");
   }

   interface Path1 {
      void run(Chr chr) throws Exception;
   }

   /**
    * Drives one real path on THREADS threads at once and asserts each thread had a buffer of its own that still
    * held what it wrote. Then repeats it single-threaded to assert the buffer is reused per thread rather than
    * allocated per call: the game thread must still see exactly one buffer, as the static gave it.
    */
   private static void run(String what, Path1 path) throws Exception {
      CyclicBarrier barrier = new CyclicBarrier(THREADS);
      Chr[] chrs = new Chr[THREADS];
      Thread[] threads = new Thread[THREADS];
      Throwable[] failures = new Throwable[THREADS];
      for (int i = 0; i < THREADS; i++) {
         int fill = 1 + i * 3; // a different count per thread, so a foreign buffer is visible
         Chr chr = new Chr(fill, barrier);
         chrs[i] = chr;
         int index = i;
         threads[i] = new Thread(
            () -> {
               try {
                  path.run(chr);
               } catch (Throwable t) {
                  failures[index] = t;
               }
            },
            "pzopt-scratch-" + i
         );
      }

      for (Thread t : threads) {
         t.start();
      }

      for (Thread t : threads) {
         t.join(30_000L);
         Check.check(!t.isAlive(), what + ": every thread finished (a deadlock means the barrier never filled)");
      }

      for (int i = 0; i < THREADS; i++) {
         Check.check(failures[i] == null, what + ": thread " + i + " threw " + failures[i]);
      }

      Set<ItemVisuals> distinct = ConcurrentHashMap.newKeySet();
      for (int i = 0; i < THREADS; i++) {
         Check.check(chrs[i].handed != null, what + ": thread " + i + " was handed a buffer");
         distinct.add(chrs[i].handed);
      }

      Check.check(
         distinct.size() == THREADS,
         what + ": each thread gets a scratch buffer of its own, " + distinct.size() + " of " + THREADS
            + " were distinct (one shared buffer means a worker can clear the list another worker is walking)"
      );

      for (int i = 0; i < THREADS; i++) {
         Check.check(
            chrs[i].handedSizeAfterFill == chrs[i].fill,
            what + ": thread " + i + " filled " + chrs[i].fill + " visuals, its buffer held "
               + chrs[i].handedSizeAfterFill + " right after the fill"
         );
         Check.check(
            chrs[i].handed.size() == chrs[i].fill,
            what + ": thread " + i + " reads back the " + chrs[i].fill + " visuals it wrote, found "
               + chrs[i].handed.size() + " once every thread had filled"
         );
      }

      // Same thread twice: one buffer, reused — what the shared static did on the game thread.
      Chr a = new Chr(2, null);
      Chr b = new Chr(5, null);
      path.run(a);
      path.run(b);
      Check.check(
         a.handed == b.handed,
         what + ": two calls on one thread reuse that thread's buffer instead of allocating per call"
      );
   }

   /**
    * Pins the shipped class's side of the story: the field this test exists for is a static {@code ItemVisuals} in
    * the jar. Read from the class file rather than by reflection so the jar's copy is inspected even though the
    * override shadows it on the classpath, and so nothing runs the class initializer.
    */
   private static void pinVanillaStaticField(String entry, String field) throws IOException {
      Path jar = gameJar();
      byte[] bytes;
      try (ZipFile zip = new ZipFile(jar.toFile())) {
         var e = zip.getEntry(entry);
         Check.check(e != null, "the game jar still has " + entry);
         try (var in = zip.getInputStream(e)) {
            bytes = in.readAllBytes();
         }
      }

      ClassModel model = java.lang.classfile.ClassFile.of().parse(bytes);
      FieldModel found = null;
      for (FieldModel f : model.fields()) {
         if (f.fieldName().stringValue().equals(field)) {
            found = f;
            break;
         }
      }

      Check.check(found != null, entry + " still declares " + field);
      Check.check(
         found.flags().has(AccessFlag.STATIC),
         entry + "'s " + field + " is still static, i.e. still shared by every character"
      );
      Check.check(
         found.fieldType().stringValue().equals("Lzombie/core/skinnedmodel/visual/ItemVisuals;"),
         entry + "'s " + field + " is still an ItemVisuals, was " + found.fieldType().stringValue()
      );
   }

   /**
    * Pins the override's side: no method of this class reads or writes the shared static buffer any more, the class
    * initializer that creates it aside. Reads the loose class file pzopt installs, so it fails on the thing that
    * actually ships.
    */
   private static void pinNoUseOfSharedBuffer(String className, String field) throws IOException {
      Path file = looseClass(className);
      ClassModel model = java.lang.classfile.ClassFile.of().parse(Files.readAllBytes(file));
      for (var method : model.methods()) {
         String name = method.methodName().stringValue();
         if (name.equals("<clinit>")) {
            continue;
         }

         var code = method.code();
         if (code.isEmpty()) {
            continue;
         }

         for (var element : code.get()) {
            if (element instanceof java.lang.classfile.instruction.FieldInstruction fi
               && fi.name().stringValue().equals(field)) {
               throw new AssertionError(
                  "FAILED: " + className + "." + name + " still touches the shared static " + field
                     + ": it needs the per-thread buffer (pzoptTempItemVisuals) like the others"
               );
            }
         }
      }
   }

   /** The compiled override, from the build output on the test classpath (build/classes ahead of the jar). */
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

      throw new AssertionError("FAILED: " + rel + " is not in a directory on the test classpath");
   }

   private static Path gameJar() {
      for (String part : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
         if (part.endsWith("projectzomboid.jar")) {
            Path p = Path.of(part);
            if (Files.isRegularFile(p)) {
               return p;
            }
         }
      }

      throw new AssertionError("FAILED: projectzomboid.jar is not on the test classpath");
   }
}

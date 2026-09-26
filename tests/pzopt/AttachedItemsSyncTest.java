package pzopt;

import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import zombie.characters.AttachedItems.AttachedItem;
import zombie.characters.AttachedItems.AttachedItems;
import zombie.characters.AttachedItems.AttachedLocationGroup;

/**
 * {@code zombie.characters.AttachedItems.AttachedItems} is thread-safe under {@code entityUpdateParallel}
 * (the AttachedItems override; ported from PZMulticore's AttachedItemsPatcher).
 *
 * <p>The class is a plain {@code ArrayList<AttachedItem>} behind get/setItem/remove/forEach, one instance per
 * character but reachable cross-thread: a worker updating one zombie can read another entity's attached items
 * (and PZMulticore first saw it through a mod's Lua callback calling {@code get(index)} against a concurrent
 * {@code setItem}). Every read re-checks {@code items.size()} against a list another thread may be shrinking, so
 * the failure is an {@code IndexOutOfBoundsException} out of {@code ArrayList} — the shape of our unexplained
 * frame-10 {@code Index -1 out of bounds for length 2} on a worker. The ported fix is the patcher's, verbatim at
 * source level: {@code synchronized} on every public method, constructors and the private {@code indexOf}
 * helpers skipped (the privates are only called from the synchronized publics, so they run under the lock).
 *
 * <p>Pinned three ways: the built override carries ACC_SYNCHRONIZED on exactly the jar's public method set (and
 * the jar's copy carries none, so the pin means something); a real-instance hammer shows {@code forEach} /
 * {@code copyFrom} / {@code clear} are atomic against each other; and the method set matches the jar so a TIS
 * restructure fails the build instead of silently leaving new methods unsynchronized.
 */
public class AttachedItemsSyncTest {

   private static final long HAMMER_MS = 1500;

   public static void main(String[] args) throws Exception {
      // ── a real instance under two threads: forEach never observes a torn copyFrom/clear ──
      AttachedLocationGroup group = new AttachedLocationGroup("pzopt-test");
      AttachedItems source = new AttachedItems(group);
      ArrayList<AttachedItem> sourceItems = itemsOf(source);
      // Two entries; null slots, because AttachedItem's constructor demands a real InventoryItem and building one
      // pulls in the texture system. The hammer counts elements and never dereferences them.
      sourceItems.add(null);
      sourceItems.add(null);
      AttachedItems shared = new AttachedItems(group);

      AtomicInteger anomalies = new AtomicInteger();
      Throwable[] readerFailure = new Throwable[1];
      CyclicBarrier start = new CyclicBarrier(2);
      long deadline = System.currentTimeMillis() + HAMMER_MS;

      Thread writer = new Thread(() -> {
         try {
            start.await();
            while (System.currentTimeMillis() < deadline) {
               shared.copyFrom(source);
               shared.clear();
            }
         } catch (Exception e) {
            throw new RuntimeException(e);
         }
      }, "pzopt-attached-writer");

      Thread reader = new Thread(() -> {
         try {
            start.await();
            int[] seen = new int[1];
            while (System.currentTimeMillis() < deadline) {
               seen[0] = 0;
               shared.forEach(item -> seen[0]++);
               if (seen[0] != 0 && seen[0] != 2) {
                  anomalies.incrementAndGet(); // forEach saw a half-done copyFrom
               }
            }
         } catch (Throwable t) {
            readerFailure[0] = t; // an unsynchronized forEach races clear() into ArrayList's IOOBE
         }
      }, "pzopt-attached-reader");

      writer.start();
      reader.start();
      writer.join(HAMMER_MS + 30_000);
      reader.join(HAMMER_MS + 30_000);
      Check.check(!writer.isAlive() && !reader.isAlive(), "the hammer threads finished");
      Check.check(readerFailure[0] == null,
            "forEach never throws against a concurrent copyFrom/clear (unsynchronized this is ArrayList's"
                  + " IndexOutOfBoundsException — the live frame-10 shape), got: " + readerFailure[0]);
      Check.check(anomalies.get() == 0,
            "forEach always sees a whole copyFrom (0 or 2 items), saw a torn list " + anomalies.get() + " times");

      // ── the built class carries the patcher's exact synchronization set ──
      ClassModel override = java.lang.classfile.ClassFile.of()
            .parse(Files.readAllBytes(looseClass("zombie.characters.AttachedItems.AttachedItems")));
      ClassModel jar = jarClass("zombie/characters/AttachedItems/AttachedItems.class");

      Check.check(methodSet(override).equals(methodSet(jar)),
            "the override carries exactly the jar's methods; jar=" + methodSet(jar) + " ours=" + methodSet(override));

      int synced = 0;
      for (MethodModel m : override.methods()) {
         String name = m.methodName().stringValue();
         String sig = name + m.methodTypeSymbol().descriptorString();
         boolean isSynchronized = m.flags().has(AccessFlag.SYNCHRONIZED);
         if (name.equals("<init>") || m.flags().has(AccessFlag.PRIVATE) || name.equals("<clinit>")) {
            Check.check(!isSynchronized,
                  sig + " stays unsynchronized like the patcher's (constructors and privates skipped)");
         } else {
            synced++;
            Check.check(isSynchronized, sig + " carries ACC_SYNCHRONIZED in the built override");
         }
      }
      Check.check(synced == 13, "the thirteen public methods are synchronized, found " + synced);

      for (MethodModel m : jar.methods()) {
         Check.check(!m.flags().has(AccessFlag.SYNCHRONIZED),
               "the jar's copy is unsynchronized (so the pin above is testing our edit, not TIS): "
                     + m.methodName().stringValue());
      }

      System.out.println("AttachedItemsSyncTest ok");
   }

   private static Set<String> methodSet(ClassModel model) {
      Set<String> out = new HashSet<>();
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals("<clinit>")) {
            continue; // the override adds the class-loaded marker's initializer
         }
         out.add(m.methodName().stringValue() + m.methodTypeSymbol().descriptorString());
      }
      return out;
   }

   /** The protected backing list, reflectively: the only way to seed AttachedItem entries without a ScriptManager. */
   @SuppressWarnings("unchecked")
   private static ArrayList<AttachedItem> itemsOf(AttachedItems items) throws Exception {
      var f = AttachedItems.class.getDeclaredField("items");
      f.setAccessible(true);
      return (ArrayList<AttachedItem>)f.get(items);
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
            + " (the AttachedItems override is not built)");
   }
}

package pzopt;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import zombie.core.TilePropertyAliasMap;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoDirections;

/**
 * {@code zombie.core.properties.PropertyContainer.initSurface()} survives being read from several threads at once
 * ({@code entityUpdateParallel}).
 *
 * <p>The live sighting: a Louisville horde route latched batching off about three seconds in with
 * {@code IndexOutOfBoundsException: Index -1 out of bounds for length 235} out of the surface walk's alias-map
 * lookup, reached from a worker's {@code updateFalling -> getHeightAboveFloor -> hasSlopedSurface}. Two defects, both
 * stock's lazy-init shape, both only reachable now that the walk runs off the game thread:
 *
 * <ol>
 *   <li><b>the done flag is published before the work.</b> Stock sets {@code surfaceFlags = 1} at the top of the
 *       block and only then fills the six surface values, so a second thread entering the guard mid-walk returns at
 *       once and reads fields still at their reset defaults (a square reporting no sloped surface when it has one).
 *       The fix accumulates everything in a scratch object and publishes the fields first, the flag last, and the
 *       flag is volatile so the ordering is a happens-before and not just a program order.</li>
 *   <li><b>the walk takes a torn view of the backing map.</b> The entry walk pairs a state byte, a key and a value
 *       read separately out of the trove map's three parallel arrays. {@code TShortShortHash.removeAt} writes
 *       {@code no_entry_key} into {@code _set} BEFORE the state byte becomes REMOVED, and
 *       {@code TShortShortHashMap.clear} fills {@code _set} with {@code no_entry_key} BEFORE it fills the states
 *       with FREE — and this container's no-entry key and value are both -1. So a walker holding a state byte that
 *       still says FULL can read a key of -1, which is exactly the index the live crash carried. The mutator is
 *       stock's own {@code IsoGridSquare.RecalcProperties}, which clears the square's container and refills it, and
 *       which chunk streaming calls from its own threads. The fix skips an alias index outside the map's range and
 *       counts the skip instead of throwing: a torn read is transient, the next call re-derives the value.</li>
 * </ol>
 *
 * <p>The pins below are of three kinds. The bytecode pins compare the built override against the jar's copy, so a
 * rework by the developers fails the build instead of silently un-fixing this. The torn-entry cases build the exact
 * array state {@code removeAt} / {@code clear} leave behind and assert the walk skips it, counts it and keeps the
 * rest of the container's values. The publication case is one-sided: with the fix in place a reader can only ever
 * see the flag together with the values it was published with, so it cannot fail for a timing reason; without the
 * fix a reader sees the flag with the reset defaults within a few thousand rounds.
 */
public class SurfaceInitTest {

   /** The seven fields initSurface derives; the defect is publishing the flag before the other six. */
   private static final Set<String> SURFACE_FIELDS = Set.of(
         "surface", "surfaceFlags", "stackReplaceTileOffset", "itemHeight",
         "slopedSurfaceDirection", "slopedSurfaceHeightMin", "slopedSurfaceHeightMax");

   public static void main(String[] args) throws Exception {
      aliasMap();
      jarShape();
      overrideShape();
      tornEntriesSkipped();
      publicationInvariant();
      System.out.println("SurfaceInitTest ok");
   }

   // trove's three parallel arrays: _set is public, _values is protected, so both come through reflection here
   private static final Field F_SET = troveField("_set");
   private static final Field F_VALUES = troveField("_values");
   private static final Field F_STATES = troveField("_states");

   private static Field troveField(String name) {
      for (Class<?> c = PropertyContainer.class; c != null; c = c.getSuperclass()) {
         try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
         } catch (NoSuchFieldException ignored) {
         }
      }
      throw new AssertionError("FAILED: the backing map has no " + name + " array any more");
   }

   private static short[] keys(PropertyContainer pc) {
      try {
         return (short[])F_SET.get(pc);
      } catch (ReflectiveOperationException e) {
         throw new AssertionError(e);
      }
   }

   private static short[] values(PropertyContainer pc) {
      try {
         return (short[])F_VALUES.get(pc);
      } catch (ReflectiveOperationException e) {
         throw new AssertionError(e);
      }
   }

   private static byte[] states(PropertyContainer pc) {
      try {
         return (byte[])F_STATES.get(pc);
      } catch (ReflectiveOperationException e) {
         throw new AssertionError(e);
      }
   }

   // ── the alias map, as a tiledef load would leave it ──────────────────────────────────────────────────────────

   private static final String[] NAMES = {
      "Surface", "ItemHeight", "StackReplaceTileOffset", "SlopedSurfaceDirection",
      "SlopedSurfaceHeightMin", "SlopedSurfaceHeightMax", "IsSurfaceOffset", "IsTable", "IsTableTop"};
   private static final String[] VALUES = {"20", "30", "7", "N", "11", "77", "true", "true", "true"};

   private static void aliasMap() {
      HashMap<String, ArrayList<String>> defs = new HashMap<>();
      for (int i = 0; i < NAMES.length; i++) {
         ArrayList<String> vals = new ArrayList<>();
         vals.add(VALUES[i]);
         defs.put(NAMES[i], vals);
      }
      TilePropertyAliasMap.instance.Generate(defs);
      for (String name : NAMES) {
         Check.check(TilePropertyAliasMap.instance.getIDFromPropertyName(name) != -1,
               "the alias map knows " + name);
      }
   }

   /** A container with all nine surface properties set, so every one of the seven fields is off its default. */
   private static PropertyContainer filled() {
      PropertyContainer pc = new PropertyContainer();
      for (int i = 0; i < NAMES.length; i++) {
         pc.set(NAMES[i], VALUES[i], false); // false: none of these is an IsoFlagType, keep the alias-map path
      }
      return pc;
   }

   private static void checkFilled(PropertyContainer pc, String where) {
      Check.check(pc.getSurface() == 20, where + ": surface is 20, got " + pc.getSurface());
      Check.check(pc.getItemHeight() == 30, where + ": item height is 30, got " + pc.getItemHeight());
      Check.check(pc.getStackReplaceTileOffset() == 7,
            where + ": stack replace offset is 7, got " + pc.getStackReplaceTileOffset());
      Check.check(pc.getSlopedSurfaceDirection() == IsoDirections.N,
            where + ": sloped direction is N, got " + pc.getSlopedSurfaceDirection());
      Check.check(pc.getSlopedSurfaceHeightMin() == 11,
            where + ": sloped min is 11, got " + pc.getSlopedSurfaceHeightMin());
      Check.check(pc.getSlopedSurfaceHeightMax() == 77,
            where + ": sloped max is 77, got " + pc.getSlopedSurfaceHeightMax());
      Check.check(pc.isSurfaceOffset(), where + ": the surface-offset bit is set");
      Check.check(pc.isTable(), where + ": the table bit is set");
      Check.check(pc.isTableTop(), where + ": the table-top bit is set");
   }

   // ── bytecode: the jar publishes the flag first, we publish it last ───────────────────────────────────────────

   private static void jarShape() throws Exception {
      ClassModel jar = jarClass("zombie/core/properties/PropertyContainer.class");
      List<String> writes = surfaceWrites(jar, "initSurface");
      Check.check(writes.contains("surfaceFlags"),
            "the jar's initSurface still writes the done flag itself — the shape this test pins, found " + writes);
      Check.check(!writes.get(writes.size() - 1).equals("surfaceFlags"),
            "the jar's initSurface sets the done flag before it fills the values (defect 1), so the pin below is"
                  + " testing our edit; its field writes are " + writes);
      Check.check(!isVolatile(jar, "surfaceFlags"),
            "the jar's surfaceFlags is a plain field, so the volatile pin below is testing our edit");
   }

   private static void overrideShape() throws Exception {
      ClassModel ours = ClassFile.of().parse(Files.readAllBytes(
            looseClass("zombie.core.properties.PropertyContainer")));

      List<String> writes = surfaceWrites(ours, "initSurface");
      Check.check(writes.containsAll(SURFACE_FIELDS),
            "initSurface publishes all seven fields itself, found " + writes);
      Check.check(writes.stream().filter("surfaceFlags"::equals).count() == 1L,
            "initSurface writes the done flag exactly once, found " + writes);
      Check.check(writes.get(writes.size() - 1).equals("surfaceFlags"),
            "the done flag is the LAST field initSurface writes, so no reader can see it set over unfilled"
                  + " values; the write order is " + writes);
      Check.check(isVolatile(ours, "surfaceFlags"),
            "surfaceFlags is volatile, so publishing it last is a happens-before and not just a program order");

      List<String> entryWrites = surfaceWrites(ours, "pzoptSurfaceEntry");
      Check.check(entryWrites.isEmpty(),
            "the per-entry handler writes none of the container's surface fields — the walk accumulates off the"
                  + " object, so the read-modify-write on the flag bits cannot lose a bit either; found "
                  + entryWrites);

      Check.check(calls(ours, "pzoptSurfaceEntry").contains("pzopt/UpdateBatch.onSurfacePropertyRaceSkipped"),
            "the per-entry handler counts an out-of-range alias index on the batch status line");
   }

   // ── runtime: the torn array state removeAt / clear leave behind ──────────────────────────────────────────────

   private static void tornEntriesSkipped() {
      int aliasCount = TilePropertyAliasMap.instance.properties.size();
      long skips = UpdateBatch.getSurfacePropertyRaceSkippedCount();
      Check.check(skips == 0L, "nothing has skipped an alias lookup yet, count is " + skips);

      // (a) a state byte that still says FULL over the no_entry_key this container was built with (-1): the exact
      //     value and the exact stack of the live crash, and the one a clear() or a removeAt() leaves for a walker.
      PropertyContainer tornKey = filled();
      putRaw(tornKey, freeSlot(tornKey), (short)-1, (short)0);
      checkFilled(tornKey, "a -1 key skipped");
      Check.check(UpdateBatch.getSurfacePropertyRaceSkippedCount() == 1L,
            "the -1 key was counted once, count is " + UpdateBatch.getSurfacePropertyRaceSkippedCount());

      // (b) the same tear past the top of the alias list (a key from another array generation after a rehash).
      PropertyContainer tornHigh = filled();
      putRaw(tornHigh, freeSlot(tornHigh), (short)aliasCount, (short)0);
      checkFilled(tornHigh, "an out-of-range key skipped");
      Check.check(UpdateBatch.getSurfacePropertyRaceSkippedCount() == 2L,
            "the out-of-range key was counted once, count is "
                  + UpdateBatch.getSurfacePropertyRaceSkippedCount());

      // (c) the value half of the same torn read: removeAt writes no_entry_value into _values too, so a FULL slot
      //     can carry a real key over a -1 value. The rest of the container must still come out right.
      PropertyContainer tornValue = filled();
      int itemHeightId = TilePropertyAliasMap.instance.getIDFromPropertyName("ItemHeight");
      int slot = slotOf(tornValue, (short)itemHeightId);
      values(tornValue)[slot] = -1;
      Check.check(tornValue.getItemHeight() == 0,
            "the torn entry contributed nothing, item height is " + tornValue.getItemHeight());
      Check.check(tornValue.getSurface() == 20,
            "the entries either side of the torn one still landed, surface is " + tornValue.getSurface());
      Check.check(UpdateBatch.getSurfacePropertyRaceSkippedCount() == 3L,
            "the torn value was counted once, count is " + UpdateBatch.getSurfacePropertyRaceSkippedCount());

      Check.check(UpdateBatch.describe().contains("surfacePropertyRaceSkipped=3"),
            "the batch status line folds the skip count in: " + UpdateBatch.describe());
   }

   // ── runtime: a reader that sees the done flag sees the values it was published with ───────────────────────────

   private static void publicationInvariant() throws Exception {
      PropertyContainer pc = filled();
      checkFilled(pc, "before the threads"); // one serial init, so the values are published once up front

      Field flags = PropertyContainer.class.getDeclaredField("surfaceFlags");
      flags.setAccessible(true);

      final int rounds = 40_000;
      java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
      java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();

      // The mutator does what IsoGridSquare.RecalcProperties does to the flag: clears the valid bit so the next
      // reader re-derives. It only touches bit 0, so the three flag bits of a published walk survive it.
      Thread mutator = new Thread(() -> {
         try {
            while (!done.get()) {
               byte f = flags.getByte(pc);
               flags.setByte(pc, (byte)(f & ~1));
            }
         } catch (Throwable t) {
            failure.compareAndSet(null, t);
         }
      }, "surface-invalidate");
      mutator.setDaemon(true);

      Thread[] readers = new Thread[3];
      for (int i = 0; i < readers.length; i++) {
         readers[i] = new Thread(() -> {
            try {
               for (int r = 0; r < rounds && failure.get() == null; r++) {
                  checkFilled(pc, "a concurrent reader");
               }
            } catch (Throwable t) {
               failure.compareAndSet(null, t);
            }
         }, "surface-reader-" + i);
         readers[i].setDaemon(true);
      }

      mutator.start();
      for (Thread r : readers) {
         r.start();
      }
      for (Thread r : readers) {
         r.join();
      }
      done.set(true);
      mutator.join();

      Throwable t = failure.get();
      if (t instanceof Error e) {
         throw e; // the Check.check AssertionError of whichever reader lost the race, verbatim
      }
      if (t != null) {
         throw new AssertionError(t);
      }
      checkFilled(pc, "after the threads");
      Check.check(UpdateBatch.getSurfacePropertyRaceSkippedCount() == 3L,
            "no reader tripped the range guard on an untorn container, count is "
                  + UpdateBatch.getSurfacePropertyRaceSkippedCount());
   }

   // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────────────

   /** Writes a raw FULL entry into the backing arrays, the way a torn read sees one. */
   private static void putRaw(PropertyContainer pc, int slot, short key, short value) {
      keys(pc)[slot] = key;
      values(pc)[slot] = value;
      states(pc)[slot] = 1; // FULL
   }

   private static int freeSlot(PropertyContainer pc) {
      byte[] states = states(pc);
      for (int i = 0; i < states.length; i++) {
         if (states[i] == 0) {
            return i;
         }
      }
      throw new AssertionError("FAILED: the container has no free slot to tear");
   }

   private static int slotOf(PropertyContainer pc, short key) {
      byte[] states = states(pc);
      short[] keys = keys(pc);
      for (int i = 0; i < states.length; i++) {
         if (states[i] == 1 && keys[i] == key) {
            return i;
         }
      }
      throw new AssertionError("FAILED: key " + key + " is not in the container");
   }

   /**
    * The container's own surface fields written by {@code method}, in bytecode order. The owner matters: the
    * accumulator carries the same field names, and a write there is the whole point.
    */
   private static List<String> surfaceWrites(ClassModel model, String method) {
      List<String> writes = new ArrayList<>();
      for (var element : method(model, method).code().orElseThrow()) {
         if (element instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTFIELD
               && fi.owner().name().stringValue().equals("zombie/core/properties/PropertyContainer")
               && SURFACE_FIELDS.contains(fi.name().stringValue())) {
            writes.add(fi.name().stringValue());
         }
      }
      return writes;
   }

   /** Every {@code owner.name} {@code method} invokes. */
   private static List<String> calls(ClassModel model, String method) {
      List<String> out = new ArrayList<>();
      for (var element : method(model, method).code().orElseThrow()) {
         if (element instanceof InvokeInstruction ii) {
            out.add(ii.owner().name().stringValue() + "." + ii.name().stringValue());
         }
      }
      return out;
   }

   private static MethodModel method(ClassModel model, String name) {
      for (MethodModel m : model.methods()) {
         if (m.methodName().stringValue().equals(name)) {
            return m;
         }
      }
      throw new AssertionError("FAILED: " + model.thisClass().name().stringValue() + "." + name
            + " not found — the developers restructured the method this override edits");
   }

   private static boolean isVolatile(ClassModel model, String field) {
      for (FieldModel f : model.fields()) {
         if (f.fieldName().stringValue().equals(field)) {
            return f.flags().has(AccessFlag.VOLATILE);
         }
      }
      throw new AssertionError("FAILED: field " + field + " not found");
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
            + " (the PropertyContainer override is not built)");
   }
}

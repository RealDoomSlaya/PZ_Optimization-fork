package pzopt;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * pzopt.LauncherJson on a temporary game folder. Nothing holding the JSON: a save replaces it (the backup is the
 * original, no tmp, no pending file). Windows, the JSON held open without delete sharing the way the running game holds
 * it: the plain replace is refused (the bug), a save stages the change as .pzopt-pending and returns false, read()
 * returns the staged copy so the boot's second writer builds on the first one's change, and the exit helper moves it
 * over the JSON once the watched process has ended (retrying while a handle outlives it). Elsewhere the hold does not
 * block a rename. An invalid pending file is ignored, and a save that lands removes any pending file.
 */
public class LauncherJsonTest {
   private static final boolean WINDOWS = File.separatorChar == '\\';
   private static final String STOCK = "{\"mainClass\":\"zombie/gameStates/MainScreenState\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
         + "\"vmArgs\":[\"-Djava.awt.headless=true\",\"-Xmx3072m\",\"-XX:-OmitStackTraceInFastThrow\"],"
         + "\"windows\":{\"6.1\":{\"vmArgs\":[\"-XX:+UseG1GC\"]},\"10.0.17134\":{\"vmArgs\":[\"-XX:+UseZGC\"]}}}";

   public static void main(String[] args) throws Exception {
      Path base = Files.createTempDirectory("pzopt-launcher-test");
      Process dummy = null;
      try {
         if (WINDOWS) {
            // the helper a staged save arms waits for this process instead of the test JVM
            dummy = quiet(new ProcessBuilder("ping", "-n", "120", "127.0.0.1")).start();
            LauncherJson.helperPid = dummy.pid();
         }
         Path g = game(base.resolve("held"));
         JSONObject j1 = noHolder(g);
         invalidPending(game(base.resolve("invalid")));
         if (WINDOWS) {
            heldOnWindows(g, j1, dummy);
         } else {
            heldElsewhere(g, j1);
         }
         saveRemovesPending(game(base.resolve("stale")));
      } finally {
         if (dummy != null) {
            dummy.destroy();
         }
         Process armed = LauncherJson.armedHelper;
         if (armed != null) {
            armed.waitFor(20, TimeUnit.SECONDS);
         }
         Updater.deleteTree(base);
      }
      System.out.println("LauncherJsonTest ok");
   }

   /** 1. Nothing holds the JSON: the save lands. */
   static JSONObject noHolder(Path g) throws Exception {
      JSONObject j1 = LauncherJson.read(g);
      Check.check(j1.similar(new JSONObject(STOCK)), "read returns the live JSON");
      j1.getJSONArray("vmArgs").put("-Dpzopt.test=one");
      Check.check(LauncherJson.save(g, j1), "nothing holds the JSON: save replaces it and returns true");
      Check.check(json(g).similar(j1), "the JSON is the saved one: " + json(g));
      Check.check(!Files.exists(pending(g)), "no pending file after a save that landed");
      Check.check(!Files.exists(tmp(g)), "no tmp file after a save that landed");
      Check.check(Files.readString(g.resolve("ProjectZomboid64.json.pzopt-backup"), StandardCharsets.UTF_8).equals(STOCK),
            "the backup holds the original");
      return j1;
   }

   /** 2. Windows: the JSON held the way the running game holds it. */
   static void heldOnWindows(Path g, JSONObject j1, Process dummy) throws Exception {
      Path json = g.resolve("ProjectZomboid64.json");
      Path pending = pending(g);
      Path tmp = tmp(g);
      String staged;
      try (FileInputStream hold = new FileInputStream(json.toFile())) { // no FILE_SHARE_DELETE, as the launcher
         Files.writeString(tmp, j1.toString(1) + "\n", StandardCharsets.UTF_8);
         boolean denied = false;
         try {
            Files.move(tmp, json, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AccessDeniedException e) {
            denied = true;
         }
         Files.deleteIfExists(tmp);
         Check.check(denied, "the hold reproduces the bug: the plain replace is refused with AccessDeniedException");

         JSONObject j2 = LauncherJson.read(g);
         j2.getJSONArray("vmArgs").put("-Dpzopt.test=two");
         Check.check(!LauncherJson.save(g, j2), "a held JSON: save stages the change and returns false");
         Check.check(json(g).similar(j1), "the live JSON is unchanged: " + json(g));
         Check.check(Files.exists(pending) && read(pending).similar(j2), "the pending file holds the change");
         Check.check(!Files.exists(tmp), "no tmp file after a staged save");
         Check.check(LauncherJson.read(g).similar(j2), "read returns the staged change");
         Process armed = LauncherJson.armedHelper;
         Check.check(armed != null && armed.isAlive(), "the staged save armed the exit helper");

         JSONObject j3 = LauncherJson.read(g);
         j3.getJSONArray("vmArgs").put("-Dpzopt.test=three");
         Check.check(!LauncherJson.save(g, j3), "the second writer is staged too");
         JSONObject p = read(pending);
         Check.check(has(p, "-Dpzopt.test=one") && has(p, "-Dpzopt.test=two") && has(p, "-Dpzopt.test=three"),
               "the pending file holds both writers' changes: " + p);
         Check.check(json(g).similar(j1), "the live JSON is still unchanged");
         Check.check(!Files.exists(tmp), "no tmp file after the second staged save");
         Check.check(LauncherJson.armedHelper == armed, "the helper is armed once per process");
         staged = Files.readString(pending, StandardCharsets.UTF_8);
      }

      // the game ends: the helper moves the pending file over the JSON (a helper that did not wait moved it ~1.2 s
      // after its start here, so the check at 3 s, with the watched process alive ~5 s, tells the two apart)
      Process child = quiet(new ProcessBuilder("ping", "-n", "6", "127.0.0.1")).start();
      Process h = LauncherJson.startExitHelper(g, child.pid());
      Thread.sleep(3000);
      boolean alive = child.isAlive();
      boolean stillPending = Files.exists(pending);
      Check.check(alive, "the watched process outlives the first check");
      Check.check(stillPending && json(g).similar(j1), "nothing moves while the watched process runs");
      Check.check(child.waitFor(20, TimeUnit.SECONDS), "the watched process ends");
      long deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline
            && (Files.exists(pending) || !Files.readString(json, StandardCharsets.UTF_8).equals(staged))) {
         Thread.sleep(100);
      }
      Check.check(Files.readString(json, StandardCharsets.UTF_8).equals(staged), "the helper moved the staged JSON in: " + json(g));
      Check.check(!Files.exists(pending), "the pending file is gone");
      Check.check(h.waitFor(20, TimeUnit.SECONDS), "the helper ends");

      // the helper the staged save armed: its process ends, nothing is pending, the JSON stays
      Process armed = LauncherJson.armedHelper;
      dummy.destroy();
      Check.check(armed.waitFor(20, TimeUnit.SECONDS), "the armed helper ends after its process");
      Check.check(Files.readString(json, StandardCharsets.UTF_8).equals(staged), "the armed helper left the applied JSON alone");

      // a handle that outlives the watched process: the helper keeps trying and applies the change once it is released
      String restaged;
      Process h2;
      try (FileInputStream hold = new FileInputStream(json.toFile())) {
         JSONObject j4 = LauncherJson.read(g);
         j4.getJSONArray("vmArgs").put("-Dpzopt.test=four");
         Check.check(!LauncherJson.save(g, j4), "held again: the change is staged");
         restaged = Files.readString(pending, StandardCharsets.UTF_8);
         Process child2 = quiet(new ProcessBuilder("ping", "-n", "2", "127.0.0.1")).start();
         h2 = LauncherJson.startExitHelper(g, child2.pid());
         Check.check(child2.waitFor(20, TimeUnit.SECONDS), "the second watched process ends");
         Thread.sleep(4000);
         Check.check(h2.isAlive() && Files.exists(pending) && Files.readString(json, StandardCharsets.UTF_8).equals(staged),
               "while the JSON is still held the helper keeps trying and changes nothing");
      }
      deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline
            && (Files.exists(pending) || !Files.readString(json, StandardCharsets.UTF_8).equals(restaged))) {
         Thread.sleep(100);
      }
      Check.check(Files.readString(json, StandardCharsets.UTF_8).equals(restaged), "released: the helper moved the change in");
      Check.check(!Files.exists(pending), "released: the pending file is gone");
      Check.check(h2.waitFor(20, TimeUnit.SECONDS), "the retrying helper ends");
   }

   /** 3. Linux / macOS: an open file does not block a rename over it. */
   static void heldElsewhere(Path g, JSONObject j1) throws Exception {
      JSONObject j2 = LauncherJson.read(g);
      j2.getJSONArray("vmArgs").put("-Dpzopt.test=two");
      try (FileInputStream hold = new FileInputStream(g.resolve("ProjectZomboid64.json").toFile())) {
         Check.check(LauncherJson.save(g, j2), "elsewhere an open JSON does not block the replace");
      }
      Check.check(json(g).similar(j2), "the JSON is the saved one: " + json(g));
      Check.check(!Files.exists(pending(g)) && !Files.exists(tmp(g)), "no pending or tmp file");
   }

   /** 4. An invalid pending file is ignored and arms nothing. */
   static void invalidPending(Path g) throws Exception {
      JSONObject stock = new JSONObject(STOCK);
      for (String bad : new String[] {"{}", "not json", "{\"mainClass\":\"m\",\"classpath\":[],\"vmArgs\":[\"-Xmx1g\"]}",
            "{\"mainClass\":\"m\",\"classpath\":[\".\"]}"}) {
         Files.writeString(pending(g), bad, StandardCharsets.UTF_8);
         Check.check(LauncherJson.read(g).similar(stock), "an invalid pending file (" + bad + ") falls back to the live JSON");
      }
      Check.check(LauncherJson.armedHelper == null, "an invalid pending file arms no helper");
   }

   /** A valid pending file is what read() returns; a save that lands removes it. */
   static void saveRemovesPending(Path g) throws Exception {
      JSONObject staged = new JSONObject(STOCK);
      staged.getJSONArray("vmArgs").put("-Dpzopt.test=staged");
      Files.writeString(pending(g), staged.toString(1), StandardCharsets.UTF_8);
      // on Windows the helper is armed already (heldOnWindows), so this read starts no second one
      Check.check(LauncherJson.read(g).similar(staged), "read returns a valid pending file");
      JSONObject j = new JSONObject(STOCK);
      j.getJSONArray("vmArgs").put("-Dpzopt.test=landed");
      Check.check(LauncherJson.save(g, j), "the save lands");
      Check.check(json(g).similar(j), "the JSON is the saved one");
      Check.check(!Files.exists(pending(g)), "a save that lands removes the pending file");
      Check.check(LauncherJson.read(g).similar(j), "read returns the live JSON again");
   }

   static Path game(Path g) throws Exception {
      Files.createDirectories(g);
      Files.writeString(g.resolve("ProjectZomboid64.json"), STOCK, StandardCharsets.UTF_8);
      return g;
   }

   static ProcessBuilder quiet(ProcessBuilder pb) {
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      return pb;
   }

   static Path pending(Path g) {
      return g.resolve("ProjectZomboid64.json.pzopt-pending");
   }

   static Path tmp(Path g) {
      return g.resolve("ProjectZomboid64.json.pzopt-tmp");
   }

   static JSONObject json(Path g) throws Exception {
      return read(g.resolve("ProjectZomboid64.json"));
   }

   static JSONObject read(Path f) throws Exception {
      return new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
   }

   static boolean has(JSONObject j, String arg) {
      JSONArray a = j.getJSONArray("vmArgs");
      for (int i = 0; i < a.length(); i++) {
         if (a.getString(i).equals(arg)) {
            return true;
         }
      }
      return false;
   }
}

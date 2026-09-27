package pzopt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * pzopt.Uninstall on a temporary game folder: the plan (the manifest's files, the AOT folder, the DLSS files the
 * Enhancements tab fetched; never a stock file, never a path outside the folder; folders deepest first), the launcher
 * undo (GcChoice's G1 and JIT flags back to the launcher's own), and the Linux / macOS helper for real: nothing is
 * deleted while the watched process lives, then the listed files and the folders they leave empty go, stock files and
 * folders stay, the log says so.
 */
public class UninstallTest {
   static int failures;

   static void check(boolean ok, String what) {
      if (!ok) {
         failures++;
         System.err.println("FAIL: " + what);
      }
   }

   public static void main(String[] args) throws Exception {
      Path dir = Files.createTempDirectory("pzopt-uninstall-test");
      try {
         plan(dir);
         launcher(dir);
         if (java.io.File.separatorChar == '/') {
            helper(dir);
         }
      } finally {
         Updater.deleteTree(dir);
      }
      if (failures > 0) {
         throw new AssertionError(failures + " check(s) failed");
      }
      System.out.println("UninstallTest ok");
   }

   static void write(Path p, String s) throws Exception {
      Files.createDirectories(p.getParent());
      Files.writeString(p, s, StandardCharsets.UTF_8);
   }

   static Path game(Path base) throws Exception {
      Path g = base.resolve("game");
      write(g.resolve("projectzomboid.jar"), "jar");
      write(g.resolve("zombie/iso/IsoChunk.class"), "ours");
      write(g.resolve("zombie/iso/IsoChunk$Inner.class"), "ours");
      write(g.resolve("pzopt/Config.class"), "ours");
      write(g.resolve("pzopt/build-info.properties"), "revision=abc\n");
      write(g.resolve("media/lua/client/pzopt/tab.lua"), "ours");
      write(g.resolve("media/lua/client/Stock.lua"), "stock");
      write(g.resolve("pzopt/aot/pzopt.jar"), "aot");
      write(g.resolve("pzopt/aot/aot.log"), "log");
      write(g.resolve("natives/libstock.so"), "stock");
      write(g.resolve("natives/libpzopt_ngx64.so"), "dlss shim");
      write(g.resolve("natives/" + UpscalerDeps.MARKER), "# written by the button\nrelease=win-abc-1234567\nlibpzopt_ngx64.so\n../evil\n");
      write(base.resolve("outside.txt"), "not ours");
      write(g.resolve(Updater.MANIFEST), "# revision=abc installed=2026-09-27T00:00:00Z\n"
            + "zombie/iso/IsoChunk.class " + "0".repeat(64) + "\n"
            + "zombie/iso/IsoChunk$Inner.class " + "0".repeat(64) + "\n"
            + "pzopt/Config.class " + "0".repeat(64) + "\n"
            + "pzopt/build-info.properties " + "0".repeat(64) + "\n"
            + "media/lua/client/pzopt/tab.lua " + "0".repeat(64) + "\n"
            + "../outside.txt " + "0".repeat(64) + "\n"
            + "pzopt/gone.class " + "0".repeat(64) + "\n");
      write(g.resolve(Updater.FILE_LIST), "zombie/iso/IsoChunk.class\n");
      return g;
   }

   static void plan(Path base) throws Exception {
      Path g = game(base.resolve("plan"));
      Uninstall.Plan p = Uninstall.plan(g);
      List<Path> f = p.files();
      for (String rel : new String[] {"zombie/iso/IsoChunk.class", "zombie/iso/IsoChunk$Inner.class", "pzopt/Config.class",
            "media/lua/client/pzopt/tab.lua", "pzopt/aot/pzopt.jar", "pzopt/aot/aot.log", "natives/libpzopt_ngx64.so",
            "natives/" + UpscalerDeps.MARKER, Updater.MANIFEST, Updater.FILE_LIST}) {
         check(f.contains(g.resolve(rel)), "plan lists " + rel);
      }
      check(!f.contains(g.resolve("projectzomboid.jar")), "plan never lists the game jar");
      check(!f.contains(g.resolve("media/lua/client/Stock.lua")) && !f.contains(g.resolve("natives/libstock.so")), "plan never lists stock files");
      check(f.stream().allMatch(x -> x.startsWith(g)), "plan stays inside the game folder: " + f);
      check(!f.contains(g.resolve("pzopt/gone.class")), "plan skips files that are already gone");
      List<Path> d = p.dirs();
      check(d.contains(g.resolve("zombie/iso")) && d.contains(g.resolve("zombie")) && d.contains(g.resolve("media/lua/client/pzopt")),
            "plan lists the folders the files leave: " + d);
      check(d.indexOf(g.resolve("zombie/iso")) < d.indexOf(g.resolve("zombie")), "folders deepest first");
      check(!d.contains(g), "the game folder itself is never a candidate");
   }

   static void launcher(Path base) throws Exception {
      Path g = base.resolve("launcher");
      write(g.resolve("ProjectZomboid64.json"), "{\"mainClass\":\"zombie/gameStates/MainScreenState\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
            + "\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseG1GC\",\"-XX:MaxGCPauseMillis=4\",\"" + GcChoice.MARKER_PAUSE + "\",\""
            + GcChoice.JIT_MARKER + "\",\"-XX:PerMethodTrapLimit=0\",\"-XX:PerBytecodeTrapLimit=0\"]}");
      check(GcChoice.undo(g), "launcher undo reports a change");
      String j = Files.readString(g.resolve("ProjectZomboid64.json"));
      check(j.contains("-XX:+UseZGC") && !j.contains("UseG1GC") && !j.contains("MaxGCPauseMillis") && !j.contains("pzopt.gc")
            && !j.contains("TrapLimit") && !j.contains("pzopt.jit") && j.contains("-Xmx3072m"), "launcher back to its own flags: " + j);
      check(!GcChoice.undo(g), "a second undo changes nothing");
   }

   static void helper(Path base) throws Exception {
      Path g = game(base.resolve("helper"));
      Uninstall.Plan p = Uninstall.plan(g);
      Path files = base.resolve("files.txt");
      Path dirs = base.resolve("dirs.txt");
      Path log = base.resolve("uninstall.log");
      Files.write(files, p.files().stream().map(Path::toString).toList(), StandardCharsets.UTF_8);
      Files.write(dirs, p.dirs().stream().map(Path::toString).toList(), StandardCharsets.UTF_8);
      Process game = new ProcessBuilder("sleep", "1").start();
      Process h = new ProcessBuilder(Uninstall.helper(false, game.pid(), files, dirs, log)).start();
      Thread.sleep(300);
      check(Files.exists(g.resolve("zombie/iso/IsoChunk.class")), "nothing is deleted while the game runs");
      check(h.waitFor(20, TimeUnit.SECONDS), "helper ends after the game");
      for (Path f : p.files()) {
         check(!Files.exists(f), "helper removed " + g.relativize(f));
      }
      check(!Files.exists(g.resolve("zombie")) && !Files.exists(g.resolve("pzopt")) && !Files.exists(g.resolve("media/lua/client/pzopt")),
            "helper removed the emptied folders");
      check(Files.exists(g.resolve("projectzomboid.jar")) && Files.exists(g.resolve("media/lua/client/Stock.lua"))
            && Files.exists(g.resolve("natives/libstock.so")) && Files.exists(base.resolve("helper/outside.txt")), "helper left stock and outside files");
      String l = Files.exists(log) ? Files.readString(log) : "";
      check(l.contains("uninstall finished; 0 files could not be removed"), "log line: " + l);
      check(!Files.exists(files) && !Files.exists(dirs), "helper removed its lists");
   }
}

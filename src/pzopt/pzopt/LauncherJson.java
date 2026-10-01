package pzopt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The launcher JSON (ProjectZomboid64.json, read by the native launcher at launch) for its in-game writers, AotCache and
 * GcChoice (and through them Uninstall): the one place that reads and writes it.
 *
 * A save backs the file up once (ProjectZomboid64.json.pzopt-backup), writes ProjectZomboid64.json.pzopt-tmp, reads it
 * back and moves it over the JSON. On Windows the running game holds the JSON (ProjectZomboid64.exe reads it at startup
 * and the JVM runs inside that process), and Windows refuses to replace a file another handle holds without delete
 * sharing: the move failed with AccessDeniedException at every boot. Linux and macOS rename over an open file. So on
 * Windows a refused replace is staged as ProjectZomboid64.json.pzopt-pending and a hidden PowerShell, started at most
 * once per process, waits for this process to end and moves the pending file over the JSON. {@link #read} returns the
 * pending copy when there is one, so the boot's second writer (and the next boot, if the helper could not apply it)
 * builds on the staged change instead of the live file.
 */
final class LauncherJson {
   static final String NAME = "ProjectZomboid64.json";
   static final String BACKUP = NAME + ".pzopt-backup";
   static final String TMP = NAME + ".pzopt-tmp";
   static final String PENDING = NAME + ".pzopt-pending";
   private static final boolean WINDOWS = File.separatorChar == '\\';

   /** The process the exit helper waits for: this one, which holds the JSON; a test points it at another. */
   static long helperPid = ProcessHandle.current().pid();
   /** The helper {@link #arm} started, for the test. */
   static volatile Process armedHelper;
   private static boolean armed;

   private LauncherJson() {
   }

   /** The launcher as the next launch will see it: the staged copy when there is a valid one, else the live JSON. */
   static synchronized JSONObject read(Path game) throws IOException {
      JSONObject staged = pending(game);
      if (staged != null) {
         arm(game);
         return staged;
      }
      return new JSONObject(Files.readString(game.resolve(NAME), StandardCharsets.UTF_8));
   }

   /**
    * Writes the launcher; true when the JSON was replaced, false when the running game holds it (Windows) and the change
    * is staged until the game exits. The tmp file never remains.
    */
   static synchronized boolean save(Path game, JSONObject j) throws IOException {
      Path json = game.resolve(NAME);
      Path backup = game.resolve(BACKUP);
      if (!Files.exists(backup)) {
         Files.copy(json, backup);
      }
      Path tmp = game.resolve(TMP);
      Path pending = game.resolve(PENDING);
      try {
         Files.writeString(tmp, j.toString(1) + "\n", StandardCharsets.UTF_8);
         JSONObject check = parse(tmp);
         if (check == null || !valid(check)) {
            throw new IOException("launcher JSON check failed; left unchanged");
         }
         try {
            Files.move(tmp, json, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (FileSystemException e) {
            if (!WINDOWS) {
               throw e;
            }
            try {
               Files.move(tmp, pending, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException s) {
               e.addSuppressed(s);
               throw e;
            }
            Log.info("launcher JSON: " + NAME + " is in use by the running game; the change is staged as " + PENDING
                  + " and applied when the game exits");
            arm(game);
            return false;
         }
      } catch (IOException | RuntimeException e) {
         try {
            Files.deleteIfExists(tmp);
         } catch (IOException d) {
            e.addSuppressed(d);
         }
         throw e;
      }
      Files.deleteIfExists(pending); // the JSON holds the newest form now
      return true;
   }

   /** The staged launcher, or null when there is none or it is not a valid launcher JSON. */
   private static JSONObject pending(Path game) {
      Path p = game.resolve(PENDING);
      if (!Files.isRegularFile(p)) {
         return null;
      }
      try {
         JSONObject j = parse(p);
         if (j != null && valid(j)) {
            return j;
         }
      } catch (IOException ignored) {
      }
      Log.warn("launcher JSON: " + PENDING + " is not a valid launcher JSON; reading " + NAME);
      return null;
   }

   private static JSONObject parse(Path f) throws IOException {
      try {
         return new JSONObject(Files.readString(f, StandardCharsets.UTF_8));
      } catch (JSONException e) {
         return null;
      }
   }

   static boolean valid(JSONObject j) {
      JSONArray cp = j.optJSONArray("classpath");
      JSONArray vm = j.optJSONArray("vmArgs");
      return j.has("mainClass") && cp != null && cp.length() > 0 && vm != null && vm.length() > 0;
   }

   /** Starts the exit helper once per process (Windows only). */
   private static void arm(Path game) {
      if (armed || !WINDOWS) {
         return;
      }
      armed = true;
      try {
         Process p = startExitHelper(game, helperPid);
         armedHelper = p;
         Log.info("launcher JSON: helper (pid " + p.pid() + ") waits for pid " + helperPid + " to end, then moves " + PENDING + " over " + NAME);
      } catch (Exception e) {
         Log.warn("launcher JSON: could not start the exit helper, " + PENDING + " stays staged: " + e);
      }
   }

   /** The helper as in pzopt.Restart / pzopt.Uninstall: a hidden PowerShell, no input, output discarded. */
   static Process startExitHelper(Path game, long pid) throws IOException {
      Path dir = game.toAbsolutePath();
      String encoded = Base64.getEncoder().encodeToString(script(pid, dir.resolve(PENDING), dir.resolve(NAME)).getBytes(StandardCharsets.UTF_16LE));
      ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-EncodedCommand", encoded).directory(dir.toFile());
      pb.redirectInput(ProcessBuilder.Redirect.from(new File("NUL")));
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      return pb.start();
   }

   /**
    * Waits for {@code pid} with no timeout (a session lasts hours), then moves the pending file over the JSON; a few
    * tries, since a handle can outlive the process by a moment. Nothing to do once the pending file is gone (a later save
    * of the same session landed).
    */
   static String script(long pid, Path pending, Path json) {
      return String.join("\n",
            "$ErrorActionPreference = 'SilentlyContinue'",
            "Wait-Process -Id " + pid,
            "for ($i = 0; $i -lt 40; $i++) {",
            "  if (-not (Test-Path -LiteralPath " + quote(pending) + ")) { break }",
            "  try { Move-Item -LiteralPath " + quote(pending) + " -Destination " + quote(json) + " -Force -ErrorAction Stop; break } catch { Start-Sleep -Milliseconds 250 }",
            "}");
   }

   private static String quote(Path p) {
      return "'" + p.toString().replace("'", "''") + "'";
   }
}

package pzopt;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * Harness scene {@code explore=lights} (2026-09-27, the maintainer's report: light sources make floating orbs of light, two of
 * them over their car): the player gets out of the car (headlights as the save had them), walks once round it, then walks
 * from lit lamp to lit lamp on foot (the movement keys along a grid path, never teleported), circles each and stands
 * watching it, so the lights are seen from a moving and a still camera. The tree walk's machinery (pzopt.TreeWalk).
 *
 * <p>With {@code director=jev} the next action comes from harness/lights/light-director.py (TypeSafe's Jev) through
 * {@code Zomboid/pzopt-lights-state.json} / {@code pzopt-lights-cmd.txt}: next_light, circle_light, watch, done (hold while
 * nothing else applies). Without it an autopilot plays the same order. Flags: {@code lights_radius} (tiles, 30),
 * {@code lights_max} (targets, the car included, 4), {@code lights_watch} (seconds, 3), {@code lights_max_seconds} (the
 * scene's time cap, 120). Every frame the player and each target's window position go to {@code Zomboid/pzopt-lights.out}.
 */
public final class LightWalk {
   private LightWalk() {
   }

   private static final String[] ACTIONS = {"next_light", "circle_light", "watch", "hold", "done"};

   private static boolean on, director, started, finished;
   private static String command = "hold";
   private static int commandSeq = -1, commands, maxTrees;
   private static float radius, watchSecs;
   private static long startNs, lastStateNs, lastCmdCheckNs, lastPlanNs, lastProgressNs, lastLogNs;
   private static java.io.File stateFile, cmdFile;
   private static BufferedWriter boxes;

   /** a target: x, y, z, kind (0 the player's car, 1 a lamp), lamp radius */
   private static final ArrayList<int[]> trees = new ArrayList<>();
   private static final Set<int[]> visited = new HashSet<>();
   private static final Set<int[]> unreachable = new HashSet<>();
   private static int[] current;
   private static float watched, maxSeconds;
   private static int circlePoint; // explore=lights: the next of the 8 waypoints round the current tree (8 = the lap is done)
   private static long pointSinceNs, stillSinceNs;
   private static float stillX, stillY;

   private static int[] pathX = new int[0], pathY = new int[0];
   private static int pathIdx, pathLen, goalX, goalY;
   private static float progressX, progressY;
   private static final Set<Long> blocked = new HashSet<>();

   public static boolean active() {
      return on;
   }

   public static boolean done() {
      return finished;
   }

   /** World-ready (game thread): the trees around the player. */
   static void worldReady(IsoPlayer p) {
      on = "lights".equalsIgnoreCase(HarnessFlags.get("explore", "").trim());
      if (!on) {
         return;
      }
      director = "jev".equalsIgnoreCase(HarnessFlags.get("director", "").trim());
      radius = Float.parseFloat(HarnessFlags.get("lights_radius", "30").trim());
      maxTrees = Integer.parseInt(HarnessFlags.get("lights_max", "4").trim());
      watchSecs = Float.parseFloat(HarnessFlags.get("lights_watch", "3").trim());
      maxSeconds = Float.parseFloat(HarnessFlags.get("lights_max_seconds", "120").trim());
      java.io.File z = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir());
      stateFile = new java.io.File(z, "pzopt-lights-state.json");
      cmdFile = new java.io.File(z, "pzopt-lights-cmd.txt");
      cmdFile.delete();
      try {
         boxes = new BufferedWriter(new FileWriter(new java.io.File(z, "pzopt-lights.out")));
         boxes.write("# epoch_ms L kind x y z window_x window_y (the light's square at its height) | epoch_ms P player_x player_y moving action screen_w screen_h zoom off_x off_y\n");
      } catch (java.io.IOException e) {
         Log.warn("harness: explore=lights: " + e);
      }
      p.getCheats().set(zombie.characters.CheatType.GOD_MODE, true);
      p.setInvisible(true, true);
   }

   private static void findLights(IsoPlayer p) {
      java.util.Stack<zombie.iso.IsoLightSource> all = IsoWorld.instance.currentCell.getLamppostPositions();
      ArrayList<Object[]> found = new ArrayList<>();
      for (int i = 0; all != null && i < all.size(); i++) {
         zombie.iso.IsoLightSource l = all.get(i);
         if (!l.active || l.radius <= 0) continue;
         float d = (float)Math.hypot(l.x + 0.5F - p.getX(), l.y + 0.5F - p.getY());
         if (d <= radius) found.add(new Object[] {d, new int[] {l.x, l.y, l.z, 1, l.radius}});
      }
      found.sort((a, b) -> Float.compare((Float)a[0], (Float)b[0]));
      for (Object[] o : found) trees.add((int[])o[1]);
   }

   static void routeStart(IsoPlayer p) {
      if (!on || started) return;
      started = true;
      startNs = System.nanoTime();
      lastProgressNs = startNs;
      int[] car = null;
      zombie.vehicles.BaseVehicle v = p.getVehicle();
      if (v != null) {
         car = new int[] {(int)Math.floor(v.getX()), (int)Math.floor(v.getY()), (int)Math.floor(v.getZ()), 0, 0};
         Log.info(String.format(Locale.ROOT, "harness: explore=lights: out of the car at %.1f,%.1f, headlights %s", v.getX(), v.getY(), v.getHeadlightsOn()));
         v.exit(p);
      }
      command = director ? "hold" : "next_light";
      findLights(p);
      IsoGridSquare cur = p.getCurrentSquare();
      ArrayList<int[]> reachable = new ArrayList<>();
      if (car != null) reachable.add(car); // the car first (its headlights), then the lamps the grid path reaches, nearest first
      for (int[] t : trees) {
         if (reachable.size() >= maxTrees) break;
         if (cur == null) continue;
         plan(cur, t[0], t[1], 2.5F);
         if (pathReaches) reachable.add(t);
      }
      pathLen = 0;
      trees.clear();
      trees.addAll(reachable);
      StringBuilder sb = new StringBuilder();
      for (int[] t : trees) sb.append(' ').append(t[3] == 0 ? "car@" : "lamp@").append(t[0]).append(',').append(t[1]).append(',').append(t[2]);
      Log.info(String.format(Locale.ROOT, "harness: explore=lights: %d targets within %.0f tiles:%s%s", trees.size(), radius, sb,
            director ? ", director jev" : ", autopilot"));
   }

   /** Per frame while the run is live (game thread). */
   static void tick(IsoPlayer p, long nowNs) {
      if (!on) return;
      logBoxes(p, nowNs);
      if (!started || finished) return;
      float dt = Math.min(0.1F, zombie.GameTime.getInstance().getRealworldSecondsSinceLastUpdate());
      if (director) {
         if (nowNs - lastCmdCheckNs >= 100_000_000L) {
            lastCmdCheckNs = nowNs;
            readCommand();
         }
      } else {
         autopilot();
      }
      Showcase.releaseKeys();
      if ((nowNs - startNs) / 1e9 > maxSeconds) {
         command = "done"; // the scene's time cap (trees_max_seconds): a walk that cannot finish still ends
         Log.info("harness: explore=lights: time cap reached");
      }
      // watchdog: a walking command that has not moved the player for 6 s gives the current tree up (a wall the grid did not see)
      boolean walking = "next_light".equals(command) || "circle_light".equals(command);
      if (!walking || Math.hypot(p.getX() - stillX, p.getY() - stillY) > 0.3) {
         stillX = p.getX();
         stillY = p.getY();
         stillSinceNs = nowNs;
      } else if (current != null && nowNs - stillSinceNs > 6_000_000_000L) {
         Log.info(String.format(Locale.ROOT, "harness: explore=lights: stuck 6 s at %.1f,%.1f (%s), target at %d,%d given up", p.getX(), p.getY(), command,
               current[0], current[1]));
         giveUp();
         stillSinceNs = nowNs;
      }
      switch (command) {
         case "next_light" -> {
            if (current == null || finishedCurrent()) {
               current = nextTree(p);
               circlePoint = 0;
               pointSinceNs = 0L; // walkCircle starts the lap from the player's side
               watched = 0F;
               if (current != null) {
                  Log.info(String.format(Locale.ROOT, "harness: explore=lights: next %s at %d,%d,%d (+%.1f s)", current[3] == 0 ? "car" : "lamp", current[0], current[1], current[2], (nowNs - startNs) / 1e9));
               }
            }
            if (current != null && !visited.contains(current)) {
               int r = walkTo(p, nowNs, current[0], current[1], current[3] == 0 ? 3.5F : 2.5F, true);
               if (r > 0) {
                  visited.add(current);
               } else if (r < 0) {
                  Log.info("harness: explore=lights: no way to the target at " + current[0] + "," + current[1] + ", next one");
                  giveUp();
               }
            }
         }
         case "circle_light" -> {
            if (current != null) walkCircle(p, nowNs);
         }
         case "watch" -> {
            if (current != null && !p.isPlayerMoving()) watched += dt;
         }
         case "done" -> {
            finished = true;
            Log.info(String.format(Locale.ROOT, "harness: explore=lights: done at +%.1f s, %d targets visited, %d commands",
                  (nowNs - startNs) / 1e9, visited.size(), commands));
            flushBoxes();
         }
         default -> { // hold
         }
      }
      if (director && nowNs - lastStateNs >= 300_000_000L) {
         lastStateNs = nowNs;
         writeState(p, nowNs);
      }
   }

   private static boolean circled() {
      return circlePoint >= 8;
   }

   private static boolean finishedCurrent() {
      return unreachable.contains(current) || visited.contains(current) && circled() && watched >= watchSecs;
   }

   /** The current tree cannot be walked to or round: it counts as finished (unreachable), the walk goes on. */
   private static void giveUp() {
      unreachable.add(current);
      visited.remove(current);
      circlePoint = 8;
      watched = watchSecs;
      pathLen = 0;
   }

   private static int[] nextTree(IsoPlayer p) {
      if (visited.size() + unreachable.size() >= maxTrees + 3 || visited.size() >= maxTrees) return null;
      for (int[] t : trees) { // in list order: the car first, then the lamps nearest the start first
         if (!visited.contains(t) && !unreachable.contains(t)) return t;
      }
      return null;
   }

   /**
    * One lap round the current tree: 8 waypoints 2.5 tiles out, clockwise from the player's side, each walked on the grid
    * path (walls and fences walked round); a waypoint not reached within 3 s is skipped (a wall, a building).
    */
   private static void walkCircle(IsoPlayer p, long nowNs) {
      if (circled()) return;
      int cx = current[0], cy = current[1];
      double rr = current[3] == 0 ? 3.5 : 2.5; // (round the car a little wider)
      double a0 = Math.atan2(p.getY() - cy - 0.5, p.getX() - cx - 0.5);
      double a = circlePoint == 0 && pointSinceNs == 0L ? a0 : circleBase + circlePoint * Math.PI / 4.0;
      if (circlePoint == 0 && pointSinceNs == 0L) {
         circleBase = a0 + Math.PI / 4.0;
      }
      int gx = (int)Math.floor(cx + 0.5 + rr * Math.cos(circleBase + circlePoint * Math.PI / 4.0));
      int gy = (int)Math.floor(cy + 0.5 + rr * Math.sin(circleBase + circlePoint * Math.PI / 4.0));
      if (pointSinceNs == 0L) {
         pointSinceNs = nowNs;
      }
      int r = walkTo(p, nowNs, gx, gy, 1.2F, false);
      if (r != 0 || nowNs - pointSinceNs > 3_000_000_000L) {
         circlePoint++;
         pointSinceNs = nowNs;
         pathLen = 0;
      }
   }

   private static double circleBase;

   // ---- walking: a grid path over the loaded ground-level squares ----

   /**
    * 1 once the player stands within {@code tol} tiles of the goal square, 0 while walking, -1 when no free square that
    * close can be reached (a tree behind a fence or in a yard). {@code run}: runs outdoors.
    */
   private static int walkTo(IsoPlayer p, long nowNs, int gx, int gy, float tol, boolean run) {
      IsoGridSquare cur = p.getCurrentSquare();
      if (cur == null) return 0;
      if (Math.hypot(gx + 0.5F - p.getX(), gy + 0.5F - p.getY()) < tol) {
         pathLen = 0;
         return 1;
      }
      boolean stuck = pathLen > 0 && nowNs - lastProgressNs > 1_500_000_000L;
      if (stuck) {
         blocked.add(key(pathX[Math.min(pathIdx, pathLen - 1)], pathY[Math.min(pathIdx, pathLen - 1)]));
      }
      if (gx != goalX || gy != goalY || stuck || pathIdx >= pathLen || nowNs - lastPlanNs > 2_000_000_000L) {
         goalX = gx;
         goalY = gy;
         lastPlanNs = nowNs;
         lastProgressNs = nowNs;
         progressX = p.getX();
         progressY = p.getY();
         plan(cur, gx, gy, tol);
         if (pathLen == 0 || !pathReaches) return -1; // nowhere nearer (it used to count as reached: the walk then circled a tree 10 tiles away into a church wall)
      }
      if (Math.hypot(p.getX() - progressX, p.getY() - progressY) > 0.4) {
         progressX = p.getX();
         progressY = p.getY();
         lastProgressNs = nowNs;
      }
      while (pathIdx < pathLen && Math.hypot(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY()) < 0.35F) {
         pathIdx++;
      }
      if (pathIdx >= pathLen) return 0;
      Showcase.moveKeys(pathX[pathIdx] + 0.5F - p.getX(), pathY[pathIdx] + 0.5F - p.getY());
      if (run && cur.isOutside()) Showcase.holdKey("Run");
      return 0;
   }

   private static long key(int x, int y) {
      return ((long)x << 32) ^ (y & 0xFFFFFFFFL);
   }

   private static boolean pathReaches;

   /** Breadth-first over the loaded squares around the player to the first square within {@code tol} of the goal, else to the reachable square nearest it. */
   private static void plan(IsoGridSquare start, int gx, int gy, float tol) {
      IsoCell cell = start.getCell();
      int r = 60, w = 2 * r + 1, ox = start.x - r, oy = start.y - r, z = start.z;
      int[] prev = new int[w * w];
      java.util.Arrays.fill(prev, -2);
      ArrayDeque<Integer> q = new ArrayDeque<>();
      int s = (start.y - oy) * w + (start.x - ox);
      prev[s] = -1;
      q.add(s);
      int found = -1, nearest = s;
      float nearestD = (float)Math.hypot(start.x - gx, start.y - gy);
      int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
      while (!q.isEmpty()) {
         int i = q.poll();
         int x = ox + i % w, y = oy + i / w;
         IsoGridSquare a = cell.getGridSquare(x, y, z);
         if (a == null) continue;
         if (Math.hypot(x - gx, y - gy) < tol) {
            found = i;
            break;
         }
         float d = (float)Math.hypot(x - gx, y - gy);
         if (d < nearestD) {
            nearestD = d;
            nearest = i;
         }
         for (int[] n : nb) {
            int nx = x + n[0], ny = y + n[1];
            if (nx < ox || ny < oy || nx >= ox + w || ny >= oy + w) continue;
            int j = (ny - oy) * w + (nx - ox);
            if (prev[j] != -2) continue;
            IsoGridSquare b = cell.getGridSquare(nx, ny, z);
            if (b == null || blocked.contains(key(nx, ny)) || !b.isFree(false) || a.isWallTo(b) || a.isWindowBlockedTo(b) || a.isDoorBlockedTo(b)) continue;
            prev[j] = i;
            q.add(j);
         }
      }
      pathReaches = found >= 0;
      int end = found >= 0 ? found : nearest;
      int n = 0;
      for (int i = end; i >= 0; i = prev[i]) n++;
      if (pathX.length < n) {
         pathX = new int[n];
         pathY = new int[n];
      }
      int k = n;
      for (int i = end; i >= 0; i = prev[i]) {
         k--;
         pathX[k] = ox + i % w;
         pathY[k] = oy + i / w;
      }
      pathLen = n <= 1 ? 0 : n;
      pathIdx = Math.min(1, n);
   }

   // ---- the targets' screen positions, per frame ----

   private static void logBoxes(IsoPlayer p, long nowNs) {
      if (boxes == null || IsoCamera.frameState == null) return;
      try {
         long ms = System.currentTimeMillis();
         float zoom = Core.getInstance().getZoom(0);
         float offX = IsoCamera.getOffX(), offY = IsoCamera.getOffY();
         int ts = Core.tileScale;
         int sw = IsoCamera.getScreenWidth(0), sh = IsoCamera.getScreenHeight(0);
         for (int[] t : trees) {
            float sx = (t[0] - t[1]) * (32 * ts), sy = (t[0] + t[1] + 1) * (16 * ts) - t[2] * (96 * ts);
            float wx = (sx - offX) / zoom, wy = (sy - offY) / zoom;
            if (wx < -200 || wy < -200 || wx > sw + 200 || wy > sh + 200) continue;
            boxes.write(String.format(Locale.ROOT, "%d L %d %d %d %d %.1f %.1f\n", ms, t[3], t[0], t[1], t[2], wx, wy));
         }
         boxes.write(String.format(Locale.ROOT, "%d P %.2f %.2f %b %s %d %d %.4f %.1f %.1f\n", ms, p.getX(), p.getY(), p.isPlayerMoving(), command, sw, sh, zoom, offX, offY));
         if (nowNs - lastLogNs > 1_000_000_000L) {
            lastLogNs = nowNs;
            boxes.flush();
         }
      } catch (java.io.IOException e) {
         boxes = null;
      }
   }

   private static void flushBoxes() {
      try {
         if (boxes != null) boxes.flush();
      } catch (java.io.IOException e) {
         // the run ends anyway
      }
   }

   // ---- the director (Jev) ----

   /** Without a director: to the next tree, circle it, watch it, next. */
   private static void autopilot() {
      String next;
      if (current == null || !visited.contains(current)) {
         next = visited.size() >= maxTrees || current == null && !visited.isEmpty() && nextTreeless() ? "done" : "next_light";
      } else if (!circled()) {
         next = "circle_light";
      } else if (watched < watchSecs) {
         next = "watch";
      } else {
         next = visited.size() >= maxTrees ? "done" : "next_light";
      }
      if (!next.equals(command)) {
         Log.info("harness: explore=lights autopilot: " + command + " -> " + next);
         command = next;
      }
   }

   private static boolean nextTreeless() {
      return trees.size() <= visited.size();
   }

   private static void readCommand() {
      try {
         if (cmdFile == null || !cmdFile.isFile()) return;
         String[] parts = java.nio.file.Files.readString(cmdFile.toPath()).trim().split("\\s+");
         if (parts.length < 2) return;
         int seq = Integer.parseInt(parts[0]);
         if (seq == commandSeq) return;
         commandSeq = seq;
         String c = parts[1];
         if (!java.util.Arrays.asList(ACTIONS).contains(c)) return;
         if (!c.equals(command)) {
            commands++;
            Log.info("harness: explore=lights director: " + command + " -> " + c + " (#" + seq + ")");
         }
         command = c;
      } catch (Exception e) {
         // a half-written file: the next check reads it
      }
   }

   private static void writeState(IsoPlayer p, long nowNs) {
      boolean at = current != null && (visited.contains(current) || unreachable.contains(current));
      float dist = current == null ? -1F : (float)Math.hypot(current[0] + 0.5F - p.getX(), current[1] + 0.5F - p.getY());
      int left = 0;
      for (int[] t : trees) left += !visited.contains(t) && !unreachable.contains(t) ? 1 : 0;
      left = Math.min(left, Math.max(0, maxTrees - visited.size()));
      String json = String.format(Locale.ROOT,
            "{\"t\":%d,\"seconds_since_start\":%.1f,\"current_action\":\"%s\",\"player\":{\"moving\":%b},"
                  + "\"lights\":{\"to_visit_total\":%d,\"visited\":%d,\"left_to_visit\":%d},"
                  + "\"current_light\":{\"chosen\":%b,\"kind\":\"%s\",\"reached\":%b,\"distance_tiles\":%.1f,\"circled_once\":%b,\"degrees_circled\":%.0f,"
                  + "\"seconds_watched\":%.1f,\"seconds_to_watch\":%.1f}}",
            System.currentTimeMillis(), (nowNs - startNs) / 1e9, command, p.isPlayerMoving(), Math.min(maxTrees, trees.size()), visited.size(), left,
            current != null, current == null ? "none" : current[3] == 0 ? "car" : "lamp", at, dist, circled(), Math.min(8, circlePoint) * 45.0, watched, watchSecs);
      try {
         java.io.File tmp = new java.io.File(stateFile.getPath() + ".tmp");
         java.nio.file.Files.writeString(tmp.toPath(), json);
         java.nio.file.Files.move(tmp.toPath(), stateFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception e) {
         Log.warn("harness: explore=lights: state write failed: " + e);
      }
   }
}

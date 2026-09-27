package zombie;

import java.util.ArrayList;
import java.util.List;
import zombie.characters.IsoZombie;
import zombie.debug.DebugLog;
import zombie.debug.DebugType;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.objects.IsoDeadBody;
import zombie.util.Type;
import zombie.util.list.PZArrayUtil;

public final class MovingObjectUpdateSchedulerUpdateBucket {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.MovingObjectUpdateSchedulerUpdateBucket");
   }

   private final UpdateSchedulerSimulationLevel simulationLevel;
   private final int frameMod;
   private final List<IsoMovingObject>[] buckets;

   public MovingObjectUpdateSchedulerUpdateBucket(UpdateSchedulerSimulationLevel simulationLevel) {
      this.simulationLevel = simulationLevel;
      this.frameMod = simulationLevel.getFrameMod();
      this.buckets = PZArrayUtil.newInstance(List.class, this.frameMod, ArrayList::new);
   }

   public void clear() {
      for (List<IsoMovingObject> bucket : this.buckets) {
         bucket.clear();
      }
   }

   public void add(IsoMovingObject o) {
      int index = o.getID() % this.frameMod;
      this.buckets[index].add(o);
   }

   public void update(int frameCounter) {
      GameTime.getInstance().perObjectMultiplier = this.frameMod;
      List<IsoMovingObject> fullSimulation = this.buckets[frameCounter % this.frameMod];
      // pzopt: entityUpdateParallel. This bucket's entities run their four update calls on the frame workers
      // instead of one after another here. The collection walks stock's loop in stock's order, so the two cases
      // that touch shared cell state — a dead body going to the cell's remove set, and the reused-zombie debug
      // branch — stay on the game thread exactly where stock had them; only the four per-entity calls move. The
      // batch runs inside this method because perObjectMultiplier above is this bucket's frame mod and is 1 again
      // below: it is only constant while one bucket's entities are in flight, so a batch never spans two buckets.
      boolean pzoptBatch = pzopt.UpdateBatch.enabled();
      if (pzoptBatch) {
         pzopt.UpdateBatch.clear();
      }

      for (int i = 0; i < fullSimulation.size(); ++i) {
         IsoMovingObject isoMovingObject = fullSimulation.get(i);
         if (isoMovingObject instanceof IsoDeadBody) {
            IsoWorld.instance.getCell().getRemoveList().add(isoMovingObject);
            continue;
         }

         IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
         if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
            DebugLog.log(
               DebugType.Zombie,
               "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + String.valueOf(isoMovingObject)
            );
            continue;
         }

         // pzopt: entityUpdateParallel. A player, an animal or a grappled zombie is not handed over — it falls
         // through to the four calls below, in this loop, in stock's order, exactly as if the key were off.
         if (pzoptBatch && pzopt.UpdateBatch.batchable(isoMovingObject)) {
            pzopt.UpdateBatch.add(isoMovingObject); // pzopt: entityUpdateParallel, run after the loop
            continue;
         }

         isoMovingObject.setCurrentSimulationLevel(this.simulationLevel);
         isoMovingObject.preupdate();
         isoMovingObject.frameStep();
         isoMovingObject.update();
         pzopt.UpdateBatch.stampInline(isoMovingObject); // pzopt: entityUpdatePipeline -- while the previous bucket's batch is airborne, freeze this inline entity's post-update position so its workers read a stable value (what they saw when inline entities all ran before the dispatch)
      }

      if (pzoptBatch) {
         // pzopt: entityUpdateParallel. Same four calls per entity, in the same order, on the workers; an entity
         // that throws is reported once and turns the batching off, so the next frame walks the loop above.
         if (pzopt.UpdateBatch.pipelineOn()) { // pzopt: entityUpdatePipeline (devPipelineAlternate flips this per window for the same-run A/B) -- the collection above overlapped the previous bucket's flight; land it, then send this bucket up without waiting
            pzopt.UpdateBatch.joinPending(); // pzopt: entityUpdatePipeline
            pzopt.UpdateBatch.dispatchAsync(this.simulationLevel); // pzopt: entityUpdatePipeline -- joined by the next bucket's update() or the scheduler override after the last one
         } else { // pzopt: entityUpdatePipeline
            pzopt.UpdateBatch.run(this.simulationLevel);
         } // pzopt: entityUpdatePipeline
      }

      GameTime.getInstance().perObjectMultiplier = 1.0F;
   }

   public void postupdate(int frameCounter) {
      GameTime.getInstance().perObjectMultiplier = this.frameMod;
      List<IsoMovingObject> fullSimulation = this.buckets[frameCounter % this.frameMod];

      for (int i = 0; i < fullSimulation.size(); ++i) {
         IsoMovingObject isoMovingObject = fullSimulation.get(i);
         IsoZombie zombie = Type.tryCastTo(isoMovingObject, IsoZombie.class);
         if (zombie != null && VirtualZombieManager.instance.isReused(zombie)) {
            DebugLog.log(
               DebugType.Zombie,
               "REUSABLE ZOMBIE IN MovingObjectUpdateSchedulerUpdateBucket IGNORED " + String.valueOf(isoMovingObject)
            );
            continue;
         }

         isoMovingObject.postupdate();
      }

      GameTime.getInstance().perObjectMultiplier = 1.0F;
   }

   public void removeObject(IsoMovingObject object) {
      for (int i = 0; i < this.buckets.length; ++i) {
         List<IsoMovingObject> bucket = this.buckets[i];
         bucket.remove(object);
      }
   }

   public List<IsoMovingObject> getBucket(int frameCounter) {
      return this.buckets[frameCounter % this.frameMod];
   }
}

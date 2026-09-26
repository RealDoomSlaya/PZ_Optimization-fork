package zombie.audio.parameters;

import zombie.audio.FMODLocalParameter;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.scripting.objects.Item;
import zombie.scripting.objects.ItemBodyLocation;

public final class ParameterShoeType extends FMODLocalParameter {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.audio.parameters.ParameterShoeType");
   }

   // pzopt: entityUpdateParallel. One ItemVisuals shared by every character's ShoeType parameter is a data race as
   // soon as two frame workers run two entities' update(): getShoeType calls character.getItemVisuals(buffer),
   // which clears the buffer and refills it from that character's worn items, and then walks it by index. A live
   // 4,220-zombie batch died here at frame 53 — NullPointerException on itemVisual.getScriptItem() — because one
   // worker's refill left the list shorter than the one another worker was part way through. The buffer is filled
   // and read inside this one call and carries nothing between calls, so one per thread is what the game thread
   // already had (a single buffer, reused) and needs no key to switch it off.
   private static final ThreadLocal<ItemVisuals> pzoptTempItemVisuals = ThreadLocal.withInitial(ItemVisuals::new);
   private final IsoGameCharacter character;
   private ParameterShoeType.ShoeType shoeType;

   public ParameterShoeType(IsoGameCharacter character) {
      super("ShoeType");
      this.character = character;
   }

   @Override
   public float calculateCurrentValue() {
      if (this.shoeType == null) {
         this.shoeType = this.getShoeType();
      }

      return (float)this.shoeType.label;
   }

   private ParameterShoeType.ShoeType getShoeType() {
      ItemVisuals tempItemVisuals = pzoptTempItemVisuals.get(); // pzopt: entityUpdateParallel
      this.character.getItemVisuals(tempItemVisuals);
      Item shoes = null;

      for (int i = 0; i < tempItemVisuals.size(); i++) {
         ItemVisual itemVisual = (ItemVisual)tempItemVisuals.get(i);
         Item scriptItem = itemVisual.getScriptItem();
         if (scriptItem != null && scriptItem.isBodyLocation(ItemBodyLocation.SHOES)) {
            shoes = scriptItem;
            break;
         }
      }

      if (shoes == null) {
         return ParameterShoeType.ShoeType.Barefoot;
      } else {
         String type = shoes.getName();
         if (!type.contains("Boots") && !type.contains("Wellies")) {
            if (type.contains("FlipFlop")) {
               return ParameterShoeType.ShoeType.FlipFlops;
            } else if (type.contains("Slippers")) {
               return ParameterShoeType.ShoeType.Slippers;
            } else {
               return type.contains("Trainer") ? ParameterShoeType.ShoeType.Sneakers : ParameterShoeType.ShoeType.Shoes;
            }
         } else {
            return ParameterShoeType.ShoeType.Boots;
         }
      }
   }

   public void setShoeType(ParameterShoeType.ShoeType shoeType) {
      this.shoeType = shoeType;
   }

   private static enum ShoeType {
      Barefoot(0),
      Boots(1),
      FlipFlops(2),
      Shoes(3),
      Slippers(4),
      Sneakers(5);

      final int label;

      private ShoeType(final int label) {
         this.label = label;
      }
   }
}

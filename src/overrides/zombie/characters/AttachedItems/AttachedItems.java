package zombie.characters.AttachedItems;

import java.util.ArrayList;
import java.util.function.Consumer;
import zombie.UsedFromLua;
import zombie.inventory.InventoryItem;

@UsedFromLua
public final class AttachedItems {
   // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
   static {
      pzopt.Overrides.onClassLoaded("zombie.characters.AttachedItems.AttachedItems");
   }

   protected final AttachedLocationGroup group;
   protected final ArrayList<AttachedItem> items = new ArrayList<AttachedItem>();

   public AttachedItems(AttachedLocationGroup group) {
      this.group = group;
   }

   public AttachedItems(AttachedItems other) {
      this.group = other.group;
      this.copyFrom(other);
   }

   // pzopt: entityUpdateParallel. Every public method below is synchronized: the backing ArrayList is read by
   // a frame worker updating one character while another thread mutates it (setItem/remove/copyFrom/clear), and
   // every read re-checks size() against a list that may be shrinking — ArrayList's IndexOutOfBoundsException is
   // the visible symptom. Ported from PZMulticore's AttachedItemsPatcher: ACC_SYNCHRONIZED on the public methods,
   // constructors and the private indexOf helpers skipped (the privates only run from the synchronized publics,
   // so they already hold the lock). One lock per character's instance: contention only if two threads touch the
   // SAME character, which the bucket scheduler prevents — the game-thread cost is a biased/thin lock.

   public synchronized void copyFrom(AttachedItems other) { // pzopt: entityUpdateParallel
      if (this.group != other.group) {
         throw new RuntimeException("group=" + this.group.id + " other.group=" + other.group.id);
      }

      this.items.clear();
      this.items.addAll(other.items);
   }

   public synchronized AttachedLocationGroup getGroup() { // pzopt: entityUpdateParallel
      return this.group;
   }

   public synchronized AttachedItem get(int index) { // pzopt: entityUpdateParallel
      return this.items.get(index);
   }

   public synchronized void setItem(String location, InventoryItem item) { // pzopt: entityUpdateParallel
      this.group.checkValid(location);
      int index = this.indexOf(location);
      if (index != -1) {
         this.items.remove(index);
      }

      if (item == null) {
         return;
      }

      this.remove(item);
      int insertAt = this.items.size();

      for (int i = 0; i < this.items.size(); ++i) {
         AttachedItem wornItem1 = this.items.get(i);
         if (this.group.indexOf(wornItem1.getLocation()) > this.group.indexOf(location)) {
            insertAt = i;
            break;
         }
      }

      AttachedItem wornItem = new AttachedItem(location, item);
      this.items.add(insertAt, wornItem);
   }

   public synchronized InventoryItem getItem(String location) { // pzopt: entityUpdateParallel
      this.group.checkValid(location);
      int index = this.indexOf(location);
      if (index == -1) {
         return null;
      } else {
         return this.items.get(index).item;
      }
   }

   public synchronized InventoryItem getItemByIndex(int index) { // pzopt: entityUpdateParallel
      if (index < 0 || index >= this.items.size()) {
         return null;
      } else {
         return this.items.get(index).getItem();
      }
   }

   public synchronized void remove(InventoryItem item) { // pzopt: entityUpdateParallel
      int index = this.indexOf(item);
      if (index == -1) {
         return;
      }

      this.items.remove(index);
   }

   public synchronized void clear() { // pzopt: entityUpdateParallel
      this.items.clear();
   }

   public synchronized String getLocation(InventoryItem item) { // pzopt: entityUpdateParallel
      int index = this.indexOf(item);
      if (index == -1) {
         return null;
      } else {
         return this.items.get(index).getLocation();
      }
   }

   public synchronized boolean contains(InventoryItem item) { // pzopt: entityUpdateParallel
      return this.indexOf(item) != -1;
   }

   public synchronized int size() { // pzopt: entityUpdateParallel
      return this.items.size();
   }

   public synchronized boolean isEmpty() { // pzopt: entityUpdateParallel
      return this.items.isEmpty();
   }

   public synchronized void forEach(Consumer<AttachedItem> c) { // pzopt: entityUpdateParallel
      for (int i = 0; i < this.items.size(); ++i) {
         c.accept(this.items.get(i));
      }
   }

   private int indexOf(String location) {
      for (int i = 0; i < this.items.size(); ++i) {
         AttachedItem item = this.items.get(i);
         if (item.location.equals(location)) {
            return i;
         }
      }

      return -1;
   }

   private int indexOf(InventoryItem item) {
      for (int i = 0; i < this.items.size(); ++i) {
         AttachedItem wornItem = this.items.get(i);
         if (wornItem.getItem() == item) {
            return i;
         }
      }

      return -1;
   }
}

package pzopt;

/**
 * The exact bookkeeping replay behind IsoAnimal.updateLOS's far-zombie skip ({@code animalLosFast}).
 *
 * <p>Vanilla updateLOS calls {@code BaseAnimalBehavior.spotted(zombie, false, dist)} for every zombie in
 * the cell, every animal, every frame. For a zombie farther than 10 tiles (with a current square) the
 * call's whole observable effect is {@code parent.spottedChr = null} plus one {@code lastAlerted}
 * decrement-with-clamp — no stress, no flee, no alert, no {@code Rand} draw. The override skips those
 * calls (squared distance &gt; 101, a margin over 10^2 so sqrt rounding can never disagree with vanilla's
 * {@code dist <= 10}) and replays the decrements here, in the same order vanilla would have applied them.
 *
 * <p>{@link #decay} is a loop, not {@code n * mult}: float subtraction is not associative, and the clamp
 * can hit zero mid-run. One call is bit-exact against N sequential vanilla bookkeeping steps
 * (AnimalLosTest pins it).
 */
public final class AnimalLos {

   /** Skipped and executed spotted() calls this session, for the run log. */
   public static long skipped, executed;

   private AnimalLos() {
   }

   /** N vanilla bookkeeping steps of BaseAnimalBehavior.spotted, folded: subtract-then-clamp, N times. */
   public static float decay(float lastAlerted, float multiplier, int n) {
      for (int i = 0; i < n; i++) {
         if (lastAlerted > 0.0f) {
            lastAlerted -= multiplier;
         }
         if (lastAlerted < 0.0f) {
            lastAlerted = 0.0f;
         }
      }
      return lastAlerted;
   }
}

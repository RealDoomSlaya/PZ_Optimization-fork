/*
 * Decompiled with CFR 0.152.
 */
package zombie.util;

import zombie.GameTime;

public class FrameDelay {
    // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
    static {
        pzopt.Overrides.onClassLoaded("zombie.util.FrameDelay");
    }

    public int delay = 1;
    private int count;
    private float delta;
    private float multiplier;

    public FrameDelay() {
    }

    public FrameDelay(int delay) {
        this.delay = delay;
    }

    public boolean update() {
        if (this.count == 0) {
            this.delta = 0.0f;
            this.multiplier = 0.0f;
        }
        this.delta += GameTime.instance.getTimeDelta();
        this.multiplier += GameTime.instance.getMultiplier();
        this.count += Math.round(pzopt.UpdateBatch.pom(GameTime.instance)); // pzopt: entityUpdateParallel -- a batch task reads its bucket's dispatch-time multiplier through pom() (the game thread may have moved on to the next bucket's global write); the live field everywhere else
        if (this.count > this.delay) {
            this.count = 0;
            return true;
        }
        return false;
    }

    public float getDelta() {
        return this.delta;
    }

    public float getMultiplier() {
        return this.multiplier;
    }

    public void reset() {
        this.count = 0;
        this.delta = 0.0f;
        this.multiplier = 0.0f;
    }
}


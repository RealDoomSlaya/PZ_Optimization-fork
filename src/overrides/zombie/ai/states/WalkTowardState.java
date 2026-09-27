/*
 * Decompiled with CFR 0.152.
 */
package zombie.ai.states;

import org.joml.Vector3f;
import zombie.UsedFromLua;
import zombie.ai.State;
import zombie.ai.states.ZombieIdleState;
import zombie.audio.parameters.ParameterZombieState;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.advancedanimation.AnimEvent;
import zombie.core.skinnedmodel.advancedanimation.AnimLayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.iso.IsoChunk;
import zombie.iso.IsoDirections;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.pathfind.PolygonalMap2;
import zombie.util.Type;

@UsedFromLua
public final class WalkTowardState
extends State {
    // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
    static {
        pzopt.Overrides.onClassLoaded("zombie.ai.states.WalkTowardState");
    }

    private static final WalkTowardState INSTANCE = new WalkTowardState();
    public static final State.Param<Boolean> IGNORE_OFFSET = State.Param.of("ignore_offset", Boolean.class);
    public static final State.Param<Long> IGNORE_TIME = State.Param.ofLong("ignore_time", 0L);
    public static final State.Param<Long> TICK_COUNT = State.Param.ofLong("tick_count", 0L);
    private final Vector2 temp = new Vector2();
    private final Vector3f worldPos = new Vector3f();

    public static WalkTowardState instance() {
        return INSTANCE;
    }

    private WalkTowardState() {
        super(false, false, false, false);
    }

    @Override
    public void enter(IsoGameCharacter owner) {
        if (owner.get(IGNORE_OFFSET) == null) {
            owner.set(IGNORE_OFFSET, false);
            owner.set(IGNORE_TIME, 0L);
        }
        if (owner.get(IGNORE_OFFSET) == Boolean.TRUE && System.currentTimeMillis() - owner.get(IGNORE_TIME) > 3000L) {
            owner.set(IGNORE_OFFSET, false);
            owner.set(IGNORE_TIME, 0L);
        }
        owner.set(TICK_COUNT, 0L);
        if (((IsoZombie)owner).isUseless()) {
            owner.changeState(ZombieIdleState.instance());
        }
        owner.getPathFindBehavior2().walkingOnTheSpot.reset(owner.getX(), owner.getY());
        ((IsoZombie)owner).getNetworkCharacterAI().extraUpdate();
    }

    @Override
    public void execute(IsoGameCharacter owner) {
        Vector2 temp = pzopt.UpdateBatch.walkScratch(); // pzopt: entityUpdateParallel -- this State is a singleton, so its temp/worldPos scratch fields are shared by every walking zombie on every thread (run lou-pipe-final: one zero-length throw here with the static scratches already converted); per-thread scratch, identical output single-threaded
        Vector3f worldPos = pzopt.UpdateBatch.walkScratch3(); // pzopt: entityUpdateParallel -- this State is a singleton, so its temp/worldPos scratch fields are shared by every walking zombie on every thread (run lou-pipe-final: one zero-length throw here with the static scratches already converted); per-thread scratch, identical output single-threaded
        long tickCount;
        boolean bCollidedWithObject;
        IsoZombie zomb = (IsoZombie)owner;
        if (!zomb.crawling) {
            owner.setOnFloor(false);
        }
        IsoGameCharacter targetChr = Type.tryCastTo(zomb.target, IsoGameCharacter.class);
        if (zomb.target != null) {
            if (zomb.isTargetLocationKnown()) {
                if (targetChr != null) {
                    zomb.getPathFindBehavior2().pathToCharacter(targetChr);
                    if (targetChr.getVehicle() != null && zomb.DistToSquared(zomb.target) < 16.0f) {
                        Vector3f v = targetChr.getVehicle().chooseBestAttackPosition(targetChr, zomb, worldPos);
                        if (v == null) {
                            zomb.setVariable("bMoving", false);
                            return;
                        }
                        if (Math.abs(owner.getX() - zomb.getPathFindBehavior2().getTargetX()) > 0.1f || Math.abs(owner.getY() - zomb.getPathFindBehavior2().getTargetY()) > 0.1f) {
                            zomb.setVariable("bPathfind", true);
                            zomb.setVariable("bMoving", false);
                            return;
                        }
                    }
                }
            } else if (zomb.lastTargetSeenX != -1 && !owner.getPathFindBehavior2().isTargetLocation((float)zomb.lastTargetSeenX + 0.5f, (float)zomb.lastTargetSeenY + 0.5f, zomb.lastTargetSeenZ)) {
                owner.pathToLocation(zomb.lastTargetSeenX, zomb.lastTargetSeenY, zomb.lastTargetSeenZ);
            }
        }
        if (owner.getPathTargetX() == PZMath.fastfloor(owner.getX()) && owner.getPathTargetY() == PZMath.fastfloor(owner.getY())) {
            if (zomb.target == null) {
                zomb.setVariable("bPathfind", false);
                zomb.setVariable("bMoving", false);
                return;
            }
            if (PZMath.fastfloor(zomb.target.getZ()) != PZMath.fastfloor(owner.getZ())) {
                zomb.setVariable("bPathfind", true);
                zomb.setVariable("bMoving", false);
                return;
            }
        }
        boolean bCollidedWithVehicle = owner.isCollidedWithVehicle();
        if (targetChr != null && targetChr.getVehicle() != null && targetChr.getVehicle().isCharacterAdjacentTo(owner)) {
            bCollidedWithVehicle = false;
        }
        if ((bCollidedWithObject = owner.isCollidedThisFrame()) && owner.get(IGNORE_OFFSET) == Boolean.FALSE) {
            owner.set(IGNORE_OFFSET, true);
            owner.set(IGNORE_TIME, System.currentTimeMillis());
            float x = zomb.getPathFindBehavior2().getTargetX();
            float y = zomb.getPathFindBehavior2().getTargetY();
            float z = zomb.getZ();
            boolean bl = bCollidedWithObject = !this.isPathClear(owner, x, y, z);
        }
        if (bCollidedWithObject || bCollidedWithVehicle) {
            zomb.allowRepathDelay = 0.0f;
            zomb.pathToLocation(owner.getPathTargetX(), owner.getPathTargetY(), owner.getPathTargetZ());
            if (!zomb.getVariableBoolean("bPathfind")) {
                zomb.setVariable("bPathfind", true);
                zomb.setVariable("bMoving", false);
            }
            return;
        }
        float targetX = zomb.getPathFindBehavior2().getTargetX();
        float targetY = zomb.getPathFindBehavior2().getTargetY();
        temp.x = targetX;
        temp.y = targetY;
        temp.x -= zomb.getX();
        temp.y -= zomb.getY();
        float dist = temp.getLength();
        if (dist < 0.25f) {
            owner.setX(targetX);
            owner.setY(targetY);
            owner.setNextX(owner.getX());
            owner.setNextY(owner.getY());
            dist = 0.0f;
        }
        if (dist < 0.025f) {
            zomb.setVariable("bPathfind", false);
            zomb.setVariable("bMoving", false);
            return;
        }
        if (!GameServer.server && !zomb.crawling && owner.get(IGNORE_OFFSET) == Boolean.FALSE) {
            float distScale = Math.min(dist / 2.0f, 4.0f);
            float x = (float)((zomb.getID() + zomb.zombieId) % 20) / 10.0f - 1.0f;
            float y = (float)((zomb.getID() + zomb.zombieId) % 20) / 10.0f - 1.0f;
            if (IsoUtils.DistanceTo(owner.getX(), owner.getY(), targetX + x * distScale, targetY + y * distScale) < dist) {
                temp.x = targetX + x * distScale - zomb.getX();
                temp.y = targetY + y * distScale - zomb.getY();
            }
        }
        zomb.running = false;
        temp.normalize();
        if (zomb.crawling) {
            if (zomb.getVariableString("TurnDirection").isEmpty()) {
                zomb.setForwardDirection(temp);
            }
        } else {
            zomb.setDir(IsoDirections.fromAngle(temp));
            zomb.setForwardDirection(temp);
        }
        if (!owner.isTurning() && owner.getPathFindBehavior2().walkingOnTheSpot.check(owner.getX(), owner.getY())) {
            owner.setVariable("bMoving", false);
        }
        if ((tickCount = owner.get(TICK_COUNT).longValue()) == 2L) {
            zomb.parameterZombieState.setState(ParameterZombieState.State.Idle);
        }
        owner.set(TICK_COUNT, tickCount + 1L);
    }

    @Override
    public void exit(IsoGameCharacter owner) {
        owner.setVariable("bMoving", false);
        ((IsoZombie)owner).getNetworkCharacterAI().extraUpdate();
    }

    @Override
    public void animEvent(IsoGameCharacter owner, AnimLayer layer, AnimationTrack track, AnimEvent event) {
    }

    @Override
    public boolean isMoving(IsoGameCharacter owner) {
        return true;
    }

    private boolean isPathClear(IsoGameCharacter owner, float x, float y, float z) {
        IsoChunk chunk;
        int chunkX = PZMath.fastfloor(x) / 8;
        int chunkY = PZMath.fastfloor(y) / 8;
        IsoChunk isoChunk = chunk = GameServer.server ? ServerMap.instance.getChunk(chunkX, chunkY) : IsoWorld.instance.currentCell.getChunkForGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), PZMath.fastfloor(z));
        if (chunk != null) {
            int flags = 1;
            return !PolygonalMap2.instance.lineClearCollide(owner.getX(), owner.getY(), x, y, PZMath.fastfloor(z), owner.getPathFindBehavior2().getTargetChar(), flags |= 2);
        }
        return false;
    }

    public boolean calculateTargetLocation(IsoZombie zomb, Vector2 location) {
        Vector2 temp = pzopt.UpdateBatch.walkScratch(); // pzopt: entityUpdateParallel -- this State is a singleton, so its temp/worldPos scratch fields are shared by every walking zombie on every thread (run lou-pipe-final: one zero-length throw here with the static scratches already converted); per-thread scratch, identical output single-threaded
        assert (zomb.isCurrentState(this));
        float targetX = zomb.getPathFindBehavior2().getTargetX();
        float targetY = zomb.getPathFindBehavior2().getTargetY();
        location.x = targetX;
        location.y = targetY;
        temp.set(location);
        temp.x -= zomb.getX();
        temp.y -= zomb.getY();
        float dist = temp.getLength();
        if (dist < 0.025f) {
            return false;
        }
        if (!GameServer.server && !zomb.crawling && zomb.get(IGNORE_OFFSET) == Boolean.FALSE) {
            float distScale = Math.min(dist / 2.0f, 4.0f);
            float x = (float)((zomb.getID() + zomb.zombieId) % 20) / 10.0f - 1.0f;
            float y = (float)((zomb.getID() + zomb.zombieId) % 20) / 10.0f - 1.0f;
            if (IsoUtils.DistanceTo(zomb.getX(), zomb.getY(), targetX + x * distScale, targetY + y * distScale) < dist) {
                location.x = targetX + x * distScale;
                location.y = targetY + y * distScale;
                return true;
            }
        }
        return false;
    }
}


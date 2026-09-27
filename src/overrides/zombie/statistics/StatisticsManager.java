/*
 * Decompiled with CFR 0.152.
 */
package zombie.statistics;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import zombie.AchievementManager;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.core.logger.ExceptionLogger;
import zombie.statistics.Statistic;
import zombie.statistics.StatisticCategory;
import zombie.statistics.StatisticType;

public final class StatisticsManager {
    // pzopt: marker so the game log shows the loose class was loaded, not the jar's copy
    static {
        pzopt.Overrides.onClassLoaded("zombie.statistics.StatisticsManager");
    }

    private final HashMap<String, Statistic> statisticHashMap = new HashMap();

    public static StatisticsManager getInstance() {
        return Holder.instance;
    }

    private StatisticsManager() {
    }

    // pzopt: MARK every map-touching method synchronized (entityUpdateParallel): the batched zombies'
    // updateMovementStatistics runs incrementStatistic on the workers, and the singleton's plain HashMap
    // raced worker vs worker (computeIfAbsent CME at frame 1 of the first live modded session, which
    // latched batching off). The manager's own monitor, uncontended on the serial path; the Statistic
    // read-modify-write increments ride the same lock.
    public synchronized void incrementStatistic(StatisticType statisticType, StatisticCategory statisticCategory, String key, float amount) { // pzopt: MARK
        this.statisticHashMap.computeIfAbsent(key, k -> new Statistic(key, statisticType, statisticCategory));
        Statistic statistic = this.statisticHashMap.get(key);
        statistic.incrementStatistic(amount);
        AchievementManager.getInstance().checkAchievementsOnStatisticChange(statistic);
    }

    public synchronized void setStatistic(StatisticType statisticType, StatisticCategory statisticCategory, String key, float amount) { // pzopt: MARK
        this.statisticHashMap.computeIfAbsent(key, k -> new Statistic(key, statisticType, statisticCategory));
        Statistic statistic = this.statisticHashMap.get(key);
        statistic.setValue(amount);
        AchievementManager.getInstance().checkAchievementsOnStatisticChange(statistic);
    }

    public synchronized float getStatistic(String key) { // pzopt: MARK
        return this.statisticHashMap.get(key).getValue();
    }

    public synchronized HashMap<String, Statistic> getStatistics() { // pzopt: MARK (the map itself escapes; callers iterate on the game thread)
        return this.statisticHashMap;
    }

    public synchronized String getAllStatisticsDebug() { // pzopt: MARK
        StringBuilder debug = new StringBuilder();
        for (Map.Entry<String, Statistic> entry : this.statisticHashMap.entrySet()) {
            debug.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue().getValue());
        }
        return debug.toString();
    }

    public synchronized void load() { // pzopt: MARK
        this.statisticHashMap.clear();
        AchievementManager.getInstance().reset();
        if (Core.getInstance().isNoSave()) {
            return;
        }
        File file = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("statistics.bin"));
        if (!file.exists()) {
            return;
        }
        try (FileInputStream fileInputStream = new FileInputStream(file);
             DataInputStream dataInputStream = new DataInputStream(fileInputStream);){
            int size = dataInputStream.readInt();
            for (int i = 0; i < size; ++i) {
                String name = dataInputStream.readUTF();
                StatisticType type = StatisticType.valueOf(dataInputStream.readUTF());
                StatisticCategory category = StatisticCategory.valueOf(dataInputStream.readUTF());
                float value = dataInputStream.readFloat();
                Statistic statistic = new Statistic(name, type, category);
                statistic.setValue(value);
                this.statisticHashMap.put(name, statistic);
                AchievementManager.getInstance().checkAchievementsOnStatisticLoad(statistic);
            }
        }
        catch (Exception ex) {
            ExceptionLogger.logException(ex);
        }
    }

    public synchronized void save() { // pzopt: MARK
        if (Core.getInstance().isNoSave()) {
            return;
        }
        File file = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("statistics.bin"));
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);){
            dataOutputStream.writeInt(this.statisticHashMap.size());
            for (Statistic statistic : this.statisticHashMap.values()) {
                dataOutputStream.writeUTF(statistic.getName());
                dataOutputStream.writeUTF(statistic.getStatisticType().name());
                dataOutputStream.writeUTF(statistic.getStatisticCategory().name());
                dataOutputStream.writeFloat(statistic.getValue());
            }
            try (FileOutputStream outputStream2 = new FileOutputStream(file);){
                outputStream2.write(byteArrayOutputStream.toByteArray());
            }
        }
        catch (Exception ex) {
            ExceptionLogger.logException(ex);
        }
    }

    private static class Holder {
        private static final StatisticsManager instance = new StatisticsManager();

        private Holder() {
        }
    }
}

import dev.robocode.tankroyale.botapi.Bot;
import dev.robocode.tankroyale.botapi.BotInfo;
import dev.robocode.tankroyale.botapi.events.BotDeathEvent;
import dev.robocode.tankroyale.botapi.events.HitBotEvent;
import dev.robocode.tankroyale.botapi.events.HitByBulletEvent;
import dev.robocode.tankroyale.botapi.events.HitWallEvent;
import dev.robocode.tankroyale.botapi.events.ScannedBotEvent;
import dev.robocode.tankroyale.botapi.graphics.Color;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

public class SpinRAM extends Bot {

    // =========================
    // Logging
    // =========================
    private enum LogMode {
        OFF,
        BASIC,
        VERBOSE
    }

    // OFF for submission
    private static final LogMode LOG_MODE = LogMode.BASIC;
    private static final int BASIC_LOG_EVERY_N_TURNS = 10;

    // =========================
    // Modes
    // =========================
    private enum Mode {
        SEARCH,
        ATTACK,
        ESCAPE_WALL
    }

    private static class Enemy {
        final int id;
        double x;
        double y;
        double energy;
        double direction;
        double speed;
        int lastSeenTurn;
        int possibleFireTurn = -9999;

        Enemy(int id) {
            this.id = id;
        }
    }

    // =========================
    // Tuning
    // =========================
    private static final double TOO_CLOSE_TO_WALL = 22.0;

    private static final double EDGE_LANE_MARGIN = 80.0;
    private static final double CORNER_AVOID = 120.0;
    private static final double EDGE_HOLD_STEP = 120.0;

    private static final double SEARCH_MOVE = 70.0;
    private static final double ATTACK_MOVE = 100.0;
    private static final double WALL_ESCAPE_MOVE = 120.0;

    private static final int TARGET_STALE_TURNS = 12;
    private static final int DROP_TARGET_TURNS = 30;
    private static final int SEARCH_SHOT_MAX_AGE = 2;
    private static final int BURST_TURNS = 6;

    // Duel-only RAM
    private static final double RAM_DISTANCE = 155.0;
    private static final double RAM_ENEMY_ENERGY = 10.0;
    private static final double RAM_ENERGY_ADVANTAGE = 12.0;

    private final Map<Integer, Enemy> enemies = new HashMap<>();

    private Mode mode = Mode.SEARCH;
    private Enemy target;

    private double moveDirection = 1.0;
    private int radarDirection = 1;
    private boolean reverseNextTurn = false;
    private int burstUntilTurn = -1;

    public static void main(String[] args) {
        new SpinRAM().start();
    }

    public SpinRAM() {
        super(BotInfo.fromFile("SpinRAM.json"));
    }

    @Override
    public void run() {
        initializeRound();

        while (isRunning()) {
            cleanupOldEnemies();

            if (target == null || isTargetStale(target)) {
                pickTarget();
            }

            if (reverseNextTurn) {
                moveDirection = -moveDirection;
                reverseNextTurn = false;
            }

            resolveMode();
            handleRadar();
            handleGun();
            handleMovement();

            go();
            debugTurn();
        }
    }

    private void initializeRound() {
        enemies.clear();
        target = null;
        mode = Mode.SEARCH;
        moveDirection = 1.0;
        radarDirection = 1;
        reverseNextTurn = false;
        burstUntilTurn = -1;

        setBodyColor(Color.fromRgb(0xF2, 0xF2, 0xF2));
        setTurretColor(Color.fromRgb(0xCC, 0xCC, 0xCC));
        setGunColor(Color.fromRgb(0xCC, 0xCC, 0xCC));
        setRadarColor(Color.fromRgb(0xFF, 0x99, 0x00));
        setScanColor(Color.fromRgb(0xFF, 0xCC, 0x33));
        setBulletColor(Color.fromRgb(0xFF, 0x66, 0x00));
        setTracksColor(Color.fromRgb(0x66, 0x66, 0x66));

        // Keep gun/radar independent of body movement
        setAdjustGunForBodyTurn(true);
        setAdjustRadarForBodyTurn(true);
        setAdjustRadarForGunTurn(true);

        setMaxSpeed(8);
    }

    @Override
    public void onScannedBot(ScannedBotEvent e) {
        Enemy enemy = enemies.computeIfAbsent(e.getScannedBotId(), Enemy::new);

        double previousEnergy = enemy.energy;
        if (enemy.lastSeenTurn > 0) {
            double energyDrop = previousEnergy - e.getEnergy();
            if (energyDrop >= 0.1 && energyDrop <= 3.0) {
                enemy.possibleFireTurn = e.getTurnNumber();
            }
        }

        enemy.x = e.getX();
        enemy.y = e.getY();
        enemy.energy = e.getEnergy();
        enemy.direction = e.getDirection();
        enemy.speed = e.getSpeed();
        enemy.lastSeenTurn = e.getTurnNumber();

        // Refresh current target immediately
        if (target != null && target.id == enemy.id) {
            target = enemy;
            return;
        }

        // If we have no target or stale target, grab this one
        if (target == null || isTargetStale(target)) {
            target = enemy;
            return;
        }

        // In duel just use the scanned enemy
        if (getEnemyCount() <= 1) {
            target = enemy;
            return;
        }

        // In multiplayer only switch if clearly better
        double currentDistance = distanceTo(target.x, target.y);
        double newDistance = distanceTo(enemy.x, enemy.y);

        if (newDistance < currentDistance * 0.85 || enemy.energy < target.energy - 15.0) {
            target = enemy;
        }
    }

    @Override
    public void onBotDeath(BotDeathEvent e) {
        enemies.remove(e.getVictimId());
        if (target != null && target.id == e.getVictimId()) {
            target = null;
        }
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        reverseNextTurn = true;
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        reverseNextTurn = true;
    }

    @Override
    public void onHitBot(HitBotEvent e) {
        reverseNextTurn = true;
    }

    private void cleanupOldEnemies() {
        int now = getTurnNumber();
        Iterator<Map.Entry<Integer, Enemy>> it = enemies.entrySet().iterator();

        while (it.hasNext()) {
            Enemy enemy = it.next().getValue();
            if (now - enemy.lastSeenTurn > DROP_TARGET_TURNS) {
                if (target != null && target.id == enemy.id) {
                    target = null;
                }
                it.remove();
            }
        }
    }

    private void pickTarget() {
        Enemy best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int now = getTurnNumber();

        for (Enemy enemy : enemies.values()) {
            int age = now - enemy.lastSeenTurn;
            if (age > TARGET_STALE_TURNS) {
                continue;
            }

            double distance = distanceTo(enemy.x, enemy.y);
            double score = distance + age * 20.0 + enemy.energy * 3.0;

            if (enemy.possibleFireTurn >= now - 1) {
                score -= 20.0;
            }

            if (target != null && target.id == enemy.id) {
                score -= 15.0;
            }

            if (score < bestScore) {
                bestScore = score;
                best = enemy;
            }
        }

        target = best;
    }

    private void resolveMode() {
        if (tooCloseToWall()) {
            mode = Mode.ESCAPE_WALL;
            return;
        }

        if (target == null || isTargetStale(target)) {
            mode = Mode.SEARCH;
            return;
        }

        mode = Mode.ATTACK;

        boolean duel = getEnemyCount() <= 1;
        double distance = distanceTo(target.x, target.y);

        if (duel && target.energy < 20.0 && distance < 250.0) {
            burstUntilTurn = Math.max(burstUntilTurn, getTurnNumber() + BURST_TURNS);
        }
    }

    private void handleRadar() {
        if (target == null) {
            setTurnRadarRight(45.0 * radarDirection);
            return;
        }

        int age = getTurnNumber() - target.lastSeenTurn;

        // If target info is getting stale, do a strong sweep
        if (age > 2) {
            setTurnRadarRight(45.0 * radarDirection);
            return;
        }

        double bearing = radarBearingTo(target.x, target.y);
        double overshoot = (mode == Mode.SEARCH)
                ? 35.0
                : (getEnemyCount() <= 1 ? 18.0 : 28.0);

        double turn = bearing + (bearing >= 0 ? overshoot : -overshoot);

        radarDirection = turn >= 0 ? 1 : -1;
        setTurnRadarRight(turn);
    }

    private void handleGun() {
        if (target == null) {
            return;
        }

        int age = getTurnNumber() - target.lastSeenTurn;

        // In SEARCH, only speculative shots at very recent last-known position
        if (mode == Mode.SEARCH && age > SEARCH_SHOT_MAX_AGE) {
            return;
        }

        double distance = distanceTo(target.x, target.y);
        double gunTurn = gunBearingTo(target.x, target.y);

        setTurnGunRight(gunTurn);

        double firepower = chooseFirepower(distance);
        if (mode == Mode.SEARCH) {
            firepower = Math.min(firepower, 1.0);
        }

        boolean duel = getEnemyCount() <= 1;
        boolean ramNow = shouldRam(distance);

        double tolerance;
        if (ramNow) {
            tolerance = 18.0;
        } else if (duel) {
            tolerance = 20.0;
        } else {
            tolerance = 16.0;
        }

        if (getGunHeat() <= 0.05
                && Math.abs(gunTurn) <= tolerance
                && getEnergy() > firepower + 0.2) {
            setFire(firepower);
        }
    }

    private double chooseFirepower(double distance) {
        boolean duel = getEnemyCount() <= 1;
        boolean burstMode = duel && getTurnNumber() <= burstUntilTurn;

        double power;
        if (shouldRam(distance)) {
            power = 2.0;
        } else if (mode == Mode.SEARCH) {
            power = 1.0;
        } else if (burstMode) {
            power = 1.0;
        } else if (duel) {
            if (distance < 140.0) {
                power = 2.2;
            } else if (distance < 320.0) {
                power = 1.7;
            } else {
                power = 1.2;
            }
        } else {
            if (distance < 150.0) {
                power = 1.5;
            } else if (distance < 340.0) {
                power = 1.2;
            } else {
                power = 1.0;
            }
        }

        if (target != null) {
            power = Math.min(power, target.energy / 3.0 + 0.6);
        }

        power = Math.min(power, getEnergy() / 6.0 + 0.6);

        if (power < 0.5) power = 0.5;
        if (power > 3.0) power = 3.0;
        return power;
    }

    private boolean shouldRam(double distance) {
        return getEnemyCount() <= 1
                && target != null
                && target.energy < RAM_ENEMY_ENERGY
                && getEnergy() > target.energy + RAM_ENERGY_ADVANTAGE
                && distance < RAM_DISTANCE
                && !tooCloseToWall();
    }

    private void handleMovement() {
        if (mode == Mode.ESCAPE_WALL) {
            escapeWall();
            return;
        }

        boolean duel = getEnemyCount() <= 1;

        if (mode == Mode.SEARCH) {
            if (!duel) {
                moveToEdgeLane();
            } else {
                if (getTurnNumber() % 18 == 0) {
                    moveDirection = -moveDirection;
                }
                double desiredDirection = getDirection() + 40.0 * moveDirection;
                goToDirection(normalizeAbsoluteAngle(desiredDirection), SEARCH_MOVE);
            }
            return;
        }

        double targetDirection = directionTo(target.x, target.y);
        double distance = distanceTo(target.x, target.y);

        if (shouldRam(distance)) {
            goToDirection(targetDirection, Math.min(220.0, distance + 30.0));
            return;
        }

        if (recentlyFiredAtUs(target) || getTurnNumber() % (duel ? 24 : 30) == 0) {
            moveDirection = -moveDirection;
        }

        if (!duel) {
            moveToEdgeLane();
            return;
        }

        double desiredDirection;
        if (distance < 140.0) {
            desiredDirection = targetDirection + 120.0 * moveDirection;
        } else if (distance > 380.0) {
            desiredDirection = targetDirection + 65.0 * moveDirection;
        } else {
            desiredDirection = targetDirection + 90.0 * moveDirection;
        }

        goToDirection(normalizeAbsoluteAngle(desiredDirection), ATTACK_MOVE);
    }

    private void moveToEdgeLane() {
        double[] waypoint = edgeLaneWaypoint();
        double absoluteDirection = directionTo(waypoint[0], waypoint[1]);
        double distance = Math.min(distanceTo(waypoint[0], waypoint[1]), EDGE_HOLD_STEP);
        goToDirection(absoluteDirection, distance);
    }

    private double[] edgeLaneWaypoint() {
        double x = getX();
        double y = getY();
        double w = getArenaWidth();
        double h = getArenaHeight();

        double left = x;
        double right = w - x;
        double bottom = y;
        double top = h - y;

        if (left <= right && left <= bottom && left <= top) {
            return new double[]{
                    EDGE_LANE_MARGIN,
                    clamp(y + moveDirection * EDGE_HOLD_STEP, CORNER_AVOID, h - CORNER_AVOID)
            };
        }

        if (right <= left && right <= bottom && right <= top) {
            return new double[]{
                    w - EDGE_LANE_MARGIN,
                    clamp(y + moveDirection * EDGE_HOLD_STEP, CORNER_AVOID, h - CORNER_AVOID)
            };
        }

        if (bottom <= left && bottom <= right && bottom <= top) {
            return new double[]{
                    clamp(x + moveDirection * EDGE_HOLD_STEP, CORNER_AVOID, w - CORNER_AVOID),
                    EDGE_LANE_MARGIN
            };
        }

        return new double[]{
                clamp(x + moveDirection * EDGE_HOLD_STEP, CORNER_AVOID, w - CORNER_AVOID),
                h - EDGE_LANE_MARGIN
        };
    }

    private void escapeWall() {
        double centerDirection = directionTo(getArenaWidth() / 2.0, getArenaHeight() / 2.0);
        double bearing = calcBearing(centerDirection);
        setTurnRight(bearing);
        setForward(WALL_ESCAPE_MOVE);
    }

    private void goToDirection(double absoluteDirection, double distance) {
        double bearing = calcBearing(absoluteDirection);
        double turn = bearing;

        if (Math.abs(bearing) > 90.0) {
            turn = (bearing > 0.0) ? bearing - 180.0 : bearing + 180.0;
            setTurnRight(turn);
            setBack(distance);
        } else {
            setTurnRight(turn);
            setForward(distance);
        }
    }

    private boolean tooCloseToWall() {
        return getX() < TOO_CLOSE_TO_WALL
                || getY() < TOO_CLOSE_TO_WALL
                || getX() > getArenaWidth() - TOO_CLOSE_TO_WALL
                || getY() > getArenaHeight() - TOO_CLOSE_TO_WALL;
    }

    private boolean isTargetStale(Enemy enemy) {
        return enemy == null || (getTurnNumber() - enemy.lastSeenTurn > TARGET_STALE_TURNS);
    }

    private boolean recentlyFiredAtUs(Enemy enemy) {
        return enemy != null && getTurnNumber() - enemy.possibleFireTurn <= 1;
    }

    private void debugTurn() {
        if (LOG_MODE == LogMode.OFF) {
            return;
        }
        if (LOG_MODE == LogMode.BASIC && getTurnNumber() % BASIC_LOG_EVERY_N_TURNS != 0) {
            return;
        }

        String targetInfo;
        if (target == null) {
            targetInfo = "NONE";
        } else {
            int age = getTurnNumber() - target.lastSeenTurn;
            targetInfo = "#" + target.id
                    + " pos=(" + (int) target.x + "," + (int) target.y + ")"
                    + " e=" + (int) target.energy
                    + " age=" + age;
        }

        System.out.println("[SpinRAM] turn=" + getTurnNumber()
                + " mode=" + mode
                + " pos=(" + (int) getX() + "," + (int) getY() + ")"
                + " energy=" + (int) getEnergy()
                + " gunHeat=" + String.format(Locale.ROOT, "%.2f", getGunHeat())
                + " enemies=" + enemies.size()
                + " target=" + targetInfo
                + " moveDir=" + moveDirection
                + " radarDir=" + radarDirection);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

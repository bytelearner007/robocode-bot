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

    private enum LogMode {
        OFF,
        BASIC,
        VERBOSE
    }

    private static final LogMode LOG_MODE = LogMode.BASIC; // OFF for submission
    private static final int BASIC_LOG_EVERY_N_TURNS = 10;

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

    private static final double EDGE_BUFFER = 35.0;
    private static final double EDGE_HOLD_MARGIN = 85.0;
    private static final double CORNER_AVOID = 130.0;

    private static final double SEARCH_MOVE = 65.0;
    private static final double ATTACK_MOVE = 110.0;
    private static final double WALL_ESCAPE_MOVE = 150.0;
    private static final double EDGE_HOLD_STEP = 145.0;

    private static final int TARGET_STALE_TURNS = 16;
    private static final int DROP_TARGET_TURNS = 40;
    private static final int SEARCH_SHOT_MAX_AGE = 3;
    private static final int BURST_TURNS = 6;

    private static final double RAM_DISTANCE = 165.0;
    private static final double RAM_ENEMY_ENERGY = 12.0;
    private static final double RAM_ENERGY_ADVANTAGE = 10.0;

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

        if (target != null && target.id == enemy.id) {
            target = enemy;
            return;
        }

        if (target == null || isTargetStale(target)) {
            target = enemy;
            return;
        }

        if (getEnemyCount() <= 1) {
            target = enemy;
            return;
        }

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
            double score = distance + age * 25.0 + enemy.energy * 3.0;

            if (enemy.possibleFireTurn >= now - 1) {
                score -= 25.0;
            }

            if (target != null && target.id == enemy.id) {
                score -= 20.0;
            }

            if (getEnemyCount() > 1 && enemy.energy < 20.0) {
                score -= 20.0;
            }

            if (score < bestScore) {
                bestScore = score;
                best = enemy;
            }
        }

        target = best;
    }

    private void resolveMode() {
        if (nearWall()) {
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

        if (age > 2) {
            setTurnRadarRight(45.0 * radarDirection);
            return;
        }

        double bearing = radarBearingTo(target.x, target.y);

        if (mode == Mode.SEARCH) {
            double turn = bearing + (bearing >= 0 ? 35.0 : -35.0);
            if (Math.abs(turn) < 8.0) {
                turn = 45.0 * radarDirection;
            }
            radarDirection = turn >= 0 ? 1 : -1;
            setTurnRadarRight(turn);
            return;
        }

        double overshoot = (getEnemyCount() <= 1) ? 18.0 : 28.0;
        double turn = bearing + (bearing >= 0 ? overshoot : -overshoot);
        radarDirection = turn >= 0 ? 1 : -1;
        setTurnRadarRight(turn);
    }

    private void handleGun() {
        if (target == null) {
            return;
        }

        int age = getTurnNumber() - target.lastSeenTurn;

        if (mode == Mode.SEARCH && age > SEARCH_SHOT_MAX_AGE) {
            return;
        }

        double distance = distanceTo(target.x, target.y);
        double firepower = chooseFirepower(distance);

        if (mode == Mode.SEARCH) {
            firepower = Math.min(firepower, 1.0);
        }

        double[] aim = predictLinearPosition(target, firepower);
        double directTurn = gunBearingTo(target.x, target.y);
        double predictedTurn = gunBearingTo(aim[0], aim[1]);
        double gunTurn = Math.abs(predictedTurn) <= 25.0 ? predictedTurn : directTurn;

        setTurnGunRight(gunTurn);

        boolean duel = getEnemyCount() <= 1;
        boolean ramNow = shouldRam(distance);

        double tolerance;
        if (ramNow) {
            tolerance = 18.0;
        } else if (duel) {
            tolerance = 14.0;
        } else {
            tolerance = 12.0;
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
            power = 2.4;
        } else if (mode == Mode.SEARCH) {
            power = 1.0;
        } else if (burstMode) {
            power = 1.0;
        } else if (duel) {
            if (distance < 140.0) {
                power = 2.4;
            } else if (distance < 320.0) {
                power = 1.8;
            } else {
                power = 1.2;
            }
        } else {
            if (distance < 150.0) {
                power = 1.7;
            } else if (distance < 340.0) {
                power = 1.25;
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
                && !nearWall();
    }

    private double[] predictLinearPosition(Enemy enemy, double firepower) {
        double bulletSpeed = Math.max(0.1, calcBulletSpeed(firepower));

        double vx = Math.cos(Math.toRadians(enemy.direction)) * enemy.speed;
        double vy = Math.sin(Math.toRadians(enemy.direction)) * enemy.speed;

        double px = enemy.x;
        double py = enemy.y;

        for (int i = 0; i < 5; i++) {
            double distance = distanceTo(px, py);
            double time = distance / bulletSpeed;

            px = enemy.x + vx * time;
            py = enemy.y + vy * time;

            px = clamp(px, EDGE_BUFFER, getArenaWidth() - EDGE_BUFFER);
            py = clamp(py, EDGE_BUFFER, getArenaHeight() - EDGE_BUFFER);
        }

        return new double[]{px, py};
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
                double desiredDirection = getDirection() + 35.0 * moveDirection;
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
            desiredDirection = targetDirection + 60.0 * moveDirection;
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
                    EDGE_HOLD_MARGIN,
                    clamp(y + moveDirection * EDGE_HOLD_STEP, CORNER_AVOID, h - CORNER_AVOID)
            };
        }

        if (right <= left && right <= bottom && right <= top) {
            return new double[]{
                    w - EDGE_HOLD_MARGIN,
                    clamp(y + moveDirection * EDGE_HOLD_STEP, CORNER_AVOID, h - CORNER_AVOID)
            };
        }

        if (bottom <= left && bottom <= right && bottom <= top) {
            return new double[]{
                    clamp(x + moveDirection * EDGE_HOLD_STEP, CORNER_AVOID, w - CORNER_AVOID),
                    EDGE_HOLD_MARGIN
            };
        }

        return new double[]{
                clamp(x + moveDirection * EDGE_HOLD_STEP, CORNER_AVOID, w - CORNER_AVOID),
                h - EDGE_HOLD_MARGIN
        };
    }

    private void escapeWall() {
        double centerDirection = directionTo(getArenaWidth() / 2.0, getArenaHeight() / 2.0);
        double bearing = calcBearing(centerDirection);
        setTurnRight(bearing);
        setForward(WALL_ESCAPE_MOVE);
    }

    private boolean nearWall() {
        return getX() < EDGE_BUFFER
                || getY() < EDGE_BUFFER
                || getX() > getArenaWidth() - EDGE_BUFFER
                || getY() > getArenaHeight() - EDGE_BUFFER;
    }

    private boolean isTargetStale(Enemy enemy) {
        return enemy == null || (getTurnNumber() - enemy.lastSeenTurn > TARGET_STALE_TURNS);
    }

    private boolean recentlyFiredAtUs(Enemy enemy) {
        return enemy != null && getTurnNumber() - enemy.possibleFireTurn <= 1;
    }

    private void goToDirection(double absoluteDirection, double distance) {
        double bearing = calcBearing(absoluteDirection);

        if (Math.abs(bearing) > 90.0) {
            if (bearing > 0.0) {
                setTurnRight(bearing - 180.0);
            } else {
                setTurnRight(bearing + 180.0);
            }
            setBack(distance);
        } else {
            setTurnRight(bearing);
            setForward(distance);
        }
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

    private void logVerbose(String msg) {
        if (LOG_MODE == LogMode.VERBOSE) {
            System.out.println(msg);
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

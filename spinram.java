import dev.robocode.tankroyale.botapi.Bot;
import dev.robocode.tankroyale.botapi.BotInfo;
import dev.robocode.tankroyale.botapi.events.BotDeathEvent;
import dev.robocode.tankroyale.botapi.events.HitBotEvent;
import dev.robocode.tankroyale.botapi.events.HitByBulletEvent;
import dev.robocode.tankroyale.botapi.events.HitWallEvent;
import dev.robocode.tankroyale.botapi.events.ScannedBotEvent;
import dev.robocode.tankroyale.botapi.graphics.Color;

public class SpinRAM extends Bot {

    // ===== Easy switches =====
    private static final boolean DEBUG = false; // true for local debugging, false for submission

    // ===== Target handling =====
    private static final int TARGET_FRESH_TURNS = 14;
    private static final int SEARCH_SHOT_MAX_AGE = 3;

    // ===== Movement =====
    private static final double WALL_MARGIN = 70.0;
    private static final double SEARCH_TURN = 35.0;
    private static final double SEARCH_MOVE = 75.0;
    private static final double ATTACK_TURN = 30.0;
    private static final double ATTACK_MOVE = 90.0;
    private static final double CLOSE_BACKOFF = 45.0;
    private static final double WALL_ESCAPE_MOVE = 120.0;

    // ===== Ram behavior =====
    private static final double RAM_DISTANCE = 160.0;
    private static final double RAM_ENEMY_ENERGY = 12.0;
    private static final double RAM_ENERGY_ADVANTAGE = 12.0;
    private static final double RAM_EXTRA = 35.0;
    private static final double MAX_RAM_DRIVE = 220.0;

    // ===== Search-shot =====
    private static final double SEARCH_SHOT_POWER = 1.0;

    private enum Mode {
        SEARCH,
        ATTACK,
        ESCAPE_WALL
    }

    private Mode mode = Mode.SEARCH;

    // Spin direction: clockwise or counterclockwise
    private int spinDirection = 1;

    // Last known target state
    private int targetId = -1;
    private double targetX;
    private double targetY;
    private double targetEnergy = 100.0;
    private int targetLastSeenTurn = -9999;

    public static void main(String[] args) {
        new SpinRAM().start();
    }

    public SpinRAM() {
        super(BotInfo.fromFile("SpinRAM.json"));
    }

    @Override
    public void run() {
        setBodyColor(Color.fromRgb(0xF2, 0xF2, 0xF2));
        setTurretColor(Color.fromRgb(0xCC, 0xCC, 0xCC));
        setGunColor(Color.fromRgb(0xCC, 0xCC, 0xCC));
        setRadarColor(Color.fromRgb(0xFF, 0x99, 0x00));
        setScanColor(Color.fromRgb(0xFF, 0xCC, 0x33));
        setBulletColor(Color.fromRgb(0xFF, 0x66, 0x00));
        setTracksColor(Color.fromRgb(0x66, 0x66, 0x66));

        setMaxSpeed(6);

        debug("SpinRAM initialized");

        while (isRunning()) {
            if (nearWall()) {
                mode = Mode.ESCAPE_WALL;
                debug("Mode=ESCAPE_WALL");
                escapeWall();
                continue;
            }

            if (!hasFreshTarget()) {
                mode = Mode.SEARCH;
                debug("Mode=SEARCH");
                searchSpin();
                continue;
            }

            mode = Mode.ATTACK;
            debug("Mode=ATTACK target=#" + targetId);
            attackOrRam();
        }
    }

    @Override
    public void onScannedBot(ScannedBotEvent e) {
        double newDistance = distanceTo(e.getX(), e.getY());
        double currentDistance = hasAnyTarget() ? distanceTo(targetX, targetY) : Double.POSITIVE_INFINITY;

        boolean shouldReplace =
                !hasAnyTarget()
                || e.getScannedBotId() == targetId
                || !hasFreshTarget()
                || newDistance < currentDistance * 0.85
                || e.getEnergy() < targetEnergy - 15.0;

        if (shouldReplace) {
            targetId = e.getScannedBotId();
            targetX = e.getX();
            targetY = e.getY();
            targetEnergy = e.getEnergy();
            targetLastSeenTurn = e.getTurnNumber();

            debug("Scanned target #" + targetId
                    + " pos=(" + (int) targetX + "," + (int) targetY + ")"
                    + " energy=" + (int) targetEnergy);

            // Opportunistic shot as soon as target is found
            double distance = distanceTo(targetX, targetY);
            turnToFaceTarget(targetX, targetY);

            if (getGunHeat() <= 0.10) {
                if (shouldRam(distance, targetEnergy)) {
                    fire(ramFirePower(targetEnergy));
                } else {
                    fire(chooseFirePower(distance, targetEnergy));
                }
            }
        }
    }

    @Override
    public void onBotDeath(BotDeathEvent e) {
        if (e.getVictimId() == targetId) {
            debug("Target died -> clearing");
            clearTarget();
        }
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        spinDirection = -spinDirection;
        debug("Hit by bullet -> reversing spin");
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        spinDirection = -spinDirection;
        debug("Hit wall -> reversing spin");
    }

    @Override
    public void onHitBot(HitBotEvent e) {
        targetId = e.getVictimId();
        targetX = e.getX();
        targetY = e.getY();
        targetEnergy = e.getEnergy();
        targetLastSeenTurn = getTurnNumber();

        debug("Hit bot #" + targetId);

        turnToFaceTarget(targetX, targetY);

        if (getGunHeat() <= 0.10) {
            if (shouldRam(distanceTo(targetX, targetY), targetEnergy)) {
                fire(ramFirePower(targetEnergy));
                forward(40);
            } else {
                fire(chooseFirePower(distanceTo(targetX, targetY), targetEnergy));
                back(30);
                spinDirection = -spinDirection;
            }
        }
    }

    private void searchSpin() {
        // If target was seen very recently, shoot at last known position while searching
        if (hasAnyTarget()) {
            int age = getTurnNumber() - targetLastSeenTurn;

            if (age <= SEARCH_SHOT_MAX_AGE) {
                debug("SEARCH_SHOT target=#" + targetId + " age=" + age);

                turnToFaceTarget(targetX, targetY);

                if (getGunHeat() <= 0.10) {
                    double distance = distanceTo(targetX, targetY);
                    double power = Math.min(SEARCH_SHOT_POWER, chooseFirePower(distance, targetEnergy));
                    fire(power);
                }

                turnRight(20.0 * spinDirection);
                forward(SEARCH_MOVE / 2.0);
                rescan();
                return;
            }
        }

        turnRight(SEARCH_TURN * spinDirection);
        forward(SEARCH_MOVE);
    }

    private void attackOrRam() {
        double distance = distanceTo(targetX, targetY);

        turnToFaceTarget(targetX, targetY);

        if (shouldRam(distance, targetEnergy)) {
            debug("RAM target=#" + targetId + " distance=" + (int) distance);

            if (getGunHeat() <= 0.10) {
                fire(ramFirePower(targetEnergy));
            }

            forward(Math.min(distance + RAM_EXTRA, MAX_RAM_DRIVE));
            return;
        }

        debug("ATTACK target=#" + targetId + " distance=" + (int) distance);

        if (getGunHeat() <= 0.10) {
            fire(chooseFirePower(distance, targetEnergy));
        }

        if (distance < 120.0) {
            turnRight(55.0 * spinDirection);
            back(CLOSE_BACKOFF);
        } else {
            turnRight(ATTACK_TURN * spinDirection);
            forward(ATTACK_MOVE);
        }

        rescan();
    }

    private boolean shouldRam(double distance, double enemyEnergy) {
        return distance <= RAM_DISTANCE
                && enemyEnergy <= RAM_ENEMY_ENERGY
                && getEnergy() > enemyEnergy + RAM_ENERGY_ADVANTAGE
                && !nearWall();
    }

    private double chooseFirePower(double distance, double enemyEnergy) {
        double power;

        if (distance < 110.0) {
            power = 2.6;
        } else if (distance < 260.0) {
            power = 1.9;
        } else {
            power = 1.2;
        }

        // Don't waste huge bullets on a nearly dead bot
        if (enemyEnergy < 8.0) {
            power = Math.min(power, 1.5);
        }

        // Protect our own energy a bit
        power = Math.min(power, Math.max(0.8, getEnergy() / 8.0));

        if (power < 0.5) power = 0.5;
        if (power > 3.0) power = 3.0;

        return power;
    }

    private double ramFirePower(double enemyEnergy) {
        if (enemyEnergy > 8.0) return 2.0;
        if (enemyEnergy > 4.0) return 1.0;
        if (enemyEnergy > 2.0) return 0.5;
        return 0.1;
    }

    private void escapeWall() {
        debug("Escaping wall");
        turnToFaceTarget(getArenaWidth() / 2.0, getArenaHeight() / 2.0);
        forward(WALL_ESCAPE_MOVE);
    }

    private void turnToFaceTarget(double x, double y) {
        double bearing = bearingTo(x, y);

        if (bearing >= 0) {
            spinDirection = 1;
        } else {
            spinDirection = -1;
        }

        turnLeft(bearing);
    }

    private boolean hasAnyTarget() {
        return targetId != -1;
    }

    private boolean hasFreshTarget() {
        return hasAnyTarget() && (getTurnNumber() - targetLastSeenTurn <= TARGET_FRESH_TURNS);
    }

    private boolean nearWall() {
        return getX() < WALL_MARGIN
                || getY() < WALL_MARGIN
                || getX() > getArenaWidth() - WALL_MARGIN
                || getY() > getArenaHeight() - WALL_MARGIN;
    }

    private void clearTarget() {
        targetId = -1;
        targetLastSeenTurn = -9999;
    }

    private void debug(String msg) {
        if (DEBUG) {
            System.out.println("[SpinRAM] turn=" + getTurnNumber() + " " + msg);
        }
    }
}

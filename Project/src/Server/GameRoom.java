// UCID: lm87 | Date: 2025-08-10
// Brief: RPS GameRoom – handles lifecycle (join/leave), session start, round start,
// and basic scoreboard sync for Milestone 2.
package Server;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class GameRoom extends Room {

    // ----- Game State -----
    private enum Phase { IDLE, CHOOSING, RESOLVING }
    private volatile Phase phase = Phase.IDLE;
    private volatile int roundNumber = 0;

    // === EXTRA CHOICES FEATURE (RPS-5) ===
    // Added LIZARD and SPOCK to Choice enum
    public enum Choice { ROCK, PAPER, SCISSORS, LIZARD, SPOCK, NONE }

    // Per-round data
    private final Map<Long, Choice> picks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Boolean> eliminated = new java.util.concurrent.ConcurrentHashMap<>();

    // Scoreboard: clientId -> points
    private final Map<Long, Integer> points = new java.util.concurrent.ConcurrentHashMap<>();

    // Optional timer handle (safe no-op usage if not wired yet)
    private ScheduledFuture<?> roundTimerFuture;

    // === EXTRA CHOICES FEATURE (RPS-5) ===
    private boolean extraChoicesEnabled = false;
    private String extraChoicesMode = "FULL"; // or "LAST3"

    // UCID: lm87 | Date: 2025-08-10
    // Brief: Single-threaded scheduler for round timer + round duration (seconds).
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();
    private static final int ROUND_SECONDS = 120;

    private enum LoseRule { LOSE_ON_ATTACK, LOSE_ON_DEFEND }

    private boolean cooldownEnabled = false;                         
    private final java.util.Map<Long, Choice> lastRoundPick = new java.util.HashMap<>(); 

    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> away =
        new java.util.concurrent.ConcurrentHashMap<>();
        

    private LoseRule loseRule = LoseRule.LOSE_ON_DEFEND; // default matches your worksheet text

    public GameRoom(String name) {
        super(name);
    }

    // ----- Timer helpers (safe even if you don't use timers yet) -----
    // UCID: lm87 | Date: 2025-08-10
    // Brief: Safe cancel for any pending round timer.
    protected synchronized void cancelRoundTimer() {
        if (roundTimerFuture != null) {
            roundTimerFuture.cancel(false);
            roundTimerFuture = null;
        }
    }

    // Called when host updates settings
    public void setExtraChoices(boolean enabled, String mode) {
        this.extraChoicesEnabled = enabled;
        this.extraChoicesMode = mode;

        if(enabled == this.extraChoicesEnabled && mode.equalsIgnoreCase(this.extraChoicesMode)){
            return;
        }
        broadcast("[SETTINGS] EXTRA_CHOICES " + enabled + " " + mode);
    }

    public boolean areExtraChoicesAllowedNow() {
        if (!extraChoicesEnabled) return false;
        if ("FULL".equals(extraChoicesMode)) return true;
        return getActivePlayers().size() <= 3;
    }

    private java.util.List<Long> getActivePlayers() {
        java.util.List<Long> active = new java.util.ArrayList<>();
        for (Long id : clientsInRoom.keySet()) {
            if (!eliminated.getOrDefault(id, false) && !away.getOrDefault(id, false)) {
                active.add(id);
            }
        }
        return active;
    }

    // === EXTRA CHOICES FEATURE (RPS-5) ===
    // Win relationships for RPSLS
    private boolean beats(Choice a, Choice b) {
        return switch (a) {
            case ROCK    -> (b == Choice.SCISSORS || b == Choice.LIZARD);
            case PAPER   -> (b == Choice.ROCK || b == Choice.SPOCK);
            case SCISSORS-> (b == Choice.PAPER || b == Choice.LIZARD);
            case LIZARD  -> (b == Choice.SPOCK || b == Choice.PAPER);
            case SPOCK   -> (b == Choice.SCISSORS || b == Choice.ROCK);
            default -> false;
        };
    }

    // ----- Lifecycle: Client Added/Removed (called by Room via hooks you added) -----
    // UCID: lm87 | Date: 2025-08-10
    // Brief: When a client joins, ensure scoreboard entry and sync the current board to them.
    @Override
    protected synchronized void onClientAdded(ServerThread st) {
        away.put(st.getClientId(), false);
        final long id = st.getClientId();
        points.putIfAbsent(id, 0);
        boolean spectator = (phase != Phase.IDLE);
        eliminated.put(id, spectator);
        picks.remove(id);
        st.sendPoints(snapshotBoard(), "[SYNC] Welcome to " + getName());
        broadcast(String.format("%s joined %s", st.getDisplayName(), getName()));
        syncUserList();
        System.out.println("[DEBUG] onClientAdded -> " + st.getDisplayName() + " id=" + id +
                " spectator=" + spectator + " phase=" + phase);
    }

    // UCID: lm87 | Date: 2025-08-10
    // Brief: When a client leaves, clean data; if room empty, reset session state.
    @Override
    protected synchronized void onClientRemoved(ServerThread st) {
        away.remove(st.getClientId());
        points.remove(st.getClientId());
        picks.remove(st.getClientId());
        eliminated.remove(st.getClientId());
        syncUserList();
        broadcast(String.format("%s left %s", st.getDisplayName(), getName()));
        System.out.println("[DEBUG] onClientRemoved -> " + st.getDisplayName() + " id=" + st.getClientId());

        if (points.isEmpty()) {
            cancelRoundTimer();
            phase = Phase.IDLE;
            roundNumber = 0;
            picks.clear();
            eliminated.clear();
            System.out.println("[DEBUG] room empty -> reset phase/round");
        }
    }

    // UCID: lm87 | Date: 2025-08-10
    // Brief: Temp: allow /start to begin a session in GameRoom for MS2 testing.
    @Override
    protected synchronized void handleMessage(ServerThread sender, String text) {
        if (text != null && tryHandleExtraChoices(sender, text)) {
            return; // consumed; do NOT relay as chat
        }
        if (tryHandleCooldown(sender, text))     return;

        if (tryHandleAway(sender, text)) return;
        // (optional) you can intercept /start or other game commands here too
        super.handleMessage(sender, text); // default relay from Room
    }

    private boolean tryHandleAway(ServerThread sender, String raw) {
        if (raw == null) return false;
    
        // Strip any "prefix: " (e.g., "Room[lobby] alice: ")
        String msg = raw;
        int colon = msg.indexOf(':');
        if (colon >= 0) {
            msg = msg.substring(colon + 1);
        }
        msg = msg.trim();
    
        // Accept either "[AWAY] ..." or "AWAY ..."
        if (msg.regionMatches(true, 0, "[AWAY]", 0, 6)) {
            msg = msg.substring(6).trim();
        } else if (msg.regionMatches(true, 0, "AWAY", 0, 4)) {
            msg = msg.substring(4).trim();
        } else {
            return false;
        }
    
        Boolean newState = null;
        if (msg.equalsIgnoreCase("TOGGLE")) {
            boolean cur = away.getOrDefault(sender.getClientId(), false);
            newState = !cur;
        } else if ("1".equals(msg) || "true".equalsIgnoreCase(msg)) {
            newState = true;
        } else if ("0".equals(msg) || "false".equalsIgnoreCase(msg)) {
            newState = false;
        } else {
            return false;
        }
    
        setAwayFor(sender, newState);  // <- broadcasts "X is away" and sends USER_LIST with away map
        return true;
    }
    private void setAwayFor(ServerThread st, boolean isAway) {
        away.put(st.getClientId(), isAway);
    
        // Relay a readable event to everyone
        broadcast(String.format("%s is %s", st.getDisplayName(), isAway ? "away" : "no longer away"));
    
        // Rebuild and send the user list to everyone (includes away map)
        java.util.Map<Long,Integer> snapshotPoints = new java.util.LinkedHashMap<>(points);
        java.util.Map<Long,Boolean> snapshotElim   = new java.util.LinkedHashMap<>(eliminated);
        java.util.Map<Long,Boolean> snapshotPending= new java.util.LinkedHashMap<>();
        for (Long id : clientsInRoom.keySet()) {
            boolean pend = !eliminated.getOrDefault(id, false)
                         && picks.getOrDefault(id, Choice.NONE) == Choice.NONE;
            snapshotPending.put(id, (!away.getOrDefault(id,false)) && pend);
        }
        for (ServerThread s : new java.util.ArrayList<>(clientsInRoom.values())) {
            s.sendUserList(snapshotPoints, snapshotElim, snapshotPending,
                           new java.util.HashMap<>(away));
        }
    }

    private boolean tryHandleCooldown(ServerThread sender, String raw) { 
        String msg = raw == null ? "" : raw.trim();
        if (msg.startsWith("[SETTINGS]")) msg = msg.substring(10).trim();
        if (!msg.startsWith("COOLDOWN")) return false;
    
        String[] parts = msg.split("\\s+");
        if (parts.length < 2) return true;
    
        boolean enabled = "1".equals(parts[1]) || "true".equalsIgnoreCase(parts[1]);
    
        // (optional) host guard like isHost(sender)
        // if (!isHost(sender)) { sender.sendMessage("[SYSTEM] Host only."); return true; }
    
        if (enabled == this.cooldownEnabled) return true; // dedupe
        setCooldown(enabled);
        return true;
    }

    private void setCooldown(boolean enabled) {          
        this.cooldownEnabled = enabled;
        broadcast("[SETTINGS] COOLDOWN " + enabled);
    }

    // UCID: lm87 | Date: 2025-08-11
// Brief: Parse "[SETTINGS] EXTRA_CHOICES <enabled> <mode>" or
//        "[EXTRA_CHOICES] <enabled> <mode>" and persist on the room.
private boolean tryHandleExtraChoices(ServerThread sender, String raw) {
    String msg = raw.trim();

    // Normalize prefixes so we can accept both message shapes
    if (msg.startsWith("[SETTINGS]")) {
        msg = msg.substring("[SETTINGS]".length()).trim();
    }
    if (msg.startsWith("[EXTRA_CHOICES]")) {
        msg = msg.substring("[EXTRA_CHOICES]".length()).trim();
    }

    if (!msg.startsWith("EXTRA_CHOICES")) {
        return false; // not our message
    }

    // Expected: "EXTRA_CHOICES <enabled> <mode>"
    String[] parts = msg.split("\\s+");
    if (parts.length < 3) {
        // malformed; ignore silently or notify sender if you prefer
        return true; // we "handled" it to avoid echoing junk to chat
    }

    String enabledStr = parts[1];
    String modeStr    = parts[2];

    boolean enabled = "1".equals(enabledStr) || "true".equalsIgnoreCase(enabledStr);

    // Sanitize mode
    String mode = ("LAST3".equalsIgnoreCase(modeStr)) ? "LAST3" : "FULL";

    // (Optional) enforce host-only: uncomment if you track host server-side
    // if (!isHost(sender)) {
    //     sender.sendMessage("[SYSTEM] Only the host can change extra choices.");
    //     return true;
    // }

            if (enabled == this.extraChoicesEnabled &&
            mode.equalsIgnoreCase(this.extraChoicesMode)) {
            return true;
        }

        
    // Persist to room state and broadcast a single canonical settings line
    setExtraChoices(enabled, mode); // make sure this method sets fields and broadcasts
    return true;
}
    // ----- Session Start -----
    // UCID: lm87 | Date: 2025-08-10
    // Brief: Starts a new session. Resets state for all players and triggers the first round.
    protected synchronized void onSessionStart() {
        if(phase!= Phase.IDLE){
            broadcast("Session already in progress.");
            return;
        }
        System.out.println("[DEBUG] Session starting in room: " + getName());
        roundNumber = 0;
        phase = Phase.IDLE;
        picks.clear();
        eliminated.clear();
        for (Long clientId : getClientIdsSafe()) {
            eliminated.put(clientId, false);
            picks.put(clientId, Choice.NONE);
        }

        // === EXTRA CHOICES FEATURE (RPS-5) ===
        // Let all clients know current extra-choice setting
        broadcast("[SETTINGS] EXTRA_CHOICES " + extraChoicesEnabled + " " + extraChoicesMode);
        broadcast("[SETTINGS] COOLDOWN " + cooldownEnabled); 
        onRoundStart();
    }

    // Helpers
    private Map<Long, Integer> snapshotBoard() {
        Map<Long, Integer> board = new LinkedHashMap<>();
        points.forEach(board::put);
        return board;
    }

    private void broadcast(String msg) {
        for (ServerThread s : new java.util.ArrayList<>(clientsInRoom.values())) {
            try { s.sendMessage(msg); } catch (Exception e) {
                System.err.println("broadcast -> sendMessage failed for " + s.getDisplayName() + ": " + e);
            }
        }
    }

    private Set<Long> getClientIdsSafe() {
        try { return getClientIds(); }
        catch (Throwable t) {
            System.out.println("[WARN] Room.getClientIds() not found; add it per MS2 notes.");
            return java.util.Collections.emptySet();
        }
    }

    // UCID: lm87 | Date: 2025-08-12
        // Brief: Parse client-side game setting messages and apply them (host-only guard if you have a host concept).
        protected synchronized void applyGameSetting(ServerThread sender, String msg) {
            if (msg == null) return;

            // Expect: "[EXTRA_CHOICES] <0|1|true|false> <FULL|LAST3>"
            // Be flexible: allow prefixes like "Room[xyz] user: [EXTRA_CHOICES] ..."
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\[EXTRA_CHOICES\\]\\s+(\\S+)\\s+(\\S+)")
                    .matcher(msg);
            if (!m.find()) {
                sender.sendMessage("Invalid setting payload: " + msg);
                return;
            }

            String onStr  = m.group(1);
            String mode   = m.group(2);
            boolean enabled = "1".equals(onStr) || "true".equalsIgnoreCase(onStr);

            // If you want host-only control, check sender here
            // e.g., if (!isHost(sender)) { sender.sendMessage("Only host can change settings."); return; }

            // Apply + broadcast (this method already broadcasts)
            setExtraChoices(enabled, mode);
        }

    private Map<Long, ServerThread> getClientsSnapshotSafe() {
        try { return getClientsSnapshot(); }
        catch (Throwable t) {
            System.out.println("[WARN] Room.getClientsSnapshot() not found; add it per MS2 notes.");
            return java.util.Collections.emptyMap();
        }
    }

    // UCID: lm87 | Date: 2025-08-10
    // Brief: Round start – init picks, set phase to CHOOSING, and arm the round timer.
    protected synchronized void onRoundStart() {
        roundNumber++;
        phase = Phase.CHOOSING;
        for (Long id : getClientIds()) {
            if (!eliminated.getOrDefault(id, false)) {
                picks.put(id, Choice.NONE);
            }
        }
        for (Long id : clientsInRoom.keySet()) {
            if (!eliminated.getOrDefault(id, false)) {
                broadcast("[PENDING] " + id + " 1");
            }
        }
        cancelRoundTimer();
        roundTimerFuture = SCHEDULER.schedule(this::safeEndRound, ROUND_SECONDS, TimeUnit.SECONDS);
        syncUserList();
        broadcast("[ROUND_START] " + ROUND_SECONDS);
        broadcast(String.format("Round %d started. Make your /pick [r|p|s%s]!", 
            roundNumber, areExtraChoicesAllowedNow() ? "|l|k" : ""));
        System.out.println("[DEBUG] onRoundStart -> round=" + roundNumber + " phase=" + phase);
    }

    // UCID: lm87 | Date: 2025-08-10
    private synchronized void onRoundEnd() {
        cancelRoundTimer();
        phase = Phase.RESOLVING;
        broadcast("Round " + roundNumber + " ending...");

        for (Long id : clientsInRoom.keySet()) {
            if (eliminated.getOrDefault(id, false)) continue;
            Choice c = picks.get(id);
            if (c == null || c == Choice.NONE) {
                eliminated.put(id, true);
                lastRoundPick.put(id, c); 
                broadcast(getNameOf(id) + " did not pick and is eliminated!");
                broadcast("[ELIM] " + id + " 1");
                broadcast("[PENDING] " + id + " 0");
            }
        }
        syncUserList();

        java.util.List<Long> active = new java.util.ArrayList<>();
        for (Long id : clientsInRoom.keySet()) {
            if (!eliminated.getOrDefault(id, false)) active.add(id);
        }

        if (active.size() <= 1) {
            Long winnerId = null;
            for (Long id : clientsInRoom.keySet()) {
                if (!eliminated.getOrDefault(id, false)) { winnerId = id; break; }
            }
            if (winnerId != null) {
                points.put(winnerId, points.getOrDefault(winnerId, 0) + 1);
                broadcast("Game over! Winner: " + getNameOf(winnerId));
            } else {
                broadcast("Game over! No players remain. It's a tie.");
            }
            syncPoints();
            onSessionEnd();
            return;
        }

        // === EXTRA CHOICES FEATURE (RPS-5) ===
        // Determine winners using RPSLS beats()
        java.util.Map<Long, Choice> activePicks = new java.util.HashMap<>();
        java.util.Set<Choice> present = new java.util.HashSet<>();
        for (Long id : active) {
            Choice c = picks.get(id);
            if (c == null) c = Choice.NONE;
            activePicks.put(id, c);
            present.add(c);
        }

        if (present.size() == 1) {
            broadcast("No decisive result this round. It's a stalemate.");
            syncPoints();
            syncUserList();
            onRoundStart();
            return;
        }

        // Find winning moves (those that beat at least one other present move and aren't beaten by any)
        java.util.Set<Choice> winningMoves = new java.util.HashSet<>();
        for (Choice candidate : present) {
            boolean beaten = false;
            for (Choice opponent : present) {
                if (beats(opponent, candidate)) { beaten = true; break; }
            }
            if (!beaten) winningMoves.add(candidate);
        }

        java.util.List<Long> losers = new java.util.ArrayList<>();
        for (var e : activePicks.entrySet()) {
            if (!winningMoves.contains(e.getValue())) {
                losers.add(e.getKey());
            }
        }
        for (Long id : losers) {
            eliminated.put(id, true);
            broadcast("Eliminated: " + getNameOf(id));
            broadcast("[ELIM] " + id + " 1");
            broadcast("[PENDING] " + id + " 0");
        }

        syncUserList();

        int survivors = 0;
        Long lastSurvivor = null;
        for (Long id : clientsInRoom.keySet()) {
            if (!eliminated.getOrDefault(id, false)) {
                survivors++;
                lastSurvivor = id;
            }
        }

        if (survivors <= 1) {
            if (lastSurvivor != null) {
                points.put(lastSurvivor, points.getOrDefault(lastSurvivor, 0) + 1);
                broadcast("Game over! Winner: " + getNameOf(lastSurvivor));
            } else {
                broadcast("Game over! No players remain. It's a tie.");
            }
            syncPoints();
            syncUserList();
            onSessionEnd();
        } else {
            syncPoints();
            syncUserList();
            onRoundStart();
        }
    }

    private void syncPoints() {
        Map<Long, Integer> snapshot = new java.util.LinkedHashMap<>(points);
        for (ServerThread st : new java.util.ArrayList<>(clientsInRoom.values())) {
            st.sendPoints(snapshot, "[SCOREBOARD]");
        }
    }

    private String getNameOf(Long id) {
        ServerThread st = getClientsSnapshotSafe().get(id);
        return (st != null) ? st.getDisplayName() : ("#" + id);
    }

    protected synchronized void endRound() {
        cancelRoundTimer();
        phase = Phase.RESOLVING;
        broadcast(String.format("Round %d ended.", roundNumber));
        System.out.println("[DEBUG] endRound -> round=" + roundNumber + " phase=" + phase);
    }

    private synchronized void safeEndRound() {
        if (phase != Phase.CHOOSING) return;
        cancelRoundTimer();
        onRoundEnd();
    }

    protected synchronized void onSessionEnd() {
        int alive = 0;
        Long winner = null;
        for (Long id : getClientIdsSafe()) {
            if (!eliminated.getOrDefault(id, false)) {
                alive++;
                winner = id;
            }
        }

        String overMsg = (alive == 1)
            ? "Game over! Winner: " + getNameOf(winner)
            : "Game over! No players remain. It's a tie.";
        broadcast(overMsg);

        java.util.List<java.util.Map.Entry<Long,Integer>> entries =
            new java.util.ArrayList<>(points.entrySet());
        entries.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));

        java.util.Map<Long,Integer> finalBoard = new java.util.LinkedHashMap<>();
        for (var e : entries) finalBoard.put(e.getKey(), e.getValue());

        for (ServerThread st : getClientsSnapshotSafe().values()) {
            st.sendPoints(finalBoard, "[FINAL] " + overMsg);
        }

        phase = Phase.IDLE;
        roundNumber = 0;
        picks.clear();
        eliminated.clear();
        for (Long id : getClientIdsSafe()) {
            broadcast("[ELIM] " + id + " 0");
            broadcast("[PENDING] " + id + " 0");
        }
        syncUserList();
        lastRoundPick.clear();
        broadcast("Session reset. Use the ready flow to start a new game.");
    }

    // UCID: lm87 | Date: 2025-08-10
    // Brief: Validate /pick, record it, announce, and check if the round should end.
    @Override
    protected synchronized void handlePick(ServerThread sender, String rawChoice) {
        if (phase != Phase.CHOOSING) {
            sender.sendMessage("You cannot pick right now. Wait for the next round.");
            return;
        }
        final long id = sender.getClientId();
        if (eliminated.getOrDefault(id, false)) {
            sender.sendMessage("You're eliminated this session and are now a spectator.");
            return;
        }
        if (picks.get(id) != null && picks.get(id) != Choice.NONE) {
            sender.sendMessage("You already picked for this round.");
            return;
        }

        Choice choice;
        switch (rawChoice.trim().toLowerCase()) {
            case "r": choice = Choice.ROCK; break;
            case "p": choice = Choice.PAPER; break;
            case "s": choice = Choice.SCISSORS; break;
            // === EXTRA CHOICES FEATURE (RPS-5) ===
            case "l": 
                if (!areExtraChoicesAllowedNow()) {
                    sender.sendMessage("Lizard is not allowed right now.");
                    return;
                }
                choice = Choice.LIZARD;
                break;
            case "k":
                if (!areExtraChoicesAllowedNow()) {
                    sender.sendMessage("Spock is not allowed right now.");
                    return;
                }
                choice = Choice.SPOCK;
                break;
            default:
                sender.sendMessage("Invalid pick. Use /pick <r|p|s" + 
                    (areExtraChoicesAllowedNow() ? "|l|k" : "") + ">");
                return;
        }

        // === COOLDOWN ENFORCEMENT ===
        if (cooldownEnabled) {
            Choice last = lastRoundPick.get(id);
            if (last != null && last == choice) {
                sender.sendMessage("That option is on cooldown for you. Pick something different this round.");
                return;
            }
    }

        if(choice == null){
            choice = Choice.NONE;
        }
        picks.put(id, choice);
        
        broadcast(getNameOf(id) + " picked their choice.");
        broadcast("[PENDING] " + id + " 0");
        syncUserList();

        if (allActivePicked()) {
            cancelRoundTimer();
            onRoundEnd();
        } else {
            broadcast(sender.getClientName() + "#" + id + " picked their choice.");
        }
    }

    private void syncUserList() {
        java.util.Map<Long,Integer> snapshotPoints = new java.util.LinkedHashMap<>(points);
        java.util.Map<Long,Boolean> snapshotElim = new java.util.LinkedHashMap<>(eliminated);
        java.util.Map<Long,Boolean> snapshotPending = new java.util.LinkedHashMap<>();
        for (Long id : clientsInRoom.keySet()) {
            boolean elim = eliminated.getOrDefault(id, false);
            boolean pend = (phase == Phase.CHOOSING) && !elim &&
                        (picks.getOrDefault(id, Choice.NONE) == Choice.NONE);

            snapshotPending.put(id, (!away.getOrDefault(id,false)) && pend);
        }
        for (ServerThread st : new java.util.ArrayList<>(clientsInRoom.values())) {
            st.sendUserList(snapshotPoints, snapshotElim, snapshotPending, new java.util.HashMap<>(away));
        }
    }

    private boolean allActivePicked() {
        for (Long id : clientsInRoom.keySet()) {
            if (!eliminated.getOrDefault(id, false) && !away.getOrDefault(id, false)) {
                if (picks.getOrDefault(id, Choice.NONE) == Choice.NONE) {
                    return false;
                }
            }
        }
        return true;
    }
}

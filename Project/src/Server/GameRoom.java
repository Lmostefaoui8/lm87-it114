// UCID: lm87 | Date: 2025-08-10
// Brief: RPS GameRoom – handles lifecycle (join/leave), session start, round start,
// and basic scoreboard sync for Milestone 2.
package Server;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import Common.PayloadType;

public class GameRoom extends Room {

    // ----- Game State -----
    private enum Phase { IDLE, CHOOSING, RESOLVING }
    private volatile Phase phase = Phase.IDLE;
    private volatile int roundNumber = 0;

    public enum Choice { ROCK, PAPER, SCISSORS,NONE }

    // Per-round data
    private final Map<Long, Choice> picks = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, Boolean> eliminated = new java.util.concurrent.ConcurrentHashMap<>();

    // Scoreboard: clientId -> points
    private final Map<Long, Integer> points = new java.util.concurrent.ConcurrentHashMap<>();

    // Optional timer handle (safe no-op usage if not wired yet)
    private ScheduledFuture<?> roundTimerFuture;

    

    // UCID: lm87 | Date: 2025-08-10
// Brief: Single-threaded scheduler for round timer + round duration (seconds).
private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor();
private static final int ROUND_SECONDS = 120;

private enum LoseRule { LOSE_ON_ATTACK, LOSE_ON_DEFEND }
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

    

    // ----- Lifecycle: Client Added/Removed (called by Room via hooks you added) -----

    // UCID: lm87 | Date: 2025-08-10
    // Brief: When a client joins, ensure scoreboard entry and sync the current board to them.
        @Override
        protected synchronized void onClientAdded(ServerThread st) {
            final long id = st.getClientId();

            // 1) Ensure scoreboard entry exists
            points.putIfAbsent(id, 0);

            // 2) Session-aware status:
            //    - If a round/session is running, joiner is a spectator (eliminated=true) until next session.
            //    - If idle, they are active (eliminated=false) and will be initialized in onRoundStart().
            boolean spectator = (phase != Phase.IDLE);
            eliminated.put(id, spectator);

            // Don’t seed picks here; do it only in onRoundStart() for active players.
            picks.remove(id);

            // 3) Sync current scoreboard ONLY to the joiner (nice UX)
            st.sendPoints(snapshotBoard(), "[SYNC] Welcome to " + getName());

            // 4) Broadcast using a snapshot to avoid ConcurrentModificationException
            broadcast(String.format("%s joined %s", st.getDisplayName(), getName()));

            System.out.println("[DEBUG] onClientAdded -> " + st.getDisplayName() + " id=" + id +
                    " spectator=" + spectator + " phase=" + phase);
        }

    // UCID: lm87 | Date: 2025-08-10
    // Brief: When a client leaves, clean data; if room empty, reset session state.
    @Override
    protected synchronized void onClientRemoved(ServerThread st) {
        points.remove(st.getClientId());
        picks.remove(st.getClientId());
        eliminated.remove(st.getClientId());

        broadcast(String.format("%s left %s", st.getDisplayName(), getName()));
        System.out.println("[DEBUG] onClientRemoved -> " + st.getDisplayName() + " id=" + st.getClientId());

        // If no one remains, hard reset game state so next session is clean
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
protected synchronized void handleMessage(ServerThread sender, String text) {
    relay(sender, text);
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

        // Reset counters and phase
        roundNumber = 0;
        phase = Phase.IDLE;

        // Reset per-round maps
        picks.clear();
        eliminated.clear();

        // Initialize entries for all current clients
        for (Long clientId : getClientIdsSafe()) {
            eliminated.put(clientId, false);
            picks.put(clientId, Choice.NONE);
            // If you want a fresh game, uncomment:
            // points.put(clientId, 0);
        }

        // Trigger first round immediately (MS2 requirement)
        onRoundStart();
    }


    // ----- Helpers -----

    // UCID: lm87 | Date: 2025-08-10
    // Brief: Snapshot the current scoreboard (ordered map for stable debug/UI).
    private Map<Long, Integer> snapshotBoard() {
        Map<Long, Integer> board = new LinkedHashMap<>();
        points.forEach(board::put);
        return board;
    }

    // UCID: lm87 | Date: 2025-08-10
    // Brief: Broadcast a system-style line to everyone in the room.
    private void broadcast(String msg) {
        for (ServerThread s : new java.util.ArrayList<>(clientsInRoom.values())) {
            try {
                s.sendMessage(msg);
            } catch (Exception e) {
                System.err.println("broadcast -> sendMessage failed for " + s.getDisplayName() + ": " + e);
            }
        }
    }

    // UCID: lm87 | Date: 2025-08-10
    // Brief: Safe wrappers in case Room accessors are added as advised.
    private Set<Long> getClientIdsSafe() {
        try {
            return getClientIds();
        } catch (Throwable t) {
            // If accessors aren’t present yet, fail loud in logs
            System.out.println("[WARN] Room.getClientIds() not found; add it per MS2 notes.");
            return java.util.Collections.emptySet();
        }
    }

    private Map<Long, ServerThread> getClientsSnapshotSafe() {
        try {
            return getClientsSnapshot();
        } catch (Throwable t) {
            System.out.println("[WARN] Room.getClientsSnapshot() not found; add it per MS2 notes.");
            return java.util.Collections.emptyMap();
        }
    }

    // UCID: lm87 | Date: 2025-08-10
// Brief: Round start – init picks, set phase to CHOOSING, and arm the round timer.
protected synchronized void onRoundStart() {
    roundNumber++;
    phase = Phase.CHOOSING;

    // Initialize this round’s picks for active players
    for (Long id : getClientIds()) {
        if (!eliminated.getOrDefault(id, false)) {
            picks.put(id, Choice.NONE); // null means "hasn't picked yet"
        }
    }

    // Cancel any prior timer and start a new one
    cancelRoundTimer();
    roundTimerFuture = SCHEDULER.schedule(this::safeEndRound, ROUND_SECONDS, TimeUnit.SECONDS);

    broadcast(String.format("Round %d started. Make your /pick [r|p|s]!", roundNumber));
    System.out.println("[DEBUG] onRoundStart -> round=" + roundNumber + " phase=" + phase);
}

// UCID: lm87 | Date: 2025-08-10
// Brief: Resolve the round (timer or all-picked). Eliminates non-pickers, runs
// clockwise battles, awards points, syncs scoreboard, and decides next step.
private synchronized void onRoundEnd() {
    // (1) stop the timer; we’re resolving now
    cancelRoundTimer();

    // (2) lock phase and announce
    phase = Phase.RESOLVING;
    broadcast("Round " + roundNumber + " ending...");

    // (3) eliminate non-pickers (null or Choice.NONE)
    for (Long id : clientsInRoom.keySet()) {
        if (eliminated.getOrDefault(id, false)) continue;
        Choice c = picks.get(id);
        if (c == null || c == Choice.NONE) {
            eliminated.put(id, true);
            broadcast(getNameOf(id) + " did not pick and is eliminated!");
        }
    }

    // (4) build active list AFTER the above eliminations
    java.util.List<Long> active = new java.util.ArrayList<>();
    for (Long id : clientsInRoom.keySet()) {
        if (!eliminated.getOrDefault(id, false)) active.add(id);
    }

    // (5) early game-over check
    if (active.size() <= 1) {
        // award the point to the last survivor (if any), then end session
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

    // (6) collect choices of remaining players
    java.util.Map<Long, Choice> activePicks = new java.util.HashMap<>();
    java.util.Set<Choice> present = new java.util.HashSet<>();
    for (Long id : active) {
        Choice c = picks.get(id);
        if (c == null) c = Choice.NONE;
        activePicks.put(id, c);
        present.add(c);
    }

    // (7) stalemate rules: all same (size=1) or all three (size=3)
    if (present.size() == 1 || present.size() == 3) {
        broadcast("No decisive result this round. It's a stalemate. Replaying the round.");
        syncPoints(); // no changes, but keeps clients in sync
        onRoundStart(); // advance to next round with same survivors
        return;
    }

    // (8) exactly two choices present -> determine the winning choice
    // PAPER beats ROCK; SCISSORS beats PAPER; ROCK beats SCISSORS
    Choice winning;
    if (present.contains(Choice.ROCK) && present.contains(Choice.PAPER)) {
        winning = Choice.PAPER;
    } else if (present.contains(Choice.PAPER) && present.contains(Choice.SCISSORS)) {
        winning = Choice.SCISSORS;
    } else { // ROCK and SCISSORS
        winning = Choice.ROCK;
    }

    // (9) eliminate all players who did NOT pick the winning choice
    java.util.List<Long> losers = new java.util.ArrayList<>();
    for (java.util.Map.Entry<Long, Choice> e : activePicks.entrySet()) {
        if (e.getValue() != winning) {
            losers.add(e.getKey());
        }
    }
    for (Long id : losers) {
        eliminated.put(id, true);
        broadcast("Eliminated: " + getNameOf(id));
    }

    // (10) count survivors and decide next step
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
        onSessionEnd();
    } else {
        // continue to next round with survivors
        syncPoints();
        onRoundStart();
    }
}

// UCID: lm87 | Date: 2025-08-10
private void syncPoints() {
    // Take a safe snapshot
    Map<Long, Integer> snapshot = new java.util.LinkedHashMap<>(points);

    // Send to all active ServerThreads in the room
    for (ServerThread st : new java.util.ArrayList<>(clientsInRoom.values())) {
        st.sendPoints(snapshot, "[SCOREBOARD]");
    }
}







// UCID: lm87 | Date: 2025-08-10
// Brief: Helper to print a player name from id, falling back to id if missing.
private String getNameOf(Long id) {
    ServerThread st = getClientsSnapshotSafe().get(id);
    return (st != null) ? st.getDisplayName() : ("#" + id);
}




// UCID: lm87 | Date: 2025-08-10
// Brief: Minimal endRound stub so MS2 round timer compiles; fill in battle logic later.
protected synchronized void endRound() {
    cancelRoundTimer();
    phase = Phase.RESOLVING;

    broadcast(String.format("Round %d ended.", roundNumber));
    System.out.println("[DEBUG] endRound -> round=" + roundNumber + " phase=" + phase);

    // TODO (MS2): mark non-pickers eliminated, resolve battles clockwise, award points,
    // sync via sendPoints(...), check winner/tie, then either onRoundStart() or session end.
}

// UCID: lm87 | Date: 2025-08-10
// Brief: Wrapper to end the round from the timer without throwing if room changed.
private synchronized void safeEndRound() {
    if (phase != Phase.CHOOSING) return; // already handled
    cancelRoundTimer();
    onRoundEnd();
}


// UCID: lm87 | Date: 2025-08-10
// Brief: Finish the session: announce winner/tie, send final sorted scoreboard, reset state.
protected synchronized void onSessionEnd() {
    // Determine winner vs tie from remaining (non-eliminated) players
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

    // Build final scoreboard sorted by points (desc)
    java.util.List<java.util.Map.Entry<Long,Integer>> entries =
        new java.util.ArrayList<>(points.entrySet());
    entries.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));

    java.util.Map<Long,Integer> finalBoard = new java.util.LinkedHashMap<>();
    for (var e : entries) finalBoard.put(e.getKey(), e.getValue());

    // Send final scoreboard to all clients
    for (ServerThread st : getClientsSnapshotSafe().values()) {
        st.sendPoints(finalBoard, "[FINAL] " + overMsg);
    }

    // Reset server-side player state (do not disconnect or move rooms)
    phase = Phase.IDLE;
    roundNumber = 0;
    picks.clear();
    eliminated.clear();
    // If you want a totally fresh next session, also clear points:
    // points.replaceAll((id, p) -> 0);

    // Let clients know they need a new ready check on their side
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
        default:
            sender.sendMessage("Invalid pick. Use /pick <r|p|s>");
            return;
    }

    if(choice == null){
        choice = Choice.NONE;
    }
    picks.put(id, choice); // now types match
    broadcast(getNameOf(id) + " picked their choice.");

    if (allActivePicked()) {
        cancelRoundTimer();
        onRoundEnd();
    } else {
        broadcast(sender.getClientName() + "#" + id + " picked their choice.");
    }
}

// UCID: lm87 | Date: 2025-08-10
// Brief: Convert "r|p|s" to enum.
private Choice parseChoice(String s) {
    if (s == null) return null;
    s = s.trim().toLowerCase();
    switch (s) {
        case "r": return Choice.ROCK;
        case "p": return Choice.PAPER;
        case "s": return Choice.SCISSORS;
        default:  return null;
    }
}

// UCID: lm87 | Date: 2025-08-10
// Brief: True if every non-eliminated player has a non-null pick.
private boolean allActivePicked() {
    for (Long id : clientsInRoom.keySet()) {
        if (!eliminated.getOrDefault(id, false)) {
            if (picks.getOrDefault(id, Choice.NONE) == Choice.NONE) {
                return false;
            }
        }
    }
    return true;
}





    // ----- (MS2 future) Processing picks & ending rounds -----
    // For Milestone 2 you’ll implement:
    // - handlePick(sender, r/p/s) -> validate & record into `picks`
    // - endRound() when all active players picked OR timer expires
    // - resolve battles clockwise, award points, sync via st.sendPoints(...)
    // - mark losers `eliminated=true`, check winner/tie/continue -> startRound()
    // The maps/fields above are already prepared for that work.
}
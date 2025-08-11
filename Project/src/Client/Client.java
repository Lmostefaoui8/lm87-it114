package Client;

import Common.*; 
import Server.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import Common.TextFX.Color;

/**
 * Demoing bi-directional communication between client and server in a
 * multi-client scenario
 */
public enum Client {
    INSTANCE;

    private Socket server = null;
    private ObjectOutputStream out = null;
    private ObjectInputStream in = null;

    // UCID: LM87 | 2025-08-09
    // Summary: Allows /connect localhost:port or IP:port.
    final Pattern ipAddressPattern = Pattern
            .compile("/connect\\s+(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d{3,5})");
    final Pattern localhostPattern = Pattern.compile("/connect\\s+(localhost:\\d{3,5})");
    private volatile boolean isRunning = true; // volatile for thread-safe visibility
    private final ConcurrentHashMap<Long, User> knownClients = new ConcurrentHashMap<Long, User>();
    private User myUser = new User();
    // track readiness per client
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> readyMap =
            new java.util.concurrent.ConcurrentHashMap<Long, Boolean>();

    // Points from server (sync payload)
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> pointsMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Round state status flags (broadcast as messages)
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> pendingMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> awayMap =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> eliminatedMap =
            new java.util.concurrent.ConcurrentHashMap<>();


    public synchronized java.util.Map<Long, Boolean> uiGetAwaySnapshot() {
        return new java.util.HashMap<>(awayMap);
    }
    public synchronized java.util.Map<Long, Integer> uiGetPointsSnapshot() {
        return new java.util.HashMap<>(pointsMap);
    }
    public synchronized java.util.Map<Long, Boolean> uiGetPendingSnapshot() {
        return new java.util.HashMap<>(pendingMap);
    }
    public synchronized java.util.Map<Long, Boolean> uiGetEliminatedSnapshot() {
        return new java.util.HashMap<>(eliminatedMap);
    }

    public synchronized String uiGetLastRoundPick() { return uiLastRoundPick; }

    private final java.util.List<String> knownRooms =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public java.util.List<String> uiGetRoomsSnapshot() {
        return new java.util.ArrayList<>(knownRooms);
    }

    private volatile boolean cooldownEnabled = false;   // NEW
   
    private volatile String  uiLastRoundPick = null;    // NEW - what I picked last round

    // UCID: LM87 | 2025-08-11
    private volatile Long hostId = null;

    private long myClientId = -1;


    // UCID: LM87 | 2025-08-11
    // Event log for Game Events Panel
    private final java.util.concurrent.CopyOnWriteArrayList<String> gameEvents =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    // Round timer tracking (computed locally from [ROUND_START] seconds)
    private volatile long roundEndEpochMs = 0L;
    private volatile int roundDurationSec = 0;

    // UCID: LM87 | 2025-08-11
    // Track what I picked this round (UI highlight), reset on round start
    private volatile String uiSelectedPick = null;
    public synchronized boolean uiCooldownEnabled() { return cooldownEnabled; }
    // === EXTRA CHOICES FEATURE (RPS-5) ===
    private volatile boolean extraChoicesEnabled = false;
    private volatile String extraChoicesMode = "FULL"; // FULL or LAST3

    private void error(String message) {
        System.out.println(TextFX.colorize(String.format("%s", message), Color.RED));
    }

    // UCID: LM87 | 2025-08-11
    // Expose events to UI
    public java.util.List<String> uiGetEventsSnapshot() {
        return new java.util.ArrayList<>(gameEvents);
    }

    // Am I eliminated right now?
    public boolean uiAmEliminated() {
        Boolean e = eliminatedMap.get(myUser.getClientId());
        return e != null && e;
    }

    // Did the round start (countdown running) and I can pick?
    public boolean uiIsChoosingNow() {
        return uiGetRoundRemainingSeconds() > 0 && !uiAmEliminated();
    }

    // My selected pick (null if none yet, or after round start)
    public synchronized String uiGetSelectedPick() {
        return uiSelectedPick;
    }

    // Called by host from ReadyPanel
    public synchronized void uiSetExtraChoices(boolean enabled, String mode) {
        String canonicalMode = "LAST3".equalsIgnoreCase(mode) ? "LAST3" : "FULL";
    
        // DEDUPE: if no change, do nothing (prevents echo loops)
        if (enabled == this.extraChoicesEnabled &&
            canonicalMode.equalsIgnoreCase(this.extraChoicesMode)) {
            return;
        }
    
        // Update local cache first
        this.extraChoicesEnabled = enabled;
        this.extraChoicesMode = canonicalMode;
    
        System.out.println("[DEBUG] Setting extra choices: enabled=" + enabled + ", mode=" + canonicalMode);
    
        try {
            Payload p = new Payload();
            p.setPayloadType(PayloadType.MESSAGE);
            p.setClientId(myUser.getClientId());
            p.setMessage("[SETTINGS] EXTRA_CHOICES " + enabled + " " + canonicalMode);
            sendToServer(p);
        } catch (Exception e) {
            System.out.println("uiSetExtraChoices error: " + e.getMessage());
        }
    }

    // UCID: lm87 | Date: 2025-08-11
// Brief: Apply server-sent settings locally without sending back.
        private synchronized void applyExtraChoicesFromServer(boolean enabled, String mode) {
            String canonicalMode = "LAST3".equalsIgnoreCase(mode) ? "LAST3" : "FULL";
            this.extraChoicesEnabled = enabled;
            this.extraChoicesMode = canonicalMode;

           
        }

// Used by ReadyPanel to set checkbox state
public synchronized boolean uiExtraChoicesEnabled() {
    return extraChoicesEnabled;
}

// Used by ReadyPanel to set mode combobox
public synchronized String uiExtraChoicesMode() {
    return extraChoicesMode;
}

// Used by PickBar to decide if Lizard/Spock buttons are clickable
public synchronized boolean uiExtraChoicesAllowed() {
    if (!extraChoicesEnabled) return false;
    if ("FULL".equalsIgnoreCase(extraChoicesMode)) return true;
    // LAST3 mode: allow only when <=3 players remain
    int remaining = 0;
    for (Boolean elim : eliminatedMap.values()) {
        if (elim == null || !elim) remaining++;
    }
    return remaining <= 3;
}

    // Send a pick from UI (r|p|s or extras if allowed)
    public synchronized void uiPick(String choice) {
        if (choice == null) return;
        choice = choice.trim().toLowerCase();

        // === EXTRA CHOICES FEATURE (RPS-5) ===
        if (!(choice.equals("r") || choice.equals("p") || choice.equals("s")
                || (uiExtraChoicesAllowed() && (choice.equals("l") || choice.equals("k"))))) {
            return; // invalid choice for current mode
        }

        try {
            // Build PICK payload directly (same as /pick)
            Payload p = new Payload();
            p.setPayloadType(PayloadType.PICK);
            p.setClientId(myUser.getClientId());
            p.setMessage(choice);
            sendToServer(p);

            // update local UI hint
            uiSelectedPick = choice;
            addEvent("You picked [" + choice + "]");
        } catch (Exception ex) {
            System.out.println("uiPick error: " + ex.getMessage());
        }
    }

    // Remaining seconds for countdown
    public int uiGetRoundRemainingSeconds() {
        if (roundEndEpochMs <= 0) return 0;
        long now = System.currentTimeMillis();
        long rem = (roundEndEpochMs - now + 999) / 1000;
        return (int) Math.max(0, rem);
    }

    // Thin command bridge so UI can still use slash commands like CLI
    public synchronized void uiSendCommand(String text) {
        try {
            if (!processClientCommand(text)) {
                // not a known command -> send as chat
                sendMessage(text);
            }
        } catch (Exception e) {
            System.out.println("uiSendCommand error: " + e.getMessage());
        }
    }

    // helper to add an event line
    private void addEvent(String line) {
        if (line == null || line.isBlank()) return;
        gameEvents.add(line);
        // Optional: cap log size
        if (gameEvents.size() > 500) {
            gameEvents.remove(0);
        }
    }


    // === EXTRA CHOICES FEATURE (RPS-5) ===
    public int uiGetRemainingPlayers() {
        int count = 0;
        for (var e : eliminatedMap.entrySet()) {
            if (!Boolean.TRUE.equals(e.getValue())) {
                count++;
            }
        }
        return count;
    }
    // needs to be private now that the enum logic is handling this
    private Client() {
        System.out.println("Client Created");
    }

    
    public synchronized void uiCreateRoom(String name) throws java.io.IOException {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.ROOM_CREATE);
        p.setMessage(name);
        sendToServer(p);
    }
    public synchronized void uiJoinRoom(String name) throws java.io.IOException {
        Payload p = new Payload();
        p.setPayloadType(PayloadType.ROOM_JOIN);
        p.setMessage(name);
        sendToServer(p);
    }

    public boolean isConnected() {
        if (server == null) {
            return false;
        }
        // https://stackoverflow.com/a/10241044
        // Note: these check the client's end of the socket connect; therefore they
        // don't really help determine if the server had a problem
        // and is just for lesson's sake
        return server.isConnected() && !server.isClosed() && !server.isInputShutdown() && !server.isOutputShutdown();
    }

    /**
     * Takes an IP address and a port to attempt a socket connection to a server.
     * 
     * @param address
     * @param port
     * @return true if connection was successful
     */
    private boolean connect(String address, int port) {
        try {
            server = new Socket(address, port);
            // channel to send to server
            out = new ObjectOutputStream(server.getOutputStream());
            // channel to listen to server
            in = new ObjectInputStream(server.getInputStream());
            System.out.println("Client connected");
            // Use CompletableFuture to run listenToServer() in a separate thread
            CompletableFuture.runAsync(this::listenToServer);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return isConnected();
    }

    /**
     * <p>
     * Check if the string contains the <i>connect</i> command
     * followed by an IP address and port or localhost and port.
     * </p>
     * <p>
     * Example format: 123.123.123.123:3000
     * </p>
     * <p>
     * Example format: localhost:3000
     * </p>
     * https://www.w3schools.com/java/java_regex.asp
     * 
     * @param text
     * @return true if the text is a valid connection command
     */
    private boolean isConnection(String text) {
        Matcher ipMatcher = ipAddressPattern.matcher(text);
        Matcher localhostMatcher = localhostPattern.matcher(text);
        return ipMatcher.matches() || localhostMatcher.matches();
    }

    /**
     * Controller for handling various text commands.
     * <p>
     * Add more here as needed
     * </p>
     * 
     * @param text
     * @return true if the text was a command or triggered a command
     * @throws IOException
     */

     // UCID: LM87 | Date: 2025-08-09
// Summary: Parse /createroom and /joinroom; build and send the right payload.
    private boolean processClientCommand(String text) throws IOException {
        boolean wasCommand = false;


            // Only treat lines that begin with slash OR known keywords as commands
            boolean looksLikeCommand = text.startsWith(Constants.COMMAND_TRIGGER)
            || text.startsWith("connect ")
            || text.startsWith("name ")
            || text.equalsIgnoreCase("listusers")
            || text.equalsIgnoreCase("quit")
            || text.equalsIgnoreCase("disconnect")
            || text.startsWith("reverse ")
            || text.startsWith("createroom ")
            || text.startsWith("joinroom ")
            || text.startsWith("leave")
            || text.startsWith("pick")
            || text.equalsIgnoreCase("start");

        if (!looksLikeCommand) {
        return false; // let sendMessage(...) handle normal chat
        }

        if (text.startsWith(Constants.COMMAND_TRIGGER)) {
            text = text.substring(1); // remove the /
            // System.out.println("Checking command: " + text);

            // UCID: LM87 | 2025-08-09
            // Summary: Parses /connect host:port and opens the socket; then sends name.
            if (isConnection("/" + text)) {
                if (myUser.getClientName() == null || myUser.getClientName().isEmpty()) {
                    System.out.println(
                            TextFX.colorize("Please set your name via /name <name> before connecting", Color.RED));
                    return true;
                }
                // replaces multiple spaces with a single space
                // splits on the space after connect (gives us host and port)
                // splits on : to get host as index 0 and port as index 1
                String[] parts = text.trim().replaceAll(" +", " ").split(" ")[1].split(":");
                connect(parts[0].trim(), Integer.parseInt(parts[1].trim()));
                sendClientName(myUser.getClientName());// sync follow-up data (handshake)
                wasCommand = true;
            } 
            
             // UCID: LM87 | 2025-08-09
            // Summary: Handles /name <value> and stores it before connecting.
            else if (text.startsWith(Command.NAME.command)) {
                text = text.replace(Command.NAME.command, "").trim();
                if (text == null || text.length() == 0) {
                    System.out.println(TextFX.colorize("This command requires a name as an argument", Color.RED));
                    return true;
                }
                myUser.setClientName(text);// temporary until we get a response from the server
                System.out.println(TextFX.colorize(String.format("Name set to %s", myUser.getClientName()),
                        Color.YELLOW));
                wasCommand = true;
            } else if (text.equalsIgnoreCase(Command.LIST_USERS.command)) {
                System.out.println(TextFX.colorize("Known clients:", Color.CYAN));
                knownClients.forEach((key, value) -> {
                    System.out.println(TextFX.colorize(String.format("%s%s", value.getDisplayName(),
                            key == myUser.getClientId() ? " (you)" : ""), Color.CYAN));
                });
                wasCommand = true;
            } else if (Command.QUIT.command.equalsIgnoreCase(text)) {
                close();
                wasCommand = true;
            } else if (Command.DISCONNECT.command.equalsIgnoreCase(text)) {
                sendDisconnect();
                wasCommand = true;
            } else if (text.startsWith(Command.REVERSE.command)) {
                text = text.replace(Command.REVERSE.command, "").trim();
                sendReverse(text);
                wasCommand = true;
            } 
            
            // UCID: LM87 | 2025-08-09
            // Summary: Parses /createroom and /joinroom then sends ROOM_CREATE/ROOM_JOIN.
            else if (text.startsWith(Command.CREATE_ROOM.command)) {
                text = text.replace(Command.CREATE_ROOM.command, "").trim();
                if (text == null || text.length() == 0) {
                    System.out.println(TextFX.colorize("This command requires a room name as an argument", Color.RED));
                    return true;
                }
                sendRoomAction(text, RoomAction.CREATE);
                wasCommand = true;
            } else if (text.startsWith(Command.JOIN_ROOM.command)) {
                text = text.replace(Command.JOIN_ROOM.command, "").trim();
                if (text == null || text.length() == 0) {
                    System.out.println(TextFX.colorize("This command requires a room name as an argument", Color.RED));
                    return true;
                }
                sendRoomAction(text, RoomAction.JOIN);
                wasCommand = true;
            } else if (text.startsWith(Command.LEAVE_ROOM.command) || text.startsWith("leave")) {
                // Note: Accounts for /leave and /leaveroom variants (or anything beginning with
                // /leave)
                sendRoomAction(text, RoomAction.LEAVE);
                wasCommand = true;
            }

                    // UCID: lm87 | Date: 2025-08-10
            // Summary: Handle /pick <r|p|s>; validate and send as a PICK payload.
            else if (text.startsWith("pick")) {
                String arg = text.replaceFirst("^pick\\s*", "").trim().toLowerCase();
                if (!(arg.equals("r") || arg.equals("p") || arg.equals("s"))) {
                    System.out.println("Usage: pick <r|p|s>");
                    return true;
                }
                Payload p = new Payload();
                p.setPayloadType(PayloadType.PICK);
              
                p.setClientId(myUser.getClientId());
                p.setMessage(arg); // r/p/s

                System.out.println("[DEBUG] sending PICK payload -> " + arg +
                                   " (clientId=" + myUser.getClientId() + ")");
           
                sendToServer(p);

                System.out.println(TextFX.colorize("You picked [" + arg + "]", TextFX.Color.CYAN));
                return true;
            }

            // UCID: lm87 | 2025-08-10
        // Brief: Start a game session in the current room.

            else if (text.equalsIgnoreCase("start")) {
                Payload p = new Payload();
                p.setPayloadType(PayloadType.START);   // see next step
                p.setClientId(myUser.getClientId());

                System.out.println("[DEBUG] sending PICK payload -> " + text + " (clientId=" + myUser.getClientId() + ")");

               
                sendToServer(p);
                System.out.println("Requested session start.");
                return true;
            }
        }
        return wasCommand;
    }

    // Start Send*() methods

    /**
     * Sends a room action to the server
     * 
     * @param roomName
     * @param roomAction (join, leave, create)
     * @throws IOException
     */
    
    // UCID: LM87 | 2025-08-09
// Summary: Builds Payload with room name and ROOM_CREATE/ROOM_JOIN type.
    private void sendRoomAction(String roomName, RoomAction roomAction) throws IOException {
        Payload payload = new Payload();
        payload.setMessage(roomName);
        switch (roomAction) {
            case CREATE:
                payload.setPayloadType(PayloadType.ROOM_CREATE);
                break;
            case JOIN:
                payload.setPayloadType(PayloadType.ROOM_JOIN);
                break;
            case LEAVE:
                payload.setPayloadType(PayloadType.ROOM_LEAVE);
                break;
                
            default:
                System.out.println(TextFX.colorize("Invalid room action", Color.RED));
                break;
        }
        sendToServer(payload);
    }

    /**
     * Sends a reverse message action to the server
     * 
     * @param message
     * @throws IOException
     */
    private void sendReverse(String message) throws IOException {
        Payload payload = new Payload();
        payload.setMessage(message);
        payload.setPayloadType(PayloadType.REVERSE);
        sendToServer(payload);

    }

    /**
     * Sends a disconnect action to the server
     * 
     * @throws IOException
     */


     // UCID: LM87 | 2025-08-09
// Summary: Builds a DISCONNECT payload and sends it to the server to request manual disconnection. 
    private void sendDisconnect() throws IOException {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.DISCONNECT);
        sendToServer(payload);
    }

    /**
     * Sends a message to the server
     * 
     * @param message
     * @throws IOException
     */

     // UCID: LM87 | 2025-08-09
// Summary: Wraps text in PayloadType.MESSAGE and writes to socket.
    private void sendMessage(String message) throws IOException {
        Payload payload = new Payload();
        payload.setMessage(message);
        payload.setPayloadType(PayloadType.MESSAGE);
        sendToServer(payload);
    }

    /**
     * Sends the client's name to the server (what the user desires to be called)
     * 
     * @param name
     * @throws IOException
     */
    private void sendClientName(String name) throws IOException {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setClientName(name);
        payload.setPayloadType(PayloadType.CLIENT_CONNECT);
        sendToServer(payload);
    }

    private void sendToServer(Payload payload) throws IOException {
        if (isConnected()) {
            out.writeObject(payload);
            out.flush(); // good practice to ensure data is written out immediately
        } else {
            System.out.println(
                    "Not connected to server (hint: type `/connect host:port` without the quotes and replace host/port with the necessary info)");
        }
    }
    // End Send*() methods

    public void start() throws IOException {
        System.out.println("Client starting");

        // Use CompletableFuture to run listenToInput() in a separate thread
        CompletableFuture<Void> inputFuture = CompletableFuture.runAsync(this::listenToInput);

        // Wait for inputFuture to complete to ensure proper termination
        inputFuture.join();
    }

    /**
     * Listens for messages from the server
     */
    private void listenToServer() {
        try {
            while (isRunning && isConnected()) {
                Payload fromServer = (Payload) in.readObject(); // blocking read
                if (fromServer != null) {
                    processPayload(fromServer);

                } else {
                    System.out.println("Server disconnected");
                    break;
                }
            }
        } catch (ClassCastException | ClassNotFoundException cce) {
            System.err.println("Error reading object as specified type: " + cce.getMessage());
            cce.printStackTrace();
        } catch (IOException e) {
            if (isRunning) {
                System.out.println("Connection dropped");
                e.printStackTrace();
            }
        } finally {
            closeServerConnection();
        }
        System.out.println("listenToServer thread stopped");
    }

    private void processPayload(Payload payload) {
        switch (payload.getPayloadType()) {
            case CLIENT_CONNECT:// unused
                break;
            case CLIENT_ID:
                processClientData(payload);
                break;
            case DISCONNECT:
                processDisconnect(payload);
                break;
            case MESSAGE:
                processMessage(payload);
                break;
            case REVERSE:
                processReverse(payload);
                break;
            case ROOM_CREATE: // unused
                break;
            case ROOM_JOIN:
                processRoomAction(payload);
                break;
            case ROOM_LEAVE:
                processRoomAction(payload);
                break;
            case SYNC_CLIENT:
                processRoomAction(payload);
                break;
                // UCID: lm87 | Date: 2025-08-10
            case POINTS_SYNC:
            processPointsSync(payload);
            break;
            case ROOMS_SYNC:
            if (payload instanceof Common.RoomsPayload) {
                Common.RoomsPayload rp = (Common.RoomsPayload) payload;
                knownRooms.clear();
                if (rp.getRooms() != null) knownRooms.addAll(rp.getRooms());
                // optional: System.out.println("Rooms: " + knownRooms);
            }
            break;

            case USER_LIST: {
                if (payload instanceof Common.UserListPayload) {
                    Common.UserListPayload up = (Common.UserListPayload) payload;
                    if (up.getPoints() != null) { pointsMap.clear(); pointsMap.putAll(up.getPoints()); }
                    if (up.getEliminated() != null) { eliminatedMap.clear(); eliminatedMap.putAll(up.getEliminated()); }
                    if (up.getPending() != null) { pendingMap.clear(); pendingMap.putAll(up.getPending()); }
                    // UI panels poll snapshots, so no extra call needed
                    if (up.getAway() != null) { awayMap.clear(); awayMap.putAll(up.getAway()); }
                }

                
                
                break;
            }
            
            default:
                System.out.println(TextFX.colorize("Unhandled payload type: " + payload.getPayloadType(), Color.YELLOW));
                break;

        }
    }
    public synchronized boolean uiAmAway() {
        User me = uiGetMyUser();
        if (me == null) return false;
        return awayMap.getOrDefault(me.getClientId(), false);
    }
    
    public synchronized void uiToggleAway() {
        boolean next = !uiAmAway();
        Common.Payload p = new Common.Payload();
        p.setPayloadType(Common.PayloadType.MESSAGE);
        p.setClientId(myUser.getClientId());
        p.setMessage("[AWAY] " + (next ? "1" : "0"));
        try {
            sendToServer(p);
        } catch (java.io.IOException e) {
            e.printStackTrace();
            // Optionally show a dialog or log the error
        }
    }

    // Start process*() methods
    private void processClientData(Payload payload) {
        if (myUser.getClientId() != Constants.DEFAULT_CLIENT_ID) {
            System.out.println(TextFX.colorize("Client ID already set, this shouldn't happen", Color.YELLOW));

        }
        myUser.setClientId(payload.getClientId());
        myClientId = payload.getClientId();
        myUser.setClientName(((ConnectionPayload) payload).getClientName());// confirmation from Server
        knownClients.put(myUser.getClientId(), myUser);
        System.out.println(TextFX.colorize("Connected", Color.GREEN));
    }

        // UCID: lm87 | Date: 2025-08-10
    // Brief: Show scoreboard sync pushed by the server.
    private void processPointsSync(Payload payload) {
        if (payload instanceof PointsPayload) {
            PointsPayload pp = (PointsPayload) payload;
            java.util.Map<Long, Integer> incoming = pp.getPointsByClientId();
            if (incoming != null) {
                pointsMap.clear();
                pointsMap.putAll(incoming);
            }
            System.out.println(TextFX.colorize("[POINTS_SYNC] " + pp.getPointsByClientId(), Color.CYAN));
        } else {
            System.out.println(TextFX.colorize("[POINTS_SYNC] " + payload, Color.CYAN));
        }
}

    private void processDisconnect(Payload payload) {
        if (payload.getClientId() == myUser.getClientId()) {
            knownClients.clear();
            readyMap.clear();
            myUser.reset();
            readyMap.clear();
            pendingMap.clear();
            eliminatedMap.clear();
            pointsMap.clear();
            System.out.println(TextFX.colorize("You disconnected", Color.RED));
        } else if (knownClients.containsKey(payload.getClientId())) {
            User disconnectedUser = knownClients.remove(payload.getClientId());
            if (disconnectedUser != null) {
                System.out.println(TextFX.colorize(String.format("%s disconnected", disconnectedUser.getDisplayName()),
                        Color.RED));
            }
        }

    }

    // UCID: LM87 | 2025-08-09
    // Summary: Prints join message and syncs known clients on ROOM_JOIN/SYNC_CLIENT.
    private void processRoomAction(Payload payload) {
        if (!(payload instanceof ConnectionPayload)) {
            error("Invalid payload subclass for processRoomAction");
            return;
        }
        ConnectionPayload connectionPayload = (ConnectionPayload) payload;
        // use DEFAULT_CLIENT_ID to clear knownClients (mostly for disconnect and room
        // transitions)
        if (connectionPayload.getClientId() == Constants.DEFAULT_CLIENT_ID) {
            knownClients.clear();
            return;
        }
        switch (connectionPayload.getPayloadType()) {

            case ROOM_LEAVE:
                // remove from map
                if (knownClients.containsKey(connectionPayload.getClientId())) {
                    knownClients.remove(connectionPayload.getClientId());
                }
                readyMap.remove(connectionPayload.getClientId()); 

                readyMap.remove(connectionPayload.getClientId());
                pendingMap.remove(connectionPayload.getClientId());
                eliminatedMap.remove(connectionPayload.getClientId());
                pointsMap.remove(connectionPayload.getClientId());

                if (connectionPayload.getMessage() != null) {
                    System.out.println(TextFX.colorize(connectionPayload.getMessage(), Color.YELLOW));
                }

                break;
            case ROOM_JOIN:
                if (connectionPayload.getMessage() != null) {
                    System.out.println(TextFX.colorize(connectionPayload.getMessage(), Color.GREEN));
                }
                // cascade to manage knownClients
            case SYNC_CLIENT:
                // add to map
                if (!knownClients.containsKey(connectionPayload.getClientId())) {
                    User user = new User();
                    user.setClientId(connectionPayload.getClientId());
                    user.setClientName(connectionPayload.getClientName());
                    knownClients.put(connectionPayload.getClientId(), user);
                }
                break;
            default:
                error("Invalid payload type for processRoomAction");
                break;
        }
    }

    private void processMessage(Payload payload) {
        String msg = payload.getMessage();
        if (msg != null) {
            // Robust READY parser:
            // works for formats like:
            //   "[READY] 2 1"
            //   "alice: [READY] 2 1"
            //   "Room[lobby] alice: [READY] 2 1"

                    java.util.regex.Matcher mCd = java.util.regex.Pattern
                    .compile("\\[SETTINGS\\]\\s+COOLDOWN\\s+(\\S+)")
                    .matcher(msg);
                if (mCd.find()) {
                    cooldownEnabled = "1".equals(mCd.group(1)) || Boolean.parseBoolean(mCd.group(1));
                    return;
                }
            // Robust parser: allow prefixes like "Room[x] alice: [SETTINGS] EXTRA_CHOICES true FULL"
                java.util.regex.Matcher mSet = java.util.regex.Pattern
                .compile("\\[SETTINGS\\]\\s+EXTRA_CHOICES\\s+(\\S+)\\s+(\\S+)")
                .matcher(msg);
                if (mSet.find()) {
                String enabledStr = mSet.group(1);
                String modeStr    = mSet.group(2);
                try {
                extraChoicesEnabled = "1".equals(enabledStr) || Boolean.parseBoolean(enabledStr);
                extraChoicesMode = modeStr;
                // Optional: small toast/log
                addEvent("Extra choices: " + extraChoicesEnabled + " (" + extraChoicesMode + ")");
                } catch (Exception ignored) {}
                return; // do not echo control messages as chat
                }

                if (msg.startsWith("[EXTRA_CHOICES]")) {
                    String[] parts = msg.split("\\s+");
                    if (parts.length >= 3) {
                        boolean en  = "1".equals(parts[1]) || Boolean.parseBoolean(parts[1]);
                        String mode = (parts.length >= 3) ? parts[2] : "FULL";
                        applyExtraChoicesFromServer(en, mode);   // <-- apply locally, DO NOT send
                    }
                    return;
                }

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\[READY\\]\\s+(\\d+)\\s+(\\d)")
                    .matcher(msg);
            if (m.find()) {
                try {
                    long id = Long.parseLong(m.group(1));
                    boolean val = "1".equals(m.group(2));
                    readyMap.put(id, val);
                                addEvent("Round started: " + roundDurationSec + "s");


                } catch (Exception ignored) {}

               



                return; // don't print READY control messages to chat
            }
            java.util.regex.Matcher mPend = java.util.regex.Pattern
                .compile("\\[PENDING\\]\\s+(\\d+)\\s+(\\d)")
                .matcher(msg);
        if (mPend.find()) {
            long id = Long.parseLong(mPend.group(1));
            boolean val = "1".equals(mPend.group(2));
            pendingMap.put(id, val);
            return;
        }

        if (msg.endsWith(" is away") || msg.endsWith(" is no longer away")) {
    addEvent(msg);
    // return; // not required; you can also still print it to console
}

        java.util.regex.Matcher mElim = java.util.regex.Pattern
                .compile("\\[ELIM\\]\\s+(\\d+)\\s+(\\d)")
                .matcher(msg);
        if (mElim.find()) {
            long id = Long.parseLong(mElim.group(1));
            boolean val = "1".equals(mElim.group(2));
            eliminatedMap.put(id, val);
            if (val) pendingMap.put(id, false); // eliminated can't be pending
            return;
        }


        java.util.regex.Matcher mRS = java.util.regex.Pattern
        .compile("\\[ROUND_START\\]\\s+(\\d+)")
        .matcher(msg);
        if (mRS.find()) {
            roundDurationSec = Integer.parseInt(mRS.group(1));
            roundEndEpochMs = System.currentTimeMillis() + (long)roundDurationSec * 1000L;
            addEvent("Round started: " + roundDurationSec + "s");

            uiSelectedPick = null;
            return; // don't echo this as chat
        }

        if(msg.startsWith("Round ") && msg.contains("ending")){
            uiLastRoundPick = uiSelectedPick;  
        }

        // Common signals produced by your GameRoom:
        if (msg.contains("picked their choice.")
            || msg.startsWith("Eliminated:")
            || msg.contains("did not pick and is eliminated")
            || msg.startsWith("Round ")    // start/end lines
            || msg.startsWith("Game over!")
            || msg.startsWith("[SCOREBOARD]")
            || msg.startsWith("[FINAL]")) {
            addEvent(msg);
        }

        }
        System.out.println(TextFX.colorize(msg, Color.BLUE));
    }

    private void processReverse(Payload payload) {
        System.out.println(TextFX.colorize(payload.getMessage(), Color.PURPLE));
    }

    public boolean uiIsHost() {
        // TEMP: assume first user in list is host
        if (!knownClients.isEmpty()) {
            long firstId = knownClients.keySet().iterator().next();
            return firstId == myClientId;
        }
        return false;
    }

    public boolean uiAllReady() {
    if (readyMap.isEmpty()) return false;
    // consider only known clients in room
    for (Long id : readyMap.keySet()) {
        if (!Boolean.TRUE.equals(readyMap.get(id))) return false;
    }
    return true;
}
    // End process*() methods

    /**
     * Listens for keyboard input from the user
     */
    private void listenToInput() {
        try (Scanner si = new Scanner(System.in)) {
            System.out.println("Waiting for input"); // moved here to avoid console spam
            while (isRunning && si.hasNextLine()) { // Run until isRunning is false

                
                String userInput = si.nextLine();
                if (!processClientCommand(userInput)) {

                    if (userInput.startsWith(Constants.COMMAND_TRIGGER)) {
                        System.out.println("Unknown command: " + userInput);
                    }
                    else{
                        sendMessage(userInput);

                    }
                }
            }
        } catch (IOException ioException) {
            System.out.println("Error in listentToInput()");
            ioException.printStackTrace();
        }
        System.out.println("listenToInput thread stopped");
    }

    /**
     * Closes the client connection and associated resources
     */
    private void close() {
        isRunning = false;
        closeServerConnection();
        System.out.println("Client terminated");
        // System.exit(0); // Terminate the application
    }

    /**
     * Closes the server connection and associated resources
     */

     // UCID: LM87 | 2025-08-09
     // Summary: Closes the out/in/socket with logs
    private void closeServerConnection() {
        try {
            if (out != null) {
                System.out.println("Closing output stream");
                out.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (in != null) {
                System.out.println("Closing input stream");
                in.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (server != null) {
                System.out.println("Closing connection");
                server.close();
                System.out.println("Closed socket");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

        private String uiLastHost = null;
        private int uiLastPort = -1;

        public synchronized boolean uiConnect(String address, int port) {
            this.uiLastHost = address;
            this.uiLastPort = port;
            boolean ok = connect(address, port);
            if (ok && myUser != null && myUser.getClientName() != null && !myUser.getClientName().isEmpty()) {
                try {
                    // send name once after a new connection is established
                    sendClientName(myUser.getClientName());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return ok;
        }

        public synchronized void uiSetName(String name) throws java.io.IOException {
            myUser.setClientName(name);
        }

        public synchronized User uiGetMyUser() {
            return myUser;
        }

        // public helper to allow UI to disconnect cleanly
        public void shutdown() {
            // mirror what close() does, but public
            isRunning = false;
            closeServerConnection();
            System.out.println("Client terminated (via UI)");
        }


        public synchronized String uiGetLastHost() { return uiLastHost; }
        public synchronized int uiGetLastPort() { return uiLastPort; }

        // === UI Ready helpers (Milestone 3) ===
        // UCID: LM87 | Date: 2025-08-11
        public synchronized void uiToggleReady(boolean ready) {
            long id = myUser.getClientId();
            if (id == Common.Constants.DEFAULT_CLIENT_ID) return;
            readyMap.put(id, ready);
            try {
                // broadcast via normal MESSAGE channel so all clients can parse
                sendMessage("[READY] " + id + " " + (ready ? "1" : "0"));
            } catch (Exception ignored) {}
        }

        public synchronized java.util.Map<Long, Boolean> uiGetReadySnapshot() {
            return new java.util.HashMap<>(readyMap);
        }

        public synchronized java.util.Map<Long, User> uiGetKnownClientsSnapshot() {
            return new java.util.HashMap<>(knownClients);
        }

        public synchronized void uiSetCooldown(boolean enabled) {   // NEW
            if (this.cooldownEnabled == enabled) return; // dedupe
            this.cooldownEnabled = enabled;
            try {
                Payload p = new Payload();
                p.setPayloadType(PayloadType.MESSAGE);
                p.setClientId(myUser.getClientId());
                p.setMessage("[SETTINGS] COOLDOWN " + enabled);
                sendToServer(p);
            } catch (Exception ignored) {}
        }

    public static void main(String[] args) {
        Client client = Client.INSTANCE;
        try {
            client.start();
        } catch (IOException e) {
            System.out.println("Exception from main()");
            e.printStackTrace();
        }
    }

    
}
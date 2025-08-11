package Server;

import Common.*;
import java.net.Socket;
import java.util.Objects;
import java.util.function.Consumer;
import Common.TextFX.Color;

/**
 * A server-side representation of a single client
 */
public class ServerThread extends BaseServerThread {
    private Consumer<ServerThread> onInitializationComplete; // callback to inform when this object is ready

    /**
     * A wrapper method so we don't need to keep typing out the long/complex sysout
     * line inside
     * 
     * @param message
     */
    protected void info(String message) {
        System.out.println(TextFX.colorize(String.format("Thread[%s]: %s", this.getClientId(), message), Color.CYAN));
    }

    /**
     * Wraps the Socket connection and takes a Server reference and a callback
     * 
     * @param myClient
     * @param server
     * @param onInitializationComplete method to inform listener that this object is
     *                                 ready
     */
    protected ServerThread(Socket myClient, Consumer<ServerThread> onInitializationComplete) {
        Objects.requireNonNull(myClient, "Client socket cannot be null");
        Objects.requireNonNull(onInitializationComplete, "callback cannot be null");
        info("ServerThread created");
        // get communication channels to single client
        this.client = myClient;
        // this.clientId = this.threadId(); // An id associated with the thread
        // instance, used as a temporary identifier
        this.onInitializationComplete = onInitializationComplete;

    }

    // Start Send*() Methods
    protected boolean sendDisconnect(long clientId) {
        Payload payload = new Payload();
        payload.setClientId(clientId);
        payload.setPayloadType(PayloadType.DISCONNECT);
        return sendToClient(payload);
    }

    protected boolean sendResetUserList() {
        return sendClientInfo(Constants.DEFAULT_CLIENT_ID, null, RoomAction.JOIN);
    }

    /**
     * Syncs Client Info (id, name, join status) to the client
     * 
     * @param clientId   use -1 for reset/clear
     * @param clientName
     * @param action     RoomAction of Join or Leave
     * @return true for successful send
     */
    protected boolean sendClientInfo(long clientId, String clientName, RoomAction action) {
        return sendClientInfo(clientId, clientName, action, false);
    }

    /**
     * Syncs Client Info (id, name, join status) to the client
     * 
     * @param clientId   use -1 for reset/clear
     * @param clientName
     * @param action     RoomAction of Join or Leave
     * @param isSync     True is used to not show output on the client side (silent
     *                   sync)
     * @return true for successful send
     */
    protected boolean sendClientInfo(long clientId, String clientName, RoomAction action, boolean isSync) {
        ConnectionPayload payload = new ConnectionPayload();
        switch (action) {
            case JOIN:
                payload.setPayloadType(PayloadType.ROOM_JOIN);
                break;
            case LEAVE:
                payload.setPayloadType(PayloadType.ROOM_LEAVE);
                break;
            default:
                break;
        }
        if (isSync) {
            payload.setPayloadType(PayloadType.SYNC_CLIENT);
        }
        payload.setClientId(clientId);
        payload.setClientName(clientName);
        return sendToClient(payload);
    }

    /**
     * Sends this client's id to the client.
     * This will be a successfully connection handshake
     * 
     * @return true for successful send
     */
    protected boolean sendClientId() {
        ConnectionPayload payload = new ConnectionPayload();
        payload.setPayloadType(PayloadType.CLIENT_ID);
        payload.setClientId(getClientId());
        payload.setClientName(getClientName());// Can be used as a Server-side override of username (i.e., profanity
                                               // filter)
        return sendToClient(payload);
    }

    /**
     * Sends a message to the client
     * 
     * @param clientId who it's from
     * @param message
     * @return true for successful send
     */
    protected boolean sendMessage(long clientId, String message) {
        Payload payload = new Payload();
        payload.setPayloadType(PayloadType.MESSAGE);
        payload.setMessage(message);
        payload.setClientId(clientId);
        return sendToClient(payload);
    }

        // UCID: lm87 | Date: 2025-08-10
    // Brief: Convenience wrapper so other classes (e.g., GameRoom) can send any payload via ServerThread.
    public boolean sendPayload(Common.Payload payload) {
        // BaseServerThread has the actual socket writer
        return sendToClient(payload);
    }

        // UCID: lm87 | Date: 2025-08-10
    // Brief: Overload to send a plain server message without specifying a sender id.
    public boolean sendMessage(String message) {
        // DEFAULT_CLIENT_ID means “server/system” in this codebase
        return sendMessage(Common.Constants.DEFAULT_CLIENT_ID, message);
    }

        // UCID: lm87 | Date: 2025-08-10
    // Brief: Sync a full scoreboard to this client using PointsPayload (Milestone 2).
    public boolean sendPoints(java.util.Map<Long, Integer> pointsByClientId, String reason) {
        Common.PointsPayload pp = new Common.PointsPayload();
        pp.setPayloadType(Common.PayloadType.POINTS_SYNC);
        pp.setClientId(getClientId());  // not required for all uses, but fine to include
        pp.setMessage(reason);
        pp.setPointsByClientId(pointsByClientId);
        return sendToClient(pp);
    }

    // End Send*() Methods
    @Override
    protected void processPayload(Payload incoming) {

        switch (incoming.getPayloadType()) {
            case CLIENT_CONNECT:
                setClientName(((ConnectionPayload) incoming).getClientName().trim());

                break;
            case DISCONNECT:
                currentRoom.handleDisconnect(this);
                break;
            case MESSAGE:
                currentRoom.handleMessage(this, incoming.getMessage());
                break;
            case REVERSE:
                currentRoom.handleReverseText(this, incoming.getMessage());
                break;
            case ROOM_CREATE:
                currentRoom.handleCreateRoom(this, incoming.getMessage());
                break;
            case ROOM_JOIN:
                currentRoom.handleJoinRoom(this, incoming.getMessage());
                break;
            case ROOM_LEAVE:
                currentRoom.handleJoinRoom(this, Room.LOBBY);
                break;

                // UCID: lm87 | Date: 2025-08-10
    // Brief: Route PICK to the room so GameRoom can record the choice.
                case PICK:
                System.out.println("Thread[" + getId() + "] PICK <- " + getClientName()
                + " choice=" + incoming.getMessage());
                currentRoom.handlePick(this, incoming.getMessage()); // "r","p","s"
                break;

                case START:
                if (currentRoom instanceof GameRoom) {
                    System.out.println("Thread[" + getId() + "]: START requested by " + getClientName());
                    ((GameRoom) currentRoom).onSessionStart();  // make this method public if needed
                } else {
                    sendMessage("This command only works in a GameRoom.");
                }
                break;
                case GAME_SETTING: {
                    // forward to the current room if it’s a GameRoom
                    Room room = getCurrentRoom(); // or however you retrieve it
                    if (room instanceof GameRoom) {
                        ((GameRoom) room).applyGameSetting(this, incoming.getMessage());
                    } else {
                        sendMessage("Settings not supported in this room.");
                    }
                    break;
                }

            default:
                System.out.println(TextFX.colorize("Unknown payload type received", Color.RED));
                break;
        }
    }

    /**
 * UCID: LM87 | Date: 2025-08-11
 * Summary: Sends User List to refreshen the UI view. 
 * 
 */
    public void sendUserList(java.util.Map<Long,Integer> points,
    java.util.Map<Long,Boolean> eliminated,
    java.util.Map<Long,Boolean> pending) {
    // Back-compat: delegate to 4-arg overload
    sendUserList(points, eliminated, pending, null,null);
    }

    public void sendUserList(java.util.Map<Long,Integer> points,
    java.util.Map<Long,Boolean> eliminated,
    java.util.Map<Long,Boolean> pending,
    java.util.Map<Long,Boolean> away,
    java.util.Map<Long,Boolean> spectators ) {

    Common.UserListPayload up = new Common.UserListPayload();
    up.setPayloadType(Common.PayloadType.USER_LIST);
    up.setClientId(getClientId());
    up.setPoints(points);
    up.setEliminated(eliminated);
    up.setPending(pending);
    if (away != null) up.setAway(away);
    if (spectators != null) up.setSpectators(spectators);
    send(up);
    }

    public void send(Common.Payload payload) {
        try {
            out.writeObject(payload);
            out.flush();
        } catch (Exception e) {
            System.err.println("send(payload) failed for " + getDisplayName() + ": " + e.getMessage());
        }
    }

   

    @Override
    protected void onInitialized() {
        // once receiving the desired client name the object is ready
        onInitializationComplete.accept(this);
    }
}
package Project.server;

import Project.common.ConnectionPayload;
import Project.common.LoggerUtil;
import Project.common.Payload;
import Project.common.PayloadType;
import Project.common.RollPayload;
import Project.common.RoomResultsPayload;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A server-side representation of a single client.
 * This class is more about the data and abstracted communication
 */
public class ServerThread extends BaseServerThread {
    public static final long DEFAULT_CLIENT_ID = -1;
    private Room currentRoom;
    private long clientId;
    private String clientName;
    private Consumer<ServerThread> onInitializationComplete; // callback to inform when this object is ready

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
        this.clientId = ServerThread.DEFAULT_CLIENT_ID;// this is updated later by the server
        this.onInitializationComplete = onInitializationComplete;
    }

    public void setClientName(String name) {
        if (name == null) {
            throw new NullPointerException("Client name can't be null");
        }
        this.clientName = name;
        onInitialized();
    }

    public String getClientName() {
        return clientName;
    }

    public long getClientId() {
        return this.clientId;
    }

    protected Room getCurrentRoom() {
        return this.currentRoom;
    }

    protected void setCurrentRoom(Room room) {
        if (room == null) {
            throw new NullPointerException("Room argument can't be null");
        }
        currentRoom = room;
    }

    @Override
    protected void onInitialized() {
        loadMuteList(); // Load the mute list when the client initializes
        onInitializationComplete.accept(this); // Notify server that initialization is complete
    }

    @Override
    protected void info(String message) {
        LoggerUtil.INSTANCE.info(String.format("ServerThread[%s(%s)]: %s", getClientName(), getClientId(), message));
    }

    @Override
    protected void cleanup() {
        currentRoom = null;
        super.cleanup();
    }

    @Override
    protected void disconnect() {
        super.disconnect();
    }

    // handle received message from the Client
    @Override
    protected void processPayload(Payload payload) {
        try {
            switch (payload.getPayloadType()) {
                case CLIENT_CONNECT:
                    ConnectionPayload cp = (ConnectionPayload) payload;
                    setClientName(cp.getClientName());
                    break;
                case MESSAGE:
                    if (!isClientMuted(payload.getClientId())) { // jah89 07-20-2024
                        currentRoom.sendMessage(this, payload.getMessage());
                    } else {
                        info("Message from " + payload.getClientId() + " skipped due to being muted."); // jah89 07-20-2024
                    }
                    break;
                case ROOM_CREATE:
                    currentRoom.handleCreateRoom(this, payload.getMessage());
                    break;
                case ROOM_JOIN:
                    currentRoom.handleJoinRoom(this, payload.getMessage());
                    break;
                case ROOM_LIST:
                    currentRoom.handleListRooms(this, payload.getMessage());
                    break;
                case DISCONNECT:
                    currentRoom.disconnect(this);
                    break;
                case ROLL:
                    RollPayload rollPayload = (RollPayload) payload;    //jah89 07/04/2024
                    currentRoom.processRollCommand(this, rollPayload);
                    break;
                case FLIP:
                    currentRoom.processFlipCommand(this, payload);
                    break;
                case MUTE: // jah89 07-20-2024
                    currentRoom.handleMute(clientId, payload.getMessage());
                    break;
                case UNMUTE: // jah89 07-20-2024
                    currentRoom.handleUnmute(clientId, payload.getMessage());
                    break;
                case PRIVATE_MESSAGE:  //jah89 07-20-2024
                    long targetId = payload.getClientId();
                    String privateMessage = payload.getMessage();
                    currentRoom.sendPrivateMessage(this, targetId, privateMessage);
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            LoggerUtil.INSTANCE.severe("Could not process Payload: " + payload, e);
        }
    }

    // send methods to pass data back to the Client

    public boolean sendRooms(List<String> rooms) {
        RoomResultsPayload rrp = new RoomResultsPayload();
        rrp.setRooms(rooms);
        return send(rrp);
    }

    public boolean sendClientSync(long clientId, String clientName) {
        ConnectionPayload cp = new ConnectionPayload();
        cp.setClientId(clientId);
        cp.setClientName(clientName);
        cp.setConnect(true);
        cp.setPayloadType(PayloadType.SYNC_CLIENT);
        return send(cp);
    }

    /**
     * Overload of sendMessage used for server-side generated messages
     * 
     * @param message
     * @return @see {@link #send(Payload)}
     */
    public boolean sendMessage(String message) {
        return sendMessage(ServerThread.DEFAULT_CLIENT_ID, message);
    }

    /**
     * Sends a message with the author/source identifier
     * 
     * @param senderId
     * @param message
     * @return @see {@link #send(Payload)}
     */
    public boolean sendMessage(long senderId, String message) {
        if (isClientMuted(senderId)) { // jah89 07-20-2024
            info("Message from " + senderId + " skipped due to being muted."); // log message
            return true;
        }
        Payload p = new Payload();
        p.setClientId(senderId);
        p.setMessage(message);
        p.setPayloadType(PayloadType.MESSAGE);
        return send(p);
    }

    /**
     * Tells the client information about a client joining/leaving a room
     * 
     * @param clientId   their unique identifier
     * @param clientName their name
     * @param room       the room
     * @param isJoin     true for join, false for leaivng
     * @return success of sending the payload
     */
    public boolean sendRoomAction(long clientId, String clientName, String room, boolean isJoin) {
        ConnectionPayload cp = new ConnectionPayload();
        cp.setPayloadType(PayloadType.ROOM_JOIN);
        cp.setConnect(isJoin); // <-- determine if join or leave
        cp.setMessage(room);
        cp.setClientId(clientId);
        cp.setClientName(clientName);
        return send(cp);
    }

    /**
     * Tells the client information about a disconnect (similar to leaving a room)
     * 
     * @param clientId   their unique identifier
     * @param clientName their name
     * @return success of sending the payload
     */
    public boolean sendDisconnect(long clientId, String clientName) {
        ConnectionPayload cp = new ConnectionPayload();
        cp.setPayloadType(PayloadType.DISCONNECT);
        cp.setConnect(false);
        cp.setClientId(clientId);
        cp.setClientName(clientName);
        return send(cp);
    }

    /**
     * Sends (and sets) this client their id (typically when they first connect)
     * 
     * @param clientId
     * @return success of sending the payload
     */
    public boolean sendClientId(long clientId) {
        this.clientId = clientId;
        ConnectionPayload cp = new ConnectionPayload();
        cp.setPayloadType(PayloadType.CLIENT_ID);
        cp.setConnect(true);
        cp.setClientId(clientId);
        cp.setClientName(clientName);
        return send(cp);
    }

    // jah89 07-20-2024
    private Map<Long, String> mutedClients = new HashMap<>();

    public void addMutedClient(long clientId) {
        if (!isClientMuted(clientId)) { // Only proceed if the client is not already muted
            mutedClients.put(clientId, currentRoom.getClient(clientId).getClientName());
            saveMuteList(); // Save the mute list after adding a client
            ServerThread target = currentRoom.getClient(clientId);
            if (target != null) {
                target.sendMessage(clientName + " muted you.");
            }
            sendMuteStatusUpdate(clientId, true); // Send mute status update to the client
        } else {
            // Send message indicating the client is already muted
            sendMessage(this.clientId, "User " + currentRoom.getClient(clientId).getClientName() + " is already muted."); 
            info("Client " + clientId + " is already muted."); // Log message indicating the client is already muted
        }
    }
    
    public void removeMutedClient(long clientId) {
        if (isClientMuted(clientId)) { // Only proceed if the client is currently muted
            mutedClients.remove(clientId);
            saveMuteList(); // Save the mute list after removing a client
            ServerThread target = currentRoom.getClient(clientId);
            if (target != null) {
                target.sendMessage(clientName + " unmuted you.");
            }
            sendMuteStatusUpdate(clientId, false); // Send mute status update to the client
        } else {
            // Send message indicating the client is not muted
            sendMessage(this.clientId, "User " + currentRoom.getClient(clientId).getClientName() + " is not muted."); 
            info("Client " + clientId + " is not muted."); // Log message indicating the client is not muted
        }
    }
    
    private void sendMuteStatusUpdate(long clientId, boolean isMuted) { //jah89 07-20-2024
        Payload p = new Payload();
        p.setClientId(clientId);
        p.setMessage(isMuted ? "MUTED" : "UNMUTED");
        p.setPayloadType(PayloadType.MUTE_STATUS);
        send(p);
    }
    
    public boolean isClientMuted(long clientId) {
        return mutedClients.containsKey(clientId);
    }

    // jah89 07-26-2024
    private void saveMuteList() {
        try {
            File file = new File("mutelist_" + clientName + ".txt");
            BufferedWriter writer = new BufferedWriter(new FileWriter(file));
            for (Map.Entry<Long, String> entry : mutedClients.entrySet()) {
                writer.write(entry.getKey() + ":" + entry.getValue());
                writer.newLine();
            }
            writer.close();
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Error saving mute list for " + clientName, e);
        }
    }

    private void loadMuteList() {  //jah89 07-27-2014
        try {
            File file = new File("mutelist_" + clientName + ".txt");
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) {
                        mutedClients.put(Long.parseLong(parts[0]), parts[1]);
                    }
                }
                reader.close();
            }
        } catch (IOException e) {
            LoggerUtil.INSTANCE.severe("Error loading mute list for " + clientName, e);
        }
    }
}

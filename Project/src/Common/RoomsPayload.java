package Common;

import java.io.Serializable;
import java.util.List;

public class RoomsPayload extends Payload implements Serializable {
    private static final long serialVersionUID = 1L;
    private java.util.List<String> rooms;

    public void setRooms(java.util.List<String> rooms) { this.rooms = rooms; }
    public java.util.List<String> getRooms() { return rooms; }

    @Override
    public String toString() {
        return "RoomsPayload{rooms=" + rooms + "}";
    }
}

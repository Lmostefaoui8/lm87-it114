// UCID: lm87 | Date: 2025-08-10
// Brief: Base payload for client/server messages. Carries type, client id, and message text.
package Common;

import java.io.Serializable;

public class Payload implements Serializable {
    private static final long serialVersionUID = 1L;

    private PayloadType payloadType;
    private long clientId;
    private String message;

    public PayloadType getPayloadType() { return payloadType; }
    public void setPayloadType(PayloadType payloadType) { this.payloadType = payloadType; }

    public long getClientId() { return clientId; }
    public void setClientId(long clientId) { this.clientId = clientId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    @Override
    public String toString() {
        // Matches the lesson-style debug
        return String.format("Payload[%s] Client Id [%s] Message: [%s]",
                getPayloadType(), getClientId(), getMessage());
    }
}

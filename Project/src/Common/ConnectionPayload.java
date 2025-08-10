// UCID: lm87 | Date: 2025-08-10
// Brief: Carries connection-related data (e.g., client display name) in addition to base payload fields.
package Common;

public class ConnectionPayload extends Payload {
    private String clientName;

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    @Override
    public String toString() {
        return super.toString() + String.format(" clientName=\"%s\"", clientName);
    }
}
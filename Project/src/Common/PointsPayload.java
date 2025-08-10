// UCID: lm87 | Date: 2025-08-10
// Summary: Extends Payload to include a per-client scoreboard map for syncing points to all clients.
package Common;

import java.util.Map;

public class PointsPayload extends Payload {
    private Map<Long, Integer> pointsByClientId;

    public Map<Long, Integer> getPointsByClientId() { return pointsByClientId; }
    public void setPointsByClientId(Map<Long, Integer> pointsByClientId) { this.pointsByClientId = pointsByClientId; }

    @Override
    public String toString() {
        return super.toString() + String.format(" points=%s", pointsByClientId);
    }
}
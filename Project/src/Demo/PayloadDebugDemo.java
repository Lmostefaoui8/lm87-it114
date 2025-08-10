// UCID: lm87 | Date: 2025-08-10
// Brief: Quick console demo to print payload debug strings for worksheet screenshots.
package Demo;

import Common.Payload;
import Common.PayloadType;
import Common.ConnectionPayload;
import Common.PointsPayload;

import java.util.LinkedHashMap;
import java.util.Map;

public class PayloadDebugDemo {
    public static void main(String[] args) {
        // Base Payload
        Payload base = new Payload();
        base.setPayloadType(PayloadType.MESSAGE);
        base.setClientId(1234L);
        base.setMessage("Hello from demo");
        System.out.println(base);

        // ConnectionPayload
        ConnectionPayload cp = new ConnectionPayload();
        cp.setPayloadType(PayloadType.CLIENT_CONNECT);
        cp.setClientId(5678L);
        cp.setMessage("Requesting connection");
        cp.setClientName("Lamia");
        System.out.println(cp);

        // PointsPayload
        PointsPayload pp = new PointsPayload();
        pp.setPayloadType(PayloadType.POINTS_SYNC);
        pp.setClientId(0L);
        pp.setMessage("Sync points");
        Map<Long,Integer> m = new LinkedHashMap<>();
        m.put(1001L, 2);
        m.put(1002L, 1);
        m.put(1003L, 0);
        pp.setPointsByClientId(m);
        System.out.println(pp);
    }
}

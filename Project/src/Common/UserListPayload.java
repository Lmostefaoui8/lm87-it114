package Common;

import java.io.Serializable;
import java.util.Map;

public class UserListPayload extends Payload implements Serializable {
    private static final long serialVersionUID = 1L;

    private Map<Long,Integer> points;
    private Map<Long,Boolean> eliminated;
    private Map<Long,Boolean> pending;
    private Map<Long,Boolean> away;

    public void setPoints(Map<Long,Integer> points) { this.points = points; }
    public void setEliminated(Map<Long,Boolean> eliminated) { this.eliminated = eliminated; }
    public void setPending(Map<Long,Boolean> pending) { this.pending = pending; }
    public void setAway(Map<Long,Boolean> away) { this.away = away; }
   

    public Map<Long,Integer> getPoints() { return points; }
    public Map<Long,Boolean> getEliminated() { return eliminated; }
    public Map<Long,Boolean> getPending() { return pending; }
    public Map<Long,Boolean> getAway() { return away; }
    

    @Override
    public String toString() {
        return "UserListPayload{points=" + points + ", eliminated=" + eliminated + ", pending=" + pending + ", away=" + away + "}";
    }
}

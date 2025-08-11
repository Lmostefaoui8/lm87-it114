package Client.UI;

import Client.Client;
import Server.User;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * UCID: LM87 | Date: 2025-08-11
 * Summary: Shows players with username#id, current points, and status flags:
 * - Pending-to-pick
 * - Eliminated
 * Sorted by points (desc), then name (asc), then id.
 */
public class UserListPanel extends JPanel {
    private final JPanel listPanel = new JPanel();
    private final JLabel title = new JLabel("Players");

    public UserListPanel() {
        setLayout(new BorderLayout(8,8));
        setBorder(new EmptyBorder(8,8,8,8));

        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        add(title, BorderLayout.NORTH);

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        add(new JScrollPane(listPanel), BorderLayout.CENTER);

        // small timer to refresh from client snapshot
        new javax.swing.Timer(500, e -> refresh()).start();
        refresh();
    }

    public void refresh() {
        Map<Long, User> users = Client.INSTANCE.uiGetKnownClientsSnapshot();
        Map<Long, Integer> points = Client.INSTANCE.uiGetPointsSnapshot();
        Map<Long, Boolean> pending = Client.INSTANCE.uiGetPendingSnapshot();
        Map<Long, Boolean> eliminated = Client.INSTANCE.uiGetEliminatedSnapshot();
        Map<Long, Boolean> away = Client.INSTANCE.uiGetAwaySnapshot();


        List<User> list = new ArrayList<>(users.values());
        list.sort((a,b) -> {
            int pa = points.getOrDefault(a.getClientId(), 0);
            int pb = points.getOrDefault(b.getClientId(), 0);
            if (pa != pb) return Integer.compare(pb, pa); // points desc
            String na = a.getClientName() == null ? "" : a.getClientName();
            String nb = b.getClientName() == null ? "" : b.getClientName();
            int nc = na.compareToIgnoreCase(nb);          // name asc
            if (nc != 0) return nc;
            return Long.compare(a.getClientId(), b.getClientId());
        });

        listPanel.removeAll();
        for (User u : list) {
            long id = u.getClientId();
            boolean isElim = eliminated.getOrDefault(id, false);
            boolean isPending = pending.getOrDefault(id, false);
            int pts = points.getOrDefault(id, 0);
            boolean isAway = away.getOrDefault(id,false);
            listPanel.add(row(u, pts, isPending, isElim,isAway));
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private JPanel row(User u, int pts, boolean pending, boolean eliminated, boolean isAway) {
        JPanel p = new JPanel(new BorderLayout(8,0));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        String name = (u.getClientName() != null ? u.getClientName() : "-") + " #" + u.getClientId();

        JLabel left = new JLabel(name);
        JLabel mid  = new JLabel("• " + (isAway ? "AWAY"
                  : (pending ? "PICKING…"
                  : (eliminated ? "ELIMINATED" : "READY/IDLE"))));

        JLabel right = new JLabel(pts + " pts");

        // visual styling
        if(isAway ||eliminated) {
            Color gray = new Color(130,130,130);
            left.setForeground(gray);
            mid.setForeground(gray);
            right.setForeground(gray);
        } else if (pending) {
            mid.setForeground(new Color(200,120,0)); // orange-ish
        } else {
            mid.setForeground(new Color(0,120,0));   // green-ish
        }

        p.add(left, BorderLayout.WEST);
        p.add(mid,  BorderLayout.CENTER);
        p.add(right,BorderLayout.EAST);
        return p;
    }
}

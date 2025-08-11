package Client.UI;

import Client.Client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/**
 * UCID: LM87 | 2025-08-11
 * Shows: users picking choices, battle resolution, elimination messages, and a round countdown.
 * Also offers a small "Command" box to run slash commands (/start, /pick r, etc.) without CLI.
 */
public class GameEventsPanel extends JPanel {
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> eventsList = new JList<>(model);

    private final JLabel timerLabel = new JLabel(" ");
    private final JTextField cmd = new JTextField();

    public GameEventsPanel() {
        setLayout(new BorderLayout(8,8));
        setBorder(new EmptyBorder(8,8,8,8));

        JLabel title = new JLabel("Game Events");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));

        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.WEST);
        timerLabel.setFont(timerLabel.getFont().deriveFont(Font.BOLD));
        top.add(timerLabel, BorderLayout.EAST);

        eventsList.setVisibleRowCount(12);
        eventsList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(eventsList);

        JPanel bottom = new JPanel(new BorderLayout(6,0));
        bottom.add(new JLabel("Command:"), BorderLayout.WEST);
        bottom.add(cmd, BorderLayout.CENTER);
        JButton send = new JButton("Send");
        bottom.add(send, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        send.addActionListener(e -> sendCmd());
        cmd.addActionListener(e -> sendCmd());

        // Refresh events and timer
        new javax.swing.Timer(400, e -> refresh()).start();
    }

    private void sendCmd() {
        String t = cmd.getText().trim();
        if (t.isEmpty()) return;
        Client.INSTANCE.uiSendCommand(t);
        cmd.setText("");
    }

    private void refresh() {
        // Timer
        int secs = Client.INSTANCE.uiGetRoundRemainingSeconds();
        timerLabel.setText(secs > 0 ? "Round ends in: " + secs + "s" : " ");

        // Events (append-only)
        List<String> events = Client.INSTANCE.uiGetEventsSnapshot();
        // keep model in sync without flicker
        int i = model.size();
        while (i < events.size()) {
            model.addElement(events.get(i));
            i++;
        }
        // Auto-scroll to bottom when new items appear
        if (model.size() > 0) {
            eventsList.ensureIndexIsVisible(model.size() - 1);
        }

        boolean spec = Client.INSTANCE.uiAmSpectator();
        cmd.setEnabled(!spec);   // whatever your field is named
        


    }
}

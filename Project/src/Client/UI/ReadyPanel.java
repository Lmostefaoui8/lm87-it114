package Client.UI;

import Client.Client;
import Server.User;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.*;

/**
 * UCID: LM87 | Date: 2025-08-11
 * Summary: Ready Check UI. Shows an "I'm Ready" toggle and a live list of players with ready indicators.
 * Uses MESSAGE relay: broadcasts "[READY] <clientId> <0|1>" and parses the same pattern.
 *
 * Updated: Added extra RPS-5 choices toggle + mode selection, host-only controls.
 */
public class ReadyPanel extends JPanel {
    private final JButton readyBtn = new JButton("I'm Ready");
    private final JPanel listPanel = new JPanel();
    private final JLabel hint = new JLabel("Toggle to broadcast readiness to the room.");
    private final JButton backBtn = new JButton("Back");
    JCheckBox chkCooldown = new JCheckBox("Enable Choice Cooldown");

    private boolean myReady = false;

    private final javax.swing.Timer swingTimer;

    private boolean suppressExtraChoicesEvents = true;


    private final JButton startBtn = new JButton("Start");

    // === Extra RPS-5 controls ===
    JCheckBox chkExtra = new JCheckBox("Enable Extra RPS-5 Choices");
    String[] modes = { "FULL", "LAST3" };
    JComboBox<String> cmbMode = new JComboBox<>(modes);

    public ReadyPanel(Runnable onBack) {
        setLayout(new BorderLayout(12, 12));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Ready Check");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        JPanel top = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel mid = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        left.add(readyBtn);
        JButton openGameBtn = new JButton("Open Game UI");
        mid.add(openGameBtn);
        right.add(backBtn);

        top.add(title, BorderLayout.NORTH);
        top.add(left, BorderLayout.WEST);
        top.add(mid, BorderLayout.CENTER);
        top.add(right, BorderLayout.EAST);

        hint.setFont(hint.getFont().deriveFont(11f));

        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(listPanel);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        mid.add(startBtn);
        startBtn.addActionListener(e -> Client.INSTANCE.uiSendCommand("/start"));

        readyBtn.addActionListener(e -> toggleReady());
        backBtn.addActionListener(e -> onBack.run());
        openGameBtn.addActionListener(e -> {
            java.awt.Window w = SwingUtilities.getWindowAncestor(this);
            if (w instanceof MainWindow) ((MainWindow) w).showGame();
        });

        // === Extra options panel below player list ===
        JPanel extraPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        extraPanel.add(chkExtra);
        extraPanel.add(new JLabel("Mode:"));
        extraPanel.add(cmbMode);
        
        extraPanel.add(Box.createHorizontalStrut(12));
        extraPanel.add(chkCooldown);
        // Create bottom area to hold both extra controls and hint
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.add(extraPanel);
        bottomPanel.add(Box.createVerticalStrut(4));
        bottomPanel.add(hint);

        add(bottomPanel, BorderLayout.SOUTH);

        // Send updates to server when host changes them
        chkExtra.addActionListener(e -> {
            if (suppressExtraChoicesEvents) return;
            if (Client.INSTANCE.uiIsHost()) {
                Client.INSTANCE.uiSetExtraChoices(
                    chkExtra.isSelected(),
                    (String) cmbMode.getSelectedItem()
                );
            }
        });


            cmbMode.addActionListener(e -> {
            if (suppressExtraChoicesEvents) return;
            if (Client.INSTANCE.uiIsHost()) {
                Client.INSTANCE.uiSetExtraChoices(
                    chkExtra.isSelected(),
                    (String) cmbMode.getSelectedItem()
                );
            }
        });

        chkCooldown.addActionListener(e -> {
            if (suppressExtraChoicesEvents) return;
            if (Client.INSTANCE.uiIsHost()) {
                Client.INSTANCE.uiSetCooldown(chkCooldown.isSelected());  // NEW
            }
        });

        swingTimer = new javax.swing.Timer(500, e -> {
            refreshList();
            refreshExtraControls();
        });
        swingTimer.setRepeats(true);
        swingTimer.start();

        refreshList();
        refreshButtonState();
        refreshExtraControls();
    }

    private void refreshStartButton() {
        boolean host = Client.INSTANCE.uiIsHost();
        boolean allReady = Client.INSTANCE.uiAllReady();
        boolean roundActive = Client.INSTANCE.uiGetRoundRemainingSeconds() > 0;

        startBtn.setVisible(host);
        startBtn.setEnabled(host && allReady && !roundActive);
    }

    private void toggleReady() {
        myReady = !myReady;
        Client.INSTANCE.uiToggleReady(myReady);
        refreshButtonState();
        refreshList();
    }

    private void refreshButtonState() {
        readyBtn.setText(myReady ? "I'm Not Ready" : "I'm Ready");
    }

    public void refreshList() {
        Map<Long, Boolean> readySnapshot = Client.INSTANCE.uiGetReadySnapshot();
        Map<Long, User> clients = Client.INSTANCE.uiGetKnownClientsSnapshot();

        listPanel.removeAll();

        java.util.List<User> users = new ArrayList<>(clients.values());
        users.sort((a, b) -> {
            boolean ra = readySnapshot.getOrDefault(a.getClientId(), false);
            boolean rb = readySnapshot.getOrDefault(b.getClientId(), false);
            if (ra != rb) return ra ? -1 : 1; // ready first
            String na = a.getClientName() == null ? "" : a.getClientName();
            String nb = b.getClientName() == null ? "" : b.getClientName();
            int cmp = na.compareToIgnoreCase(nb);
            return cmp != 0 ? cmp : Long.compare(a.getClientId(), b.getClientId());
        });

        for (User u : users) {
            boolean isReady = readySnapshot.getOrDefault(u.getClientId(), false);
            listPanel.add(rowForUser(u, isReady));
        }

        refreshStartButton();

        listPanel.revalidate();
        listPanel.repaint();
    }

    // update method
private void refreshExtraControls() {
    boolean host = Client.INSTANCE.uiIsHost();
    boolean en   = Client.INSTANCE.uiExtraChoicesEnabled();
    String  mode = Client.INSTANCE.uiExtraChoicesMode();
    boolean cd   = Client.INSTANCE.uiCooldownEnabled();

    suppressExtraChoicesEvents = true;
    try {
        chkExtra.setEnabled(host);
        cmbMode.setEnabled(host);
        chkCooldown.setEnabled(host);

        // only set if different to avoid needless events
        if (chkExtra.isSelected() != en) {
            chkExtra.setSelected(en);
        }
        if (!java.util.Objects.equals(cmbMode.getSelectedItem(), mode)) {
            cmbMode.setSelectedItem(mode);
        }

        if (chkCooldown.isSelected() != cd) chkCooldown.setSelected(cd);
    } finally {
        suppressExtraChoicesEvents = false;
    }
}

    private JPanel rowForUser(User u, boolean ready) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

        JLabel dot = new JLabel(ready ? "●" : "○"); // unicode filled/hollow circle
        JLabel name = new JLabel((u.getClientName() != null ? u.getClientName() : "-") + " #" + u.getClientId());
        JLabel tag = new JLabel(ready ? "READY" : "—");
        if (ready) {
            dot.setForeground(new Color(0, 128, 0));
            tag.setForeground(new Color(0, 128, 0));
        } else {
            dot.setForeground(new Color(128, 128, 128));
            tag.setForeground(new Color(128, 128, 128));
        }

        row.add(dot);
        row.add(Box.createHorizontalStrut(8));
        row.add(name);
        row.add(Box.createHorizontalStrut(8));
        row.add(tag);
        return row;
    }
}

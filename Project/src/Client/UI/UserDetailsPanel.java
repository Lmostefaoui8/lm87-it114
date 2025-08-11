package Client.UI;

import Client.Client;
import Server.User;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * UCID: LM87 | Date: 2025-08-10
 * Summary: Displays read-only connection/user data and navigation to Ready Check.
 */
public class UserDetailsPanel extends JPanel {
    private final JLabel status = new JLabel("-");
    private final JLabel username = new JLabel("-");
    private final JLabel clientId = new JLabel("-");
    private final JLabel host = new JLabel("-");
    private final JLabel port = new JLabel("-");

    private final JButton readyPanelBtn = new JButton("Ready Check…");
    private final JButton disconnectBtn = new JButton("Disconnect");

    private final Runnable onDisconnect;

    public UserDetailsPanel(Runnable onDisconnect) {
        this.onDisconnect = onDisconnect;
        setLayout(new BorderLayout(12, 12));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("User Details");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        JPanel grid = new JPanel(new GridLayout(0, 2, 8, 8));
        grid.add(new JLabel("Status:"));    grid.add(status);
        grid.add(new JLabel("Username:"));  grid.add(username);
        grid.add(new JLabel("Client ID:")); grid.add(clientId);
        grid.add(new JLabel("Host:"));      grid.add(host);
        grid.add(new JLabel("Port:"));      grid.add(port);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(readyPanelBtn);
        actions.add(disconnectBtn);

        add(title, BorderLayout.NORTH);
        add(grid, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);

        disconnectBtn.addActionListener(e -> doDisconnect());
        readyPanelBtn.addActionListener(e -> openReady());
    }

    public void refreshFromClient() {
        Client c = Client.INSTANCE;
        boolean connected = c.isConnected();
        status.setText(connected ? "Connected" : "Disconnected");

        User me = c.uiGetMyUser();
        username.setText(me != null && me.getClientName() != null ? me.getClientName() : "-");
        clientId.setText(me != null ? String.valueOf(me.getClientId()) : "-");
        host.setText(c.uiGetLastHost() != null ? c.uiGetLastHost() : "-");
        port.setText(c.uiGetLastPort() > 0 ? String.valueOf(c.uiGetLastPort()) : "-");

        disconnectBtn.setEnabled(connected);
        readyPanelBtn.setEnabled(connected);
    }

    private void doDisconnect() {
        try { Client.INSTANCE.shutdown(); } catch (Exception ignored) {}
        onDisconnect.run();
    }

    private void openReady() {
        java.awt.Window w = SwingUtilities.getWindowAncestor(this);
        if (w instanceof MainWindow) {
            ((MainWindow) w).showReady();
        }
    }
}

// Client/ui/ConnectionPanel.java
package Client.UI;

import Client.Client;
import Server.User;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

public class ConnectionPanel extends JPanel {
    private final JTextField username = new JTextField(18);
    private final JTextField host = new JTextField(18);
    private final JSpinner port = new JSpinner(new SpinnerNumberModel(3000, 1, 65535, 1));
    private final JButton connectBtn = new JButton("Connect");
    private final Consumer<Void> onConnected;

    public ConnectionPanel(Consumer<Void> onConnected) {
        this.onConnected = onConnected;
        setLayout(new BorderLayout(12, 12));
        setBorder(new EmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Connect to Server");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(6, 6, 6, 6);
        gc.anchor = GridBagConstraints.LINE_END;

        gc.gridx = 0; gc.gridy = 0; form.add(new JLabel("Username:"), gc);
        gc.gridy++;                 form.add(new JLabel("Host:"), gc);
        gc.gridy++;                 form.add(new JLabel("Port:"), gc);

        gc.anchor = GridBagConstraints.LINE_START;
        gc.gridx = 1; gc.gridy = 0; form.add(username, gc);
        gc.gridy++;                 form.add(host, gc);
        gc.gridy++;                 form.add(port, gc);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(connectBtn);

        add(title, BorderLayout.NORTH);
        add(form, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);

        connectBtn.addActionListener(e -> doConnect());
        refreshFromClient();
    }

    private void doConnect() {
        String u = username.getText().trim();
        String h = host.getText().trim();
        int p = (int) port.getValue();
        if (u.isEmpty()) { JOptionPane.showMessageDialog(this, "Please enter a username."); return; }
        if (h.isEmpty()) { JOptionPane.showMessageDialog(this, "Please enter a host."); return; }

        try {
            Client c = Client.INSTANCE;
            c.uiSetName(u);
            boolean ok = c.uiConnect(h, p);
            if (!ok) {
                JOptionPane.showMessageDialog(this, "Failed to connect to " + h + ":" + p, "Connection failed", JOptionPane.ERROR_MESSAGE);
                return;
            }
            onConnected.accept(null);
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "Exception", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void refreshFromClient() {
        Client c = Client.INSTANCE;
        host.setText(c.uiGetLastHost() != null ? c.uiGetLastHost() : "localhost");
        port.setValue(c.uiGetLastPort() > 0 ? c.uiGetLastPort() : 3000);
        User me = c.uiGetMyUser();
        if (me != null && me.getClientName() != null) {
            username.setText(me.getClientName());
        }
    }
}

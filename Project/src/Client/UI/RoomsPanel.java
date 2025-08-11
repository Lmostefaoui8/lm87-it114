package Client.UI;

import Client.Client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.*;


/**
 * UCID: LM87 | Date: 2025-08-11
 * Summary: Rooms Panel UI. Gives the option to join or create a room. 
 */
public class RoomsPanel extends JPanel {
    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);

    private final JTextField newRoom = new JTextField(16);
    private final JButton createBtn = new JButton("Create");
    private final JButton joinBtn = new JButton("Join");
    private final JButton backBtn = new JButton("Back");

    private final Runnable onJoined;
    private final Runnable onBack;

    // keep selection across refreshes; avoid fighting the user while they click
    private volatile boolean userInteracting = false;
    private String lastSelected = null;

    public RoomsPanel(Runnable onJoined, Runnable onBack) {
        this.onJoined = onJoined;
        this.onBack = onBack;

        setLayout(new BorderLayout(8,8));
        setBorder(new EmptyBorder(12,12,12,12));

        JLabel title = new JLabel("Rooms");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        JPanel top = new JPanel(new BorderLayout());
        top.add(title, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(backBtn);
        top.add(right, BorderLayout.EAST);

        // List config
        list.setVisibleRowCount(10);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                lastSelected = list.getSelectedValue();
                joinBtn.setEnabled(lastSelected != null);
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { userInteracting = true; }
            @Override public void mouseReleased(MouseEvent e) { userInteracting = false; }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    doJoin();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(list);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottom.add(new JLabel("Name:"));
        bottom.add(newRoom);
        bottom.add(createBtn);
        bottom.add(joinBtn);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        createBtn.addActionListener(e -> doCreate());
        joinBtn.addActionListener(e -> doJoin());
        backBtn.addActionListener(e -> onBack.run());
        joinBtn.setEnabled(false); // enable when a room is selected

        // Refresh rooms periodically, but preserve selection and don't interrupt clicks
        new javax.swing.Timer(800, e -> refreshFromClient()).start();
        refreshFromClient();
    }

    private void refreshFromClient() {
        if (userInteracting) return; // don't fight the user's click/drag

        java.util.List<String> rooms = Client.INSTANCE.uiGetRoomsSnapshot();

        // Only rewrite the model if it actually changed
        java.util.List<String> current = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) current.add(model.get(i));

        if (!rooms.equals(current)) {
            model.clear();
            for (String r : rooms) model.addElement(r);
        }

        // Restore previous selection if it still exists
        if (lastSelected != null) {
            int idx = model.indexOf(lastSelected);
            if (idx >= 0) {
                list.setSelectedIndex(idx);
                list.ensureIndexIsVisible(idx);
            }
        }

        // Enable Join only if something is selected
        joinBtn.setEnabled(list.getSelectedValue() != null);
    }

    private void doCreate() {
        String name = newRoom.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter a room name.");
            return;
        }
        try {
            Client.INSTANCE.uiCreateRoom(name);
            // server auto-joins creator — go straight to Ready
            onJoined.run();
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Create failed: " + ex.getMessage());
        }
    }

    private void doJoin() {
        String sel = list.getSelectedValue();
        if (sel == null) {
            JOptionPane.showMessageDialog(this, "Select a room to join.");
            return;
        }
        try {
            Client.INSTANCE.uiJoinRoom(sel);
            onJoined.run();
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Join failed: " + ex.getMessage());
        }
    }
}

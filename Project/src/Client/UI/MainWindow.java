package Client.UI;

import javax.swing.*;
import java.awt.*;

/**
 * UCID: LM87 | Date: 2025-08-10
 * Summary: Top-level frame hosting the Connection, User Details, Ready, and Game panels.
 */
public class MainWindow extends JFrame {
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);

    public static final String CARD_CONNECT = "connect";
    public static final String CARD_DETAILS = "details";
    public static final String CARD_READY   = "ready";
    public static final String CARD_GAME    = "game";
    public static final String CARD_ROOMS  = "rooms";


    private final ConnectionPanel connectionPanel;
    private final UserDetailsPanel detailsPanel;
    private final ReadyPanel readyPanel;
    private final GamePanel gamePanel;

    private final RoomsPanel roomsPanel;

    public MainWindow() {
        super("RPS - Milestone 3");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(600, 420));
        setLocationByPlatform(true);

        connectionPanel = new ConnectionPanel(v -> showRooms());
        detailsPanel = new UserDetailsPanel(() -> showConnect());
        readyPanel = new ReadyPanel(() -> showDetails());
        gamePanel = new GamePanel(() -> showReady());
        roomsPanel = new RoomsPanel(() -> showReady(), () -> showDetails());

        root.add(connectionPanel, CARD_CONNECT);
        root.add(detailsPanel,   CARD_DETAILS);
        root.add(readyPanel,     CARD_READY);
        root.add(gamePanel,      CARD_GAME);
        root.add(roomsPanel, CARD_ROOMS);

        setContentPane(root);
    }

    public void showRooms() { cards.show(root, CARD_ROOMS); }


    public void showConnect() {
        cards.show(root, CARD_CONNECT);
        connectionPanel.refreshFromClient();
    }

    public void showDetails() {
        detailsPanel.refreshFromClient();
        cards.show(root, CARD_DETAILS);
    }

    public void showReady() {
        readyPanel.refreshList();
        cards.show(root, CARD_READY);
    }

    public void showGame() {
        cards.show(root, CARD_GAME);
    }
}

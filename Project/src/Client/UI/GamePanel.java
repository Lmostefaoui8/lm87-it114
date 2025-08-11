package Client.UI;

import javax.swing.*;
import java.awt.*;
import Client.Client;
/**
 * UCID: LM87 | Date: 2025-08-11
 * Summary: Game page container (for now: UserListPanel only).
 */
public class GamePanel extends JPanel {
    private final UserListPanel userList = new UserListPanel();
    private final GameEventsPanel events = new GameEventsPanel();
    private final PickBar pickBar = new PickBar();


    private final JButton backBtn = new JButton("Back");
    private final JButton startBtn = new JButton("Start");
    private final JButton awayBtn = new JButton("Set Away");



    public GamePanel(Runnable onBack) {
        setLayout(new BorderLayout(8,8));
    
        JPanel top = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Game");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(backBtn);
       
        top.add(title, BorderLayout.WEST);
        top.add(right, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
    
        right.add(startBtn);
        right.add(awayBtn);
        startBtn.addActionListener(e -> Client.INSTANCE.uiSendCommand("/start"));
        JSplitPane split = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            userList,
            events
        );
        awayBtn.addActionListener(e -> {
            boolean nowAway = Client.INSTANCE.uiAmAway();
            Client.INSTANCE.uiToggleAway();                 // send to server
            awayBtn.setText(nowAway ? "Set Away" : "I'm Back"); // optimistic flip
        });
        split.setResizeWeight(0.45);
        add(split, BorderLayout.CENTER);
    
        add(pickBar, BorderLayout.SOUTH); // ← add this line
    
        backBtn.addActionListener(e -> onBack.run());
    }
}

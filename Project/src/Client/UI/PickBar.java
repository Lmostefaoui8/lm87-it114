package Client.UI;

import Client.Client;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * UCID: LM87 | 2025-08-11
 * Shows: Creates the options for picking a choice. 
 * Updated: Added Lizard and Spock for RPS-5 with conditional enable based on session settings.
 */
public class PickBar extends JPanel {
    private final JButton rock = new JButton("Rock");
    private final JButton paper = new JButton("Paper");
    private final JButton scissors = new JButton("Scissors");
    private final JLabel status = new JLabel(" ");

    // === EXTRA CHOICES FEATURE (RPS-5) ===
    private final JButton lizard = new JButton("🦎 Lizard");
    private final JButton spock  = new JButton("🖖 Spock");

    public PickBar() {
        setLayout(new BorderLayout(8,8));
        setBorder(new EmptyBorder(8,8,8,8));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT));
        left.add(new JLabel("Your Pick:"));
        left.add(rock); left.add(paper); left.add(scissors);
        left.add(lizard); left.add(spock);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        right.add(status);

        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);

        // Add listeners for all choices
        rock.addActionListener(e -> Client.INSTANCE.uiPick("r"));
        paper.addActionListener(e -> Client.INSTANCE.uiPick("p"));
        scissors.addActionListener(e -> Client.INSTANCE.uiPick("s"));
        lizard.addActionListener(e -> Client.INSTANCE.uiPick("l")); // 🦎
        spock.addActionListener(e -> Client.INSTANCE.uiPick("k"));  // 🖖 (using 'k' to avoid conflict with 's')

        // refresh UI state periodically
        new javax.swing.Timer(300, e -> refresh()).start();
        refresh();
    }

    private void refresh() {


        boolean canPick = Client.INSTANCE.uiIsChoosingNow() && !Client.INSTANCE.uiAmEliminated();
        String sel = Client.INSTANCE.uiGetSelectedPick();
        boolean extraAllowed = Client.INSTANCE.uiExtraChoicesAllowed();

        boolean cooldown      = Client.INSTANCE.uiCooldownEnabled();
        String  last          = Client.INSTANCE.uiGetLastRoundPick();

        // Base RPS
        rock.setEnabled(canPick && sel == null);
        paper.setEnabled(canPick && sel == null);
        scissors.setEnabled(canPick && sel == null);
         // Extra choices
         lizard.setEnabled(canPick && sel == null && extraAllowed);
         spock.setEnabled(canPick && sel == null && extraAllowed);


        // Apply cooldown: disable ONLY the same choice as last round (if applicable)
    if (canPick && sel == null && cooldown && last != null) {
        switch (last) {
            case "r": rock.setEnabled(false);  rock.setToolTipText("On cooldown from last round"); break;
            case "p": paper.setEnabled(false); paper.setToolTipText("On cooldown from last round"); break;
            case "s": scissors.setEnabled(false); scissors.setToolTipText("On cooldown from last round"); break;
            case "l": lizard.setEnabled(false); lizard.setToolTipText("On cooldown from last round"); break;
            case "k": spock.setEnabled(false);  spock.setToolTipText("On cooldown from last round"); break;
        }
    } else {
        // clear tooltips if not cooled
        rock.setToolTipText(null); paper.setToolTipText(null); scissors.setToolTipText(null);
        lizard.setToolTipText(null); spock.setToolTipText(null);
    }

        // Reset highlights
        resetHighlight(rock); resetHighlight(paper); resetHighlight(scissors);
        resetHighlight(lizard); resetHighlight(spock);

        // Highlight chosen
        if ("r".equals(sel)) highlight(rock);
        if ("p".equals(sel)) highlight(paper);
        if ("s".equals(sel)) highlight(scissors);
        if ("l".equals(sel)) highlight(lizard);
        if ("k".equals(sel)) highlight(spock);

       


    
        // Status label
        if (Client.INSTANCE.uiAmEliminated()) {
            status.setText("ELIMINATED");
            status.setForeground(new Color(150,0,0));
        } else if (!Client.INSTANCE.uiIsChoosingNow()) {
            status.setText("Waiting…");
            status.setForeground(new Color(80,80,80));
        } else if (sel == null) {
            status.setText("Pick now!");
            status.setForeground(new Color(0,120,0));
        } else {
            status.setText("You picked");
            status.setForeground(new Color(0,120,0));
        }
    }

    private void refreshButtonStates() {
        boolean canPick = Client.INSTANCE.uiIsChoosingNow();
    
        // Standard R/P/S always available during pick phase
        rock.setEnabled(canPick);
        paper.setEnabled(canPick);
        scissors.setEnabled(canPick);
    
        // Lizard/Spock only if extra choices allowed
        boolean allowExtra = Client.INSTANCE.uiExtraChoicesAllowed();
        lizard.setEnabled(canPick && allowExtra);
        spock.setEnabled(canPick && allowExtra);
    }

    private void highlight(JButton b) {
        b.setBorder(BorderFactory.createLineBorder(new Color(0,120,0), 2));
    }
    private void resetHighlight(JButton b) {
        b.setBorder(UIManager.getBorder("Button.border"));
    }
}

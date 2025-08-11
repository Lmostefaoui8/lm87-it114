// Client/UIMain.java
package Client;

import Client.UI.MainWindow;
import javax.swing.*;

public class UIMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainWindow win = new MainWindow();
            win.setVisible(true);
            win.showConnect();
        });
    }
}

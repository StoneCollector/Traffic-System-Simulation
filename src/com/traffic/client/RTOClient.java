package com.traffic.client;

import javafx.scene.control.TextInputDialog;
import java.util.Optional;

import com.traffic.interfaces.RTOClientInterface;
import com.traffic.interfaces.SignalControllerInterface;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RTOClient extends Application implements RTOClientInterface {

    private final String rtoId = "RTO_" + UUID.randomUUID().toString().substring(0, 4);
    private SignalControllerInterface server;

    // --- GUI Components ---
    private final Text road12Status = createStatusText("?");
    private final Text ped12Status = createStatusText("?");
    private final Text road34Status = createStatusText("?");
    private final Text ped34Status = createStatusText("?");
    private final ObservableList<String> historyLogs = FXCollections.observableArrayList();
    private final Label acknowledgmentLabel = new Label();

    public RTOClient() throws RemoteException {
        // Required for RMI export
        UnicastRemoteObject.exportObject(this, 0);
    }
    private String serverIp;
    @Override
    public void start(Stage primaryStage) {

        TextInputDialog dialog = new TextInputDialog("localhost");
        dialog.setTitle("Server Connection");
        dialog.setHeaderText("Enter the Server's IP Address");
        dialog.setContentText("IP Address:");

        Optional<String> result = dialog.showAndWait();

        // 2. Process the user's input
        if (result.isPresent() && !result.get().trim().isEmpty()) {
            this.serverIp = result.get().trim();
        } else {
            System.out.println("No server IP provided. Exiting.");
            Platform.exit();
            return;
        }

        primaryStage.setTitle("RTO Dashboard: " + rtoId);

        GridPane statusGrid = createStatusGrid();
        VBox controlBox = createControlBox();
        ListView<String> historyView = new ListView<>(historyLogs);
        
        TitledPane statusPane = new TitledPane("Live Signal Status", statusGrid);
        statusPane.setCollapsible(false);
        TitledPane controlPane = new TitledPane("Manual Override", controlBox);
        controlPane.setCollapsible(false);
        TitledPane historyPane = new TitledPane("Event History (from DB)", historyView);
        historyPane.setCollapsible(false);
        
        VBox root = new VBox(20, statusPane, controlPane, historyPane);
        root.setPadding(new Insets(15));
        VBox.setVgrow(historyPane, Priority.ALWAYS);

        primaryStage.setScene(new Scene(root, 400, 600));
        primaryStage.show();

        connectToServer();
    }

    private void connectToServer() {
        new Thread(() -> {
            try {
                // CHANGED: This now uses the 'serverIp' variable from the dialog
                Registry registry = LocateRegistry.getRegistry(serverIp, 1099);
                server = (SignalControllerInterface) registry.lookup("TrafficSignalService");
                server.registerRTO(this);
                Platform.runLater(() -> acknowledgmentLabel.setText("Connected to server at " + serverIp));
                refreshHistory();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    acknowledgmentLabel.setText("Connection to " + serverIp + " failed.");
                    System.err.println(e.getMessage());
                });
            }
        }).start();
    }
    
    private void refreshHistory() {
        try {
            List<String> history = server.getLogHistory();
            Platform.runLater(() -> historyLogs.setAll(history));
        } catch (RemoteException e) {
            Platform.runLater(() -> acknowledgmentLabel.setText("Failed to get history."));
        }
    }

    // --- RMI Callback Methods ---
    @Override
    public void updateStatus(Map<String, String> status) {
        Platform.runLater(() -> {
            updateStatusText(road12Status, status.get("road_1_2"));
            updateStatusText(ped12Status, status.get("ped_1_2"));
            updateStatusText(road34Status, status.get("road_3_4"));
            updateStatusText(ped34Status, status.get("ped_3_4"));
        });
    }

    @Override
    public void acknowledge(String message) {
        Platform.runLater(() -> acknowledgmentLabel.setText(message));
    }
    
    // --- GUI Creation ---
    private VBox createControlBox() {
        Button force12 = new Button("Force Roads 1 & 2 Green");
        force12.setOnAction(e -> forceChange("1_2"));
        Button force34 = new Button("Force Roads 3 & 4 Green");
        force34.setOnAction(e -> forceChange("3_4"));
        Button refresh = new Button("Refresh History");
        refresh.setOnAction(e -> refreshHistory());

        HBox buttonBox = new HBox(10, force12, force34);
        buttonBox.setAlignment(Pos.CENTER);
        
        return new VBox(15, buttonBox, refresh, acknowledgmentLabel);
    }
    
    private void forceChange(String direction) {
        try {
            server.forceSignalChange(direction, rtoId);
            acknowledgmentLabel.setText("Override command sent...");
        } catch (RemoteException e) {
            acknowledgmentLabel.setText("Error: Could not send command.");
        }
    }
    
    private GridPane createStatusGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.add(new Label("Roads 1 & 2:"), 0, 0);
        grid.add(road12Status, 1, 0);
        grid.add(new Label("Pedestrians 1 & 2:"), 0, 1);
        grid.add(ped12Status, 1, 1);
        grid.add(new Label("Roads 3 & 4:"), 0, 2);
        grid.add(road34Status, 1, 2);
        grid.add(new Label("Pedestrians 3 & 4:"), 0, 3);
        grid.add(ped34Status, 1, 3);
        return grid;
    }

    private Text createStatusText(String initial) {
        Text text = new Text(initial);
        text.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
        return text;
    }
    
    private void updateStatusText(Text text, String status) {
        if (status == null) return;
        text.setText(status);
        switch (status.toUpperCase()) {
            case "GREEN": text.setFill(Color.GREEN); break;
            case "YELLOW": text.setFill(Color.ORANGE); break;
            case "RED": text.setFill(Color.RED); break;
            default: text.setFill(Color.BLACK); break;
        }
    }
    
    

    // In TrafficClient.java AND RTOClient.java

    public static void main(String[] args) {
        // Use your NetworkUtils to automatically set this client's IP for RMI callbacks
        try {
            String localIp = NetworkUtils.getLocalIpAddress();
            System.setProperty("java.rmi.server.hostname", localIp);
            System.out.println("Client RMI callback IP automatically set to: " + localIp);
        } catch (Exception e) {
            System.err.println("Could not set RMI hostname automatically.");
        }
        
        // Launch the JavaFX application
        launch(args);
    }
}
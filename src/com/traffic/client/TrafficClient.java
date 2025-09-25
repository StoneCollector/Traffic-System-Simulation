package com.traffic.client;

import com.traffic.interfaces.ClientInterface;
import com.traffic.interfaces.SignalControllerInterface;

import javafx.scene.control.TextInputDialog;
import java.util.Optional;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TrafficClient extends Application implements ClientInterface {

    private String clientId;
    private String clientType; // e.g., "ROAD_1_2"
    private SignalControllerInterface server;
    
    // --- GUI Components ---
    private Circle redLight, yellowLight, greenLight;
    private Timeline blinker;
    // NEW: Fields for the graphical pedestrian signal
    private Circle pedRedLight, pedGreenLight;
    private Timeline pedBlinker;
    
    public TrafficClient() {
        // Required public no-arg constructor
    }

    @Override
    public void init() throws Exception {
        super.init();
        Parameters params = getParameters();
        String roadPair = params.getRaw().get(0); 
        this.clientType = "ROAD_" + roadPair;
        this.clientId = "Pair_" + roadPair;
    }

    private String serverIp;
    @Override
    public void start(Stage primaryStage) {
        
        TextInputDialog dialog = new TextInputDialog("localhost");
        dialog.setTitle("Server Connection");
        dialog.setHeaderText("Enter the Server's IP Address");
        dialog.setContentText("IP Address:");

        Optional<String> result = dialog.showAndWait();

        if (result.isPresent() && !result.get().trim().isEmpty()) {
            this.serverIp = result.get().trim();
        } else {
            System.out.println("No server IP provided. Exiting.");
            Platform.exit();
            return;
        }
        
        primaryStage.setTitle("Signal: " + clientId);

        HBox root = createGui(); // CHANGED: The root is now an HBox for a wider layout
        primaryStage.setScene(new Scene(root));
        // CHANGED: The window is now larger and resizable by default
        primaryStage.setMinWidth(320);
        primaryStage.setMinHeight(300);
        primaryStage.show();

        setupBlinkers(); // CHANGED: Renamed to setup both blinkers
        connectAndStartRequests();
    }
    
    private HBox createGui() {
        // --- Traffic Signal VBox ---
        String titleText = clientType.equals("ROAD_1_2") ? "Roads 1 & 2" : "Roads 3 & 4";
        Label title = new Label(titleText);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        title.setTextFill(Color.WHITE);

        redLight = new Circle(40, Color.DARKSLATEGRAY);
        yellowLight = new Circle(40, Color.DARKSLATEGRAY);
        greenLight = new Circle(40, Color.DARKSLATEGRAY);

        VBox trafficSignalBox = new VBox(15, title, redLight, yellowLight, greenLight);
        trafficSignalBox.setAlignment(Pos.CENTER);

        // --- Pedestrian Signal VBox ---
        Label pedTitle = new Label("Pedestrians");
        pedTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        pedTitle.setTextFill(Color.WHITE);

        pedRedLight = new Circle(40, Color.RED); // Default to STOP
        pedGreenLight = new Circle(40, Color.DARKSLATEGRAY);

        VBox pedestrianSignalBox = new VBox(15, pedTitle, pedRedLight, pedGreenLight);
        pedestrianSignalBox.setAlignment(Pos.CENTER);

        // --- Main HBox Container ---
        HBox root = new HBox(30, trafficSignalBox, pedestrianSignalBox);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #333;");
        return root;
    }

    private void setupBlinkers() {
        // Blinker for main traffic light
        blinker = new Timeline(
            new KeyFrame(Duration.seconds(0.5), e -> redLight.setFill(Color.DARKSLATEGRAY)),
            new KeyFrame(Duration.seconds(1.0), e -> redLight.setFill(Color.RED))
        );
        blinker.setCycleCount(Timeline.INDEFINITE);
        
        // NEW: Blinker for pedestrian light
        pedBlinker = new Timeline(
            new KeyFrame(Duration.seconds(0.5), e -> pedRedLight.setFill(Color.DARKSLATEGRAY)),
            new KeyFrame(Duration.seconds(1.0), e -> pedRedLight.setFill(Color.RED))
        );
        pedBlinker.setCycleCount(Timeline.INDEFINITE);
    }
    
    private void connectAndStartRequests() {
        new Thread(() -> {
            try {
                UnicastRemoteObject.exportObject(this, 0);
                Registry registry = LocateRegistry.getRegistry(serverIp, 1099);
                server = (SignalControllerInterface) registry.lookup("TrafficSignalService");
                server.registerClient(this, clientType);
                System.out.println("Client [" + clientId + "] successfully registered with server at " + serverIp);

                ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                Random random = new Random();
                scheduler.scheduleAtFixedRate(() -> {
                    try {
                        boolean isVip = random.nextInt(10) == 0;
                        int proximity = 10 + random.nextInt(90);
                        initiateRequest(isVip, proximity);
                    } catch (RemoteException e) {
                        System.err.println("Failed to send request: " + e.getMessage());
                    }
                }, 5, random.nextInt(8) + 5, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("Connection to server at " + serverIp + " failed. Is the server running?");
                Platform.exit();
            }
        }).start();
    }

    @Override
    public String getClientId() throws RemoteException {
        return this.clientId;
    }

    @Override
    public void initiateRequest(boolean isVip, int proximity) throws RemoteException {
        String direction = clientType.substring(5); // "1_2" or "3_4"
        server.receiveRequest(clientId, direction, isVip, proximity);
    }
    
    @Override
    public void updateSignalState(String state, String details) {
        System.out.println("CLIENT DEBUG: Received update from server! New state: " + state);
        Platform.runLater(() -> {
            // Stop all animations and reset all lights
            blinker.stop();
            pedBlinker.stop();
            redLight.setFill(Color.DARKSLATEGRAY);
            yellowLight.setFill(Color.DARKSLATEGRAY);
            greenLight.setFill(Color.DARKSLATEGRAY);
            pedRedLight.setFill(Color.DARKSLATEGRAY);
            pedGreenLight.setFill(Color.DARKSLATEGRAY);

            // Set main traffic light state
            if (state.endsWith("GREEN")) {
                greenLight.setFill(Color.LIMEGREEN);
            } else if (state.endsWith("YELLOW")) {
                yellowLight.setFill(Color.ORANGE);
            } else if (state.endsWith("RED")) {
                redLight.setFill(Color.RED);
            } else if (state.endsWith("BLINK_RED")) {
                blinker.play();
            }
            
            // --- CORRECTED PEDESTRIAN LOGIC ---
            String myDir = clientType.substring(5); // "1_2" or "3_4"

            // The server only sends this client states relevant to its direction (e.g., 1_2_...)
            // So we only need to check what the state of OUR light is.
            if (state.startsWith(myDir)) {
                if (state.endsWith("GREEN")) {
                    // Our light is green, so pedestrians must stop.
                    pedRedLight.setFill(Color.RED);
                } else if (state.endsWith("YELLOW")) {
                    // Our light is yellow, so pedestrians get a blinking red warning.
                    pedBlinker.play();
                } else {
                    // Our light is RED or BLINKING_RED, so pedestrians can walk.
                    pedGreenLight.setFill(Color.LIMEGREEN);
                }
            }
        });
    }

    public static void main(String[] args) {
        try {
            String localIp = NetworkUtils.getLocalIpAddress();
            System.setProperty("java.rmi.server.hostname", localIp);
            System.out.println("Client RMI callback IP automatically set to: " + localIp);
        } catch (Exception e) {
            System.err.println("Could not set RMI hostname automatically.");
        }
        
        launch(args);
    }
}
package com.traffic.server;

import com.traffic.interfaces.ClientInterface;
import com.traffic.interfaces.RTOClientInterface;
import com.traffic.interfaces.SignalControllerInterface;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.Scanner;

public class SignalController extends Application implements SignalControllerInterface {

    // --- Inner classes defined first to be visible throughout the class ---
    record ClientInfo(ClientInterface stub, String type) {}
    record Request(String clientId, String direction, boolean isVip, int proximity) {
        @Override
        public String toString() {
            return clientId + (isVip ? " (VIP)" : "");
        }
    }
    class RoadProcess {
        final String id;
        int clock = 0;
        boolean requestingCS = false;
        RoadProcess(String id) { this.id = id; }
        boolean receiveRequest(int otherClock, String otherId) {
            clock = Math.max(clock, otherClock) + 1;
            log("MUTEX: " + id + " received REQUEST(" + otherClock + ") from " + otherId + ". My clock is now " + clock);
            if (!requestingCS || otherClock < this.clock || (otherClock == this.clock && otherId.compareTo(this.id) < 0)) {
                log("MUTEX: " + id + " grants permission. Sending REPLY to " + otherId);
                return true;
            } else {
                log("MUTEX: " + id + " defers reply to " + otherId + " (My request has priority).");
                return false;
            }
        }
    }

    // --- RMI and Client Management ---
    private static final SignalController INSTANCE = createInstance();
    private final Map<String, ClientInfo> clients = new ConcurrentHashMap<>();
    private final int REQUIRED_CLIENTS = 2;
    private static String hostIp = "Not Detected";

    // --- NEW RTO MANAGEMENT ---
    private final List<RTOClientInterface> rtoClients = new CopyOnWriteArrayList<>();
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock(true);

    private volatile String activeDirection = "1_2";
    private volatile boolean inTransition = false;
    private final Object lock = new Object();
    private final BlockingQueue<Request> vipQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Request> normalQueue1 = new LinkedBlockingQueue<>(5);
    private final BlockingQueue<Request> normalQueue2 = new LinkedBlockingQueue<>();
    private volatile boolean useQueue1 = true;
    private final int QUEUE_THRESHOLD = 5;

    private static final Text road12Status = createStatusText("GREEN");
    private static final Text road34Status = createStatusText("RED");
    private static final Text ped12Status = createStatusText("RED");
    private static final Text ped34Status = createStatusText("GREEN");
    private static final ObservableList<String> logs = FXCollections.observableArrayList();
    private static final ObservableList<Request> queue1Data = FXCollections.observableArrayList();
    private static final ObservableList<Request> queue2Data = FXCollections.observableArrayList();
    private static final ObservableList<Request> vipQueueData = FXCollections.observableArrayList();

    private final RoadProcess process12 = new RoadProcess("1_2");
    private final RoadProcess process34 = new RoadProcess("3_4");

    private static SignalController createInstance() {
        try {
            return new SignalController();
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }
    }

    public SignalController() throws RemoteException {
        super();
    }

    @Override
    public void registerClient(ClientInterface client, String clientType) throws RemoteException {
        String clientId = client.getClientId();
        clients.put(clientId, new ClientInfo(client, clientType));
        log("Client registered: " + clientId + " as " + clientType);
        log(clients.size() + " of " + REQUIRED_CLIENTS + " clients connected.");

        if (clients.size() == REQUIRED_CLIENTS) {
            log("All clients connected. Starting system.");
            new Thread(this::processRequests).start();
        }
    }

    @Override
    public void receiveRequest(String clientId, String direction, boolean isVip, int proximity) throws RemoteException {
        Request newRequest = new Request(clientId, direction, isVip, proximity);
        handleRequest(newRequest);
    }

    @Override
    public void registerRTO(RTOClientInterface rto) throws RemoteException {
        rtoClients.add(rto);
        log("RTO client connected.");
        rto.updateStatus(getCurrentStatusMap());
    }

    @Override
    public void forceSignalChange(String direction, String rtoId) throws RemoteException {
        log("RTO " + rtoId + " is attempting a manual override to " + direction + ".");
        if (stateLock.writeLock().tryLock()) {
            try {
                if (direction.equals(activeDirection) || inTransition) {
                    log("Manual override rejected: Direction is already active or in transition.");
                    return;
                }
                log("WRITE LOCK ACQUIRED by " + rtoId + ". Forcing state change.");
                DatabaseManager.logEvent("Manual override initiated by " + rtoId + " to " + direction);
                switchDirection(direction);
            } finally {
                stateLock.writeLock().unlock();
                log("WRITE LOCK RELEASED by " + rtoId);
            }
        } else {
            log("Manual override rejected: System is busy.");
        }
    }

    @Override
    public List<String> getLogHistory() throws RemoteException {
        return DatabaseManager.getHistory();
    }

    private void handleRequest(Request request) {
        log("Received request: " + request);
        if (request.isVip()) {
            vipQueue.offer(request);
        } else {
            if (useQueue1 && normalQueue1.size() >= QUEUE_THRESHOLD) {
                log("LOAD BALANCING: Queue 1 is full. Switching to Queue 2.");
                useQueue1 = false;
            }
            if (!useQueue1 && normalQueue1.isEmpty()) {
                log("LOAD BALANCING: Queue 1 is empty. Switching back.");
                useQueue1 = true;
            }
            BlockingQueue<Request> targetQueue = useQueue1 ? normalQueue1 : normalQueue2;
            if (!targetQueue.offer(request)) {
                log("ERROR: Both queues are full. Dropping request: " + request);
            }
        }
        updateQueueViews();
    }

    private void processRequests() {
        while (true) {
            try {
                Request request = takeRequestFromQueues();
                log("Processing next request: " + request);
                updateQueueViews();

                if (request.direction().equals(activeDirection) || inTransition) {
                    log("Ignoring request for already active/transitioning direction.");
                    continue;
                }
                
                stateLock.writeLock().lock();
                try {
                    log("WRITE LOCK ACQUIRED by automated system.");
                    if (runMutexProtocol(request)) {
                        switchDirection(request.direction());
                    }
                } finally {
                    stateLock.writeLock().unlock();
                    log("WRITE LOCK RELEASED by automated system.");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log("Request processing thread interrupted.");
            }
        }
    }
    
    private boolean runMutexProtocol(Request request) {
        RoadProcess requester = request.direction().equals("1_2") ? process12 : process34;
        RoadProcess other = request.direction().equals("1_2") ? process34 : process12;
        log("MUTEX: " + requester.id + " wants to enter Critical Section (CS).");
        requester.requestingCS = true;
        requester.clock++;
        log("MUTEX: " + requester.id + " sending REQUEST(" + requester.clock + ") to " + other.id);
        boolean permissionGranted = other.receiveRequest(requester.clock, requester.id);
        if (permissionGranted) {
            log("MUTEX: " + requester.id + " received REPLY from " + other.id + ". Entering CS.");
            requester.requestingCS = false;
            return true;
        } else {
            log("MUTEX: " + requester.id + " did not get permission. Request will be retried later.");
            handleRequest(request);
            return false;
        }
    }

    private void switchDirection(String newDirection) {
        synchronized (lock) {
            if (inTransition) return;
            inTransition = true;
        }
        try {
            log("TRANSITION: Starting switch to " + newDirection);
            String yellowDir = activeDirection;
            broadcastState(yellowDir.equals("1_2") ? "1_2_YELLOW" : "3_4_YELLOW", "Transitioning");
            broadcastState(yellowDir.equals("1_2") ? "3_4_BLINK_RED" : "1_2_BLINK_RED", "Transitioning");
            updateStatusGUI(yellowDir, "YELLOW", "RED");
            Thread.sleep(5000);

            activeDirection = newDirection;
            broadcastState(activeDirection.equals("1_2") ? "1_2_GREEN" : "3_4_GREEN", "Active");
            broadcastState(activeDirection.equals("1_2") ? "3_4_RED" : "1_2_RED", "Stopped");
            updateStatusGUI(activeDirection, "GREEN", "RED");

            // MODIFICATION 2: Add a 5-second delay to keep the light green
            log("GREEN LIGHT: Holding " + newDirection + " green for 5 seconds.");
            Thread.sleep(5000);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inTransition = false;
            log("TRANSITION: Complete. Active direction is now " + activeDirection);
        }
    }
    
    private Request takeRequestFromQueues() throws InterruptedException {
        // MODIFICATION 3: Simplified logic to always prioritize emptying queue 1
        while (true) {
            Request r = vipQueue.poll();
            if (r != null) return r;
            
            r = normalQueue1.poll();
            if (r != null) return r;

            r = normalQueue2.poll();
            if (r != null) return r;
            
            Thread.sleep(100); // Wait if all queues are empty
        }
    }

    private void broadcastState(String state, String details) {
        String direction = state.substring(0, 3);
        String targetType = "ROAD_" + direction;
        List<ClientInterface> relevantClients = clients.values().stream()
            .filter(info -> info.type().equals(targetType))
            .map(ClientInfo::stub)
            .collect(Collectors.toList());
        for (ClientInterface client : relevantClients) {
            try {
                client.updateSignalState(state, details);
            } catch (RemoteException e) {
                log("Could not reach client: " + e.getMessage());
            }
        }
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Traffic Controller Server");
        VBox leftPanel = new VBox(20);
        leftPanel.setPadding(new Insets(10));
        leftPanel.setMinWidth(350);
        TitledPane statusPane = new TitledPane("Signal Status", createStatusGrid());
        statusPane.setCollapsible(false);
        TitledPane queuePane = new TitledPane("Request Queues", createQueueVBox());
        queuePane.setCollapsible(false);
        VBox.setVgrow(queuePane, Priority.ALWAYS);
        leftPanel.getChildren().addAll(statusPane, queuePane);
        ListView<String> logView = createLogListView();
        VBox.setVgrow(logView, Priority.ALWAYS);
        TitledPane logPane = new TitledPane("System Logs", logView);
        logPane.setCollapsible(false);
        HBox.setHgrow(logPane, Priority.ALWAYS);
        SplitPane splitPane = new SplitPane(leftPanel, logPane);
        splitPane.setDividerPositions(0.35);
        ScrollPane scrollPane = new ScrollPane(splitPane);
        scrollPane.setFitToWidth(true);
        primaryStage.setScene(new Scene(scrollPane));
        primaryStage.setMaximized(true);
        primaryStage.show();
        INSTANCE.log("Server GUI Started.");
    }
    
// In SignalController.java

    private GridPane createStatusGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(15);
        grid.setPadding(new Insets(15, 10, 15, 10));
        grid.add(new Label("Roads 1 & 2:"), 0, 0);
        grid.add(road12Status, 1, 0);
        grid.add(new Label("Pedestrians 1 & 2:"), 0, 1);
        grid.add(ped12Status, 1, 1);
        grid.add(new Label("Roads 3 & 4:"), 0, 2);
        grid.add(road34Status, 1, 2);
        grid.add(new Label("Pedestrians 3 & 4:"), 0, 3);
        grid.add(ped34Status, 1, 3);
        
        // --- ADD THESE LINES ---
        Label ipLabel = new Label("Server IP:");
        ipLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        Text ipAddressText = new Text(hostIp); // Reads the IP from our static variable
        ipAddressText.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 14));
        ipAddressText.setFill(Color.DIMGRAY);
        grid.add(ipLabel, 0, 4);
        grid.add(ipAddressText, 1, 4);
        // --- END OF ADDED LINES ---
        
        return grid;
    }
    
    private VBox createQueueVBox() {
        TableView<Request> queue1Table = createQueueTableView("Normal Queue 1 (Capacity: 5)");
        queue1Table.setItems(queue1Data);
        TableView<Request> queue2Table = createQueueTableView("Normal Queue 2");
        queue2Table.setItems(queue2Data);
        TableView<Request> vipQueueTable = createQueueTableView("VIP Queue");
        vipQueueTable.setItems(vipQueueData);
        VBox.setVgrow(queue1Table, Priority.SOMETIMES);
        VBox.setVgrow(queue2Table, Priority.SOMETIMES);
        VBox.setVgrow(vipQueueTable, Priority.SOMETIMES);
        return new VBox(10, queue1Table, queue2Table, vipQueueTable);
    }
    
    private TableView<Request> createQueueTableView(String title) {
        TableView<Request> table = new TableView<>();
        table.setPlaceholder(new Label(title));
        TableColumn<Request, String> col = new TableColumn<>("Request From");
        col.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().toString()));
        col.setPrefWidth(300);
        table.getColumns().add(col);
        return table;
    }

    private ListView<String> createLogListView() {
        ListView<String> logView = new ListView<>(logs);
        logView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    if (item.contains("MUTEX")) setStyle("-fx-text-fill: blue;");
                    else if (item.contains("PRIORITY")) setStyle("-fx-font-weight: bold; -fx-text-fill: red;");
                    else if (item.contains("LOAD BALANCING")) setStyle("-fx-font-weight: bold; -fx-text-fill: orange;");
                    else setStyle("-fx-text-fill: black;");
                }
            }
        });
        return logView;
    }
    
    private static Text createStatusText(String initial) {
        Text text = new Text(initial);
        text.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));
        updateTextColor(text, initial);
        return text;
    }

    private static void updateTextColor(Text text, String status) {
        switch (status.toUpperCase()) {
            case "GREEN": text.setFill(Color.GREEN); break;
            case "YELLOW": text.setFill(Color.ORANGE); break;
            case "RED": text.setFill(Color.RED); break;
            default: text.setFill(Color.BLACK); break;
        }
    }
    
    private void log(String message) {
        Platform.runLater(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
            logs.add(0, "[" + timestamp + "] " + message);
            if (logs.size() > 200) logs.remove(200);
        });
        if (message.contains("TRANSITION") || message.contains("PRIORITY") || message.contains("Manual override")) {
            DatabaseManager.logEvent(message);
        }
    }

    private void updateStatusGUI(String direction, String roadStatus, String pedStatus) {
        Platform.runLater(() -> {
            if (direction.equals("1_2")) {
                road12Status.setText(roadStatus);
                updateTextColor(road12Status, roadStatus);
                ped12Status.setText(pedStatus);
                updateTextColor(ped12Status, pedStatus);
                road34Status.setText(pedStatus);
                updateTextColor(road34Status, pedStatus);
                ped34Status.setText(roadStatus);
                updateTextColor(ped34Status, roadStatus);
            } else {
                road34Status.setText(roadStatus);
                updateTextColor(road34Status, roadStatus);
                ped34Status.setText(pedStatus);
                updateTextColor(ped34Status, pedStatus);
                road12Status.setText(pedStatus);
                updateTextColor(road12Status, pedStatus);
                ped12Status.setText(roadStatus);
                updateTextColor(ped12Status, roadStatus);
            }
        });
        Map<String, String> currentStatus = getCurrentStatusMap();
        for (RTOClientInterface rto : rtoClients) {
            try {
                rto.updateStatus(currentStatus);
            } catch (RemoteException e) {
                log("Failed to update an RTO client. It may have disconnected.");
                rtoClients.remove(rto);
            }
        }
    }
    
    private Map<String, String> getCurrentStatusMap() {
        return Map.of(
            "road_1_2", road12Status.getText(),
            "ped_1_2", ped12Status.getText(),
            "road_3_4", road34Status.getText(),
            "ped_3_4", ped34Status.getText()
        );
    }
    
    private void updateQueueViews() {
        Platform.runLater(() -> {
            queue1Data.setAll(new CopyOnWriteArrayList<>(normalQueue1));
            queue2Data.setAll(new CopyOnWriteArrayList<>(normalQueue2));
            vipQueueData.setAll(new CopyOnWriteArrayList<>(vipQueue));
        });
    }

    // MODIFICATION 1: New helper method to find the local IP
    private static String getLocalIpAddress() {
        // try {
        //     Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
        //     while (networkInterfaces.hasMoreElements()) {
        //         NetworkInterface ni = networkInterfaces.nextElement();
        //         Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
        //         while (inetAddresses.hasMoreElements()) {
        //             InetAddress ia = inetAddresses.nextElement();
        //             if (!ia.isLoopbackAddress() && !ia.isLinkLocalAddress() && ia.isSiteLocalAddress()) {
        //                 return ia.getHostAddress();
        //             }
        //         }
        //     }
        // } catch (SocketException e) {
        //     System.err.println("Could not determine local IP address: " + e.getMessage());
        // }
        // return "127.0.0.1"; // Fallback
        System.out.println("Enter IP Address of this machine: ");
        Scanner sc = new Scanner(System.in);
        return sc.nextLine();
    }

    // In SignalController.java

    public static void main(String[] args) {
        DatabaseManager.initialize();
        try {
            // This line is new - it saves the IP to our static variable
            hostIp = getLocalIpAddress(); 
            
            System.setProperty("java.rmi.server.hostname", hostIp);
            System.out.println("RMI Hostname set to: " + hostIp);
            
            Registry registry = LocateRegistry.createRegistry(1099);
            SignalControllerInterface stub = (SignalControllerInterface) UnicastRemoteObject.exportObject(INSTANCE, 0);
            registry.rebind("TrafficSignalService", stub);
            INSTANCE.log("RMI Service bound. Waiting for clients...");
        } catch (Exception e) {
            e.printStackTrace();
        }
        launch(args);
    }
}
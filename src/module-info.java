module TrafficSystem {
    // Defines what this module needs to run
    requires javafx.controls;
    requires javafx.graphics;
    requires java.rmi;
    requires java.desktop;
    requires java.sql; // NEW: For database (JDBC) access

    // Makes our RMI interfaces visible to other modules
    exports com.traffic.interfaces;
    
    // Allows the JavaFX framework to launch our client applications
    exports com.traffic.client to javafx.graphics;
    exports com.traffic.server to javafx.graphics;

    // Allows RMI to use reflection on our client code
    opens com.traffic.client to java.rmi;
    
    // Allows JavaFX to use properties in our server's data models
    opens com.traffic.server to javafx.base;
}
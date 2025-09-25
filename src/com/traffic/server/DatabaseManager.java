package com.traffic.server;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class DatabaseManager {

    private static final String DB_URL = "jdbc:sqlite:traffic_log.db";

    public static void initialize() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            // As requested, create 3 redundant log tables
            stmt.execute("CREATE TABLE IF NOT EXISTS logs_1 (timestamp TEXT, event TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS logs_2 (timestamp TEXT, event TEXT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS logs_3 (timestamp TEXT, event TEXT)");
            
            System.out.println("Database initialized successfully.");

        } catch (SQLException e) {
            System.err.println("Database initialization failed: " + e.getMessage());
        }
    }

    public static void logEvent(String event) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        String sql = "INSERT INTO logs_1(timestamp,event) VALUES(?,?);";
        String sql2 = "INSERT INTO logs_2(timestamp,event) VALUES(?,?);";
        String sql3 = "INSERT INTO logs_3(timestamp,event) VALUES(?,?);";

        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            conn.setAutoCommit(false); // Use a transaction for consistency
            
            try (PreparedStatement pstmt1 = conn.prepareStatement(sql);
                 PreparedStatement pstmt2 = conn.prepareStatement(sql2);
                 PreparedStatement pstmt3 = conn.prepareStatement(sql3)) {

                pstmt1.setString(1, timestamp);
                pstmt1.setString(2, event);
                pstmt1.executeUpdate();

                pstmt2.setString(1, timestamp);
                pstmt2.setString(2, event);
                pstmt2.executeUpdate();

                pstmt3.setString(1, timestamp);
                pstmt3.setString(2, event);
                pstmt3.executeUpdate();
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback(); // If one fails, none should be saved.
                System.err.println("Database logging failed, transaction rolled back: " + e.getMessage());
            }

        } catch (SQLException e) {
            System.err.println(e.getMessage());
        }
    }

    public static List<String> getHistory() {
        List<String> history = new ArrayList<>();
        // We only need to query one table, as they are all the same
        String sql = "SELECT timestamp, event FROM logs_1 ORDER BY timestamp DESC LIMIT 100";

        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                history.add("[" + rs.getString("timestamp") + "] " + rs.getString("event"));
            }
        } catch (SQLException e) {
            System.err.println("Failed to retrieve log history: " + e.getMessage());
        }
        return history;
    }
}
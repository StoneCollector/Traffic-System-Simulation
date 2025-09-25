package com.traffic.interfaces;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Map;

public interface RTOClientInterface extends Remote {

    // Server pushes a full status update to the RTO client
    void updateStatus(Map<String, String> status) throws RemoteException;

    // Server sends a confirmation or error message after a manual override attempt
    void acknowledge(String message) throws RemoteException;
}
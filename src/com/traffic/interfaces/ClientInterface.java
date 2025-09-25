package com.traffic.interfaces;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ClientInterface extends Remote {

    // The server calls this to tell the client the new state of the signals
    void updateSignalState(String state, String details) throws RemoteException;

    // A simple method for the server to get the client's unique ID
    String getClientId() throws RemoteException;
    
    // Server asks the client to send a traffic request
    void initiateRequest(boolean isVip, int proximity) throws RemoteException;
}
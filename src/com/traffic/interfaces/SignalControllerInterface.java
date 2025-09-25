package com.traffic.interfaces;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface SignalControllerInterface extends Remote {
    
    // For Traffic Signal Clients
    void registerClient(ClientInterface client, String clientType) throws RemoteException;
    void receiveRequest(String clientId, String direction, boolean isVip, int proximity) throws RemoteException;

    // --- NEW METHODS FOR RTOs ---
    void registerRTO(RTOClientInterface rto) throws RemoteException;
    void forceSignalChange(String direction, String rtoId) throws RemoteException;
    List<String> getLogHistory() throws RemoteException;
}  
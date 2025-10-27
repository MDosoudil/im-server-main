package utb.fai;

import java.util.*;

public class ActiveHandlers {
    private static final long serialVersionUID = 1L;
    private HashSet<SocketHandler> activeHandlersSet = new HashSet<SocketHandler>();

    private final Map<String, SocketHandler> nameIndex = new HashMap<>();

    private final Map<String, Set<SocketHandler>> groups = new HashMap<>();

    /**
     * sendMessageToAll - Pole zprávu vem aktivním klientùm kromì sebe sama
     * 
     * @param sender  - reference odesílatele
     * @param message - øetìzec se zprávou
     */

    synchronized void sendMessageToAll(SocketHandler sender, String message) {
        for (SocketHandler handler : activeHandlersSet)
            if (handler != sender) {
                if (!handler.messages.offer(message))
                    System.err.printf("Client %s message queue is full, dropping the message!\n", handler.clientID);
            }
    }

    synchronized boolean setName(SocketHandler handler, String newName) {
        SocketHandler existing = nameIndex.get(newName);
        if (existing != null && existing != handler) return false;
        if (handler.userName != null) {
            SocketHandler cur = nameIndex.get(handler.userName);
            if (cur == handler) nameIndex.remove(handler.userName);
        }
        handler.userName = newName;
        nameIndex.put(newName, handler);
        return true;
    }

    synchronized void joinGroup(String room, SocketHandler handler) {
        groups.computeIfAbsent(room, k -> new HashSet<>()).add(handler);
    }

    synchronized void leaveGroup(String room, SocketHandler handler) {
        Set<SocketHandler> set = groups.get(room);
        if (set != null) {
            set.remove(handler);
            if (set.isEmpty()) groups.remove(room);
        }
    }

 
    synchronized Set<String> groupsOf(SocketHandler handler) {
        Set<String> res = new LinkedHashSet<>();
        for (Map.Entry<String, Set<SocketHandler>> e : groups.entrySet()) {
            if (e.getValue().contains(handler)) res.add(e.getKey());
        }
        return res;
    }

    synchronized void broadcastToGroups(SocketHandler sender, String message) {
        Set<SocketHandler> recipients = new HashSet<>();
        for (Map.Entry<String, Set<SocketHandler>> e : groups.entrySet()) {
            if (e.getValue().contains(sender)) recipients.addAll(e.getValue());
        }
        recipients.remove(sender);
        for (SocketHandler h : recipients) {
            if (!h.messages.offer(message))
                System.err.printf("Client %s message queue is full, dropping the message!\n", h.clientID);
        }
    }

    synchronized boolean sendPrivate(String toName, String message, SocketHandler from) {
        SocketHandler dst = nameIndex.get(toName);
        if (dst == null) return false;
        if (!dst.messages.offer(message))
            System.err.printf("Client %s message queue is full, dropping the private message!\n", dst.clientID);
        return true;
    }

   /**
     * add pøidá do mnoiny aktivních handlerù nový handler.
     * Metoda je sychronizovaná, protoe HashSet neumí multithreading.
     * 
     * @param handler - reference na handler, který se má pøidat.
     * @return true if the set did not already contain the specified element.
     */

    synchronized boolean add(SocketHandler handler) {
        boolean added = activeHandlersSet.add(handler);
        if (added) {
            joinGroup("public", handler);
        }
        return added;
    }

    /**
     * remove odebere z mnoiny aktivních handlerù nový handler.
     * Metoda je sychronizovaná, protoe HashSet neumí multithreading.
     * 
     * @param handler - reference na handler, který se má odstranit
     * @return true if the set did not already contain the specified element.
     */

    synchronized boolean remove(SocketHandler handler) {
        if (handler.userName != null) {
            SocketHandler cur = nameIndex.get(handler.userName);
            if (cur == handler) nameIndex.remove(handler.userName);
        }
        for (Set<SocketHandler> set : groups.values()) {
            set.remove(handler);
        }
        groups.entrySet().removeIf(e -> e.getValue().isEmpty());
        return activeHandlersSet.remove(handler);
    }
}

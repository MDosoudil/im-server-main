package utb.fai;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class SocketHandler {
	/** mySocket je socket, o který se bude tento SocketHandler starat */
	Socket mySocket;

	/** client ID je øetìzec ve formátu <IP_adresa>:<port> */
	String clientID;

	/**
	 * activeHandlers je reference na mnoinu vech právì bìících SocketHandlerù.
	 * Potøebujeme si ji udrovat, abychom mohli zprávu od tohoto klienta
	 * poslat vem ostatním!
	 */
	ActiveHandlers activeHandlers;

	/**
	 * messages je fronta pøíchozích zpráv, kterou musí mít kadý klient svoji
	 * vlastní - pokud bude je pøetíená nebo nefunkèní klientova sí,
	 * èekají zprávy na doruèení právì ve frontì messages
	 */
	ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<String>(20);

	/**
	 * startSignal je synchronizaèní závora, která zaøizuje, aby oba tasky
	 * OutputHandler.run() a InputHandler.run() zaèaly ve stejný okamik.
	 */
	CountDownLatch startSignal = new CountDownLatch(2);

	/** outputHandler.run() se bude starat o OutputStream mého socketu */
	OutputHandler outputHandler = new OutputHandler();
	/** inputHandler.run() se bude starat o InputStream mého socketu */
	InputHandler inputHandler = new InputHandler();
	/**
	 * protoe v outputHandleru nedovedu detekovat uzavøení socketu, pomùe mi
	 * inputFinished
	 */
	volatile boolean inputFinished = false;

	volatile String userName = null;

	public SocketHandler(Socket mySocket, ActiveHandlers activeHandlers) {
		this.mySocket = mySocket;
		clientID = mySocket.getInetAddress().toString() + ":" + mySocket.getPort();
		this.activeHandlers = activeHandlers;
	}

	class OutputHandler implements Runnable {
		public void run() {
			OutputStreamWriter writer;
			try {
				System.err.println("DBG>Output handler starting for " + clientID);
				startSignal.countDown();
				startSignal.await();
				System.err.println("DBG>Output handler running for " + clientID);
				writer = new OutputStreamWriter(mySocket.getOutputStream(), "UTF-8");
				writer.write("\nYou are connected from " + clientID + "\n");
				writer.write("Welcome to IM server.\n");
				writer.write("Enter your name (no spaces), or type '#setMyName <name>'\n");
				writer.write("You are in room 'public' by default. Type #help for help.\n");
				writer.flush();
				while (!inputFinished) {
					String m = messages.take();
					writer.write(m + "\r\n");
					writer.flush();
					System.err.println("DBG>Message sent to " + clientID + ":" + m + "\n");
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			System.err.println("DBG>Output handler for " + clientID + " has finished.");

		}
	}

	class InputHandler implements Runnable {
		public void run() {
			try {
				System.err.println("DBG>Input handler starting for " + clientID);
				startSignal.countDown();
				startSignal.await();
				System.err.println("DBG>Input handler running for " + clientID);
				String request = "";
				/**
				 * v okamiku, kdy nás Thread pool spustí, pøidáme se do mnoiny
				 * vech aktivních handlerù, aby chodily zprávy od ostatních i nám
				 */
				activeHandlers.add(SocketHandler.this); // auto-joins 'public' inside ActiveHandlers.add()
				BufferedReader reader = new BufferedReader(new InputStreamReader(mySocket.getInputStream(), "UTF-8"));
				String line;
				boolean nameSet = (userName != null && !userName.isEmpty());

				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty()) continue;
					if (!nameSet) {
						String candidate = null;
						if (line.startsWith("#setMyName")) {
							String[] parts = line.split("\\s+", 2);
							if (parts.length == 2) candidate = parts[1].trim();
						} else {
							candidate = line;
						}

						if (candidate == null || candidate.isEmpty() || candidate.contains(" ")) {
							messages.offer("Invalid name. Use '#setMyName <name>' and do not use spaces.");
							continue;
						}
						if (activeHandlers.setName(SocketHandler.this, candidate)) {
							nameSet = true;
							messages.offer("Name set to '" + userName + "'. You can now chat.");
						} else {
							messages.offer("Name '" + candidate + "' is already taken. Try another.");
						}
						continue;
					}

					if (line.startsWith("#")) {
						if (line.startsWith("#setMyName")) {
							String[] parts = line.split("\\s+", 2);
							if (parts.length < 2 || parts[1].trim().isEmpty() || parts[1].contains(" ")) {
								messages.offer("Usage: #setMyName <name> (no spaces)");
							} else if (activeHandlers.setName(SocketHandler.this, parts[1].trim())) {
								messages.offer("Name changed to '" + userName + "'.");
							} else {
								messages.offer("Name '" + parts[1].trim() + "' is already taken.");
							}
						} else if (line.startsWith("#sendPrivate")) {
							String[] parts = line.split("\\s+", 3);
							if (parts.length < 3) {
								messages.offer("Usage: #sendPrivate <name> <message>");
							} else {
								String to = parts[1].trim();
								String msg = parts[2];
								boolean ok = activeHandlers.sendPrivate(to, String.format("[private][%s] >> %s", userName, msg), SocketHandler.this);
								if (!ok) messages.offer("User '" + to + "' not found.");
							}
						} else if (line.startsWith("#join")) {
							String[] parts = line.split("\\s+", 2);
							if (parts.length < 2 || parts[1].trim().isEmpty()) {
								messages.offer("Usage: #join <room>");
							} else {
								String room = parts[1].trim();
								activeHandlers.joinGroup(room, SocketHandler.this);
								messages.offer("Joined room '" + room + "'.");
							}
						} else if (line.startsWith("#leave")) {
							String[] parts = line.split("\\s+", 2);
							if (parts.length < 2 || parts[1].trim().isEmpty()) {
								messages.offer("Usage: #leave <room>");
							} else {
								String room = parts[1].trim();
								activeHandlers.leaveGroup(room, SocketHandler.this);
								messages.offer("Left room '" + room + "'.");
							}
						} else if (line.equals("#groups")) {
							Set<String> gs = activeHandlers.groupsOf(SocketHandler.this);
							messages.offer(String.join(",", gs));
						} else if (line.equals("#help")) {
							messages.offer("Commands: #setMyName <name>, #sendPrivate <name> <msg>, #join <room>, #leave <room>, #groups");
						} else {
							messages.offer("Unknown command. Type #help.");
						}
						continue;
					}

					String formatted = String.format("[%s] >> %s", userName, line);
					activeHandlers.broadcastToGroups(SocketHandler.this, formatted);
				}
				inputFinished = true;
				messages.offer("OutputHandler, wakeup and die!");
			} catch (UnknownHostException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				e.printStackTrace();
			} finally {
				// remove yourself from the set of activeHandlers
				synchronized (activeHandlers) {
					activeHandlers.remove(SocketHandler.this);
				}
			}
			System.err.println("DBG>Input handler for " + clientID + " has finished.");
		}

	}
}

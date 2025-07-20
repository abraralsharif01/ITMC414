import java.io.*;
import java.net.*;
import java.util.*;

public class ChatServer {
    private static final int TEXT_PORT = 12345;
    private static final int AUDIO_PORT = 12346;
    private static final int VIDEO_PORT = 12347;
    private static Map<String, List<Socket>> textRooms = new HashMap<>();
    private static Map<String, List<Socket>> audioRooms = new HashMap<>();
    private static Map<String, List<Socket>> videoRooms = new HashMap<>();

    public static void main(String[] args) {
        new Thread(() -> startTextServer()).start();
        new Thread(() -> startAudioServer()).start();
        new Thread(() -> startVideoServer()).start();
    }

    private static void startTextServer() {
        startServer(TEXT_PORT, "TEXT", textRooms);
    }

    private static void startAudioServer() {
        startServer(AUDIO_PORT, "AUDIO", audioRooms);
    }

    private static void startVideoServer() {
        startServer(VIDEO_PORT, "VIDEO", videoRooms);
    }

    private static void startServer(int port, String type, Map<String, List<Socket>> rooms) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println(type + " Server running on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket, type, rooms)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket socket, String type, Map<String, List<Socket>> rooms) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            String clientInfo = dis.readUTF();
            String[] parts = clientInfo.split(":");
            String username = parts[0];
            String roomId = parts[1];

            synchronized (rooms) {
                rooms.computeIfAbsent(roomId, k -> new ArrayList<>()).add(socket);
            }
            System.out.println(username + " joined " + roomId + " (" + type + ")");

            if (type.equals("TEXT")) {
                handleTextClient(socket, dis, roomId, rooms);
            } else if (type.equals("AUDIO")) {
                handleAudioClient(socket, dis, roomId, rooms);
            } else if (type.equals("VIDEO")) {
                handleVideoClient(socket, dis, roomId, rooms);
            }
        } catch (IOException e) {
            System.out.println("Client connection error: " + e.getMessage());
        } finally {
            synchronized (rooms) {
                rooms.values().forEach(room -> room.remove(socket));
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private static void handleTextClient(Socket socket, DataInputStream dis, 
                                      String roomId, Map<String, List<Socket>> rooms) throws IOException {
        while (true) {
            String message = dis.readUTF();
            broadcastText(roomId, socket, message, rooms);
        }
    }

    private static void handleAudioClient(Socket socket, DataInputStream dis,
                                        String roomId, Map<String, List<Socket>> rooms) throws IOException {
        byte[] buffer = new byte[4096];
        while (true) {
            int bytesRead = dis.read(buffer);
            if (bytesRead == -1) break;
            
            broadcastMedia(roomId, socket, buffer, bytesRead, rooms, "AUDIO");
        }
    }

    private static void handleVideoClient(Socket socket, DataInputStream dis,
                                       String roomId, Map<String, List<Socket>> rooms) throws IOException {
        // قراءة حجم الإطار أولاً
        while (true) {
            int frameSize = dis.readInt();
            if (frameSize == -1) break;
            
            byte[] frameData = new byte[frameSize];
            dis.readFully(frameData);
            
            broadcastMedia(roomId, socket, frameData, frameSize, rooms, "VIDEO");
        }
    }

    private static void broadcastMedia(String roomId, Socket sender, 
                                     byte[] data, int dataLength,
                                     Map<String, List<Socket>> rooms, String type) {
        List<Socket> roomClients;
        synchronized (rooms) {
            roomClients = new ArrayList<>(rooms.getOrDefault(roomId, Collections.emptyList()));
        }
        
        for (Socket clientSocket : roomClients) {
            if (clientSocket != sender && !clientSocket.isClosed()) {
                try {
                    if (type.equals("VIDEO")) {
                        DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
                        dos.writeInt(dataLength);
                        dos.write(data, 0, dataLength);
                    } else {
                        OutputStream out = clientSocket.getOutputStream();
                        out.write(data, 0, dataLength);
                    }
                } catch (IOException e) {
                    System.out.println("Error sending " + type + " to client: " + e.getMessage());
                    try { clientSocket.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private static void broadcastText(String roomId, Socket sender, 
                                    String message, Map<String, List<Socket>> rooms) {
        List<Socket> roomClients;
        synchronized (rooms) {
            roomClients = new ArrayList<>(rooms.getOrDefault(roomId, Collections.emptyList()));
        }
        
        for (Socket socket : roomClients) {
            if (socket != sender && !socket.isClosed()) {
                try {
                    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                    dos.writeUTF(message);
                    dos.flush();
                } catch (IOException e) {
                    System.out.println("Error sending text to client: " + e.getMessage());
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
        }
    }
}
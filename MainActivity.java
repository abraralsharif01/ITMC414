package com.example.multicast;


import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MainActivity extends AppCompatActivity {

    Button sendButton, receiveButton;

    private static final String MULTICAST_ADDRESS = "230.0.0.0";
    private static final int MULTICAST_PORT = 4446;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sendButton = findViewById(R.id.sendButton);
        receiveButton = findViewById(R.id.receiveButton);

        sendButton.setOnClickListener(v -> sendMulticastMessage("Hello from Android!"));

        receiveButton.setOnClickListener(v -> startMulticastReceiver());
    }

    private void sendMulticastMessage(String message) {
        new Thread(() -> {
            try {
                InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
                MulticastSocket socket = new MulticastSocket();
                byte[] buf = message.getBytes();

                DatagramPacket packet = new DatagramPacket(buf, buf.length, group, MULTICAST_PORT);
                socket.send(packet);
                socket.close();

                runOnUiThread(() -> Toast.makeText(this, "Message sent!", Toast.LENGTH_SHORT).show());

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Failed to send message.", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void startMulticastReceiver() {
        new Thread(() -> {
            try {
                InetAddress group = InetAddress.getByName(MULTICAST_ADDRESS);
                MulticastSocket socket = new MulticastSocket(MULTICAST_PORT);
                socket.joinGroup(group);

                byte[] buf = new byte[256];

                while (true) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    String received = new String(packet.getData(), 0, packet.getLength());

                    runOnUiThread(() -> Toast.makeText(this, "Received: " + received, Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, "Failed to receive message.", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}

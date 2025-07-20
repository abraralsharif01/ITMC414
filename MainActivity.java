package com.example.socketchatapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputLayout;

public class MainActivity extends AppCompatActivity {
    private EditText serverIpInput, usernameInput, roomIdInput, portInput;
    private SharedPreferences prefs;
    private static final int DEFAULT_PORT = 1234;
    private static final int CLIENT_1_PORT = 1234;
    private static final int CLIENT_2_PORT = 12340;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // تهيئة العناصر
        TextInputLayout serverIpLayout = findViewById(R.id.serverIpLayout);
        TextInputLayout usernameLayout = findViewById(R.id.usernameLayout);
        TextInputLayout roomIdLayout = findViewById(R.id.roomIdLayout);
        TextInputLayout portLayout = findViewById(R.id.portLayout);

        serverIpInput = serverIpLayout.getEditText();
        usernameInput = usernameLayout.getEditText();
        roomIdInput = roomIdLayout.getEditText();
        portInput = portLayout.getEditText();

        Button chatBtn = findViewById(R.id.chatBtn);
        Button audioCallBtn = findViewById(R.id.audioCallBtn);
        Button videoCallBtn = findViewById(R.id.videoCallBtn);
        Button settingsBtn = findViewById(R.id.settingsBtn);

        // تحميل الإعدادات
        prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        serverIpInput.setText(prefs.getString("server_ip", "192.168.1.100"));
        usernameInput.setText(prefs.getString("username", ""));
        roomIdInput.setText(prefs.getString("room_id", "general"));
        portInput.setText(String.valueOf(prefs.getInt("server_port", DEFAULT_PORT)));

        // إعداد الأزرار
        chatBtn.setOnClickListener(v -> startChat());
        audioCallBtn.setOnClickListener(v -> startCall(AudioCallActivity.class));
        videoCallBtn.setOnClickListener(v -> startCall(VideoCallActivity.class));
        settingsBtn.setOnClickListener(v -> {
            // يمكنك إضافة إعدادات إضافية هنا
            Toast.makeText(this, "فتح الإعدادات", Toast.LENGTH_SHORT).show();
        });
    }

    private void startChat() {
        if (validateInputs()) {
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra("SERVER_IP", serverIpInput.getText().toString().trim());
            intent.putExtra("SERVER_PORT", Integer.parseInt(portInput.getText().toString().trim()));
            intent.putExtra("USERNAME", usernameInput.getText().toString().trim());
            intent.putExtra("ROOM_ID", roomIdInput.getText().toString().trim());
            saveSettings();
            startActivity(intent);
        }
    }

    private void startCall(Class<?> activityClass) {
        if (validateInputs()) {
            Intent intent = new Intent(this, activityClass);
            intent.putExtra("SERVER_IP", serverIpInput.getText().toString().trim());
            intent.putExtra("SERVER_PORT", Integer.parseInt(portInput.getText().toString().trim()));
            intent.putExtra("USERNAME", usernameInput.getText().toString().trim());
            intent.putExtra("ROOM_ID", roomIdInput.getText().toString().trim());
            saveSettings();
            startActivity(intent);
        }
    }

    private boolean validateInputs() {
        if (serverIpInput.getText().toString().trim().isEmpty() ||
                usernameInput.getText().toString().trim().isEmpty() ||
                portInput.getText().toString().trim().isEmpty()) {

            Toast.makeText(this, "الرجاء إدخال جميع البيانات المطلوبة", Toast.LENGTH_SHORT).show();
            return false;
        }

        try {
            Integer.parseInt(portInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "منفذ الخادم يجب أن يكون رقماً", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private void saveSettings() {
        prefs.edit()
                .putString("server_ip", serverIpInput.getText().toString().trim())
                .putString("username", usernameInput.getText().toString().trim())
                .putString("room_id", roomIdInput.getText().toString().trim())
                .putInt("server_port", Integer.parseInt(portInput.getText().toString().trim()))
                .apply();
    }
}
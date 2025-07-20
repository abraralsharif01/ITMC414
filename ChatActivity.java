package com.example.socketchatapp;

import android.view.View; // أضف هذا السطر
import com.bumptech.glide.Glide; // تأكد من وجوده
import java.io.DataInputStream; // أضف هذا السطر
import java.io.DataOutputStream; // أضف هذا السطر

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.example.socketchatapp.databinding.ActivityChatBinding;
import com.example.socketchatapp.databinding.ItemMessageBinding;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatActivity extends AppCompatActivity {
    private static final int SOCKET_TIMEOUT = 30000; // 30 ثانية
    private static final int HEARTBEAT_INTERVAL = 15000; // 15 ثانية
    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private Handler handler = new Handler(Looper.getMainLooper());
    private Socket socket;
    private DataInputStream dis;
    private DataOutputStream dos;
    private String username, roomId, serverIp;
    private int serverPort, clientId;
    private DatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        dbHelper = new DatabaseHelper(this);

        username = getIntent().getStringExtra("USERNAME");
        roomId = getIntent().getStringExtra("ROOM_ID");
        serverIp = getIntent().getStringExtra("SERVER_IP");
        serverPort = getIntent().getIntExtra("SERVER_PORT", 12345);
        clientId = getIntent().getIntExtra("CLIENT_ID", 1);

        messages.addAll(dbHelper.getAllMessages(roomId));

        binding.toolbar.setTitle("غرفة: " + roomId + " | عميل: " + clientId);
        binding.toolbar.setSubtitle(username);
        setSupportActionBar(binding.toolbar);

        adapter = new MessageAdapter(messages);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        binding.btnSend.setOnClickListener(v -> sendMessage());
        binding.btnAttach.setOnClickListener(v -> showAttachmentOptions());

        connectToServer();
    }

    private void connectToServer() {
        executor.execute(() -> {
            try {
                socket = new Socket(serverIp, serverPort);
                dis = new DataInputStream(socket.getInputStream());
                dos = new DataOutputStream(socket.getOutputStream());

                dos.writeUTF(String.valueOf(clientId));
                dos.writeUTF(roomId);
                dos.writeUTF(username);
                dos.flush();

                startMessageReceiver();
            } catch (IOException e) {
                handler.post(() -> {
                    Toast.makeText(this, "خطأ في الاتصال: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    reconnectToServer();
                });
            }
        });
    }

    private void startMessageReceiver() {
        executor.execute(() -> {
            try {
                while (!socket.isClosed()) {
                    String typeStr = dis.readUTF();
                    Message.MessageType type = Message.MessageType.valueOf(typeStr);
                    String senderId = dis.readUTF();

                    if (type == Message.MessageType.TEXT) {
                        String message = dis.readUTF();
                        handler.post(() -> {
                            boolean isSent = senderId.equals(String.valueOf(clientId));
                            Message newMessage = new Message(message, isSent, senderId);
                            addMessage(newMessage);
                            dbHelper.addMessage(newMessage, roomId);
                        });
                    } else {
                        String fileName = dis.readUTF();
                        long fileSize = dis.readLong();
                        byte[] fileData = new byte[(int) fileSize];
                        dis.readFully(fileData);

                        String filePath = saveFile(fileName, fileData);

                        handler.post(() -> {
                            boolean isSent = senderId.equals(String.valueOf(clientId));
                            Message newMessage = new Message(filePath, isSent, senderId, type);
                            addMessage(newMessage);
                            dbHelper.addMessage(newMessage, roomId);
                        });
                    }
                }
            } catch (IOException e) {
                handler.post(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(this, "انقطع الاتصال", Toast.LENGTH_SHORT).show();
                        reconnectToServer();
                    }
                });
            }
        });
    }

    private String saveFile(String fileName, byte[] data) throws IOException {
        File dir = new File(getFilesDir(), "chat_files");
        if (!dir.exists()) dir.mkdirs();

        File file = new File(dir, fileName);
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(data);
        fos.close();

        return file.getAbsolutePath();
    }

    private void sendMessage() {
        String message = binding.messageInput.getText().toString().trim();
        if (!TextUtils.isEmpty(message)) {
            executor.execute(() -> {
                try {
                    dos.writeUTF("TEXT");
                    dos.writeUTF(String.valueOf(clientId));
                    dos.writeUTF(message);
                    dos.flush();

                    handler.post(() -> {
                        Message newMessage = new Message(message, true, String.valueOf(clientId));
                        addMessage(newMessage);
                        dbHelper.addMessage(newMessage, roomId);
                        binding.messageInput.setText("");
                    });
                } catch (IOException e) {
                    handler.post(() ->
                            Toast.makeText(this, "فشل إرسال الرسالة", Toast.LENGTH_SHORT).show()
                    );
                }
            });
        }
    }

    private void showAttachmentOptions() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        startActivityForResult(Intent.createChooser(intent, "اختر ملف"), 101);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                String filePath = getPathFromUri(fileUri);
                if (filePath != null) {
                    String mimeType = getContentResolver().getType(fileUri);
                    Message.MessageType type = (mimeType != null && mimeType.startsWith("image/"))
                            ? Message.MessageType.IMAGE
                            : Message.MessageType.FILE;
                    sendFile(filePath, type);
                }
            }
        }
    }

    private String getPathFromUri(Uri uri) {
        String[] projection = {MediaStore.Images.Media.DATA};
        Cursor cursor = getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            cursor.moveToFirst();
            String path = cursor.getString(column_index);
            cursor.close();
            return path;
        }
        return uri.getPath();
    }

    private void sendFile(final String filePath, final Message.MessageType type) {
        executor.execute(() -> {
            try {
                File file = new File(filePath);
                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[(int) file.length()];
                fis.read(buffer);
                fis.close();

                dos.writeUTF(type.name());
                dos.writeUTF(String.valueOf(clientId));
                dos.writeUTF(file.getName());
                dos.writeLong(buffer.length);
                dos.write(buffer);
                dos.flush();

                handler.post(() -> {
                    Message newMessage = new Message(filePath, true, String.valueOf(clientId), type);
                    addMessage(newMessage);
                    dbHelper.addMessage(newMessage, roomId);
                });
            } catch (IOException e) {
                handler.post(() ->
                        Toast.makeText(this, "فشل إرسال الملف", Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private void addMessage(Message message) {
        messages.add(message);
        adapter.notifyItemInserted(messages.size() - 1);
        binding.recyclerView.smoothScrollToPosition(messages.size() - 1);
    }

    private void reconnectToServer() {
        handler.postDelayed(this::connectToServer, 3000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            executor.shutdown();
            dbHelper.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {
        private final List<Message> messages;

        public MessageAdapter(List<Message> messages) {
            this.messages = messages;
        }

        @NonNull
        @Override
        public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemMessageBinding binding = ItemMessageBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);

            // تعيين معلمات التخطيط للتحكم في الموضع
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)
                    binding.messageCard.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;

            return new MessageViewHolder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
            holder.bind(messages.get(position));
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        class MessageViewHolder extends RecyclerView.ViewHolder {
            private final ItemMessageBinding binding;

            public MessageViewHolder(ItemMessageBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }

            public void bind(Message message) {
                binding.messageText.setVisibility(View.GONE);
                binding.messageImage.setVisibility(View.GONE);
                binding.fileNameText.setVisibility(View.GONE);

                switch (message.getType()) {
                    case TEXT:
                        binding.messageText.setVisibility(View.VISIBLE);
                        binding.messageText.setText(message.getText());
                        break;
                    case IMAGE:
                        binding.messageImage.setVisibility(View.VISIBLE);
                        Glide.with(binding.getRoot().getContext())
                                .load(new File(message.getFilePath()))
                                .into(binding.messageImage);
                        break;
                    case FILE:
                        binding.fileNameText.setVisibility(View.VISIBLE);
                        binding.fileNameText.setText(new File(message.getFilePath()).getName());
                        break;
                }

                binding.messageTime.setText(message.getTime());

                // تحديد موضع الرسالة حسب كونها مرسلة أو مستقبلة
                ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)
                        binding.messageCard.getLayoutParams();

                if (message.isSent()) {
                    // الرسائل المرسلة على اليمين
                    layoutParams.leftMargin = 100;  // هامش من اليسار
                    layoutParams.rightMargin = 0;   // لا هامش من اليمين
                    binding.getRoot().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                } else {
                    // الرسائل المستقبلة على اليسار
                    layoutParams.rightMargin = 100; // هامش من اليمين
                    layoutParams.leftMargin = 0;    // لا هامش من اليسار
                    binding.getRoot().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                }

                binding.messageCard.setLayoutParams(layoutParams);
                binding.messageCard.setCardBackgroundColor(
                        ContextCompat.getColor(binding.getRoot().getContext(),
                                message.isSent() ? R.color.message_sent : R.color.message_received));
            }
        }
    }

    static class Message {
        private String text;
        private String filePath;
        private String time;
        private boolean sent;
        private String senderId;
        private MessageType type;

        public enum MessageType {
            TEXT, IMAGE, FILE
        }

        public Message(String text, boolean sent, String senderId) {
            this.text = text;
            this.sent = sent;
            this.senderId = senderId;
            this.type = MessageType.TEXT;
            this.time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        }

        public Message(String filePath, boolean sent, String senderId, MessageType type) {
            this.filePath = filePath;
            this.sent = sent;
            this.senderId = senderId;
            this.type = type;
            this.time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        }

        public String getText() {
            return text;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public boolean isSent() {
            return sent;
        }

        public String getSenderId() {
            return senderId;
        }

        public MessageType getType() {
            return type;
        }
    }
}

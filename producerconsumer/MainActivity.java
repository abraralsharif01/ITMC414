package com.example.producerconsumer;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private Q q;
    private TextView infoText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        infoText = findViewById(R.id.infoText);

        q = new Q(this);

        new Producer(q);
        new Consumer(q);
    }

    public void updateUI(String message) {
        runOnUiThread(() -> {
            infoText.append(message + "\n");

            // نجعل TextView ينزل تلقائيًا للأسفل لعرض أحدث قيمة
            final int scrollAmount = infoText.getLayout().getLineTop(infoText.getLineCount()) - infoText.getHeight();
            if (scrollAmount > 0)
                infoText.scrollTo(0, scrollAmount);
            else
                infoText.scrollTo(0, 0);
        });
    }

    class Producer implements Runnable {
        private final Q q;

        Producer(Q q) {
            this.q = q;
            new Thread(this, "Producer").start();
        }

        @Override
        public void run() {
            int i = 0;
            while (true) {
                q.put(i++);
            }
        }
    }

    class Consumer implements Runnable {
        private final Q q;

        Consumer(Q q) {
            this.q = q;
            new Thread(this, "Consumer").start();
        }

        @Override
        public void run() {
            while (true) {
                q.get();
            }
        }
    }

    class Q {
        private int n;
        private boolean valueSet = false;
        private final MainActivity activity;

        Q(MainActivity activity) {
            this.activity = activity;
        }

        synchronized int get() {
            while (!valueSet) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            String message = "Got: " + n;
            activity.updateUI(message);
            valueSet = false;
            notify();
            return n;
        }

        synchronized void put(int n) {
            while (valueSet) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            this.n = n;
            valueSet = true;
            String message = "Put: " + n;
            activity.updateUI(message);
            notify();
        }
    }
}

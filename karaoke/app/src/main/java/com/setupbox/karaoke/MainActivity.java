package com.setupbox.karaoke;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setText("Karaoke");
        t.setTextSize(48);
        setContentView(t);
    }
}

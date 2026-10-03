package ai.moeru.airipocket;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FloatingCompanionPlugin.class);
        super.onCreate(savedInstanceState);
    }
}

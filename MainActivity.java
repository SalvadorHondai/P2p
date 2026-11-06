package app.p2p.server;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.util.List;

public class MainActivity extends Activity {
    private TextView st, ad;
    private Button tg;
    private final Handler h = new Handler(Looper.getMainLooper());

    private Button btn(LinearLayout l, String t, View.OnClickListener c) {
        Button b = new Button(this); b.setText(t); b.setOnClickListener(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = 24; l.addView(b, p); return b;
    }
    private void open(String path) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + ServerService.PORT + path))); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(48, 96, 48, 48);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView t = new TextView(this); t.setText("سرور پیام‌رسان P2P"); t.setTextSize(24); l.addView(t);
        st = new TextView(this); st.setTextSize(16); st.setPadding(0, 24, 0, 8); l.addView(st);
        ad = new TextView(this); ad.setTextSize(18); ad.setTextIsSelectable(true); ad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); l.addView(ad);
        tg = btn(l, "", v -> { if (ServerService.running) stopService(new Intent(this, ServerService.class)); else startForegroundService(new Intent(this, ServerService.class)); h.postDelayed(this::refresh, 700); });
        btn(l, "باز کردن پیام‌رسان (روی همین گوشی)", v -> open("/"));
        btn(l, "نمایش QR برای دعوت دستگاه‌های دیگر", v -> open("/invite"));
        TextView tip = new TextView(this); tip.setPadding(0, 32, 0, 0);
        tip.setText("دستگاه‌های دیگر فقط باید به همین وای‌فای یا هات‌اسپات وصل باشند و آدرس بالا را در مرورگر باز کنند. اینترنت لازم نیست.");
        l.addView(tip); setContentView(l);
        if (!ServerService.running) startForegroundService(new Intent(this, ServerService.class));   // خودکار روشن می‌شود
        h.postDelayed(this::refresh, 700);
    }
    @Override protected void onResume() { super.onResume(); refresh(); }

    private void refresh() {
        boolean on = ServerService.running;
        st.setText(on ? "● سرور روشن است" : "○ سرور خاموش است");
        tg.setText(on ? "خاموش کردن سرور" : "روشن کردن سرور");
        List<String> u = ServerService.urls();
        ad.setText(!on ? "" : u.isEmpty() ? "به وای‌فای وصل شوید یا هات‌اسپات را روشن کنید" : String.join("\n", u));
    }
}

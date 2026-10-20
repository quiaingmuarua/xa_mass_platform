package com.xa.mass.android.workerdemo;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded local inbox and interaction surface, not a view of Server task state. */
public final class MessagesActivity extends Activity {
    private AndroidMessageDemo messages;
    private AndroidMessageNotifications notifications;
    private LinearLayout rows;
    private View list, detail;
    private TextView notice, identity, body, feedback;
    private EditText reply;
    private Button submit;
    private String selected;
    private boolean active;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final Runnable changed = this::requestRender;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_messages);
        AndroidWorkerDemoApplication app = (AndroidWorkerDemoApplication) getApplication();
        messages = app.messages();
        notifications = app.messageNotifications();
        rows = findViewById(R.id.messageRows);
        list = findViewById(R.id.messageList);
        detail = findViewById(R.id.messageDetail);
        notice = findViewById(R.id.messageNotice);
        identity = findViewById(R.id.messageIdentity);
        body = findViewById(R.id.messageBody);
        feedback = findViewById(R.id.messageFeedback);
        reply = findViewById(R.id.messageReply);
        submit = findViewById(R.id.messageReplyButton);
        selected = saved == null ? token(getIntent()) : saved.getString("messageToken");
        findViewById(R.id.messageBack).setOnClickListener(view -> {
            selected = null;
            reply.setText("");
            render();
        });
        findViewById(R.id.messageRefresh).setOnClickListener(view -> render());
        findViewById(R.id.messagePermission).setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 73);
            } else {
                startActivity(Build.VERSION.SDK_INT >= 26
                        ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + getPackageName())));
            }
        });
        submit.setOnClickListener(view -> {
            if (selected == null) return;
            try {
                String outcome = messages.reply(selected, reply.getText().toString(), UUID.randomUUID().toString());
                render();
                feedback.setText(outcome);
                reply.setText("");
            } catch (IllegalArgumentException invalid) {
                reply.setError("请输入 1–4096 字符的非空回复");
            }
        });
        render();
    }

    @Override protected void onStart() { super.onStart(); messages.addListener(changed); }
    @Override protected void onResume() {
        super.onResume();
        active = true;
        notifications.permissionChanged();
        render();
    }
    @Override protected void onPause() { active = false; super.onPause(); }
    @Override protected void onStop() { messages.removeListener(changed); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putString("messageToken", selected);
        super.onSaveInstanceState(saved);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        selected = token(intent);
        reply.setText("");
        render();
        reportOpening();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) reportOpening();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == 73) { notifications.permissionChanged(); render(); }
    }

    private void requestRender() {
        if (refreshPending.compareAndSet(false, true)) getWindow().getDecorView().post(() -> {
            refreshPending.set(false);
            if (active && !isDestroyed() && !isFinishing()) render();
        });
    }
    private void render() {
        notice.setText("仅当前 App 进程的本地演示；不发送真实短信。\n"
                + (notifications.available() ? "通知已开启；只展示最近一条消息。" : "通知未开启；仍可在此打开和回复。"));
        boolean showingDetail = selected != null;
        list.setVisibility(showingDetail ? View.GONE : View.VISIBLE);
        detail.setVisibility(showingDetail ? View.VISIBLE : View.GONE);
        if (showingDetail) {
            AndroidMessageDemo.Message message = messages.message(selected);
            if (message == null) {
                identity.setText("消息已过期或属于上一个进程");
                body.setText("");
                feedback.setText("旧关联不可恢复，请查看平台已保存的结果");
                submit.setEnabled(false); reply.setEnabled(false);
            } else {
                identity.setText(message.recipient + "\n" + message.messageId);
                body.setText(message.body);
                feedback.setText(message.feedback);
                submit.setEnabled(message.receiptAvailable); reply.setEnabled(message.receiptAvailable);
            }
            return;
        }
        rows.removeAllViews();
        List<AndroidMessageDemo.Message> recent = messages.recentMessages();
        if (recent.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无本地受理记录。请从 Messages 创建并启动任务。");
            rows.addView(empty);
        }
        for (AndroidMessageDemo.Message message : recent) {
            Button open = new Button(this);
            open.setText(message.recipient + "\n" + message.messageId);
            open.setAllCaps(false);
            open.setContentDescription("message:" + message.messageId);
            open.setOnClickListener(view -> {
                selected = message.token;
                reply.setText("");
                render();
                body.post(this::reportOpening);
            });
            rows.addView(open);
        }
    }
    private void reportOpening() {
        if (active && hasWindowFocus() && selected != null && messages.message(selected) != null)
            feedback.setText(messages.opened(selected));
    }
    private static String token(Intent intent) {
        return intent != null && intent.getData() != null && "xa-messages".equals(intent.getData().getScheme())
                && "open".equals(intent.getData().getAuthority()) ? intent.getData().getLastPathSegment() : null;
    }
}

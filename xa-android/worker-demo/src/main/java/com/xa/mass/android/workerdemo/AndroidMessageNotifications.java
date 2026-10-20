package com.xa.mass.android.workerdemo;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** One Android presentation of the local channel; notification publication is not delivery evidence. */
final class AndroidMessageNotifications {
    static final String CHANNEL = "local-message-demo", REPLY = "messageReply";
    static final int NOTIFICATION_ID = 7301;
    private final Context context;
    private final AndroidMessageDemo messages;
    private final NotificationManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean pending = new AtomicBoolean();
    private String postedToken;

    AndroidMessageNotifications(Context context, AndroidMessageDemo messages) {
        this.context = context.getApplicationContext();
        this.messages = messages;
        manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID); // Notifications cannot recover a previous process's Reporter.
            if (Build.VERSION.SDK_INT >= 26)
                manager.createNotificationChannel(new NotificationChannel(CHANNEL, "本地消息演示", NotificationManager.IMPORTANCE_LOW));
        }
    }

    void requestRefresh() {
        if (pending.compareAndSet(false, true)) main.post(() -> {
            pending.set(false);
            refresh();
        });
    }

    boolean available() {
        if (manager == null || (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED))
            return false;
        if (!manager.areNotificationsEnabled()) return false;
        return Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL) == null
                || manager.getNotificationChannel(CHANNEL).getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    void permissionChanged() { postedToken = null; requestRefresh(); }
    void dismiss(String token) {
        if (manager != null && token != null && token.equals(postedToken)) manager.cancel(NOTIFICATION_ID);
    }

    private void refresh() {
        if (manager == null) return;
        List<AndroidMessageDemo.Message> recent = messages.recentMessages();
        if (recent.isEmpty()) { manager.cancel(NOTIFICATION_ID); return; }
        AndroidMessageDemo.Message message = recent.get(0);
        if (!message.receiptAvailable || message.interacted) { manager.cancel(NOTIFICATION_ID); return; }
        if (message.token.equals(postedToken) || !available()) return;
        try {
            manager.notify(NOTIFICATION_ID, notification(message));
            postedToken = message.token;
        } catch (RuntimeException ignored) {
            // UI and permissions cannot undo the channel's confirmed acceptance.
        }
    }

    Notification notification(AndroidMessageDemo.Message message) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        Intent open = new Intent(context, MessagesActivity.class)
                .setData(uri("open", message.token))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent reply = new Intent(context, MessageReplyReceiver.class).setData(uri("reply", message.token));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent replyIntent = PendingIntent.getBroadcast(context, 0, reply, flags);
        Notification.Action action = new Notification.Action.Builder(android.R.drawable.ic_menu_send, "回复", replyIntent)
                .addRemoteInput(new RemoteInput.Builder(REPLY).setLabel("回复演示消息").build())
                .setAllowGeneratedReplies(false).build();
        return builder.setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("本地演示 · " + message.recipient)
                .setContentText(message.body)
                .setStyle(new Notification.BigTextStyle().bigText(message.body))
                .setContentIntent(content).addAction(action)
                .setOnlyAlertOnce(true).setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build();
    }

    static Uri uri(String action, String token) {
        return new Uri.Builder().scheme("xa-messages").authority(action).appendPath(token).build();
    }
}

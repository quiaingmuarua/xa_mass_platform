package com.xa.mass.android.workerdemo;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import java.util.UUID;

/** Explicit, non-exported Android notification callback. No task is created or replayed here. */
public final class MessageReplyReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null || !"xa-messages".equals(intent.getData().getScheme())
                || !"reply".equals(intent.getData().getAuthority())) return;
        Bundle result = RemoteInput.getResultsFromIntent(intent);
        CharSequence reply = result == null ? null : result.getCharSequence(AndroidMessageNotifications.REPLY);
        if (reply == null) return;
        AndroidWorkerDemoApplication application = (AndroidWorkerDemoApplication) context.getApplicationContext();
        String feedback;
        try {
            feedback = application.messages().reply(intent.getData().getLastPathSegment(),
                    reply.toString(), UUID.randomUUID().toString());
        } catch (IllegalArgumentException invalid) {
            feedback = "回复须为 1–4096 字符的非空文本";
        }
        application.messageNotifications().dismiss(intent.getData().getLastPathSegment());
        Toast.makeText(context, feedback, Toast.LENGTH_LONG).show();
    }
}

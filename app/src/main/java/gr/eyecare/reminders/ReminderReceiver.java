package gr.eyecare.reminders;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.time.LocalDate;
import java.time.ZonedDateTime;

public final class ReminderReceiver extends BroadcastReceiver {
    private static final String ALARM = "gr.eyecare.reminders.ALARM";
    private static final String TEST = "test";
    private static final String CHANNEL = "eye_drops";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ALARM.equals(intent.getAction())) {
            scheduleAll(context);
            return;
        }
        String id = intent.getData().getLastPathSegment();
        if (TEST.equals(id)) {
            notify(context, id, "Δοκιμή EyeCare", "Η δοκιμαστική ειδοποίηση έφτασε.");
            return;
        }

        LocalDate today = LocalDate.now();
        ReminderStore store = new ReminderStore(context);
        for (ReminderStore.Reminder reminder : store.reminders()) {
            if (!reminder.id().equals(id)) continue;
            schedule(context, reminder);
            if (today.toString().equals(intent.getStringExtra("date"))
                    && (reminder.through() == null || !today.isAfter(reminder.through()))
                    && !store.taken(reminder, today)) {
                notify(context, id, "Υπενθύμιση κολλυρίου", reminder.medication()
                        + " · " + reminder.time());
            }
            return;
        }
    }

    static void scheduleAll(Context context) {
        for (ReminderStore.Reminder reminder : new ReminderStore(context).reminders()) {
            schedule(context, reminder);
        }
    }

    static void schedule(Context context, ReminderStore.Reminder reminder) {
        ZonedDateTime next = nextOccurrence(reminder, ZonedDateTime.now());
        if (next == null) {
            cancel(context, reminder.id());
            return;
        }
        setAlarm(context, next.toInstant().toEpochMilli(),
                operation(context, reminder.id(), next.toLocalDate().toString()));
    }

    static ZonedDateTime nextOccurrence(ReminderStore.Reminder reminder, ZonedDateTime now) {
        ZonedDateTime next = now.toLocalDate().atTime(reminder.time()).atZone(now.getZone());
        if (!next.isAfter(now)) {
            next = now.toLocalDate().plusDays(1).atTime(reminder.time()).atZone(now.getZone());
        }
        return reminder.through() == null || !next.toLocalDate().isAfter(reminder.through())
                ? next : null;
    }

    static void scheduleTest(Context context) {
        setAlarm(context, System.currentTimeMillis() + 15_000L, operation(context, TEST, ""));
    }

    static boolean exactAllowed(Context context) {
        return Build.VERSION.SDK_INT < 31
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void cancel(Context context, String id) {
        context.getSystemService(AlarmManager.class).cancel(operation(context, id, ""));
        dismiss(context, id);
    }

    static void dismiss(Context context, String id) {
        context.getSystemService(NotificationManager.class).cancel(id, 1);
    }

    static NotificationManager notifications(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Υπενθυμίσεις EyeCare",
                NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(channel);
        return manager;
    }

    static boolean remindersEnabled(Context context) {
        NotificationManager manager = notifications(context);
        return manager.areNotificationsEnabled()
                && manager.getNotificationChannel(CHANNEL).getImportance()
                    != NotificationManager.IMPORTANCE_NONE;
    }

    private static void setAlarm(Context context, long time, PendingIntent operation) {
        AlarmManager manager = context.getSystemService(AlarmManager.class);
        if (exactAllowed(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation);
                return;
            } catch (SecurityException revokedDuringScheduling) {
                // Permission may be revoked after the check.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation);
    }

    private static PendingIntent operation(Context context, String id, String date) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(ALARM)
                .setData(Uri.parse("eyecare://alarm/" + id)).putExtra("date", date);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void notify(Context context, String id, String title, String message) {
        if (!remindersEnabled(context)) return;
        Intent launch = new Intent(context, MainActivity.class)
                .setData(Uri.parse("eyecare://today/" + id));
        PendingIntent open = PendingIntent.getActivity(context, 0, launch,
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(gr.eyecare.reminders.R.drawable.ic_drop)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .build();
        try {
            notifications(context).notify(id, 1, notification);
        } catch (SecurityException revokedDuringDelivery) {
            // The user can disable notifications at any time.
        }
    }
}

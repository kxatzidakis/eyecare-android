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
import java.time.Instant;
import java.time.ZonedDateTime;

public final class ReminderReceiver extends BroadcastReceiver {
    private static final String ALARM = "gr.eyecare.reminders.ALARM";
    private static final String NEXT = "next";
    private static final String TEST = "test";
    private static final String CHANNEL = "eye_drops";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ALARM.equals(intent.getAction())) {
            if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                cancelLegacyAlarms(context);
            }
            scheduleAll(context);
            return;
        }

        String id = intent.getData().getLastPathSegment();
        if (TEST.equals(id)) {
            notify(context, id, "Δοκιμή EyeCare", "Η δοκιμαστική ειδοποίηση έφτασε.");
            return;
        }
        if (NEXT.equals(id)) {
            deliverDueReminders(context, intent.getLongExtra("due", 0L));
        }
        scheduleAll(context);
    }

    private void deliverDueReminders(Context context, long dueMillis) {
        if (dueMillis == 0L) return;
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime due = Instant.ofEpochMilli(dueMillis).atZone(now.getZone());
        LocalDate today = now.toLocalDate();
        if (!today.equals(due.toLocalDate())) return;

        ReminderStore store = new ReminderStore(context);
        for (ReminderStore.Reminder reminder : store.reminders()) {
            if (reminder.through() != null && today.isAfter(reminder.through())) continue;
            ZonedDateTime scheduled = today.atTime(reminder.time()).atZone(now.getZone());
            if (!scheduled.isBefore(due) && !scheduled.isAfter(now) && !store.taken(reminder, today)) {
                notify(context, reminder.id(), "Υπενθύμιση φαρμάκου",
                        reminder.medication() + " · " + reminder.time());
            }
        }
    }

    static void scheduleAll(Context context) {
        ReminderStore store = new ReminderStore(context);
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate today = now.toLocalDate();
        ZonedDateTime next = null;
        for (ReminderStore.Reminder reminder : store.reminders()) {
            ZonedDateTime candidate = nextOccurrence(reminder, now);
            if (candidate != null && candidate.toLocalDate().equals(today)
                    && store.taken(reminder, today)) {
                candidate = nextOccurrence(reminder, candidate);
            }
            if (candidate != null && (next == null || candidate.isBefore(next))) next = candidate;
        }
        if (next == null) {
            cancelAlarm(context, NEXT);
            return;
        }
        long due = next.toInstant().toEpochMilli();
        setAlarm(context, due, operation(context, NEXT, due,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private void cancelLegacyAlarms(Context context) {
        for (ReminderStore.Reminder reminder : new ReminderStore(context).reminders()) {
            cancelAlarm(context, reminder.id());
        }
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
        long due = System.currentTimeMillis() + 15_000L;
        setAlarm(context, due, operation(context, TEST, due,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    static boolean exactAllowed(Context context) {
        return Build.VERSION.SDK_INT < 31
                || context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    private static void cancelAlarm(Context context, String id) {
        PendingIntent operation = operation(context, id, 0L,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (operation != null) {
            context.getSystemService(AlarmManager.class).cancel(operation);
            operation.cancel();
        }
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

    private static PendingIntent operation(Context context, String id, long due, int flags) {
        Intent intent = new Intent(context, ReminderReceiver.class).setAction(ALARM)
                .setData(Uri.parse("eyecare://alarm/" + id)).putExtra("due", due);
        return PendingIntent.getBroadcast(context, 0, intent, flags);
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

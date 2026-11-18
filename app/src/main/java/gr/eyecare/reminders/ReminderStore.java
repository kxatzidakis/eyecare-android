package gr.eyecare.reminders;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class ReminderStore {
    record Reminder(String id, String medication, LocalTime time, LocalDate through) {}
    record Entry(String id, String medication, LocalDate date, LocalTime time) {}

    private final SharedPreferences preferences;

    ReminderStore(Context context) {
        preferences = context.getSharedPreferences("eyecare", Context.MODE_PRIVATE);
    }

    List<Reminder> reminders() {
        List<Reminder> reminders = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith("reminder/")) continue;
            String[] fields = preferences.getString(key, "").split("\\|", -1);
            reminders.add(new Reminder(key.substring(9), fields[0], LocalTime.parse(fields[1]),
                    fields[2].isEmpty() ? null : LocalDate.parse(fields[2])));
        }
        reminders.sort(Comparator.comparing(Reminder::time).thenComparing(Reminder::medication));
        return reminders;
    }

    Reminder save(Reminder previous, String medication, LocalTime time, LocalDate through) {
        String id = previous == null ? UUID.randomUUID().toString() : previous.id();
        preferences.edit().putString("reminder/" + id,
                medication + "|" + time + "|" + (through == null ? "" : through)).apply();
        return new Reminder(id, medication, time, through);
    }

    void remove(Reminder reminder) {
        preferences.edit().remove("reminder/" + reminder.id()).apply();
    }

    List<Entry> history() {
        List<Entry> entries = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith("taken/")) continue;
            String[] keyFields = key.split("/");
            String[] fields = preferences.getString(key, "").split("\\|");
            entries.add(new Entry(keyFields[1], fields[0], LocalDate.parse(keyFields[2]),
                    LocalTime.parse(fields[1])));
        }
        entries.sort(Comparator.comparing(Entry::date).thenComparing(Entry::time).reversed());
        return entries;
    }

    boolean taken(Reminder reminder, LocalDate date) {
        return preferences.contains(entryKey(reminder.id(), date));
    }

    void markTaken(Reminder reminder, LocalDate date) {
        preferences.edit().putString(entryKey(reminder.id(), date),
                reminder.medication() + "|" + reminder.time()).apply();
    }

    void undo(String id, LocalDate date) {
        preferences.edit().remove(entryKey(id, date)).apply();
    }

    private String entryKey(String id, LocalDate date) {
        return "taken/" + id + "/" + date;
    }
}

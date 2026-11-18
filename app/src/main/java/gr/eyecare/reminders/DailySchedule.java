package gr.eyecare.reminders;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

final class DailySchedule {
    static final int MINUTES_PER_DAY = 24 * 60;

    static List<LocalTime> evenlySpaced(int count, LocalTime first, LocalTime last) {
        int start = minuteOfDay(first);
        if (count == 1) return Collections.singletonList(first);
        int span = minuteOfDay(last) - start;
        if (span < count - 1) {
            throw new IllegalArgumentException("Διάλεξε μεταγενέστερη τελευταία ώρα ή λιγότερες λήψεις.");
        }

        List<LocalTime> times = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int offset = Math.round(index * span / (float) (count - 1));
            times.add(fromMinute(start + offset));
        }
        return times;
    }

    static List<LocalTime> fixedInterval(int count, LocalTime first, int intervalMinutes) {
        int start = minuteOfDay(first);
        long last = start + (long) (count - 1) * intervalMinutes;
        if (last >= MINUTES_PER_DAY) {
            throw new IllegalArgumentException("Η τελευταία λήψη περνά τα μεσάνυχτα. "
                    + "Άλλαξε την πρώτη ώρα, το διάστημα ή το πλήθος λήψεων.");
        }

        List<LocalTime> times = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            times.add(fromMinute(start + index * intervalMinutes));
        }
        return times;
    }

    private static int minuteOfDay(LocalTime time) {
        return 60 * time.getHour() + time.getMinute();
    }

    private static LocalTime fromMinute(int minute) {
        return LocalTime.of(minute / 60, minute % 60);
    }
}

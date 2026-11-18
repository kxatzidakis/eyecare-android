package gr.eyecare.reminders;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xFFEDF5F1;
    private static final int INK = 0xFF162C27;
    private static final int MUTED = 0xFF455D56;
    private static final int ACCENT = 0xFF006B5D;
    private static final int PALE = 0xFFD9ECE5;
    private static final int WHITE = 0xFFFFFFFF;
    private static final String[] MEDICATIONS = {"Vigamox", "Maxidex"};
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("el-GR"));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Handler summaryHandler = new Handler(Looper.getMainLooper());
    private final Runnable summaryRefresh = this::refreshSummary;
    private record Summary(TextView frequency, TextView next) {}
    private final Map<String, Summary> summaries = new LinkedHashMap<>();
    private ReminderStore store;
    private LinearLayout content;
    private ScrollView scroll;
    private boolean history;
    private boolean exactAllowed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        history = state != null && state.getBoolean("history") && getIntent().getData() == null;
        store = new ReminderStore(this);
        exactAllowed = ReminderReceiver.exactAllowed(this);
        ReminderReceiver.scheduleAll(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean canScheduleExactly = ReminderReceiver.exactAllowed(this);
        if (exactAllowed != canScheduleExactly) {
            exactAllowed = canScheduleExactly;
            ReminderReceiver.scheduleAll(this);
        }
        render(true);
    }

    @Override
    protected void onPause() {
        summaryHandler.removeCallbacks(summaryRefresh);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        history = false;
        render(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("history", history);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        render(true);
    }

    private void render(boolean keepScroll) {
        int previousScroll = keepScroll && scroll != null ? scroll.getScrollY() : 0;
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        content = column();
        content.setPadding(dp(20), dp(20), dp(20), dp(24));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);

        add(content, label("EyeCare", 34, INK, true), 0);
        add(content, label(LocalDate.now().format(DAY), 15, MUTED, false), 3);
        LinearLayout tabs = new LinearLayout(this);
        tabs.addView(button("Σήμερα", !history, () -> showHistory(false)), weighted());
        LinearLayout.LayoutParams second = weighted();
        second.setMarginStart(dp(8));
        tabs.addView(button("Ιστορικό", history, () -> showHistory(true)), second);
        add(content, tabs, 20);

        summaryHandler.removeCallbacks(summaryRefresh);
        if (history) renderHistory();
        else renderToday();
        scroll.post(() -> scroll.scrollTo(0, previousScroll));
    }

    private void renderToday() {
        LocalDate today = LocalDate.now();
        boolean notifications = ReminderReceiver.remindersEnabled(this);
        if (!notifications || !exactAllowed) {
            LinearLayout permissions = card();
            if (!notifications) {
                add(permissions, label("Οι ειδοποιήσεις είναι απενεργοποιημένες.", 15, INK, true), 0);
                add(permissions, button("Ενεργοποίηση ειδοποιήσεων", false,
                        this::enableNotifications), 8);
            }
            if (Build.VERSION.SDK_INT >= 31 && !exactAllowed) {
                add(permissions, label("Χωρίς άδεια ακριβούς ώρας, οι ειδοποιήσεις μπορεί να καθυστερήσουν.",
                        15, MUTED, false), notifications ? 0 : 12);
                add(permissions, button("Άδεια ακριβούς ώρας", false, () -> startActivity(
                        new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))), 8);
            }
        }

        add(content, button("＋ Προσθήκη φαρμάκου", true, () -> editSchedule(null)), 18);
        add(content, label("Ανά φάρμακο", 22, INK, true), 20);
        List<ReminderStore.Reminder> reminders = store.reminders();
        summaries.clear();
        for (String name : medicationNames(reminders)) {
            LinearLayout overview = card();
            add(overview, label(name, 19, INK, true), 0);
            TextView frequency = label("", 15, MUTED, false);
            TextView next = label("", 16, ACCENT, true);
            summaries.put(name, new Summary(frequency, next));
            add(overview, frequency, 4);
            add(overview, next, 5);
            add(overview, button("Ρύθμιση προγράμματος", false, () -> editSchedule(name)), 8);
        }
        refreshSummary();

        add(content, label("Πρόγραμμα", 22, INK, true), 22);
        if (reminders.isEmpty()) {
            add(card(), label("Πρόσθεσε την πρώτη σου υπενθύμιση.", 16, MUTED, false), 0);
        }
        for (ReminderStore.Reminder reminder : reminders) {
            boolean expired = reminder.through() != null && today.isAfter(reminder.through());
            boolean taken = store.taken(reminder, today);
            LinearLayout item = card();
            add(item, label(reminder.time().toString(), 28, ACCENT, true), 0);
            add(item, label(reminder.medication(), 19, INK, true), 2);
            if (reminder.through() != null) {
                String prefix = expired ? "Έληξε στις " : "Καθημερινά έως ";
                add(item, label(prefix + reminder.through().format(DATE), 14, MUTED, false), 3);
            } else {
                add(item, label("Καθημερινά", 14, MUTED, false), 3);
            }
            if (taken) {
                add(item, label("Καταχωρίστηκε σήμερα", 14, ACCENT, true), 8);
            } else if (!expired) {
                add(item, button("Καταχώριση λήψης", false, () -> {
                    store.markTaken(reminder, LocalDate.now());
                    ReminderReceiver.dismiss(this, reminder.id());
                    ReminderReceiver.scheduleAll(this);
                    render(true);
                }), 8);
            }
            add(item, button("Επεξεργασία", false, () -> edit(reminder)), 6);
        }
        add(content, button("＋  Νέα υπενθύμιση", true, () -> edit(null)), 16);
        if (notifications) {
            add(content, button("Δοκιμή ειδοποίησης", false, () -> {
                ReminderReceiver.scheduleTest(this);
                Toast.makeText(this, exactAllowed ? "Κλείδωσε την οθόνη. Δοκιμή σε 15 δευτερόλεπτα."
                                : "Η δοκιμή προγραμματίστηκε. Μπορεί να καθυστερήσει.",
                        Toast.LENGTH_LONG).show();
            }), 8);
        }
    }

    private void refreshSummary() {
        ZonedDateTime now = ZonedDateTime.now();
        LocalDate today = now.toLocalDate();
        List<ReminderStore.Reminder> reminders = store.reminders();
        for (Map.Entry<String, Summary> summary : summaries.entrySet()) {
            int activeTimes = 0;
            ZonedDateTime next = null;
            for (ReminderStore.Reminder reminder : reminders) {
                if (!summary.getKey().equals(reminder.medication())
                        || (reminder.through() != null && reminder.through().isBefore(today))) {
                    continue;
                }
                activeTimes++;
                ZonedDateTime candidate = ReminderReceiver.nextOccurrence(reminder, now);
                if (candidate != null && candidate.toLocalDate().equals(today)
                        && store.taken(reminder, today)) {
                    candidate = ReminderReceiver.nextOccurrence(reminder, candidate);
                }
                if (candidate != null && (next == null || candidate.isBefore(next))) {
                    next = candidate;
                }
            }
            summary.getValue().frequency().setText(activeTimes == 0 ? getString(R.string.no_daily_frequency)
                    : getResources().getQuantityString(
                            R.plurals.daily_frequency, activeTimes, activeTimes));
            summary.getValue().next().setText(next == null
                    ? getString(R.string.no_next_reminder)
                    : getString(R.string.next_reminder, next.toLocalDate().equals(today)
                            ? getString(R.string.today) : getString(R.string.tomorrow),
                            next.toLocalTime().withSecond(0).withNano(0)));
        }
        summaryHandler.postDelayed(summaryRefresh, 60_000L - System.currentTimeMillis() % 60_000L);
    }

    private void renderHistory() {
        List<ReminderStore.Entry> entries = store.history();
        if (entries.isEmpty()) {
            add(card(), label("Οι λήψεις που καταχωρίζεις θα εμφανίζονται εδώ.", 16,
                    MUTED, false), 0);
        }
        LocalDate previous = null;
        for (ReminderStore.Entry entry : entries) {
            if (!entry.date().equals(previous)) {
                add(content, label(entry.date().format(DATE), 18, INK, true), 18);
                previous = entry.date();
            }
            LinearLayout item = card();
            add(item, label(entry.medication() + " · " + entry.time(), 17, INK, true), 0);
            add(item, button("Αναίρεση καταχώρισης", false, () -> {
                store.undo(entry.id(), entry.date());
                ReminderReceiver.scheduleAll(this);
                render(true);
            }), 8);
        }
    }

    private List<String> medicationNames(List<ReminderStore.Reminder> reminders) {
        LinkedHashSet<String> names = new LinkedHashSet<>(Arrays.asList(MEDICATIONS));
        for (ReminderStore.Reminder reminder : reminders) names.add(reminder.medication());
        return new ArrayList<>(names);
    }

    private void editSchedule(String name) {
        List<ReminderStore.Reminder> reminders = store.reminders();
        List<ReminderStore.Reminder> existing = new ArrayList<>();
        for (ReminderStore.Reminder reminder : reminders) {
            if (reminder.medication().equals(name)) existing.add(reminder);
        }
        new ScheduleEditor(this, name, medicationNames(reminders), existing, plan -> {
            store.replaceSchedule(plan.medication(), plan.times(), plan.through());
            ReminderReceiver.scheduleAll(this);
            render(true);
        }).show();
    }

    private void edit(ReminderStore.Reminder existing) {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(10), dp(20), 0);
        Spinner medication = new Spinner(this);
        List<String> names = medicationNames(store.reminders());
        ArrayAdapter<String> choices = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        medication.setAdapter(choices);
        if (existing != null) medication.setSelection(names.indexOf(existing.medication()));
        LocalTime[] time = {existing == null ? null : existing.time()};
        LocalDate[] through = {existing == null ? null : existing.through()};
        add(form, label("Κολλύριο", 15, INK, true), 0);
        medication.setMinimumHeight(dp(48));
        add(form, medication, 0);
        Button timeButton = button(time[0] == null ? "Επιλογή ώρας" : "Ώρα " + time[0], false, null);
        timeButton.setOnClickListener(v -> {
            LocalTime initial = time[0] == null ? LocalTime.now() : time[0];
            new TimePickerDialog(this, (picker, hour, minute) -> {
                time[0] = LocalTime.of(hour, minute);
                timeButton.setText(getString(R.string.selected_time, time[0]));
            }, initial.getHour(), initial.getMinute(), true).show();
        });
        add(form, timeButton, 8);
        Button end = button(endLabel(through[0]), false, null);
        end.setOnClickListener(v -> {
            LocalDate initial = through[0] == null ? LocalDate.now() : through[0];
            new DatePickerDialog(this, (picker, year, month, day) -> {
                through[0] = LocalDate.of(year, month + 1, day);
                end.setText(endLabel(through[0]));
            }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show();
        });
        add(form, end, 8);
        add(form, button("Χωρίς ημερομηνία λήξης", false, () -> {
            through[0] = null;
            end.setText(endLabel(null));
        }), 6);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Νέα υπενθύμιση" : "Επεξεργασία υπενθύμισης")
                .setView(form).setNegativeButton("Άκυρο", null).setPositiveButton("Αποθήκευση", null);
        if (existing != null) builder.setNeutralButton("Διαγραφή", (dialog, which) -> confirmDelete(existing));
        AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (time[0] == null) {
                Toast.makeText(this, "Επίλεξε ώρα υπενθύμισης.", Toast.LENGTH_SHORT).show();
                return;
            }
            String selected = medication.getSelectedItem().toString();
            for (ReminderStore.Reminder reminder : store.reminders()) {
                if ((existing == null || !existing.id().equals(reminder.id()))
                        && selected.equals(reminder.medication()) && time[0].equals(reminder.time())
                        && (reminder.through() == null || !reminder.through().isBefore(LocalDate.now()))) {
                    Toast.makeText(this, "Υπάρχει ήδη υπενθύμιση για αυτή την ώρα.", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            store.save(existing, selected, time[0], through[0]);
            ReminderReceiver.scheduleAll(this);
            dialog.dismiss();
            render(true);
        });
    }

    private void confirmDelete(ReminderStore.Reminder reminder) {
        new AlertDialog.Builder(this).setTitle("Διαγραφή υπενθύμισης;")
                .setMessage(reminder.medication() + " · " + reminder.time())
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Διαγραφή", (dialog, which) -> {
                    store.remove(reminder);
                    ReminderReceiver.dismiss(this, reminder.id());
                    ReminderReceiver.scheduleAll(this);
                    render(true);
                }).show();
    }

    private void enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                && (!getPreferences(MODE_PRIVATE).getBoolean("notification_prompted", false)
                    || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_prompted", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private void showHistory(boolean display) {
        if (history == display) return;
        history = display;
        render(false);
    }

    private String endLabel(LocalDate date) {
        return date == null ? "Ημερομηνία λήξης (προαιρετική)" : "Έως " + date.format(DATE);
    }

    private Uri packageUri() {
        return Uri.fromParts("package", getPackageName(), null);
    }

    private LinearLayout card() {
        LinearLayout card = column();
        card.setBackground(background(WHITE, 18));
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        add(content, card, 10);
        return card;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setTextSize(15);
        button.setTextColor(primary ? WHITE : INK);
        button.setMinimumHeight(dp(48));
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), dp(9), dp(12), dp(9));
        android.content.res.ColorStateList ripple =
                android.content.res.ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x22006B5D);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ripple, background(primary ? ACCENT : PALE, 13), null));
        if (action != null) button.setOnClickListener(v -> action.run());
        return button;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

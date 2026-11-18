package gr.eyecare.reminders;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ScheduleEditor {
    record Plan(String medication, List<LocalTime> times, LocalDate through) {}

    private static final int EVEN = 0;
    private static final int INTERVAL = 1;
    private static final int MANUAL = 2;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final Activity activity;
    private final String existingName;
    private final List<String> medicationNames;
    private final Consumer<Plan> save;
    private final List<LocalTime> manualTimes = new ArrayList<>();
    private LocalDate through;
    private boolean mixedEndDates;
    private LocalTime firstTime;
    private LocalTime lastTime;
    private EditText name;
    private EditText count;
    private EditText interval;
    private Spinner intervalUnit;
    private Spinner mode;
    private LinearLayout manualRows;
    private TextView error;

    ScheduleEditor(Activity activity, String existingName, List<String> medicationNames,
            List<ReminderStore.Reminder> existing, Consumer<Plan> save) {
        this.activity = activity;
        this.existingName = existingName;
        this.medicationNames = medicationNames;
        this.save = save;
        LocalDate today = LocalDate.now();
        List<ReminderStore.Reminder> active = existing.stream()
                .filter(reminder -> reminder.through() == null || !reminder.through().isBefore(today))
                .collect(java.util.stream.Collectors.toList());
        if (!active.isEmpty()) {
            through = active.get(0).through();
            for (ReminderStore.Reminder reminder : active) {
                if (!manualTimes.contains(reminder.time())) manualTimes.add(reminder.time());
                mixedEndDates |= !Objects.equals(through, reminder.through());
            }
            if (mixedEndDates) through = null;
        }
    }

    void show() {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(12));
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        if (existingName == null) {
            name = input(form, "Όνομα φαρμάκου",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            name.setSingleLine(true);
        }

        form.addView(text("Πώς θέλεις να ορίσεις τις ώρες;"));
        mode = new Spinner(activity);
        mode.setContentDescription("Τρόπος προγραμματισμού");
        String[] modes = {"Ισομερώς μέσα στην ημέρα", "Κάθε Χ ώρες ή λεπτά", "Συγκεκριμένες ώρες"};
        ArrayAdapter<String> choices = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, modes);
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(choices);
        mode.setMinimumHeight(dp(48));
        form.addView(mode);
        mode.setSelection(manualTimes.isEmpty() ? EVEN : MANUAL);

        LinearLayout automatic = column();
        form.addView(automatic);
        count = input(automatic, "Λήψεις την ημέρα", InputType.TYPE_CLASS_NUMBER);
        if (!manualTimes.isEmpty()) count.setText(Integer.toString(manualTimes.size()));
        timeButton(automatic, "Πρώτη ώρα", () -> firstTime, time -> firstTime = time);
        Button last = timeButton(automatic, "Τελευταία ώρα (για 2 λήψεις ή περισσότερες)",
                () -> lastTime, time -> lastTime = time);
        LinearLayout intervalFields = column();
        automatic.addView(intervalFields);
        interval = input(intervalFields, "Διάστημα μεταξύ λήψεων", InputType.TYPE_CLASS_NUMBER);
        intervalUnit = new Spinner(activity);
        intervalUnit.setContentDescription("Μονάδα διαστήματος");
        ArrayAdapter<String> units = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, new String[]{"Ώρες", "Λεπτά"});
        units.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        intervalUnit.setAdapter(units);
        intervalUnit.setMinimumHeight(dp(48));
        intervalFields.addView(intervalUnit);

        LinearLayout manual = column();
        form.addView(manual);
        Button addTime = button("＋ Προσθήκη ώρας");
        addTime.setOnClickListener(view -> pickTime(null, time -> {
            if (manualTimes.contains(time)) {
                showError("Αυτή η ώρα υπάρχει ήδη.");
                return;
            }
            manualTimes.add(time);
            showError("");
            renderManualTimes();
        }));
        manual.addView(addTime);
        manualRows = column();
        manual.addView(manualRows);
        renderManualTimes();

        Button end = button(endLabel());
        form.addView(end);
        end.setOnClickListener(view -> {
            LocalDate initial = through == null ? LocalDate.now() : through;
            DatePickerDialog picker = new DatePickerDialog(activity, (v, year, month, day) -> {
                through = LocalDate.of(year, month + 1, day);
                mixedEndDates = false;
                end.setText(endLabel());
            }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
            picker.setTitle("Ημερομηνία λήξης");
            picker.getDatePicker().setMinDate(System.currentTimeMillis());
            picker.show();
        });
        Button noEnd = button("Χωρίς ημερομηνία λήξης");
        form.addView(noEnd);
        noEnd.setOnClickListener(view -> {
            through = null;
            mixedEndDates = false;
            end.setText(endLabel());
        });
        error = text("");
        error.setTextColor(0xFF9C2626);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        form.addView(error);

        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                automatic.setVisibility(position == MANUAL ? View.GONE : View.VISIBLE);
                manual.setVisibility(position == MANUAL ? View.VISIBLE : View.GONE);
                last.setVisibility(position == EVEN ? View.VISIBLE : View.GONE);
                intervalFields.setVisibility(position == INTERVAL ? View.VISIBLE : View.GONE);
                showError("");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(existingName == null ? "Προσθήκη φαρμάκου" : "Πρόγραμμα για " + existingName)
                .setView(scroll)
                .setNegativeButton("Άκυρο", null)
                .setPositiveButton("Έλεγχος ωρών", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                Plan plan = readPlan();
                String timeList = String.join(" · ", plan.times().stream()
                        .map(LocalTime::toString).collect(java.util.stream.Collectors.toList()));
                String summary = plan.times().size() == 1 ? "1 λήψη την ημέρα"
                        : plan.times().size() + " λήψεις την ημέρα";
                String message = summary + "\n\n" + timeList;
                if (plan.through() != null) message += "\n\nΈως " + plan.through().format(DATE);
                if (existingName != null) message += "\n\nΤο νέο πρόγραμμα θα αντικαταστήσει τις προηγούμενες ώρες.";
                new AlertDialog.Builder(activity).setTitle(plan.medication())
                        .setMessage(message)
                        .setNegativeButton("Πίσω", null)
                        .setPositiveButton("Αποθήκευση", (confirmation, which) -> {
                            save.accept(plan);
                            dialog.dismiss();
                        }).show();
            } catch (IllegalArgumentException invalid) {
                showError(invalid.getMessage());
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private Plan readPlan() {
        String medication = existingName == null ? name.getText().toString().trim() : existingName;
        if (medication.isEmpty() || medication.contains("|")) {
            throw new IllegalArgumentException("Γράψε το όνομα του φαρμάκου χωρίς τον χαρακτήρα |.");
        }
        if (existingName == null && medicationNames.stream().anyMatch(medication::equalsIgnoreCase)) {
            throw new IllegalArgumentException("Υπάρχει ήδη αυτό το φάρμακο. Άνοιξε τη ρύθμιση του προγράμματός του.");
        }
        if (mixedEndDates) {
            throw new IllegalArgumentException("Οι παλιές ώρες έχουν διαφορετικές λήξεις. Διάλεξε ημερομηνία για το νέο πρόγραμμα ή «Χωρίς ημερομηνία λήξης».");
        }
        return new Plan(medication, readTimes(), through);
    }

    private List<LocalTime> readTimes() {
        int selected = mode.getSelectedItemPosition();
        if (selected == MANUAL) {
            if (manualTimes.isEmpty()) throw new IllegalArgumentException("Πρόσθεσε τουλάχιστον μία ώρα.");
            return new ArrayList<>(manualTimes);
        }

        int doses = positiveNumber(count, "Γράψε πόσες λήψεις θέλεις την ημέρα.",
                DailySchedule.MINUTES_PER_DAY);
        if (firstTime == null) throw new IllegalArgumentException("Επίλεξε την πρώτη ώρα.");
        if (doses == 1) return Collections.singletonList(firstTime);
        if (selected == INTERVAL) {
            int multiplier = intervalUnit.getSelectedItemPosition() == 0 ? 60 : 1;
            int amount = positiveNumber(interval, "Γράψε ένα διάστημα μικρότερο από μία ημέρα.",
                    (DailySchedule.MINUTES_PER_DAY - 1) / multiplier);
            return DailySchedule.fixedInterval(doses, firstTime, amount * multiplier);
        }
        if (lastTime == null) throw new IllegalArgumentException("Επίλεξε την τελευταία ώρα.");
        return DailySchedule.evenlySpaced(doses, firstTime, lastTime);
    }

    private int positiveNumber(EditText input, String errorMessage, int maximum) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value > 0 && value <= maximum) return value;
        } catch (NumberFormatException invalid) {
            // Report the same field error for missing and out-of-range values.
        }
        throw new IllegalArgumentException(errorMessage);
    }

    private void renderManualTimes() {
        manualTimes.sort(LocalTime::compareTo);
        manualRows.removeAllViews();
        for (LocalTime time : manualTimes) {
            LinearLayout row = new LinearLayout(activity);
            Button edit = button(time.toString());
            edit.setOnClickListener(view -> pickTime(time, newTime -> {
                if (newTime.equals(time)) return;
                if (manualTimes.contains(newTime)) {
                    showError("Αυτή η ώρα υπάρχει ήδη.");
                    return;
                }
                manualTimes.set(manualTimes.indexOf(time), newTime);
                showError("");
                renderManualTimes();
            }));
            row.addView(edit, new LinearLayout.LayoutParams(0, -2, 1));
            Button remove = button("Αφαίρεση");
            remove.setContentDescription("Αφαίρεση ώρας " + time);
            remove.setOnClickListener(view -> {
                manualTimes.remove(time);
                renderManualTimes();
            });
            row.addView(remove);
            manualRows.addView(row);
        }
    }

    private Button timeButton(LinearLayout form, String title, Supplier<LocalTime> time, Consumer<LocalTime> select) {
        Button button = button(title);
        button.setOnClickListener(view -> pickTime(
                time.get(), chosen -> {
                    select.accept(chosen);
                    button.setText(activity.getString(R.string.schedule_time, title, chosen));
                }));
        form.addView(button);
        return button;
    }

    private void pickTime(LocalTime time, Consumer<LocalTime> select) {
        LocalTime initial = time == null ? LocalTime.now() : time;
        new TimePickerDialog(activity, (picker, hour, minute) -> select.accept(LocalTime.of(hour, minute)),
                initial.getHour(), initial.getMinute(), true).show();
    }

    private String endLabel() {
        if (mixedEndDates) return "Ορισμός κοινής ημερομηνίας λήξης";
        return through == null ? "Ημερομηνία λήξης (προαιρετική)" : "Έως " + through.format(DATE);
    }

    private void showError(String message) {
        error.setText(message);
        error.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private EditText input(LinearLayout form, String caption, int type) {
        TextView label = text(caption);
        label.setId(View.generateViewId());
        form.addView(label);
        EditText input = new EditText(activity);
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setInputType(type);
        input.setSingleLine(true);
        form.addView(input, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private TextView text(String title) {
        TextView text = new TextView(activity);
        text.setText(title);
        text.setTextSize(15);
        text.setPadding(0, dp(8), 0, dp(4));
        return text;
    }

    private Button button(String title) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(title);
        button.setMinimumHeight(dp(48));
        return button;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private int dp(int units) {
        return Math.round(units * activity.getResources().getDisplayMetrics().density);
    }
}

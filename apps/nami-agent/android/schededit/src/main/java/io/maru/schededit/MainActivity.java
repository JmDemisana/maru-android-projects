package io.maru.schededit;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.Nullable;

import io.maru.applets.LinkBoundActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends LinkBoundActivity {
    private static final String PREFS_NAME = "schededit_native";
    private static final String KEY_DOCUMENT = "schedule_document_v1";
    private static final List<String> DAYS = Arrays.asList(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"
    );
    private static final int[] REMINDER_OPTIONS = { -1, 10, 15, 30, 60 };

    private static final int[] ACCENT_COLORS = {
        Color.parseColor("#ff6b87"),
        Color.parseColor("#ffb347"),
        Color.parseColor("#70b9ff"),
        Color.parseColor("#74e1b3"),
        Color.parseColor("#b998ff"),
        Color.parseColor("#ff9d7a"),
    };

    private static final int BG_DARK = 0xFF0B1020;
    private static final int CARD_BG_1 = 0xFF14203A;
    private static final int CARD_BG_2 = 0xFF0F172A;
    private static final int CARD_BG_3 = 0xFF0B111E;
    private static final int CARD_BORDER = 0xFF25385E;
    private static final int TEXT_PRIMARY = 0xFFF4F7FF;
    private static final int TEXT_SECONDARY = 0xFF99A8BF;
    private static final int BTN_BG = 0xFF1A2540;
    private static final int BTN_ACTIVE = 0xFF2A3A60;
    private static final int ACCENT_BLUE = 0xFF7A9BFF;
    private static final int INPUT_BG = 0xFF1A2540;

    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US);
    private final DateTimeFormatter humanFormatter = DateTimeFormatter.ofPattern("EEE, MMM d  h:mm a", Locale.US);

    private final ScheduleDocument document = new ScheduleDocument();

    private View nextClassCard;
    private TextView nextClassTitle;
    private TextView nextClassDetail;
    private TextView nextClassBadge;
    private LinearLayout reminderRow;
    private LinearLayout scheduleContainer;
    private Button addClassButton;
    private View selectedEntryCard;
    private ScheduleEntry selectedEntry;
    private TextView syncStatusText;
    private Button syncButton;
    private Button pullButton;
    private boolean syncBusy = false;
    private String syncMessage = "";
    private String syncError = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ensureLinkAvailable("SchedEdit")) {
            return;
        }

        loadDocument();

        LinearLayout root = createPageColumn();
        root.addView(buildHeader());
        root.addView(buildNextUpSection());
        root.addView(buildQuickActions());
        root.addView(buildSyncSection());
        root.addView(buildReminderSection());
        root.addView(buildScheduleHeader());
        scheduleContainer = new LinearLayout(this);
        scheduleContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(scheduleContainer);

        setPageContent(root);
        renderDocument();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderDocument();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(6), 0, dp(6));

        TextView subtitle = makeSmallLabel("DEVELOPMENT APPLET");
        subtitle.setTextColor(Color.parseColor("#8FA6D5"));
        subtitle.setPadding(0, 0, 0, dp(4));
        header.addView(subtitle);

        TextView title = new TextView(this);
        title.setText("SchedEdit");
        title.setTextColor(TEXT_PRIMARY);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(32f);
        header.addView(title);

        TextView note = new TextView(this);
        note.setText("Classes, reminders, and the next thing you should not miss.");
        note.setTextColor(TEXT_SECONDARY);
        note.setTextSize(14f);
        note.setPadding(0, dp(6), 0, 0);
        header.addView(note);

        return header;
    }

    private View buildNextUpSection() {
        nextClassCard = buildAccentCard(-1, false);
        nextClassCard.setPadding(dp(18), dp(16), dp(18), dp(16));

        LinearLayout inner = (LinearLayout) ((ViewGroup) nextClassCard).getChildAt(0);

        nextClassBadge = makeSmallLabel("NEXT UP");
        nextClassBadge.setPadding(0, 0, 0, dp(6));
        inner.addView(nextClassBadge);

        nextClassTitle = new TextView(this);
        nextClassTitle.setTextColor(TEXT_PRIMARY);
        nextClassTitle.setTypeface(Typeface.DEFAULT_BOLD);
        nextClassTitle.setTextSize(20f);
        inner.addView(nextClassTitle);

        nextClassDetail = new TextView(this);
        nextClassDetail.setTextColor(TEXT_SECONDARY);
        nextClassDetail.setTextSize(14f);
        nextClassDetail.setLineSpacing(0f, 1.3f);
        nextClassDetail.setPadding(0, dp(4), 0, 0);
        inner.addView(nextClassDetail);

        return nextClassCard;
    }

    private View buildQuickActions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        addClassButton = new Button(this);
        addClassButton.setText("+ Add Class");
        stylePrimaryButton(addClassButton);
        addClassButton.setOnClickListener(v -> showEntryDialog(null));
        row.addView(addClassButton);

        return row;
    }

    private View buildSyncSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(10), 0, dp(6));

        TextView label = makeSectionTitle("Account Options");
        label.setPadding(0, 0, 0, dp(10));
        section.addView(label);

        LinearLayout card = makeCard();
        syncStatusText = makeBodyText(
            "SchedEdit keeps your classes on this phone right now. Open Account Options when you want the shared Maru Link lane."
        );
        card.addView(syncStatusText);

        syncButton = new Button(this);
        syncButton.setText("Open Account Options");
        stylePrimaryButton(syncButton);
        syncButton.setOnClickListener(v -> openLinkAccountSync());
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        syncParams.topMargin = dp(12);
        card.addView(syncButton, syncParams);

        pullButton = new Button(this);
        pullButton.setText("Keep Local Schedule");
        styleSecondaryButton(pullButton);
        LinearLayout.LayoutParams pullParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        pullParams.topMargin = dp(10);
        pullButton.setOnClickListener(v -> {
            syncMessage = "SchedEdit is staying local on this phone for now.";
            syncError = "";
            if (syncStatusText != null) {
                syncStatusText.setText(syncMessage);
            }
        });
        card.addView(pullButton, pullParams);

        section.addView(card);
        return section;
    }

    private View buildReminderSection() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(14), 0, dp(6));

        TextView label = makeSectionTitle("Reminder Lead Time");
        label.setPadding(0, 0, 0, dp(10));
        section.addView(label);

        reminderRow = new LinearLayout(this);
        reminderRow.setOrientation(LinearLayout.VERTICAL);
        section.addView(reminderRow);

        return section;
    }

    private View buildScheduleHeader() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(0, dp(14), 0, 0);

        TextView label = makeSectionTitle("Weekly Classes");
        label.setPadding(0, 0, 0, dp(10));
        section.addView(label);

        return section;
    }

    private void renderDocument() {
        renderNextClass();
        renderReminderButtons();
        renderScheduleList();
    }

    private void renderNextClass() {
        UpcomingClass next = findNextClass();
        int accent = next != null ? pickAccent(next.entry) : 0;

        View accentStrip = ((ViewGroup) nextClassCard).getChildAt(1);
        if (accentStrip != null && accent >= 0) {
            accentStrip.setBackgroundColor(ACCENT_COLORS[accent]);
        }

        if (next == null) {
            nextClassBadge.setVisibility(View.GONE);
            nextClassTitle.setText("No upcoming class");
            nextClassTitle.setTextColor(TEXT_SECONDARY);
            nextClassDetail.setText("Add a class below to get started.");
            return;
        }

        nextClassBadge.setVisibility(View.VISIBLE);
        nextClassTitle.setTextColor(ACCENT_COLORS[accent]);
        nextClassTitle.setText(next.entry.courseCode + "  " + next.entry.title);

        String dayLabel = getRelativeDayLabel(next.start);
        nextClassDetail.setText(
            dayLabel + "  ·  " +
            timeFormatter.format(next.start) + " – " + timeFormatter.format(next.end) +
            (next.entry.room.isEmpty() ? "" : "\n" + next.entry.room) +
            (next.entry.instructor.isEmpty() ? "" : "  ·  " + next.entry.instructor)
        );
    }

    private void renderReminderButtons() {
        reminderRow.removeAllViews();
        LinearLayout pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        pillRow.setPadding(0, 0, 0, 0);

        for (int option : REMINDER_OPTIONS) {
            Button pill = makePillButton(
                option < 0 ? "Off" : option + "m",
                document.reminderLeadMinutes == option
            );
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, dp(44)
            );
            params.weight = 1;
            if (pillRow.getChildCount() > 0) {
                params.leftMargin = dp(6);
            }
            pill.setLayoutParams(params);
            pill.setOnClickListener(v -> {
                document.reminderLeadMinutes = option;
                persistDocument();
                renderReminderButtons();
            });
            pillRow.addView(pill);
        }
        reminderRow.addView(pillRow);
    }

    private void renderScheduleList() {
        scheduleContainer.removeAllViews();
        selectedEntryCard = null;

        List<ScheduleEntry> sorted = new ArrayList<>(document.entries);
        Collections.sort(sorted, this::compareEntries);

        if (sorted.isEmpty()) {
            TextView empty = makeBodyText("No classes yet. Tap + Add Class to build your week.");
            empty.setPadding(0, dp(18), 0, dp(18));
            scheduleContainer.addView(empty);
            return;
        }

        String lastDay = "";
        for (ScheduleEntry entry : sorted) {
            if (!entry.day.equals(lastDay)) {
                if (!lastDay.isEmpty()) {
                    View spacer = new View(this);
                    spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(14)));
                    scheduleContainer.addView(spacer);
                }
                TextView dayHeader = makeDayHeader(entry.day);
                scheduleContainer.addView(dayHeader);
                lastDay = entry.day;
            }
            scheduleContainer.addView(buildEntryCard(entry));
        }
    }

    private View buildEntryCard(ScheduleEntry entry) {
        int accentIndex = pickAccent(entry);
        int accentColor = ACCENT_COLORS[accentIndex];

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) card.getLayoutParams()).bottomMargin = dp(8);

        View accentStrip = new View(this);
        accentStrip.setLayoutParams(new LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT));
        accentStrip.setBackgroundColor(accentColor);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));
        content.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        TextView codeView = new TextView(this);
        codeView.setText(entry.courseCode);
        codeView.setTextColor(accentColor);
        codeView.setTypeface(Typeface.DEFAULT_BOLD);
        codeView.setTextSize(15f);
        content.addView(codeView);

        String label = entry.title;
        if (!entry.section.isEmpty() && !entry.section.equals(document.yearSection)) {
            label += "  ·  " + entry.section;
        }
        TextView titleView = new TextView(this);
        titleView.setText(label);
        titleView.setTextColor(TEXT_PRIMARY);
        titleView.setTextSize(14.5f);
        titleView.setPadding(0, dp(2), 0, 0);
        content.addView(titleView);

        TextView metaView = new TextView(this);
        String meta = formatMinutes(entry.startMinute) + " – " + formatMinutes(entry.endMinute);
        if (!entry.room.isEmpty()) {
            meta += "  ·  " + entry.room;
        }
        if (!entry.instructor.isEmpty()) {
            meta += "  ·  " + entry.instructor;
        }
        metaView.setText(meta);
        metaView.setTextColor(TEXT_SECONDARY);
        metaView.setTextSize(13f);
        metaView.setPadding(0, dp(3), 0, 0);
        content.addView(metaView);

        card.addView(accentStrip);
        card.addView(content);

        card.setBackground(makeGlassDrawable());
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> showEntryDialog(entry));

        int pad = dp(14);
        card.setPadding(0, 0, pad, 0);

        return card;
    }

    private View buildAccentCard(int accentIndex, boolean clickable) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);

        int accentColor = accentIndex >= 0 ? ACCENT_COLORS[accentIndex] : Color.TRANSPARENT;

        View accentStrip = new View(this);
        accentStrip.setLayoutParams(new LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT));
        accentStrip.setBackgroundColor(accentColor);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ));

        card.addView(content);
        card.addView(accentStrip);
        card.setBackground(makeGlassDrawable());

        if (clickable) {
            card.setClickable(true);
            card.setFocusable(true);
        }

        return card;
    }

    private GradientDrawable makeGlassDrawable() {
        GradientDrawable drawable = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] { CARD_BG_1, CARD_BG_2, CARD_BG_3 }
        );
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(16));
        drawable.setStroke(dp(1), CARD_BORDER);
        return drawable;
    }

    private GradientDrawable makePillDrawable(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(22));
        drawable.setColor(fillColor);
        if (strokeColor != Color.TRANSPARENT) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private Button makePillButton(String text, boolean active) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTransformationMethod(null);
        btn.setTextSize(13.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);

        if (active) {
            btn.setTextColor(0xFFFFFFFF);
            btn.setBackground(makePillDrawable(ACCENT_BLUE, Color.TRANSPARENT));
        } else {
            btn.setTextColor(TEXT_SECONDARY);
            btn.setBackground(makePillDrawable(BTN_BG, CARD_BORDER));
        }

        return btn;
    }

    private void showEntryDialog(@Nullable ScheduleEntry existingEntry) {
        boolean editing = existingEntry != null;
        selectedEntry = existingEntry;

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(16), dp(20), dp(8));
        scroll.addView(form);

        EditText courseInput = makeInput(form, "Course Code", editing ? existingEntry.courseCode : "");
        courseInput.setHint("e.g. ECE334-V");

        EditText titleInput = makeInput(form, "Title", editing ? existingEntry.title : "");
        titleInput.setHint("e.g. Signals, Spectra, Signal Processing");

        addFieldLabel(form, "Day");
        Spinner daySpinner = new Spinner(this);
        ArrayAdapter<String> dayAdapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, buildDayLabels()
        );
        daySpinner.setAdapter(dayAdapter);
        if (editing) {
            daySpinner.setSelection(Math.max(0, DAYS.indexOf(existingEntry.day)));
        }
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        spinnerParams.bottomMargin = dp(10);
        form.addView(daySpinner, spinnerParams);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);

        EditText startInput = makeInlineInput(timeRow, "Start", editing ? formatMinutes(existingEntry.startMinute) : "08:00");
        EditText endInput = makeInlineInput(timeRow, "End", editing ? formatMinutes(existingEntry.endMinute) : "09:30");
        form.addView(timeRow);

        EditText sectionInput = makeInput(form, "Section", editing ? existingEntry.section : document.yearSection);
        EditText instructorInput = makeInput(form, "Instructor", editing ? existingEntry.instructor : "");
        EditText roomInput = makeInput(form, "Room", editing ? existingEntry.room : "");
        EditText notesInput = makeInput(form, "Notes", editing ? existingEntry.notes : "");
        notesInput.setMinLines(3);

        int accentIndex = editing ? pickAccent(existingEntry) : 0;
        TextView accentLabel = makeSmallLabel("ACCENT COLOR");
        accentLabel.setPadding(0, dp(10), 0, dp(6));
        form.addView(accentLabel);

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        int[] selectedAccent = { accentIndex };

        for (int i = 0; i < ACCENT_COLORS.length; i++) {
            final int index = i;
            View colorDot = new View(this);
            int dotSize = dp(36);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) {
                dotParams.leftMargin = dp(8);
            }
            colorDot.setLayoutParams(dotParams);

            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(ACCENT_COLORS[i]);
            if (i == selectedAccent[0]) {
                dotBg.setStroke(dp(3), 0xFFFFFFFF);
            }
            colorDot.setBackground(dotBg);
            colorDot.setClickable(true);
            colorDot.setFocusable(true);

            final View dotView = colorDot;
            colorDot.setOnClickListener(v -> {
                selectedAccent[0] = index;
                for (int j = 0; j < colorRow.getChildCount(); j++) {
                    View child = colorRow.getChildAt(j);
                    GradientDrawable bg = (GradientDrawable) child.getBackground();
                    if (j == index) {
                        bg.setStroke(dp(3), 0xFFFFFFFF);
                    } else {
                        bg.setStroke(0, Color.TRANSPARENT);
                    }
                    child.setBackground(bg);
                }
            });
            colorRow.addView(colorDot);
        }
        form.addView(colorRow);

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(editing ? "Edit Class" : "Add Class")
            .setView(scroll)
            .setPositiveButton(editing ? "Save" : "Add", null)
            .setNegativeButton("Cancel", null);

        if (editing) {
            builder.setNeutralButton("Delete", (di, which) -> {
                document.entries.remove(existingEntry);
                persistDocument();
                renderDocument();
            });
        }

        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(listener -> {
            Button positive = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(view -> {
                Integer startMin = parseTimeInput(startInput.getText().toString().trim());
                Integer endMin = parseTimeInput(endInput.getText().toString().trim());
                String code = courseInput.getText().toString().trim();
                String title = titleInput.getText().toString().trim();
                if (code.isEmpty() || title.isEmpty() || startMin == null || endMin == null || endMin <= startMin) {
                    return;
                }

                ScheduleEntry target = editing ? existingEntry : new ScheduleEntry();
                if (!editing) {
                    target.id = UUID.randomUUID().toString();
                }
                target.courseCode = code;
                target.title = title;
                target.day = DAYS.get(daySpinner.getSelectedItemPosition());
                target.startMinute = startMin;
                target.endMinute = endMin;
                target.section = sectionInput.getText().toString().trim();
                target.instructor = instructorInput.getText().toString().trim();
                target.room = roomInput.getText().toString().trim();
                target.notes = notesInput.getText().toString().trim();
                target.accentId = selectedAccent[0];

                if (!editing) {
                    document.entries.add(target);
                }
                persistDocument();
                renderDocument();
                dialog.dismiss();
            });
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(makeGlassDrawable());
        }
    }

    private EditText makeInput(LinearLayout parent, String label, String value) {
        addFieldLabel(parent, label);
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(TEXT_SECONDARY);
        input.setBackground(makePillDrawable(INPUT_BG, CARD_BORDER));
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(10);
        parent.addView(input, params);
        return input;
    }

    private EditText makeInlineInput(LinearLayout parent, String hint, String value) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextColor(TEXT_PRIMARY);
        input.setHintTextColor(TEXT_SECONDARY);
        input.setHint(hint);
        input.setBackground(makePillDrawable(INPUT_BG, CARD_BORDER));
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        input.setInputType(InputType.TYPE_CLASS_DATETIME);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        if (parent.getChildCount() > 0) {
            params.leftMargin = dp(8);
        }
        params.bottomMargin = dp(10);
        parent.addView(input, params);
        return input;
    }

    private void addFieldLabel(LinearLayout parent, String label) {
        TextView view = makeSmallLabel(label.toUpperCase(Locale.US));
        view.setPadding(0, dp(6), 0, dp(4));
        parent.addView(view);
    }

    private TextView makeDayHeader(String day) {
        TextView header = new TextView(this);
        String label = day.substring(0, 1).toUpperCase(Locale.US) + day.substring(1);

        String today = LocalDate.now().getDayOfWeek().name().toLowerCase(Locale.US);
        if (day.equals(today)) {
            label += "  ·  Today";
        }

        header.setText(label);
        header.setTextColor(TEXT_SECONDARY);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextSize(13f);
        header.setPadding(dp(4), dp(4), 0, dp(6));
        header.setLetterSpacing(0.05f);
        return header;
    }

    private TextView makeSmallLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(TEXT_SECONDARY);
        view.setTextSize(11f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLetterSpacing(0.08f);
        return view;
    }

    private String getRelativeDayLabel(LocalDateTime dateTime) {
        LocalDate today = LocalDate.now();
        LocalDate target = dateTime.toLocalDate();
        if (target.equals(today)) {
            return "Today";
        }
        if (target.equals(today.plusDays(1))) {
            return "Tomorrow";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("EEEE", Locale.US));
    }

    private int pickAccent(ScheduleEntry entry) {
        if (entry.accentId >= 0 && entry.accentId < ACCENT_COLORS.length) {
            return entry.accentId;
        }
        return Math.abs(entry.courseCode.hashCode() % ACCENT_COLORS.length);
    }

    private void loadDocument() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String raw = prefs.getString(KEY_DOCUMENT, "");
        if (raw == null || raw.trim().isEmpty()) {
            applyDefaultDocument();
            return;
        }

        try {
            JSONObject json = new JSONObject(raw);
            document.term = json.optString("term", "1st");
            document.schoolYear = json.optString("schoolYear", "2025-2026");
            document.yearSection = json.optString("yearSection", "BSECE-V-3B-V");
            document.saveMode = json.optString("saveMode", "local");
            document.reminderLeadMinutes = json.optInt("reminderLeadMinutes", -1);
            document.entries.clear();

            JSONArray entries = json.optJSONArray("entries");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject ej = entries.optJSONObject(i);
                    if (ej == null) continue;
                    ScheduleEntry e = new ScheduleEntry();
                    e.id = ej.optString("id", UUID.randomUUID().toString());
                    e.day = ej.optString("day", "monday");
                    e.startMinute = ej.optInt("startMinute", 8 * 60);
                    e.endMinute = ej.optInt("endMinute", 9 * 60);
                    e.courseCode = ej.optString("courseCode", "");
                    e.title = ej.optString("title", "");
                    e.section = ej.optString("section", "");
                    e.instructor = ej.optString("instructor", "");
                    e.room = ej.optString("room", "");
                    e.notes = ej.optString("notes", "");
                    e.accentId = ej.optInt("accentId", -1);
                    document.entries.add(e);
                }
            }

            if (document.entries.isEmpty()) {
                applyDefaultDocument();
            }
        } catch (Exception ignored) {
            applyDefaultDocument();
        }
    }

    private void persistDocument() {
        try {
            JSONObject json = new JSONObject();
            json.put("term", document.term);
            json.put("schoolYear", document.schoolYear);
            json.put("yearSection", document.yearSection);
            json.put("saveMode", document.saveMode);
            json.put("reminderLeadMinutes", document.reminderLeadMinutes);

            JSONArray entries = new JSONArray();
            for (ScheduleEntry e : document.entries) {
                JSONObject ej = new JSONObject();
                ej.put("id", e.id);
                ej.put("day", e.day);
                ej.put("startMinute", e.startMinute);
                ej.put("endMinute", e.endMinute);
                ej.put("courseCode", e.courseCode);
                ej.put("title", e.title);
                ej.put("section", e.section);
                ej.put("instructor", e.instructor);
                ej.put("room", e.room);
                ej.put("notes", e.notes);
                ej.put("accentId", e.accentId);
                entries.put(ej);
            }
            json.put("entries", entries);

            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_DOCUMENT, json.toString())
                .apply();
        } catch (Exception ignored) {}
    }

    private void applyDefaultDocument() {
        document.term = "1st";
        document.schoolYear = "2025-2026";
        document.yearSection = "BSECE-V-3B-V";
        document.saveMode = "local";
        document.reminderLeadMinutes = -1;
        document.entries.clear();
        document.entries.add(makeEntry("monday", "13:00", "16:45", "ECE334-V", "Signals, Spectra, Signal Processing", "Seonomio S.", "ECE LAB 1"));
        document.entries.add(makeEntry("tuesday", "08:15", "12:00", "ECE334B-V", "Data Communications and Networking", "Abeto", "NEB 7"));
        document.entries.add(makeEntry("tuesday", "13:00", "15:30", "ECE332-V", "Power and Green Electronics", "Penaroyo", "NEB 7"));
        document.entries.add(makeEntry("tuesday", "15:30", "16:45", "ECE331-V", "Advanced ECE", "Benitez", "NEB 7"));
        document.entries.add(makeEntry("wednesday", "08:15", "12:00", "ECE334B-V", "Data Communications and Networking", "Abeto", "ECE LAB 2"));
        document.entries.add(makeEntry("thursday", "08:15", "12:00", "ECE334C-V", "Transmission Media and Antenna Design", "Benitez", "NEB 8"));
        document.entries.add(makeEntry("friday", "13:00", "16:45", "ECE334C-V", "Transmission Media and Antenna Design", "Benitez", "ECE LAB 1"));
        persistDocument();
    }

    private ScheduleEntry makeEntry(String day, String start, String end, String code, String title, String inst, String room) {
        ScheduleEntry e = new ScheduleEntry();
        e.id = UUID.randomUUID().toString();
        e.day = day;
        e.startMinute = parseTimeInput(start);
        e.endMinute = parseTimeInput(end);
        e.courseCode = code;
        e.title = title;
        e.section = document.yearSection;
        e.instructor = inst;
        e.room = room;
        e.notes = "";
        e.accentId = -1;
        return e;
    }

    private UpcomingClass findNextClass() {
        if (document.entries.isEmpty()) return null;
        LocalDateTime now = LocalDateTime.now();
        UpcomingClass best = null;
        for (ScheduleEntry entry : document.entries) {
            int dayIndex = DAYS.indexOf(entry.day);
            if (dayIndex < 0) continue;
            DayOfWeek targetDay = DayOfWeek.of(dayIndex + 1);
            LocalDate targetDate = now.toLocalDate();
            int daysAhead = (targetDay.getValue() - targetDate.getDayOfWeek().getValue() + 7) % 7;
            LocalDateTime start = LocalDateTime.of(targetDate.plusDays(daysAhead), LocalTime.of(entry.startMinute / 60, entry.startMinute % 60));
            LocalDateTime end = LocalDateTime.of(targetDate.plusDays(daysAhead), LocalTime.of(entry.endMinute / 60, entry.endMinute % 60));
            if (end.isBefore(now)) {
                start = start.plusDays(7);
                end = end.plusDays(7);
            }
            if (best == null || start.isBefore(best.start)) {
                best = new UpcomingClass(entry, start, end);
            }
        }
        return best;
    }

    private int compareEntries(ScheduleEntry a, ScheduleEntry b) {
        int d = Integer.compare(DAYS.indexOf(a.day), DAYS.indexOf(b.day));
        if (d != 0) return d;
        int s = Integer.compare(a.startMinute, b.startMinute);
        if (s != 0) return s;
        return a.courseCode.compareToIgnoreCase(b.courseCode);
    }

    private Integer parseTimeInput(String raw) {
        try {
            String[] parts = raw.split(":");
            if (parts.length != 2) return null;
            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return hour * 60 + minute;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String formatMinutes(int total) {
        int h = Math.max(0, total) / 60;
        int m = Math.max(0, total) % 60;
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    private String[] buildDayLabels() {
        String[] labels = new String[DAYS.size()];
        for (int i = 0; i < DAYS.size(); i++) {
            String d = DAYS.get(i);
            labels[i] = d.substring(0, 1).toUpperCase(Locale.US) + d.substring(1);
        }
        return labels;
    }

    private static final class ScheduleDocument {
        String term = "1st";
        String schoolYear = "2025-2026";
        String yearSection = "BSECE-V-3B-V";
        String saveMode = "local";
        int reminderLeadMinutes = -1;
        final List<ScheduleEntry> entries = new ArrayList<>();
    }

    private static final class ScheduleEntry {
        String id;
        String day;
        int startMinute;
        int endMinute;
        String courseCode;
        String title;
        String section;
        String instructor;
        String room;
        String notes;
        int accentId = -1;
    }

    private static final class UpcomingClass {
        final ScheduleEntry entry;
        final LocalDateTime start;
        final LocalDateTime end;

        UpcomingClass(ScheduleEntry entry, LocalDateTime start, LocalDateTime end) {
            this.entry = entry;
            this.start = start;
            this.end = end;
        }
    }
}

package io.maru.tupgradesolver;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import io.maru.applets.LinkBoundActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends LinkBoundActivity {
    private final List<BulkRow> bulkRows = new ArrayList<>();

    private EditText courseInput;
    private EditText prelimInput;
    private EditText midtermInput;
    private TextView resultValueText;
    private TextView resultNoteText;
    private TextView accountStatusText;
    private LinearLayout singlePanel;
    private LinearLayout bulkPanel;
    private Button singleModeButton;
    private Button bulkModeButton;
    private LinearLayout bulkRowsContainer;
    private boolean bulkMode;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ensureLinkAvailable("TUP Grade Solver")) {
            return;
        }

        setTitle("TUP Grade Solver");

        LinearLayout root = createPageColumn();
        TextView eyebrow = makeEyebrowText("NATIVE APPLET");
        eyebrow.setPadding(0, 0, 0, dp(6));
        root.addView(eyebrow);
        root.addView(buildHeroCard());
        root.addView(buildModeCard());
        root.addView(buildSingleCard());
        root.addView(buildBulkCard());
        root.addView(buildAccountCard());

        updateModeUi();
        updateSingleResult();
        updateAccountCard();

        setPageContent(root);
    }

    private View buildHeroCard() {
        LinearLayout card = makeCard();

        TextView title = makeSectionTitle("TUP Grade Solver");
        TextView body = makeBodyText(
            "Compute the endterm grade you need from your prelim and midterm marks. Bulk mode lets you keep several subjects in one native sheet."
        );
        body.setPadding(0, dp(8), 0, 0);

        card.addView(title);
        card.addView(body);
        return card;
    }

    private View buildModeCard() {
        LinearLayout card = makeCard();
        card.addView(makeSectionTitle("Solver Mode"));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(14), 0, 0);

        singleModeButton = new Button(this);
        singleModeButton.setText("Single Subject");
        stylePrimaryButton(singleModeButton);
        singleModeButton.setOnClickListener(view -> {
            bulkMode = false;
            updateModeUi();
        });

        bulkModeButton = new Button(this);
        bulkModeButton.setText("Bulk Subjects");
        styleSecondaryButton(bulkModeButton);
        LinearLayout.LayoutParams bulkParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        bulkParams.leftMargin = dp(10);
        bulkModeButton.setLayoutParams(bulkParams);
        bulkModeButton.setOnClickListener(view -> {
            bulkMode = true;
            updateModeUi();
        });

        LinearLayout.LayoutParams singleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        singleModeButton.setLayoutParams(singleParams);

        row.addView(singleModeButton);
        row.addView(bulkModeButton);
        card.addView(row);
        return card;
    }

    private View buildSingleCard() {
        singlePanel = makeCard();
        singlePanel.addView(makeSectionTitle("Single Subject"));

        courseInput = makeField(singlePanel, "Course", "Example: ECE334-V", InputType.TYPE_CLASS_TEXT);
        prelimInput = makeField(
            singlePanel,
            "Prelim Grade",
            "0.0 to 10.0",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        midtermInput = makeField(
            singlePanel,
            "Midterm Grade",
            "0.0 to 10.0",
            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );

        attachGradeWatcher(prelimInput);
        attachGradeWatcher(midtermInput);

        LinearLayout resultCard = makeCard();
        TextView resultLabel = makeBodyText("Required Endterm");
        resultLabel.setTextColor(Color.parseColor("#9FB1D8"));
        resultValueText = makeSectionTitle("--");
        resultValueText.setTextSize(32f);
        resultNoteText = makeBodyText("Enter both grades to compute the requirement.");
        resultNoteText.setPadding(0, dp(8), 0, 0);
        resultCard.addView(resultLabel);
        resultCard.addView(resultValueText);
        resultCard.addView(resultNoteText);
        singlePanel.addView(resultCard);
        return singlePanel;
    }

    private View buildBulkCard() {
        bulkPanel = makeCard();
        bulkPanel.addView(makeSectionTitle("Bulk Subjects"));

        TextView body = makeBodyText(
            "Keep several subjects in one native sheet. Each row updates its required endterm as soon as the grades change."
        );
        body.setPadding(0, dp(8), 0, dp(12));
        bulkPanel.addView(body);

        bulkRowsContainer = new LinearLayout(this);
        bulkRowsContainer.setOrientation(LinearLayout.VERTICAL);
        bulkPanel.addView(bulkRowsContainer);

        Button addButton = new Button(this);
        addButton.setText("Add Subject");
        stylePrimaryButton(addButton);
        addButton.setOnClickListener(view -> addBulkRow("", "", ""));
        bulkPanel.addView(addButton);

        addBulkRow("", "", "");
        return bulkPanel;
    }

    private View buildAccountCard() {
        LinearLayout card = makeCard();
        card.addView(makeSectionTitle("Maru Link"));

        accountStatusText = makeBodyText("");
        accountStatusText.setPadding(0, dp(8), 0, dp(12));
        card.addView(accountStatusText);

        Button linkButton = new Button(this);
        linkButton.setText("Open Account Sync");
        styleSecondaryButton(linkButton);
        linkButton.setOnClickListener(view -> openLinkAccountSync());
        card.addView(linkButton);
        return card;
    }

    private void updateModeUi() {
        if (singlePanel == null || bulkPanel == null) {
            return;
        }
        singlePanel.setVisibility(bulkMode ? View.GONE : View.VISIBLE);
        bulkPanel.setVisibility(bulkMode ? View.VISIBLE : View.GONE);
        if (bulkMode) {
            styleSecondaryButton(singleModeButton);
            stylePrimaryButton(bulkModeButton);
        } else {
            stylePrimaryButton(singleModeButton);
            styleSecondaryButton(bulkModeButton);
        }
    }

    private void updateSingleResult() {
        Double required = computeRequiredEndterm(
            safeText(prelimInput),
            safeText(midtermInput)
        );
        if (required == null) {
            resultValueText.setText("--");
            resultNoteText.setText("Enter both grades to compute the requirement.");
            return;
        }
        if (required < 0d) {
            resultValueText.setText("Passed");
            resultNoteText.setText("You already cleared the passing requirement.");
            return;
        }
        resultValueText.setText(String.format(Locale.US, "%.1f", required));
        String course = safeText(courseInput);
        resultNoteText.setText(
            course.isEmpty()
                ? "This is the endterm grade you need."
                : "Required endterm for " + course + "."
        );
    }

    private void updateAccountCard() {
        SharedAuthUser user = getSharedAuthUser();
        if (user == null) {
            accountStatusText.setText(
                "No shared Maru account is linked yet. Maru Link handles sign-in and shared app identity for later cross-app sync."
            );
            return;
        }
        accountStatusText.setText(
            "Shared account ready for " + user.fullName + " (" + user.email + ")."
        );
    }

    private void addBulkRow(String course, String prelim, String midterm) {
        BulkRow row = new BulkRow(course, prelim, midterm);
        bulkRows.add(row);
        bulkRowsContainer.addView(row.container);
        updateBulkRows();
    }

    private void removeBulkRow(BulkRow row) {
        if (bulkRows.size() <= 1) {
            row.courseInput.setText("");
            row.prelimInput.setText("");
            row.midtermInput.setText("");
            row.updateResult();
            return;
        }
        bulkRows.remove(row);
        bulkRowsContainer.removeView(row.container);
        updateBulkRows();
    }

    private void updateBulkRows() {
        for (int index = 0; index < bulkRows.size(); index += 1) {
            bulkRows.get(index).bindIndex(index + 1, bulkRows.size() > 1);
        }
    }

    private EditText makeField(
        LinearLayout parent,
        String label,
        String hint,
        int inputType
    ) {
        TextView fieldLabel = makeBodyText(label);
        fieldLabel.setTextColor(Color.parseColor("#D9E2F7"));
        fieldLabel.setPadding(0, dp(10), 0, dp(6));
        parent.addView(fieldLabel);

        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#7283A8"));
        input.setInputType(inputType);
        input.setBackground(makeFieldBackground());
        input.setPadding(dp(14), dp(14), dp(14), dp(14));
        parent.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return input;
    }

    private void attachGradeWatcher(EditText input) {
        input.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                updateSingleResult();
            }
        });
    }

    private Double computeRequiredEndterm(String prelim, String midterm) {
        Double prelimValue = parseGrade(prelim);
        Double midtermValue = parseGrade(midterm);
        if (prelimValue == null || midtermValue == null) {
            return null;
        }
        double required = (4.85d - 0.3d * prelimValue - 0.3d * midtermValue) / 0.4d;
        return clampMax(roundToOne(required), 10d);
    }

    private Double parseGrade(String rawValue) {
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return 5d;
        }
        try {
            return Double.parseDouble(rawValue.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private double roundToOne(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private double clampMax(double value, double max) {
        return value > max ? max : value;
    }

    private String safeText(EditText input) {
        return input == null || input.getText() == null ? "" : input.getText().toString().trim();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }

    private GradientDrawable makeFieldBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(16));
        drawable.setColor(Color.parseColor("#10182B"));
        drawable.setStroke(dp(1), Color.parseColor("#2F4771"));
        return drawable;
    }

    private final class BulkRow {
        final LinearLayout container;
        final TextView title;
        final EditText courseInput;
        final EditText prelimInput;
        final EditText midtermInput;
        final TextView resultText;
        final Button removeButton;

        BulkRow(String course, String prelim, String midterm) {
            container = makeCard();

            title = makeSectionTitle("Subject");
            title.setTextSize(16f);
            container.addView(title);

            courseInput = makeInlineField(container, "Course", course);
            prelimInput = makeInlineField(container, "Prelim", prelim);
            prelimInput.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
            );
            midtermInput = makeInlineField(container, "Midterm", midterm);
            midtermInput.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
            );

            resultText = makeBodyText("");
            resultText.setTextColor(Color.WHITE);
            resultText.setPadding(0, dp(10), 0, dp(10));
            container.addView(resultText);

            removeButton = new Button(MainActivity.this);
            removeButton.setText("Remove Subject");
            styleDangerButton(removeButton);
            removeButton.setOnClickListener(view -> removeBulkRow(this));
            container.addView(removeButton);

            TextWatcher watcher = new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    updateResult();
                }
            };
            courseInput.addTextChangedListener(watcher);
            prelimInput.addTextChangedListener(watcher);
            midtermInput.addTextChangedListener(watcher);
            updateResult();
        }

        void bindIndex(int index, boolean removable) {
            title.setText("Subject " + index);
            removeButton.setVisibility(removable ? View.VISIBLE : View.GONE);
        }

        void updateResult() {
            Double required = computeRequiredEndterm(
                safeText(prelimInput),
                safeText(midtermInput)
            );
            if (required == null) {
                resultText.setText("Required endterm: --");
                return;
            }
            if (required < 0d) {
                resultText.setText("Required endterm: You already passed.");
                return;
            }
            resultText.setText(
                "Required endterm: " + String.format(Locale.US, "%.1f", required)
            );
        }

        private EditText makeInlineField(LinearLayout parent, String label, String value) {
            TextView fieldLabel = makeBodyText(label);
            fieldLabel.setTextColor(Color.parseColor("#DDE7FF"));
            fieldLabel.setPadding(0, dp(8), 0, dp(5));
            parent.addView(fieldLabel);

            EditText input = new EditText(MainActivity.this);
            input.setText(value);
            input.setHint(label);
            input.setTextColor(Color.WHITE);
            input.setHintTextColor(Color.parseColor("#7283A8"));
            input.setBackground(makeFieldBackground());
            input.setPadding(dp(14), dp(14), dp(14), dp(14));
            parent.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            return input;
        }
    }
}

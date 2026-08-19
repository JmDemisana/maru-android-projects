package io.maru.cupcuppercuppers;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import io.maru.applets.LinkBoundActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends LinkBoundActivity {
    private static final int SWAP_COUNT = 8;
    private static final long SWAP_DELAY_MS = 560L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<CupItem> slots = new ArrayList<>();

    private TextView scoreboardText;
    private TextView promptText;
    private TextView resultText;
    private TextView linkStatusText;
    private final Button[] cupButtons = new Button[3];
    private Button actionButton;

    private String phase = "preview";
    private int computerIndex = -1;
    private int selectedIndex = -1;
    private int shuffleStep = 0;
    private int rounds;
    private int wins;
    private int streak;
    private int bestStreak;
    private int points;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ensureLinkAvailable("Cup-Cupper-Cuppers")) {
            return;
        }

        setTitle("Cup-Cupper-Cuppers");
        resetRound();

        LinearLayout root = createPageColumn();
        TextView eyebrow = makeEyebrowText("NATIVE APPLET");
        eyebrow.setPadding(0, 0, 0, dp(6));
        root.addView(eyebrow);
        root.addView(buildHeroCard());
        root.addView(buildBoardCard());
        root.addView(buildLinkCard());

        setPageContent(root);
        renderRound();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    private View buildHeroCard() {
        LinearLayout card = makeCard();
        card.addView(makeSectionTitle("Cup-Cupper-Cuppers"));

        TextView body = makeBodyText(
            "Track the shuffle, then pick the hidden cup that beats the computer's revealed counter."
        );
        body.setPadding(0, dp(8), 0, 0);
        card.addView(body);

        scoreboardText = makeBodyText("");
        scoreboardText.setTextColor(Color.WHITE);
        scoreboardText.setPadding(0, dp(14), 0, 0);
        card.addView(scoreboardText);
        return card;
    }

    private View buildBoardCard() {
        LinearLayout card = makeCard();

        promptText = makeSectionTitle("");
        promptText.setTextSize(20f);
        card.addView(promptText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(18), 0, dp(18));
        for (int index = 0; index < cupButtons.length; index += 1) {
            Button button = new Button(this);
            button.setText("Cup");
            button.setAllCaps(false);
            button.setTextSize(16f);
            button.setTextColor(Color.WHITE);
            button.setPadding(dp(10), dp(20), dp(10), dp(20));
            styleSecondaryButton(button);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            );
            if (index > 0) {
                params.leftMargin = dp(10);
            }
            button.setLayoutParams(params);

            final int cupIndex = index;
            button.setOnClickListener(view -> onCupPressed(cupIndex));
            cupButtons[index] = button;
            row.addView(button);
        }
        card.addView(row);

        resultText = makeBodyText("");
        resultText.setTextColor(Color.parseColor("#DDE7FF"));
        resultText.setPadding(0, 0, 0, dp(14));
        card.addView(resultText);

        actionButton = new Button(this);
        actionButton.setText("Start shuffle");
        stylePrimaryButton(actionButton);
        actionButton.setOnClickListener(view -> onActionPressed());
        card.addView(actionButton);
        return card;
    }

    private View buildLinkCard() {
        LinearLayout card = makeCard();
        card.addView(makeSectionTitle("Maru Link"));

        linkStatusText = makeBodyText("");
        linkStatusText.setPadding(0, dp(8), 0, dp(12));
        card.addView(linkStatusText);

        Button linkButton = new Button(this);
        linkButton.setText("Open Account Sync");
        styleSecondaryButton(linkButton);
        linkButton.setOnClickListener(view -> openLinkAccountSync());
        card.addView(linkButton);
        return card;
    }

    private void onActionPressed() {
        if ("preview".equals(phase)) {
            startShuffle();
            return;
        }
        if ("result".equals(phase)) {
            resetRound();
            renderRound();
        }
    }

    private void onCupPressed(int index) {
        if (!"picking".equals(phase)) {
            return;
        }
        if (index == computerIndex) {
            return;
        }

        selectedIndex = index;
        CupItem selected = slots.get(selectedIndex);
        CupItem computer = slots.get(computerIndex);
        boolean win = selected.beats.equals(computer.id);
        phase = "result";
        rounds += 1;
        if (win) {
            wins += 1;
            streak += 1;
            bestStreak = Math.max(bestStreak, streak);
            points += 10 + streak * 2;
        } else {
            streak = 0;
        }
        renderRound();
    }

    private void startShuffle() {
        phase = "shuffling";
        computerIndex = -1;
        selectedIndex = -1;
        shuffleStep = 0;
        renderRound();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::runShuffleStep, SWAP_DELAY_MS);
    }

    private void runShuffleStep() {
        if (!"shuffling".equals(phase)) {
            return;
        }
        if (shuffleStep >= SWAP_COUNT) {
            computerIndex = random.nextInt(slots.size());
            phase = "picking";
            renderRound();
            return;
        }

        int first = random.nextInt(slots.size());
        int second = random.nextInt(slots.size());
        while (second == first) {
            second = random.nextInt(slots.size());
        }
        Collections.swap(slots, first, second);
        shuffleStep += 1;
        renderRound();
        handler.postDelayed(this::runShuffleStep, SWAP_DELAY_MS);
    }

    private void resetRound() {
        handler.removeCallbacksAndMessages(null);
        phase = "preview";
        computerIndex = -1;
        selectedIndex = -1;
        shuffleStep = 0;
        slots.clear();
        slots.add(new CupItem("rock", "Rock", "\uD83E\uDEA8", "scissors"));
        slots.add(new CupItem("paper", "Paper", "\uD83D\uDCC4", "rock"));
        slots.add(new CupItem("scissors", "Scissors", "\u2702\uFE0F", "paper"));
    }

    private void renderRound() {
        scoreboardText.setText(String.format(
            Locale.US,
            "Score %d   |   Streak %d   |   Wins %d/%d",
            points,
            streak,
            wins,
            rounds
        ));

        if ("preview".equals(phase)) {
            promptText.setText("Memorize the cups.");
            resultText.setText("Press Start Shuffle when you are ready.");
            actionButton.setText("Start Shuffle");
            actionButton.setEnabled(true);
        } else if ("shuffling".equals(phase)) {
            promptText.setText("Tracking shuffle " + shuffleStep + " of " + SWAP_COUNT);
            resultText.setText("Keep your eyes on the winning counter.");
            actionButton.setText("Shuffling...");
            actionButton.setEnabled(false);
        } else if ("picking".equals(phase)) {
            promptText.setText(
                "Computer showed " + slots.get(computerIndex).label + ". Pick the hidden cup that beats it."
            );
            resultText.setText("Rock beats Scissors, Scissors beats Paper, Paper beats Rock.");
            actionButton.setText("Choose a Cup");
            actionButton.setEnabled(false);
        } else {
            CupItem selected = selectedIndex >= 0 ? slots.get(selectedIndex) : null;
            CupItem computer = computerIndex >= 0 ? slots.get(computerIndex) : null;
            boolean win = selected != null && computer != null && selected.beats.equals(computer.id);
            promptText.setText(win ? "Nice tracking." : "Lost the cup.");
            if (selected != null && computer != null) {
                resultText.setText(
                    selected.label +
                    (win ? " beats " : " does not beat ") +
                    computer.label +
                    ". Best streak: " +
                    bestStreak +
                    "."
                );
            } else {
                resultText.setText("Shuffle again for another round.");
            }
            actionButton.setText("Shuffle Again");
            actionButton.setEnabled(true);
        }

        for (int index = 0; index < cupButtons.length; index += 1) {
            Button button = cupButtons[index];
            CupItem item = slots.get(index);
            button.setEnabled(!"shuffling".equals(phase));

            if ("preview".equals(phase) || "result".equals(phase)) {
                button.setText(item.icon + "\n" + item.label);
            } else if ("picking".equals(phase) && index == computerIndex) {
                button.setText(item.icon + "\nComputer: " + item.label);
            } else {
                button.setText("Hidden Cup");
            }

            if (index == selectedIndex && "result".equals(phase)) {
                stylePrimaryButton(button);
            } else if (index == computerIndex && !"preview".equals(phase)) {
                styleDangerButton(button);
            } else {
                styleSecondaryButton(button);
            }
        }

        SharedAuthUser user = getSharedAuthUser();
        if (user == null) {
            linkStatusText.setText(
                "No shared Maru account is linked yet. Maru Link keeps the future cross-app profile lane ready for this game."
            );
        } else {
            linkStatusText.setText("Shared account ready for " + user.fullName + ".");
        }
    }

    private static final class CupItem {
        final String id;
        final String label;
        final String icon;
        final String beats;

        CupItem(String id, String label, String icon, String beats) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.beats = beats;
        }
    }
}

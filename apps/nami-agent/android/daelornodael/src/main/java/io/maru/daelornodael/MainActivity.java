package io.maru.daelornodael;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import io.maru.applets.LinkBoundActivity;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends LinkBoundActivity {
    private static final int[] BRIEFCASE_AMOUNTS = {
        1, 5, 10, 25, 50, 75, 100, 200, 300, 400, 500, 750,
        1_000, 5_000, 10_000, 25_000, 50_000, 75_000, 100_000, 200_000,
        300_000, 400_000, 500_000, 1_000_000
    };
    private static final int[] OPEN_SEQUENCE = { 6, 5, 4, 3, 2, 1, 1 };
    private static final int CASE_COLUMNS = 4;

    private final Random random = new Random();
    private final List<Briefcase> briefcases = new ArrayList<>();
    private final Button[] caseButtons = new Button[24];
    private final List<Integer> offerHistory = new ArrayList<>();

    private TextView statsText;
    private TextView statusText;
    private TextView bankerText;
    private TextView historyText;
    private TextView valuesText;
    private TextView linkStatusText;
    private Button keepButton;
    private Button dealButton;
    private Button noDealButton;
    private Button swapButton;
    private Button restartButton;

    private String phase = "pick-case";
    private int playerCaseId = -1;
    private int roundIndex;
    private int openedThisRound;
    private int currentOffer;
    private int roundsPlayed;
    private int bestDeal;
    private int bestNoDeal;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!ensureLinkAvailable("Dael or No Dael")) {
            return;
        }

        setTitle("Dael or No Dael");
        resetGame();

        LinearLayout root = createPageColumn();
        TextView eyebrow = makeEyebrowText("NATIVE APPLET");
        eyebrow.setPadding(0, 0, 0, dp(6));
        root.addView(eyebrow);
        root.addView(buildHeroCard());
        root.addView(buildBoardCard());
        root.addView(buildLinkCard());

        setPageContent(root);
        renderGame();
    }

    private View buildHeroCard() {
        LinearLayout card = makeCard();
        card.addView(makeSectionTitle("Dael or No Dael"));

        TextView body = makeBodyText(
            "Choose one case to keep sealed, open the rest in rounds, and decide when the banker's offer is good enough."
        );
        body.setPadding(0, dp(8), 0, dp(12));
        card.addView(body);

        statsText = makeBodyText("");
        statsText.setTextColor(Color.WHITE);
        card.addView(statsText);
        return card;
    }

    private View buildBoardCard() {
        LinearLayout card = makeCard();

        statusText = makeSectionTitle("");
        statusText.setTextSize(20f);
        card.addView(statusText);

        bankerText = makeBodyText("");
        bankerText.setPadding(0, dp(8), 0, dp(14));
        card.addView(bankerText);

        LinearLayout board = new LinearLayout(this);
        board.setOrientation(LinearLayout.VERTICAL);
        int totalRows = (int) Math.ceil(caseButtons.length / (double) CASE_COLUMNS);
        for (int rowIndex = 0; rowIndex < totalRows; rowIndex += 1) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            if (rowIndex > 0) {
                row.setPadding(0, dp(10), 0, 0);
            }
            for (int colIndex = 0; colIndex < CASE_COLUMNS; colIndex += 1) {
                int caseIndex = rowIndex * CASE_COLUMNS + colIndex;
                if (caseIndex >= caseButtons.length) {
                    break;
                }
                Button button = new Button(this);
                button.setAllCaps(false);
                button.setTextColor(Color.WHITE);
                button.setTextSize(16f);
                button.setPadding(dp(8), dp(20), dp(8), dp(20));
                styleSecondaryButton(button);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                );
                if (colIndex > 0) {
                    params.leftMargin = dp(8);
                }
                button.setLayoutParams(params);

                final int briefcaseId = caseIndex + 1;
                button.setOnClickListener(view -> onCasePressed(briefcaseId));
                caseButtons[caseIndex] = button;
                row.addView(button);
            }
            board.addView(row);
        }
        card.addView(board);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.VERTICAL);
        actionRow.setPadding(0, dp(18), 0, 0);

        dealButton = new Button(this);
        dealButton.setText("Dael");
        stylePrimaryButton(dealButton);
        dealButton.setOnClickListener(view -> acceptDeal());
        actionRow.addView(dealButton);

        noDealButton = new Button(this);
        noDealButton.setText("No Dael");
        styleSecondaryButton(noDealButton);
        LinearLayout.LayoutParams noDealParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        noDealParams.topMargin = dp(10);
        noDealButton.setLayoutParams(noDealParams);
        noDealButton.setOnClickListener(view -> rejectDeal());
        actionRow.addView(noDealButton);

        keepButton = new Button(this);
        keepButton.setText("Keep My Case");
        stylePrimaryButton(keepButton);
        LinearLayout.LayoutParams keepParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        keepParams.topMargin = dp(10);
        keepButton.setLayoutParams(keepParams);
        keepButton.setOnClickListener(view -> finishNoDeal(false));
        actionRow.addView(keepButton);

        swapButton = new Button(this);
        swapButton.setText("Swap Cases");
        styleSecondaryButton(swapButton);
        LinearLayout.LayoutParams swapParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        swapParams.topMargin = dp(10);
        swapButton.setLayoutParams(swapParams);
        swapButton.setOnClickListener(view -> finishNoDeal(true));
        actionRow.addView(swapButton);

        restartButton = new Button(this);
        restartButton.setText("New Round");
        stylePrimaryButton(restartButton);
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        restartParams.topMargin = dp(10);
        restartButton.setLayoutParams(restartParams);
        restartButton.setOnClickListener(view -> {
            resetGame();
            renderGame();
        });
        actionRow.addView(restartButton);
        card.addView(actionRow);

        historyText = makeBodyText("");
        historyText.setPadding(0, dp(16), 0, dp(8));
        historyText.setTextColor(Color.parseColor("#DDE7FF"));
        card.addView(historyText);

        valuesText = makeBodyText("");
        valuesText.setTextColor(Color.parseColor("#AFC1E8"));
        card.addView(valuesText);
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

    private void onCasePressed(int briefcaseId) {
        Briefcase briefcase = findBriefcase(briefcaseId);
        if (briefcase == null) {
            return;
        }

        if ("pick-case".equals(phase)) {
            playerCaseId = briefcaseId;
            phase = "open-cases";
            renderGame();
            return;
        }

        if (!"open-cases".equals(phase) || briefcase.id == playerCaseId || briefcase.opened) {
            return;
        }

        briefcase.opened = true;
        openedThisRound += 1;
        int boardCasesRemaining = getRemainingBoardCases().size();
        int target = getRoundTarget(boardCasesRemaining);
        if (openedThisRound >= target) {
            if (boardCasesRemaining <= 1) {
                phase = "swap";
            } else {
                currentOffer = calculateOffer();
                offerHistory.add(currentOffer);
                phase = "offer";
            }
        }
        renderGame();
    }

    private void acceptDeal() {
        if (!"offer".equals(phase)) {
            return;
        }
        roundsPlayed += 1;
        bestDeal = Math.max(bestDeal, currentOffer);
        phase = "finished";
        statusText.setText("Dael accepted.");
        bankerText.setText(
            "You took " + formatAmount(currentOffer) + " instead of risking your case."
        );
        renderGame();
    }

    private void rejectDeal() {
        if (!"offer".equals(phase)) {
            return;
        }
        roundIndex += 1;
        openedThisRound = 0;
        currentOffer = 0;
        if (getRemainingBoardCases().size() <= 1) {
            phase = "swap";
        } else {
            phase = "open-cases";
        }
        renderGame();
    }

    private void finishNoDeal(boolean swapped) {
        if (!"swap".equals(phase)) {
            return;
        }
        int finalCaseId = playerCaseId;
        if (swapped) {
            List<Briefcase> remainingBoardCases = getRemainingBoardCases();
            if (!remainingBoardCases.isEmpty()) {
                finalCaseId = remainingBoardCases.get(0).id;
            }
        }
        Briefcase finalCase = findBriefcase(finalCaseId);
        if (finalCase == null) {
            return;
        }
        roundsPlayed += 1;
        bestNoDeal = Math.max(bestNoDeal, finalCase.amount);
        playerCaseId = finalCaseId;
        phase = "finished";
        statusText.setText(swapped ? "Case swap finished." : "No dael to the end.");
        bankerText.setText(
            (swapped ? "You swapped into " : "You kept ") +
            "Case #" +
            finalCaseId +
            " and won " +
            formatAmount(finalCase.amount) +
            "."
        );
        renderGame();
    }

    private void resetGame() {
        briefcases.clear();
        List<Integer> shuffled = new ArrayList<>();
        for (int amount : BRIEFCASE_AMOUNTS) {
            shuffled.add(amount);
        }
        Collections.shuffle(shuffled, random);
        for (int index = 0; index < shuffled.size(); index += 1) {
            briefcases.add(new Briefcase(index + 1, shuffled.get(index)));
        }
        offerHistory.clear();
        phase = "pick-case";
        playerCaseId = -1;
        roundIndex = 0;
        openedThisRound = 0;
        currentOffer = 0;
    }

    private void renderGame() {
        statsText.setText(
            "Best dael " +
            formatAmount(bestDeal) +
            "   |   Best no-dael " +
            formatAmount(bestNoDeal) +
            "   |   Rounds " +
            roundsPlayed
        );

        if ("pick-case".equals(phase)) {
            statusText.setText("Choose your lucky case.");
            bankerText.setText("That case stays sealed until the end.");
        } else if ("open-cases".equals(phase)) {
            int remainingToOpen = Math.max(0, getRoundTarget(getRemainingBoardCases().size()) - openedThisRound);
            statusText.setText("Open " + remainingToOpen + " more case" + (remainingToOpen == 1 ? "" : "s") + ".");
            bankerText.setText("Round " + Math.min(roundIndex + 1, OPEN_SEQUENCE.length) + " is still live.");
        } else if ("offer".equals(phase)) {
            statusText.setText("The banker is calling.");
            bankerText.setText("Offer: " + formatAmount(currentOffer));
        } else if ("swap".equals(phase)) {
            statusText.setText("Keep or swap?");
            bankerText.setText("Only one board case is left. Decide how you want to finish.");
        } else if (!"finished".equals(phase)) {
            bankerText.setText("");
        }

        for (int index = 0; index < caseButtons.length; index += 1) {
            Button button = caseButtons[index];
            Briefcase briefcase = briefcases.get(index);
            boolean isPlayerCase = briefcase.id == playerCaseId;
            boolean canOpen = "open-cases".equals(phase) && !briefcase.opened && !isPlayerCase;
            boolean canChoose = "pick-case".equals(phase);

            if (briefcase.opened || "finished".equals(phase)) {
                button.setText("#" + briefcase.id + "\n" + formatCompactAmount(briefcase.amount));
            } else if (isPlayerCase) {
                button.setText("#" + briefcase.id + "\nYours");
            } else {
                button.setText("Case\n#" + briefcase.id);
            }

            button.setEnabled(canOpen || canChoose);
            if (isPlayerCase) {
                stylePrimaryButton(button);
            } else if (briefcase.opened) {
                styleDangerButton(button);
            } else {
                styleSecondaryButton(button);
            }
        }

        dealButton.setVisibility("offer".equals(phase) ? View.VISIBLE : View.GONE);
        noDealButton.setVisibility("offer".equals(phase) ? View.VISIBLE : View.GONE);
        keepButton.setVisibility("swap".equals(phase) ? View.VISIBLE : View.GONE);
        swapButton.setVisibility("swap".equals(phase) ? View.VISIBLE : View.GONE);
        restartButton.setVisibility("finished".equals(phase) ? View.VISIBLE : View.GONE);

        historyText.setText(buildOfferHistoryText());
        valuesText.setText(buildValuesText());

        SharedAuthUser user = getSharedAuthUser();
        if (user == null) {
            linkStatusText.setText(
                "No shared Maru account is linked yet. Maru Link keeps the shared profile lane ready for this app."
            );
        } else {
            linkStatusText.setText("Shared account ready for " + user.fullName + ".");
        }
    }

    private String buildOfferHistoryText() {
        if (offerHistory.isEmpty()) {
            return "Offer history: The banker has not made a call yet.";
        }
        StringBuilder builder = new StringBuilder("Offer history: ");
        for (int index = 0; index < offerHistory.size(); index += 1) {
            if (index > 0) {
                builder.append("  |  ");
            }
            builder.append("R").append(index + 1).append(" ").append(formatAmount(offerHistory.get(index)));
        }
        return builder.toString();
    }

    private String buildValuesText() {
        List<Integer> remaining = new ArrayList<>();
        for (Briefcase briefcase : briefcases) {
            if (!briefcase.opened) {
                remaining.add(briefcase.amount);
            }
        }
        Collections.sort(remaining);
        StringBuilder builder = new StringBuilder("Still alive: ");
        for (int index = 0; index < remaining.size(); index += 1) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(formatCompactAmount(remaining.get(index)));
        }
        return builder.toString();
    }

    private int getRoundTarget(int boardCasesRemaining) {
        int configured = OPEN_SEQUENCE[Math.min(roundIndex, OPEN_SEQUENCE.length - 1)];
        return Math.min(configured, boardCasesRemaining);
    }

    private List<Briefcase> getRemainingBoardCases() {
        List<Briefcase> remaining = new ArrayList<>();
        for (Briefcase briefcase : briefcases) {
            if (!briefcase.opened && briefcase.id != playerCaseId) {
                remaining.add(briefcase);
            }
        }
        return remaining;
    }

    private int calculateOffer() {
        List<Integer> unopenedValues = new ArrayList<>();
        for (Briefcase briefcase : briefcases) {
            if (!briefcase.opened) {
                unopenedValues.add(briefcase.amount);
            }
        }
        if (unopenedValues.isEmpty()) {
            return 0;
        }

        double sum = 0d;
        int highest = Integer.MIN_VALUE;
        int lowest = Integer.MAX_VALUE;
        for (Integer value : unopenedValues) {
            sum += value;
            highest = Math.max(highest, value);
            lowest = Math.min(lowest, value);
        }

        double average = sum / unopenedValues.size();
        double progressBoost = Math.min(
            0.96d,
            0.66d + roundIndex * 0.055d + (BRIEFCASE_AMOUNTS.length - unopenedValues.size()) * 0.012d
        );
        double volatilityPenalty = Math.min(
            0.12d,
            ((highest - average) / Math.max(highest, 1d)) * 0.22d
        );
        double sweetener = lowest * 0.35d;

        return Math.max(
            5,
            roundToNearestFive(average * (progressBoost - volatilityPenalty) + sweetener)
        );
    }

    private int roundToNearestFive(double value) {
        return (int) Math.round(value / 5d) * 5;
    }

    private String formatAmount(int value) {
        return "$" + NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    private String formatCompactAmount(int value) {
        if (value >= 1_000_000) {
            return String.format(Locale.US, "%.0fM", value / 1_000_000d);
        }
        if (value >= 1_000) {
            return String.format(Locale.US, "%.0fK", value / 1_000d);
        }
        return String.valueOf(value);
    }

    private Briefcase findBriefcase(int id) {
        for (Briefcase briefcase : briefcases) {
            if (briefcase.id == id) {
                return briefcase;
            }
        }
        return null;
    }

    private static final class Briefcase {
        final int id;
        final int amount;
        boolean opened;

        Briefcase(int id, int amount) {
            this.id = id;
            this.amount = amount;
        }
    }
}

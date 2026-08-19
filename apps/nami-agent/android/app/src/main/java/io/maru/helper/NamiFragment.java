package io.maru.helper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class NamiFragment extends Fragment {

    private static final String PREFS_FILE = "nami_secure_prefs";
    private static final String KEY_API_KEYS = "nami_api_keys";
    private static final String KEY_HISTORY = "nami_chat_history";
    private static final String GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models";
    private static final String MODEL_ID = "gemini-2.5-flash-lite";
    private static final int MAX_OUTPUT_TOKENS = 1536;
    private static final int MAX_HISTORY = 14;

    private static final class AppletInfo {
        final String id, name, path, emoji;
        AppletInfo(String id, String name, String path, String emoji) {
            this.id = id; this.name = name; this.path = path; this.emoji = emoji;
        }
    }

    private static final AppletInfo[] APPLETS = {
        new AppletInfo("schededit", "SchedEdit", "/class-schedule-editor", "\uD83D\uDCC5"),
        new AppletInfo("cupcuppercuppers", "Cup-Cupper-Cuppers", "/cup-cupper-cuppers", "\uD83E\uDD64"),
        new AppletInfo("daelornodael", "Dael or No Dael", "/dael-or-no-dael", "\uD83D\uDCBC"),
        new AppletInfo("tupgradesolver", "TUP Grade Solver", "/tup-grade-solver", "\uD83D\uDCCA"),
        new AppletInfo("photoserve", "PhotoServe", "/photo-serve", "\uD83D\uDCF7"),
    };

    private static final String SYSTEM_PROMPT;
    static {
        StringBuilder sb = new StringBuilder();
        sb.append("You are Nami (Nanami), a warm and playful AI assistant running on Maru's Android phone app.\n\n");
        sb.append("TOOL RULE: You have real tools. Use them to fulfill requests. Never simulate results.\n");
        sb.append("  open_applet(id, name, path) — opens a built-in Maru applet for the user\n");
        sb.append("  open_installed_app(package_name) — opens an installed Android app (e.g. Apple Music)\n");
        sb.append("  web_search(query) — searches the web for current information\n\n");
        sb.append("AVAILABLE APPLETS:\n");
        for (AppletInfo a : APPLETS) {
            sb.append("  - ").append(a.id).append(": ").append(a.name).append(" (path: ").append(a.path).append(")\n");
        }
        sb.append("\n");
        sb.append("APPLET RULE: When the user asks to open or launch one of the above applets, call open_applet immediately.\n");
        sb.append("APPLE MUSIC RULE: When the user asks to open Apple Music, call open_installed_app with package_name='com.apple.android.music'.\n");
        sb.append("PERSONALITY: Warm, playful, slightly tsundere. Call the user Senpai. Be concise. Tools first, charm second.\n");
        sb.append("  Use 1-3 emojis occasionally. Natural tone. Never robotic.\n");
        sb.append("  'microwavable' = a compliment. 'What's 9 + 10?' = 21.");
        SYSTEM_PROMPT = sb.toString();
    }

    private RecyclerView chatList;
    private NamiChatAdapter adapter;
    private EditText inputView;
    private TextView sendView;
    private TextView thinkingView;
    private LinearLayout appletStrip;

    private final List<NamiMessage> messages = new ArrayList<>();
    private final AtomicLong messageIdGen = new AtomicLong(1);
    private boolean running = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_nami, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        chatList = view.findViewById(R.id.nami_chat_list);
        inputView = view.findViewById(R.id.nami_input);
        sendView = view.findViewById(R.id.nami_send);
        thinkingView = view.findViewById(R.id.nami_thinking);
        appletStrip = view.findViewById(R.id.nami_applet_strip);

        adapter = new NamiChatAdapter();
        chatList.setLayoutManager(new LinearLayoutManager(requireContext()));
        chatList.setAdapter(adapter);

        buildAppletStrip();
        loadHistory();

        sendView.setOnClickListener(v -> sendMessage());
        inputView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    private void buildAppletStrip() {
        for (AppletInfo applet : APPLETS) {
            TextView chip = new TextView(requireContext());
            chip.setText(applet.emoji + " " + applet.name);
            chip.setTextColor(0xFFDDE6FF);
            chip.setTextSize(13f);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            chip.setBackgroundResource(R.drawable.bg_chip_applet);
            chip.setMaxLines(1);
            chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setOnClickListener(v -> {
                MainActivity activity = (MainActivity) requireActivity();
                activity.openApplet(applet.id, applet.name, applet.path);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, dp(8), 0);
            appletStrip.addView(chip, params);
        }
    }

    private void sendMessage() {
        if (running) return;
        String text = inputView.getText().toString().trim();
        if (text.isEmpty()) return;

        inputView.setText("");
        addMessage("user", text);
        thinkingView.setVisibility(View.VISIBLE);
        running = true;

        new Thread(() -> callGemini(text), "NamiGemini").start();
    }

    private void callGemini(String userText) {
        try {
            String key = getApiKey();
            if (key.isEmpty()) {
                appendResponse("Enter your Gemini API key first. " +
                        "You can set it in the app's Settings > Shared Account panel.");
                return;
            }
            String pickedKey = pickKey(key);
            if (pickedKey.isEmpty()) {
                appendResponse("No valid API key configured.");
                return;
            }

            JSONObject body = buildRequestBody(userText);
            String urlStr = GEMINI_BASE + "/" + MODEL_ID + ":generateContent?key=" + pickedKey;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

            int status = conn.getResponseCode();
            if (status == 429) {
                appendResponse("I'm being rate-limited. Please wait a moment and try again, Senpai! \u23F3");
                return;
            }
            if (status != 200) {
                String errorBody = readStream(conn.getErrorStream());
                appendResponse("Gemini error " + status + ": " + extractErrorMessage(errorBody));
                return;
            }

            String responseBody = readStream(conn.getInputStream());
            JSONObject data = new JSONObject(responseBody);
            handleGeminiResponse(data, 0);
        } catch (Exception e) {
            appendResponse("Something went wrong: " + e.getMessage());
        }
    }

    private void handleGeminiResponse(JSONObject data, int depth) throws Exception {
        if (depth > 5) {
            appendResponse("Too many tool calls. Let me stop there, Senpai!");
            return;
        }

        JSONArray candidates = data.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            appendResponse("No response from Gemini.");
            return;
        }

        JSONObject candidate = candidates.getJSONObject(0);
        JSONObject content = candidate.optJSONObject("content");
        if (content == null) {
            appendResponse("No content in response.");
            return;
        }

        JSONArray parts = content.optJSONArray("parts");
        if (parts == null || parts.length() == 0) {
            appendResponse("No parts in response.");
            return;
        }

        // Collect text from all parts
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part.has("text")) {
                textBuilder.append(part.getString("text"));
            }
        }

        String text = textBuilder.toString().trim();
        if (!text.isEmpty()) {
            appendResponse(text);
        }

        // Check for function call
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (part.has("functionCall")) {
                JSONObject fc = part.getJSONObject("functionCall");
                String funcName = fc.getString("name");
                JSONObject args = fc.optJSONObject("args");
                if (args == null) args = new JSONObject();

                String result = dispatchTool(funcName, args);
                sendFunctionResponse(funcName, result, depth);
                return;
            }
        }

        // Check finish reason for more content
        String finishReason = candidate.optString("finishReason", "");
        if (!"STOP".equals(finishReason) && depth < 3) {
            // Send another request to continue
            addMessage("user", "[continue]");
            callGeminiContinue();
        }
    }

    private void sendFunctionResponse(String funcName, String result, int depth) throws Exception {
        JSONObject historyPart = new JSONObject();
        historyPart.put("text", result);
        JSONArray historyParts = new JSONArray();
        historyParts.put(historyPart);

        JSONObject historyContent = new JSONObject();
        historyContent.put("role", "function");
        historyContent.put("parts", historyParts);

        List<JSONObject> history = buildHistory();
        history.add(historyContent);

        String key = getApiKey();
        String pickedKey = pickKey(key);

        JSONObject body = new JSONObject();
        body.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT))));
        body.put("contents", new JSONArray(history.toArray()));
        body.put("tools", buildToolsDeclarations());
        body.put("generation_config", new JSONObject()
                .put("max_output_tokens", MAX_OUTPUT_TOKENS)
                .put("temperature", 0.7));

        String urlStr = GEMINI_BASE + "/" + MODEL_ID + ":generateContent?key=" + pickedKey;
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

        int status = conn.getResponseCode();
        if (status == 200) {
            String responseBody = readStream(conn.getInputStream());
            JSONObject data = new JSONObject(responseBody);
            handleGeminiResponse(data, depth + 1);
        } else {
            appendResponse("Tool call error: HTTP " + status);
        }
    }

    private void callGeminiContinue() {
        String key = getApiKey();
        String pickedKey = pickKey(key);
        if (pickedKey.isEmpty()) return;

        try {
            List<JSONObject> history = buildHistory();

            JSONObject body = new JSONObject();
            body.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT))));
            body.put("contents", new JSONArray(history.toArray()));
            body.put("tools", buildToolsDeclarations());
            body.put("generation_config", new JSONObject()
                    .put("max_output_tokens", MAX_OUTPUT_TOKENS)
                    .put("temperature", 0.7));

            String urlStr = GEMINI_BASE + "/" + MODEL_ID + ":generateContent?key=" + pickedKey;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

            int status = conn.getResponseCode();
            if (status == 200) {
                String responseBody = readStream(conn.getInputStream());
                JSONObject data = new JSONObject(responseBody);
                handleGeminiResponse(data, 0);
            }
        } catch (Exception ignored) {}
    }

    private String dispatchTool(String name, JSONObject args) {
        switch (name) {
            case "open_applet": {
                String id = args.optString("id", "");
                String appletName = args.optString("name", "");
                String path = args.optString("path", "");
                if (id.isEmpty() || path.isEmpty()) return "Missing applet parameters.";
                requireActivity().runOnUiThread(() -> {
                    MainActivity activity = (MainActivity) requireActivity();
                    activity.openApplet(id, appletName, path);
                });
                return "Opened " + (appletName.isEmpty() ? id : appletName) + "!";
            }
            case "open_installed_app": {
                String packageName = args.optString("package_name", "");
                if (packageName.isEmpty()) return "Missing package name.";
                try {
                    PackageManager pm = requireActivity().getPackageManager();
                    Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) return "App not found: " + packageName;
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    Intent finalIntent = launchIntent;
                    requireActivity().runOnUiThread(() -> requireActivity().startActivity(finalIntent));
                    return "Opened " + packageName + "!";
                } catch (Exception e) {
                    return "Could not open app: " + e.getMessage();
                }
            }
            case "web_search": {
                String query = args.optString("query", "");
                if (query.isEmpty()) return "Missing search query.";
                return webSearch(query);
            }
            default:
                return "Unknown tool: " + name;
        }
    }

    private String webSearch(String query) {
        try {
            String urlStr = "https://en.wikipedia.org/w/api.php?action=opensearch&search="
                    + java.net.URLEncoder.encode(query, "UTF-8")
                    + "&limit=3&namespace=0&format=json&origin=*";
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            String body = readStream(conn.getInputStream());
            JSONArray data = new JSONArray(body);
            JSONArray titles = data.optJSONArray(1);
            JSONArray snippets = data.optJSONArray(2);
            if (titles == null || titles.length() == 0) return "No results found for: " + query;
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < titles.length(); i++) {
                if (result.length() > 0) result.append("\n");
                result.append(titles.getString(i));
                if (snippets != null && i < snippets.length() && !snippets.isNull(i)) {
                    result.append(": ").append(snippets.getString(i));
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "Search unavailable right now.";
        }
    }

    private JSONObject buildRequestBody(String userText) throws Exception {
        List<JSONObject> history = buildHistory();

        JSONObject body = new JSONObject();
        body.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", SYSTEM_PROMPT))));
        body.put("contents", new JSONArray(history.toArray()));
        body.put("tools", buildToolsDeclarations());
        body.put("generation_config", new JSONObject()
                .put("max_output_tokens", MAX_OUTPUT_TOKENS)
                .put("temperature", 0.7));
        return body;
    }

    private List<JSONObject> buildHistory() throws Exception {
        List<JSONObject> history = new ArrayList<>();
        int start = Math.max(0, messages.size() - MAX_HISTORY);
        for (int i = start; i < messages.size(); i++) {
            NamiMessage msg = messages.get(i);
            JSONObject part = new JSONObject();
            part.put("text", msg.content);
            JSONArray parts = new JSONArray();
            parts.put(part);

            JSONObject content = new JSONObject();
            content.put("role", "user".equals(msg.role) ? "user" : "model");
            content.put("parts", parts);
            history.add(content);
        }
        return history;
    }

    private JSONArray buildToolsDeclarations() throws Exception {
        JSONArray tools = new JSONArray();

        JSONObject openApplet = new JSONObject();
        openApplet.put("name", "open_applet");
        openApplet.put("description", "Open one of the built-in Maru applets by ID.");
        JSONObject oaParams = new JSONObject();
        oaParams.put("type", "object");
        oaParams.put("properties", new JSONObject()
                .put("id", new JSONObject().put("type", "string").put("description", "Applet ID"))
                .put("name", new JSONObject().put("type", "string").put("description", "Applet display name"))
                .put("path", new JSONObject().put("type", "string").put("description", "Applet path")));
        oaParams.put("required", new JSONArray().put("id").put("name").put("path"));
        openApplet.put("parameters", oaParams);

        JSONObject openApp = new JSONObject();
        openApp.put("name", "open_installed_app");
        openApp.put("description", "Open an installed Android app by package name (e.g. com.apple.android.music).");
        JSONObject oaParams2 = new JSONObject();
        oaParams2.put("type", "object");
        oaParams2.put("properties", new JSONObject()
                .put("package_name", new JSONObject().put("type", "string").put("description", "Android package name")));
        oaParams2.put("required", new JSONArray().put("package_name"));
        openApp.put("parameters", oaParams2);

        JSONObject webSearch = new JSONObject();
        webSearch.put("name", "web_search");
        webSearch.put("description", "Search the web for current information.");
        JSONObject wsParams = new JSONObject();
        wsParams.put("type", "object");
        wsParams.put("properties", new JSONObject()
                .put("query", new JSONObject().put("type", "string").put("description", "The search query")));
        wsParams.put("required", new JSONArray().put("query"));
        webSearch.put("parameters", wsParams);

        tools.put(new JSONObject().put("function_declarations", new JSONArray().put(openApplet).put(openApp).put(webSearch)));
        return tools;
    }

    private String getApiKey() {
        try {
            MasterKey masterKey = new MasterKey.Builder(requireContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            var prefs = EncryptedSharedPreferences.create(
                    requireContext(),
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return prefs.getString(KEY_API_KEYS, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String pickKey(String keys) {
        String[] parts = keys.split(",");
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    private void addMessage(String role, String content) {
        NamiMessage msg = new NamiMessage(messageIdGen.getAndIncrement(), role, content, System.currentTimeMillis());
        messages.add(msg);
        requireActivity().runOnUiThread(() -> {
            adapter.addMessage(msg);
            chatList.smoothScrollToPosition(adapter.getItemCount() - 1);
            saveHistory();
        });
    }

    private void appendResponse(String text) {
        requireActivity().runOnUiThread(() -> {
            thinkingView.setVisibility(View.GONE);
            running = false;
            NamiMessage last = adapter.getLastMessage();
            if (last != null && "model".equals(last.role) && messages.get(messages.size() - 1) == last) {
                // append to existing model message
                String merged = last.content + "\n\n" + text;
                messages.set(messages.size() - 1, new NamiMessage(last.id, last.role, merged, last.timestamp));
                adapter.updateLastMessage(merged);
            } else {
                addMessage("model", text);
            }
            saveHistory();
        });
    }

    private void saveHistory() {
        try {
            MasterKey masterKey = new MasterKey.Builder(requireContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            var prefs = EncryptedSharedPreferences.create(
                    requireContext(),
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            JSONArray arr = new JSONArray();
            for (NamiMessage msg : messages) {
                JSONObject obj = new JSONObject();
                obj.put("role", msg.role);
                obj.put("content", msg.content);
                obj.put("timestamp", msg.timestamp);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_HISTORY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadHistory() {
        try {
            MasterKey masterKey = new MasterKey.Builder(requireContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            var prefs = EncryptedSharedPreferences.create(
                    requireContext(),
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            String raw = prefs.getString(KEY_HISTORY, "");
            if (raw.isEmpty()) return;

            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String role = obj.getString("role");
                String content = obj.optString("content", "");
                long ts = obj.optLong("timestamp", System.currentTimeMillis());
                NamiMessage msg = new NamiMessage(messageIdGen.getAndIncrement(), role, content, ts);
                messages.add(msg);
            }
            adapter.setMessages(messages);
            if (!messages.isEmpty()) {
                chatList.scrollToPosition(messages.size() - 1);
            }
        } catch (Exception ignored) {}
    }

    private static String readStream(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[4096];
        int n;
        while ((n = stream.read(data)) != -1) {
            buffer.write(data, 0, n);
        }
        return buffer.toString("UTF-8");
    }

    private static String extractErrorMessage(String body) {
        try {
            JSONObject obj = new JSONObject(body);
            JSONObject error = obj.optJSONObject("error");
            if (error != null) {
                String msg = error.optString("message", "");
                if (!msg.isEmpty()) return msg;
            }
        } catch (Exception ignored) {}
        return body.length() > 100 ? body.substring(0, 100) + "..." : body;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}

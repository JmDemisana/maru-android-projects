package io.maru.helper;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

class NamiChatAdapter extends RecyclerView.Adapter<NamiChatAdapter.MessageHolder> {

    static final int TYPE_USER = 0;
    static final int TYPE_ASSISTANT = 1;

    private final List<NamiMessage> messages = new ArrayList<>();

    void setMessages(List<NamiMessage> msgs) {
        messages.clear();
        messages.addAll(msgs);
        notifyDataSetChanged();
    }

    void addMessage(NamiMessage msg) {
        messages.add(msg);
        notifyItemInserted(messages.size() - 1);
    }

    void updateLastMessage(String content) {
        if (!messages.isEmpty()) {
            NamiMessage last = messages.get(messages.size() - 1);
            messages.set(messages.size() - 1, new NamiMessage(last.id, last.role, content, last.timestamp));
            notifyItemChanged(messages.size() - 1);
        }
    }

    NamiMessage getLastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    @Override
    public int getItemViewType(int position) {
        return "user".equals(messages.get(position).role) ? TYPE_USER : TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public MessageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_USER) {
            return new MessageHolder(inflater.inflate(R.layout.item_nami_user, parent, false), true);
        }
        return new MessageHolder(inflater.inflate(R.layout.item_nami_assistant, parent, false), false);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageHolder holder, int position) {
        holder.bind(messages.get(position));
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageHolder extends RecyclerView.ViewHolder {
        private final TextView textView;
        private final boolean isUser;

        MessageHolder(@NonNull View itemView, boolean isUser) {
            super(itemView);
            this.isUser = isUser;
            this.textView = itemView.findViewById(android.R.id.text1);
        }

        void bind(NamiMessage msg) {
            textView.setText(renderContent(msg.content));
        }

        private CharSequence renderContent(String content) {
            SpannableStringBuilder ssb = new SpannableStringBuilder();
            boolean inBold = false;
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '*' && i + 1 < content.length() && content.charAt(i + 1) == '*') {
                    if (current.length() > 0) {
                        int start = ssb.length();
                        ssb.append(current);
                        if (inBold) {
                            ssb.setSpan(new StyleSpan(Typeface.BOLD), start, ssb.length(), 0);
                        }
                        current = new StringBuilder();
                    }
                    inBold = !inBold;
                    i++;
                    continue;
                }
                if (c == '\n') {
                    if (current.length() > 0) {
                        int start = ssb.length();
                        ssb.append(current);
                        if (inBold) {
                            ssb.setSpan(new StyleSpan(Typeface.BOLD), start, ssb.length(), 0);
                        }
                        current = new StringBuilder();
                    }
                    ssb.append('\n');
                    continue;
                }
                current.append(c);
            }
            if (current.length() > 0) {
                int start = ssb.length();
                ssb.append(current);
                if (inBold) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), start, ssb.length(), 0);
                }
            }
            return ssb;
        }
    }
}

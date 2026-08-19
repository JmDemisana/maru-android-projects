package io.maru.helper;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppsFragment extends Fragment {
    private final List<AppletItem> appletItems = buildApplets();

    @Nullable
    @Override
    public View onCreateView(
        @NonNull LayoutInflater inflater,
        @Nullable ViewGroup container,
        @Nullable Bundle savedInstanceState
    ) {
        View view = inflater.inflate(R.layout.fragment_apps, container, false);

        RecyclerView recyclerView = view.findViewById(R.id.apps_recycler);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(new AppletsAdapter(appletItems));

        return view;
    }

    private List<AppletItem> buildApplets() {
        List<AppletItem> applets = new ArrayList<>();
        applets.add(new AppletItem(
            "schededit",
            "SchedEdit",
            "Weekly classes and reminders",
            R.drawable.ic_schedule,
            "/mobile-app?applet=schededit"
        ));
        applets.add(new AppletItem(
            "cupcuppercuppers",
            "Cup-Cupper-Cuppers",
            "Focus and memory shell game",
            R.drawable.ic_cup,
            "/cup-cupper-cuppers"
        ));
        applets.add(new AppletItem(
            "daelornodael",
            "Dael or No Dael",
            "Case ladder risk game",
            R.drawable.ic_dael,
            "/dael-or-no-dael"
        ));
        applets.add(new AppletItem(
            "tupgradesolver",
            "TUP Grade Solver",
            "Target score calculator",
            R.drawable.ic_grade,
            "/tup-grade-solver"
        ));
        applets.add(new AppletItem(
            "photoserve",
            "PhotoServe",
            "Photo layout and export workstation",
            R.drawable.ic_photoserve,
            "/photoserve"
        ));
        return applets;
    }

    static final class AppletItem {
        final String id;
        final String name;
        final String desc;
        final int icon;
        final String path;

        AppletItem(String id, String name, String desc, int icon, String path) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.icon = icon;
            this.path = path;
        }
    }

    final class AppletsAdapter extends RecyclerView.Adapter<AppletsAdapter.ViewHolder> {
        private final List<AppletItem> items;

        AppletsAdapter(List<AppletItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater
                .from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            AppletItem item = items.get(position);
            MainActivity activity = (MainActivity) requireActivity();

            holder.icon.setImageResource(item.icon);
            holder.name.setText(item.name);
            holder.desc.setText(item.desc);
            holder.meta.setText("Opens inside Maru.");
            holder.progressBar.setVisibility(View.GONE);
            holder.progressText.setVisibility(View.GONE);
            holder.progressBar.setIndeterminate(false);
            holder.actionButton.setText("Open");
            holder.actionButton.setEnabled(true);
            holder.actionButton.setOnClickListener(view -> {
                boolean opened = activity.openApplet(item.id, item.name, item.path);
                if (!opened) {
                    Toast.makeText(
                        view.getContext(),
                        item.name + " could not open right now.",
                        Toast.LENGTH_SHORT
                    ).show();
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView desc;
            final TextView meta;
            final ProgressBar progressBar;
            final TextView progressText;
            final Button actionButton;

            ViewHolder(View view) {
                super(view);
                icon = view.findViewById(R.id.app_icon);
                name = view.findViewById(R.id.app_name);
                desc = view.findViewById(R.id.app_desc);
                meta = view.findViewById(R.id.app_meta);
                progressBar = view.findViewById(R.id.app_progress_bar);
                progressText = view.findViewById(R.id.app_progress_text);
                actionButton = view.findViewById(R.id.download_btn);
            }
        }
    }
}

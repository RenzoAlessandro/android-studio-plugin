package com.crowdin.action;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

public abstract class BackgroundAction extends AnAction {

    abstract void performInBackground(@NonNull AnActionEvent e, @NonNull ProgressIndicator indicator);

    abstract String loadingText(AnActionEvent e);

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ProgressManager.getInstance().run(new Task.Backgroundable(e.getProject(), "Crowdin") {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText(loadingText(e));
                performInBackground(e, indicator);
            }
        });

    }
}

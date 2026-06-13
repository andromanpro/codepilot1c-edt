package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager;

/**
 * Shared resolution of the EDT project a tool should act on when {@code projectName} is omitted:
 * the active editor project (from the current session), or — if exactly one project is open — that
 * project. Used by read-only tools to avoid forcing the agent to repeat the project name.
 */
public final class ActiveProjectSupport {

    private ActiveProjectSupport() {
    }

    /**
     * @return the active editor project, or the single open project, or {@code null} when the
     *     project cannot be determined unambiguously (zero or multiple open projects, no session).
     */
    public static IProject resolveActiveProject() {
        try {
            Session session = SessionManager.getInstance().getOrCreateCurrentSession();
            if (session != null && session.getProjectPath() != null && !session.getProjectPath().isEmpty()) {
                IProject project = SessionManager.getInstance().findProjectByPath(session.getProjectPath());
                if (project != null && project.exists() && project.isOpen()) {
                    return project;
                }
            }
        } catch (Exception e) {
            // Fall through to the single-open-project heuristic.
        }
        List<IProject> open = openProjects();
        return open.size() == 1 ? open.get(0) : null;
    }

    /** @return the resolved active project name, or {@code null}. */
    public static String resolveActiveProjectName() {
        IProject project = resolveActiveProject();
        return project != null ? project.getName() : null;
    }

    /** @return all open projects in the workspace (never {@code null}). */
    public static List<IProject> openProjects() {
        List<IProject> result = new ArrayList<>();
        try {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.exists() && project.isOpen()) {
                    result.add(project);
                }
            }
        } catch (Exception e) {
            // Workspace unavailable; return what we have.
        }
        return result;
    }

    /** Comma-separated names of open projects, for actionable error messages. */
    public static String openProjectNames() {
        List<IProject> open = openProjects();
        return open.isEmpty()
                ? "(no open projects)" //$NON-NLS-1$
                : open.stream().map(IProject::getName).collect(Collectors.joining(", ")); //$NON-NLS-1$
    }
}

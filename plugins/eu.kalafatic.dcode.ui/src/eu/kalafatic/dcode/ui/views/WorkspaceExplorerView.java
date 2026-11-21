package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.ui.DesktopActions;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import eu.kalafatic.dcode.ui.editors.DCodeFileEditorInput;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.ViewPart;

/**
 * Multi-repository workspace explorer. All data comes from the backend API; the tree
 * is lazily loaded per directory. Repository nodes expose git operations through the
 * policy-enforced API endpoints. Loading, empty, error and recovery states are
 * rendered explicitly — the view is never silently blank.
 */
public final class WorkspaceExplorerView extends ViewPart implements Ui.Refreshable {

    private Tree tree;
    private org.eclipse.swt.widgets.Label status;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new org.eclipse.swt.layout.GridLayout(1, false));
        status = new org.eclipse.swt.widgets.Label(root, SWT.WRAP);
        status.setLayoutData(new org.eclipse.swt.layout.GridData(
                org.eclipse.swt.layout.GridData.FILL_HORIZONTAL));
        tree = new Tree(root, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        tree.setLayoutData(new org.eclipse.swt.layout.GridData(
                org.eclipse.swt.layout.GridData.FILL_BOTH));
        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Add repository…") {
            @Override
            public void run() {
                openAddRepositoryDialog();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("New workspace…") {
            @Override
            public void run() {
                openNewWorkspaceDialog();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Connection…") {
            @Override
            public void run() {
                try {
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
                            .showView("eu.kalafatic.dcode.ui.views.connection");
                } catch (Exception e) {
                    Ui.log("error", "Cannot open Connection view: " + e.getMessage());
                }
            }
        });
        hookContextMenu();
        refreshContent();
    }

    private void openNewWorkspaceDialog() {
        InputDialog dialog = new InputDialog(tree.getShell(), "New workspace",
                "Workspace name:", "", null);
        if (dialog.open() == org.eclipse.jface.dialogs.Dialog.OK) {
            String name = dialog.getValue().trim();
            if (name.isEmpty()) {
                return;
            }
            Ui.run(RuntimeConnection.instance().api().createWorkspace(name, null),
                    workspace -> {
                        Ui.log("success", "Workspace '" + workspace.name()
                                + "' created");
                        refreshContent();
                    }, "create workspace");
        }
    }

    private void hookContextMenu() {
        MenuManager manager = new MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(new IMenuListener() {
            @Override
            public void menuAboutToShow(IMenuManager menu) {
                TreeItem[] selection = tree.getSelection();
                if (selection.length == 0) {
                    return;
                }
                TreeItem item = selection[0];
                Object data = item.getData();
                if (data instanceof Workspace workspace) {
                    fillWorkspaceMenu(menu, workspace);
                } else if (data instanceof RepoNode repoNode) {
                    fillRepositoryMenu(menu, repoNode);
                } else if (data instanceof FileNode fileNode) {
                    fillFileMenu(menu, fileNode);
                }
            }
        });
        Menu menu = manager.createContextMenu(tree);
        tree.setMenu(menu);
    }

    private void fillWorkspaceMenu(IMenuManager menu, Workspace workspace) {
        menu.add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });
        menu.add(new Action("Copy Path") {
            @Override
            public void run() {
                copyToClipboard(workspace.rootPath());
            }
        });
        menu.add(new Action("Open Terminal") {
            @Override
            public void run() {
                DesktopActions.openTerminalIn(workspace.rootPath());
            }
        });
    }

    private void fillRepositoryMenu(IMenuManager menu, RepoNode repoNode) {
        menu.add(new Action("Git status") {
            @Override
            public void run() {
                Ui.run(RuntimeConnection.instance().api().repositoryStatus(
                                repoNode.workspace().id(), repoNode.repositoryId()),
                        WorkspaceExplorerView.this::showJson,
                        "git status");
            }
        });
        menu.add(new Action("Diff") {
            @Override
            public void run() {
                Ui.run(RuntimeConnection.instance().api().repositoryDiff(
                                repoNode.workspace().id(), repoNode.repositoryId()),
                        WorkspaceExplorerView.this::showJson,
                        "git diff");
            }
        });
        menu.add(new Action("Commit…") {
            @Override
            public void run() {
                InputDialog dialog = new InputDialog(tree.getShell(), "Commit",
                        "Commit message:", "", null);
                if (dialog.open() == org.eclipse.jface.dialogs.Dialog.OK) {
                    Ui.run(RuntimeConnection.instance().api().repositoryCommit(
                                    repoNode.workspace().id(), repoNode.repositoryId(),
                                    dialog.getValue(), null),
                            json -> Ui.log("Commit: " + json.path("commit").asText()),
                            "commit");
                }
            }
        });
        menu.add(new Action("Pull") {
            @Override
            public void run() {
                Ui.run(RuntimeConnection.instance().api().repositoryPull(
                                repoNode.workspace().id(), repoNode.repositoryId()),
                        json -> Ui.log("Pull done"),
                        "pull");
            }
        });
        menu.add(new Action("Push") {
            @Override
            public void run() {
                Ui.run(RuntimeConnection.instance().api().repositoryPush(
                                repoNode.workspace().id(), repoNode.repositoryId()),
                        json -> Ui.log("Push done"),
                        "push");
            }
        });
        menu.add(new Action("Java project structure") {
            @Override
            public void run() {
                Ui.run(RuntimeConnection.instance().api().executeTool(
                                "java_project_structure", activeSessionId(),
                                args("repository", repoNode.repositoryName())),
                        json -> Ui.log("Java tooling: " + json.toString()),
                        "java_project_structure");
            }
        });
    }

    private void fillFileMenu(IMenuManager menu, FileNode fileNode) {
        menu.add(new Action("Open") {
            @Override
            public void run() {
                openFile(fileNode);
            }
        });
        menu.add(new Action("Copy Path") {
            @Override
            public void run() {
                copyToClipboard(fileNode.workspace().rootPath() + "/" + fileNode.path());
            }
        });
        if (fileNode.directory()) {
            menu.add(new Action("Open Terminal") {
                @Override
                public void run() {
                    DesktopActions.openTerminalIn(fileNode.workspace().rootPath()
                            + "/" + fileNode.path());
                }
            });
        }
        if (fileNode.path().endsWith(".java")) {
            menu.add(new Action("AST summary") {
                @Override
                public void run() {
                    Ui.run(RuntimeConnection.instance().api().executeTool("java_ast_summary",
                                    activeSessionId(), args("path", fileNode.path())),
                            json -> Ui.log("AST: " + json.toString()),
                            "java_ast_summary");
                }
            });
            menu.add(new Action("Analyze imports") {
                @Override
                public void run() {
                    Ui.run(RuntimeConnection.instance().api().executeTool(
                                    "java_analyze_imports", activeSessionId(),
                                    args("path", fileNode.path())),
                            json -> Ui.log("Imports: " + json.toString()),
                            "java_analyze_imports");
                }
            });
        }
        if (fileNode.path().endsWith("MANIFEST.MF")) {
            menu.add(new Action("Inspect bundle") {
                @Override
                public void run() {
                    String bundleDir = fileNode.path().replace("/META-INF/MANIFEST.MF", "");
                    Ui.run(RuntimeConnection.instance().api().executeTool(
                                    "pde_inspect_bundle", activeSessionId(),
                                    args("path", bundleDir)),
                            json -> Ui.log("Bundle: " + json.toString()),
                            "pde_inspect_bundle");
                }
            });
        }
    }

    private void openFile(FileNode fileNode) {
        try {
            PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
                    .openEditor(new DCodeFileEditorInput(fileNode.workspace(),
                            fileNode.repositoryName(), fileNode.path()),
                            "eu.kalafatic.dcode.ui.editors.file");
        } catch (Exception e) {
            Ui.log("error", "cannot open " + fileNode.path() + ": " + e.getMessage());
        }
    }

    /** Copies a path to the clipboard (single implementation for all explorer nodes). */
    private void copyToClipboard(String path) {
        if (path == null || path.isBlank()) {
            Ui.log("error", "No local path known for the selection (server-side entry)");
            return;
        }
        try {
            org.eclipse.swt.dnd.Clipboard clipboard =
                    new org.eclipse.swt.dnd.Clipboard(tree.getDisplay());
            clipboard.setContents(new Object[]{path},
                    new org.eclipse.swt.dnd.Transfer[]{
                            org.eclipse.swt.dnd.TextTransfer.getInstance()});
            clipboard.dispose();
            Ui.log("info", "Path copied: " + path);
        } catch (Exception e) {
            Ui.log("error", "Copy path failed: " + e.getMessage());
        }
    }

    private void openAddRepositoryDialog() {
        Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
            if (workspaces.isEmpty()) {
                Ui.log("error", "create a workspace first (no workspaces exist)");
                return;
            }
            Workspace workspace = workspaces.get(0);
            AddRepositoryDialog dialog = new AddRepositoryDialog(tree.getShell(), workspace);
            dialog.open();
            refreshContent();
        }, "list workspaces");
    }

    private void showJson(JsonNode json) {
        org.eclipse.jface.dialogs.MessageDialog.openInformation(tree.getShell(),
                "Git", json.toString());
    }

    private String activeSessionId() {
        var session = RuntimeConnection.instance().activeSession();
        return session != null ? session.getId() : "";
    }

    private java.util.Map<String, Object> args(String key, String value) {
        return java.util.Map.of(key, value);
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            tree.removeAll();
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                renderErrorState("Backend connection is " + RuntimeConnection.instance()
                        .state().name() + ".", "Reconnect or fix the endpoint from the "
                        + "Connection view (toolbar: Connection…)");
                return;
            }
            status.setText("Loading workspaces…");
            tree.setRedraw(false);
            Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
                if (workspaces.isEmpty()) {
                    status.setText("No workspaces yet — use \"New workspace…\" "
                            + "to create one, or \"Add repository…\" to attach a "
                            + "repository to the first workspace.");
                    return;
                }
                status.setText(workspaces.size() + " workspace(s)");
                for (Workspace workspace : workspaces) {
                    TreeItem item = new TreeItem(tree, SWT.NONE);
                    item.setText(workspace.name() + "  (" + workspace.repositories().size()
                            + " repositories)");
                    item.setData(workspace);
                    new TreeItem(item, SWT.NONE).setText("loading…");
                    item.addListener(SWT.Expand, event -> loadWorkspace(item, workspace));
                }
            }, "list workspaces");
            tree.setRedraw(true);
        });
    }

    private void renderErrorState(String headline, String recovery) {
        status.setText(headline + "\n" + recovery);
        TreeItem item = new TreeItem(tree, SWT.NONE);
        item.setText("(backend unavailable — see status above and the Console for "
                + "details)");
    }

    private void loadWorkspace(TreeItem item, Workspace workspace) {
        if (item.getItemCount() == 1 && item.getItem(0).getText().equals("loading…")) {
            item.removeAll();
            for (var repository : workspace.repositories()) {
                TreeItem repoItem = new TreeItem(item, SWT.NONE);
                repoItem.setText(repository.name()
                        + (repository.isReadOnly() ? "  [READ_ONLY]" : ""));
                repoItem.setData(new RepoNode(workspace, repository.id(),
                        repository.name()));
                new TreeItem(repoItem, SWT.NONE).setText("loading…");
                repoItem.addListener(SWT.Expand,
                        event -> loadRepository(repoItem, workspace, repository.name()));
            }
        }
    }

    private void loadRepository(TreeItem repoItem, Workspace workspace, String repoName) {
        if (repoItem.getItemCount() == 1
                && repoItem.getItem(0).getText().equals("loading…")) {
            repoItem.removeAll();
            Ui.run(RuntimeConnection.instance().api().tree(workspace.id(), "", 1),
                    root -> {
                        for (JsonNode entry : root.path("entries")) {
                            String path = entry.path("path").asText();
                            String name = entry.path("name").asText();
                            boolean dir = "dir".equals(entry.path("type").asText());
                            TreeItem child = new TreeItem(repoItem, SWT.NONE);
                            child.setText(name);
                            child.setData(new FileNode(workspace, repoName, path, dir));
                            if (dir) {
                                new TreeItem(child, SWT.NONE).setText("loading…");
                                child.addListener(SWT.Expand,
                                        event -> loadDirectory(child, workspace, repoName,
                                                path));
                            }
                        }
                    }, "tree");
        }
    }

    private void loadDirectory(TreeItem item, Workspace workspace, String repoName,
                               String path) {
        if (item.getItemCount() == 1 && item.getItem(0).getText().equals("loading…")) {
            item.removeAll();
            Ui.run(RuntimeConnection.instance().api().tree(workspace.id(), path, 1),
                    root -> {
                        for (JsonNode entry : root.path("entries")) {
                            String childPath = entry.path("path").asText();
                            String name = entry.path("name").asText();
                            boolean dir = "dir".equals(entry.path("type").asText());
                            TreeItem child = new TreeItem(item, SWT.NONE);
                            child.setText(name);
                            child.setData(new FileNode(workspace, repoName, childPath,
                                    dir));
                            if (dir) {
                                new TreeItem(child, SWT.NONE).setText("loading…");
                                child.addListener(SWT.Expand,
                                        event -> loadDirectory(child, workspace, repoName,
                                                childPath));
                            }
                        }
                    }, "tree");
        }
    }

    @Override
    public void setFocus() {
        tree.setFocus();
    }

    record RepoNode(Workspace workspace, String repositoryId, String repositoryName) {
    }

    record FileNode(Workspace workspace, String repositoryName, String path,
                    boolean directory) {
    }
}

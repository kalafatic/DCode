package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.core.model.Workspace;
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
 * policy-enforced API endpoints.
 */
public final class WorkspaceExplorerView extends ViewPart implements Ui.Refreshable {

    private Tree tree;

    @Override
    public void createPartControl(Composite parent) {
        tree = new Tree(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
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
        hookContextMenu();
        refreshContent();
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
            tree.setRedraw(false);
            Ui.run(RuntimeConnection.instance().api().listWorkspaces(), workspaces -> {
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
                            child.setData(new FileNode(workspace, repoName, path));
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
                            child.setData(new FileNode(workspace, repoName, childPath));
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

    record FileNode(Workspace workspace, String repositoryName, String path) {
    }
}

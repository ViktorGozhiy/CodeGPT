package ee.carlrobert.codegpt.actions;

import static com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE_ARRAY;
import static com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE;
import static java.lang.String.format;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.ui.CheckboxTreeListener;
import com.intellij.ui.CheckedTreeNode;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UI.PanelFactory;
import ee.carlrobert.codegpt.CodeGPTBundle;
import ee.carlrobert.codegpt.CodeGPTKeys;
import ee.carlrobert.codegpt.EncodingManager;
import ee.carlrobert.codegpt.settings.state.IncludedFilesSettingsState;
import ee.carlrobert.codegpt.ui.UIUtil;
import ee.carlrobert.codegpt.ui.checkbox.FileCheckboxTree;
import ee.carlrobert.codegpt.ui.checkbox.PsiElementCheckboxTree;
import ee.carlrobert.codegpt.ui.checkbox.VirtualFileCheckboxTree;
import ee.carlrobert.codegpt.util.file.FileUtil;
import ee.carlrobert.embedding.ReferencedFile;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class IncludeFilesInContextAction extends AnAction {

  private static final Logger LOG = Logger.getInstance(IncludeFilesInContextAction.class);

  public IncludeFilesInContextAction() {
    super(CodeGPTBundle.get("action.includeFilesInContext.title"));
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    var project = e.getProject();
    if (project == null) {
      return;
    }

    var checkboxTree = getCheckboxTree(e.getDataContext());
    if (checkboxTree == null) {
      throw new RuntimeException("Could not obtain file tree");
    }

    var totalTokensLabel = new TotalTokensLabel(checkboxTree.getReferencedFiles());
    checkboxTree.addCheckboxTreeListener(new CheckboxTreeListener() {
      @Override
      public void nodeStateChanged(@NotNull CheckedTreeNode node) {
        totalTokensLabel.updateState(node);
      }
    });

    var includedFilesSettings = IncludedFilesSettingsState.getInstance();
    var promptTemplateTextArea = UIUtil.createTextArea(includedFilesSettings.getPromptTemplate());
    var repeatableContextTextArea =
        UIUtil.createTextArea(includedFilesSettings.getRepeatableContext());
    var show = showMultiFilePromptDialog(
        project,
        promptTemplateTextArea,
        repeatableContextTextArea,
        totalTokensLabel,
        checkboxTree);
    if (show == OK_EXIT_CODE) {
      project.putUserData(CodeGPTKeys.SELECTED_FILES, checkboxTree.getReferencedFiles());
      project.getMessageBus()
          .syncPublisher(IncludeFilesInContextNotifier.FILES_INCLUDED_IN_CONTEXT_TOPIC)
          .filesIncluded(checkboxTree.getReferencedFiles());
      includedFilesSettings.setPromptTemplate(promptTemplateTextArea.getText());
      includedFilesSettings.setRepeatableContext(repeatableContextTextArea.getText());
    }
  }

  private @Nullable FileCheckboxTree getCheckboxTree(DataContext dataContext) {
    var psiElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
    if (psiElement != null) {
      return new PsiElementCheckboxTree(psiElement);
    }

    var selectedVirtualFiles = VIRTUAL_FILE_ARRAY.getData(dataContext);
    if (selectedVirtualFiles != null) {
      return new VirtualFileCheckboxTree(selectedVirtualFiles);
    }

    return null;
  }

  private static class TotalTokensLabel extends JBLabel {

    private static final EncodingManager encodingManager = EncodingManager.getInstance();

    private int fileCount;
    private int totalTokens;

    TotalTokensLabel(List<ReferencedFile> referencedFiles) {
      fileCount = referencedFiles.size();
      totalTokens = calculateTotalTokens(referencedFiles);
      updateText();
    }

    void updateState(CheckedTreeNode checkedNode) {
      var fileContent = getNodeFileContent(checkedNode);
      if (fileContent != null) {
        int tokenCount = encodingManager.countTokens(fileContent);
        if (checkedNode.isChecked()) {
          totalTokens += tokenCount;
          fileCount++;
        } else {
          totalTokens -= tokenCount;
          fileCount--;
        }

        SwingUtilities.invokeLater(this::updateText);
      }
    }

    private @Nullable String getNodeFileContent(CheckedTreeNode checkedNode) {
      var userObject = checkedNode.getUserObject();
      if (userObject instanceof PsiElement) {
        var psiFile = ((PsiElement) userObject).getContainingFile();
        if (psiFile != null) {
          var virtualFile = psiFile.getVirtualFile();
          if (virtualFile != null) {
            return getVirtualFileContent(virtualFile);
          }
        }
      }
      if (userObject instanceof VirtualFile) {
        return getVirtualFileContent((VirtualFile) userObject);
      }
      return null;
    }

    private String getVirtualFileContent(VirtualFile virtualFile) {
      try {
        return new String(Files.readAllBytes(Paths.get(virtualFile.getPath())));
      } catch (IOException ex) {
        LOG.error(ex);
      }
      return null;
    }

    private void updateText() {
      setText(format(
          "<html><strong>%d</strong> %s totaling <strong>%s</strong> tokens</html>",
          fileCount,
          fileCount == 1 ? "file" : "files",
          FileUtil.convertLongValue(totalTokens)));
    }

    private int calculateTotalTokens(List<ReferencedFile> referencedFiles) {
      return referencedFiles.stream()
          .mapToInt(file -> encodingManager.countTokens(file.getFileContent()))
          .sum();
    }
  }

  private static int showMultiFilePromptDialog(
      Project project,
      JBTextArea promptTemplateTextArea,
      JBTextArea repeatableContextTextArea,
      JBLabel totalTokensLabel,
      JComponent component) {
    var dialogBuilder = new DialogBuilder(project);
    dialogBuilder.setTitle(CodeGPTBundle.get("action.includeFilesInContext.dialog.title"));
    dialogBuilder.setActionDescriptors();
    var fileTreeScrollPane = ScrollPaneFactory.createScrollPane(component);
    fileTreeScrollPane.setPreferredSize(
        new Dimension(480, component.getPreferredSize().height + 48));
    dialogBuilder.setNorthPanel(FormBuilder.createFormBuilder()
        .addLabeledComponent(
            CodeGPTBundle.get("shared.promptTemplate"),
            PanelFactory.panel(promptTemplateTextArea).withComment(
                    "<html><p>The template that will be used to create the final prompt. "
                        + "The <strong>{REPEATABLE_CONTEXT}</strong> placeholder must be included "
                        + "to correctly map the file contents.</p></html>")
                .createPanel(),
            true)
        .addVerticalGap(4)
        .addLabeledComponent(
            CodeGPTBundle.get("action.includeFilesInContext.dialog.repeatableContext.label"),
            PanelFactory.panel(repeatableContextTextArea).withComment(
                    "<html><p>The context that will be repeated for each included file. "
                        + "Acceptable placeholders include <strong>{FILE_PATH}</strong> and "
                        + "<strong>{FILE_CONTENT}</strong>.</p></html>")
                .createPanel(),
            true)
        .addComponent(JBUI.Panels.simplePanel()
            .addToRight(getRestoreButton(promptTemplateTextArea, repeatableContextTextArea)))
        .addVerticalGap(16)
        .addComponent(
            new JBLabel(CodeGPTBundle.get("action.includeFilesInContext.dialog.description"))
                .setCopyable(false)
                .setAllowAutoWrapping(true))
        .addVerticalGap(4)
        .addLabeledComponent(totalTokensLabel, fileTreeScrollPane, true)
        .addVerticalGap(16)
        .getPanel());
    dialogBuilder.addOkAction().setText(CodeGPTBundle.get("dialog.continue"));
    dialogBuilder.addCancelAction();
    return dialogBuilder.show();
  }

  private static JButton getRestoreButton(JBTextArea promptTemplateTextArea,
      JBTextArea repeatableContextTextArea) {
    var restoreButton = new JButton(
        CodeGPTBundle.get("action.includeFilesInContext.dialog.restoreToDefaults.label"));
    restoreButton.addActionListener(e -> {
      var includedFilesSettings = IncludedFilesSettingsState.getInstance();
      includedFilesSettings.setPromptTemplate(IncludedFilesSettingsState.DEFAULT_PROMPT_TEMPLATE);
      includedFilesSettings.setRepeatableContext(
          IncludedFilesSettingsState.DEFAULT_REPEATABLE_CONTEXT);
      promptTemplateTextArea.setText(IncludedFilesSettingsState.DEFAULT_PROMPT_TEMPLATE);
      repeatableContextTextArea.setText(IncludedFilesSettingsState.DEFAULT_REPEATABLE_CONTEXT);
    });
    return restoreButton;
  }
}
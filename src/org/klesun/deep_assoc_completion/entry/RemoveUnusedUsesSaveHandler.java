package org.klesun.deep_assoc_completion.entry;

import com.intellij.codeHighlighting.TextEditorHighlightingPass;
import com.intellij.codeInsight.daemon.impl.HighlightInfoProcessor;
import com.intellij.codeInsight.daemon.impl.TextEditorHighlightingPassRegistrarEx;
import com.intellij.codeInspection.GlobalInspectionTool;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.DocumentRunnable;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.lang.inspections.PhpUnusedAliasInspection;
import com.jetbrains.php.lang.psi.elements.PhpUse;
import com.jetbrains.php.lang.psi.elements.PhpUseList;
import com.jetbrains.php.lang.psi.elements.impl.PhpUseListImpl;
import org.jetbrains.annotations.NotNull;
import org.klesun.lang.Tls;

import java.util.List;

import static org.klesun.lang.Lang.*;

public class RemoveUnusedUsesSaveHandler implements FileDocumentManagerListener
{
    @Override
    public void beforeAllDocumentsSaving() {
        // do nothing ^_^
    }

    @Override
    public void beforeDocumentSaving(@NotNull Document document) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (DeepSettings.inst(project).removeUnusedImportsOnSaveEnabled) {
                PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
                PhpUnusedAliasInspection inspection = new PhpUnusedAliasInspection();
                InspectionManager manager = InspectionManager.getInstance(psiFile.getProject());
                List<ProblemDescriptor> checked = inspection.processFile(psiFile, manager);

                L<T2<Integer, Integer>> unuseRanges = L(checked)
                    .map(problem -> problem.getPsiElement())
                    .fop(toCast(PhpUse.class))
                    .fop(psi -> Tls.findParent(psi, PhpUseListImpl.class, a -> true))
                    .flt(lst -> L(lst.getChildren())
                        .fop(toCast(PhpUse.class))
                        .size() == 1) // just one name on this use line
                    .map(unuse -> unuse.getTextRange())
                    .map(r -> T2(r.getStartOffset(), r.getEndOffset() + 1))
                    .flt(p -> document.getText(new TextRange(p.a, p.b)).endsWith("\n"))
                    ;

                ApplicationManager.getApplication().runWriteAction(() ->
                    CommandProcessor.getInstance().runUndoTransparentAction(() ->
                        unuseRanges.srt(r -> -r.a) // start from the end to not mess up range indexes
                            .fch(range -> document.deleteString(range.a, range.b))));
            }
        }
    }

    @Override
    public void beforeFileContentReload(VirtualFile virtualFile, @NotNull Document document) {
        // do nothing ^_^
    }

    @Override
    public void fileWithNoDocumentChanged(@NotNull VirtualFile virtualFile) {
        // do nothing ^_^
    }

    @Override
    public void fileContentReloaded(@NotNull VirtualFile virtualFile, @NotNull Document document) {
        // do nothing ^_^
    }

    @Override
    public void fileContentLoaded(@NotNull VirtualFile virtualFile, @NotNull Document document) {
        // do nothing ^_^
    }

    @Override
    public void unsavedDocumentsDropped() {
        // do nothing ^_^
    }
}
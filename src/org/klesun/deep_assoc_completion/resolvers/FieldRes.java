package org.klesun.deep_assoc_completion.resolvers;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.impl.*;
import org.klesun.deep_assoc_completion.*;
import org.klesun.deep_assoc_completion.helpers.FuncCtx;
import org.klesun.deep_assoc_completion.helpers.MultiType;
import org.klesun.deep_assoc_completion.resolvers.var_res.AssRes;
import org.klesun.lang.Lang;
import org.klesun.lang.Tls;

import java.util.List;

public class FieldRes extends Lang
{
    final private FuncCtx ctx;

    public FieldRes(FuncCtx ctx)
    {
        this.ctx = ctx;
    }

    private static L<FieldReferenceImpl> findReferences(PsiFile file, String name)
    {
        // ReferenceSearch seems to cause freezes
//        SearchScope scope = GlobalSearchScope.fileScope(
//            fieldRef.getProject(),
//            decl.getContainingFile().getVirtualFile()
//        );
//        return ReferencesSearch.search(decl, scope, false).findAll();

        return L(PsiTreeUtil.findChildrenOfType(file, FieldReferenceImpl.class))
            .flt(ref -> name.equals(ref.getName()));
    }

    private boolean isCircularExpr(FieldReferenceImpl fieldRef)
    {
        L<PhpExpression> psiTrace = ctx.getSearch().psiTrace;
        // -1, because last always will be this PSI, not a circular reference
        for (int i = 0; i < psiTrace.size() - 1; ++i) {
            if (psiTrace.get(i).isEquivalentTo(fieldRef)) {
                return true;
            }
        }
        return false;
    }

    public MultiType resolve(FieldReferenceImpl fieldRef)
    {
        if (isCircularExpr(fieldRef)) {
            return MultiType.CIRCULAR_REFERENCE;
        }

        L<DeepType> result = list();
        opt(fieldRef.resolve())
            .thn(resolved -> {
                FuncCtx implCtx = new FuncCtx(ctx.getSearch());
                Tls.cast(FieldImpl.class, resolved)
                    .map(fld -> fld.getDefaultValue())
                    .fop(toCast(PhpExpression.class))
                    .map(def -> implCtx.findExprType(def).types)
                    .thn(result::addAll);

                L<Assign> asses = opt(resolved.getContainingFile())
                    .map(file -> findReferences(file, fieldRef.getName()))
                    .def(L())
                    .fop(psi -> (new AssRes(implCtx)).collectAssignment(psi, false));

                List<DeepType> types = AssRes.assignmentsToTypes(asses);
                result.addAll(types);
            });

        return new MultiType(result);
    }

}

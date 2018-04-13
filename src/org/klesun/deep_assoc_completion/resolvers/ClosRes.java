package org.klesun.deep_assoc_completion.resolvers;

import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.elements.impl.*;
import org.klesun.deep_assoc_completion.DeepType;
import org.klesun.deep_assoc_completion.helpers.FuncCtx;
import org.klesun.lang.Lang;
import org.klesun.lang.Tls;

public class ClosRes extends Lang
{
    final private FuncCtx ctx;

    public ClosRes(FuncCtx ctx)
    {
        this.ctx = ctx;
    }

    public static L<PhpReturnImpl> findFunctionReturns(PsiElement funcBody)
    {
        L<PhpReturnImpl> result = list();
        for (var child: funcBody.getChildren()) {
            // anonymous functions
            if (child instanceof Function) continue;

            Tls.cast(PhpReturnImpl.class, child)
                .thn(result::add);

            findFunctionReturns(child).forEach(result::add);
        }
        return result;
    }

    public DeepType resolve(FunctionImpl func)
    {
        var result = new DeepType(func, func.getLocalType(true));
        findFunctionReturns(func)
            .map(ret -> ret.getArgument())
            .fop(toCast(PhpExpression.class))
            .fch(retVal -> {
                F<FuncCtx, L<DeepType>> rtGetter =
                    (funcCtx) -> funcCtx.findExprType(retVal).types;
                result.returnTypeGetters.add(rtGetter);
            });
        return result;
    }

}

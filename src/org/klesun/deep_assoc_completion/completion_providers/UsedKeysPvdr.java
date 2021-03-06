package org.klesun.deep_assoc_completion.completion_providers;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import com.jetbrains.php.lang.psi.elements.ArrayCreationExpression;
import com.jetbrains.php.lang.psi.elements.impl.ArrayHashElementImpl;
import com.jetbrains.php.lang.psi.elements.impl.StringLiteralExpressionImpl;
import org.jetbrains.annotations.NotNull;
import org.klesun.deep_assoc_completion.contexts.ExprCtx;
import org.klesun.deep_assoc_completion.contexts.FuncCtx;
import org.klesun.deep_assoc_completion.contexts.IExprCtx;
import org.klesun.deep_assoc_completion.contexts.SearchCtx;
import org.klesun.deep_assoc_completion.structures.DeepType;
import org.klesun.deep_assoc_completion.helpers.*;
import org.klesun.deep_assoc_completion.resolvers.UsageResolver;
import org.klesun.lang.It;
import org.klesun.lang.Opt;
import org.klesun.lang.Tls;

import java.util.HashSet;
import java.util.Set;

import static org.klesun.lang.Lang.*;

/**
 * provides completion of keys in `$someVar = ['' => 123]`
 * by looking at what keys are used in the `$someVar`
 * made first of all for cases when function takes an associative array and it
 * is a pain to collect key names one-by-one from the function implementation
 */
public class UsedKeysPvdr extends CompletionProvider<CompletionParameters>
{
    private static LookupElementBuilder makeLookupBase(String name, String briefValueF, String type)
    {
        return  LookupElementBuilder.create(name)
            .bold()
            .withIcon(AssocKeyPvdr.getIcon())
            .withTailText(briefValueF, true)
            .withTypeText(type);
    }

    private static It<LookupElement> makeLookup(DeepType.Key keyEntry, Set<String> alreadyDeclared)
    {
        Opt<String> briefVal = keyEntry.getBriefVal();
        String type = keyEntry.getTypes().fst()
            .map(t -> t.briefType.toString())
            .flt(t -> t.length() > 0).def("mixed");
        String comment = Tls.implode(" ", keyEntry.comments);
        String briefValue = briefVal.map(v -> " = " + v).def("");
        if (!comment.trim().equals("")) {
            briefValue = Tls.substr(briefValue, 0, 12) + " " + Tls.substr(comment, 0, 60);
        }
        final String briefValueF = briefValue;
        return It(keyEntry.keyType.getTypes())
            .flt(t -> !t.isNumber())
            .flt(t -> !alreadyDeclared.contains(t.stringValue))
            .unq(t -> t.stringValue)
            .fap((t, i) -> It.cnc(
                opt(t.stringValue)
                    .flt(strVal -> !t.cstName.has() || !t.isNumber)
                    .map(strVal -> makeLookupBase(strVal, briefValueF, type))
                    .map((lookup) -> PrioritizedLookupElement.withPriority(lookup, 2000 - i)),
                t.cstName
                    .map(cstName -> makeLookupBase(cstName, briefValueF, type)
                        .withInsertHandler(GuiUtil.toAlwaysRemoveQuotes()))
                    .map((lookup) -> PrioritizedLookupElement.withPriority(lookup, 2000 - i + 50))
            ));
    }

    private static Opt<ArrayCreationExpression> assertArrCtorKey(PsiElement caretPsi)
    {
        return opt(caretPsi.getParent())
            .fop(literal -> opt(literal.getParent())
                .map(arrKey -> arrKey.getParent())
                .fop(parent -> Opt.fst(
                    // assoc key
                    () -> Tls.cast(ArrayHashElementImpl.class, parent)
                        .flt(hash -> literal.isEquivalentTo(hash.getKey()))
                        .map(hash -> hash.getParent())
                        .fop(toCast(ArrayCreationExpression.class)),
                    // indexed value (that may become associative key as user continues typing)
                    () -> Tls.cast(ArrayCreationExpression.class, parent)
                )));
    }

    private static Mt resolve(ArrayCreationExpression lit, boolean isAutoPopup)
    {
        SearchCtx search = new SearchCtx(lit.getProject())
            .setDepth(AssocKeyPvdr.getMaxDepth(isAutoPopup, lit.getProject()));
        FuncCtx funcCtx = new FuncCtx(search);
        IExprCtx exprCtx = new ExprCtx(funcCtx, lit, 0);

        return new UsageResolver(exprCtx).resolve(lit);
    }

    @Override
    protected void addCompletions(@NotNull CompletionParameters parameters, ProcessingContext processingContext, @NotNull CompletionResultSet result)
    {
        long startTime = System.nanoTime();
        Opt<ArrayCreationExpression> arrCtorOpt = assertArrCtorKey(parameters.getPosition());

        Set<String> alreadyDeclared = arrCtorOpt
            .fap(arrCtor -> arrCtor.getHashElements())
            .fop(el -> opt(el.getKey()))
            .fop(toCast(StringLiteralExpressionImpl.class))
            .map(lit -> lit.getContents())
            .wap(tit -> new HashSet<>(tit.arr()));

        It<DeepType.Key> usedKeys = arrCtorOpt
            .fap(arrCtor -> resolve(arrCtor, parameters.isAutoPopup())
                .types.fap(t -> t.keys));

        usedKeys
            .fap(k -> makeLookup(k, alreadyDeclared))
            // to preserve order
            .unq(l -> l.getLookupString())
            .map((lookup, i) -> PrioritizedLookupElement.withPriority(lookup, 3000 - i))
            .fch(result::addElement);

        long elapsed = System.nanoTime() - startTime;
        result.addLookupAdvertisement("Resolved used keys in " + (elapsed / 1000000000.0) + " seconds");
    }

    // ================================
    //  GotoDeclarationHandler part follows
    // ================================

    public static It<PsiElement> resolveDeclPsis(@NotNull PsiElement psi, int mouseOffset)
    {
        return opt(psi.getParent())
            .fop(toCast(StringLiteralExpressionImpl.class))
            .fap(lit -> opt(lit.getFirstChild())
                .fop(c -> assertArrCtorKey(c))
                .fap(arrCtor -> resolve(arrCtor, false)
                    .types.fap(t -> t.keys).fap(k -> k.keyType.getTypes())
                    .flt(k -> lit.getContents().equals(k.stringValue))
                    .map(k -> k.definition)));
    }
}

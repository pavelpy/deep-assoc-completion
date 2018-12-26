package org.klesun.deep_assoc_completion.contexts;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.impl.FieldReferenceImpl;
import org.klesun.deep_assoc_completion.entry.DeepSettings;
import org.klesun.deep_assoc_completion.resolvers.MainRes;
import org.klesun.deep_assoc_completion.structures.DeepType;
import org.klesun.lang.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class SearchCtx extends Lang
{
    // parametrized fields
    private long startTime = System.nanoTime();
    private long lastReportTime = System.nanoTime();
    public int maxDepth = 20;
    final public static boolean DEBUG_DEFAULT = false;
    public boolean debug = DEBUG_DEFAULT;
    private Opt<Double> timeout = opt(null);
    final public Opt<Project> project;
    // for performance measurement
    private int expressionsResolved = 0;
    final private Map<IFuncCtx, Map<PhpExpression, Iterable<DeepType>>> ctxToExprToResult = new HashMap<>();
    public Opt<Integer> overrideMaxExpr = non();
    final public Map<PsiFile, Collection<FieldReferenceImpl>> fileToFieldRefs = new HashMap<>();
    public boolean isMain = false;

    public SearchCtx(Project project)
    {
        this.project = opt(project);
    }

    public SearchCtx(CompletionParameters parameters)
    {
        this(parameters.getEditor().getProject());
    }

    public SearchCtx setDepth(int depth)
    {
        this.maxDepth = depth;
        return this;
    }

    public SearchCtx setTimeout(double timeout)
    {
        this.timeout = opt(timeout);
        return this;
    }

    public SearchCtx setDebug(boolean debug)
    {
        this.debug = debug;
        return this;
    }

    public Integer getMaxExpressions()
    {
        // max expressions per single search - guard
        // against memory overflow in circular references
        return Opt.fst(
            () -> overrideMaxExpr,
            () -> project.map(project -> {
                DeepSettings settings = DeepSettings.inst(project);
                return settings.totalExpressionLimit;
            })
        ).def(10000);
    }

    private static <T> boolean endsWith(L<T> superList, L<T> subList)
    {
        for (int i = 0; i < subList.size(); ++i) {
            if (i >= superList.size() || !superList.get(-i - 1).equals(subList.get(-i - 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isRecursion(L<PsiElement> psiTrace)
    {
        // imagine sequence: a b c d e f g e f g
        //                           ^_____^_____
        // I'm not sure this assumption is right, but I'll try to
        // treat any case where end repeats pre-end as recursion
        for (int i = 0; i < psiTrace.size() / 2; ++i) {
            L<PsiElement> subList = psiTrace.sub(psiTrace.size() - i * 2 - 2, i + 1);
            if (endsWith(psiTrace, subList)) {
                return true;
            }
        }
        return false;
    }

    private Opt<Iterable<DeepType>> takeFromCache(IFuncCtx ctx, PhpExpression expr)
    {
        if (!ctxToExprToResult.containsKey(ctx)) {
            return opt(null);
        }
        if (!ctxToExprToResult.get(ctx).containsKey(expr)) {
            return opt(null);
        }
        Iterable<DeepType> mt = ctxToExprToResult.get(ctx).get(expr);
        return opt(mt);
    }

    public static String formatPsi(PsiElement expr)
    {
        String fileText = expr.getContainingFile().getText();
        int phpLineNum = Tls.substr(fileText, 0, expr.getTextOffset()).split("\n").length;
        return Tls.singleLine(expr.getText(), 120) + " - " + expr.getContainingFile().getName() + ":" + phpLineNum;
    }

    private void putToCache(IFuncCtx ctx, PhpExpression expr, Iterable<DeepType> result)
    {
        if (!ctxToExprToResult.containsKey(ctx)) {
            ctxToExprToResult.put(ctx, new HashMap<>());
        }
        if (ctxToExprToResult.get(ctx).containsKey(expr)) {
            ctxToExprToResult.get(ctx).remove(expr);
        }
        ctxToExprToResult.get(ctx).put(expr, result);
    }

    private void printCache()
    {
        Map<PhpExpression, L<IFuncCtx>> psiToCtxs = new LinkedHashMap<>();
        ctxToExprToResult.forEach((ctx, psiToMt) -> {
            psiToMt.forEach((psi, mt) -> {
                if (!psiToCtxs.containsKey(psi)) {
                    psiToCtxs.put(psi, list());
                }
                psiToCtxs.get(psi).add(ctx);
            });
        });
        L(psiToCtxs.keySet())
            .srt(psi -> -psiToCtxs.get(psi).size())
            .fch(psi -> {
                System.out.println("  ** PSI: " + formatPsi(psi));
                psiToCtxs.get(psi).fch(ctx -> {
                    System.out.println("   +-- Ctx: " + ctx.hashCode() + " " + ctx);
                });
            });
    }

    private boolean shouldCache(ExprCtx exprCtx)
    {
        return !exprCtx.doNotCache;
    }

    private static L<PsiElement> getExprChain(ExprCtx ctx)
    {
        L<PsiElement> fromEnd = list();
        while (ctx != null) {
            if (!fromEnd.lst().equals(som(ctx.expr))) {
                fromEnd.add(ctx.expr);
            }
            ctx = ctx.parent.def(null);
        }
        return fromEnd.rvr();
    }

    public Iterable<DeepType> findExprType(PhpExpression expr, ExprCtx exprCtx)
    {
        long time = System.nanoTime();
        double seconds = (time - startTime) / 1000000000.0;
        if (!debug && (time - lastReportTime) / 1000000000.0 > 1.0) {
            lastReportTime = System.nanoTime();
            //System.out.println("deep-assoc-completion warning at " + time + ": type resolution takes " + seconds + " seconds " + expr.getText() + " " + expr.getClass());
        }

        if (exprCtx.depth > maxDepth) {
            return It.non();
        }
        L<PsiElement> chain = getExprChain(exprCtx);
        if (++expressionsResolved > getMaxExpressions()) {
            return It.non();
        } else if (timeout.flt(tout -> seconds > tout).has()) {
            return It.non();
        }

        Opt<Iterable<DeepType>> result = takeFromCache(exprCtx.func(), expr);
        if (result.has()) {
            if (debug) {
                //System.out.println(indent + "<< TAKING RESULT FROM CACHE");
            }
        } else if (isRecursion(chain)) {
            return It.non();
        } else {
            if (shouldCache(exprCtx)) {
                putToCache(exprCtx.func(), expr, list());
            }

            It<DeepType> tit = new MainRes(exprCtx).resolve(expr)
                //.lmt(1000) // .lmt() is just a safety measure, it should not be needed if everything works properly
                .unq() // .unq() before caching is important since types taken from cache would grow in count exponentially otherwise
                ;
            Iterable<DeepType> mit = new MemIt<>(tit.iterator());
            result = som(mit);
            if (shouldCache(exprCtx)) {
                result.thn(mt -> putToCache(exprCtx.func(), expr, mit));
            }
        }

        /** @debug */
        //System.out.println("zalupa expr " + expr.getText() + " self is " + result.fap(a -> a).rdc((sum,el) -> sum + "|" +
        //    el.clsRefType.map(t -> t.toString()).def("(no cls)"), "") + " trace: " + funcCtx.func());

        return It(result.def(It.non()))
            .thn(cnt -> exprCtx.typeCnt = som(cnt))
            ;
    }

    public int getExpressionsResolved()
    {
        return this.expressionsResolved;
    }
}
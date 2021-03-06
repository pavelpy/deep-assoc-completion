package org.klesun.deep_assoc_completion.resolvers;

import com.intellij.database.model.DasObject;
import com.intellij.database.model.ObjectKind;
import com.intellij.database.psi.DbPsiFacade;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.jetbrains.php.PhpIndex;
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocMethod;
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocMethodTag;
import com.jetbrains.php.lang.documentation.phpdoc.psi.tags.PhpDocReturnTag;
import com.jetbrains.php.lang.psi.elements.Method;
import com.jetbrains.php.lang.psi.elements.MethodReference;
import com.jetbrains.php.lang.psi.elements.PhpExpression;
import com.jetbrains.php.lang.psi.elements.impl.MethodReferenceImpl;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import org.klesun.deep_assoc_completion.contexts.IExprCtx;
import org.klesun.deep_assoc_completion.entry.DeepSettings;
import org.klesun.deep_assoc_completion.helpers.Mt;
import org.klesun.deep_assoc_completion.resolvers.mem_res.MemRes;
import org.klesun.deep_assoc_completion.resolvers.var_res.DocParamRes;
import org.klesun.deep_assoc_completion.structures.DeepType;
import org.klesun.lang.*;
import org.klesun.lang.iterators.RegexIterator;

import java.util.Iterator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MethCallRes extends Lang
{
    final private IExprCtx ctx;

    public MethCallRes(IExprCtx ctx)
    {
        this.ctx = ctx;
    }

    public static boolean nameIs(MethodReferenceImpl call, String cls, String mth)
    {
        String callCls = opt(call.getClassReference())
            .map(clsPsi -> clsPsi.getName()).def("");
        String callMet = opt(call.getName()).def("");

        return callCls.equals(cls) && callMet.equals(mth);
    }

    public static It<Method> findOverridingMethods(Method meth)
    {
        return opt(PhpIndex.getInstance(meth.getProject()))
            .fop(idx -> opt(meth.getContainingClass())
                .map(cls -> idx.getAllSubclasses(cls.getFQN())))
            .fap(clses -> clses)
            .fop(cls -> opt(cls.findMethodByName(meth.getName())));
    }

    private static It<DasObject> getDasChildren(DasObject parent, ObjectKind kind)
    {
        // return getDasChildren(ObjectKind.COLUMN);
        return It(parent.getDbChildren(DasObject.class, kind));
    }

    private static It<String> getTableColumns(String table, Project project)
    {
        return It(DbPsiFacade.getInstance(project).getDataSources())
            .fap(src -> src.getModel().getModelRoots())
            .fap(root -> getDasChildren(root, ObjectKind.TABLE))
            .flt(t -> t.getName().equals(table))
            .fap(tab -> getDasChildren(tab, ObjectKind.COLUMN))
            .map(col -> col.getName());
    }

    private DeepType parseSqlSelect(DeepType strType, Project project)
    {
        DeepType parsedType = new DeepType(strType.definition, PhpType.ARRAY);
        String sql = opt(strType.stringValue).def("");
        int regexFlags = Pattern.DOTALL | Pattern.CASE_INSENSITIVE;
        Opt<L<String>> matched = Opt.fst(
            () -> Tls.regex("\\s*SELECT\\s+(\\S.*?)\\s+FROM\\s+([A-Za-z_][A-Za-z0-9_]*)?.*?", sql, regexFlags),
            () -> Tls.regex("\\s*SELECT\\s+(\\S.*)", sql, regexFlags) // partial SQL without FROM
        );
        matched.fap(matches -> {
            It<String> fields = It(matches.gat(0).def("").split(",", -1));
            String table = matches.gat(1).def("");
            return fields.map(str -> str.trim())
                .fap(f -> {
                    if (f.equals("*")) {
                        return getTableColumns(table, project);
                    } else {
                        return Tls.regex("(\\S+.*?\\.)?(\\S+\\s+AS\\s+)?(\\S+)", f, regexFlags)
                            .fop(m -> m.gat(2))
                            .fap(a -> list(a));
                    }
                });
        }).fch(name -> parsedType.addKey(name, ctx.getRealPsi(strType.definition))
            .addType(() -> new Mt(list(new DeepType(strType.definition, PhpType.STRING))), PhpType.STRING));
        return parsedType;
    }

    private static It<String> getBindVars(DeepType sqlStrT)
    {
        String pattern = ":([A-Za-z_][A-Za-z0-9_]*)";
        String text = opt(sqlStrT.stringValue).def("");
        return It(() -> new RegexIterator(pattern, text))
            .map(groups -> groups.get(1));
    }

    public It<DeepType> getModelRowType(MethodReference methCall, Method meth)
    {
        // treating any class named "Model" as a base ORM class for Doctrine/Eloquent/CustomStuff completion
        String clsNme = opt(meth.getContainingClass()).map(cls -> cls.getName()).def("");
        return Tls.ifi(
            clsNme.equals("Model") && meth.getName().equals("get"),
            () -> new MiscRes(ctx).resolveClassReferenceFromMember(methCall)
        ).fap(ideaType -> {
            Mutable<Boolean> isAssoc = new Mutable<>(false);
            It<T2<String, PsiElement>> fieldNames =
                ArrCtorRes.resolveIdeaTypeCls(ideaType, methCall.getProject())
                .fap(callCls -> It.cnc(som(callCls), It(callCls.getSupers())).end(sup -> sup.getName().equals("Model")).unq())
                .fap(callCls -> callCls.getFields())
                // could also add here "getFields" functions, not just "fields" property
                .flt(f -> f.getName().equals("fields"))
                .fap(f -> opt(f.getDefaultValue()))
                .fop(toCast(PhpExpression.class))
                .fap(val -> ctx.limitResolveDepth(15, val))
                .fap(valt -> valt.keys)
                .btw(k -> isAssoc.set(It(k.keyType.getTypes())
                    .fst().any(kt -> !kt.isNumber())))
                .fap(k -> {
                    It<DeepType> keyTypes = isAssoc.get()
                        ? It(k.keyType.getTypes())
                        : k.getTypes();
                    return keyTypes.fap(t -> opt(t.stringValue).map(str -> T2(str, t.definition)));
                })
                .unq(t2 -> t2.a);
            return It.cnc(
                som(new DeepType(methCall, ideaType)),
                fieldNames.map(nme -> UsageResolver.makeAssoc(nme.b, som(nme)))
            );
        });
    }

    private It<DeepType> findBuiltInRetType(Method meth, IExprCtx argCtx, MethodReference methCall)
    {
        It<DeepType> types = It(list());
        String clsNme = opt(meth.getContainingClass()).map(cls -> cls.getName()).def("");
        if (clsNme.equals("PDO") && meth.getName().equals("query") ||
            clsNme.equals("PDO") && meth.getName().equals("prepare")
        ) {
            DeepType type = new DeepType(methCall);
            argCtx.func().getArg(0)
                .fap(mt -> mt.types)
                .fch(strType -> {
                    DeepType fetchType = parseSqlSelect(strType, meth.getProject());
                    type.pdoFetchTypes.add(fetchType);
                    getBindVars(strType).fch(type.pdoBindVars::add);
                });
            types = It(list(type));
        } else if (clsNme.equals("PDOStatement") && meth.getName().equals("fetch")
                || clsNme.equals("mysqli_result") && meth.getName().equals("fetch_assoc")
        ) {
            It<DeepType> pdoTypes = opt(methCall.getClassReference())
                .fop(toCast(PhpExpression.class))
                .fap(obj -> ctx.findExprType(obj))
                .fap(t -> t.pdoFetchTypes);
            types = It(pdoTypes);
        } else if (clsNme.equals("mysqli") && meth.getName().equals("query")) {
            MemIt<DeepType> rowTypes = argCtx.func().getArg(0).fap(mt -> mt.types)
                .flt(strType -> !opt(strType.stringValue).def("").equals(""))
                .map(strType -> parseSqlSelect(strType, meth.getProject())).mem();
            types = It.cnc(
                som(new DeepType(methCall).btw(t -> {
                    // it's not a PDO, but nah
                    rowTypes.itr().fch((rowt, i) -> t.pdoFetchTypes.add(rowt));
                })),
                // since PHP 5.4 mysqli_result can also be iterated
                som(Mt.getInArraySt(It(rowTypes), methCall))
            );
        }
        It<DeepType> modelRowTypes = getModelRowType(methCall, meth);
        It<DeepType> modelRowArrTypes = !modelRowTypes.has() ? It.non() :
            It(som(Mt.getInArraySt(modelRowTypes, methCall)));
        types = It.cnc(types, modelRowArrTypes);
        return types;
    }

    private static It<DeepType> parseReturnDoc(PhpDocReturnTag returnDoc, IExprCtx funcCtx)
    {
        IExprCtx docCtx = funcCtx.subCtxEmpty(returnDoc);
        return Tls.regex("^\\s*(like|=|)\\s*((?:\\[|[a-zA-Z]+[\\(:]|new\\s+).*)$", returnDoc.getTagValue())
            .fop(match -> match.gat(1))
            .fap(expr -> DocParamRes.parseExpression(expr, returnDoc.getProject(), docCtx));
    }

    public static F<IExprCtx, It<DeepType>> findMethRetType(Method meth)
    {
        return (IExprCtx funcCtx) -> {
            L<Method> impls = list(meth);
            if (meth.isAbstract()) {
                impls = It.cnc(list(meth), findOverridingMethods(meth)).arr();
                // ignore $this and args in implementations
                // since there may be dozens of them (Laravel)
                if (!DeepSettings.inst(meth.getProject()).passArgsToImplementations) {
                    funcCtx = funcCtx.subCtxEmpty();
                }
            }
            IExprCtx finalCtx = funcCtx;
            return impls.fap(m -> It.cnc(
                opt(meth.getDocComment()).map(doc -> doc.getReturnTag())
                    .fap(tag -> parseReturnDoc(tag, finalCtx)),
                Tls.cast(PhpDocMethod.class, meth)
                    .fap(doc -> opt(doc.getParent())
                        .cst(PhpDocMethodTag.class)
                        .fap(tag -> {
                            // text after signature _on same line_
                            String descrPart = It(tag.getChildren())
                                .flt(psi -> (psi + "").equals("DOC_METHOD_DESCR"))
                                .map(psi -> psi.getText()).str("");
                            // text on following lines
                            String valuePart = tag.getTagValue();
                            String fullDescr = descrPart + "\n" + valuePart;
                            return new DocParamRes(finalCtx)
                                .parseEqExpression(fullDescr, doc);
                        })),
                opt(m.getReturnType()).fap(rt -> list(new DeepType(rt, rt.getType()))),
                ClosRes.getReturnedValue(m, finalCtx)
            ));
        };
    }

    public static It<Method> resolveMethodsNoNs(MethodReference call, IExprCtx ctx)
    {
        String cls = opt(call.getClassReference()).map(c -> c.getText()).def("");
        String mth = opt(call.getName()).def("");
        return MethCallRes.resolveMethodsNoNs(cls, mth, call.getProject());
    }

    private static It<Method> resolveMethodsNoNs(String partialFqn, String func, Project proj)
    {
        return MemRes.findClsByFqnPart(partialFqn, proj)
            .fap(cls -> cls.getMethods())
            .flt(m -> Objects.equals(m.getName(), func));
    }

    private It<Method> findReferenced(MethodReferenceImpl fieldRef)
    {
        It<Method> mit = new MemRes(ctx).resolveCls(fieldRef)
            .fap(cls -> cls.getMethods())
            .flt(f -> f.getName().equals(fieldRef.getName()));
        return mit;
    }

    private It<Method> resolveMethodFromCall(MethodReferenceImpl call)
    {
        return It.frs(
            () -> findReferenced(call),
            () -> It(call.multiResolve(false))
                .fap(v -> opt(v.getElement()))
                .cst(Method.class),
            () -> resolveMethodsNoNs(call, ctx)
        );
    }

    public It<DeepType> resolveCall(MethodReferenceImpl funcCall)
    {
        IExprCtx funcCtx = ctx.subCtxDirect(funcCall);
        return resolveMethodFromCall(funcCall)
            .fap(func -> It.cnc(
                findMethRetType(func).apply(funcCtx),
                findBuiltInRetType(func, funcCtx, funcCall)
            ));
    }
}

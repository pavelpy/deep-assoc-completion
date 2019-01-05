package org.klesun.deep_assoc_completion.resolvers;

import com.intellij.psi.PsiElement;
import com.jetbrains.php.lang.psi.elements.*;
import com.jetbrains.php.lang.psi.elements.impl.*;
import com.jetbrains.php.lang.psi.resolve.types.PhpType;
import org.klesun.deep_assoc_completion.contexts.ExprCtx;
import org.klesun.deep_assoc_completion.contexts.FuncCtx;
import org.klesun.deep_assoc_completion.contexts.IExprCtx;
import org.klesun.deep_assoc_completion.contexts.SearchCtx;
import org.klesun.deep_assoc_completion.helpers.Mt;
import org.klesun.deep_assoc_completion.resolvers.var_res.DocParamRes;
import org.klesun.deep_assoc_completion.structures.DeepType;
import org.klesun.deep_assoc_completion.structures.KeyType;
import org.klesun.lang.*;

import java.util.HashSet;
import java.util.Set;

import static org.klesun.deep_assoc_completion.structures.Mkt.assoc;
import static org.klesun.deep_assoc_completion.structures.Mkt.*;

/**
 * takes associative array that caret points at and returns
 * all key names that will be accessed on this array later
 */
public class KeyUsageResolver extends Lang
{
    final private IExprCtx fakeCtx;
    final private int depthLeft;

    public KeyUsageResolver(IExprCtx fakeCtx, int depthLeft)
    {
        this.fakeCtx = fakeCtx;
        this.depthLeft = depthLeft;
    }

    private It<DeepType> resolveReplaceKeys(ParameterList argList, int order)
    {
        return It(argList.getParameters())
            .flt((psi, i) -> i < order)
            .fop(toCast(PhpExpression.class))
            .fap(exp -> fakeCtx.findExprType(exp));
    }

    private static It<ArrayIndex> findUsedIndexes(Function meth, String varName)
    {
        return Tls.findChildren(
            meth.getLastChild(),
            ArrayAccessExpressionImpl.class,
            subPsi -> !(subPsi instanceof FunctionImpl)
        )
            .fop(acc -> opt(acc.getValue())
                .fop(toCast(VariableImpl.class))
                .flt(varUsage -> varName.equals(varUsage.getName()))
                .map(varUsage -> acc.getIndex()));
    }

    public It<DeepType> findArgTypeFromUsage(Function meth, int argOrder, IExprCtx nextCtx)
    {
        return L(meth.getParameters()).gat(argOrder)
            .fop(toCast(ParameterImpl.class))
            .fap(arg -> It.cnc(
                findUsedIndexes(meth, arg.getName())
                    .map(idx -> idx.getValue())
                    .cst(PhpExpression.class)
                    .fap(lit -> nextCtx.limitResolveDepth(15, lit)
                        .unq(t -> t.stringValue)
                        .fap(t -> opt(t.stringValue)
                            .map(name -> {
                                DeepType assoct = new DeepType(arg, PhpType.ARRAY);
                                S<Mt> getType = () -> Tls.findParent(lit, ArrayAccessExpression.class, a -> true)
                                    .fap(acc -> new KeyUsageResolver(nextCtx, depthLeft - 1).findExprTypeFromUsage(acc)).wap(Mt::new);
                                assoct.addKey(name, t.definition)
                                    .addType(getType, PhpType.UNSET);
                                return assoct;
                            }))
                    ),
                opt(arg.getDocComment())
                    .map(doc -> doc.getParamTagByName(arg.getName()))
                    .fap(doc -> new DocParamRes(nextCtx).resolve(doc)),
                opt(arg.getDefaultValue())
                    .cst(PhpExpression.class)
                    .fap(xpr -> nextCtx.subCtxEmpty().findExprType(xpr)),
                new KeyUsageResolver(nextCtx, depthLeft - 1).findVarTypeFromUsage(arg)
            ));
    }

    // not sure this method belongs here... and name should be changed
    public It<DeepType> resolveArgCallArrKeys(Function meth, int funcVarArgOrder, int caretArgOrder)
    {
        return L(meth.getParameters()).gat(funcVarArgOrder)
            .fop(toCast(ParameterImpl.class))
            .fap(arg -> findVarReferences(arg))
            .fop(var -> opt(var.getParent()))
            // TODO: include not just dirrect calls,
            // but also array_map and other built-ins
            .fop(toCast(FunctionReference.class))
            .fop(call -> L(call.getParameters()).gat(caretArgOrder))
            .fop(toCast(PhpExpression.class))
            .fap(exp -> fakeCtx.findExprType(exp));
    }

    private static Opt<Function> resolveFunc(ParameterList argList)
    {
        return opt(argList.getParent())
            .fop(par -> Opt.fst(
                () -> Tls.cast(FunctionReference.class, par)
                    .map(call -> call.resolve()),
                () -> Tls.cast(MethodReferenceImpl.class, par)
                    .map(call -> call.resolve()),
                () -> Tls.cast(NewExpressionImpl.class, par)
                    .map(newEx -> newEx.getClassReference())
                    .map(ref -> ref.resolve())
            )  .fop(toCast(Function.class)));
    }

    private static It<Variable> findVarReferences(PhpNamedElement caretVar)
    {
        return Tls.findParent(caretVar, Function.class, a -> true)
            .fap(meth -> Tls.findChildren(
                meth.getLastChild(),
                Variable.class,
                subPsi -> !(subPsi instanceof Function)
            ).flt(varUsage -> caretVar.getName().equals(varUsage.getName())));
    }

    public static DeepType makeAssoc(PsiElement psi, Iterable<T2<String, PsiElement>> keys)
    {
        DeepType assoct = new DeepType(psi, PhpType.ARRAY);
        for (T2<String, PsiElement> key: keys) {
            assoct.addKey(key.a, key.b);
        }
        return assoct;
    }

    // add completion from new SomeClass() that depends
    // on class itself, not on the constructor function
    private Mt findClsMagicCtorUsedKeys(NewExpressionImpl newEx, int order)
    {
        return opt(newEx.getClassReference())
            .map(ref -> ref.resolve())
            .fop(clsPsi -> Opt.fst(
                () -> Tls.cast(Method.class, clsPsi).map(m -> m.getContainingClass()), // class has own constructor
                () -> Tls.cast(PhpClass.class, clsPsi) // class does not have an own constructor
            ))
            // Laravel's Model takes array of initial column values in constructor
            .flt(cls -> order == 0)
            .fap(cls -> {
                L<PhpClass> supers = L(cls.getSupers());
                boolean isModel = supers.any(sup -> sup.getFQN()
                    .equals("\\Illuminate\\Database\\Eloquent\\Model"));
                if (!isModel) {
                    return list();
                } else {
                    Set<String> inherited = new HashSet<>(supers.fap(s -> It(s.getOwnFields()).map(f -> f.getName())).arr());
                    return makeAssoc(newEx, It(cls.getOwnFields())
                        .flt(fld -> !fld.getModifier().isPrivate())
                        .flt(fld -> !inherited.contains(fld.getName()))
                        .map(fld -> T2(fld.getName(), fld))).mt().types;
                }
            })
            .wap(Mt::new);
    }

    private static It<? extends Function> getImplementations(Function meth)
    {
        return It.cnc(
            Tls.cast(Method.class, meth)
                .fap(m -> MethCallRes.findOverridingMethods(m)).map(a -> a),
            list(meth)
        );
    }

    private It<DeepType> findKeysUsedInArrayMap(Function meth, ParameterList argList, int caretArgOrder)
    {
        return opt(meth.getName())
            .flt(n -> n.equals("array_map"))
            .map(n -> L(argList.getParameters()))
            .flt(args -> caretArgOrder == 1)
            .fop(args -> args.gat(0))
            .fop(func -> Tls.cast(PhpExpressionImpl.class, func)
                .map(expr -> expr.getFirstChild())
                .fop(toCast(Function.class))) // TODO: support in a var
            .fap(func -> {
                DeepType arrt = new DeepType(argList, PhpType.ARRAY);
                DeepType.Key k = arrt.addKey(KeyType.unknown(argList));
                L(argList.getParameters()).gat(caretArgOrder)
                    .cst(PhpExpression.class)
                    .thn(arrCtor -> k.addType(Tls.onDemand(() -> {
                        IExprCtx subCtx = fakeCtx.subCtxSingleArgArr(arrCtor);
                        return findArgTypeFromUsage(func, 0, subCtx).wap(Mt::new);
                    })));
                return list(arrt);
            });
    }

    private It<DeepType> findKeysUsedInPdoExec(Method meth, ParameterList argList, int caretArgOrder)
    {
        return som(meth)
            .flt(m -> "\\PDOStatement".equals(opt(m.getContainingClass()).map(cls -> cls.getFQN()).def("")))
            .flt(m -> "execute".equals(m.getName()))
            .flt(m -> caretArgOrder == 0)
            .fop(m -> opt(argList.getParent()))
            .fop(toCast(MethodReference.class))
            .fop(methRef -> opt(methRef.getClassReference()))
            .fap(clsRef -> fakeCtx.findExprType(clsRef))
            .map(pdostt -> makeAssoc(pdostt.definition, It(pdostt.pdoBindVars)
                .map(varName -> T2(varName, pdostt.definition))));
    }

    private It<DeepType> findKeysUsedInModelGet(Method func, ParameterList argList, int caretArgOrder)
    {
        return som(func)
            .flt(m -> caretArgOrder == 0)
            .fap(meth -> opt(argList.getParent())
                .cst(MethodReference.class)
                .fap(methCall -> (new MethCallRes(fakeCtx)).getModelRowType(methCall, meth)));
    }

    private DeepType stream_context_create(Function def)
    {
        return assoc(def, list(
            T2("http", assoc(def, list(
                T2("header", str(def, "Content-type: application/x-www-form-urlencoded\\r\\n").mt()),
                T2("method", new Mt(list("GET", "POST", "OPTIONS", "PUT", "HEAD", "DELETE", "CONNECT", "TRACE", "PATCH").map(m -> str(def, m)))),
                T2("content", str(def, "name=Vasya&age=26&price=400").mt()),
                T2("user_agent", str(def, "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/71.0.3578.80 Chrome/71.0.3578.80 Safari/537.36").mt()),
                T2("proxy", str(def, "tcp://proxy.example.com:5100").mt()),
                T2("request_fulluri", bool(def).mt()),
                T2("follow_location", inte(def).mt()),
                T2("max_redirects", inte(def).mt()),
                T2("protocol_version", floate(def).mt()),
                T2("timeout", floate(def).mt()),
                T2("ignore_errors", bool(def).mt())
            )).mt()),
            T2("socket", assoc(def, list(
                T2("bindto", str(def, "128.211.185.166:3345").mt()),
                T2("backlog", inte(def).mt()),
                T2("ipv6_v6only", bool(def).mt()),
                T2("so_reuseport", inte(def).mt()),
                T2("so_broadcast", inte(def).mt()),
                T2("tcp_nodelay", bool(def).mt())
            )).mt()),
            T2("ftp", assoc(def, list(
                T2("overwrite", bool(def).mt()),
                T2("resume_pos", inte(def).mt()),
                T2("proxy", str(def, "tcp://squid.example.com:8000").mt())
            )).mt()),
            T2("ssl", assoc(def, list(
                T2("peer_name", bool(def).mt()),
                T2("verify_peer", bool(def).mt()),
                T2("verify_peer_name", bool(def).mt()),
                T2("allow_self_signed", bool(def).mt()),
                T2("cafile", str(def, "/path/to/cert/auth/file").mt()),
                T2("capath", str(def, "/path/to/cert/auth/dir").mt()),
                T2("local_cert", str(def, "/path/to/cert.pem").mt()),
                T2("local_pk", str(def, "/path/to/private/key.pem").mt()),
                T2("passphrase", str(def, "qwerty123").mt()),
                T2("verify_depth", inte(def).mt()),
                T2("ciphers", str(def, "ALL:!COMPLEMENTOFDEFAULT:!eNULL").mt()),
                T2("capture_peer_cert", bool(def).mt()),
                T2("capture_peer_cert_chain", bool(def).mt()),
                T2("SNI_enabled", bool(def).mt()),
                T2("disable_compression", bool(def).mt()),
                T2("peer_fingerprint", new Mt(list(str(def, "tcp://squid.example.com:8000"), arr(def))))
            )).mt()),
            T2("phar", assoc(def, list(
                T2("compress", inte(def).mt()),
                T2("metadata", mixed(def).mt())
            )).mt()),
            T2("zip", assoc(def, list(
                T2("cafile", str(def, "qwerty123").mt())
            )).mt())
        ));
    }

    private It<DeepType> findBuiltInArgType(Function builtInFunc, int argOrder, ParameterList argList)
    {
        return Tls.cast(Method.class, builtInFunc)
            .uni(meth -> It.cnc(
                findKeysUsedInPdoExec(meth, argList, argOrder),
                findKeysUsedInModelGet(meth, argList, argOrder)
            ), () -> It.cnc(
                opt(builtInFunc.getName())
                    .flt(n -> n.equals("array_merge") || n.equals("array_replace"))
                    .fap(n -> resolveReplaceKeys(argList, argOrder)),
                opt(builtInFunc.getName())
                    .flt(n -> n.equals("stream_context_create"))
                    .flt(n -> argOrder == 0)
                    .map(n -> stream_context_create(builtInFunc)),
                findKeysUsedInArrayMap(builtInFunc, argList, argOrder)
            ));
    }

    // if arg is assoc array - will return type with keys accessed on it
    // if arg is string - will return type of values it can take
    public It<DeepType> findExprTypeFromUsage(PhpExpression arrCtor)
    {
        return opt(arrCtor.getParent())
            .fop(toCast(ParameterList.class))
            .fap(argList -> {
                int order = L(argList.getParameters()).indexOf(arrCtor);
                Opt<PsiElement> callOpt = opt(argList.getParent());
                return resolveFunc(argList)
                    .fap(meth -> It.cnc(
                        getImplementations(meth).fap(ipl -> {
                            IExprCtx nextCtx = callOpt.fop(call -> Opt.fst(
                                () -> Tls.cast(MethodReference.class, call).map(casted -> fakeCtx.subCtxDirect(casted)),
                                () -> Tls.cast(NewExpression.class, call).map(casted -> fakeCtx.subCtxDirect(casted))
                            )).def(fakeCtx.subCtxEmpty());
                            return findArgTypeFromUsage(ipl, order, nextCtx);
                        }),
                        opt(argList.getParent())
                            .fop(toCast(NewExpressionImpl.class))
                            .fap(newEx -> findClsMagicCtorUsedKeys(newEx, order).types),
                        findBuiltInArgType(meth, order, argList)
                    ));
            });
    }

    private It<DeepType> findVarTypeFromUsage(PhpNamedElement caretVar)
    {
        if (depthLeft < 1) {
            return It.non();
        }
        return findVarReferences(caretVar)
            .flt(ref -> ref.getTextOffset() > caretVar.getTextOffset())
            .fop(toCast(Variable.class))
            .fap(refVar -> It.cnc(
                findExprTypeFromUsage(refVar),
                // $this->$magicProp
                opt(refVar.getParent())
                    .cst(FieldReference.class)
                    .flt(fld -> !caretVar.equals(fld.getClassReference()))
                    .fap(fld -> opt(fld.getClassReference()))
                    .fap(fld -> fakeCtx.findExprType(fld))
                    // TODO: add declared field names here too
                    .fap(objt -> objt.props.vls())
                    .fap(prop -> prop.keyType.getTypes.get()),
                // $this->props[$varName]
                opt(refVar.getParent())
                    .cst(ArrayIndex.class)
                    .fap(idx -> opt(idx.getParent()))
                    .cst(ArrayAccessExpression.class)
                    .fap(acc -> opt(acc.getValue()))
                    .cst(PhpExpression.class)
                    .fap(value -> fakeCtx.findExprType(value))
                    .fap(objt -> objt.keys)
                    .fap(prop -> prop.keyType.getTypes.get())
            ));
    }

    private Mt resolveOuterArray(PhpPsiElementImpl val) {
        return opt(val.getParent())
            .fop(par -> {
                Opt<String> key;
                if (par instanceof ArrayHashElement) {
                    key = opt(((ArrayHashElement) par).getKey())
                        .fop(toCast(StringLiteralExpression.class))
                        .map(lit -> lit.getContents());
                    par = par.getParent();
                } else {
                    int order = It(par.getChildren())
                        .fop(toCast(PhpPsiElementImpl.class))
                        .arr().indexOf(par);
                    key = order > -1 ? opt(order + "") : opt(null);
                }
                Opt<String> keyf = key;
                return opt(par)
                    .fop(toCast(ArrayCreationExpression.class))
                    .map(outerArr -> resolve(outerArr))
                    .map(outerMt -> outerMt.getKey(keyf.def(null)));
            })
            .def(Mt.INVALID_PSI);
    }

    public Mt resolve(ArrayCreationExpression arrCtor)
    {
        SearchCtx fakeSearch = new SearchCtx(arrCtor.getProject());
        FuncCtx funcCtx = new FuncCtx(fakeSearch);
        IExprCtx fakeCtx = new ExprCtx(funcCtx, arrCtor, 0);

        return list(
            findExprTypeFromUsage(arrCtor),
            opt(arrCtor.getParent())
                .fop(toCast(BinaryExpression.class))
                .flt(sum -> arrCtor.isEquivalentTo(sum.getRightOperand()))
                .map(sum -> sum.getLeftOperand())
                .fop(toCast(PhpExpression.class))
                .fap(exp -> fakeCtx.findExprType(exp)),
            opt(arrCtor.getParent())
                .fop(toCast(AssignmentExpression.class))
                .flt(ass -> arrCtor.isEquivalentTo(ass.getValue()))
                .map(ass -> ass.getVariable())
                .fop(toCast(Variable.class))
                .fap(var -> It.cnc(
                    new VarRes(fakeCtx).getDocType(var),
                    findVarTypeFromUsage(var)
                )),
            // assoc array in an assoc array
            opt(arrCtor.getParent())
                .fop(toCast(PhpPsiElementImpl.class))
                .fap(val -> resolveOuterArray(val).types)
        ).fap(a -> a).wap(Mt::new);
    }
}
